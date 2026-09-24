package com.rootflow.domain.glass

/**
 * 液态玻璃的**参数集**（规格 `docs/liquid-glass-spec.md` §三 uniform 表 + §五 参数建议）。
 *
 * ## 为什么全部是 `Float` 且由 domain 持有
 * 规格给的是一组**经验区间**（`refractionHeight` 24–40、`refractionAmount` 8–20…）。
 * 把它们放在 domain 的纯数据类里，是为了让"越界值被收敛"这件事**可以脱离设备被断言**
 * —— 真机上参数越界的表现是"折射看起来怪"，而那是无法量化判读的。
 * 单位统一为**像素**（由调用方用 `LocalDensity` 把 dp 换算后传入），
 * 因为 AGSL 的 `size` uniform 本来就是像素。
 *
 * ## `dispersion` 默认 0 且**不暴露 UI**
 * 规格 §三.6 / §六：色散要对同一位置采样 **7 次**（采样数 ×7，规格自己说"很贵"）。
 * 已批准决策 **P4**：本阶段色散**默认 0，只留参数与开关**。
 * 判定函数里的分支仍然保留（`if (dispersion > 0.0)`），因此将来要开只需传值，
 * **不需要改 shader**。
 *
 * @property blurRadius 链式 `createBlurEffect` 的半径（px）。**与 Tier 2 取同一个值**，
 *   否则跨档切换时"糊的程度会跳一下"（`STAGE7-PLAN.md §3` 硬要求 3）
 * @property refractionHeight 边缘折射带宽度（px，规格建议 24–40）
 * @property refractionAmount 最大采样偏移（px，规格建议 8–20）
 * @property depthEffect 朝中心的额外偏移（规格建议 0.2–0.5，做出"中间凸起"的厚玻璃感）
 * @property samplingStability 五点平均权重：静止 0.001、滚动中 1.0（规格 §五）
 * @property dispersion 色散强度（本阶段恒 0；见类 KDoc）
 * @property highlightAlpha 边缘高光 alpha（规格经验值 `0.08 * intensity`）
 * @property cornerRadii 四角半径 px，顺序 **TL, TR, BR, BL**（规格 §三 的 `float4` 约定）
 */
