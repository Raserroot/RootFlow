package com.rootflow.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮胶囊底栏（需求 §6：`NavigationBar` 自定义 + Haze 毛玻璃 + 液态玻璃高光边框）。
 *
 * ## 结构（**绘制顺序自上而下**，不能换）
 * ```
 * 父 Box（横向 20dp / 纵向 12dp 留白 + 导航栏 inset）
 *   └─ 胶囊 Box（高 64dp，测量位置回填给玻璃层）
 *       ├─ 液态指示器（全栏唯一、可滑动、会膨胀 / 拉伸）
 *       ├─ Tab Row：内容 + 底面遮罩 + （Tier 3 才有的）hazeEffect
 *       ├─ 液态玻璃微光边框
 *       └─ 交互高光（手指按住哪里，哪里亮）
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
 * # ★★★ 阶段 11 / 11b：液态指示器（`STAGE11-PLAN.md`）
 *
 * ## 推翻 6e 的裁定（**U1 / U2，已登记**）
 * 6e 曾把"底栏不做动画"定为结论。用户 2026-09-24 指定参照液态玻璃实现升级底栏 ⇒
 * 理由①「液态感不是需求项」**已被新决定覆盖**；理由②「弹性会让底栏尺寸在转场期变化」
 * **仍然成立**，因此处置方式是：
 *
 * **只让指示器动，不让胶囊动。** 胶囊的尺寸、位置、圆角全部保持常量 ——
 * 这正是 `GlassLayer` 逐像素对齐与 `RenderEffect` 缓存的前提（`§2.6`）。
 * 已评估过的 [NavBarElastic]（整条胶囊拉宽）因此**仍然不参与生产**。
 *
 * ## 11b 为什么重做（用户实测反馈：「还是不行」）
 * 阶段 11 的第一版指示器是一枚 **44dp 静态圆**、形变只有 18%，
 * 按下去**毫无反馈** —— 那不是"液态"，只是一个会滑动的色块。
 * 参照 [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass) 的
 * `LiquidBottomTabs.kt` + `DampedDragAnimation.kt` 重做后，本版具备：
 *
 * | 特性 | 做法 | 落点 |
 * |---|---|---|
 * | **形状** | 占满一个 Tab 格的 capsule（宽 = 格宽 − 内缩，高 = 胶囊高 − 内缩） | 本文件 |
 * | **按下膨胀** | `1 → 1.39`（`78/56`），且 scaleX / scaleY 用**不同**弹簧 | `pressScaleX/Y` |
 * | **速度拉伸** | `scaleX /= 1−clamp(v·0.75,±0.2)`；`scaleY *= 1−clamp(v·0.25,±0.2)` | [NavBarIndicator] |
 * | **真实速度** | `VelocityTracker`（不是"位移 ÷ 一帧"的估算） | 本文件 |
 * | **点击也有挤压** | 点击 → `press()` → 140ms → `release()` | [PRESS_PULSE_MILLIS] |
 * | **跟手高光** | AGSL 光晕 + `BlendMode.Plus`，uniform 每帧更新 | [navBarGlow] |
 * | **立体感** | 主体竖向渐变 + 迎光侧描边 | `indicatorSurface` |
 *
 * ## 指示器的三条设计约束（改动前先读）
 * 1. **它是全栏唯一的**：不再由每个 [NavBarItem] 各带一枚背景圆（那是阶段 8.0 的做法）。
 * 2. **它的水平位置只有两个来源**：非拖拽时由弹簧 `animate` 驱动；拖拽时由手指位移驱动。
 *    两者都写进同一个 `indicatorX`，不存在第二个真相。
 * 3. **它不得进入 `GlassParams`**：位置是每帧都在变的量，而 `RenderEffect` 的缓存纪律
 *    要求"只在尺寸/参数/档位变化时重建"。把位置塞进参数 = 每帧重建 effect = 规格 §六 明令禁止。
 *    （高光因此走 [navBarGlow] 这条**独立的绘制路径**，而不是改玻璃的参数。）
 *
 * @param currentTab 当前选中的 Tab（决定指示器滑到哪）
 * @param onSelect 点击回调
 * @param hazeState 由 `RootFlowMain` 提供的 Haze 状态（source 挂在页面内容上）
 * @param blurSupported 是否真正启用模糊（由 `BlurPolicy.effectiveBlur` 决定）
 * @param glassTier 阶段 7 的三档判定结果（决定"模糊交给谁"与底面用哪个 alpha）
 * @param onCapsuleTopMeasured 胶囊顶边在**根坐标**里的 y（px）；玻璃层用它对齐内容
 * @param badges 各 Tab 的角标计数（阶段 11）。**调用方传空表**，见 `§3.2` 的空能力登记
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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ── 几何 ─────────────────────────────────────────────────────────────
    // 胶囊实测宽度 ⇒ 等宽项宽。0 表示还没测量到（首帧），此时指示器不画。
    var stripWidthPx by remember { mutableFloatStateOf(0f) }
    val itemWidthPx = if (tabs.isEmpty()) 0f else stripWidthPx / tabs.size
    val insetPx = with(density) { NavBarIndicator.INSET_DP.dp.toPx() }
    val indicatorWidthPx = (itemWidthPx - insetPx * 2f).coerceAtLeast(0f)
    val indicatorHeightPx = with(density) { NavBarHeight.toPx() } - insetPx * 2f
    // 预先换算成 Dp：`.size(width = …, height = …)` 若写成**多行参数**，
    // ktlint 的链式调用规则不允许后面再接 `.graphicsLayer`（`chain-method-continuation`）
    val indicatorWidthDp = with(density) { indicatorWidthPx.toDp() }
    val indicatorHeightDp = with(density) { indicatorHeightPx.toDp() }

    // ── 指示器状态 ───────────────────────────────────────────────────────
    // 唯一真相：弹簧动画与拖拽手势都写它一个。初值 0 由下面的"首次测量"分支纠正。
    var indicatorX by remember { mutableFloatStateOf(0f) }
    // 速度（**Tab/秒**，不是 px/秒）：拉伸是相对量，归一化后小屏大屏观感一致
    var indicatorVelocity by remember { mutableFloatStateOf(0f) }
    // 是否已经按测量到的宽度就位过（首帧不许从 0 滑过去）
    var positioned by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    // 手指在**胶囊局部坐标**里的位置（高光跟手用）
    var finger by remember { mutableStateOf(Offset.Zero) }

    val pressProgress = remember { Animatable(0f) }
    val pressScaleX = remember { Animatable(1f) }
    val pressScaleY = remember { Animatable(1f) }
    val velocityTracker = remember { VelocityTracker() }

    // ── 按下 / 释放（三个量各用各的弹簧，这是"液态"的一半）────────────────
    // scaleX 0.6/250 与 scaleY 0.7/250 的差异来自参考实现：
    // 两个轴的收敛速度**故意不同**，形变才会看起来像"被甩开的一坨"而不是"等比放大"。
    fun press() {
        scope.launch { pressProgress.animateTo(1f, PRESS_SPEC) }
        scope.launch { pressScaleX.animateTo(NavBarIndicator.PRESSED_SCALE, SCALE_X_SPEC) }
        scope.launch { pressScaleY.animateTo(NavBarIndicator.PRESSED_SCALE, SCALE_Y_SPEC) }
    }

    fun release() {
        scope.launch { pressProgress.animateTo(0f, PRESS_SPEC) }
        scope.launch { pressScaleX.animateTo(1f, SCALE_X_SPEC) }
        scope.launch { pressScaleY.animateTo(1f, SCALE_Y_SPEC) }
    }

    // ── 位置弹簧 ─────────────────────────────────────────────────────────
    // key 里的 `pressing` 让"松手"成为一次重启 ⇒ 指示器从当前拖到的位置滑向目标，天然可中断。
    LaunchedEffect(selectedIndex, itemWidthPx, pressing) {
        if (pressing || itemWidthPx <= 0f) return@LaunchedEffect
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
            indicatorVelocity = NavBarIndicator.velocityTabsPerSecond(velocity, itemWidthPx)
        }
    }

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
                        val width = coordinates.size.width.toFloat()
                        // 宽度变化（旋转 / 折叠屏）⇒ 让下一次 LaunchedEffect 重新就位，
                        // 否则指示器会停在按旧宽度算出来的位置
                        if (width != stripWidthPx) {
                            stripWidthPx = width
                            positioned = false
                        }
                    }.shadow(
                        // ★ 阶段 11b：投影。参考 App 的胶囊是**浮**在内容之上的
                        //   （它的底栏下方有一圈柔和暗影），而本版此前只有边框高光 ——
                        //   没有投影的玻璃看起来是"贴"在背景上的一层膜，不是一块厚玻璃。
                        //   6dp 是"看得见悬浮、又不至于像卡片掉下来"的量级。
                        elevation = NAV_BAR_SHADOW_ELEVATION,
                        shape = RoundedCornerShape(NavBarCornerRadius),
                        clip = false,
                    ).clip(RoundedCornerShape(NavBarCornerRadius))
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
                    // ⑤ 拖拽切换（**手势挂在胶囊上**，不在单个 Tab 上）。
                    //   `clickable` 在 Tab 上负责点击；Compose 的手势分发是"子先父后"，
                    //   点击被 Tab 消费、拖动落到这里，两者不会互相吞掉。
                    //   ★ key 里不放 currentTab：那会让每次切 Tab 都重建 pointerInput，
                    //     进行中的拖拽会被打断。落点判定只依赖几何，不依赖当前选中项。
                    .pointerInput(itemWidthPx, tabs.size) {
                        if (itemWidthPx <= 0f || tabs.isEmpty()) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { down ->
                                pressing = true
                                finger = down
                                velocityTracker.resetTracking()
                                press()
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                finger = change.position
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                indicatorX = (indicatorX + dragAmount).coerceIn(0f, stripWidthPx)
                                // ★ 真实速度来自 VelocityTracker（此前是"位移 ÷ 一帧"的估算）
                                indicatorVelocity =
                                    NavBarIndicator.velocityTabsPerSecond(
                                        pixelsPerSecond = velocityTracker.calculateVelocity().x,
                                        itemWidth = itemWidthPx,
                                    )
                            },
                            onDragEnd = {
                                pressing = false
                                release()
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
                            onDragCancel = {
                                pressing = false
                                release()
                            },
                        )
                    },
            // ⑥ 交互高光（跟手 AGSL 光晕）**在阶段 11c 摘除** —— 它在真机上两次造成
            //   "拖动时整块死白"（非 premultiplied 返回值 + smoothstep 的未定义行为）。
            //   原因、取舍与复活条件见 `NavBarGlow.kt` 的文件头；处置同 `NavBarElastic`。
        ) {
            // 液态指示器：画在 Tab 内容**之下**（它是背景，不该盖住图标）
            if (indicatorWidthPx > 0f && indicatorHeightPx > 0f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(width = indicatorWidthDp, height = indicatorHeightDp)
                            .graphicsLayer {
                                // 中心对齐：`indicatorX` 是中心 x，Box 的基准是左上角
                                translationX = indicatorX - size.width / 2f
                                // 按下膨胀（1 → 1.39）叠加速度拉伸（两个轴**反向**）
                                scaleX =
                                    pressScaleX.value *
                                    NavBarIndicator.stretchScaleX(indicatorVelocity)
                                scaleY =
                                    pressScaleY.value *
                                    NavBarIndicator.stretchScaleY(indicatorVelocity)
                            }.clip(CircleShape)
                            .background(NavBarPalette.indicator(dark))
                            .indicatorSurface(),
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
                        dark = dark,
                        badgeCount = badges[tab],
                        onPulse = {
                            // 点击也走一次"按下 → 释放"：否则点 Tab 时指示器只是滑过去，
                            // 没有"被捏了一下"的反馈（参考实现的 animateToValue 同样先 press）
                            if (!pressing) {
                                press()
                                scope.launch {
                                    delay(PRESS_PULSE_MILLIS)
                                    release()
                                }
                            }
                        },
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 按下 / 释放的弹簧（临界阻尼、很硬）：参考实现的 `spring(1f, 1000f, 0.001f)`。 */
private val PRESS_SPEC = spring<Float>(dampingRatio = 1f, stiffness = 1000f)

/** 横向缩放弹簧：**故意**比纵向软（0.6 vs 0.7），两个轴的收敛不同步 ⇒ 液体感。 */
private val SCALE_X_SPEC = spring<Float>(dampingRatio = 0.6f, stiffness = 250f)

/** 纵向缩放弹簧（见 [SCALE_X_SPEC]）。 */
private val SCALE_Y_SPEC = spring<Float>(dampingRatio = 0.7f, stiffness = 250f)

/** 点击时那次"挤压"的持续时间（ms）。短到不耽误连点，长到看得见。 */
private const val PRESS_PULSE_MILLIS = 140L

/**
 * 单个 Tab：图标 + 文字 + （可选）角标。
 *
 * ## 它不自带选中背景
 * 阶段 8.0 起，选中态是**这一项自己**的 44dp 圆在 `alpha 0 → 0.55` 之间淡入。
 * 那个圆现在被上提为底栏层的**液态指示器**（见 [FloatingNavBar] 的 KDoc），
 * 因此这里只剩"图标 + 文字"，选中只改变**颜色**。
 *
 * @param badgeCount 角标计数（`null` / `<= 0` 不显示，见 [NavBarBadge.text]）
 * @param onPulse 点击时的"挤压"回调（由底栏触发一次 press → release）
 */
@Composable
private fun NavBarItem(
    tab: TabDestination,
    selected: Boolean,
    dark: Boolean,
    badgeCount: Int?,
    onPulse: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    // ★ 选中蓝 / 未选中灰（用户点名的配色，见 [NavBarPalette]）—— 不再跟随 MD3 主题色
    val contentColor by animateColorAsState(
        targetValue = if (selected) NavBarPalette.selected(dark) else NavBarPalette.unselected(dark),
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
                    onClick = {
                        onPulse()
                        onClick()
                    },
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(ICON_FRAME_SIZE),
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
 * 从玻璃背景上跳出来；用 `primary` 会与选中态混淆，让人以为"这一格被选中了"。
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
 * 图标容器的固定尺寸（44dp）。
 *
 * 用固定容器而不是让图标自己撑开：图标与文字的相对位置因此**与图标无关**，
 * 换一套图标不会让文字上下跳。
 *
 * 它与指示器的高度（`NavBarHeight − 2 × INSET_DP` = 64 − 20 = 44dp）**数值相同但是巧合** ——
 * 前者是"图标占位"，后者是"水滴的高度"。改其中一个不该被另一个牵动，
 * 因此这里是两个独立的概念，只在视觉上恰好一致。
 */
private val ICON_FRAME_SIZE = 44.dp

/**
 * 底栏的**选中 / 未选中配色**（阶段 11d）。
 *
 * ## 为什么不用 `MaterialTheme.colorScheme`
 * 用户明确点名了配色：**选中蓝、未选中灰**（参照 `LSPosed 2.1.1` 的底栏做法与
 * `OPCameraPro 3.2.10` 的选中色）。而 MD3 的 `colorScheme.primary` 在本项目的默认配色下
 * 是**紫色**，与"蓝"不是一回事；`onSurfaceVariant` 也不是用户要的那个灰。
 *
 * ## 与「MD3 动态取色」（需求 §6）的关系
 * 这组颜色**覆盖**动态取色 —— 这是一次**有意的局部例外**：底栏是唯一一处
 * 用户直接点名了颜色的地方，其余界面仍完全跟随动态取色。
 * 若将来要恢复，把这几个常量换成 `colorScheme.primary` / `onSurfaceVariant` 即可。
 *
 * ## 取值来源（对参照 App 的截图逐像素取样）
 * | 用途 | 浅色 | 深色 | 来源 |
 * |---|---|---|---|
 * | 选中 | `#3B7DE4` | `#7FB0FF` | OPCameraPro 的选中图标/文字实测 `#3B7FE4`；深色档调亮以保住对比 |
 * | 未选中 | `#7A7A7A` | `#9E9E9E` | OPCameraPro 的未选中**文字**实测 `#7A7A7A` |
 * | 指示器填充 | 蓝 16% | 蓝 22% | 参照 App 的淡色药丸（它用中性灰，本版按用户要求改蓝） |
 */
