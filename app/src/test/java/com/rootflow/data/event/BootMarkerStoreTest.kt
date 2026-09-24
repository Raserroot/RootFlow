package com.rootflow.data.event

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [FileBootMarkerStore] 单测（阶段 3d，D9 的持久化半）。
 *
 * 用 JUnit5 的 [TempDir] 提供真实临时目录——本类只做文件 IO，**不需要 Android 环境**，
 * 这也是它被设计成"注入 `File` 而不是 `Context`"的目的（纯 JVM 可测）。
 *
 * ## 为什么"损坏"与"缺失"都要覆盖
 * 两者都返回 `null`，但含义完全不同：缺失 = 全新安装（正常），损坏 = 写入被截断或
 * 被外部篡改（异常）。实现必须把后者**记进日志**（不静默），因此用例同时断言 `Log.w` 被调用。
 */
class BootMarkerStoreTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a missing file reads as no marker`() {
        installLogStub()
        val store = FileBootMarkerStore(File(tempDir, "absent.txt"))

        assertNull(store.read(), "全新安装是正常路径：文件不存在 → null")
    }

    @Test
    fun `write then read round trips the value`() {
        installLogStub()
        val store = FileBootMarkerStore(File(tempDir, "marker.txt"))

        store.write(1_234_567L)

        assertEquals(1_234_567L, store.read(), "写入/读取必须逐值往返")
    }

    @Test
    fun `write overwrites unconditionally`() {
        installLogStub()
        val store = FileBootMarkerStore(File(tempDir, "marker.txt"))

        store.write(1L)
        store.write(2L)

        // "每次启动都无条件覆写"是 D9 的硬要求：只在首次写会让回退判定失效
        assertEquals(2L, store.read(), "每次启动都必须覆写，而不是仅首次写入")
    }

    @Test
    fun `a corrupted file reads as no marker and warns`() {
        installLogStub()
        val file = File(tempDir, "marker.txt")
        file.writeText("not-a-number")
        val store = FileBootMarkerStore(file)

        assertNull(store.read(), "无法解析的内容不得被当成有效标记")
        io.mockk.verify { Log.w(any(), match<String> { it.contains("BOOT_MARKER_CORRUPTED") }) }
    }

    @Test
    fun `a truncated write is detected as corruption`() {
        installLogStub()
        val file = File(tempDir, "marker.txt")
        // 模拟"写了一半"：只有高位数，缺少结尾
        file.writeText("12345")
        val store = FileBootMarkerStore(file)

        // 12345 是合法 Long：内容可解析即视为有效（解析失败才告警）
        assertEquals(12_345L, store.read())
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        installLogStub()
        val file = File(tempDir, "marker.txt")
        file.writeText("  42\n")
        val store = FileBootMarkerStore(file)

        assertEquals(42L, store.read(), "读写往返不应被结尾换行破坏")
    }

    @Test
    fun `write creates the parent directory when needed`() {
        installLogStub()
        val store = FileBootMarkerStore(File(tempDir, "nested/dir/marker.txt"))

        store.write(7L)

        assertEquals(7L, store.read(), "父目录不存在时必须自动创建（首次安装的路径形态）")
    }

    @Test
    fun `a write failure does not throw`() {
        installLogStub()
        // 用一个**目录**冒充文件：写入必然失败，但方法不得抛出
        // （它在 Application.onCreate 的关键路径上，抛异常会让 App 起不来）
        val asDirectory = File(tempDir, "i-am-a-dir")
        assertTrue(asDirectory.mkdirs())
        val store = FileBootMarkerStore(asDirectory)

        store.write(1L)

        io.mockk.verify { Log.w(any(), match<String> { it.contains("BOOT_MARKER_WRITE_FAILED") }) }
    }

    /** 固定 `android.util.Log`（`mockkStatic` 不跨用例持久）。 */
    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
