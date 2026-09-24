package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.ScheduledAlarm
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.repository.TriggerRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 闹钟注册的数据来源（阶段 3c.2 引入，**3d 改为真实实现**）。
 *
 * ## 为什么是端口而不是直接查 Room
 * `AlarmEventSource.sync()` 需要的是"本轮应有的全部闹钟"。若它直接依赖 `TriggerRepository`，
 * 3c.2 的单测就得伪造整个 Room 层（`FakeDatabase` + 手写 DAO，3a 的遗留项之一）。
 * 把"取数据"抽成一个只返回 [ScheduledAlarm] 的端口后：
 * - 3c.2 的 `sync()` 逻辑可在**纯 JVM** 下用假件完整覆盖
 * - 3d 只需提供真实实现，`AlarmEventSource` 的注册逻辑一行不改
 *
 * ## 3d 的选择：**道路 1**（改为 `suspend`）
 * 本端口 3c.2 时是同步的，KDoc 预留了二选一。3d **取道路 1**：
 * `TriggerRepository.enabledForEvent` 本身就是 `suspend`，若坚持同步形态，
 * 调用方就得在别处 `runBlocking`（阻塞线程）或提前取好数据再喂进来（多一处状态传递）。
 * 改为 `suspend` 后，"取数 → 对账 → 注册"成为**一次可取消的连续动作**，
 * 代价是 `AlarmEventSource.sync` 一并变 `suspend`（已完成，其单测在 `runTest` 里直接调用）。
 */
interface TriggerScheduleProvider {
    /** 本轮**应有**的全部闹钟（`time` + `interval`）。 */
    suspend fun currentAlarms(): List<ScheduledAlarm>
}

/**
 * 真实实现（阶段 3d）：从 `TriggerRepository` 取两类闹钟事件的全量启用触发器。
 *
 * ## 为什么查两次而不是查全表
 * `TriggerRepository` 的既有端口只有 `enabledForEvent(eventType)`（热路径，走
 * `(event_type, enabled)` 复合索引）。为"取全部闹钟"新增一个全表查询要动 3a 的端口，
 * 而 3d 方案明确"本阶段不引入 Room 的全局触发器 Flow"。两次点查与一次全表扫在
 * 触发器量级（几条到几十条）上没有可观测差异。
 *
 * ## `params` 不足的触发器**逐条记日志**（3d 方案 §3）
 * `ScheduledAlarm.from` 返回 `null` 表示 `params` 里缺 `hourOfDay`/`minuteOfHour`
 * （`time`）或 `intervalMinutes`（`interval`）。这类触发器**永远不会触发**，
 * 是最难排查的一类缺陷——必须逐条可见，**不得**静默跳过。
 */
@Singleton
class RoomTriggerScheduleProvider
    @Inject
    constructor(
        private val triggerRepository: TriggerRepository,
    ) : TriggerScheduleProvider {
        override suspend fun currentAlarms(): List<ScheduledAlarm> {
            val triggers =
                triggerRepository.forEvent(SystemEvent.TIME) +
                    triggerRepository.forEvent(SystemEvent.INTERVAL)

            val alarms = mutableListOf<ScheduledAlarm>()
            triggers.forEach { trigger ->
                val alarm = ScheduledAlarm.from(trigger)
                if (alarm == null) {
                    Log.w(
                        TAG,
                        "ALARM_SKIPPED_INVALID trigger=${trigger.id} script=${trigger.scriptId} " +
                            "event=${trigger.eventType} reason=params insufficient " +
                            "(time needs hourOfDay+minuteOfHour; interval needs intervalMinutes)",
                    )
                } else {
                    alarms += alarm
                }
            }
            Log.i(TAG, "ALARM_PLAN_LOADED triggers=${triggers.size} alarms=${alarms.size}")
            return alarms
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
