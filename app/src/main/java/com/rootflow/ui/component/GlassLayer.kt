package com.rootflow.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rootflow.domain.glass.GlassParams
import com.rootflow.domain.glass.GlassTier
import com.rootflow.ui.theme.NavBarBlurRadius
import com.rootflow.ui.theme.NavBarCornerRadius

/**
 * 玻璃层（阶段 7 完整版）：把"胶囊背后那一条内容"画进本层，再交给 [LiquidGlassRenderer]。
 *
 * ## 它在整棵树里的位置（**关键，别搬**）
 * 本组件是 `RootFlowMain` 根 Box 的**直接子节点**（与内容层同级、与胶囊逐像素对齐）。
 * 它与内容层共用**根坐标系**，因此"让本层显示的内容 = 胶囊背后的内容"可以只用
 * 根坐标里的三个量算出来。若把它搬进胶囊内部（坐标系原点变成胶囊左上角），
 * 就得再减去"胶囊在根坐标里的位置"——多一层换算，多一处出错的机会；
 * 而错位的表现是"玻璃里是黑的/花纹与真实内容对不上"，极易被误判成"shader 写错了"。
 *
 * ## ★★ 对齐量的推导（**两处真机踩到的坑，都在这里**，2026-09-20）
 *
 * 记根坐标下：
 * - `L` = **内容层的原点**（[contentLayerOriginPx]，由 `RootFlowMain` 测量回填；本机 = 107，即 safeDrawing 的顶 inset）
 * - `G` = 本玻璃层的顶边（[GlassLayer] 自己测量）
 * - `C` = 胶囊顶边 = [contentOffsetPx]（由 `FloatingNavBar` 测量回填）
 *
 * ### 坑 1：内容盒必须是 **definite 全屏高**，`offset` 挪不动布局尺寸
 * 第一版把内容放进一个小 Box 里加 `offset(y = -offsetPx)`，真机日志给出了根因：
 * ```
 * GLASS_SIZE    w=960 h=192            ← 玻璃层 = 胶囊尺寸（64dp）
 * GLASS_CONTENT y=-2124 h=192 w=960    ← 内容被**布局约束**在 192 高！
 * ```
 * 195 高的盒子里那个 `fillMaxSize()` 的 `LazyColumn` 视口只有 192px ⇒ 它画的是
 * **列表顶部**，而不是"滚到 2124px 处"的那一条。`offset` 只改绘制位置、不改尺寸，
 * 所以"小盒子 + 偏移"根本达不到目的。现在内容盒 = 屏幕高（`requiredHeight`，
 * definite ⇒ `LazyColumn` 可滚动、`fillMaxSize()` 解析出正确视口）。
 *
 * ### 坑 2：上移量的基准是**内容层原点**，不是屏幕高度
 * 内容盒要覆盖"内容层在屏幕上的那一块"，因此它自己的坐标系原点必须落在 `L` 上：
 * ```
 * translationY = L - C        ← 正确
 * ```
 * 第二版误用了屏幕高度（`G + screenH - C`），于是内容被整体上移了
 * `screenH - L`（本机 = 2293px）⇒ 玻璃里采到的是"页面顶部那一条"而不是胶囊背后那一条。
 * 这解释了真机上"玻璃里是一块与背后无关的深色"（采到了终端卡内部的深色面板）。
 *
 * ## 代价（如实登记）
 * **页面内容被绘制两次**（一次正常、一次进玻璃层），且第二次是**整页**而不是胶囊那 9%
 * —— 它必须拥有与真实内容相同的视口，才能画出"同一个滚动位置"。
 * Phase 1a 探针实测的门禁数据（90th 11→9ms）就是在这一形态下取得的。
 *
 * ## 三处失败都不崩
 * 1. 还没测量到对齐量 ⇒ 整层不画 effect，也不画内容
 * 2. `renderer.effect(...)` 返回 `null` ⇒ 不套 effect（本层退化成"内容原样画一遍"）
 * 3. shader 构造抛异常 ⇒ [AndroidGlassEffectFactory] 内部降级到纯 blur
 *
 * @param tier 三档判定结果（调用方保证**不是** [GlassTier.HAZE] —— 那一档没有玻璃层）
 * @param renderer 渲染器（缓存 + 降级都在它内部）
 * @param contentOffsetPx 胶囊顶边在根坐标里的 y（由 `FloatingNavBar` 测量回填）
 * @param contentLayerOriginPx 内容层原点在根坐标里的 y（由 `RootFlowMain` 测量回填）
 * @param contentSlot 页面内容槽位（**与正常绘制的那一份是同一个 lambda**）
 *
 * ## 调试这一层时怎么定位（两处坑都是这么找出来的）
 * 在 [GlassLayer] 与内容层上临时加 `onGloballyPositioned` + `Log.i`，把三个根坐标量
 * （`G` / `C` / `L`）与内容盒的**实测高宽**打出来。判据：
 * - 内容盒高度 ≈ 屏幕高（= 2400）；若等于胶囊高（192）⇒ 退回「坑 1」
 * - 内容盒 `translationY` ≈ `C − L`（本机 ≈ 2017）；若 ≈ 2293 ⇒ 退回「坑 2」
 *
 * **诊断完必须删除**（本次实测留下过 `GLASS_ALIGN` / `GLASS_SIZE` / `GLASS_CONTENT` 三行，
 * 已清；`Select-String 'GLASS_'` 应为空）。
 */
