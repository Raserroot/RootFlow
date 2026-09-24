package com.rootflow.domain.settings

/**
 * 毛玻璃的**生效判定**（需求 §6：「性能降级：关闭模糊时用半透明纯色；低端机默认关闭」）。
 *
 * ## 为什么放在 domain 而不是 ui
 * 它是一条**判定**，不是一次绘制。放在 domain 后可被纯 JVM 单测穷举
 * （与 `SettingsTarget` 不返回 `Intent`、`ForegroundState` 是纯值对象同一取舍）：
 * UI 侧只剩"把结论交给 Haze"。
 *
 * ## 与 `RootFlowSettings.blurEnabled` 的三态关系
 * ```
 * 用户选择 null  -> 用设备判定（低端机 = 关）
 * 用户选择 true  -> 仍要过 API 档位门（RenderEffect 模糊需 API 31+）
 * 用户选择 false -> 关，且不再看设备判定
 * ```
 * 最后一道 `apiLevel >= 31` 是**硬门**：Haze 在 API 31 以下只能回落成纯色 tint，
 * 让它"以为开着"只会白跑一遍模糊管线（`AGENTS.md`：不得自欺）。
 */
object BlurPolicy {
    /**
     * 低端机 → 默认关闭模糊。
     *
     * @param isLowRamDevice 来自 `ActivityManager.isLowRamDevice`
     */
    fun defaultBlurEnabled(isLowRamDevice: Boolean): Boolean = !isLowRamDevice

    /**
     * 最终是否真的渲染模糊。
     *
     * @param userSetting 用户选择；`null` = 从未选择过
     * @param isLowRamDevice 设备是否被系统标记为低内存
     * @param apiLevel `Build.VERSION.SDK_INT`（**显式传入**，便于单测穷举 26/30/31 三档）
     */
    fun effectiveBlur(
        userSetting: Boolean?,
        isLowRamDevice: Boolean,
        apiLevel: Int,
    ): Boolean {
        val desired = userSetting ?: defaultBlurEnabled(isLowRamDevice)
        return desired && apiLevel >= MIN_BLUR_API_LEVEL
    }

    /** RenderEffect 模糊的最低 API（`Modifier.blur` / Haze 的 GPU 路径）。 */
    const val MIN_BLUR_API_LEVEL: Int = 31
}
