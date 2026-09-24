package com.rootflow.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌配色（需求 §6「MD3 动态配色」）。
 *
 * ## 两条路径，一套角色
 * | 场景 | 来源 |
 * |---|---|
 * | API 31+ 且用户开启动态取色 | 系统壁纸派生（`dynamicLightColorScheme` / `dynamicDarkColorScheme`） |
 * | 其余（API 26–30，或用户关闭了动态取色） | 本文件的品牌调色板 |
 *
 * 两条路径产出的都是 `ColorScheme`，UI 侧只认 `MaterialTheme.colorScheme` 的角色名，
 * **不写任何 `Build.VERSION` 分支**（分支只存在于 [resolveColorScheme] 一处）。
 *
 * ## 为什么是"深青 + 琥珀"而不是默认紫
 * 本应用的视觉主体是**深色终端**（主页日志）。品牌主色若与终端背景同色系，
 * 卡片与终端会糊成一片；选深青（冷）配琥珀（暖）既能在浅色下形成层级，
 * 又能在深色下与 `LogTerminalBackground` 明确分开。
 *
 * ## 取色可访问性
 * 每个主色与其 `on*` 容器色都按 MD3 的对比度要求成对给出（浅色 scheme 用 `40` 档，
 * 深色 scheme 用 `80` 档），不在此处做自动推导——自动推导出的组合在浅色下常常不达标。
 */
internal val RootFlowPrimary = Color(0xFF00696D)
internal val RootFlowOnPrimary = Color(0xFFFFFFFF)
internal val RootFlowPrimaryContainer = Color(0xFF6FF6FC)
internal val RootFlowOnPrimaryContainer = Color(0xFF002021)

internal val RootFlowSecondary = Color(0xFF4A6365)
internal val RootFlowOnSecondary = Color(0xFFFFFFFF)
internal val RootFlowSecondaryContainer = Color(0xFFCCE8E9)
internal val RootFlowOnSecondaryContainer = Color(0xFF051F21)

internal val RootFlowTertiary = Color(0xFF8C4A5F)
internal val RootFlowOnTertiary = Color(0xFFFFFFFF)
internal val RootFlowTertiaryContainer = Color(0xFFFFD9E1)
internal val RootFlowOnTertiaryContainer = Color(0xFF3A071C)

internal val RootFlowError = Color(0xFFBA1A1A)
internal val RootFlowOnError = Color(0xFFFFFFFF)
internal val RootFlowErrorContainer = Color(0xFFFFDAD6)
internal val RootFlowOnErrorContainer = Color(0xFF410002)

internal val RootFlowBackground = Color(0xFFFAFDFC)
internal val RootFlowOnBackground = Color(0xFF191C1C)
internal val RootFlowSurface = Color(0xFFFAFDFC)
internal val RootFlowOnSurface = Color(0xFF191C1C)
internal val RootFlowSurfaceVariant = Color(0xFFDAE4E4)
internal val RootFlowOnSurfaceVariant = Color(0xFF3F4949)
internal val RootFlowOutline = Color(0xFF6F7979)
internal val RootFlowOutlineVariant = Color(0xFFBEC8C8)

/** 浅色 scheme（API 26–30，或用户关闭动态取色）。 */
internal val RootFlowLightScheme =
    lightColorScheme(
        primary = RootFlowPrimary,
        onPrimary = RootFlowOnPrimary,
        primaryContainer = RootFlowPrimaryContainer,
        onPrimaryContainer = RootFlowOnPrimaryContainer,
        secondary = RootFlowSecondary,
        onSecondary = RootFlowOnSecondary,
        secondaryContainer = RootFlowSecondaryContainer,
        onSecondaryContainer = RootFlowOnSecondaryContainer,
        tertiary = RootFlowTertiary,
        onTertiary = RootFlowOnTertiary,
        tertiaryContainer = RootFlowTertiaryContainer,
        onTertiaryContainer = RootFlowOnTertiaryContainer,
        error = RootFlowError,
        onError = RootFlowOnError,
        errorContainer = RootFlowErrorContainer,
        onErrorContainer = RootFlowOnErrorContainer,
        background = RootFlowBackground,
        onBackground = RootFlowOnBackground,
        surface = RootFlowSurface,
        onSurface = RootFlowOnSurface,
        surfaceVariant = RootFlowSurfaceVariant,
        onSurfaceVariant = RootFlowOnSurfaceVariant,
        outline = RootFlowOutline,
        outlineVariant = RootFlowOutlineVariant,
    )

