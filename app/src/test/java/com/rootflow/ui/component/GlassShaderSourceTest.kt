package com.rootflow.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AGSL 源码的**静态断言**（`STAGE7-PLAN.md §6.1` 的 `GlassShaderSourceTest`）。
 *
 * ## 为什么这个类值钱（不是"为覆盖率而写"）
 * 规格 §一.2 明写：「名字不匹配会**直接报错或黑屏**」——而那是**只有真机才能发现的失败**。
 * 把「AGSL 声明的 uniform 集合」与「Kotlin 侧设置的集合」做成**三向一致断言**，
 * 就把一类真机黑屏问题变成了**不需要设备**的静态检查。
 * 这正是 `AGENT_PROTOCOL.md §8.0`（跨模块契约必须有跨界断言：AGSL 与 Kotlin 就是两个模块）
 * 与 §8.3（静态可判的禁忌要写成单测断言）的同一手法。
 *
 * ## 契约的三个来源
 * | 来源 | 位置 |
 * |---|---|
 * | ① AGSL 的 `uniform` 声明 | [LIQUID_GLASS_AGSL] |
 * | ② Kotlin 侧设值 | `LiquidGlassRenderer.setUniforms` |
 * | ③ 两者之间的桥 | [LIQUID_GLASS_AGSL] 头部的 `/// @uniform` 标记 |
 *
 * ③ 是唯一在**两个语言里都可见**的东西，因此它同时充当"人类可读的清单"与
 * "②的机器可读代理"（②本身写不出名字集合。Kotlin 没有"列出某方法调用的字符串参数"
 * 的静态手段；用正则去 parse 源码会让这条断言本身变成易碎品）。
 *
 * ## 诚实声明
 * 本类**不验**折射对不对、圆角正不正 —— AGSL 的正确性 **100% 由真机承担**
 * （沙箱无 GPU，`RuntimeShader` 也没有可用的纯 JVM 替身）。
 */
class GlassShaderSourceTest {
    /** 规格 §三 uniform 表 + `STAGE7-PLAN.md §5.5` 的完整清单（**冻结**）。 */
    private val specUniforms: Set<String> =
        setOf(
            "content",
            "size",
            "cornerRadii",
            "refractionHeight",
            "refractionAmount",
            "depthEffect",
            "samplingStability",
            "stabilizeCenter",
            "refractionDirection",
            "dispersion",
            "highlightAlpha",
        )

