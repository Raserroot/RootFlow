package com.rootflow.data.trigger

import com.rootflow.data.db.dao.ScriptEventDao
import com.rootflow.data.db.mapper.toDomain
import com.rootflow.data.db.mapper.toRow
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TriggerRepository] 实现（阶段 3a；**总开关重构**后语义已变）。
 *
 * ## 与旧实现的差异
 * - `enabledForEvent` → [forEvent]：**查询仍带 `AND enabled = 1`**
 *   （`enabled = false` = **停用但保留配置**，见 [Trigger] 的三态 KDoc）。
 *   改名是为了让"事件 → 订阅"这条查询的语义在**名字**里可见
 * - `save(trigger)` → [replaceForScript]：编辑器给的是"订阅集合"，
 *   一次覆盖式写入即可保证"库里 == 界面看到的"，不让调用方自己算差集
 * - 删除 `@Update` 路径：订阅行不再被原地修改（要改就是整份集合重写）
 *
 * ## JSON 解码的失败可见性
 * `params` 是 JSON 字符串，解码失败**不抛异常**（一条坏记录不该让调度器停摆），
 * 但必须可见 —— 因此构造时可注入 [onParamsWarning]。生产路径由 `data/di/` 装配为
 * Android 日志；单测注入收集器以断言"确实告警了"。
 */
@Singleton
class TriggerRepositoryImpl
    @Inject
    constructor(
        private val scriptEventDao: ScriptEventDao,
        private val onParamsWarning: (String) -> Unit = {},
        private val clock: () -> Long = System::currentTimeMillis,
    ) : TriggerRepository {
        override fun observeForScript(scriptId: Long): Flow<List<Trigger>> =
            scriptEventDao.observeAll().map { rows ->
                rows.filter { it.scriptId == scriptId }.map { it.toDomain(decodeParams(it.params)) }
            }

        override fun observeAll(): Flow<List<Trigger>> =
            scriptEventDao.observeAll().map { rows -> rows.map { it.toDomain(decodeParams(it.params)) } }

        override suspend fun all(): List<Trigger> = scriptEventDao.all().map { it.toDomain(decodeParams(it.params)) }

        override suspend fun forEvent(eventType: String): List<Trigger> =
            scriptEventDao.forEvent(eventType).map { it.toDomain(decodeParams(it.params)) }

        /**
         * 覆盖式写入：把该脚本的订阅集合**整体**替换为 [triggers]。
         *
         * ## ★ `scriptId` 参数**必须参与插入**（这不是可省的形式参数）
         * 调用方（[ScriptEditorViewModel]）从 `triggers.all()` 拿到全库订阅、
         * 只改自己那一部分，因此传进来的 [Trigger.scriptId] 可能是**别的脚本**的 id。
         * 若不覆写，`toRow` 会原样落库 ⇒ **别的脚本的订阅被搬到当前脚本名下**
         * （表现为"改 A 脚本，B 脚本的订阅消失了"）。
         *
         * 实现对此的保证是：**清空 + 插入都只用 [scriptId]**，
         * 传进来的 [Trigger.scriptId] 一律被覆写为 [scriptId]。
         */
        override suspend fun replaceForScript(
            scriptId: Long,
            triggers: List<Trigger>,
        ): WriteResult<List<Trigger>> =
            runCatching {
                scriptEventDao.deleteForScript(scriptId)
                val now = clock()
                val saved =
                    triggers.map { trigger ->
                        val row =
                            trigger
                                .copy(
                                    // ★ 强制归属当前脚本（见 KDoc）
                                    scriptId = scriptId,
                                    createdAt = if (trigger.createdAt == 0L) now else trigger.createdAt,
                                ).toRow(TriggerParamsCodec.encode(trigger.params))
                        trigger.copy(scriptId = scriptId, id = scriptEventDao.insert(row))
                    }
                WriteResult.Ok(saved)
            }.getOrElse { error ->
                WriteResult.Failed(
                    "replaceForScript failed (script=$scriptId, " +
                        "requested=${triggers.size}): ${error.message ?: error::class.java.name}",
                )
            }

        override suspend fun delete(id: Long): WriteResult<Unit> =
            runCatching {
                scriptEventDao.deleteById(id)
                WriteResult.Ok(Unit)
            }.getOrElse { error ->
                WriteResult.Failed("trigger delete failed: ${error.message ?: error::class.java.name}")
            }

        override suspend fun countForScript(scriptId: Long): Int = scriptEventDao.countForScript(scriptId)

        private fun decodeParams(raw: String) = TriggerParamsCodec.decode(raw, onWarning = onParamsWarning)
    }
