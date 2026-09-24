package com.rootflow.data.run

import android.util.Log
import com.rootflow.data.db.FakeDatabase
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.repository.RunMeta
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RunHistoryCollector] 单测（阶段 3d，落库闭环）。
 *
 * ## 覆盖重点
 * 1. 批次透传（含 `runFinished`）与 `scriptId` / `triggerEvent` 由 `runMeta` 带上
 * 2. **收尾信号独立于批次**：取消路径上没有 `runFinished=true` 的批次时仍要落 `finished_at`
 * 3. 队列溢出：**丢最旧 + 计数 + 告警**（与管道同款纪律，不静默丢数据）
 * 4. `runMeta` 缺失：按未知脚本落库并告警，而不是丢掉整次运行的日志
 *
 * ## 为什么用真实的 `RunHistoryWriter` + `FakeDatabase`
 * 本类的语义就是"把管道批次翻译成落库批次"，用真实写入器才能验证**翻译后**真的落进了
 * `runs` / `run_log_entries`（包括 D6 的退出码提取）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunHistoryCollectorTest {
    /** 消费者作用域；每个用例结束必须取消（它承载一个永不返回的循环）。 */
    private var consumerScope: CoroutineScope? = null

    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        consumerScope?.cancel()
        consumerScope = null
    }

    @Test
    fun `a batch is written with the run meta from the pipeline`() =
        runTest {
            val fixture = fixture()

            fixture.collector.onBatch(
                meta = RunMeta(scriptId = 7L, triggerEvent = "boot"),
                batch = batch(runId = "run-1", entries = 3, finished = false),
            )
            advanceUntilIdle()

            val row = fixture.db.runs["run-1"]
            assertNotNull(row)
            assertEquals(7L, row?.scriptId)
            assertEquals("boot", row?.triggerEvent)
            assertEquals(3, fixture.db.entriesOf("run-1").size)
            assertNull(row?.finishedAt, "未收尾的运行不得写 finishedAt")
        }

    @Test
    fun `the terminal batch marks the run finished`() =
        runTest {
            val fixture = fixture()

            fixture.collector.onBatch(RunMeta(1L, "boot"), batch("run-1", entries = 2, finished = true))
            advanceUntilIdle()

            assertEquals(5_000L, fixture.db.runs["run-1"]?.finishedAt, "收尾批必须落 finished_at")
            assertEquals(2, fixture.db.runs["run-1"]?.logEntryCount)
        }

    @Test
    fun `the exit code is extracted from the runtime SYS line`() =
        runTest {
            val fixture = fixture()
            val entries =
                logEntries(count = 1, runId = "run-1") +
                    LogEntry(
                        runId = "run-1",
                        sequence = 1,
                        timestamp = 2L,
                        stream = LogStream.SYS,
                        text = "script 7 exited with code 143",
                    )

            fixture.collector.onBatch(RunMeta(7L, "boot"), batch("run-1", entries = entries, finished = true))
            advanceUntilIdle()

            assertEquals(143, fixture.db.runs["run-1"]?.exitCode, "D6 的退出码提取必须端到端成立")
        }

    @Test
    fun `the run finished signal alone closes the run row`() =
        runTest {
            // 取消路径：管道可能只发出 runFinished 信号而没有终批（订阅通道已断）。
            // 若收尾只靠批次携带，runs.finished_at 会永远为 null（3a 遗留 #2）。
            val fixture = fixture()

            fixture.collector.onBatch(RunMeta(3L, "interval"), batch("run-1", entries = 1, finished = false))
            fixture.collector.onRunFinished(runId = "run-1", meta = RunMeta(3L, "interval"))
            advanceUntilIdle()

            assertEquals(5_000L, fixture.db.runs["run-1"]?.finishedAt)
        }

    @Test
    fun `the finished signal is idempotent alongside the terminal batch`() =
        runTest {
            val fixture = fixture()

            fixture.collector.onBatch(RunMeta(1L, "boot"), batch("run-1", entries = 1, finished = true))
            fixture.collector.onRunFinished("run-1", RunMeta(1L, "boot"))
            advanceUntilIdle()

            assertEquals(1, fixture.db.runs.size)
            assertEquals(1, fixture.db.entriesOf("run-1").size, "重复收尾不得重复写日志行")
            assertEquals(5_000L, fixture.db.runs["run-1"]?.finishedAt)
        }

    @Test
    fun `a queue overflow drops the oldest batches and counts them`() =
        runTest {
            // 容量 1：第 2 批到达时第 1 批必须被丢弃并计数（与管道同款 DROP_OLDEST 纪律）
            val fixture = fixture(queueCapacity = 1)

            fixture.collector.onBatch(RunMeta(1L, "boot"), batch("run-a", entries = 2, finished = false))
            fixture.collector.onBatch(RunMeta(1L, "boot"), batch("run-b", entries = 3, finished = false))
            advanceUntilIdle()

            assertEquals(1L, fixture.collector.droppedBatchCount, "被丢弃的批次数必须可见（不静默）")
            assertTrue(fixture.db.runs.containsKey("run-b"), "保留的应是较新的批次：${fixture.db.runs.keys}")
        }

    @Test
    fun `batches without run meta are recorded as unknown and warned`() =
        runTest {
            val fixture = fixture()

            fixture.collector.onBatch(meta = null, batch = batch("run-1", entries = 2, finished = true))
            advanceUntilIdle()

            assertEquals(
                RunHistoryCollector.UNKNOWN_SCRIPT_ID,
                fixture.db.runs["run-1"]?.scriptId,
                "缺 runMeta 时按未知脚本落库（而不是丢掉整次运行的日志）",
            )
            assertEquals(2, fixture.db.entriesOf("run-1").size)
        }

    @Test
    fun `pipeline level drops are logged and not double counted`() =
        runTest {
            val fixture = fixture()

            // 丢弃条数由**后续批次**的 `droppedEntries` 携带（管道契约）：
            // 因此必须"先发一批（dropped=0）再发携带该计数的收尾批"，
            // 否则收尾批自己写进 runs 时读到的累计值仍是 0（实测教训：断言会看起来像功能没做）。
            fixture.collector.onBatchesDropped(count = 42, meta = RunMeta(1L, "boot"))
            fixture.collector.onBatch(
                meta = RunMeta(1L, "boot"),
                batch = batch("run-1", entries = 1, finished = false),
            )
            fixture.collector.onBatch(
                meta = RunMeta(1L, "boot"),
                batch = batch("run-1", entries = 1, finished = true, dropped = 42),
            )
            advanceUntilIdle()

            assertEquals(42, fixture.db.runs["run-1"]?.droppedLogEntries, "管道级丢弃必须落库（不静默）")
        }

    @Test
    fun `the consumer is started only once`() =
        runTest {
            val fixture = fixture()

            // 多批并发到达（模拟多个脚本同时运行）：只有一个消费者协程
            repeat(5) { index ->
                fixture.collector.onBatch(RunMeta(1L, "boot"), batch("run-$index", entries = 1, finished = false))
            }
            advanceUntilIdle()

            assertEquals(5, fixture.db.runs.size)
        }

    // ---------------------------------------------------------------- 工具

    private class Fixture(
        val db: FakeDatabase,
        val collector: RunHistoryCollector,
    )

    /**
     * 组装一个收集器 + 它自己的消费者作用域。
     *
     * ## 为什么既不能用 `this`（测试作用域）也不能用 `backgroundScope`（3d 实测结论）
     * - **测试作用域**：消费者是 `for (pending in queue)`，**永不返回** ⇒ `runTest` 会等它到超时
     *   （报 `UncompletedCoroutinesError: there were active child jobs`）
     * - **`backgroundScope`**：`advanceUntilIdle()` **不会推进它**的排队任务 ⇒ 批次永远写不进库
     *   （实测：`db.runs` 恒为空，断言看起来像"功能没做"而非"测试没驱动起来"）
     *
     * 因此用一个**挂在自己 Job 上、但共用测试调度器**的独立作用域：虚拟时间能推进它
     * （`advanceUntilIdle` 生效），而 `runTest` 不等它（不是测试作用域的子作业）。
     * 用例结束由 [tearDown] 取消。
     */
    private fun TestScope.fixture(queueCapacity: Int = RunHistoryCollector.DEFAULT_QUEUE_CAPACITY): Fixture {
        val db = FakeDatabase()
        val writer = RunHistoryWriter(db.runDao, db.runLogDao, clock = { 5_000L })
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        consumerScope = scope
        val collector = RunHistoryCollector(writer = writer, scope = scope, queueCapacity = queueCapacity)
        return Fixture(db, collector)
    }

    private fun batch(
        runId: String,
        entries: Int,
        finished: Boolean,
        dropped: Long = 0,
    ): LogBatch =
        LogBatch(
            runId = runId,
            sequence = 0,
            entries = logEntries(count = entries, runId = runId),
            droppedEntries = dropped,
            runFinished = finished,
        )

    private fun batch(
        runId: String,
        entries: List<LogEntry>,
        finished: Boolean,
    ): LogBatch =
        LogBatch(
            runId = runId,
            sequence = 0,
            entries = entries.map { it.copy(runId = runId) },
            droppedEntries = 0,
            runFinished = finished,
        )

    private companion object {
        /** 便捷构造 domain 日志条目（本地命名，避免与 `LogBatch.entries` 属性混淆）。 */
        fun logEntries(
            count: Int,
            runId: String,
        ): List<LogEntry> =
            List(count) { index ->
                LogEntry(
                    runId = runId,
                    sequence = index.toLong(),
                    timestamp = index.toLong(),
                    stream = LogStream.STDOUT,
                    text = "line-$index",
                )
            }
    }
}
