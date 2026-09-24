package com.rootflow.data.di

import com.rootflow.data.event.EventSourceRegistry
import com.rootflow.data.service.ForegroundServiceController
import com.rootflow.data.service.ServiceNotifierImpl
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.DaemonSupervisor
import com.rootflow.domain.service.SafeModeAlertSink
import com.rootflow.domain.service.ServiceNotifier
import com.rootflow.domain.service.ServiceStateProvider
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * 前台服务的 DI 装配（阶段 5，需求 §7）。
 *
 * ## 绑定一览
 * | 端口 | 实现 | 形态 |
 * |---|---|---|
 * | [ServiceNotifier] | [ServiceNotifierImpl] | `@Binds` |
 * | [SafeModeAlertSink] | [ForegroundServiceController] | `@Binds`（**防环的关键**） |
 * | [ServiceStateProvider] | [ForegroundServiceController] | `@Binds`（阶段 6b：主页数据源） |
 * | [ForegroundServiceController] | 自身 | `@Provides`（有默认参数，见下） |
 *
 * ## ★ 为什么 [SafeModeAlertSink] 要绑到控制器（依赖方向图）
 * ```
 * CircuitBreakerImpl ──> SafeModeNotifier ──> SafeModeAlertSink ◀── 实现 ── ForegroundServiceController
 *                          (NotificationSafeModeNotifier)                          │
 * ForegroundServiceController ──> Lazy<CircuitBreaker>（只订阅 safeMode / tripReason）┘
 * ```
 * 若让 `NotificationSafeModeNotifier` 直接依赖控制器，就会成环。
 *
 * ## ★ 而**光有端口断不开环**（实测记录，勿凭直觉改）
 * `SafeModeAlertSink` 把"控制器实现 sink"这条边的方向反转了，但 `@Binds` 转发仍被 Dagger
 * 计为同一条依赖路径。实测 `hiltJavaCompileDebug` 直接报：
 * ```
 * [Dagger/DependencyCycle] Found a dependency cycle:
 *   CircuitBreakerImpl ← bindCircuitBreaker ← CircuitBreaker
 *   ← provideForegroundServiceController ← ForegroundServiceController
 *   ← bindSafeModeAlertSink ← SafeModeAlertSink ← NotificationSafeModeNotifier
 *   ← bindSafeModeNotifier ← SafeModeNotifier ← CircuitBreakerImpl
 * ```
 * （原方案 §5.3 以为"抽端口即可断环"，**该预判不正确**。）
 *
 * 真正的断环点是控制器侧的 `dagger.Lazy<CircuitBreaker>`：`Lazy` / `Provider` 是 Dagger
 * 官方文档明列的断环手段，它不作为普通依赖边参与拓扑排序。
 * **因此本模块提供的是 `Lazy<CircuitBreaker>`，不得改回直接注入。**
 *
 * ## 为什么控制器要 `@Provides` 而不能靠 `@Inject` 构造
 * 它的 `scope` 形参带 `@EventDispatcherScope` 限定符，且**限定符无法写在可空/默认参数上**
 * （与 `BootloopGuard` 同款坑型，阶段 4 已实测）。
 * 这里显式列出全部依赖，顺带让"控制器究竟依赖谁"在 DI 层一目了然。
 *
 * ## 为什么复用 `@EventDispatcherScope`
 * 控制器订阅的是**事件系统的状态**（熔断器的 `safeMode`），与 `UsageStatsPollingSource` /
 * `AlarmSyncCoordinator` 同域。**不新建 `AppScopeModule`**：本阶段只多这一个订阅者，
 * 新建模块只会多一个"谁都可以注入"的过宽绑定（`LogModule` 的同类纪律）。
 * 将来若出现第三个互不相关的长驻消费者，再统一抽 `AppScopeModule`。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    /** 通知投递端口。 */
    @Binds
    @Singleton
    abstract fun bindServiceNotifier(impl: ServiceNotifierImpl): ServiceNotifier

    /**
     * 熔断告警的反向端口 —— 由前台服务控制器实现。
     *
     * **这一行就是防环的落点**：`CircuitBreakerImpl` 经 `SafeModeNotifier` 依赖本端口，
     * 而本端口的实现是订阅 `CircuitBreaker` 的控制器；若改成"通知实现直接依赖控制器"，
     * Hilt 会报依赖环。
     */
    @Binds
    @Singleton
    abstract fun bindSafeModeAlertSink(impl: ForegroundServiceController): SafeModeAlertSink

    /**
     * 主页服务状态卡与事件源列表的端口（阶段 6b）——同样由控制器实现。
     *
     * ## 为什么绑到控制器而不是新写一个实现类
     * 控制器是 `EventSourceRegistry.start()/stop()` 的**唯一所有者**（阶段 5 的单所有者
     * 不变量）。若另写一个实现去读事件源状态，就出现了"第二个知道事件源状态的人"，
     * 而它能看到的只是控制器的**下游产物**——两者迟早漂移。
     *
     * 绑定之后，"常驻通知显示的 6/7"与"主页卡片显示的 6/7"来自**同一次**
     * `currentState()` 计算（见 `pushNotification` 的实现），结构上不可能不一致。
     */
    @Binds
    @Singleton
    abstract fun bindServiceStateProvider(impl: ForegroundServiceController): ServiceStateProvider

    companion object {
        /**
         * 前台服务控制器（生命周期的唯一编排者）。
         *
         * `circuitBreaker` **必须是 `Lazy`**：那是本模块唯一的断环点，见类 KDoc。
         * 控制器在 `register()` 里第一次 `get()` 它。
         */
        @Provides
        @Singleton
        fun provideForegroundServiceController(
            eventSourceRegistry: EventSourceRegistry,
            circuitBreaker: Lazy<CircuitBreaker>,
            notifier: ServiceNotifier,
            daemonSupervisor: DaemonSupervisor,
            @EventDispatcherScope scope: CoroutineScope,
        ): ForegroundServiceController =
            ForegroundServiceController(
                eventSourceRegistry = eventSourceRegistry,
                circuitBreaker = circuitBreaker,
                notifier = notifier,
                daemonSupervisor = daemonSupervisor,
                scope = scope,
            )

        // `ServiceNotificationChannels` 不需要 @Provides：它只有 `@ApplicationContext Context`
        // 一个依赖，`@Inject` 构造已足够。此处刻意**不写**转发方法——3c.1/3d/4 反复踩过
        // "Kotlin 默认参数对 Dagger 不可见 ⇒ MissingBinding"的坑型，
        // 少写一个转发就少一处会漂移的绑定。
    }
}
