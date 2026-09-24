package com.rootflow.data.di

import android.util.Log
import com.rootflow.data.log.LogPipelineImpl
import com.rootflow.domain.repository.LogPipeline
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

/** 日志管道内部作用域/调度器的限定符。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LogPipelineScope

/**
 * 日志管道的 DI 装配（阶段 2）。
 *
 * **本项目第一个 `@Module`**：此前 `runtime/` 全部依赖构造函数注入，无需模块。
 * 这里需要模块，是因为 [LogPipelineImpl] 的依赖里包含**无法由 Hilt 自行构造**的东西
 * ——[CoroutineScope] 与带默认值的批量参数（`Int`/`Long`）。
 *
 * ## 关于作用域归属（有意为之的临时决定）
 * 本阶段把"日志管道的作用域"定义在**日志模块内部**，而不是引入全局 `AppScopeModule`：
 * 阶段 2 只有日志需要长期作用域，过早抽象全局作用域会凭空造出一个"谁都可以注入"的东西。
 * 阶段 5 引入前台服务时若需要统一作用域，再单独建模块——届时只需把这里改为注入，
 * 管道内部实现不受影响。
 *
 * 作用域由 [SupervisorJob] 承载：单次运行的收集失败**不应**拖垮整个管道。
 * 两个 `@Provides` 都带 [LogPipelineScope] 限定符，避免裸 `CoroutineScope` /
 * `CoroutineDispatcher` 成为可被任意注入的过宽绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object LogModule {
    /** 管道内部协程作用域。 */
    @Provides
    @Singleton
    @LogPipelineScope
    fun provideLogPipelineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 管道驱动调度器（单独 provide 便于将来替换实现，而不必改作用域）。 */
    @Provides
    @Singleton
    @LogPipelineScope
    fun provideLogPipelineDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** 日志管道唯一实现。 */
    @Provides
    @Singleton
    fun provideLogPipeline(
        @LogPipelineScope scope: CoroutineScope,
        @LogPipelineScope dispatcher: CoroutineDispatcher,
    ): LogPipeline =
        LogPipelineImpl(
            scope = scope,
            dispatcher = dispatcher,
            // 阶段 3d：落库回调（`LogBatchSink`）自身失败时经此上报，**不静默**
            onWarning = { message -> Log.w(TAG, message) },
        )

    private const val TAG = "RootFlow"
}
