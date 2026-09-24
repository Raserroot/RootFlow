package com.rootflow.data.run

import com.rootflow.data.db.dao.RunDao
import com.rootflow.data.db.dao.RunLogDao
import com.rootflow.data.db.mapper.toDomain
import com.rootflow.data.db.mapper.toSummary
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.repository.RunHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunHistoryRepository] 的实现（阶段 3a 只读 + 阶段 6d 增加保留策略清理）。
 *
 * ## 它解决什么
 * 阶段 2 遗留项：「`tail()` 只在运行进行中可用」——因为 `LogPipelineImpl` 的 worker
 * 结束即回收 state。**已结束运行的日志现在可从 Room 读取**，运行中的则继续走管道快照。
 * 二者互补，阶段 6 的 UI 按"运行是否结束"选择数据源。
 *
 * ## 阶段 6d 的两处新增（**只增不改**）
 * [deleteOlderThan] / [countAll] 服务于设置页「日志」组：用户选定的保留天数需要一个
 * 可立即执行的清理入口，以及一个能对照的条数。既有只读方法**语义一律未动**。
 */
@Singleton
class RunHistoryReader
    @Inject
    constructor(
        private val runDao: RunDao,
        private val runLogDao: RunLogDao,
    ) : RunHistoryRepository {
        override suspend fun recent(limit: Int): List<RunSummary> = runDao.recent(limit).map { it.toSummary() }

        override suspend fun recentForScript(
            scriptId: Long,
            limit: Int,
        ): List<RunSummary> = runDao.recentForScript(scriptId, limit).map { it.toSummary() }

        override suspend fun find(runId: String): RunSummary? = runDao.findById(runId)?.toSummary()

        override suspend fun readLogs(
            runId: String,
            limit: Int,
            offset: Int,
        ): List<LogEntry> = runLogDao.page(runId, limit, offset).map { it.toDomain() }

        override suspend fun countLogs(runId: String): Int = runLogDao.count(runId)

        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = runDao.deleteOlderThan(cutoffMillis)

        override suspend fun countAll(): Int = runDao.count()
    }
