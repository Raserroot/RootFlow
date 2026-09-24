package com.rootflow.data.event

import com.rootflow.domain.model.SystemEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TriggerEventKeys] 单测（3b 清单 1–3）。
 *
 * 键是**持久化契约**（写进 `triggers.event_type`），因此必须逐值钉死：
 * 单测失败即意味着"改动会破坏已有用户的触发器数据"。
 */
class TriggerEventKeysTest {
    @Test
    fun `event ids match the requirement table verbatim`() {
        // 逐值对应 REQUIREMENTS.md §2.1 的「事件 ID」列，加上决策 3 追加的 battery_okay
        assertEquals("boot", SystemEvent.Boot.eventId)
        assertEquals("screen_on", SystemEvent.ScreenOn.eventId)
        assertEquals("screen_off", SystemEvent.ScreenOff.eventId)
        assertEquals("unlock", SystemEvent.Unlock.eventId)
        assertEquals("app_foreground", SystemEvent.AppForeground("p").eventId)
        assertEquals("app_background", SystemEvent.AppBackground("p").eventId)
        assertEquals("time", SystemEvent.Time.eventId)
        assertEquals("interval", SystemEvent.Interval.eventId)
        assertEquals("battery_low", SystemEvent.BatteryLow.eventId)
        assertEquals("battery_okay", SystemEvent.BatteryOkay.eventId)
        assertEquals("power_connected", SystemEvent.PowerConnected.eventId)
        assertEquals("power_disconnected", SystemEvent.PowerDisconnected.eventId)
        assertEquals("wifi_changed", SystemEvent.WifiChanged(true).eventId)
    }

    @Test
    fun `all thirteen ids are unique and registered`() {
        val ids =
            listOf(
                SystemEvent.Boot,
                SystemEvent.ScreenOn,
                SystemEvent.ScreenOff,
                SystemEvent.Unlock,
                SystemEvent.AppForeground("p"),
                SystemEvent.AppBackground("p"),
                SystemEvent.Time,
                SystemEvent.Interval,
                SystemEvent.BatteryLow,
                SystemEvent.BatteryOkay,
                SystemEvent.PowerConnected,
                SystemEvent.PowerDisconnected,
                SystemEvent.WifiChanged(true),
                SystemEvent.AlwaysRun,
            ).map { it.eventId }

        // 12 = 需求 §2.1 的事件数；+battery_okay（决策 3）；+always_run（阶段 10「一直运行」）
        assertEquals(14, ids.size)
        assertEquals(14, ids.toSet().size, "eventId 必须互不相同：$ids")
        assertEquals(ids.toSet(), TriggerEventKeys.knownIds, "knownIds 必须覆盖全部 14 个事件")
    }

    @Test
    fun `battery okay round trips through the persistence key`() {
        // 决策 3 追加的事件同样是持久化契约：triggers.event_type 里会出现 "battery_okay"
        assertEquals(SystemEvent.BatteryOkay, TriggerEventKeys.fromId(SystemEvent.BATTERY_OKAY))
        assertEquals("battery_okay", TriggerEventKeys.toId(SystemEvent.BatteryOkay))
    }

    @Test
    fun `fromId round trips every known key`() {
        TriggerEventKeys.knownIds.forEach { id ->
            val event = TriggerEventKeys.fromId(id)
            assertEquals(id, event?.eventId, "键 $id 无法往返")
        }
    }

    @Test
    fun `unknown key returns null and warns instead of throwing`() {
        val warnings = mutableListOf<String>()

        val event = TriggerEventKeys.fromId("no_such_event", warnings::add)

        assertNull(event, "未知键必须返回 null（不能让调度器因一条坏记录停摆）")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith(TriggerEventKeys.WARNING_PREFIX), warnings.single())
        assertTrue(warnings.single().contains("no_such_event"), warnings.single())
    }

    @Test
    fun `parameterised events restore with a safe default`() {
        // 裸键只能还原无参事件；带参字段用安全默认值，且调度器不依赖它们做判定
        val foreground = TriggerEventKeys.fromId(SystemEvent.APP_FOREGROUND)
        assertEquals(
            SystemEvent.AppForeground(TriggerEventKeys.UNKNOWN_FIELD),
            foreground,
        )
    }
}
