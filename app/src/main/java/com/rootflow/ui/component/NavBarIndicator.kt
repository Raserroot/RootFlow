package com.rootflow.ui.component

import kotlin.math.abs
import kotlin.math.floor

/**
 * 悬浮底栏的**指示器几何**（阶段 11）。
 *
 * ## 它取代了什么
 * 阶段 8.0 起，选中态是**每个 Tab 自带一枚 44dp 圆**、靠 alpha 淡入淡出
 * （`FloatingNavBar.NavBarItem` 的 `badgeColor`）。那种做法**没有位移**：
 * 换 Tab 时是两个圆在原地各自亮灭。
 *
 * 本阶段把指示器上提为**全栏唯一**的可移动元素，选中的表意变成"它滑过去了"。
 * 这正是参考实现（`akbardzulfikar/liquid_glass_bottom_nav`）的
 * "physics-based sliding bubble indicator"。
 *
 * ## 为什么这些数值要抽成纯对象
 * 本仓库**没有 UI 测试**（决策 B），而底栏的几何与弹簧参数若写在 Composable 里，
 * 覆盖率就是 0。抽到这里之后，"第 i 项的中心在哪""拖到 x 该落到哪一项"
 * "速度多快算满量程""越界与退化输入"都能在纯 JVM 下穷举。
 *
 * 与 [NavBarElastic] 的分工（**两者互不重叠**）：
 * | 对象 | 管什么 | 生产状态 |
 * |---|---|---|
 * | [NavBarElastic] | **整条胶囊**的宽/上浮（6e 的弹性） | 不参与生产（6e 裁定回退，见其 KDoc） |
 * | [NavBarIndicator]（本对象） | **指示器**的位移/形变 | 阶段 11 起参与生产 |
 *
 * 后者**不**复活前者：把形变限制在胶囊**内部**的指示器上，胶囊外框因此仍是常量 ——
 * 而那是玻璃层（`GlassLayer`）逐像素对齐的前提，也是 6e 否决"整条胶囊拉宽"的第②条理由
 * 在阶段 11 仍然成立的原因（`STAGE11-PLAN.md §2.6`）。
 */
object NavBarIndicator {
    /**
     * 满量程速度下的最大拉伸比例（宽度 +18%、高度 -10.8%）。
     *
     * 18% 是"看得见但不夸张"的量级：再大就不像一块玻璃上的高光泡，
     * 而像被拉长的橡皮糖；再小则在拖动中根本注意不到。
     * 高度按 [SQUASH_RATIO] 反向补偿，让形变读起来是"被甩扁"而不是"整体变大"。
     */
    const val MAX_STRETCH: Float = 0.18f

    /**
     * 速度满量程（px/s）。
     *
     * 取"每秒跨越一个 Tab 宽度"的量级（约 600px/s，对应 3 个 Tab、屏宽 ~1080 的常见形态）。
     * 定这个数的意义是**与设备无关**：不取它就得按屏宽比例算，
     * 而那会让"小屏甩一下就满、大屏怎么拖都不到满"。
     */
    const val STRETCH_VELOCITY_REFERENCE: Float = 600f

    /**
     * 高度压缩相对宽度拉伸的比例（0.6 ⇒ 拉伸 18% 时压缩 10.8%）。
     *
     * 不取 1.0：完全等面积守恒在圆角形状上看起来会"瘪下去"，
     * 近似守恒（0.6）才有"液体被甩动"的观感。
     */
    const val SQUASH_RATIO: Float = 0.6f

    /**
     * 弹簧阻尼比（**欠阻尼**：0.72 ⇒ 到位时有一次可见回弹）。
     *
     * 三条边界理由：
     * - 取 1.0（临界阻尼）⇒ 与 `animateFloatAsState` 的默认缓动无异，**看不出弹簧**
     * - 取 < 0.5 ⇒ 回弹两三次，底栏是高频操作目标，会让"点了没反应"变成真实体感
     * - 取 0.72 ⇒ 一次回弹，既证明"这是物理"，又不耽误下一次点击
     *
     * 刚度不在这里（它是 Compose `Spring` 的常量，直接写在调用处）——
     * 本对象只持有**被断言过**的量，而刚度无法在纯 JVM 下断言其体感。
     */
    const val SPRING_DAMPING_RATIO: Float = 0.72f

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
     */
    fun nearestIndex(
        x: Float,
        itemWidth: Float,
        count: Int,
    ): Int {
        if (count <= 0 || itemWidth <= 0f) return 0
        val raw = floor(x / itemWidth).toInt()
        return raw.coerceIn(0, count - 1)
    }

    /**
     * 速度 → 归一化拉伸量（`0..1`，越界与 `NaN` 都收敛）。
     *
     * 只认**速度大小**、不认方向：向左甩和向右甩都该被拉长。
     * `NaN` 走 [abs] 后仍是 `NaN`，而 `coerceIn` 对 `NaN` **不生效**（比较恒为 false），
     * 因此必须先判 `isFinite()` —— 与 `GlassParams.orZero()` 是同一纪律
     * （那样一个 `NaN` 会一路进 `graphicsLayer.scaleX`，表现为指示器消失）。
     */
    fun normalizedStretch(velocityX: Float): Float {
        if (!velocityX.isFinite()) return 0f
        return (abs(velocityX) / STRETCH_VELOCITY_REFERENCE).coerceIn(0f, 1f)
    }

    /** 归一化拉伸量 → 宽度倍率（拉伸）。 */
    fun widthScale(stretch: Float): Float = 1f + sanitizeStretch(stretch) * MAX_STRETCH

    /** 归一化拉伸量 → 高度倍率（压缩，与 [widthScale] 反向）。 */
    fun heightScale(stretch: Float): Float = 1f - sanitizeStretch(stretch) * MAX_STRETCH * SQUASH_RATIO

    /** 拉伸量的统一收敛点（越界 clamp + `NaN` 归零）。 */
    private fun sanitizeStretch(stretch: Float): Float = if (!stretch.isFinite()) 0f else stretch.coerceIn(0f, 1f)
}

/**
 * Tab 角标的**显示规则**（阶段 11）。
 *
 * ## 为什么规则要独立于组件
 * 参考实现（`akbardzulfikar/liquid_glass_bottom_nav`）的 `badgeCount` 语义是
 * "数字本身的纯反射" —— 组件不猜"什么时候该清掉"。本项目沿用这条：
 * **能不能显示、显示成什么**由本对象决定，**什么时候把计数清零**由调用方（数据源）决定。
 *
 * ## ★ 本阶段的已知空能力（如实登记）
 * RootFlow 的三个 Tab **没有未读语义**，而现有数据源都不合适
 * （`RunActivityProvider` 是"最近一次运行"且单调不回退；`RunSessionRegistry.activeRuns()`
 * 没有 UI 端口）。因此 `RootFlowMain` 本阶段传的是空表，**角标不会出现**。
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
