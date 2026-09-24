package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AlarmSchedule
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.NextFireTime
import com.rootflow.domain.event.ScheduledAlarm
import com.rootflow.domain.model.SystemEvent
import com.rootflow.service.receiver.AlarmActions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次 `sync()` 的结果（阶段 3c.2）。
 *
 * ## 四个列表的语义
 * | 字段 | 含义 |
 * |---|---|
 * | [registered] | 本轮**成功注册**的 `requestCode` |
 * | [cancelled] | **上一轮注册、本轮已不在计划内** → 已取消的 `requestCode` |
 * | [skippedConflicts] | 因 `requestCode` 撞车被**跳过**的（决策 5：绝不静默覆盖） |
 * | [invalid] | `params` 不足以算出触发时刻的 `triggerId` |
 *
 * ## 为什么必须报告而不是静默（本仓库的反复纪律）
 * `registered=0` 与"注册了但用户看不到"在真机上表现完全一样。四个列表把
 * "本轮到底发生了什么"变成可断言、可打日志的事实；真机 `findstr /C:"ALARM_SYNC"` 即可判读。
 */
data class AlarmPlan(
    val registered: List<Int>,
    val cancelled: List<Int>,
    val skippedConflicts: List<Int>,
    val invalid: List<Long>,
) {
    /** 是否本轮什么都没做。 */
    val isEmpty: Boolean
        get() = registered.isEmpty() && cancelled.isEmpty() && skippedConflicts.isEmpty() && invalid.isEmpty()

    companion object {
        /** 空计划。 */
        val EMPTY: AlarmPlan =
            AlarmPlan(
                registered = emptyList(),
                cancelled = emptyList(),
                skippedConflicts = emptyList(),
                invalid = emptyList(),
            )
    }
}

/**
 * 闹钟操作句柄（阶段 3c.2 的 Android 注入缝）。
 *
 * ## 为什么每个方法都要 `(requestCode, action)` 成对
 * `AlarmManager` 的闹钟身份是 **`(requestCode, action)` 组合**（底层是 `PendingIntent` 的
 * 等价性判定）。**只给 `requestCode` 无法确定取消哪一个**——同一脚本的 `time` 与 `interval`
 * 触发器派生出的 `requestCode` 是**同一个值**（都由 `scriptId` 折叠而来，
 * 见 [AlarmSchedule.deriveRequestCode]），仅靠不同的 `action` 字符串区分。
 * 因此本接口**不接受**"只给 code"的调用形态。
 *
 * 唯一实现 `data/event/android/AndroidAlarmHandle` 直调 `AlarmManager`，
 * **按决策 13 不进单测**（正确性由真机 `dumpsys alarm` 覆盖）。
 */
interface AlarmHandle {
    /** 取消一个闹钟。**不存在时调用必须安全**（`AlarmManager.cancel` 本就幂等）。 */
    fun cancel(
        requestCode: Int,
        action: String,
    )

    /**
     * 取消**本应用**在 [action] 这个域下的全部闹钟（阶段 3d，决策 **D-3d-1**）。
     *
     * ## 为什么必须新增它
     * `AlarmManager` **无法按 action 列举自身已注册的闹钟**（没有这样的查询 API），
     * 而跨进程记不住 `requestCode`：进程被杀后重启，系统里仍留着上一进程注册的闹钟，
     * 内存记忆却是空的（3c.2 遗留 #3）。唯一可行的对账起点就是"先全清一遍"。
     *
     * ## 代价（已登记在 3d 方案 §5）
     * `AlarmManager.cancelAll()` 取消本应用**全部**闹钟，因此两个域要各清一次
     * （两次调用合起来才覆盖全部）。副作用是启动瞬间"先清空再重建"，
     * 若 App 恰在闹钟到点前启动，**可能丢一次触发窗口**。替代方案（进程内记忆 + 全量对账）
     * 在进程重启后同样会漏，故取前者：简单、可验证、窗口极窄。
     *
     * @param action 闹钟域（[AlarmActions.TIME] / [AlarmActions.INTERVAL]）
     */
    fun cancelAll(action: String)

