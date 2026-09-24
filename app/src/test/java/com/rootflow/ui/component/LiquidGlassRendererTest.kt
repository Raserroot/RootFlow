package com.rootflow.ui.component

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.IntSize
import com.rootflow.domain.glass.GlassParams
import com.rootflow.domain.glass.GlassTier
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [LiquidGlassRenderer] 的**路由与缓存**单测（`STAGE7-PLAN.md §6.1/§6.2`）。
 *
 * ## 这个类能测什么、不能测什么（**诚实划界**）
 * | 覆盖 | 方式 |
 * |---|---|
 * | 档位路由（39+ / 只模糊 / 不套 effect） | ✅ 假工厂，纯 JVM |
 * | 缓存身份（同一实例、参数变化才重建） | ✅ 假工厂 |
 * | 参数清洗只做一次、且传出去的是合法值 | ✅ 假工厂 |
 * | **平台调用本身**（`RuntimeShader` / `createBlurEffect` / `createChainEffect`） | ❌ **只有真机能验** |
 *
 * 最后一行是本仓库反复要求的"不得假装有覆盖"：`RenderEffect` 是 final 平台类，
 * 其静态工厂在纯 JVM 下会抛 `Method … not mocked`（`AGENT_PROTOCOL.md §5.10`），
 * 因此 [AndroidGlassEffectFactory] 的**正确性 100% 由真机承担**。
 *
 * ## 为什么用 `mockk<RenderEffect>()` 而不是 MockK 的 relaxed 模式
 * 本类的测试替身只是**身份占位**（断言"是同一个对象"），不调用它的任何方法
 * —— 因此不会踩到"Android 类在 JVM 下没有实现"的问题。
 */
class LiquidGlassRendererTest {
    /** 记录型假工厂：只记参数、只回占位对象，**不碰任何平台 API**。 */
    private class FakeGlassEffectFactory(
        private val result: RenderEffect?,
    ) : GlassEffectFactory {
        val calls = mutableListOf<Call>()

        var releases: Int = 0
            private set

        override fun create(
            tier: GlassTier,
            size: IntSize,
            params: GlassParams,
        ): RenderEffect? {
            calls += Call(tier = tier, size = size, params = params)
            return result
        }

        override fun release() {
            releases++
        }

        data class Call(
            val tier: GlassTier,
            val size: IntSize,
            val params: GlassParams,
        )
    }

    private val effect: RenderEffect = mockk()

    private fun params(blurRadius: Float = 24f) = GlassParams.default(blurRadiusPx = blurRadius, cornerRadiusPx = 28f)

    private fun fakeFactory(result: RenderEffect? = effect): FakeGlassEffectFactory = FakeGlassEffectFactory(result)

