package com.rootflow.ui.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 路由参数解析的单测（阶段 6c，**纯函数**，`STAGE6C-PLAN.md §4`）。
 *
 * ## 为什么这条路径值得单独一个测试类
 * 它接收的是**不受本应用控制**的字符串：`NavBackStackEntry` 的参数可能来自
 * 系统恢复的 `Bundle`、深层链接、或旧版本写入的路由。这类输入的处理原则与
 * `TabDestinations.fromIndex` 完全一致 —— **响亮地降级，绝不崩**
 * （6a 已把"升级后一开 App 就崩"列为要避免的形态）。
 */
class ScriptEditorArgsTest {
    @Test
    @DisplayName("null / 空 / 全空白 ⇒ 新建（且不是无效参数）")
    fun `a missing argument means a new script`() {
        listOf(null, "", "   ").forEach { raw ->
            val key = ScriptEditorArgs.parse(raw)

            assertNull(key.scriptId, "「$raw」应回落新建")
            assertFalse(key.invalid, "参数缺席是「新建」这条路由的正常形态，不是无效输入：raw=$raw")
        }
    }

    @Test
    @DisplayName("固定段 new ⇒ 新建（不标记无效）")
    fun `the literal new key means a new script`() {
        val key = ScriptEditorArgs.parse(ScriptEditorArgs.NEW_KEY)

        assertNull(key.scriptId)
        assertFalse(key.invalid)
    }

    @Test
    @DisplayName("正整数字符串 ⇒ 编辑该 id（两侧空白容忍）")
    fun `a positive integer means editing that script`() {
        assertEquals(7L, ScriptEditorArgs.parse("7").scriptId)
        assertEquals(7L, ScriptEditorArgs.parse(" 7 ").scriptId)
        assertFalse(ScriptEditorArgs.parse("7").invalid)
        assertEquals(9_007_199_254_740_993L, ScriptEditorArgs.parse("9007199254740993").scriptId)
    }

    @Test
    @DisplayName("★ 非数字 / 负数 / 小数 / 零 ⇒ 回落新建且标记 invalid（不崩）")
    fun `unparseable arguments fall back to a new script`() {
        listOf("abc", "-1", "1.5", "0", "9999999999999999999999", "7a", "+").forEach { raw ->
            val key = ScriptEditorArgs.parse(raw)

            assertNull(key.scriptId, "「$raw」必须回落新建")
            assertTrue(key.invalid, "「$raw」必须被标记为无效参数（否则用户看不到任何提示）：$key")
        }
    }

    @Test
    @DisplayName("★ 「0」被判无效：0 是 domain 的「尚未入库」哨兵，不是可编辑的 id")
    fun `zero is rejected because it is the unsaved sentinel`() {
        val key = ScriptEditorArgs.parse("0")

        assertTrue(key.invalid, "把 0 当成可编辑 id 会让 load(0) 只得到 NotFound")
        assertNull(key.scriptId)
        // 若当成 id，用户看到的是「脚本元数据不存在」—— 一个由路由解析错误伪装成的数据丢失。
        assertTrue(
            ScriptLoadFailure.NOT_FOUND.message.contains("元数据不存在"),
            "这正是「0 被当成 id」时会出现的误导性文案，实际=${ScriptLoadFailure.NOT_FOUND.message}",
        )
    }

    @Test
    @DisplayName("参数名与编辑器 ViewModel 的键一致（两处拼错的表现是「永远按新建打开」）")
    fun `the route argument name matches the view model key`() {
        assertEquals(ScriptEditorViewModel.KEY_SCRIPT_ID, ScriptRoutes.ARG_ID)
        assertEquals("scripts/editor/{${ScriptEditorViewModel.KEY_SCRIPT_ID}}", ScriptRoutes.EDITOR_ARG)
    }

    @Test
    @DisplayName("editor(id) 拼路由：null ⇒ 无参数路由；非空 ⇒ 参数化路由")
    fun `the route builder distinguishes new from edit`() {
        assertEquals(ScriptRoutes.EDITOR_NEW, ScriptRoutes.editor(null))
        assertEquals("scripts/editor/7", ScriptRoutes.editor(7))
        // 往返自洽：拼出来的路由能解析回同一个 id
        assertEquals(7L, ScriptEditorArgs.parse("7").scriptId)
        assertEquals(ScriptRoutes.LIST, "scripts")
    }
}
