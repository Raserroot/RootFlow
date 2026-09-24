package com.rootflow.data.di

import com.rootflow.data.event.FifoEventChannelImpl
import com.rootflow.domain.event.EventChannel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 事件投递通道的依赖装配（**P5**）。
 *
 * ## 为什么单开一个模块（而不是塞进 `EventModule`）
 * `EventModule` 是 `object`（只有 `@Provides`），而 `@Binds` **只能写在抽象模块里**
 * （`RunModule` / `CircuitBreakerModule` / `MasterSwitchModule` 都是这个形态）。
 * 把 `EventModule` 改成 `abstract class + companion object` 需要搬动它全部 5 个
 * `@Provides`，而收益只是少一个文件 —— 改动面与收益不成比例。
 *
 * ## 依赖环检查
 * [FifoEventChannelImpl] 只依赖 `RootShellManager`（`runtime` 层，无反向边）。
 * 它的消费者是 `TriggeredScriptRunner` 与 `TriggerDispatcherImpl`，
 * 两者都不被它依赖 ⇒ **不存在环**（这一点只在 `hiltJavaCompileDebug` 暴露）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventChannelModule {
    /** 事件通道端口（FIFO，见 `FifoEventChannelImpl` 的三条实测约束）。 */
    @Binds
    @Singleton
    abstract fun bindEventChannel(impl: FifoEventChannelImpl): EventChannel
}
