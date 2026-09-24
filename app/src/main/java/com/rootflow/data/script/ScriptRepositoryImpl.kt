package com.rootflow.data.script

import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.data.db.mapper.toDomainWithContent
import com.rootflow.data.db.mapper.toDomainWithoutContent
import com.rootflow.data.db.mapper.toRow
import com.rootflow.data.fs.RootFileResult
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.model.Script
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScriptRepository] 实现（阶段 3a）。
 * ## 两处存储的一致性策略
 * | 场景 | 顺序 | 失败时 |
 * |---|---|---|
 * | 新建 | 插行（占位路径）→ 写正文 → 回填路径与摘要 | **删行**，不留悬挂元数据 |
 * | 更新 | 写正文 → 更新元数据 | 保留旧元数据不动（文件已覆写，但摘要会暴露不一致） |
 * | 删除 | 删行（级联删触发器）→ 删正文目录 | 行已删即视为成功；文件失败仅告警 |
 *
 * ## 为什么"新建"要先插行
 * 正文路径含 `id`（`scripts/<id>/main.sh`），而 `id` 由 Room 自增分配。
 * 因此必须两步：先拿到 `id`，再据此写文件。
 *
 * ## 为什么 list 不读正文
 * [observeAll] 只返回元数据、`content` 置空——列表页刷新频繁，每次读 N 个文件既慢又无必要。
 * 需要正文时走 [load]。
 */
@Singleton
class ScriptRepositoryImpl
    @Inject
    constructor(
        private val scriptDao: ScriptDao,
        private val fileStore: RootFileStore,
        private val clock: () -> Long = System::currentTimeMillis,
    ) : ScriptRepository {
        override fun observeAll(): Flow<List<Script>> =
            scriptDao.observeAll().map { rows -> rows.map { it.toDomainWithoutContent() } }

        override suspend fun load(id: Long): ScriptLoadResult {
            val row = scriptDao.findById(id) ?: return ScriptLoadResult.NotFound
            val path = RootFlowPaths.ROOT + "/" + row.contentPath
            return when (val file = fileStore.readText(path, row.contentSha256)) {
                is RootFileResult.Ok ->
                    ScriptLoadResult.Ok(row.toDomainWithContent(file.value))

                is RootFileResult.Missing -> ScriptLoadResult.Missing

                is RootFileResult.Corrupted ->
                    ScriptLoadResult.Corrupted(expected = file.expected, actual = file.actual)

                is RootFileResult.Unavailable -> ScriptLoadResult.Unavailable(file.reason)
            }
        }

        override suspend fun save(script: Script): WriteResult<Script> =
            if (script.id == 0L) {
                insertNew(script)
            } else {
                updateExisting(script)
            }

        override suspend fun delete(id: Long): WriteResult<Unit> {
            val row = scriptDao.findById(id)
            scriptDao.deleteById(id)
            val fileResult = fileStore.deleteScriptDirectory(id)
            return when {
                row == null && fileResult is RootFileResult.Unavailable ->
                    WriteResult.Failed("row absent and directory removal failed: ${fileResult.reason}")

                fileResult is RootFileResult.Unavailable ->
                    WriteResult.Failed("script row deleted, but directory removal failed: ${fileResult.reason}")

                else -> WriteResult.Ok(Unit)
            }
        }

        // ------------------------------------------------------------------ 内部

        private suspend fun insertNew(script: Script): WriteResult<Script> {
            val now = clock()
            val extension = RootFlowPaths.extensionFor(script.language)
            // 先用占位路径插行以取得自增 id；随后回填真实路径与摘要。
            val id =
                scriptDao.insert(
                    script
                        .copy(id = 0L, createdAt = now, updatedAt = now)
                        .toRow(contentPath = PLACEHOLDER_PATH, contentSha256 = null),
                )

            val relative = RootFlowPaths.scriptBodyRelative(id, extension)
            val absolute = RootFlowPaths.ROOT + "/" + relative
            return when (val written = fileStore.writeText(absolute, script.content)) {
                is RootFileResult.Ok -> {
                    val row =
                        scriptDao.findById(id)
                            ?: return WriteResult.Failed("row $id vanished right after insert")
                    val updated = row.copy(contentPath = relative, contentSha256 = written.value, updatedAt = now)
                    scriptDao.update(updated)
                    return WriteResult.Ok(updated.toDomainWithContent(script.content))
                }

                else -> {
                    // 回滚：正文没写成，就不能留下指向不存在文件的元数据。
                    scriptDao.deleteById(id)
                    return WriteResult.Failed("body write failed: ${describe(written)}")
                }
            }
        }

        private suspend fun updateExisting(script: Script): WriteResult<Script> {
            val existing =
                scriptDao.findById(script.id)
                    ?: return WriteResult.Failed("script ${script.id} not found")

            val absolute = RootFlowPaths.ROOT + "/" + existing.contentPath
            return when (val written = fileStore.writeText(absolute, script.content)) {
                is RootFileResult.Ok -> {
                    // ★ 以**新值**为主体做整行映射，只把"只能来自库"的字段显式覆盖回来。
                    //
                    // ## 为什么不能反过来写（`existing.copy(字段 = script.字段, …)`）
                    // 那是**字段白名单**：每新增一个可编辑字段都必须记得补一行，
                    // 漏掉的表现是「用户在 UI 上改了 → 保存成功 → 库里没变」，**零报错**。
                    //
                    // 这不是假设：`resident`（总开关重构新增的字段）就被这条路径静默丢过 ——
                    // P4 真机验证抓到：编辑页拨到「常驻」、保存返回列表，行上仍显示「单次」。
                    // 白名单的默认是"丢弃"，而正确的默认是"**跟随**"。
                    val updated =
                        script
                            .toRow(
                                // contentPath 由数据库分配（`scripts/<id>/main.sh`），
                                // domain 的 `Script` 根本没有这个字段 ⇒ 必须以库里的为准
                                contentPath = existing.contentPath,
                                contentSha256 = written.value,
                            ).copy(
                                // 创建时间不该被"更新"改掉（调用方若传 0 会把档案抹掉）
                                createdAt = existing.createdAt,
                                updatedAt = clock(),
                            )
                    scriptDao.update(updated)
                    WriteResult.Ok(updated.toDomainWithContent(script.content))
                }

                else -> WriteResult.Failed("body write failed: ${describe(written)}")
            }
        }

        private fun describe(result: RootFileResult<*>): String =
            when (result) {
                is RootFileResult.Ok -> "ok"
                RootFileResult.Missing -> "missing"
                is RootFileResult.Corrupted -> "corrupted(expected=${result.expected}, actual=${result.actual})"
                is RootFileResult.Unavailable -> result.reason
            }

        private companion object {
            /** 插行时的占位路径；写完正文立即回填。 */
            const val PLACEHOLDER_PATH = "scripts/pending/main.sh"
        }
    }
