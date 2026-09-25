package com.rootflow.data.log

import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.model.LogTail
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [LogPipeline] 的唯一实现（阶段 2）。
 *
 * ## 数据流
 * ```
 * source: Flow<LogEntry>（已由 data/run/ScriptRunCoordinator 从 runtime 映射而来）
 *   → 逐条写入 RunLogRing（保留最近 N 条，供 tail 取首屏快照）
 *   → 同步 trySend 进内部 Channel（非挂起）
 *   → 独立 flush 协程聚合：满 batchMaxEntries 条 或 距上次 flush ≥ batchFlushIntervalMillis
 *   → LogBatch → MutableSharedFlow → observe(runId)
 * ```
 *
 * ## 背压策略（本项目唯一显式策略，`AGENTS.md` 要求）
 * | 环节 | 策略 |
 * |---|---|
 * | 生产者（source 发射） | **永不阻塞**：写 ring 与 `trySend` 都非挂起，不做任何等待订阅者的操作 |
 * | 订阅通道 | 容量 [subscriberBufferCapacity] + [BufferOverflow.DROP_OLDEST] |
 * | 溢出后果 | 丢最旧的**批次**，条目数累加进后续批次的 `LogBatch.droppedEntries`，**不静默** |
 * | 环形缓冲溢出 | 淘汰最旧**条目**，累计数由 `LogTail.droppedEntries` 暴露 |
 *
 * 之所以选"丢弃"而不是 `SUSPEND`：脚本执行时序是阶段 1c 的核心语义（退出码、进程组、
 * 终止递进都建立在它之上），**UI 卡顿绝不能反压到 runtime**；日志价值随时间衰减，丢旧留新最优。
 *
 * ## 两级序号（订阅者据此自检数据完整性）
 * - **条目序号**：运行内全局单调、从 0 开始，**含已被丢弃批次的条目**——因此
 *   `entries[i].sequence` 的跳号即代表"有批次被丢"
 * - **批次序号**：仅对**成功推送**的批次递增——因此批次序号连续即代表无批次丢失
 *
 * ## 并发与生命周期
 * - [startRun] 对同一 `runId` **幂等**（重复登记被忽略）
 * - **`startRun` 立即返回，不等待收集**（阶段 2 起冻结的契约，阶段 4 **不得**改成 join）：
 *   调用方需要"运行进行中"这一状态可观测（`tail()` / 环形缓冲 / 活跃订阅者数）。
 *   阶段 4 曾试过 `collector.join()` 以让"返回即收尾"，结果一次性打红
 *   `LogPipelineImplTest` 的 8 条用例（`tail honours the limit` / `slow subscriber does not
 *   block the producer` 等**正是**断言"运行中可观测"）—— 那是契约不是缺陷，已回退。
 * - 因此"这次运行已收尾"的唯一可靠信号是 **[LogBatchSink.onRunFinished]**，
 *   它在 [collectRun] 的 `finally` 里、**收集作业内部**被调用（批次与收尾都在此之前完成）
 * - 运行在以下任一时机收尾：`source` 完成 / 抛出异常 / 作用域取消 / [markFinished] / [release]
 * - 运行数超过 [retainedRuns] 时，淘汰**已结束**的最久未活动运行；仍在运行的不会被淘汰
 *
 * ## 落库上下文回调（阶段 3d）
 * [startRun] 可带 [RunMeta] 与 [LogBatchSink]：管道在**每次推送**时同步回调 sink，
 * 从而使落库侧不必订阅热流（`MutableSharedFlow` 无 replay，"先 `startRun` 再订阅"
 * 之间存在丢批窗口）。**收尾批必带 `runFinished = true`**——它是落库侧判断
 * "这次运行结束了"的唯一信号源（阶段 3a 遗留 #2）。
 * 回调异常只记录不上抛（[onWarning]），保证管道自身的收敛不被落库故障破坏。
 *
 * @param scope 管道内部协程作用域（生产为应用级；单测传 `TestScope`）
 * @param dispatcher 驱动 source 与 flush 的调度器；`null` 表示用 [Dispatchers.Default]
 * @param ringCapacity 单次运行的环形缓冲容量（条目数）
 * @param subscriberBufferCapacity 订阅通道容量（批次数）
 * @param batchMaxEntries 单批最大条目数
 * @param batchFlushIntervalMillis 单批最长等待（保证低速脚本不必等满一批）
 * @param retainedRuns 同时保留的运行数上限
 * @param onWarning 告警回调（阶段 3d 新增）：落库回调自身失败时上报。
 *   默认空实现，使阶段 2 的既有直接构造（单测）无需改动。
 */
