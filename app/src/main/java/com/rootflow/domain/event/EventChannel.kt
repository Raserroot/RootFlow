package com.rootflow.domain.event

/**
 * **事件投递通道**（P5 / 方案 §2.3 的 C1 端口）。
 *
 * ## 它解决的问题
 * 总开关重构前，`ROOTFLOW_EVENT` 只在**启动脚本时**注入一次 ⇒ **常驻脚本收不到后续事件**：
 * 它启动之后外面发生的事，它一无所知。C 的语义是「事件 = 投递给**正在运行的**脚本的通知」，
 * 因此需要一条能给**运行中的进程**送消息的通道 —— 本端口就是那条通道。
 *
 * ## 为什么是 FIFO（而不是 `tail -f` 事件日志 / inotify）
 * 方案 §2.3 三方案对比的结论：
 * - 脚本侧只需 `read -r line < $ROOTFLOW_EVENT_FIFO` —— 对目标用户（写 shell 的人）零学习成本
 * - **天然阻塞、无轮询**：不烧 CPU（`tail -f` 与 inotify 轮询都要持续占用）
 * - **可回退**：读不到就超时返回，脚本可以带超时轮询
 *
 * ## 生命周期：与**一次运行**绑定（不是与脚本绑定）
 * ```
 * TriggeredScriptRunner.start
 *   ① open(runId)   → mkfifo + chmod 666，返回 FIFO 路径
 *   ② 该路径经 ROOTFLOW_EVENT_FIFO 注入脚本环境
 *   ③ 事件到达      → deliver(runId, line)
 *   ④ 运行结束      → close(runId)（删掉 FIFO）
 * ```
 * 与运行同寿的理由见 `RootFlowPaths.eventFifo` 的 KDoc（跨轮次不串队列）。
 */
interface EventChannel {
    /**
     * 为一次运行开通道。
     *
     * ## ★ 为什么还要 `scriptId`（通道路径里只有 `runId`）
     * **投递侧只知道 `scriptId`**：`TriggerDispatcher` 拿到的是一个事件与一批订阅
     * （`TriggerRepository.forEvent` 给的是 `scriptId`），它并不知道"那个脚本此刻
     * 那次运行的 `runId` 是什么"。让调用方去查 `RunSessionRegistry` 会多一条依赖边，
     * 而**映射本来就该由持有通道的一方维护** —— 开通道时记下 `scriptId → runId`，
     * 投递时按 `scriptId` 反查（见 [deliver]），关通道时按 `runId` 清掉。
     *
     * @return FIFO 的绝对路径（要注入给脚本的就是它）；**`null` = 开不出来**
     *   （root 通道不可用 / `mkfifo` 失败）。此时脚本拿不到 `ROOTFLOW_EVENT_FIFO` ——
     *   那是**如实**的：它确实收不到事件，而编一个不存在的路径会让脚本 `read` 卡在
     *   一个永远不会有数据的文件上（比"没有这个变量"更难排查）。
     */
    suspend fun open(
        runId: String,
        scriptId: Long,
    ): String?

    /**
     * **按脚本**投递一行（**同步阻塞**：调用方决定在哪个作用域里调）。
     *
     * 内部查出该脚本**当前**那条通道；没有通道（脚本没在运行）⇒ [EventDelivery.NotRunning]。
     *
     * ## 必须包 `timeout`（P0 实测的硬约束，勿省）
     * FIFO **无读端**时 `open(O_WRONLY)` 会挂住（实测 `RC=124`）—— 不包 timeout 会把
     * 一次投递变成一次永久阻塞，进而拖死整条分发链路。
     */
    suspend fun deliver(
        scriptId: Long,
        line: String,
    ): EventDelivery

    /**
     * 关通道并清理 FIFO 文件（同时清掉它的 `scriptId → runId` 映射）。
     *
     * **必须幂等**：运行的收尾（`invokeOnCompletion`）与"清残留"两条路径都会调它。
     */
    suspend fun close(runId: String)
}

/** 一次投递的结果。**刻意不是布尔**：几种结局的判读方向完全不同。 */
sealed interface EventDelivery {
    /** 已送达（写进 FIFO 后由读端取走）。 */
    data object Delivered : EventDelivery

    /**
     * **该脚本此刻没有运行中的通道**（它没在跑，或那条通道开不出来）。
     *
     * 这是 P5 之后最常见的一种结局：事件**不再启动脚本**，只投给正在运行的常驻脚本。
     * 单次脚本、正在退避等待重启的常驻脚本、通道开不出来的脚本都会落到这里 ——
     * 判读方向是"这条通知没人收"，而不是"通道坏了"。
     */
    data object NotRunning : EventDelivery

    /**
     * **没有读端**（`timeout` 命中，`RC=124`）。
     *
     * 与 [NotRunning] 的区别很具体：这里**通道是存在的**（脚本确实在监管名单里、
     * FIFO 也建出来了），只是此刻没有进程在 `read` 它 —— 例如脚本刚好在两次 `read` 之间。
     * 判读方向是"通知到了门口但没人开门"。
     */
    data object NoReader : EventDelivery

    /** 通道不可用（`su` 失败 / FIFO 不存在 / root 通道不可用）。 */
    data class Failed(
        val reason: String,
    ) : EventDelivery
}
