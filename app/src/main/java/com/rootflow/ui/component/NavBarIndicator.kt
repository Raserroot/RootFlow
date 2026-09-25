package com.rootflow.ui.component

import kotlin.math.abs
import kotlin.math.floor

/**
 * 悬浮底栏的**液态指示器几何**（阶段 11；阶段 11b 按参考实现重做）。
 *
 * ## 它取代了什么
 * 阶段 8.0 起，选中态是**每个 Tab 自带一枚 44dp 圆**、靠 alpha 淡入淡出 —— 没有位移。
 * 阶段 11 把它上提为全栏唯一元素，但形状仍是**静态圆**、只有 18% 的轻微形变，
 * 观感与"液态"相去甚远（用户实测反馈：「还是不行」）。
 *
 * ## 本版（11b）对齐参考实现的三件事
 * 参照 [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass) 的
 * `LiquidBottomTabs.kt` + `DampedDragAnimation.kt`：
 *
 * | 特性 | 参考实现的做法 | 本版 |
 * |---|---|---|
 * | **按下膨胀** | `pressedScale = 78f/56f ≈ 1.39`，且 scaleX / scaleY 用**不同**的弹簧 | [PRESSED_SCALE] + 两个独立的 spring |
 * | **速度拉伸** | `scaleX /= 1 - clamp(v·0.75, ±0.2)`；`scaleY *= 1 - clamp(v·0.25, ±0.2)` | [stretchScaleX] / [stretchScaleY] |
 * | **形状** | 占满一个 Tab 格的 `Capsule()` | 宽度 = Tab 格宽 − 间距，高度 = 胶囊高 − 间距 |
 *
 * ## 为什么这些数值要抽成纯对象
 * 本仓库**没有 UI 测试**（决策 B）。底栏的几何与弹簧参数若写在 Composable 里，
 * 覆盖率就是 0。抽到这里之后，「按下放大多少」「多快的速度拉伸多少」「越界与退化输入」
 * 都能在纯 JVM 下穷举 —— 而**真机上唯一能判的是"好不好看"，不是"数值对不对"**。
 *
 * 与 [NavBarElastic] 的分工（**两者互不重叠**）：
 * | 对象 | 管什么 | 生产状态 |
 * |---|---|---|
 * | [NavBarElastic] | **整条胶囊**的宽/上浮（6e 的弹性） | 不参与生产（6e 裁定回退，见其 KDoc） |
 * | [NavBarIndicator]（本对象） | **指示器**的位移 / 膨胀 / 拉伸 | 阶段 11 起参与生产 |
 *
 * 后者**不**复活前者：形变限制在胶囊**内部**的指示器上，胶囊外框因此仍是常量 ——
 * 那既是玻璃层（`GlassLayer`）逐像素对齐的前提，也是 6e 否决"整条胶囊拉宽"的第②条理由
 * 在阶段 11 仍然成立的原因（`STAGE11-PLAN.md §2.6`）。
 */
object NavBarIndicator {
    /**
     * 按下时指示器的缩放倍数（**1.39**）。
     *
     * 来自参考实现的 `pressedScale = 78f / 56f` —— 即"按下时的直径 / 静止时的直径"。
     * 这个数不是随便取的：它大到**一眼能看出"这颗水滴被压大了"**，
     * 又小到不会把相邻 Tab 的图标盖住（超出的部分由胶囊自身裁剪）。
     *
     * 与之相对，本对象在阶段 11 的第一版取的是 18% 的轻微形变 ——
     * 那在真机上根本看不出"液态"，是本轮重做的直接原因。
     */
    const val PRESSED_SCALE: Float = 78f / 56f

    /**
     * 速度的缩放除数。
     *
     * 参考实现里 `velocity` 的单位是「每秒跨越多少个 Tab」（0..N 的量级），
     * 而拉伸公式里的系数要落在 `±0.2` 这个量级上才有意义，故除以 10。
     * 本对象的 [velocityTabsPerSecond] 输出同样的单位。
     */
    const val VELOCITY_DIVISOR: Float = 10f

    /** 横向拉伸系数：速度为负时横向**压缩**（除以小于 1 的数即放大，见 [stretchScaleX]）。 */
    const val STRETCH_FACTOR_X: Float = 0.75f