@OptIn(DelicateCoroutinesApi::class) // Channel.isClosedForReceive：仅用于判断收敛时机
class LogPipelineImpl(
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher? = null,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val subscriberBufferCapacity: Int = DEFAULT_SUBSCRIBER_BUFFER_CAPACITY,
    private val batchMaxEntries: Int = DEFAULT_BATCH_MAX_ENTRIES,
    private val batchFlushIntervalMillis: Long = DEFAULT_BATCH_FLUSH_INTERVAL_MILLIS,
    private val retainedRuns: Int = DEFAULT_RETAINED_RUNS,
    private val onWarning: (String) -> Unit = {},
) : LogPipeline {
    private val dispatcher: CoroutineDispatcher = dispatcher ?: Dispatchers.Default
    private val runs = ConcurrentHashMap<String, RunState>()
    private val runsLock = Mutex()

    override suspend fun startRun(
        runId: String,
        source: Flow<LogEntry>,
        runMeta: RunMeta?,
        batchSink: LogBatchSink?,
    ): Job =
        // ★ 11e 补丁4：整段「查重 → 登记 → 启动 → 回填 collector」必须在**同一把锁**内。
        //   否则幂等分支会读到 collector 尚未回填的中间态，把一个"还没开始"的作业
        //   当成"已在收集"交给调用方 —— 那正是本次要修的那个 bug 的镜像形态。
        runsLock.withLock {
            // 幂等：同一 runId 已在收集 ⇒ 返回**它那一次**的作业。
            // 不能返回一个新建的已完成作业：那会让调用方误判"运行已经结束"。
            runs[runId]?.collector?.let { return@withLock it }

            val state =
                RunState(
                    runId = runId,
                    ring = RunLogRing(ringCapacity),
                    subscriberBufferCapacity = subscriberBufferCapacity,
                    meta = runMeta,
                    sink = batchSink,
                )
            runs[runId] = state
            evictIfNeededLocked()
            val collector =
                scope.launch(dispatcher) {
                    try {
                        collectRun(runId, source, state)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // 源（通常是脚本运行的日志流）自身抛错时**不让它炸掉调用方作用域**：
                        // 管道只负责记录与收敛。脚本失败本身会由 runtime 的 SYS 退出行体现。
                        logSourceFailure(runId, state, error)
                    }
                }
            state.collector = collector
            collector
        }

    override fun observe(runId: String): Flow<LogBatch> = runs[runId]?.batches ?: emptyFlow()

    override suspend fun tail(
        runId: String,
        limit: Int,
    ): LogTail {
        val state = runs[runId] ?: return LogTail(runId = runId, entries = emptyList(), droppedEntries = 0L)
        return LogTail(
            runId = runId,
            entries = state.ring.snapshot(limit),
            droppedEntries = state.ring.droppedCount(),
        )
    }

    override suspend fun markFinished(runId: String) {
        // 显式结束：取消收集协程 → 其 finally 关闭通道 → flusher 优雅收敛并 flush 残量。
        // 正常路径下 source 自行完成即收尾，无需调用本方法。
        runs[runId]?.collector?.cancel()
    }

    override suspend fun release(runId: String) {
        val state = runs.remove(runId)
        state?.collector?.cancel()
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 超出 [retainedRuns] 时淘汰最久未活动的**已结束**运行。
     *
     * ⚠️ **调用方必须已持有 [runsLock]**：11e 补丁4 起由 [startRun] 在锁内调用。
     * 它此前自带 `withLock`，若沿用会与新写的"锁内登记"**自锁死**（Mutex 不可重入）。
     */
    private fun evictIfNeededLocked() {
        while (runs.size > retainedRuns) {
            val victim =
                runs.values
                    .filter { it.finished.get() }
                    .minByOrNull { it.lastActivity }
                    ?: break
            runs.values.remove(victim)
            victim.collector?.cancel()
        }
    }

    /**
     * 收集一次运行。
     *
     * 用 `withContext` 构造**结构化并发**：flusher 作为子协程与 source 收集并行，
     * 且 `withContext` 会等两者都结束才返回——因此 `startRun` 返回（工作协程结束）时，
     * 该运行的批次**已全部推送完毕**，不存在"最后一批还在路上"的竞态。
     */
    private suspend fun collectRun(
        runId: String,
        source: Flow<LogEntry>,
        state: RunState,
    ) {
        try {
            withContext(dispatcher) {
                val flusher = launch { flushLoop(runId, state) }

                launch {
                    try {
                        source.collect { mapped ->
                            val entry =
                                mapped.copy(
                                    runId = runId,
                                    sequence = state.nextSequence(),
                                )
                            state.ring.append(entry)
                            state.touch()
                            // 非挂起：生产者永不因订阅者慢而阻塞（背压硬约束）。
                            state.entries.trySend(entry)
                        }
                    } finally {
                        // 正常结束、异常、取消三种路径都在此关闭通道 → flusher 优雅收敛。
                        state.entries.close()
                    }
                }

                flusher.join()
            }
        } finally {
            // 到这里 flusher 必已结束（withContext 等齐所有子协程），最后一批已推送，
            // 因此可以安全移除 state 而不产生"最后一刻订阅到空 flow"的竞态。
            state.finished.set(true)
            state.stopped.complete(Unit)
            // 先通知落库侧收尾，再移除 state：取消路径上"最后一批 runFinished=true"可能
            // 根本发不出去（通道已断），落库侧必须仍能收到"结束"信号，否则
            // `runs.finished_at` 永远为 null（阶段 3a 遗留 #2 的正面要求）。
            state.sink?.onRunFinished(runId, state.meta)
            runs.remove(runId, state)
        }
    }

    /** 记录源流自身的故障；不重新抛出（见 [startRun] 中的说明）。 */
    private fun logSourceFailure(
        runId: String,
        state: RunState,
        error: Throwable,
    ) {
        val description = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.name
        val entry =
            LogEntry(
                runId = runId,
                sequence = state.nextSequence(),
                timestamp = System.currentTimeMillis(),
                stream = LogStream.SYS,
                text = "log pipeline: source FAILED ($description)",
            )
        state.ring.append(entry)
        emitBatch(
            state = state,
            entries = listOf(entry),
            runFinished = true,
        )
    }

    /**
     * 聚合与推送。
     *
     * 两段式：先等一条（最多 [batchFlushIntervalMillis]，超时即说明"该 flush 了"），
     * 再尽量攒到 [batchMaxEntries] 条（同样受时间上限约束）。
     *
     * **退出条件只有"通道关闭"一个**：用 `onReceiveCatching` 感知关闭并 break，
     * 而不是 `select { stopped.onAwait }`。原因是后者会让空闲时反复因超时唤醒，
     * 在 `runTest` 的虚拟时间下形成"每次唤醒都推进时钟"的活锁（`advanceUntilIdle` 永不返回）。
     * 通道由收集协程的 `finally` 关闭，覆盖正常结束 / 异常 / 取消三条路径。
     *
     * 结束方式为**优雅收敛**：关闭时 break 并 flush 残量。若改用 `receive()`，
     * 通道关闭会抛 `ClosedReceiveChannelException`，使 flusher 以异常收场并污染收集协程。
     *
     * **收尾那批必须带 `runFinished = true`**（阶段 3d 修正）：管道是落库侧判断
     * "这次运行结束了"的**唯一信号源**，缺了它 `runs.finished_at` 永远是 null
     * （阶段 3a 遗留 #2 / 3d 方案 §6）。收尾批即使没有新条目也要推送——
     * 否则"末批在更早的一次 flush 里已经出去"的运行收不到结束标记。
     */
    private suspend fun flushLoop(
        runId: String,
        state: RunState,
    ) {
        val pending = mutableListOf<LogEntry>()
        var closed = false

        while (!closed) {
            val first =
                withTimeoutOrNull(batchFlushIntervalMillis) {
                    state.entries.receiveCatching().getOrNull()
                }
            if (first == null) {
                // 两种可能：攒批超时（把已攒的推出去）或通道已关闭（置 closed 退出）。
                if (state.entries.isClosedForReceive) {
                    closed = true
                } else if (pending.isNotEmpty()) {
                    flush(runId, state, pending, runFinished = false)
                }
            } else {
                pending += first
                withTimeoutOrNull(batchFlushIntervalMillis) {
                    while (pending.size < batchMaxEntries) {
                        val next = state.entries.tryReceive().getOrNull() ?: break
                        pending += next
                    }
                }
                flush(runId, state, pending, runFinished = false)
            }
        }

        // 收尾：把通道里可能残留的条目一并推出（提前结束路径下可能有未消费部分）。
        while (true) {
            val remaining = state.entries.tryReceive().getOrNull() ?: break
            pending += remaining
        }
        emitBatch(state = state, entries = pending.toList(), runFinished = true)
        pending.clear()
    }

    /**
     * 推送一批（非收尾批）。
     *
     * `sequence` 只在**成功推送**时递增——批次序号连续即代表无批次丢失；
     * 推送失败时把本批条目数记入丢弃计数，由下一批携带出去（不静默丢数据）。
     */
    private fun flush(
        runId: String,
        state: RunState,
        pending: MutableList<LogEntry>,
        runFinished: Boolean,
    ) {
        if (pending.isEmpty()) return
        emitBatch(state = state, entries = pending.toList(), runFinished = runFinished)
        pending.clear()
    }

    /**
     * 推送一批并同步通知落库侧（阶段 3d）。
     *
     * ## 为什么 [LogBatchSink.onBatch] 在 `tryEmit` 之外单独调用
     * `MutableSharedFlow.tryEmit` 在**没有订阅者**时也返回 `true`（批次被直接丢弃），
     * 因此不能用它的返回值判断"落库侧是否收到"。上下文回调是**独立通道**，
     * 与订阅者数量无关——这正是 3d 用它而不是让落库侧订阅热流的原因。
     *
     * ## 为什么批次序号用原子量
     * 本方法可能被**收集协程**（源故障路径的收尾批）与 **flusher 协程**调用，
     * 普通 `var` 会有竞态（两个批次拿到同一序号）。
     *
     * ## 回调解耦（硬约束：落库绝不反压脚本执行）
     * 回调异常**不得**破坏管道自身的收敛：捕获后只记录，绝不上抛。
     * 回调本身由实现方保持轻量（见 [LogBatchSink] 的 KDoc）。
     */
    private fun emitBatch(
        state: RunState,
        entries: List<LogEntry>,
        runFinished: Boolean,
    ) {
        val sequence = state.nextBatchSequence()
        val batch =
            LogBatch(
                runId = state.runId,
                sequence = sequence,
                entries = entries,
                droppedEntries = state.droppedEntries(),
                runFinished = runFinished,
            )
        state.sink?.let { sink ->
            try {
                sink.onBatch(state.meta, batch)
            } catch (error: Throwable) {
                onSinkFailure(state.runId, error)
            }
        }
        if (entries.isNotEmpty() && !state.batches.tryEmit(batch)) {
            state.addDropped(entries.size)
            state.sink?.let { sink ->
                try {
                    sink.onBatchesDropped(entries.size, state.meta)
                } catch (error: Throwable) {
                    onSinkFailure(state.runId, error)
                }
            }
        }
    }

    /**
     * 记录落库回调自身的故障。
     *
     * 管道不认识 `android.util.Log`（它是纯逻辑，单测在 JVM 下跑），
     * 因此经 [onWarning] 这条可注入的缝上报——**不静默**。
     */
    private fun onSinkFailure(
        runId: String,
        error: Throwable,
    ) {
        onWarning("log pipeline: batch sink failed for run $runId: ${error.message ?: error::class.java.name}")
    }

    /** 每次运行的可变状态；字段跨协程访问，故用原子量。 */
    private class RunState(
        val runId: String,
        val ring: RunLogRing,
        subscriberBufferCapacity: Int,
        val meta: RunMeta?,
        val sink: LogBatchSink?,
    ) {
        val batches: MutableSharedFlow<LogBatch> =
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = subscriberBufferCapacity,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** 收集协程 → flush 协程的交接通道。无界：背压由 ring + 批次策略承担，不用通道容量。 */
        val entries: Channel<LogEntry> = Channel(capacity = Channel.UNLIMITED)

        /** 显式结束信号（`markFinished` / `release` / 淘汰）。 */
        val stopped: CompletableDeferred<Unit> = CompletableDeferred()

        /** 收集协程句柄，供 `release` / 淘汰时取消。 */
        @Volatile
        var collector: Job? = null

        val finished: AtomicBoolean = AtomicBoolean(false)

        @Volatile
        var lastActivity: Long = System.currentTimeMillis()

        private val dropped = AtomicLong(0)

        /** 批次序号发生器（仅对成功推送的批次递增；两个协程都会调用，故用原子量）。 */
        private val batchSequence = AtomicLong(0)

        /** 运行内条目序号发生器；与"实际入 ring 的条数"一致，不受环形淘汰影响。 */
        private val sequence = AtomicLong(0)

        fun nextSequence(): Long = sequence.getAndIncrement()

        fun nextBatchSequence(): Long = batchSequence.getAndIncrement()

        fun touch() {
            lastActivity = System.currentTimeMillis()
        }

        fun addDropped(count: Int) {
            dropped.addAndGet(count.toLong())
        }

        fun droppedEntries(): Long = dropped.get()
    }

    companion object {
        /** 单次运行的环形缓冲容量（条目数）。2000 × ~200B ≈ 400KB/run。 */
        const val DEFAULT_RING_CAPACITY: Int = 2_000

        /** 订阅通道容量（批次数）。 */
        const val DEFAULT_SUBSCRIBER_BUFFER_CAPACITY: Int = 64

        /** 单批最大条目数。 */
        const val DEFAULT_BATCH_MAX_ENTRIES: Int = 200

        /** 单批最长等待（毫秒）。 */
        const val DEFAULT_BATCH_FLUSH_INTERVAL_MILLIS: Long = 100L

        /** 同时保留的运行数上限。 */
        const val DEFAULT_RETAINED_RUNS: Int = 4
    }
}
