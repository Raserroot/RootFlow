package com.rootflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体体系（需求 §6，MD3）。
 *
 * ## ★★ 阶段 8.1：整体放大（用户反馈"字体偏小、要眯眼"）
 *
 * ### 为什么必须改成**显式字号**，而不是继续用 `Typography()` 默认值
 * MD3 的默认档位对本项目的版式整体偏小（正文 14sp、标签 11–12sp），
 * 这是"密集设置页"的默认，不是"卡片流 + 深色终端"的默认。
 * 更要紧的是：**默认值不可见**。用 `Typography()` 时"这个标题多大"这个问题
 * 在代码里没有任何答案，只能靠查 MD3 基线表 —— 于是"放大一点"这种需求
 * 每次都要重新推导一遍。改成显式常量后，字号与它的**依据**写在同一个地方。
 *
 * ### 量出来的旧值 → 新值（旧值 = MD3 基线，已逐项核对）
 * | 用途 | token | 旧 | 新 |
 * |---|---|---|---|
 * | 顶部大标题 | `headlineMedium` | 28 | **32** |
 * | 状态卡主标题 | `headlineSmall` | 24 | **26** |
 * | 状态卡副标题 | `bodyLarge` | 16 | **18** |
 * | 卡片标题 / 信息卡 label | `titleMedium` | 16 | **18** |
 * | 信息卡 value / 安全卡第三行 | `bodyMedium` | 14 | **16** |
 * | 终端每行 / 辅助说明 | `bodySmall` | 12 | **14** |
 * | 终端/终端摘要标签 | `labelMedium` | 12 | **13** |
 * | 底栏文字 / chip 标签 | `labelSmall` | 11 | **13** |
 *
 * ### 为什么 `bodyLarge` 与 `titleMedium` 都是 18
 * 两者在本项目的分工是"副标题"与"卡片内主标签"，视觉上本就同级
 * （都在卡片里、都不是标题）。给它们同一个字号是**有意的**：
 * 差 1sp 只会让对照看起来像没对齐。
 *
 * ### ⚠️ 影响面（如实登记）
 * 这四个 token（`bodyLarge` / `bodyMedium` / `bodySmall` / `labelSmall`）**是全应用共用**的，
 * 因此配置页 / 设置页 / 编辑器的文字**也会一起变大**。
 * 这是用户 2026-09-20 的明确要求（"不要只放大标题——信息行、终端、底栏全都放大"），
 * 但**那三个页面的版式未随本次改动复核**（本阶段的范围仍是主页 + 底栏）——
 * 若某处出现换行/裁切，改的是那一处，**不要**为了个别页面把全局字号调回去。
 *
 * ### 等宽字体在哪
 * 终端直接用 `FontFamily.Monospace`（见 [TerminalTextStyle]），**不在此处定义**：
 * 终端是唯一需要等宽的地方，把它做成全局 token 会诱导别处也去用。
 */
internal val RootFlowTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
        headlineSmall =
            TextStyle(
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontSize = 18.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontSize = 18.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.4.sp,
            ),
        labelMedium =
            TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            ),
    )

/**
 * 底栏 Tab 文字的字号（阶段 11e：**11sp**）。
 *
 * ## 为什么独立于 `labelSmall`（13sp）而不直接改它
 * 用户 2026-09-25 反馈「底栏字体调小一点」。`labelSmall` 是**全应用共用**的
 * （chip 标签、角标都用它），直接改它会波及别处 —— 与 [TerminalFontSize]
 * 独立于 `bodySmall` 是同一条纪律：**只调需要调的那一处**。
 *
 * ## 为什么是 11
 * 13sp 在 3 格底栏里已经接近「文字与图标抢视觉重心」；11sp 让图标重新成为主视觉。
 * 不再往下取（10sp）：中文笔画在这个尺寸下会开始糊，而底栏是最高频的落点，
 * 看不清楚比"偏大"糟得多。
 */
internal val NavBarLabelFontSize = 11.sp

/**
 * 底栏 Tab 文字的行高（阶段 11e：**14sp**）。
 *
 * ## 为什么必须显式给
 * `Text` 只覆盖 `fontSize` 时，行高仍从 `LocalTextStyle` 继承 —— 底栏这里是 `labelSmall`
 * 的 **18sp**。于是 11sp 的字被塞进 18sp 的行框，`Column` 的内容总高白白多出 4dp，
 * 标签又被推向胶囊下沿（与 [NavBarLabelFontSize] 一起构成用户的「字要往上调」）。
 *
 * 14sp ≈ 11sp × 1.27，是"装得下字形又不留空转"的比例。
 */
internal val NavBarLabelLineHeight = 14.sp

/**
 * 深色终端每一行的字号（阶段 8.1：12sp → **13sp**）。
 *
 * ## 为什么单独一个常量、不直接 `bodySmall`
 * 终端是**等宽 + 高密度**的行列表：13sp 已经是"能看清且一屏还能放下 15 行左右"的上限。
 * 把它与 `bodySmall`（14sp，用于辅助说明）分开，将来单独调终端时
 * 不会连带把说明文字也改掉。
 */
internal val TerminalFontSize = 13.sp

/** 终端行的行高（比字号松一点，长行折行时才不会糊在一起）。 */
internal val TerminalLineHeight = 19.sp

/**
 * 终端行的完整样式（等宽 + 固定字号）。
 *
 * ## 为什么 `fontFamily` 在这里给
 * 见 [RootFlowTypography] 的"等宽字体在哪"：终端是唯一需要等宽的地方。
 */
internal val TerminalTextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = TerminalFontSize,
        lineHeight = TerminalLineHeight,
    )
