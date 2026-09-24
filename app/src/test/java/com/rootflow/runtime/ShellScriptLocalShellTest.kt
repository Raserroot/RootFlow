package com.rootflow.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 阶段 1b **脚本层自检**。
 *
 * 背景：阶段 1b 曾两度把只有真机才能发现的问题交给用户验证（先是缺 `sh -c`，
 * 后是 stderr 落文件后缺 `cat` 读回），浪费了两轮真机往返。按新流程，涉及 shell
 * 脚本逻辑的修复**必须先在脚本层验证**，才允许打包请求真机验证。
 *
 * ## 本环境的能力边界（重要）
 *
 * 本项目所在的 Windows 沙箱**禁止命名管道**，因此 MSYS2 系 shell（Git for Windows
 * 的 `sh.exe` / `bash.exe`）虽能启动，但运行结果不可信：实测 `processExitCode=0`
 * 却丢失 stderr 与脚本追加的标记行。WSL 亦无已安装发行版。
 *
 * 因此本测试的实际保障分三层，**前两层始终生效**，第三层在 shell 不可信时跳过：
 *
 * 1. **golden 逐字节比对**：生成命令必须与 [EXPECTED_COMMAND] 完全一致。
 * 2. **结构完整性断言**：校验 stderr 中转链路的关键环节齐备（写入 → 读回 → 计数
 *    → 清理 → 退出码恢复），并**强制**"写文件的动作必须伴随读回动作"——
 *    这正是上一轮缺 `cat` 的缺陷所在，属于即使无法执行也能静态抓出的错误。
 * 3. **本地 shell 执行**（[assumeTrue] 守卫）：把同一份命令交给本机 POSIX shell 跑，
 *    断言 stdout / stderr / 退出码。需要外部环境先满足"允许命名管道"。
 */
class ShellScriptLocalShellTest {
    @Test
    fun `generated command matches the reviewed literal byte for byte`() {
        val command = ShellScriptRuntime().wrapForShellForTest(SELF_TEST_CONTENT)

        assertEquals(
            EXPECTED_COMMAND.replace("\r\n", "\n"),
            command,
            "生成的 shell 命令与已审阅的字面量不一致——脚本逻辑变更必须先在此处体现并重新审阅",
        )
    }

    @Test
    fun `stderr relay chain is structurally complete`() {
        val command = ShellScriptRuntime().wrapForShellForTest(SELF_TEST_CONTENT)

        // 1) stderr 必须被重定向到中转文件（subshell 的 stderr）
        assertTrue(
            command.contains(") 2>$STDERR_FILE"),
            "subshell 的 stderr 未被重定向到中转文件：\n$command",
        )

        // 2) 关键回归：写了文件就必须把文件读回 stdout。
        //    上一轮的缺陷正是漏掉这一步，导致 stderr 永久丢失。
        val writeIndex = command.indexOf("2>$STDERR_FILE")
        val readBackIndex = command.indexOf("cat $STDERR_FILE")
        assertTrue(readBackIndex > 0, "缺少 stderr 读回动作（cat $STDERR_FILE）：\n$command")
        assertTrue(
            readBackIndex > writeIndex,
            "读回动作必须出现在写入之后：\n$command",
        )

        // 3) 必须在 cat/wc 之前保存退出码（二者都会覆盖 $?）
        val retIndex = command.indexOf("__RF_RET=\$?")
        assertTrue(retIndex in 1..<readBackIndex, "退出码必须在 cat/wc 之前保存：\n$command")

        // 4) 必须自报 stderr 行数（供上层切分）
        assertTrue(
            command.contains("$STDERR_COUNT_PREFIX\$(wc -l <$STDERR_FILE)"),
            "缺少 stderr 行数标记：\n$command",
        )

        // 5) 必须清理中转文件，避免污染下次运行
        assertTrue(command.contains("rm -f $STDERR_FILE"), "缺少中转文件清理：\n$command")

        // 6) 必须恢复退出码，使 libsu 的 $? 收尾取到正文真实退出码
        assertTrue(command.contains("echo __RF_EXIT__"), "缺少退出码自报：\n$command")

        // 7) 禁止跨 subshell 的 fd 备份（阶段 1c 真机根因，见 ShellScriptRuntime.wrapForShell 文档）。
        //    目标机实测：subshell 内一旦真的向 stderr 写入，外层 `exec 4>&3` 即报
        //    `bad file descriptor`；`exec` 失败对非交互 shell 致命，会让整段收尾链
        //    （__RF_RET / cat / rm -f / __RF_EXIT__）全部不执行。
        //    这是**静态即可抓出**的禁忌，不依赖执行层用例。
        assertTrue(
            !Regex("""exec\s+\d+>&""").containsMatchIn(command),
            "禁止使用 exec N>&M 形式的 fd 备份（subshell 内写 stderr 后会使 fd 失效，" +
                "导致收尾链被致命终止）：\n$command",
        )
        assertTrue(
            !command.contains("2>&1"),
            "不应使用 2>&1（会与 stdout 分流冲突）：\n$command",
        )

        // 8) 正文必须放进 **subshell**（`( ... )`），不能用 group command（`{ ... }`）。
        //    `{ }` 在当前 shell 内执行，正文里的 exit 会终止整个 sh -c，
        //    使 __RF_RET / cat 全部失效——阶段 1b 曾因此返工。
        assertTrue(command.contains("(\n"), "正文必须放在 subshell 中：\n$command")
        assertTrue(
            command.contains(") 2>$STDERR_FILE"),
            "subshell 的 stderr 必须重定向到中转文件：\n$command",
        )
        assertTrue(
            !command.contains("{\n"),
            "不得使用 group command `{ }`（正文的 exit 会终止外层 sh -c）：\n$command",
        )
    }

