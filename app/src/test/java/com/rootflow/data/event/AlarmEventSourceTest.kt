package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AlarmSchedule
import com.rootflow.service.receiver.AlarmActions
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * `AlarmEventSource` 单测（阶段 3c.2）——**本轮 3c.2 的验收核心**。
 *
 * ## 为什么单测而非真机是核心
 * 按**决策 8**，`time` / `interval` 的真机验证推到 3d（触发前提是"Room 里有触发器数据"，
 * 而写入路径属 3d）。因此 `sync()` 的全部逻辑——`requestCode` 派生、冲突检测、
 * `cancel → set` 顺序、陈旧取消、无效报告——**真机覆盖不到**，只能靠本类。
 * 变更报告中不得以"真机通过"表述这些结论。
 *
 * ## 固定时钟与时区
 * `clock` 固定为 `2026-09-19 10:00 UTC`，`calendarFactory` 固定 UTC，
 * 因此"下次触发时刻"的期望值可用 `utc(...)` 精确写出（与本机时区无关）。
 */
class AlarmEventSourceTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * 固定"现在" = `2026-09-19 06:00 UTC`。
     *
     * 取 06:00 而不是更晚的时刻是有意的：本类多处断言"07:00 的闹钟落在**今天**"，
     * 若 now 晚于 07:00，`NextFireTime` 会按设计顺延到明天（那是正确行为，
     * 但会让断言的意图变得含糊）。now 在 07:00 之前，语义一目了然。
     */
    private val now = utc(2026, 9, 19, 6, 0)

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

    private fun source(handle: AlarmHandle): AlarmEventSource =
        AlarmEventSource(handle, clock = { now }, calendarFactory = { Calendar.getInstance(utc) })

    @Test
    fun `sync registers a time alarm with the computed fire time`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan = source.sync(listOf(timeAlarm(triggerId = 1, scriptId = 100, hour = 23, minute = 30)))

            val code = AlarmSchedule.deriveRequestCode(100)
            assertEquals(listOf(code), plan.registered)
            assertTrue(plan.isEmpty.not())

            // 首个动作必然是 cancel（先取消旧配置），随后 setNext
            assertEquals("cancel", handle.calls.first().op)
            val set = handle.calls.single { it.op == "setNext" }
            assertEquals(code, set.requestCode)
            assertEquals(AlarmActions.TIME, set.action)
            assertEquals(utc(2026, 9, 19, 23, 30), set.atMillis)
        }
    }

    @Test
    fun `sync registers an interval alarm as an inexact repeating alarm`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan = source.sync(listOf(intervalAlarm(triggerId = 5, scriptId = 200, intervalMinutes = 15)))

            val code = AlarmSchedule.deriveRequestCode(200)
            assertEquals(listOf(code), plan.registered)
            val set = handle.calls.single { it.op == "setRepeating" }
            assertEquals(AlarmActions.INTERVAL, set.action, "D4：interval 用非精确重复，且 action 与 time 分离")
            assertEquals(900_000L, set.intervalMillis)
        }
    }

    @Test
    fun `cancel always precedes set for a newly registered alarm`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            source.sync(listOf(timeAlarm(1, 100, hour = 12, minute = 0)))

            val ops = handle.calls.map { it.op }
            assertEquals(listOf("cancel", "setNext"), ops, "调用序必须固定为 cancel → set")
        }
    }

    @Test
    fun `syncing the same set twice does not touch the handle again`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)
            val alarms = listOf(timeAlarm(1, 100, hour = 12, minute = 0))

            val first = source.sync(alarms)
            val second = source.sync(alarms)

            assertEquals(first, second, "同一集合重复 sync 必须给出相同结果（幂等）")
            assertEquals(2, handle.calls.size, "第二轮不得再 cancel/set 任何闹钟：${handle.calls}")
        }
    }

    @Test
    fun `a conflicting requestCode skips the later trigger and keeps the earlier one`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            // 同一 scriptId 的两条 time 触发器 → 派生同一 requestCode（决策 5 的冲突场景）
            val plan =
                source.sync(
                    listOf(
                        timeAlarm(triggerId = 1, scriptId = 100, hour = 7, minute = 0),
                        timeAlarm(triggerId = 2, scriptId = 100, hour = 8, minute = 0),
                    ),
                )

            val code = AlarmSchedule.deriveRequestCode(100)
            assertEquals(listOf(code), plan.registered, "只注册前者")
            assertEquals(listOf(code), plan.skippedConflicts, "后者必须被记为冲突（绝不静默覆盖）")
            assertEquals(1, handle.count("setNext"), "冲突项不得下发")
            // 注册的是**第一条**（7:00），不是被跳过的那条
            assertEquals(utc(2026, 9, 19, 7, 0), handle.calls.single { it.op == "setNext" }.atMillis)
        }
    }

    @Test
    fun `invalid params are reported and not registered`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            // 非法时刻（hour=24）→ NextFireTime 返回 null → 必须进 invalid，不得注册
            val plan = source.sync(listOf(timeAlarm(triggerId = 9, scriptId = 100, hour = 24, minute = 0)))

            assertTrue(plan.registered.isEmpty())
            assertEquals(listOf(9L), plan.invalid)
            assertTrue(handle.calls.none { it.op == "setNext" }, "非法配置不得下发：${handle.calls}")
        }
    }

    @Test
    fun `an invalid interval is reported and not registered`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan = source.sync(listOf(intervalAlarm(triggerId = 11, scriptId = 200, intervalMinutes = 0)))

            assertTrue(plan.registered.isEmpty())
            assertEquals(listOf(11L), plan.invalid)
            assertTrue(handle.calls.none { it.op == "setRepeating" })
        }
    }

    @Test
    fun `alarms that disappear from the plan are cancelled`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)
            val first = timeAlarm(1, 100, hour = 7, minute = 0)
            val second = timeAlarm(2, 200, hour = 8, minute = 0)

            source.sync(listOf(first, second))
            val plan = source.sync(listOf(first))

            val dropped = AlarmSchedule.deriveRequestCode(200)
            assertEquals(listOf(dropped), plan.cancelled, "本轮不再需要的闹钟必须被取消")
            assertTrue(handle.calls.any { it.op == "cancel" && it.requestCode == dropped })
        }
    }

    @Test
    fun `an empty plan cancels everything registered before`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)
            val alarm = timeAlarm(1, 100, hour = 7, minute = 0)

            source.sync(listOf(alarm))
            handle.calls.clear()
            val plan = source.sync(emptyList())

            assertEquals(listOf(AlarmSchedule.deriveRequestCode(100)), plan.cancelled)
            assertEquals(listOf("cancel"), handle.calls.map { it.op }, "全部取消，且不再注册")
            assertTrue(plan.registered.isEmpty())
        }
    }

    @Test
    fun `time and interval for the same script do not collide`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan =
                source.sync(
                    listOf(
                        timeAlarm(triggerId = 1, scriptId = 100, hour = 7, minute = 0),
                        intervalAlarm(triggerId = 2, scriptId = 100, intervalMinutes = 30),
                    ),
                )

            val code = AlarmSchedule.deriveRequestCode(100)
            assertEquals(listOf(code, code), plan.registered.sorted(), "两类各注册一次（同 code、不同 action）")
            assertTrue(plan.skippedConflicts.isEmpty(), "不同 action 域不构成冲突：${plan.skippedConflicts}")
            assertEquals(1, handle.count("setNext"))
            assertEquals(1, handle.count("setRepeating"))
            // 取消也必须落在各自的 action 上
            assertTrue(handle.calls.any { it.op == "cancel" && it.action == AlarmActions.TIME })
            assertTrue(handle.calls.any { it.op == "cancel" && it.action == AlarmActions.INTERVAL })
        }
    }

    @Test
    fun `exact true still registers an inexact alarm and warns`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            // D3：v1 不申请 SCHEDULE_EXACT_ALARM、不走精确路径 → 仍按非精确注册
            val plan = source.sync(listOf(timeAlarm(1, 100, hour = 7, minute = 0, exact = true)))

            assertEquals(1, plan.registered.size, "exact=true 不得导致触发器完全失效")
            assertEquals(1, handle.count("setNext"), "仍下发（非精确）")
        }
    }

    @Test
    fun `a handle failure does not break the rest of the sync`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            handle.failOnOp = "setNext"
            val source = source(handle)

            val plan =
                source.sync(
                    listOf(
                        timeAlarm(1, 100, hour = 7, minute = 0),
                        intervalAlarm(2, 200, intervalMinutes = 10),
                    ),
                )

            // time 的 setNext 失败（被 safely 吞掉并记警告），interval 必须仍然注册
            assertTrue(plan.registered.contains(AlarmSchedule.deriveRequestCode(200)), "一条失败不得中断整轮：$plan")
        }
    }

    @Test
    fun `sync results are sorted for stable device logs`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan =
                source.sync(
                    listOf(
                        timeAlarm(1, scriptId = 300, hour = 7, minute = 0),
                        timeAlarm(2, scriptId = 100, hour = 8, minute = 0),
                        timeAlarm(3, scriptId = 200, hour = 9, minute = 0),
                    ),
                )

            assertEquals(plan.registered.sorted(), plan.registered, "顺序必须确定（真机逐行比对）")
        }
    }

    @Test
    fun `the time source reports the expected identity and permissions`() {
        installLogStub()
        val source = source(FakeAlarmHandle())
        val time = source.time

        assertEquals("alarm_time", time.sourceId)
        assertTrue(
            time.requiredPermissions.isEmpty(),
            "D3：非精确路径不依赖 SCHEDULE_EXACT_ALARM，权限集必须为空",
        )
        assertFalse(time.status().state.isRunning)
    }

    @Test
    fun `the interval source reports the expected identity and permissions`() {
        installLogStub()
        val source = source(FakeAlarmHandle())
        val interval = source.interval

        assertEquals("alarm_interval", interval.sourceId)
        assertTrue(interval.requiredPermissions.isEmpty(), "D4：setInexactRepeating 不需要权限")
    }

    @Test
    fun `start and stop are idempotent per source`() {
        installLogStub()
        val source = source(FakeAlarmHandle())

        source.time.start()
        source.time.start()
        assertTrue(
            source.time
                .status()
                .state.isRunning,
        )
        assertFalse(
            source.interval
                .status()
                .state.isRunning,
            "两类源的启动状态互不影响",
        )

        source.time.stop()
        source.time.stop()
        assertFalse(
            source.time
                .status()
                .state.isRunning,
        )
    }

    @Test
    fun `start does not register any alarm by itself`() {
        installLogStub()
        val handle = FakeAlarmHandle()
        val source = source(handle)

        // 注册数据来自 Room（3d），start() 不得凭空注册（否则会注册出无意义的闹钟）
        source.time.start()
        source.interval.start()

        assertTrue(handle.calls.isEmpty(), "start() 不得触碰 AlarmManager：${handle.calls}")
    }

    @Test
    fun `alarm action constants are the platform-shared literals`() {
        runTest {
            installLogStub()
            // 这三个常量是写入侧 / 接收侧 / AndroidManifest.xml 三方的契约，必须逐字一致
            assertEquals("com.rootflow.TIME", AlarmActions.TIME)
            assertEquals("com.rootflow.INTERVAL", AlarmActions.INTERVAL)

            val handle = FakeAlarmHandle()
            source(
                handle,
            ).sync(listOf(timeAlarm(1, 100, hour = 7, minute = 0), intervalAlarm(2, 200, intervalMinutes = 5)))

            assertTrue(handle.calls.all { it.action == AlarmActions.TIME || it.action == AlarmActions.INTERVAL })
        }
    }

    @Test
    fun `an empty input list is a no-op`() {
        runTest {
            installLogStub()
            val handle = FakeAlarmHandle()
            val source = source(handle)

            val plan = source.sync(emptyList())

            assertEquals(AlarmPlan.EMPTY, plan)
            assertTrue(plan.isEmpty)
            assertTrue(handle.calls.isEmpty())
        }
    }

    @Test
    fun `alarm plan emptiness reflects every list`() {
        assertTrue(AlarmPlan.EMPTY.isEmpty)
        assertFalse(AlarmPlan(registered = listOf(1), emptyList(), emptyList(), emptyList()).isEmpty)
        assertFalse(AlarmPlan(emptyList(), cancelled = listOf(1), emptyList(), emptyList()).isEmpty)
        assertFalse(AlarmPlan(emptyList(), emptyList(), skippedConflicts = listOf(1), emptyList()).isEmpty)
        assertFalse(AlarmPlan(emptyList(), emptyList(), emptyList(), invalid = listOf(1L)).isEmpty)
    }

    /** 固定 `android.util.Log`（源内多处打日志）。 */
    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
