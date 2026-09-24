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
     * @param script 待执行脚本
     * @param ctx 运行上下文（触发事件、环境变量）
     * @return 本次运行的句柄；脚本尚未执行
     */
    suspend fun run(
        script: ScriptEntity,
        ctx: RunContext,
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
