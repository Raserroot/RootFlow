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

    /** 看门狗发现服务不在、且**系统拒绝了后台重启**时的标题（阶段 12c）。 */
    const val TITLE_SERVICE_DOWN: String = "RootFlow 服务已停止"

    /**
     * 服务未能自动恢复时的正文（阶段 12c）。
     *
     * ## 为什么必须如实说"系统拒绝"而不是"正在恢复"
     * Android 14+ 起，**非精确闹钟**在后台拉起前台服务会被系统拒绝
     * （`ForegroundServiceStartNotAllowedException`，见 `AndroidKeepAliveWaker` 的 KDoc）。
     * 那条路径上"自动恢复"根本没发生 —— 若正文写成"正在恢复中"，用户会以为不用管，
     * 而实际上脚本与事件监听**一直没在跑**。本仓库的文案纪律是"不得谎报已恢复"
     * （与 [NOTIFICATIONS_BLOCKED_SUFFIX] 同一条）。
     *
     * ## 为什么不写成"点此恢复"这种祈使句
     * 通知**已经**带 `openAppIntent()`（点击即打开 App），用户点一下就是最自然的恢复动作；
     * 正文只说"打开 App 可恢复"，把动作留给通知本身的交互。
     */
    const val SERVICE_DOWN_TEXT: String = "系统拒绝了后台自动恢复，打开 App 即可重新启动监听"

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

    /**
     * 服务掉线的**提醒**通知模型（阶段 12c，看门狗的降级路径）。
     *
     * ## 为什么复用常驻渠道与常驻 id，而不是新开一条通知类型
     * `ServiceNotifier.update(model)` 走的是常驻通知 id（`ServiceNotificationIds.FOREGROUND`）
     * ⇒ **服务一旦恢复，下一次 `update()` 就会把这条覆盖掉**，不需要任何"撤销"逻辑。
     * 若给它单独一个 id，就得再加一处"什么时候撤"的判定，而漏判的表现是
     * "服务早就好了，通知栏还写着已停止"（本仓库反复禁止的形态）。
     *
     * 代价（如实登记）：常驻渠道是 `IMPORTANCE_LOW` 且静默 ⇒ 这条提醒**不会响**，
     * 只在通知栏里挂着。对"服务掉线"这个级别的事件这个强度是合适的 ——
     * 它同时是**不可滑除**的（`ongoing = true`），用户下拉通知栏一定会看到。
     *
     * @param notificationsGranted `POST_NOTIFICATIONS` 是否已授予；未授予时正文要如实说明
     *   "你看不到这条通知"（否则这就成了一条**发不出去且无人知道**的提醒）
     */
    fun serviceDown(notificationsGranted: Boolean = true): ServiceNotificationModel =
        ServiceNotificationModel(
            channelId = ServiceChannels.FOREGROUND,
            title = TITLE_SERVICE_DOWN,
            text = if (notificationsGranted) SERVICE_DOWN_TEXT else SERVICE_DOWN_TEXT + NOTIFICATIONS_BLOCKED_SUFFIX,
            alert = false,
        )
}
