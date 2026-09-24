package com.rootflow.domain.event

/**
 * 事件源的运行状态（阶段 3c.1）。
 *
 * ## 为什么是三态而不是布尔
 * 「还没启动」与「启动失败」对阶段 6 的 UI 是**两种不同的提示**：
 * 前者是"尚未开始"，后者要给出"缺哪个权限"并附设置页跳转。
 * 折叠成 `Boolean` 会让这两者不可区分（`PROJECT_STATE.md` 决策 7 明令禁止）。
 */
sealed interface EventSourceState {
    /** 尚未调用 `start()`。 */
    data object NotStarted : EventSourceState

    /** 已启动，正在转发事件。 */
    data object Running : EventSourceState

    /**
     * 无法启动。
     *
     * [reason] **必须具体**，例如 `permission missing: android.permission.ACCESS_NETWORK_STATE`；
     * 不得只写一个笼统的 `false`（决策 7）。
     */
    data class Unavailable(
        val reason: String,
    ) : EventSourceState

    /** 便捷判定；供编排者与 UI 使用。 */
    val isRunning: Boolean
        get() = this is Running
}

/** 单个事件源的状态快照。 */
data class EventSourceStatus(
    val sourceId: String,
    val state: EventSourceState,
)

/**
 * 推送型事件源端口（阶段 3c.1）。
 *
 * ## 两条实现路线
 * - **动态注册**：进程存活期间有效（`screen_on` / `screen_off` / `unlock` / `wifi_changed`）。
 *   需求 §2.1 要求它们"须驻留前台服务"，前台服务属**阶段 5**；阶段 3 阶段它们挂
 *   `Application` 域，**进程被系统回收即失效**（见 `PROJECT_STATE.md` D4）
 * - **清单注册**：由系统在事件发生时实例化接收器（`boot` / `power_*` / `battery_*`）。
 *   这类源的 `start()`/`stop()` 是**无操作**，它们存在只为让 [EventSourceRegistry]
 *   对全部事件一视同仁地上报状态
 *
 * ## 实现契约（实现类必须满足）
 * - `start()` / `stop()` 必须**幂等**：重复调用不得重复注册或重复反注册
 * - `start()` **不得抛异常**（失败必须转成 [EventSourceState.Unavailable]，经 `status()` 暴露）——
 *   一个源失败不能拖垮整个注册表（`SupervisorJob` 式的"单个失败不拖垮全局"）
 * - `status()` 必须是**无副作用**的纯查询
 */
interface EventSource {
    /** 稳定标识，用于 [EventSourceStatus.sourceId] 与真机日志判读。 */
    val sourceId: String

    /**
     * **本源产出的**事件键（`EventCatalog` 的 `eventId`）。
     *
     * ## 它唯一的用途：按需启（P5c / 方案 §7 决策 6 + §1.7）
     * 重构前 registry 无条件启动全部源 —— 7 个源（广播接收器 + 2 秒轮询 + 2 个闹钟）
     * **与有没有脚本订阅无关**，那是纯开销。有了本属性，registry 只在
     * "本源产出的任一事件**有生效订阅**"时才启动它。
     *
     * ## ★ 为什么**不给默认值**
     * 给 `emptySet()` 默认值意味着"忘了声明的源**永远不会被启动**"——
     * 一个**静默失效**（订阅了却收不到事件，日志里一行都没有），正是本仓库反复禁止的形态。
     * 不给默认值 ⇒ 新增源时编译器强制它回答这个问题。
     *
     * ## 为什么由源自己声明（而不是在 registry 里放一张映射表）
     * 映射表会与源**漂移**：源改了产出而表忘改，症状同样是静默失效。
     * 声明在源里，改产出时改的是**同一个类**。
     *
     * ## 边界（照实写，不要多也不要少）
     * - `usage_stats` **订阅** `screen_on`/`screen_off` 只是为了"熄屏暂停轮询"，
     *   它**不产出**这两个事件 ⇒ 不要写进来（写进去会让它被无关的订阅唤醒）
     * - `boot` 与 `always_run` **不属于任何源**：前者走清单接收器 + D9 补发，
     *   后者是监工的触发键 ⇒ 任何源都不该声明它们
     */
    val providesEvents: Set<String>

    /**
     * 本源启动所需的权限（决策 10 的目录项）。
     *
     * [EventSourceRegistry] 在 `start()` 时逐项检查，任一项未 `GRANTED` 即**不放行**该源，
     * 并把该权限字符串写进 [EventSourceState.Unavailable.reason]（决策 7：原因要具体）。
     *
     * 默认空集：多数推送型源（`screen_on`/`screen_off`/`unlock`、电源、电量广播）
     * **不需要任何权限**——它们由系统广播驱动，无需申请。
     */
    val requiredPermissions: Set<AndroidPermission>
        get() = emptySet()

    /** 启动。幂等；不抛异常（失败经 `status()` 暴露）。 */
    fun start()

    /** 停止。幂等；未 [start] 时调用必须安全。 */
    fun stop()

    /** 当前状态快照。 */
    fun status(): EventSourceStatus
}

/**
 * 动态接收器共用的「action → 事件」出口（阶段 3c.1）。
 *
 * ## 为什么不是 `(Intent) -> Unit`
 * 本项目单测是**纯 JVM（无 Robolectric）**，`android.content.Intent` 的构造与
 * `getAction()` 在未 mock 的 JVM 下会抛 `Method ... not mocked`。因此接收器的可测入口
 * **只接收 action 字符串**——与 `BootEventReceiver.handleAction(action: String?)` 同一手法。
 *
 * ## 为什么不是 `EventBus`
 * 动态接收器在**运行期**才确定是否应当转发（例如 [BroadcastRegistration] 已反注册后的
 * 迟到回调）。把"是否转发"的判定留在接收器内、把"发到哪里"抽象成单方法接口，
 * 使接收器可以在**不构造事件总线**的情况下被单测覆盖。
 */
fun interface SourceActionSink {
    /** 收到一个广播 action。 */
    fun onAction(action: String)
}

/**
 * 动态广播注册的极薄抽象（阶段 3c.1）。
 *
 * ## 为什么需要它
 * `Context.registerReceiver` / `unregisterReceiver` 是 Android 框架调用，纯 JVM 下不可用。
 * 事件源本身（`ScreenEventSource`）不应因此变得不可测，故把注册动作收敛到本接口，
 * 由 `data/event/android/AndroidBroadcastRegistration` 提供唯一实现
 * （**该实现按决策 13 不进单测，正确性由真机覆盖**）。
 *
 * ## 为什么有 [elapsedRealtimeMillis]
 * [ScreenEventReceiver] 需要对"反复出现的未知 action"做**一次性告警**（否则周期性的
 * 亮/熄屏会刷爆日志）。时间源若硬编码 `android.os.SystemClock`，该窗口逻辑将无法单测。
 * `SystemClock` 是**纯 JVM 可用**的类（自身不调用框架方法），生产实现直接委托给它。
 */
interface BroadcastRegistration {
    /**
     * 注册接收器。
     *
     * @param actions 关注的 action 列表
     * @param onAction 收到广播时的回调（只传 action 字符串，见 [SourceActionSink]）
     */
    fun register(
        actions: Collection<String>,
        onAction: (String) -> Unit,
    )

    /** 反注册。**未注册时调用必须安全**（不得抛 `IllegalArgumentException`）。 */
    fun unregister()

    /** 当前是否已注册。 */
    val isRegistered: Boolean

    /** 单调时间（自开机起算的毫秒数），供"一次性告警"窗口使用。 */
    fun elapsedRealtimeMillis(): Long
}
