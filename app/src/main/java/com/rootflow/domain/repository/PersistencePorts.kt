package com.rootflow.domain.repository

import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import kotlinx.coroutines.flow.Flow

/**
 * 脚本装载结果。
 *
 * **绝不返回"空正文表示失败"**：空正文会让脚本静默地什么也不做。失败必须是可区分的显式状态
 * （与 `data/fs/RootFileResult` 同款纪律，见 `AGENT_PROTOCOL.md §8`）。
 */
sealed interface ScriptLoadResult {
    data class Ok(
        val script: Script,
    ) : ScriptLoadResult

    /** 元数据在库中，但正文文件不存在。 */
    data object Missing : ScriptLoadResult

    /** 正文摘要与库中记录不符（文件被外部改动）。 */
    data class Corrupted(
        val expected: String,
        val actual: String,
    ) : ScriptLoadResult

    /** 元数据不在库中。 */
    data object NotFound : ScriptLoadResult

    /** root 通道不可用或命令失败。 */
    data class Unavailable(
        val reason: String,
    ) : ScriptLoadResult
}

/** 写操作结果（保存/删除）。 */
sealed interface WriteResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : WriteResult<T>

    data class Failed(
        val reason: String,
    ) : WriteResult<Nothing>
}

/**
 * 脚本仓库（阶段 3a）。
 *
 * ## 两处存储、一处真相
 * - **元数据** → Room（[Script] 的除 `content` 外的字段）
 * - **正文** → 文件系统 `/data/local/tmp/rootflow/scripts/<id>/main.sh`（需求 §3.2）
 *
 * 正文**不缓存进内存**：每次 [load] 都从文件系统读——脚本正文很短（KB 级），
 * 而缓存会引入"缓存与文件不一致"的第二处真相。
 */
interface ScriptRepository {
    /** 观察全部脚本元数据（不含正文，避免每次列表刷新都读文件）。 */
    fun observeAll(): Flow<List<Script>>

    /** 读取单个脚本（含正文）。 */
    suspend fun load(id: Long): ScriptLoadResult

    /**
     * 新建或更新脚本。
     *
     * 顺序保证：先写元数据拿到 `id` → 再写正文文件 → 回填 `contentPath`/`contentSha256`。
     * **任一步失败则回滚**（新增时删行；更新时保留旧行不动），不留"有行无文件"的悬挂元数据。
     *
     * @return 成功时返回落库后的脚本（含 `id` 与摘要）
     */
    suspend fun save(script: Script): WriteResult<Script>

    /**
     * 删除脚本。
     *
     * 先删元数据（`ON DELETE CASCADE` 会一并删除其触发器），再删正文目录。
     * 文件删除失败**不阻塞**行删除（行是真相源），但会返回 [WriteResult.Failed] 以便告警。
     */
    suspend fun delete(id: Long): WriteResult<Unit>
}

/**
 * **脚本 × 事件**订阅仓库（需求 §2.3：唯一真相源 = Room，表 `script_events`）。
 *
 * ## ★ 语义变化（总开关重构，v1 → v2）
 * 旧端口的核心方法叫 `enabledForEvent`，SQL 带 `AND enabled = 1`。重构后
 * **存在即订阅**（表里没有 `enabled` 列），因此：
 * - `enabledForEvent` **删除**，改为 [forEvent]（不再有条件可判）
 * - [replaceForScript] 取代"逐条 save/update 再删多余的"那套增量写入
 * - 不再有"暂停某个订阅但保留它"这种状态 —— 要停就删，要开就插
 *
 * ## 事件的新语义（配套 [Script.resident]）
 * 事件**不再是"启动脚本"的理由**，而是**投递给正在运行的常驻脚本的通知**。
 * ⇒ 单次脚本（`resident = false`）在本表里有行也没有意义，UI 应据 `resident` 决定是否展示订阅区。
 */
interface TriggerRepository {
    /** 观察某脚本的全部订阅。 */
    fun observeForScript(scriptId: Long): Flow<List<Trigger>>

