package com.rootflow.data.log

import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.runtime.LogLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * [LogMappers] 单测（阶段 2 清单 11）。
 *
 * 重点不是"能转换"，而是**枚举取值不漂移**：`runtime` 与 `domain` 各有一个
 * `LogStream`，任一新增取值都必须在这里显式补齐，否则编译期就会失败——
 * 这正是引入映射层的收益。
 */
class LogMappersTest {
    @Test
    fun `runtime log line maps to domain entry for every stream value`() {
        com.rootflow.runtime.LogStream.entries.forEach { runtimeStream ->
            val line = LogLine(timestamp = 1_700_000_000_000L, stream = runtimeStream, text = "hello")

            val entry = LogMappers.toDomain(line, runId = "run-42")

            assertEquals("run-42", entry.runId)
            assertEquals(1_700_000_000_000L, entry.timestamp)
            assertEquals("hello", entry.text)
            assertEquals(runtimeStream.name, entry.stream.name, "枚举名必须一一对应")
            assertEquals(
                LogMappers.SEQUENCE_PLACEHOLDER,
                entry.sequence,
                "映射不负责序号：必须留占位，由管道覆盖",
            )
        }
    }

    @Test
    fun `domain entry maps back to runtime log line`() {
        LogStream.entries.forEach { domainStream ->
            val entry =
                LogEntry(
                    runId = "run-42",
                    sequence = 7L,
                    timestamp = 1_700_000_000_001L,
                    stream = domainStream,
                    text = "back",
                )

            val line = LogMappers.toRuntime(entry)

            assertEquals(1_700_000_000_001L, line.timestamp)
            assertEquals("back", line.text)
            assertEquals(domainStream.name, line.stream.name, "反向映射同样必须一一对应")
        }
    }

    @Test
    fun `round trip preserves stream, timestamp and text`() {
        com.rootflow.runtime.LogStream.entries.forEach { runtimeStream ->
            val original = LogLine(timestamp = 123L, stream = runtimeStream, text = "round")

            val back = LogMappers.toRuntime(LogMappers.toDomain(original, runId = "r"))

            assertEquals(original, back, "runtime → domain → runtime 必须还原（sequence 不参与）")
        }
    }

    @Test
    fun `sequence placeholder is not a valid sequence`() {
        assertNotEquals(0L, LogMappers.SEQUENCE_PLACEHOLDER, "占位不能与真实序号 0 混淆")
        assertEquals(-1L, LogMappers.SEQUENCE_PLACEHOLDER)
    }
}
