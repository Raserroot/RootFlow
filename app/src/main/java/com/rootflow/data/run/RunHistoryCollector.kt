package com.rootflow.data.run

import android.util.Log
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.RunMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把日志管道推来的批次落到运行历史（阶段 3d）——**落库侧的唯一订阅者**。
 *
 * ## 它在链路中的位置
 * ```
 * LogPipelineImpl.flush / flushLoop / logSourceFailure
 *      └─LogBatchSink.onBatch──▶ 本类 ──有界队列（容量 256）──▶ 消费者协程 ──▶ RunHistoryWriter
 * ```
 *
 * ## 为什么经队列而不是直接写库（硬约束：落库绝不反压脚本执行）
 * [LogBatchSink] 的回调在管道的**收集/推送协程内同步执行**。若在其中直接调用
 * `RunHistoryWriter.record`（Room 写库，`suspend`），管道就被 DB 耗时拖住了——
 * 而 `AGENTS.md` 与阶段 2 的背压策略都写明"**UI 卡顿 / 落库慢绝不反压 runtime**"。
 * 因此回调只做一次 `trySend`（非挂起），写库由独立消费者协程完成。
 *
 * ## 队列满怎么办：**丢最旧 + 计数 + 告警**（与管道同款纪律）
 * - 丢弃的**条数**由 `LogBatch.droppedEntries`（管道自己的记录）承担，最终落进
 *   `runs.dropped_log_entries` —— **不静默丢数据**
 * - 丢弃的**批次数**进 [droppedBatchCount]，供单测与真机判读
 * - 队列溢出必然连带丢掉"结束标记"（收尾批在队尾），因此**必须**有一条不依赖批次的
 *   收尾路径：`LogBatchSink.onRunFinished`，管道在收尾时**无条件**调用它
 *   （取消路径上收尾批可能根本发不出去）
 *
 * ## 为什么 `scriptId` / `triggerEvent` 来自 `runMeta`（3d 方案 §8 的候选 A）
 * 运行元信息与日志源在**同一次** `startRun` 调用里交给管道，由管道随每个批次回调带出来。
 * **无竞态**——不存在"批次先到、元信息还没登记"的窗口（候选 C 就有该竞态）。
 * `runMeta` 缺失时（阶段 2 的既有直接调用方、手动自检）按"未知脚本 `-1`"落库并**告警**，
 * 而不是把整次运行的日志丢掉。
 */
