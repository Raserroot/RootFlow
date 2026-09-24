package com.rootflow.data.residue

import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.data.fs.RootFileResult
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.domain.residue.ResidueCleanResult
import com.rootflow.domain.residue.ResidueCleaner
import com.rootflow.domain.residue.ResidueReport
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.runtime.RootShellManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ResidueCleaner] 的实现（阶段 6d，需求 §8）。
 *
 * ## 目录列表为什么走 `rootShellManager` 而不是 `RootFileStore`
 * `RootFileStore` 只有"按已知路径读/写/删"的能力，**没有列目录**（阶段 3a 不需要）。
 * 加一个列目录方法会给那个类引入"输出解析"这类新职责；而这里只需要一次 `ls -1`，
 * 与 `RootFileStore` 用的是**同一个** `RootShellManager` 控制通道（`AGENTS.md`：
 * libsu 只在 `runtime/`，本类不认识 libsu ✅）。
 *
 * ## 删除为什么用 `RootFileStore.deleteScriptDirectory`
 * 那是"删一个脚本目录"的既有唯一实现（`rm -rf scripts/<id>`，含 `meta.json` 与将来的附加资源）。
 * 自己再拼一条 `rm -rf` 就是第二份真相，将来目录结构一变就会漏删。
 *
 * ## 命令形态（`AGENT_PROTOCOL.md §7.1`）
 * `if [ -d … ]; then ls -1 …; fi` —— **单条、无 `&&`/`|`/`>`**。
 * 目录不存在时退出码仍为 0、输出为空（"还没有 scripts 目录"不是错误）。
 */
@Singleton
class ResidueCleanerImpl
    @Inject
    constructor(
        private val rootShellManager: RootShellManager,
        private val rootFileStore: RootFileStore,
        private val scriptDao: ScriptDao,
        private val triggerRepository: TriggerRepository,
    ) : ResidueCleaner {
        override suspend fun scan(): ResidueScan {
            val knownIds =
                runCatching { scriptDao.all().map { it.id }.toSet() }
                    .getOrElse { error -> return ResidueScan.Unavailable("读取脚本表失败：${error.describe()}") }

            val dirIds =
                listScriptDirIds()
                    ?: return ResidueScan.Unavailable("无法列出 ${RootFlowPaths.SCRIPTS}（root 控制通道不可用或命令失败）")

            val triggers =
                runCatching { triggerRepository.all() }
                    .getOrElse { error -> return ResidueScan.Unavailable("读取触发器表失败：${error.describe()}") }

            return ResidueScan.Ok(
                ResidueReport(
                    orphanScriptDirs = dirIds.filter { it !in knownIds }.sorted(),
                    orphanTriggerRows = triggers.count { it.scriptId !in knownIds },
                ),
            )
        }

        override suspend fun clean(): ResidueCleanResult {
            val scan = scan()
            if (scan is ResidueScan.Unavailable) {
                // 扫描失败 ⇒ **一个都不删**：宁可留着残留，也不在"不知道哪些是孤儿"时动手
                return ResidueCleanResult(
                    removedScriptDirs = emptyList(),
                    removedTriggerRows = 0,
                    failures = listOf(scan.reason),
                )
            }
            val report = (scan as ResidueScan.Ok).report

            val removedDirs = mutableListOf<Long>()
            val failures = mutableListOf<String>()

            report.orphanScriptDirs.forEach { id ->
                when (val result = rootFileStore.deleteScriptDirectory(id)) {
                    is RootFileResult.Ok -> removedDirs += id
                    is RootFileResult.Unavailable -> failures += "目录 scripts/$id：${result.reason}"
                    is RootFileResult.Missing -> removedDirs += id // 已经不在了 = 目的达成
                    is RootFileResult.Corrupted -> failures += "目录 scripts/$id：${result.expected}"
                }
            }

            var removedRows = 0
            if (report.orphanTriggerRows > 0) {
                val knownIds = runCatching { scriptDao.all().map { it.id }.toSet() }.getOrElse { emptySet() }
                val orphans =
                    runCatching { triggerRepository.all() }
                        .getOrElse { error ->
                            failures += "触发器表读取失败：${error.describe()}"
                            emptyList()
                        }.filter { it.scriptId !in knownIds }
                orphans.forEach { trigger ->
                    when (val result = triggerRepository.delete(trigger.id)) {
                        is WriteResult.Ok -> removedRows++
                        is WriteResult.Failed -> failures += "触发器行 id=${trigger.id}：${result.reason}"
                    }
                }
            }

            return ResidueCleanResult(
                removedScriptDirs = removedDirs,
                removedTriggerRows = removedRows,
                failures = failures,
            )
        }

        /**
         * 列出 `scripts/` 下的**数字**目录名。
         *
         * @return 目录 id 列表；`null` = 命令失败（控制通道不可用 / `ls` 报错）
         */
        private suspend fun listScriptDirIds(): List<Long>? {
            val result =
                rootShellManager.execControl(
                    "if [ -d '${RootFlowPaths.SCRIPTS}' ]; then ls -1 '${RootFlowPaths.SCRIPTS}'; fi",
                )
            if (result.exitCode != 0) return null
            // 非数字条目不是本项目写的（见 ResidueReport 的 KDoc）：跳过，不认领也不删除
            return result.stdout
                .lineSequence()
                .mapNotNull { line -> line.trim().toLongOrNull() }
                .toList()
        }

        private fun Throwable.describe(): String = message ?: this::class.java.simpleName
    }
