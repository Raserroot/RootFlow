package com.rootflow.runtime

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [ProcessGroupManager] 单元测试。
 *
 * 全部用例**不依赖真机**：对 [RootShellManager] 打桩即可（本类只经它工作，
 * 这也是"libsu 引用点收敛在 RootShellManager"带来的可测性收益）。
 *
 * 覆盖的阶段 1c 实测结论（都来自真机 E13 / 第一步验证）：
 * - `setsid ... & p=$!; echo $p > 文件; wait $p` 的包装形态与次序
 * - PGID 走文件 + 控制通道 `cat`（App 不直接读文件）
 * - **TERM 之后先探测存活，已消失则不发 KILL**（真机上 KILL 会报 No such process）
 * - 进程组仍存活时才升级到 KILL
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessGroupManagerTest {
    private val rootShellManager = mockk<RootShellManager>()

    private val manager = ProcessGroupManager(rootShellManager) { runId -> File("/tmp/.rf_pgid_$runId") }

    // ------------------------------------------------------------ 包装形态

    @Test
    fun `wrap starts a new process group and waits for it`() {
        val command = manager.wrap(INNER, RUN_ID)

        assertTrue(command.startsWith("setsid sh -c '"), "必须以 setsid 启动新进程组：$command")
        assertTrue(command.contains("' & p=\$!"), "必须后台化并捕获子进程 PID：$command")
        assertTrue(
            command.trimEnd().endsWith("wait \$p"),
            "必须以 wait 收尾，否则 libsu 收尾标记会与脚本输出竞争：$command",
        )
    }

    @Test
    fun `wrap writes the pgid file before waiting`() {
        val command = manager.wrap(INNER, RUN_ID)

        val writeIndex = command.indexOf("echo \$p > ${manager.shellPathFor(RUN_ID)}")
        val waitIndex = command.indexOf("wait \$p")
        assertTrue(writeIndex > 0, "必须把 PGID 写入按运行唯一化的文件：$command")
        assertTrue(
            writeIndex < waitIndex,
            "PGID 必须在 wait 之前写入，否则脚本运行期间读不到：$command",
        )
    }

    @Test
    fun `wrap keeps the inner script body intact`() {
        val command = manager.wrap(INNER, RUN_ID)

        assertTrue(command.contains("__RF_EXIT__"), "内部脚本的自报退出码必须保留：$command")
        assertTrue(command.contains("( exit 42 )"), "内部脚本正文必须原样保留：$command")
    }

    @Test
    fun `wrap escapes inner single quotes so the outer sh -c argument stays intact`() {
        val command = manager.wrap(INNER, RUN_ID)

        // 阶段 1c 真机根因：内层脚本自带 2 个单引号（sh -c '…' 的首尾）。
        // 不转义会让外层引号对提前闭合 → 外层只收到残破参数 `sh -c ` → 语法错误 → 整条命令不执行。
        assertTrue(
            command.contains("'\\''"),
            "内层单引号必须按 POSIX 惯用法转义为 '\\''：$command",
        )
        // 结构性防复发：转义正确时单引号必然成对（原始 2 个 → 转义后 4 个，仍为偶数）。
        assertEquals(
            0,
            command.count { it == '\'' } % 2,
            "命令中单引号数必须为偶数，否则外层引号对未闭合：$command",
        )
        // 必须仍以 setsid 启动，且外层参数不得被内层引号截断
        assertTrue(command.startsWith("setsid sh -c '"), "必须以 setsid 包装：$command")
        assertTrue(
            command.contains("' & p=\$!"),
            "setsid 参数必须以转义后的引号收尾再后台化：$command",
        )
    }

    @Test
    fun `wrap keeps the outer form stable regardless of trailing newline`() {
        val command = manager.wrap("$INNER\n", RUN_ID)

        // 关键回归点不是"某个拼接字符串相等"，而是：
        // 尾部换行被规整后，外层形态仍然完整 —— 否则 `exit 0` 与 `&` 粘连成
        // `exit 0& p=$!` 这类语法错误，或 PGID 写入/等待步骤丢失。
        assertTrue(
            !command.contains("exit 0& p="),
            "换行处理不当会让 exit 与 & 粘连成语法错误：$command",
        )
        assertTrue(
            command.contains("& p=\$!; echo \$p >"),
            "必须后台化子进程并紧跟 PGID 写入：$command",
        )
        assertTrue(
            command.trimEnd().endsWith("wait \$p"),
            "必须以 wait 收尾：$command",
        )
        assertTrue(
            command.contains("( exit 42 )"),
            "内部脚本正文不得被换行处理破坏：$command",
        )
    }

    // ------------------------------------------------------------ PGID 读取

    @Test
    fun `readPgid parses the value from the control channel`() =
        runTest {
            coEvery { rootShellManager.execControl(any()) } returns
                ShellResult(stdout = " 4242 \n", stderr = "", exitCode = 0)

            val pgid = manager.readPgid(RUN_ID)

            assertEquals(4242, pgid)
        }

    @Test
    fun `readPgid returns unresolved when the file is not readable yet`() =
        runTest {
            coEvery { rootShellManager.execControl(any()) } returns
                ShellResult(stdout = "", stderr = "", exitCode = 0)

            val pgid = manager.readPgid(RUN_ID)

            assertEquals(RunHandle.PGID_UNRESOLVED, pgid)
        }

    @Test
    fun `readPgidWithRetry returns unresolved after exhausting attempts`() =
        runTest {
            var calls = 0
            coEvery { rootShellManager.execControl(any()) } answers {
                calls++
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            val pgid = manager.readPgidWithRetry(RUN_ID, attempts = 3, intervalMillis = 1)

            assertEquals(RunHandle.PGID_UNRESOLVED, pgid)
            assertEquals(3, calls, "应按指定次数重试")
        }

    // ------------------------------------------------------------ 终止递进

    @Test
    fun `terminate sends TERM then skips KILL when the group is already gone`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                val out = if (cmd.startsWith("kill -TERM")) "" else ProcessGroupManager.GROUP_GONE_RESULT
                ShellResult(stdout = out, stderr = "", exitCode = 0)
            }

            val report = manager.terminate(pgid = 1234)

            assertTrue(report.pgidResolved)
            assertTrue(report.termSent, "应先发 TERM")
            assertFalse(report.killed, "整组已消失时不得再发 KILL")
            assertTrue(report.goneAfterTerm)
            assertTrue(
                commands.none { it.startsWith("kill -KILL") },
                "不得发出 KILL（真机上会报 No such process）：$commands",
            )
        }

    @Test
    fun `terminate escalates to KILL when the group survives TERM`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                val out = if (cmd.startsWith("kill -")) "" else ProcessGroupManager.GROUP_ALIVE_RESULT
                ShellResult(stdout = out, stderr = "", exitCode = 0)
            }

            val report = manager.terminate(pgid = 4321)

            assertTrue(report.termSent)
            assertTrue(report.killed, "存活时必须升级到 KILL")
            assertFalse(report.goneAfterTerm)
            assertTrue(
                commands.any { it == "kill -KILL -4321" },
                "必须对整组发 KILL：$commands",
            )
        }

    @Test
    fun `terminate with force skips TERM and goes straight to KILL`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                val out = if (cmd.startsWith("kill -")) "" else ProcessGroupManager.GROUP_ALIVE_RESULT
                ShellResult(stdout = out, stderr = "", exitCode = 0)
            }

            val report = manager.terminate(pgid = 99, force = true)

            assertFalse(report.termSent, "force 模式不应再发 TERM")
            assertTrue(report.killed)
            assertTrue(commands.none { it == "kill -TERM -99" }, "force 模式不得发 TERM：$commands")
        }

    @Test
    fun `terminate reports unresolved when the pgid is unknown`() =
        runTest {
            val report = manager.terminate(pgid = RunHandle.PGID_UNRESOLVED)

            assertFalse(report.pgidResolved)
            assertFalse(report.termSent)
            assertFalse(report.killed)
        }

    // ------------------------------------------------------------ 存活探测

    @Test
    fun `isGroupAlive reflects the probe output`() =
        runTest {
            coEvery { rootShellManager.execControl(any()) } returns
                ShellResult(stdout = ProcessGroupManager.GROUP_ALIVE_RESULT, stderr = "", exitCode = 0)
            assertTrue(manager.isGroupAlive(777))

            coEvery { rootShellManager.execControl(any()) } returns
                ShellResult(stdout = ProcessGroupManager.GROUP_GONE_RESULT, stderr = "", exitCode = 0)
            assertFalse(manager.isGroupAlive(777))
        }

    @Test
    fun `isGroupAlive is false when the probe command fails`() =
        runTest {
            coEvery { rootShellManager.execControl(any()) } returns
                ShellResult(stdout = "", stderr = "control channel is not available", exitCode = -3)

            assertFalse(manager.isGroupAlive(777), "探测不可用时按未存活处理，避免阻塞主流程")
        }

    // ------------------------------------------------------------ 三态探测（1c 性能修复）

    @Test
    fun `probe command uses a single ps invocation`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                commands += firstArg<String>()
                ShellResult(stdout = ProcessGroupManager.GROUP_GONE_RESULT, stderr = "", exitCode = 0)
            }

            manager.probeGroupLiveness(4321)

            val probe = commands.single()
            // 阶段 1c 性能修复：旧实现逐 PID spawn `ps`（真机 500-1500 次），
            // 单次探测耗时 20-30s，远超 execControl 的 10s 上限。
            assertEquals(
                1,
                Regex("""\bps\b""").findAll(probe).count(),
                "探测命令必须只调用一次 ps（禁止逐 PID spawn）：$probe",
            )
        }

    @Test
    fun `probe command distinguishes a failed ps from an empty result`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                commands += firstArg<String>()
                ShellResult(stdout = ProcessGroupManager.GROUP_GONE_RESULT, stderr = "", exitCode = 0)
            }

            manager.probeGroupLiveness(4321)

            val probe = commands.single()
            assertTrue(
                probe.contains(ProcessGroupManager.GROUP_PROBE_FAILED_RESULT),
                "探测失败必须有独立标记，否则无法与「已消失」区分：$probe",
            )
            assertTrue(
                probe.contains(ProcessGroupManager.GROUP_ALIVE_RESULT) &&
                    probe.contains(ProcessGroupManager.GROUP_GONE_RESULT),
                "三种结果标记必须齐备：$probe",
            )
        }

    @Test
    fun `probeGroupLiveness maps the three probe outcomes`() =
        runTest {
            suspend fun probeFor(stdout: String): GroupLiveness {
                coEvery { rootShellManager.execControl(any()) } returns
                    ShellResult(stdout = stdout, stderr = "", exitCode = 0)
                return manager.probeGroupLiveness(777)
            }

            assertEquals(GroupLiveness.ALIVE, probeFor(ProcessGroupManager.GROUP_ALIVE_RESULT))
            assertEquals(GroupLiveness.GONE, probeFor(ProcessGroupManager.GROUP_GONE_RESULT))
            assertEquals(
                GroupLiveness.UNKNOWN,
                probeFor(ProcessGroupManager.GROUP_PROBE_FAILED_RESULT),
                "ps 执行失败必须映射为 UNKNOWN，不得当作已消失",
            )
        }

    @Test
    fun `probeGroupLiveness is UNKNOWN for transport failure, timeout and garbage output`() =
        runTest {
            suspend fun probeWith(result: ShellResult): GroupLiveness {
                coEvery { rootShellManager.execControl(any()) } returns result
                return manager.probeGroupLiveness(777)
            }

            assertEquals(
                GroupLiveness.UNKNOWN,
                probeWith(ShellResult(stdout = "", stderr = "unavailable", exitCode = -3)),
                "控制通道不可用必须映射为 UNKNOWN",
            )
            assertEquals(
                GroupLiveness.UNKNOWN,
                probeWith(ShellResult(stdout = "", stderr = "timeout", exitCode = -4)),
                "超时必须映射为 UNKNOWN",
            )
            assertEquals(
                GroupLiveness.UNKNOWN,
                probeWith(ShellResult(stdout = "some unexpected text", stderr = "", exitCode = 0)),
                "非预期输出必须映射为 UNKNOWN",
            )
        }

    @Test
    fun `terminate still sends KILL when the probe is UNKNOWN`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                // 探测一律失败：模拟 ps 不可用 / 控制通道超时。
                val out = if (cmd.startsWith("kill -")) "" else ProcessGroupManager.GROUP_PROBE_FAILED_RESULT
                ShellResult(stdout = out, stderr = "", exitCode = 0)
            }

            val report = manager.terminate(pgid = 5555)

            // 核心护栏：探测不可信时**绝不能**当作"已消失"而跳过 KILL，
            // 否则会把仍存活的进程组留在设备上。
            assertTrue(report.killed, "探测失败时必须升级到 KILL")
            assertFalse(report.goneAfterTerm, "探测失败不得声称整组已消失")
            assertTrue(report.probeFailed, "必须如实报告探测不可信")
            assertTrue(
                commands.any { it == "kill -KILL -5555" },
                "探测失败时必须发出 KILL：$commands",
            )
        }

    @Test
    fun `terminate still sends KILL when the control channel is unavailable`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                if (cmd.startsWith("kill -")) {
                    ShellResult(stdout = "", stderr = "", exitCode = 0)
                } else {
                    ShellResult(stdout = "", stderr = "control channel is not available", exitCode = -3)
                }
            }

            val report = manager.terminate(pgid = 6666)

            assertTrue(report.killed, "控制通道不可用时不得跳过 KILL")
            assertTrue(report.probeFailed)
            assertTrue(commands.any { it == "kill -KILL -6666" }, "必须发出 KILL：$commands")
        }

    @Test
    fun `cleanup removes the pgid file through the control channel`() =
        runTest {
            val commands = mutableListOf<String>()
            coEvery { rootShellManager.execControl(any()) } answers {
                commands += firstArg<String>()
                ShellResult(stdout = "", stderr = "", exitCode = 0)
            }

            manager.cleanup(RUN_ID)

            assertTrue(
                commands.any { it == "rm -f ${manager.shellPathFor(RUN_ID)}" },
                "应清理按运行唯一化的 PGID 文件：$commands",
            )
        }

    @Test
    fun `pgid file path is unique per run id`() {
        val a = manager.pgidFile("run-a").absolutePath
        val b = manager.pgidFile("run-b").absolutePath

        assertTrue(a != b, "PGID 文件必须按运行唯一化")
        assertTrue(a.contains("run-a") && b.contains("run-b"))
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val INNER = "sh -c 'exec 3>&1\n( exit 42 )\necho __RF_EXIT__\$?'"
    }
}
