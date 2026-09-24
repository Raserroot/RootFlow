package com.rootflow.data.run

import android.os.SystemClock
import android.util.Log
import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.domain.event.BootloopDecision
import com.rootflow.domain.event.CrashMarkerStore
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段 4 真机验证用的幂等预置数据（**阶段 6e 起无调用点，保留作为造数手段**）。
 *
 * ## ★ 为什么 6e 删了调用点却留下这个类（**经批准的裁定 P4**）
 * 阶段 4 有 4 项真机未补验（B1 脚本超时 / B3 高频自启 / C1 失败风暴跨进程 /
 * 第 9 项 root 无响应，见 `PROJECT_STATE.md` 的「4 真机验证状态」），
 * 而本机 **toybox 无 `sqlite3`**（`AGENT_PROTOCOL.md §7.3`）且**禁止从 PC 侧写库**
 * （6c 立的硬约束）⇒ 删掉本类会让那 4 项从"一条 `setprop` 造数"退化成"手搓四组脚本"。
 *
 * 代价是 4 个 debug-only 的数据类留在仓库里；收益是阶段 4 的缺口**仍然可补验**。
 *
 * ## 怎么用（6e 之后）
 * ```cmd
 * adb shell setprop debug.rf.diagnostics 1  :: 打开诊断闸门（否则下面三个开关没人读）
 * adb shell setprop debug.rf.presets 1      :: 建 4 个脚本与各自触发器
 * adb shell setprop debug.rf.bootloop 1     :: 把崩溃计数预置到"再启动一次即熔断"
 * adb shell setprop debug.rf.reset_crash 1  :: 清 bootloop 崩溃标记
 * ```
 * 三个预置开关由 `RootFlowApp.verifyStage4Fixtures` 读取，而该入口**只在诊断闸门打开时**
 * 才被调到（`RootFlowApp.DIAGNOSTIC_ENTRIES`）⇒ 平时本类是死代码，这是刻意的（见上）。
 *
 * ## 为什么必须由 App 内做
 * 写 Room 与经 root 通道写脚本正文**只有 App 进程能做**。
 * 本机 toybox 无 `sqlite3`（`AGENT_PROTOCOL.md §7.3`），
 * 因此"从 adb 直接改库"这条路不存在。
 *
 * ## 预置内容（与真机验证批 A/B/C 逐项对应）
 *
 * | 脚本名 | 关键字段 | 触发器 | 服务哪一项 |
 * |---|---|---|---|
 * | `__4_exempt__` | `runOnSafeMode=true` | `screen_on` | A3 豁免放行 |
 * | `__4_fail__` | 正文 `exit 1` | `boot` | B2 连续失败 |
 * | `__4_timeout__` | `timeoutSec=5`，正文永久循环 | `interval` | B1 脚本超时 |
 * | `__4_freq__` | 正文成功退出 | `power_connected` | B3 高频自启 |
 *
 * ## 每个事件类型只归一个脚本（这是有意的）
 * 同事件挂多脚本会让"哪条脚本被启动了"不可判读，也会让 B2 的失败计数与 B3 的
 * 高频计数互相污染。因此事件分配是一对一的：
 * `screen_on` 归豁免、`boot` 归失败、`interval` 归超时、`power_connected` 归高频。
 *
 * ## 跑之前先看：各项之间会互相影响
 * B2 的 3 次重启会顺带启动 B1 的 `interval` 脚本（1 分钟后），而它超时即熔断。
 * 因此 **B1 与 B2 各自跑完后都应先清掉安全模式**再跑下一项：熔断一旦发生，
 * 所有事件都不再分发（需求 §5.4），后一项的 burst 会永远不发，表现为"验不出来"。
 *
 * ## 幂等粒度：逐项
 * "脚本在、触发器缺"必须能补建。只按脚本名整体跳过会让半建状态永久化
 * ——3d 真机就是这样撞上 `triggers=1` 的。
 *
 * ## 不覆盖已有脚本的字段
 * 脚本已存在时只补触发器，不重写 `timeoutSec` / `runOnSafeMode`：那属用户配置，
 * 越权重写是本仓库禁止的。因此预置内容一旦建错，恢复办法是 `pm clear` 重来。
 */
