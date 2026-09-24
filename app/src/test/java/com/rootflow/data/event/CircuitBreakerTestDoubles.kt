package com.rootflow.data.event

import com.rootflow.data.run.RunOutcomeSink
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 阶段 4 的测试替身集合（纯 JVM，无 Android 依赖）。
// 与 `RunChainTestDoubles` / `EventTestDoubles` 同款纪律：共用脚手架集中一处，
// 读一次就能掌握全部注入面。

/**
 * 记录型熔断器假件（[CircuitBreaker] 端口）。
 *
 * ## 为什么需要它（而不是给 `TriggeredScriptRunner` 传 null / 真实实现）
 * `TriggeredScriptRunner` 要断言的三件事**都只体现为"端口被怎么调用"**：
 * 1. 安全模式下的拒绝 —— 需要能把 [safeMode] 置位
 * 2. 受理计数 —— `onRunAccepted` 必须在**受理之后**才被调用（顺序本身是语义）
 * 3. 熔断动作不得在投递路径上被触发 —— 记录型假件让"没被调用"成为可断言的事实
 *
 * ## 调用顺序是断言对象
 * [onRunAccepted] 与 [runAcceptedAt] 一起暴露"第几次调用时被记录"，
 * 使 `RUN_ACCEPTED` 日志与计数的先后可被钉死（`CircuitBreaker.onRunAccepted` 的 KDoc 要求
 * "受理之后才计数"——被拒的不该进计数，否则"高频自启"阈值会失去意义）。
 */
internal class FakeCircuitBreaker(
    initialSafeMode: Boolean = false,
) : CircuitBreaker {
    private val _safeMode = MutableStateFlow(initialSafeMode)
    private val _tripReason = MutableStateFlow<TripReason?>(null)

    override val safeMode: StateFlow<Boolean> = _safeMode

    override val tripReason: StateFlow<TripReason?> = _tripReason

    /** 被受理的脚本 id 序列。 */
    val accepted = mutableListOf<Long>()

    /** 收到的运行结局（`scriptId` → `exitCode`）。 */
    val finished = mutableListOf<Pair<Long, Int?>>()

    /** 收到的超时上报（`scriptId` → `timeoutMillis`）。 */
    val timeouts = mutableListOf<Pair<Long, Long>>()

    /** root 无响应上报次数。 */
    var rootUnresponsiveCalls: Int = 0
        private set

    /** 手动熔断次数。 */
    var manualTrips: Int = 0
        private set

    /** bootloop 熔断次数。 */
    var bootloopTrips: Int = 0
        private set

    /** 恢复调用（`mode` 序列）。 */
    val restores = mutableListOf<RestoreMode>()

    /** 外部安全模式探测次数。 */
    var externalProbes: Int = 0
        private set

    /** 测试用：直接置位安全模式（模拟熔断发生）。 */
    fun setSafeMode(
        active: Boolean,
        reason: TripReason? = null,
    ) {
        _safeMode.value = active
        _tripReason.value = reason
    }

    override suspend fun onRunAccepted(scriptId: Long) {
        accepted += scriptId
    }

    override suspend fun onRunFinished(
        scriptId: Long,
        exitCode: Int?,
    ) {
        finished += scriptId to exitCode
    }

    override suspend fun onRunTimeout(
        scriptId: Long,
        timeoutMillis: Long,
    ) {
        timeouts += scriptId to timeoutMillis
    }

    override suspend fun onRootUnresponsive(elapsedMillis: Long) {
        rootUnresponsiveCalls++
    }

    override suspend fun tripManually() {
        manualTrips++
        _safeMode.value = true
        _tripReason.value = TripReason.Manual
    }

    override suspend fun tripForBootloop(crashes: Int) {
        bootloopTrips++
        _safeMode.value = true
        _tripReason.value = TripReason.Bootloop(crashes = crashes)
    }

    override suspend fun restore(mode: RestoreMode) {
        restores += mode
        _safeMode.value = false
        _tripReason.value = null
    }

    override suspend fun probeExternalSafeMode(): TripReason? {
        externalProbes++
        return null
    }
}

/**
 * 记录型运行结局接收端（[RunOutcomeSink]）。
 *
 * `ScriptRunCoordinator` 的用例靠它断言"退出码**随回调带出**"——
 * 这正是替换掉"查 `RunHistoryRepository.find(runId).exitCode`"那条竞态路径的证据。
 */
internal class RecordingOutcomeSink : RunOutcomeSink {
    /** 一次运行结局的入参快照。 */
    data class Outcome(
        val runId: String,
        val scriptId: Long,
        val exitCode: Int?,
    )

    /** 一次超时上报的入参快照。 */
    data class Timeout(
        val runId: String,
        val scriptId: Long,
        val timeoutMillis: Long,
    )

    val outcomes = mutableListOf<Outcome>()

    val timeouts = mutableListOf<Timeout>()

    override suspend fun onRunOutcome(
        runId: String,
        scriptId: Long,
        exitCode: Int?,
    ) {
        outcomes += Outcome(runId = runId, scriptId = scriptId, exitCode = exitCode)
    }

    override suspend fun onRunTimeout(
        runId: String,
        scriptId: Long,
        timeoutMillis: Long,
    ) {
        timeouts += Timeout(runId = runId, scriptId = scriptId, timeoutMillis = timeoutMillis)
    }
}
