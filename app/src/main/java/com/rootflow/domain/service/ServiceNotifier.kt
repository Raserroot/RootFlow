package com.rootflow.domain.service

import com.rootflow.domain.event.TripReason

/**
 * 前台服务通知的投递端口（阶段 5）。
 *
 * ## 为什么是端口而不是直接注入 Android 的 `NotificationManager`
 * 1. `ForegroundServiceController` 要保持**纯 JVM 可测**（不被 `Service` 拖进不可测区）
 * 2. 判定逻辑与投递动作分离，使"该显示什么"可被穷举单测，Android 侧只剩翻译
 *
 * ## 实现契约
 * - [ensureChannels] / [update] / [alert] / [cancelAlert] 都**不得抛异常**：
 *   通知失败（渠道被用户关闭、`POST_NOTIFICATIONS` 被拒）**绝不能让服务起不来**，
 *   脚本宿主的第一职责是把服务跑起来
 * - [update] **幂等**：内容未变时不得重复 post（重复 post 只在日志与通知栏里制造噪声）
 * - [alert] 是**一次性告警**，[cancelAlert] 撤销它；两者与 [update] 走**不同通知 id**，
 *   互不覆盖（见 [ServiceChannels] 的渠道拆分理由）
 */
interface ServiceNotifier {
    /** 建立通知渠道（幂等；API 26+ 必须，minSdk=26 故无档位分支）。 */
    fun ensureChannels()

    /** 刷新**常驻通知**（服务存活指标）。 */
    fun update(model: ServiceNotificationModel)

    /** 投出**安全模式告警**（需求 §5.2 第 5 步：高优先级）。 */
    fun alert(model: ServiceNotificationModel)

    /** 撤销安全模式告警（恢复后调用；需求 §5.3 第 1 条）。 */
    fun cancelAlert()

    /**
     * 通知当前是否**真的能显示**（阶段 5）。
     *
     * ## 为什么它属于本端口而不是控制器的职责
     * 判定"通知能不能显示"要问 `NotificationManagerCompat`，那是 Android 调用；
     * 控制器只该消费这个结论来**如实降级文案**。放进端口后，控制器完全不必碰 Android，
     * 单测里也可以用假件直接构造"权限被拒"的场景。
     *
     * ## ★ 它的返回值**不参与任何控制流**
     * API 33+ 下拒绝 `POST_NOTIFICATIONS` **不会**让 `startForeground` 抛异常
     * （服务仍是前台服务，只是通知不显示）。因此本方法只影响**文案**，
     * 绝不能拿来决定"要不要前台化"或"要不要启动事件源"。
     *
     * 默认实现返回 `true`：**不确定时不报降级**——平白多一句"通知被拒"会训练用户忽略警告。
     */
    fun notificationsVisible(): Boolean = true
}

/**
 * 熔断告警的**反向端口**（阶段 5，需求 §5.2 第 4/5 步）。
 *
 * ## ★ 它存在的唯一理由：**打断一条会形成 Hilt 环的依赖**
 * 阶段 5 的两个事实放在一起就会成环：
 *
 * | 事实 | 来源 |
 * |---|---|
 * | 熔断器需要发告警通知 | 需求 §5.2 第 4/5 步；`CircuitBreakerImpl` 构造注入 `SafeModeNotifier` |
 * | 前台服务控制器要**订阅**熔断状态来切换常驻通知 | 需求 §5.4「通知常驻」+ 本阶段方案 §5.3 |
 *
 * 于是自然的写法 `CircuitBreakerImpl → SafeModeNotifier → ForegroundServiceController
 * → CircuitBreaker` 是**一个环**，Hilt 在 `hiltJavaCompileDebug` 阶段会直接报
 * `Found a dependency cycle`（阶段 4 的 `BootloopGuard` 已经踩过同一坑，
 * 当时的解法是 `onTrip = null` 由 `RootFlowApp` 驱动）。
 *
 * ## 解法：把"发布告警"抽成本端口，由**控制器实现**，方向反过来
 * ```
 * CircuitBreakerImpl ──> SafeModeNotifier (data) ──> SafeModeAlertSink   ← 本端口
 *                                                          ▲
 * ForegroundServiceController ────────────────────────────┘（实现）
 * ForegroundServiceController ──> CircuitBreaker（订阅 safeMode / tripReason）
 * ```
 * ⇒ 依赖方向**单向**，图无环。**这正是阶段 4 交付记录里
 * "`NoopSafeModeNotifier` 阶段 5 替换为真实通知时不要接成环"所要求的结构。**
 *
 * ## 为什么不用"通知实现里直接订阅 `CircuitBreaker.safeMode`"绕开
 * 那样通知侧与控制器会**各订阅一次**同一状态，出现两个真相源：controller 按
 * `safeMode` 刷常驻通知、notifier 按同一个流发告警 —— 恢复时两者的时序无法保证，
 * 会出现"告警已撤销但常驻通知还写着熔断"或反之的中间态。
 * 由**同一个控制器**统一投影（[ForegroundState]）才能保证两条通知一致。
 *
 * ## 与 `SafeModeNotifier` 的分工（勿混）
 * | 端口 | 谁调用 | 语义 |
 * |---|---|---|
 * | `SafeModeNotifier`（阶段 4 端口） | `CircuitBreakerImpl` 的六步动作 | "**熔断发生了**"（同步、必达） |
 * | [SafeModeAlertSink]（本端口） | `SafeModeNotifier` 的实现 | "**把这条告警投出去**"（可能无人接收） |
 *
 * 二者都不得抛异常：告警发不出去是错误的严重降级，但**绝不能**让熔断的其余五步不执行。
 */
