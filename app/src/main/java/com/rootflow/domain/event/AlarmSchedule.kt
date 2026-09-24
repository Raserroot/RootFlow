package com.rootflow.domain.event

import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger

/**
 * 一条待注册的闹钟（阶段 3c.2，需求 §2.1 的 `time` / `interval`）。
 *
 * ## 为什么不是直接用 `Trigger`
 * `Trigger.params` 是**两种事件共用**的结构（`TriggerParams` 同时含 `intervalMinutes`
 * 与 `hourOfDay`/`minuteOfHour`，见 3a 的 `PersistenceModels`）。而 `time` 的 `params` 里
 * 不该有 `intervalMinutes`、反之亦然。本类型把"哪一种闹钟"显式化：
 * - [Time]：单次，指向"下次该时刻"
 * - [Interval]：重复，固定间隔
 *
 * 这样 `sync()` 的冲突域与 `AlarmManager` 的 `(requestCode, action)` 命名空间**一一对应**，
 * 不必在注册逻辑里再判 `eventType` 字符串。
 */
sealed interface ScheduledAlarm {
    /** 触发器主键（`triggers.id`），仅用于日志与 `invalid` 报告。 */
    val triggerId: Long

    /** 脚本主键（`triggers.script_id`），**`requestCode` 由它派生**。 */
    val scriptId: Long

    /**
     * 是否请求精确闹钟。
     *
     * 按 **D3**，v1 **不申请** `SCHEDULE_EXACT_ALARM`、也不走精确路径：
     * 本字段本期**只用于记一行日志**（见 `AlarmEventSource` 的 `exact=true` 处理）。
     */
    val exact: Boolean

    /** `time`：在某天某时某分触发一次。 */
    data class Time(
        override val triggerId: Long,
        override val scriptId: Long,
        override val exact: Boolean,
        val hourOfDay: Int,
        val minuteOfHour: Int,
    ) : ScheduledAlarm

    /** `interval`：按固定间隔重复触发。 */
    data class Interval(
        override val triggerId: Long,
        override val scriptId: Long,
        override val exact: Boolean,
        val intervalMinutes: Int,
    ) : ScheduledAlarm

    companion object {
        /**
         * 从 Room 的触发器模型构造。
         *
         * 参数不足以判定类型时返回 `null`（调用方记入 `AlarmPlan.invalid`，
         * **不得**静默跳过——"某条触发器永远不触发"是最难排查的一类缺陷）。
         *
         * @param eventType `triggers.event_type`，只认 [SystemEvent.TIME] / [SystemEvent.INTERVAL]
         */
        fun from(trigger: Trigger): ScheduledAlarm? =
            when (trigger.eventType) {
                SystemEvent.TIME -> {
                    val hour = trigger.params.hourOfDay
                    val minute = trigger.params.minuteOfHour
                    if (hour == null || minute == null) {
                        null
                    } else {
                        Time(
                            triggerId = trigger.id,
                            scriptId = trigger.scriptId,
                            exact = trigger.params.exact,
                            hourOfDay = hour,
                            minuteOfHour = minute,
                        )
                    }
                }

                SystemEvent.INTERVAL -> {
                    val minutes = trigger.params.intervalMinutes
                    if (minutes == null) {
                        null
                    } else {
                        Interval(
                            triggerId = trigger.id,
                            scriptId = trigger.scriptId,
                            exact = trigger.params.exact,
                            intervalMinutes = minutes,
                        )
                    }
                }

                else -> null
            }
    }
}

