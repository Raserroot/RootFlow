package com.rootflow.data.event

import android.os.SystemClock
import android.util.Log
import com.rootflow.domain.event.BootloopDecision
import com.rootflow.domain.event.CrashMarkerStore
import com.rootflow.domain.event.TripReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bootloop 兜底（阶段 4，需求 §5.3 第 4 条）。
 *
 * ## 与需求措辞的偏离（**见 `BootloopDecision` 的完整说明**）
 * 需求说"连续 3 次 **60s 内崩溃**"，那要求能观测到"进程正常退出"——
 * **Android 上不可得**（`Application.onTerminate()` 真机永不调用；
 * 进程被 kill / 回收 / 划掉时无任何回调）。
 *
 * 本实现改用**反向标记**（**已批准**的修正 ②）：
 * - 启动时置 `healthy = false`（= "本次启动进行中"）
 * - 活过 [healthThresholdMillis]（默认 60s）后置 `healthy = true`（= "这次是好的"）
 * - 下次启动读标记：`healthy == false` 且同周期 ⇒ 上次**没活到阈值** ⇒ 计一次崩溃
 *
 * ⇒ **真实 bootloop（启动即崩）必然被计入；正常开关 App 只要活过阈值就清零**。
 *
 * ## `onDestroy` / `onTerminate` 为什么一个都不用
 * - `onTerminate()`：真机永不调用（仅模拟器环境）
 * - `Activity.onDestroy()`：系统回收 / 用户划掉时**也不保证调用**
 * 因此本类**不在退出时写任何东西**——判定完全建立在"下次启动时回看标记"上，
 * 不依赖任何退出回调（这正是它能在真机工作的原因）。
 *
 * ## 健康阈值的选取
 * 需求写 60s，但 60s 对单测是 60 次虚拟时间推进（无所谓）而对真机是"必须开着 App 一分钟"。
 * 阈值本身**是注入参数**，生产用 [DEFAULT_HEALTH_THRESHOLD_MILLIS] = 60s（贴合需求原文）。
 *
 * ## ★ 恢复需要人工（**必须让用户知道**）
 * 一旦因 bootloop 进入安全模式，**App 不会自己恢复**（那正是兜底的本意：
 * 反复崩溃的自动化必须先停下来）。恢复途径：
 * 1. App 内「退出安全模式」（阶段 6 的 UI；阶段 4 有临时验证入口）
 * 2. root 侧删除 flag：`su -c rm -f /data/local/tmp/rootflow/safemode.flag`
 *
 * ⇒ **用户看到的现象会是"App 打开后什么都不跑"**。这一点已同时写进
 * `PROJECT_STATE.md`，避免被误判为"App 坏了"。
 *
 * ## ★ 已知限制：快速连续启动 3 次可能误判为崩溃（**用户可见，勿当成 bug 修**）
 * 本方案只能观测"上次有没有活到 [DEFAULT_HEALTH_THRESHOLD_MILLIS]"，
 * **无法区分"崩溃"与"快速正常退出"**——用户在 60s 内连续正常开关 App 三次，
 * 第三次会被判为 bootloop 并进入安全模式。这是**平台限制**（`onTerminate` 真机不调用、
 * `onDestroy` 不保证调用 ⇒ 根本没有"正常退出"事件可用），**阈值 3 是权衡结果**。
 * 完整论证、代价分析三条缓解见 [BootloopDecision] 的 KDoc。
 *
 * ## 构造函数形态（**勿改成带默认值的 `@Inject` 构造**）
 * Kotlin 默认参数对 Dagger 不可见 ⇒ Hilt 会要求绑定 `Function0<Long>` / `Long`
 * 而报 `MissingBinding`（3c.1/3d 反复踩过的同一坑型）。
 * 因此 `@Inject` 构造**只含可注入依赖**，`elapsedRealtime` / `healthThresholdMillis`
 * 走 `internal` 次构造函数（默认值 `null` 在类体内回落，避免"改了默认值却忘了同步"）。
 *
 * ## ★ 为什么 `onTrip` 是**可空**且默认 `null`（阶段 4 收尾修正）
 * 早先的形态是"构造必须传一个 `suspend (TripReason) -> Unit`"，并打算在 DI 里把它接到
 * `CircuitBreakerImpl.tripForBootloop`。那会形成
 * `CircuitBreakerImpl → BootloopGuard → CircuitBreakerImpl` 的**循环依赖**，Hilt 直接拒。
 *
 * 改为"`onTrip` 可选"之后，职责也更干净：
 * - **判定**（本类）：`evaluateStartup()` 返回"本次启动是否已触发熔断"
 * - **动作**（接线方）：`RootFlowApp` 拿到 `true` 后调 `circuitBreaker.tripForBootloop(crashes)`
 *
 * 于是本类**不依赖**任何熔断端口（依赖方向单向），`onTrip` 只作为
 * "不关心动作的调用方"与单测的观测钩子保留。
 */
