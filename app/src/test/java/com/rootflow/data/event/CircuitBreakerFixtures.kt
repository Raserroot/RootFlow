package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.fs.FakeRootShell
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.data.run.RunSessionRegistry
import com.rootflow.domain.event.BootloopDecision
import com.rootflow.domain.event.CrashMarkerStore
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.SafeModeNotifier
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// 阶段 4 测试脚手架：熔断编排测试需要的全部替身。
// 与 EventTestDoubles / RunChainTestDoubles 同款纪律：集中一处，读一次即掌握注入面。

/**
 * 记录型崩溃标记存储（内存版）。
 *
 * ## 为什么手写而不 MockK
 * `BootloopGuard` 的语义是**读改写**（读旧标记 → 判处置 → 写新标记 → 计数）。
 * 用 MockK 逐调用打桩会把"状态如何在调用之间流转"藏进 `every { }` 列表里，
 * 而本类要断言的恰恰是那个状态流转（例如"先写 in-progress 再判健康"）。
 * 内存实现让状态可见、可断言。
 */
internal class InMemoryCrashMarkerStore(
    private var bootId: Long? = null,
    private var healthy: Boolean? = null,
    private var crashCount: Int = 0,
) : CrashMarkerStore {
    override fun readStartupMarker(): BootloopDecision.StartupMarker? {
        val id = bootId ?: return null
        if (id <= 0L) return null
        return BootloopDecision.StartupMarker(bootId = id, healthy = healthy ?: false)
    }

    override fun markStartupInProgress(bootId: Long) {
        this.bootId = bootId
        healthy = false
    }

    override fun markHealthy(bootId: Long) {
        this.bootId = bootId
        healthy = true
    }

    override fun readCrashCount(): Int = crashCount

    override fun writeCrashCount(count: Int) {
        crashCount = count
    }

    /** 只清计数，保留启动标记（与真实实现同语义；阶段 5 修复的端口方法）。 */
    override fun resetCrashCount() {
        crashCount = 0
    }

    override fun clear() {
        bootId = null
        healthy = null
        crashCount = 0
    }

    /** 供断言：当前是否已置"本次启动健康"。 */
    fun isHealthy(): Boolean? = healthy

    /** 供断言：当前记录的启动标识。 */
    fun recordedBootId(): Long? = bootId
}

/** 记录型安全模式通知（阶段 4 的空实现只记日志，这里把调用记下来以便断言）。 */
internal class RecordingSafeModeNotifier : SafeModeNotifier {
    val trips = mutableListOf<TripReason>()
    var restores: Int = 0
        private set

    override fun onTrip(reason: TripReason) {
        trips += reason
    }

    override fun onRestore() {
        restores++
    }
}

/**
 * 可编排的 Root 健康探针。
 *
 * [snapshots] 按调用顺序返回（用尽后重复最后一个），因此"先失败后成功"这类
 * 序列可以用例直接写出，而不必引入可变状态。
 */
internal class FakeRootHealthProbe(
    private val snapshots: List<RootHealthProbe.Snapshot> =
        listOf(RootHealthProbe.Snapshot(responsive = true, elapsedMillis = 5L)),
    private val external: TripReason? = null,
) : RootHealthProbe {
    var probeCalls: Int = 0
        private set

    var externalCalls: Int = 0
        private set

    override suspend fun probe(): RootHealthProbe.Snapshot {
        val index = probeCalls.coerceAtMost(snapshots.lastIndex)
        probeCalls++
        return snapshots[index]
    }

    override suspend fun detectExternalSafeMode(): TripReason? {
        externalCalls++
        return external
    }
}

/** 内存版运行历史（只实现 D11 预置与查询需要的那两个方法）。 */
internal class InMemoryRunHistoryRepository(
    private var rows: List<RunSummary> = emptyList(),
) : RunHistoryRepository {
    var recentCalls: Int = 0
        private set

    /** 让 `recent` 抛错（覆盖"读历史失败不得阻止启动"分支）。 */
    var failRecent: Boolean = false

    override suspend fun recent(limit: Int): List<RunSummary> {
        recentCalls++
        if (failRecent) throw IllegalStateException("history is on fire")
        return rows.take(limit)
    }

    override suspend fun recentForScript(
        scriptId: Long,
        limit: Int,
    ): List<RunSummary> = rows.filter { it.scriptId == scriptId }.take(limit)

    override suspend fun find(runId: String): RunSummary? = rows.firstOrNull { it.runId == runId }

    override suspend fun readLogs(
        runId: String,
        limit: Int,
        offset: Int,
    ): List<com.rootflow.domain.model.LogEntry> = emptyList()

    override suspend fun countLogs(runId: String): Int = 0

    // 阶段 6d 新增：熔断用例集不涉及保留策略清理 ⇒ 如实返回"没删"与 0 条
    override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0

    override suspend fun countAll(): Int = 0
}

/**
 * 记录型触发器仓库（熔断的第 3 步要"读全量 + 逐条禁用/还原"）。
 *
 * 与 `TriggerDispatcherImplTest` 里的同名假件不同：本类**可变**（`save` 会真的改内存），
 * 因为熔断编排的正确性恰恰体现在"禁用后能按快照还原"这一状态变化上。
 */