@Composable
internal fun GlassLayer(
    tier: GlassTier,
    renderer: LiquidGlassRenderer,
    contentOffsetPx: Int,
    contentLayerOriginPx: Int,
    contentSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // 本层顶边在根坐标里的 y（测量回填）。与另两个量同坐标系 ⇒ 差值可直接用。
    var glassTopInRootPx by remember { mutableIntStateOf(Int.MIN_VALUE) }

    val measured = contentOffsetPx > 0 && glassTopInRootPx != Int.MIN_VALUE
    // 内容盒的最终绘制位置 = 本层顶 + 上移量；上移量 = (内容层原点 − 胶囊顶)（见类 KDoc 坑 2）
    val contentShiftPx = (glassTopInRootPx + contentLayerOriginPx - contentOffsetPx).coerceAtLeast(0)

    Box(
        modifier =
            modifier
                // 裁成胶囊形状：本层比胶囊略宽（含横向留白），
                // 不裁的话边缘会露出被折射的"多余一条"
                .clip(RoundedCornerShape(NavBarCornerRadius))
                .onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInRoot().y.toInt()
                    if (top != glassTopInRootPx) glassTopInRootPx = top
                }.then(
                    if (!measured) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            if (size.width > 0f && size.height > 0f) {
                                renderEffect =
                                    renderer.effect(
                                        tier = tier,
                                        size = IntSize(size.width.toInt(), size.height.toInt()),
                                        params =
                                            GlassParams.default(
                                                blurRadiusPx = with(this) { NavBarBlurRadius.toPx() },
                                                cornerRadiusPx = with(this) { NavBarCornerRadius.toPx() },
                                            ),
                                    )
                            }
                        }
                    },
                ),
    ) {
        if (measured) {
            // ⚠️ 这个盒子**必须**是 definite 的全屏高（见类 KDoc 坑 1）；换成 `fillMaxSize()`
            //    或 `height()` 都会退回"内容被约束成胶囊高"的旧缺陷。
            Box(
                modifier =
                    Modifier
                        .requiredWidth(with(density) { configuration.screenWidthDp.dp })
                        .requiredHeight(with(density) { configuration.screenHeightDp.dp })
                        .graphicsLayer { translationY = -contentShiftPx.toFloat() },
            ) {
                contentSlot()
            }
        }
    }
}
