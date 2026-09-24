package com.rootflow.data.event

import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `ScreenEventReceiver` 单测（阶段 3c.1）。
 *
 * ## 为什么接收器可以构造注入 `EventBus`
 * 它由 `ScreenEventSource` 在运行期 `new` 出来（不是系统实例化的），因此不必走
 * `EventBusHolder` 那套全局单例——单测也就不用碰任何全局状态。
 *
 * ## 覆盖的重点
 * action → 事件映射、未知 action 忽略、空 action 安全、以及**「一次性告警」窗口**
 * （周期性亮/熄屏会让未知 action 反复到达，窗口逻辑失效会刷爆日志）。
 */
class ScreenEventReceiverTest {
    @Test
    fun `the three actions map to their events`() {
        installLogStub()
        val bus = RecordingEventBus()
        val receiver = ScreenEventReceiver(bus, FakeBroadcastRegistration())

        receiver.handleAction(ScreenEventReceiver.ACTION_SCREEN_ON)
        receiver.handleAction(ScreenEventReceiver.ACTION_SCREEN_OFF)
        receiver.handleAction(ScreenEventReceiver.ACTION_USER_PRESENT)

        assertEquals(
            listOf(SystemEvent.ScreenOn, SystemEvent.ScreenOff, SystemEvent.Unlock),
            bus.sent,
        )
    }

    @Test
    fun `unknown action is ignored and warned`() {
        installLogStub()
        val bus = RecordingEventBus()
        val warnings = mutableListOf<String>()
        val receiver = ScreenEventReceiver(bus, FakeBroadcastRegistration(), warnings::add)

        receiver.handleAction("android.intent.action.SOMETHING_ELSE")

        assertTrue(bus.sent.isEmpty(), "未知 action 不得被误当成屏幕事件：${bus.sent}")
        assertEquals(1, warnings.size, "未知 action 必须记警告而不是静默忽略")
    }

    @Test
    fun `an unknown action warns only once per window`() {
        installLogStub()
        val registration = FakeBroadcastRegistration(now = 1_000L)
        val warnings = mutableListOf<String>()
        val receiver = ScreenEventReceiver(RecordingEventBus(), registration, warnings::add)

        // 周期性的亮/熄屏会让未知 action 反复到达：窗口内只许告警一次，否则日志会被刷爆
        repeat(5) { receiver.handleAction("android.intent.action.UNEXPECTED") }
        assertEquals(1, warnings.size, "同一 action 在窗口内必须只告警一次")

        // 越过窗口后允许再告警一次（长期观测需要，而不是永久静音）
        registration.now = 1_000L + ScreenEventReceiver.WARN_WINDOW_MILLIS
        receiver.handleAction("android.intent.action.UNEXPECTED")
        assertEquals(2, warnings.size, "越过窗口后应再次告警")
    }

    @Test
    fun `distinct unknown actions each warn once`() {
        installLogStub()
        val warnings = mutableListOf<String>()
        val receiver = ScreenEventReceiver(RecordingEventBus(), FakeBroadcastRegistration(), warnings::add)

        receiver.handleAction("android.intent.action.AAA")
        receiver.handleAction("android.intent.action.BBB")

        assertEquals(2, warnings.size, "不同 action 各自独立计数")
    }

    @Test
    fun `onReceive tolerates a null action`() {
        installLogStub()
        val bus = RecordingEventBus()
        val receiver = ScreenEventReceiver(bus, FakeBroadcastRegistration())
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns null

        // 接收器崩溃会被系统记入日志并可能被限流，因此空 action 必须安全返回
        receiver.onReceive(mockk<Context>(relaxed = true), intent)

        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun `action literals match the framework when android classes are loadable`() {
        // 与 BootEventReceiverTest 同款守卫：纯 JVM 下 Intent 的静态字段不可用 → 跳过。
        // 注意 Kotlin 会内联编译期常量，故本断言只在真机/Robolectric 环境下才有意义。
        val framework =
            try {
                listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT)
            } catch (error: RuntimeException) {
                assumeTrue(false, "Android 框架类在纯 JVM 下不可用，跳过常量一致性断言：${error.message}")
                return
            }

        assertEquals(
            framework,
            listOf(
                ScreenEventReceiver.ACTION_SCREEN_ON,
                ScreenEventReceiver.ACTION_SCREEN_OFF,
                ScreenEventReceiver.ACTION_USER_PRESENT,
            ),
        )
    }

    private fun installLogStub() = EventTestLog.install()
}

/**
 * `android.util.Log` 的固定桩（每个用例都要调用：`mockkStatic` 不跨用例持久）。
 *
 * 抽成共享工具是因为 3c.1 有 4 个测试类需要它；放在本文件避免额外的脚手架文件。
 */
internal object EventTestLog {
    fun install() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
