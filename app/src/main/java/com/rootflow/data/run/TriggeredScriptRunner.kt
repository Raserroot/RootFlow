package com.rootflow.data.run

import android.util.Log
import com.rootflow.BuildConfig
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventChannel
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.RunAdmissionGate
import com.rootflow.domain.event.SafeModeDecision
import com.rootflow.domain.event.ScriptRunner
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.RunMeta
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.runtime.RunContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实的脚本投递实现（阶段 3d，替换 3b 的 `NoopScriptRunner`；阶段 4 接入熔断）。
 *
 * ## 它在链路中的位置
 * ```
 * TriggerDispatcher.dispatch(event)
 *   → ScriptRunner.start(scriptId, eventId, payload)      ← 本类
 *        ⓪ ★总闸：app_switch.master_enabled == false → 拒绝（最高优先，含 runOnSafeMode）
 *        ① ScriptRepository.load(scriptId)（suspend，经 root 通道读正文）
 *        ② enabled == false → 拒绝（需求 §4.4）
 *        ③ ★SafeModeDecision.shouldRun（需求 §5.4）—— 闸门**之前**
 *        ④ Script → runtime.ScriptEntity（ScriptEntityMapper）
 *        ⑤ RunAdmissionGate.tryAcquire（重入 + 全局上限 4）
 *             （安全模式下**跳过**，见 SafeModeDecision:skipsAdmissionGate）
 *        ⑥ ★RunSessionRegistry.register（无条件：熔断要按 runId 找到每一个在跑的运行）
 *        ⑦ RunContext(triggerEvent, env + ROOTFLOW_SAFEMODE)
 *        ⑧ ScriptRunCoordinator.startLogging(...)
 *             job.invokeOnCompletion { registry.unregister; gate.release }
 *        ⑨ ★CircuitBreaker.onRunAccepted（受理之后才计数）
 * ```
 *
 * ## ★ 总闸（⓪）与安全模式（③）是**两条独立规则**（总开关重构 P3）
 * | | 总闸 `master_enabled` | 安全模式 |
 * |---|---|---|
 * | 谁触发 | 用户（主页大开关） | 故障（连续失败 / 超时 / bootloop / 手动） |
 * | 豁免 | **无**（`runOnSafeMode = true` 的脚本也停） | `runOnSafeMode = true` 可放行 |
 * | 恢复后 | 脚本按 `enabled && resident` 立刻回来 | 需人工退出安全模式 |
 *
 * 判据来源是 `docs/总开关机制-方案.md` §7 决策 5（用户裁定：总开关是**绝对**闸门）。
 * 判据读内存快照（`MasterSwitch.enabled`），因此这道闸门**零 IO**。
 *
 * ## 每一步失败都必须有具体日志（本仓库的反复纪律）
 * `load` 的四种失败结果（`Missing` / `Corrupted` / `NotFound` / `Unavailable`）
 * **各有独立文案**，因为它们的排查方向完全不同：文件被删、文件被外部改动、
 * 元数据不在库中、root 通道不可用。把它们折叠成一句"启动失败"会让真机排障
 * 从一次 `findstr` 变成一轮猜测。
 *
 * ## 闸门释放是硬约束（3d 方案 §2 第 6 步）
 * 拿到 [RunAdmissionGate.AdmissionResult.Accepted] 之后**必须**确保释放：
 * 未释放会让该脚本的**重入拒绝永久生效**——比不拒绝更糟（脚本再也跑不起来）。
 * 因此释放挂在工作协程的 `invokeOnCompletion` 上，正常结束 / 异常 / 取消三条路径都会触发。
 * 启动路径本身抛错时（`catch` 分支）立即归还名额。
 *
 * ## ★ 安全模式下**跳过闸门**（需求 §5.4，`SafeModeDecision` 的 KDoc 有完整理由）
 * 正常脚本在安全模式下全被挡下，能跑的只有 `runOnSafeMode=true` 的少数"救砖用"脚本；
 * 让它们因闸门满而跑不起来与本机制的目的相悖。
 * **但 `RunSessionRegistry` 仍然无条件登记**——否则熔断的第 2 步会漏 kill 这些运行
 * （这正是 `RunSessionRegistry` 独立于闸门存在的理由）。
 * 跳过闸门时**不得**在收尾释放名额：那会误放掉别人占用的名额
 * （`RunAdmissionGate.release` 按 `scriptId` 计数，放错会让别的脚本被并发启动两次）。
 *
 * ## 为什么依赖 `ScriptRepository` 而不是 `ScriptDao`
 * 正文在文件系统（需求 §3.2），必须经 root 通道读取并校验摘要；
 * 这些一致性策略（含"有行无文件"的显式区分）由 3a 的仓库实现，本类不重复实现。
 */