@Singleton
class RunHistoryCollector
    @Inject
    constructor(
        private val writer: RunHistoryWriter,
        private val scope: CoroutineScope,
    ) : LogBatchSink {
        /** 单测缝：注入较小的队列容量以覆盖溢出分支。 */
        internal constructor(
            writer: RunHistoryWriter,
            scope: CoroutineScope,
            queueCapacity: Int,
        ) : this(writer = writer, scope = scope) {
            this.queueCapacityOverride = queueCapacity
        }

        private var queueCapacityOverride: Int? = null

        private val queue: Channel<Pending> by lazy {
            Channel(capacity = queueCapacityOverride ?: DEFAULT_QUEUE_CAPACITY)
        }

        /** 消费者协程只启动一次；由 [startLock] 双检保护。 */
        private val consumerStarted = AtomicBoolean(false)

        /**
         * 启动消费者用的锁。
         *
         * 用 `Any()` 而非协程 `Mutex`：[onBatch] 是**同步回调**（管道不允许挂起它），
         * 在里面 `Mutex.withLock` 需要 `runBlocking`，会阻塞管道线程。同步块足够：
         * 临界区只有"置标记 + launch"。
         */
        private val startLock = Any()

        /** 因队列溢出被丢弃的**批次数**（真机判读 + 单测断言用）。 */
        private val droppedBatches = AtomicLong(0)

        internal val droppedBatchCount: Long
            get() = droppedBatches.get()

        override fun onBatch(
            meta: RunMeta?,
            batch: LogBatch,
        ) {
            enqueue(Pending.Batch(meta = meta, batch = batch))
        }

        override fun onRunFinished(
            runId: String,
            meta: RunMeta?,
        ) {
            enqueue(Pending.Finished(runId = runId, meta = meta))
        }

        override fun onBatchesDropped(
            count: Int,
            meta: RunMeta?,
        ) {
            // 管道已把丢弃条数记进 `LogBatch.droppedEntries`（由后续批次携带），
            // 因此这里不重复计数——只把"发生过管道级丢弃"这件事实记进日志。
            Log.w(
                TAG,
                "RUN_HISTORY_PIPELINE_DROPPED count=$count script=${meta?.scriptId ?: UNKNOWN_SCRIPT_ID} " +
                    "(subscriber channel overflow; history will be incomplete)",
            )
        }

        // ------------------------------------------------------------------ 内部

        private fun enqueue(pending: Pending) {
            ensureConsumer()
            if (queue.trySend(pending).isSuccess) return

            // 队列满：丢**最旧**的（与管道的 DROP_OLDEST 一致——旧日志价值随时间衰减）。
            // 只对"批次"计数：结束信号被丢不做条目计数（它是标记，不是数据），
            // 但**必须**在日志里可见，否则"运行没有 finished_at"会无从解释。
            val evicted = queue.tryReceive().getOrNull()
            countDropped(evicted)
            if (queue.trySend(pending).isFailure) {
                // 腾位后仍失败（容量为 0 的极端注入）→ 如实计数，不静默
                countDropped(pending)
            }
            Log.w(
                TAG,
                "RUN_HISTORY_QUEUE_OVERFLOW droppedBatches=${droppedBatches.get()} " +
                    "(history will be incomplete)",
            )
        }

        private fun countDropped(pending: Pending?) {
            when (pending) {
                is Pending.Batch -> droppedBatches.incrementAndGet()
                is Pending.Finished ->
                    Log.w(
                        TAG,
                        "RUN_HISTORY_FINISH_MARKER_DROPPED runId=${pending.runId} " +
                            "(queue overflow; runs.finished_at may stay null)",
                    )

                null -> Unit
            }
        }

        /**
         * 启动消费者（**只启动一次**）。
         *
         * ## 为什么必须双检加锁
         * `onBatch` 可能来自**不同运行的不同协程**（并发脚本各自一个收集协程）。
         * check-then-act 的竞态会启动两个消费者，而两个消费者会**并发写同一个 runId 的行**
         * ——`RunHistoryWriter` 的 `written` 计数是普通 `Map`，并发会互相覆盖。
         */
        private fun ensureConsumer() {
            if (consumerStarted.get()) return
            synchronized(startLock) {
                if (consumerStarted.get()) return
                consumerStarted.set(true)
                scope.launch { drain() }
            }
        }

        private suspend fun drain() {
            for (pending in queue) {
                try {
                    when (pending) {
                        is Pending.Batch -> writeBatch(pending)

                        is Pending.Finished -> {
                            // 队列溢出丢掉的是"条目"，不是"运行已结束"这件事实：
                            // 结束必须落库，否则 `runs.finished_at` 永远为 null（3a 遗留 #2）。
                            writer.finish(pending.runId)
                            Log.i(
                                TAG,
                                "RUN_FINISHED runId=${pending.runId} " +
                                    "script=${(pending.meta ?: unknownMeta(pending.runId)).scriptId}",
                            )
                        }
                    }
                } catch (error: Throwable) {
                    // 写库失败不得让消费者协程死掉：后续批次仍应继续尝试。
                    // 某一次运行的历史缺失，好过整条落库链路停摆。
                    Log.w(TAG, "RUN_HISTORY_WRITE_FAILED: ${error.message ?: error::class.java.name}")
                }
            }
        }

        private suspend fun writeBatch(pending: Pending.Batch) {
            val batch = pending.batch
            val meta = pending.meta ?: unknownMeta(batch.runId)
            writer.record(
                RunHistoryBatch(
                    runId = batch.runId,
                    meta = meta,
                    entries = batch.entries,
                    droppedEntries = batch.droppedEntries,
                    runFinished = batch.runFinished,
                ),
            )
            Log.i(
                TAG,
                "RUN_HISTORY_WRITTEN runId=${batch.runId} script=${meta.scriptId} " +
                    "event=${meta.triggerEvent ?: "-"} entries=${batch.entries.size} " +
                    "dropped=${batch.droppedEntries} finished=${batch.runFinished}",
            )
        }

        private fun unknownMeta(runId: String): RunMeta {
            Log.w(
                TAG,
                "RUN_HISTORY_NO_META runId=$runId: batch arrived without runMeta; " +
                    "recording as unknown script ($UNKNOWN_SCRIPT_ID)",
            )
            return RunMeta(scriptId = UNKNOWN_SCRIPT_ID, triggerEvent = null)
        }

        /** 队列中的一项。 */
        private sealed interface Pending {
            /** 元信息随批次一起传递（候选 A：无竞态）。 */
            val meta: RunMeta?

            /** 一批已推送的日志。 */
            data class Batch(
                override val meta: RunMeta?,
                val batch: LogBatch,
            ) : Pending

            /**
             * 收尾信号。
             *
             * **独立于批次**：取消路径上 `runFinished = true` 的收尾批可能根本发不出去，
             * 若收尾只靠批次携带，`runs.finished_at` 会永远为 null。
             */
            data class Finished(
                val runId: String,
                override val meta: RunMeta?,
            ) : Pending
        }

        internal companion object {
            const val TAG: String = "RootFlow"

            /** 落库队列容量（批次数）。256 批 ≈ 51200 条（按默认 200 条/批）。 */
            const val DEFAULT_QUEUE_CAPACITY: Int = 256

            /** `runMeta` 缺失时使用的未知脚本标识（负数即非真实脚本）。 */
            const val UNKNOWN_SCRIPT_ID: Long = -1L
        }
    }
