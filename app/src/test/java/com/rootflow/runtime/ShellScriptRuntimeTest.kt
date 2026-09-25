package com.rootflow.runtime

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ShellScriptRuntime] 单元测试。
 *
 * 全部用例**不依赖真实 Root 与 libsu**：对 [RootShellManager] 打桩即可，
 * 因为 [ShellScriptRuntime] 只通过它的公开 API 工作——这也是"libsu 引用点收敛在
 * [RootShellManager]"这条约束带来的可测性收益。
 *
 * 关于 stub 的写法：`RootShellManager.exec` 带默认参数 `timeoutMillis`，
 * 因此这里用 `any()` 通配，而不是容易因默认参数失配的 `any<String>()`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellScriptRuntimeTest {
    private val rootShellManager = mockk<RootShellManager>()

    private val runtime =
        ShellScriptRuntime(rootShellManager).also { shellRuntime ->
            // 生产路径由 Hilt 注入该字段；单测里显式装配。
            shellRuntime.processGroupManager = ProcessGroupManager(rootShellManager)
        }

    init {
        // run() 结束时会经控制通道读一次 PGID 文件；默认打桩为"读不到"，
        // 使既有的日志断言不被额外日志影响。
        coEvery { rootShellManager.execControl(any(), any()) } returns
            ShellResult(stdout = "", stderr = "", exitCode = 0)
    }

    // ------------------------------------------------------------ 输出映射

    @Test
    fun `stdout lines are mapped to STDOUT log lines in order`() =
        runTest {
            stubExec(stdout = "one\ntwo\nthree", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            val stdout = lines.filter { it.stream == LogStream.STDOUT }.map { it.text }
            assertEquals(listOf("one", "two", "three"), stdout)
        }

    @Test
    fun `stderr and stdout are separated into their own streams`() =
        runTest {
            // 新契约：stderr 经 stdout 通道回来，末尾标记声明 stderr 行数
            stubExec(stdout = "out1\nout2\nerr1\n__RF_ERR_LINES__1", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertEquals(
                listOf("out1", "out2"),
                lines.filter { it.stream == LogStream.STDOUT }.map { it.text },
            )
            assertEquals(
                listOf("err1"),
                lines.filter { it.stream == LogStream.STDERR }.map { it.text },
            )
            // SYS 流只承载运行时自身的行，绝不能混入脚本输出
            val sys = lines.filter { it.stream == LogStream.SYS }.map { it.text }
            assertFalse(sys.contains("out1"), "脚本 stdout 不应出现在 SYS 流中")
            assertFalse(sys.contains("err1"), "脚本 stderr 不应出现在 SYS 流中")
        }

    // ------------------------------------------------------------ SYS 起止

    @Test
    fun `SYS start and exit lines are emitted around the script output`() =
        runTest {
            stubExec(stdout = "hello", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(id = 42L), context(), newRunId()).output.toList()

            assertEquals(LogStream.SYS, lines.first().stream)
            assertEquals("script 42 started", lines.first().text)
            assertEquals(LogStream.SYS, lines.last().stream, "最后一行应为 SYS 退出行")
            assertEquals("script 42 exited with code 0", lines.last().text)
            // 前两条是 SYS 头（started / exec），之后才是脚本输出，最后一条是退出行。
            assertEquals(listOf("hello"), lines.drop(2).dropLast(1).map { it.text })
        }

    @Test
    fun `exit line reports the real exit code`() =
        runTest {
            stubExec(stdout = "boom\n__RF_ERR_LINES__1", stderr = "", exitCode = 7)

            val lines = runtime.run(entity(id = 5L), context(), newRunId()).output.toList()

            assertEquals("script 5 exited with code 7", lines.last().text)
            assertEquals(listOf("boom"), lines.filter { it.stream == LogStream.STDERR }.map { it.text })
        }

    @Test
    fun `trigger event is surfaced in the SYS start line`() =
        runTest {
            stubExec(stdout = "", stderr = "", exitCode = 0)

            val lines =
                runtime
                    .run(entity(id = 9L), RunContext(triggerEvent = "boot", env = emptyMap()), newRunId())
                    .output
                    .toList()

            assertEquals("script 9 started (trigger=boot)", lines.first().text)
        }

    @Test
    fun `blank output lines are dropped and timestamps are real millis`() =
        runTest {
            stubExec(stdout = "a\n\nb\n", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertEquals(
                listOf("a", "b"),
                lines.filter { it.stream == LogStream.STDOUT }.map { it.text },
                "空行应被丢弃（含输出末尾换行产生的空串）",
            )
            // 时间戳来自 System.currentTimeMillis()，必然是真实的正毫秒数
            assertTrue(lines.all { it.timestamp > 0L }, "每条日志都应带真实时间戳")
        }

    // ------------------------------------------- 命令构造（回归防护）

    // ------------------------------------------------ 阶段 6b：env 真正注入脚本

    @Test
    fun `the run context env is actually exported into the submitted command`() =
        runTest {
            // ★ 跨模块契约的"中间那一跳"（见 AGENT_PROTOCOL.md「跨模块契约必须有跨界断言」）。
            //
            // 此前写入侧（TriggeredScriptRunnerTest 断言 ctx.env 有值）与消费侧
            // （本文件的 golden 断言恒传空 env）**各自都绿**，而 env 从未被 export
            // ⇒ 真机上 $ROOTFLOW_EVENT_PAYLOAD 是未定义变量、展开为空串（D10 补验暴露）。
            // 这条用例断言的是"rootShellManager **实际收到**的命令"，因此那一跳再也躲不掉。
            val submitted = mutableListOf<String>()
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            runtime
                .run(
                    entity(content = "echo body"),
                    RunContext(
                        triggerEvent = "boot",
                        env = mapOf("ROOTFLOW_EVENT_PAYLOAD" to """{"source":"boot"}"""),
                    ),
                    newRunId(),
                ).output
                .toList()

            val command = submitted.single()
            // 管道最外层是 ProcessGroupManager.wrap（它自己会把 ' 再转义一遍），
            // 因此这里断言**可逐字符验证的部分**：前缀 + 未转义的 JSON 值 + 收尾链完整。
            // 转义形态本身在下面那条 `wrapForShell` 用例里**精确**断言（那一层才是本类职责）。
            assertTrue(command.contains("export ROOTFLOW_EVENT_PAYLOAD="), "env 必须真的 export：$command")
            assertTrue(
                command.contains("""{"source":"boot"}"""),
                "JSON 负载必须原样出现在命令里（{ \" } 都是字面量）：$command",
            )
            assertTrue(command.contains("__RF_EXIT__"), "注入不得破坏收尾链：$command")
            // 必须在正文之前：写在 subshell 之后等于没注入（正文已经跑完）
            val exportAt = command.indexOf("export ROOTFLOW_EVENT_PAYLOAD=")
            val bodyAt = command.indexOf("echo body")
            assertTrue(exportAt in 0..<bodyAt, "export 必须在正文之前：$command")
        }

    @Test
    fun `every env entry is exported, not just some`() =
        runTest {
            val submitted = mutableListOf<String>()
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            runtime
                .run(
                    entity(content = "echo body"),
                    RunContext(
                        triggerEvent = "boot",
                        env =
                            mapOf(
                                "ROOTFLOW_SCRIPT_ID" to "1",
                                "ROOTFLOW_EVENT" to "boot",
                                "ROOTFLOW_APP_VERSION" to "0.6.0-ui",
                                "ROOTFLOW_SAFEMODE" to "false",
                            ),
                    ),
                    newRunId(),
                ).output
                .toList()

            val command = submitted.single()
            listOf(
                "ROOTFLOW_SCRIPT_ID" to "1",
                "ROOTFLOW_EVENT" to "boot",
                "ROOTFLOW_APP_VERSION" to "0.6.0-ui",
                "ROOTFLOW_SAFEMODE" to "false",
            ).forEach { (key, value) ->
                assertTrue(
                    command.contains("export $key="),
                    "缺少 $key 的注入：$command",
                )
                assertTrue(command.contains(value), "$key 的值必须原样出现在命令里：$command")
            }
        }

    @Test
    fun `a value containing a single quote is escaped at the wrapper layer`() {
        // 断言在**本类负责的那一层**（`wrapForShell`）精确进行：只有一层转义，可逐字符验证。
        // `ProcessGroupManager.wrap` 的第二层转义由它自己的测试覆盖 ——
        // 在最终命令上断言"两层咬合"的形态曾连红两轮（值内部引号会被写两遍），
        // 那种断言既难读、又与被测职责错位。
        val command = ShellScriptRuntime().wrapForShellForTest("echo hi", mapOf("K" to "a'b"))

        assertTrue(
            command.contains("export K='a'\\''b'"),
            "值里的 ' 必须按 POSIX 惯用法写成 '\\''：$command",
        )
        assertTrue(command.contains("__RF_EXIT__"), "转义不得破坏收尾链：$command")
    }

    @Test
    fun `a payload with json braces survives the wrapper untouched`() {
        val command =
            ShellScriptRuntime()
                .wrapForShellForTest("echo hi", mapOf("ROOTFLOW_EVENT_PAYLOAD" to """{"source":"boot"}"""))

        assertTrue(
            command.contains("""export ROOTFLOW_EVENT_PAYLOAD='{"source":"boot"}'"""),
            "JSON 的 { \" } 必须是字面量（不需要转义）：$command",
        )
        // 正文与收尾链必须完好（注入是**前置**，不能把 subshell 结构挤坏）
        assertTrue(command.contains("echo hi"), "正文必须仍在：$command")
        assertTrue(command.contains("__RF_ERR_LINES__"), "stderr 计数标记必须仍在：$command")
    }

    @Test
    fun `a value containing a newline is refused with a warning instead of being injected`() =
        runTest {
            val submitted = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            runtime.onWarning = { warnings += it }
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            runtime
                .run(
                    entity(content = "echo body"),
                    RunContext(
                        triggerEvent = "boot",
                        // 换行无法在 POSIX 单引号串里表示：硬拼会把一条 export 拆成两条命令（注入面）
                        env = mapOf("EVIL" to "x\necho PWNED", "OK" to "fine"),
                    ),
                    newRunId(),
                ).output
                .toList()

            val command = submitted.single()
            assertFalse(command.contains("PWNED"), "含换行的值绝不能被拼进命令：$command")
            assertTrue(command.contains("export OK="), "其余变量仍必须注入：$command")
            assertTrue(command.contains("fine"), "被接受的值必须原样出现：$command")
            assertTrue(
                warnings.any { it.contains("SCRIPT_ENV_REJECTED") && it.contains("EVIL") },
                "拒绝必须逐条告警（不静默），实际=$warnings",
            )
        }

    @Test
    fun `an empty env produces exactly the same wrapper as before the change`() {
        // 1c 冻结契约：env 为空时包裹形态必须**逐字节不变**，
        // 否则 ShellScriptLocalShellTest 的 golden 会被无声改写（那条 golden 是 1c 的
        // "脚本层执行验证"唯一可用证据，见 AGENT_PROTOCOL.md §5.7）。
        val withDefault = ShellScriptRuntime().wrapForShellForTest("echo hi")
        val withExplicitEmpty = ShellScriptRuntime().wrapForShellForTest("echo hi", emptyMap())

        assertEquals(withDefault, withExplicitEmpty, "空 env 的两种写法必须完全一致")
        assertFalse(withDefault.contains("export "), "空 env 不得产生任何 export 行：$withDefault")
    }

    @Test
    fun `content is wrapped in sh -c with stderr captured into the marker`() =
        runTest {
            val submitted = mutableListOf<String>()
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            runtime.run(entity(content = "echo hi"), context(), newRunId()).output.toList()

            val command = submitted.single()
            // 包裹的必要性：>&2 需要 shell 解释；exit 只能终止子 shell，
            // 不能终止 libsu 的交互 shell（它依赖该 shell 回收退出码）。
            // 阶段 1c 起最外层是 setsid 进程组包装，内部仍是 sh -c '...'
            assertTrue(command.startsWith("setsid sh -c '"), "必须以 setsid 包装进程组：$command")
            // 内层仍是 sh -c '(...'，但被 ProcessGroupManager 转义后单引号变成 '\''
            assertTrue(
                command.contains("sh -c '\\''(\n"),
                "内部仍是 sh -c 包裹且正文处于 subshell（引号须已转义）：$command",
            )
            assertTrue(command.contains("echo hi"), "正文需原样嵌入：$command")
            assertTrue(command.contains("__RF_EXIT__"), "必须自报退出码（setsid 不传递）：$command")
            // 包裹的第二个必要性：目标设备上 libsu 的 stderr 管道不可用，
            // 必须在 shell 内把 stderr 兜住再搬进 stdout。
            assertTrue(command.contains("2>/tmp/.rf_err"), "正文的 stderr 需被重定向到中转文件")
            assertTrue(command.contains("__RF_ERR_LINES__"), "需追加 stderr 行数标记")
            // 阶段 1c 修正：禁止跨 subshell 的 fd 备份（真机上 subshell 内写 stderr 后
            // `exec 4>&3` 报 bad file descriptor，exec 失败致命 → 收尾链被整段跳过）。
            assertTrue(
                !Regex("""exec\s+\d+>&""").containsMatchIn(command),
                "不得使用 exec N>&M 形式的 fd 备份：$command",
            )
        }

    @Test
    fun `single quotes inside content are escaped for the shell`() =
        runTest {
            val submitted = mutableListOf<String>()
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            runtime.run(entity(content = "echo 'quoted'"), context(), newRunId()).output.toList()

            // POSIX 惯用法：正文内的 ' 先由 ShellScriptRuntime 写成 '\''，
            // 随后 ProcessGroupManager 把内层整体塞进 setsid sh -c '…' 时**再转义一次**。
            // 因此最终命令里不存在单次转义形态，只有双重转义形态。
            val sent = submitted.single()
            assertTrue(
                sent.contains("'\\''\\'\\'''\\''"),
                "内层引号在塞入 setsid sh -c 时必须二次转义，实际为：$sent",
            )
            assertTrue(
                sent.contains("quoted"),
                "正文内容必须原样保留，实际为：$sent",
            )
        }

    @Test
    fun `exec diagnostic line reports content length without leaking content`() =
        runTest {
            stubExec(stdout = "", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(id = 8L, content = "echo 12345"), context(), newRunId()).output.toList()

            val head = lines.take(2).map { it.text }
            assertEquals("script 8 started", head[0])
            assertEquals("script 8 exec: sh -c <10 chars>", head[1])
        }

    // ------------------------------- stderr 分流：合并输出的切分（真机偏差回归防护）

    @Test
    fun `stderr lines are split back out of the merged channel by the marker`() =
        runTest {
            // 目标设备上 stderr 只能经 stdout 通道回来，末尾标记给出 stderr 行数
            stubExec(stdout = "one\ntwo\nthree\noops\n__RF_ERR_LINES__1", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertEquals(
                listOf("one", "two", "three"),
                lines.filter { it.stream == LogStream.STDOUT }.map { it.text },
            )
            assertEquals(
                listOf("oops"),
                lines.filter { it.stream == LogStream.STDERR }.map { it.text },
                "标记声明的 stderr 行数必须被当作 STDERR 切出",
            )
        }

    @Test
    fun `marker line itself is never emitted as a log line`() =
        runTest {
            stubExec(stdout = "out\nerr1\nerr2\n__RF_ERR_LINES__2", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertFalse(
                lines.any { it.text.startsWith("__RF_ERR_LINES__") },
                "内部标记不得出现在日志里",
            )
            assertEquals(listOf("out"), lines.filter { it.stream == LogStream.STDOUT }.map { it.text })
            assertEquals(listOf("err1", "err2"), lines.filter { it.stream == LogStream.STDERR }.map { it.text })
        }

    @Test
    fun `missing or zero marker keeps every line on stdout without losing content`() =
        runTest {
            stubExec(stdout = "a\nb", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertEquals(
                listOf("a", "b"),
                lines.filter { it.stream == LogStream.STDOUT }.map { it.text },
                "标记缺失时不得丢内容，全部归 stdout",
            )
            assertTrue(lines.none { it.stream == LogStream.STDERR })
        }

    @Test
    fun `realistic merged output maps to three stdout and one stderr`() =
        runTest {
            // 与真机自检脚本形状一致：stdout 3 行 + stderr 1 行
            stubExec(
                stdout = "one\ntwo\nthree\noops\n__RF_ERR_LINES__1",
                stderr = "",
                exitCode = 0,
            )

            val lines =
                runtime
                    .run(
                        entity(content = "printf \"one\\ntwo\\nthree\\n\"; printf \"oops\\n\" >&2; exit 0"),
                        context(),
                        newRunId(),
                    ).output
                    .toList()

            assertEquals(
                listOf("one", "two", "three"),
                lines.filter { it.stream == LogStream.STDOUT }.map { it.text },
            )
            assertEquals(
                listOf("oops"),
                lines.filter { it.stream == LogStream.STDERR }.map { it.text },
                "stderr 必须作为独立流出现，不能被丢弃",
            )
        }

    @Test
    fun `non-zero exit code from the shell is surfaced verbatim`() =
        runTest {
            stubExec(stdout = "__RF_ERR_LINES__0", stderr = "", exitCode = 1)

            val lines = runtime.run(entity(id = 4L), context(), newRunId()).output.toList()

            assertEquals(
                "script 4 exited with code 1",
                lines.last().text,
                "退出码必须原样透传，不得被改写或吞掉",
            )
        }

    // ------------------------------------------------- 一次性收集（二次 collect）

    @Test
    fun `second collection of the same handle throws IllegalStateException`() =
        runTest {
            stubExec(stdout = "only-once", stderr = "", exitCode = 0)
            val handle = runtime.run(entity(), context(), newRunId())

            val first = handle.output.toList()
            assertEquals(1, first.count { it.stream == LogStream.STDOUT })

            val secondError =
                try {
                    handle.output.toList()
                    error("第二次收集本应抛 IllegalStateException，但正常返回了")
                } catch (thrown: IllegalStateException) {
                    thrown
                }
            assertEquals(RunHandle.CONSUMED_MESSAGE, secondError.message)
        }

    @Test
    fun `script runs exactly once even when collection stops early`() =
        runTest {
            var execCount = 0
            coEvery { rootShellManager.exec(any()) } answers {
                execCount++
                ShellResult(stdout = "a\nb\nc", stderr = "", exitCode = 0)
            }
            val handle = runtime.run(entity(), context(), newRunId())

            // 首次收集只取到第一条脚本 stdout 后即中断（触发流取消）。
            // 流开头有两条 SYS 头行（started / exec），所以跳过它们。
            val firstScriptLine = handle.output.drop(2).first()
            assertEquals("a", firstScriptLine.text)

            // 句柄已被占用：不允许复用（否则会再跑一次脚本）
            assertThrows(IllegalStateException::class.java) {
                // 非挂起 lambda 中不能直接调挂起函数，故用 runBlocking 包裹。
                runBlocking { handle.output.toList() }
            }
            assertEquals(1, execCount, "脚本只应被执行一次，即使首次收集提前中断")
        }

    // ------------------------------------------------------------ kill（阶段 1b）

    @Test
    fun `killed handle skips execution without calling the shell`() =
        runTest {
            var execCount = 0
            coEvery { rootShellManager.exec(any()) } answers {
                execCount++
                ShellResult(stdout = "should-not-run", stderr = "", exitCode = 0)
            }
            val handle = runtime.run(entity(id = 3L), context(), newRunId())

            runtime.kill(handle)
            assertTrue(handle.killed, "kill 后 killed 应为 true")

            val lines = handle.output.toList()

            assertEquals(1, lines.size)
            assertEquals(LogStream.SYS, lines.single().stream)
            assertTrue(lines.single().text.contains("skipped"), "应给出可观测的跳过说明")
            assertEquals(0, execCount, "被 kill 的句柄不得下发脚本")
        }

    @Test
    fun `handle is not killed by default`() =
        runTest {
            val handle = runtime.run(entity(), context(), newRunId())
            assertFalse(handle.killed)
        }

    @Test
    fun `each handle gets a distinct run id`() =
        runTest {
            val a = runtime.run(entity(), context(), newRunId())
            val b = runtime.run(entity(), context(), newRunId())
            assertNotEquals(a.id, b.id, "每次运行应有独立 UUID")
        }

    // ----------------------------------------------------------------- 夹具

    /**
     * 打桩 [RootShellManager.exec] 返回固定结果。
     *
     * 阶段 1c 起真实输出末尾带有脚本自报的 `__RF_EXIT__<n>` 标记；这里默认**自动补齐**
     * 该标记，使既有用例继续表达"退出码来自脚本自报"的真实契约。
     * 需要显式测试"标记缺失"时传 `includeExitMarker = false`。
     */
    private fun stubExec(
        stdout: String,
        stderr: String,
        exitCode: Int,
        includeExitMarker: Boolean = true,
    ) {
        val effectiveStdout =
            if (includeExitMarker) {
                "$stdout\n$EXIT_MARKER$exitCode"
            } else {
                stdout
            }
        coEvery { rootShellManager.exec(any()) } returns
            ShellResult(stdout = effectiveStdout, stderr = stderr, exitCode = exitCode)
    }

    private fun entity(
        id: Long = 1L,
        content: String = "echo hi",
    ): ScriptEntity =
        ScriptEntity(
            id = id,
            name = "test-script",
            language = "shell",
            enabled = true,
            timeoutSec = 60,
            autoDisableOnFail = false,
            runOnSafeMode = false,
            content = content,
        )

    private fun context(): RunContext = RunContext(triggerEvent = null, env = emptyMap())

    /**
     * 11e 补丁4：`ScriptRuntime.run` 现在要求调用方给出 `runId`（它同时决定
     * `.rf_pgid_<id>` / `.rf_err_<id>` 的文件名与日志里那个 id）。
     *
     * 本文件的用例各自只验证输出映射 / 日志分流 / 退出码 / 命令生成，**都不关心** runId，
     * 因此统一走这个薄封装，而不是把随机 UUID 在 29 个调用点各抄一遍。
     *
     * ⚠️ 这**不是**给生产留的后门：`runId` 在生产路径上必须由调度侧生成并贯穿到底。
     */
    private fun newRunId(): String = UUID.randomUUID().toString()

    // ------------------------------------- 11e 补丁4：runId 的贯穿

    /**
     * ★ **`run` 必须把调用方给的 `runId` 原样用作 `RunHandle.id`**。
     *
     * ## 它防的是什么
     * 此前 `run` 调 `createRunHandle { }` **不传 id**，句柄于是自己造了个随机 UUID。
     * 而 pgid / err 文件都以 `handle.id` 命名（`ProcessGroupManager` 的
     * `.rf_pgid_<id>`、`wrapForShell` 的 `.rf_err_<id>`），日志与 `terminateRun`
     * 用的却是**另一个** `runId` ⇒ `terminateRun(runId)` 永远找不到文件，
     * **超时终止与熔断的"杀进程"全是空操作**，而日志只显示"pgid 未解析"。
     *
     * ## 为什么这条必须写在本文件
     * 本文件的 `ShellScriptRuntime` 是**真实实现**（只打桩 `RootShellManager`）。
     * `TriggeredScriptRunnerTest` 那条同类断言**覆盖不到这里** —— 那里
     * `ShellScriptRuntime` 被整体 MockK 掉了，把 `createRunHandle(id = …)` 改回
     * 不传 id 它照样是绿的（已实测）。
     */
    @Test
    fun `the handle id is exactly the runId handed in`() =
        runTest {
            stubExec(stdout = "hi", stderr = "", exitCode = 0)

            val runId = newRunId()
            val handle = runtime.run(entity(), context(), runId)

            assertEquals(
                runId,
                handle.id,
                "★ handle.id 必须**就是**传入的 runId（否则 .rf_pgid_<id> 与日志对不上，" +
                    "terminateRun 找不到文件 ⇒ 杀进程是空操作）",
            )
        }

    // ------------------------------------- 退出码标记（阶段 1c 新增的回归防护）

    @Test
    fun `exit code marker overrides the shell result code`() =
        runTest {
            // shell 返回 0（setsid 不传递退出码），脚本自报 42 -> 必须采用 42
            stubExec(stdout = "out", stderr = "", exitCode = 0, includeExitMarker = false)
            coEvery { rootShellManager.exec(any()) } returns
                ShellResult(stdout = "out\n${EXIT_MARKER}42", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(id = 77L), context(), newRunId()).output.toList()

            assertEquals(
                "script 77 exited with code 42",
                lines.last().text,
                "退出码必须取自脚本自报的标记，而不是 setsid 的返回值",
            )
        }

    @Test
    fun `exit code falls back to shell result when the marker is missing`() =
        runTest {
            // 被 kill 的运行不会执行到自报退出码那一步
            stubExec(stdout = "partial", stderr = "", exitCode = 143, includeExitMarker = false)

            val lines = runtime.run(entity(id = 78L), context(), newRunId()).output.toList()

            assertEquals("script 78 exited with code 143", lines.last().text)
        }

    @Test
    fun `exit marker is stripped from the emitted log lines`() =
        runTest {
            stubExec(stdout = "only", stderr = "", exitCode = 0)

            val lines = runtime.run(entity(), context(), newRunId()).output.toList()

            assertTrue(
                lines.none { it.text.startsWith(EXIT_MARKER) },
                "退出码标记不得作为日志外泄：${lines.map { it.text }}",
            )
        }

    @Test
    fun `exit marker coexists with the stderr line count marker`() =
        runTest {
            stubExec(stdout = "a\nerr\n${STDERR_MARKER}1", stderr = "", exitCode = 5, includeExitMarker = false)
            coEvery { rootShellManager.exec(any()) } returns
                ShellResult(
                    stdout = "a\nerr\n${STDERR_MARKER}1\n${EXIT_MARKER}5",
                    stderr = "",
                    exitCode = 0,
                )

            val lines = runtime.run(entity(id = 79L), context(), newRunId()).output.toList()

            assertEquals(listOf("a"), lines.filter { it.stream == LogStream.STDOUT }.map { it.text })
            assertEquals(listOf("err"), lines.filter { it.stream == LogStream.STDERR }.map { it.text })
            assertEquals("script 79 exited with code 5", lines.last().text)
        }

    @Test
    fun `stderr temp file path is unique per run`() =
        runTest {
            stubExec(stdout = "", stderr = "", exitCode = 0)
            val submitted = mutableListOf<String>()
            coEvery { rootShellManager.exec(any()) } answers {
                submitted += firstArg<String>()
                ShellResult(stdout = "${EXIT_MARKER}0", stderr = "", exitCode = 0)
            }

            runtime.run(entity(), context(), newRunId()).output.toList()
            runtime.run(entity(), context(), newRunId()).output.toList()

            val paths =
                submitted
                    .map { cmd -> Regex("""/tmp/\.rf_err_\S+?(?=\s|$)""").find(cmd)?.value }
                    .filterNotNull()
            assertEquals(2, paths.size, "两次运行都应带上中转文件路径：$submitted")
            assertTrue(
                paths[0] != paths[1],
                "中转文件路径必须按运行唯一化，实际为：$paths",
            )
        }

    private companion object {
        const val EXIT_MARKER = "__RF_EXIT__"
        const val STDERR_MARKER = "__RF_ERR_LINES__"
    }
}
