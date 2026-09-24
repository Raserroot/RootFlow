package com.rootflow.domain.event

import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AlarmSchedule] / [ScheduledAlarm] 单测（阶段 3c.2，**决策 5** 的守护）。
 *
 * ## 为什么 `deriveRequestCode` 要逐值钉死
 * 它是**闹钟身份的一半**（另一半是 action）。`scriptId` 是 Room 自增主键，
 * 但用户可能导出/导入脚本导致 id 变化；一旦派生规则被"顺手改成更简单"的写法
 * （例如截断低 32 位），已经在系统里注册的闹钟就会与新的 `requestCode` 对不上，
 * 表现为"旧闹钟永远取消不掉"。因此这里把折叠策略钉死。
 */
class AlarmScheduleTest {
    @Test
    fun `deriveRequestCode folds low and high words with xor`() {
        // 低 32 位与高 32 位异或；小 id 时高 32 位为 0，结果就是 id 本身
        assertEquals(0, AlarmSchedule.deriveRequestCode(0L))
        assertEquals(1, AlarmSchedule.deriveRequestCode(1L))
        assertEquals(42, AlarmSchedule.deriveRequestCode(42L))
        assertEquals(Int.MAX_VALUE, AlarmSchedule.deriveRequestCode(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun `deriveRequestCode uses the high word so ids beyond 32 bits do not collide with low ids`() {
        // 与"截断低 32 位"的关键差异：id + 2^32 必须派生出**不同**的值
        val low = AlarmSchedule.deriveRequestCode(1L)
        val high = AlarmSchedule.deriveRequestCode(1L + (1L shl 32))

        assertTrue(low != high, "high=$high low=$low（截断实现会让两者相同）")
        assertEquals(1 xor 1, high, "1 xor 1 = 0 → 高位参与后得到 0")
    }

    @Test
    fun `deriveRequestCode is stable and accepts negative results`() {
        // 关键：`-1L` 的高低位**互相抵消** → 0（不是 -1）。
        // 这正是"异或折叠"与"截断低 32 位"的区别所在：异或把高位也纳入，
        // 因此 `scriptId` 与其 `+2^32` 的取值派生出不同结果（见下一个用例）。
        assertEquals(0, AlarmSchedule.deriveRequestCode(-1L), "全 1 异或自身 → 0")
        assertEquals(0, AlarmSchedule.deriveRequestCode(-1L), "必须稳定")
        assertEquals(-1, AlarmSchedule.deriveRequestCode(0xFFFF_FFFFL), "低 32 位全 1、高位为 0 → -1")
    }

    @Test
    fun `deriveRequestCode handles extreme values`() {
        // 这两个值钉死"末尾的 and 0xFFFF_FFFF 掩码"语义（把 Long 的低 32 位原样当作 Int 位模式）
        assertEquals(
            Int.MIN_VALUE,
            AlarmSchedule.deriveRequestCode(Long.MIN_VALUE),
            "0x8000_0000_0000_0000 的高低位相同 → 异或为 0，低 32 位为 0 → 0",
        )
        assertEquals(
            AlarmSchedule.deriveRequestCode(Long.MIN_VALUE),
            AlarmSchedule.deriveRequestCode(Long.MAX_VALUE),
            "两者高低 32 位分别相同 → 异或后低位都清零（这正是异或折叠的特性，非缺陷）",
        )
    }

    @Test
    fun `the fold is injective for ids that differ only in the high word`() {
        // 这是"异或折叠"相对"截断低 32 位"的价值：高位不同 → 结果不同
        val low = AlarmSchedule.deriveRequestCode(1L)
        val high = AlarmSchedule.deriveRequestCode(1L + (1L shl 32))

        assertTrue(low != high, "low=$low high=$high（截断实现会让两者相同）")
        assertEquals(1 xor 1, high, "1 xor 1 = 0")
    }

    @Test
    fun `the unsigned mask is the low 32 bits`() {
        assertEquals(0xFFFF_FFFFL, AlarmSchedule.UNSIGNED_INT_MASK)
    }

    @Test
    fun `the same scriptId yields the same code for time and interval`() {
        // 这正是"必须用不同 action 区分"的原因（见 AlarmSchedule 的 KDoc）
        val scriptId = 12345L

        assertEquals(
            AlarmSchedule.deriveRequestCode(scriptId),
            AlarmSchedule.deriveRequestCode(scriptId),
        )
    }

    @Test
    fun `from maps a time trigger`() {
        val trigger =
            Trigger(
                id = 7,
                scriptId = 100,
                eventType = SystemEvent.TIME,
                params = TriggerParams(exact = true, hourOfDay = 6, minuteOfHour = 30),
                createdAt = 0,
            )

        val alarm = ScheduledAlarm.from(trigger)

        assertEquals(
            ScheduledAlarm.Time(triggerId = 7, scriptId = 100, exact = true, hourOfDay = 6, minuteOfHour = 30),
            alarm,
        )
    }

    @Test
    fun `from maps an interval trigger`() {
        val trigger =
            Trigger(
                id = 8,
                scriptId = 200,
                eventType = SystemEvent.INTERVAL,
                params = TriggerParams(intervalMinutes = 15),
                createdAt = 0,
            )

        val alarm = ScheduledAlarm.from(trigger)

        assertEquals(
            ScheduledAlarm.Interval(triggerId = 8, scriptId = 200, exact = false, intervalMinutes = 15),
            alarm,
        )
    }

    @Test
    fun `from returns null when params are incomplete`() {
        // 参数不足以判定"何时触发" → null，由调用方记入 AlarmPlan.invalid（不得静默跳过）
        assertNull(
            ScheduledAlarm.from(
                Trigger(1, 10, SystemEvent.TIME, TriggerParams(hourOfDay = 6), createdAt = 0),
            ),
            "time 缺 minuteOfHour",
        )
        assertNull(
            ScheduledAlarm.from(
                Trigger(1, 10, SystemEvent.INTERVAL, TriggerParams(), createdAt = 0),
            ),
            "interval 缺 intervalMinutes",
        )
    }

    @Test
    fun `from ignores non-alarm event types`() {
        assertNull(
            ScheduledAlarm.from(
                Trigger(1, 10, SystemEvent.SCREEN_OFF, TriggerParams(), createdAt = 0),
            ),
            "屏幕事件不属闹钟类",
        )
        assertNull(
            ScheduledAlarm.from(
                Trigger(
                    1,
                    10,
                    SystemEvent.BOOT,
                    TriggerParams(hourOfDay = 1, minuteOfHour = 0),
                    createdAt = 0,
                ),
            ),
            "即使 params 里有时刻字段，boot 也不是闹钟",
        )
    }

    @Test
    fun `from carries the exact flag through`() {
        val exactTime =
            ScheduledAlarm.from(
                Trigger(1, 10, SystemEvent.TIME, TriggerParams(exact = true, hourOfDay = 1, minuteOfHour = 2), true, 0),
            )
        val inexactInterval =
            ScheduledAlarm.from(
                Trigger(2, 10, SystemEvent.INTERVAL, TriggerParams(exact = false, intervalMinutes = 5), true, 0),
            )

        assertTrue(exactTime?.exact == true)
        assertTrue(inexactInterval?.exact == false)
    }
}
