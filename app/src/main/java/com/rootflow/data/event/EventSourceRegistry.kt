package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.di.EventDispatcherScope
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.repository.TriggerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推送型事件源的编排者（阶段 3c.1）。
 *
 * ## 为什么放在 `data/event/`（决策 2）
 * `service/` 只放**必须由系统实例化的 Android 组件**（`BroadcastReceiver` 等）。
 * 本类是**编排者**，与各事件源同层，从而可在**纯 JVM** 下单测（无需 Android 环境）。
 *
 * ## 职责边界
 * - 只负责 [start] / [stop] / [status] 三件事
 * - **不认识** `EventBus`、不认识 Room：事件由各源自己发给总线，匹配由 `TriggerDispatcher` 做
 * - 不做权限**申请**（本阶段不弹任何授权框）；只做权限**上报**与门控
 */
interface EventSourceRegistry {
    /**
     * 启动全部事件源。
     *
     * **幂等**：重复调用只生效一次。
     * **单个源失败不影响其余**：失败原因进 [status]（与 `SupervisorJob` 的
     * "单个失败不拖垮全局"同款纪律）。
     */
    fun start()

    /** 停止全部事件源。**幂等**；未 [start] 时调用必须安全。 */
    fun stop()

    /**
     * 逐个源的状态，按 `sourceId` 升序（顺序确定，便于真机逐行比对）。
     *
     * 每个源必须能回答「是否启动」+「未启动的具体原因」，原因要**具体到缺哪个权限**
     * （决策 7）——阶段 6 的 UI 据此逐项提示用户授权。
     */
    fun status(): List<EventSourceStatus>

    /**
     * 逐源状态的**可观察**形态（P5c）。
     *
     * ## 为什么不能让调用方继续拉 [status]
     * P5c 之前，本注册表的可变状态只在 [start] / [stop] 两处写，而这两处**只由
     * `ForegroundServiceController` 调用** ⇒ 调用方"自己改成什么样，自己知道"，
     * 拉一次快照就够（控制器的"无常驻 ticker"决策正建立在这个前提上）。
     *
     * P5c 把状态还交给了**订阅表 + 总闸**（见 `EventSourceRegistryImpl.reconcile`）：
     * 用户在编辑器里勾一个 chip，源就起停，而**变化不经过控制器**。
     * 此时拉取式快照会一直停在"服务注册那一刻"的值 —— 真机实测到
     * "总闸打开 + 订阅 `screen_on` ⇒ 日志里 `screen started=true`，主页仍显示 `事件源 2/7`"。
     *
     * 因此改由**状态的真相方**主动推送。控制器转发给 `ServiceStateProvider`，
     * 仍然是"只有一个知道事件源状态的人"（6b 决策），只是它现在会说话。
     *
     * **不是 ticker**：只在真的变化时发射（`StateFlow` 自带去重）。
     */
    val statusFlow: StateFlow<List<EventSourceStatus>>
}

/**
 * [EventSourceRegistry] 实现（阶段 3c.1）。
 *
 * ## 状态机
 * ```
 * NotStarted --start()--> started=true  --stop()--> started=false（源逐个 stop）
 *                              |
 *                              +-- 权限缺失的源：不 start，状态保持 Unavailable（决策 7 的具体原因）
 * ```
 *
 * ## 为什么 [status] 要区分"未曾尝试"与"尝试后失败"
 * 两者对阶段 6 的 UI 是不同提示；且在真机验证时，"从未调用 start"与"调用了但因权限未启"
 * 是两种完全不同的排查方向（决策 7）。
 *
 * ## 权限门控
 * [PermissionStatusProvider.refresh] 在 [start] / [stop] 各调一次（决策 6：不注册系统监听器）。
 * [start] 时对每个源的 `requiredPermissions` 逐项检查：任一项 `GRANTED` 之外的结论都会阻止启动，
 * 并把**该权限字符串**写进原因。
 *
 * ## 并发
 * [start] / [stop] / [status] 可能来自不同线程（真机验证入口在 IO 车道、将来 UI 在主线程），
 * 因此内部状态由 [lock] 保护。`synchronized` 可重入，故 [start] 持锁调用 [logStatus]
 * （其中再调 [status]）不会自锁。
 *
 * ## 关于构造函数的形态（勿改回带默认值的 `@Inject` 构造）
 * `onWarning` 走**内部次构造函数**而非 `@Inject` 构造的默认参数：Kotlin 默认参数对
 * Dagger 不可见，会被当成必须绑定 `Function1<String, Unit>` 而报 `MissingBinding`（已实测）。
 * 同一原因也适用于 `WifiEventSource` 与 `AndroidPermissionStatusProvider`。
 *
 * @param sources 全部推送型源（Hilt multibinding 注入；顺序无关，内部按 `sourceId` 排序）
 * @param permissionStatus 权限状态（决策 10 的 5 项目录）
 */
