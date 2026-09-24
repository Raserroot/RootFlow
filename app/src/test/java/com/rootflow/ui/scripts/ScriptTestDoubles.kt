package com.rootflow.ui.scripts

import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.ui.AlertSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/*
 * 配置页单测的公共假件（阶段 6c）。
 *
 * ## 为什么放在共享文件而不是各自的测试类里
 * `ScriptListViewModelTest` 与 `ScriptEditorViewModelTest` 需要**同一套**假仓库语义。
 * 各写一份会让"两个 ViewModel 面对的仓库行为不同"成为一个静默的测试偏差
 * （本仓库反复禁止的形态：两处各自自洽，而接缝没人测）。
 *
 * ## 它们模拟的关键性质
 * | 假件 | 关键性质 |
 * |---|---|
 * | `FakeScriptRepository` | `observeAll` 是**热流**（写入后订阅者能看到新值）；`load` 可被逐个打桩；`save` 可失败并**不落库** |
 * | `FakeTriggerRepository` | `all()` 可被挂起（制造"计数还没到"的真实窗口）与失败 |
 *
 * 用块注释而非 KDoc：ktlint 的 `kdoc` 规则不允许"一个 KDoc 紧邻另一个 KDoc"
 * （本文件顶部若写成 KDoc，就会与 `testScript` 的 KDoc 相撞）。
 */

/**
 * 记录型告警落点（阶段 6e）。
 *
 * ## 为什么它是一个实现，而不是"构造后给 `var` 赋值"
 * 6e 之前 `onWarning` 是 ViewModel 里的 `internal var`，测试靠 `.also { it.onWarning = … }`
 * 在**构造之后**接线 ⇒ `init` 里同步打出的告警落进默认 no-op，永远断言不到。
 * 现在它由构造器注入 [AlertSink] 实例 ⇒ 缝在第一行代码之前就接好了。
 *
 * ## 为什么 `messages` 是 `val` 而不是构造参数
 * 测试通常在 `@BeforeEach` 里 `alertSink = RecordingAlertSink()`，而**断言**发生在用例里
 * —— 每次都 `clear()` 比每次新建实例更省心，也避免"旧实例的列表被断言到"。
 */
internal class RecordingAlertSink : AlertSink {
    val messages: MutableList<String> = mutableListOf()

    override fun warn(message: String) {
        messages += message
    }

    fun clear() {
        messages.clear()
    }
}

