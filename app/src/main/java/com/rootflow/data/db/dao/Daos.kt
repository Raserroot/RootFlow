package com.rootflow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rootflow.data.db.entity.AppSwitchRow
import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow
import kotlinx.coroutines.flow.Flow

/** 脚本元数据 DAO。正文不入库，见 [ScriptRow] KDoc。 */
@Dao
interface ScriptDao {
    @Insert
    suspend fun insert(row: ScriptRow): Long

    @Update
    suspend fun update(row: ScriptRow)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun findById(id: Long): ScriptRow?

    /**
     * 按脚本名查找（阶段 3d 新增）。
     *
     * 用途单一且明确：**预置数据的幂等判据**（阶段 6e 起唯一消费者是
     * `Stage4VerificationFixtures` —— 它在诊断闸门内按 `__4_*` 名字查脚本）。
     * 用名字而不是 id，是因为 id 是自增的（重装 / 清库后会变），
     * 而"预置脚本是否已存在"应跨这些变化保持一致。
     *
     * 不走 `name` 索引的**唯一性**假设：`ScriptRow.name` 只有普通索引（可重名），
     * 因此 `LIMIT 1` 显式取一条，避免重名时抛异常。
     */
    @Query("SELECT * FROM scripts WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ScriptRow?

    @Query("SELECT * FROM scripts ORDER BY name")
    fun observeAll(): Flow<List<ScriptRow>>

    @Query("SELECT * FROM scripts ORDER BY id")
    suspend fun all(): List<ScriptRow>

    @Query("SELECT * FROM scripts WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<ScriptRow>
}

/**
 * **脚本 × 事件**订阅 DAO（原 `TriggerDao`，总开关重构后改名）。
 *
 * ## 语义变化（与旧 `TriggerDao` 的关键差别）
 * 1. **表名与索引**：`triggers` → `script_events`；索引从 `(event_type, enabled)` 复合
 *    改为单列 `event_type`
 * 2. **`enabled` 保留**：`false` = **停用但保留配置**（三态语义见 [ScriptEventRow.enabled]），
 *    因此 [forEvent] 仍带 `AND enabled = 1`
 * 3. **写入改成集合替换**：不再"逐条 save/update + 删多余的"，而是
 *    [deleteForScript] + 批量插入（见 `TriggerRepositoryImpl.replaceForScript`）
 *
 * ## 曾走过的弯路（留档，避免重犯）
 * 中途曾计划**删掉 `enabled` 列**、用"行存在与否"表达开关（"存在即订阅"）。
 * 实施到测试层才确认那条路会把「**停用但保留配置**」这个用户仍需要的状态抹掉
 * —— 那是**信息丢失，不是简化**。⇒ 保留字段，只把它降级为普通数据字段。
 *
 * 热路径是 [forEvent]（调度器每次事件都会查）。
 */
@Dao
interface ScriptEventDao {
    @Insert
    suspend fun insert(row: ScriptEventRow): Long

    @Query("DELETE FROM script_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM script_events ORDER BY id")
    fun observeAll(): Flow<List<ScriptEventRow>>

    @Query("SELECT * FROM script_events ORDER BY id")
    suspend fun all(): List<ScriptEventRow>

    /**
     * 调度器热路径：订阅了 [eventType] 且**生效**的订阅。
     *
     * `AND enabled = 1` 是必要的：`enabled = 0` 表示"停用但保留配置"
     * （见 [ScriptEventRow.enabled] 的 KDoc），那些行不该被投递。
     */
    @Query("SELECT * FROM script_events WHERE event_type = :eventType AND enabled = 1 ORDER BY id")
    suspend fun forEvent(eventType: String): List<ScriptEventRow>

    @Query("SELECT * FROM script_events WHERE script_id = :scriptId ORDER BY id")
    suspend fun forScript(scriptId: Long): List<ScriptEventRow>

    @Query("SELECT COUNT(*) FROM script_events WHERE script_id = :scriptId")
    suspend fun countForScript(scriptId: Long): Int

    /** 覆盖式写入一个脚本的全部订阅（编辑器保存时用：先清后插，单事务内）。 */
    @Query("DELETE FROM script_events WHERE script_id = :scriptId")
    suspend fun deleteForScript(scriptId: Long)
}

/**
 * 总开关 DAO（单行表，主键恒为 [AppSwitchRow.SINGLETON_ID]）。
 *
 * ## 为什么没有 `@Insert`/`@Update` 的常规用法
 * 单行表的"创建"与"修改"是同一件事：**没有行就插一行默认值**（迁移已保证有行，
 * 但全新安装时数据库回调可能尚未写入）。因此提供 [ensureRow] 做 upsert 语义。
 */
@Dao
interface AppSwitchDao {
    @Query("SELECT * FROM app_switch WHERE id = :id")
    fun observe(id: Long = AppSwitchRow.SINGLETON_ID): Flow<AppSwitchRow?>

    @Query("SELECT * FROM app_switch WHERE id = :id")
    suspend fun get(id: Long = AppSwitchRow.SINGLETON_ID): AppSwitchRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AppSwitchRow)

    /** 行不存在则按"总闸关闭"的安全默认写入一行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: AppSwitchRow): Long
}

/** 运行历史 DAO。 */
@Dao
interface RunDao {
    /**
     * 登记一次运行。[id] 为 `runId`，`IGNORE` 使重复登记幂等
     * （管道可能重复投递首批）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RunRow)

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun findById(runId: String): RunRow?

    @Query("SELECT * FROM runs ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RunRow>

    @Query("SELECT * FROM runs WHERE script_id = :scriptId ORDER BY started_at DESC LIMIT :limit")
    suspend fun recentForScript(
        scriptId: Long,
        limit: Int,
    ): List<RunRow>

    @Query(
        "UPDATE runs SET log_entry_count = :count, dropped_log_entries = :dropped WHERE id = :runId",
    )
    suspend fun updateCounters(
        runId: String,
        count: Int,
        dropped: Int,
    )

    @Query(
        "UPDATE runs SET finished_at = :finishedAt, exit_code = :exitCode, " +
            "log_entry_count = :count, dropped_log_entries = :dropped WHERE id = :runId",
    )
    suspend fun markFinished(
        runId: String,
        finishedAt: Long,
        exitCode: Int?,
        count: Int,
        dropped: Int,
    )

    /**
     * 保留策略：按 `started_at DESC` 保留最近 [keep] 次，删除其余。
     *
     * 日志行经 `ON DELETE CASCADE` 一并清除（见 [LogEntryRow] 的外键）。
     */
    @Query(
        "DELETE FROM runs WHERE id IN (" +
            "SELECT id FROM runs ORDER BY started_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun deleteBeyond(keep: Int)

    /**
     * 保留策略（阶段 6d，**按时间**）：删除 `started_at` 早于 [cutoffMillis] 的运行。
     *
     * ## 与 [deleteBeyond] 的关系：并存，不是替代
     * - [deleteBeyond]（按**条数**，`DEFAULT_RETAINED_RUNS = 30`）是**安全网**：
     *   即使保留天数很大（30 天），也不会因为脚本高频运行而把库撑爆
     * - 本方法（按**时间**）是**用户设置**的表达：用户在设置页选的"保留 N 天"
     * 两者语义不同、都要有；改这里**不要**顺手删掉 [deleteBeyond]。
     *
     * 日志行经 `ON DELETE CASCADE` 一并清除（见 [LogEntryRow] 的外键）。
     *
     * @return 实际删除的**运行条数**（Room 的 `DELETE` 支持返回受影响行数）
     */
    @Query("DELETE FROM runs WHERE started_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM runs")
    suspend fun count(): Int
}

/** 运行日志 DAO。 */
@Dao
interface RunLogDao {
    /**
     * 批量插入。`IGNORE` + 唯一索引 `(run_id, sequence)` 使重复写入幂等。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<LogEntryRow>)

    @Query(
        "SELECT * FROM run_log_entries WHERE run_id = :runId " +
            "ORDER BY sequence ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(
        runId: String,
        limit: Int,
        offset: Int,
    ): List<LogEntryRow>

    @Query("SELECT COUNT(*) FROM run_log_entries WHERE run_id = :runId")
    suspend fun count(runId: String): Int
}