interface SafeModeAlertSink {
    /** 熔断告警。实现方应构造 [ServiceNotificationModel] 并调用 [ServiceNotifier.alert]。 */
    fun onSafeModeAlert(reason: TripReason)

    /** 恢复（需求 §5.3 第 1 条）：撤销告警，常驻通知回到运行中态。 */
    fun onSafeModeRestore()
}

/**
 * 保活健康检查（阶段 5 立的契约，**阶段 12c 落地**）。
 *
 * ## ★ 阶段 5 为什么不实现（当时的裁定 ①）与它为什么不成立
 * 需求 §7 原文是「保活：前台服务 + `START_STICKY` + **可选** WorkManager 健康检查 + 电池白名单引导」，
 * 而 `androidx.work` **不在本工作区的离线缓存中**（构建为 `--offline`）：
 * ```
 * caches\modules-2\files-2.1\androidx.work\   → 目录不存在
 * 递归查找 work-runtime*                       → 0 命中
 * ```
 * 当时据此裁定为「**只留端口与设计，不实现**」，并在真机日志里打一行
 * `KEEPALIVE_HEALTH_TODO … implemented=false`。
 *
 * **那个裁定的前提是错的**：本端口的设计表里用的是
 * `AlarmManager` 一次性闹钟自续期（复用既有的 `AlarmFireReceiver` 与一个新的 action 域），
 * **根本不需要 WorkManager**。也就是说这不是"依赖缺失导致的让步"，
 * 而是"有能力落地却没落地"——阶段 5 的收尾记录因此把代价记成了"已知限制"。
 * **阶段 12c 把这一条关闭**（用户明确要求"必须做保活"）。
 *
 * ## 落地形态（= 设计表，逐项未改）
 * | 项 | 值 | 理由 |
 * |---|---|---|
 * | 触发方式 | `AlarmManager` 一次性闹钟，**自续期** | 复用既有闹钟基础设施，**不引新依赖** |
 * | 间隔 | [CHECK_INTERVAL_MILLIS]（15 分钟量级） | 更密无意义（服务被杀不会被"探测"救活），更疏则用户长时间无感 |
 * | 判据 | 服务"心跳"（`ForegroundServiceController` 每次状态变化写入） | 见 `KeepAliveWatchdog` 的 KDoc（判据在落地时被细化，如实登记在那里） |
 * | 时钟 | **必须注入** | 纯 JVM 单测下 `SystemClock` 未实现（`not implemented`），且 `mockkStatic` 会污染 JVM 全局 |
 * | 动作 | 重投常驻通知 + `startForegroundService` | 只做"提醒与自愈"，**不做任何黑产保活** |
 *
 * ## 保留的纪律（**不因落地而放宽**）
 * 不做双进程互拉、1 像素 Activity、无声音乐，也不申请电池白名单的自动放行。
 * 落地后的看门狗动作只有两件：**重投常驻通知**、**必要时 `startForegroundService`**。
 * 首启的知情同意声明（`ui/consent/`）正是这条纪律的配套。
 */
interface KeepAliveHealthCheck {
    /**
     * 下一次健康检查应有的时刻（与 `nowMillis` **同一时钟**）。
     *
     * 默认实现即"从当前时刻起算一个间隔"，自续期由看门狗在每次检查处理完之后调用。
     *
     * @param nowMillis 当前时刻（单调时钟读数；见接口 KDoc 的"时钟"一行）
     */
    fun nextCheckAt(nowMillis: Long): Long = nowMillis + CHECK_INTERVAL_MILLIS

    companion object {
        /** 健康检查间隔（15 分钟；见接口 KDoc 的设计表）。 */
        const val CHECK_INTERVAL_MILLIS: Long = 15 * 60 * 1000L

        /**
         * 判定输入不足时的**补偿间隔**（30 秒）。
         *
         * ## 什么时候用
         * 心跳广播可能**冷启动进程**，而安全模式的恢复发生在 `Application.onCreate`
         * 的异步启动链里。此时 `safeMode` 是"还不知道"（三态的第三档）⇒ 判定为
         * [`KeepAliveAction.DEFER`][com.rootflow.domain.service.KeepAliveAction.DEFER]，
         * 30 秒后重排一次再判。**代价是"这次不拉"而不是"不拉了"**。
         *
         * ## 为什么不用"未知就当不在安全模式"
         * 那正是本阶段 ① 要钉死的路：熔断期间看门狗把服务拉起来 ⇒ 常驻监管与事件源复活。
         */
        const val DEFER_INTERVAL_MILLIS: Long = 30 * 1000L

        /**
         * 真机判读用的显式标记：证明健康检查**已经落地并在跑**。
         *
         * 与阶段 5 的 `KEEPALIVE_HEALTH_TODO`（"设计已留、实现未做"）互为对照 ——
         * 那个常量随本期落地**一并删除**（留一个永不再出现的标记是死代码）。
         * 真机判据：`register` 之后应且只应出现一次
         * `KEEPALIVE_HEALTH_ARMED … implemented=true`。
         */
        const val HEALTH_CHECK_ARMED_MARKER: String = "KEEPALIVE_HEALTH_ARMED"
    }
}
