package com.rootflow.data.service

import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.KeepAliveHealthCheck
import com.rootflow.domain.service.ServiceChannels
import com.rootflow.domain.service.ServiceNotificationText
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 前台服务控制器的单测（阶段 5，需求 §7）。
 *
 * ## 为什么这些用例是本阶段的主要自动化护栏
 * 控制器是"事件源启停 + 通知投影"的**唯一**编排点，而它碰的每一样东西在真机上都要靠肉眼与
 * logcat 判读。把它做成纯 Kotlin 后，本文件覆盖的正是阶段 5 的核心语义：
 * 所有权、幂等、时序、告警路由。
 *
 * ## 协程纪律（`AGENT_PROTOCOL.md §9`）
 * - 驱动协程用 `testDispatcher.scheduler.runCurrent()`，**不用** `advanceUntilIdle()`
 *   （§9.1：它会把虚拟时钟推到所有待处理任务完成，越过我们想观测的那一刻）
 * - **控制器的订阅作用域与 `runTest` 必须共用同一个 [TestDispatcher]**（§9.3）：
 *   否则两者各有独立虚拟时钟，`runCurrent()` 推不动订阅协程，断言会在协程跑起来之前执行，
 *   表现得像"功能没做"（阶段 2 实测踩过这个坑）
 * - 订阅作业永不返回，因此它跑在**独立 Job + 共用调度器**上，由 `@AfterEach` 取消
 *   （§9.2 的取舍：`backgroundScope` 里的排队任务不被推动，而这里恰恰需要推它）
 * - 控制器**没有**周期性超时空转（§9.4），因此不存在活锁风险
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundServiceControllerTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher)

    private val registry = FakeEventSourceRegistry()
    private val breaker = FakeControllerCircuitBreaker()
    private val notifier = FakeServiceNotifier()

    private val controller =
        ForegroundServiceController(
            eventSourceRegistry = registry,
            // `Lazy` 是 Dagger 的断环手段（见控制器类 KDoc）；单测里直接给一个即时求值的 Lazy
            circuitBreaker = Lazy { breaker },
            notifier = notifier,
            daemonSupervisor = RecordingDaemonSupervisor(),
            scope = controllerScope,
        )

    @AfterEach
    fun tearDown() {
        // 订阅作业永不返回：必须显式取消，否则测试进程留着活动协程
        controllerScope.cancel()
    }

    @Test
    fun `register starts the registry, builds channels and pushes the first notification`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, registry.startCalls, "事件源的启停必须由控制器驱动（D4 关闭的前提）")
            assertEquals(1, notifier.ensureChannelsCalls, "渠道必须在任何前台化之前建好")
            assertTrue(notifier.updates.isNotEmpty(), "register 返回时通知就必须已经正确（不留引导态窗口）")

            val first = notifier.updates.first()
            assertEquals(foregroundChannel, first.channelId)
            assertEquals(ServiceNotificationText.TITLE_SERVICE, first.title)
            assertTrue(first.text.contains("事件源 4/4"), "实际=${first.text}")
            assertTrue(controller.isRegistered)
        }

    @Test
    fun `register is idempotent so a sticky redelivery cannot double start the registry`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            val afterFirst = notifier.updates.size

            // sticky 重启会再次投递 onStartCommand ⇒ 控制器会再次被调用
            controller.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, registry.startCalls, "重复 register 不得重复启动事件源（会重复注册接收器）")
            assertEquals(1, notifier.ensureChannelsCalls)
            assertEquals(afterFirst, notifier.updates.size, "幂等路径不得重复投递通知")
        }

    @Test
    fun `safe mode from the circuit breaker switches the persistent notification to the alert channel`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            assertEquals(ServiceChannels.FOREGROUND, notifier.last?.channelId)

            breaker.safeModeFlow.value = true
            breaker.reasonFlow.value = TripReason.Manual
            testDispatcher.scheduler.runCurrent()

            val last = notifier.last
            assertEquals(ServiceChannels.SAFE_MODE, last?.channelId)
            assertEquals(ServiceNotificationText.TITLE_SAFE_MODE, last?.title)
            assertTrue(last?.alert == true)
        }

    @Test
    fun `restore returns the persistent notification to the running state`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            breaker.safeModeFlow.value = true
            breaker.reasonFlow.value = TripReason.Manual
            testDispatcher.scheduler.runCurrent()

            breaker.reasonFlow.value = null
            breaker.safeModeFlow.value = false
            testDispatcher.scheduler.runCurrent()

            val last = notifier.last
            assertEquals(ServiceChannels.FOREGROUND, last?.channelId)
            assertEquals(ServiceNotificationText.TITLE_SERVICE, last?.title)
            assertFalse(last?.alert == true)
        }

    @Test
    fun `unregister stops the registry, cancels the subscription and cancels the alert`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()

            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, registry.stopCalls, "服务停止必须一并停止事件源（否则动态源会在服务没了之后继续转发）")
            assertFalse(controller.isRegistered)
            // 常驻通知的撤销由 Service.stopForeground 负责（那是 Android 侧的事），
            // 控制器只负责撤销**告警**通知
            assertEquals(1, notifier.cancelAlertCalls)
        }

    @Test
    fun `no notification is pushed after unregister`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            controller.unregister()
            testDispatcher.scheduler.runCurrent()
            val before = notifier.updates.size

            // 订阅已取消：此后任何状态变化都不得再产生通知
            breaker.safeModeFlow.value = true
            testDispatcher.scheduler.runCurrent()

            assertEquals(before, notifier.updates.size, "取消订阅后不得再发通知（否则会显示与真实状态不符的内容）")
        }

    @Test
    fun `unregister before register is a safe no-op`() =
        runTest(testDispatcher) {
            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            assertEquals(0, registry.stopCalls, "从未注册时 stop 不得被调用")
            assertEquals(0, notifier.cancelAlertCalls)
        }

    @Test
    fun `re-register after unregister works and does not accumulate subscriptions`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            controller.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(2, registry.startCalls, "停止后重新注册必须真的重新启动事件源")
            breaker.safeModeFlow.value = true
            breaker.reasonFlow.value = TripReason.Manual
            testDispatcher.scheduler.runCurrent()

            // 若旧订阅没被取消，这里会出现两条内容相同但重复的投递
            val safeModeUpdates = notifier.updates.count { it.channelId == ServiceChannels.SAFE_MODE }
            assertEquals(1, safeModeUpdates, "旧订阅必须已被取消（否则同一变化会被投递两次）")
        }

    @Test
    fun `safe mode alert goes through the sink even when the service is not registered`() {
        // 熔断的第 5 步是"发送高优先级通知"：服务没注册时静默返回 = "熔断了但用户毫无感知"
        assertFalse(controller.isRegistered)

        controller.onSafeModeAlert(TripReason.Bootloop(crashes = 3))

        assertEquals(1, notifier.alerts.size)
        val alert = notifier.alerts.single()
        assertEquals(ServiceChannels.SAFE_MODE, alert.channelId)
        assertEquals(ServiceNotificationText.TITLE_ALERT, alert.title)
        assertTrue(alert.alert)
        assertTrue(alert.text.contains(TripReason.Bootloop.KEY))
    }

    @Test
    fun `restore cancels the alert`() {
        controller.onSafeModeAlert(TripReason.Manual)
        controller.onSafeModeRestore()

        assertEquals(1, notifier.cancelAlertCalls)
    }

    @Test
    fun `registry failures never prevent the service from coming up`() {
        val exploding = FakeEventSourceRegistry(startThrows = true)
        val localNotifier = FakeServiceNotifier()
        val localController =
            ForegroundServiceController(
                eventSourceRegistry = exploding,
                circuitBreaker = Lazy { breaker },
                notifier = localNotifier,
                daemonSupervisor = RecordingDaemonSupervisor(),
                scope = controllerScope,
            )

        localController.register()

        assertTrue(localController.isRegistered, "注册失败不得让控制器留在未注册态")
        assertTrue(localNotifier.updates.isNotEmpty(), "事件源起不来也必须把通知投出去（否则用户看不到任何东西）")
    }

    @Test
    fun `a registry status change republishes the card and the notification`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()
            assertEquals(4, (controller.state.value as ForegroundState.Running).enabled)

            // 真机场景（P5c）：用户在编辑器里改订阅 ⇒ 某个源停掉 ⇒ 卡片与通知都要跟着变。
            // 修之前这里会一直停在 register 那一刻的 4/4 —— 真机上表现为
            // "日志里 screen started=true，主页仍显示 事件源 2/7"。
            registry.changeSources(listOf("battery" to true, "power" to true, "wifi" to true))
            testDispatcher.scheduler.runCurrent()

            val after = controller.state.value as ForegroundState.Running
            assertEquals(3, after.enabled, "registry 推了新状态，卡片必须转发（否则就是真机的 UI 不同步）")
            assertEquals(3, after.total)
            assertEquals(3, controller.sources.value.size, "源列表也要跟着缩短")
        }

    @Test
    fun `notification content degrades honestly when notifications are blocked`() =
        runTest(testDispatcher) {
            notifier.visible = false

            controller.register()
            testDispatcher.scheduler.runCurrent()

            val text = notifier.updates.first().text
            assertTrue(
                text.contains(ServiceNotificationText.NOTIFICATIONS_BLOCKED_SUFFIX),
                "通知被系统拒绝时必须如实说明，实际=$text",
            )
        }

    @Test
    fun `health check is explicitly marked as not implemented`() {
        // 裁定 ①：本阶段不实现健康检查。这两条是"设计已留、实现未做"的显式证据，
        // 而不是让后续会话以为漏做了（真正的证据是真机日志里的 KEEPALIVE_HEALTH_TODO）。
        assertEquals(15 * 60 * 1000L, KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS)
        assertEquals("KEEPALIVE_HEALTH_TODO", KeepAliveHealthCheck.HEALTH_CHECK_TODO_MARKER)
    }

    @Test
    fun `idle state is reported before register so the bootstrap notification cannot lie`() {
        val bootstrap =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Idle,
                reason = null,
                notificationsGranted = true,
            )

        assertEquals("服务未运行", bootstrap.text)
        assertNull(controller.currentNotification(), "register 之前没有真实模型")
        // 未注册时 status() 必须如实报 NotStarted，而不是假装在跑
        assertTrue(registry.status().all { !it.state.isRunning })
    }
}
