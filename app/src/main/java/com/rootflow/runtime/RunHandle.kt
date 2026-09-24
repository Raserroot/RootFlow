package com.rootflow.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次运行的句柄。
 *
 * ## [output] 的收集语义（重要）
 *
 * `output` 是一条**冷流**：`run()` 本身不执行脚本，**第一次 `collect` 时才真正执行**。
 * 并且每条 [RunHandle] 的 `output` **只允许被收集一次**：
 *
 * - 第一次收集：执行脚本并依次发射日志。
 * - 第二次及以后收集：抛 [IllegalStateException]（消息为 [CONSUMED_MESSAGE]）。
 *
 * 之所以选择"一次性"而不是"每次收集都重跑"：
 * 1. `run()` 语义上是"运行一次脚本"，返回的句柄代表**那一次**运行；允许重复收集
 *    会让同一次运行的日志与执行次数脱钩（收集两次＝执行两次），极易被误用。
 * 2. 阶段 2 引入日志管道后，`output` 会改为真实流式（边执行边发射）。届时重复收集
 *    在语义上更不可能成立，现在就把契约钉死可避免将来破坏性变更。
 * 3. "已消费"标记在**第一次收集开始时**即置位（而非成功结束后），因此即使首次
 *    收集因异常或取消而中断，该句柄也不会被复用——避免"半个脚本又跑一次"。
 *
 * ## [killed] 在阶段 1b 的实际作用（如实说明）
 *
 * [ScriptRuntime.kill] 在阶段 1b **不产生真正的终止动作**（进程组终止属阶段 1c）。
 * 为避免 [killed] 沦为完全无效的状态位，[ShellScriptRuntime] 在开始执行前会检查它：
 * 若已被 kill，则不发脚本、只发射一条 SYS 行并结束。**它无法中断已在运行的脚本。**
 *
 * @property id 本次运行的唯一标识（UUID）
 * @property startedAt 运行创建时间（`System.currentTimeMillis()`）
 * @property output 一次性冷流，见上文
 */
class RunHandle internal constructor(
    val id: String,
    val startedAt: Long,
    private val flow: Flow<LogLine>,
) {
    private val killedFlag = AtomicBoolean(false)

    /** 本次运行的日志流；只能收集一次，见类文档。 */
    val output: Flow<LogLine> = flow

    /**
     * 本次运行所在**进程组**的 PGID；尚未解析出来时为 [PGID_UNRESOLVED]。
     *
     * 可见性为 `internal`（阶段 1c 决定）：运行期终止需要它，但公开契约保持
     * 1b 原样（`id` / `startedAt` / `output` / `killed`）；将来 UI 若需要展示，
     * 再显式升为 public。
     *
     * 解析时机：脚本由 `setsid` 启动后，包装命令把 `$!`（即进程组 leader 的 PID，
     * 也就是 PGID）写入文件；App 经控制通道 `cat` 该文件得到。因此**解析是
     * 异步的**，脚本开始时通常已经就绪，但极短脚本可能来不及。
     */
    internal val pgid: Int
        get() = pgidValue

    @Volatile
    private var pgidValue: Int = PGID_UNRESOLVED

    /** 是否已被 [ScriptRuntime.kill] 请求终止。 */
    val killed: Boolean
        get() = killedFlag.get()

    /**
     * 标记本次运行已被请求终止。
     *
     * 阶段 1b：仅置位。阶段 1c：由进程组管理在真正下发信号**之前**调用，
     * 用于让"尚未开始的执行"跳过运行（见 [ShellScriptRuntime] 的 killed 检查）。
     */
    internal fun markKilled() {
        killedFlag.set(true)
    }

    /** 记录解析出的 PGID；仅在首次解析成功时生效。 */
    internal fun markPgidResolved(pgid: Int) {
        if (pgidValue == PGID_UNRESOLVED) {
            pgidValue = pgid
        }
    }

    companion object {
        /** 二次收集时的异常消息；单测与调用方都可能依赖该文案。 */
        const val CONSUMED_MESSAGE: String = "RunHandle already consumed"

        /** [pgid] 尚未解析出来时的取值。真实 PGID 恒为正数。 */
        const val PGID_UNRESOLVED: Int = -1
    }
}

/**
 * 创建一条一次性冷流句柄。
 *
 * @param id 运行标识，默认随机 UUID
 * @param startedAt 创建时间，默认当前毫秒
 * @param block 冷流正文：在**第一次收集时**执行；接收句柄自身，便于读取 [RunHandle.killed]
 */
internal fun createRunHandle(
    id: String = UUID.randomUUID().toString(),
    startedAt: Long = System.currentTimeMillis(),
    block: suspend RunHandle.(FlowCollector<LogLine>) -> Unit,
): RunHandle {
    val consumed = AtomicBoolean(false)
    lateinit var handle: RunHandle
    val flow =
        flow<LogLine> {
            // 占用必须在最前：在 ensureActive 之前就置位，
            // 保证"用过了"这件事不可回滚（首次收集中断也不会被复用）。
            // 用 compareAndSet 而非先 get 再 set：并发收集时只有一个能拿到所有权。
            check(consumed.compareAndSet(false, true)) { RunHandle.CONSUMED_MESSAGE }
            currentCoroutineContext().ensureActive()
            handle.block(this)
        }
    handle = RunHandle(id = id, startedAt = startedAt, flow = flow)
    return handle
}
