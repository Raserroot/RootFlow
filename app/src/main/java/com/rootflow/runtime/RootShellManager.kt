package com.rootflow.runtime

import android.util.Log
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Root shell 通道管理器（单例）。
 *
 * 职责边界（见 `AGENTS.md` 分层约束：所有 Root 操作必须集中在 `runtime/` 包）：
 * - 本类是**唯一**允许直接触碰 libsu 的地方；`ui/` 与 `domain/` 只能通过它访问 Root。
 * - **数据通道**复用 libsu 的全局 shell 单例（[Shell.getShell]），承载脚本执行，
 *   不新开 shell 进程。
 * - **控制通道**（阶段 1c 新增）是一条**独立的第二个 shell 实例**，只承载短控制命令
 *   （如 `kill -TERM -<pgid>`、存活探测）。之所以必须独立：libsu 的
 *   `ShellImpl.execTask()` 是 `synchronized`，且每次作业前会
 *   `cleanInputStream(STDOUT/STDERR)` 并抢占 stdin——**同一个 shell 上不可能并发两个作业**，
 *   否则正在运行的脚本输出会被清空。因此"运行期 kill"需要第二条通道。
 *
 * 线程模型：libsu 的 [Shell.getShell] / [Shell.Builder.build] / [Shell.Job.exec]
 * 都是阻塞调用，因此全部切到 [ioDispatcher] 执行，不做主线程阻塞。
 * 控制通道的建立与使用分别由 [controlMutex] / [controlExecMutex] 串行化。
 *
 * @param ioDispatcher 执行阻塞 shell 调用的调度器；单测注入 `StandardTestDispatcher`。
 */
