package com.rootflow.data.event

import com.rootflow.domain.event.ScheduledAlarm

/**
 * 3c.2 的测试替身（纯 JVM）。
 *
 * ## 为什么 `FakeAlarmHandle` 记录 `(requestCode, action)` 成对
 * 闹钟身份是 `(requestCode, action)` 组合（见 `AlarmHandle` 的 KDoc）。
 * 只记 `requestCode` 无法验证"同一脚本的 `time` 与 `interval` 是否落在不同域"，
 * 也无法验证"取消的是不是对的那一个"。
 */
internal class FakeAlarmHandle : AlarmHandle {
    /** 一条调用记录。 */
    data class Call(
        val op: String,
        val requestCode: Int,
        val action: String,
        val atMillis: Long? = null,
        val intervalMillis: Long? = null,
    )

    val calls = mutableListOf<Call>()

    /** 设为非 null 时，指定 op（`cancel` / `setNext` / `setRepeating`）会抛异常。 */
    var failOnOp: String? = null

    override fun cancel(
        requestCode: Int,
        action: String,
    ) {
        maybeFail("cancel")
        calls += Call(op = "cancel", requestCode = requestCode, action = action)
    }

    /**
     * 取消本应用的**全部**闹钟（阶段 3d，决策 D-3d-1）。
     *
     * ## 为什么它也记进 [calls]
     * `AlarmSyncCoordinator` 的启动时序是"**先清后同步**"，若 `cancelAll` 不进调用记录，
     * 单测就无法断言"清空确实发生在 sync 之前"——而那正是决策 D-3d-1 的全部意义。
     * `requestCode` 记 [CANCEL_ALL_REQUEST_CODE] 这个哨兵值：真实实现里
     * `AlarmManager.cancelAll()` **不接受** requestCode。
     */
    override fun cancelAll(action: String) {
        maybeFail("cancelAll")
        calls += Call(op = "cancelAll", requestCode = CANCEL_ALL_REQUEST_CODE, action = action)
    }

    override fun setNext(
        requestCode: Int,
        action: String,
        atMillis: Long,
    ) {
        maybeFail("setNext")
        calls += Call(op = "setNext", requestCode = requestCode, action = action, atMillis = atMillis)
    }

    override fun setRepeating(
        requestCode: Int,
        action: String,
        intervalMillis: Long,
    ) {
        maybeFail("setRepeating")
        calls += Call(op = "setRepeating", requestCode = requestCode, action = action, intervalMillis = intervalMillis)
    }

    /** 某 op 的调用次数。 */
    fun count(op: String): Int = calls.count { it.op == op }

    /** 全部调用的 op 序列（用于断言调用**顺序**，如 `cancel → set`、`cancelAll → sync`）。 */
    fun opSequence(): List<String> = calls.map { it.op }

    private fun maybeFail(op: String) {
        if (failOnOp == op) throw IllegalStateException("boom-$op")
    }

    companion object {
        /** `cancelAll` 在调用记录里使用的哨兵 requestCode（真实 API 无此参数）。 */
        const val CANCEL_ALL_REQUEST_CODE: Int = Int.MIN_VALUE
    }
}

/**
 * 「可编排」的前台读取假件。
 *
 * [script] 是一串预设读数，**按调用顺序**逐一返回；用尽后重复最后一项
 * （便于"暂停期间反复读取"的用例不必写长脚本）。
 */
internal class FakeUsageStatsReader(
    private val script: List<UsageStatsRead>,
    startCursor: Long = 1_000L,
) : UsageStatsReader {
    var readCalls: Int = 0
        private set

    var resetCalls: Int = 0
        private set

    private var cursor: Long = startCursor

    override val cursorMillis: Long
        get() = cursor

    override fun read(currentMillis: Long): UsageStatsRead {
        val index = readCalls.coerceAtMost(script.lastIndex)
        readCalls++
        cursor = currentMillis
        return script[index]
    }

    override fun reset() {
        resetCalls++
    }
}

/** 便捷构造：一串前台包名（`null` = 本轮无新事件）。 */
internal fun foregroundScript(vararg packages: String?): List<UsageStatsRead> =
    packages.map { UsageStatsRead.Foreground(packageName = it) }

/** 便捷构造：`time` 闹钟。 */
internal fun timeAlarm(
    triggerId: Long,
    scriptId: Long,
    hour: Int,
    minute: Int,
    exact: Boolean = false,
): ScheduledAlarm =
    ScheduledAlarm.Time(
        triggerId = triggerId,
        scriptId = scriptId,
        exact = exact,
        hourOfDay = hour,
        minuteOfHour = minute,
    )

/** 便捷构造：`interval` 闹钟。 */
internal fun intervalAlarm(
    triggerId: Long,
    scriptId: Long,
    intervalMinutes: Int,
    exact: Boolean = false,
): ScheduledAlarm =
    ScheduledAlarm.Interval(
        triggerId = triggerId,
        scriptId = scriptId,
        exact = exact,
        intervalMinutes = intervalMinutes,
    )
