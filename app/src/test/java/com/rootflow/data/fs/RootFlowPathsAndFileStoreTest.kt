package com.rootflow.data.fs

import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RootFlowPaths] 与 [RootFileStore] 单测（阶段 3a）。
 *
 * ## 无 Robolectric / 无真机
 * `RootFileStore` 只经 [RootShellManager] 工作，因此打桩即可，符合项目既有单测形态
 * （`ProcessGroupManagerTest` 同法）。
 *
 * ## 为什么自带一个"假 shell"
 * 只断言"命令字符串包含 chmod"无法证明写入真的能读回。这里用 [FakeRootShell] 解释
 * `mkdir -p` / `base64 -d` / `cat` / `test -f` / `rm` 等命令，维护一个内存文件表——
 * 于是"写→读"是**真的往返**，而不是字符串比对。
 */
class RootFlowPathsAndFileStoreTest {
    // ---------------------------------------------------------------- 路径

    @Test
    fun `paths follow the D7 decision`() {
        // 偏离项 D7：需求 §3.2 要求 /data/adb/rootflow，本设备不可用 → /data/local/tmp/rootflow
        assertEquals("/data/local/tmp/rootflow", RootFlowPaths.ROOT)
        assertEquals("/data/local/tmp/rootflow/scripts", RootFlowPaths.SCRIPTS)
        assertEquals("/data/local/tmp/rootflow/scripts/7", RootFlowPaths.scriptDir(7))
        assertEquals("/data/local/tmp/rootflow/scripts/7/main.sh", RootFlowPaths.scriptBody(7, "sh"))
        assertEquals("scripts/7/main.sh", RootFlowPaths.scriptBodyRelative(7, "sh"))
        assertEquals("scripts/7/meta.json", RootFlowPaths.scriptMetaRelative(7))
        assertEquals("/data/local/tmp/rootflow/safemode.flag", RootFlowPaths.SAFE_MODE_FLAG)
    }

    @Test
    fun `extension follows language with a safe fallback`() {
        assertEquals("sh", RootFlowPaths.extensionFor("shell"))
        assertEquals("sh", RootFlowPaths.extensionFor("SHELL"))
        assertEquals("lua", RootFlowPaths.extensionFor("lua"))
        // 未知语言回退 sh：该值只影响文件名，不影响执行语义
        assertEquals("sh", RootFlowPaths.extensionFor("python"))
    }

    // ---------------------------------------------------------------- 写入命令形态

    @Test
    fun `write hardens permissions and transfers the body as base64`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val path = RootFlowPaths.scriptBody(1, "sh")
            val content = "echo 'quoted'; exit 0"

            val result = store.writeText(path, content)

            assertTrue(result is RootFileResult.Ok, "写入应成功，实际 $result")
            val command = shell.commands.single()
            assertTrue(command.contains("mkdir -p '/data/local/tmp/rootflow/scripts/1'"), command)
            assertTrue(command.contains("chmod ${RootFlowPaths.DIR_MODE}"), "目录必须 700：$command")
            assertTrue(command.contains("chmod ${RootFlowPaths.FILE_MODE} '$path'"), "文件必须 600：$command")
            assertTrue(command.contains("base64 -d > '$path'"), "正文必须以 base64 传输：$command")
            // 需求 §3.2 的 base64 传输：载荷本身必须能在 App 侧无损重建
            assertEquals("sha256:", (result as RootFileResult.Ok).value.substring(0, 7))
        }

    @Test
    fun `write normalises the trailing newline`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val path = RootFlowPaths.scriptBody(2, "sh")

            store.writeText(path, "echo a\n\n\n")

            // 规范化 = 恰好一个结尾换行；与 readText 的去换行互为逆操作
            assertEquals("echo a\n", shell.files[path])
        }

    @Test
    fun `digest is stable and distinguishes content`() {
        val shell = FakeRootShell()
        val store = RootFileStore(shell.manager)

        val a = store.sha256Of("echo 1\n")
        val b = store.sha256Of("echo 1\n")
        val c = store.sha256Of("echo 2\n")

        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.startsWith("sha256:"))
        assertEquals(7 + 64, a.length, "sha256: + 64 位 hex")
    }

    // ---------------------------------------------------------------- 读回（真往返）

    @Test
    fun `write then read round trips the body`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val path = RootFlowPaths.scriptBody(3, "sh")
            val content = "set -e\nprintf \"one\\ntwo\\n\"\nexit 0"

            val written = store.writeText(path, content)
            val digest = (written as RootFileResult.Ok).value
            val read = store.readText(path, expectedSha256 = digest)

            assertEquals(RootFileResult.Ok(content), read, "写→读必须无损往返")
        }

    @Test
    fun `read reports Missing for a file that is absent`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            val read = store.readText(RootFlowPaths.scriptBody(404, "sh"))

            assertEquals(RootFileResult.Missing, read, "缺失必须是显式状态，不能伪装成空正文")
        }

    @Test
    fun `read reports Corrupted when the digest does not match`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val path = RootFlowPaths.scriptBody(4, "sh")
            store.writeText(path, "echo original")
            val staleDigest = store.sha256Of("echo original\n")

            // 模拟"文件被外部改动"
            shell.files[path] = "echo tampered\n"
            val read = store.readText(path, expectedSha256 = staleDigest)

            assertTrue(read is RootFileResult.Corrupted, "摘要不符必须被检出，实际 $read")
        }

    @Test
    fun `read reports Unavailable when the control channel fails`() =
        runTest {
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any()) } returns
                ShellResult(stdout = "", stderr = "control channel is not available", exitCode = -3)
            val store = RootFileStore(manager)

            val read = store.readText(RootFlowPaths.scriptBody(5, "sh"))

            assertTrue(read is RootFileResult.Unavailable, "通道失败必须与缺失区分，实际 $read")
        }

    // ---------------------------------------------------------------- 删除 / 目录

    @Test
    fun `delete removes the whole script directory`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val dir = RootFlowPaths.scriptDir(6)
            shell.files[RootFlowPaths.scriptBody(6, "sh")] = "echo x\n"
            shell.files[RootFlowPaths.scriptMeta(6)] = "{}"

            store.deleteScriptDirectory(6)

            assertTrue(shell.files.keys.none { it.startsWith("$dir/") }, "目录内容必须被清空：${shell.files.keys}")
        }

    @Test
    fun `ensureBaseDirectories creates root scripts and logs with 700`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            val result = store.ensureBaseDirectories()

            assertTrue(result is RootFileResult.Ok)
            val command = shell.commands.single()
            assertTrue(command.contains("mkdir -p '${RootFlowPaths.SCRIPTS}' '${RootFlowPaths.LOGS}'"), command)
            assertTrue(command.contains("chmod ${RootFlowPaths.DIR_MODE}"), "根与子目录必须 700：$command")
        }
}