    @Test
    fun `stderr is read back to stdout and exit code is restored`() {
        val sh = findWorkingPosixShell()
        assumeTrue(
            sh != null,
            "本机无结果可信的 POSIX shell（沙箱禁止命名管道），跳过执行层自检",
        )

        val command = ShellScriptRuntime().wrapForShellForTest(SELF_TEST_CONTENT)
        val run = runShell(sh!!, command)
        val transcript = run.transcript

        assertTrue(transcript.contains("one"), "stdout 缺少 one：\n$transcript")
        assertTrue(transcript.contains("two"), "stdout 缺少 two：\n$transcript")
        assertTrue(transcript.contains("three"), "stdout 缺少 three：\n$transcript")
        assertTrue(transcript.contains("oops"), "stderr 内容未被搬到 stdout（漏了 cat？）：\n$transcript")
        assertTrue(
            transcript.contains("$STDERR_COUNT_PREFIX$STDERR_EXPECTED_LINES"),
            "未自报 stderr 行数 $STDERR_EXPECTED_LINES：\n$transcript",
        )
        assertEquals(
            SELF_TEST_EXIT_CODE.toString(),
            run.reportedExitCode,
            "退出码未被恢复：\n$transcript",
        )
    }

    @Test
    fun `non-zero exit code from content is restored`() {
        val sh = findWorkingPosixShell()
        assumeTrue(sh != null, "本机无结果可信的 POSIX shell，跳过执行层自检")

        val run = runShell(sh!!, ShellScriptRuntime().wrapForShellForTest("echo x >&2; exit 7"))

        assertTrue(run.transcript.contains("x"), "stderr 内容未搬回：\n${run.transcript}")
        assertEquals("7", run.reportedExitCode, "非零退出码未被恢复：\n${run.transcript}")
    }

    @Test
    fun `content with non-zero exit still returns non-zero code`() {
        val command = ShellScriptRuntime().wrapForShellForTest("echo bad >&2; exit 42")

        // 结构层：退出码必须先被保存、再被自报（无需执行即可校验）
        assertTrue(command.contains("__RF_RET=\$?"), "退出码未被保存：\n$command")
        assertTrue(
            command.contains("echo __RF_EXIT__\$__RF_RET"),
            "退出码未被自报（setsid 不传递退出码，故必须经 stdout 带回）：\n$command",
        )

        // 执行层：workaround 不得吞掉正文的退出码
        val sh = findWorkingPosixShell()
        assumeTrue(sh != null, "本机无结果可信的 POSIX shell，跳过执行层校验")

        val run = runShell(sh!!, command)
        assertTrue(run.transcript.contains("bad"), "stderr 未搬回：\n${run.transcript}")
        assertEquals("42", run.reportedExitCode, "退出码被 workaround 吞掉了：\n${run.transcript}")
    }

    @Test
    fun `no fd backup is used across the subshell boundary`() {
        val command = ShellScriptRuntime().wrapForShellForTest("( echo FDTEST ) 2>/dev/null; exit 0")

        // 阶段 1c 真机根因的静态护栏：stdout 直接继承外层，不做任何 fd 备份。
        // 目标机上 subshell 内写 stderr 会让 `exec 4>&3` 报 bad file descriptor，
        // 而 exec 失败对非交互 shell 是致命的——收尾链会被整段跳过。
        assertTrue(
            !Regex("""exec\s+\d+>&""").containsMatchIn(command),
            "不得引入 exec N>&M 形式的 fd 备份：\n$command",
        )
        // 正文仍必须处于 subshell 中，stderr 仍必须落中转文件
        assertTrue(command.contains("(\n"), "正文必须放在 subshell 中：\n$command")
        assertTrue(command.contains(") 2>$STDERR_FILE"), "subshell 的 stderr 未落中转文件：\n$command")
        assertTrue(command.contains("FDTEST"), "测试正文未嵌入：\n$command")
    }

    /**
     * 寻找**结果可信**的 POSIX shell：必须通过"冒烟探测"——进程退出码为 0，
     * **且**能读到脚本追加的标记行。只检测文件存在是不够的：沙箱下 `sh.exe`
     * 能启动却会丢失输出。
     */
    private fun findWorkingPosixShell(): String? =
        CANDIDATE_SHELLS.firstOrNull { path ->
            File(path).isFile && runShell(path, "echo $PROBE_MARKER").isTrustworthy
        }

