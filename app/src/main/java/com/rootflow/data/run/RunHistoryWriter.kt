package com.rootflow.data.run

import com.rootflow.data.db.dao.RunDao
import com.rootflow.data.db.dao.RunLogDao
import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.mapper.toRow
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.repository.RunMeta
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次运行的历史批次（`domain.model.LogBatch` + [RunMeta] 的组装体）。
 *
 * 之所以不直接接收 `domain.model.LogBatch`：那个类型没有、也不该有 `scriptId`
 * （它是纯日志管道的产物）。本类在写入侧把两者结合。
 */
data class RunHistoryBatch(
    val runId: String,
    val meta: RunMeta,
    val entries: List<LogEntry>,
    val droppedEntries: Long,
    val runFinished: Boolean,
)

/**
 * 运行历史落库（阶段 3a）。
 *
 * ## 为什么单列一个类而不是塞进管道
 * 管道（`data/log/LogPipelineImpl`）的职责是"有界、不阻塞、批量推送"，它**不认识脚本与 Room**。
 * 持久化是另一个关切：它需要 `scriptId`、需要处理行数上限与保留策略。二者通过本类衔接，
 * 保持管道可独立测试（阶段 2 的 20 个用例即建立在此之上）。
 *
 * ## 硬约束：写库绝不反压脚本执行
 * 调用方（`RunHistoryCollector`）用**有界队列 + 溢出丢弃 + 计数**衔接，
 * 与管道同款"丢弃可见"原则。本类自身的每个方法都只做一次批量 DB 操作，不阻塞等待。
 *
 * ## 行数上限（决策 3）
 * 单 run 最多落 [maxEntriesPerRun] 行（默认 10_000）。超出部分**丢弃并计数**，
 * 记入 `runs.dropped_log_entries` —— **不静默丢数据**。
 */