    /** 纵向压缩系数（比横向小得多，否则看起来像"整体变小"而不是"被拉长"）。 */
    const val STRETCH_FACTOR_Y: Float = 0.25f

    /**
     * 拉伸量的绝对值上限（±0.2）。
     *
     * 不设上限的话，一次甩动会让指示器被拉成一根面条 —— 那不是"液态"，是"坏了"。
     */
    const val STRETCH_CLAMP: Float = 0.2f

    /**
     * 指示器位置弹簧的阻尼比（**欠阻尼**：0.72 ⇒ 到位时有一次可见回弹）。
     *
     * 参考实现用的是 `spring(1f, 1000f, threshold)`（临界阻尼、很硬）；
     * 本版取欠阻尼，是因为底栏只有三格、位移距离短，临界阻尼下"滑过去"几乎是瞬移，
     * 看不出过程。一次回弹既能证明"这是物理"，又不耽误下一次点击。
     */
    const val SPRING_DAMPING_RATIO: Float = 0.72f

    /**
     * 指示器相对 Tab 格的**内缩**（四边各一份，dp）。
     *
     * ## 3dp 的来历：对着参考 App 实测出来的
     * 实测 `OPCameraPro 3.2.10` 的底栏（1080×2400、density 2.75 的模拟器）：
     *
     * | 量 | 像素 | 折算 |
     * |---|---|---|
     * | 胶囊高 | 170 px | ≈ 62 dp |
     * | 选中块高 | 160 px | ≈ 58 dp |
     * | 每格宽 | 232 px | ≈ 84 dp |
     * | 选中块宽 | 230 px | ≈ 84 dp |
     *
     * 也就是说：**选中块几乎填满整格**，上下各只留约 2dp、左右约 1dp。
     *
     * ## ★ 阶段 11b 从 10 改到 3（用户本轮实测反馈的直接产物）
     * 阶段 11 取 10dp 的理由是"让水滴有独立感"，但对着参考一看，
     * 那样得到的是一枚**比胶囊小一圈的块**，不是"填满格位的水滴"。
     * 缩到 3dp 之后，选中块才真正读作"液体充满了一格"。
     *
     * 不能取 0：零间隙会让相邻两格在切换途中**看起来连成一片**，
     * 那是"整条底栏在变色"，不是"一颗水滴滑过去"。
     */
    const val INSET_DP: Float = 3f

    /**
     * 第 [index] 项的中心 x（px）。
     *
     * 等宽布局下就是 `itemWidth * (index + 0.5)`。**不做 clamp**：
     * 它是"目标位置"，而合法范围只有调用方知道（它持有 Tab 总数）。
     * 强行 clamp 需要一个额外的 `count` 形参，而越界在这里意味着调用方算错了下标
     * —— 那种错误应当在单测里被抓住，而不是被一个静默的 clamp 抹平。
     */
    fun centerX(
        index: Int,
        itemWidth: Float,
    ): Float = itemWidth * (index + 0.5f)

    /**
     * 手指 x → 最近的项下标（拖拽松手时的吸附目标）。
     *
     * 用 `floor(x / itemWidth)` 而不是"遍历所有中心点取最近"：等宽布局下两者等价，
     * 而前者是 O(1) 且**不依赖 Tab 数量**。
     *
     * 边界（三条都有单测）：
     * - `count <= 0` 或 `itemWidth <= 0` ⇒ 回 0（退化输入不抛：首帧宽度可能是 0）
     * - `x` 为负（拖出左边界）⇒ clamp 到 0
     * - `x` 超过右边界 ⇒ clamp 到 `count - 1`
     * - `x` 为 `NaN` ⇒ 回 0（`NaN` 的比较恒为 false，会一路穿过 clamp）
     */
    fun nearestIndex(
        x: Float,
        itemWidth: Float,
        count: Int,
    ): Int {
        if (count <= 0 || itemWidth <= 0f || !x.isFinite()) return 0
        val raw = floor(x / itemWidth).toInt()
        return raw.coerceIn(0, count - 1)
    }

