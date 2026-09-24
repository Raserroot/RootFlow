package com.rootflow.data.trigger

import com.rootflow.domain.model.TriggerParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TriggerParamsCodec] 单测（阶段 3a）。
 *
 * 本编解码是**手写**的（不引入 `kotlinx-serialization`，理由见类 KDoc），因此必须逐字段覆盖。
 * 重点不是"能解出正确值"，而是**任何畸形输入都不崩、且回退可见**。
 */
class TriggerParamsCodecTest {
    @Test
    fun `round trips every field`() {
        val params =
            TriggerParams(
                exact = true,
                intervalMinutes = 15,
                hourOfDay = 3,
                minuteOfHour = 45,
                payload = "wifi:home",
            )

        val json = TriggerParamsCodec.encode(params)

        assertEquals(params, TriggerParamsCodec.decode(json))
    }

    @Test
    fun `encodes only non-null optionals`() {
        val json = TriggerParamsCodec.encode(TriggerParams(exact = false, intervalMinutes = 5))

        assertEquals("""{"exact":false,"intervalMinutes":5}""", json)
    }

    @Test
    fun `round trips a payload containing quotes backslashes and newlines`() {
        val payload = "a\"b\\c\nd\te"
        val params = TriggerParams(payload = payload)

        val json = TriggerParamsCodec.encode(params)
        val decoded = TriggerParamsCodec.decode(json)

        assertEquals(payload, decoded.payload, "转义必须无损：$json")
    }

    @Test
    fun `empty object and blank input yield defaults without warnings`() {
        val warnings = mutableListOf<String>()

        assertEquals(TriggerParams(), TriggerParamsCodec.decode("", warnings::add))
        assertEquals(TriggerParams(), TriggerParamsCodec.decode("{}", warnings::add))
        assertTrue(warnings.isEmpty(), "空配置属正常情况，不应告警：$warnings")
    }

    @Test
    fun `malformed input falls back to defaults and reports a warning`() {
        val warnings = mutableListOf<String>()

        val decoded = TriggerParamsCodec.decode("not json at all", warnings::add)

        assertEquals(TriggerParams(), decoded, "损坏记录不得让调度器停摆")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith(TriggerParamsCodec.WARNING_PREFIX), warnings.single())
    }

    @Test
    fun `unbalanced quotes are treated as malformed`() {
        val warnings = mutableListOf<String>()

        val decoded = TriggerParamsCodec.decode("""{"payload":"unterminated}""", warnings::add)

        assertEquals(TriggerParams(), decoded)
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun `unknown keys are ignored but reported`() {
        val warnings = mutableListOf<String>()

        val decoded = TriggerParamsCodec.decode("""{"exact":true,"futureField":7}""", warnings::add)

        assertTrue(decoded.exact, "已知字段仍必须解析")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("futureField"), warnings.single())
    }

    @Test
    fun `wrong value types are ignored without crashing`() {
        val warnings = mutableListOf<String>()

        // intervalMinutes 给了字符串、exact 给了数字 —— 一律忽略（保留默认）
        val decoded = TriggerParamsCodec.decode("""{"intervalMinutes":"15","exact":1}""", warnings::add)

        assertEquals(null, decoded.intervalMinutes)
        assertEquals(false, decoded.exact)
    }

    @Test
    fun `values containing commas and colons do not split the token stream`() {
        // 手写解析最容易在这里错：字符串内的 , 与 : 不能当分隔符
        val params = TriggerParams(payload = "a,b:c", hourOfDay = 1)

        val decoded = TriggerParamsCodec.decode(TriggerParamsCodec.encode(params))

        assertEquals("a,b:c", decoded.payload)
        assertEquals(1, decoded.hourOfDay)
    }
}