    /** ①：从 AGSL 里取 `uniform <type> <name>;` 的 name 集合。 */
    private fun declaredUniforms(source: String): Set<String> =
        Regex("""(?m)^\s*uniform\s+\w+\s+(\w+)\s*;""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

    /** ③：从 `/// @uniform <name> <type> <setter>` 取 name 集合。 */
    private fun markerUniforms(source: String): Set<String> =
        // ★ 必须带 MULTILINE：Kotlin 的原始字符串会**去掉首个换行**，
        //   因此第一行 `// @uniform content` 紧跟在 `"""` 之后 ⇒ 不开启多行模式时
        //   `^` 只匹配整个字符串的开头，会得到**空集合**（这条断言会以
        //   "expected 11, actual 0" 的形式失败，看起来像声明漂移，实为正则写错）。
        Regex("""(?m)^\s*//\s*@uniform\s+(\w+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    @DisplayName("★ AGSL 声明的 uniform 集合 == Kotlin 侧清单（防真机黑屏）")
    fun `declared uniforms match the kotlin side contract`() {
        val declared = declaredUniforms(LIQUID_GLASS_AGSL)

        assertEquals(
            markerUniforms(LIQUID_GLASS_AGSL),
            declared,
            "`/// @uniform` 标记与真正的 `uniform` 声明必须逐字一致",
        )
        assertEquals(specUniforms, declared, "规格 §三 的 uniform 清单不得增删（增了要同步 setUniforms）")
    }

    @Test
    @DisplayName("★ content 必须是 `uniform shader`（名字取自 createRuntimeShaderEffect 的第二参数）")
    fun `content is declared as a shader uniform`() {
        assertTrue(
            Regex("""uniform\s+shader\s+content\s*;""").containsMatchIn(LIQUID_GLASS_AGSL),
            "规格 §一.2：AGSL 里必须声明 `uniform shader content;`",
        )
    }

    @Test
    @DisplayName("★ lensMap 是 x^4 * (5 - 4x)，且**不得**写成 smootherstep（规格 §一.5）")
    fun `lens map uses the documented lens curve`() {
        // 曲线本体：pow(x, 4.0) * (5.0 - 4.0 * x)
        assertTrue(
            Regex("""pow\(\s*\w+\s*,\s*4\.0\s*\)\s*\*\s*\(\s*5\.0\s*-\s*4\.0\s*\*""")
                .containsMatchIn(LIQUID_GLASS_AGSL),
            "lensMap 必须是 x^4 * (5 - 4x)",
        )
        // 标准 smootherstep 的系数**一个都不该出现**（6/15/10 那组）
        assertFalse(
            LIQUID_GLASS_AGSL.contains("6.0 * "),
            "不得把 x^4(5-4x) 误写成标准 smootherstep 6x^5-15x^4+10x^3",
        )
        assertFalse(LIQUID_GLASS_AGSL.contains("smootherstep"), "文档里也不要用 smootherstep 这个名字")
    }

    @Test
    @DisplayName("色散有分支保护（默认 0 ⇒ 采样数不 ×7，规格 §六）")
    fun `dispersion is guarded by a branch`() {
        assertTrue(
            Regex("""if\s*\(\s*dispersion\s*>\s*0\.0\s*\)""").containsMatchIn(LIQUID_GLASS_AGSL),
            "色散必须被 `if (dispersion > 0.0)` 包住，否则默认路径也会 7 次采样",
        )
    }

    @Test
    @DisplayName("cornerRadii 是 float4（Kotlin 侧走 setFloatUniform(name, float[]) 重载）")
    fun `corner radii is a float4`() {
        assertTrue(
            Regex("""uniform\s+float4\s+cornerRadii\s*;""").containsMatchIn(LIQUID_GLASS_AGSL),
            "规格 §三：cornerRadii 的四个分量是 TL, TR, BR, BL",
        )
    }

    @Test
    @DisplayName("★ 源码不含 #include / 预处理指令（AGSL 没有预处理器，写了必然编译失败）")
    fun `source has no preprocessor directives`() {
        assertFalse(LIQUID_GLASS_AGSL.contains("#include"), "AGSL 无预处理器：不得有 #include")
        assertFalse(
            Regex("""(?m)^\s*#""").containsMatchIn(LIQUID_GLASS_AGSL),
            "AGSL 源码里不得出现任何以 # 开头的预处理指令",
        )
    }

    @Test
    @DisplayName("★ 折射只发生在边缘带内（增量权重，中心为 0）")
    fun `edge band weight is incremental so the center is untouched`() {
        // 探针是 clamp(-sdf / h, 0, 1)（玻璃中心也偏移）；完整版必须是 1 - clamp(...)
        assertTrue(
            Regex("""1\.0\s*-\s*clamp\(\s*-sdf""").containsMatchIn(LIQUID_GLASS_AGSL),
            "环带权重必须是 1 - clamp(-sdf / refractionHeight, 0, 1)，否则整块玻璃都会被推成糊的",
        )
    }

    @Test
    @DisplayName("玻璃外的像素原样输出（规格 §七 验收：外部内容不被模糊或折射）")
    fun `outside the glass the content passes through`() {
        assertTrue(
            Regex("""if\s*\(\s*sdf\s*>\s*0\.0\s*\)""").containsMatchIn(LIQUID_GLASS_AGSL),
            "必须有「SDF > 0 ⇒ 原样返回 content」的早退分支",
        )
    }

    @Test
    @DisplayName("★ 探针魔数已删除（品红只用于 Phase 1a 证明 shader 在跑）")
    fun `probe magenta magic number is gone`() {
        assertFalse(
            LIQUID_GLASS_AGSL.contains("1.0, 0.0, 1.0"),
            "品红魔数（mix(color, float3(1,0,1), 0.18)）必须已删除",
        )
        assertFalse(
            LIQUID_GLASS_AGSL.contains("magic", ignoreCase = true),
            "不得残留任何探针期的魔数痕迹",
        )
    }

    @Test
    @DisplayName("声明了 refractionDirection 但当前不参与运算（有意保留 uniform，见文件头偏离表）")
    fun `refraction direction is declared for future parameterization`() {
        assertTrue(declaredUniforms(LIQUID_GLASS_AGSL).contains("refractionDirection"))
        assertTrue(
            Regex("""uniform\s+float2\s+refractionDirection\s*;""").containsMatchIn(LIQUID_GLASS_AGSL),
            "归一化的 float2（规格 §五）",
        )
    }
}