/** 测试用的脚本构造（`id = 0` 表示新建）。 */
internal fun testScript(
    id: Long,
    name: String = "script-$id",
    language: String = "shell",
    enabled: Boolean = true,
    timeoutSec: Int = 0,
    autoDisableOnFail: Boolean = false,
    runOnSafeMode: Boolean = false,
    content: String = "echo $id",
    contentSha256: String? = "sha256:test-$id",
    createdAt: Long = 1_000L * id,
    updatedAt: Long = 2_000L * id,
): Script =
    Script(
        id = id,
        name = name,
        language = language,
        enabled = enabled,
        timeoutSec = timeoutSec,
        autoDisableOnFail = autoDisableOnFail,
        runOnSafeMode = runOnSafeMode,
        content = content,
        contentSha256 = contentSha256,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** 测试用的触发器构造。 */
internal fun testTrigger(
    id: Long,
    scriptId: Long,
    eventType: String = "boot",
    enabled: Boolean = true,
): Trigger =
    Trigger(
        id = id,
        scriptId = scriptId,
        eventType = eventType,
        params = TriggerParams(),
        enabled = enabled,
        createdAt = id,
    )

/**
 * 脚本仓库假件。
 *
 * ## 为什么 `observeAll()` 是"每次调用建一条新流"
 * 真实 `ScriptRepositoryImpl.observeAll()` 就是
 * `scriptDao.observeAll().map { … }` —— 一次调用一条 Room 查询流。
 * 假件照抄这个形态，是为了让"ViewModel 订阅两次"这种缺陷**在测试里也表现为两条流**
 * （如果假件返回同一个 `StateFlow` 实例，那个缺陷就永远测不出来）。
 */
internal class FakeScriptRepository : ScriptRepository {
    /** 库里的脚本（`observeAll` 与 `load` 都读它）。 */
    val stored: MutableStateFlow<List<Script>> = MutableStateFlow(emptyList())

    /** `load` 的逐个打桩：命中就返回它，否则按 [stored] 返回 `Ok` / `NotFound`。 */
    val loadResults: MutableMap<Long, ScriptLoadResult> = mutableMapOf()

    /** `save` 收到的全部入参（顺序敏感：用来断言"恰一次"）。 */
    val saveCalls: MutableList<Script> = mutableListOf()

    /** `delete` 收到的 id。 */
    val deleteCalls: MutableList<Long> = mutableListOf()

    /** 非 null 时 `save` 返回它（不落库）。 */
    var saveFailure: String? = null

    /** 非 null 时 `delete` 返回它（**仍会从库里删掉**，与真实语义一致：行删除是真相源）。 */
    var deleteFailure: String? = null

    /** 非 null 时 `load` 抛出它（覆盖 ViewModel 的最后一道防线）。 */
    var loadThrows: Throwable? = null

    /** 新建时分配的自增 id。 */
    private var nextId: Long = 100L

    override fun observeAll(): Flow<List<Script>> = stored.map { it }

    override suspend fun load(id: Long): ScriptLoadResult {
        loadThrows?.let { throw it }
        loadResults[id]?.let { return it }
        val found = stored.value.firstOrNull { it.id == id }
        return if (found == null) ScriptLoadResult.NotFound else ScriptLoadResult.Ok(found)
    }

    override suspend fun save(script: Script): WriteResult<Script> {
        saveCalls += script
        saveFailure?.let { return WriteResult.Failed(it) }
        val id = if (script.id == 0L) nextId++ else script.id
        val storedScript = script.copy(id = id)
        stored.value = stored.value.filterNot { it.id == id } + storedScript
        return WriteResult.Ok(storedScript)
    }

    override suspend fun delete(id: Long): WriteResult<Unit> {
        deleteCalls += id
        stored.value = stored.value.filterNot { it.id == id }
        deleteFailure?.let { return WriteResult.Failed(it) }
        return WriteResult.Ok(Unit)
    }
}

/**
 * 触发器仓库假件。
 *
 * ## [pendingGate] / [failAfterGate]：制造"计数快照还没到"的真实窗口
 * 计数是**一次性查询**，而列表是**热流**；两者的到达时间差正是 §0.3 ① 要防的那个坑。
 * 用 `CompletableDeferred` 把查询挂住，那个窗口就从"偶发"变成"必然"。
 *
 * ## [failNext]：只失败一次
 * "首次失败 ⇒ 全部显示未知"与"刷新失败 ⇒ 保留旧值"是两条不同的路径，
 * 必须能精确地只让第一次失败。
 */
internal class FakeTriggerRepository : TriggerRepository {
    val triggers: MutableStateFlow<List<Trigger>> = MutableStateFlow(emptyList())

    /** `all()` 的调用次数（断言"返回列表时刷了一次"）。 */
    var allCalls: Int = 0
        private set

    /** `countForScript` 的调用次数。 */
    var countCalls: Int = 0
        private set

    /** 非 null 时 `all()` 先挂起等它完成。 */
    var pendingGate: CompletableDeferred<Unit>? = null

    /** 非 null 时 `all()` 抛出它（每次调用都抛）。 */
    var failWith: Throwable? = null

    /** 只让**下一次** `all()` 失败（用于"首次失败 / 之后成功"）。 */
    var failNext: Throwable? = null

    /** `countForScript` 的返回值（默认按 [triggers] 数）。 */
    var countOverride: Int? = null

    /** 非 null 时 `countForScript` 抛出它（编辑器摘要的失败路径）。 */
    var countFailure: Throwable? = null

    /**
     * `replaceForScript` 收到的全部入参（**展平**：一次集合替换的每一项都在这里，顺序敏感）。
     *
     * ★ 记录的是**补值之前**的入参：`id`（0 → 自增）与 `createdAt`（0 → 现在）在落库阶段才补。
     * 要断言"落库后的形态"请看 [triggers] —— 那才是库里的真相。
     */
    val saveCalls: MutableList<Trigger> = mutableListOf()

    /** `delete` 收到的全部 id（顺序敏感：diff 的调用序列是契约）。 */
    val deleteCalls: MutableList<Long> = mutableListOf()

    /**
     * 命中这些事件类型时 `save` 失败（不落库）。
     *
     * **按事件类型**而不是"第 N 次调用"：6e 的失败断言要精确到"哪一条没存上"，
     * 而"第 N 次"会让用例在 diff 顺序变化时静默地测错对象。
     */
    val failSaveForEvents: MutableSet<String> = mutableSetOf()

    /** 命中这些 id 时 `delete` 失败（**仍会从库里删掉**，与真实语义一致：行删除是真相源）。 */
    val failDeleteForIds: MutableSet<Long> = mutableSetOf()

    /** 新建时分配的自增 id 起点。 */
    private var nextId: Long = 1_000L

    /** 补 `createdAt` 用的单调"现在"（真实实现用 `System::currentTimeMillis`）。 */
    private var clockMillis: Long = 500_000L

    override fun observeForScript(scriptId: Long): Flow<List<Trigger>> =
        triggers.map { list -> list.filter { it.scriptId == scriptId } }

    /** 阶段 10：常驻脚本的监工订阅它做增删对账（与 [observeForScript] 同源，不另造真相）。 */
    override fun observeAll(): Flow<List<Trigger>> = triggers

    override suspend fun all(): List<Trigger> {
        allCalls++
        pendingGate?.await()
        failNext?.let { failure ->
            failNext = null
            throw failure
        }
        failWith?.let { throw it }
        return triggers.value
    }

    /**
     * 与真实 SQL 同款过滤（`AND enabled = 1`）：`enabled = 0` = **停用但保留配置**，
     * 那些行不参与投递。漏掉它会"禁用行不投递"这条不变量假绿。
     */
    override suspend fun forEvent(eventType: String): List<Trigger> =
        triggers.value.filter { it.eventType == eventType && it.enabled }

    /**
     * 覆盖式写入（总开关重构后取代逐条 `save`）。
     *
     * [failSaveForEvents] 的语义保持：**该脚本意图订阅的事件里有一个在名单上** ⇒ 整体失败。
     * 这与旧的"那一条存不上"是同一个断言强度（"没生效"），
     * 而新模型下失败是**整体**的（要么整个集合写成功、要么都不写）—— 这正是重构带来的简化。
     */
    override suspend fun replaceForScript(
        scriptId: Long,
        triggers: List<Trigger>,
    ): WriteResult<List<Trigger>> {
        saveCalls += triggers
        val blocked = triggers.firstOrNull { it.eventType in failSaveForEvents }
        if (blocked != null) {
            return WriteResult.Failed("trigger save failed (fake, event=${blocked.eventType})")
        }
        val saved =
            triggers.map { trigger ->
                val id = if (trigger.id == 0L) nextId++ else trigger.id
                trigger.copy(
                    scriptId = scriptId,
                    id = id,
                    // ★ 与真实实现同款补值：`createdAt = 0` 由仓库补成"现在"
                    //   （0 会被 Room 当成"未设置"）。断言"落库后 createdAt 非 0"请看 [triggers]，
                    //   **不能看 [saveCalls]** —— 那记录的是补值**之前**的入参。
                    createdAt = if (trigger.createdAt == 0L) clockMillis++ else trigger.createdAt,
                )
            }
        this.triggers.value = this.triggers.value.filterNot { it.scriptId == scriptId } + saved
        return WriteResult.Ok(saved)
    }

    override suspend fun delete(id: Long): WriteResult<Unit> {
        deleteCalls += id
        triggers.value = triggers.value.filterNot { it.id == id }
        if (id in failDeleteForIds) {
            return WriteResult.Failed("trigger delete failed (fake, id=$id)")
        }
        return WriteResult.Ok(Unit)
    }

    override suspend fun countForScript(scriptId: Long): Int {
        countCalls++
        countFailure?.let { throw it }
        return countOverride ?: triggers.value.count { it.scriptId == scriptId }
    }
}
