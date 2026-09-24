package com.rootflow.data.service

import android.util.Log
import com.rootflow.data.di.EventDispatcherScope
import com.rootflow.data.event.EventSourceRegistry
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.DaemonSupervisor
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.KeepAliveHealthCheck
import com.rootflow.domain.service.SafeModeAlertSink
import com.rootflow.domain.service.ServiceNotificationModel
import com.rootflow.domain.service.ServiceNotificationText
import com.rootflow.domain.service.ServiceNotifier
import com.rootflow.domain.service.ServiceStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台服务的**生命周期编排**（阶段 5，需求 §7）。
 *
 * ## 为什么逻辑在这里，而不在 `Service` 里
 * `AGENTS.md` 的分层约束写明「`service/` 只做系统集成，不写业务逻辑」。
 * 本类**不 import 任何 Android 组件**（只用 `android.util.Log`，它在纯 JVM 下可用），
 * 因此"什么时候启动事件源""通知该显示什么""停止时该反注册什么"这三条判定
 * 全部可在纯 JVM 下单测 —— `KeepAliveService` 退化成三行转发。
 *
 * ## ★ 单所有者不变量（本阶段最关键的架构改动）
 * `EventSourceRegistry.start()` / `stop()` 的**唯一调用者**是本类。
 * 阶段 3c.1~4 里它们由 `RootFlowApp.onCreate` 无条件调用（进程域），
 * 而需求 §2.1 明写 `screen_on` / `screen_off` 这类动态注册源「**须驻留前台服务**」，
 * 对应 `PROJECT_STATE.md` 的偏离项 **D4**（阶段 3 只能挂在 Application 域，
 * "进程被系统回收后即失效"）。阶段 5 把启停绑定到本类 ⇒ D4 正面关闭。
 *
 * 违反该不变量的后果是**静默的**：Application 若仍调用 `start()`，
 * 注册表的 `started` 标志会让服务的调用变成空操作，于是"服务停了但事件源还活着"
 * ——正是 D4 要修的那个形态。`ForegroundServiceControllerTest` 有断言守着它。
 *
 * ## 顺序（`register()` 内，勿调换）
 * ```
 * ① 置 registered（重复调用立即返回 ⇒ sticky 重复投递安全）
 * ② 建通知渠道（必须在任何前台化之前）
 * ③ eventSourceRegistry.start()（动态源开始转发）
 * ④ 订阅 safeMode / tripReason → 刷新通知
 * ⑤ 立即推一次当前状态（不等下一次发射）
 * ```
 * ⑤ 单独存在的理由：`CircuitBreaker.safeMode` 是 `StateFlow`，订阅**会**立即收到当前值；
 * 但"收到值 → 算出模型 → 通知出去"这条链要等到调度器真的跑那个协程。
 * 若进程刚被 sticky 拉起、而协程还没被调度，前台通知就会停留在服务自己设的引导态。
 * 因此这里同步算一次，让状态在 `register()` 返回时就已经正确。
 *
 * ## 与 `KeepAliveHealthCheck` 的关系（裁定 ①）
 * 本类**不实现**健康检查（`androidx.work` 不在离线缓存，已报并裁定为"只留端口与设计"）。
 * 这里只打一行 [KeepAliveHealthCheck.HEALTH_CHECK_TODO_MARKER]，
 * 让"设计已留、实现未做"在真机日志里是**显式事实**而不是遗漏。
 *
 * ## ★ 为什么 `circuitBreaker` 是 `dagger.Lazy`（**实测踩坑记录，勿改回直接注入**）
 * 本类与熔断器之间**天然互相需要**：
 * - 熔断器要发告警通知：`CircuitBreakerImpl → SafeModeNotifier → SafeModeAlertSink(= 本类)`
 * - 本类要订阅熔断状态来刷常驻通知：`本类 → CircuitBreaker`
 *
 * 于是依赖图直接闭合。**仅靠 `SafeModeAlertSink` 端口抽象是断不开的**——
 * 端口把"控制器实现 sink"这条边的方向反转了，但 `@Binds` 转发仍被 Dagger 计为
 * **同一条依赖路径**，实测 `hiltJavaCompileDebug` 报：
 * ```
 * [Dagger/DependencyCycle] Found a dependency cycle:
 *   CircuitBreakerImpl ← bindCircuitBreaker ← CircuitBreaker
 *   ← provideForegroundServiceController ← ForegroundServiceController
 *   ← bindSafeModeAlertSink ← SafeModeAlertSink ← NotificationSafeModeNotifier
 *   ← bindSafeModeNotifier ← SafeModeNotifier ← CircuitBreakerImpl
 * ```
 * （原方案 §5.3 以为端口即可断环，**该预判不正确**，此处如实登记。）
 *
 * 解法是 Dagger 的**标准断环手段**：把其中一条边声明为 `Lazy`。
 * `Lazy` 在依赖图里不是一条普通边（Dagger 文档明列 `Provider` / `Lazy` 可用于断环），
 * 于是图变为可拓扑排序；真实构造推迟到 [register] 里第一次 `get()` 时发生，
 * 而那一刻本类已经构造完成 ⇒ 运行期也不存在环。
 *
 * **纪律**：不得把 `Lazy` 改成直接注入；也不得把 `get()` 提前到构造期
 * （字段初始化里调用即可复现同一个环）。
 *
 * ## 阶段 6b：本类同时是 `ServiceStateProvider` 的实现
 * 主页的服务状态卡与事件源列表都读它（`state` / `sources`）。
 * 为什么把事件源列表也放在这里、以及为什么**没有**常驻 ticker，
 * 见 [ServiceStateProvider] 与 [publishState] 的 KDoc —— 这两条是 6b 的已批准决策。
 *
 * @param eventSourceRegistry 事件源注册表（**唯一所有者**是本类）
 * @param circuitBreaker 熔断器的**延迟**引用（只订阅，不驱动；`Lazy` 的理由见上）
 * @param notifier 通知投递端口
 * @param daemonSupervisor 「一直运行」常驻脚本的监管端口（阶段 10）。
 *   **与事件源同属一个所有者**：常驻脚本是"须驻留前台服务"的**极端**形态
 *   —— 一个一直在跑的脚本若挂在进程域，服务被系统回收后就没人看着它了。
 *   因此 [register] 起、[unregister] 停、[onSafeModeAlert] 中止（用户 2026-09-20 裁定）。
 * @param scope 长驻订阅的作用域（复用 `@EventDispatcherScope`：本类订阅的正是事件系统的状态，
 *   与 `UsageStatsPollingSource` / `AlarmSyncCoordinator` 同域；**不新建 `AppScopeModule`**，
 *   理由见方案 §11——只有一个新增订阅者，新建模块只会多一个可被任意注入的过宽绑定）
 */
