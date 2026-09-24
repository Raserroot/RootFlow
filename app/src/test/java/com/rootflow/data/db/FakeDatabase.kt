package com.rootflow.data.db

import com.rootflow.data.db.dao.AppSwitchDao
import com.rootflow.data.db.dao.RunDao
import com.rootflow.data.db.dao.RunLogDao
import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.data.db.dao.ScriptEventDao
import com.rootflow.data.db.entity.AppSwitchRow
import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 DAO 假件（阶段 3a 测试用）。
 *
 * ## 为什么用手写假件而不是 Room 实例
 * 本项目**不引入 `androidTest`**（决策 6），而 Room 的 JVM 实例化需要 Robolectric——
 * Robolectric 的 JUnit5 支持需额外依赖，且其 jar 在离线缓存中不完整。
 * 因此本阶段用"手写内存实现 + MockK"覆盖仓库逻辑，Room 的 SQL 正确性由
 * `RootFlowSchemaTest` 的导出 schema 与实体反射比对来保证。
 */
internal class FakeDatabase {
    val scripts = linkedMapOf<Long, ScriptRow>()
    val scriptEvents = linkedMapOf<Long, ScriptEventRow>()
    val appSwitch = linkedMapOf<Long, AppSwitchRow>()
    val runs = linkedMapOf<String, RunRow>()
    val logEntries = mutableListOf<LogEntryRow>()

    private var scriptSeq = 1L
    private var triggerSeq = 1L
    private var logSeq = 1L

    val scriptDao: ScriptDao =
        object : ScriptDao {
            override suspend fun insert(row: ScriptRow): Long {
                val id = if (row.id == 0L) scriptSeq++ else row.id
                scripts[id] = row.copy(id = id)
                return id
            }

            override suspend fun update(row: ScriptRow) {
                scripts[row.id] = row
            }

            override suspend fun deleteById(id: Long) {
                scripts.remove(id)
                // 模拟 ON DELETE CASCADE
                scriptEvents.entries.removeIf { it.value.scriptId == id }
            }

            override suspend fun findById(id: Long): ScriptRow? = scripts[id]

            override suspend fun findByName(name: String): ScriptRow? = scripts.values.firstOrNull { it.name == name }

            override fun observeAll(): Flow<List<ScriptRow>> = MutableStateFlow(scripts.values.toList())

            override suspend fun all(): List<ScriptRow> = scripts.values.toList()

            override suspend fun enabled(): List<ScriptRow> = scripts.values.filter { it.enabled }
        }

    val scriptEventDao: ScriptEventDao =
        object : ScriptEventDao {
            override suspend fun insert(row: ScriptEventRow): Long {
                val id = if (row.id == 0L) triggerSeq++ else row.id
                scriptEvents[id] = row.copy(id = id)
                return id
            }

            override suspend fun deleteById(id: Long) {
                scriptEvents.remove(id)
            }

            override fun observeAll(): Flow<List<ScriptEventRow>> = MutableStateFlow(scriptEvents.values.toList())

            override suspend fun all(): List<ScriptEventRow> = scriptEvents.values.toList()

            /**
             * 与真实 SQL **逐字对齐**：`WHERE event_type = ? AND enabled = 1 ORDER BY id`。
             *
             * `AND enabled = 1` 不是可省的条件：`enabled = 0` = **停用但保留配置**，
             * 那些行不该被投递。夹具漏掉它会让"禁用行不投递"这条不变量在单测里**假绿**。
             */
            override suspend fun forEvent(eventType: String): List<ScriptEventRow> =
                scriptEvents.values
                    .filter { it.eventType == eventType && it.enabled }
                    .sortedBy { it.id }

            override suspend fun forScript(scriptId: Long): List<ScriptEventRow> =
                scriptEvents.values.filter { it.scriptId == scriptId }

            override suspend fun countForScript(scriptId: Long): Int =
                scriptEvents.values.count { it.scriptId == scriptId }

