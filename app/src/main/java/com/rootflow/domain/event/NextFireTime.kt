package com.rootflow.domain.event

import java.util.Calendar

/**
 * 触发时刻的计算（阶段 3c.2，**纯函数**）。
 *
 * ## 为什么单独一个对象
 * "今天该时刻，已过则明天"这类时间计算是最容易写错、又最难在真机上复现的一类逻辑
 * （依赖当前时间与时区）。把它做成吃 [calendarFactory] 的纯函数后，
 * 单测可以用**固定时区 + 固定 now** 把每条分支钉死，不必等真机到点。
 *
 * ## 为什么注入 `Calendar` 工厂
 * 单测需要固定 `TimeZone`（否则用例结果随宿主时区漂移）。
 * 不用 `java.time`：本项目 3a 起未使用，且 `Calendar` 与 `AlarmManager` 的 RTC 墙钟语义更贴近。
 *
 * ## 入参是 `ScheduledAlarm` 而不是 `TriggerParams`
 * 二者携带同一批字段，但 `TriggerParams` 是**两类事件共用**的结构（同时含
 * `intervalMinutes` 与 `hourOfDay`）。直接吃 [ScheduledAlarm.Time] / [ScheduledAlarm.Interval]
 * 让"该有哪些字段"由类型保证，避免调用方在两种模型间来回搬运字段（少一处会漏字段的地方）。
 */
object NextFireTime {
    /** `hourOfDay` 的合法范围。 */
    const val HOUR_MIN: Int = 0
    const val HOUR_MAX: Int = 23

    /** `minuteOfHour` 的合法范围。 */
    const val MINUTE_MIN: Int = 0
    const val MINUTE_MAX: Int = 59

    /** 一分钟的毫秒数。 */
    const val MILLIS_PER_MINUTE: Long = 60_000L

    /**
     * 算 `time` 闹钟的下一次触发时刻。
     *
     * @param alarm `time` 闹钟（`hourOfDay` / `minuteOfHour` 由类型保证非空）
     * @param nowMillis 当前时刻（`System.currentTimeMillis()` 语义，RTC 墙钟）
     * @param calendarFactory 生成用于计算的 `Calendar`（单测注入固定 `TimeZone`）
     * @return 下次触发时刻（毫秒）；**字段非法时返回 `null`**（调用方记入 `AlarmPlan.invalid`）
     */
    fun nextFor(
        alarm: ScheduledAlarm.Time,
        nowMillis: Long,
        calendarFactory: () -> Calendar,
    ): Long? {
        if (alarm.hourOfDay !in HOUR_MIN..HOUR_MAX) return null
        if (alarm.minuteOfHour !in MINUTE_MIN..MINUTE_MAX) return null

        val calendar = calendarFactory()
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, alarm.hourOfDay)
        calendar.set(Calendar.MINUTE, alarm.minuteOfHour)
        // 秒与毫秒清零：否则会带上创建时刻的秒数，每天漂移
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val candidate = calendar.timeInMillis
        // 已过（含正好等于 now）则顺延一天。
        // **必须用 `<=`**：等于 now 时若原样返回，AlarmManager 会立刻触发一次，
        // 与"每天这个时刻"的语义不符（且会造成启动瞬间的意外触发）。
        if (candidate <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }
        return candidate
    }

    /**
     * 校验 `interval` 配置并换算成毫秒。
     *
     * @return 可用于 `setInexactRepeating` 的间隔毫秒数；配置非法时 `null`
     */
    fun repeatingIntervalMillis(alarm: ScheduledAlarm.Interval): Long? {
        if (alarm.intervalMinutes < 1) return null
        return alarm.intervalMinutes.toLong() * MILLIS_PER_MINUTE
    }
}
