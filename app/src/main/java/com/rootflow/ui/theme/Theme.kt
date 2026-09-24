package com.rootflow.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.rootflow.domain.settings.ThemeMode

/**
 * 主题模式 + 动态取色 → `ColorScheme`（**纯函数**）。
 *
 * ## ★ 为什么把 `apiLevel` 抽成参数（而不是函数内部读 `Build.VERSION.SDK_INT`）
 * 本项目的单测基线是**纯 JVM**（不引 Robolectric，见 `AGENT_PROTOCOL.md §5.8`）。
 * 若在这里读 `Build.VERSION.SDK_INT`，那么"API 30 该走品牌色、API 31 才该走动态取色"
 * 这条**唯一的分支判定**就只能靠真机肉眼验证 —— 与决策 9
 * （`SettingsTarget` 不返回 `Intent`）是同一个取舍。
 *
 * 参数化之后，`ResolveColorSchemeTest` 可以把 26/30/31 三档一次性穷举。
 * 生产调用点（[RootFlowTheme]）负责把 `Build.VERSION.SDK_INT` 传进来。
 *
 * ## `context` 为什么可空
 * 只有"动态取色且 API ≥ 31"这一条路径需要 `Context`（去读系统壁纸派生的资源）。
 * 另外三条路径（品牌浅色 / 品牌深色）都不需要它。
 * 让 `context` 可空后，单测可以在**不 mock Context** 的情况下覆盖那三条路径里的两条，
 * 从而把"动态取色的 API 门"这条判定本身也纳入单测。
 *
 * @param dark 是否使用深色（由 `ThemeMode` + `isSystemInDarkTheme()` 共同决定，见 [RootFlowTheme]）
 * @param dynamicColor 用户是否开启动态取色
 * @param apiLevel `Build.VERSION.SDK_INT`
 * @param context 仅 `dynamicColor && apiLevel >= 31` 时被解引用；其余情况可为 `null`
 */
internal fun resolveColorScheme(
    dark: Boolean,
    dynamicColor: Boolean,
    apiLevel: Int,
    context: Context?,
): ColorScheme {
    val dynamicContext = context.takeIf { dynamicColor && apiLevel >= Build.VERSION_CODES.S }
    return when {
        dynamicContext != null && dark -> dynamicDarkColorScheme(dynamicContext)
        dynamicContext != null -> dynamicLightColorScheme(dynamicContext)
        dark -> RootFlowDarkScheme
        else -> RootFlowLightScheme
    }
}

/**
 * 主题模式 + 系统深色设置 → 是否深色（**纯函数**）。
 *
 * 与 [resolveColorScheme] 分开，是因为两者的可测性来源不同：
 * 这里不需要任何 Android 类型，连 `apiLevel` 都不需要。
 */
internal fun resolveDarkTheme(
    mode: ThemeMode,
    systemInDarkTheme: Boolean,
): Boolean =
    when (mode) {
        ThemeMode.SYSTEM -> systemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/**
 * 应用主题（需求 §6：MD3 动态配色 + 深色/浅色/跟随系统）。
 *
 * ## 为什么参数是 `themeMode` 而不是 `RootFlowSettings`
 * `ui/theme` 不应该认识"设置的形状"——那是 `MainViewModel` 的事。
 * 只收它真正需要的两个字段，主题层就能被独立预览与替换。
 *
 * ## 为什么同时传 `lightColorScheme` 与 `darkColorScheme`
 * `MaterialTheme` 的两个参数是"给系统的两套配色"，而**用哪一套由本函数决定**
 * （`resolveDarkTheme`）。两处都传同一个解析结果，是为了让
 * `MaterialTheme` 内部任何"按系统深色自行切换"的行为都不可能发生——
 * 否则用户在设置里选"强制浅色"时，系统深色一开就会被悄悄翻回去。
 *
 * ## 状态栏图标
 * `enableEdgeToEdge()`（`MainActivity`）会按当前主题自动调整系统栏图标的明暗，
 * 因此本函数**不**去碰 `WindowInsetsController`：手动设置会在"跟随系统 + 系统切换深色"
 * 时与 Activity 的自动行为打架。
 */
@Composable
internal fun RootFlowTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(mode = themeMode, systemInDarkTheme = isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme =
        resolveColorScheme(
            dark = dark,
            dynamicColor = dynamicColor,
            apiLevel = Build.VERSION.SDK_INT,
            context = context,
        )

    MaterialTheme(
        colorScheme = scheme,
        typography = RootFlowTypography,
        shapes = RootFlowShapes,
        content = content,
    )
}
