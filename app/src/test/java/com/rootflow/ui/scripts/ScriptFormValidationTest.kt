package com.rootflow.ui.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 表单校验的单测（阶段 6c，**纯函数**）。
 *
 * ## 为什么错误文案被逐字钉死
 * 真机排障（`findstr`）与用户反馈截图都依赖这些字符串。改一个字就会让验证命令失效，
 * 而"验证命令失效"的表现是"什么都没搜到"，与"功能未执行"**无法区分**
 * —— 本仓库反复禁止的静默失败形态。
 */
class ScriptFormValidationTest {
    @Test
    @DisplayName("合法表单：无错误")
    fun `a valid form has no errors`() {
        val errors = ScriptFormValidation.validate(ScriptForm.New.copy(name = "备份", content = "echo hi"))

        assertTrue(errors.isEmpty(), "实际=$errors")
    }

    @Test
    @DisplayName("名称全空白（含全空格）⇒ 报错")
    fun `a whitespace only name is rejected`() {
        val errors = ScriptFormValidation.validate(ScriptForm.New.copy(name = "   ", content = "echo hi"))

        assertEquals(ScriptFormField.NAME, errors.single().field)
        assertEquals("名称不能为空", errors.single().message)
        assertTrue(errors.single().blocking, "名称错误必须阻断保存")
    }

    @Test
    @DisplayName("首尾空格不算空（trim 后非空即合法）")
    fun `a padded name is accepted`() {
        val errors = ScriptFormValidation.validate(ScriptForm.New.copy(name = "  备份  ", content = "echo hi"))

        assertTrue(errors.isEmpty(), "trim 后非空就合法，实际=$errors")
    }

    @Test
    @DisplayName("正文全空白 ⇒ 报错")
    fun `a whitespace only body is rejected`() {
        val errors = ScriptFormValidation.validate(ScriptForm.New.copy(name = "x", content = "\n  \n"))

        assertEquals(ScriptFormField.BODY, errors.single().field)
        assertEquals("脚本正文不能为空", errors.single().message)
    }

    @Test
    @DisplayName("timeoutSec = 0 合法（语义是「用全局默认 60s」）")
    fun `a zero timeout is valid`() {
        val errors =
            ScriptFormValidation.validate(
                ScriptForm.New.copy(name = "x", content = "echo hi", timeoutSec = "0"),
            )

        assertTrue(errors.isEmpty(), "0 是默认值、合法，实际=$errors")
    }

    @Test
    @DisplayName("timeoutSec 非数字 / 负数 ⇒ 报错")
    fun `a non numeric or negative timeout is rejected`() {
        listOf("abc", "", "-1", "1.5").forEach { raw ->
            val errors =
                ScriptFormValidation.validate(ScriptForm.New.copy(name = "x", content = "echo hi", timeoutSec = raw))

            assertTrue(
                errors.any { it.field == ScriptFormField.TIMEOUT },
                "「$raw」必须被判为非法超时，实际=$errors",
            )
        }
    }

    @Test
    @DisplayName("多条错误一次性全部返回（不是只报第一条）")
    fun `all errors are returned at once`() {
        val errors = ScriptFormValidation.validate(ScriptForm.New.copy(name = "", content = "", timeoutSec = "x"))

        assertEquals(
            setOf(ScriptFormField.NAME, ScriptFormField.BODY, ScriptFormField.TIMEOUT),
            errors.map { it.field }.toSet(),
            "只看第一条会让用户「改一个、再报一个」，来回三次才存下去",
        )
    }

    @Test
    @DisplayName("toScript：trim 名称、解析超时、保留 id 与开关")
    fun `toScript normalizes the persisted fields`() {
        val form =
            ScriptForm.New.copy(
                id = 9,
                name = "  名  ",
                content = "echo hi",
                timeoutSec = " 45 ",
                enabled = false,
                autoDisableOnFail = true,
                runOnSafeMode = true,
            )

        val script = ScriptFormValidation.toScript(form, createdAt = 111L, updatedAt = 222L, contentSha256 = "sha256:x")

        assertEquals(9L, script.id)
        assertEquals("名", script.name)
        assertEquals(45, script.timeoutSec)
        assertEquals(false, script.enabled)
        assertEquals(true, script.autoDisableOnFail)
        assertEquals(true, script.runOnSafeMode)
        assertEquals(111L, script.createdAt)
        assertEquals("echo hi", script.content)
    }

    @Test
    @DisplayName("错误文案稳定性（改文案必须同时改这条断言）")
    fun `the error messages are stable`() {
        val messages =
            ScriptFormValidation
                .validate(ScriptForm.New.copy(name = "", content = "", timeoutSec = "-1", language = "lua"))
                .map { it.message }

        assertEquals(
            listOf(
                "名称不能为空",
                "脚本正文不能为空",
                "超时必须是 ≥ 0 的整数（0 = 默认 60s）",
                "v1 只支持 shell 脚本",
            ),
            messages,
        )
    }
}