    /** 注册"下次某时刻触发一次"的闹钟（非精确，D3）。 */
    fun setNext(
        requestCode: Int,
        action: String,
        atMillis: Long,
    )

    /** 注册"固定间隔重复"的闹钟（非精确，D4）。 */
    fun setRepeating(
        requestCode: Int,
        action: String,
        intervalMillis: Long,
    )
}

/**
 * `time` / `interval` 共用的闹钟注册逻辑（阶段 3c.2）。
 *
 * ## 为什么两类闹钟合在一个类（实现决策 D-1，2026-09-19 批准）
 * `requestCode` 派生（决策 5）、冲突检测、`cancel→set` 顺序、无效参数报告——
 * 这些逻辑对 `time` 与 `interval` **完全同构**。拆成两个类会产生"两份会各自漂移的真相"；
 * 它们的差异其实只有两点：**action 字符串**与**注册调用**（[AlarmHandle.setNext] /
 * [AlarmHandle.setRepeating]）。这两点由 [Kind] 参数化，因此本源对外仍暴露
 * **两个** `EventSource`（[time] / [interval]），以满足决策 7 的逐源状态上报。
 *
 * ## `sync()` 是幂等的
 * 可反复调用（触发器增删改后各调一次）。实现方式是**按上一轮快照对账**：
 * 先取消"上一轮有、本轮没有"的，再逐条 `cancel → set`。
 * 因此"改了 `params`"不会留下按旧配置触发的闹钟。
 *
 * ## `exact=true` 的处理（D3）
 * v1 **不申请** `SCHEDULE_EXACT_ALARM`、**不走**精确路径。收到 `exact=true` 时
 * 只记一行 `EXACT_REQUESTED_IGNORED` 警告，**仍按非精确注册**——
 * 静默忽略会让用户以为"精确已生效"，而直接跳过注册会让触发器完全失效，两者都更糟。
 *
 * ## 下一轮对账的边界（已知限制，3d 处理）
 * "上一轮"是**进程内**记忆。进程被杀后重启，系统里仍留着上一进程注册的闹钟，
 * 而本类记忆为空 → **不会取消**它们。3d 接线时应从 Room 读"应有的全量集合"再对账，
 * 或在启动时先清一次（本阶段不做，见变更报告「已知问题」）。
 *
 * ## 构造函数形态（勿改成带默认值的 `@Inject` 构造）
 * `clock` / `calendarFactory` 走 `internal` 次构造函数：Kotlin 默认参数对 Dagger 不可见，
 * 放进 `@Inject` 构造会报 `MissingBinding`（3c.1 实测教训）。
 */
