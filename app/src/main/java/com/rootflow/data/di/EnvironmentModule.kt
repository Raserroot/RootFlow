package com.rootflow.data.di

import com.rootflow.data.env.RootEnvironmentInfoProvider
import com.rootflow.domain.env.EnvironmentInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 环境信息端口的 DI 装配（阶段 6b）。
 *
 * ## 为什么单独一个模块（而不是塞进 `ServiceModule`）
 * `ServiceModule` 的职责是"前台服务的生命周期编排"，它的 KDoc 已经解释了
 * 依赖环与 `Lazy` 断环那套东西。环境探测与那套依赖图**毫无关系**，
 * 塞进去会让"这个模块在管什么"变得含糊。本项目已有的纪律是
 * "一个模块一个关注点"（`LogModule` / `EventModule` / `RunModule` /
 * `CircuitBreakerModule` / `ServiceModule` / `SettingsModule` 各自独立）。
 *
 * ## 为什么不需要 `@Provides`
 * [RootEnvironmentInfoProvider] 只有一个依赖 `RootShellManager`，
 * 而后者是 `@Inject` 构造的单例 ⇒ `@Binds` 足够。
 * 这里**刻意不写**转发用的 `@Provides`：3c.1 起反复踩过
 * "Kotlin 默认参数对 Dagger 不可见 ⇒ `MissingBinding`"的坑型，
 * 少写一个转发就少一处会漂移的绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnvironmentModule {
    /** 环境信息端口 → 唯一实现。 */
    @Binds
    @Singleton
    abstract fun bindEnvironmentInfoProvider(impl: RootEnvironmentInfoProvider): EnvironmentInfoProvider
}