private object NavBarPalette {
    private val selectedLight = Color(0xFF3B7DE4)
    private val selectedDark = Color(0xFF7FB0FF)
    private val unselectedLight = Color(0xFF7A7A7A)
    private val unselectedDark = Color(0xFF9E9E9E)

    fun selected(dark: Boolean): Color = if (dark) selectedDark else selectedLight

    fun unselected(dark: Boolean): Color = if (dark) unselectedDark else unselectedLight

    /**
     * 指示器的填充 = 选中色的低 alpha 版。
     *
     * 用**半透明**而不是实心色块：底栏本身是玻璃，实心色块会在渐变描边下显得像
     * "贴上去的贴纸"（参照 App 的药丸也是淡色的）。
     */
    fun indicator(dark: Boolean): Color = selected(dark).copy(alpha = if (dark) 0.22f else 0.16f)
}

/** 角标相对图标框右上角的内缩（dp）。 */
private val BADGE_INSET = 6.dp

/**
 * 胶囊的投影高度（dp）。
 *
 * 6dp 的来历：对着参考 App 的截图目测 —— 它的底栏下方有一圈**柔和但不浓重**的暗影，
 * 量级与"一张浮在桌面上的卡片"相当。取 2–4 会在浅色背景上看不出悬浮；
 * 取 12 以上会让底栏像被人从页面里"抠"出来一块，与玻璃材质冲突。
 *
 * ## 为什么三档降级都保留投影
 * 投影表达的是"**这块东西浮在内容之上**"，与"里面是折射还是模糊"无关 ——
 * 恰恰相反，越是没有折射的低端档，越需要投影来维持"它是一块独立的玻璃"这个读法。
 */
