package com.rootflow.data.event

import com.rootflow.domain.model.SystemEvent

/**
 * [SystemEvent] ↔ `triggers.event_type` 字符串键的双向映射（需求 §2.3）。
 *
 * ## 覆盖范围（14 个键）
 * 需求 §2.1 的 12 个事件 **+** [SystemEvent.BATTERY_OKAY]（决策 3 追加的恢复边）
 * **+** [SystemEvent.ALWAYS_RUN]（阶段 10 追加的「一直运行」）。
 * 需求里没有后两个键，但 `triggers.event_type` 是**持久化契约**，
 * 因此本项目追加的键同样必须在此登记并逐值钉死。
 *
 * ## 为什么单独一个对象
 * 键是**持久化契约**（写进 Room 的 `triggers` 表），一旦发布就不能随意改。
 * 集中在一处 + 单测逐值钉死，避免"某个事件源的字符串拼错"这类只能在真机上才发现的错误。
 *
 * ## 容错（不崩、不静默）
 * [fromId] 遇到未知键返回 `null` 并调用 [onWarning]：**一条坏记录不能让调度器停摆**，
 * 但"忽略了"这件事必须可见——与 `TriggerParamsCodec` 同款纪律。
 *
 * ## 无参 vs 带参
 * 裸 `eventId` 只能还原**无参事件**；带参事件（前台包名、WiFi 状态）在 [fromId] 中
 * 以**安全的默认值**构造——调度器只按 `eventId` 匹配，不依赖这些字段做判定，
 * 因此默认值不会导致误触发。
 */
object TriggerEventKeys {
    /** 告警文案前缀。 */
    const val WARNING_PREFIX: String = "TriggerEventKeys:"

    /** 全部已知键（含带参事件的键），供校验与文档用。 */
    val knownIds: Set<String> =
        setOf(
            SystemEvent.BOOT,
            SystemEvent.SCREEN_ON,
            SystemEvent.SCREEN_OFF,
            SystemEvent.UNLOCK,
            SystemEvent.APP_FOREGROUND,
            SystemEvent.APP_BACKGROUND,
            SystemEvent.TIME,
            SystemEvent.INTERVAL,
            SystemEvent.BATTERY_LOW,
            SystemEvent.BATTERY_OKAY,
            SystemEvent.POWER_CONNECTED,
            SystemEvent.POWER_DISCONNECTED,
            SystemEvent.WIFI_CHANGED,
            // 阶段 10：「一直运行」不是外部事件，但同样写进 triggers.event_type，
            // 因此必须在这个持久化契约里登记（否则 UI 配得出、调度器认不得）。
            SystemEvent.ALWAYS_RUN,
        )

    /**
     * 字符串键 → 事件实例。
     *
     * @param id `triggers.event_type` 的值
     * @param onWarning 未知键时的回调（文案已带 [WARNING_PREFIX] 前缀）
     * @return 事件实例；未知键返回 `null`
     */
    fun fromId(
        id: String,
        onWarning: (String) -> Unit = {},
    ): SystemEvent? =
        when (id) {
            SystemEvent.BOOT -> SystemEvent.Boot
            SystemEvent.SCREEN_ON -> SystemEvent.ScreenOn
            SystemEvent.SCREEN_OFF -> SystemEvent.ScreenOff
            SystemEvent.UNLOCK -> SystemEvent.Unlock
            // 带参事件的键可识别，但字段用安全默认值（见类 KDoc）
            SystemEvent.APP_FOREGROUND -> SystemEvent.AppForeground(packageName = UNKNOWN_FIELD)
            SystemEvent.APP_BACKGROUND -> SystemEvent.AppBackground(packageName = UNKNOWN_FIELD)
            SystemEvent.TIME -> SystemEvent.Time
            SystemEvent.INTERVAL -> SystemEvent.Interval
            SystemEvent.BATTERY_LOW -> SystemEvent.BatteryLow
            SystemEvent.BATTERY_OKAY -> SystemEvent.BatteryOkay
            SystemEvent.POWER_CONNECTED -> SystemEvent.PowerConnected
            SystemEvent.POWER_DISCONNECTED -> SystemEvent.PowerDisconnected
            SystemEvent.WIFI_CHANGED -> SystemEvent.WifiChanged(connected = false)
            SystemEvent.ALWAYS_RUN -> SystemEvent.AlwaysRun
            else -> {
                onWarning("$WARNING_PREFIX unknown event_type '$id' ignored")
                null
            }
        }

    /** 事件 → 字符串键。 */
    fun toId(event: SystemEvent): String = event.eventId

    /** 带参事件在 [fromId] 中使用的占位值。 */
    const val UNKNOWN_FIELD: String = "<unknown>"
}
