package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `WifiEventSource` 单测（阶段 3c.1，**决策 4 + 决策 12** 的语义）。
 *
 * ## 快照语义（读测试前先看这条）
 * - `snapshot(true)` = 默认网络是 Wi-Fi 且可达
 * - `snapshot(false)` = 默认网络是 Wi-Fi 但不可达
 * - `null` = **默认网络不是 Wi-Fi**（`onLost`，或切到了移动数据）
 *
 * ## 这些用例就是决策 12 本身
 * 决策 4 要求"必须按 `TRANSPORT_WIFI` 过滤"，否则"切到移动数据"会被误报为 Wi-Fi 变化；
 * 决策 12 指出**纯按 transport 过滤会丢掉真实的 Wi-Fi 断开**（`onLost` 时默认网络已不是 Wi-Fi），
 * 因此上报条件改为**派生 `connected` 与上次上报值是否不同**：
 * - 误报边：非 Wi-Fi 传输的来回变化 → **一次都不上报**
 * - 断开边：Wi-Fi 有 → 无（`onLost` 或换成蜂窝） → **必须**上报 `connected = false`
 */
class WifiEventSourceTest {
    @Test
    fun `non-wifi transport changes never emit`() {
        installLogStub()
        val bus = RecordingEventBus()
        // 当前就是蜂窝网络：探测不到 Wi-Fi 传输 → 基线未连接
        val monitor = FakeNetworkMonitor(probeResult = null)
        val source = WifiEventSource(monitor, bus)
        source.start()
        assertEquals(emptyList<SystemEvent>(), bus.sent.toList(), "未连 Wi-Fi 时启动不得有基线事件")

        // "切到移动数据"这类与 Wi-Fi 无关的变化一律不给快照（适配器返回 null）
        repeat(3) { monitor.emit(null) }

        assertEquals(
            emptyList<SystemEvent>(),
            bus.sent.toList(),
            "非 Wi-Fi 传输的变化必须被过滤掉（决策 4 要防的误报）：${bus.sent}",
        )
    }

    @Test
    fun `wifi disappearing emits disconnected even though the default network is no longer wifi`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()
        assertEquals(listOf(SystemEvent.WifiChanged(true)), bus.sent, "启动基线：已连 Wi-Fi 应发一条 true")

        // 决策 12 的核心用例：断开瞬间默认网络已变成蜂窝（onLost 甚至不带快照）。
        // 若按"transport 过滤后直接 return"实现，这条边会被静默丢弃 → wifi_changed 变成单向事件。
        monitor.emit(null)

