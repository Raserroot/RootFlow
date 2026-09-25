package com.rootflow.domain.repository

import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogTail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/**
 * 一次运行的元信息中**管道本身不掌握**的部分（阶段 3d 的接线参数）。
 *
 * 管道的 `LogBatch` 只有 `runId` / 条目 / 丢弃计数，而"哪个脚本、什么事件触发的"
 * 只在执行侧才有（`scriptId` + `RunContext.triggerEvent`）。
 *
 * ## 为什么在 `domain` 而不是 `data`
 * 它是 [LogPipeline.startRun] 的形参，而 [LogPipeline] 是 `domain` 端口。
 * 若把类型留在 `data`，`domain` 的接口签名就会反向依赖 `data`——违反
 * `AGENTS.md` 的 `ui → domain → data / runtime` 依赖方向。
 * 本类型是纯数据（两个字段、无行为），天然属 `domain`。
 *
 * ## 为什么随 `startRun` 一次传入（而不是事后注册映射）
 * **无竞态**：运行元与 `source` 在同一次调用里交给管道，收集侧不存在
 * "批次先到、元信息还没登记"的窗口。事后注册 `runId → meta` 映射（3d 方案 §8 的候选 C）
 * 会有该竞态，需加锁或延迟，复杂度更高。
 *
 * @property scriptId 归属脚本
 * @property triggerEvent 与 `RunContext.triggerEvent` 一致；`null` = 手动/自检触发
 */
data class RunMeta(
    val scriptId: Long,
    val triggerEvent: String?,
)

/**
 * 管道上下文回调（阶段 3d）。
 *
 * 存在的唯一目的：让落库侧（`data/run/RunHistoryCollector`）**按推送顺序**收到
 * 带元信息的批次，而不必自行订阅 `MutableSharedFlow`——那是热流，
 * "先 `startRun` 再订阅"之间存在丢批窗口。
 *
 * 实现方（`LogPipelineImpl`）保证：
 * - [onBatch] 对**每一批**实际推送的批次调用一次，顺序与推送顺序一致
 * - [onRunFinished] 恰在收尾时调用一次（正常结束 / 源异常 / 取消三条路径）
 * - [onBatchesDropped] 仅在订阅通道溢出、确实丢批时调用（**不静默**）
 *
 * 回调在管道的工作协程内**同步**执行，因此实现方**必须**保持轻量：
 * 慢回调会推迟 `source` 的收集——而"UI/落库不得反压脚本执行"是本项目的硬约束。
 * 落库实现用"有界队列 + 溢出丢弃 + 计数"衔接（见 `RunHistoryCollector`）。
 */
interface LogBatchSink {
    /** 一批已推送的日志（`batch.runFinished` 为 `true` 表示这是最后一批）。 */
    fun onBatch(
        meta: RunMeta?,
        batch: LogBatch,
    )

    /**
     * 运行收尾（正常结束 / 源异常 / 取消三条路径**各恰一次**）。
     *
     * **必须与批次独立**：取消路径上"`runFinished = true` 的收尾批"可能根本发不出去
     * （订阅通道已断），若收尾只靠批次携带，`runs.finished_at` 会永远为 null。
     */
    fun onRunFinished(
        runId: String,
        meta: RunMeta?,
    ) = Unit

    /** 订阅通道溢出导致 [count] 条目被丢弃（管道级丢弃，与行数上限截断区分开）。 */
    fun onBatchesDropped(
        count: Int,
        meta: RunMeta?,
    ) = Unit
}

/**
 * 日志管道（阶段 2）。
 *
 * ## 职责
 * 把 `runtime` 产出的一次性日志流，汇聚成**有界、不阻塞生产者、可被多个订阅者消费**的
 * 批次流：
 *
 * 1. **环形缓冲**：每次运行保留最近 N 条（容量见实现），供 [tail] 取首屏快照
 * 2. **批量 flush**：满 N 条或距上次 flush ≥ T 毫秒时推送一批，避免逐行驱动 UI
 * 3. **背压策略（显式）**：内部通道容量固定 + **溢出丢弃最旧的批次**；
 *    **绝不反压生产者**——UI 卡顿不得拖慢脚本执行（脚本执行时序是阶段 1c 的核心语义）
 * 4. **丢弃可见**：任何丢弃都计入 [LogBatch.droppedEntries]，不静默丢数据
 *
 * ## 与 `runtime.Flow<LogLine>` 的关系
 * 本端口**不认识** `runtime` 的任何类型——入参是已映射好的 `Flow<LogEntry>`
 * （映射由 `data/log/LogMappers.kt` 负责）。这样 `domain` 的类型契约与
 * `runtime` 的实现细节解耦。
 *
 * ## 收集者唯一性（重要）
 * `runtime.RunHandle.output` 是**一次性冷流**，只能被收集一次。因此约定：
 * **每个 RunHandle 有且只有一个收集者**——由 `data/run/ScriptRunCoordinator` 负责
 * 收集并调用 [startRun] 馈入本管道。UI / ViewModel **不得**直接收集 `RunHandle.output`，
 * 只能订阅 [observe]。
 *
 * ## 序号约定
 * [startRun] 的 `source` 产出的 [LogEntry] 其 `sequence` 字段**会被实现覆盖**为
 * 该运行内的单调序号（从 0 开始）。映射函数只负责 `timestamp` / `stream` / `text` / `runId`。
 */