    @Test
    @DisplayName("HAZE 档不套任何 effect，且**不去碰**工厂（Haze 才是这条路的毛玻璃）")
    fun `haze tier returns null and never calls the factory`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)

        assertNull(renderer.effect(GlassTier.HAZE, IntSize(1080, 168), params()))
        assertEquals(0, factory.calls.size, "HAZE 档必须完全不碰平台 API")
    }

    @Test
    @DisplayName("BLUR_ONLY 与 REFRACTION 都会造 effect，且档位被原样传达")
    fun `blur only and refraction both delegate to the factory`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)

        assertNotNull(renderer.effect(GlassTier.BLUR_ONLY, IntSize(1080, 168), params()))
        assertNotNull(renderer.effect(GlassTier.REFRACTION, IntSize(1080, 168), params(blurRadius = 30f)))

        assertEquals(listOf(GlassTier.BLUR_ONLY, GlassTier.REFRACTION), factory.calls.map { it.tier })
    }

    @Test
    @DisplayName("★ 缓存：尺寸与参数都不变 ⇒ 工厂只被调用一次，且回的是同一个实例")
    fun `same size and params hit the cache`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)
        val size = IntSize(1080, 168)

        val first = renderer.effect(GlassTier.REFRACTION, size, params())
        val second = renderer.effect(GlassTier.REFRACTION, size, params())

        assertEquals(1, factory.calls.size, "规格 §六：effect 只在尺寸或参数变化时重建")
        assertSame(first, second, "必须回同一个实例：换新对象会让 graphicsLayer 判定结果变化而重绘")
    }

    @Test
    @DisplayName("★ 缓存：尺寸变化 ⇒ 重建")
    fun `size change rebuilds the effect`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)

        renderer.effect(GlassTier.REFRACTION, IntSize(1080, 168), params())
        renderer.effect(GlassTier.REFRACTION, IntSize(1080, 200), params())

        assertEquals(2, factory.calls.size)
        assertEquals(listOf(IntSize(1080, 168), IntSize(1080, 200)), factory.calls.map { it.size })
    }

    @Test
    @DisplayName("★ 缓存：参数变化（滚动 ⇒ samplingStability 变）⇒ 重建")
    fun `param change rebuilds the effect`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)
        val size = IntSize(1080, 168)

        renderer.effect(GlassTier.REFRACTION, size, params())
        renderer.effect(
            GlassTier.REFRACTION,
            size,
            params().copy(samplingStability = GlassParams.SCROLL_SAMPLING_STABILITY),
        )

        assertEquals(2, factory.calls.size, "静止 0.001 → 滚动 1.0 必须换来一次重建")
        assertEquals(
            GlassParams.SCROLL_SAMPLING_STABILITY,
            factory.calls
                .last()
                .params.samplingStability,
        )
    }

    @Test
    @DisplayName("★ 缓存：档位变化 ⇒ 重建（同一尺寸同一参数也不行）")
    fun `tier change rebuilds the effect`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)
        val size = IntSize(1080, 168)

        renderer.effect(GlassTier.REFRACTION, size, params())
        renderer.effect(GlassTier.BLUR_ONLY, size, params())

        assertEquals(2, factory.calls.size)
    }

    @Test
    @DisplayName("★ 尺寸非正时直接返回 null 且不碰工厂（首帧拿不到尺寸是常态）")
    fun `non positive size short circuits`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)

        assertNull(renderer.effect(GlassTier.REFRACTION, IntSize(0, 168), params()))
        assertNull(renderer.effect(GlassTier.REFRACTION, IntSize(1080, 0), params()))

        assertEquals(0, factory.calls.size, "没有尺寸就没有 effect，不该白触发一次 shader 构造")
    }

    @Test
    @DisplayName("★ 参数清洗只在这里做一次：传出去的已经是合法值（含 NaN 与越界）")
    fun `params are sanitized before they leave the renderer`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)

        val dirty =
            params().copy(
                refractionHeight = 9999f,
                samplingStability = 0f,
                cornerRadii = floatArrayOf(Float.NaN, -1f),
            )
        renderer.effect(GlassTier.REFRACTION, IntSize(1080, 168), dirty)

        val received = factory.calls.single().params
        assertEquals(GlassParams.MAX_REFRACTION_HEIGHT, received.refractionHeight, "越界必须收敛")
        assertEquals(GlassParams.MIN_SAMPLING_STABILITY, received.samplingStability, "0 必须被抬起（规格 §一.6）")
        assertEquals(GlassParams.CORNER_RADII_COUNT, received.cornerRadii.size, "四角长度恒为 4")
        received.cornerRadii.forEach { assertEquals(0f, it) }
    }

    @Test
    @DisplayName("★ 工厂返回 null（effect 不可用）⇒ 渲染器如实回 null，不崩")
    fun `factory returning null degrades without crashing`() {
        val factory = fakeFactory(result = null)
        val renderer = LiquidGlassRenderer(factory)

        assertNull(renderer.effect(GlassTier.REFRACTION, IntSize(1080, 168), params()))
    }

    @Test
    @DisplayName("release：清掉缓存并通知工厂（下一次调用必须重建）")
    fun `release clears the cache and the factory`() {
        val factory = fakeFactory()
        val renderer = LiquidGlassRenderer(factory)
        val size = IntSize(1080, 168)

        renderer.effect(GlassTier.REFRACTION, size, params())
        renderer.release()
        renderer.effect(GlassTier.REFRACTION, size, params())

        assertEquals(2, factory.calls.size, "release 之后必须重建")
        assertEquals(1, factory.releases)
    }
}