data class GlassParams(
    val blurRadius: Float,
    val refractionHeight: Float,
    val refractionAmount: Float,
    val depthEffect: Float,
    val samplingStability: Float,
    val dispersion: Float,
    val highlightAlpha: Float,
    val cornerRadii: FloatArray,
) {
    /**
     * 收敛到规格的合法区间（**唯一入口**；调用方不要自己 clamp）。
     *
     * ## 为什么要 `sanitized()` 而不是在构造器里 require
     * 参数来自运行时（尺寸测量、用户设置、将来可能的调参 UI），越界**不该崩**
     * —— 底栏消失比"折射带略宽"严重得多。这与 `LogRetention.sanitize` 是同一纪律：
     * **非法输入在边界上收敛一次，内部只面对合法值**。
     *
     * ## 两条容易漏的边界
     * 1. `samplingStability = 0` 在规格 §一.6 被点名：shader 里若拿它当除数量或权重，
     *    直接设 0 会除零或边缘跳变 ⇒ 抬到 [MIN_SAMPLING_STABILITY]（0.001）。
     * 2. `cornerRadii` **长度必须恒为 4**：不足补 0、超出截断。
     *    长度不对会让 `setFloatUniform(name, float[])` 静默错位（四个角全乱），
     *    而那在真机上表现为"圆角看起来怪"，极难定位。
     */
    fun sanitized(): GlassParams =
        GlassParams(
            blurRadius = blurRadius.orZero().coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS),
            refractionHeight = refractionHeight.orZero().coerceIn(MIN_REFRACTION_HEIGHT, MAX_REFRACTION_HEIGHT),
            refractionAmount = refractionAmount.orZero().coerceIn(MIN_REFRACTION_AMOUNT, MAX_REFRACTION_AMOUNT),
            depthEffect = depthEffect.orZero().coerceIn(MIN_DEPTH_EFFECT, MAX_DEPTH_EFFECT),
            samplingStability = samplingStability.orZero().coerceIn(MIN_SAMPLING_STABILITY, MAX_SAMPLING_STABILITY),
            dispersion = dispersion.orZero().coerceIn(MIN_DISPERSION, MAX_DISPERSION),
            highlightAlpha = highlightAlpha.orZero().coerceIn(MIN_HIGHLIGHT_ALPHA, MAX_HIGHLIGHT_ALPHA),
            cornerRadii = cornerRadii.sanitizedRadii(),
        )

    /**
     * 数组字段的相等性必须按**内容**比较。
     *
     * `FloatArray` 默认是引用相等，而本类的实例会被当作 `RenderEffect` 缓存键的一部分
     * （见 `ui/component/LiquidGlassRenderer`）：引用相等会让"参数没变"被判成"变了"，
     * 于是每帧重建 effect —— 正是规格 §六 明令禁止的那件事。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassParams) return false
        return blurRadius == other.blurRadius &&
            refractionHeight == other.refractionHeight &&
            refractionAmount == other.refractionAmount &&
            depthEffect == other.depthEffect &&
            samplingStability == other.samplingStability &&
            dispersion == other.dispersion &&
            highlightAlpha == other.highlightAlpha &&
            cornerRadii.contentEquals(other.cornerRadii)
    }

    override fun hashCode(): Int {
        var result = blurRadius.hashCode()
        result = 31 * result + refractionHeight.hashCode()
        result = 31 * result + refractionAmount.hashCode()
        result = 31 * result + depthEffect.hashCode()
        result = 31 * result + samplingStability.hashCode()
        result = 31 * result + dispersion.hashCode()
        result = 31 * result + highlightAlpha.hashCode()
        result = 31 * result + cornerRadii.contentHashCode()
        return result
    }

    companion object {
        // ── 区间（规格 §五「参数建议」） ──────────────────────────────────────────

        /** 模糊半径下限：0 会让"链里那一趟 blur"变成空操作，仍按极小值处理。 */
        const val MIN_BLUR_RADIUS: Float = 0.1f

        /** 模糊半径上限（防御性；远超任何合理观感值）。 */
        const val MAX_BLUR_RADIUS: Float = 256f

        const val MIN_REFRACTION_HEIGHT: Float = 24f
        const val MAX_REFRACTION_HEIGHT: Float = 40f
        const val MIN_REFRACTION_AMOUNT: Float = 8f
        const val MAX_REFRACTION_AMOUNT: Float = 20f
        const val MIN_DEPTH_EFFECT: Float = 0.2f
        const val MAX_DEPTH_EFFECT: Float = 0.5f

        /** 规格 §一.6 点名的除零风险值（**不得用 0**）。 */
        const val MIN_SAMPLING_STABILITY: Float = 0.001f

        /** 滚动中的五点平均权重（规格 §五：滚动时 1–2）。 */
        const val SCROLL_SAMPLING_STABILITY: Float = 1.0f
        const val MAX_SAMPLING_STABILITY: Float = 2f

        const val MIN_DISPERSION: Float = 0f
        const val MAX_DISPERSION: Float = 0.3f

        const val MIN_HIGHLIGHT_ALPHA: Float = 0f
        const val MAX_HIGHLIGHT_ALPHA: Float = 0.5f

        /** 四角半径个数（`float4`：TL, TR, BR, BL）。 */
        const val CORNER_RADII_COUNT: Int = 4

        /**
         * 默认参数（**不含 blurRadius**：它必须由底栏的 `NAV_BAR_BLUR_RADIUS` 派生，
         * 以保证与 Tier 2 的模糊半径跨档连续，见 `STAGE7-PLAN.md §3` 硬要求 3）。
         *
         * @param blurRadiusPx 由调用方换算好的像素半径（= `NAV_BAR_BLUR_RADIUS`）
         * @param cornerRadiusPx 胶囊圆角（= `NavBarCornerRadius`），四角相同
         */
        fun default(
            blurRadiusPx: Float,
            cornerRadiusPx: Float,
        ): GlassParams =
            GlassParams(
                blurRadius = blurRadiusPx,
                refractionHeight = 28f,
                refractionAmount = 14f,
                depthEffect = 0.35f,
                samplingStability = MIN_SAMPLING_STABILITY,
                dispersion = MIN_DISPERSION,
                highlightAlpha = 0.08f,
                cornerRadii = FloatArray(CORNER_RADII_COUNT) { cornerRadiusPx },
            ).sanitized()

        /**
         * NaN / Infinity 归零。
         *
         * `coerceIn` 对 NaN **不生效**（NaN 的比较恒为 false，会原样穿过），
         * 因此必须先把 NaN 摘掉 —— 否则 NaN 会一路进 `setFloatUniform`，
         * 表现为"整块玻璃消失"，而那看起来像 shader 没跑，与真正的 shader 失败无法区分。
         */
        private fun Float.orZero(): Float = if (isFinite()) this else 0f

        /**
         * 长度补齐/截断到 [CORNER_RADII_COUNT]，并逐项归零 + 抬到非负。
         *
         * 补 0 而不是补某个半径值：缺失的角**没有**已知的正确值，
         * 补 0（直角）比补一个猜出来的圆角更容易在真机截图上一眼看出来。
         */
        private fun FloatArray.sanitizedRadii(): FloatArray =
            FloatArray(CORNER_RADII_COUNT) { index ->
                this
                    .getOrElse(index) { 0f }
                    .orZero()
                    .coerceAtLeast(0f)
            }
    }
}
