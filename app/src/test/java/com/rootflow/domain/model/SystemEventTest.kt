package com.rootflow.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SystemEvent] 单测（阶段 3c.1，**决策 3** 的守护）。
 *
 * ## 为什么需要单独一个类
 * 3c.1 往密封层次里加了第 13 个事件（battery_okay），阶段 10 又加了第 14 个（always_run） [SystemEvent.BatteryOkay]——需求 §2.1 的表格里**没有**它。
 * 事件类型是**持久化契约**（`triggers.event_type` 里存的就是这些字符串），
 * 因此新增事件必须被显式钉死，而不是"加个 data object 就完事"。
 */
class SystemEventTest {
    @Test
    fun `battery okay carries the decision 3 event id`() {
        assertEquals("battery_okay", SystemEvent.BatteryOkay.eventId)
        assertEquals(SystemEvent.BATTERY_OKAY, SystemEvent.BatteryOkay.eventId)
    }

    @Test
    fun `the hierarchy now has fourteen distinct event ids`() {
        val ids = allEvents().map { it.eventId }

        assertEquals(
            14,
            ids.size,
            "需求 §2.1 的 12 个 + 决策 3 的 battery_okay + 阶段 10 的 always_run",
        )
        assertEquals(14, ids.toSet().size, "eventId 必须互不相同：$ids")
    }

    @Test
    fun `every event id matches its companion constant`() {
        assertEquals(SystemEvent.BOOT, SystemEvent.Boot.eventId)
        assertEquals(SystemEvent.SCREEN_ON, SystemEvent.ScreenOn.eventId)
        assertEquals(SystemEvent.SCREEN_OFF, SystemEvent.ScreenOff.eventId)
        assertEquals(SystemEvent.UNLOCK, SystemEvent.Unlock.eventId)
        assertEquals(SystemEvent.APP_FOREGROUND, SystemEvent.AppForeground("p").eventId)
        assertEquals(SystemEvent.APP_BACKGROUND, SystemEvent.AppBackground("p").eventId)
        assertEquals(SystemEvent.TIME, SystemEvent.Time.eventId)
        assertEquals(SystemEvent.INTERVAL, SystemEvent.Interval.eventId)
        assertEquals(SystemEvent.BATTERY_LOW, SystemEvent.BatteryLow.eventId)
        assertEquals(SystemEvent.BATTERY_OKAY, SystemEvent.BatteryOkay.eventId)
        assertEquals(SystemEvent.POWER_CONNECTED, SystemEvent.PowerConnected.eventId)
        assertEquals(SystemEvent.POWER_DISCONNECTED, SystemEvent.PowerDisconnected.eventId)
        assertEquals(SystemEvent.WIFI_CHANGED, SystemEvent.WifiChanged(true).eventId)
    }

    @Test
    fun `payload stays null in stage 3c_1`() {
        // 3b 决定"其余事件负载在 3c 随各源一起补"；3c.1 明确不做序列化（清单 §F）
        allEvents().forEach { event ->
            assertEquals(null, event.payloadJson(), "${event.eventId} 的负载序列化属 3c.2/3d 范围")
        }
    }

    @Test
    fun `wifi changed keeps its connected flag`() {
        assertEquals(true, SystemEvent.WifiChanged(true).connected)
        assertEquals(false, SystemEvent.WifiChanged(false).connected)
        assertTrue(SystemEvent.WifiChanged(true) != SystemEvent.WifiChanged(false), "两条边必须可区分")
    }

    /** 全部 14 个事件实例（新增事件时必须同步扩这张表——它是本类的"完整性清单"）。 */
    private fun allEvents(): List<SystemEvent> =
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
        )
}
