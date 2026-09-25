package com.rootflow.data.service

import android.util.Log
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.KeepAliveHealthCheck
import com.rootflow.domain.service.ServiceChannels
import com.rootflow.domain.service.ServiceNotificationText
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
    init {
        // ★ 11e 补丁5：本类（经由 `ForegroundServiceController`）会调 `Log.i` / `Log.w`，
        //   但此前**没有**自己打桩 —— 它靠**别的测试类** `mockkStatic(Log)` 留下的静态桩
        //   才能跑过，于是结果取决于测试执行顺序：单独跑这个类必炸
        //   （`Method i in android.util.Log not mocked`），全量跑时又可能因顺序不同而红或绿。
        //   补上打桩后与另外 22 个类行为一致，也不再依赖执行顺序。
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher)

    private val registry = FakeEventSourceRegistry()
    private val breaker = FakeControllerCircuitBreaker()
    private val notifier = FakeServiceNotifier()

    private val daemonSupervisor = RecordingDaemonSupervisor()

    /** 阶段 12c：看门狗假件（四条边各一个计数，见 `RecordingKeepAliveWatchdog` 的 KDoc）。 */
    private val watchdog = RecordingKeepAliveWatchdog()

    /** 阶段 12c：安全模式三态快照（控制器是它的**运行期**写入点）。 */
    private val snapshot = SafeModeSnapshot()

    private val controller =
        ForegroundServiceController(
            eventSourceRegistry = registry,
            // `Lazy` 是 Dagger 的断环手段（见控制器类 KDoc）；单测里直接给一个即时求值的 Lazy
            circuitBreaker = Lazy { breaker },
            notifier = notifier,
            daemonSupervisor = daemonSupervisor,
            keepAliveWatchdog = watchdog,
            safeModeSnapshot = snapshot,
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

    /**
     * ★ **11e 补丁5：熔断停掉的常驻监管，恢复时必须被拉回来。**
     *
     * ## 它防的是什么（真机实测的真缺陷）
     * `onSafeModeAlert` 里有 `daemonSupervisor.stop()`，而恢复路径**只有**
     * `notifier.cancelAlert()` —— 停有关、启没有。后果：
     * 熔断 → 退出安全模式后，总开关显示「已打开」、脚本显示「已启用」、
     * 前台服务也显示「服务运行中」，**但那个常驻脚本就是不跑**，且一行错误都没有
     * （真机日志：等 40 秒零条 `DAEMON_*`，脚本进程数 0；重启 App 才恢复）。
     *
     * 根因是 `stop()` 停的**不是脚本进程本身，而是监管循环** ——
     * 脚本被杀只是顺带结果，真正被拆掉的是"会把它再拉起来"的机制。
     *
     * ## 为什么原来的用例没抓住
     * 既有的 `restore cancels the alert` **只断言 `cancelAlertCalls`**，
     * 从不看监管器的启停次数；而夹具里那个 `RecordingDaemonSupervisor` 是**内联新建**的，
     * 连字段都没有 ⇒ 这条"配对"性质完全没有护栏。
     */
    @Test
    fun `★ restore restarts the daemon supervisor, paired with the alert stop`() {
        controller.onSafeModeAlert(TripReason.Manual)
        assertEquals(1, daemonSupervisor.stopCalls, "熔断必须停掉常驻监管")
        assertFalse(daemonSupervisor.running, "停了之后不该还在监管")

        controller.onSafeModeRestore()
        assertEquals(1, daemonSupervisor.startCalls, "★ 恢复必须重启常驻监管（与 stop 配对）")
        assertTrue(daemonSupervisor.running, "★ 恢复后必须真的在监管，否则常驻脚本永远不会跑")
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
                keepAliveWatchdog = RecordingKeepAliveWatchdog(),
                safeModeSnapshot = SafeModeSnapshot(),
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
    fun `health check is armed rather than merely designed`() {
        // 阶段 5 的裁定 ①（"只留端口与设计、不实现"）建在一个**错误的前提**上：
        // 当时以为要 `androidx.work`，而它不在离线缓存。实际那份设计用的是
        // `AlarmManager` 自续期 ⇒ 不需要任何新依赖。阶段 12c 落地，这条断言随之改为"已启用"。
        assertEquals(15 * 60 * 1000L, KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS)
        assertEquals(30 * 1000L, KeepAliveHealthCheck.DEFER_INTERVAL_MILLIS, "判定输入不足时的补偿间隔")
        assertEquals("KEEPALIVE_HEALTH_ARMED", KeepAliveHealthCheck.HEALTH_CHECK_ARMED_MARKER)
    }

    // ------------------------------------------------------------ ★ 阶段 12c：安全护栏与看门狗

    /**
     * ★★ **本阶段第 ① 项的核心断言。**
     *
     * ## 它防的是什么
     * `register()` 此前**无条件**调 `daemonSupervisor.start()`，而
     * `DaemonSupervisorImpl.start()` 内部没有任何安全模式判断。走的路径是
     * `START_STICKY`（系统重建服务）——**偶发**。加入 15 分钟一次的心跳看门狗后，
     * 它会**周期性**走那条路：熔断期间服务若被杀，监管与事件源会被反复复活，
     * 而"安全模式 = 全部停下来"正是熔断第 2 步刚做的事。
     *
     * 它不会真的绕过熔断（脚本执行前还有 `SafeModeDecision.shouldRun`），
     * 但那层兜底是**第二层** —— 第一层必须干净。
     */
    @Test
    fun `★ safe mode register does not start the daemon supervisor`() {
        val safeBreaker = FakeControllerCircuitBreaker(safeMode = true, reason = TripReason.Manual)
        val supervisor = RecordingDaemonSupervisor()
        val safeModeController =
            ForegroundServiceController(
                eventSourceRegistry = FakeEventSourceRegistry(),
                circuitBreaker = Lazy { safeBreaker },
                notifier = FakeServiceNotifier(),
                daemonSupervisor = supervisor,
                keepAliveWatchdog = RecordingKeepAliveWatchdog(),
                safeModeSnapshot = SafeModeSnapshot(),
                scope = controllerScope,
            )

        safeModeController.register()

        assertEquals(0, supervisor.startCalls, "★ 安全模式下不得启动常驻脚本监管（护栏）")
        assertFalse(supervisor.running, "★ 监管必须真的没在跑，而不只是没计数")
        assertTrue(
            safeModeController.isRegistered,
            "护栏只挡监管 —— 服务本身照常注册（否则事件源、通知、状态卡全都不会工作）",
        )
    }

    @Test
    fun `the safe mode gate is paired with the restore path`() {
        // 与上一条构成对称：熔断期间不启动监管，**退出安全模式时必须把它拉回来**
        // （`onSafeModeRestore` 里的 start，11e 补丁5 的既有行为）。
        val safeBreaker = FakeControllerCircuitBreaker(safeMode = true, reason = TripReason.Manual)
        val supervisor = RecordingDaemonSupervisor()
        val safeModeController =
            ForegroundServiceController(
                eventSourceRegistry = FakeEventSourceRegistry(),
                circuitBreaker = Lazy { safeBreaker },
                notifier = FakeServiceNotifier(),
                daemonSupervisor = supervisor,
                keepAliveWatchdog = RecordingKeepAliveWatchdog(),
                safeModeSnapshot = SafeModeSnapshot(),
                scope = controllerScope,
            )
        safeModeController.register()
        assertEquals(0, supervisor.startCalls)

        safeModeController.onSafeModeRestore()

        assertEquals(1, supervisor.startCalls, "退出安全模式必须重启监管，否则常驻脚本永远不回来")
        assertTrue(supervisor.running)
    }

    @Test
    fun `register arms the watchdog and every publish reports a heartbeat`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, watchdog.ensureScheduledCalls, "服务起来就要确保下一次检查已被安排")
            assertTrue(watchdog.heartbeatCalls >= 1, "设计要求的写入点是'每次状态变化'")

            val afterRegister = watchdog.heartbeatCalls

            // 一次真实的状态变化（事件源少了一个）⇒ 又是一次心跳
            registry.changeSources(listOf("battery" to true, "power" to true, "wifi" to true))
            testDispatcher.scheduler.runCurrent()

            assertTrue(
                watchdog.heartbeatCalls > afterRegister,
                "状态变化会经过 publishState ⇒ 心跳必须跟着写（实际 $afterRegister → ${watchdog.heartbeatCalls}）",
            )
        }

    @Test
    fun `unregister tells the watchdog the service is gone`() =
        runTest(testDispatcher) {
            controller.register()
            testDispatcher.scheduler.runCurrent()

            controller.unregister()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, watchdog.stoppedCalls, "服务停止必须让看门狗知道（否则它以为服务还活着，永不尝试拉起）")
        }

    @Test
    fun `a failing heartbeat cannot break the state publish`() =
        runTest(testDispatcher) {
            watchdog.heartbeatThrows = true

            controller.register()
            testDispatcher.scheduler.runCurrent()

            // 心跳失败只是"看门狗少了一次报活"，绝不能让服务注册或通知投递跟着塌掉
            assertTrue(controller.isRegistered)
            assertTrue(notifier.updates.isNotEmpty(), "通知必须照常投出去")
        }

    @Test
    fun `publishing a state refreshes the safe mode snapshot`() =
        runTest(testDispatcher) {
            val localSnapshot = SafeModeSnapshot()
            val localController =
                ForegroundServiceController(
                    eventSourceRegistry = FakeEventSourceRegistry(),
                    circuitBreaker = Lazy { breaker },
                    notifier = FakeServiceNotifier(),
                    daemonSupervisor = RecordingDaemonSupervisor(),
                    keepAliveWatchdog = RecordingKeepAliveWatchdog(),
                    safeModeSnapshot = localSnapshot,
                    scope = controllerScope,
                )
            assertNull(localSnapshot.current, "注册之前是未知（三态里的第三档，不得当成'不在安全模式'）")

            localController.register()
            testDispatcher.scheduler.runCurrent()
            assertEquals(false, localSnapshot.current)

            breaker.safeModeFlow.value = true
            breaker.reasonFlow.value = TripReason.Manual
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                true,
                localSnapshot.current,
                "运行期熔断必须跟着刷新 —— 否则看门狗会永久停在启动那一刻的结论上",
            )
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
