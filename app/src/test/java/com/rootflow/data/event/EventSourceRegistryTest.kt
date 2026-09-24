package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.run.FakeMasterSwitch
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.ForegroundDetector
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.event.SettingsTarget
import com.rootflow.domain.model.EventCatalog
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 全部事件键。
 *
 * ## 为什么在**文件级**而不是测试类里
 * `StubEventSource` 是**嵌套类** ⇒ 它访问不到外部类的**实例**成员
 * （Kotlin 的嵌套类 ≠ 内部类，没有隐式的外部实例）。放在文件顶层两个作用域都能用。
 */
private val ALL_EVENT_IDS: Set<String> = EventCatalog.ALL.map { it.eventId }.toSet()

/**
 * `EventSourceRegistryImpl` 单测（阶段 3c.1，决策 2 / 决策 7）。
 *
 * ## 覆盖的核心承诺
 * 1. **启动/停止是幂等的**
 * 2. **单个源失败不影响其余**（一道异常不能拖垮整张注册表）
 * 3. **权限缺失时该源不启动，且原因具体到权限字符串**（决策 7：阶段 6 的 UI 据此逐项引导）
 * 4. `status()` 在 `start()` 之前如实报 `NotStarted`（"未曾尝试" ≠ "尝试后失败"）
 */
class EventSourceRegistryTest {
    /**
     * 记录型事件源。
     *
     * @param sourceId 稳定标识（`status()` 按它排序，故用可读前缀保证顺序确定）
     * @param requiredPermissions 门控权限
     * @param stateAfterStart `start()` 之后 `status()` 应报的状态
     * @param failOnStart 是否在 `start()` 里抛异常
     */
    private class StubEventSource(
        override val sourceId: String,
        override val requiredPermissions: Set<AndroidPermission> = emptySet(),
        /**
         * 本源产出的事件（P5c）。
         *
         * **默认 = 全部事件**是**有意**的：本文件的既有用例关心的是权限门 / 状态机 /
         * 异常门，而不是按需启。让它覆盖"所有事件"⇒ 只要订阅表里有任何生效订阅，
         * 它就会被启动 —— 既有断言因此**一字不改**。
         * 按需启本身由 `a source with no subscription is not started` 等用例显式覆盖。
         */
        override val providesEvents: Set<String> = ALL_EVENT_IDS,
        /** P5c：让本源的启动依赖某个特定事件（按需启用例用）。 */
        private val stateAfterStart: EventSourceState = EventSourceState.Running,
        private val failOnStart: Boolean = false,
    ) : EventSource {
        var startCalls: Int = 0
            private set

        var stopCalls: Int = 0
            private set

        private var started: Boolean = false

        override fun start() {
            startCalls++
            if (failOnStart) throw IllegalStateException("boom-$sourceId")
            started = true
        }

        override fun stop() {
            stopCalls++
            started = false
        }

        override fun status(): EventSourceStatus =
            EventSourceStatus(sourceId, if (started) stateAfterStart else EventSourceState.NotStarted)
    }

    /** 可注入的权限快照提供者（不触碰 Android）。 */
    private class StubPermissionStatusProvider(
        private var snapshot: Map<AndroidPermission, PermissionState> = grantedAll(),
    ) : PermissionStatusProvider {
        var refreshCalls: Int = 0
            private set

        private val flow = MutableStateFlow(snapshot)

        override fun current(): Map<AndroidPermission, PermissionState> = snapshot

        override fun observe(): Flow<Map<AndroidPermission, PermissionState>> = flow.asStateFlow()

        override fun refresh() {
            refreshCalls++
            flow.value = snapshot
        }

        override fun settingsTargetFor(permission: AndroidPermission): SettingsTarget? = null

        /** 让某项权限变为未授予。 */
        fun deny(permission: AndroidPermission) {
            snapshot =
                snapshot +
                (permission to PermissionState(permission, PermissionGrant.DENIED, "denied in test"))
        }

        companion object {
            fun grantedAll(): Map<AndroidPermission, PermissionState> =
                AndroidPermission.entries.associateWith {
                    PermissionState(it, PermissionGrant.GRANTED, "granted in test")
                }
        }
    }

    @Test
    fun `start starts every source exactly once`() {
        installLogStub()
        val a = StubEventSource("a-screen")
        val b = StubEventSource("b-power")
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(a, b), warnings = warnings)

        registry.start()

