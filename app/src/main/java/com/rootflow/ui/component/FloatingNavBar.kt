package com.rootflow.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rootflow.domain.glass.GlassTier
import com.rootflow.ui.TabDestination
import com.rootflow.ui.TabDestinations
import com.rootflow.ui.icon
import com.rootflow.ui.theme.GLASS_SCRIM_ALPHA
import com.rootflow.ui.theme.NAV_BAR_FALLBACK_ALPHA
import com.rootflow.ui.theme.NAV_BAR_NOISE_FACTOR
import com.rootflow.ui.theme.NavBarBlurRadius
import com.rootflow.ui.theme.NavBarCornerRadius
import com.rootflow.ui.theme.NavBarHeight
import com.rootflow.ui.theme.NavBarHorizontalPadding
import com.rootflow.ui.theme.NavBarLight
import com.rootflow.ui.theme.NavBarVerticalPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * 悬浮胶囊底栏（需求 §6：`NavigationBar` 自定义 + Haze 毛玻璃 + 液态玻璃高光边框）。
 *
 * ## 结构（**绘制顺序自上而下**，不能换）
 * ```
 * 父 Box（横向 20dp / 纵向 12dp 留白 + 导航栏 inset）
 *   └─ 胶囊 Box（高 64dp，测量位置回填给玻璃层）
 *       ├─ 指示器（阶段 11：全栏唯一、可滑动、会形变）
 *       ├─ Tab Row：内容 + 底面遮罩 + （Tier 3 才有的）hazeEffect + 微光边框
 *       └─ ？玻璃层不在这里（见下）
 * ```
 *
 * ## ★★ 玻璃层为什么不在这里，而在 `RootFlowMain`
 * 玻璃层需要"胶囊背后那一条**屏幕像素**"，而 `RenderEffect` 作用在**本 layer 自己绘制的内容**上。
 * 也就是说：玻璃层里必须再画一遍页面内容，并且把那一遍**上移胶囊顶边的距离**。
 *
 * 上移量的计算要求"玻璃层与内容层共用同一坐标系"：
 * - 二者都是 `RootFlowMain` 根 Box 的**直接子节点** ⇒ 共用根坐标 ⇒
 *   上移量 = 胶囊顶边在根坐标里的 y（本组件用 [onCapsuleTopMeasured] 回报）
 * - 若把玻璃层放进本组件（胶囊内部），它的坐标系原点在胶囊左上角，
 *   上移量就得再减去"胶囊在根坐标里的位置"——多一层换算，多一处出错的机会。
 *
 * 本组件因此只负责**几何与交互**，以及把胶囊位置**测量出来**交给根组件。
 *
 * ## ★ `blurEnabled` 只有两条生效路径（**1.6.10 实测，勿再写第三条**）
 * | 路径 | 位置 |
 * |---|---|
 * | `HazeState.blurEnabled` | `rememberHazeState(blurEnabled = …)`（`RootFlowMain`） |
 * | `HazeEffectScope.blurEnabled` | 本文件的 `hazeEffect` block |
 *
 * 解析优先级（1.6.10 源码 `resolveBlurEnabled()`）：
 * `block 里设过` > `state != null 时取 state.blurEnabled` > `HazeDefaults.blurEnabled()`。
 * **`Modifier.hazeSource` 没有 `enabled` 参数**（签名只有 `(state, zIndex, key)`）。
 *
 * ## ★ 三档与"谁负责模糊"（阶段 7，已批准决策 P1）
 * | 档位 | 模糊由谁做 | 本组件调 `hazeEffect` 吗 |
 * |---|---|---|
 * | [GlassTier.REFRACTION]（33+） | AGSL 链里的 inner blur（同一趟） | **不调** |
 * | [GlassTier.BLUR_ONLY]（31–32 / 能力不足） | `RenderEffect.createBlurEffect`（玻璃层） | **不调** |
 * | [GlassTier.HAZE]（≤30 / 用户关闭） | Haze（现状，`v0.6-ui` 的样子） | 调 |
 *
 * 前两档**必须**不调：同一区域糊两次 = 双采样源 + 性能翻倍（`STAGE7-PLAN.md §1` 冲突点 1）。
 * 但**底面与边框三档都保留** —— 它们分别是"文字可读性"与"玻璃感的一半"，不是 Haze 的职责。
 *
 * # ★★★ 阶段 11：指示器与动效（`STAGE11-PLAN.md`）
 *
 * ## 这一节推翻了 6e 的裁定（**U1 / U2，已登记**）
 * 6e 曾把"底栏不做动画"定为结论，理由①"液态感不是需求项"②"弹性会让底栏尺寸在转场期变化"。
 * 用户 2026-09-24 指定参照两个 Flutter 实现升级底栏 ⇒ 理由①**已被新决定覆盖**。
 * 理由②**本阶段仍然成立**，因此处置方式是：
 *
 * **只让指示器动，不让胶囊动。** 胶囊的尺寸、位置、圆角全部保持常量 ——
 * 这正是 [GlassLayer] 逐像素对齐与 `RenderEffect` 缓存的前提（`§2.6`）。
 * 已评估过的 [NavBarElastic]（整条胶囊拉宽）因此**仍然不参与生产**。
 *
 * ## 指示器的三条设计约束（改动前先读）
 * 1. **它是全栏唯一的**：不再由每个 [NavBarItem] 各带一枚背景圆（那是阶段 8.0 的做法，
 *    选中的表意是"原地淡入"）。现在选中的表意是"它滑过去了"。
 * 2. **它的水平位置只有两个来源**：非拖拽时由弹簧 `animate` 驱动；拖拽时由手指位移驱动。
 *    两者都写进同一个 `indicatorX`，不存在第二个真相。
 * 3. **它不得进入 `GlassParams`**：位置是每帧都在变的量，而 `RenderEffect` 的缓存纪律
 *    要求"只在尺寸/参数/档位变化时重建"。把位置塞进参数 = 每帧重建 effect = 规格 §六 明令禁止。
 *
 * ## 为什么用 `animate()` 而不是 `Animatable`
 * `animate(initial, target, spec) { value, velocity -> … }` 的回调**同时给值与速度**，
 * 而速度正是 squash-and-stretch 要用的量。用 `Animatable` 得去读 `snapshotFlow { it.velocity }`，
 * 多一层间接；而在拖拽中直接给 `mutableFloatStateOf` 赋值是**同步**的，
 * 不必为每一帧 `launch { snapTo() }`（那会每秒创建 60 个协程）。
 *
 * @param currentTab 当前选中的 Tab（决定指示器滑到哪）
 * @param onSelect 点击回调
 * @param hazeState 由 `RootFlowMain` 提供的 Haze 状态（source 挂在页面内容上）
 * @param blurSupported 是否真正启用模糊（由 `BlurPolicy.effectiveBlur` 决定）
 * @param glassTier 阶段 7 的三档判定结果（决定"模糊交给谁"与底面用哪个 alpha）
 * @param onCapsuleTopMeasured 胶囊顶边在**根坐标**里的 y（px）；玻璃层用它对齐内容
 * @param badges 各 Tab 的角标计数（阶段 11）。**本阶段调用方传空表**，见 `§3.2` 的空能力登记
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun FloatingNavBar(
    currentTab: TabDestination,
    onSelect: (TabDestination) -> Unit,
    hazeState: HazeState,
    blurSupported: Boolean,
    glassTier: GlassTier,
    onCapsuleTopMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<TabDestination, Int> = emptyMap(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val dark = colorScheme.background.luminanceIsDark()
    // Tier 1/2 = 玻璃层在跑（它自带模糊）⇒ 本组件不再调 Haze
    val localGlassActive = glassTier != GlassTier.HAZE

    val tabs = TabDestinations.ALL
    val selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0)

    // ── 阶段 11：指示器的水平位置（px，相对胶囊左边缘）────────────────────
    // 唯一真相：弹簧动画与拖拽手势都写它一个。初值 0 由下面的"首次测量"分支纠正。
    var indicatorX by remember { mutableFloatStateOf(0f) }
    var indicatorVelocity by remember { mutableFloatStateOf(0f) }
    // 是否已经按测量到的宽度就位过（首帧不许从 0 滑过去）
    var positioned by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    // 胶囊实测宽度 ⇒ 等宽项宽。0 表示还没测量到（首帧），此时指示器不画。
    var stripWidthPx by remember { mutableFloatStateOf(0f) }
    val itemWidthPx = if (tabs.isEmpty()) 0f else stripWidthPx / tabs.size

    // 图标行的中心 y（根坐标）—— 指示器要垂直对齐到**图标**上，而不是胶囊正中。
    // 用一个实测值而不是猜一个 dp 常量：文字行高由 `Type.kt` 的字号体系决定（阶段 8.1 改过），
    // 写死偏移会在下次调字号时静默错位。
    var iconCenterInRootPx by remember { mutableFloatStateOf(0f) }
    var capsuleTopInRootPx by remember { mutableFloatStateOf(0f) }

    // ── 弹簧滑动 ─────────────────────────────────────────────────────────
    // key 里的 `dragging` 让"松手"成为一次重启 ⇒ 苹果从当前拖到的位置滑向目标，天然可中断。
    LaunchedEffect(selectedIndex, itemWidthPx, dragging) {
        if (dragging || itemWidthPx <= 0f) return@LaunchedEffect
        val target = NavBarIndicator.centerX(selectedIndex, itemWidthPx)
        if (!positioned) {
            // 首次测量（或换屏宽后重新测量）：直接就位，不播一次"从左滑过来"
            indicatorX = target
            positioned = true
            return@LaunchedEffect
        }
        animate(
            initialValue = indicatorX,
            targetValue = target,
            animationSpec =
                spring(
                    dampingRatio = NavBarIndicator.SPRING_DAMPING_RATIO,
                    stiffness = Spring.StiffnessMediumLow,
                ),
        ) { value, velocity ->
            indicatorX = value
            indicatorVelocity = velocity
        }
    }

    // squash-and-stretch：弹簧滑行与拖拽都由 `indicatorVelocity` 供速（见各自的写入点）
    val stretch = NavBarIndicator.normalizedStretch(indicatorVelocity)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // 只有横向留白是"设计"，纵向要让位系统导航栏 —— 否则胶囊会压在导航条下面
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = NavBarHorizontalPadding, vertical = NavBarVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(NavBarHeight)
                    // ★ 测量胶囊顶边（根坐标）并回报给根组件：玻璃层靠它把内容对齐到
                    //   "胶囊背后那一条像素"。positionInRoot 与玻璃层的坐标系同源
                    //   （两者都是根 Box 的直接子节点）。
                    .onGloballyPositioned { coordinates ->
                        onCapsuleTopMeasured(coordinates.positionInRoot().y.toInt())
                        capsuleTopInRootPx = coordinates.positionInRoot().y
                        val width = coordinates.size.width.toFloat()
                        // 宽度变化（旋转 / 折叠屏）⇒ 让下一次 LaunchedEffect 重新就位，
                        // 否则指示器会停在按旧宽度算出来的位置
                        if (width != stripWidthPx) {
                            stripWidthPx = width
                            positioned = false
                        }
                    }.clip(RoundedCornerShape(NavBarCornerRadius))
                    // ② 底面遮罩：
                    //    · Tier 3（Haze）：仅在不支持模糊时铺纯色（需求 §6 的 0.92f）
                    //    · Tier 1/2：**始终**铺一层薄遮罩（见 GLASS_SCRIM_ALPHA 的 KDoc）
                    .then(
                        when {
                            localGlassActive -> {
                                Modifier.background(colorScheme.surface.copy(alpha = GLASS_SCRIM_ALPHA))
                            }

                            blurSupported -> {
                                Modifier
                            }

                            else -> {
                                Modifier.background(colorScheme.surface.copy(alpha = NAV_BAR_FALLBACK_ALPHA))
                            }
                        },
                    )
                    // ③ 毛玻璃：**只有 Tier 3 走 Haze**
                    .then(
                        if (localGlassActive) {
                            Modifier
                        } else {
                            Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin()) {
                                // ★ 四处显式赋值（F3）：**不依赖任何隐式解析链**。
                                // Haze 的解析顺序是 本节点 → style → compositionLocalStyle
                                // （`resolveBackgroundColor` / `resolveTints` / `resolveFallbackTint`，1.6.10 源码）。
                                backgroundColor = colorScheme.surface
                                blurRadius = NavBarBlurRadius
                                noiseFactor = NAV_BAR_NOISE_FACTOR
                                blurEnabled = blurSupported
                                fallbackTint = HazeTint(colorScheme.surface.copy(alpha = NAV_BAR_FALLBACK_ALPHA))
                            }
                        },
                    )
                    // ④ 液态玻璃微光边框（三档都用：它是"玻璃还在"的锚点）
                    .liquidGlassBorder(cornerRadius = NavBarCornerRadius, dark = dark)
                    // ⑤ 拖拽切换（阶段 11）：**手势挂在胶囊上**，不在单个 Tab 上。
                    //   `clickable` 在 Tab 上负责点击；Compose 的手势分发是"子先父后"，
                    //   点击被 Tab 消费、拖动落到这里，两者不会互相吞掉。
                    //   ★ key 里不放 currentTab：那会让每次切 Tab 都重建 pointerInput，
                    //     进行中的拖拽会被打断。落点判定只依赖几何，不依赖当前选中项。
                    .pointerInput(itemWidthPx, tabs.size) {
                        if (itemWidthPx <= 0f || tabs.isEmpty()) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragging = true
                                indicatorVelocity = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                // 速度估算：`detectHorizontalDragGestures` 不给速度，
                                // 用"本帧位移 / 一帧时长"。swatch 的满量程是 600px/s，
                                // 这个粗糙估算足够驱动 squash（它只影响形变的量，不影响落点）
                                indicatorVelocity = dragAmount / DRAG_FRAME_SECONDS
                                indicatorX = (indicatorX + dragAmount).coerceIn(0f, stripWidthPx)
                            },
                            onDragEnd = {
                                dragging = false
                                val index =
                                    NavBarIndicator.nearestIndex(
                                        x = indicatorX,
                                        itemWidth = itemWidthPx,
                                        count = tabs.size,
                                    )
                                // 无条件调用：`MainViewModel.select` 对"点的就是当前 Tab"
                                // 会返回 false 且不做事（幂等），因此这里不需要比较 currentTab
                                // —— 而比较会迫使 pointerInput 把 currentTab 当 key（见上）。
                                onSelect(tabs[index])
                            },
                            onDragCancel = { dragging = false },
                        )
                    },
        ) {
            // 指示器：画在 Tab 内容**之下**（它是背景，不该盖住图标）
            if (itemWidthPx > 0f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .size(SELECTED_BADGE_SIZE)
                            .graphicsLayer {
                                // 水平：把"中心 x"换算成左上角；垂直：对齐到图标行中心
                                translationX = indicatorX - size.width / 2f
                                translationY = iconCenterInRootPx - capsuleTopInRootPx - size.height / 2f
                                // squash-and-stretch（阶段 11）：沿运动方向拉伸、垂直压缩
                                scaleX = NavBarIndicator.widthScale(stretch)
                                scaleY = NavBarIndicator.heightScale(stretch)
                            }.clip(CircleShape)
                            .background(colorScheme.secondaryContainer.copy(alpha = SELECTED_BADGE_ALPHA)),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(NavBarHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    NavBarItem(
                        tab = tab,
                        selected = tab == currentTab,
                        badgeCount = badges[tab],
                        onIconMeasured = { centerYInRoot ->
                            if (centerYInRoot != iconCenterInRootPx) iconCenterInRootPx = centerYInRoot
                        },
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 单帧时长（秒）：用于把拖拽位移换算成速度。60Hz 的标称值。 */
private const val DRAG_FRAME_SECONDS = 1f / 60f

/**
 * 单个 Tab：图标 + 文字 + （可选）角标。
 *
 * ## 阶段 11：它不再自带选中背景
 * 阶段 8.0 起，选中态是**这一项自己**的 44dp 圆在 `alpha 0 → 0.55` 之间淡入。
 * 那个圆现在被上提到底栏层成为**可滑动的指示器**（见 [FloatingNavBar] 的 KDoc），
 * 因此这里只剩"图标 + 文字"，选中只改变**颜色**。
 *
 * 图标的位置要**回填给底栏**（[onIconMeasured]）：指示器必须垂直对齐到图标行，
 * 而"图标行中心相对胶囊顶边偏移多少"取决于文字行高 —— 那由 `Type.kt` 的字号体系决定。
 * 用一个实测值代替猜一个 dp 常量，下次调字号时就不会静默错位。
 *
 * @param badgeCount 角标计数（`null` / `<= 0` 不显示，见 [NavBarBadge.text]）
 * @param onIconMeasured 图标中心在**根坐标**里的 y（px），由底栏用于对齐指示器
 */
@Composable
private fun NavBarItem(
    tab: TabDestination,
    selected: Boolean,
    badgeCount: Int?,
    onIconMeasured: (Float) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        label = "navItemColor",
    )
    // 不显示水波纹：底栏是玻璃材质，涟漪会在渐变边框下显得脏
    val interactionSource = remember { MutableInteractionSource() }
    val badgeText = NavBarBadge.text(badgeCount)

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(NavBarCornerRadius))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(SELECTED_BADGE_SIZE)
                    .onGloballyPositioned { coordinates ->
                        onIconMeasured(coordinates.positionInRoot().y + coordinates.size.height / 2f)
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon(),
                contentDescription = tab.title,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            if (badgeText != null) {
                Badge(text = badgeText)
            }
        }
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 角标（阶段 11）：图标右上角的一枚小圆。
 *
 * ## 为什么用 `error` 色而不是主题强调色
 * 角标的语义是"这里多了点东西，去看一眼"。在底栏三格里，只有**警示类**颜色能在一眼之内
 * 从玻璃背景上跳出来；用 `primary` 会与选中态（`onSecondaryContainer` 的图标色）混淆，
 * 让人以为"这一格被选中了"。
 *
 * ## 尺寸与文本
 * 显示文本由 [NavBarBadge.text] 决定（含 `99+` 截断）；本组件只负责画。
 * `minSize` 让一位数也是圆形而不是竖条；两位以上靠 `padding` 自然撑成胶囊。
 */
@Composable
private fun BoxScope.Badge(text: String) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(start = BADGE_INSET)
                .clip(CircleShape)
                .background(colorScheme.error)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onError,
        )
    }
}

