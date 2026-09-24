package com.rootflow.domain.event

/**
 * 脚本投递端口：把"某个脚本被某个事件触发"这件事交给执行侧。
 *
 * ## 为什么是端口而不是直接用 `ScriptRunCoordinator`
 * 调度器手上只有 `scriptId`（`triggers.script_id`），而 `ScriptRunCoordinator.startLogging`
 * 需要的是 `runtime.ScriptEntity`（含**正文**）。把 `scriptId` 变成可执行实体需要
 * `ScriptRepository.load` + root 通道读文件。
 *
 * 用端口把这条依赖切开，[TriggerDispatcher] 才能在 3b 独立实现与测试
 * （阶段 2 的 `LogPipeline` 端口也是同一手法）。
 *
 * ## 实现沿革（**勿再引入占位实现**）
 * 3b 注入 `NoopScriptRunner`（只记日志、返回未受理），3d 替换为
 * `data/run/TriggeredScriptRunner`（真实装载 + 准入 + 启动），占位类已删除。
 */
interface ScriptRunner {
    /**
     * 启动一次脚本运行。
     *
     * @param scriptId 待运行脚本的 id
     * @param triggerEvent 触发事件键（写入 `RunContext.triggerEvent`，需求 §3.1）
     * @param payload 事件负载（需求 §3.2 的 `ROOTFLOW_EVENT_PAYLOAD`）；无则 `null`。
     *   3d 起由 `TriggerDispatcher` 按
     *   `payloadOverride` > `TriggerRow.params.payload` > `event.payloadJson()` 选定
     *   （见 `PROJECT_STATE.md` 偏离项 **D10**）
     * @param timeoutOverrideMillis **超时覆盖**（阶段 10 新增；默认 `null` = 按脚本自身设置）。
     *   只有「一直运行」的常驻脚本会传值：它必须**不受时长限制** ——
     *   否则一个正常运行的常驻进程会在默认 60 秒被当超时杀掉、并计一次失败
     *   （见 `TriggeredScriptRunner.effectiveTimeoutMillis` 与 `DaemonSupervisor` 的 KDoc）。
     *   `<= 0` 表示**不限时**（与 `ScriptRunCoordinator` 的契约一致）。
     * @return `true` 表示**已受理**（脚本执行是冷流，稍后才发生）；
     *   `false` 表示未启动（装载失败 / 脚本被禁用 / 闸门拒绝）
     */
    suspend fun start(
        scriptId: Long,
        triggerEvent: String,
        payload: String?,
        timeoutOverrideMillis: Long? = null,
    ): Boolean
}

/**
 * "该脚本当前是否正在运行"的查询端口——供 [TriggerDispatcher] 做**重入拒绝**。
 *
 * 需求 §2.2：「同一脚本不允许并发运行（**重入直接拒绝并记录日志**）」。
 *
 * ## 实现沿革
 * 3b 注入 `NoopScriptRunRegistry`（恒返回 `false`），因此**当时真机上不会拒绝任何重入**
 * （该分支已由单测覆盖，但只是逻辑上的）。3d 起由
 * `data/run/RunAdmissionGateImpl` 实现（同一实例也承担准入闸门的 `tryAcquire` / `release`），
 * **重入拒绝自 3d 起在真机生效**，占位类已删除。
 */
interface ScriptRunRegistry {
    /** @return `true` 表示该脚本当前有运行中的实例。 */
    suspend fun isRunning(scriptId: Long): Boolean
}
