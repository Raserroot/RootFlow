package com.rootflow.ui.settings

import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.SettingsTarget
import com.rootflow.domain.event.SettingsTargets
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.residue.ResidueCleanResult
import com.rootflow.domain.residue.ResidueCleaner
import com.rootflow.domain.residue.ResidueReport
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceStateProvider
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.SettingsRepository
import com.rootflow.domain.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 阶段 6d 的测试替身集合（纯 JVM，无 Android 依赖）。
// 与 `CircuitBreakerTestDoubles` / `ServiceTestDoubles` 同款纪律：注入面集中一处。

/**
 * 记录型设置仓库假件。
 *
 * @param failWrites `true` = 每次写都抛（验"写失败可见且不崩"这条路径）
 */
internal class FakeSettingsRepository(
    initial: RootFlowSettings = RootFlowSettings(),
    private val failWrites: Boolean = false,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<RootFlowSettings> = state

    val themeWrites = mutableListOf<ThemeMode>()

    val dynamicColorWrites = mutableListOf<Boolean>()

    /** 毛玻璃写入序列（含 `null` = 恢复"跟随设备"）。 */
    val blurWrites = mutableListOf<Boolean?>()

    /** 保留天数写入序列（阶段 6d）。 */
    val retentionWrites = mutableListOf<Int>()

    override suspend fun current(): RootFlowSettings = state.value

    override suspend fun setThemeMode(mode: ThemeMode) {
        maybeFail()
        themeWrites += mode
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun setBlurEnabled(enabled: Boolean?) {
        maybeFail()
        blurWrites += enabled
        state.value = state.value.copy(blurEnabled = enabled)
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        maybeFail()
        dynamicColorWrites += enabled
        state.value = state.value.copy(dynamicColor = enabled)
    }

    override suspend fun setLogRetentionDays(days: Int) {
        maybeFail()
        retentionWrites += days
        state.value = state.value.copy(logRetentionDays = LogRetention.sanitize(days))
    }

    /** 保活同意的写入序列（断言"只在点同意/不同意时才落盘"）。 */
    val consentWrites = mutableListOf<Boolean>()

    override suspend fun setKeepAliveConsent(consented: Boolean) {
        maybeFail()
        consentWrites += consented
        state.value = state.value.copy(keepAliveConsent = consented)
    }

    // ★ 阶段 11e：`setLiquidGlassEnabled` 的替身已随特性移除。

    private fun maybeFail() {
        if (failWrites) error("datastore write failed (fake)")
    }
}

/**
 * 运行历史假件（阶段 6d 的保留策略清理）。
 *
 * ## 为什么它自己维护一份"运行时间"列表
 * 清理的语义是"删除 `started_at` 早于 cutoff 的行"，因此假件必须能**按时间**筛选，
 * 只记录"被调用过"是不够的（那样断言不出 cutoff 是否正确）。
 */
internal class FakeRunHistoryRepository(
    seedStartedAt: List<Long> = emptyList(),
) : RunHistoryRepository {
    /** (runId, startedAt) */
    private val runs: MutableList<Pair<String, Long>> =
        seedStartedAt.mapIndexed { index, at -> "run-$index" to at }.toMutableList()

    /** 收到的 cutoff 序列（断言"按设置的保留天数算"）。 */
    val deletedCutoffs = mutableListOf<Long>()

    var deleteThrows: Boolean = false

    var countThrows: Boolean = false

    override suspend fun recent(limit: Int): List<RunSummary> = emptyList()

    override suspend fun recentForScript(
        scriptId: Long,
        limit: Int,
    ): List<RunSummary> = emptyList()

    override suspend fun find(runId: String): RunSummary? = null

    override suspend fun readLogs(
        runId: String,
        limit: Int,
        offset: Int,
    ): List<LogEntry> = emptyList()

    override suspend fun countLogs(runId: String): Int = 0

    override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
        deletedCutoffs += cutoffMillis
        if (deleteThrows) error("run history delete failed (fake)")
        val before = runs.size
        runs.removeAll { (_, startedAt) -> startedAt < cutoffMillis }
        return before - runs.size
    }

    override suspend fun countAll(): Int {
        if (countThrows) error("run history count failed (fake)")
        return runs.size
    }
}

/** 可写的服务状态假件。 */
internal class FakeServiceStateProvider(
    initial: ForegroundState = ForegroundState.Idle,
    sources: List<EventSourceStatus> = emptyList(),
) : ServiceStateProvider {
    private val stateFlow = MutableStateFlow(initial)
    private val sourcesFlow = MutableStateFlow(sources)

    override val state: StateFlow<ForegroundState> = stateFlow

    override val sources: StateFlow<List<EventSourceStatus>> = sourcesFlow

    fun setState(value: ForegroundState) {
        stateFlow.value = value
    }

    fun setSources(value: List<EventSourceStatus>) {
        sourcesFlow.value = value
    }
}

/**
 * 权限假件。
 *
 * ## 为什么 [settingsTargetFor] 直接转给 `SettingsTargets`
 * 该映射是**冻结的领域判定**（决策 9 + 裁定 ③），假件自己再实现一遍等于把真相复制成两份
 * ——两份迟早漂移，而漂移的表现是"UI 说能跳、实际跳不了"。
 */
