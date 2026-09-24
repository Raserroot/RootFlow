package com.rootflow.data.service

import com.rootflow.domain.event.EventSourceState
import dagger.Lazy
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSourceLifecycleOwnershipTest {
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
                    scope = controllerScope,
                )

            localController.register()
            testDispatcher.scheduler.runCurrent()

            val text = localNotifier.updates.first().text
            assertTrue(text.contains("事件源 4/7"), "部分可用时必须如实上报，实际=$text")
            assertFalse(text.contains("7/7"), "不得把不可用的源算成可用，实际=$text")
        }
}
