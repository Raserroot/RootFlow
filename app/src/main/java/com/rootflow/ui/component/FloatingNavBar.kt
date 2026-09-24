package com.rootflow.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * ## 液态玻璃微光边框为什么用 `drawWithCache` + `Brush.linearGradient`
 * 需求 §6 点名了这两个 API。装进 `drawWithCache` 而不是 `drawBehind` 的原因是
 * **对象生命周期**：`Brush` 与 `CornerRadius` 只在尺寸/配色变化时重建一次，
 * 滚动与 Tab 切换不产生新对象（否则每帧都要新建渐变对象，与 §6 的性能要求相悖）。
 *
 * @param currentTab 当前选中的 Tab（决定高亮）
 * @param onSelect 点击回调
 * @param hazeState 由 `RootFlowMain` 提供的 Haze 状态（source 挂在页面内容上）
 * @param blurSupported 是否真正启用模糊（由 `BlurPolicy.effectiveBlur` 决定）
 * @param glassTier 阶段 7 的三档判定结果（决定"模糊交给谁"与底面用哪个 alpha）
 * @param onCapsuleTopMeasured 胶囊顶边在**根坐标**里的 y（px）；玻璃层用它对齐内容
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
) {
    val colorScheme = MaterialTheme.colorScheme
    val dark = colorScheme.background.luminanceIsDark()
    // Tier 1/2 = 玻璃层在跑（它自带模糊）⇒ 本组件不再调 Haze
    val localGlassActive = glassTier != GlassTier.HAZE

    // ===== 阶段 6e：底栏**不做动画**（用户裁定，2026-09-20）=====
    //
    // 6e 曾实现过一版"切 Tab 时胶囊临时拉宽 + 上浮"的弹性（Haze Level 1），
    // 真机验证前被用户裁定**回退**：① "液态感"不是需求项；② 弹性会让底栏尺寸在
    // 转场期变化，而那是 6b 已确认过的模糊管线之外的新变量。
    //
    // 回退后底栏**没有任何动画**：胶囊尺寸是常量、位置是常量。
    // `NavBarElastic`（纯函数 + 单测）**保留**为已评估过的 API，但**不参与生产**。
    //
    // ★ 阶段 7 的 AGSL 与它无关：折射参数是常量，**不要**借"液态玻璃"之名把弹性挂回来
    //   （`STAGE7-PLAN.md §8` 第 9 条）。
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // 只有横向留白是"设计"，纵向要让位系统导航栏 —— 否则胶囊会压在导航条下面
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = NavBarHorizontalPadding, vertical = NavBarVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(NavBarHeight)
                    // ★ 测量胶囊顶边（根坐标）并回报给根组件：玻璃层靠它把内容对齐到
                    //   "胶囊背后那一条像素"。positionInRoot 与玻璃层的坐标系同源
                    //   （两者都是根 Box 的直接子节点）。
                    .onGloballyPositioned { coordinates ->
                        onCapsuleTopMeasured(coordinates.positionInRoot().y.toInt())
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
                    .liquidGlassBorder(cornerRadius = NavBarCornerRadius, dark = dark),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabDestinations.ALL.forEach { tab ->
                NavBarItem(
                    tab = tab,
                    selected = tab == currentTab,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 单个 Tab：图标 + 文字；选中时图标底下垫一枚"药丸"。 */
@Composable
private fun NavBarItem(
    tab: TabDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        label = "navItemColor",
    )
    // ★ 阶段 8：选中底色从"包住图标的宽胶囊"改为**圆形软背景**（对齐 LSPosed 参考图）。
    //   尺寸恒定（44.dp）⇒ 选中/未选中之间只有**颜色**在淡入淡出，没有尺寸跳动；
    //   未选中时 alpha = 0（而不是换成另一套布局分支），因此图标不会因选中而位移。
    val badgeColor by animateColorAsState(
        targetValue =
            if (selected) {
                colorScheme.secondaryContainer.copy(alpha = SELECTED_BADGE_ALPHA)
            } else {
                Color.Transparent
            },
        label = "navItemBadge",
    )
    // 不显示水波纹：底栏是玻璃材质，涟漪会在渐变边框下显得脏
    val interactionSource = remember { MutableInteractionSource() }

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
            modifier = Modifier.size(SELECTED_BADGE_SIZE).clip(CircleShape).background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon(),
                contentDescription = tab.title,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
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
 * 选中项那枚圆形软背景的尺寸（参考图里它比图标大一圈，但不占满 Tab 宽度）。
 *
 * 44.dp 落在 LSPosed 观感的 40–48 区间中段；若取 40，图标（22dp）四周只剩 9dp
 * 呼吸位，看起来会像"图标被框住"；取 48 则会在 3 个 Tab 的底栏里显得挤。
 */
private val SELECTED_BADGE_SIZE = 44.dp

/**
 * 圆形软背景的 alpha。
 *
 * 用**半透明**而不是实心 `secondaryContainer`：底栏本身是玻璃/毛玻璃，
 * 实心色块会在渐变描边下显得像"贴上去的贴纸"（参考图的选中态也是半透明浅灰圆）。
 */
private const val SELECTED_BADGE_ALPHA = 0.55f

/**
 * 液态玻璃微光边框（需求 §6）。
 *
 * ## 为什么是**斜向**渐变
 * 玻璃的棱边高光来自单一光源：左上最亮、右下最暗。用一条 45° 的
 * `Brush.linearGradient` 只描边不填充，就得到"边缘被光扫过"的观感；
 * 若是四周均匀描边，看起来会像普通 `border`，失去玻璃感。
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
        val topAlpha = if (dark) 0.24f else 0.70f
        val midAlpha = 0.06f
        val bottomAlpha = if (dark) 0.14f else 0.40f
        val brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = topAlpha),
                        Color.White.copy(alpha = midAlpha),
                        Color.White.copy(alpha = bottomAlpha),
                    ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
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
