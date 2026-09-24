package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.ScheduledAlarm
import com.rootflow.service.receiver.AlarmActions
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [AlarmSyncCoordinator] 单测（阶段 3d；2026-09-19 真机根因修正后新增）。
 *
 * ## 本类存在的唯一理由：钉死"`cancelAll` 每进程只做一次"
 *
 * ### 真机缺陷（`dumpsys alarm` 墓碑为证）
 * 原实现**每次** `runSync` 都 `cancelAll` 再重建，而它挂着 `after-dispatch` 钩子
 * ⇒ 每次事件分发都"清空全部闹钟 → 重新 set"；而 `setInexactRepeating` 的
 * `when = now + interval` ⇒ **每次 set 都把倒计时归零** ⇒ 60 秒的 `interval` 闹钟
 * **永远在将要触发前被自己清掉**（墓碑里 `requester=-15m29s` = 取消时已过期 15 分钟）。
 * 现象是"注册成功、App 常驻、永不触发"，极易误判为 receiver / PendingIntent 故障。
 *
 * 因此下面两条断言是这个缺陷的**唯一自动化护栏**，**不得删减**：
 * 1. [a repeated sync does not clear the alarms again]：第二次对账**不得**再 `cancelAll`
 * 2. [the first sync clears both action domains]：首次对账仍须逐域清一次（D-3d-1 的语义不能丢）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSyncCoordinatorTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 固定"现在"，让 `time` 闹钟的下次触发时刻可精确写出。 */
    private val now = utc(2026, 9, 19, 6, 0)

    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun `the first sync clears both action domains`() =
        runTest {
            val handle = FakeAlarmHandle()
            val coordinator = coordinator(handle = handle)

            coordinator.start()
            advanceUntilIdle()

            assertEquals(
                AlarmActions.ALL,
                handle.calls.filter { it.op == "cancelAll" }.map { it.action },
                "首次对账必须逐域清一次（决策 D-3d-1 的跨进程对账起点）",
            )
            assertTrue(coordinator.hasClearedThisProcess)
        }

    @Test
    fun `a repeated sync does not clear the alarms again`() =
        runTest {
            // 真机缺陷场景：boot 广播分发 → after-dispatch 重同步 ⇒ 第二次 runSync
            val handle = FakeAlarmHandle()
            val coordinator = coordinator(handle = handle)

            coordinator.start()
            advanceUntilIdle()
            val afterFirst = handle.count("cancelAll")

            coordinator.requestSync(reason = "after-dispatch")
            advanceUntilIdle()

            assertEquals(
                afterFirst,
                handle.count("cancelAll"),
                "第二次对账**不得**再 cancelAll：那会把正在倒计时的 interval 闹钟清掉，" +
                    "使其永远等不到触发（真机根因，勿改回）",
            )
            assertEquals(2, coordinator.completedSyncs, "两次对账都应真正完成（只是不再清空）")
        }

    @Test
    fun `repeated syncs stay idempotent for an unchanged alarm set`() =
        runTest {
            val handle = FakeAlarmHandle()
            val coordinator = coordinator(handle = handle)
            val alarms = listOf(intervalAlarm(triggerId = 1, scriptId = 100, intervalMinutes = 1))
            val provider = FakeScheduleProvider(alarms)
            val source = AlarmEventSource(handle, clock = { now }, calendarFactory = { Calendar.getInstance(utc) })
            val coordinatorWithAlarms = coordinator(handle = handle, provider = provider, source = source)

            coordinatorWithAlarms.start()
            advanceUntilIdle()
            val setsAfterFirst = handle.count("setRepeating")
            handle.calls.clear()

            repeat(3) { coordinatorWithAlarms.requestSync(reason = "after-dispatch-$it") }
            advanceUntilIdle()

            assertEquals(1, setsAfterFirst, "首次应下发一次 interval 闹钟")
            assertEquals(
                0,
                handle.count("setRepeating"),
                "集合未变时**不得**重新 set：重新 set 会把倒计时归零（真机根因）",
            )
            assertEquals(0, handle.count("cancelAll"), "且不得再清空")
            assertTrue(handle.calls.isEmpty(), "未变动的集合不该产生任何 AlarmManager 调用：${handle.calls}")
        }

    @Test
    fun `a sync failure is reported and does not abort later syncs`() =
        runTest {
            val handle = FakeAlarmHandle()
            handle.failOnOp = "cancelAll"
            val warnings = mutableListOf<String>()
            val coordinator = coordinator(handle = handle, warnings = warnings)

            coordinator.start()
            advanceUntilIdle()

            assertTrue(
                warnings.any { it.contains("alarm sync failed") },
                "对账失败必须可见（不得静默）：$warnings",
            )
            // 失败后 `clearedThisProcess` 仍为 false（清了才算数），后续仍会尝试
            assertEquals(false, coordinator.hasClearedThisProcess)
        }

    // ---------------------------------------------------------------- 工具

    private class FakeScheduleProvider(
        private val alarms: List<ScheduledAlarm>,
    ) : TriggerScheduleProvider {
        override suspend fun currentAlarms(): List<ScheduledAlarm> = alarms
    }

    private fun TestScope.coordinator(
        handle: FakeAlarmHandle,
        provider: TriggerScheduleProvider = FakeScheduleProvider(emptyList()),
        source: AlarmEventSource =
            AlarmEventSource(handle, clock = { now }, calendarFactory = { Calendar.getInstance(utc) }),
        warnings: MutableList<String> = mutableListOf(),
    ): AlarmSyncCoordinator =
        AlarmSyncCoordinator(
            provider = provider,
            alarmEventSource = source,
            alarmHandle = handle,
            scope = this,
            onWarning = warnings::add,
        )

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar
            .getInstance(utc)
            .apply {
                clear()
                set(year, month - 1, day, hour, minute)
            }.timeInMillis
}