/**
 * `requestCode` 的派生策略（阶段 3c.2，**决策 5**）。
 *
 * ## 折叠策略：`(scriptId XOR (scriptId ushr 32)).toInt()`
 * `AlarmManager` 的 `requestCode` 是 `Int`，而 `scriptId` 是 `Long`（Room 主键）。
 * 折叠方式为**低 32 位异或高 32 位**：
 *
 * - **稳定**：同一 `scriptId` 永远派生出同一值，无需持久化
 * - **与脚本生命周期一致**：脚本删除重建会得到新 id → 新 `requestCode`，
 *   旧闹钟由 [AlarmPlan.cancelled] 的"取消上一轮"机制清理
 * - **优于"截断低 32 位"**：截断会让 `scriptId` 与其 `+2^32` 的取值碰撞；
 *   异或把高位也纳入，实际使用中（自增主键）碰撞概率可忽略
 *
 * ## 为什么用 `scriptId` 而不是 `triggerId`
 * 语义是"**这个脚本的定时**"：同一脚本若有两条 `time` 触发器，它们本就该合并成一个闹钟
 * （触发同一个脚本、同一事件）。用 `triggerId` 会让"删掉再新建触发器"改变 `requestCode`，
 * 留下指向已删触发器的旧闹钟（`AlarmManager` 不会因为你换了 code 而自动清理）。
 *
 * ## 命名空间是 `(requestCode, action)` 组合
 * 因此 `time` 与 `interval` **必须用不同 action 字符串**
 * （[AlarmEventSource.ACTION_TIME] / [AlarmEventSource.ACTION_INTERVAL]）：
 * 同一脚本的 `time` 与 `interval` 触发器派生出的 `requestCode` **相同**，
 * 靠 action 区分才互不覆盖。取消闹钟时**必须成对给出 `(requestCode, action)`**，
 * 只给 `requestCode` 会取消错域。
 *
 * ## 冲突检测（必须做，不能静默）
 * `sync()` 内维护 `requestCode → triggerId` 映射；若两条触发器派生出同一 `requestCode`
 * → **记日志 + 跳过后者**（进 [AlarmPlan.skippedConflicts]），**绝不静默覆盖**——
 * 那会让前一条触发器"被静默替换"，属本仓库反复禁止的静默失败。
 */
object AlarmSchedule {
    /**
     * 由 `scriptId` 派生 `requestCode`。
     *
     * ## 为什么末尾要 `and 0xFFFF_FFFFL`
     * 折叠结果是 `Long`，需要压到 `AlarmManager` 要的 `Int`。若直接 `.toInt()`，
     * 会走 Kotlin 的**有符号窄化**（截断低 32 位并保留符号），于是：
     * - `Long.MIN_VALUE`（`0x8000_0000_0000_0000`）折叠后是 `0x8000_0000` → `.toInt()` 得
     *   `Int.MIN_VALUE`（负数），而语义上它应当是"低 32 位"这个无符号值
     * - `-1L`（全 1）折叠后是 `0xFFFF_FFFF` → `.toInt()` 得 `-1`，又被窄化"看起来对"，
     *   使两类值的行为不一致、难以推理
     *
     * 先 `and 0xFFFF_FFFFL` 把高位清零得到**无符号低 32 位**（`0..2^32-1` 的 `Long`），
     * 再 `.toInt()`：此时值仍在 `Long` 范围内无溢出，Kotlin 的 `Long.toInt()` 对
     * `0..2^32-1` 的输入按**低 32 位原样**给出 `Int`（`0x8000_0000` → `Int.MIN_VALUE`，
     * `0xFFFF_FFFF` → `-1`），语义就是"把 32 位模式原封不动交给 `AlarmManager`"。
     *
     * `AlarmManager` 接受任意 `Int`（含负数），因此负值完全合法——本函数的契约是
     * **稳定且单射**，不是"必须为正"。
     *
     * @return 折叠后的 `Int`；**可能为负**
     */
    fun deriveRequestCode(scriptId: Long): Int = ((scriptId xor (scriptId ushr 32)) and UNSIGNED_INT_MASK).toInt()

    /** 低 32 位的掩码（把 `Long` 的有符号值当作无符号 32 位模式处理）。 */
    const val UNSIGNED_INT_MASK: Long = 0xFFFF_FFFFL
}
