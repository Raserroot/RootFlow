package com.rootflow.runtime

import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程组管理（阶段 1c）。
 *
 * ## 职责（三条，边界明确）
 * 1. **包装**：把内部脚本包成独立进程组 —
 *    `setsid sh -c '<内部脚本>' & p=$!; echo $p > <pgid 文件>; wait $p`
 *    - `setsid` 让脚本成为新 session / 进程组 leader（真机 E1a 实测 `$! == 脚本 PID == PGID`）
 *    - `wait` **必须保留**：`setsid ... &` 会后台化，不等待则 libsu 追加的收尾标记会与
 *      脚本输出竞争、导致输出截断（真机 E10a 实测 `DIFF_SECONDS=0`）
 *    - `echo $p > 文件` 写在 `wait` **之前**，使 PGID 在脚本**运行期间**即可读到
 * 2. **PGID 传递**：脚本以 root 写入 App 私有目录；App 经**控制通道** `cat` 读取，
 *    App 自身不直接读该文件（规避 `u0_aXXX` 与 root 的权限差异，路线 2）
 * 3. **终止生命周期**：`TERM → 等待宽限 → 探测存活 → 必要时 KILL → 再探测`。
 *    **TERM 已让整组消失时跳过 KILL**（真机实测：此时 KILL 会返回
 *    `kill: -<pgid>: No such process`，纯噪声）
 *
 * ## 明确不负责的事
 * **不接管脚本的执行时机**。1b 的 [RunHandle] 契约是"一次性冷流、收集时才执行"，
 * 那由 [ShellScriptRuntime] 决定。因此本类提供 [wrap] + [readPgid] + [terminate]，
 * 由调用方在需要时显式传入 PGID。这也意味着 `ShellScriptRuntime.run()` 返回时
 * PGID 通常尚未就绪（脚本还没跑）；运行期终止需要调用方拿到 PGID 后显式调用
 * [terminate]。该边界已登记在阶段 1c 变更报告中。
 *
 * ## 与 libsu 的关系
 * 本类**不直接触碰 libsu**，只经 [RootShellManager] 的 [RootShellManager.exec] 与
 * [RootShellManager.execControl]。`runtime/` 内 libsu 引用点仍只有 [RootShellManager]。
 *
 * @param rootShellManager Root 通道（数据 + 控制）
 * @param pgidFileFactory 按运行 id 生成 PGID 文件；生产路径应注入 App 私有 cacheDir
 */