interface LogPipeline {
    /**
     * 开始接收一次运行的日志。
     *
     * **立即返回**，收集在返回的作业里进行；`source` 正常结束、抛出异常、或所在作用域
     * 被取消时，该运行会被自动收尾（发最后一批 `runFinished = true`）。
     *
     * 对同一 [runId] 重复调用是**幂等**的：第二次起被忽略（避免重复缓冲与重复批次），
     * 并返回**第一次**那次收集的作业。
     *
     * ## ★ 为什么必须把作业交出来（11e 补丁4 修的真缺陷）
     * "这次运行结束了"的唯一可靠信号在**收集作业内部**（见 `LogBatchSink.onRunFinished`），
     * 而"立即返回"意味着调用方**拿不到**它 —— 于是调用方若把运行收尾的钩子挂在
     * `startRun` 那次调用上，钩子会在运行**刚开始**时就触发。
     *
     * 生产上正是这样炸的：`ScriptRunCoordinator` 把它包在 `scope.launch { … }` 里，
     * 那个 `job` 随 `startRun` 返回而完成，于是**注销运行 / 释放闸门 / 关事件通道 /
     * 取消超时看门狗 / 记存活时长**全部提前执行 —— 常驻脚本被误判成
     * "活了 0 毫秒、连崩 5 次"（真机日志 `DAEMON_EXITED runMillis=0` ×5 → `DAEMON_GIVEN_UP`），
     * 而脚本进程其实一直在跑。
     *
     * ⚠️ **不得**把本方法改成"等收集完再返回"（`collector.join()`）：那会打红
     * `LogPipelineImplTest` 的一批既有断言（`tail honours the limit` /
     * `slow subscriber does not block the producer` 等**正是**断言"运行中可观测"）。
     * **交出作业**与**立即返回**两者同时成立，才是正确形态。
     *
     * @param runId 运行标识；**必须**与 `RunHandle.id` 一致 —— 否则 `terminateRun`
     *   按 runId 找不到 `.rf_pgid_<id>` 文件，超时/熔断的"杀进程"会变成空操作
     * @param source 已映射的日志来源；通常由 `handle.output.map { … }` 得到
     * @param runMeta 运行元信息；供 [batchSink] 带上 `scriptId` / `triggerEvent`
     *   （阶段 3d 的落库需要，管道自身不使用）
     * @param batchSink 推送回调；`null` 表示无落库订阅者（例如阶段 2 的既有调用方与单测）
     * @return 承载本次收集的作业；它在 `source` 走完、flusher 收敛、收尾批推送**之后**才完成
     */
    suspend fun startRun(
        runId: String,
        source: Flow<LogEntry>,
        runMeta: RunMeta? = null,
        batchSink: LogBatchSink? = null,
    ): Job

    /**
     * 订阅某次运行的批次流。
     *
     * 热流：只推送订阅**之后**产生的批次，不含历史（历史用 [tail] 取）。
     * 多个订阅者各自独立收到相同的增量。
     *
     * @param runId 运行标识
     */
    fun observe(runId: String): Flow<LogBatch>

    /**
     * 取某次运行的日志快照（环形缓冲现存内容），用于首屏渲染与旋转回填。
     *
     * @param runId 运行标识
     * @param limit 最多返回多少条（取**最新**的 limit 条）；`limit <= 0` 表示不限（受环形容量约束）
     * @return 快照；该运行不存在时返回空 entries 的 [LogTail]
     */
    suspend fun tail(
        runId: String,
        limit: Int = 0,
    ): LogTail

    /**
     * 标记某次运行已结束。
     *
     * 供"脚本执行与日志收集由不同组件负责"的场景显式收尾；正常路径下
     * `source` 完成即自动收尾，无需调用。重复调用无副作用。
     *
     * @param runId 运行标识
     */
    suspend fun markFinished(runId: String)

    /**
     * 释放某次运行的缓冲与订阅通道（内存回收）。
     *
     * 与 [markFinished] 的区别：本方法会**移除**该运行的数据，之后 [tail] 返回空。
     *
     * @param runId 运行标识
     */
    suspend fun release(runId: String)
}
