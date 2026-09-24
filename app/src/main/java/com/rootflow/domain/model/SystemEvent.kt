package com.rootflow.domain.model

/**
 * 系统事件（需求 §2.1 的 12 个事件类型 + 本项目追加的 [SystemEvent.BatteryOkay]，共 13 个）。
 *
 * ## 为什么是密封类层次而不是枚举
 * 部分事件天然带参（`app_foreground` 需要包名、`wifi_changed` 需要连接状态），
 * 枚举 + 旁挂字段会退化成"永远有一半字段是 null"。
 *
 * ## `eventId` 必须是稳定字符串
 * 它会被写进 `triggers.event_type`（需求 §2.3），因此**不能**用枚举序号（重排即错位），
 * **也不能**用 `toString()`（类名重构即漂移）。每个取值的字面量由
 * `TriggerEventKeys` 双向映射，并有单测逐值钉死。
 *
 * ## 参数归属（重要）
 * [Time] / [Interval] **不带**时刻与间隔：它们来自 `TriggerRow.params`（需求 §2.3），
 * 调度器按 [eventId] 匹配即可。事件里再带一份会形成"两处真相"。
 * 真正需要随事件下发的运行时信息（如前台包名）才放在事件里，并经 [payloadJson] 暴露给脚本。
 */
sealed interface SystemEvent {
    /** 稳定字符串键，对应需求 §2.1 表格的「事件 ID」列。 */
    val eventId: String

    /**
     * 注入给脚本的事件负载（需求 §3.2 的 `ROOTFLOW_EVENT_PAYLOAD`）。
     *
     * **阶段 3b 只有 [Boot]，恒返回 `null`**；其余事件的具体负载在 **3c** 随各事件源一起补，
     * 避免 3b 先写一堆当前用不到的序列化。
     */
    fun payloadJson(): String? = null

    /** 开机完成（需求 §2.1：静态 `BroadcastReceiver` 监听 `ACTION_BOOT_COMPLETED`）。 */
    data object Boot : SystemEvent {
        override val eventId: String = BOOT
    }

    /** 亮屏（需求 §2.1：只能动态注册，须驻留前台服务）。 */
    data object ScreenOn : SystemEvent {
        override val eventId: String = SCREEN_ON
    }

    /** 熄屏（同上）。 */
    data object ScreenOff : SystemEvent {
        override val eventId: String = SCREEN_OFF
    }

    /** 解锁（`ACTION_USER_PRESENT`）。 */
    data object Unlock : SystemEvent {
        override val eventId: String = UNLOCK
    }

    /** 前台应用变更。 */
    data class AppForeground(
        val packageName: String,
    ) : SystemEvent {
        override val eventId: String = APP_FOREGROUND
    }

    /** 应用退到后台。 */
    data class AppBackground(
        val packageName: String,
    ) : SystemEvent {
        override val eventId: String = APP_BACKGROUND
    }

    /** 定时任务触发（cron 风格；具体时刻由 `TriggerRow.params` 给出）。 */
    data object Time : SystemEvent {
        override val eventId: String = TIME
    }

    /** 固定间隔触发（具体间隔由 `TriggerRow.params` 给出）。 */
    data object Interval : SystemEvent {
        override val eventId: String = INTERVAL
    }

    /** 低电量。 */
    data object BatteryLow : SystemEvent {
        override val eventId: String = BATTERY_LOW
    }

    /**
     * 电量恢复正常（`ACTION_BATTERY_OKAY`）。
     *
     * **需求 §2.1 未列出此事件，但必须建**（`PROJECT_STATE.md` 决策 3）：
     * 缺了恢复边，按 [BatteryLow] 做过省电动作的用户脚本**永远回不去**
     * （低电量状态无法复位）。除 `eventId` 外与需求事件完全同构。
     */
    data object BatteryOkay : SystemEvent {
        override val eventId: String = BATTERY_OKAY
    }

    /** 接入电源。 */
    data object PowerConnected : SystemEvent {
        override val eventId: String = POWER_CONNECTED
    }

    /** 断开电源。 */
    data object PowerDisconnected : SystemEvent {
        override val eventId: String = POWER_DISCONNECTED
    }

    /**
     * Wi-Fi 连接状态变化。
     */
    data class WifiChanged(
        val connected: Boolean,
    ) : SystemEvent {
        override val eventId: String = WIFI_CHANGED
    }

    /**
     * 「一直运行」：**没有外部事件**，脚本被前台服务当作常驻进程监管。
     *
     * ## 它不是"事件"，但走事件的通道（**有意的**）
     * 触发器的本质是"某事件 → 某脚本"的一行数据（`enabledForEvent(eventId)`）。
     * 把「一直运行」做成一个**无参数的稳定事件键**，就能复用
     * 既有的勾选 / 落库 / 计数 / 状态展示全链路，**不必新建事件源**。
     *
     * ## 它**不**参与"事件源 N / 7 可用"
     * 主页与设置页的"N / 7"统计的是 `EventSourceRegistry` 里的 7 个**真实事件源**
     * （alarm_interval / alarm_time / battery / power / screen / usage_stats / wifi）。
     * 本键没有对应的源，因此 `EVENT_SOURCE_TOTAL` **保持 7 不变**。
     *
     * ## 语义由 [com.rootflow.domain.event.DaemonSupervisor] 承担
     * 常驻脚本随**前台服务**起停：服务 `register()` 时启动、`unregister()` 时停止、
     * 熔断时中止。退出后被拉起，重启节奏见 `DaemonRestartPolicy`。
     */
    data object AlwaysRun : SystemEvent {
        override val eventId: String = ALWAYS_RUN
    }

    companion object {
        /** `eventId` 字面量集中定义；`TriggerEventKeys` 与单测都引用这里，避免字面量散落。 */
        const val BOOT: String = "boot"
        const val SCREEN_ON: String = "screen_on"
        const val SCREEN_OFF: String = "screen_off"
        const val UNLOCK: String = "unlock"
        const val APP_FOREGROUND: String = "app_foreground"
        const val APP_BACKGROUND: String = "app_background"
        const val TIME: String = "time"
        const val INTERVAL: String = "interval"
        const val BATTERY_LOW: String = "battery_low"

        /**
         * `battery_okay` 的稳定键。
         *
         * 需求 §2.1 的表格里**没有**这一行（需求只有 12 个事件），
         * 它是本项目按决策 3 追加的第 13 个事件，理由见 [BatteryOkay]。
         */
        const val BATTERY_OKAY: String = "battery_okay"
        const val POWER_CONNECTED: String = "power_connected"
        const val POWER_DISCONNECTED: String = "power_disconnected"
        const val WIFI_CHANGED: String = "wifi_changed"

        /**
         * `always_run` 的稳定键（本项目追加的第 14 个键）。
         *
         * 键名刻意**不用** `*_event` 风格：它不是外部事件，而是"常驻"这一模式。
         * 但仍必须是**稳定字符串** —— 它会被写进 `triggers.event_type`。
         */
        const val ALWAYS_RUN: String = "always_run"
    }
}
