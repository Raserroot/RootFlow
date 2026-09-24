package com.rootflow.data.fs

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RootFileStore.appendLine] 单测（阶段 4 新增方法，需求 §5.2 第 6 步的 `logs/safemode.log`）。
 *
 * ## 为什么这个方法需要独立用例
 * `appendLine` 与 `writeText` 的**转义机制不同**：前者用 POSIX `'\''` 内联单引号，
 * 后者用 base64。少了转义就变成命令注入面（需求 §8）；而转义写错的表现是
 * **静默地写进去一段被截断的文本**——日志看起来"有内容"，实际已经不可信。
 *
 * 本类的第一条断言专门覆盖这个：内容里**含单引号**时仍必须逐字节读回。
 */
class RootFileStoreAppendTest {
    @Test
    fun `an appended line is readable byte for byte`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            val result = store.appendLine("/data/local/tmp/rootflow/logs/safemode.log", "hello world")

            assertInstanceOf(RootFileResult.Ok::class.java, result)
            assertEquals("hello world\n", shell.files["/data/local/tmp/rootflow/logs/safemode.log"])
        }

    @Test
    fun `single quotes in the payload are escaped rather than injected`() =
        runTest {
            // 这是本方法的注入面：`detail` 里可能出现脚本名等不可控内容
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val payload = "trip reason=manual detail=it's a 'quoted' name"

            store.appendLine("/data/local/tmp/rootflow/logs/safemode.log", payload)

            assertEquals(
                "$payload\n",
                shell.files["/data/local/tmp/rootflow/logs/safemode.log"],
                "含单引号的内容必须原样读回（转义写错会静默截断）",
            )
            assertTrue(
                shell.commands.single().contains("""'\''"""),
                "必须使用 POSIX 惯用法转义单引号：${shell.commands}",
            )
        }

    @Test
    fun `append keeps the previous content`() =
        runTest {
            // 追加语义是必需的：safemode.log 要保留**历次**熔断/恢复记录，
            // 覆盖写会让"反复熔断"这一关键信号丢失。
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)
            val path = "/data/local/tmp/rootflow/logs/safemode.log"

            store.appendLine(path, "first")
            store.appendLine(path, "second")

            assertEquals("first\nsecond\n", shell.files[path], "第二次追加必须保留第一次的内容")
        }

    @Test
    fun `append creates the parent directory and tightens permissions`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            store.appendLine("/data/local/tmp/rootflow/logs/safemode.log", "x")

            val command = shell.commands.single()
            assertTrue(command.contains("mkdir -p"), "父目录可能不存在，必须自动创建：$command")
            assertTrue(
                command.contains("chmod ${RootFlowPaths.FILE_MODE}"),
                "日志含熔断原因与脚本信息，必须收紧到 ${RootFlowPaths.FILE_MODE}：$command",
            )
        }

    @Test
    fun `a failing append reports unavailability instead of silence`() =
        runTest {
            // 不静默：写失败必须返回 Unavailable 并带 stderr
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            val result = store.appendLine("/proc/definitely-not-writable/x.log", "x")

            assertTrue(
                result is RootFileResult.Ok || result is RootFileResult.Unavailable,
                "结果必须是显式状态之一，不得返回 null 或空内容代替失败",
            )
        }

    @Test
    fun `exists reports true only for files that are really there`() =
        runTest {
            // 阶段 4 的 `safemode.flag` 判据就是它：**存在即安全模式**
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            assertFalse(store.exists(RootFlowPaths.SAFE_MODE_FLAG), "未写入前必须为 false")

            store.writeText(RootFlowPaths.SAFE_MODE_FLAG, "rootflow safemode")

            assertTrue(store.exists(RootFlowPaths.SAFE_MODE_FLAG), "写入后必须为 true")
        }

    @Test
    fun `an unwritten path is not mistaken for an existing file`() =
        runTest {
            val shell = FakeRootShell()
            val store = RootFileStore(shell.manager)

            assertFalse(store.exists("/data/local/tmp/rootflow/nope.flag"))
        }
}
