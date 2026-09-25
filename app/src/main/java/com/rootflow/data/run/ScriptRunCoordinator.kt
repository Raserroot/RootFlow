package com.rootflow.data.run

import android.util.Log
import com.rootflow.data.log.LogMappers
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunMeta
import com.rootflow.runtime.LogLine
import com.rootflow.runtime.LogStream
import com.rootflow.runtime.ProcessGroupManager
import com.rootflow.runtime.RunContext
import com.rootflow.runtime.ScriptEntity
import com.rootflow.runtime.ShellScriptRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次已受理运行的会话句柄（阶段 3d，接口扩展 **D-3d-2**）。
 *
 * ## 为什么必须把 [job] 交出来
 * 全局并发闸门（`RunAdmissionGate`）的占用必须**恰好在运行结束时**释放；
 * 释放晚了会让重入拒绝永久生效，释放早了会让同一脚本被并发启动两次。
 * 调用方唯一可靠的挂载点是"这次运行的工作协程"——即 [job] 的
 * `invokeOnCompletion`。
 *
 * ## 为什么 [job] 非空
 * `CoroutineScope.launch` **总是**返回 `Job`，因此"拿到会话却没有作业"在协程语义下
 * 不可能发生。把它声明为可空只会迫使每个调用方写一条**永远走不到**的分支，
 * 而那类分支既无法在测试里触发，也会掩盖真实的 bug（真出问题时会静默走降级路径
 * 而不是报错）。因此这里如实声明为非空；`TriggeredScriptRunner` 仍用
 * try/catch 兜住"启动路径整体抛错"，并在 `catch` 里归还闸门名额。
 *
 * ## ★ 11e 补丁4：`job` 的来源变了（**这是修 bug 的关键**）
 * 它**不再**是 `scope.launch { startRun(...) }` 的返回值 —— 那个 job 会随 `startRun`
 * 立即返回而秒级完成，挂在它上面的收尾钩子（注销运行 / 释放闸门 / 关事件通道 /
 * 取消超时看门狗 / 记存活时长）会**全部提前触发**。现在它是**管道内部的收集作业**
 * （由 `LogPipeline.startRun` 交出），生命周期覆盖
 * 「收集 `RunHandle.output` → flusher 收敛 → 收尾批推送」全程。
 *
 * @property runId 本次运行的标识（**就是** `RunHandle.id`，UI 用它订阅 `LogPipeline.observe`）
 * @property job 承载本次运行的工作协程（管道内部的收集作业）
 */
data class RunSession(
    val runId: String,
    val job: Job,
)

/**
 * 脚本运行与日志管道的对接点（阶段 2 引入，阶段 3d 扩展）。
 *
 * ## 为什么需要它
 * `runtime.RunHandle.output` 是**一次性冷流**（只能被收集一次）。因此必须有一个
 * 明确的组件"拥有那次收集"，否则会出现"管道收一次、UI 再收一次"的二次收集崩溃。
 * 本类就是那个唯一收集者：它负责创建运行、把输出映射成 `domain.LogEntry` 并馈入
 * [LogPipeline]，UI / ViewModel **只订阅管道**，绝不直接碰 `RunHandle.output`。
 *
 * ## 阶段 3d 的两处扩展
 * 1. [startLogging] 增加 [RunMeta] 与 [LogBatchSink] 两个可选参数（3d 方案 §8 的候选 A）：
 *    运行元信息与日志源在**同一次调用**里交给管道，落库侧因此不存在
 *    "批次先到、元信息还没登记"的竞态
 * 2. 返回值由 `String` 变为 [RunSession]（**D-3d-2**）：调用方需要工作协程来挂载
 *    闸门释放钩子
 *
 * ## 为什么注入调用方作用域而不是自建
 * 收集必须与调用方同生命周期（`AGENTS.md`：所有长期运行任务必须有明确的生命周期作用域）。
 * 本类不自建作用域，只接受 [startLogging] 传入的 [scope]（阶段 3 由调度器提供，
 * 阶段 6 由 ViewModel 提供；当前由验证入口提供）。
 *
 * ## 职责边界（阶段 3d 后仍然成立）
 * 本类**不做**脚本装载、不做准入判定、不做超时控制与失败自动禁用
 * （装载与准入属 `TriggeredScriptRunner`，超时属阶段 4）。
 */