@Singleton
class ProcessGroupManager
    constructor(
        private val rootShellManager: RootShellManager,
        private val pgidFileFactory: (String) -> File,
    ) {
        /** Hilt 注入入口；`@Inject` 落在次构造函数的原因同 [RootShellManager]。 */
        @Inject
        constructor(rootShellManager: RootShellManager) : this(
            rootShellManager = rootShellManager,
            pgidFileFactory = { runId -> File(DEFAULT_PGID_DIR, "$PGID_FILE_PREFIX$runId") },
        )

        /** 运行期登记：runId → PGID。仅在本进程内有效，用于 [terminateRun]。 */
        private val pgidByRun: MutableMap<String, Int> = ConcurrentHashMap()

        /**
         * 把内部脚本包装成独立进程组，并让 PGID 在运行期间即可读。
         *
         * **单引号必须转义（阶段 1c 真机根因，勿回退）**：内部脚本
         * （[ShellScriptRuntime.wrapForShell] 的产物）自身以 `sh -c '…'` 开头、以 `'` 收尾，
         * 含 2 个单引号。把它们**原样**塞进外层 `setsid sh -c '…'` 会让外层引号对在
         * 内层第一个引号处提前闭合：外层 shell 实际只收到 `sh -c ` 这一个残破参数，
         * 其余 400 余字符成为游离文本 → 语法错误 → **整条命令从未执行**
         * （真机现象：脚本 stdout 全丢、stderr 中转文件根本不创建、退出码回退到 1）。
         *
         * 修复：按 POSIX 惯用法把每个 `'` 写成 `'\''`（闭合 →插入字面单引号 →重开）。
         * `$`、`\`、双引号在单引号串内本就按字面保留，无需额外处理。
         * 该惯用法与 [ShellScriptRuntime] 处理用户正文时完全一致。
         *
         * @param innerCommand [ShellScriptRuntime] 生成的内部脚本（含 stderr 中转链与标记）
         * @param runId 运行标识，用于 PGID 文件唯一化
         */
        fun wrap(
            innerCommand: String,
            runId: String,
        ): String {
            // 内部脚本末行必须以换行收尾，否则 `exit 0` 会与拼接内容粘连成语法错误。
            val escaped = innerCommand.trimEnd('\n').replace("'", "'\\''")
            return "setsid sh -c '$escaped' & p=\$!; " +
                "echo \$p > ${shellPathFor(runId)}; wait \$p"
        }

        /** 某次运行的 PGID 文件路径。 */
        fun pgidFile(runId: String): File = pgidFileFactory(runId)

        /**
         * PGID 文件在 **shell 命令中**使用的路径。
         *
         * 把路径分隔符规范化为 `/`：生产路径（`cacheDir`）本就是 POSIX 形式，
         * 但单测可能在 Windows 上运行，`File.absolutePath` 会给出 `\`，
         * 那样构造出的 shell 命令无法在目标设备上执行。此处统一，避免环境差异。
         */
        fun shellPathFor(runId: String): String = pgidFile(runId).absolutePath.replace('\\', '/')

        /**
         * 读取某次运行的 PGID（经**控制通道** `cat`，App 不直接读文件）。
         *
         * @return PGID；尚未落盘或不可解析时返回 [RunHandle.PGID_UNRESOLVED]
         */
        suspend fun readPgid(runId: String): Int {
            val result = rootShellManager.execControl("cat ${shellPathFor(runId)} 2>/dev/null")
            val pgid = result.stdout.trim().toIntOrNull()
            if (pgid != null && pgid > 0) {
                pgidByRun[runId] = pgid
                return pgid
            }
            return RunHandle.PGID_UNRESOLVED
        }

        /**
         * 读取 PGID，带**有界重试**（脚本刚启动时文件可能还没落盘）。
         *
         * @return PGID；重试耗尽仍不可用时返回 [RunHandle.PGID_UNRESOLVED]
         */
        suspend fun readPgidWithRetry(
            runId: String,
            attempts: Int = PGID_READ_ATTEMPTS,
            intervalMillis: Long = PGID_READ_INTERVAL_MILLIS,
        ): Int {
            repeat(attempts) { attempt ->
                val pgid = readPgid(runId)
                if (pgid > 0) return pgid
                if (attempt < attempts - 1) delay(intervalMillis)
            }
            return RunHandle.PGID_UNRESOLVED
        }

        /**
         * 终止某次运行（按 runId 查已登记的 PGID，未登记则先读取）。
         *
         * @return 终止结果；PGID 未知时 [TerminationReport.pgidResolved] 为 false
         */
        suspend fun terminateRun(
            runId: String,
            force: Boolean = false,
        ): TerminationReport {
            val pgid = pgidByRun[runId] ?: readPgidWithRetry(runId)
            if (pgid <= 0) {
                return TerminationReport(pgidResolved = false)
            }
            return terminate(pgid, force)
        }

        /**
         * 终止指定进程组：`TERM → 等待宽限 → 探测存活 → 必要时 KILL → 再探测`。
         *
         * **TERM 之后若整组已消失，直接返回且不发 KILL。**
         *
         * ## 三态判定（阶段 1c 性能修复）
         *
         * **只有 [GroupLiveness.GONE] 允许跳过 KILL。** [GroupLiveness.UNKNOWN]（探测执行失败、
         * 控制通道不可用、或超时）**必须升级到 KILL**：留下活进程的代价（进程组泄漏）远高于
         * 多发一次 KILL 的噪声。跳过 KILL 的原始动机是"已确认整组消失"，不能用"探测失败"冒充。
         */
        suspend fun terminate(
            pgid: Int,
            force: Boolean = false,
        ): TerminationReport {
            if (pgid <= 0) return TerminationReport(pgidResolved = false)

            val termSent: Boolean
            if (force) {
                termSent = false
            } else {
                sendSignal(pgid, SIG_TERM)
                termSent = true
                delay(GRACE_MILLIS)
            }

            val liveness = probeGroupLiveness(pgid)
            if (liveness == GroupLiveness.GONE) {
                // TERM 已经让整组消失：跳过 KILL，避免 `No such process` 噪声。
                return TerminationReport(
                    pgidResolved = true,
                    termSent = termSent,
                    killed = false,
                    goneAfterTerm = termSent,
                    probeFailed = false,
                )
            }

            sendSignal(pgid, SIG_KILL)
            delay(KILL_SETTLE_MILLIS)
            return TerminationReport(
                pgidResolved = true,
                termSent = termSent,
                killed = true,
                goneAfterTerm = false,
                probeFailed = liveness == GroupLiveness.UNKNOWN,
            )
        }

        /**
         * 探测进程组存活状态（三态）。
         *
         * 三态而非布尔：把"探测失败"与"确认已消失"折叠成同一个 `false` 是危险的——
         * [terminate] 会因此把一次失败的探测误读为"TERM 成功了"，从而**跳过 KILL**，
         * 把仍存活的进程组留在设备上。
         *
         * 超时/不可用时返回 [GroupLiveness.UNKNOWN]（**不是** [GroupLiveness.GONE]）。
         */
        suspend fun probeGroupLiveness(pgid: Int): GroupLiveness {
            if (pgid <= 0) return GroupLiveness.GONE
            val probe = rootShellManager.execControl(probeCommand(pgid))
            if (probe.exitCode != 0) return GroupLiveness.UNKNOWN
            return when (probe.stdout.trim()) {
                GROUP_ALIVE_RESULT -> GroupLiveness.ALIVE
                GROUP_GONE_RESULT -> GroupLiveness.GONE
                else -> GroupLiveness.UNKNOWN
            }
        }

        /**
         * 进程组是否仍有存活进程（[probeGroupLiveness] 的布尔薄封装）。
         *
         * `UNKNOWN` 与 `GONE` 都返回 `false`——本方法仅供"反查是否还活着"的观测使用；
         * **终止决策必须用 [probeGroupLiveness]**，否则会把探测失败当成已消失。
         */
        suspend fun isGroupAlive(pgid: Int): Boolean = probeGroupLiveness(pgid) == GroupLiveness.ALIVE

        /** 清理某次运行的 PGID 文件与登记项。 */
        suspend fun cleanup(runId: String) {
            pgidByRun.remove(runId)
            rootShellManager.execControl("rm -f ${shellPathFor(runId)}")
        }

        /** 下发信号；经控制通道执行（数据通道正被脚本占用）。 */
        private suspend fun sendSignal(
            pgid: Int,
            signal: String,
        ) {
            rootShellManager.execControl("kill -$signal -$pgid")
        }

        /**
         * 存活探测命令。
         *
         * ## 为什么不用 `kill -0 -<pgid>`
         * toybox 对"进程组探测"的语义不统一。
         *
         * ## 为什么不用逐 PID spawn `ps`（阶段 1c 性能修复）
         * 旧实现是 `for p in $(ps -A -o pid=); do ps -o pgid= -p $p; done` ——
         * **对设备上每个进程各 spawn 一次 `ps`**。真机 500–1500 个进程 → 500–1500 次
         * fork/exec，实测单次探测耗时 20–30 秒，远超 [RootShellManager.execControl] 的
         * 10 秒上限（于是探测总是超时失败，进而让判定建立在"失败"之上）。
         *
         * ## 现在的写法
         * **一次** `ps -A -o pgid=` 取回全部组号，再在 shell 内部用 POSIX 参数展开
         * 做全词匹配 —— 常数级开销，无 fork 循环、不依赖 `grep -w` / `awk` 的玩具箱实现差异。
         *
         * 三态输出的取得方式：
         * - `ps` 输出为空（执行失败/不可用）→ [GROUP_PROBE_FAILED_RESULT]
         * - 找到匹配组号 → [GROUP_ALIVE_RESULT]
         * - `ps` 成功但无匹配 → [GROUP_GONE_RESULT]
         *
         * 全词匹配用 `${o#* }` / `${o%% *}` 在空白处切片后做等值比较：组号被空白包围时
         * 总能切出恰好等于该组号的片段（首尾补空格以覆盖边界）。
         */
        private fun probeCommand(pgid: Int): String =
            "o=\$(ps -A -o pgid= 2>/dev/null); " +
                "if [ -z \"\$o\" ]; then echo $GROUP_PROBE_FAILED_RESULT; else " +
                "f=0; o=\" \$o \"; " +
                "while [ -n \"\$o\" ]; do " +
                "o=\${o#* }; t=\${o%% *}; " +
                "[ \"\$t\" = \"$pgid\" ] && { f=1; break; }; " +
                "done; " +
                "if [ \$f -eq 1 ]; then echo $GROUP_ALIVE_RESULT; " +
                "else echo $GROUP_GONE_RESULT; fi; fi"

        companion object {
            /** TERM 与 KILL 之间的宽限期；需求 §4.2 规定 3s。 */
            const val GRACE_MILLIS: Long = 3_000L

            /** 发出 KILL 后等待的稳定时间。 */
            const val KILL_SETTLE_MILLIS: Long = 500L

            /** PGID 文件轮询的最大次数。 */
            const val PGID_READ_ATTEMPTS: Int = 20

            /** PGID 文件轮询间隔。 */
            const val PGID_READ_INTERVAL_MILLIS: Long = 200L

            /** 探测到进程组仍存活时的输出标记。 */
            const val GROUP_ALIVE_RESULT: String = "RF_GROUP_ALIVE"

            /** 探测到进程组已消失时的输出标记。 */
            const val GROUP_GONE_RESULT: String = "RF_GROUP_GONE"

            /**
             * 探测命令**未能给出可判定结果**时的输出标记（阶段 1c 性能修复新增）。
             *
             * 与 [GROUP_GONE_RESULT] 严格区分：`ps` 执行失败/不可用时输出本标记，
             * 调用方须映射为 [GroupLiveness.UNKNOWN]，**不得**当作"已消失"。
             */
            const val GROUP_PROBE_FAILED_RESULT: String = "RF_GROUP_PROBE_FAILED"

            /** `kill -TERM`。 */
            const val SIG_TERM: String = "TERM"

            /** `kill -KILL`。 */
            const val SIG_KILL: String = "KILL"

            /** PGID 文件名前缀。 */
            const val PGID_FILE_PREFIX: String = ".rf_pgid_"

            /**
             * PGID 文件的默认目录。
             *
             * 默认 `/data/local/tmp`（root 可写的公共临时目录）仅用于测试与兜底；
             * 生产路径应由调用方注入 App 私有 `cacheDir`。
             */
            const val DEFAULT_PGID_DIR: String = "/data/local/tmp"
        }
    }

