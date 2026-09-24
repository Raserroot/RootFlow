package com.rootflow.ui.component

/**
 * 悬浮底栏的**弹性参数**（阶段 6e 实现 → **同日回退，纯函数与单测保留**）。
 *
 * ## ★ 现状：**不参与生产**
 * 6e 实现过一版"切 Tab 时胶囊临时拉宽 + 上浮"的弹性（Haze Level 1），
 * 但**真机验证前**被用户裁定**回退**：
 * ① "液态感"不是需求项（需求 §6 只要求 Haze 毛玻璃 + 液态玻璃高光边框）；
 * ② 弹性会让底栏**尺寸在转场期变化**，而那是 6b 已确认过的模糊管线之外的新变量 ——
 *    为一个非需求项去动已确认的观感，收益不成立。
 *
 * `FloatingNavBar` 现在**没有任何动画**（尺寸与位置都是常量）。
 * 本对象因此是**已评估过、被否决的设计**：保留下来（连同单测）是因为
 * ① 用户明确要求保留这组 API；② 数值与边界条件已经算清楚，将来若真要做"液态玻璃"
 * 的动效，这是一份现成的起点；③ 删掉它意味着那次评估的结论无处可查。
 *
 * **不要在 `FloatingNavBar` 里挂回本对象** —— 要挂回先读上面两条否决理由。
 *
 * ## 为什么把数值抽成纯对象（即使现在不用）
 * 本仓库**没有 UI 测试**（决策 B），动画/几何数值若直接写在 Composable 里，
 * 它的取值与边界（clamp、常量关系）覆盖率就是 0。抽到这里之后，
 * "进度 0/0.5/1 各是多少""负数与 >1 的防御"都能在纯 JVM 下穷举
 * （`NavBarElasticTest` 仍在跑）。
 *
 * ## 为什么内阴影 / 外投影不在这里
 * 那要动**绘制栈**（`drawBehind` / `shadow` 与既有 `liquidGlassBorder` 的
 * `drawWithCache` 层序、以及 `hazeEffect` 的 `fallbackTint` 会相互影响）。
 * 6b 立下的结论是"`NAV_BAR_BLUR_RADIUS` / `NOISE_FACTOR` / `fallbackTint` 现在是对的、
 * 不得调整"，因此那部分从未在 6e 动过。
 */
object NavBarElastic {
    /**
     * 静止时占屏宽的比例（**弹性的基准值**；当前生产用的是下面的
     * [REST_HORIZONTAL_PADDING_DP] 固定留白，不是这个比例）。
     *
     * 0.85f 与旧实现（`padding(horizontal = 20.dp)`）**不是等价值** ——
     * 前者按屏宽比例算，后者是绝对 dp。回退后生产走的是绝对 dp（分辨率无关），
     * 本常量只作为"拉宽"这一步的起点保留。
     */
    const val REST_WIDTH_FRACTION: Float = 0.85f

    /**
     * 移动中**临时拉宽**的比例（需求口径：+15%）。
     *
     * ★ 它与 [REST_WIDTH_FRACTION] 必须满足 `REST + GAIN == 1f`
     * —— 由 `NavBarElasticTest` 断言，防止将来只改一个常量而让"拉宽"变成"没拉满"
     * 或"溢出屏幕"。
     */
    const val PRESS_WIDTH_GAIN: Float = 0.15f

    /** 移动中上浮的距离（dp）。 */
    const val LIFT_DP: Float = 4f

    /**
     * **回退后生产实际使用的**横向留白（dp）。
     *
     * 这是 `FloatingNavBar` 的常量尺寸来源，也是"回退 = 恢复原几何"的凭据：
     * 它与 6a 起的实现逐字相同（`padding(horizontal = 20.dp)`）。
     */
    const val REST_HORIZONTAL_PADDING_DP: Float = 20f

    /** 回退后生产实际使用的纵向留白（dp）。 */
    const val REST_VERTICAL_PADDING_DP: Float = 12f

    /** 回退后生产实际使用的上浮量（恒为 0 —— **没有动画**）。 */
    const val REST_LIFT_DP: Float = 0f

    /**
     * `pressProgress` → 占屏宽比例。
     *
     * @param progress 0 = 静止；1 = 完全按下/移动中。**入参会 clamp** ——
     *   弹簧动画在收敛前的采样值理论上落在 `0..1`，但"理论上"不是契约，
     *   而一个越界值会让 `fillMaxWidth(1.02f)` 直接抛 `IllegalArgumentException`
     *   （Compose 的 fraction 有 `require(fraction in 0f..1f)`）。
     */
    fun widthFraction(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return REST_WIDTH_FRACTION + PRESS_WIDTH_GAIN * clamped
    }

    /** `pressProgress` → 上浮距离（dp，入参同样 clamp）。 */
    fun liftDp(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return LIFT_DP * clamped
    }
}
