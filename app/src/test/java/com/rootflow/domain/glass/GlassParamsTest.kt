package com.rootflow.domain.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * [GlassParams.sanitized] 的单测（`STAGE7-PLAN.md §6.1` 的 `GlassParamsTest`）。
 *
 * ## 为什么参数收敛必须单测
 * 真机上参数越界的表现是"折射看起来怪"——那是**无法量化判读**的
 * （不像崩溃或帧时间有客观判据）。因此唯一能钉死它的地方就是这里。
 * 两条最值钱的断言：
 * 1. **`samplingStability = 0` 被抬到 0.001**（规格 §一.6 点名的除零风险）；
 * 2. **`cornerRadii` 长度恒为 4** —— 长度不对会让 `setFloatUniform(name, float[])`
 *    静默错位（四个角全乱），真机上极难定位。
 */
class GlassParamsTest {
    private fun params(
        blurRadius: Float = 24f,
        refractionHeight: Float = 28f,
        refractionAmount: Float = 14f,
        depthEffect: Float = 0.35f,
        samplingStability: Float = 0.001f,
        dispersion: Float = 0f,
        highlightAlpha: Float = 0.08f,
        cornerRadii: FloatArray = floatArrayOf(28f, 28f, 28f, 28f),
        fresnelPower: Float = 2.4f,
        fresnelStrength: Float = 0.28f,
        vibrancy: Float = 1.4f,
        lightDirection: FloatArray = floatArrayOf(-0.7071f, -0.7071f),
    ) = GlassParams(
        blurRadius = blurRadius,
        refractionHeight = refractionHeight,
        refractionAmount = refractionAmount,
        depthEffect = depthEffect,
        samplingStability = samplingStability,
        dispersion = dispersion,
        highlightAlpha = highlightAlpha,
        cornerRadii = cornerRadii,
        fresnelPower = fresnelPower,
        fresnelStrength = fresnelStrength,
        vibrancy = vibrancy,
        lightDirection = lightDirection,
    )

    @Test
    @DisplayName("合法值原样通过（sanitized 不是「什么都改」）")
    fun `valid values pass through unchanged`() {
        val sanitized = params().sanitized()

        assertEquals(24f, sanitized.blurRadius)
        assertEquals(28f, sanitized.refractionHeight)
        assertEquals(14f, sanitized.refractionAmount)
        assertEquals(0.35f, sanitized.depthEffect)
        assertEquals(0.08f, sanitized.highlightAlpha)
        assertEquals(0f, sanitized.dispersion)
        assertTrue(sanitized.cornerRadii.contentEquals(floatArrayOf(28f, 28f, 28f, 28f)))
    }

    @Test
    @DisplayName("★ samplingStability = 0 被抬到 0.001（规格 §一.6 的除零风险）")
    fun `zero sampling stability is lifted to the documented minimum`() {
        val sanitized = params(samplingStability = 0f).sanitized()

        assertEquals(
            GlassParams.MIN_SAMPLING_STABILITY,
            sanitized.samplingStability,
            "0 会让 shader 里的权重变成除零/边缘跳变（规格 §一.6）",
        )
        assertEquals(0.001f, sanitized.samplingStability)
    }

    @Test
    @DisplayName("滚动档 1.0 合法（静止 0.001 → 滚动 1.0 是本阶段唯一的动态参数）")
    fun `scroll sampling stability is allowed`() {
        assertEquals(
            1f,
            params(samplingStability = GlassParams.SCROLL_SAMPLING_STABILITY).sanitized().samplingStability,
        )
        // 超过上限仍被收敛
        assertEquals(2f, params(samplingStability = 99f).sanitized().samplingStability)
    }

