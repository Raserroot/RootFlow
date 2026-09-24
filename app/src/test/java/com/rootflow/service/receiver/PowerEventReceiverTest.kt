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
 * [PowerEventReceiver] 单测（阶段 3c.1，清单注册路线）。
 *
 * 与 `BootEventReceiverTest` 同构：直接调用可测入口 `handleAction(action)`，配合
 * [EventBusHolder] 注入假总线——纯 JVM 可跑，无需 Robolectric。
 */
class PowerEventReceiverTest {
    @AfterEach
    fun tearDown() {
        EventBusHolder.clear()
    }

    @Test
    fun `power connected is forwarded to the event bus`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        PowerEventReceiver().handleAction(PowerEventReceiver.ACTION_POWER_CONNECTED)

        assertEquals(listOf(SystemEvent.PowerConnected), bus.sent)
    }

    @Test
    fun `power disconnected is forwarded to the event bus`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        PowerEventReceiver().handleAction(PowerEventReceiver.ACTION_POWER_DISCONNECTED)

        assertEquals(listOf(SystemEvent.PowerDisconnected), bus.sent)
    }

    @Test
    fun `battery low is not mistaken for a power event`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        // 两个接收器都在清单里注册；收到不属于自己的 action 必须忽略（而不是误报）
        PowerEventReceiver().handleAction("android.intent.action.BATTERY_LOW")

        assertTrue(bus.sent.isEmpty(), "电池广播不得被电源接收器误当电源事件：${bus.sent}")
    }

    @Test
    fun `unknown and null actions are ignored`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        PowerEventReceiver().handleAction("android.intent.action.SOMETHING_ELSE")
        PowerEventReceiver().handleAction(null)

        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun `missing bus does not crash the receiver`() {
        installLogStub()

        // 刻意不 install：模拟"应用尚未完成初始化就收到广播"
        PowerEventReceiver().handleAction(PowerEventReceiver.ACTION_POWER_CONNECTED)

        // 不抛异常即通过——接收器崩溃会被系统记入日志并可能被限流
        assertTrue(true)
    }

    @Test
    fun `action constants are the platform literals`() {
        // 与 BootEventReceiver 同款纪律：不引用 Intent.ACTION_*（纯 JVM 不可用、MockK 也无法
        // stub 静态字段），因此用自有字面量并在此钉死，避免拼错导致"永远收不到"
        assertEquals("android.intent.action.ACTION_POWER_CONNECTED", PowerEventReceiver.ACTION_POWER_CONNECTED)
        assertEquals("android.intent.action.ACTION_POWER_DISCONNECTED", PowerEventReceiver.ACTION_POWER_DISCONNECTED)
    }

    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
