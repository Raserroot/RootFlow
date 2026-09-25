package com.rootflow.data.service

import android.util.Log
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.domain.event.EventSourceState
import dagger.Lazy
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 事件源生命周期的**所有权**单测（阶段 5，`PROJECT_STATE.md` 偏离项 **D4** 的护栏）。
 *
 * ## 为什么需要单独一个文件
 * 阶段 3c.1~4 里 `EventSourceRegistry.start()` 由 `RootFlowApp.onCreate` 无条件调用。
 * 阶段 5 把它移交给 `ForegroundServiceController`（需求 §2.1：动态注册的源「须驻留前台服务」）。
 * 这次移交有一个**完全静默**的失败形态：
 *
 * > `EventSourceRegistryImpl.start()` 是幂等的（`started` 标志）。
 * > 若 Application 哪天"顺手"又调了一次 `start()`，服务的 `start()` 就变成空操作
 * > ⇒ 服务停了而事件源还活着 —— 正是 D4 要修的那个形态，**且没有任何编译错误或运行期异常**。
 *
 * `EventSourceRegistryImpl` 自身的幂等性已有 `EventSourceRegistryTest` 覆盖；
 * 本文件覆盖的是**编排侧**：谁在什么时候调它、调用几次、之后状态如何。
 *
 * 协程纪律同 [ForegroundServiceControllerTest]：共用同一 [TestDispatcher]，用 `runCurrent()`
 * 推进一步而不是 `advanceUntilIdle()`（`AGENT_PROTOCOL.md §9.1 / §9.3`）。
 *
 * ## ★ 必须自己打桩 `android.util.Log`（2026-09-25 补，勿删）
 * 本类经由 `ForegroundServiceController` 调 `Log.i`，而单测**没有**开 `returnDefaultValues`
 * （`AGENT_PROTOCOL.md §5.10`）⇒ 不打桩时任何一条注册路径都会抛
 * `RuntimeException: Method i in android.util.Log not mocked`。
 *
 * 它此前"能跑过"是因为**别的测试类**（字母序在前的那些）已经装了静态桩 ——
 * 那让本类的成败取决于**执行顺序**：单独跑本类必炸，全量跑却绿。
 * 同一形态在 11e 补丁5 已经修过一次（`ForegroundServiceControllerTest`），
 * 这次是同一个坑的第二个受害者：**过滤跑子集时它立刻暴露了**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSourceLifecycleOwnershipTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher)

    private val registry = FakeEventSourceRegistry()
    private val notifier = FakeServiceNotifier()
    private val controller =
        ForegroundServiceController(
            eventSourceRegistry = registry,
            circuitBreaker = Lazy { FakeControllerCircuitBreaker() },
            notifier = notifier,
            daemonSupervisor = RecordingDaemonSupervisor(),
            // 阶段 12c 的两个新依赖与"事件源所有权"无关：本类测的是"谁在启停源"，
            // 因此这里给**记录型**假件即可（它们的正确性由 ForegroundServiceControllerTest 覆盖）。
            keepAliveWatchdog = RecordingKeepAliveWatchdog(),
            safeModeSnapshot = SafeModeSnapshot(),
            scope = controllerScope,
        )

    @AfterEach
    fun tearDown() {
        controllerScope.cancel()
    }

    @Test
    fun `sources stay stopped until the controller registers`() {
        // 构造控制器、拿到注册表 —— 这一切都不得启动任何源。
        // 若哪天有人在 Application.onCreate 里补一句 start()，
        // 下面的 `register is the only starter` 会红（计数会变成 2）。
        assertTrue(registry.status().all { !it.state.isRunning }, "未注册前任何源都不得处于 Running")
        assertEquals(0, registry.startCalls)
    }

    @Test
    fun `register is the only starter and unregister is the only stopper`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, registry.startCalls, "只有控制器能启动事件源")
            assertTrue(registry.status().all { it.state.isRunning }, "服务在跑时全部源都应可用")

            controller.unregister()
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, registry.stopCalls, "只有控制器能停止事件源")
            assertTrue(
                registry.status().none { it.state.isRunning },
                "服务停止后不得有源仍在 Running（否则事件会在服务没了之后继续被转发）",
            )
        }

    @Test
    fun `status after stop reports NotStarted rather than pretending`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            // 决策 7：三态必须如实。停掉之后报 Running 会让真机判读与阶段 6 的 UI 都失去意义。
            assertTrue(registry.status().all { it.state == EventSourceState.NotStarted })
        }

    @Test
    fun `a partially available registry is reported honestly in the notification`() =
        runTest(testDispatcher) {
            // 7 个源里只有 4 个能启动（权限门挡下 3 个）：正文必须报 4/7 而不是 7/7
            val partial =
                FakeEventSourceRegistry(
                    sources =
                        listOf(
                            "battery" to true,
                            "power" to true,
                            "screen" to true,
                            "wifi" to true,
                            "time" to false,
                            "interval" to false,
                            "app_foreground" to false,
                        ),
                )
            val localNotifier = FakeServiceNotifier()
            val localController =
                ForegroundServiceController(
                    eventSourceRegistry = partial,
                    circuitBreaker = Lazy { FakeControllerCircuitBreaker() },
                    notifier = localNotifier,
                    daemonSupervisor = RecordingDaemonSupervisor(),
                    keepAliveWatchdog = RecordingKeepAliveWatchdog(),
                    safeModeSnapshot = SafeModeSnapshot(),
                    scope = controllerScope,
                )

            localController.register()
            testDispatcher.scheduler.runCurrent()

            val text = localNotifier.updates.first().text
            assertTrue(text.contains("事件源 4/7"), "部分可用时必须如实上报，实际=$text")
            assertFalse(text.contains("7/7"), "不得把不可用的源算成可用，实际=$text")
        }
}