/**
 * 投影函数的单测（阶段 6c，**纯函数**）。
 *
 * 这是本项目在"无 UI 自动化测试"（已批准决策 B）下唯一能覆盖"这一行显示什么"的地方。
 */
class ScriptProjectionsTest {
    @Test
    @DisplayName("★ 空名回落 #<id>（绝不显示空白行）")
    fun `a blank name falls back to the id`() {
        assertEquals("#7", ScriptProjections.displayName(id = 7, name = ""))
        assertEquals("#7", ScriptProjections.displayName(id = 7, name = "   "))
        assertEquals("备份", ScriptProjections.displayName(id = 7, name = " 备份 "))
    }

    @Test
    @DisplayName("★ timeoutSec = 0 显示「默认 60s」，不显示裸 0（否则会被读成「不限」）")
    fun `a zero timeout is labelled as the default`() {
        assertEquals("默认 60s", ScriptProjections.timeoutLabel(0))
        assertEquals("30s", ScriptProjections.timeoutLabel(30))
        assertFalse(ScriptProjections.timeoutLabel(0).contains("不限"), "0 的语义是回落全局默认，不是不限")
    }

    @Test
    @DisplayName("★ 触发器计数三态：未知 / 0 / N 三者互不相同")
    fun `the trigger count has three distinct renderings`() {
        assertEquals("…", ScriptProjections.triggerSummary(null))
        assertEquals("未配置触发器", ScriptProjections.triggerSummary(0))
        assertEquals("3 个触发器", ScriptProjections.triggerSummary(3))

        assertEquals(
            3,
            setOf(
                ScriptProjections.triggerSummary(null),
                ScriptProjections.triggerSummary(0),
                ScriptProjections.triggerSummary(3),
            ).size,
            "未知、0、N 必须是三条不同的文案 —— 把未知显示成 0 就是假信息（§0.3 ①）",
        )
    }

    @Test
    @DisplayName("★ 删除提示：未知说未知，0 时不显示那句子，N 时带数字")
    fun `the delete notice never invents a zero`() {
        assertEquals("（触发器数未知）", ScriptProjections.deleteTriggerNotice(null))
        assertEquals("", ScriptProjections.deleteTriggerNotice(0))
        assertEquals("将一并删除 3 条触发器", ScriptProjections.deleteTriggerNotice(3))
    }

    @Test
    @DisplayName("删除确认正文带名称，N == 0 时不出现级联句")
    fun `the delete dialog body carries the name and the optional cascade`() {
        assertEquals("将删除「备份」。", ScriptProjections.deleteDialogBody("备份", 0))
        assertEquals("将删除「备份」。将一并删除 3 条触发器", ScriptProjections.deleteDialogBody("备份", 3))
        assertEquals("将删除「备份」。（触发器数未知）", ScriptProjections.deleteDialogBody("备份", null))
    }

    @Test
    @DisplayName("删除确认标题稳定")
    fun `the delete dialog title is stable`() {
        assertEquals("删除脚本？", ScriptProjections.deleteDialogTitle())
    }

    @Test
    @DisplayName("★ 删除部分成功文案：说「已删除」，不说「删除失败」")
    fun `the partial success message never says the deletion failed`() {
        val message = ScriptProjections.deletePartialSuccess("rm -rf failed (exit=1)")

        assertTrue(message.contains("脚本已删除"), "必须说清行已经删了：$message")
        assertFalse(message.contains("删除失败"), "说「删除失败」会让用户反复重删一行不存在的记录：$message")
        assertTrue(message.contains("rm -rf failed (exit=1)"), "成因必须原样透出（便于排障）：$message")
    }

    @Test
    @DisplayName("语言可编辑性：只有 shell")
    fun `only shell is editable`() {
        assertTrue(ScriptProjections.isLanguageEditable("shell"))
        assertTrue(ScriptProjections.isLanguageEditable("SHELL"))
        assertFalse(ScriptProjections.isLanguageEditable("lua"))
    }

    @Test
    @DisplayName("row 投影：计数未知时是 null 而不是 0")
    fun `the row projection keeps an unknown count unknown`() {
        val unknown = ScriptProjections.row(testScript(id = 3, name = "x"), count = null)
        val zero = ScriptProjections.row(testScript(id = 3, name = "x"), count = 0)

        assertEquals(null, unknown.triggerCount)
        assertEquals(ScriptProjections.COUNT_UNKNOWN, unknown.triggerSummary)
        assertEquals(0, zero.triggerCount)
        assertEquals("未配置触发器", zero.triggerSummary)
    }

    @Test
    @DisplayName("计数不可用的两条提示互不相同（首次失败与刷新失败是两件事）")
    fun `the two count failure hints differ`() {
        val first = ScriptProjections.countsUnavailable(hadSnapshot = false)
        val later = ScriptProjections.countsUnavailable(hadSnapshot = true)

        assertFalse(first == later, "「首次失败」与「刷新失败保留旧值」必须能区分")
        assertTrue(later.contains("上次结果"), "刷新失败时要说清显示的是旧值：$later")
    }
}
