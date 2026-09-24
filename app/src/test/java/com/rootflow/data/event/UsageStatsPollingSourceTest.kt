package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.ForegroundDetector
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `UsageStatsPollingSource` 单测（阶段 3c.2）。
 *
 * ## 覆盖的三个承诺
 * 1. **按 2000ms 轮询**（需求 §2.1：1~2s；取上限省电）
 * 2. **息屏暂停 / 亮屏恢复**（需求 §2.1：「仅前台时轮询」）——且**暂停期间不发 `AppBackground`**
 * 3. **`A→B` 产出两条事件**（同一状态机给出 `left` 与 `entered`）
 *
 * ## §9 遵守要点
 * - 用 **`advanceTimeBy`** 而非 `advanceUntilIdle()`（§9.1：后者会把"暂停期间"也推完，
 *   断言必然失真）。推进多少就断言多少。
 * - 永不结束的流订阅（屏幕事件流）由 `stop()` 取消；**每个用例结尾都必须 `stop()`**，
 *   否则 `runTest` 会等那个永不结束的收集（§9.2 的同款陷阱）。
 * - 轮询循环是 `delay` 驱动的确定循环，不是"空闲超时自旋"，因此不触发 §9.4 的活锁。
 */
class UsageStatsPollingSourceTest {
    /** 记录型事件总线：可注入屏幕事件，同时记录源发出的全部事件。 */
    private class FakeBus : EventBus {
        private val flow = MutableSharedFlow<SystemEvent>(replay = 0, extraBufferCapacity = 64)
        val sent = mutableListOf<SystemEvent>()

        override fun send(event: SystemEvent): Boolean {
            sent += event
            return flow.tryEmit(event)
        }

        override fun events(): Flow<SystemEvent> = flow

        override fun droppedCount(): Long = 0L

        /** 注入一次系统屏幕事件（源据此暂停/恢复）。 */
        fun emit(event: SystemEvent) {
            flow.tryEmit(event)
        }
    }