@Singleton
class RootShellManager
    constructor(
        private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val _state: MutableStateFlow<RootState> = MutableStateFlow(RootState.Unknown)

        /**
         * 当前 Root 状态。初始为 [RootState.Unknown]，调用 [ensureReady] 后更新。
         * 对外只读，背压策略为 `StateFlow` 的"仅保留最新值"。
         */
        val state: StateFlow<RootState> = _state.asStateFlow()

        /** 控制通道的 shell 实例；`null` 表示尚未建立或已失效。 */
        @Volatile
        private var controlShell: Shell? = null

        /** 串行化控制通道的**建立**，避免并发重复 build。 */
        private val controlMutex = Mutex()

        /** 串行化控制通道上的**命令执行**，避免并发作业互相干扰。 */
        private val controlExecMutex = Mutex()

        /**
         * 控制通道当前是否可用。
         *
         * 与 [state] 相互独立：控制通道不可用**不**改写 [state]
         * （Root 可能依然可用，只是没有第二条通道发控制命令）。
         * 置为 `false` 后，[ensureControlReady] 的下一次调用会重新尝试建立。
         */
        @Volatile
        var controlChannelAvailable: Boolean = false
            private set

        /**
         * Hilt 注入入口。
         *
         * `@Inject` 之所以落在次构造函数而不落在主构造函数：主构造函数的
         * [ioDispatcher] 若带默认值，Kotlin 会生成**两个**构造函数，两个都会被标上
         * `@Inject`，Hilt 随即报 "Type ... may only contain one injected constructor"
         * （实测已复现）。因此主构造函数保持无默认值、无注解，仅暴露给单元测试。
         */
        @Inject
        @Suppress("InjectDispatcher") // 生产路径就是要用 Dispatchers.IO
        constructor() : this(Dispatchers.IO)

        /**
         * 确保 Root shell 通道可用，并刷新 [state]。
         *
         * 可取消：调用方取消协程时抛出 [CancellationException]，**不**改写 [state]
         * （取消是调用方的意愿，不是 Root 故障，不应被记为 [RootState.Error]）。
         *
         * @return true 表示 Root 可用（状态已置为 [RootState.Granted]）。
         */
        suspend fun ensureReady(): Boolean =
            withContext(ioDispatcher) {
                try {
                    // 取全局 shell 单例：libsu 会在此过程中触发 su 授权（首次运行时
                    // Root 管理器会弹授权框）。不会新建额外 shell 进程。
                    val shell = Shell.getShell()
                    // 注意：libsu 5.x 中 isRoot() 是 Shell 的**实例方法**（4.x 里是静态的），
                    // 因此必须通过 getShell() 返回的实例调用。
                    val granted = shell.isRoot
                    if (granted) {
                        _state.value = RootState.Granted
                    } else {
                        // libsu 能拿到 shell，但该 shell 不是 root shell
                        // （设备无 Root，或 su 授权被拒绝）。
                        _state.value = RootState.Denied("shell acquired but not a root shell")
                    }
                    granted
                } catch (cancellation: CancellationException) {
                    // 协程取消必须原样上抛，保证 withContext 的可取消语义，且不改写状态。
                    throw cancellation
                } catch (noShell: NoShellException) {
                    _state.value = RootState.Denied(noShell.message ?: NO_SHELL_FALLBACK_MESSAGE)
                    false
                } catch (throwable: Throwable) {
                    _state.value = RootState.Error(throwable.message ?: throwable::class.java.name)
                    false
                }
            }

        /**
         * 执行一条 shell 命令并返回结果（**数据通道**）。
         *
         * 执行前会先调用 [ensureReady]；Root 不可用或超时则返回 [ShellResult] 形式的
         * 失败（**不抛异常**），便于调用方统一按退出码处理。退出码约定：
         * - `0`：执行成功
         * - [EXIT_CODE_NOT_READY]：Root 未就绪（未执行命令）
         * - [EXIT_CODE_TIMEOUT]：超时（未拿到结果）
         *
         * 已知限制：libsu 的 `exec()` 是阻塞调用，超时只能放弃**等待**，无法中断
         * 已在运行的原生命令；强制终止需要控制通道（见 [execControl]）。
         *
         * @param cmd 待执行的 shell 命令
         * @param timeoutMillis 等待上限，默认 [DEFAULT_TIMEOUT_MILLIS]
         */
        suspend fun exec(
            cmd: String,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ): ShellResult {
            if (!ensureReady()) {
                val reason =
                    (_state.value as? RootState.Denied)?.reason
                        ?: (_state.value as? RootState.Error)?.message
                        ?: NOT_READY_FALLBACK_MESSAGE
                return ShellResult(stdout = "", stderr = reason, exitCode = EXIT_CODE_NOT_READY)
            }
            val result = execOnDataChannel(cmd, timeoutMillis)
            if (result == null) {
                return ShellResult(
                    stdout = "",
                    stderr = "timeout after ${timeoutMillis}ms: $cmd",
                    exitCode = EXIT_CODE_TIMEOUT,
                )
            }
            return result.toShellResult()
        }

        /**
         * 数据通道的执行体。
         *
         * **保持 1b 的原路径**（`Shell.cmd(...)`）：libsu 的静态 `cmd` 走全局单例 shell，
         * 是阶段 1a/1b 已验证的行为，也是 1b 单测的打桩点。改成实例路径
         * （`newJob()`）会破坏那批断言——实测已发生并被本改动修正。
         */
        private suspend fun execOnDataChannel(
            cmd: String,
            timeoutMillis: Long,
        ): Shell.Result? =
            withContext(ioDispatcher) {
                withTimeoutOrNull(timeoutMillis) {
                    Shell.cmd(cmd).exec()
                }
            }

        /**
         * 指定 shell 实例上的执行体（控制通道使用）。
         *
         * 与数据通道不同，控制通道必须在**第二个 shell 实例**上执行，因此走实例的
         * `newJob()` 而不是静态 `Shell.cmd()`（后者永远指向全局单例）。
         * 显式指定输出列表，避免依赖 libsu 的默认行为。
         */
        private suspend fun execOnControlChannel(
            shell: Shell,
            cmd: String,
            timeoutMillis: Long,
        ): Shell.Result? =
            withContext(ioDispatcher) {
                withTimeoutOrNull(timeoutMillis) {
                    shell
                        .newJob()
                        .add(cmd)
                        .to(mutableListOf<String>(), mutableListOf<String>())
                        .exec()
                }
            }

        /**
         * 确保**控制通道**可用。
         *
         * 控制通道是独立于数据通道的第二个 shell 实例，专用于运行期控制命令
         * （如 kill 进程组）。用途与必要性见类文档。
         *
         * 行为：
         * 1. 已建立且仍然存活 → 直接返回 true。
         * 2. 首次调用或实例已失效 → 懒加载建立，**最多尝试 [CONTROL_BUILD_ATTEMPTS] 次**，
         *    间隔 [CONTROL_BUILD_RETRY_DELAY_MILLIS]；先执行 [ensureReady] 确保 su 授权
         *    已完成，避免无谓的重复授权请求。
         * 3. 重试耗尽 → 返回 false，[controlChannelAvailable] 置 `false`，并打 WARN 日志；
         *    **不**改写 [state]。
         *
         * 可取消：取消时原样抛出 [CancellationException]，不改写任何状态。
         *
         * 参数取值（尝试 3 次 / 间隔 500ms）属当前阶段决定，见阶段 1c 变更报告。
         *
         * @return true 表示控制通道可用。
         */
        suspend fun ensureControlReady(): Boolean {
            controlShell?.takeIf { it.isAlive }?.let {
                controlChannelAvailable = true
                return true
            }
            return controlMutex.withLock {
                // 双重检查：等待锁期间可能已被其他调用建立。
                controlShell?.takeIf { it.isAlive }?.let {
                    controlChannelAvailable = true
                    return@withLock true
                }
                // 先确认数据通道可用：控制通道同样是 su shell，
                // 若 Root 根本不可用，重试也只是浪费授权请求。
                ensureReady()
                repeat(CONTROL_BUILD_ATTEMPTS) { attempt ->
                    val built =
                        withContext(ioDispatcher) {
                            runCatching { Shell.Builder.create().build() }
                        }
                    val shell = built.getOrNull()
                    if (shell != null && shell.isRoot) {
                        controlShell = shell
                        controlChannelAvailable = true
                        Log.i(
                            TAG,
                            "CONTROL_CHANNEL_ESTABLISHED attempt=${attempt + 1} " +
                                "pid=${processPidOrNull(shell)}",
                        )
                        return@withLock true
                    }
                    val reason =
                        built.exceptionOrNull()?.let { "${it::class.java.name}: ${it.message}" }
                            ?: "shell acquired but not a root shell"
                    Log.w(
                        TAG,
                        "CONTROL_CHANNEL_BUILD_FAILED attempt=${attempt + 1}/" +
                            "$CONTROL_BUILD_ATTEMPTS reason=$reason",
                    )
                    if (attempt < CONTROL_BUILD_ATTEMPTS - 1) {
                        delay(CONTROL_BUILD_RETRY_DELAY_MILLIS)
                    }
                }
                controlShell = null
                controlChannelAvailable = false
                Log.w(TAG, "CONTROL_CHANNEL_UNAVAILABLE after $CONTROL_BUILD_ATTEMPTS attempts")
                false
            }
        }

        /**
         * 在**控制通道**上执行一条短控制命令（如 `kill -TERM -<pgid>`、存活探测）。
         *
         * 与 [exec] 的关键区别：执行在第二个 shell 实例上，因此**可以在脚本运行期间**
         * 下发，不会清空数据通道（正在运行的脚本）的输出流。
         *
         * 退出码约定在 [exec] 的基础上增加：
         * - [EXIT_CODE_CONTROL_UNAVAILABLE]：控制通道不可用（未执行命令）
         * - [EXIT_CODE_CONTROL_TIMEOUT]：控制通道超时（未拿到结果）
         *
         * 命令执行由 [controlExecMutex] 串行化，不可重入。
         *
         * @param cmd 待执行的控制命令
         * @param timeoutMillis 等待上限，默认 [DEFAULT_CONTROL_TIMEOUT_MILLIS]
         */
        suspend fun execControl(
            cmd: String,
            timeoutMillis: Long = DEFAULT_CONTROL_TIMEOUT_MILLIS,
        ): ShellResult {
            if (!ensureControlReady()) {
                return controlUnavailableResult()
            }
            val shell = controlShell
            if (shell == null || !shell.isAlive) {
                controlChannelAvailable = false
                return controlUnavailableResult()
            }
            val result =
                controlExecMutex.withLock {
                    execOnControlChannel(shell, cmd, timeoutMillis)
                }
            if (result == null) {
                return ShellResult(
                    stdout = "",
                    stderr = "control timeout after ${timeoutMillis}ms: $cmd",
                    exitCode = EXIT_CODE_CONTROL_TIMEOUT,
                )
            }
            return result.toShellResult()
        }

        /**
         * 控制通道 shell 的进程 PID；取不到（OOM 分数或反射失败）时返回 `null`。
         *
         * 仅供诊断日志与真机验证使用——用于区分"数据通道"与"控制通道"确实是两个进程。
         */
        fun controlShellPidOrNull(): Int? = controlShell?.let { processPidOrNull(it) }

        /** 把 libsu 的结果映射为 [ShellResult]。 */
        private fun Shell.Result.toShellResult(): ShellResult =
            ShellResult(
                stdout = out.joinToString(separator = "\n"),
                stderr = err.joinToString(separator = "\n"),
                exitCode = code,
            )

        /** 控制通道不可用时的失败结果。 */
        private fun controlUnavailableResult(): ShellResult =
            ShellResult(
                stdout = "",
                stderr = CONTROL_UNAVAILABLE_MESSAGE,
                exitCode = EXIT_CODE_CONTROL_UNAVAILABLE,
            )

        companion object {
            /** [exec] 的默认等待上限。具体数值属当前阶段决定，见阶段 1a 变更报告。 */
            const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L

            /** Root 未就绪时 [ShellResult.exitCode] 的取值（非真实进程退出码）。 */
            const val EXIT_CODE_NOT_READY: Int = -1

            /** 超时时 [ShellResult.exitCode] 的取值（非真实进程退出码）。 */
            const val EXIT_CODE_TIMEOUT: Int = -2

            /** 控制通道不可用时 [ShellResult.exitCode] 的取值。 */
            const val EXIT_CODE_CONTROL_UNAVAILABLE: Int = -3

            /** 控制通道超时时 [ShellResult.exitCode] 的取值。 */
            const val EXIT_CODE_CONTROL_TIMEOUT: Int = -4

            /** 控制通道建立的最大尝试次数。 */
            const val CONTROL_BUILD_ATTEMPTS: Int = 3

            /** 控制通道建立失败时的重试间隔（毫秒）。 */
            const val CONTROL_BUILD_RETRY_DELAY_MILLIS: Long = 500L

            /** [execControl] 的默认等待上限（控制命令都很短）。 */
            const val DEFAULT_CONTROL_TIMEOUT_MILLIS: Long = 10_000L

            private const val NO_SHELL_FALLBACK_MESSAGE = "libsu could not acquire a shell"
            private const val NOT_READY_FALLBACK_MESSAGE = "root shell is not ready"
            private const val CONTROL_UNAVAILABLE_MESSAGE = "control channel is not available"
            private const val TAG = "RootFlow"

            /**
             * 通过反射取 libsu shell 底层进程的 PID。
             *
             * libsu 的公开 API **不**暴露进程 PID，而"确认数据通道与控制通道是两个进程"
             * 是本阶段真机验证的必要观察项，因此这里用反射做**只读诊断**：
             * `AndroidProcess.getPid()` / `Process.pid()`（API 33+）之一。
             * 失败一律返回 `null`，绝不影响主流程。
             */
            private fun processPidOrNull(shell: Shell): Int? =
                runCatching {
                    val hidden = shell::class.java.getDeclaredField("proc")
                    hidden.isAccessible = true
                    val process = hidden.get(shell) as? Process ?: return@runCatching null
                    runCatching {
                        Process::class.java.getDeclaredMethod("getPid").invoke(process) as? Int
                    }.getOrNull()
                        ?: runCatching {
                            Process::class.java.getDeclaredMethod("pid").invoke(process) as? Int
                        }.getOrNull()
                }.getOrNull()
        }
    }
