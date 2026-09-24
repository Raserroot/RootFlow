package com.rootflow.domain.event

/**
 * 启动准入闸门（阶段 3d，需求 §2.2）。
 *
 * ## 它同时承担两件事
 * | 约束 | 需求 | 违反后果 |
 * |---|---|---|
 * | **重入拒绝** | §2.2「同一脚本不允许并发运行（直接拒绝并记录日志）」 | 同一脚本叠加跑，互相踩状态 |
 * | **全局并发上限** | §2.2「全局同时运行上限 4」 | Root 侧进程泛滥，低端机被拖垮 |
 *
 * ## 为什么实现既有端口而不是新接口
 * `TriggerDispatcher` 在分发**之前**就要问"这个脚本是否在跑"（重入拒绝），
 * 而 3b 已为此定义了 [ScriptRunRegistry]。真实运行集合本就该由执行链路维护，
 * 因此 3d 让同一个闸门既做"查询"（[ScriptRunRegistry.isRunning]）又做"准入"
 * （[tryAcquire] / [release]），避免"两处真相"。
 *
 * ## 决策 C：内存 + 进程内，**不持久化**
 * 进程被杀后无法可靠判断"脚本是否还在跑"（PGID 对账属阶段 5 保活）。
 * 持久化只会制造**假状态**——一个永远拒绝启动的闸门比偶尔的重复接纳更糟。
 * 因此 `activeCount` 随进程重启归零，宁可在极窄场景下放过一次。
 *
 * ## 释放责任（3d 的硬约束）
 * 每次成功的 [tryAcquire] **必须**配一次 [release]，否则该脚本的重入拒绝
 * **永久生效**——那比不拒绝更糟。生产路径由 `RunSession.job.invokeOnCompletion`
 * 保证（正常结束 / 异常 / 取消三条路径都会触发）。
 */
interface RunAdmissionGate : ScriptRunRegistry {
    /**
     * 请求启动一个脚本。
     *
     * @param scriptId 待启动脚本
     * @return 见 [AdmissionResult]；**只有** [AdmissionResult.Accepted] 允许继续
     */
    fun tryAcquire(scriptId: Long): AdmissionResult

    /**
     * 释放一次占用。
     *
     * 幂等：未占用的 `scriptId` 调用它无副作用（不抛、不降计数）。
     */
    fun release(scriptId: Long)

    /** 当前正在运行的脚本数（全局上限判定与真机判读用）。 */
    val activeCount: Int

    /** 准入结论。刻意不是布尔：阶段 6 的 UI 与真机日志都需要具体原因。 */
    sealed interface AdmissionResult {
        /** 允许启动。 */
        data object Accepted : AdmissionResult

        /** 该脚本已在运行（需求 §2.2 的重入拒绝）。 */
        data object ReentryRejected : AdmissionResult

        /** 全局并发已达上限（需求 §2.2）。 */
        data object GlobalLimitReached : AdmissionResult
    }

    companion object {
        /** 需求 §2.2：全局同时运行上限 4。 */
        const val DEFAULT_MAX_CONCURRENT_RUNS: Int = 4
    }
}