@Singleton
class EventSourceRegistryImpl
    @Inject
    constructor(
        private val sources: Set<@JvmSuppressWildcards EventSource>,
        private val permissionStatus: PermissionStatusProvider,
        /**
         * **P5c：按需启的判据来源** —— 订阅表热流。
         *
         * 重构前 registry 无条件启动全部源（`sources.forEach { startOne(it) }`），
         * 与有没有脚本订阅无关 ⇒ 7 个源（广播接收器 + 2 秒轮询 + 2 个闹钟）是**纯开销**
         * （方案 §1.7）。现在只有"产出的某个事件**有生效订阅**"的源才启动。
         */
        private val triggerRepository: TriggerRepository,
        /**
         * **P5c：总闸关闭 ⇒ 一个源都不启**（方案 §4 的状态机：
         * `master_enabled = false` ⇒ 事件源全停）。总闸与"有没有订阅"是**两个条件**，
         * 缺一不可：关闸时用户明确说了"别提供服务"，开着也收事件是错的。
         */
        private val masterSwitch: MasterSwitch,
        /**
         * **P5c：订阅订阅表用的作用域**（生产为应用级）。
         *
         * [start] 由 `ForegroundServiceController.register()` 的**同步**路径调用，
         * 而订阅是 Flow ⇒ 必须 `launch`（与 `DaemonSupervisorImpl.start()` 同款）。
         */
        @EventDispatcherScope private val scope: CoroutineScope,
    ) : EventSourceRegistry {
        /**
         * 告警回调的**测试缝**（`internal`，仅单测用）。
         *
         * 生产由 [init] 接到 `Log.w`。**不把 `onWarning` 放进 `@Inject` 构造**：
         * Kotlin 默认参数对 Dagger 不可见，它会尝试注入
         * `Function1<String, Unit>` 而报 `MissingBinding`（已实测）。
         */
        internal constructor(
            sources: Set<EventSource>,
            permissionStatus: PermissionStatusProvider,
            triggerRepository: TriggerRepository,
            masterSwitch: MasterSwitch,
            scope: CoroutineScope,
            onWarning: (String) -> Unit,
        ) : this(
            sources = sources,
            permissionStatus = permissionStatus,
            triggerRepository = triggerRepository,
            masterSwitch = masterSwitch,
            scope = scope,
        ) {
            this.onWarning = onWarning
        }

        private var onWarning: (String) -> Unit = { message -> Log.w(TAG, message) }

        /** 运行期可变状态：是否已启动。由 [lock] 保护（start/stop 可能来自不同线程）。 */
        private val lock = Any()

        private var started: Boolean = false

        /** P5c：订阅订阅表的作业（[stop] 时取消）。 */
        private var subscription: Job? = null

        /**
         * 每个源"实际调用过 `start()`"的标记。
         *
         * 记录**编排者是否放行过它**（与源自己的报告互补）：源报 `Running` 只说明它注册成功，
         * 而本标记说明"我们确实让它启动过"。
         */
        private val startedSources = mutableSetOf<String>()

        /**
         * 被编排者**拒绝启动**的源 → 具体原因（编排者的决定记录）。
         *
         * ## 为什么必须由 registry 记住，而不是问源
         * 权限门与异常门都在 [startOne] 里：被挡下的源**从未被调用 `start()`**，
         * 因此它的 `status()` 只能如实报"我没被放行过"（`NotStarted`）——
         * 源**不知道**"为什么没人启动我"，它也不该知道（权限判定是编排者的职责，
         * 让源也判一次会让同一条规则有两处实现）。
         *
         * 若本映射缺失，决策 7 要求的"原因具体到缺哪个权限"就只剩日志里有，
         * `status()` 会退化成笼统的 `not started`（**真机实测踩到过**）。
         *
         * 原因文案与 `onWarning` 复用同一格式：
         * - `permission missing: <android.permission.X>`
         * - `start failed: <异常信息>`
         */
        private val unavailableReasons = mutableMapOf<String, String>()

        /**
         * [statusFlow] 的载体（P5c）。
         *
         * 初值取 [status]：此刻 `started = false`，因此如实给出"全部 `NotStarted`"，
         * 而不是一个"还没想好"的空列表 —— 空列表会被订阅者误读成"一个源都没有"。
         *
         * 声明位置在 [unavailableReasons] **之后**是刻意的：初值表达式会调用 [status]，
         * 而它读 [lock] / [started] / [unavailableReasons]，三者必须已初始化。
         */
        private val _statusFlow: MutableStateFlow<List<EventSourceStatus>> = MutableStateFlow(status())

        override val statusFlow: StateFlow<List<EventSourceStatus>> = _statusFlow.asStateFlow()

        override fun start() {
            synchronized(lock) {
                if (started) return
                started = true
                permissionStatus.refresh()
            }
            // ★ P5c：**不再无条件全启** —— 改为订阅订阅表，按需启停（见 [reconcile]）。
            //
            //   首次发射即"当前全量"，因此这一处同时替代了原来那次 `forEach { startOne }`。
            //   `launch` 是必须的：本方法由 `ForegroundServiceController.register()`
            //   的**同步**路径调用，而订阅是 Flow（与 `DaemonSupervisorImpl.start()` 同款）。
            subscription =
                scope.launch {
                    combine(
                        triggerRepository.observeAll(),
                        masterSwitch.enabled,
                    ) { subscriptions, masterEnabled -> subscriptions to masterEnabled }
                        .catch { error ->
                            // 订阅断掉不该让服务崩（与 register 路径逐项 runCatching 同一条理由）。
                            // 留痕，不静默。
                            onWarning(
                                "event source registry observe failed: " +
                                    (error.message ?: error::class.java.name),
                            )
                        }.collect { (subscriptions, masterEnabled) ->
                            reconcile(subscriptions = subscriptions, masterEnabled = masterEnabled)
                        }
                }
            Log.i(TAG, "EVENT_SOURCE_REGISTRY_STARTED mode=on-demand (sources start only when subscribed)")
        }

        /**
         * 按需对账（P5c）：把"哪些源该在跑"对齐到"订阅表 + 总闸"说的集合。
         *
         * ## 判据
         * ```
         * 需要某源  <=>  总闸开着  且  该源产出的某个事件有 enabled = true 的订阅
         * ```
         * 两个条件**缺一不可**：总闸关闭时用户明确说了"别提供服务"（方案 §4 的状态机
         * 第一条就是"事件源全停"）；总闸开着但没人订阅某个事件时，为它常驻一个
         * 广播接收器或 2 秒轮询是纯开销（方案 §1.7）。
         *
         * ## 为什么"清单注册的源"也在账内
         * `power` / `battery` 的 `start()` 是 no-op、`status()` 恒 `Running`
         * （系统按清单实例化接收器，本应用无法运行时启停）。把它们纳入对账**无害**
         * （启停是空操作），而排除在外会让"哪些源在跑"的账目**残缺** ——
         * 真机判读时那两个源会凭空出现在列表里、却不在任何一次对账的日志中。
         *
         * ## 为什么"不变就什么都不做"
         * 用户在编辑器里勾一个 chip 就会触发一次本方法。若"全部重建"，
         * 一次无关的勾选会重启所有源 —— 反复注册/反注册广播接收器有真实代价
         * （窗口错失 + 日志噪声）。差集与 `DaemonSupervisorImpl.reconcile` 同款。
         */
        private fun reconcile(
            subscriptions: List<Trigger>,
            masterEnabled: Boolean,
        ) {
            val wanted: Set<String> =
                if (masterEnabled) {
                    subscriptions.filter { it.enabled }.map { it.eventType }.toSet()
                } else {
                    // 总闸关闭 ⇒ 一个源都不启（方案 §4：事件源全停）
                    emptySet()
                }
            synchronized(lock) {
                sources.sortedBy { it.sourceId }.forEach { source ->
                    val needed = source.providesEvents.any { it in wanted }
                    val isOn = source.sourceId in startedSources
                    when {
                        needed && !isOn -> startOne(source)

                        !needed && isOn -> {
                            stopOne(source)
                            startedSources -= source.sourceId
                            // 停掉的源不该留着上一次的拒绝原因（它已经不在账内了）
                            unavailableReasons -= source.sourceId
                        }

                        else -> Unit
                    }
                }
                logStatus("EVENT_SOURCE_REGISTRY_RECONCILED wanted=$wanted masterEnabled=$masterEnabled")
                // ★ P5c：对账改了状态 ⇒ 必须推给 [statusFlow]。
                //   否则"用户勾了一个订阅"这件事只留在日志里，主页的 `事件源 x/7`
                //   会一直停在服务注册那一刻的值（真机实测到这个形态）。
                publishStatus()
            }
        }

        override fun stop() {
            synchronized(lock) {
                if (!started) return
                started = false
                // P5c：先取消订阅 —— 否则停掉之后再来的订阅变化会把源又启起来
                subscription?.cancel()
                subscription = null
                sources.sortedBy { it.sourceId }.forEach { source -> stopOne(source) }
                startedSources.clear()
                // 清空拒绝原因：否则"停后重启"会残留上一轮的原因（例如权限已授予却仍报缺失）
                unavailableReasons.clear()
                permissionStatus.refresh()
                // ★ P5c：全停之后同样推一次 —— 否则主页会留着"停之前那一刻"的源数量。
                publishStatus()
                Log.i(TAG, "EVENT_SOURCE_REGISTRY_STOPPED")
            }
        }

        override fun status(): List<EventSourceStatus> {
            val wasStarted = synchronized(lock) { started }
            val rejected = synchronized(lock) { unavailableReasons.toMap() }
            return sources
                .sortedBy { it.sourceId }
                .map { source ->
                    // 被编排者拒绝启动的：**由 registry 给出具体原因**（决策 7），
                    // 优先于源的报告（源此时只可能报 NotStarted，信息量更低）
                    rejected[source.sourceId]?.let { reason ->
                        return@map EventSourceStatus(source.sourceId, EventSourceState.Unavailable(reason))
                    }
                    if (wasStarted) {
                        source.status()
                    } else {
                        // 尚未 start()：如实上报"未曾尝试"，而不是伪装成失败
                        EventSourceStatus(source.sourceId, EventSourceState.NotStarted)
                    }
                }
        }

        /**
         * 启动单个源。
         *
         * 两道门控按序执行，**两条都把具体原因同时写进日志与 [unavailableReasons]**：
         * 1. **权限门**：`requiredPermissions` 任一项未 `GRANTED` → 跳过并记具体原因
         * 2. **异常门**：`start()` 抛异常 → 捕获、记日志、继续下一个源
         *    （一个源失败绝不能拖垮整个注册表）
         */
        private fun startOne(source: EventSource) {
            blockingPermission(source)?.let { blocking ->
                val reason = "$REASON_PERMISSION_MISSING${blocking.permission.permission}"
                unavailableReasons[source.sourceId] = reason
                onWarning("event source '${source.sourceId}' not started: $reason")
                return
            }

            try {
                source.start()
                startedSources += source.sourceId
            } catch (error: Throwable) {
                // 必须捕获 Throwable：源里可能是 RuntimeException，也可能是 LinkageError
                // （Android 版本差异）；任何一种都不该让其余事件源陪葬。
                val reason = "$REASON_START_FAILED${error.message ?: error::class.java.name}"
                unavailableReasons[source.sourceId] = reason
                onWarning("event source '${source.sourceId}' start failed: ${error.message ?: error::class.java.name}")
            }
        }

        /** 停止单个源；同样不得让异常外溢。 */
        private fun stopOne(source: EventSource) {
            try {
                source.stop()
            } catch (error: Throwable) {
                onWarning(
                    "event source '${source.sourceId}' stop failed: " +
                        (error.message ?: error::class.java.name),
                )
            }
        }

        /**
         * 取第一个阻断该源启动的权限项。
         *
         * @return 阻断项；全部满足时 `null`
         */
        private fun blockingPermission(source: EventSource): PermissionState? {
            val snapshot = permissionStatus.current()
            return source.requiredPermissions
                .asSequence()
                .mapNotNull { snapshot[it] }
                .firstOrNull { it.grant != PermissionGrant.GRANTED }
        }

        /**
         * 把当前逐源状态推给 [statusFlow] 的订阅者（P5c）。
         *
         * `StateFlow` 在值相等时不发射，因此"一次什么都没改变的对账"不会造成 UI 抖动
         * （用户在编辑器里勾一个已勾选的 chip 也不会）。
         *
         * 调用点在 [lock] 内，而本方法会再进 [status]（它自己也 `synchronized(lock)`）——
         * `synchronized` 可重入，与 [logStatus] 同款，不自锁。
         */
        private fun publishStatus() {
            _statusFlow.value = status()
        }

        /** 逐源打一行状态，供真机 `findstr /C:"EVENT_SOURCE"` 判读。 */
        private fun logStatus(banner: String) {
            Log.i(TAG, banner)
            status().forEach { status ->
                val state = status.state
                val running = state.isRunning
                val reason =
                    when (state) {
                        is EventSourceState.Unavailable -> state.reason
                        EventSourceState.Running -> "-"
                        EventSourceState.NotStarted -> "not started"
                    }
                // `started=` 对 Unavailable 必须是 false：把"未启动"报成"启动"会让真机判读失去意义
                Log.i(TAG, "EVENT_SOURCE id=${status.sourceId} started=$running reason=$reason")
            }
        }

        private companion object {
            const val TAG = "RootFlow"

            /** 权限门拒绝原因的前缀（与真机 `findstr` 的关键字一致）。 */
            const val REASON_PERMISSION_MISSING: String = "permission missing: "

            /** 异常门拒绝原因的前缀。 */
            const val REASON_START_FAILED: String = "start failed: "
        }
    }