        assertEquals(
            listOf(SystemEvent.WifiChanged(true), SystemEvent.WifiChanged(false)),
            bus.sent,
            "Wi-Fi 断开必须上报 connected=false",
        )
    }

    @Test
    fun `a wifi network that lost its internet capability also reports disconnected`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()

        // 仍是 Wi-Fi 传输，但已不可达（例如热点掉了外网）→ 也是一次真实的连接状态变化
        monitor.emit(snapshot(false))

        assertEquals(
            listOf(SystemEvent.WifiChanged(true), SystemEvent.WifiChanged(false)),
            bus.sent,
        )
    }

    @Test
    fun `repeating the same state is deduplicated`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()
        bus.sent.clear()

        repeat(5) { monitor.emit(snapshot(true)) }
        repeat(3) { monitor.emit(null) }
        repeat(4) { monitor.emit(null) }

        assertEquals(
            listOf(SystemEvent.WifiChanged(false)),
            bus.sent,
            "只有真正的状态变化才允许发射：${bus.sent}",
        )
    }

    @Test
    fun `wifi present but disconnected does not emit when it was already disconnected`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(false))
        val source = WifiEventSource(monitor, bus)
        source.start()

        // 基线即"不可达" → 后续同样是"不可达"的回调不得上报
        monitor.emit(snapshot(false))
        monitor.emit(snapshot(false))

        assertTrue(bus.sent.isEmpty(), "状态未变，不得发射：${bus.sent}")
    }

    @Test
    fun `start emits a baseline only when wifi is already connected`() {
        installLogStub()
        val connectedBus = RecordingEventBus()
        WifiEventSource(FakeNetworkMonitor(probeResult = snapshot(true)), connectedBus).start()
        assertEquals(
            listOf(SystemEvent.WifiChanged(true)),
            connectedBus.sent,
            "已连 Wi-Fi 时启动必须给出基线，让脚本知道当前状态",
        )

        val offlineBus = RecordingEventBus()
        WifiEventSource(FakeNetworkMonitor(probeResult = null), offlineBus).start()
        assertTrue(offlineBus.sent.isEmpty(), "未连 Wi-Fi 时启动不得产生噪声事件：${offlineBus.sent}")
    }

    @Test
    fun `onLost right after an offline baseline is not reported as a change`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = null)
        val source = WifiEventSource(monitor, bus)
        source.start()

        // 启动时已是"未连"：基线即 false，随后的 onLost 仍是 false → 不是变化，不得上报。
        // （若 start() 不写 false 基线，首条 onLost 会被判成 false != null 而多发一条事件。）
        monitor.emit(null)
        monitor.emit(null)

        assertTrue(bus.sent.isEmpty(), "已处于未连接状态时，重复的断开信号不得上报：${bus.sent}")
    }

    @Test
    fun `reconnect reports connected again`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()
        bus.sent.clear()

        monitor.emit(null)
        monitor.emit(snapshot(true))

        assertEquals(
            listOf(SystemEvent.WifiChanged(false), SystemEvent.WifiChanged(true)),
            bus.sent,
            "断开与重连两条边都必须上报",
        )
    }

    @Test
    fun `stop unregisters the callback and silences the source`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()
        bus.sent.clear()

        source.stop()
        assertFalse(monitor.hasCallback(), "stop() 必须让监控不再持有回调")
        monitor.emit(null)
        monitor.emit(snapshot(true))

        assertTrue(bus.sent.isEmpty(), "stop() 后不得再发射任何事件：${bus.sent}")
    }

    @Test
    fun `stop before start is safe and does not touch the monitor`() {
        installLogStub()
        val monitor = FakeNetworkMonitor()
        val source = WifiEventSource(monitor, RecordingEventBus())

        source.stop()

        assertEquals(0, monitor.stopCalls, "未启动时 stop() 不得调用监控的 stop()")
    }

    @Test
    fun `start is idempotent and a stop-start cycle works`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = null)
        val source = WifiEventSource(monitor, bus)

        source.start()
        source.start()
        assertEquals(1, monitor.startCalls, "重复 start() 不得重复注册回调")

        source.stop()
        source.start()
        assertEquals(2, monitor.startCalls, "stop() 后必须能重新 start()")

        // 两次基线都是"未连"，因此不产生事件
        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun `status follows the start-stop lifecycle`() {
        installLogStub()
        val source = WifiEventSource(FakeNetworkMonitor(), RecordingEventBus())

        assertEquals("wifi", source.sourceId)
        assertFalse(source.status().state.isRunning)

        source.start()
        assertTrue(source.status().state.isRunning)

        source.stop()
        assertFalse(source.status().state.isRunning)
    }

    @Test
    fun `the source requires ACCESS_NETWORK_STATE`() {
        val source = WifiEventSource(FakeNetworkMonitor(), RecordingEventBus())

        assertEquals(
            setOf(AndroidPermission.ACCESS_NETWORK_STATE),
            source.requiredPermissions,
            "需求 §2.1：wifi_changed 需要 ACCESS_NETWORK_STATE",
        )
    }

    @Test
    fun `a custom derivation is honoured`() {
        installLogStub()
        val bus = RecordingEventBus()
        val monitor = FakeNetworkMonitor(probeResult = null)
        // 可插拔派生的测试缝：这里用"总是已连接"的极端派生，证明它真的被使用
        val source = WifiEventSource(monitor, bus, WifiStateSource { true })
        source.start()

        assertEquals(
            listOf(SystemEvent.WifiChanged(true)),
            bus.sent,
            "自定义派生必须生效",
        )
    }

    @Test
    fun `concurrent callbacks from several threads emit exactly one event`() {
        installLogStub()
        val bus = RecordingEventBus()
        // 以"已连 Wi-Fi"为基线，再并发投递"断开"信号：恰好允许一条 false
        val monitor = FakeNetworkMonitor(probeResult = snapshot(true))
        val source = WifiEventSource(monitor, bus)
        source.start()
        bus.sent.clear()

        // NetworkCallback 在 ConnectivityManager 的系统线程回调；`lastReported` 的
        // "读过再写"必须原子，否则 200 次并发会发射多次（去重失效）。
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(200) { Callable { source.onCapabilitiesChanged(null) } }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertEquals(
            listOf(SystemEvent.WifiChanged(false)),
            bus.sent,
            "并发回调下去重必须仍然成立：${bus.sent}",
        )
    }

    /** 造一个"默认网络是 Wi-Fi"的快照；`null` 用 `emit(null)` 直接表达。 */
    private fun snapshot(connected: Boolean) = WifiNetworkSnapshot(connected = connected)

    /** 固定 `android.util.Log`（`ScreenEventSource.start()` 与本源都会打日志）。 */
    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
