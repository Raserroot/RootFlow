package com.rootflow.ui.scripts

import com.rootflow.ui.scripts.ScriptSafetyScan.DangerSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 危险指令弹窗的**文案投影**单测（阶段 12c）。
 *
 * ## 为什么文案值得单测
 * 本仓库没有 UI 测试（决策 B）⇒ `ScriptEditorScreen` 里的分支覆盖率恒为 0。
 * 而这段文案的三处分支都不是装饰：标题分两档（高危 / 需留意）、
 * 正文要能指到行、命中过多时要折叠。任何一处错了，用户看到的就是
 * 一次含糊的误报说明 —— 而误报的代价是"用户学会无脑点继续保存"。
 */
class ScriptDangerProjectionsTest {
    @Test
    @DisplayName("标题分两档，且两档措辞不同（不得用同一个词糊过去）")
    fun `the two severities get different titles`() {
        assertEquals(ScriptDangerProjections.TITLE_HIGH, ScriptDangerProjections.title(DangerSeverity.HIGH))
        assertEquals(ScriptDangerProjections.TITLE_MEDIUM, ScriptDangerProjections.title(DangerSeverity.MEDIUM))
        assertFalse(
            ScriptDangerProjections.TITLE_HIGH == ScriptDangerProjections.TITLE_MEDIUM,
            "两档同词 ⇒ 分档这件事在用户侧不存在",
        )
    }

    @Test
    @DisplayName("正文带行号、原因与原文摘录（用户要能直接定位）")
    fun `the body points at the exact line`() {
        val prompt = promptOf("echo ok", "rm -rf /", "ls")

        val body = ScriptDangerProjections.body(prompt)

        assertTrue(body.contains("第 2 行"), "必须给出行号，实际=$body")
        assertTrue(body.contains(ScriptSafetyScan.scan("rm -rf /").single().reason), "必须给原因")
        assertTrue(body.contains("rm -rf /"), "必须给原文摘录（行号 + 原文才是精确指认）")
    }

    @Test
    @DisplayName("命中数超过上限 ⇒ 折叠成'另有 N 处'，未超过则不出现折叠行")
    fun `extra findings are folded into a count`() {
        val many = promptOf(*Array(5) { "rm -rf /" })
        val few = promptOf("rm -rf /")

        val manyBody = ScriptDangerProjections.body(many)
        val fewBody = ScriptDangerProjections.body(few)

        assertTrue(
            manyBody.contains("另有 ${5 - ScriptDangerProjections.MAX_LISTED} 处"),
            "超过上限必须如实说还有多少，实际=$manyBody",
        )
        assertFalse(fewBody.contains("另有"), "没超过上限时不得出现折叠行（那是在编一个不存在的数）")
    }

    @Test
    @DisplayName("两个按钮是**动词短语**，不是「确定 / 取消」")
    fun `the buttons name the action`() {
        assertFalse(ScriptDangerProjections.CONFIRM_LABEL == "确定")
        assertFalse(ScriptDangerProjections.DISMISS_LABEL == "取消")
        assertEquals("继续保存", ScriptDangerProjections.CONFIRM_LABEL)
        assertEquals("让我再想想", ScriptDangerProjections.DISMISS_LABEL)
    }

    @Test
    @DisplayName("★ 脚注必须说明'这只是文本匹配'（否则会被读成安全承诺）")
    fun `the footnote is honest about the limits`() {
        val footnote = ScriptDangerProjections.footnote()

        assertTrue(footnote.contains("文本匹配"), "必须写明扫描的性质，实际=$footnote")
        assertTrue(footnote.contains("不影响你保存"), "必须写明它不阻断保存，实际=$footnote")
    }

    /** 用若干行正文构造一个提示（走真实的扫描器，避免手搓 `DangerFinding` 掩盖规则失配）。 */
    private fun promptOf(vararg lines: String): ScriptDangerPrompt =
        ScriptDangerPrompt(findings = ScriptSafetyScan.scan(lines.joinToString("\n")))
}
