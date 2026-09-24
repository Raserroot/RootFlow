package com.rootflow.data.di

import android.util.Log
import com.rootflow.data.event.InMemoryEventBus
import com.rootflow.data.event.TriggerDispatcherImpl
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 事件调度器内部作用域/调度器的限定符。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EventDispatcherScope

/**
 * 「一直运行」监管协程所用调度器的限定符（阶段 10）。
 *
 * ## 为什么需要它（**是测试逼出来的，不是预先设计**）
 * `DaemonSupervisorImpl` 第一版在内部自建
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)`。那让它的单测**根本无法驱动**：
 * 测试用 `StandardTestDispatcher` 的虚拟时间，而监管协程跑在真实的 `Dispatchers.IO` 上
 * —— `advanceUntilIdle()` 推不动它，6 个用例全红。
 *
 * 把调度器变成**注入依赖**后，生产给 [Dispatchers.IO]、单测给测试调度器即可。
 *
 * ## 为什么注入调度器而不是注入 `CoroutineScope`
 * 监管的生命周期是"随前台服务起停"（`start()` / `stop()`），而注入的
 * `@EventDispatcherScope` 是**应用级、不可取消**的作用域。若直接用它，
 * `stop()` 就没有东西可取消 —— 会出现"服务停了但监管协程还在跑"。
 * 注入调度器则保留"每次 `start()` 自建一个可取消的 SupervisorJob"这一正确形态。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DaemonDispatcher

/**
 * 事件系统的 DI 装配（阶段 3b；阶段 3d 移除占位绑定）。
 *
 * ## 阶段 3d 的变化（本模块**不再**提供 `ScriptRunner` / `ScriptRunRegistry`）
 * 3b 在这里提供 `NoopScriptRunner` / `NoopScriptRunRegistry` 两个占位。
 * 3d 起真实实现由 `RunModule` 绑定（`@Binds` 到 `TriggeredScriptRunner` /
 * `RunAdmissionGateImpl`），**占位类已删除**。
 *
 * 这里保留两个接口的 import 仅用于文档引用（`TriggerDispatcherImpl` 的构造参数类型）。
 *
 * ## 调度器的 `start()`
 * 由 `RootFlowApp` 在应用启动时调用一次（与 `EventBusHolder.install` 同一处）。
 * 本模块只负责构造，不负责启动——避免 DI 阶段产生副作用（那会让单测难以隔离）。
 *
 * ## 阶段 3c.1/3c.2 的绑定在别处
 * 推送型事件源（含 `EventSource` 的 multibinding）与两个 domain 端口绑定
 * （`BroadcastRegistration` / `PermissionStatusProvider`）在
 * `data/event/android/AndroidEventSources.kt`；`usage_stats` 与两个闹钟源在
 * `AlarmEventSourceModule`。它们都是 `abstract class`，因为 `@Binds` 只能写在抽象模块里。
 * 本模块保持 `object`，**不**承担那些绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object EventModule {
    /** 事件总线（有界 + DROP_OLDEST）。 */
    @Provides
    @Singleton
    fun provideEventBus(): EventBus = InMemoryEventBus()

    /** 调度器内部作用域；由 `SupervisorJob` 承载，单个订阅失败不拖垮全局。 */
    @Provides
    @Singleton
    @EventDispatcherScope
    fun provideEventDispatcherScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 「一直运行」监管协程的调度器（阶段 10）。
     *
     * 用 `Dispatchers.IO`：监管循环要读 Room（`TriggerRepository.enabledForEvent`）
     * 与轮询运行集合，都是 I/O 性质。见 [DaemonDispatcher] 的 KDoc。
     */
    @Provides
    @Singleton
    @DaemonDispatcher
    fun provideDaemonDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * 触发调度器。
     *
     * `ScriptRunner` / `ScriptRunRegistry` 由 `RunModule` 提供（3d 起为真实实现）。
     * 本类**不能**靠 `@Inject` 构造：它的默认参数（防抖窗口 / 时钟 / 告警 / 分发后动作）
     * 对 Dagger 不可见，会报 `MissingBinding`（见 `TriggerDispatcherImpl` 的类 KDoc）。
     *
     * 阶段 4 新增 `circuitBreaker`：安全模式下**入口丢弃整个事件**（需求 §5.4）。
     * 它是 `CircuitBreakerImpl` 单例，与 `TriggeredScriptRunner` 注入的
     * `ScriptRunner`/`RunOutcomeSink` 共享同一份安全模式状态。
     */
    @Provides
    @Singleton
    fun provideTriggerDispatcher(
        triggerRepository: com.rootflow.domain.repository.TriggerRepository,
        eventChannel: EventChannel,
        eventBus: EventBus,
        circuitBreaker: CircuitBreaker,
        @EventDispatcherScope scope: CoroutineScope,
    ): TriggerDispatcherImpl =
        TriggerDispatcherImpl(
            triggerRepository = triggerRepository,
            eventChannel = eventChannel,
            eventBus = eventBus,
            scope = scope,
            onWarning = { message -> Log.w(TAG, message) },
            circuitBreaker = circuitBreaker,
        )

    private const val TAG = "RootFlow"
}
