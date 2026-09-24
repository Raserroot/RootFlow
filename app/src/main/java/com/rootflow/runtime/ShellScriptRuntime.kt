package com.rootflow.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 shell 的脚本运行时（v1 唯一实现）。
 *
 * ## 与 Root 通道的关系
 *
 * 本类**不直接触碰 libsu**，只通过 [RootShellManager] 的公开能力执行脚本
 * （`AGENTS.md`：所有 Root 操作必须集中在 `runtime/` 包，且 libsu 的引用点收敛在
 * [RootShellManager]）。本文件不含任何 libsu 的 import 或类型引用——这一点由
 * `findstr` 自查保证，因此此处刻意不写出 libsu 的包名字面量，避免把注释本身
 * 变成匹配命中。
 *
 * ## 脚本如何被提交给 shell
 *
 * 正文经 [wrapForShell] 包裹后作为单条命令交给 [RootShellManager.exec]。包裹的必要性：
 * `>&2` 等重定向需要 shell 解释；正文必须放进 subshell，其 `exit` 才不会终止
 * `sh -c` 自身（详见 [wrapForShell]）。
 *
 * ## stderr 的现实约束与应对（环境特化结论）
 *
 * **在当前测试环境（OnePlus 8 + APatch + libsu 5.2.2 + 当前 `RootShellManager`
 * 实现）下**，libsu 独立 stderr 通道实测返回 `0B`；因此采用脚本层 stderr 捕获
 * workaround。**该结论为环境特化，不推广到其它 Root 方案**——libsu 自身确实有
 * 独立 stderr 管道（`process.getErrorStream()` + 独立 `StreamGobbler`），
 * 只是在该环境下拿不到数据。
 *
 * 应对：[wrapForShell] 在脚本内把 stderr 兜进中转文件、读回 stdout 并自报行数；
 * [splitMergedOutput] 据此把合并输出切回 `STDOUT` / `STDERR` 两路。
 * **分流能力得以保留**，代价是 stderr 行在输出中排在全部 stdout 行之后
 * （底层本就是一次性取回，无实时交错）。
 *
 * ## 输出如何变成 [LogLine]
 *
 * [RootShellManager.exec] 返回的 [ShellResult] 已把多行合并为单个字符串，
 * 因此这里按 `\n` 切分，**每个非空行**映射为一条 [LogLine]。
 * 已知代价：行内空行会被丢弃。阶段 2 引入真正的流式日志管道后，
 * 本切片逻辑与 stderr 合并机制都会重新设计。
 *
 * ## 发射顺序
 *
 * 1. `SYS`：`script <id> started`（有触发事件时为 `... started (trigger=<event>)`）
 * 2. `SYS`：`script <id> exec: sh -c <<n> chars>`（诊断行，便于真机核对下发内容）
 * 3. 脚本 `stdout` 的每一行（顺序）→ `STDOUT`
 * 4. 脚本 `stderr` 的每一行（顺序）→ `STDERR`
 * 5. `SYS`：`script <id> exited with code <exitCode>`
 *
 * 若收集被取消，第 5 步（退出行）不会发射。
 *
 * ## 退出码的来源（阶段 1c 起）
 *
 * 内部脚本末尾会 `echo __RF_EXIT__$__RF_RET`，把**正文的真实退出码**经 stdout 送回
 * （因为 `setsid` 不传递退出码——真机实测 `setsid -w` 也返回 0）。
 * [splitMergedOutput] 负责解析该标记；标记缺失（例如运行被 kill、脚本没跑到末尾）时
 * 退回使用 [ShellResult.exitCode]。
 *
 * ## kill 的当前能力
 *
 * 见 [RunHandle.killed] 与 [ProcessGroupManager]：
 * - **开始前**被 kill → 跳过执行（阶段 1b 行为，保留）
 * - **运行期**终止 → 需要 PGID，由 [ProcessGroupManager.terminate] 负责；
 *   `run()` 返回时 PGID 通常尚未就绪（脚本还没跑），因此运行期终止需由调用方
 *   在拿到 PGID 后显式调用。
 *
 * @param rootShellManager Root 通道；构造注入
 */