@Singleton
class AlarmEventSource
    @Inject
    constructor(
        private val handle: AlarmHandle,
    ) {
        /** 测试缝：注入固定时钟与固定时区 `Calendar`（生产走 `@Inject` 主构造）。 */
        internal constructor(
            handle: AlarmHandle,
            clock: () -> Long,
            calendarFactory: () -> Calendar,
        ) : this(handle = handle) {
            this.clock = clock
            this.calendarFactory = calendarFactory
        }

        private var clock: () -> Long = System::currentTimeMillis

        private var calendarFactory: () -> Calendar = { Calendar.getInstance() }

        private val lock = Any()

        /** 上一轮 `sync` 已注册的 `(action, requestCode)` 快照，用于对账。 */
        private var lastRegistered: Set<Pair<String, Int>> = emptySet()

        /**
         * `(action, requestCode)` → 上一轮注册时对应的 `triggerId`。
         *
         * 用于判断"本轮同一条触发器是否变了配置"：若 `triggerId` 相同，说明闹钟内容未变，
         * **不需要**再 `cancel + set` 一次（幂等）；若不同（触发器被删后重建），则必须重下发。
         */
        private var lastSeenTrigger: MutableMap<Pair<String, Int>, Long> = mutableMapOf()

        /**
         * `time` 事件的源（`sourceId = "alarm_time"`）。
         *
         * **权限集为空**：D3 决定 v1 走非精确（`setAndAllowWhileIdle`），
         * 因此**不依赖** `SCHEDULE_EXACT_ALARM`。将来真正启用精确路径时，这里才需要加该权限。
         */
        val time: EventSource = Source(id = SOURCE_ID_TIME, kind = Kind.TIME)

        /**
         * `interval` 事件的源（`sourceId = "alarm_interval"`）。
         *
         * **权限集为空**：需求 §2.1 的降级策略写"无"。按 **D2/D4**，3c.2 用
         * `setInexactRepeating`；阶段 5 改用前台服务内协程 delay 时只替换本源实现，不影响 `time`。
         */
        val interval: EventSource = Source(id = SOURCE_ID_INTERVAL, kind = Kind.INTERVAL)

        /**
         * 同步闹钟集合（幂等）。
         *
         * 两类闹钟按类分别对账，因为两类各有独立的 `(requestCode, action)` 命名空间。
         *
         * ## 为什么是 `suspend`（阶段 3d）
         * 3d 的取数来源 `TriggerScheduleProvider.currentAlarms()` 必须改成挂起
         * （`TriggerRepository.enabledForEvent` 是 `suspend`）。为让"取消了就不该继续
         * 触碰 `AlarmManager`"保持成立，本方法随之为 `suspend` 并在**每一类**开始时
         * 检查取消（`AGENTS.md`：所有 suspend 函数必须可取消）。
         * 单测的 `runTest` 里直接调用即可，无额外桥接。
         *
         * @param alarms 本轮**应有**的全部闹钟（来自 Room；3d 起由
         *   `RoomTriggerScheduleProvider` 提供）
         */
        suspend fun sync(alarms: List<ScheduledAlarm>): AlarmPlan {
            // 取消检查必须在**进入临界区之前**：`synchronized` 是内联块、不是挂起上下文，
            // 里面不能调 `ensureActive()`。此处进入前检查已满足"取消后不再触碰 AlarmManager"。
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                // **两类都要走一轮对账，即使本轮集合里没有该类**：
                // 若因"本轮没有 time 闹钟"就跳过 time 域，那么"删掉最后一条 time 触发器"
                // 时旧闹钟会永远留在系统里（静默失败）。空集合也必须能取消上一轮的注册。
                val kinds =
                    Kind.entries.filter { kind ->
                        // 上一轮注册过（需要取消差额）或本轮有该类闹钟（需要注册）
                        val registeredBefore = lastRegistered.any { it.first == kind.action }
                        registeredBefore || alarms.any { kind.accepts(it) }
                    }
                if (kinds.isEmpty()) return AlarmPlan.EMPTY

                var registered = emptyList<Int>()
                var cancelled = emptyList<Int>()
                var conflicts = emptyList<Int>()
                var invalid = emptyList<Long>()

                kinds.forEach { kind ->
                    val plan = syncKind(kind, alarms)
                    registered = registered + plan.registered
                    cancelled = cancelled + plan.cancelled
                    conflicts = conflicts + plan.skippedConflicts
                    invalid = invalid + plan.invalid
                }

                val plan =
                    AlarmPlan(
                        registered = registered.sorted(),
                        cancelled = cancelled.sorted(),
                        skippedConflicts = conflicts.sorted(),
                        invalid = invalid.sorted(),
                    )
                Log.i(
                    TAG,
                    "ALARM_SYNC registered=${plan.registered} cancelled=${plan.cancelled} " +
                        "conflicts=${plan.skippedConflicts} invalid=${plan.invalid}",
                )
                return plan
            }
        }

        /** 只对账一类闹钟。 */
        private fun syncKind(
            kind: Kind,
            alarms: List<ScheduledAlarm>,
        ): AlarmPlan {
            val mine = alarms.filter { kind.accepts(it) }

            // 本轮**应当存在**的 requestCode → triggerId。
            // 冲突检测（决策 5：绝不静默覆盖）在此完成。
            val currentCodes = LinkedHashMap<Int, Long>()
            val conflicts = mutableListOf<Int>()
            mine.forEach { alarm ->
                val code = AlarmSchedule.deriveRequestCode(alarm.scriptId)
                val previous = currentCodes[code]
                if (previous != null) {
                    conflicts += code
                    Log.w(
                        TAG,
                        "ALARM_CONFLICT action=${kind.action} requestCode=$code " +
                            "keptTrigger=$previous skippedTrigger=${alarm.triggerId}",
                    )
                } else {
                    currentCodes[code] = alarm.triggerId
                }
            }

            // 陈旧 = 上一轮注册了、但本轮**不在** currentCodes 里（触发器被删或被改坏）。
            // 注意：不能用"合并了上一轮的映射"来判断陈旧，否则"本轮不再需要"的 code
            // 会被自己的历史记录一直保活，永远取消不掉。
            val stale =
                lastRegistered.filter { (action, code) ->
                    action == kind.action && code !in currentCodes
                }
            val cancelled = stale.map { it.second }

            // 先取消陈旧的
            cancelled.forEach { code -> safely("cancel") { handle.cancel(code, kind.action) } }

            // 再处理本轮目标：只有"上一轮没注册过、或 triggerId 变了"的才需要重新下发
            val registered = mutableListOf<Int>()
            val invalid = mutableListOf<Long>()
            currentCodes.forEach { (code, triggerId) ->
                if (lastRegistered.contains(kind.action to code) && lastSeenTrigger[kind.action to code] == triggerId) {
                    // 上一轮已按同一条触发器注册过 → 不打扰系统（幂等）
                    registered += code
                    return@forEach
                }
                val alarm = mine.firstOrNull { it.triggerId == triggerId } ?: return@forEach
                if (registerOne(kind, code, alarm)) {
                    registered += code
                } else {
                    invalid += alarm.triggerId
                }
            }

            lastRegistered = lastRegistered - stale.toSet() + currentCodes.keys.map { kind.action to it }.toSet()
            currentCodes.forEach { (code, triggerId) -> lastSeenTrigger[kind.action to code] = triggerId }
            stale.forEach { (action, code) -> lastSeenTrigger.remove(action to code) }

            return AlarmPlan(
                registered = registered.sorted(),
                cancelled = cancelled.sorted(),
                skippedConflicts = conflicts.distinct().sorted(),
                invalid = invalid.sorted(),
            )
        }

        /**
         * 下发一条闹钟（先 `cancel` 后 `set`）。
         *
         * **先取消是必需的**：改了 `params` 的触发器必须按新配置生效；显式 `cancel`
         * 让两类注册路径行为一致，也便于真机观察调用序。
         *
         * @return `true` 已下发；`false` 配置算不出触发时刻（由调用方记入 `invalid`）
         */
        private fun registerOne(
            kind: Kind,
            code: Int,
            alarm: ScheduledAlarm,
        ): Boolean {
            val action: (AlarmHandle, Int) -> Unit =
                when (kind) {
                    Kind.TIME -> {
                        val typed = alarm as? ScheduledAlarm.Time
                        val at = typed?.let { NextFireTime.nextFor(it, clock(), calendarFactory) }
                        if (at == null) {
                            Log.w(
                                TAG,
                                "ALARM_INVALID action=${kind.action} trigger=${alarm.triggerId} " +
                                    "reason=no-fire-time",
                            )
                            return false
                        }
                        { handle, requestCode -> handle.setNext(requestCode, kind.action, at) }
                    }

                    Kind.INTERVAL -> {
                        val typed = alarm as? ScheduledAlarm.Interval
                        val interval = typed?.let { NextFireTime.repeatingIntervalMillis(it) }
                        if (interval == null) {
                            Log.w(
                                TAG,
                                "ALARM_INVALID action=${kind.action} trigger=${alarm.triggerId} " +
                                    "reason=no-interval",
                            )
                            return false
                        }
                        { handle, requestCode -> handle.setRepeating(requestCode, kind.action, interval) }
                    }
                }

            if (alarm.exact) {
                // D3：不申请 SCHEDULE_EXACT_ALARM、不走精确路径；如实记日志而不是装作支持
                Log.w(
                    TAG,
                    "EXACT_REQUESTED_IGNORED action=${kind.action} trigger=${alarm.triggerId} " +
                        "(v1 uses inexact alarms; see PROJECT_STATE.md D3)",
                )
            }

            // 先取消后注册：改了 params 的触发器必须按新配置生效；显式 cancel
            // 让两类注册路径行为一致，也便于真机观察调用序。
            safely("cancel") { handle.cancel(code, kind.action) }
            safely("set") { action(handle, code) }
            return true
        }

        /**
         * 执行一次句柄调用并吞掉异常。
         *
         * `AlarmManager` 在极端情况（权限被撤销、系统服务不可用）会抛 `SecurityException` /
         * `IllegalStateException`；**一条闹钟失败不能中断整轮同步**，但也**不得静默**。
         */
        private inline fun safely(
            operation: String,
            block: () -> Unit,
        ) {
            try {
                block()
            } catch (error: Throwable) {
                Log.w(TAG, "ALARM_CALL_FAILED op=$operation: ${error.message ?: error::class.java.name}")
            }
        }

        /**
         * 闹钟种类：把两类闹钟的**差异**集中在一处。
         *
         * 新增一类闹钟只需在此加一个枚举值 + 在 [registerOne] 加一条分支，
         * `sync()` 的对账逻辑（冲突检测 / 陈旧取消 / `cancel→set` / 无效报告）完全复用。
         */
        internal enum class Kind(
            val action: String,
        ) {
            /** `time`：单次，指向"下次该时刻"。 */
            TIME(AlarmActions.TIME) {
                override fun accepts(alarm: ScheduledAlarm): Boolean = alarm is ScheduledAlarm.Time
            },

            /** `interval`：重复，固定间隔（D4 的 `setInexactRepeating`）。 */
            INTERVAL(AlarmActions.INTERVAL) {
                override fun accepts(alarm: ScheduledAlarm): Boolean = alarm is ScheduledAlarm.Interval
            },
            ;

            /** 该种类是否接受这条闹钟（`sync` 据此把集合分流到两类）。 */
            abstract fun accepts(alarm: ScheduledAlarm): Boolean
        }

        internal companion object {
            const val TAG: String = "RootFlow"

            /** `time` 源的稳定 `sourceId`。 */
            const val SOURCE_ID_TIME: String = "alarm_time"

            /** `interval` 源的稳定 `sourceId`。 */
            const val SOURCE_ID_INTERVAL: String = "alarm_interval"
        }

        /** 「一类闹钟」的 `EventSource` 视图（`time` / `interval` 共用本实现）。 */
        private class Source(
            private val id: String,
            private val kind: Kind,
        ) : EventSource {
            private var started: Boolean = false

            override val sourceId: String = id

            /** 按闹钟类别声明：`time` 实例产出 `time`，`interval` 实例产出 `interval`。 */
            override val providesEvents: Set<String> =
                when (kind) {
                    Kind.TIME -> setOf(SystemEvent.TIME)
                    Kind.INTERVAL -> setOf(SystemEvent.INTERVAL)
                }

            override fun start() {
                if (started) return
                started = true
                // 注册数据来自 Room（属 3d），因此这里**不**自动注册：
                // 3d 拿到触发器后调 `sync(...)`。本阶段只置状态，避免凭空注册闹钟。
                Log.i(TAG, "ALARM_SOURCE_STARTED id=$id action=${kind.action}")
            }

            override fun stop() {
                if (!started) return
                started = false
                Log.i(TAG, "ALARM_SOURCE_STOPPED id=$id")
            }

            override fun status(): EventSourceStatus =
                EventSourceStatus(
                    sourceId = id,
                    state = if (started) EventSourceState.Running else EventSourceState.NotStarted,
                )

            private companion object {
                const val TAG: String = "RootFlow"
            }
        }
    }
