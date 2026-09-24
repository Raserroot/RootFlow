package com.rootflow.data.event.android

import com.rootflow.data.di.EventDispatcherScope
import com.rootflow.data.event.EventSourceRegistry
import com.rootflow.data.event.EventSourceRegistryImpl
import com.rootflow.data.event.PowerEventSource
import com.rootflow.data.event.ScreenEventSource
import com.rootflow.data.event.WifiEventSource
import com.rootflow.data.event.WifiNetworkMonitor
import com.rootflow.domain.event.BroadcastRegistration
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.ForegroundDetector
import com.rootflow.domain.event.PermissionStatusProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * 阶段 3c.1 的 DI 装配：把 Android 侧适配器绑到 domain 端口，并汇总全部推送型事件源。
 *
 * ## 为什么是 `abstract class`（本阶段把 `EventModule` 也改为同构的原因）
 * `@Binds`（接口 → 实现）只能写在抽象模块里。3c.1 新增两个 domain 端口
 * （[BroadcastRegistration] / [PermissionStatusProvider]），用 `@Binds` 比写两个纯转发的
 * `@Provides` 更短、且不产生无意义的中间对象。
 *
 * ## 哪些源走构造函数注入、哪些走本模块
 * - `ScreenEventSource` / `PowerEventSource` / `BatteryEventSource` 的依赖都能由 Hilt 直接构造
 *   （`BroadcastRegistration` + `EventBus` / 无依赖），故**不写 `@Provides`**，直接用
 *   `@Inject constructor` —— 少一层转发，少一处会忘记同步的代码
 * - [WifiEventSource] 依赖 [WifiNetworkMonitor]（接口，需绑定）故只能在这里提供
 *
 * ## 按决策 13：本模块与四个适配器均不进单测
 * 它们只做 Android API 直调与绑定。绑定关系错的后果是**编译期/启动期失败**（Hilt 会报
 * `MissingBinding`），而不是运行期静默错误，因此真机启动一次即可确认。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AndroidEventSources {
    /** 动态广播注册：domain 端口 → `Context` 适配。 */
    @Binds
    @Singleton
    abstract fun bindBroadcastRegistration(impl: AndroidBroadcastRegistration): BroadcastRegistration

    /** 权限状态：domain 端口 → `Context` 适配。 */
    @Binds
    @Singleton
    abstract fun bindPermissionStatusProvider(impl: AndroidPermissionStatusProvider): PermissionStatusProvider

    companion object {
        /**
         * 纯状态机（`app_foreground` / `app_background` 的推断核心，阶段 3c.2）。
         *
         * **必须是 `@Singleton`**：状态机的基线（"上次前台包"）要跨轮询持续存在；
         * 若每次注入都新建，`A→B` 的切换会退化成"每次都是首次快照" → **永远不上报**。
         */
        @Provides
        @Singleton
        fun provideForegroundDetector(): ForegroundDetector = ForegroundDetector()

        /**
         * 轮询源所需的协程作用域（`UsageStatsPollingSource` 的构造依赖）。
         *
         * **为什么在这里提供而不是新建限定符**：3c.1/3c.2 的源都活在**同一个应用级作用域**
         * 里（随进程存活），语义上就是"事件 dispatcher 的作用域"。复用 `@EventDispatcherScope`
         * 避免再造一个"谁都可以注入的过宽绑定"（`LogModule` 的同类纪律）。
         */
        @Provides
        @Singleton
        fun provideEventSourceScope(
            @EventDispatcherScope scope: CoroutineScope,
        ): CoroutineScope = scope

        /** 默认网络回调：domain 端口 → `ConnectivityManager` 适配。 */
        @Provides
        @Singleton
        fun provideWifiNetworkMonitor(impl: AndroidNetworkMonitor): WifiNetworkMonitor = impl

        /**
         * `screen_on` / `screen_off` / `unlock` 动态源。
         *
         * **必须在此登记**：`EventSourceRegistryImpl` 通过 `Set<EventSource>`（Hilt multibinding）
         * 拿到全部源；漏掉一条 `@IntoSet` 会让该事件源**静默不启动**——没有编译错误，
         * 真机上只表现为"事件永不触发"。`EventSourceRegistryTest` 的
         * "状态覆盖全部 8 个事件" 用例即为此设的护栏。
         */
        @Provides
        @Singleton
        @IntoSet
        fun provideScreenEventSource(source: ScreenEventSource): EventSource = source

        /**
         * `wifi_changed` 源。
         *
         * 不传 `stateSource`：使用 `WifiEventSource` 的默认派生
         * （`TRANSPORT_WIFI` + 适配器算出的 `wifiConnected` 语义位）。
         */
        @Provides
        @Singleton
        @IntoSet
        fun provideWifiEventSource(source: WifiEventSource): EventSource = source

        /** 清单注册的电源源；显式列入 multibinding，否则不会进入 `Set<EventSource>`。 */
        @Provides
        @Singleton
        @IntoSet
        fun providePowerEventSource(source: PowerEventSource): EventSource = source

        // `battery_low` / `battery_okay` 源的 @IntoSet 贡献**不在此处**，见 `BatteryEventSourceModule`。
        // 把这第 4 条贡献放在本 companion object 里时 KSP 解析失败（错误会串到本模块的其余绑定上），
        // 移除该条贡献即 BUILD SUCCESSFUL —— 已用 A/B 对照定位，完整实验记录在该模块的 KDoc 里。
        // 拆成独立模块后行为等价（贡献到同一个 Set<EventSource>）且可编译。

        /** 事件源注册表（`@Inject constructor` 已可构造，此处仅显式绑定接口便于注入方使用）。 */
        @Provides
        @Singleton
        fun provideEventSourceRegistry(impl: EventSourceRegistryImpl): EventSourceRegistry = impl
    }
}