internal class MutableTriggerRepository(
    initial: List<Trigger> = emptyList(),
) : TriggerRepository {
    val items = initial.toMutableList()

    /** 让 `save` 全部失败（覆盖"禁用触发器失败不得中断后续步骤"分支）。 */
    var failSave: Boolean = false

    /** 让 `all()` 抛错（覆盖 `runCatching` 的兜底）。 */
    var failAll: Boolean = false

    override fun observeForScript(scriptId: Long): Flow<List<Trigger>> =
        flow { emit(items.filter { it.scriptId == scriptId }) }

    override fun observeAll(): Flow<List<Trigger>> = flow { emit(items.toList()) }

    override suspend fun all(): List<Trigger> {
        if (failAll) throw IllegalStateException("trigger table is on fire")
        return items.toList()
    }

    override suspend fun forEvent(eventType: String): List<Trigger> = items.filter { it.eventType == eventType }

    /**
     * 总开关重构后没有单条 `save`（订阅是**集合替换**）。
     * 保留 [failSave] 开关的语义：置位时整个替换失败。
     */
    override suspend fun replaceForScript(
        scriptId: Long,
        triggers: List<Trigger>,
    ): WriteResult<List<Trigger>> {
        if (failSave) return WriteResult.Failed("save refused")
        items.removeAll { it.scriptId == scriptId }
        val saved = triggers.map { it.copy(scriptId = scriptId) }
        items.addAll(saved)
        return WriteResult.Ok(saved)
    }

    override suspend fun delete(id: Long): WriteResult<Unit> {
        items.removeAll { it.id == id }
        return WriteResult.Ok(Unit)
    }

    override suspend fun countForScript(scriptId: Long): Int = items.count { it.scriptId == scriptId }

    /**
     * 当前**生效**的订阅 id（真机判读同款口径）。
     *
     * 新模型「存在即订阅」⇒ 库里有行就是生效的，因此恒为全部 id。
     * 方法名保留是为了不动调用方（它们断言的是"哪些订阅生效"）。
     */
    fun enabledIds(): List<Long> = items.map { it.id }
}

/** 构造一条触发器（默认启用）。 */
internal fun trigger(
    id: Long,
    scriptId: Long = id,
    enabled: Boolean = true,
    eventType: String = "boot",
): Trigger =
    Trigger(
        id = id,
        scriptId = scriptId,
        eventType = eventType,
        params = TriggerParams(),
        enabled = enabled,
        createdAt = 0L,
    )

/** 构造一条运行历史摘要（D11 的预置输入）。 */
internal fun runSummary(
    runId: String,
    startedAt: Long,
    exitCode: Int?,
    scriptId: Long = 1L,
): RunSummary =
    RunSummary(
        runId = runId,
        scriptId = scriptId,
        triggerEvent = "boot",
        startedAt = startedAt,
        finishedAt = startedAt + 1,
        exitCode = exitCode,
        logEntryCount = 0,
        droppedLogEntries = 0,
    )

/**
 * 熔断编排测试的公共装配。
 *
 * 把"用真实 `RootFileStore` + `FakeRootShell`"作为默认：熔断的第 1/3/6 步都是**文件动作**，
 * 用真实现才能验到"flag 真的写了 / 快照真的能读回"——那正是六步里最容易假绿的部分。
 */
internal class CircuitBreakerFixture(
    /**
     * "订阅表护栏探针"（P6 起换了个角色）。
     *
     * ## 它**不再**被注入给 `CircuitBreakerImpl`
     * 那个构造形参已删（见 `CircuitBreakerImpl` 的 KDoc：熔断改成内存级拦截后，
     * 六步动作里没有任何一步需要触发器表）。
     *
     * ## 那为什么留着它
     * 它支撑着一批"熔断 / 恢复**不得**改动用户订阅"的用例（`enabledIds()` 断言）。
     * 删掉它，那些用例就只剩注释里的历史，**没有任何东西守着这条不变量** ——
     * 一旦谁把 `TriggerRepository` 依赖加回熔断器并真的写库，不会有任何测试报警。
     * 留着它，那批断言就变成一道"架构不许回退"的护栏。
     *
     * ## 读这些用例时要知道的事
     * 此刻它们是**恒真**的（依赖不存在 ⇒ 没人能动这个仓储）。恒真不是假绿：
     * 它们守的是"依赖不许回来"，而那正是 P6 之后的正确性来源。
     */
    val triggers: MutableTriggerRepository = MutableTriggerRepository(),
    val shell: FakeRootShell = FakeRootShell(),
    val sessions: RunSessionRegistry = RunSessionRegistry(),
    val notifier: RecordingSafeModeNotifier = RecordingSafeModeNotifier(),
    val probe: FakeRootHealthProbe = FakeRootHealthProbe(),
    val history: InMemoryRunHistoryRepository = InMemoryRunHistoryRepository(),
    val processGroupManager: com.rootflow.runtime.ProcessGroupManager =
        com.rootflow.runtime.ProcessGroupManager(
            shell.manager,
        ) { runId -> java.io.File("C:/tmp/.rf_pgid_$runId") },
) {
    val fileStore: RootFileStore = RootFileStore(shell.manager)

    /** 安全模式标志文件是否存在（真机同款判据：**存在即安全模式**）。 */
    fun flagExists(): Boolean = shell.files.containsKey(RootFlowPaths.SAFE_MODE_FLAG)

    /** 快照文件内容；不存在返回 `null`。 */
    fun stateFile(): String? = shell.files[RootFlowPaths.SAFE_MODE_FLAG.replace("safemode.flag", "safemode.state")]

    /** 熔断日志的全部行。 */
    fun safeModeLogLines(): List<String> =
        shell.files[RootFlowPaths.SAFE_MODE_LOG]
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    companion object {
        /** 装配一个已静音 Android `Log` 的环境（本仓库所有数据层单测的既有做法）。 */
        fun installAndroidLogStubs() {
            mockkStatic(Log::class)
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
        }
    }
}