    /**
     * 观察**全部**订阅。
     *
     * ## 为什么要"持续订阅"而不是"一次性读"
     * 常驻脚本的监管必须在**订阅被勾选/取消的那一刻**就生效。
     * 之前只有挂起函数 [all]（一次性快照）时，"新建一个订阅"要等**下次前台服务启动**
     * 才起作用 —— 用户勾完没有任何反应，是个真实验缺口（阶段 10 已修）。
     */
    fun observeAll(): Flow<List<Trigger>>

    /** 一次性全量快照（与 [observeAll] 的分工：这是一次性读，那是持续订阅）。 */
    suspend fun all(): List<Trigger>

    /**
     * 调度器热路径：取订阅了 [eventType] 的全部脚本。
     *
     * `params` 的 JSON 解析失败时回退为默认值并触发 `paramsWarningSink`（不崩、不静默）。
     */
    suspend fun forEvent(eventType: String): List<Trigger>

    /**
     * **覆盖式写入**某脚本的全部订阅：先清空、再插入 [triggers]。
     *
     * ## 为什么需要它（而不是逐条 save）
     * 编辑器的交互是"勾选集合" —— 用户勾完得到的是一个**集合**，而不是一串增删指令。
     * 覆盖式写入让"库里的订阅集合 == 用户看到的勾选集合"成为**一次调用就能保证**的事实，
     * 不需要调用方自己算差集（算错就会留下幽灵订阅）。
     *
     * 实现必须放在**单事务**里，否则"清空成功、插入失败"会丢掉用户的全部订阅。
     */
    suspend fun replaceForScript(
        scriptId: Long,
        triggers: List<Trigger>,
    ): WriteResult<List<Trigger>>

    suspend fun delete(id: Long): WriteResult<Unit>

    /** 统计某脚本的订阅数量（删除脚本前提示用）。 */
    suspend fun countForScript(scriptId: Long): Int
}

/**
 * 运行历史仓库（阶段 3a，**只读**）。
 *
 * 写入由 `data/run/RunHistoryWriter.kt` 在管道批次到达时完成，本端口只暴露查询，
 * 以免调用方绕过管道的写入路径。
 *
 * 本端口**正面解决阶段 2 遗留的"`tail()` 仅运行中可用"**：运行结束后日志在 Room 里。
 */
interface RunHistoryRepository {
    suspend fun recent(limit: Int): List<RunSummary>

    suspend fun recentForScript(
        scriptId: Long,
        limit: Int,
    ): List<RunSummary>

    suspend fun find(runId: String): RunSummary?

    /** 分页读取某次运行的日志（按 `sequence` 升序）。 */
    suspend fun readLogs(
        runId: String,
        limit: Int,
        offset: Int,
    ): List<com.rootflow.domain.model.LogEntry>

    /** 某次运行的日志总行数（分页用）。 */
    suspend fun countLogs(runId: String): Int

    /**
     * 删除 `started_at` 早于 [cutoffMillis] 的运行（阶段 6d 的「立即清理」）。
     *
     * ## 为什么加在只读端口上（而不是另开一个"清理端口"）
     * 清理的**对象**与查询完全同一份数据（`runs` 表），拆成两个端口只会让
     * "谁在什么时候删了运行历史"分散到两处；而调用方（设置页）本来就已经持有本端口。
     * 既有只读方法的**语义一律不变**：这里只**新增**两个方法，不改任何既有签名。
     *
     * ## 级联
     * 日志行（`run_log_entries`）随 `ON DELETE CASCADE` 一并删除，
     * 因此调用方**不需要**（也**不应该**）自己再删一遍日志。
     *
     * @param cutoffMillis 截止时间戳（用 `LogRetention.cutoffMillis(now, days)` 生成）
     * @return 实际删除的运行条数（`0` = 保留期内没有过期运行，属正常结果而非失败）
     */
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    /**
     * 运行历史总条数。
     *
     * 用途：设置页「日志」组显示"当前 N 条"，以及清理前后的**可核对差异**
     * （清理若报删除 0 条，用户需要一个数能对照）。
     */
    suspend fun countAll(): Int
}
