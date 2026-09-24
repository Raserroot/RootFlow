package com.rootflow.data.log

import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LogPipelineImpl] 单测（阶段 2 清单 4–10、12、13）。
 *
 * ## 关于虚拟时间
 * 管道内部作用域与调度器都指向 `runTest` 的 `TestScope` / `StandardTestDispatcher(testScheduler)`，
 * 因此 `batchFlushIntervalMillis` 的超时走**虚拟时间**：`advanceUntilIdle()` 瞬间推进到
 * 下一个到期定时器，无需真实等待。
 *
 * 注：`LogPipelineImpl.dispatcher` 形参类型是 `CoroutineDispatcher`（非 `TestDispatcher`），
 * 故传 `StandardTestDispatcher(testScheduler)` —— 它本身是 `CoroutineDispatcher`，
 * 且与 `runTest` 共用同一调度器与虚拟时钟。
 *
 * ## 两条决定断言写法的语义
 * 1. **`startRun` 的工作协程结束即移除该运行的 state**（回收内存）。因此 [LogPipeline.tail]
 *    只能在**运行进行中**查询；运行结束后再查必然为空（历史留存属阶段 3 持久化范围）。
 *    需要"运行持续中"的用例，用 [firstThenDelay] 让源在中途挂起。
 * 2. **订阅者放 `backgroundScope`**：`SharedFlow` 永不结束，放前台会让 `runTest` 挂住。
 *
 * ## 关于"丢弃"的观测口径
 * `MutableSharedFlow` 的 `DROP_OLDEST` 在溢出时**仍返回 `true`**（本次发射确实被接收，
 * 只是挤掉了最旧的一条）。因此**不能**用 `tryEmit` 返回值判断丢批，只能用
 * **批次序号是否连续**判断 —— 这正是把 `sequence` 放进 [LogBatch] 的用途。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogPipelineImplTest {
    @Test
    fun `batch honours the max size and the run is queryable while it is running`() =
        runTest {
            val pipeline = newPipeline()
            val batches = mutableListOf<LogBatch>()
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(batches) }

            // 300 条后挂起：保证运行仍进行中，可查 tail。
            pipeline.startRun(RUN_ID, firstThenDelay(total = 400, firstBatch = 300, inBetweenMillis = 60_000))
            runCurrent()

            assertTrue(batches.isNotEmpty(), "至少应推出一批")
            assertEquals(
                LogPipelineImpl.DEFAULT_BATCH_MAX_ENTRIES,
                batches.first().entries.size,
                "单批不得超过上限",
            )
            val running = pipeline.tail(RUN_ID)
            assertTrue(running.entries.isNotEmpty(), "运行进行中 tail 必须能查到日志")
            assertEquals(
                List(300) { it.toLong() },
                running.entries.map { it.sequence },
                "条目序号必须是运行内连续的 0..299",
            )
        }

    @Test
    fun `flushes on time even when the batch is not full`() =
        runTest {
            val pipeline = newPipeline()
            val batches = mutableListOf<LogBatch>()
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(batches) }

            // 每条间隔 150ms（> 100ms 的 flush 间隔）→ 每条都靠超时独立成批。
            pipeline.startRun(RUN_ID, trickle(count = 5, intervalMillis = 150))
            advanceUntilIdle()

            assertEquals(5, batches.sumOf { it.entries.size }, "低速脚本的行必须靠超时送出，不能一直攒着")
            assertTrue(batches.size >= 2, "应产生多个超时批次：实际 ${batches.size}")
        }

    @Test
    fun `batch sequence increases without gaps`() =
        runTest {
            val pipeline = newPipeline()
            val batches = mutableListOf<LogBatch>()
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(batches) }

            pipeline.startRun(RUN_ID, trickle(count = 6, intervalMillis = 150))
            advanceUntilIdle()

            assertEquals(6, batches.sumOf { it.entries.size })
            assertEquals(
                List(batches.size) { it.toLong() },
                batches.map { it.sequence },
                "批次序号必须从 0 连续递增（订阅者据此判断有无丢批）",
            )
        }

    @Test
    fun `no subscriber still lets the producer finish and leaves the ring filled`() =
        runTest {
            val pipeline = newPipeline()
            pipeline.startRun(RUN_ID, firstThenDelay(total = 200, firstBatch = 200, inBetweenMillis = 60_000))
            runCurrent()

            val tail = pipeline.tail(RUN_ID)
            assertEquals(200, tail.entries.size, "无订阅者时生产者仍必须完整跑完并留下缓冲")
            assertEquals(
                List(200) { it.toLong() },
                tail.entries.map { it.sequence },
            )
        }

    @Test
    fun `slow subscriber does not block the producer`() =
        runTest {
            // 极端背压：批大小 = 1（每条一批）× 400 条，远超订阅通道容量 64。
            val pipeline = newPipeline(batchMaxEntries = 1)
            val received = mutableListOf<LogBatch>()
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(received) }

            // 源在中途挂起 → 生产者在"订阅者只消费了一部分"时仍未阻塞。
            pipeline.startRun(RUN_ID, firstThenDelay(total = 400, firstBatch = 400, inBetweenMillis = 60_000))
            runCurrent()

            val running = pipeline.tail(RUN_ID)
            assertEquals(400, running.entries.size, "生产者必须跑完全部 400 条（UI 慢绝不反压 runtime）")

            // 丢弃（若发生）必须可观测：批次序号出现跳号，且保留的是最新批次。
            val sequences = received.map { it.sequence }
            var hasGap = false
            var expected = 0L
            for (sequence in sequences) {
                if (sequence != expected) hasGap = true
                expected = sequence + 1
            }
            if (hasGap) {
                assertEquals(399L, sequences.last(), "丢弃必须是 drop-oldest：最后一条必须是最新序号")
            }
        }

    @Test
    fun `every subscriber receives the same batches`() =
        runTest {
            val pipeline = newPipeline()
            val first = mutableListOf<LogBatch>()
            val second = mutableListOf<LogBatch>()
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(first) }
            backgroundScope.launch { pipeline.observe(RUN_ID).toList(second) }

            pipeline.startRun(RUN_ID, trickle(count = 5, intervalMillis = 150))
            advanceUntilIdle()

            assertEquals(5, first.sumOf { it.entries.size })
            assertEquals(5, second.sumOf { it.entries.size })
            assertEquals(first.map { it.sequence }, second.map { it.sequence })
        }

    @Test
    fun `keeps the newest entries in the ring and reports how many rolled out`() =
        runTest {
            val pipeline = newPipeline(ringCapacity = 10)
            pipeline.startRun(RUN_ID, firstThenDelay(total = 50, firstBatch = 50, inBetweenMillis = 60_000))
            runCurrent()

            val tail = pipeline.tail(RUN_ID)
            assertEquals(10, tail.entries.size, "环形缓冲只保留最新 10 条")
            assertEquals(
                listOf(40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L),
                tail.entries.map { it.sequence },
            )
            assertEquals(40L, tail.droppedEntries, "滚出的 40 条必须可见")
        }

    @Test
    fun `tail honours the limit by returning the newest entries`() =
        runTest {
            val pipeline = newPipeline()
            pipeline.startRun(RUN_ID, firstThenDelay(total = 50, firstBatch = 50, inBetweenMillis = 60_000))
            runCurrent()

            val tail = pipeline.tail(RUN_ID, limit = 3)
            assertEquals(listOf(47L, 48L, 49L), tail.entries.map { it.sequence })
        }

    @Test
    fun `source failure does not blow up the caller scope`() =
        runTest {
            val pipeline = newPipeline()
            val failing =
                flow<LogEntry> {
                    emit(entry(0))
                    throw IllegalStateException("boom")
                }

            // 源抛异常时，收集协程的 finally 仍会关闭通道并回收 state；
            // 异常被 catch 分支吸收，**不得**冒泡到调用方作用域（否则会炸掉调度器）。
            pipeline.startRun(RUN_ID, failing)
            advanceUntilIdle()

            // 能推进到此处而不抛出，即为核心断言；且故障不得让后续运行受影响。
            val next = newPipeline()
            next.startRun(RUN_ID, firstThenDelay(total = 3, firstBatch = 3, inBetweenMillis = 60_000))
            runCurrent()
            assertEquals(3, next.tail(RUN_ID).entries.size, "前一次源故障不得影响后续运行")
        }

    @Test
    fun `releasing a run stops collection and drops its buffered data`() =
        runTest {
            val pipeline = newPipeline()
            pipeline.startRun(RUN_ID, firstThenDelay(total = 50, firstBatch = 50, inBetweenMillis = 60_000))
            runCurrent()
            assertTrue(pipeline.tail(RUN_ID).entries.isNotEmpty(), "释放前应已有缓冲内容")

            pipeline.release(RUN_ID)
            advanceUntilIdle()

            assertTrue(pipeline.tail(RUN_ID).entries.isEmpty(), "release 后该运行的数据必须释放")
        }

    @Test
    fun `duplicate startRun for the same run id is ignored`() =
        runTest {
            val pipeline = newPipeline()
            pipeline.startRun(RUN_ID, firstThenDelay(total = 3, firstBatch = 3, inBetweenMillis = 60_000))
            pipeline.startRun(RUN_ID, entries(99))
            runCurrent()

            assertEquals(
                3,
                pipeline.tail(RUN_ID).entries.size,
                "重复登记必须被忽略，不得重复缓冲第二份数据",
            )
        }

    // ------------------------------------------------------------------ 工具

    private fun kotlinx.coroutines.test.TestScope.newPipeline(
        ringCapacity: Int = LogPipelineImpl.DEFAULT_RING_CAPACITY,
        batchMaxEntries: Int = LogPipelineImpl.DEFAULT_BATCH_MAX_ENTRIES,
    ): LogPipelineImpl =
        LogPipelineImpl(
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            ringCapacity = ringCapacity,
            batchMaxEntries = batchMaxEntries,
        )

    private fun entry(index: Int): LogEntry =
        LogEntry(
            runId = RUN_ID,
            sequence = LogMappers.SEQUENCE_PLACEHOLDER,
            timestamp = index.toLong(),
            stream = LogStream.STDOUT,
            text = "line-$index",
        )

    private fun entries(count: Int): Flow<LogEntry> =
        flow {
            repeat(count) { emit(entry(it)) }
        }

    private fun trickle(
        count: Int,
        intervalMillis: Long,
    ): Flow<LogEntry> =
        flow {
            repeat(count) {
                emit(entry(it))
                delay(intervalMillis)
            }
        }

    /** 先吐 [firstBatch] 条，再挂起 [inBetweenMillis]，使运行保持"进行中"以便查询 tail。 */
    private fun firstThenDelay(
        total: Int,
        firstBatch: Int,
        inBetweenMillis: Long,
    ): Flow<LogEntry> =
        flow {
            repeat(firstBatch) { emit(entry(it)) }
            delay(inBetweenMillis)
            for (index in firstBatch until total) emit(entry(index))
        }

    private companion object {
        const val RUN_ID = "run-1"
    }
}