    /**
     * 像素速度 → 「每秒跨越多少个 Tab」。
     *
     * `itemWidth <= 0` 时回 0（首帧退化），而不是除零。
     *
     * ## 为什么用"Tab/秒"而不是"px/秒"
     * 拉伸是**相对**的：小屏上 600px/s 已经是从头甩到尾，大屏上只是轻轻一划。
     * 归一化到 Tab 格之后，同一套系数在两种屏幕上观感一致 —— 这正是参考实现的做法。
     */
    fun velocityTabsPerSecond(
        pixelsPerSecond: Float,
        itemWidth: Float,
    ): Float {
        if (itemWidth <= 0f || !pixelsPerSecond.isFinite()) return 0f
        return pixelsPerSecond / itemWidth
    }

    /**
     * 速度 → 横向缩放倍率。
     *
     * 公式来自参考实现：`scaleX /= 1 - clamp(v·0.75, ±0.2)`（`v = 速度 / 10`）。
     * 向右拖（正速度）时分母变小 ⇒ **横向放大**，看起来像被甩长；
     * 向左拖时同理反向。分母恒 > 0（`clamp` 的上下限保证了 1−0.15 = 0.85 以上）。
     */
    fun stretchScaleX(velocityTabs: Float): Float {
        val normalized = normalize(velocityTabs)
        return 1f / (1f - (normalized * STRETCH_FACTOR_X).coerceIn(-STRETCH_CLAMP, STRETCH_CLAMP))
    }

    /**
     * 速度 → 纵向缩放倍率。
     *
     * `scaleY *= 1 - clamp(v·0.25, ±0.2)`。**与横向反向**：拉长时变扁，
     * 这样形变读起来是"一坨液体被甩出去"，而不是"整体变大"。
     */
    fun stretchScaleY(velocityTabs: Float): Float {
        val normalized = normalize(velocityTabs)
        return 1f - (normalized * STRETCH_FACTOR_Y).coerceIn(-STRETCH_CLAMP, STRETCH_CLAMP)
    }

    /** 速度的归一化除数（见 [VELOCITY_DIVISOR]）与 `NaN` 防护。 */
    private fun normalize(velocityTabs: Float): Float =
        if (!velocityTabs.isFinite()) 0f else velocityTabs / VELOCITY_DIVISOR

    /** 速度大小（供调用方判断"是否在动"，用于决定要不要开色散）。 */
    fun speedOf(velocityTabs: Float): Float = if (!velocityTabs.isFinite()) 0f else abs(velocityTabs)
}

/**
 * Tab 角标的**显示规则**（阶段 11）。
 *
 * ## 为什么规则要独立于组件
 * 参考实现（`akbardzulfikar/liquid_glass_bottom_nav`）的 `badgeCount` 语义是
 * "数字本身的纯反射" —— 组件不猜"什么时候该清掉"。本项目沿用这条：
 * **能不能显示、显示成什么**由本对象决定，**什么时候把计数清零**由调用方（数据源）决定。
 *
 * ## 已知空能力（如实登记）
 * RootFlow 的三个 Tab **没有未读语义**，而现有数据源都不合适
 * （`RunActivityProvider` 是"最近一次运行"且单调不回退；`RunSessionRegistry.activeRuns()`
 * 没有 UI 端口）。因此 `RootFlowMain` 传的是空表，**角标不会出现**。
 * 详细推导与建议见 `STAGE11-PLAN.md §3.2`。
 */
object NavBarBadge {
    /**
     * 显示上限：超过它就写成 `"99+"`。
     *
     * 99 而不是 999：底栏角标直径约 16dp，两位数是这个尺寸下的可读上限；
     * 三位数会把角标撑得比图标还宽，挤掉旁边的 Tab。
     */
    const val MAX_DISPLAYED: Int = 99

    /**
     * 计数 → 显示文本；`null` = 不显示。
     *
     * | 输入 | 输出 |
     * |---|---|
     * | `null` / `0` / 负数 | `null`（不显示） |
     * | `1..99` | 十进制文本 |
     * | `>= 100` | `"99+"` |
     *
     * `Int.MAX_VALUE` 走的是同一分支 ⇒ 不可能溢出，也不会因为 `+1` 之类的运算回绕。
     */
    fun text(count: Int?): String? =
        when {
            count == null || count <= 0 -> null
            count > MAX_DISPLAYED -> "$MAX_DISPLAYED+"
            else -> count.toString()
        }
}
