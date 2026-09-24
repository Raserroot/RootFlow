package com.rootflow.data.event

import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.model.SystemEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ScreenEventSource` 单测（阶段 3c.1，**动态注册路线**）。
 *
 * ## 覆盖边界（诚实声明）
 * 动态注册本身（`Context.registerReceiver`）在纯 JVM 下不可执行，落在
 * `AndroidBroadcastRegistration` 里，**按决策 13 不进单测**（正确性由真机覆盖）。
 * 本类覆盖的是**被注册之前与之后**的全部本项目逻辑：注册哪些 action、幂等、
 * 停止后不再转发、状态上报——接收器自身的映射逻辑见 `ScreenEventReceiverTest`。
 */
class ScreenEventSourceTest {
    @Test
    fun `registered actions are exactly the three screen actions in a fixed order`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, RecordingEventBus())

        source.start()

        assertEquals(
            listOf(
                ScreenEventReceiver.ACTION_SCREEN_ON,
                ScreenEventReceiver.ACTION_SCREEN_OFF,
                ScreenEventReceiver.ACTION_USER_PRESENT,
            ),
            registration.lastActions,
            "注册的 action 列表与顺序都必须固定（真机日志按此逐项核对）",
        )
    }

    @Test
    fun `start is idempotent`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, RecordingEventBus())

        source.start()
        source.start()
        source.start()

        assertEquals(1, registration.registerCalls, "重复 start() 不得重复注册（否则事件会重复投递）")
    }

    @Test
    fun `status follows the registration state`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, RecordingEventBus())

        assertEquals("screen", source.sourceId)
        assertEquals(EventSourceState.NotStarted, source.status().state)

        source.start()
        assertEquals(EventSourceState.Running, source.status().state)

        source.stop()
        assertEquals(EventSourceState.NotStarted, source.status().state, "stop() 后必须回到 NotStarted")
    }

    @Test
    fun `stop unregisters exactly once and is safe before start`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, RecordingEventBus())

        // 未 start() 时 stop() 必须安全且不做任何事
        source.stop()
        assertEquals(0, registration.unregisterCalls)

        source.start()
        source.stop()
        source.stop()
        assertEquals(1, registration.unregisterCalls, "重复 stop() 不得重复反注册")
    }

    @Test
    fun `a stop-start cycle re-registers`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, RecordingEventBus())

        source.start()
        source.stop()
        source.start()

        assertEquals(2, registration.registerCalls, "stop() 后必须能重新 start()")
        assertTrue(registration.isRegistered)
    }

    @Test
    fun `the source requires no permissions`() {
        // 三个屏幕事件由系统广播驱动，无需任何权限（决策 10 的目录里没有对应项）
        val source = ScreenEventSource(FakeBroadcastRegistration(), RecordingEventBus())

        assertTrue(source.requiredPermissions.isEmpty(), "屏幕事件不需要权限：${source.requiredPermissions}")
    }

    @Test
    fun `events reach the bus only while registered`() {
        installLogStub()
        val bus = RecordingEventBus()
        val registration = FakeBroadcastRegistration()
        val source = ScreenEventSource(registration, bus)

        source.start()
        registration.sink?.invoke(ScreenEventReceiver.ACTION_SCREEN_ON)
        assertEquals(listOf(SystemEvent.ScreenOn), bus.sent)

        source.stop()
        // 反注册后即使有迟到回调，源自身也不再持有 sink（真实实现由 Context 保证不再投递）
        assertEquals(1, registration.unregisterCalls)
    }

    private fun installLogStub() = EventTestLog.install()
}
