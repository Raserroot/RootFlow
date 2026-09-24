package com.rootflow.domain.service

import com.rootflow.domain.event.TripReason

/**
 * 通知文案的**唯一产出点**（阶段 5，纯函数）。
 *
 * ## 为什么把文案也放进 domain
 * 通知文案是**用户唯一可见的服务状态**，也是真机验证的判据之一
 * （`NOTIF_POSTED … channel=… alert=…`）。把它写在 `Service` 或 `NotificationCompat.Builder`
 * 旁边，等于让"安全模式该显示什么"退化成只有肉眼能验；放在这里则可被穷举单测，
 * Android 侧只剩"把字符串塞进 Builder"。
 *
 * ## 三条文案纪律
 * 1. **安全模式必须给出成因**：`TripReason.detail` 是给人看的（含阈值与实测值），
 *    `reasonKey` 是给机器看的。告警通知两者都要有（正文给人，日志给机器），
 *    否则用户看到"已熔断"却查不出为什么——这正是阶段 4 修复 #3（成因跨重启丢失）的同一诉求
 * 2. **成因不可还原时如实说"未知"**：`TripReason.fromKey` 对带参成因刻意返回 `null`
 *    （不为带参成因伪造参数）。此处**不得**用 `reasonKey` 兜底去编一个原因，
 *    而要显式显示"未知（flag 存在即安全模式）"
 * 3. **通知被拒时不得谎报"运行正常"**：API 33+ 下拒绝 `POST_NOTIFICATIONS`
 *    **不会**让 `startForeground` 抛异常（服务仍是前台服务，只是通知不显示），
 *    此时用户看不到任何东西 ⇒ 正文必须显式说明"通知未显示"，见 [serviceNotificationText]
 */
object ServiceNotificationText {
    /**
     * 常驻通知的标题（同时用于 `SAFEMODE_NOTIFY_*` 之外的判读）。
     *
     * 注意：安全模式态**复用同一个标题**，只有正文变化 —— 通知 id 与标题稳定，
     * 用户才能认出"还是那一条常驻通知"，而不是被一堆新通知刷屏。
     */
    const val TITLE_SERVICE: String = "RootFlow 运行中"

    /** 安全模式下的常驻通知标题（与 [TITLE_SERVICE] 区分，让用户一眼看出状态变了）。 */
    const val TITLE_SAFE_MODE: String = "RootFlow 已熔断"

    /** 安全模式告警通知的标题（需求 §5.2 第 5 步：高优先级）。 */
    const val TITLE_ALERT: String = "RootFlow 已进入安全模式"

    /** 成因不可还原时的正文（对应 `TripReason.fromKey` 返回 `null` 的既有语义）。 */
    const val REASON_UNKNOWN: String = "未知（flag 存在即安全模式）"

    /** 通知权限被拒时的正文后缀（不得谎报"监听正常"）。 */
    const val NOTIFICATIONS_BLOCKED_SUFFIX: String = " · 通知被系统拒绝，服务仍在运行"

    /**
     * 常驻通知的正文。
     *
     * @param state 当前前台服务状态
     * @param reason 熔断成因；`null` 表示"在安全模式但成因不可还原"
     * @param notificationsGranted `POST_NOTIFICATIONS` 是否已授予
     *   （API 33 以下或未上报时传 `true`——**不确定时不报降级**，避免制造假警报）
     */
    fun serviceNotificationText(
        state: ForegroundState,
        reason: TripReason?,
        notificationsGranted: Boolean,
    ): String {
        if (state !is ForegroundState.Running) {
            return "服务未运行"
        }
        val base =
            if (state.safeMode) {
                "原因：" + (reason?.detail ?: REASON_UNKNOWN) + " · 脚本与触发器已停止"
            } else {
                "事件源 ${state.enabled}/${state.total} · 触发监听正常"
            }
        return if (notificationsGranted) base else base + NOTIFICATIONS_BLOCKED_SUFFIX
    }

    /**
     * 构造常驻通知模型。
     *
     * @param state 当前前台服务状态
     * @param reason 熔断成因（可为 `null`，见 [REASON_UNKNOWN]）
     * @param notificationsGranted `POST_NOTIFICATIONS` 是否已授予
     */
    fun serviceNotification(
        state: ForegroundState,
        reason: TripReason?,
        notificationsGranted: Boolean = true,
    ): ServiceNotificationModel {
        val safeMode = state is ForegroundState.Running && state.safeMode
        return ServiceNotificationModel(
            channelId = if (safeMode) ServiceChannels.SAFE_MODE else ServiceChannels.FOREGROUND,
            title = if (safeMode) TITLE_SAFE_MODE else TITLE_SERVICE,
            text = serviceNotificationText(state = state, reason = reason, notificationsGranted = notificationsGranted),
            alert = safeMode,
        )
    }

    /**
     * 构造安全模式**告警**通知模型（需求 §5.2 第 5 步）。
     *
     * 与 [serviceNotification] 的差别：它**总是**走高优先级渠道且 `alert = true`，
     * 与常驻通知使用不同通知 id ⇒ 两者并存、互不覆盖（见 [ServiceChannels]）。
     */
    fun safeModeAlert(reason: TripReason): ServiceNotificationModel =
        ServiceNotificationModel(
            channelId = ServiceChannels.SAFE_MODE,
            title = TITLE_ALERT,
            text = "原因：" + reason.detail + "（" + reason.reasonKey + "）",
            alert = true,
        )

    /** 真机判读用的单行摘要（日志与通知共用同一份取值逻辑，避免两处漂移）。 */
    fun describe(model: ServiceNotificationModel): String =
        "channel=${model.channelId} alert=${model.alert} title=[${model.title}]"
}