@Singleton
class Stage4VerificationFixtures
    @Inject
    constructor(
        private val scriptDao: ScriptDao,
        private val scriptRepository: ScriptRepository,
        private val triggerRepository: TriggerRepository,
        private val crashMarkerStore: CrashMarkerStore,
    ) {
        /**
         * 确保四个脚本与各自触发器存在（幂等，逐项补齐）。
         *
         * @return `true` 表示本次真的写入了内容
         */
        suspend fun ensureFixtures(): Boolean {
            var acted = false
            acted = ensureSpec(EXEMPT_SPEC) || acted
            acted = ensureSpec(FAIL_SPEC) || acted
            acted = ensureSpec(TIMEOUT_SPEC) || acted
            acted = ensureSpec(FREQ_SPEC) || acted

            if (!acted) {
                Log.i(TAG, "STAGE4_FIXTURE skipped: all scripts and triggers already present")
            }
            return acted
        }

        /**
         * 把 bootloop 崩溃计数预置到"再启动一次即熔断"（C2 的前提）。
         *
         * ## 为什么不让用户用 root 手写 SharedPreferences
         * `CrashMarkerStore` 的实现是应用私有的 `SharedPreferences`。用 root 或
         * `run-as` 手写 XML 有三个坑：文件属主会变成 root 导致 App 之后读不到；
         * 同目录的缓存会覆盖手写内容；键名与类型写错是静默的，读出来只是默认值。
         *
         * 因此改由 App 自己写 —— 走的就是生产用的那个实现，不存在上述任何一个坑。
         *
         * ## 语义
         * 置"上次启动未健康"且计数为阈值减一。于是下一次启动的
         * `BootloopGuard.evaluateStartup` 会增到阈值并熔断，与"真的崩了 3 次"
         * 走的是同一条判定路径，而不是伪造熔断结果。
         *
         * @return 写入后的计数
         */
        fun presetBootloopForNextStart(): Int {
            val bootId = SystemClock.elapsedRealtime()
            crashMarkerStore.markStartupInProgress(bootId)
            crashMarkerStore.writeCrashCount(BOOTLOOP_PRESET_COUNT)
            Log.i(
                TAG,
                "STAGE4_FIXTURE bootloop preset bootId=" + bootId +
                    " crashCount=" + BOOTLOOP_PRESET_COUNT + " healthy=false" +
                    " nextStartupReaches=" + (BOOTLOOP_PRESET_COUNT + 1),
            )
            return BOOTLOOP_PRESET_COUNT
        }

        // ------------------------------------------------------------------ 内部

        /**
         * 清空 bootloop 崩溃标记（`debug.rf.reset_crash` 的落点）。
         *
         * ## 为什么必须有它（C2 真机暴露的残留）
         * C2 预置的 `crashCount=2` + `healthy=false` **留在 SharedPreferences 里**：
         * 即使把 `debug.rf.bootloop` 置 0，**下一次启动仍会 +1 到 3 并再次熔断** ——
         * 表现为"设备莫名其妙又进安全模式"，而日志里只有一行 `BOOTLOOP_TRIPPED`，
         * 极易被误判为"兜底坏了"。
         *
         * 清掉之后计数从 0 开始，与"新装 App 的首次启动"一致（`BootloopDecision` 的
         * `Unchanged` 分支：没有标记就不计崩溃）。
         *
         * ## 为什么不复用 `CircuitBreakerImpl.restore`
         * `restore` 只 `bootloopGuard?.reset()`，而 `BootloopGuard` 是**进程内对象**：
         * 它重置的是内存态计数，`CrashMarkerStore` 里的持久化标记**由 `reset()` 内的
         * `store.clear()` 一并清掉** —— 但那条路径要求"当前处于安全模式"才走到。
         * 本入口**无条件**清，因此要在"不在安全模式"时也能用。
         */
        fun resetCrashMarker() {
            crashMarkerStore.clear()
            Log.i(TAG, "STAGE4_FIXTURE crash marker cleared crashCount=0 healthy=<none>")
        }

        /** 一个预置脚本的完整描述：脚本加上它唯一的那条触发器。 */
        private data class Spec(
            val name: String,
            val body: String,
            val eventType: String,
            val runOnSafeMode: Boolean = false,
            val timeoutSec: Int = 0,
            val intervalMinutes: Int? = null,
        )

        private suspend fun ensureSpec(spec: Spec): Boolean {
            val scriptId = ensureScript(spec) ?: return false
            val existing = existingTriggerEvents(scriptId)
            if (spec.eventType in existing) {
                Log.i(TAG, "STAGE4_FIXTURE " + spec.name + ": script id=" + scriptId + " trigger present")
                return false
            }
            return ensureTrigger(spec, scriptId)
        }

        private suspend fun ensureScript(spec: Spec): Long? {
            scriptDao.findByName(spec.name)?.let { existing ->
                Log.i(TAG, "STAGE4_FIXTURE " + spec.name + ": script present id=" + existing.id)
                return existing.id
            }

            val candidate =
                Script(
                    id = 0L,
                    name = spec.name,
                    language = "shell",
                    enabled = true,
                    timeoutSec = spec.timeoutSec,
                    autoDisableOnFail = false,
                    runOnSafeMode = spec.runOnSafeMode,
                    content = spec.body,
                    contentSha256 = null,
                    createdAt = 0L,
                    updatedAt = 0L,
                )

            return when (val saved = scriptRepository.save(candidate)) {
                is WriteResult.Ok -> {
                    Log.i(
                        TAG,
                        "STAGE4_FIXTURE " + spec.name + ": script created id=" + saved.value.id +
                            " timeoutSec=" + spec.timeoutSec +
                            " runOnSafeMode=" + spec.runOnSafeMode +
                            " path=scripts/" + saved.value.id + "/main.sh",
                    )
                    saved.value.id
                }

                is WriteResult.Failed -> {
                    Log.w(TAG, "STAGE4_FIXTURE " + spec.name + ": script save failed: " + saved.reason)
                    null
                }
            }
        }

        private suspend fun ensureTrigger(
            spec: Spec,
            scriptId: Long,
        ): Boolean {
            val candidate =
                Trigger(
                    id = 0L,
                    scriptId = scriptId,
                    eventType = spec.eventType,
                    params = TriggerParams(intervalMinutes = spec.intervalMinutes),
                    createdAt = 0L,
                )

            // 总开关重构后订阅是**集合替换**（存在即订阅），没有单条 save。
            // 夹具的语义是"确保这个脚本订阅了这个事件"，因此读现有的再合并写回。
            val existing = triggerRepository.all().filter { it.scriptId == scriptId }
            if (existing.any { it.eventType == spec.eventType }) {
                Log.i(TAG, "STAGE4_FIXTURE " + spec.name + ": trigger already present event=" + spec.eventType)
                return true
            }

            return when (
                val saved =
                    triggerRepository.replaceForScript(
                        scriptId = scriptId,
                        triggers = existing + candidate,
                    )
            ) {
                is WriteResult.Ok -> {
                    Log.i(
                        TAG,
                        "STAGE4_FIXTURE " + spec.name + ": trigger created event=" + spec.eventType +
                            " subscriptions=" + saved.value.size,
                    )
                    true
                }

                is WriteResult.Failed -> {
                    Log.w(TAG, "STAGE4_FIXTURE " + spec.name + ": trigger save failed: " + saved.reason)
                    false
                }
            }
        }

        /**
         * 取某脚本已有的触发器事件键。
         *
         * 走既有的 domain 端口（`observeForScript` + `first()`），**不碰持久化细节**
         * —— 6e 之前这里写的是"照抄 `RunVerificationFixtures` 的读法"，那个类已随
         * 验证入口清理删除（见 `PROJECT_STATE.md` 的「6e 收尾记录 · 清理裁定表」）。
         */
        private suspend fun existingTriggerEvents(scriptId: Long): Set<String> =
            try {
                triggerRepository
                    .observeForScript(scriptId)
                    .first()
                    .map { it.eventType }
                    .toSet()
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "STAGE4_FIXTURE trigger lookup failed for script " + scriptId + ": " +
                        (error.message ?: error::class.java.name) + " will recreate anyway",
                )
                emptySet()
            }

        companion object {
            const val TAG: String = "RootFlow"

            /**
             * shell 变量引用前缀。
             *
             * 单独抽成常量是**有意的**：正文里的 `$` 必须在 Kotlin 源码里转义，
             * 而转义写错的后果是静默的（Kotlin 会尝试解析成模板表达式，
             * 报一个与真实意图无关的未定义符号）。用拼接表达可以一眼看出意图。
             */
            private const val D: String = "$"

            // 以下四个规格全部 `private`：`Spec` 是类内私有类型，暴露成 public 属性会让
            // "public 属性暴露 private 类型"成为**编译错误**（Kotlin 的可见性检查，
            // 不是风格问题）。它们只在 ensureFixtures 内部使用，本就不该外泄。

            /**
             * A3 豁免脚本名。
             *
             * 暴露为常量是必需的：A3 的验证入口要按名字找到它（`RootFlowApp` 拿不到
             * `Spec`，那是类内私有类型）。**只暴露名字，不暴露 Spec**。
             */
            const val EXEMPT_SCRIPT_NAME: String = "__4_exempt__"

            /** A3：安全模式下仍执行。触发用 `screen_on`，靠物理按电源键。 */
            private val EXEMPT_SPEC: Spec =
                Spec(
                    name = EXEMPT_SCRIPT_NAME,
                    body = "echo STAGE4_EXEMPT_RAN safe=" + D + "ROOTFLOW_SAFEMODE event=" + D + "ROOTFLOW_EVENT",
                    eventType = SystemEvent.SCREEN_ON,
                    runOnSafeMode = true,
                )

            /** B2：连续失败。触发用 `boot`，靠重启驱动，每次开机一次。 */
            private val FAIL_SPEC: Spec =
                Spec(
                    name = "__4_fail__",
                    body = "echo STAGE4_FAIL_RAN; exit 1",
                    eventType = SystemEvent.BOOT,
                )

            /** B1：脚本超时。`timeoutSec=5` 加永久循环，5 秒后必超时并熔断。 */
            private val TIMEOUT_SPEC: Spec =
                Spec(
                    name = "__4_timeout__",
                    body = "echo STAGE4_TIMEOUT_RAN; while true; do sleep 1; done",
                    eventType = SystemEvent.INTERVAL,
                    timeoutSec = 5,
                    intervalMinutes = 1,
                )

            /** B3：高频自启。正文成功退出，只让受理次数累积，不污染连续失败计数。 */
            private val FREQ_SPEC: Spec =
                Spec(
                    name = "__4_freq__",
                    body = "echo STAGE4_FREQ_RAN",
                    eventType = SystemEvent.POWER_CONNECTED,
                )

            /**
             * C2 预置的崩溃计数。
             *
             * 取阈值减一：下一次启动加一后正好达到阈值，与真的崩了同样次数
             * 走同一条判定路径。
             */
            const val BOOTLOOP_PRESET_COUNT: Int = BootloopDecision.DEFAULT_CRASH_THRESHOLD - 1
        }
    }