    /**
     * 执行命令原文并返回结果（转录已把 CRLF 规范化为 LF）。
     *
     * 在生成命令之后追加 `__RF_EXIT=$?`，用于捕获**被包裹命令自身**的退出码
     * （即真机上 libsu 会看到的那个值）。
     */
    private fun runShell(
        sh: String,
        command: String,
    ): ShellRun {
        val script = File.createTempFile("rf-shellcheck", ".sh")
        val outFile = File.createTempFile("rf-shellcheck-out", ".txt")
        return try {
            script.writeText("$command\n__RF_EXIT=\$?\n", Charsets.UTF_8)
            val exit =
                ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    "\"\"$sh\" \"${script.absolutePath}\" > \"${outFile.absolutePath}\" 2>&1\"",
                ).start().waitFor()
            ShellRun(
                transcript = outFile.readText(Charsets.UTF_8).replace("\r\n", "\n"),
                processExitCode = exit,
            )
        } finally {
            script.delete()
            outFile.delete()
        }
    }

    /** 一次 shell 执行的结果。 */
    private data class ShellRun(
        val transcript: String,
        val processExitCode: Int,
    ) {
        /** 脚本内记录的退出码（`__RF_EXIT=<n>`）；每行先 trim 以兼容 CRLF。 */
        val reportedExitCode: String?
            get() =
                transcript
                    .lineSequence()
                    .map { it.trim() }
                    .lastOrNull { it.startsWith("__RF_EXIT=") }
                    ?.removePrefix("__RF_EXIT=")

        /**
         * 本次执行是否可信：退出码为 0 且能读到脚本追加的标记行。
         *
         * 沙箱下 `sh.exe` 可能返回 0 却完全不产出追加标记（输出在退出前丢失），
         * 这种结果不能用来判定脚本正确性。
         */
        val isTrustworthy: Boolean
            get() = processExitCode == 0 && reportedExitCode != null
    }

    private companion object {
        const val STDERR_COUNT_PREFIX = "__RF_ERR_LINES__"
        const val STDERR_EXPECTED_LINES = 1
        const val SELF_TEST_EXIT_CODE = 0
        const val STDERR_FILE = "/tmp/.rf_err_selftest"

        /** shell 可用性冒烟探测的标记串。 */
        const val PROBE_MARKER = "RF_SHELL_PROBE_OK"

        /** 与真机自检脚本一致：stdout 3 行 + stderr 1 行 + exit 0。 */
        const val SELF_TEST_CONTENT = "printf \"one\\ntwo\\nthree\\n\"; printf \"oops\\n\" >&2; exit 0"

        /**
         * 已审阅的命令字面量。
         *
         * 逐点对应 [ShellScriptRuntime.wrapForShell] 的 KDoc：
         * **`( 正文 ) 2>文件`** 兜住 stderr（必须是 subshell，否则正文的 `exit` 会终止
         * 整个 `sh -c`，收尾步骤全部失效）→ `__RF_RET=$?` 记退出码
         * → **`cat` 把 stderr 搬回 stdout** → 自报行数 → 清理 → **自报退出码** → `exit 0`。
         *
         * **阶段 1c 修正**：不再做 fd 备份（原先的 `exec 3>&1` / `exec 4>&3` / `exec 2>&4`
         * 已移除）。真机实测：subshell 内一旦真的向 stderr 写入，外层 `exec 4>&3` 报
         * `bad file descriptor`，而 `exec` 失败对非交互 shell 致命，导致收尾链被整段跳过
         * （stderr 丢失、退出码回退、中转文件残留）。stdout 现直接继承外层。
         *
         * 中转文件路径在阶段 1c 起**按运行 id 唯一化**，测试入口（`wrapForShellForTest`）
         * 使用固定的 `STDERR_TMP_RUN_ID`，因此这里的字面量依然确定。
         */
        const val EXPECTED_COMMAND =
            "sh -c '(\n" +
                "printf \"one\\ntwo\\nthree\\n\"; printf \"oops\\n\" >&2; exit 0\n" +
                ") 2>/tmp/.rf_err_selftest\n" +
                "__RF_RET=\$?\n" +
                "cat /tmp/.rf_err_selftest\n" +
                "echo __RF_ERR_LINES__\$(wc -l </tmp/.rf_err_selftest)\n" +
                "rm -f /tmp/.rf_err_selftest\n" +
                "echo __RF_EXIT__\$__RF_RET\n" +
                "exit 0'"

        val CANDIDATE_SHELLS =
            listOf(
                "C:\\Program Files\\Git\\bin\\sh.exe",
                "C:\\Program Files\\Git\\usr\\bin\\sh.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "/bin/sh",
                "/usr/bin/sh",
            )
    }
}