@Singleton
class ShellScriptRuntime
    @Inject
    constructor(
        private val rootShellManager: RootShellManager,
    ) : ScriptRuntime {
        /**
         * 进程组管理；由 Hilt 注入。
         *
         * 之所以是 `lateinit var` 而非构造参数：单测用
         * `ShellScriptRuntime(rootShellManager)` 单参构造（1b 起既有写法），
         * 而 Kotlin 的默认参数不能跳过中间参数，故把第二个依赖改为属性注入。
         * 生产路径由 Hilt 正常注入，行为不受影响。
         */
        @Inject
        lateinit var processGroupManager: ProcessGroupManager

        /**
         * 告警缝（阶段 6b）；生产为 `Log.w`，单测可注入记录器。
         *
         * ## 为什么需要它（`AGENT_PROTOCOL.md §5.10`）
         * 本类在单测里被直接构造，而 `android.util.Log` 在纯 JVM 下**未 stub**
         * ⇒ 直接调 `Log.w` 会让用例因"日志"而红。而 [sanitizeEnv] 拒绝换行值的告警
         * 又**必须**能上报（静默丢弃用户配的 payload 是本仓库禁止的形态），
         * 因此走注入的缝 —— 与 `LogPipelineImpl.onWarning` 同款。
         *
         * 默认 no-op 而不是 `Log.w`：本类的无参测试构造下也要求可安全调用。
         * 生产路径的告警由 [com.rootflow.data.run.ScriptRunCoordinator] 那条链承担
         * （它在运行时打印 SYS 行）。
         */
        internal var onWarning: (String) -> Unit = {}

        /**
         * **仅测试用**的无参构造：让不关心依赖的测试（如命令生成的 golden 比对）
         * 能直接构造本类。
         *
         * 之所以能这样加：Hilt 只接受**带 `@Inject`** 的那一个构造函数，本构造无注解。
         * 注意 [processGroupManager] 在本构造下**未初始化**，因此该实例只能用于
         * 不触及进程组的能力（如 [wrapForShellForTest]）；一旦真正运行脚本，
         * `lateinit` 会抛出 `UninitializedPropertyAccessException` 而不是静默错行为。
         */
        internal constructor() : this(
            rootShellManager = RootShellManager(),
        )

        override suspend fun run(
            script: ScriptEntity,
            ctx: RunContext,
        ): RunHandle =
            createRunHandle { collector ->
                emitScript(handle = this, collector = collector, script = script, ctx = ctx)
            }

        override suspend fun kill(
            handle: RunHandle,
            force: Boolean,
        ) {
            // 先置 kill 标记：让"尚未开始执行"的运行跳过运行（阶段 1b 行为，保留）。
            handle.markKilled()
            // 阶段 1c：若 PGID 已解析出来，则终止整个进程组。
            // 注：run() 返回时 PGID 通常尚未就绪（脚本还没跑），因此这里的运行期终止
            // 只在 PGID 已知时生效；显式终止请用 ProcessGroupManager.terminate(pgid)。
            val pgid = handle.pgid
            if (pgid > 0) {
                processGroupManager.terminate(pgid, force)
            }
        }

        /**
         * 冷流正文：检查 kill 标记 → 发射头部行 → 执行脚本 → 分流发射 → 发射退出行。
         */
        private suspend fun emitScript(
            handle: RunHandle,
            collector: FlowCollector<LogLine>,
            script: ScriptEntity,
            ctx: RunContext,
        ) {
            if (handle.killed) {
                collector.emitSys("script ${script.id} skipped: killed before start")
                return
            }

            collector.emitSys(startMessage(script, ctx))
            collector.emitSys("script ${script.id} exec: sh -c <${script.content.length} chars>")

            val inner = wrapForShell(script.content, handle.id, sanitizeEnv(ctx.env))
            // 阶段 1c：包进独立进程组（setsid + wait），并让 PGID 在运行期间可读。
            val result = rootShellManager.exec(processGroupManager.wrap(inner, handle.id))
            val split = splitMergedOutput(result.stdout)

            split.stdout.forEach { line ->
                currentCoroutineContext().ensureActive()
                collector.emit(LogLine(System.currentTimeMillis(), LogStream.STDOUT, line))
            }
            split.stderr.forEach { line ->
                currentCoroutineContext().ensureActive()
                collector.emit(LogLine(System.currentTimeMillis(), LogStream.STDERR, line))
            }

            // 回填 PGID（运行结束后文件必然已落盘），供日志与后续诊断使用。
            val pgid = processGroupManager.readPgid(handle.id)
            if (pgid > 0) {
                handle.markPgidResolved(pgid)
            }

            currentCoroutineContext().ensureActive()
            // 退出码优先取脚本自报的 __RF_EXIT__N（setsid 不传递退出码）；缺失时退回 shell 结果。
            val exitCode = split.exitCode ?: result.exitCode
            collector.emitSys("script ${script.id} exited with code $exitCode")
        }

        /**
         * 把脚本正文包成一条可在 shell 中正确执行的命令。
         *
         * 生成的命令全文（`wrapForShellForTest` 可打印原文，供本地/真机 shell 逐字复跑）：
         *
         * ```sh
         * sh -c '(
         * <正文>
         * ) 2>/tmp/.rf_err_<runId>
         * __RF_RET=$?
         * cat /tmp/.rf_err_<runId>
         * echo __RF_ERR_LINES__$(wc -l </tmp/.rf_err_<runId>)
         * rm -f /tmp/.rf_err_<runId>
         * echo __RF_EXIT__$__RF_RET
         * exit 0'
         * ```
         *
         * 逐点说明：
         * - `( 正文 ) 2>文件`：**正文必须放在 subshell 里**，不能用 `{ ... }`。
         *   `{ }` 是 group command，在当前 shell 内执行，因此正文里的 `exit`
         *   会**直接终止整个 `sh -c`**，导致后续的 `__RF_RET=$?`、`cat`
         *   全部没有机会执行——退出码与 stderr 双双丢失。
         *   `( )` 是 subshell，`exit` 只终止该子 shell，其退出码成为整条命令的
         *   退出码，收尾步骤照常执行。
         * - **stdout 直接继承外层，不做任何 fd 备份**（阶段 1c 修正，见下）。
         * - `__RF_RET=$?`：**立刻**记下正文退出码——后续 `cat` / `wc` 会覆盖 `$?`。
         * - `cat 文件`：把中转文件里的 stderr 内容搬进 stdout。漏掉这一步会让
         *   stderr 永久丢失（阶段 1b 曾因此返工）。
         * - `echo __RF_ERR_LINES__$(wc -l <文件)`：自报 stderr 行数，供
         *   [splitMergedOutput] 切回两路。
         * - `rm -f`：清理中转文件，避免污染下一次运行。
         * - `echo __RF_EXIT__$__RF_RET`：把正文真实退出码经 **stdout** 送回。
         *   阶段 1c 新增：外层 `setsid` **不传递退出码**（真机实测 `setsid -w` 也返回 0），
         *   因此退出码必须自己带回；由 [splitMergedOutput] 解析。
         * - `exit 0`：显式以 0 退出本 `sh -c`，不再依赖外层能否传递退出码。
         *   `exit` 只终止本子 shell，不影响 libsu 的交互 shell。
         *
         * ## 为什么不再用 fd 3 备份（阶段 1c 真机根因，勿回退）
         *
         * 阶段 1b 曾用 `exec 3>&1` 备份原始 stdout、`exec 4>&3; exec 2>&4` 把 stderr
         * 接到该备份。**该写法在目标机上会被 subshell 内的 stderr 写入破坏**：
         * 真机探针实测（`1c_probe.sh` 的对照实验，2026-09-19）：
         * - `exec 3>&1; ( echo SUB ) 2>/tmp/f; echo B; exec 4>&3; echo C` → `SUB/B/C` 全通过；
         * - 仅把 subshell 正文改成**真的向 stderr 写入**（`echo E >&2`）后，同一命令
         *   报 `/system/bin/sh: 4>&3 : bad file descriptor`，**`exec` 失败对非交互
         *   shell 是致命的** → `sh -c` 立即终止 → `__RF_RET` / `cat` / `rm -f` /
         *   `__RF_EXIT__` 全部不执行（真机表现为：stderr 行丢失、退出码回退到无意义的
         *   1、`/tmp/.rf_err_<runId>` 永久残留）。
         * - 阶段 1b 未暴露此缺陷，是因为当时用 `{ }`（不 fork，fd 3 不跨进程边界）；
         *   1c 为隔离正文的 `exit` 必须改用 `( )`，缺陷才显现。
         *
         * 因此改为：**stdout 直接继承外层 stdout（无缓冲问题），stderr 单独落中转文件后
         * 由 `cat` 读回**。收尾链不再跨 subshell 依赖任何文件描述符。
         * 代价不变：stderr 行排在全部 stdout 行之后。
         * **禁止重新引入 `exec N>&M` 形式的 fd 备份**（`ShellScriptLocalShellTest` 有禁忌断言）。
         *
         * 转义：正文内的单引号按 POSIX 惯用法写成 `'\''`，因此任意正文都能安全
         * 传递，且不会被宿主 shell 提前展开——这同时消除了注入面。
         */
        private fun wrapForShell(
            content: String,
            runId: String = STDERR_TMP_RUN_ID,
            env: Map<String, String> = emptyMap(),
        ): String {
            val escaped = content.replace("'", "'\\''")
            val stderrFile = stderrFilePath(runId)
            // ★ `env` 为空时**不产生任何行** —— 这是 1c 冻结契约（golden 逐字节比对）的前提，
            //   见 ShellScriptLocalShellTest。改动这里必须同步那条 golden。
            val exports = buildExportLines(env)
            return "sh -c '$exports(\n$escaped\n) 2>$stderrFile\n" +
                "__RF_RET=$?\n" +
                "cat $stderrFile\n" +
                "echo $STDERR_COUNT_PREFIX\$(wc -l <$stderrFile)\n" +
                "rm -f $stderrFile\n" +
                "echo $EXIT_CODE_PREFIX\$__RF_RET\n" +
                "exit 0'"
        }

        /**
         * 生成环境变量注入行（需求 §3.2：脚本必须拿到 `ROOTFLOW_*`）。
         *
         * ## ★ 为什么必须放在 subshell **内部**、正文之前
         * 三种写法都试过权衡：
         * | 位置 | 问题 |
         * |---|---|
         * | 外层（`setsid` 之前） | 用户正文的每行 stderr 会连带 `setsid`/`wait` 的上下文；且外层还包着 `<pgid 文件>` 那一段，语义被摊到两条命令上 |
         * | subshell **之后** | **无效** —— 正文已经跑完了 |
         * | **subshell 内、正文之前**（采纳） | 变量对正文可见；且不改变 `$?` 的取值（`export` 不改退出码） |
         *
         * ## 转义：复用既有惯用法，不新写一套
         * 值里的单引号按 POSIX 惯用法写成 `'\''`（与用户正文、与
         * [ProcessGroupManager.wrap] 的外层转义**同一套**）。因此值里的
         * `{`、`"`、`}`、`$`、反引号都是字面量 —— JSON 负载 `{"source":"boot"}` 安全。
         *
         * 最终命令里 `'\''` 会被 [ProcessGroupManager.wrap] **再转义一次**成 `'\'''\''`，
         * 这是**正确**的（两层 shell 各剥一层）。
         *
         * ## 空 `env` ⇒ 一个字符都不加
         * 1c 起 `ShellScriptLocalShellTest` 有**逐字节 golden** 守着包裹形态，
         * 那条契约属 1c 冻结范围。因此这里对空 map 必须返回空串，不能加一个空行。
         */
        private fun buildExportLines(env: Map<String, String>): String {
            if (env.isEmpty()) return ""
            return env.entries.joinToString(separator = "") { (key, value) ->
                "export " + key + "='" + value.replace("'", "'\\''") + "'\n"
            }
        }

        /**
         * 过滤出**可以安全注入**的环境变量（阶段 6b）。
         *
         * ## 为什么含换行的值必须被拒绝（**注入面，不是洁癖**）
         * POSIX 单引号串里**无法表示换行**。值含换行时拼接结果会把一条 export 拆成两条命令：
         * ```
         * export KEY='第一行
         * 第二行'          ← 第二行成了独立命令（可被构造出任意命令执行）
         * ```
         * 这不是"显示不正确"，而是**命令注入**。负载来自用户可配置的
         * `Trigger.params.payload`（需求 §3.3），因此必须显式拒绝而不是拼进去。
         *
         * 被拒绝的键**逐条告警**（[onWarning]），不静默：用户会看到"我配的 payload 没到脚本"，
         * 而原因必须是可查的。
         *
         * @return 可注入的子集（其余键已告警）
         */
        private fun sanitizeEnv(env: Map<String, String>): Map<String, String> {
            if (env.isEmpty()) return env
            val rejected = env.filterValues { it.contains('\n') || it.contains('\r') }
            if (rejected.isEmpty()) return env
            rejected.keys.forEach { key ->
                onWarning(
                    "SCRIPT_ENV_REJECTED key=$key reason=value contains a newline " +
                        "(cannot be represented in a POSIX single-quoted string; " +
                        "injecting it would split the command — refusing instead)",
                )
            }
            return env - rejected.keys
        }

        /**
         * 把合并输出切回 stdout / stderr 两路，并解析脚本自报的退出码。
         *
         * 依据 [wrapForShell] 追加的两个标记：
         * - `__RF_EXIT__<n>`：正文真实退出码（阶段 1c 新增，setsid 不传递退出码）
         * - `__RF_ERR_LINES__<n>`：stderr 行数，末尾 n 行属于 stderr，其余属于 stdout
         *
         * **切分算法与阶段 1b 完全一致**（1c 只在其前后做了"剥离退出码标记"与
         * "解析退出码"两件事），因此 1b 的分流断言继续成立。
         * 标记缺失时（例如运行被 kill）退出码为 `null`、全部行归入 stdout，保证内容不丢。
         */
        private fun splitMergedOutput(merged: String): SplitOutput {
            val allLines = merged.lineSequence().filter { it.isNotEmpty() }.toList()

            // 先剥离退出码标记（它排在最后一条），再走 1b 原有的切分逻辑。
            val exitMarkerIndex = allLines.indexOfLast { it.startsWith(EXIT_CODE_PREFIX) }
            val exitCode =
                if (exitMarkerIndex >= 0) {
                    allLines[exitMarkerIndex].removePrefix(EXIT_CODE_PREFIX).trim().toIntOrNull()
                } else {
                    null
                }
            val lines =
                if (exitMarkerIndex >= 0) {
                    allLines.filterIndexed { index, _ -> index != exitMarkerIndex }
                } else {
                    allLines
                }

            val markerIndex = lines.indexOfLast { it.startsWith(STDERR_COUNT_PREFIX) }
            if (markerIndex < 0) {
                return SplitOutput(stdout = lines, stderr = emptyList(), exitCode = exitCode)
            }

            val countText = lines[markerIndex].removePrefix(STDERR_COUNT_PREFIX).trim()
            val stderrCount = countText.toIntOrNull() ?: 0
            val stdoutLines = lines.subList(0, markerIndex)
            if (stderrCount <= 0 || stderrCount > stdoutLines.size) {
                // 计数异常（含 0）时不做切分，避免把 stdout 误判成 stderr
                return SplitOutput(stdout = stdoutLines, stderr = emptyList(), exitCode = exitCode)
            }
            val splitAt = stdoutLines.size - stderrCount
            return SplitOutput(
                stdout = stdoutLines.subList(0, splitAt),
                stderr = stdoutLines.subList(splitAt, stdoutLines.size),
                exitCode = exitCode,
            )
        }

        /**
         * 启动行文案。
         *
         * 触发事件存在时附带在启动行里（而非单独一行），便于阶段 3 接入事件系统后
         * 在日志中直接看出"这次是哪个事件触发的"。
         *
         * ## `RunContext.env` 的注入点（阶段 6b 更正）
         * 这里原先写着「`RunContext.env` 本阶段未使用：环境变量注入需要进程组包装（阶段 1c）」。
         * **那句话是错的、且正是真机缺陷的成因**：阶段 1c 做完进程组后**从未回头补注入**，
         * 于是 `TriggeredScriptRunner` 辛苦算好的 `ROOTFLOW_*`（含 **D10 的事件负载**）
         * 一路透传到本类后被**静默丢弃** —— 真机现象是
         * `RUN_VERIFY_SCRIPT … payload=` 后面空白（`$ROOTFLOW_EVENT_PAYLOAD` 是未定义变量，
         * 展开为空串）。现已由 [buildExportLines] 真正注入。
         */
        private fun startMessage(
            script: ScriptEntity,
            ctx: RunContext,
        ): String {
            val trigger = ctx.triggerEvent
            return if (trigger == null) {
                "script ${script.id} started"
            } else {
                "script ${script.id} started (trigger=$trigger)"
            }
        }

        /** 发射一条 SYS 行（运行时自身的日志，非脚本输出）。 */
        private suspend fun FlowCollector<LogLine>.emitSys(text: String) {
            emit(LogLine(System.currentTimeMillis(), LogStream.SYS, text))
        }

        /** [splitMergedOutput] 的返回值。 */
        private data class SplitOutput(
            val stdout: List<String>,
            val stderr: List<String>,
            /** 脚本自报的退出码；标记缺失（如运行被 kill）时为 `null`。 */
            val exitCode: Int?,
        )

        private companion object {
            /**
             * stderr 行数标记前缀。
             *
             * 形态为 `__RF_ERR_LINES__<n>`，单独成行。用 `indexOfLast` 定位，
             * 因此即使脚本文本里出现同名前缀也不会误判（脚本自己的输出排在更前面）。
             */
            const val STDERR_COUNT_PREFIX = "__RF_ERR_LINES__"

            /**
             * 退出码标记前缀（阶段 1c 新增）。
             *
             * 形态为 `__RF_EXIT__<n>`。存在的理由：外层 `setsid` **不传递退出码**
             * （真机实测连 `setsid -w` 也返回 0），因此正文退出码必须经 stdout 自报。
             */
            const val EXIT_CODE_PREFIX = "__RF_EXIT__"

            /**
             * stderr 中转文件路径前缀。
             *
             * 阶段 1c 起**按运行 id 唯一化**（原为固定 `/tmp/.rf_err`）：一旦存在并发
             * 或连续运行，固定路径会互相覆盖。目录用 `/tmp`（root shell 下可写）。
             */
            const val STDERR_FILE_PREFIX = "/tmp/.rf_err_"

            /** 生成某次运行的 stderr 中转文件路径。 */
            fun stderrFilePath(runId: String): String = "$STDERR_FILE_PREFIX$runId"

            /**
             * [wrapForShell] 在未显式传入运行 id 时使用的默认值。
             *
             * 仅供测试入口（`wrapForShellForTest`）使用，使 golden 比对保持确定性；
             * 生产路径一律传真实运行 id，从而获得按运行唯一化的中转文件。
             */
            const val STDERR_TMP_RUN_ID = "selftest"
        }

        /**
         * 测试可见入口：暴露 [wrapForShell] 生成的命令原文。
         *
         * 存在的唯一目的是让 `ShellScriptCommandTest` 打印**与真机下发的完全同一份**
         * 脚本文本，供本地 POSIX shell 逐字复跑（阶段 1b 新流程要求：脚本层先在本地
         * 验证通过，才允许打包请求真机验证）。不作为生产 API 使用。
         */
        fun wrapForShellForTest(
            content: String,
            env: Map<String, String> = emptyMap(),
        ): String = wrapForShell(content = content, env = env)
    }
