package com.rootflow.domain.settings

/**
 * 主题模式（需求 §6「深色/浅色/跟随系统，边缘到边缘」）。
 *
 * 这是**纯领域枚举**，不含任何 Android 常量（与 `PermissionGrant` 同款纪律）：
 * 「现在系统是不是深色」由 `ui/theme` 层的 `isSystemInDarkTheme()` 回答，
 * 本枚举只表达**用户的选择**。
 */
enum class ThemeMode {
    /** 跟随系统（默认）。 */
    SYSTEM,

    /** 强制浅色。 */
    LIGHT,

    /** 强制深色。 */
    DARK,
    ;

    companion object {
        /** 稳定字符串键。持久化与真机判读都用它，**不得**用 `ordinal`（改名即错位）。 */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM

        /** 全部合法键（schema 测试与设置页遍历用）。 */
        val ALL_KEYS: List<String> = entries.map { it.key }
    }

    /** 持久化用的稳定键。 */
    val key: String
        get() =
            when (this) {
                SYSTEM -> "system"
                LIGHT -> "light"
                DARK -> "dark"
            }
}

/**
 * 用户设置（阶段 6a 只落两项；6d 的设置页会在此追加日志保留、动效等）。
 *
 * ## 为什么 `blurEnabled` 是 `Boolean?` 而不是 `Boolean`
 * `null` 表达的是「**用户从未选择过**」，`false` 表达的是「用户明确关掉了」。
 * 折叠成 `false` 会让「低端机默认关闭」（需求 §6）永远无法区分于「用户自己关的」——
 * 那是两种不同的语义，UI 文案也不同（前者应显示"已按设备能力自动关闭"）。
 * 与 `PROJECT_STATE.md` 反复强调的"不得折叠成布尔"同一纪律（`PermissionGrant`、
 * `GroupLiveness`、`EventSourceState` 都因此是三态）。
 *
 * @property themeMode 主题模式（默认跟随系统）
 * @property blurEnabled 毛玻璃开关；`null` = 跟随设备判定（见 [BlurPolicy]）
 * @property dynamicColor 是否使用 MD3 动态取色（API 31+ 生效）
 * @property logRetentionDays 运行历史 / 日志的保留天数；**必须经 [LogRetention.sanitize] 收敛**
 *   （档位 3/7/14/30，见 [LogRetention] 的需求口径澄清）
 */
data class RootFlowSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val blurEnabled: Boolean? = null,
    val dynamicColor: Boolean = true,
    val logRetentionDays: Int = LogRetention.DEFAULT_DAYS,
    // ★ 阶段 11e：`liquidGlassEnabled` **已移除**（用户指令）。
    //   底栏只剩毛玻璃一种材质，该字段的消费者（`GlassPolicy.decide` 的硬门 1、
    //   设置页开关、`RootFlowMain` 的档位计算）全部一并移除。
    //   它的历史语义与"为什么当初必须能被关掉"（已批准决策 P5）保留在
    //   `GlassTier.kt` / `LiquidGlassRenderer.kt` 的文件头里。
)
