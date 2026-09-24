package com.rootflow.data.service

import com.rootflow.data.event.EventSourceRegistry
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.ServiceChannels
import com.rootflow.domain.service.ServiceNotificationModel
import com.rootflow.domain.service.ServiceNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 阶段 5 的测试替身集合（纯 JVM，无 Android 依赖）。
// 与 3c.1 的 EventTestDoubles.kt 同一纪律：共用脚手架集中在一处，读一次即掌握注入面。

/**
 * 记录型通知假件。
 *
 * ## 为什么必须记录"投递了几次"而不只记录最后一条
 * `ForegroundServiceController` 的两条关键纪律只能靠次数断言：
 * 1. **`register()` 幂等**：sticky 重启会重复调用它，重复投递通知是可见的回归
 * 2. **订阅取消后不得再发通知**：`unregister()` 之后 `safeMode` 若发生发射而通知照发，
 *    用户会看到"服务已停但仍显示运行中"
 */
internal class FakeServiceNotifier : ServiceNotifier {
    /** 每次 [update] 的内容（按投递顺序）。 */
    val updates: MutableList<ServiceNotificationModel> = mutableListOf()

    /** 每次 [alert] 的内容。 */
    val alerts: MutableList<ServiceNotificationModel> = mutableListOf()

    var ensureChannelsCalls: Int = 0
        private set

    var cancelAlertCalls: Int = 0
        private set

    /** 由用例决定"通知是否可见"（只影响正文降级文案）。 */
    var visible: Boolean = true

    /** 最近一次投递的常驻通知；未投递过为 `null`。 */
    val last: ServiceNotificationModel?
        get() = updates.lastOrNull()

    override fun ensureChannels() {
        ensureChannelsCalls++
    }

    override fun update(model: ServiceNotificationModel) {
        updates += model
    }

    override fun alert(model: ServiceNotificationModel) {
        alerts += model
    }

    override fun cancelAlert() {
        cancelAlertCalls++
    }

    override fun notificationsVisible(): Boolean = visible
}

/**
 * 记录型事件源注册表假件。
 *
 * [sources] 决定 `status()` 报什么（通知正文要显示 `enabled/total`）；
 * `start` / `stop` 的**计数**用于断言所有权（只应由控制器调用）。
 */
internal class FakeEventSourceRegistry(
    private var sources: List<Pair<String, Boolean>> =
        listOf("battery" to true, "power" to true, "screen" to true, "wifi" to true),
    /** `true` 时 [start] 抛异常（覆盖"源起不来也必须把服务与通知拉起来"）。 */
    private val startThrows: Boolean = false,
) : EventSourceRegistry {
    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    private var started: Boolean = false

    override fun start() {
        startCalls++
        if (startThrows) throw IllegalStateException("registry boom")
        started = true
        // P5c：状态变了就推 —— 与真注册表"reconcile 后 publishStatus()"同一行为
        pushStatus()
    }

    override fun stop() {
        stopCalls++
        started = false
        pushStatus()
    }

    override fun status(): List<EventSourceStatus> =
        sources.map { (id, running) ->
            EventSourceStatus(
                sourceId = id,
                state =
                    when {
                        !started -> EventSourceState.NotStarted
                        running -> EventSourceState.Running
                        // 已启动但该源被权限门挡下：决策 7 要求原因具体
                        else -> EventSourceState.Unavailable("permission missing: test")
                    },
            )
        }

    /**
     * P5c：状态流与 [status] 同源。
     *
     * 初值取 [status]（此刻未 `start` ⇒ 全 `NotStarted`，但**源清单已在**）：
     * 控制器订阅它时会立刻收到一次当前值，给空列表会被读成"一个源都没有"。
     *
     * `start` / `stop` 之后各推一次；用例也可以显式调 [pushStatus] / [changeSources]。
     */
    private val _statusFlow = MutableStateFlow(status())

    override val statusFlow: StateFlow<List<EventSourceStatus>> = _statusFlow.asStateFlow()

    /** 把当前 [status] 推给订阅者（[start] / [stop] 与用例都走这里）。 */
    fun pushStatus() {
        _statusFlow.value = status()
    }

    /**
     * P5c：模拟"运行期源状态变了"（真注册表里这件事由 `reconcile` 干）。
     *
     * 供控制器用例验证它**转发**了这次变化 —— 而不是把卡片停在注册那一刻。
     */
    fun changeSources(next: List<Pair<String, Boolean>>) {
        sources = next
        pushStatus()
    }
}

/**
 * 可驱动的熔断器假件（只实现控制器用到的三个成员）。
 *
 * 其余成员抛 [NotImplementedError]：**故意**如此——若控制器哪天开始依赖它们，
 * 用例会立刻炸出来，而不是让"悄悄多了一个依赖"溜过去。
 */
internal class FakeControllerCircuitBreaker(
    safeMode: Boolean = false,
    reason: TripReason? = null,
) : CircuitBreaker {
    val safeModeFlow = MutableStateFlow(safeMode)
    val reasonFlow = MutableStateFlow(reason)

    override val safeMode: StateFlow<Boolean> = safeModeFlow

    override val tripReason: StateFlow<TripReason?> = reasonFlow

    override suspend fun onRunAccepted(scriptId: Long): Unit = unsupported()

    override suspend fun onRunFinished(
        scriptId: Long,
        exitCode: Int?,
    ): Unit = unsupported()

    override suspend fun onRunTimeout(
        scriptId: Long,
        timeoutMillis: Long,
    ): Unit = unsupported()

    override suspend fun onRootUnresponsive(elapsedMillis: Long): Unit = unsupported()

    override suspend fun tripManually(): Unit = unsupported()

    override suspend fun tripForBootloop(crashes: Int): Unit = unsupported()

    override suspend fun restore(mode: RestoreMode): Unit = unsupported()

    override suspend fun probeExternalSafeMode(): TripReason? = unsupported()

    private fun unsupported(): Nothing =
        throw NotImplementedError("ForegroundServiceController must not use this CircuitBreaker member")
}

/** 期望的常驻渠道 id（避免用例里到处写字符串字面量）。 */
internal val foregroundChannel: String = ServiceChannels.FOREGROUND