private val NAV_BAR_SHADOW_ELEVATION = 6.dp

/**
 * 指示器表面的**立体感**（阶段 11b）。
 *
 * ## 为什么需要它
 * 阶段 11 的第一版指示器是一个**纯色圆** —— 没有高光、没有暗部，
 * 看起来就是一块贴纸。真正的"水滴"必须有厚度：迎光侧亮、背光侧暗。
 *
 * ## 两层怎么叠
 * 1. **主体**：竖向渐变（顶部提亮 → 中部透明 → 底部压暗）。
 *    竖向而不是斜向，是因为参照的水滴本体是"液面" —— 液面在重力下有水平的明暗分界。
 * 2. **边缘描边**：斜向渐变（左上亮 → 右下暗），与 `liquidGlassBorder` 同一个光源方向
 *    （[NavBarLight.DEFAULT_ANGLE_DEGREES]）。两处**必须同源**，否则高光会打架。
 *
 * 用 `drawWithCache` 而不是 `drawBehind`：`Brush` 与 `Stroke` 只在尺寸变化时重建一次，
 * 而指示器的尺寸在按下/拉伸时**每帧都在变** —— 这正好是缓存的对象生命周期该管的事。
 */
private fun Modifier.indicatorSurface(): Modifier =
    this.drawWithCache {
        val radius = CornerRadius(size.height / 2f)
        val body =
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.06f),
                    ),
            )
        val axis =
            NavBarLight.axis(
                width = size.width,
                height = size.height,
                direction = NavBarLight.unitVector(NavBarLight.DEFAULT_ANGLE_DEGREES),
            )
        val rim =
            Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f)),
                start = axis.end,
                end = axis.start,
            )
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRoundRect(brush = body, cornerRadius = radius, size = size)
            drawRoundRect(brush = rim, cornerRadius = radius, size = size, style = stroke)
        }
    }

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
 * 它几乎是一条**水平**渐变 —— 也就是说"斜向高光"其实是个巧合，只在 w ≈ h 时成立。
 *
 * 现在渐变轴由 [NavBarLight.axis] 按**光源方向**算出来：过中心、沿光向、两端刚好把
 * 矩形投影覆盖住。于是斜向是**算出来的**，且与 AGSL 的 Fresnel、指示器的描边
 * **同源**（都取自 [NavBarLight]）—— 三处高光打架从结构上就不会发生。
 *
 * ## 为什么深色下要**提高**白色透明度
 * 深色主题的表面本来就暗，白色高光在暗底上对比度更低；若沿用浅色的数值，
 * 边框会几乎看不见。因此深色用更高的 alpha（0.24/0.14）而不是更低。
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