            override suspend fun deleteForScript(scriptId: Long) {
                scriptEvents.entries.removeIf { it.value.scriptId == scriptId }
            }
        }

    val appSwitchDao: AppSwitchDao =
        object : AppSwitchDao {
            override fun observe(id: Long): Flow<AppSwitchRow?> = MutableStateFlow(appSwitch[id])

            override suspend fun get(id: Long): AppSwitchRow? = appSwitch[id]

            override suspend fun upsert(row: AppSwitchRow) {
                appSwitch[row.id] = row
            }

            override suspend fun insertIfAbsent(row: AppSwitchRow): Long {
                if (appSwitch.containsKey(row.id)) return -1L
                appSwitch[row.id] = row
                return row.id
            }
        }

    val runDao: RunDao =
        object : RunDao {
            override suspend fun insert(row: RunRow) {
                runs.putIfAbsent(row.id, row)
            }

            override suspend fun findById(runId: String): RunRow? = runs[runId]

            override suspend fun recent(limit: Int): List<RunRow> =
                runs.values.sortedByDescending { it.startedAt }.take(limit)

            override suspend fun recentForScript(
                scriptId: Long,
                limit: Int,
            ): List<RunRow> =
                runs.values
                    .filter { it.scriptId == scriptId }
                    .sortedByDescending { it.startedAt }
                    .take(limit)

            override suspend fun updateCounters(
                runId: String,
                count: Int,
                dropped: Int,
            ) {
                runs[runId]?.let { runs[runId] = it.copy(logEntryCount = count, droppedLogEntries = dropped) }
            }

            override suspend fun markFinished(
                runId: String,
                finishedAt: Long,
                exitCode: Int?,
                count: Int,
                dropped: Int,
            ) {
                runs[runId]?.let {
                    runs[runId] =
                        it.copy(
                            finishedAt = finishedAt,
                            exitCode = exitCode,
                            logEntryCount = count,
                            droppedLogEntries = dropped,
                        )
                }
            }

            override suspend fun deleteBeyond(keep: Int) {
                val ordered = runs.values.sortedByDescending { it.startedAt }
                ordered.drop(keep).forEach { victim ->
                    runs.remove(victim.id)
                    // 模拟 ON DELETE CASCADE
                    logEntries.removeIf { it.runId == victim.id }
                }
            }

            /**
             * 阶段 6d 的「按时间清理」（6e 编译时才暴露出本假件缺这一条）。
             *
             * 语义与真实 SQL 逐字对齐：`DELETE FROM runs WHERE started_at < :cutoff`，
             * 日志行经 `ON DELETE CASCADE` 一并删。
             */
            override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
                val victims = runs.values.filter { it.startedAt < cutoffMillis }
                victims.forEach { victim ->
                    runs.remove(victim.id)
                    // 模拟 ON DELETE CASCADE
                    logEntries.removeIf { it.runId == victim.id }
                }
                return victims.size
            }

            override suspend fun count(): Int = runs.size
        }

    val runLogDao: RunLogDao =
        object : RunLogDao {
            override suspend fun insertAll(rows: List<LogEntryRow>) {
                rows.forEach { row ->
                    val duplicate = logEntries.any { it.runId == row.runId && it.sequence == row.sequence }
                    if (!duplicate) {
                        logEntries += row.copy(id = logSeq++)
                    }
                }
            }

            override suspend fun page(
                runId: String,
                limit: Int,
                offset: Int,
            ): List<LogEntryRow> =
                logEntries
                    .filter { it.runId == runId }
                    .sortedBy { it.sequence }
                    .drop(offset)
                    .take(limit)

            override suspend fun count(runId: String): Int = logEntries.count { it.runId == runId }
        }

    /** 供断言用的便捷查询。 */
    fun entriesOf(runId: String): List<LogEntryRow> = logEntries.filter { it.runId == runId }
}
