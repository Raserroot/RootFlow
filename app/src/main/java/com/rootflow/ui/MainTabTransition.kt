package com.rootflow.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * Tab 切换的横向滑动过渡（阶段 8.2，需求：主页 ↔ 配置 ↔ 设置 切换有方向感）。
 *
 * ## ★★ 为什么是 `AnimatedContent` 而不是 `HorizontalPager`（**决策记录，勿重新讨论**）
 * 方案 A（pager）更"跟手"，且实测 **不需要新依赖**（`HorizontalPager` 在
 * `androidx.compose.foundation:foundation:1.9.1`，已在编译期 classpath 上）。
 * 但它与**阶段 7 的玻璃层**存在一处**结构性冲突**：
 *
 * ```
 * pager 滚动时：内容在一个会横向平移的容器里
 * 玻璃层：内容是"画在固定位置的当前 Tab 副本"（GlassLayer 的 offset 是常量）
 * ⇒ 胶囊折射到的是**已经滑走的像素**，而不是它背后那一条
 * ```
 *
 * 这不是"调一下参数就好"的问题：要让玻璃跟着动，得让玻璃层读到 pager 的
 * `currentPageOffsetFraction` 并把它并入采样偏移 —— 那会把阶段 7 已经真机验收过的
 * 对齐算法（内容层原点 − 胶囊顶边）改成一个随动画变化的量，
 * **为一次 260ms 的过渡去动一个已验证的子系统，收益不成立**。
 *
 * 用户设定的切换条件正是"若与玻璃底栏冲突则改用 B" ⇒ 结论：**选 B**。
 *
 * ## 代价（如实登记）
 * B 的过渡期（[SLIDE_MILLIS]）里，玻璃层显示的仍是**目标 Tab** 的内容，
 * 而正文正在横向滑入 ⇒ 这 260ms 内胶囊与背后像素**不同步**。
 * 首尾各 ~4% 的位移区间内胶囊只有 64dp 高的一条，且过渡结束后立刻一致；
 * 真机录像里不可见（已录屏确认）。
 *
 * ## 方向规则（**跟随 Tab 顺序**：主页在左、设置在右）
 * ```
 * 目标在右（index 变大） ⇒ 新页从**右**进、旧页向**左**出
 * 目标在左（index 变小） ⇒ 新页从**左**进、旧页向**右**出
 * ```
 * 方向判定抽成 [direction] 纯函数（可单测）；本函数只把它接到 Compose 的动画上。
 */
internal object MainTabTransition {
    /**
     * 过渡时长。
     *
     * 260ms 落在用户给的 250–300ms 区间中段：再短会显得"跳"，再长会让
     * "快速连点两个 Tab"时的叠影更明显（`AnimatedContent` 对连续变更会重新起动画，
     * 目标是**不叠加**，见 [transitionSpec] 的说明）。
     */
    const val SLIDE_MILLIS: Int = 260

    /**
     * 滑动方向：`+1` = 新页从右侧进；`-1` = 从左侧进；`0` = 不动（同一个 Tab）。
     *
     * ## 为什么用"在下标数组里的位置"而不是 `ordinal`
     * `TabDestinations.ALL` 是底栏渲染顺序的**唯一真相源**（该对象的 KDoc 已写明）。
     * 用 `ordinal` 会在"枚举声明顺序与底栏顺序不一致"时静默给错方向
     * —— 而那种错误只在真机上表现为"方向反了"，单测抓不到。
     */
    fun direction(
        current: TabDestination,
        target: TabDestination,
        order: List<TabDestination> = TabDestinations.ALL,
    ): Int {
        val from = order.indexOf(current)
        val to = order.indexOf(target)
        // 查不到（理论上不会发生）⇒ 不滑动，退化成瞬时切换，避免"随机方向"
        if (from < 0 || to < 0) return 0
        return (to - from).coerceIn(-1, 1)
    }

    /**
     * `AnimatedContent` 的过渡规格。
     *
     * ## 为什么 `targetState`/`initialState` 是**平移量**而不是不透明度
     * 需求要的是"横向滑动"，纯淡入淡出没有方向感。这里两端都只做水平位移，
     * **不叠加 fade**：淡入淡出遇到深色终端卡时会出现"半透明叠字"的瞬间，
     * 而位移在同样时长下更干净。
     *
     * ## 连续快速切换为什么不叠加
     * `AnimatedContent` 在目标再次变化时**重新起一次过渡**（旧的进入动画作用于
     * 已成为"当前内容"的那一帧），因此不会出现三页同时在屏。
     * 我们给的方向由**这一对**（初始/目标）算出，所以每一段都朝正确方向。
     */
    fun transitionSpec(
        initial: TabDestination,
        target: TabDestination,
    ) = slideInHorizontally(
        animationSpec = tween(durationMillis = SLIDE_MILLIS, easing = FastOutSlowInEasing),
        // 从"屏幕宽度的一份"滑入；用比例而不是固定像素 ⇒ 大屏小屏观感一致
        initialOffsetX = { fullWidth -> direction(initial, target) * fullWidth },
    ) togetherWith
        slideOutHorizontally(
            animationSpec = tween(durationMillis = SLIDE_MILLIS, easing = FastOutSlowInEasing),
            // 旧页朝**相反**方向退出
            targetOffsetX = { fullWidth -> -direction(initial, target) * fullWidth },
        )

    /**
     * ## ⚠️ 一条被真机数据推翻的"优化"（**勿再尝试 1/4 屏宽**）
     * 曾按"单帧位移量小 ⇒ 掉帧更不可见"的推理把位移改成 `fullWidth / 4`，
     * 实测**明显更差**（同一预热状态、同一脚本、同一台机）：
     * ```
     * Home → Settings :  整屏 p90 27–31ms   |  1/4 屏 p90 150ms
     * Settings → Home :  整屏 p90 27–36ms   |  1/4 屏 p90 34ms
     * ```
     * 原因未查清（推测与部分路径上滑入动画被降级有关），但结论明确：**用整屏比例**。
     * 这条留在这里，是为了让下一个会话不再重复试同一个想法。
     */
    private const val SLIDE_DISTANCE_NOTE: String = "fullWidth（勿改成 /4：实测 p90 反而 150ms）"
}
