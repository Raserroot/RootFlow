package com.rootflow.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.data.event.EventBusHolder
import com.rootflow.domain.model.SystemEvent

/**
 * 闹钟触发接收器（阶段 3c.2 的 `time` / `interval` 事件源）。
 *
 * ## 清单注册
 * 两个 action 由 `AndroidManifest.xml` 静态声明（见 [AlarmActions]），
 * 因此系统在闹钟到点时**直接实例化本接收器**——即使 App 进程不在前台。
 * 也正因如此，它**无法构造注入** `EventBus`，必须走 [EventBusHolder]。
 *
 * ## 只做一件事：把事件丢进总线
 * `onReceive` 有严格时限（超时 ANR），因此这里**不查数据库、不读文件、不启脚本**。
 * 匹配（查哪些触发器订阅了该事件）与投递由 `TriggerDispatcherImpl` 在自己的协程里做。
 *
 * ## 与 `BootEventReceiver` 完全同构（三条纪律）
 * 1. **不引用 `Intent.ACTION_*` 静态字段**（纯 JVM 下 Android 静态字段不可用、
 *    MockK 也无法 stub 静态字段），改用 [AlarmActions] 的常量
 * 2. `onReceive` 只取 action **字符串**，全部判定在 [handleAction]
 * 3. `EventBusHolder` 为空时**只记警告、不抛异常**（接收器崩溃会被系统记入日志并可能限流）
 *
 * ## 已知限制：闹钟不携带触发器上下文
 * `PendingIntent` 只带 action，因此本接收器发的是**无参事件**（[SystemEvent.Time] /
 * [SystemEvent.Interval]）。"哪条触发器该跑"由 `TriggerDispatcher` 按
 * `triggers.event_type` 匹配得出（需求 §2.3：唯一真相源 = Room）。
 * `TriggerRow.params`（如 `payload`）的注入属 **3d** 的 `ScriptRunner` 范围。
 */
class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        handleAction(intent.action)
    }

    /**
     * 处理一个 action 字符串（[onReceive] 的实际逻辑，也是单测入口）。
     *
     * @param action 闹钟 action；`null` 视为异常输入
     */
    internal fun handleAction(action: String?) {
        Log.i(TAG, "ALARM_RECEIVED action=${action ?: "<null>"}")

        val event =
            when (action) {
                AlarmActions.TIME -> SystemEvent.Time
                AlarmActions.INTERVAL -> SystemEvent.Interval
                else -> {
                    // 清单只注册了两个 action；出现其它 action 说明清单/写入侧与预期不符，
                    // 记下来而不是静默忽略。
                    Log.w(TAG, "ALARM_RECEIVED unexpected action=${action ?: "<null>"}, ignored")
                    return
                }
            }

        val bus = EventBusHolder.get()
        if (bus == null) {
            Log.w(TAG, "ALARM_RECEIVED but EventBusHolder is empty; ${event.eventId} dropped")
            return
        }

        bus.send(event)
        Log.i(TAG, "EVENT_BUS sent=${event.eventId}")
    }

    internal companion object {
        const val TAG: String = "RootFlow"
    }
}
