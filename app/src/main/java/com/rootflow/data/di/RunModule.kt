package com.rootflow.data.di

import android.content.Context
import com.rootflow.data.event.FileBootMarkerStore
import com.rootflow.data.run.DaemonSupervisorImpl
import com.rootflow.data.run.RunAdmissionGateImpl
import com.rootflow.data.run.RunHistoryCollector
import com.rootflow.data.run.RunHistoryWriter
import com.rootflow.data.run.RunOutcomeSink
import com.rootflow.data.run.RunSessionRegistry
import com.rootflow.data.run.TriggeredScriptRunner
import com.rootflow.domain.event.BootMarkerStore
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.DaemonRestartPolicy
import com.rootflow.domain.event.DaemonSupervisor
import com.rootflow.domain.event.EventChannel
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.RunAdmissionGate
import com.rootflow.domain.event.ScriptRunRegistry
import com.rootflow.domain.event.ScriptRunner
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.run.RunActivityProvider
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
 * 端到端接线的 DI 装配（阶段 3d）。
 *
 * ## 本模块替换掉 3b 的两个占位
 * | 端口 | 3b 占位 | 3d 真实实现 |
 * |---|---|---|
 * | [ScriptRunner] | `NoopScriptRunner`（只记日志，返回未受理） | `TriggeredScriptRunner` |
 * | [ScriptRunRegistry] | `NoopScriptRunRegistry`（恒 `false`） | `RunAdmissionGateImpl` |
 *
 * 3b 的占位类（`data/event/Placeholders.kt`）随本模块上线一并**删除**——
 * 留着"两种实现并存"会让后续会话误以为还能切回占位。
 *
 * ## 为什么有些类需要 `@Provides`、有些直接用 `@Inject`
 * - `RunAdmissionGateImpl`：无参 `@Inject` 构造 → Hilt 直接绑定（接口绑定见下面的 `@Binds`）
 * - `TriggeredScriptRunner`：依赖 `CoroutineScope`，必须用 `@EventDispatcherScope` 限定，
 *   而限定符无法写在构造参数上（构造注入不接受限定符注解的默认值形态）
 * - `RunHistoryCollector`：同上（依赖作用域）
 * - `FileBootMarkerStore`：需要从 `Context` 派生 `File`。`File` 本身无法被 Dagger 构造，
 *   而把 `Context` 直接注入 `data/event` 的存储实现会把 Android 类型带进纯逻辑层
 *   （该层的单测在纯 JVM 下跑，`Context` 不可用）
 *
 * ## 绑定形态：`abstract class` + 嵌套 `object`
 * `@Binds` 只能写在抽象模块里，`@Provides` 的伴生对象里放不下 `@Binds`。
 * 因此拆成"抽象类负责 `@Binds`、companion object 负责 `@Provides`"的既有形态
 * （与 `AndroidEventSources` / `AlarmEventSourceModule` 同构）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RunModule {
    /** 真实投递端口（替换 3b 的 `NoopScriptRunner`）。 */
    @Binds
    @Singleton
    abstract fun bindScriptRunner(impl: TriggeredScriptRunner): ScriptRunner

    /**
     * 真实运行集合（替换 3b 的 `NoopScriptRunRegistry`）。
     *
     * 绑定之后 3b 遗留项「真机上重入拒绝不生效」才真正解决：`TriggerDispatcherImpl`
     * 注入的就是这个闸门。
     */
    @Binds
    @Singleton
    abstract fun bindScriptRunRegistry(impl: RunAdmissionGateImpl): ScriptRunRegistry

    /**
     * 闸门自身的接口绑定。
     *
     * 两个绑定**都必须存在**：`TriggerDispatcherImpl` 注入的是 `ScriptRunRegistry`
     * （3b 既有端口，只问"是否在跑"），而 `TriggeredScriptRunner` 注入的是
     * `RunAdmissionGate`（3d 新增的准入能力）。二者是同一个单例实例
     * （`@Singleton` + 同一个 `impl`），因此"调度器问到的状态"与"投递侧占用的状态"
     * 必然一致——这正是重入拒绝能生效的前提。
     */
    @Binds
    @Singleton
    abstract fun bindRunAdmissionGate(impl: RunAdmissionGateImpl): RunAdmissionGate

    /** 落库订阅者（`LogPipeline` 的批次回调唯一实现）。 */
    @Binds
    @Singleton
    abstract fun bindLogBatchSink(impl: RunHistoryCollector): LogBatchSink

    /**
     * 「一直运行」常驻脚本的监管端口（阶段 10）。
     *
     * 绑定成**单例**：`ForegroundServiceController` 的 `register()` / `unregister()` /
     * `onSafeModeAlert()` 都会引用它（起 / 停 / 中止）。若每次注入拿到不同实例，
     * "停"就停不掉"起"的那一份监管协程 —— 会出现服务已停但常驻脚本仍在跑的漏网形态。
     */
    @Binds
    @Singleton
    abstract fun bindDaemonSupervisor(impl: DaemonSupervisorImpl): DaemonSupervisor

    /**
     * 「最近一次运行」的只读端口（阶段 6b）。
     *
     * **与 `TriggeredScriptRunner` 注入的是同一个 `RunSessionRegistry` 单例**：
     * 一个 `@Binds` 指向 `@Inject` 构造的 `@Singleton` 类，Hilt 只建一个实例，
     * 因此主页终端看到的 `latestRunId` 与熔断第 2 步用的 `activeRuns()`
     * 必然来自同一份登记状态。
     */
    @Binds
    @Singleton
    abstract fun bindRunActivityProvider(impl: RunSessionRegistry): RunActivityProvider

    /** boot 标记存储（D9 的持久化半）。 */
    @Binds
    @Singleton
    abstract fun bindBootMarkerStore(impl: FileBootMarkerStore): BootMarkerStore

    companion object {
        /**
         * 「一直运行」的重启节奏（阶段 10，用户 2026-09-20 裁定）。
         *
         * ## 为什么用 `@Provides` 而不是让 `DaemonRestartPolicy` 自己 `@Inject`
         * 它是一个**纯数据类**，位于 `domain/event` —— 按 `AGENTS.md` 的分层约束，
         * domain 不得依赖 DI 框架。给它加 `@Inject` 会把 Dagger 带进 domain 层。
         * 这里显式提供 `DEFAULT` 实例；单测可以直接 new 一套更短的节奏（毫秒级跑完）。
         */
        @Provides
        @Singleton
        fun provideDaemonRestartPolicy(): DaemonRestartPolicy = DaemonRestartPolicy.DEFAULT

        /**
         * 真实投递端口的构造。
         *
         * `scope` 用 `@EventDispatcherScope`：脚本日志收集必须活在**应用级**作用域里，
         * 否则"触发即运行"的协程会随某个短命调用方一起被取消。
         *
         * 阶段 4 新增三个依赖（都由 `CircuitBreakerModule` / `RunModule` 自身提供）：
         * - `circuitBreaker`：安全模式放行判定 + 受理计数
         * - `sessionRegistry`：熔断第 2 步按 `runId` 终止**每一个**在跑的运行
         * - `outcomeSink`：运行结局（带退出码）回流给熔断器
         *
         * `outcomeSink` 与 `circuitBreaker` 是**同一个** `CircuitBreakerImpl` 单例
         * （两个 `@Binds` 指向同一实现），这一点由 `CircuitBreakerModule` 的 KDoc 说明。
         *
         * P3（总开关重构）新增一个依赖：
         * - `masterSwitch`：准入链**最前**一道闸门（总闸关闭 ⇒ 任何脚本都不得启动，含
         *   `runOnSafeMode` 的）。它与 `circuitBreaker` 正交 —— 前者是用户意图，
         *   后者是故障状态（见 `MasterSwitch` 的 KDoc 对照表）。
         *
         * P5 再新增一个：
         * - `eventChannel`：每次运行开一条 FIFO，路径经 `ROOTFLOW_EVENT_FIFO` 注入脚本 ——
         *   这是"事件=通知"能成立的那条通道（见 `EventChannel` 的 KDoc）。
         */
        @Provides
        @Singleton
        fun provideTriggeredScriptRunner(
            scriptRepository: ScriptRepository,
            coordinator: com.rootflow.data.run.ScriptRunCoordinator,
            gate: RunAdmissionGate,
            sink: LogBatchSink,
            @EventDispatcherScope scope: CoroutineScope,
            circuitBreaker: CircuitBreaker,
            masterSwitch: MasterSwitch,
            eventChannel: EventChannel,
            sessionRegistry: RunSessionRegistry,
            outcomeSink: RunOutcomeSink,
        ): TriggeredScriptRunner =
            TriggeredScriptRunner(
                scriptRepository = scriptRepository,
                coordinator = coordinator,
                gate = gate,
                batchSink = sink,
                scope = scope,
                circuitBreaker = circuitBreaker,
                masterSwitch = masterSwitch,
                eventChannel = eventChannel,
                sessionRegistry = sessionRegistry,
                outcomeSink = outcomeSink,
            )

        /**
         * 落库订阅者。
         *
         * ## 为什么必须显式 `@Provides`（而不是靠 `@Inject` 构造）
         * 它的 `@Inject` 构造带默认参数（`clock` / `onWarning` 等），而 **Kotlin 默认参数
         * 对 Dagger 不可见**——Dagger 会把它们当成必须绑定的依赖，报
         * `MissingBinding: Function0<Long>` 之类（3c.1 起反复实测的坑型）。
         * `scope` 同理需要限定符。
         */
        @Provides
        @Singleton
        fun provideRunHistoryCollector(
            writer: RunHistoryWriter,
            @EventDispatcherScope scope: CoroutineScope,
        ): RunHistoryCollector = RunHistoryCollector(writer = writer, scope = scope)

        /**
         * boot 标记文件（D9）。
         *
         * 位置：`filesDir/boot_marker.txt`——应用私有目录，**无需 root 通道**
         * （见 `BootMarkerStore` 的 KDoc：每次启动都要读写，不能背一次 `su` 的约 300ms 开销）。
         * 用 `filesDir` 而非 `cacheDir`：系统可在低存储时清理 `cacheDir`，
         * 那会让标记静默丢失（后果是多补发一次 boot——可接受，但没必要）。
         */
        @Provides
        @Singleton
        fun provideBootMarkerFile(
            @ApplicationContext context: Context,
        ): File = File(context.filesDir, BOOT_MARKER_FILE_NAME)

        /** boot 标记存储实现。 */
        @Provides
        @Singleton
        fun provideFileBootMarkerStore(markerFile: File): FileBootMarkerStore = FileBootMarkerStore(markerFile)

        /** boot 标记文件名。 */
        const val BOOT_MARKER_FILE_NAME: String = "boot_marker.txt"
    }
}
