package com.rootflow.domain.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [GlassPolicy.decide] 的穷举单测（`STAGE7-PLAN.md §6.1` 的 `GlassTierTest`）。
 *
 * ## 为什么这个类值钱
 * 三档降级的**判定**在真机上无法穷举：本机只有 API 35，31–32 与 ≤30 的设备不存在。
 * 把 `apiLevel` / `graphicsCapabilityOk` / `lowRam` 做成显式形参后，这里可以把
 * 26/30/31/32/33/34/35 × 能力 × 开关 × 低端机**全部钉死**
 * —— 与 `BlurPolicyTest` 对 `effectiveBlur` 的做法完全一致。
 *
 * ## 真机只能验 Tier 1
 * ⇒ 本类是 Tier 2 / Tier 3 **判定逻辑**的唯一证据。**它不是**"观感已验证"：
 * 观感 100% 由真机承担（见 `LiquidGlassRenderer` 的诚实声明与 `STAGE7-PLAN.md §7.1-bis`）。
 */
class GlassTierTest {
    private fun decide(
        apiLevel: Int = 35,
        capabilityOk: Boolean = true,
        enabled: Boolean = true,
        lowRam: Boolean = false,
        blurSupported: Boolean = true,
    ): GlassTier =
        GlassPolicy.decide(
            apiLevel = apiLevel,
            graphicsCapabilityOk = capabilityOk,
            liquidGlassEnabled = enabled,
            lowRam = lowRam,
            blurSupported = blurSupported,
        )

    @Test
    @DisplayName("API 33+ 且能力可用且开关开 ⇒ REFRACTION")
    fun `api 33 and above with capability yields refraction`() {
        assertEquals(GlassTier.REFRACTION, decide(apiLevel = 33))
        assertEquals(GlassTier.REFRACTION, decide(apiLevel = 34))
        assertEquals(GlassTier.REFRACTION, decide(apiLevel = 35))
    }

    @Test
    @DisplayName("★ 硬门 33：API 31/32 只能 BLUR_ONLY（33 是折射的唯一入口）")
    fun `api below 33 never yields refraction`() {
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = 32))
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = 31))
    }

    @Test
    @DisplayName("★ 硬门 31：API 30 与 26 落到 HAZE（没有 RenderEffect）")
    fun `api below 31 yields haze`() {
        assertEquals(GlassTier.HAZE, decide(apiLevel = 30))
        assertEquals(GlassTier.HAZE, decide(apiLevel = 26))
    }

    @Test
    @DisplayName("33+ 但图形能力查询不通过 ⇒ BLUR_ONLY（不崩，也不是纯 tint）")
    fun `capability failure degrades to blur only`() {
        assertEquals(GlassTier.BLUR_ONLY, decide(capabilityOk = false))
    }

    @Test
    @DisplayName("★ 用户关掉开关 ⇒ 恒 HAZE（优先于任何设备能力判定）")
    fun `user switch off forces haze on every api level`() {
        listOf(26, 30, 31, 32, 33, 35).forEach { apiLevel ->
            assertEquals(
                GlassTier.HAZE,
                decide(apiLevel = apiLevel, enabled = false),
                "API $apiLevel 上关掉开关必须回到 Haze",
            )
        }
        // 能力可用也救不回来：用户的显式意愿优先
        assertEquals(GlassTier.HAZE, decide(capabilityOk = true, enabled = false))
    }

    @Test
    @DisplayName("★ 低端机永远拿不到 REFRACTION（需求 §6「低端机默认关闭」）")
    fun `low ram device never reaches refraction`() {
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = 35, lowRam = true))
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = 35, lowRam = true, capabilityOk = true))
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = 31, lowRam = true))
        // 低端机 + 31 以下 ⇒ 连 blur 都没有
        assertEquals(GlassTier.HAZE, decide(apiLevel = 30, lowRam = true))
    }

    @Test
    @DisplayName("低端机 + 用户关了毛玻璃 ⇒ HAZE（不合成一层用户已明确关掉的模糊）")
    fun `low ram with blur disabled yields haze`() {
        assertEquals(GlassTier.HAZE, decide(apiLevel = 35, lowRam = true, blurSupported = false))
    }

    @Test
    @DisplayName("用户关掉毛玻璃（非低端机）⇒ HAZE，不得偷偷走 BLUR_ONLY")
    fun `blur disabled yields haze`() {
        assertEquals(GlassTier.HAZE, decide(apiLevel = 35, blurSupported = false))
    }

    @Test
    @DisplayName("★ 穷举 26–35 × 能力 × 开关 × 低端机：三条硬约束在任何组合下都成立")
    fun `exhaustive sweep honors every hard constraint`() {
        (26..35).forEach { apiLevel ->
            listOf(true, false).forEach { capability ->
                listOf(true, false).forEach { enabled ->
                    listOf(true, false).forEach { lowRam ->
                        listOf(true, false).forEach { blurSupported ->
                            val tier =
                                decide(
                                    apiLevel = apiLevel,
                                    capabilityOk = capability,
                                    enabled = enabled,
                                    lowRam = lowRam,
                                    blurSupported = blurSupported,
                                )
                            val where =
                                "api=$apiLevel capability=$capability enabled=$enabled " +
                                    "lowRam=$lowRam blur=$blurSupported"
                            if (!enabled) {
                                assertEquals(GlassTier.HAZE, tier, "开关关 ⇒ 必须 HAZE（$where）")
                            }
                            if (apiLevel < 31) {
                                assertEquals(GlassTier.HAZE, tier, "API < 31 ⇒ 必须 HAZE（$where）")
                            }
                            if (lowRam) {
                                assertNotEquals(GlassTier.REFRACTION, tier, "低端机不得进入折射（$where）")
                            }
                            if (!blurSupported) {
                                assertEquals(GlassTier.HAZE, tier, "毛玻璃关 ⇒ 不得在本机合成任何模糊（$where）")
                            }
                            if (tier == GlassTier.REFRACTION) {
                                assertTrue(apiLevel >= 33, "折射必须 API ≥ 33（$where）")
                                assertTrue(capability, "折射必须能力可用（$where）")
                                assertTrue(enabled, "折射必须开关开（$where）")
                                assertTrue(!lowRam, "折射必须非低端机（$where）")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("MIN_REFRACTION_API_LEVEL 与规格一致（RuntimeShader 从 API 33 起）")
    fun `refraction api floor matches the spec`() {
        assertEquals(33, GlassPolicy.MIN_REFRACTION_API_LEVEL)
        assertEquals(GlassTier.REFRACTION, decide(apiLevel = GlassPolicy.MIN_REFRACTION_API_LEVEL))
        assertEquals(GlassTier.BLUR_ONLY, decide(apiLevel = GlassPolicy.MIN_REFRACTION_API_LEVEL - 1))
    }
}