@Singleton
class BootloopGuard
    @Inject
    constructor(
        private val store: CrashMarkerStore,
        private val scope: CoroutineScope,
        private val onTrip: (suspend (TripReason) -> Unit)?,
    ) {
        /** 测试缝：注入固定时钟与阈值（生产用 60s / `SystemClock.elapsedRealtime`）。 */
        internal constructor(
            store: CrashMarkerStore,
            onTrip: suspend (TripReason) -> Unit,
            scope: CoroutineScope,
            elapsedRealtime: (() -> Long)?,
            healthThresholdMillis: Long?,
        ) : this(store = store, scope = scope, onTrip = onTrip) {
            elapsedRealtime?.let { this.elapsedRealtime = it }
            healthThresholdMillis?.let { this.healthThresholdMillis = it }
        }

        /**
         * 测试缝：只注入存储与作用域（生产 DI 用的形态）。
         *
         * 存在的理由：`onTrip` 是**可空**的（见类 KDoc），而主构造把它放在第三位。
         * 单测若只想断言"计数与健康标记"，用本构造可以完全不提 `onTrip`。
         */
        internal constructor(
            store: CrashMarkerStore,
            scope: CoroutineScope,
        ) : this(store = store, scope = scope, onTrip = null)

        private var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

        private var healthThresholdMillis: Long = DEFAULT_HEALTH_THRESHOLD_MILLIS

        private val lock = Any()

        private var healthJob: Job? = null

        /** 本次启动的累计崩溃数（真机判读 + 单测断言用）。 */
        var crashCount: Int = 0
            private set

        /** 本次启动是否已判定需要熔断（幂等：重复 [evaluateStartup] 不重复熔断）。 */
        var tripped: Boolean = false
            private set

        /**
         * 启动时调用一次：处置崩溃计数，必要时触发熔断。
         *
         * 顺序要求：必须在 `CircuitBreaker` 能接收 [onTrip] **之后**调用
         * （`RootFlowApp.onCreate` 里紧随 breaker 构造）。
         *
         * @return `true` 表示本次启动已触发 bootloop 熔断
         */
        suspend fun evaluateStartup(): Boolean {
            val bootId = elapsedRealtime()
            val previous = store.readStartupMarker()

            // 处置旧计数 → 立即把"本次启动进行中"写下去（**先写**，
            // 这样即使随后就崩，下次启动也能正确判定）
            val verdict = BootloopDecision.evaluate(elapsedRealtimeMillis = bootId, marker = previous)
            val updated =
                when (verdict) {
                    BootloopDecision.Verdict.Unchanged -> store.readCrashCount()
                    BootloopDecision.Verdict.IncrementCrashCount -> store.readCrashCount() + 1
                    BootloopDecision.Verdict.ResetForNewBoot -> 0
                }
            store.writeCrashCount(updated)
            store.markStartupInProgress(bootId)
            crashCount = updated

            Log.i(
                TAG,
                "BOOTLOOP_EVALUATE bootId=$bootId previous=${previous?.let {
                    "bootId=${it.bootId} healthy=${it.healthy}"
                } ?: "<none>"} " +
                    "verdict=$verdict crashCount=$updated threshold=${BootloopDecision.DEFAULT_CRASH_THRESHOLD}",
            )

            if (!BootloopDecision.shouldTrip(updated)) {
                scheduleHealthMark()
                return false
            }

            val reason = TripReason.Bootloop(crashes = updated)
            synchronized(lock) {
                if (tripped) return true
                tripped = true
            }
            Log.w(TAG, "BOOTLOOP_TRIPPED crashes=$updated reason=${reason.reasonKey}")
            // `onTrip` 可空（见类 KDoc）：不关心动作的调用方与单测不传它。
            // 无论有没有钩子，本方法都返回 `true` —— 调用方据此驱动熔断动作，
            // 因此"钩子缺失"不会让熔断被静默跳过。
            onTrip?.invoke(reason)

            // ★ 阶段 5 修复：**熔断之后立刻清零计数**（真机暴露的缺陷，见下方 KDoc）。
            clearCrashCountAfterTrip()
            return true
        }

        /**
         * 熔断后清零崩溃计数（**阶段 5 真机暴露的缺陷修复**）。
         *
         * ## 缺陷现场（批 2 第 4 项）
         * ```
         * 20:31:20 crashCount=2
         * 20:31:29 crashCount=3 → SAFEMODE_TRIPPED reason=bootloop
         * 20:31:37 crashCount=4   ← 下一次启动，本该是 0
         * 20:32:03 SAFEMODE_TRIPPED reason=bootloop detected 5 short-lived startups
         * ```
         *
         * ## 根因
         * 本方法上方的"先写再判"会把 `3` 落到磁盘（那是**正确**的：崩溃计数必须能跨进程累加）。
         * 但熔断路径上**从来没有清过它**，于是那个 `3` 永久留在 `SharedPreferences`：
         * 下一次启动算出 `4` ⇒ `shouldTrip` 立即成立 ⇒ **每次启动都熔断一次**。
         * `tripped`（内存标志）只在本进程内幂等，**跨进程毫无作用**——这正是"看起来像兜底坏了"
         * 的表象。用户报告的"清场 `debug.rf.reset_crash` 之后仍 `bootloopTripped=true` 又熔断"
         * 与它是**同一根因**：那个 `true` 不是某个"未清的标志位"，而是"计数从未归零"导致的
         * **每次启动都重新判定为真**。
         *
         * ## 为什么清零是安全的（不会放过真实 bootloop）
         * - 真实的"启动即崩"循环里计数**永远到不了 3**：每崩 3 次就被熔断拦下并清零，
         *   下一次循环重新从 0 累加 ⇒ 兜底照常生效
         * - 清零**不打断**当前开机周期的累计：`startup_boot_id` / `startup_healthy` 保持原样，
         *   刚熔断就崩的那一次仍会被"下一次启动"计入
         * - 熔断后 App 已进安全模式，事件被入口整体丢弃（需求 §5.4）⇒ 正常路径下不会再自主启动
         *
         * ## 与 [reset] 的分工（勿混）
         * | 方法 | 何时用 | 动什么 |
         * |---|---|---|
         * | 本方法（私有） | **熔断后**，由 [evaluateStartup] 自己调用 | **只**清计数（保留启动标记） |
         * | [reset] | **恢复安全模式**时，由 `CircuitBreakerImpl.restore` 调用 | 清计数 + 清启动标记 + 取消健康作业 |
         *
         * 代价（有意接受）：熔断会在计数上"少算一次"（3 → 0 而非 3 → 3 之后 +1），
         * 表现为"疑似启动崩溃的日志里可能出现 1..3 的重复段"。
         * 这是可接受的：日志重复远好于**永久熔断**。
         */
        private fun clearCrashCountAfterTrip() {
            synchronized(lock) {
                healthJob?.cancel()
                healthJob = null
                crashCount = 0
            }
            store.resetCrashCount()
            Log.w(
                TAG,
                "BOOTLOOP_COUNT_CLEARED reason=trip nextStartupCountsFrom=0 " +
                    "(count was ${BootloopDecision.DEFAULT_CRASH_THRESHOLD})",
            )
        }

        /**
         * 活过 [healthThresholdMillis] 后置"健康"标记。
         *
         * **不前台阻塞**：跑在一个 `delay` 作业里，由 `RootFlowApp` 的作用域承载。
         * 单测用虚拟时间推进（`advanceTimeBy(threshold)`）。
         */
        private fun scheduleHealthMark() {
            val bootId = elapsedRealtime()
            synchronized(lock) {
                healthJob?.cancel()
                healthJob =
                    scope.launch {
                        delay(healthThresholdMillis)
                        store.markHealthy(bootId)
                        Log.i(TAG, "BOOTLOOP_HEALTHY bootId=$bootId afterMs=$healthThresholdMillis")
                    }
            }
        }

        /** 重置（恢复安全模式时调用：清计数与标记，让下一次启动从零开始）。 */
        fun reset() {
            synchronized(lock) {
                healthJob?.cancel()
                healthJob = null
                tripped = false
                crashCount = 0
            }
            store.clear()
        }

        companion object {
            const val TAG: String = "RootFlow"

            /** 需求 §5.3 的"60s 内"：活过这么久即视为"这次启动是好的"。 */
            const val DEFAULT_HEALTH_THRESHOLD_MILLIS: Long = 60_000L
        }
    }
