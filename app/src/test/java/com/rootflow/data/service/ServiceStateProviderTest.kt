package com.rootflow.data.service

import android.util.Log
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.service.ForegroundState
import dagger.Lazy
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `ForegroundServiceController` 的 `ServiceStateProvider` 实现（阶段 6b）。
 *
 * ## 这份测试在守什么
 * 主页的服务状态卡与事件源列表**没有 UI 测试**（已批准决策 B：不引 androidTest），
 * 因此"卡片上显示的东西对不对"在自动化层面**只能**靠这里的断言。
 * 三条最关键的不变量：
 * 1. **口径唯一**：`state`（通知用的快照）与 `sources`（卡片用的列表）必须同源，
 *    否则会出现"通知说 6/7、卡片说 5/7"——那种不一致只在真机上肉眼可见
 * 2. **未注册时如实报 `Idle` + 空列表**：不假装在跑，也不编造"未启动"的原因
 * 3. **无活锁**：`register()` 的有界追赶必须**收敛**（§9.4：无界轮询会让
 *    `advanceUntilIdle()` 永不返回），下面 `advanceUntilIdle()` 能返回本身就是断言
 *
 * ## ★ 必须自己打桩 `android.util.Log`（2026-09-25 补，勿删）
 * 本类经由 `ForegroundServiceController` 调 `Log.i`（`register()` 每次都打），
 * 而单测**没有**开 `returnDefaultValues`（`AGENT_PROTOCOL.md §5.10`）
 * ⇒ 不打桩时单独跑必炸（`Method i in android.util.Log not mocked`）。
 *
 * 它此前"能跑过"是因为全量跑时别的类已经装了静态桩 ——
 * **本类是这条纪律的第 5 个受害者**（前四个见 `AGENT_PROTOCOL.md §5.10` 的清单）。
 * 之所以这次才发现：它只有在**单独**跑时才暴露，而本轮改动让它进入了被单独验证的名单。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStateProviderTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher)

    private val registry = FakeEventSourceRegistry()
    private val breaker = FakeControllerCircuitBreaker()
    private val notifier = FakeServiceNotifier()

    private val controller =
        ForegroundServiceController(
            eventSourceRegistry = registry,
            circuitBreaker = Lazy { breaker },
            notifier = notifier,
            daemonSupervisor = RecordingDaemonSupervisor(),
            // 阶段 12c 的两个新依赖：本类测的是"状态有没有如实发布给 UI"，
            // 因此给记录型假件（它们的语义由 ForegroundServiceControllerTest 覆盖）。
            keepAliveWatchdog = RecordingKeepAliveWatchdog(),
            safeModeSnapshot = SafeModeSnapshot(),
            scope = controllerScope,
        )

    @AfterEach
    fun tearDown() {
        controllerScope.cancel()
    }

    @Test
    @DisplayName("未注册时如实报 Idle，事件源列表为空（不假装在跑）")
    fun `idle before register with an empty source list`() {
        assertEquals(ForegroundState.Idle, controller.state.value, "register 之前必须是 Idle")
        assertTrue(controller.sources.value.isEmpty(), "服务没起来时事件源列表必须为空，而不是全部 NotStarted")
    }

    @Test
    @DisplayName("register 之后 state 与 sources 同时就绪，且计数口径一致")
    fun `register publishes running state and the source list from one snapshot`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()

            val state = controller.state.value
            assertTrue(state is ForegroundState.Running, "register 之后必须是 Running，实际=$state")
            state as ForegroundState.Running

            val sources = controller.sources.value
            assertEquals(4, sources.size, "假注册表报了 4 个源")
            assertEquals(
                sources.count { it.state.isRunning },
                state.enabled,
                "state.enabled 与 sources 的 Running 条数必须同源（否则通知与卡片会漂移）",
            )
            assertEquals(sources.size, state.total)
            assertFalse(state.safeMode, "熔断器未触发时不得报安全模式")
        }

    @Test
    @DisplayName("sources 按 sourceId 升序（与真机日志逐行可比对）")
    fun `sources are ordered by sourceId`() =
        runTest(testDispatcher) {
            // 用一个**遵守 EventSourceRegistry 契约**（"按 sourceId 升序"）的注册表：
            // 共享的 FakeEventSourceRegistry 按传入顺序返回，不模拟那条契约，
            // 因此这里必须用局部假件 —— 否则测的是假件的顺序，而不是本类把顺序透出。
            val sorted =
                OrderedStubRegistry(
                    listOf(
                        "wifi" to true,
                        "battery" to true,
                        "screen" to false,
                        "power" to true,
                    ),
                )
            val local = newController(sorted)

            local.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                listOf("battery", "power", "screen", "wifi"),
                local.sources.value.map { it.sourceId },
                "控制器必须原样透出注册表的顺序（真机靠它逐行比对）",
            )
        }

    @Test
    @DisplayName("缺权限的源在列表里带出具体原因（决策 7：不得笼统）")
    fun `unavailable sources expose the concrete reason`() =
        runTest(testDispatcher) {
            val withRejected =
                FakeEventSourceRegistry(
                    sources = listOf("screen" to true, "usage_stats" to false),
                )
            val local = newController(withRejected)

            local.register()
            testDispatcher.scheduler.runCurrent()

            val rejected = local.sources.value.single { it.sourceId == "usage_stats" }
            val reason = (rejected.state as EventSourceState.Unavailable).reason
            assertTrue(reason.startsWith("permission missing:"), "原因必须具体到权限，实际=$reason")
            // 被挡下的源不得计入 running
            assertEquals(1, (local.state.value as ForegroundState.Running).enabled)
            assertEquals(2, (local.state.value as ForegroundState.Running).total)
        }

    @Test
    @DisplayName("安全模式变化会重新发布，但不改变事件源计数")
    fun `safe mode change republishes the state without touching source counts`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            val before = controller.state.value as ForegroundState.Running
            assertEquals(1, registry.startCalls, "重复发布不得重复启动事件源")

            breaker.safeModeFlow.value = true
            testDispatcher.scheduler.runCurrent()

            val after = controller.state.value as ForegroundState.Running
            assertTrue(after.safeMode, "安全模式必须传播到 state（banner 与通知都读它）")
            assertEquals(before.enabled, after.enabled, "安全模式不改变事件源计数")
            assertEquals(before.total, after.total)
            assertEquals(1, registry.startCalls, "重新发布状态绝不能再次 start 注册表")
        }

    @Test
    @DisplayName("unregister 之后回到 Idle 且事件源列表清空")
    fun `unregister returns to idle and clears the source list`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            assertTrue(controller.sources.value.isNotEmpty())

            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            assertEquals(ForegroundState.Idle, controller.state.value)
            assertTrue(controller.sources.value.isEmpty(), "服务停了就不该再显示任何事件源")
            assertEquals(1, registry.stopCalls)
        }

    @Test
    @DisplayName("重复 register 幂等：不重复发布、不重复启动注册表")
    fun `duplicate register does not republish or restart the registry`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            val stateAfterFirst = controller.state.value

            controller.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, registry.startCalls, "sticky 重复投递不得重复启动事件源")
            assertEquals(stateAfterFirst, controller.state.value, "幂等路径不得改变状态值")
            assertTrue(controller.isRegistered)
        }

    @Test
    @DisplayName("有界追赶必然收敛（advanceUntilIdle 能返回 ⇒ 无 §9.4 活锁）")
    fun `the bounded catch-up converges`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            val afterRegister = controller.state.value

            // 若能返回，就证明追赶是**有界**的；无界轮询会让这一行永不返回（测试挂死）。
            advanceUntilIdle()

            assertEquals(afterRegister, controller.state.value, "追赶只重复发布同一份快照，不应改变状态")
            assertEquals(1, registry.startCalls, "追赶不得重启注册表")
            assertTrue(controller.sources.value.isNotEmpty(), "追赶之后列表仍应在")
        }

    @Test
    @DisplayName("注册表 status() 抛异常时状态降级为空列表，且不炸掉控制器")
    fun `a throwing registry degrades to an empty source list`() =
        runTest(testDispatcher) {
            val exploding = ThrowingStatusRegistry()
            val local = newController(exploding)

            local.register()
            testDispatcher.scheduler.runCurrent()

            assertTrue(local.isRegistered, "注册表报错不得让控制器留在未注册态")
            assertTrue(local.sources.value.isEmpty(), "取不到源状态时如实报空，不编造")
            val state = local.state.value
            assertTrue(state is ForegroundState.Running)
            assertEquals(0, (state as ForegroundState.Running).enabled)
        }

    /** 用给定注册表构造一个控制器（复用本类的其余假件）。 */
    private fun newController(registry: com.rootflow.data.event.EventSourceRegistry): ForegroundServiceController =
        ForegroundServiceController(
            eventSourceRegistry = registry,
            circuitBreaker = Lazy { breaker },
            notifier = FakeServiceNotifier(),
            daemonSupervisor = RecordingDaemonSupervisor(),
            keepAliveWatchdog = RecordingKeepAliveWatchdog(),
            safeModeSnapshot = SafeModeSnapshot(),
            scope = controllerScope,
        )

    /** `status()` 抛异常的注册表（覆盖"源状态取不到"的降级路径）。 */
    private class ThrowingStatusRegistry : com.rootflow.data.event.EventSourceRegistry {
        override fun start(): Unit = Unit

        override fun stop(): Unit = Unit

        override fun status(): List<com.rootflow.domain.event.EventSourceStatus> =
            throw IllegalStateException("status boom")

        /**
         * P5c：本 stub 的 [status] 恒抛异常，因此状态流只能给空列表 ——
         * 与"取不到状态时如实报空"的语义一致（正是本类的用例要覆盖的降级形态）。
         */
        override val statusFlow: kotlinx.coroutines.flow.StateFlow<List<com.rootflow.domain.event.EventSourceStatus>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }

    /**
     * 遵守"按 `sourceId` 升序"契约的最小注册表（`EventSourceRegistry.status()` 的 KDoc 要求）。
     *
     * 与共享 `FakeEventSourceRegistry` 的差别只有这一点：后者按传入顺序返回，
     * 因此不能用来验证"顺序被透出"。
     */
    private class OrderedStubRegistry(
        private val entries: List<Pair<String, Boolean>>,
    ) : com.rootflow.data.event.EventSourceRegistry {
        private var started: Boolean = false

        /**
         * P5c：与 [status] 同源，`start` 后推一次。
         *
         * 控制器现在会转发这个流（`statusSubscription`），因此它必须给出**同一份**
         * 有序快照 —— 否则用例测到的会是"流里那份"，而不是本类要验证的顺序透出。
         */
        private val _statusFlow =
            kotlinx.coroutines.flow.MutableStateFlow(
                emptyList<com.rootflow.domain.event.EventSourceStatus>(),
            )

        override val statusFlow: kotlinx.coroutines.flow.StateFlow<List<com.rootflow.domain.event.EventSourceStatus>> =
            _statusFlow

        override fun start() {
            started = true
            _statusFlow.value = status()
        }

        override fun stop() {
            started = false
            _statusFlow.value = status()
        }

        override fun status(): List<com.rootflow.domain.event.EventSourceStatus> =
            entries
                .sortedBy { it.first }
                .map { (id, running) ->
                    com.rootflow.domain.event.EventSourceStatus(
                        sourceId = id,
                        state =
                            when {
                                !started -> EventSourceState.NotStarted
                                running -> EventSourceState.Running
                                else -> EventSourceState.Unavailable("permission missing: test")
                            },
                    )
                }
    }
}
