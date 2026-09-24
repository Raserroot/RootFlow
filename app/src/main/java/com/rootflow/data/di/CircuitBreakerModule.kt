package com.rootflow.data.di

import android.content.Context
import android.content.SharedPreferences
import com.rootflow.data.event.BootloopGuard
import com.rootflow.data.event.CircuitBreakerImpl
import com.rootflow.data.event.NotificationSafeModeNotifier
import com.rootflow.data.event.RootHealthProbeImpl
import com.rootflow.data.event.SharedPrefsCrashMarkerStore
import com.rootflow.data.run.RunOutcomeSink
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.CrashMarkerStore
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.SafeModeNotifier
import com.rootflow.runtime.ProcessGroupManager
import com.rootflow.runtime.RootShellManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/**
 * 安全熔断的 DI 装配（阶段 4，需求 §5）。
 *
 * ## 绑定一览
 * | 端口 | 实现 | 形态 |
 * |---|---|---|
 * | [CircuitBreaker] | [CircuitBreakerImpl] | `@Binds` |
 * | [RunOutcomeSink] | [CircuitBreakerImpl] | `@Binds`（**同一个实例**） |
 * | [RootHealthProbe] | [RootHealthProbeImpl] | `@Binds` |
 * | [CrashMarkerStore] | [SharedPrefsCrashMarkerStore] | `@Binds` |
 * | [SafeModeNotifier] | [NotificationSafeModeNotifier] | `@Binds`（**阶段 5 起为真实通知**；防环见该类 KDoc） |
 *
 * `CircuitBreakerImpl` 同时以两个端口暴露，且两个 `@Binds` 指向**同一个实现类**：
 * Hilt 对 `@Singleton` 实现只建一个实例，因此 `TriggeredScriptRunner` 注入的
 * `RunOutcomeSink` 与 `TriggerDispatcherImpl` 注入的 `CircuitBreaker` 必然是同一对象——
 * 这正是"熔断了，结局回调也进同一个判定机"的前提。
 *
 * ## 为什么 `SharedPreferences` 必须 `@Provides`
 * Hilt 无法凭空构造它（需要 `Context` + 文件名 + `MODE_PRIVATE`）。
 * 文件名取自 [SharedPrefsCrashMarkerStore.PREFS_NAME]，**不在本模块另起字面量**：
 * 两处各写一个文件名会让"改了实现里的名字、DI 还提供旧的"成为静默错配。
 *
 * ## 为什么 `ProcessGroupManager` 也在这里 `@Provides`（**阶段 4 修正**）
 * 它本有无参 `@Inject` 构造，因此 Hilt 图里**原本就有**一个隐式绑定——那个绑定用的是
 * `DEFAULT_PGID_DIR = /data/local/tmp`。而脚本实际执行时用的是**本模块显式提供的这个实例**
 * （`ShellScriptRuntime` 与 `ScriptRunCoordinator` 都由 Hilt 注入），
 * 于是"写 PGID 的实例"与"读 PGID 的实例"必须**是同一个**，否则熔断的第 2 步
 * （终止所有运行中脚本）会因为 `pgidResolved=false` 而**静默失效**。
 *
 * 显式 `@Provides` 覆盖隐式绑定后，全项目只有**一处** PGID 路径来源，
 * `RootFlowApp` 原来的自建实例已删除。
 *
 * ## PGID 文件放 `cacheDir`（**不是**脚本正文所在的 `/data/local/tmp`）
 * 这不是"两套路径"，而是两类文件的**寿命不同**：
 * | 文件 | 位置 | 卸载后 | 理由 |
 * |---|---|---|---|
 * | 脚本正文 / meta | `/data/local/tmp/rootflow/` | **保留** | "救砖"定位：重装 App 后脚本还在（偏离项 D7 的副作用，有意接受） |
 * | PGID 文件 `.rf_pgid_<runId>` | App 私有 `cacheDir` | **清除** | 它是**临时文件**：只在一次运行的生命周期内有意义，App 没了就没有"进程组"要终止 |
 *
 * `ProcessGroupManager` 自身的 KDoc 也是这么写的（"生产路径应由调用方注入 App 私有 `cacheDir`"），
 * `DEFAULT_PGID_DIR` 保留给单测。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CircuitBreakerModule {
    /** 熔断器端口。 */
    @Binds
    @Singleton
    abstract fun bindCircuitBreaker(impl: CircuitBreakerImpl): CircuitBreaker

    /**
     * 运行结局接收端口（`ScriptRunCoordinator` → 熔断器）。
     *
     * 与 [bindCircuitBreaker] 指向同一实现 ⇒ 同一单例（见类 KDoc）。
     */
    @Binds
    @Singleton
    abstract fun bindRunOutcomeSink(impl: CircuitBreakerImpl): RunOutcomeSink

    /** Root 健康探针（`su -c echo` + 外部安全模式探测）。 */
    @Binds
    @Singleton
    abstract fun bindRootHealthProbe(impl: RootHealthProbeImpl): RootHealthProbe

    /** Bootloop 崩溃标记存储（`SharedPreferences`，`commit()` 同步落盘）。 */
    @Binds
    @Singleton
    abstract fun bindCrashMarkerStore(impl: SharedPrefsCrashMarkerStore): CrashMarkerStore

    /**
     * 安全模式通知（**阶段 5 起为真实通知**，需求 §5.2 第 4/5 步）。
     *
     * ## 阶段 4 → 阶段 5 的替换
     * 阶段 4 绑的是 `NoopSafeModeNotifier`（只记一行日志），那是有意的过渡形态；
     * 阶段 5 换成 [NotificationSafeModeNotifier]，它经 `SafeModeAlertSink` 端口
     * 把告警交给前台服务控制器。
     *
     * **防环**：本类**不得**直接依赖 `ForegroundServiceController`（后者订阅
     * `CircuitBreaker`，会形成依赖环并让 `hiltJavaCompileDebug` 报
     * `Found a dependency cycle`）。`SafeModeAlertSink` 的 KDoc 有完整论证。
     *
     * **刻意不做成可选绑定**：可空参数会让调用点散落 `?.`，
     * 漏接一处就是"熔断了但用户毫无感知"（见 `SafeModeNotifier` 的 KDoc）。
     */
    @Binds
    @Singleton
    abstract fun bindSafeModeNotifier(impl: NotificationSafeModeNotifier): SafeModeNotifier

    companion object {
        /**
         * Bootloop 崩溃标记的 `SharedPreferences`。
         *
         * 用 `MODE_PRIVATE`（应用私有目录，**不走 root 通道**）：它是每次启动都要读写的
         * 关键路径，不能背一次 `su` 的约 300ms 开销。
         */
        @Provides
        @Singleton
        fun provideCrashMarkerPreferences(
            @ApplicationContext context: Context,
        ): SharedPreferences =
            context.getSharedPreferences(
                SharedPrefsCrashMarkerStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            )

        /**
         * 进程组管理（**全项目唯一的 PGID 路径来源**，见类 KDoc）。
         *
         * PGID 文件落在 App 私有 `cacheDir`：它是临时文件，随卸载一起清掉。
         */
        @Provides
        @Singleton
        fun provideProcessGroupManager(
            rootShellManager: RootShellManager,
            @ApplicationContext context: Context,
        ): ProcessGroupManager =
            ProcessGroupManager(rootShellManager) { runId ->
                File(context.cacheDir, "${ProcessGroupManager.PGID_FILE_PREFIX}$runId")
            }

        /**
         * Bootloop 兜底。
         *
         * ## 为什么必须显式 `@Provides`（不能靠 `@Inject` 构造）
         * 它的 `@Inject` 构造需要 `CoroutineScope`（需限定符），而**限定符无法写在
         * 可空函数类型参数上**；`BootloopGuard` 的 KDoc 也写明"勿改成带默认值的 `@Inject` 构造"
         * —— Kotlin 默认参数对 Dagger 不可见，会报 `MissingBinding: Function0<Long>`。
         *
         * ## 为什么**不注入** `CircuitBreaker`
         * 那会形成 `CircuitBreakerImpl → BootloopGuard → CircuitBreakerImpl` 的**循环**，
         * Hilt 在 `hiltJavaCompileDebug` 阶段直接报 `Found a dependency cycle`。
         * 因此 `onTrip` 传 `null`，熔断动作由 `RootFlowApp` 驱动：
         * `evaluateStartup()` 返回 `true` ⇒ 调 `circuitBreaker.tripForBootloop(crashes)`。
         * 依赖方向保持单向（guard 不认识熔断器）。
         *
         * ## `scope` 用调度器作用域是**硬的**（不是随便挑的）
         * `BootloopGuard.scheduleHealthMark()` 要 `delay(60s)` 后落"健康标记"；
         * 这个作业必须活在应用级作用域里 —— 用短命作用域会让标记永不落盘，
         * 于是**每次启动都被计成一次崩溃**，连开三次就误熔断。
         */
        @Provides
        @Singleton
        fun provideBootloopGuard(
            store: CrashMarkerStore,
            @EventDispatcherScope scope: CoroutineScope,
        ): BootloopGuard =
            BootloopGuard(
                store = store,
                scope = scope,
                onTrip = null,
            )
    }
}
