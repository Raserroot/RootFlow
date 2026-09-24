package com.rootflow.domain.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 保留策略（阶段 6d）。
 *
 * ## 为什么这些用例是"护栏"而不是重复
 * 设置里存的是**磁盘上的历史数据**：旧版本可能写过已下线的档位，用户也可能手改文件。
 * [LogRetention.sanitize] 的回落行为因此必须被钉死 —— 它一旦抛异常，
 * 表现是"设置页打不开 + 清理路径不可用"，而那是用户自救的唯一入口。
 */
class LogRetentionTest {
    @Test
    fun `档位与默认值自洽`() {
        assertEquals(listOf(3, 7, 14, 30), LogRetention.ALLOWED_DAYS)
        assertTrue(
            LogRetention.DEFAULT_DAYS in LogRetention.ALLOWED_DAYS,
            "默认值必须在档位内，否则 UI 上会出现「一个都没选中」的状态",
        )
    }

    @Test
    fun `非法值一律回落默认而不抛`() {
        assertEquals(LogRetention.DEFAULT_DAYS, LogRetention.sanitize(null))
        assertEquals(LogRetention.DEFAULT_DAYS, LogRetention.sanitize(0))
        assertEquals(LogRetention.DEFAULT_DAYS, LogRetention.sanitize(-1))
        assertEquals(LogRetention.DEFAULT_DAYS, LogRetention.sanitize(999))
        assertEquals(LogRetention.DEFAULT_DAYS, LogRetention.sanitize(Int.MIN_VALUE))
    }

    @Test
    fun `合法档位原样保留`() {
        LogRetention.ALLOWED_DAYS.forEach { days ->
            assertEquals(days, LogRetention.sanitize(days))
        }
    }

    @Test
    fun `文案对非法值也给出可读结果`() {
        assertEquals("7 天", LogRetention.label(7))
        assertEquals("30 天", LogRetention.label(30))
        assertEquals("7 天", LogRetention.label(999), "非法值经收敛后再生成文案")
    }

    @Test
    fun `截止时间按天算且不会为负`() {
        val now = 1_700_000_000_000L
        assertEquals(now - 3L * LogRetention.MILLIS_PER_DAY, LogRetention.cutoffMillis(now, 3))
        assertEquals(
            0L,
            LogRetention.cutoffMillis(nowMillis = 1_000L, days = 30),
            "时钟很小（或刚开机）时不得回绕成负数 —— 负数会让 DELETE 条件命中全部行",
        )
    }

    @Test
    fun `一天毫秒数固定`() {
        assertEquals(86_400_000L, LogRetention.MILLIS_PER_DAY)
    }
}