@Singleton
class RunHistoryWriter
    @Inject
    constructor(
        private val runDao: RunDao,
        private val runLogDao: RunLogDao,
        private val maxEntriesPerRun: Int = DEFAULT_MAX_ENTRIES_PER_RUN,
        private val retainedRuns: Int = DEFAULT_RETAINED_RUNS,
        private val clock: () -> Long = System::currentTimeMillis,
        private val onWarning: (String) -> Unit = {},
    ) {
        /** 已登记的 runId（避免重复 `INSERT`；`RunDao.insert` 本身也是 IGNORE 幂等）。 */
        private val registered = mutableSetOf<String>()

        /** 已落库的条目数（用于行数上限判断）。 */
        private val written = mutableMapOf<String, Int>()

        /** 已丢弃的条目数（上限截断部分），随 `markFinished` 落库。 */
        private val dropped = mutableMapOf<String, Int>()

        /** 已提取到的退出码（见 [noteExitCode]）。 */
        private val exitCodes = mutableMapOf<String, Int>()

        /** 已收尾的 runId（让 [finish] 幂等，见其 KDoc）。 */
        private val finishedRuns = mutableSetOf<String>()

        /**
         * 写入一批。
         *
         * 首次见到 [RunHistoryBatch.runId] 时会顺带登记 `runs` 行。
         */
        suspend fun record(batch: RunHistoryBatch) {
            ensureRegistered(batch)

            // 管道自身丢的批（订阅者通道溢出）与"单 run 行数上限截断"是**两种不同的丢失**，
            // 但都要让用户看见，因此都累计进 `runs.dropped_log_entries`：
            // - 管道级：单独告警（排障时要能区分"丢在管道里"还是"丢在上限上"）
            // - 上限级：只计数（它本来就是设计内的截断）
            if (batch.droppedEntries > 0) {
                onWarning(
                    "run ${batch.runId}: pipeline dropped ${batch.droppedEntries} entries " +
                        "(subscriber channel overflow) — history will be incomplete",
                )
                addDropped(batch.runId, batch.droppedEntries)
            }

            val alreadyWritten = written[batch.runId] ?: 0
            val remaining = maxEntriesPerRun - alreadyWritten
            if (remaining <= 0) {
                // 已达上限：全批计入丢弃。**再收尾**——否则"末批恰好超限"的运行
                // 永远不会落 `finished_at`（3d 实测发现的缺口）。
                addDropped(batch.runId, batch.entries.size.toLong())
                if (batch.runFinished) finish(batch.runId)
                return
            }

            val accepted = if (batch.entries.size <= remaining) batch.entries else batch.entries.take(remaining)
            val rejected = batch.entries.size - accepted.size
            if (rejected > 0) {
                addDropped(batch.runId, rejected.toLong())
            }

            if (accepted.isNotEmpty()) {
                runLogDao.insertAll(accepted.map { it.toRow() })
                written[batch.runId] = alreadyWritten + accepted.size
            }

            noteExitCode(batch)

            // 顺序是硬要求（3d 实测发现）：**先把所有丢弃计数累计完，再收尾**。
            // `finish` 会把 `dropped[runId]` 写进 `runs.dropped_log_entries`；
            // 若先收尾再累计，末批携带的丢弃数会**正好漏掉**，而该值此后不再更新
            // （`finish` 幂等、后续不再调用）——"丢弃数静默为 0"正是本仓库反复禁止的静默失败。
            if (batch.runFinished) {
                finish(batch.runId)
            } else {
                runDao.updateCounters(batch.runId, written[batch.runId] ?: 0, dropped[batch.runId] ?: 0)
            }
        }

        /** 累计丢弃条数（内部计数，单位从 `Long` 收敛到结构体要求的 `Int`）。 */
        private fun addDropped(
            runId: String,
            count: Long,
        ) {
            dropped[runId] = ((dropped[runId] ?: 0) + count).toInt()
        }

        /**
         * 收尾一次运行：落 `finishedAt` / 退出码 / 计数，并执行保留策略。
         *
         * 幂等：重复调用**直接返回**（第一次已写好的 `finishedAt` 不会被后一次覆写）。
         *
         * ## 为什么是 public（阶段 3d）
         * `RunHistoryCollector` 通过 `LogBatchSink.onRunFinished` 收到的**是一个与批次
         * 独立的收尾信号**——取消路径上"`runFinished = true` 的收尾批"可能根本发不出去。
         * 若收尾只能经 [record] 携带，`runs.finished_at` 会永远为 null（阶段 3a 遗留 #2）。
         *
         * 因此正常路径上收尾会被请求两次（末批的 `runFinished=true` + 独立收尾信号），
         * [finishedRuns] 让第二次成为无副作用的空操作。
         */
        suspend fun finish(runId: String) {
            if (!finishedRuns.add(runId)) return
            val code = exitCodes[runId]
            if (code == null) {
                // 运行已结束却没有可提取的退出码：这是 D6 耦合点失效的信号（runtime 改了文案？），
                // 必须告警而非静默写 null。
                onWarning(
                    "run $runId finished without an extractable exit code " +
                        "(runtime SYS wording may have changed — see PROJECT_STATE D6)",
                )
            }
            runDao.markFinished(
                runId = runId,
                finishedAt = clock(),
                exitCode = code,
                count = written[runId] ?: 0,
                dropped = dropped[runId] ?: 0,
            )
            runDao.deleteBeyond(retainedRuns)
        }

        private suspend fun ensureRegistered(batch: RunHistoryBatch) {
            if (registered.contains(batch.runId)) return
            runDao.insert(
                RunRow(
                    id = batch.runId,
                    scriptId = batch.meta.scriptId,
                    triggerEvent = batch.meta.triggerEvent,
                    startedAt = batch.entries.firstOrNull()?.timestamp ?: clock(),
                ),
            )
            registered += batch.runId
        }

        /**
         * 从 SYS 行提取退出码。
         *
         * **跨模块耦合点**（见 `PROJECT_STATE.md` 偏离项 **D6**）：`LogBatch` 不携带退出码，
         * 退出码只存在于 runtime 发的 SYS 行 `script <id> exited with code <n>` 里。
         * 提取失败**不静默**——[onWarning] 会收到告警。
         */
        private fun noteExitCode(batch: RunHistoryBatch) {
            if (exitCodes.containsKey(batch.runId)) return
            batch.entries
                .filter { it.stream == LogStream.SYS }
                .firstNotNullOfOrNull { ExitCodeExtractor.extract(it.text) }
                ?.let { exitCodes[batch.runId] = it }
        }

        companion object {
            /** 单 run 最大落库行数（决策 3）。 */
            const val DEFAULT_MAX_ENTRIES_PER_RUN: Int = 10_000

            /** 保留最近多少次运行（决策 3）。 */
            const val DEFAULT_RETAINED_RUNS: Int = 30
        }
    }

/**
 * 从 runtime 的 SYS 日志行提取退出码（`PROJECT_STATE.md` 偏离项 D6 的实现）。
 *
 * runtime 的文案为 `script <id> exited with code <n>`；本提取器**特意宽松**：
 * 只要求行尾出现 `exited with code` 后跟一个整数，不绑定具体的 script id 形式。
 * 这样即使将来 id 展示方式变化，提取仍然有效；而文案整体改写时
 * [RunHistoryWriter] 会通过告警暴露出来。
 */
internal object ExitCodeExtractor {
    private val pattern = Regex("""exited with code\s+(-?\d+)\s*$""")

    /** @return 退出码；无法识别时 `null` */
    fun extract(text: String): Int? =
        pattern
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}

/** 供测试与将来扩展使用：把 `LogEntryRow` 列表转回 domain（当前由 reader 使用）。 */
internal fun List<LogEntryRow>.toDomainEntries(): List<LogEntry> =
    map { row ->
        LogEntry(
            runId = row.runId,
            sequence = row.sequence,
            timestamp = row.timestamp,
            stream = runCatching { LogStream.valueOf(row.stream) }.getOrDefault(LogStream.SYS),
            text = row.text,
        )
    }
