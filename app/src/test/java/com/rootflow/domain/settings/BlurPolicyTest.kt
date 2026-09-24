package com.rootflow.domain.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [BlurPolicy] 的单测（阶段 6a）。
 *
 * ## 为什么要为一个"返回布尔"的小函数写 9 个用例
 * 需求 §6 把它写成了两句话：「关闭模糊时用半透明纯色」+「低端机默认关闭」。
 * 这两句话合起来是**三种输入**（用户选择三态 × 设备两态 × API 两档）的判定，
 * 而它在真机上的失败形态是"看起来只是不模糊了"——**没有日志、没有崩溃**。
 * 因此把每一条边都钉死在这里，真机只用来确认"Haze 确实按这个输入渲染"。
 *
 * 纯 JVM，无 Android 依赖（`apiLevel` 是显式参数，见 `BlurPolicy` 的 KDoc）。
 */
class BlurPolicyTest {
    @Nested
    @DisplayName("defaultBlurEnabled：低端机默认关闭（需求 §6）")
    inner class Defaults {
        @Test
        fun `low ram device defaults to blur off`() {
            assertFalse(BlurPolicy.defaultBlurEnabled(isLowRamDevice = true))
        }

        @Test
        fun `capable device defaults to blur on`() {
            assertTrue(BlurPolicy.defaultBlurEnabled(isLowRamDevice = false))
        }
    }

    @Nested
    @DisplayName("effectiveBlur：用户三态 × 设备 × API 档位")
    inner class Effective {
        @Test
        fun `user never chose - follows device verdict`() {
            // OnePlus 8（本机真机）不是低端机 ⇒ 默认开
            assertTrue(BlurPolicy.effectiveBlur(userSetting = null, isLowRamDevice = false, apiLevel = 35))
            // 低端机 ⇒ 默认关
            assertFalse(BlurPolicy.effectiveBlur(userSetting = null, isLowRamDevice = true, apiLevel = 35))
        }

        @Test
        fun `user explicitly enabled - overrides low ram verdict`() {
            // 用户明确要求开：设备判定不得覆盖用户意志（否则"开关点了没用"）
            assertTrue(BlurPolicy.effectiveBlur(userSetting = true, isLowRamDevice = true, apiLevel = 35))
        }

        @Test
        fun `user explicitly disabled - stays off even on capable device`() {
            assertFalse(BlurPolicy.effectiveBlur(userSetting = false, isLowRamDevice = false, apiLevel = 35))
        }

        @Test
        fun `api below 31 is a hard gate even when user asked for blur`() {
            // RenderEffect 模糊需 API 31+：让 Haze "以为开着"只会白跑一遍模糊管线
            assertFalse(BlurPolicy.effectiveBlur(userSetting = true, isLowRamDevice = false, apiLevel = 30))
            assertFalse(BlurPolicy.effectiveBlur(userSetting = true, isLowRamDevice = false, apiLevel = 26))
        }

        @Test
        fun `api 31 is the first level where blur is allowed`() {
            assertFalse(BlurPolicy.effectiveBlur(userSetting = true, isLowRamDevice = false, apiLevel = 30))
            assertTrue(BlurPolicy.effectiveBlur(userSetting = true, isLowRamDevice = false, apiLevel = 31))
        }

        @Test
        fun `min api constant matches the gate used by effectiveBlur`() {
            // 常量与判定必须一致：改了其中一个而忘了另一个，会让"文档说 31、代码判 30"
            assertEquals(31, BlurPolicy.MIN_BLUR_API_LEVEL)
            assertFalse(
                BlurPolicy.effectiveBlur(
                    userSetting = true,
                    isLowRamDevice = false,
                    apiLevel = BlurPolicy.MIN_BLUR_API_LEVEL - 1,
                ),
            )
            assertTrue(
                BlurPolicy.effectiveBlur(
                    userSetting = true,
                    isLowRamDevice = false,
                    apiLevel = BlurPolicy.MIN_BLUR_API_LEVEL,
                ),
            )
        }
    }

    @Nested
    @DisplayName("ThemeMode：稳定键与回落")
    inner class ThemeModeKeys {
        @Test
        fun `every mode round-trips through its key`() {
            ThemeMode.entries.forEach { mode ->
                assertEquals(mode, ThemeMode.fromKey(mode.key), "mode $mode 的键未能往返")
            }
        }

        @Test
        fun `keys are unique`() {
            assertEquals(ThemeMode.entries.size, ThemeMode.ALL_KEYS.toSet().size)
        }

        @Test
        fun `unknown and null keys fall back to SYSTEM`() {
            // 旧版本写的键、或文件被改坏：必须回落而不是抛（设置损坏不能让人进不去设置页）
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(""))
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("night"))
        }
    }
}