    private fun source(
        scope: TestScope,
        reader: FakeUsageStatsReader,
        bus: FakeBus,
    ) = UsageStatsPollingSource(
        reader = reader,
        eventBus = bus,
        scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler)),
        detector = ForegroundDetector(),
        pollIntervalMillis = UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS,
        clock = { 1_000L },
    )

    @Test
    fun `default poll interval matches the requirement`() {
        assertEquals(2_000L, UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS, "需求 §2.1：1~2s，取上限")
        assertEquals("usage_stats", UsageStatsPollingSource.SOURCE_ID)
    }

    @Test
    fun `the source requires PACKAGE_USAGE_STATS`() {
        val source =
            UsageStatsPollingSource(
                reader = FakeUsageStatsReader(foregroundScript("com.a")),
                eventBus = FakeBus(),
                scope = CoroutineScope(StandardTestDispatcher()),
                detector = ForegroundDetector(),
            )

        assertEquals(
            setOf(AndroidPermission.PACKAGE_USAGE_STATS),
            source.requiredPermissions,
            "需求 §2.1：app_foreground 依赖 PACKAGE_USAGE_STATS（缺失时由 registry 挡下）",
        )
    }

    @Test
    fun `start subscribes to screen events and polls immediately`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a"))
            val bus = FakeBus()
            val source = source(this, reader, bus)

            source.start()
            testScheduler.runCurrent()

            assertTrue(source.status().state.isRunning)
            assertEquals(1, reader.readCalls, "start() 后应立即轮询一次")
            assertEquals(1, reader.resetCalls, "start() 必须重置游标（不重放历史事件）")
            assertTrue(bus.sent.isEmpty(), "首次快照只建立基线，不上报")

            source.stop()
        }

    @Test
    fun `polling repeats at the configured interval`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()
            assertEquals(1, reader.readCalls)

            // 推进 3 个间隔 → 再轮询 3 次（§9.1：用 advanceTimeBy，不用 advanceUntilIdle）
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS * 3)
            testScheduler.runCurrent()

            assertEquals(4, reader.readCalls, "每 2000ms 一轮")

            source.stop()
        }

    @Test
    fun `screen off pauses the polling loop`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null, null, null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            bus.emit(SystemEvent.ScreenOff)
            testScheduler.runCurrent()
            val afterPause = reader.readCalls
            assertFalse(source.isPolling, "息屏后必须停止轮询")

            // 推进 3 个间隔：若暂停失效，这里会多出 3 次读取
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS * 3)
            testScheduler.runCurrent()

            assertEquals(afterPause, reader.readCalls, "暂停期间不得再读取前台状态")

            source.stop()
        }

    @Test
    fun `screen off does not emit app background`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            bus.emit(SystemEvent.ScreenOff)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS * 2)
            testScheduler.runCurrent()

            assertTrue(
                bus.sent.isEmpty(),
                "息屏 ≠ 用户切走应用：发 AppBackground 会让「退到后台就清理」这类脚本每次锁屏被误触发：${bus.sent}",
            )

            source.stop()
        }

    @Test
    fun `screen on resumes polling`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null, null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            bus.emit(SystemEvent.ScreenOff)
            testScheduler.runCurrent()
            val paused = reader.readCalls

            bus.emit(SystemEvent.ScreenOn)
            testScheduler.runCurrent()
            assertTrue(source.isPolling, "亮屏后必须恢复轮询")
            assertEquals(paused + 1, reader.readCalls, "恢复后立即轮询一次")

            source.stop()
        }

    @Test
    fun `repeated screen off and on cycles stay stable`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            repeat(3) {
                bus.emit(SystemEvent.ScreenOff)
                testScheduler.runCurrent()
                assertFalse(source.isPolling)
                bus.emit(SystemEvent.ScreenOn)
                testScheduler.runCurrent()
                assertTrue(source.isPolling)
            }

            source.stop()
        }

    @Test
    fun `a foreground change emits background then foreground`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", "com.b"))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS)
            testScheduler.runCurrent()

            assertEquals(2, bus.sent.size, "A→B 必须产出两条事件：${bus.sent}")
            assertEquals("app_background", bus.sent[0].eventId, "顺序固定：先 Left")
            assertEquals("app_foreground", bus.sent[1].eventId)
            assertEquals(SystemEvent.AppBackground("com.a"), bus.sent[0])
            assertEquals(SystemEvent.AppForeground("com.b"), bus.sent[1])

            source.stop()
        }

    @Test
    fun `the same package across polls emits nothing`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", "com.a", "com.a", "com.a"))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS * 3)
            testScheduler.runCurrent()

            assertTrue(bus.sent.isEmpty(), "同包名不得重复上报：${bus.sent}")

            source.stop()
        }

    @Test
    fun `an unavailable read warns and emits nothing`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(listOf(UsageStatsRead.Unavailable, UsageStatsRead.Unavailable))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS)
            testScheduler.runCurrent()

            assertTrue(bus.sent.isEmpty(), "不可用时不得上报任何事件：${bus.sent}")
            assertEquals(2, reader.readCalls, "不可用不得终止轮询（权限可能随后恢复）")

            source.stop()
        }

    @Test
    fun `a reader exception does not stop the loop`() =
        runTest {
            installLogStub()
            val reader =
                object : com.rootflow.data.event.UsageStatsReader {
                    var calls = 0
                    override val cursorMillis: Long = 0L

                    override fun read(currentMillis: Long): UsageStatsRead {
                        calls++
                        if (calls == 1) throw IllegalStateException("boom")
                        return UsageStatsRead.Foreground(packageName = "com.a")
                    }

                    override fun reset() = Unit
                }
            val bus = FakeBus()
            val source =
                UsageStatsPollingSource(
                    reader = reader,
                    eventBus = bus,
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    detector = ForegroundDetector(),
                    pollIntervalMillis = UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS,
                    clock = { 1_000L },
                )
            source.start()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS)
            testScheduler.runCurrent()

            assertEquals(2, reader.calls, "一次异常不得让轮询永久停止")

            source.stop()
        }

    @Test
    fun `stop cancels both jobs and further screen events are ignored`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", null, null))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()
            val before = reader.readCalls

            source.stop()
            assertFalse(source.status().state.isRunning)
            assertFalse(source.isPolling)

            bus.emit(SystemEvent.ScreenOn)
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS * 2)
            testScheduler.runCurrent()

            assertEquals(before, reader.readCalls, "stop() 后不得再轮询")
        }

    @Test
    fun `stop before start is safe`() {
        val reader = FakeUsageStatsReader(foregroundScript("com.a"))
        val source =
            UsageStatsPollingSource(
                reader = reader,
                eventBus = FakeBus(),
                scope = CoroutineScope(StandardTestDispatcher()),
                detector = ForegroundDetector(),
            )

        source.stop()

        assertEquals(0, reader.readCalls)
        assertEquals(0, reader.resetCalls, "未启动时 stop() 不得触碰读取端口")
    }

    @Test
    fun `start is idempotent`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a"))
            val bus = FakeBus()
            val source = source(this, reader, bus)

            source.start()
            testScheduler.runCurrent()
            source.start()
            testScheduler.runCurrent()

            assertEquals(1, reader.resetCalls, "重复 start() 不得重复初始化")

            source.stop()
        }

    @Test
    fun `resuming after screen off treats the first snapshot as a new baseline`() =
        runTest {
            installLogStub()
            val reader = FakeUsageStatsReader(foregroundScript("com.a", "com.a"))
            val bus = FakeBus()
            val source = source(this, reader, bus)
            source.start()
            testScheduler.runCurrent()

            bus.emit(SystemEvent.ScreenOff)
            testScheduler.runCurrent()
            bus.emit(SystemEvent.ScreenOn)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(UsageStatsPollingSource.DEFAULT_POLL_INTERVAL_MILLIS)
            testScheduler.runCurrent()

            assertTrue(
                bus.sent.isEmpty(),
                "亮屏恢复后首个快照是新基线：不得报一对 Left/Entered 噪声：${bus.sent}",
            )

            source.stop()
        }

    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