        assertEquals(1, a.startCalls)
        assertEquals(1, b.startCalls)
        assertTrue(warnings.isEmpty(), "全部成功时不应有告警：$warnings")
    }

    @Test
    fun `start is idempotent`() {
        installLogStub()
        val a = StubEventSource("a-screen")
        val registry = registryOf(listOf(a))

        registry.start()
        registry.start()
        registry.start()

        assertEquals(1, a.startCalls, "重复 start() 不得重复启动源")
    }

    @Test
    fun `stop stops every source and is idempotent`() {
        installLogStub()
        val a = StubEventSource("a-screen")
        val b = StubEventSource("b-power")
        val registry = registryOf(listOf(a, b))

        registry.start()
        registry.stop()
        registry.stop()

        assertEquals(1, a.stopCalls)
        assertEquals(1, b.stopCalls)
    }

    @Test
    fun `stop before start is safe`() {
        installLogStub()
        val a = StubEventSource("a-screen")
        val registry = registryOf(listOf(a))

        registry.stop()

        assertEquals(0, a.stopCalls, "未 start() 时 stop() 不得触碰任何源")
    }

    @Test
    fun `a permission-blocked source reports Unavailable with the permission name`() {
        installLogStub()
        // 真机回归（3c.2 实测）：被权限门挡下的源原先报 NotStarted → 文案是笼统的 "not started"，
        // 违反决策 7「原因要具体到缺哪个权限」。根因是 registry 把原因只写进了日志、没有寄存。
        val blocked = StubEventSource("b-wifi", requiredPermissions = setOf(AndroidPermission.ACCESS_NETWORK_STATE))
        val permissions = StubPermissionStatusProvider().apply { deny(AndroidPermission.ACCESS_NETWORK_STATE) }
        val registry = registryOf(listOf(blocked), permissions)

        registry.start()

        val state = registry.status().single().state
        assertTrue(state is EventSourceState.Unavailable, "必须是 Unavailable 而不是 NotStarted：$state")
        val reason = (state as EventSourceState.Unavailable).reason
        assertTrue(reason.contains("permission missing"), "reason 必须含固定前缀：$reason")
        assertTrue(
            reason.contains(AndroidPermission.ACCESS_NETWORK_STATE.permission),
            "reason 必须含权限字符串（决策 7：阶段 6 的 UI 据此逐项引导授权）：$reason",
        )
        assertFalse(state.isRunning, "被挡下的源不得报为已启动")
    }

    @Test
    fun `a permission-blocked source does not affect the others`() {
        installLogStub()
        val blocked = StubEventSource("b-wifi", requiredPermissions = setOf(AndroidPermission.ACCESS_NETWORK_STATE))
        val healthy = StubEventSource("a-screen")
        val permissions = StubPermissionStatusProvider().apply { deny(AndroidPermission.ACCESS_NETWORK_STATE) }
        val registry = registryOf(listOf(blocked, healthy), permissions)

        registry.start()

        val byId = registry.status().associateBy { it.sourceId }
        assertTrue(byId.getValue("b-wifi").state is EventSourceState.Unavailable)
        assertEquals(
            EventSourceState.Running,
            byId.getValue("a-screen").state,
            "一个源被权限挡下不得影响其余源",
        )
    }

    @Test
    fun `stop clears the recorded rejection reasons`() {
        installLogStub()
        val blocked = StubEventSource("b-wifi", requiredPermissions = setOf(AndroidPermission.ACCESS_NETWORK_STATE))
        val permissions = StubPermissionStatusProvider().apply { deny(AndroidPermission.ACCESS_NETWORK_STATE) }
        val registry = registryOf(listOf(blocked), permissions)

        registry.start()
        assertTrue(registry.status().single().state is EventSourceState.Unavailable)

        registry.stop()

        assertEquals(
            EventSourceState.NotStarted,
            registry.status().single().state,
            "stop() 必须清空拒绝原因，否则停后重启会残留上一轮的原因（权限已授予却仍报缺失）",
        )
    }

    @Test
    fun `a source that throws on start reports Unavailable with the failure reason`() {
        installLogStub()
        val broken = StubEventSource("a-broken", failOnStart = true)
        val registry = registryOf(listOf(broken), StubPermissionStatusProvider())

        registry.start()

        val state = registry.status().single().state
        assertTrue(state is EventSourceState.Unavailable, "异常门失败也必须给出具体原因：$state")
        val reason = (state as EventSourceState.Unavailable).reason
        assertTrue(reason.contains("start failed"), "reason 必须含固定前缀：$reason")
        assertTrue(reason.contains("boom-a-broken"), "reason 必须含异常信息：$reason")
    }

    @Test
    fun `a granted source never reports Unavailable`() {
        installLogStub()
        // 无假阳性：有权限时不得出现 Unavailable（否则阶段 6 会给用户一个无意义的授权提示）
        val granted = StubEventSource("b-wifi", requiredPermissions = setOf(AndroidPermission.ACCESS_NETWORK_STATE))
        val registry = registryOf(listOf(granted), StubPermissionStatusProvider())

        registry.start()

        val state = registry.status().single().state
        assertEquals(EventSourceState.Running, state, "权限已授予时必须正常启动：$state")
        assertTrue(state !is EventSourceState.Unavailable)
    }

    @Test
    fun `status reports NotStarted before start and Running after`() {
        installLogStub()
        val a = StubEventSource("a-screen")
        val registry = registryOf(listOf(a))

        assertEquals(
            EventSourceState.NotStarted,
            registry.status().single().state,
            "未曾尝试与尝试后失败必须可区分（决策 7）",
        )

        registry.start()
        assertEquals(EventSourceState.Running, registry.status().single().state)

        registry.stop()
        assertEquals(
            EventSourceState.NotStarted,
            registry.status().single().state,
            "stop() 后源的 isRunning 为 false，如实上报",
        )
    }

    @Test
    fun `a missing permission blocks only that source and the reason names the permission`() {
        installLogStub()
        val blocked = StubEventSource("b-wifi", requiredPermissions = setOf(AndroidPermission.ACCESS_NETWORK_STATE))
        val allowed = StubEventSource("a-screen")
        val permissions = StubPermissionStatusProvider().apply { deny(AndroidPermission.ACCESS_NETWORK_STATE) }
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(blocked, allowed), permissions, warnings)

        registry.start()

        assertEquals(0, blocked.startCalls, "缺权限的源不得启动")
        assertEquals(1, allowed.startCalls, "缺权限的源不得影响其它源")
        assertEquals(
            1,
            warnings.size,
            "必须有一条告警（不得静默跳过）：$warnings",
        )
        val warning = warnings.single()
        assertTrue(warning.contains("b-wifi"), "告警必须点名源：$warning")
        assertTrue(
            warning.contains(AndroidPermission.ACCESS_NETWORK_STATE.permission),
            "告警必须具体到缺哪个权限（决策 7）：$warning",
        )
    }

    @Test
    fun `a source that throws does not prevent the others from starting`() {
        installLogStub()
        val broken = StubEventSource("a-broken", failOnStart = true)
        val healthy = StubEventSource("b-healthy")
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(broken, healthy), warnings = warnings)

        registry.start()

        assertEquals(1, broken.startCalls)
        assertEquals(1, healthy.startCalls, "一个源抛异常绝不能拖垮其余源")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("a-broken"), warnings.single())
        assertTrue(warnings.single().contains("boom-a-broken"), "告警必须带上异常信息：${warnings.single()}")
    }

    @Test
    fun `a source that throws on stop does not prevent the others from stopping`() {
        installLogStub()
        val broken =
            object : EventSource {
                override val sourceId: String = "a-broken"

                override val providesEvents: Set<String> = ALL_EVENT_IDS

                override fun start() = Unit

                override fun stop() = throw IllegalStateException("stop-boom")

                override fun status(): EventSourceStatus = EventSourceStatus(sourceId, EventSourceState.Running)
            }
        val healthy = StubEventSource("b-healthy")
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(broken, healthy), warnings = warnings)

        registry.start()
        registry.stop()

        assertEquals(1, healthy.stopCalls)
        assertTrue(warnings.any { it.contains("stop-boom") }, "反注册失败必须可见：$warnings")
    }

    @Test
    fun `status is ordered by sourceId regardless of injection order`() {
        installLogStub()
        val registry =
            registryOf(
                listOf(
                    StubEventSource("c-battery"),
                    StubEventSource("a-screen"),
                    StubEventSource("b-power"),
                ),
            )

        registry.start()

        assertEquals(
            listOf("a-screen", "b-power", "c-battery"),
            registry.status().map { it.sourceId },
            "顺序必须确定，否则真机日志无法逐行比对",
        )
    }

    @Test
    fun `status covers all seven sources of stages 3c_1 and 3c_2`() {
        installLogStub()
        val alarmSource = AlarmEventSource(FakeAlarmHandle())
        // 与真实装配同构：screen(3 事件) + wifi(1) + power(2) + battery(2) + usage_stats(2)
        // + alarm_time(1) + alarm_interval(1) = 7 个源、12 个事件
        val registry =
            registryOf(
                listOf(
                    ScreenEventSource(FakeBroadcastRegistration(), RecordingEventBus()),
                    WifiEventSource(FakeNetworkMonitor(), RecordingEventBus()),
                    PowerEventSource(),
                    BatteryEventSource(),
                    UsageStatsPollingSource(
                        reader = FakeUsageStatsReader(foregroundScript("com.a")),
                        eventBus = RecordingEventBus(),
                        scope = CoroutineScope(StandardTestDispatcher()),
                        detector = ForegroundDetector(),
                    ),
                    alarmSource.time,
                    alarmSource.interval,
                ),
            )

        registry.start()

        val ids = registry.status().map { it.sourceId }
        assertEquals(
            listOf("alarm_interval", "alarm_time", "battery", "power", "screen", "usage_stats", "wifi"),
            ids,
            "顺序按 sourceId 升序，真机可逐行比对",
        )
        assertEquals(ids.size, ids.toSet().size, "sourceId 不得重复")
        assertEquals(7, ids.size, "3c.1 的 4 源 + 3c.2 的 3 源")
    }

    @Test
    fun `a source missing PACKAGE_USAGE_STATS is blocked with a specific reason`() {
        installLogStub()
        val usageStats =
            UsageStatsPollingSource(
                reader = FakeUsageStatsReader(foregroundScript("com.a")),
                eventBus = RecordingEventBus(),
                scope = CoroutineScope(StandardTestDispatcher()),
                detector = ForegroundDetector(),
            )
        val permissions = StubPermissionStatusProvider().apply { deny(AndroidPermission.PACKAGE_USAGE_STATS) }
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(usageStats), permissions, warnings)

        registry.start()

        val state = registry.status().single().state
        assertTrue(
            state is EventSourceState.Unavailable && state.reason.contains("permission missing"),
            "缺 PACKAGE_USAGE_STATS 时 app_foreground/app_background 必须不可用，" +
                "且原因要具体到权限（需求 §2.1 的降级策略 + 决策 7）：$state",
        )
        assertTrue(
            warnings.single().contains(AndroidPermission.PACKAGE_USAGE_STATS.permission),
            "原因必须具体到权限字符串（决策 7）：$warnings",
        )
    }

    @Test
    fun `refresh is called on start and stop`() {
        installLogStub()
        val permissions = StubPermissionStatusProvider()
        val registry = registryOf(listOf(StubEventSource("a-screen")), permissions)

        registry.start()
        assertEquals(1, permissions.refreshCalls, "决策 6：start() 必须刷一次权限")

        registry.stop()
        assertEquals(2, permissions.refreshCalls, "决策 6：stop() 也必须刷一次")
    }

    @Test
    fun `registry exposes the screen source as started`() {
        installLogStub()
        val registration = FakeBroadcastRegistration()
        val registry = registryOf(listOf(ScreenEventSource(registration, RecordingEventBus())))

        registry.start()

        assertTrue(registration.isRegistered, "screen 源必须真的完成动态注册")
        assertTrue(
            registry
                .status()
                .single()
                .state.isRunning,
        )
    }

    @Test
    fun `an empty source set is tolerated`() {
        installLogStub()
        val registry = registryOf(emptyList())

        registry.start()

        assertTrue(registry.status().isEmpty())
    }

    @Test
    fun `a declared but unregistered permission is not treated as missing`() {
        installLogStub()
        // requiredPermissions 是空集的源无需任何权限（屏幕/电源/电量广播）
        val source = StubEventSource("a-screen")
        val permissions = StubPermissionStatusProvider()
        val warnings = mutableListOf<String>()
        val registry = registryOf(listOf(source), permissions, warnings)

        registry.start()

        assertEquals(1, source.startCalls)
        assertTrue(warnings.isEmpty(), "无权限需求的源不得被门控挡下：$warnings")
    }

    /**
     * 造一个 registry。
     *
     * ## 为什么用一个 `Unconfined` 作用域，而不是把全部用例改成 `runTest`
     * 本文件的用例几乎都是**同步**的（`start()` 之后立刻断言 `startCalls`）——
     * 那是 P5c 之前 registry 完全同步的历史形态。P5c 只让 `start()` 内部多了一次
     * **订阅**，而 `StateFlow` 的首次发射是**同步**的 ⇒ `Unconfined` 下第一次对账
     * 在 `start()` 返回前就完成了 ⇒ 既有用例**一行都不用改写**。
     *
     * 需要控制时序的场景（动态勾选）用 `subscriptionsFlow` 换新值即可 ——
     * 同样由 `StateFlow` 的同步发射驱动，无需虚拟时间。
     *
     * ## 两个默认值让既有用例的观察结果**一字不改**
     * - `subscribedEvents` 默认 = **全部事件** ⇒ 每个源都能找到自己的订阅 ⇒ 都会被启动
     *   （与 P5c 之前"无条件全启"看起来一样）
     * - `masterEnabled` 默认 = `true`
     * 按需启本身由专门的三条用例显式覆盖（改这两个参数）。
     */
    private fun registryOf(
        sources: List<EventSource>,
        permissions: StubPermissionStatusProvider = StubPermissionStatusProvider(),
        warnings: MutableList<String> = mutableListOf(),
        subscribedEvents: Set<String> = ALL_EVENT_IDS,
        masterEnabled: Boolean = true,
        subscriptionsFlow: MutableStateFlow<List<Trigger>>? = null,
    ): EventSourceRegistryImpl =
        EventSourceRegistryImpl(
            sources = sources.toSet(),
            permissionStatus = permissions,
            triggerRepository = StubTriggerRepository(subscribedEvents, subscriptionsFlow),
            masterSwitch = FakeMasterSwitch(masterEnabled),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onWarning = warnings::add,
        )

    /** 只实现 `observeAll` 的订阅表替身（P5c 的判据来源）。 */
    private class StubTriggerRepository(
        subscribedEvents: Set<String>,
        private val flow: MutableStateFlow<List<Trigger>>? = null,
    ) : TriggerRepository {
        private val state: MutableStateFlow<List<Trigger>> =
            flow ?: MutableStateFlow(
                subscribedEvents.mapIndexed { index, eventType ->
                    Trigger(
                        id = index + 1L,
                        scriptId = 1L,
                        eventType = eventType,
                        params = TriggerParams(),
                        enabled = true,
                        createdAt = 0L,
                    )
                },
            )

        override fun observeAll(): Flow<List<Trigger>> = state

        override fun observeForScript(scriptId: Long): Flow<List<Trigger>> = state

        override suspend fun all(): List<Trigger> = state.value

        override suspend fun forEvent(eventType: String): List<Trigger> =
            state.value.filter { it.eventType == eventType && it.enabled }

        override suspend fun replaceForScript(
            scriptId: Long,
            triggers: List<Trigger>,
        ): WriteResult<List<Trigger>> = WriteResult.Ok(triggers)

        override suspend fun delete(id: Long): WriteResult<Unit> = WriteResult.Ok(Unit)

        override suspend fun countForScript(scriptId: Long): Int = state.value.size
    }

    // ---------------------------------------------------------------- P5c：按需启（方案 §1.7 + §7 决策 6）

    @Test
    @DisplayName("P5c：没有生效订阅的源**一个都不启动**（省掉常驻接收器与 2 秒轮询）")
    fun `a source with no subscription is not started`() =
        runTest {
            val screen = StubEventSource("screen", providesEvents = setOf(SystemEvent.SCREEN_ON))
            val wifi = StubEventSource("wifi", providesEvents = setOf(SystemEvent.WIFI_CHANGED))
            val registry =
                registryOf(
                    listOf(screen, wifi),
                    // 只订阅 screen_on ⇒ wifi 源该待命
                    subscribedEvents = setOf(SystemEvent.SCREEN_ON),
                )

            registry.start()
            advanceUntilIdle()

            assertEquals(1, screen.startCalls, "有生效订阅的源必须启动")
            assertEquals(
                0,
                wifi.startCalls,
                "**没有生效订阅的源一个字都不该启动** —— 那正是方案 §1.7 记的纯开销" +
                    "（广播接收器 + usage_stats 的 2 秒轮询）",
            )
            assertEquals(
                EventSourceState.NotStarted,
                registry.status().first { it.sourceId == "wifi" }.state,
                "状态必须如实报 NotStarted，而不是伪装成 Running",
            )
        }

    @Test
    @DisplayName("P5c：总闸关闭 ⇒ 一个源都不启（方案 §4：事件源全停）")
    fun `the master switch off keeps every source stopped`() =
        runTest {
            val screen = StubEventSource("screen", providesEvents = setOf(SystemEvent.SCREEN_ON))
            val registry =
                registryOf(
                    listOf(screen),
                    // 订阅是有的，但总闸关着
                    subscribedEvents = setOf(SystemEvent.SCREEN_ON),
                    masterEnabled = false,
                )

            registry.start()
            advanceUntilIdle()

            assertEquals(
                0,
                screen.startCalls,
                "总闸关闭时用户明确说了「别提供服务」⇒ 连有订阅的源也不该启（方案 §4）",
            )
        }

    @Test
    @DisplayName("P5c：新勾选一个事件 ⇒ 对应源被**动态**拉起（不必重启服务）")
    fun `a newly subscribed event starts its source`() =
        runTest {
            val screen = StubEventSource("screen", providesEvents = setOf(SystemEvent.SCREEN_ON))
            val subscriptions = MutableStateFlow(listOf(subscription(SystemEvent.WIFI_CHANGED)))
            val registry =
                registryOf(
                    listOf(screen),
                    subscriptionsFlow = subscriptions,
                )

            registry.start()
            advanceUntilIdle()
            assertEquals(0, screen.startCalls, "前置：此刻订阅的是 wifi，screen 源该待命")

            // 用户在编辑器里勾选了「亮屏」
            subscriptions.value = listOf(subscription(SystemEvent.SCREEN_ON))
            advanceUntilIdle()

            assertEquals(
                1,
                screen.startCalls,
                "新勾选的事件必须**立刻**拉起对应源 —— 不必等下次服务启动",
            )
        }

    @Test
    @DisplayName("P5c：状态流跟着订阅走（主页的「事件源 x/7」靠它才不滞后）")
    fun `the status flow follows the reconciled set`() =
        runTest {
            val screen = StubEventSource("screen", providesEvents = setOf(SystemEvent.SCREEN_ON))
            val wifi = StubEventSource("wifi", providesEvents = setOf(SystemEvent.WIFI_CHANGED))
            val subscriptions = MutableStateFlow(listOf(subscription(SystemEvent.WIFI_CHANGED)))
            val registry =
                registryOf(
                    listOf(screen, wifi),
                    subscriptionsFlow = subscriptions,
                )

            // ① 还没 start：如实给出完整清单（全 NotStarted），而不是空列表 ——
            //    空列表会被订阅者读成"一个源都没有"
            assertEquals(
                listOf("screen" to false, "wifi" to false),
                registry.statusFlow.value.map { it.sourceId to it.state.isRunning },
                "未 start 时必须给出源清单本体（这是它的初值语义）",
            )

            // ② start 后的第一次对账必须推出去
            registry.start()
            advanceUntilIdle()
            assertEquals(
                listOf("screen" to false, "wifi" to true),
                registry.statusFlow.value.map { it.sourceId to it.state.isRunning },
                "对账结果必须推给 statusFlow（只写日志的话，主页会停在旧计数）",
            )

            // ③ 用户改订阅 ⇒ 流立刻反映（wifi 停、screen 起）
            subscriptions.value = listOf(subscription(SystemEvent.SCREEN_ON))
            advanceUntilIdle()
            assertEquals(
                listOf("screen" to true, "wifi" to false),
                registry.statusFlow.value.map { it.sourceId to it.state.isRunning },
                "订阅一变，流就得变 —— 真机缺陷正是「日志说 source 起来了，主页仍显示 2/7」",
            )

            // ④ 全停之后回落到"全 NotStarted"（主页必须能看到服务停了）
            registry.stop()
            advanceUntilIdle()
            assertEquals(
                listOf("screen" to false, "wifi" to false),
                registry.statusFlow.value.map { it.sourceId to it.state.isRunning },
                "stop 之后必须回落，否则主页会保留停之前那一刻的源数量",
            )
        }

    /** 一条生效订阅（`enabled = true`），供 P5c 的用例构造订阅表。 */
    private fun subscription(eventType: String): Trigger =
        Trigger(
            id = 1L,
            scriptId = 1L,
            eventType = eventType,
            params = TriggerParams(),
            enabled = true,
            createdAt = 0L,
        )

    /** 固定 `android.util.Log`（注册表与各源都会打日志）。 */
    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun `sanity - the stub itself behaves`() {
        // 防止"替身写错导致全部用例假通过"：显式钉死替身的关键行为
        val stub = StubEventSource("x")
        assertEquals(EventSourceState.NotStarted, stub.status().state)
        stub.start()
        assertEquals(EventSourceState.Running, stub.status().state)
        assertTrue(stub.requiredPermissions.isEmpty())
    }
}