/** [ProcessGroupManager.terminate] 的结果描述。 */
data class TerminationReport(
    /** PGID 是否解析成功。 */
    val pgidResolved: Boolean,
    /** 是否发出过 TERM。 */
    val termSent: Boolean = false,
    /** 是否发出过 KILL。 */
    val killed: Boolean = false,
    /** TERM 后整组是否已消失（消失则未发 KILL）。 */
    val goneAfterTerm: Boolean = false,
    /**
     * 判定存活时探测是否不可信（[GroupLiveness.UNKNOWN]）。
     *
     * 为 `true` 时表示：这次终止"发了 KILL 但无法确认整组已清理"，
     * 调用方**不应**把它当作已确认清理。
     */
    val probeFailed: Boolean = false,
)

/**
 * 进程组存活探测结果（三态）。
 *
 * 之所以不是布尔：把"探测失败"与"确认已消失"折叠成同一个 `false`，会让
 * [ProcessGroupManager.terminate] 误跳过 KILL，把存活进程组留在设备上。
 */
enum class GroupLiveness {
    /** 确认组内仍有进程（含 leader 已死、同组子进程仍活的情况）。 */
    ALIVE,

    /** 探测命令成功执行，确认组内已无进程。 */
    GONE,

    /** 探测未能给出可判定结果（执行失败 / 控制通道不可用 / 超时）。 */
    UNKNOWN,
}