@Singleton
class ForegroundServiceController
    @Inject
    constructor(
        private val eventSourceRegistry: EventSourceRegistry,
        private val circuitBreaker: dagger.Lazy<CircuitBreaker>,
        private val notifier: ServiceNotifier,
        private val daemonSupervisor: DaemonSupervisor,
        @EventDispatcherScope private val scope: CoroutineScope,
    ) : SafeModeAlertSink,
        ServiceStateProvider {
        private val lock = Any()

        private var registered: Boolean = false

        /** 订阅作业；[unregister] 时取消，取消后不得再发通知。 */
        private var subscription: Job? = null

        /**
         * 事件源状态流的订阅作业（P5c）。
         *
         * 与 [subscription] 分开：那条是"熔断状态 → 通知"，这条是"源状态 → 卡片"。
         * 生命周期相同，但失败域不同 —— 一条塌了不该带走另一条。
         */
        private var statusSubscription: Job? = null

        /** 最近一次算出的通知模型（`KeepAliveService` 前台化与真机判读用）。 */
        private var lastModel: ServiceNotificationModel? = null

        /** 服务是否已注册（只读，供日志与断言）。 */
        val isRegistered: Boolean
            get() = synchronized(lock) { registered }

        /** 最近一次的通知模型；`register()` 之前为 `null`。 */
        fun currentNotification(): ServiceNotificationModel? = synchronized(lock) { lastModel }

        // ------------------------------------------------ ServiceStateProvider（阶段 6b）

        /**
         * 主页服务状态卡的唯一真相（见 [ServiceStateProvider] 的 KDoc）。
         *
         * **初值 [ForegroundState.Idle]**：冷启动时服务确实还没注册，
         * 这不是"未知"而是已知事实 —— 报 `Idle` 让卡片立即正确，无需等第一次发布。
         */
        private val _state: MutableStateFlow<ForegroundState> = MutableStateFlow(ForegroundState.Idle)

        override val state: StateFlow<ForegroundState> = _state.asStateFlow()

        /** 逐源状态镜像；未注册时为空（见端口的 KDoc：不编造原因）。 */
        private val _sources: MutableStateFlow<List<EventSourceStatus>> = MutableStateFlow(emptyList())

        override val sources: StateFlow<List<EventSourceStatus>> = _sources.asStateFlow()

        /**
         * 服务已创建/被重新投递：启动事件源并开始观察熔断状态。**幂等**。
         *
         * sticky 重启（`onStartCommand` 再次投递）会重复调用本方法，
         * 因此幂等是**正确性要求**而非优化：重复启动事件源会重复注册接收器。
         */
        fun register() {
            synchronized(lock) {
                if (registered) {
                    Log.i(TAG, "FGS_REGISTER skipped reason=already registered")
                    return
                }
                registered = true
            }

            Log.i(TAG, "FGS_REGISTER begin")
            // 第一次解引用（`Lazy` 的断环点，见类 KDoc）：此刻本类已构造完成，
            // 熔断器才开始构造，因此运行期也不会形成环。
            val breaker = circuitBreaker.get()
            // 单个依赖失败不得让服务起不来：脚本宿主的第一职责是把服务跑起来。
            // 这里刻意逐项 runCatching，而不是整体包一层——整体包住会让"渠道建失败"
            // 连带跳过"事件源启动"，那是**功能性**损失，比通知显示不出来严重得多。
            runCatching { notifier.ensureChannels() }
                .onFailure { Log.w(TAG, "FGS_REGISTER channels failed: ${describe(it)}") }
            runCatching { eventSourceRegistry.start() }
                .onFailure { Log.w(TAG, "FGS_REGISTER registry start failed: ${describe(it)}") }
            Log.i(TAG, "FGS_REGISTRY_STARTED sources=${describeSources()}")

            // ★ P5c：事件源状态改由 registry **主动推送**（见 `EventSourceRegistry.statusFlow`）。
            //   位置在 `start()` 之后：先把订阅接上，再听它说话。
            //   放在 `pushNotification` 之前也刻意 —— 那条路径尾部自己会 `publishState()`，
            //   本订阅只负责**之后**的变化（订阅建立时 StateFlow 会立即给一次当前值，
            //   两份快照在 `MutableStateFlow` 上去重后不会打架）。
            statusSubscription =
                scope.launch {
                    eventSourceRegistry.statusFlow.collect { publishState() }
                }

            // ★ 阶段 10：常驻脚本与事件源**同一个所有者**（见构造参数 daemonSupervisor 的说明）。
            //   放在事件源之后：常驻脚本的启动要读 Room（触发器表），
            //   而事件源是"外部随时可能来事件"的那一侧 —— 先订阅再干别的。
            //   单独 runCatching：常驻脚本起不来不该让服务起不来。
            runCatching { daemonSupervisor.start() }
                .onFailure { Log.w(TAG, "FGS_REGISTER daemon supervisor start failed: ${describe(it)}") }

            subscription =
                scope.launch {
                    combine(breaker.safeMode, breaker.tripReason) { safeMode, reason ->
                        safeMode to reason
                    }.collect { (safeMode, reason) ->
                        runCatching { pushNotification(safeMode = safeMode, reason = reason) }
                            .onFailure { Log.w(TAG, "FGS_NOTIFICATION_FAILED: ${describe(it)}") }
                    }
                }

            // ⑤ 立即推一次：不让通知停留在服务自己设的引导态（见类 KDoc 的顺序说明）
            runCatching {
                pushNotification(
                    safeMode = breaker.safeMode.value,
                    reason = breaker.tripReason.value,
                )
            }.onFailure { Log.w(TAG, "FGS_NOTIFICATION_FAILED (initial): ${describe(it)}") }

            // ⑥ 阶段 6b：有界追赶（见 [STATE_CATCH_UP_STEPS] 的取值理由）
            scheduleStateCatchUp()

            Log.i(
                TAG,
                KeepAliveHealthCheck.HEALTH_CHECK_TODO_MARKER +
                    " design=AlarmManager-self-renew intervalMillis=" +
                    KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS +
                    " implemented=false reason=androidx.work not in offline cache (approved decision)",
            )
        }

        /**
         * 服务即将销毁：反注册事件源、取消订阅、撤销告警。
         *
         * 顺序**刻意**是"先停订阅、再停事件源"：反过来的话，订阅还活着，
         * `safeMode` 在停止过程中若发生发射，会推出一条"服务已停但仍显示运行中"的通知。
         */
        fun unregister() {
            synchronized(lock) {
                if (!registered) return
                registered = false
            }

            Log.i(TAG, "FGS_UNREGISTER begin")
            subscription?.cancel()
            subscription = null
            // P5c：状态流的订阅必须与它一起走 —— 否则服务已停，registry 的推送还会
            // 把 `_state` / `_sources` 从 Idle 拉回 Running（UI 显示"停了但还在跑"）。
            statusSubscription?.cancel()
            statusSubscription = null
            // ★ 阶段 10：先停常驻脚本，再停事件源。顺序与"先停订阅、再停事件源"同一条理由 ——
            //   常驻脚本是被**监管**的一侧，停掉监管会连带终止它的进程；
            //   放在事件源停止之前，是为了让"服务正在停"这件事先从脚本侧收敛干净。
            runCatching { daemonSupervisor.stop() }
                .onFailure { Log.w(TAG, "FGS_UNREGISTER daemon supervisor stop failed: ${describe(it)}") }
            runCatching { eventSourceRegistry.stop() }
                .onFailure { Log.w(TAG, "FGS_UNREGISTER registry stop failed: ${describe(it)}") }
            runCatching { notifier.cancelAlert() }
                .onFailure { Log.w(TAG, "FGS_UNREGISTER cancel alert failed: ${describe(it)}") }
            // 阶段 6b：服务停了，主页必须立刻如实反映 —— 状态回 Idle、源列表清空。
            // 放在最后：这一步是**发布**，不是动作，不能排在会抛异常的动作之前。
            publishState()
            Log.i(
                TAG,
                "FGS_REGISTRY_STOPPED reason=service stopped sources=${describeSources()}",
            )
        }

        // ------------------------------------------------------ SafeModeAlertSink

        /**
         * 熔断告警（需求 §5.2 第 4/5 步）。
         *
         * ## 为什么服务没注册时也照发
         * 熔断的六步动作里，第 5 步是"发送高优先级通知"。若本方法在服务未注册时静默返回，
         * 就会出现"熔断了但用户毫无感知"——正是 `SafeModeNotifier` 的 KDoc 明令禁止的形态。
         * 通知**不依赖前台服务**，因此这里无条件投递。
         */
        override fun onSafeModeAlert(reason: TripReason) {
            Log.w(TAG, "SAFEMODE_NOTIFY_TRIP reason=${reason.reasonKey} via=foreground-service-controller")
            // ★ 阶段 10：熔断时中止常驻脚本（用户 2026-09-20 裁定：常驻也受安全模式约束）。
            //   为什么放在通知**之前**：熔断的语义是"全部停下来"，先让脚本真的停，
            //   再告诉用户"已经停了" —— 反过来的话通知会先于事实到达。
            //   注意本方法在服务**未注册**时也会被调用（见 KDoc：通知不依赖前台服务），
            //   而 `stop()` 是幂等的，因此这里无需判 `registered`。
            runCatching { daemonSupervisor.stop() }
                .onFailure { Log.w(TAG, "SAFEMODE_DAEMON_STOP_FAILED: ${describe(it)}") }
            runCatching { notifier.alert(ServiceNotificationText.safeModeAlert(reason)) }
                .onFailure { Log.w(TAG, "SAFEMODE_NOTIFY_TRIP failed: ${it.message ?: it::class.java.name}") }
        }

        /** 恢复（需求 §5.3 第 1 条）：撤销告警；常驻通知由订阅在下次发射时回到运行中态。 */
        override fun onSafeModeRestore() {
            Log.i(TAG, "SAFEMODE_NOTIFY_RESTORE via=foreground-service-controller")
            runCatching { notifier.cancelAlert() }
                .onFailure { Log.w(TAG, "SAFEMODE_NOTIFY_RESTORE failed: ${it.message ?: it::class.java.name}") }
        }

        // ------------------------------------------------------ 内部

        /** 由当前状态算出模型并投递；同时记住它供前台化复用。 */
        private fun pushNotification(
            safeMode: Boolean,
            reason: TripReason?,
        ) {
            val state = currentState(safeMode = safeMode)
            val model =
                ServiceNotificationText.serviceNotification(
                    state = state,
                    reason = reason,
                    notificationsGranted = notificationsGranted(),
                )
            synchronized(lock) { lastModel = model }
            // 阶段 6b：同一份 state 也发布给 UI。**放在这里而不是另算一次**是有意的 ——
            // 两处各算一次就会出现"通知说 6/7、卡片说 5/7"的漂移，而那类不一致
            // 只在真机上肉眼可见（本仓库反复禁止的形态）。
            publishState(safeMode = safeMode)
            notifier.update(model)
        }

        /**
         * 发布当前状态给 [ServiceStateProvider] 的订阅者（阶段 6b）。
         *
         * ## 调用点（**不要加常驻 ticker**）
         * | 时点 | 理由 |
         * |---|---|
         * | [register] 尾部 | 服务刚注册，状态从 `Idle` 变为 `Running` |
         * | [pushNotification] 尾部 | 与通知共用同一份快照，结构上杜绝漂移 |
         * | [unregister] 尾部 | 服务停了，必须立刻回 `Idle` |
         * | [statusSubscription] 的发射 | **P5c**：源状态被"订阅表 / 总闸"改变，本类不是发起者 |
         *
         * ## ★ 为什么没有常驻 ticker（**已批准的 6b 决策，勿"优化"成定时轮询**）
         * 1. **`AGENT_PROTOCOL.md §9.4`**：虚拟时间下"空闲轮询"会让 `advanceUntilIdle()`
         *    永不返回（活锁）。本类的单测正是用 `runTest` + 虚拟时间驱动的
         * 2. **不需要轮询**：状态的**真相方**自己会说话 —— `EventSourceRegistry.statusFlow`
         *    在每次 `reconcile` 之后推一次（上表第 4 行就是它的订阅），
         *    而 `StateFlow` 自带去重 ⇒ "什么都没变"不会造成发布
         *
         * ## ★ 6b 的旧前提已被 P5c 作废（这段曾经写的是"没有信息可轮询"）
         * 6b 时 `EventSourceRegistryImpl` 的可变状态**只在 `start()` / `stop()` 两处写**，
         * 而这两处都只由本类调用 ⇒ 拉一次快照就够。P5c 把状态还交给了**订阅表 + 总闸**
         * （用户在编辑器里勾一个 chip 就起停），变化不经过本类 ⇒ 拉取式快照会滞后
         * （真机实测：日志 `screen started=true`，主页仍显示 `事件源 2/7`）。
         * 修法是**让它推**（上表第 4 行），而不是让本类去轮询 —— 后者会同时踩理由 1。
         *
         * 残余的不确定性是"源自身的 `status()` 在 `start()` 之后极短窗口内才稳定"，
         * 由 [scheduleStateCatchUp] 的**有界**追赶覆盖。
         *
         * ## 线程
         * 由 [lock] 串行化"取快照"。`MutableStateFlow` 的赋值本身是原子的，
         * 但"读 registered → 读 registry.status()"这一段不是，两个发布点并发时
         * 可能发出**新旧倒置**的一对值（UI 会闪回旧状态）。加锁的代价可忽略
         * （无挂起点、两次内存读），收益是状态序列单调。
         */
        private fun publishState(safeMode: Boolean = currentSafeMode()) {
            val snapshot =
                synchronized(lock) {
                    val state = currentState(safeMode = safeMode)
                    val sources = if (isRegistered) querySources() else emptyList()
                    state to sources
                }
            _state.value = snapshot.first
            _sources.value = snapshot.second
        }

        /**
         * 阶段 6b：注册后的**有界**追赶发布（[STATE_CATCH_UP_STEPS] 次，约 1.2s 结束）。
         *
         * 为什么需要它：`register()` 返回时事件源才刚被 `start()`，
         * 个别源的 `status()` 可能还在"启动中"的短窗口里（例如网络回调尚未建立）。
         * 追赶让卡片在 1.2s 内自行收敛，而不必让用户手动刷新。
         *
         * **为什么必须有界**：无界循环会同时踩 §9.4 的活锁与"虚拟时间测试永不结束"。
         * 本方法最多 `delay` 两次即返回，因此 `advanceUntilIdle()` 能正常收敛。
         *
         * ## ★ 这里刻意**不打日志**（勿"补"回一行 `Log.i`/`Log.w`）
         * 本项目的单测在纯 JVM 下跑，而 `android.util.Log` **没有被 stub** ——
         * 任何 `Log.*` 调用都会抛
         * `RuntimeException: Method i in android.util.Log not mocked`。
         *
         * 本类的既有测试之所以一直绿，是因为它们从不把虚拟时间推进到这条协程真的执行；
         * 而 6b 新增的"有界收敛"断言必然要 `advanceUntilIdle()` ⇒ 一旦这里打日志，
         * **整个 `ServiceStateProviderTest` 会集体变红**（实测：8 条用例同时失败）。
         *
         * 追赶本身没有值得判读的语义：状态在 `state` / `sources` 两个 `StateFlow` 上
         * 直接可观测，真机判读看 `FGS_REGISTRY_STARTED sources=…` 即可。
         * 为一行纯诊断信息付"一批单测不可测"的代价不划算。
         */
        private fun scheduleStateCatchUp() {
            scope.launch {
                repeat(STATE_CATCH_UP_STEPS) {
                    delay(STATE_CATCH_UP_INTERVAL_MILLIS)
                    // 服务可能在追赶期间已被停止：此时不作无意义发布（unregister 已发过 Idle）。
                    if (!isRegistered) return@launch
                    // `publishState` 内部已经对 registry / breaker 的异常做了兜底
                    // （`querySources` / `currentSafeMode` 各自 runCatching），
                    // 因此这里不再包一层 —— 包了也没地方上报（见上面的"不打日志"）。
                    publishState()
                }
            }
        }

        /** 取熔断器当前的安全模式；拿不到时按"不在安全模式"（保守：不误报熔断）。 */
        private fun currentSafeMode(): Boolean = runCatching { circuitBreaker.get().safeMode.value }.getOrDefault(false)

        /** 源状态快照；失败时给空列表（`publishState` 的调用方不应因它崩）。 */
        private fun querySources(): List<EventSourceStatus> =
            runCatching { eventSourceRegistry.status() }.getOrDefault(emptyList())

        /** 未注册时如实报 [ForegroundState.Idle]，不假装在运行。 */
        private fun currentState(safeMode: Boolean): ForegroundState {
            if (!isRegistered) return ForegroundState.Idle
            val statuses = querySources()
            return ForegroundState.Running(
                enabled = statuses.count { it.state.isRunning },
                total = statuses.size,
                safeMode = safeMode,
            )
        }

        /**
         * 通知是否可见（供正文如实降级）。
         *
         * **不确定时报 `true`**：`ServiceNotifier` 的默认实现即如此，而"平白多一句降级警告"
         * 比"少一句"更糟——它会训练用户忽略警告。
         */
        private fun notificationsGranted(): Boolean = runCatching { notifier.notificationsVisible() }.getOrDefault(true)

        /** 逐源状态摘要（真机判读 `FGS_REGISTRY_STARTED sources=…`）。 */
        private fun describeSources(): String =
            runCatching {
                eventSourceRegistry.status().joinToString(separator = ",") { status ->
                    "${status.sourceId}:" + if (status.state.isRunning) "on" else "off"
                }
            }.getOrDefault("<unavailable>")

        /** 异常摘要（含类名：`Method ... not mocked` 这类信息只在类名里有）。 */
        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /**
             * 注册后追赶发布的**次数**（阶段 6b）。
             *
             * 取值理由：`register()` 返回时事件源刚被 `start()`，个别源的状态可能还在
             * 极短窗口内未稳定。2 次 × 300ms = 约 1.2s 足够覆盖，且**必然终止**
             * （§9.4：无界轮询会让 `advanceUntilIdle()` 活锁）。
             */
            const val STATE_CATCH_UP_STEPS: Int = 2

            /** 追赶间隔（毫秒）；与 [STATE_CATCH_UP_STEPS] 共同决定 1.2s 的收敛上界。 */
            const val STATE_CATCH_UP_INTERVAL_MILLIS: Long = 300L
        }
    }
