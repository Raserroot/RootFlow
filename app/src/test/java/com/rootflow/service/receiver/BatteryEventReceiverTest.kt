package com.rootflow.service.receiver

import android.util.Log
import com.rootflow.data.event.EventBusHolder
import com.rootflow.data.event.RecordingEventBus
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BatteryEventReceiver] 单测（阶段 3c.1，清单注册路线 + **决策 3**）。
 *
 * 决策 3 的核心是**恢复边**：需求 §2.1 只列了 `battery_low`，但缺了 `battery_okay`
 * 会让"低电量"状态无法复位（用户脚本做了省电动作后永远回不去）。
 * 因此本类的每一条 `battery_okay` 用例都是对该决策的直接守护。
 */
class BatteryEventReceiverTest {
    @AfterEach
    fun tearDown() {
        EventBusHolder.clear()
    }

    @Test
    fun `battery low is forwarded to the event bus`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BatteryEventReceiver().handleAction(BatteryEventReceiver.ACTION_BATTERY_LOW)

        assertEquals(listOf(SystemEvent.BatteryLow), bus.sent)
    }

    @Test
    fun `battery okay is forwarded as the recovery edge`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BatteryEventReceiver().handleAction(BatteryEventReceiver.ACTION_BATTERY_OKAY)

        assertEquals(
            listOf(SystemEvent.BatteryOkay),
            bus.sent,
            "决策 3：battery_okay 必须建成事件，否则低电量状态无法复位",
        )
        assertEquals("battery_okay", SystemEvent.BatteryOkay.eventId)
    }

    @Test
    fun `low and okay are not confusable`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BatteryEventReceiver().handleAction(BatteryEventReceiver.ACTION_BATTERY_LOW)
        BatteryEventReceiver().handleAction(BatteryEventReceiver.ACTION_BATTERY_OKAY)

        assertEquals(listOf(SystemEvent.BatteryLow, SystemEvent.BatteryOkay), bus.sent)
        assertTrue(
            SystemEvent.BatteryLow.eventId != SystemEvent.BatteryOkay.eventId,
            "两条边的 eventId 必须不同，否则触发器无法区分",
        )
    }

    @Test
    fun `a power action is not mistaken for a battery action`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BatteryEventReceiver().handleAction("android.intent.action.ACTION_POWER_CONNECTED")

        assertTrue(bus.sent.isEmpty(), "电源广播不得被电池接收器误报：${bus.sent}")
    }

    @Test
    fun `unknown and null actions are ignored`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BatteryEventReceiver().handleAction("android.intent.action.SOMETHING_ELSE")
        BatteryEventReceiver().handleAction(null)

        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun `missing bus does not crash the receiver`() {
        installLogStub()

        BatteryEventReceiver().handleAction(BatteryEventReceiver.ACTION_BATTERY_LOW)

        assertTrue(true)
    }

    @Test
    fun `action constants are the platform literals`() {
        assertEquals("android.intent.action.BATTERY_LOW", BatteryEventReceiver.ACTION_BATTERY_LOW)
        assertEquals("android.intent.action.BATTERY_OKAY", BatteryEventReceiver.ACTION_BATTERY_OKAY)
    }

    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
