package com.rootflow.runtime

/**
 * 脚本运行时抽象。
 *
 * 分层意义：`domain/` 与 `ui/` 只依赖本接口，不依赖任何具体语言实现；
 * v1 只提供 [ShellScriptRuntime]，Lua 运行时留待后续（`ROADMAP.md` 之外的范围）。
 *
 * 实现方必须遵守的契约：
 * - [run] **不在调用时执行脚本**，只创建 [RunHandle]；真正的执行发生在
 *   `RunHandle.output` 第一次被收集时（冷流，见 [RunHandle] 的收集语义）。
 * - [run] 本身可取消、不阻塞调用线程。
 *
 * @see RunHandle
 * @see ShellScriptRuntime
 */
interface ScriptRuntime {
    /**
     * 创建一次运行的句柄。
     *
     * ## ★ 为什么必须传入 `runId`（11e 补丁4 修的真缺陷）
     * `RunHandle.id` 与"这次运行对外暴露的 runId"**必须是同一个值** —— 否则
     * `ProcessGroupManager` 按 runId 去读 `.rf_pgid_<runId>` 时永远找不到文件
     * （文件实际叫 `.rf_pgid_<handle.id>`），于是超时终止与熔断的"杀进程"
     * 全都变成**空操作**，而日志上只显示"pgid 未解析"，看起来像设备问题。
     *
     * 调用方（`ScriptRunCoordinator`）自 P5 起就需要**提前**知道 runId
     * （事件通道路径 `ipc/<runId>.q` 必须在 `run` 之前建好并注入环境），
     * 因此由它生成、本方法接收，而不是反过来在 `RunHandle` 里造一个再回传。
     *
     * @param script 待执行脚本
     * @param ctx 运行上下文（触发事件、环境变量）
     * @param runId 本次运行的标识；**原样**成为 `RunHandle.id`
     * @return 本次运行的句柄；脚本尚未执行
     */
    suspend fun run(
        script: ScriptEntity,
        ctx: RunContext,
        runId: String,
    ): RunHandle

    /**
     * 请求终止一次运行。
     *
     * **阶段 1b 未实现真正的终止**：当前仅将句柄标记为已 kill，使尚未开始的
     * 执行被跳过（见 [RunHandle.killed] 的说明）。进程组级强制终止（kill PGID、
     * SIGTERM→SIGKILL 递进）属阶段 1c。
     *
     * @param handle [run] 返回的句柄
     * @param force `true` 表示跳过优雅终止阶段；阶段 1b 无语义差异
     */
    suspend fun kill(
        handle: RunHandle,
        force: Boolean = false,
    )
}