/**
 * 选中项那枚圆形软背景的尺寸（参考图里它比图标大一圈，但不占满 Tab 宽度）。
 *
 * 44.dp 落在 LSPosed 观感的 40–48 区间中段；若取 40，图标（22dp）四周只剩 9dp
 * 呼吸位，看起来会像"图标被框住"；取 48 则会在 3 个 Tab 的底栏里显得挤。
 *
 * 阶段 11 起它同时是**指示器**的尺寸与**图标容器**的尺寸 —— 两者必须相等，
 * 否则滑动中的圆会比图标底下那个圈大一圈或小一圈（那看起来像"没对齐"而不是"在滑动"）。
 */
private val SELECTED_BADGE_SIZE = 44.dp

/**
 * 圆形软背景的 alpha。
 *
 * 用**半透明**而不是实心 `secondaryContainer`：底栏本身是玻璃/毛玻璃，
 * 实心色块会在渐变描边下显得像"贴上去的贴纸"（参考图的选中态也是半透明浅灰圆）。
 */
private const val SELECTED_BADGE_ALPHA = 0.55f

/** 角标相对图标框右上角的内缩（dp）。 */
private val BADGE_INSET = 6.dp

/**
 * 液态玻璃微光边框（需求 §6；阶段 11 起**跟随光源角**）。
 *
 * ## 为什么是**斜向**渐变
 * 玻璃的棱边高光来自单一光源：迎光侧最亮、背光侧次之、中间最暗。用一条
 * `Brush.linearGradient` 只描边不填充，就得到"边缘被光扫过"的观感；
 * 若是四周均匀描边，看起来会像普通 `border`，失去玻璃感。
 *
 * ## ★ 阶段 11 改了什么（`STAGE11-PLAN.md §1.4`，推翻 `STAGE7-PLAN §8` 第 11 条）
 * 阶段 7 这版写的是 `start = Offset.Zero, end = Offset(w, h)`。
 * 那在**正方形**上恰好是 45°，但在底栏这种**宽扁**形状（约 1000×160）上，
 * 它几乎是一条**水平**渐变 —— 也就是说"斜向高光"其实是个巧合，
 * 只在 w ≈ h 时成立。
 *
 * 现在渐变轴由 [NavBarLight.axis] 按**光源方向**算出来：过中心、沿光向、两端刚好把
 * 矩形投影覆盖住。于是：
 * - 斜向是**算出来的**，与形状无关
 * - 光源角与 AGSL 的 Fresnel 项**同源**（都取自 `NavBarLight`）——
 *   镜面亮在左上、边框却亮在右下这种事从结构上就不会发生
 * - 默认光角（左上）下，颜色顺序仍是"迎光端最亮、中间最暗、背光端次亮"，
 *   因此深浅两套 alpha 的语义没有变，变的只是**轴**
 *
 * ## 为什么深色下要**提高**白色透明度
 * 深色主题的表面本来就暗，白色高光在暗底上对比度更低；若沿用浅色的数值，
 * 边框会几乎看不见。因此深色用更高的 alpha（0.24/0.14）而不是更低。
 *
 * ## 与 `Modifier.border` 的区别
 * `Modifier.border` 只接受单色或 `Brush` 且无法缓存几何计算。
 * 这里用 `drawWithCache` 同时缓存了 `Brush` 与 `Stroke`，并把描边画在
 * **内容之上**（`drawContent()` 之后）——这正是"微光"必须的层序：
 * 高光在玻璃表面之上，而不是被内容盖住。
 */
