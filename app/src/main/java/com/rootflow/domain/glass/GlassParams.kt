package com.rootflow.domain.glass

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
 * @property fresnelPower 边缘镜面的锐度指数（阶段 11）。越大 ⇒ 高光越集中在最外一圈
 * @property fresnelStrength 边缘镜面的强度（阶段 11）。与 [highlightAlpha] **叠加**，不取代它
 * @property vibrancy 背景提饱和倍率（阶段 11）。**1.0 = 恒等变换**（与"不开此特性"逐像素等价）
 * @property lightDirection 归一化的光源方向 `[x, y]`（阶段 11）。同时驱动 AGSL 的 Fresnel
 *   与边框渐变轴 —— 两处必须同源，否则"镜面亮在左上、边框亮在右下"（`STAGE11-PLAN.md §1.3`）
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
    val fresnelPower: Float,
    val fresnelStrength: Float,
    val vibrancy: Float,
    val lightDirection: FloatArray,
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
            fresnelPower = fresnelPower.orZero().coerceIn(MIN_FRESNEL_POWER, MAX_FRESNEL_POWER),
            fresnelStrength = fresnelStrength.orZero().coerceIn(MIN_FRESNEL_STRENGTH, MAX_FRESNEL_STRENGTH),
            vibrancy = vibrancy.orZero().coerceIn(MIN_VIBRANCY, MAX_VIBRANCY),
            lightDirection = lightDirection.sanitizedDirection(),
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
            cornerRadii.contentEquals(other.cornerRadii) &&
            fresnelPower == other.fresnelPower &&
            fresnelStrength == other.fresnelStrength &&
            vibrancy == other.vibrancy &&
            lightDirection.contentEquals(other.lightDirection)
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
        result = 31 * result + fresnelPower.hashCode()
        result = 31 * result + fresnelStrength.hashCode()
        result = 31 * result + vibrancy.hashCode()
        result = 31 * result + lightDirection.contentHashCode()
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

        // ── 阶段 11：光学增强（Fresnel 镜面 / 背景提饱和 / 光源方向） ──────────────

        /** 镜面锐度：1 = 线性（整条边缘带均匀），越大越集中在最外一圈。 */
        const val MIN_FRESNEL_POWER: Float = 1f
        const val MAX_FRESNEL_POWER: Float = 6f

        /** 镜面强度：**0 = 关闭该项**（回到只有 `highlightAlpha` 的 `STAGE7` 观感）。 */
        const val MIN_FRESNEL_STRENGTH: Float = 0f
        const val MAX_FRESNEL_STRENGTH: Float = 0.5f

        /**
         * 背景提饱和倍率：**下限是 1.0，不是 0**。
         *
         * 语义是"把透过玻璃的颜色调浓"，而 1.0 就是恒等变换
         * （AGSL 里 `mix(luma, color, vibrancy)` 在 1.0 处恰好还原原色）。
         * 允许 < 1 会让这个参数变成"去饱和"，而那不是本特性要干的事 ——
         * 一个能顺手把玻璃调成灰的旋钮，迟早会被误用成"关掉颜色"。
         */
        const val MIN_VIBRANCY: Float = 1f
        const val MAX_VIBRANCY: Float = 2f

        /** 光源方向的向量分量个数（`float2`）。 */
        const val LIGHT_DIRECTION_COMPONENTS: Int = 2

        /** 方向向量的最小可用长度：短于它按"零向量"处理（见 [sanitizedDirection]）。 */
        const val MIN_DIRECTION_LENGTH: Float = 1e-4f

        // ── 阶段 11 的默认值（观感取向，全部有单测钉住） ────────────────────────

        /** 默认镜面锐度：高光落在最外约 1/3 的环带里。 */
        const val DEFAULT_FRESNEL_POWER: Float = 2.4f

        /**
         * 默认镜面强度。
         *
         * 0.28 与既有 `highlightAlpha = 0.08` 叠加后，迎光侧的边缘亮度约为背光侧的 3 倍 ——
         * 这个比例是"看得出方向、但不像贴了一条白边"的经验分界。
         */
        const val DEFAULT_FRESNEL_STRENGTH: Float = 0.28f

        /**
         * 默认提饱和倍率（1.4 = 比原色浓四成）。
         *
         * 本项是阶段 11 的**点名的升级项**（不是可选开关），因此默认开。
         * 风险等级最低：它只改颜色浓淡，不会崩、不会黑屏、不吃额外采样。
         */
        const val DEFAULT_VIBRANCY: Float = 1.4f

        /**
         * 默认光源角（度）：**左上方**。
         *
         * ## ★ 单一真相源
         * `ui/theme/NavBarLight.DEFAULT_ANGLE_DEGREES` **引用本常量**（UI 依赖 domain 是合法方向）。
         * 两处各写一个 `-135f` 会漂移，而漂移的表现是"镜面亮在左上、边框却亮在右下"
         * —— 只有真机截图能发现（`STAGE11-PLAN.md §1.3`）。
         *
         * ## 坐标约定
         * 屏幕坐标 **y 轴向下** ⇒ 左上是 `(-0.707, -0.707)`。
         * 若按数学课本的习惯写成 +135°，光会跑到**右下角**。
         */
        const val DEFAULT_LIGHT_ANGLE_DEGREES: Float = -135f

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
                fresnelPower = DEFAULT_FRESNEL_POWER,
                fresnelStrength = DEFAULT_FRESNEL_STRENGTH,
                vibrancy = DEFAULT_VIBRANCY,
                lightDirection = defaultLightDirection(),
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

        /**
         * 光源方向的清洗（阶段 11）：补齐到 2 分量 → 逐项归零 → **归一化**。
         *
         * ## 为什么必须归一化，而不能像 [sanitizedRadii] 那样只处理长度与符号
         * AGSL 里用的是 `max(dot(n, lightDirection), 0.0)`：
         * - 向量长于 1 ⇒ 点积超过 1 ⇒ 镜面项**过曝**（边缘糊成一条白线，玻璃感反而消失）
         * - 向量短于 1 ⇒ 高光弱到看不见，表现为"Fresnel 没生效"
         *   —— 而那与"shader 根本没跑"在截图上一模一样，是最难定位的一类失败
         *
         * ## 零向量的处置：**回退到默认方向**，不是留在原地
         * 零向量在 AGSL 里让 `dot` 恒为 0 ⇒ 镜面只剩"不依赖方向的那一半"，
         * 看起来像"光突然没了"。回退到默认方向让退化输入仍有合理观感 ——
         * 与 [MIN_SAMPLING_STABILITY] 把 0 抬到 0.001 是同一条纪律
         * （**非法输入在边界上收敛一次，内部只面对合法值**）。
         */
        private fun FloatArray.sanitizedDirection(): FloatArray {
            val x = getOrElse(0) { 0f }.orZero()
            val y = getOrElse(1) { 0f }.orZero()
            val length = sqrt(x * x + y * y)
            if (length < MIN_DIRECTION_LENGTH) return defaultLightDirection()
            return floatArrayOf(x / length, y / length)
        }

        /** 默认光源方向的单位向量（由 [DEFAULT_LIGHT_ANGLE_DEGREES] 换算）。 */
        private fun defaultLightDirection(): FloatArray {
            val radians = DEFAULT_LIGHT_ANGLE_DEGREES * PI.toFloat() / 180f
            return floatArrayOf(cos(radians), sin(radians))
        }
    }
}
