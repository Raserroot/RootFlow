package com.rootflow.ui.theme

import com.rootflow.domain.settings.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [resolveDarkTheme] / [resolveColorScheme] 的单测（阶段 6a）。
 *
 * ## 为什么这两条判定值得单独测
 * 它们是需求 §6 前两行（「深色/浅色/跟随系统」+「MD3 动态配色」）的**全部逻辑**。
 * 放进 `@Composable` 之后就只能靠真机肉眼验证"是不是深色"——而**肉眼判色极不可靠**
 * （尤其在浅色主题下用户以为自己在看深色）。
 *
 * `apiLevel` 与 `context` 都是显式参数，因此纯 JVM 下可穷举 26/30/31 三档；
 * 只有"动态取色真去读系统资源"那一条路径需要 `Context`（真机覆盖）。
 */
class ResolveColorSchemeTest {
    @Nested
    @DisplayName("resolveDarkTheme：三种模式映射到是否深色")
    inner class DarkTheme {
        @Test
        fun `SYSTEM follows the system setting`() {
            assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
            assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        }

        @Test
        fun `LIGHT forces light even when the system is dark`() {
            assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
            assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
        }

        @Test
        fun `DARK forces dark even when the system is light`() {
            assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
            assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
        }
    }

    @Nested
    @DisplayName("resolveColorScheme：品牌色 fallback 与动态取色的 API 门")
    inner class ColorSchemeResolution {
        @Test
        fun `api 26 through 30 use the brand palette`() {
            // 动态取色（dynamicLightColorScheme）需 API 31+：26–30 必须走品牌色
            listOf(26, 28, 30).forEach { api ->
                assertEquals(
                    RootFlowLightScheme,
                    resolveColorScheme(dark = false, dynamicColor = true, apiLevel = api, context = null),
                    "API $api 浅色应使用品牌调色板",
                )
                assertEquals(
                    RootFlowDarkScheme,
                    resolveColorScheme(dark = true, dynamicColor = true, apiLevel = api, context = null),
                    "API $api 深色应使用品牌调色板",
                )
            }
        }

        @Test
        fun `api 31 with dynamic color enabled but no context falls back to brand palette`() {
            // 这是"接不上系统资源"的防御分支：不得抛，必须回落
            assertEquals(
                RootFlowLightScheme,
                resolveColorScheme(dark = false, dynamicColor = true, apiLevel = 31, context = null),
            )
            assertEquals(
                RootFlowDarkScheme,
                resolveColorScheme(dark = true, dynamicColor = true, apiLevel = 31, context = null),
            )
        }

        @Test
        fun `dynamic color disabled uses brand palette on any api`() {
            listOf(26, 30, 31, 35).forEach { api ->
                assertEquals(
                    RootFlowLightScheme,
                    resolveColorScheme(dark = false, dynamicColor = false, apiLevel = api, context = null),
                    "关闭动态取色时 API $api 应使用品牌色",
                )
                assertEquals(
                    RootFlowDarkScheme,
                    resolveColorScheme(dark = true, dynamicColor = false, apiLevel = api, context = null),
                    "关闭动态取色时 API $api 应使用品牌深色",
                )
            }
        }

        @Test
        fun `brand light and dark palettes are actually different schemes`() {
            // 浅色与深色若意外相同（复制粘贴事故），上面所有断言都会"通过"但主题是坏的
            assertNotEquals(RootFlowLightScheme, RootFlowDarkScheme)
            assertNotEquals(RootFlowLightScheme.primary, RootFlowDarkScheme.primary)
            assertNotEquals(RootFlowLightScheme.surface, RootFlowDarkScheme.surface)
        }

        @Test
        fun `dark brand surface is darker than light brand surface`() {
            // 用感知亮度而不是逐通道比较：深色主题的"更暗"是设计意图，值得钉死
            assertTrue(
                RootFlowDarkScheme.surface.luminance() < RootFlowLightScheme.surface.luminance(),
                "深色主题的 surface 应比浅色更暗",
            )
        }
    }

    /** 感知亮度（`0.299R + 0.587G + 0.114B`）：人眼对绿最敏感，简单平均会误判深绿。 */
    private fun androidx.compose.ui.graphics.Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
}
