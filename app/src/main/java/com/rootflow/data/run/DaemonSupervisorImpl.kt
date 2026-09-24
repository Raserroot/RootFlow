package com.rootflow.data.run

import android.os.SystemClock
import android.util.Log
import com.rootflow.data.di.DaemonDispatcher
import com.rootflow.domain.event.DaemonRestartPolicy
import com.rootflow.domain.event.DaemonSupervisor
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.ScriptRunner
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.repository.ScriptRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「一直运行」常驻脚本的监管实现（阶段 10）。
 *
 * ## 生命周期（与事件源同一个所有者）
 * ```
 * ForegroundServiceController.register()        → start()
 * ForegroundServiceController.unregister()      → stop()
 * ForegroundServiceController.onSafeModeAlert() → stop()    ← 用户裁定：常驻也受安全模式约束
 * ```
 * 放在前台服务而不是 `Application` 域：需求 §2.1 的"须驻留前台服务"对常驻脚本**更加**成立
 * —— 一个"一直在跑"的脚本若挂在进程域，服务被系统回收后就没人看着它了。
 *
 * ## 监管循环（每个脚本一条协程）
 * ```
 * while (isActive) {
 *   受理 → 等它结束（轮询 RunSessionRegistry）→ 量存活时长 → 退避 → 再来一轮
 * }
 * ```
 *
 * ### 为什么"等它结束"用轮询而不是回调
 * `ScriptRunner.start` 的契约是**受理即返回**（脚本执行是冷流），而它内部的
 * `RunSession` 不外泄。要拿到"这次跑完了"的信号只有三条路：
 * 1. 改 `ScriptRunner` 的返回值把 `RunSession` 暴露出来（接口改动 + 影响既有测试替身）
 * 2. 让 runner 接受完成回调（同样要动接口）
 * 3. **轮询 `RunSessionRegistry.activeRuns()`**（本实现所选）
 *
 * 选 3：`activeRuns()` 是**既有公开 API**（熔断第 2 步就在用它），语义恰好是
 * "此刻在跑的 `(runId, scriptId)`"。轮询间隔 [POLL_INTERVAL_MILLIS] 对监管一个常驻进程
 * 完全够用，且**不会漏**：脚本真正结束的判据是"该 `scriptId` 不在 `activeRuns()` 里"，
 * 而那由一个原子快照给出。
 *
 * 代价（如实登记）：结束与察觉之间最多 [POLL_INTERVAL_MILLIS] 的延迟，
 * 以及每 [POLL_INTERVAL_MILLIS] 一次集合快照 —— 相对一个常驻进程的开销可忽略。
 *
 * ## 三条裁定的落地位置（用户 2026-09-20，勿改）
 * - **超时**：[DAEMON_TIMEOUT_MILLIS] 覆盖为"实践上无限"。**不能**依赖 `timeoutSec = 0`
 *   —— 那条路在 `TriggeredScriptRunner.effectiveTimeoutMillis` 里被解释为**回落 60 秒**。
 * - **并发名额**：**占用**全局名额（不去动 `RunAdmissionGate`）。常驻脚本走同一条
 *   `start` 路径，因此天然占名额；闸门满时 `start` 返回 `false`，这里按退避节奏**重试**
 *   而非放弃 —— 名额满是**暂时**状态，与"脚本崩了"是两回事。
 * - **崩溃**：**不触发全局熔断**。本类只维护自己那份"连续快速崩"计数，
 *   超过 [DaemonRestartPolicy.maxRapidCrashes] 就**只停掉它自己**并留痕。
 *   理由是刻意的：既有熔断规则是"同一脚本连续失败 3 次 ⇒ 进安全模式、停全部触发器"，
 *   而常驻脚本"退出即重启"会反复失败，照搬会让一个写错的常驻脚本冻结整个 App。
 *
 * ## ★ `scriptRunner` 必须是 `Lazy`（**断环点，勿改成直接注入**）
 * 依赖链是：
 * ```
 * ForegroundServiceController → DaemonSupervisor → ScriptRunner
 *   → CircuitBreaker（RunModule.provideTriggeredScriptRunner 的构造参数）
 *   → SafeModeAlertSink → ForegroundServiceController      ← 环
 * ```
 * 既有的断环点是 `ForegroundServiceController` 里的 `Lazy<CircuitBreaker>`，
 * 但本类若**直接**注入 `ScriptRunner`，Dagger 就要在构造监督器时**立刻**解析
 * `ScriptRunner`，从而立刻构造 `CircuitBreaker` —— 那正好绕过那个断环点，
 * 于是报 `[Dagger/DependencyCycle]`（只在 `hiltJavaCompileDebug` 才暴露，
 * **Kotlin 编译看不出来**）。改成 `Lazy` 后，`ScriptRunner` 在本类
 * **第一次真正要跑脚本时**才被解析，环被打断。
 *
 * @param scriptRunner 投递端口，**延迟**引用（断环点，见上）
 * @param dispatcher 监管协程的调度器。生产为 `Dispatchers.IO`；
 *   **单测必须注入测试调度器**，否则虚拟时间推不动监管循环（`advanceUntilIdle` 对
 *   真实的 `Dispatchers.IO` 无效 —— 第一版没注入，6 个用例全红）。
 *   注入的是**调度器**而不是作用域：监管的起停随前台服务，需要一个可取消的自建作用域。
 * @param elapsedRealtimeMillis **单调**时钟（默认 `SystemClock.elapsedRealtime`）。
 *   ## 为什么不能直接 `System.currentTimeMillis()`
 *   1. **墙钟可被回拨**（用户改时间 / NTP 校时）⇒ 时长会算成负数或跳变，
 *      而"脚本活了多久"必须单调。项目里 `BootloopGuard` 出于同一理由用 `elapsedRealtime`。
 *   2. **虚拟时间下不可测**：单测用 `StandardTestDispatcher` 推进虚拟时间，而墙钟几乎不动
 *      ⇒ 存活时长恒为 ~0ms ⇒ 每个稳住的脚本都被误判成"快速崩"。
 *      阶段 10 实测到这个假失败，因此把它做成可注入的函数缝（与 `BootloopGuard` 同款）。
 */
