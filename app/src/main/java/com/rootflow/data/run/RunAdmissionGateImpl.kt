package com.rootflow.data.run

import com.rootflow.domain.event.RunAdmissionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunAdmissionGate] 的实现（阶段 3d，需求 §2.2 + 决策 C）。
 *
 * ## 为什么叫 `Impl` 而不是 `RunAdmissionGate`
 * 3d 方案 §7 把端口与实现的**名字**都写作 `RunAdmissionGate`（它当时假设端口是
 * `ScriptRunRegistry`）。落地时端口必须显式表达"准入"语义（`tryAcquire` / `release` /
 * `activeCount` 不属于 3b 的"查询是否在运行"），因此端口占用了该名字
 * （`domain/event/RunAdmissionGate.kt`），实现加 `Impl` 后缀——与本仓库
 * `EventSourceRegistry` / `EventSourceRegistryImpl`、`LogPipeline` / `LogPipelineImpl`
 * 的既有命名一致。**语义与方案完全一致**，仅后缀不同。
 *
 * ## 状态形态
 * 一个 `MutableSet<Long>`（正在运行的 `scriptId`）+ 一把对象锁。
 *
 * - **重入**：`tryAcquire` 时若 `scriptId` 已在集合中 → [AdmissionResult.ReentryRejected]
 * - **全局上限**：[RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS]（4）
 * - **[release] 幂等**：移除不存在的元素本就无副作用
 *
 * ## 为什么用 `synchronized` 而不是协程 `Mutex`
 * [tryAcquire] / [release] / [activeCount] 都是**同步、非挂起**的短操作
 * （纯内存集合读写），没有可挂起点。用 `Mutex` 会把它们变成挂起函数，
 * 迫使调用方（`TriggeredScriptRunner.start` 的失败分支与 `invokeOnCompletion` 回调）
 * 引入额外作用域才能释放——而 `Job.invokeOnCompletion` 回调**本身就是同步的**，
 * 在里面 `launch` 一个协程去释放闸门，会在作用域取消/进程退出时静默丢失释放。
 * 同步实现让"释放"成为**不可能被取消**的操作。
 *
 * ## 决策 C：不持久化
 * 见 [RunAdmissionGate] 的 KDoc。进程重启后 `activeCount` 归零，
 * 宁可放过一次也不留下"永远拒绝启动"的假状态。
 *
 * ## 构造函数形态（勿改回带默认值的 `@Inject` 构造）
 * [maxConcurrentRuns] 走 `internal` 次构造函数：Kotlin 默认参数对 Dagger 不可见，
 * 放进 `@Inject` 构造会让它尝试绑定额外的 `Int` 而报 `MissingBinding`
 * （3c.1/3c.2 反复实测的坑型）。
 */
@Singleton
class RunAdmissionGateImpl
    @Inject
    constructor() : RunAdmissionGate {
        /** 测试缝：注入自定义上限（生产走 [RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS]）。 */
        internal constructor(maxConcurrentRuns: Int) : this() {
            this.maxConcurrentRuns = maxConcurrentRuns
        }

        private var maxConcurrentRuns: Int = RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS

        /** 正在运行的脚本集合。由 [lock] 保护。 */
        private val running = mutableSetOf<Long>()

        private val lock = Any()

        override fun tryAcquire(scriptId: Long): RunAdmissionGate.AdmissionResult =
            synchronized(lock) {
                when {
                    running.contains(scriptId) -> RunAdmissionGate.AdmissionResult.ReentryRejected
                    running.size >= maxConcurrentRuns -> RunAdmissionGate.AdmissionResult.GlobalLimitReached
                    else -> {
                        running += scriptId
                        RunAdmissionGate.AdmissionResult.Accepted
                    }
                }
            }

        override fun release(scriptId: Long) {
            synchronized(lock) { running -= scriptId }
        }

        override val activeCount: Int
            get() = synchronized(lock) { running.size }

        override suspend fun isRunning(scriptId: Long): Boolean = synchronized(lock) { running.contains(scriptId) }

        /** 当前占用的脚本快照（真机判读 + 单测断言用；顺序不保证）。 */
        internal fun activeScripts(): Set<Long> = synchronized(lock) { running.toSet() }
    }
