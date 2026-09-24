package com.rootflow.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.data.event.EventBusHolder
import com.rootflow.domain.model.SystemEvent

/**
 * 电源插拔广播接收器（需求 §2.1 的 `power_connected` / `power_disconnected`）。
 *
 * ## 与 `BootEventReceiver` 完全同构（三条纪律）
 * 1. **不引用 `Intent.ACTION_*` 静态字段**（纯 JVM 下 Android 静态字段不可用、MockK 也无法
 *    stub 静态字段），改用自有常量
 * 2. `onReceive` 只取 action **字符串**，全部判定在 [handleAction]
 * 3. `EventBusHolder` 为空时**只记警告、不抛异常**（接收器崩溃会被系统记入日志并可能限流）
 *
 * ## 只做一件事：把事件丢进总线
 * `onReceive` 有严格时限（超时 ANR），因此这里**不查数据库、不读文件、不启脚本**。
 * 匹配与投递由 `TriggerDispatcherImpl` 在自己的协程里做。
 *
 * ## 为什么必须打日志
 * 真机验证时若只看到"没有触发"，无法区分是**广播没到**还是**总线/调度器没工作**。
 * `POWER_RECEIVED` 是这条链路的第一个可观测点（配合 3c.1 的 `verifyEventSources` 入口）。
 *
 * ## 清单注册（不是动态注册）
 * `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` 不在 Android 8.0 的隐式广播禁令内，
 * 因此由 `AndroidManifest.xml` 静态声明，系统在事件发生时自行实例化本类——
 * 也正因如此，它**无法构造注入** `EventBus`，必须走 [EventBusHolder]。
 */
class PowerEventReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        handleAction(intent.action)
    }

    /**
     * 处理一个 action 字符串（[onReceive] 的实际逻辑，也是单测入口）。
     *
     * @param action 广播 action；`null` 视为异常输入
     */
    internal fun handleAction(action: String?) {
        Log.i(TAG, "POWER_RECEIVED action=${action ?: "<null>"}")

        val event =
            when (action) {
                ACTION_POWER_CONNECTED -> SystemEvent.PowerConnected
                ACTION_POWER_DISCONNECTED -> SystemEvent.PowerDisconnected
                else -> {
                    // 清单只注册了两个 action；出现其它 action 说明清单/调用方与预期不符，
                    // 记下来而不是静默忽略。
                    Log.w(TAG, "POWER_RECEIVED unexpected action=${action ?: "<null>"}, ignored")
                    return
                }
            }

        val bus = EventBusHolder.get()
        if (bus == null) {
            Log.w(TAG, "POWER_RECEIVED but EventBusHolder is empty; ${event.eventId} dropped")
            return
        }

        bus.send(event)
        Log.i(TAG, "EVENT_BUS sent=${event.eventId}")
    }

    internal companion object {
        const val TAG = "RootFlow"

        /** `ACTION_POWER_CONNECTED` 的字面量副本（理由见类 KDoc 纪律 1）。 */
        const val ACTION_POWER_CONNECTED: String = "android.intent.action.ACTION_POWER_CONNECTED"

        /** `ACTION_POWER_DISCONNECTED` 的字面量副本。 */
        const val ACTION_POWER_DISCONNECTED: String = "android.intent.action.ACTION_POWER_DISCONNECTED"
    }
}
