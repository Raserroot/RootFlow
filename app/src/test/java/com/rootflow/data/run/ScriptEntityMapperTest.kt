package com.rootflow.data.run

import com.rootflow.domain.model.Script
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ScriptEntityMapper] 单测（阶段 3d）。
 *
 * ## 为什么值得单测（只有 8 个字段的映射）
 * `content` 一旦丢失，脚本会**静默地什么都不做**——而"脚本不执行"与"脚本执行了但没输出"
 * 在真机日志里几乎无法区分。因此逐字段钉死，尤其正文与 `enabled`。
 */
class ScriptEntityMapperTest {
    @Test
    fun `every field is carried over verbatim`() {
        val source =
            Script(
                id = 42L,
                name = "nightly-backup",
                language = "shell",
                enabled = false,
                timeoutSec = 120,
                autoDisableOnFail = true,
                runOnSafeMode = true,
                content = "echo hello",
                contentSha256 = "sha256:deadbeef",
                createdAt = 111L,
                updatedAt = 222L,
            )

        val entity = ScriptEntityMapper.toRuntime(source)

        assertEquals(42L, entity.id)
        assertEquals("nightly-backup", entity.name)
        assertEquals("shell", entity.language)
        assertEquals(false, entity.enabled)
        assertEquals(120, entity.timeoutSec)
        assertEquals(true, entity.autoDisableOnFail)
        assertEquals(true, entity.runOnSafeMode)
        assertEquals("echo hello", entity.content)
    }

    @Test
    fun `the body is preserved byte for byte`() {
        // 正文里含引号、换行、shell 元字符与中文：映射**不得**做任何 trim / 转义 / 规范化
        // （转义是 runtime 的 `'\''` 职责，这里动一次会让 golden 断言与真机行为分叉）
        val body = "echo 'quoted'\nIFS=; echo \"\$HOME\"\n# 中文注释\n"
        val entity = ScriptEntityMapper.toRuntime(script(content = body))

        assertEquals(body, entity.content)
    }

    @Test
    fun `metadata that runtime does not need is not invented`() {
        // `createdAt` / `updatedAt` / `contentSha256` 是 domain 视角的字段，
        // runtime 入参不该凭空多出字段（1b 的契约是冻结的）
        val entity = ScriptEntityMapper.toRuntime(script(id = 7L))

        assertEquals(7L, entity.id)
        assertTrue(entity.content.isNotEmpty())
    }

    @Test
    fun `a language other than shell is still carried over`() {
        // v1 只实现 shell，但映射层不做语言判定（判定属 runtime 与 UI），避免"两处真相"
        val entity = ScriptEntityMapper.toRuntime(script().copy(language = "lua"))

        assertEquals("lua", entity.language)
    }

    @Test
    fun `the mapping is pure and repeatable`() {
        val source = script(name = "s", content = "echo x")

        val first = ScriptEntityMapper.toRuntime(source)
        val second = ScriptEntityMapper.toRuntime(source)

        assertEquals(first, second, "纯函数：同一输入必须给出同一结果（data class 逐字段相等）")
    }
}
