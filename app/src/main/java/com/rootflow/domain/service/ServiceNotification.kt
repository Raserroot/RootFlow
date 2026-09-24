package com.rootflow.domain.service

/**
 * 前台服务的运行状态（阶段 5）。
 *
 * ## 为什么它必须能被纯 JVM 构造
 * 通知文案是**真机上唯一用户可见的服务状态**，而本项目的单测基线是纯 JVM（不引 Robolectric）。
 * 若把"当前该显示什么"写在 `Service` 里，这条判定就只能靠真机肉眼验证
 * —— 与决策 9（`SettingsTarget` 不返回 `Intent`）同一取舍：
 * **把判定挪到 domain 的纯函数里，Android 侧只做投影**。
 *
 * ## 与 `CircuitBreaker.safeMode` 的关系
 * 本类型是**只读投影**，不持有真相：`safeMode` 的真相在 `CircuitBreaker`，
 * `enabledSources` 的真相在 `EventSourceRegistry`。这里只描述"此刻通知该长什么样"。
 */
sealed interface ForegroundState {
    /** 服务尚未注册（`MainActivity` 还没把它拉起来，或已被停止）。 */
    data object Idle : ForegroundState

    /**
     * 服务已注册（`ForegroundServiceController.register()` 之后）。
     *
     * @param enabled 已经真正启动的事件源数（`EventSourceRegistry.status()` 里 `Running` 的条数）
     * @param total 目录里的全部事件源数（含因缺权限未启动的）
     * @param safeMode 当前是否处于安全模式（需求 §5.4：通知常驻且要能看出熔断）
     */
    data class Running(
        val enabled: Int,
        val total: Int,
        val safeMode: Boolean,
    ) : ForegroundState
}

/**
 * 一条通知的内容描述（阶段 5，纯值对象）。
 *
 * ## 为什么不是直接产出 `android.app.Notification`
 * `Notification` 在纯 JVM 下不可构造（`Method ... not mocked`），直接产出它会让
 * "安全模式该显示什么文案""通知被拒时该不该降级"这些**唯一可测的判定**全部变成不可测。
 * 因此 domain 只给内容，`data/service/ServiceNotifierImpl` 负责翻译成 `NotificationCompat`。
 *
 * @param channelId 渠道 id（见 [ServiceChannels]）；**同时决定视觉与优先级**
 * @param title 通知标题
 * @param text 通知正文
 * @param alert `true` = 这是一条一次性的**安全模式告警**（高优先级），
 *   `false` = 这是**常驻通知**（低优先级、无声、不可滑除）
 */
data class ServiceNotificationModel(
    val channelId: String,
    val title: String,
    val text: String,
    val alert: Boolean,
)

/**
 * 通知渠道 id 的**唯一真相源**（阶段 5）。
 *
 * ## 为什么放在 domain
 * 渠道 id 会出现在真机日志（`NOTIF_POSTED channel=…`）与阶段 6 的 UI 设置项里，
 * 是**跨阶段的稳定契约**；而 `NotificationChannel` 本身是 Android 类型。
 * 这里只钉字符串，Android 侧的建立动作在 `data/service/ServiceNotificationChannels`。
 *
 * ## 为什么安全模式要单独一个渠道（而不是复用常驻渠道）
 * 需求 §5.2 第 5 步要求"**高优先级**通知"，而 §5.4 要求"通知**常驻**"——两者诉求相反：
 * 常驻通知必须安静（`IMPORTANCE_LOW`、无声），告警通知必须显眼（`IMPORTANCE_HIGH`、有声）。
 * 同一个渠道**无法同时满足**（渠道的重要性一经创建便由用户与系统共同决定，代码不能事后提高）。
 * 因此拆成两个渠道，各自承担一个诉求，且**互不覆盖**。
 */
object ServiceChannels {
    /** 常驻通知渠道（`IMPORTANCE_LOW`）。 */
    const val FOREGROUND: String = "rootflow_service"

    /** 安全模式告警渠道（`IMPORTANCE_HIGH`）。 */
    const val SAFE_MODE: String = "rootflow_safemode"

    /** 全部渠道（建立与清理都遍历它，避免"加了渠道忘了建"）。 */
    val ALL: List<String> = listOf(FOREGROUND, SAFE_MODE)
}