@Singleton
class TriggeredScriptRunner
    @Inject
    constructor(
        private val scriptRepository: ScriptRepository,
        private val coordinator: ScriptRunCoordinator,
        private val gate: RunAdmissionGate,
        private val batchSink: LogBatchSink,
        private val scope: CoroutineScope,
        private val circuitBreaker: CircuitBreaker,
        private val masterSwitch: MasterSwitch,
        /** P5：给**运行中的**脚本投事件用的通道（FIFO）。 */
        private val eventChannel: EventChannel,
        private val sessionRegistry: RunSessionRegistry,
        private val outcomeSink: RunOutcomeSink,
    ) : ScriptRunner {
        override suspend fun start(
            scriptId: Long,
            triggerEvent: String,
            payload: String?,
            timeoutOverrideMillis: Long?,
        ): Boolean {
            // ⓪ ★总闸（总开关重构 P3 的不变量 1）：**任何**脚本都不得在总闸关闭时启动 ——
            //    含 `runOnSafeMode = true` 的救砖脚本（方案 §7 决策 5：总闸是**绝对**闸门，
            //    与安全模式是两条独立规则，互不豁免）。
            //
            //    位置在**最前**（早于 load），两个理由：
            //    - 语义：用户明确说了"别提供服务"，没有比这更根本的拒绝理由
            //    - 代价：连一次经 root 通道的正文读取都不做（`load` 是要 `su` 的）
            //
            //    读内存快照（`StateFlow`）⇒ **零 IO**。这条闸门每次启动都要过。
            if (!masterSwitch.enabled.value) {
                Log.w(
                    TAG,
                    "RUN_REJECTED script=$scriptId reason=master switch off " +
                        "(app_switch.master_enabled=false; turn it on from the home screen)",
                )
                return false
            }

            // ① 装载（suspend，经 root 通道读正文 + 摘要校验）
            val loaded = scriptRepository.load(scriptId)
            val script =
                when (loaded) {
                    is ScriptLoadResult.Ok -> loaded.script

                    // 四种失败各自**独立记一行**：它们指向完全不同的排查方向
                    // （文件被删 / 文件被外部改动 / 元数据不在库 / root 通道不可用），
                    // 折叠成一句"启动失败"会让真机排障从一次 findstr 变成一轮猜测。
                    ScriptLoadResult.Missing -> {
                        Log.w(
                            TAG,
                            "SCRIPT_LOAD_FAILED script=$scriptId event=$triggerEvent reason=body file missing (row exists, file gone)",
                        )
                        return false
                    }

                    is ScriptLoadResult.Corrupted -> {
                        Log.w(
                            TAG,
                            "SCRIPT_LOAD_FAILED script=$scriptId event=$triggerEvent " +
                                "reason=body checksum mismatch expected=${loaded.expected} actual=${loaded.actual}",
                        )
                        return false
                    }

                    ScriptLoadResult.NotFound -> {
                        Log.w(
                            TAG,
                            "SCRIPT_LOAD_FAILED script=$scriptId event=$triggerEvent reason=script row not found",
                        )
                        return false
                    }

                    is ScriptLoadResult.Unavailable -> {
                        Log.w(
                            TAG,
                            "SCRIPT_LOAD_FAILED script=$scriptId event=$triggerEvent " +
                                "reason=root channel unavailable: ${loaded.reason}",
                        )
                        return false
                    }
                }

            // ② enabled 校验（需求 §4.4「检查脚本 enabled」）
            if (!script.enabled) {
                Log.w(TAG, "RUN_REJECTED script=$scriptId reason=script disabled (enabled=false)")
                return false
            }

            // ③ ★安全模式放行判定（需求 §5.4）——**闸门之前**
            //
            // 顺序有意如此：安全模式是比"并发上限"更根本的拒绝理由，先判它能省掉一次
            // 闸门占用（也就省掉一次释放失误的机会），且拒绝原因更准确。
            //
            // 判定本身只有一处实现（`SafeModeDecision`）：阶段 6 的「手动运行」会走同一条
            // `start`，若在这里另写一个 `if`，两处迟早漂移——而"部分脚本仍在安全模式里跑"
            // 正是熔断要防的事。
            //
            // 取值在**这一次**判定内只读一次并复用（`safeMode` 可能被熔断动作并发置位），
            // 否则"放行判定"与"是否跳过闸门"可能基于两个不同的快照，
            // 出现"按放行进入、却按不放行去占名额"的错配。
            val safeMode = circuitBreaker.safeMode.value
            if (!SafeModeDecision.shouldRun(safeMode = safeMode, runOnSafeMode = script.runOnSafeMode)) {
                Log.w(
                    TAG,
                    "RUN_REJECTED script=$scriptId reason=safe mode active " +
                        "(runOnSafeMode=false; enable it to allow this script in safe mode)",
                )
                return false
            }

            // ④ domain.Script → runtime.ScriptEntity
            val entity = ScriptEntityMapper.toRuntime(script)

            // ⑤ 准入闸门：重入拒绝 + 全局并发上限（需求 §2.2）。
            //    安全模式下**跳过**（已批准的语义，见 SafeModeDecision 的 KDoc）：
            //    能跑的本就只有 runOnSafeMode=true 的少数救砖脚本，让它们因闸门满而
            //    跑不起来与机制目的相悖。跳过时**不占名额**，因此收尾也**不得释放**。
            val gateHeld = !SafeModeDecision.skipsAdmissionGate(safeMode)
            if (gateHeld) {
                when (val admission = gate.tryAcquire(scriptId)) {
                    RunAdmissionGate.AdmissionResult.Accepted -> Unit

                    RunAdmissionGate.AdmissionResult.ReentryRejected -> {
                        Log.w(TAG, "RUN_ADMISSION_REJECTED script=$scriptId reason=reentry (already running)")
                        return false
                    }

                    RunAdmissionGate.AdmissionResult.GlobalLimitReached -> {
                        Log.w(
                            TAG,
                            "RUN_ADMISSION_REJECTED script=$scriptId " +
                                "reason=global limit reached (active=${gate.activeCount})",
                        )
                        return false
                    }
                }
            } else {
                Log.i(
                    TAG,
                    "RUN_ADMISSION_SKIPPED script=$scriptId reason=safe mode " +
                        "(runOnSafeMode=true; registry still tracks this run)",
                )
            }

            // ⑥ ★ P5：为**本次运行**开事件通道（FIFO），并把它的路径注入脚本环境。
            //
            //   位置必须在 `startLogging` **之前**：`ROOTFLOW_EVENT_FIFO` 是随 `ctx` 交给
            //   子进程的，而进程一旦起来就再也拿不到"后来才建"的通道 —— 它会在一个
            //   不存在的路径上 `read` 阻塞，那是脚本侧最难排查的一类失败。
            //
            //   `runId` 由本类预生成并传给协调者（默认值那侧是同一个函数），
            //   因此通道路径与 logcat 里的 `runId` **一眼能对上**。
            //
            //   开不出来时**不注入该变量**（见 [buildEnv]）：脚本因此知道"没有通道"，
            //   而不是对着一个永远不会有数据的路径阻塞。
            val runId = UUID.randomUUID().toString()
            val fifo = eventChannel.open(runId = runId, scriptId = scriptId)
            if (fifo == null) {
                Log.w(
                    TAG,
                    "RUN_EVENT_CHANNEL_ABSENT script=$scriptId runId=$runId " +
                        "(no ROOTFLOW_EVENT_FIFO; the script will not receive later events)",
                )
            }

            // ⑦ 运行上下文：事件负载经 ROOTFLOW_EVENT_PAYLOAD 注入脚本（需求 §3.2）。
            //    `payload` 由 TriggerDispatcher 传入（优先 params.payload，回退 payloadJson()）。
            //    `ROOTFLOW_SAFEMODE` 是阶段 4 新增：脚本据此自行决定是否降级行为（需求 §3.2 列有此变量）。
            //    注意：`RunContext.env` 的实际注入由 runtime 侧完成（阶段 1b 起为"承载并透传"），
            //    本阶段只负责把值填对。
            val ctx =
                RunContext(
                    triggerEvent = triggerEvent,
                    env = buildEnv(scriptId, triggerEvent, payload, safeMode, fifo),
                )

            // ⑧ 启动、登记运行集合、并挂载收尾钩子
            return try {
                val session =
                    coordinator.startLogging(
                        script = entity,
                        ctx = ctx,
                        scope = scope,
                        // 超时阈值在投递侧换算（`timeoutSec` 的语义解释权留在这里，
                        // 见 ScriptRunCoordinator 的 KDoc：它只认毫秒）
                        //
                        // ★ `timeoutOverrideMillis` 优先（阶段 10）：「一直运行」的常驻脚本
                        //   传 `Long.MAX_VALUE` ⇒ 不受时长限制。**不能**让它走
                        //   `effectiveTimeoutMillis(0)` 那条路 —— 那是"回落默认 60 秒"，
                        //   会把一个正常运行几分钟的常驻进程当超时杀掉并计一次失败。
                        timeoutMillis = timeoutOverrideMillis ?: effectiveTimeoutMillis(script.timeoutSec),
                        outcomeSink = outcomeSink,
                        runMeta = RunMeta(scriptId, triggerEvent),
                        batchSink = batchSink,
                        runId = runId,
                    )

                // ★登记"当前在跑"：**无条件**执行，与是否走过闸门无关。
                // 熔断的第 2 步按 runId 终止**每一个**在跑的运行（含跳过闸门的那些），
                // 少了这一行就会出现"熔断了但脚本还在跑"。
                sessionRegistry.register(session.runId, scriptId)

                // 释放挂在工作协程的完成回调上：正常结束 / 异常 / 取消三条路径都会触发。
                // 未释放会让该脚本的**重入拒绝永久生效**——比不拒绝更糟。
                // 注销同理：漏注销会让熔断去 kill 一个早已结束的运行（纯噪声 + 误导判读）。
                session.job.invokeOnCompletion {
                    sessionRegistry.unregister(session.runId)
                    if (gateHeld) gate.release(scriptId)
                    // ★ P5：运行结束 ⇒ 关掉它的事件通道（删 FIFO）。
                    //   挂在这同一个收尾钩子上，与 unregister/release 同寿。
                    //   非 suspend 的回调里要 launch：用**应用级**的 `scope`（不是运行作用域，
                    //   后者此刻正在收尾）。漏关的后果只是设备上残留一个 FIFO，
                    //   而下一次运行开通道时会 `rm -f` 清掉它。
                    scope.launch { eventChannel.close(runId) }
                }

                Log.i(
                    TAG,
                    "RUN_ACCEPTED runId=${session.runId} script=$scriptId event=$triggerEvent " +
                        "active=${gate.activeCount} gateHeld=$gateHeld safeMode=$safeMode",
                )

                // ★受理之后才计数（`CircuitBreaker.onRunAccepted` 的 KDoc 要求）：
                // 防抖与重入拒绝已经挡掉部分重复，把被拒的也算进来会让"高频自启"阈值失去意义。
                circuitBreaker.onRunAccepted(scriptId)
                true
            } catch (error: Throwable) {
                // 启动路径自身抛错：已占用的闸门必须归还，否则该脚本被永久拒绝
                if (gateHeld) gate.release(scriptId)
                Log.w(
                    TAG,
                    "RUN_START_FAILED script=$scriptId event=$triggerEvent: " +
                        (error.message ?: error::class.java.name),
                )
                false
            }
        }

        /**
         * 构造注入脚本的环境变量（需求 §3.2）。
         *
         * ## 变量名以**需求 §3.2 原文**为准（阶段 6b 改名）
         * 需求列的是 `ROOTFLOW_EVENT`，而实现此前用的是 `ROOTFLOW_EVENT_ID`。
         * 两者只在本仓库内部自洽（fixture 脚本回显的也是 `_ID`），**与规格不符**——
         * 而需求侧是唯一真相源，因此改名为 [ENV_EVENT]。
         *
         * 改名必须**一次做完**（写入侧 + 回显侧 + 断言 + KDoc），否则会留下
         * "脚本回显一个不存在的变量名 ⇒ 看起来像没注入"的假缺陷。
         *
         * | 变量 | 来源 | 说明 |
         * |---|---|---|
         * | `ROOTFLOW_SCRIPT_ID` | 脚本主键 | |
         * | `ROOTFLOW_EVENT` | `RunContext.triggerEvent` | 触发事件键（需求 §3.2 的原文名） |
         * | `ROOTFLOW_EVENT_PAYLOAD` | 事件负载 | **无负载时不写入该键**：写空串会让脚本无法区分"没有负载"与"负载是空串" |
         * | `ROOTFLOW_APP_VERSION`（阶段 6b 补） | [BuildConfig.VERSION_NAME] | 需求 §3.2 列出但**从未注入过**。直用 `BuildConfig` 而不经 domain 端口：app 模块内已有此依赖（`RootEnvironmentInfoProvider` 同款）、它是编译期常量、且**没有第二个消费者**——为它加一个端口只多一层无意义的转发 |
         * | `ROOTFLOW_SAFEMODE`（阶段 4） | 熔断器 | **始终写入**（`"true"` / `"false"`）：布尔状态而非可选值，缺失会让脚本无法区分"不在安全模式"与"宿主没实现该变量" |
         * | `ROOTFLOW_EVENT_FIFO`（P5） | 事件通道 | **有通道才写入**：常驻脚本用它 `read` 后续事件。缺失 = 宿主开不出通道（**如实**告知），而不是给一个永远读不到数据的路径 |
         *
         * ## 注入由 runtime 侧完成（阶段 6b 才真正落地）
         * 本方法只负责**把值填对**；真正的 `export` 在
         * `ShellScriptRuntime.buildExportLines`。阶段 1b~6b 之间那一跳一直是断的
         * （见 `AGENT_PROTOCOL.md` 的「跨模块契约必须有跨界断言」一节）。
         */
        private fun buildEnv(
            scriptId: Long,
            triggerEvent: String,
            payload: String?,
            safeMode: Boolean,
            eventFifo: String?,
        ): Map<String, String> =
            buildMap {
                put(ENV_SCRIPT_ID, scriptId.toString())
                put(ENV_EVENT, triggerEvent)
                put(ENV_APP_VERSION, BuildConfig.VERSION_NAME)
                put(ENV_SAFE_MODE, safeMode.toString())
                payload?.let { put(ENV_EVENT_PAYLOAD, it) }
                // ★ P5：事件通道。**无通道时不写该键**（与 payload 同款纪律）：
                //   写一个空串会让脚本无法区分"宿主没提供通道"与"通道路径是空串"；
                //   前者应当让它走"没有事件可读"的分支，后者会让它去读一个空路径。
                eventFifo?.let { put(ENV_EVENT_FIFO, it) }
            }

        /**
         * 把 `ScriptEntity.timeoutSec` 换算成毫秒阈值（需求 §5.1 第 1 条）。
         *
         * ## ★ `timeoutSec = 0` 回落全局默认 60s（**已确认的契约**）
         * 需求 §3.3 把 `0` 定义为"不限"，但需求 §5.1 写"见 `timeoutSec`，**全局默认 60s**"。
         * 二者冲突时以 **§5.1 优先**：熔断语境下**不允许无超时**——
         * 一个挂死的脚本会让整个熔断机制失效（而"脚本挂死"正是熔断要防的首要场景）。
         *
         * 该取舍已写进 `ScriptRunCoordinator.startLogging` 的 KDoc 并经用户确认；
         * 需要"真不限"时用 `timeoutSec = Int.MAX_VALUE`（阶段 6 的 UI 可据此提供选项）。
         *
         * 换算放在**投递侧**而不是 `ScriptRunCoordinator`：协调者只认毫秒，
         * `timeoutSec` 的语义解释权留在这一处，才不会出现"两处各有一套换算规则"。
         */
        private fun effectiveTimeoutMillis(timeoutSec: Int): Long =
            if (timeoutSec > 0) {
                timeoutSec * MILLIS_PER_SECOND
            } else {
                DEFAULT_TIMEOUT_MILLIS
            }

        companion object {
            /** logcat tag；与全项目一致。 */
            const val TAG: String = "RootFlow"

            /** 注入脚本的环境变量名（需求 §3.2）。 */
            const val ENV_SCRIPT_ID: String = "ROOTFLOW_SCRIPT_ID"

            /**
             * 触发事件键（需求 §3.2 原文名）。
             *
             * 阶段 6b 由 `ROOTFLOW_EVENT_ID` 改名而来 —— 需求侧是唯一真相源，
             * 改名必须与回显侧（脚本正文里的 `$ROOTFLOW_EVENT`）、断言、KDoc **一次做完**。
             */
            const val ENV_EVENT: String = "ROOTFLOW_EVENT"

            /** 应用版本（需求 §3.2；阶段 6b 补注入）。 */
            const val ENV_APP_VERSION: String = "ROOTFLOW_APP_VERSION"

            /** 事件负载（无负载时该键不存在）。 */
            const val ENV_EVENT_PAYLOAD: String = "ROOTFLOW_EVENT_PAYLOAD"

            /** 安全模式标志（始终存在，取值 `"true"` / `"false"`）。 */
            const val ENV_SAFE_MODE: String = "ROOTFLOW_SAFEMODE"

            /**
             * 事件通道（P5）。
             *
             * 常驻脚本侧用法：`read -r ev < "$ROOTFLOW_EVENT_FIFO"`。
             * **有通道才存在该变量** —— 缺失代表宿主开不出通道（root 通道不可用），
             * 脚本据此可以走"没有事件可读"的分支而不是阻塞在一个无效路径上。
             */
            const val ENV_EVENT_FIFO: String = "ROOTFLOW_EVENT_FIFO"

            /** 需求 §5.1：`timeoutSec = 0` 时回落的全局默认超时。 */
            const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L

            private const val MILLIS_PER_SECOND: Long = 1000L
        }
    }