    @Test
    @DisplayName("区间 clamp：上下界都被收进规格建议范围")
    fun `out of range values are clamped to the spec ranges`() {
        val tooSmall =
            params(
                refractionHeight = 1f,
                refractionAmount = 1f,
                depthEffect = 0f,
                highlightAlpha = -1f,
            ).sanitized()
        assertEquals(GlassParams.MIN_REFRACTION_HEIGHT, tooSmall.refractionHeight)
        assertEquals(GlassParams.MIN_REFRACTION_AMOUNT, tooSmall.refractionAmount)
        assertEquals(GlassParams.MIN_DEPTH_EFFECT, tooSmall.depthEffect)
        assertEquals(GlassParams.MIN_HIGHLIGHT_ALPHA, tooSmall.highlightAlpha)

        val tooLarge =
            params(
                refractionHeight = 999f,
                refractionAmount = 999f,
                depthEffect = 99f,
                dispersion = 99f,
                highlightAlpha = 99f,
                blurRadius = 99999f,
            ).sanitized()
        assertEquals(GlassParams.MAX_REFRACTION_HEIGHT, tooLarge.refractionHeight)
        assertEquals(GlassParams.MAX_REFRACTION_AMOUNT, tooLarge.refractionAmount)
        assertEquals(GlassParams.MAX_DEPTH_EFFECT, tooLarge.depthEffect)
        assertEquals(GlassParams.MAX_DISPERSION, tooLarge.dispersion)
        assertEquals(GlassParams.MAX_HIGHLIGHT_ALPHA, tooLarge.highlightAlpha)
        assertEquals(GlassParams.MAX_BLUR_RADIUS, tooLarge.blurRadius)
    }

    @Test
    @DisplayName("★ NaN / Infinity 归零（coerceIn 拦不住 NaN，会一路进 setFloatUniform）")
    fun `nan and infinity are neutralized`() {
        val sanitized =
            params(
                blurRadius = Float.NaN,
                refractionHeight = Float.POSITIVE_INFINITY,
                refractionAmount = Float.NEGATIVE_INFINITY,
                depthEffect = Float.NaN,
                samplingStability = Float.NaN,
                dispersion = Float.NaN,
                highlightAlpha = Float.NaN,
                cornerRadii = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 10f, 10f),
            ).sanitized()