internal val RootFlowDarkPrimary = Color(0xFF4CD9E0)
internal val RootFlowDarkOnPrimary = Color(0xFF003739)
internal val RootFlowDarkPrimaryContainer = Color(0xFF004F52)
internal val RootFlowDarkOnPrimaryContainer = Color(0xFF6FF6FC)

internal val RootFlowDarkSecondary = Color(0xFFB0CCCD)
internal val RootFlowDarkOnSecondary = Color(0xFF1B3436)
internal val RootFlowDarkSecondaryContainer = Color(0xFF324B4D)
internal val RootFlowDarkOnSecondaryContainer = Color(0xFFCCE8E9)

internal val RootFlowDarkTertiary = Color(0xFFFFB1C6)
internal val RootFlowDarkOnTertiary = Color(0xFF532031)
internal val RootFlowDarkTertiaryContainer = Color(0xFF6F3547)
internal val RootFlowDarkOnTertiaryContainer = Color(0xFFFFD9E1)

internal val RootFlowDarkError = Color(0xFFFFB4AB)
internal val RootFlowDarkOnError = Color(0xFF690005)
internal val RootFlowDarkErrorContainer = Color(0xFF93000A)
internal val RootFlowDarkOnErrorContainer = Color(0xFFFFDAD6)

internal val RootFlowDarkBackground = Color(0xFF0E1515)
internal val RootFlowDarkOnBackground = Color(0xFFDDE4E4)
internal val RootFlowDarkSurface = Color(0xFF0E1515)
internal val RootFlowDarkOnSurface = Color(0xFFDDE4E4)
internal val RootFlowDarkSurfaceVariant = Color(0xFF3F4949)
internal val RootFlowDarkOnSurfaceVariant = Color(0xFFBEC8C8)
internal val RootFlowDarkOutline = Color(0xFF889392)
internal val RootFlowDarkOutlineVariant = Color(0xFF3F4949)

/** 深色 scheme（API 26–30，或用户关闭动态取色）。 */
internal val RootFlowDarkScheme =
    darkColorScheme(
        primary = RootFlowDarkPrimary,
        onPrimary = RootFlowDarkOnPrimary,
        primaryContainer = RootFlowDarkPrimaryContainer,
        onPrimaryContainer = RootFlowDarkOnPrimaryContainer,
        secondary = RootFlowDarkSecondary,
        onSecondary = RootFlowDarkOnSecondary,
        secondaryContainer = RootFlowDarkSecondaryContainer,
        onSecondaryContainer = RootFlowDarkOnSecondaryContainer,
        tertiary = RootFlowDarkTertiary,
        onTertiary = RootFlowDarkOnTertiary,
        tertiaryContainer = RootFlowDarkTertiaryContainer,
        onTertiaryContainer = RootFlowDarkOnTertiaryContainer,
        error = RootFlowDarkError,
        onError = RootFlowDarkOnError,
        errorContainer = RootFlowDarkErrorContainer,
        onErrorContainer = RootFlowDarkOnErrorContainer,
        background = RootFlowDarkBackground,
        onBackground = RootFlowDarkOnBackground,
        surface = RootFlowDarkSurface,
        onSurface = RootFlowDarkOnSurface,
        surfaceVariant = RootFlowDarkSurfaceVariant,
        onSurfaceVariant = RootFlowDarkOnSurfaceVariant,
        outline = RootFlowDarkOutline,
        outlineVariant = RootFlowDarkOutlineVariant,
    )
