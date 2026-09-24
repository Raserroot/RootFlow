package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.db.FakeDatabase
import com.rootflow.data.trigger.TriggerRepositoryImpl
import com.rootflow.domain.event.ScheduledAlarm
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.TriggerRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RoomTriggerScheduleProvider] 单测（阶段 3d）。
 *
 * 用 3a 的 [FakeDatabase]（内存 DAO）而不是 MockK 仓库：本类要做的是
 * "从仓库取两类事件 → 翻译成 `ScheduledAlarm` → params 不足的逐条可见"，
 * 用真实仓库实现能顺带覆盖 `params` 的 JSON 解码链路（那是 `TriggerParamsCodec` 的产物）。
 */
class RoomTriggerScheduleProviderTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun `both alarm event types are merged into one list`() =
        runTest {
            val repo =
                repositoryWith(
                    trigger(1L, 10L, SystemEvent.TIME, TriggerParams(hourOfDay = 7, minuteOfHour = 30)),
                    trigger(2L, 20L, SystemEvent.INTERVAL, TriggerParams(intervalMinutes = 15)),
                )

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertEquals(2, alarms.size)
            assertTrue(alarms.any { it is ScheduledAlarm.Time && it.scriptId == 10L })
            assertTrue(alarms.any { it is ScheduledAlarm.Interval && it.scriptId == 20L })
        }

    @Test
    fun `non alarm event types are ignored`() =
        runTest {
            val repo =
                repositoryWith(
                    trigger(1L, 10L, SystemEvent.BOOT, TriggerParams()),
                    trigger(2L, 11L, SystemEvent.SCREEN_OFF, TriggerParams()),
                    trigger(3L, 12L, SystemEvent.TIME, TriggerParams(hourOfDay = 1, minuteOfHour = 2)),
                )

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertEquals(1, alarms.size, "只有 time/interval 属于闹钟域：$alarms")
            assertEquals(12L, alarms.single().scriptId)
        }

    @Test
    fun `a time trigger without hour and minute is reported and skipped`() =
        runTest {
            // params 缺字段 → ScheduledAlarm.from 返回 null。这类触发器**永远不会触发**，
            // 属最难排查的静默失败，因此必须逐条记日志（3d 方案 §3）。
            val repo = repositoryWith(trigger(1L, 10L, SystemEvent.TIME, TriggerParams()))

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertTrue(alarms.isEmpty())
            verify { Log.w(any(), match<String> { it.contains("ALARM_SKIPPED_INVALID") && it.contains("trigger=1") }) }
        }

    @Test
    fun `an interval trigger without an interval is reported and skipped`() =
        runTest {
            val repo = repositoryWith(trigger(5L, 10L, SystemEvent.INTERVAL, TriggerParams()))

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertTrue(alarms.isEmpty())
            verify { Log.w(any(), match<String> { it.contains("ALARM_SKIPPED_INVALID") && it.contains("trigger=5") }) }
        }

    @Test
    fun `disabled triggers are not returned`() =
        runTest {
            val repo =
                repositoryWith(
                    // 前置：这一条**被停用**（`enabled = false` = 停用但保留配置）。
                    // 旧版本漏了它 —— 两条都启用，却断言"只返回一条"，必然失败：
                    // 缺的是前置，不是要放宽的断言。
                    trigger(
                        1L,
                        10L,
                        SystemEvent.TIME,
                        TriggerParams(hourOfDay = 7, minuteOfHour = 0),
                        enabled = false,
                    ),
                    trigger(2L, 20L, SystemEvent.TIME, TriggerParams(hourOfDay = 8, minuteOfHour = 0)),
                )

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertEquals(listOf(20L), alarms.map { it.scriptId }, "只接受启用中的触发器")
        }

    @Test
    fun `a corrupted params row falls back to defaults and is reported as invalid`() =
        runTest {
            // 损坏 JSON 由 TriggerRepositoryImpl 回退为默认值（3a 语义）→ 这里应报 invalid
            val db = FakeDatabase()
            val repo = TriggerRepositoryImpl(db.scriptEventDao)
            db.scriptEventDao.insert(
                com.rootflow.data.db.entity.ScriptEventRow(
                    scriptId = 9L,
                    eventType = SystemEvent.TIME,
                    params = "{ broken",
                    enabled = true,
                    createdAt = 1L,
                ),
            )

            val alarms = RoomTriggerScheduleProvider(repo).currentAlarms()

            assertTrue(alarms.isEmpty(), "损坏的 params 不得被当成有效闹钟")
        }

    @Test
    fun `an empty trigger table yields an empty plan`() =
        runTest {
            val repo = TriggerRepositoryImpl(FakeDatabase().scriptEventDao)

            assertTrue(RoomTriggerScheduleProvider(repo).currentAlarms().isEmpty())
        }

    // ---------------------------------------------------------------- 工具

    private fun trigger(
        id: Long,
        scriptId: Long,
        eventType: String,
        params: TriggerParams,
        enabled: Boolean = true,
    ): Trigger =
        Trigger(
            id = id,
            scriptId = scriptId,
            eventType = eventType,
            params = params,
            enabled = enabled,
            createdAt = 1L,
        )

    private suspend fun repositoryWith(vararg triggers: Trigger): TriggerRepository {
        val db = FakeDatabase()
        val repo = TriggerRepositoryImpl(db.scriptEventDao)
        // 总开关重构后订阅是**集合替换**（先清后插），因此按脚本分组写入
        triggers.groupBy { it.scriptId }.forEach { (scriptId, rows) ->
            repo.replaceForScript(scriptId, rows.toList())
        }
        return repo
    }
}
