package com.rootflow.data.log

import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RunLogRing] 单测（阶段 2 清单 1–3）。
 *
 * 环形缓冲最易错的是**回绕后的顺序**：覆盖写之后 `head` 指向最旧元素，
 * 遍历必须从 `(head - count + capacity) % capacity` 起算。这里用"回绕后
 * 快照必须等于追加序列的后缀"来钉死它。
 */
class RunLogRingTest {
    @Test
    fun `keeps entries in order while below capacity`() {
        val ring = RunLogRing(capacity = 5)

        repeat(3) { ring.append(entry(it)) }

        assertEquals(listOf(0L, 1L, 2L), ring.snapshot().map { it.sequence })
        assertEquals(3, ring.size())
        assertEquals(0L, ring.droppedCount())
    }

    @Test
    fun `snapshot keeps the newest entries in order after wrapping`() {
        val ring = RunLogRing(capacity = 5)

        // 追加 12 条（回绕 2 次以上），应只剩最后 5 条且顺序正确。
        repeat(12) { ring.append(entry(it)) }

        assertEquals(listOf(7L, 8L, 9L, 10L, 11L), ring.snapshot().map { it.sequence })
        assertEquals(5, ring.size())
    }

    @Test
    fun `overflow drops the oldest entries and counts them`() {
        val ring = RunLogRing(capacity = 3)

        repeat(10) { ring.append(entry(it)) }

        assertEquals(listOf(7L, 8L, 9L), ring.snapshot().map { it.sequence })
        assertEquals(7L, ring.droppedCount(), "淘汰计数 = 追加总数 - 容量")
        assertEquals(3, ring.size())
    }

    @Test
    fun `snapshot honour the limit by returning the newest entries`() {
        val ring = RunLogRing(capacity = 10)
        repeat(10) { ring.append(entry(it)) }

        assertEquals(listOf(8L, 9L), ring.snapshot(limit = 2).map { it.sequence })
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), ring.snapshot(limit = 5).map { it.sequence })
    }

    @Test
    fun `empty ring yields no entries`() {
        val ring = RunLogRing(capacity = 4)

        assertTrue(ring.snapshot().isEmpty())
        assertEquals(0, ring.size())
        assertEquals(0L, ring.droppedCount())
    }

    private fun entry(index: Int): LogEntry =
        LogEntry(
            runId = "run-1",
            sequence = index.toLong(),
            timestamp = index.toLong(),
            stream = LogStream.STDOUT,
            text = "line-$index",
        )
}