        // NaN 归 0 后再被 coerceIn 抬到各自下界 —— 关键是**不再有非有限值**
        listOf(
            sanitized.blurRadius,
            sanitized.refractionHeight,
            sanitized.refractionAmount,
            sanitized.depthEffect,
            sanitized.samplingStability,
            sanitized.dispersion,
            sanitized.highlightAlpha,
            sanitized.cornerRadii[0],
            sanitized.cornerRadii[1],
        ).forEach { value ->
            assertTrue(value.isFinite(), "收敛后不得残留非有限值：$value")
        }
        assertEquals(GlassParams.MIN_SAMPLING_STABILITY, sanitized.samplingStability)
        assertEquals(0f, sanitized.cornerRadii[0])
        assertEquals(10f, sanitized.cornerRadii[2])
    }

    @Test
    @DisplayName("★ cornerRadii 长度恒定 4：不足补 0、超出截断、负值抬到 0")
    fun `corner radii are always normalized to four entries`() {
        assertEquals(
            GlassParams.CORNER_RADII_COUNT,
            params(cornerRadii = floatArrayOf()).sanitized().cornerRadii.size,
        )
        assertEquals(
            GlassParams.CORNER_RADII_COUNT,
            params(cornerRadii = floatArrayOf(1f, 2f)).sanitized().cornerRadii.size,
        )
        assertEquals(
            GlassParams.CORNER_RADII_COUNT,
            params(cornerRadii = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)).sanitized().cornerRadii.size,
        )

        val padded = params(cornerRadii = floatArrayOf(7f, 8f)).sanitized()
        assertTrue(padded.cornerRadii.contentEquals(floatArrayOf(7f, 8f, 0f, 0f)))

        val truncated = params(cornerRadii = floatArrayOf(1f, 2f, 3f, 4f, 5f)).sanitized()
        assertTrue(truncated.cornerRadii.contentEquals(floatArrayOf(1f, 2f, 3f, 4f)))

        val negatives = params(cornerRadii = floatArrayOf(-5f, -1f, 0f, 12f)).sanitized()
        assertTrue(negatives.cornerRadii.contentEquals(floatArrayOf(0f, 0f, 0f, 12f)))
    }

    @Test
    @DisplayName("★ Default 的 blurRadius 由调用方派生（跨档连续），不是写死的常量")
    fun `default takes the blur radius from the caller`() {
        val first = GlassParams.default(blurRadiusPx = 63f, cornerRadiusPx = 73.5f)
        val second = GlassParams.default(blurRadiusPx = 120f, cornerRadiusPx = 73.5f)

        assertEquals(63f, first.blurRadius)
        assertEquals(120f, second.blurRadius)
        assertNotEquals(
            first.blurRadius,
            second.blurRadius,
            "默认值必须跟随 NAV_BAR_BLUR_RADIUS，否则 Tier 1 与 Tier 2 的糊度会跳一下",
        )
        // 四角都等于胶囊圆角
        assertTrue(first.cornerRadii.all { it == 73.5f })
        // 静止档 = 规格 §一.6 的最小值
        assertEquals(GlassParams.MIN_SAMPLING_STABILITY, first.samplingStability)
        // 色散默认关（决策 P4）
        assertEquals(0f, first.dispersion)
    }

    @Test
    @DisplayName("Default 已经过收敛（不必再调一次 sanitized）")
    fun `default is already sanitized`() {
        // 传一个越界的圆角：Default 内部必须已经收敛过
        val huge = GlassParams.default(blurRadiusPx = 99999f, cornerRadiusPx = -3f)

        assertEquals(GlassParams.MAX_BLUR_RADIUS, huge.blurRadius)
        assertTrue(huge.cornerRadii.all { it == 0f })
        assertEquals(GlassParams.default(blurRadiusPx = 99999f, cornerRadiusPx = -3f), huge.sanitized())
    }

    @Test
    @DisplayName("★ 数组按内容比较：缓存键不能因引用不同而每帧判定为「变了」")
    fun `equality compares corner radii by content`() {
        val left = params(cornerRadii = floatArrayOf(1f, 2f, 3f, 4f))
        val right = params(cornerRadii = floatArrayOf(1f, 2f, 3f, 4f))
        val different = params(cornerRadii = floatArrayOf(1f, 2f, 3f, 5f))

        assertEquals(left, right, "内容相同必须相等（否则 RenderEffect 会被每帧重建）")
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, different)
    }

    // ── 阶段 11：Fresnel / vibrancy / 光源方向（`STAGE11-PLAN.md §1.5`）────────────
    // 这四个字段的**观感**只有真机能验，但"取值是否合法"必须在这里穷举 ——
    // 越界值在真机上的表现是"镜面过曝/看不见"，与"shader 没跑"无法区分。

    @Test
    @DisplayName("阶段 11 新字段：越界被收进各自区间")
    fun `stage 11 fields are clamped to their ranges`() {
        val tooSmall =
            params(fresnelPower = 0f, fresnelStrength = -1f, vibrancy = 0f).sanitized()
        assertEquals(GlassParams.MIN_FRESNEL_POWER, tooSmall.fresnelPower)
        assertEquals(GlassParams.MIN_FRESNEL_STRENGTH, tooSmall.fresnelStrength)
        assertEquals(GlassParams.MIN_VIBRANCY, tooSmall.vibrancy)

        val tooLarge =
            params(fresnelPower = 99f, fresnelStrength = 99f, vibrancy = 99f).sanitized()
        assertEquals(GlassParams.MAX_FRESNEL_POWER, tooLarge.fresnelPower)
        assertEquals(GlassParams.MAX_FRESNEL_STRENGTH, tooLarge.fresnelStrength)
        assertEquals(GlassParams.MAX_VIBRANCY, tooLarge.vibrancy)
    }

    @Test
    @DisplayName("★ vibrancy 下限是 1.0：本项只提饱和，不允许被调成「去饱和」")
    fun `vibrancy can never go below identity`() {
        assertEquals(GlassParams.MIN_VIBRANCY, params(vibrancy = 0.5f).sanitized().vibrancy)
        assertEquals(1f, params(vibrancy = 1f).sanitized().vibrancy, "1.0 必须原样保留（AGSL 里是恒等变换）")
    }

    @Test
    @DisplayName("★ 光源方向被归一化（长于 1 会让镜面过曝、短于 1 会让它看不见）")
    fun `light direction is normalized`() {
        val long = params(lightDirection = floatArrayOf(10f, 0f)).sanitized()
        assertEquals(1f, long.lightDirection[0], 1e-4f)
        assertEquals(0f, long.lightDirection[1], 1e-4f)

        val short = params(lightDirection = floatArrayOf(0f, 0.01f)).sanitized()
        assertEquals(0f, short.lightDirection[0], 1e-4f)
        assertEquals(1f, short.lightDirection[1], 1e-4f)

        val diagonal = params(lightDirection = floatArrayOf(3f, 4f)).sanitized()
        assertEquals(1f, hypot(diagonal.lightDirection[0], diagonal.lightDirection[1]), 1e-4f)
    }

    @Test
    @DisplayName("★ 光源方向：长度恒为 2；零向量与非有限值都回退到默认方向（不是留在原地）")
    fun `light direction degrades to the default`() {
        assertEquals(
            GlassParams.LIGHT_DIRECTION_COMPONENTS,
            params(lightDirection = floatArrayOf()).sanitized().lightDirection.size,
        )

        val zero = params(lightDirection = floatArrayOf(0f, 0f)).sanitized()
        assertTrue(
            hypot(zero.lightDirection[0], zero.lightDirection[1]) > 0.99f,
            "零向量必须回退成可用方向（长度约 1）",
        )

        val nonFinite = params(lightDirection = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)).sanitized()
        assertEquals(1f, hypot(nonFinite.lightDirection[0], nonFinite.lightDirection[1]), 1e-4f)

        // 回退方向 = 默认光源角（左上；屏幕坐标 y 向下 ⇒ 负 y 才是上方）
        assertTrue(
            nonFinite.lightDirection[0] < 0f && nonFinite.lightDirection[1] < 0f,
            "退化输入的兜底方向必须与 GlassParams.DEFAULT_LIGHT_ANGLE_DEGREES 一致",
        )
    }

    @Test
    @DisplayName("★ 光源方向按内容比较（它同样进 RenderEffect 的缓存键）")
    fun `equality compares light direction by content`() {
        val left = params(lightDirection = floatArrayOf(-0.7071f, -0.7071f))
        val right = params(lightDirection = floatArrayOf(-0.7071f, -0.7071f))
        val different = params(lightDirection = floatArrayOf(0.7071f, 0.7071f))

        assertEquals(left, right, "引用不同的同内容数组必须相等")
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, different, "换一个光源方向必须被判定为变化（否则镜面会停在旧朝向）")
    }

    @Test
    @DisplayName("Default 的四个阶段 11 字段都已填好且在区间内")
    fun `default fills the stage 11 fields`() {
        val d = GlassParams.default(blurRadiusPx = 63f, cornerRadiusPx = 28f)

        assertEquals(GlassParams.DEFAULT_FRESNEL_POWER, d.fresnelPower)
        assertEquals(GlassParams.DEFAULT_FRESNEL_STRENGTH, d.fresnelStrength)
        assertEquals(GlassParams.DEFAULT_VIBRANCY, d.vibrancy)
        assertTrue(
            hypot(d.lightDirection[0], d.lightDirection[1]) > 0.99f,
            "默认光向必须是单位向量",
        )
        assertTrue(d.lightDirection[0] < 0f && d.lightDirection[1] < 0f, "默认光来自左上")
    }
}
