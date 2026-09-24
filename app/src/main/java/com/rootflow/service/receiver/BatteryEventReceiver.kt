package com.rootflow.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.data.event.EventBusHolder
import com.rootflow.domain.model.SystemEvent

/**
 * 电量广播接收器（需求 §2.1 的 `battery_low` + **决策 3 的 `battery_okay`**）。
 *
 * ## 为什么有两条 action
 * 需求 §2.1 只列了 `battery_low`。**缺了恢复边会让"低电量"状态无法复位**：
 * 用户脚本若按 `battery_low` 做了省电动作（关同步、降亮度、停后台任务），
 * 没有 `battery_okay` 就永远回不去（`PROJECT_STATE.md` 决策 3）。
 * 因此 `ACTION_BATTERY_OKAY` → `SystemEvent.BatteryOkay` 与本接收器一起建。
 *
 * ## 与 `BootEventReceiver` 完全同构（三条纪律）
 * 见 [PowerEventReceiver] 的类文档（不引用 `Intent.ACTION_*` 静态字段 / 只取 action 字符串 /
 * 总线为空只警告不抛）。
 *
 * ## 一次系统事件 → 一次总线投递
 * 本接收器**不维护"当前是否低电量"状态**：`battery_low` 与 `battery_okay` 由系统成对发出，
 * 直接转发即可。若在本地维护状态，就产生了"App 侧状态与系统状态不一致"的第二处真相
 * （例如 App 在低电量期间被杀死后重启，本地状态就无从恢复）。
 * 重复触发的抑制属 `TriggerDispatcher` 的防抖窗口（需求 §2.2 的 500ms）。
 */
class BatteryEventReceiver : BroadcastReceiver() {
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
        Log.i(TAG, "BATTERY_RECEIVED action=${action ?: "<null>"}")

        val event =
            when (action) {
                ACTION_BATTERY_LOW -> SystemEvent.BatteryLow
                ACTION_BATTERY_OKAY -> SystemEvent.BatteryOkay
                else -> {
                    Log.w(TAG, "BATTERY_RECEIVED unexpected action=${action ?: "<null>"}, ignored")
                    return
                }
            }

        val bus = EventBusHolder.get()
        if (bus == null) {
            Log.w(TAG, "BATTERY_RECEIVED but EventBusHolder is empty; ${event.eventId} dropped")
            return
        }

        bus.send(event)
        Log.i(TAG, "EVENT_BUS sent=${event.eventId}")
    }

    internal companion object {
        const val TAG = "RootFlow"

        /** `ACTION_BATTERY_LOW` 的字面量副本（理由见类 KDoc 纪律 1）。 */
        const val ACTION_BATTERY_LOW: String = "android.intent.action.BATTERY_LOW"

        /** `ACTION_BATTERY_OKAY` 的字面量副本（决策 3 的恢复边）。 */
        const val ACTION_BATTERY_OKAY: String = "android.intent.action.BATTERY_OKAY"
    }
}