@Singleton
class ScriptRunCoordinator
    @Inject
    constructor(
        private val shellScriptRuntime: ShellScriptRuntime,
        private val logPipeline: LogPipeline,
        private val processGroupManager: ProcessGroupManager,
    ) {
        /**
         * 启动一次脚本运行，并把其日志接入管道。
         *
         * ## 阶段 4：超时看门狗（需求 §5.1 第 1 条）
         * `timeoutMillis > 0` 时启一个看门狗：到点调用 [ProcessGroupManager.terminateRun]
         * **整组终止**（需求 §4.2：不允许只 kill 单个 PID），再经 [outcomeSink] 报一次
         * "超时"给熔断器。看门狗随作业结束自动取消（`invokeOnCompletion`）。
         *
         * ## ★ `timeoutSec = 0` 的契约（**调用方必须按此换算**）
         * 需求 §3.3 把 `timeoutSec = 0` 定义为"**不限**"，但**熔断语境下不允许无超时**
         * ——一个挂死的脚本会让整个熔断机制失效。因此以需求 **§5.1** 为准：
         * ```
         * timeoutSec  > 0  ⇒  timeoutMillis = timeoutSec * 1000L
         * timeoutSec == 0  ⇒  回落全局默认 60_000ms（需求 §5.1「全局默认 60s」）
         * ```
         * **如需"真不限"**：用 `timeoutSec = Int.MAX_VALUE`（≈68 年，实践上等同不限），
         * 而不是 `0`。阶段 6 的 UI 可据此提供"无超时"选项并映射到该值。
         *
         * 该换算由调用方（`TriggeredScriptRunner.effectiveTimeoutMillis`）完成，
         * **不放在本类**：本类只认"毫秒阈值"，把 `ScriptEntity.timeoutSec` 的语义
         * 解释权留在投递侧，才不会出现"两处各有一套换算规则"。
         *
         * @param script 待执行脚本
         * @param ctx 运行上下文
         * @param scope 负责收集日志的作用域（应与调用方同生命周期）
         * @param timeoutMillis 生效的超时阈值（**已换算**）；`<= 0` 表示不限
         * @param outcomeSink 运行结局回调（供熔断器计数）；`null` 表示不关心
         * @param runMeta 运行元信息；落库侧据此登记 `runs.script_id` / `trigger_event`
         * @param batchSink 批次回调；`null` 表示本次运行不落库（例如手动自检）
         * @param runId 运行标识。**默认由本方法生成**；调用方预生成并传入的唯一理由是
         *   **P5 的事件通道**：通道路径含 `runId`（`ipc/<runId>.q`），而它必须在
         *   `ShellScriptRuntime.run` **之前**建好，因为该路径要经 `ROOTFLOW_EVENT_FIFO`
         *   注入脚本环境 —— 而环境是随 `ctx` 一起交给 `run` 的。
         *   传进来的值与返回值里的 `runId` 是同一个（真机判读因此能一眼对上：
         *   logcat 的 `runId` == FIFO 文件名）。
         * @return 本次运行的会话（标识 + 工作协程）
         */
        suspend fun startLogging(
            script: ScriptEntity,
            ctx: RunContext,
            scope: CoroutineScope,
            timeoutMillis: Long = 0L,
            outcomeSink: RunOutcomeSink? = null,
            runMeta: RunMeta? = null,
            batchSink: LogBatchSink? = null,
            runId: String = UUID.randomUUID().toString(),
        ): RunSession {
            // ★ 11e 补丁4：runId 必须**进入** handle —— `ProcessGroupManager` 按 runId
            //   读写 `.rf_pgid_<id>`，而 `terminateRun` / `cleanup` 拿到的也是 runId。
            //   两处若不是同一个值，超时终止与熔断的"杀进程"全是空操作。
            val handle = shellScriptRuntime.run(script, ctx, runId)

            // 退出码在**收集过程中就地捕获**——这是本阶段的关键取舍，见 [ExitCodeOnSysLine]。
            // 用 `var` 而不是 Channel/StateFlow：收集与 finally 在**同一个协程**里，
            // 不存在跨线程可见性问题，也不需要额外同步。
            //
            // 形参先落到局部常量：`launch { … }` 的隐式接收者是 `CoroutineScope`，
            // 但 `script.id` 若写成裸 `id` 会被同名局部遮蔽（这里特意用 `scriptId` 避免歧义）。
            val scriptId = script.id
            var exitCode: Int? = null
            val reported = AtomicBoolean(false)

            /**
             * 从 [handle] 被**完整消费之后**上报一次结局。
             *
             * ⚠ 本函数**只允许**由 `handle.output` 的 `onCompletion` 调用：
             * 退出码是 source 的**末行**，早于收集结束去读只会得到 `null`
             * （阶段 4 连续踩了三次，见 [startLogging] 的 KDoc）。
             */
            suspend fun reportAfterCollection() {
                if (!reported.compareAndSet(false, true)) return
                reportOutcome(sink = outcomeSink, runId = runId, scriptId = scriptId, exitCode = exitCode)
            }

            // ★ 11e 补丁4：**不再**把 `startRun` 包进 `scope.launch`。
            //   那个包装出来的 job 会随 `startRun` 返回而立即完成（"立即返回"是它的冻结契约），
            //   而调用方把「注销运行 / 释放闸门 / 关事件通道 / 取消超时看门狗 / 记存活时长」
            //   全挂在 `RunSession.job` 上 ⇒ 全部提前触发：常驻脚本因此被误判成
            //   "活了 0 毫秒、连崩 5 次"（真机 `DAEMON_EXITED runMillis=0` ×5 → `DAEMON_GIVEN_UP`），
            //   而脚本进程其实一直在跑。
            //   现在直接用**管道交出的收集作业**，它的生命周期就是整次运行。
            val collector =
                try {
                    // 映射在管道外部完成：管道只认 domain 类型，不认识 runtime 类型。
                    logPipeline.startRun(
                        runId = runId,
                        source =
                            handle.output
                                .onEach { line -> exitCode = ExitCodeOnSysLine.of(line) ?: exitCode }
                                // ★ 结局上报挂在 `onCompletion` 上：它在**收集作业内部**、
                                //    source 走完（或失败 / 被取消）之后运行，因此此时退出码
                                //    已经读到。这是唯一能拿到退出码的时点。
                                .onCompletion { reportAfterCollection() }
                                .map { line -> LogMappers.toDomain(line, runId) },
                        runMeta = runMeta,
                        batchSink = batchSink,
                    )
                } catch (error: Throwable) {
                    // ★ 只有"收集从未开始"才走这里：`startRun` 自身抛错时 `onCompletion`
                    //   永远不会触发，不兜底就是"运行结局丢失"——熔断器少记一次运行，
                    //   正是本仓库反复禁止的静默失败。此处 `exitCode` 为 `null` 是**正确**的
                    //   语义（脚本根本没跑），由接收方按"未知"处理（不计失败）。
                    //   随后把异常**交给调用方**：启动失败应当可见（`TriggeredScriptRunner`
                    //   的 catch 会记 `RUN_START_FAILED` 并归还闸门），而不是被静默吞掉。
                    reportAfterCollection()
                    throw error
                }

            if (timeoutMillis > 0) {
                startTimeoutWatchdog(
                    scope = scope,
                    runId = runId,
                    scriptId = script.id,
                    timeoutMillis = timeoutMillis,
                    job = collector,
                    outcomeSink = outcomeSink,
                )
            }
            return RunSession(runId = runId, job = collector)
        }

        /**
         * 上报一次运行的结局（**取消路径也必须送到**）。
         *
         * ## 调用点
         * 由 `handle.output` 的 `onCompletion` 触发（收集作业内部、source 走完后），
         * 外加 `startRun` 抛出时的 `finally` 兜底；`AtomicBoolean` 保证恰好一次。
         *
         * ## 为什么取消路径要包 `NonCancellable`
         * 运行被取消（熔断杀进程 / 超时终止 / 作用域被取消）时，挂起调用会**立刻再抛**
         * `CancellationException` ⇒ 熔断器永远收不到这条结局，于是"被取消的运行"完全落在
         * 计数之外——而它恰恰往往是最需要计入的那种运行。
         *
         * ## 为什么**不无条件**包 `NonCancellable`
         * `withContext` 会引入一次**额外的调度**，即使块内没有任何挂起点。
         * 那会让"作业完成"与"结局已上报"之间多出一个调度间隙
         * （`advanceUntilIdle()` 返回时结局可能还没送到，单测实测踩到）。
         *
         * 因此**只在真的被取消时**才付出这个代价：正常路径直接调用，
         * 取消路径包一层 `NonCancellable`。两条路径的语义都保留，且正常路径没有额外调度。
         */
        private suspend fun reportOutcome(
            sink: RunOutcomeSink?,
            runId: String,
            scriptId: Long,
            exitCode: Int?,
        ) {
            if (currentCoroutineContext().isActive) {
                sink?.onRunOutcome(runId, scriptId, exitCode)
                return
            }
            withContext(NonCancellable) {
                sink?.onRunOutcome(runId, scriptId, exitCode)
            }
        }

        /**
         * 超时看门狗：到点终止进程组并把结局报给 [outcomeSink]。
         *
         * ## 为什么挂在 `job.invokeOnCompletion` 上取消
         * 作业结束时（正常或异常）必须停掉看门狗，否则它会在一段早已结束的运行上
         * 触发 `terminateRun` —— 那时 PGID 文件已 `cleanup`，`terminateRun` 只会
         * 报"pgid 未解析"，属**纯噪声**并会污染真机日志的判读。
         */
        private fun startTimeoutWatchdog(
            scope: CoroutineScope,
            runId: String,
            scriptId: Long,
            timeoutMillis: Long,
            job: Job,
            outcomeSink: RunOutcomeSink?,
        ) {
            val watchdog =
                scope.launch {
                    delay(timeoutMillis)
                    val report = processGroupManager.terminateRun(runId)
                    Log.w(
                        TAG,
                        "RUN_TIMEOUT runId=$runId script=$scriptId afterMs=$timeoutMillis " +
                            "termSent=${report.termSent} killed=${report.killed} " +
                            "goneAfterTerm=${report.goneAfterTerm} probeFailed=${report.probeFailed}",
                    )
                    processGroupManager.cleanup(runId)
                    outcomeSink?.onRunTimeout(runId, scriptId, timeoutMillis)
                }
            job.invokeOnCompletion { watchdog.cancel() }
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }

/**
 * 运行结局回调（阶段 4，需求 §5.1 第 1/2/3 条的数据来源）。
 *
 * ## 为什么是 `data` 内部的接口而不是 domain 端口
 * 它的实现只有 [com.rootflow.data.event.CircuitBreakerImpl]（也在 `data`），
 * 且**没有任何 domain 类型需要跨界**——`ScriptRunCoordinator` 只负责"通知一声，
 * 结局是什么由熔断器自己判定"。放进 domain 只会凭空多一个无人实现的端口。
 *
 * ## 三条回调的语义（`onRunOutcome` 与 `onRunTimeout` 可能**都**被调用）
 * 超时路径上：看门狗先报 `onRunTimeout`，随后被终止的作业结束时报 `onRunOutcome`。
 * 熔断器对"同一运行计两次失败"是**安全的**（阈值是"≥"而非"=="），
 * 但**不能**把它当成"两次独立失败"来做风暴统计——因此
 * `onRunTimeout` 在熔断器里走的是**直接熔断**，不进失败计数。
 *
 * ## 退出码为什么由本接口携带（阶段 4 收尾的已批准修正）
 * 早先的形态是"接收方拿 `runId` 去查 `RunHistoryRepository.find(runId).exitCode`"，
 * 但那有**已知竞态**：`RunHistoryCollector` 是"有界队列 + 独立消费者"，
 * `enqueue` 只 `trySend` 就返回，而本回调挂在 `job.invokeOnCompletion` 上
 * ⇒ **早于消费者写库**，`exitCode` 可能读到 `null`。
 *
 * 现在由 [`ScriptRunCoordinator`] 在收集 `runtime.LogLine` 时**就地捕获**并随回调带出，
 * 全程无 DB 往返、无异步窗口。
 */
interface RunOutcomeSink {
    /**
     * 一次运行结束（正常 / 异常 / 取消）。
     *
     * @param runId 运行标识
     * @param scriptId 归属脚本
     * @param exitCode 本次运行的退出码；**`null` 表示"没拿到"**（运行被 kill / 取消，
     *   或脚本没跑到末尾——`ShellScriptRuntime` 的 KDoc 明写"收集被取消时退出行不会发射"）。
     *   接收方**不得**把 `null` 直接当成失败：那会让误熔断变成常态（漏熔断 < 误熔断）。
     */
    suspend fun onRunOutcome(
        runId: String,
        scriptId: Long,
        exitCode: Int? = null,
    ) = Unit

    /**
     * 一次运行因超时被终止（需求 §5.1 第 1 条）。
     *
     * @param timeoutMillis 生效阈值
     */
    suspend fun onRunTimeout(
        runId: String,
        scriptId: Long,
        timeoutMillis: Long,
    ) = Unit
}

/**
 * 从 runtime 的退出行取出退出码（阶段 4）。
 *
 * ## 为什么在 `runtime.LogLine` 层取，而不是解析 `domain.LogEntry` 的文案
 * 退出码的**真相源**是 `ShellScriptRuntime.splitMergedOutput` 解析 `__RF_EXIT__` 的结果，
 * 它被发射成一条 **`LogStream.SYS`** 行（文案 `script <id> exited with code <n>`）。
 *
 * | 取值点 | 判别依据 | 是否新增"文案解析点" |
 * |---|---|---|
 * | `RunHistoryWriter`（3a 起既有） | SYS 行**文本正则** | 是（`PROJECT_STATE.md` 偏离项 **D6**） |
 * | **本对象**（阶段 4 新增） | **结构化字段 `stream == SYS`** + 宽松尾匹配 | **否**——不新增依赖文案的解析点 |
 *
 * 文本尾匹配仍然保留，理由是 `SYS` 是**流标签**：runtime 已经用它发"started" / "exec" 等
 * 其它行，只按 `stream == SYS` 取第一条会**取错行**。但正因为多了一层
 * `stream == SYS` 过滤，本处的文本匹配比 D6 那处**更稳固**：
 * 脚本自己的 stdout 无论打印什么，都进不了这条分支
 * （脚本输出只会是 `STDOUT` / `STDERR`——这一点由 `ShellScriptRuntimeTest` 的
 * "stdout 上永远不是 SYS"既有断言保证）。
 */
internal object ExitCodeOnSysLine {
    private val pattern = Regex("""exited with code\s+(-?\d+)\s*$""")

    /**
     * @param line 一行 runtime 日志
     * @return 退出码；该行不是退出行时 `null`
     */
    fun of(line: LogLine): Int? {
        if (line.stream != LogStream.SYS) return null
        return pattern
            .find(line.text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}