@Singleton
class DaemonSupervisorImpl
    @Inject
    constructor(
        private val scriptRepository: ScriptRepository,
        private val scriptRunner: dagger.Lazy<ScriptRunner>,
        private val sessionRegistry: RunSessionRegistry,
        private val policy: DaemonRestartPolicy,
        private val masterSwitch: MasterSwitch,
        @DaemonDispatcher private val dispatcher: CoroutineDispatcher,
    ) : DaemonSupervisor {
        /**
         * 单调时钟；测试缝：构造后由单测替换为虚拟时钟。
         *
         * 用 `var` 而不是构造参数注入：本类是 `@Inject` 构造，而 `() -> Long` 这种
         * 函数类型无法被 Dagger 绑定（没有可解析的 `@Provides`）。`BootloopGuard`
         * 对同一问题用的是"第二个可选构造参数作测试缝"，这里沿用它的**效果**，
         * 但用属性赋值的形式，免得为它多写一个 `@Provides`。
         */
        internal var elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime
        private val lock = Any()

        /** 每个常驻脚本一条监管协程（`scriptId` → job）。 */
        private val jobs = mutableMapOf<Long, Job>()

        /** 已放弃重启的脚本 → 原因（**如实留痕**，见端口的 KDoc）。 */
        private val givenUp = mutableMapOf<Long, String>()

        /**
         * 监管协程的作用域；`null` = 未启动。
         *
         * 用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（与
         * `RootFlowApp` 的 `startupScope` / `diagnosticsScope` 同款）：
         * 一个脚本的监管协程抛错不该连带取消其它脚本的监管。
         */
        private var scope: CoroutineScope? = null

        override val supervisedCount: Int
            get() = synchronized(lock) { jobs.size }

        /** 被监管的脚本 id 快照（P4 的「为什么没跑」读它定位到具体脚本）。 */
        override fun supervisedIds(): Set<Long> = synchronized(lock) { jobs.keys.toSet() }

        override fun givenUpReasons(): Map<Long, String> = synchronized(lock) { givenUp.toMap() }

        override fun start() {
            synchronized(lock) {
                if (scope != null) {
                    Log.i(TAG, "DAEMON_SUPERVISOR_START skipped reason=already started")
                    return
                }
                scope = CoroutineScope(SupervisorJob() + dispatcher)
                givenUp.clear()
            }
            val activeScope = synchronized(lock) { scope } ?: return

            // ★ 订阅触发器变化，做**增删对账**（阶段 10 的体验缺口修复）。
            //
            //   缺口是什么：第一版只在 `start()` 时查一次 `enabledForEvent(always_run)`，
            //   于是"新建一个「一直运行」触发器"要等**下次前台服务启动**才生效 ——
            //   用户勾完没有任何反应。
            //
            //   为什么用持续订阅，而不是"在保存触发器的地方调一次 refresh"：
            //   触发器有**多条**写入路径（编辑器保存、熔断批量禁用/还原、残留清理），
            //   逐个加调用点必然漏掉；而 Room 的 `observeAll` 是它们的共同下游，
            //   订阅它一次就覆盖全部路径，也不给将来新增的路径留坑。
            //
            //   首次发射即"当前全量"，因此这一处同时替代了原来的那次一次性查询。
            //
            //   为什么必须 `launch`：`observeAll()` 是要订阅的 Flow，而本方法是从
            //   `ForegroundServiceController.register()` 的**同步**路径调用的。
            //
            //   ★ P3（总开关重构）：多一条输入 —— **总闸**。
            //   不变量 3（关闭 ⇒ 终止已在跑的）与不变量 4（打开 ⇒ 立即启动）都由此
            //   **天然满足**，不需要额外的命令式调用：总闸只是 `reconcile` 的又一个输入流，
            //   变化即对账。这也是"判据集中在**一处**"的延续 ——
            //   若改成"在拨总闸的地方再调一次 supervisor.stop()/start()"，
            //   就会多出第二条能改变监管集合的路径，而两条路径迟早漂移。
            activeScope.launch {
                combine(
                    scriptRepository.observeAll(),
                    masterSwitch.enabled,
                ) { scripts, masterEnabled -> scripts to masterEnabled }
                    .catch { error ->
                        // 订阅断掉不该让服务崩（与 register 路径逐项 runCatching 同一条理由）。
                        // 留痕，不静默。
                        Log.w(TAG, "DAEMON_SUPERVISOR_OBSERVE_FAILED: ${describe(error)}")
                    }.collect { (scripts, masterEnabled) ->
                        reconcile(scope = activeScope, scripts = scripts, masterEnabled = masterEnabled)
                    }
            }
        }

        override fun stop() {
            val activeScope =
                synchronized(lock) {
                    val current = scope
                    scope = null
                    jobs.clear()
                    current
                } ?: return
            // 取消监管协程 ⇒ 脚本进程随之被终止（运行作用域被取消时走 kill 路径，
            // 与"熔断第 2 步"同一机制）。
            activeScope.cancel()
            Log.i(TAG, "DAEMON_SUPERVISOR_STOPPED reason=service stopped")
        }

        // ------------------------------------------------------------------ 内部

        /**
         * 把"当前被监管的脚本集合"对齐到"**总闸 + 脚本行**说的集合"。
         *
         * ## 语义（用户可见的后果）
         * - **新增**（新建一个 `resident` 脚本 / 拨开它的开关）⇒ 立刻开始监管并启动
         * - **移除 / 被禁用** ⇒ 停止监管，脚本进程随之被终止
         * - **总闸关闭** ⇒ 监管集合**清空**，全部常驻脚本被终止（不变量 3）
         * - **总闸打开** ⇒ 按 `enabled && resident` 重新填满（不变量 4），**不必等下次服务启动**
         * - **不变** ⇒ 一个动作都不做（否则每次任何脚本变化都会重启全部常驻脚本）
         *
         * ## 为什么"移除"要连带终止进程
         * 用户的意图是"它不该再一直跑了"。只停止重启会让一个已经跑起来的进程永远留着，
         * 那是更难被发现的状态（"我明明关了它"）—— 总闸关闭时同理，且这正是
         * 方案 §4 明写的**有意差异**（与 systemd 的"suspend 不作用于已开始"相反）：
         * 用户按总开关的预期就是"全停"。
         *
         * ## 为什么用"差集"而不是"全部重建"
         * 重建会让**每一个**触发器变化（哪怕只是改了一个无关脚本的定时）都重启所有常驻脚本
         * —— 对"一直运行"的进程是明显可感的打扰。
         *
         * @param masterEnabled 总闸的当前值（来自 [MasterSwitch] 的输入流，**不是**快照读）
         */
        private fun reconcile(
            scope: CoroutineScope,
            scripts: List<Script>,
            masterEnabled: Boolean,
        ) {
            // 判据：**总闸** × **脚本自己的两个字段**，不查订阅表。
            // 阶段 10 的旧实现读「勾了 always_run 的订阅」，那是把"运行形态"寄存在触发器上；
            // 总开关重构后它升格为 `scripts.resident`，因此这里直接读脚本行即可。
            //
            // ★ 总闸关闭时 `desired` **短路成空集**（而不是在循环里逐条判）：
            //   于是 toStop 自然覆盖全部在监管的脚本，复用同一条"移除 ⇒ 终止进程"的既有路径
            //   —— 没有第二套停机逻辑，也就没有第二套可能出现的行为差异。
            val desired =
                if (!masterEnabled) {
                    emptySet()
                } else {
                    scripts
                        .filter { it.enabled && it.resident }
                        .map { it.id }
                        .toSet()
                }
            val current = synchronized(lock) { jobs.keys.toSet() }

            val toStart = desired - current
            val toStop = current - desired

            // ★ 两侧都要清"已放弃重启"的记录，理由不同（阶段 10 真机实测补的缺陷）：
            //   - **停止侧**：被移除 / 禁用了，这条记录已无意义
            //   - **启动侧**：用户取消勾选再勾回来，意图明确是"再试一次"。
            //     第一版只在停止侧清，于是"重新勾上"时 `givenUp` 仍记着该脚本，
            //     对账会**跳过启动**（`toStart` 里没有它）⇒ 脚本再也不跑，
            //     而且**一行日志都没有**。真机实测到过：
            //     只看到 `DAEMON_SUPERVISION_ADDED`，没有 `DAEMON_STARTED`。
            synchronized(lock) {
                (toStop + toStart).forEach { givenUp.remove(it) }
            }

            toStop.forEach { scriptId ->
                synchronized(lock) { jobs.remove(scriptId) }?.cancel()
                Log.i(TAG, "DAEMON_SUPERVISION_REMOVED script=$scriptId reason=trigger removed or disabled")
            }
            toStart.forEach { scriptId ->
                Log.i(TAG, "DAEMON_SUPERVISION_ADDED script=$scriptId reason=trigger created or enabled")
                supervise(scope = scope, scriptId = scriptId)
            }
            if (toStart.isNotEmpty() || toStop.isNotEmpty()) {
                Log.i(
                    TAG,
                    "DAEMON_RECONCILED masterEnabled=$masterEnabled desired=$desired " +
                        "started=${toStart.size} stopped=${toStop.size} " +
                        "supervised=${synchronized(lock) { jobs.size }}",
                )
            }
        }

        /** 为一个脚本起监管循环。 */
        private fun supervise(
            scope: CoroutineScope,
            scriptId: Long,
        ) {
            val job =
                scope.launch {
                    var rapidCrashes = 0
                    while (isActive) {
                        val accepted =
                            runCatching {
                                scriptRunner.get().start(
                                    scriptId = scriptId,
                                    triggerEvent = SystemEvent.ALWAYS_RUN,
                                    payload = null,
                                    timeoutOverrideMillis = DAEMON_TIMEOUT_MILLIS,
                                )
                            }.getOrElse { error ->
                                Log.w(TAG, "DAEMON_START_FAILED script=$scriptId: ${describe(error)}")
                                false
                            }

                        if (!accepted) {
                            // 未受理的原因有多种（被禁用 / 闸门满 / 装载失败 / 安全模式），
                            // 它们**都不是崩溃** ⇒ 不累计快速崩计数（也就不该判死），
                            // 只按基础退避重试：名额满了会自己好，被禁用则该行已不在查询结果里。
                            Log.i(
                                TAG,
                                "DAEMON_NOT_ACCEPTED script=$scriptId retryIn=${policy.baseDelayMillis}ms",
                            )
                            delay(policy.baseDelayMillis)
                            continue
                        }

                        Log.i(TAG, "DAEMON_STARTED script=$scriptId rapidCrashes=$rapidCrashes")
                        // ★ 存活时长由 [awaitRunEnd] 量并返回，**不在调用侧**用 `startedAt` 相减。
                        //   理由（阶段 10 的一个真缺陷）：调用侧的计时**包含**"等这次运行出现在
                        //   注册表里"的那段时间（root 往返 + 装载，可能几百毫秒），
                        //   于是"一个正常跑了几秒的脚本"也可能被算成"存活很短" ⇒ 被误判成快速崩。
                        //   实测：脚本运行 250ms（阈值 200ms）却报 `rapidCrashes=3` 而被判死。
                        val runMillis = awaitRunEnd(scriptId)

                        if (policy.isStable(runMillis)) {
                            rapidCrashes = 0
                            Log.i(TAG, "DAEMON_EXITED script=$scriptId runMillis=$runMillis stable=true")
                        } else {
                            rapidCrashes += 1
                            Log.w(
                                TAG,
                                "DAEMON_EXITED script=$scriptId runMillis=$runMillis stable=false " +
                                    "rapidCrashes=$rapidCrashes/${policy.maxRapidCrashes}",
                            )
                        }

                        val nextDelay = policy.nextDelayMillis(rapidCrashes)
                        if (nextDelay == null) {
                            val reason =
                                "连续快速崩 ${policy.maxRapidCrashes} 次" +
                                    "（每次存活 < ${policy.rapidExitThresholdMillis}ms）"
                            synchronized(lock) {
                                givenUp[scriptId] = reason
                                jobs.remove(scriptId)
                            }
                            // 明确**不**调用熔断器：用户 2026-09-20 的裁定。
                            Log.w(
                                TAG,
                                "DAEMON_GIVEN_UP script=$scriptId reason=rapid crashes " +
                                    "$rapidCrashes/${policy.maxRapidCrashes} " +
                                    "(only this script stops; global circuit breaker untouched)",
                            )
                            return@launch
                        }
                        Log.i(TAG, "DAEMON_RESTART_IN script=$scriptId delayMillis=$nextDelay")
                        delay(nextDelay)
                    }
                }
            synchronized(lock) { jobs[scriptId] = job }
        }

        /**
         * 等这个脚本的这一轮运行结束，并**返回它实际活了多久**（毫秒）。
         *
         * ## 时长从哪来（P9 修正）
         * **不再**靠本方法自己计时，而是取 [RunSessionRegistry.lastDurationMillis] ——
         * 那是 `register` / `unregister` **两个真实时刻**的差，不含任何轮询误差。
         *
         * 本方法**仍然**要轮询"它在不在跑"，因为监管循环需要一个"这一轮结束了"的信号；
         * 但轮询只用来**触发判断**，不再用来**测量**。
         *
         * 旧实现（两次 `elapsedRealtime` 相减）的三个误差：
         * 1. 量化：最多多算一个 `POLL_INTERVAL`
         * 2. 相位：起点是"探测到它出现"，实际开始得更早
         * 3. **整次丢失**：`echo` 型脚本活 200ms，若在两次轮询之间起止则**一次都没被看见**
         *
         * ## 为什么先等它"出现"、再等它"消失"
         * `start` 返回 `true` 只表示已受理，`RunSessionRegistry.register` 发生在 runner
         * 内部稍后的一刻。若立刻开始轮询"它消失了吗"，可能在**注册之前**就判定为"已结束"
         * ⇒ 监管循环会以 0ms 存活时长疯狂重启。因此先等它出现，且给一个上限。
         *
         * ## ★ "没等到它出现"不等于"它没跑"（旧实现在这里丢掉了整次运行）
         * 极短命脚本可能正好落在两次轮询之间 —— 它的 `register` 与 `unregister`
         * **都已经执行完了**，真实时长就躺在注册表里。旧实现无条件返回 `0`
         * ⇒ 被 `DaemonRestartPolicy.isStable` 判成"快速崩"（真机日志：
         * `DAEMON_APPEAR_TIMEOUT waited=3000ms` 之后跟一串 `rapidCrashes`）。
         * 现在先查注册表，查不到才是真的"没起来"。
         *
         * @return 本次存活时长；**确实没起来**时返回 `0`（按"立刻结束"处理，
         *   由 [DaemonRestartPolicy.isStable] 判定成快速崩 —— 那正是"起不来"该有的待遇）
         */
        private suspend fun awaitRunEnd(scriptId: Long): Long {
            var waitedForAppear = 0L
            var appeared = false
            while (!appeared && waitedForAppear < APPEAR_TIMEOUT_MILLIS) {
                if (isScriptActive(scriptId)) {
                    appeared = true
                } else {
                    delay(POLL_INTERVAL_MILLIS)
                    waitedForAppear += POLL_INTERVAL_MILLIS
                }
            }
            if (!appeared) {
                // ★ P9：先取注册表里的真实时长（短命脚本在这里能被救回来）
                sessionRegistry.lastDurationMillis(scriptId)?.let { measured ->
                    Log.i(
                        TAG,
                        "DAEMON_APPEAR_MISSED script=$scriptId measuredMillis=$measured " +
                            "(finished between two polls; duration from the session registry)",
                    )
                    return measured
                }
                // 注册表里也没有 ⇒ 它真的没起来
                Log.w(TAG, "DAEMON_APPEAR_TIMEOUT script=$scriptId waited=${APPEAR_TIMEOUT_MILLIS}ms")
                return 0L
            }
            // 就绪兜底计时的起点（只在注册表取不到值时才会用到）
            val fallbackSince = elapsedRealtimeMillis()
            while (isScriptActive(scriptId)) {
                delay(POLL_INTERVAL_MILLIS)
            }
            // ★ P9：取真实差值。循环退出时 `unregister` 必然已执行
            //   （两者读同一把锁），因此这里拿到的就是这一轮的时长。
            return sessionRegistry.lastDurationMillis(scriptId)
                ?: (elapsedRealtimeMillis() - fallbackSince)
        }

        private fun isScriptActive(scriptId: Long): Boolean =
            sessionRegistry.activeRuns().any { (_, id) -> id == scriptId }

        private fun describe(error: Throwable): String =
            error::class.java.simpleName + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /**
             * 常驻脚本的超时阈值：`Long.MAX_VALUE`（≈ 292 年，实践上等同不限）。
             *
             * ## 为什么不是 `0`
             * `TriggeredScriptRunner.effectiveTimeoutMillis` 把 `timeoutSec = 0` 解释为
             * **回落全局默认 60 秒**（需求 §5.1 优先于 §3.3 的"0 = 不限"）。
             * 传 0 会让一个正常运行的常驻脚本在 60 秒被当超时杀掉、并计一次失败。
             * 传一个实践上无限的正数，是那条换算之外唯一诚实的表达。
             */
            const val DAEMON_TIMEOUT_MILLIS: Long = Long.MAX_VALUE

            /** 轮询间隔：足以察觉结束，又远低于任何可感知开销。 */
            const val POLL_INTERVAL_MILLIS: Long = 200L

            /** 等"这次运行出现"的上限：3 秒足够覆盖一次 root 往返 + 装载。 */
            const val APPEAR_TIMEOUT_MILLIS: Long = 3_000L
        }
    }