internal class FakePermissionStatusProvider(
    initial: Map<AndroidPermission, PermissionState> = emptyMap(),
) : PermissionStatusProvider {
    private val stateFlow = MutableStateFlow(initial)

    var refreshCalls: Int = 0
        private set

    /** `true` = 刷新时抛（验告警路径）。 */
    var refreshThrows: Boolean = false

    override fun current(): Map<AndroidPermission, PermissionState> = stateFlow.value

    override fun observe() = stateFlow

    override fun refresh() {
        refreshCalls++
        if (refreshThrows) error("permission probe failed (fake)")
    }

    override fun settingsTargetFor(permission: AndroidPermission): SettingsTarget? = SettingsTargets.resolve(permission)

    fun setStates(value: Map<AndroidPermission, PermissionState>) {
        stateFlow.value = value
    }
}

/**
 * 带**闸门**的熔断器假件。
 *
 * ## 为什么不复用 `data.event.FakeCircuitBreaker`
 * 本页要断言 `busy` 的**防重入**（连点两次"立即熔断"只能有一次真的执行）。
 * 防重入只有在动作**悬挂**时才可观测；既有假件的动作是同步完成的，
 * 用它会得到"两次都执行完了，busy 看起来没起作用"的假象。故这里给动作加一个
 * [gate]（`null` = 不悬挂，立即完成）。
 */
internal class GatedFakeCircuitBreaker(
    initialSafeMode: Boolean = false,
    initialReason: TripReason? = null,
) : CircuitBreaker {
    private val safeModeFlow = MutableStateFlow(initialSafeMode)
    private val reasonFlow = MutableStateFlow(initialReason)

    override val safeMode: StateFlow<Boolean> = safeModeFlow

    override val tripReason: StateFlow<TripReason?> = reasonFlow

    var manualTrips: Int = 0
        private set

    /** 恢复调用序列（断言 `RestoreMode` 被原样传达）。 */
    val restores = mutableListOf<RestoreMode>()

    /** `true` = 动作抛（验失败可见）。 */
    var actionThrows: Boolean = false

    /** 非 `null` 时动作会先 `await()` 它（用来制造"进行中"窗口）。 */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun onRunAccepted(scriptId: Long) = Unit

    override suspend fun onRunFinished(
        scriptId: Long,
        exitCode: Int?,
    ) = Unit

    override suspend fun onRunTimeout(
        scriptId: Long,
        timeoutMillis: Long,
    ) = Unit

    override suspend fun onRootUnresponsive(elapsedMillis: Long) = Unit

    override suspend fun tripManually() {
        gate?.await()
        if (actionThrows) error("trip failed (fake)")
        manualTrips++
        safeModeFlow.value = true
        reasonFlow.value = TripReason.Manual
    }

    override suspend fun tripForBootloop(crashes: Int) {
        safeModeFlow.value = true
        reasonFlow.value = TripReason.Bootloop(crashes = crashes)
    }

    override suspend fun restore(mode: RestoreMode) {
        gate?.await()
        if (actionThrows) error("restore failed (fake)")
        restores += mode
        safeModeFlow.value = false
        reasonFlow.value = null
    }

    override suspend fun probeExternalSafeMode(): TripReason? = null
}

/** 便于构造事件源快照。 */
internal fun runningSource(sourceId: String): EventSourceStatus =
    EventSourceStatus(sourceId = sourceId, state = EventSourceState.Running)

/** 便于构造"缺权限"的事件源快照。 */
internal fun unavailableSource(
    sourceId: String,
    reason: String,
): EventSourceStatus = EventSourceStatus(sourceId = sourceId, state = EventSourceState.Unavailable(reason))

/**
 * 残留清理假件（阶段 6d，需求 §8）。
 *
 * ## 为什么它能改"清理后的报告"
 * 设置页在清理后会**重扫**，UI 上的"N 项残留"必须变成清理后的真实值。
 * 若假件不能改报告，就断言不出"重扫"这件事真的发生了
 * （只会看到两个相同的数字 —— 那正是"没重扫"也会通过的错误用例）。
 */
internal class FakeResidueCleaner(
    initialReport: ResidueReport = ResidueReport(orphanScriptDirs = emptyList(), orphanTriggerRows = 0),
) : ResidueCleaner {
    /** 当前扫描报告（清理后可由 [reportAfterClean] 改写）。 */
    var report: ResidueReport = initialReport

    /** 非 `null` = 扫描返回不可用（验"不静默"）。 */
    var scanUnavailable: String? = null

    /** 清理后报告变成什么；`null` = 不改（模拟"没删干净"）。 */
    var reportAfterClean: ResidueReport? = null

    /** 清理返回的结果；`null` = 按当前报告推导一个"全删成功"的结果。 */
    var cleanResult: ResidueCleanResult? = null

    var cleanThrows: Boolean = false

    var scans: Int = 0
        private set

    var cleans: Int = 0
        private set

    override suspend fun scan(): ResidueScan {
        scans++
        val reason = scanUnavailable
        return if (reason != null) ResidueScan.Unavailable(reason) else ResidueScan.Ok(report)
    }

    override suspend fun clean(): ResidueCleanResult {
        cleans++
        if (cleanThrows) error("residue clean failed (fake)")
        val result =
            cleanResult
                ?: ResidueCleanResult(
                    removedScriptDirs = report.orphanScriptDirs,
                    removedTriggerRows = report.orphanTriggerRows,
                    failures = emptyList(),
                )
        reportAfterClean?.let { report = it }
        return result
    }
}