private fun Modifier.liquidGlassBorder(
    cornerRadius: Dp,
    dark: Boolean,
): Modifier =
    this.drawWithCache {
        val strokeWidth = 1.dp.toPx()
        val radius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        val lightwardAlpha = if (dark) 0.24f else 0.70f
        val midAlpha = 0.06f
        val shadowwardAlpha = if (dark) 0.14f else 0.40f
        val axis =
            NavBarLight.axis(
                width = size.width,
                height = size.height,
                direction = NavBarLight.unitVector(NavBarLight.DEFAULT_ANGLE_DEGREES),
            )
        val brush =
            Brush.linearGradient(
                // 第一个颜色对应 `start`。`NavBarLight.axis` 的 start 在**背光侧**
                // （center − 光向 × 半轴），因此这里从暗到亮排。
                colors =
                    listOf(
                        Color.White.copy(alpha = shadowwardAlpha),
                        Color.White.copy(alpha = midAlpha),
                        Color.White.copy(alpha = lightwardAlpha),
                    ),
                start = axis.start,
                end = axis.end,
            )
        val stroke = Stroke(width = strokeWidth)
        onDrawWithContent {
            drawContent()
            drawRoundRect(brush = brush, cornerRadius = radius, style = stroke)
        }
    }

/**
 * 判断一个颜色是不是"深色"（用于决定微光边框的 alpha）。
 *
 * ## 为什么不用 `isSystemInDarkTheme()`
 * 主题可以由用户强制为深色（`ThemeMode.DARK`），此时系统仍是浅色。
 * 边框的明暗必须跟随**实际生效的配色**，而不是系统设置。
 *
 * 用感知亮度（`0.299R + 0.587G + 0.114B`）而不是简单平均：
 * 人眼对绿最敏感、对蓝最不敏感，简单平均会把深绿误判成浅色。
 */
private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
