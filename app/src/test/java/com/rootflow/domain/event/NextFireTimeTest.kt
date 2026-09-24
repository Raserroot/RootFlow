package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [NextFireTime] 单测（阶段 3c.2）。
 *
 * ## 为什么必须固定时区
 * "今天该时刻，已过则明天"的结果依赖宿主 `TimeZone`。若用默认时区，同一份断言在
 * CI 与本机会给出不同结果（本机 `Asia/Shanghai`，CI 常为 UTC）。因此这里注入
 * **固定 UTC** 的 `Calendar` 工厂，并用 `calendar(...)` 按 UTC 构造期望值。
 */
class NextFireTimeTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun factory(): Calendar = Calendar.getInstance(utc)

    /** 按 UTC 构造一个时刻（便于把期望值写成可读的日子/时分）。 */
    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long =
        Calendar
            .getInstance(utc)
            .apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
            }.timeInMillis

    @Test
    fun `today is used when the configured time has not passed yet`() {
        // 2026-09-19 10:00 UTC，目标 23:30 → 今天 23:30
        val now = utcMillis(2026, 9, 19, 10, 0)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 23, minuteOfHour = 30)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        assertEquals(utcMillis(2026, 9, 19, 23, 30), next)
    }

    @Test
    fun `tomorrow is used when the configured time has already passed`() {
        // 2026-09-19 23:45 UTC，目标 23:30 → 明天 23:30
        val now = utcMillis(2026, 9, 19, 23, 45)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 23, minuteOfHour = 30)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        assertEquals(utcMillis(2026, 9, 20, 23, 30), next)
    }

    @Test
    fun `the result is always strictly after now even when the minute matches exactly`() {
        // 恰好等于目标时刻：**必须**顺延一天而不是返回 now，
        // 否则 AlarmManager 会立刻触发一次，与"每天这个时刻"的语义不符
        val now = utcMillis(2026, 9, 19, 23, 30)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 23, minuteOfHour = 30)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        assertEquals(utcMillis(2026, 9, 20, 23, 30), next)
        assertTrue((next ?: 0L) > now, "结果必须严格晚于 now")
    }

    @Test
    fun `seconds and millis are truncated to zero`() {
        // 若不清零，会带上创建时刻的秒数 → 每天的触发时刻缓慢漂移
        val now = utcMillis(2026, 9, 19, 10, 0, second = 37)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 12, minuteOfHour = 5)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        val calendar = factory().apply { timeInMillis = next ?: 0L }
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
        assertEquals(12, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `midnight boundary is handled`() {
        val now = utcMillis(2026, 9, 19, 0, 0)
        val midnight = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 0, minuteOfHour = 0)

        val next = NextFireTime.nextFor(midnight, now, ::factory)

        // 现在正好是 0:0 → 顺延到明天 0:0
        assertEquals(utcMillis(2026, 9, 20, 0, 0), next)
    }

    @Test
    fun `month and year rollover are handled`() {
        val now = utcMillis(2026, 12, 31, 23, 59)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 23, minuteOfHour = 0)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        assertEquals(utcMillis(2027, 1, 1, 23, 0), next, "跨年必须正确（Calendar 负责进位）")
    }

    @Test
    fun `leap day is handled by Calendar`() {
        val now = utcMillis(2028, 2, 28, 23, 59)
        val alarm = ScheduledAlarm.Time(triggerId = 1, scriptId = 10, exact = false, hourOfDay = 1, minuteOfHour = 0)

        val next = NextFireTime.nextFor(alarm, now, ::factory)

        assertEquals(utcMillis(2028, 2, 29, 1, 0), next, "2028 是闰年，2/29 存在")
    }

    @Test
    fun `out of range hour or minute is rejected`() {
        val now = utcMillis(2026, 9, 19, 10, 0)

        assertNull(
            NextFireTime.nextFor(
                ScheduledAlarm.Time(1, 10, exact = false, hourOfDay = 24, minuteOfHour = 0),
                now,
                ::factory,
            ),
            "hour=24 非法 → null（调用方记入 invalid，不得静默注册到错误时刻）",
        )
        assertNull(
            NextFireTime.nextFor(
                ScheduledAlarm.Time(1, 10, exact = false, hourOfDay = 12, minuteOfHour = 60),
                now,
                ::factory,
            ),
            "minute=60 非法 → null",
        )
        assertNull(
            NextFireTime.nextFor(
                ScheduledAlarm.Time(1, 10, exact = false, hourOfDay = -1, minuteOfHour = 0),
                now,
                ::factory,
            ),
            "hour=-1 非法 → null",
        )
    }

    @Test
    fun `the calculation is pure`() {
        val now = utcMillis(2026, 9, 19, 10, 0)
        val alarm = ScheduledAlarm.Time(1, 10, exact = false, hourOfDay = 23, minuteOfHour = 30)

        assertEquals(
            NextFireTime.nextFor(alarm, now, ::factory),
            NextFireTime.nextFor(alarm, now, ::factory),
        )
    }

    @Test
    fun `repeating interval converts minutes to millis`() {
        val alarm = ScheduledAlarm.Interval(1, 10, exact = false, intervalMinutes = 15)

        assertEquals(900_000L, NextFireTime.repeatingIntervalMillis(alarm))
        assertEquals(60_000L, NextFireTime.repeatingIntervalMillis(alarm.copy(intervalMinutes = 1)))
    }

    @Test
    fun `repeating interval rejects non-positive minutes`() {
        val base = ScheduledAlarm.Interval(1, 10, exact = false, intervalMinutes = 0)

        assertNull(NextFireTime.repeatingIntervalMillis(base), "0 分钟 → null")
        assertNull(NextFireTime.repeatingIntervalMillis(base.copy(intervalMinutes = -5)), "负数 → null")
    }

    @Test
    fun `range constants match the documented bounds`() {
        assertEquals(0, NextFireTime.HOUR_MIN)
        assertEquals(23, NextFireTime.HOUR_MAX)
        assertEquals(0, NextFireTime.MINUTE_MIN)
        assertEquals(59, NextFireTime.MINUTE_MAX)
        assertEquals(60_000L, NextFireTime.MILLIS_PER_MINUTE)
    }
}
