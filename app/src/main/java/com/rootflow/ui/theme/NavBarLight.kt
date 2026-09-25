package com.rootflow.ui.theme

import androidx.compose.ui.geometry.Offset
import com.rootflow.domain.glass.GlassParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 底栏的**虚拟光源**（阶段 11）。
 *
 * ## 它是什么、不是什么
 * 「光源」在本项目里**只是一个方向向量**，用来让两处高光**指向同一侧**：
 * 1. AGSL 里的 Fresnel 镜面项（`lightDirection` uniform）
 * 2. 边框渐变轴（[axis]，替换原先写死的 45°）
 *
 * **不是**一套光照模型：没有环境光/平行光/衰减，没有阴影，也没有多光源。
 * 参考实现（`burakozyurt/liquid-glass-bottom-nav-bar`）里的 `lightAngle` 就是这个量级的东西
 * —— 一个角度。把它做成完整光照模型是 `STAGE7-PLAN.md §8` 第 5 条明令不做的事。
 *
 * ## 为什么"两处必须同源"
 * 镜面亮在左上、边框却亮在右下，看起来不是"玻璃"，是"两块贴图"。
 * 因此**不允许**在 `FloatingNavBar` 里另写一个角度：边框必须从
 * `GlassParams.lightDirection` 的同一个来源拿方向（见 [axis] 的调用方）。
 *
 * ## 坐标系（**与 AGSL 一致，别搞反**）
 * 屏幕坐标 **y 轴向下**：`x` 向右、`y` 向下。
 * 因此"左上方"= `(-0.707, -0.707)`，对应角度 **-135°**。
 * 若按数学课本的习惯把左上写成 +135°，高光会跑到右下角
 * —— 而那是只有真机截图才能发现的错误（本阶段无真机，见 `STAGE11-PLAN.md §5`）。
 */
object NavBarLight {
    /**
     * 默认光源角（度）。
     *
     * -135° = 左上方。选左上而不是正上：
     * 正上方只能让左右两侧**对称**亮，那样边框看起来像"两头翘的管子"；
     * 斜向光才有"棱边被光扫过"的方向性，这也是 `STAGE7` 那版 45° 渐变当初的意图
     * （`FloatingNavBar.liquidGlassBorder` 的 KDoc：「玻璃的棱边高光来自单一光源」）。
     * 本阶段把那个**写死的 45°** 换成一个**有名字、可配置、两处共用**的角度。
     *
     * ## ★ 它是 domain 常量的引用，不是另一个字面量
     * 真值在 [com.rootflow.domain.glass.GlassParams.DEFAULT_LIGHT_ANGLE_DEGREES]，
     * 因为"光从哪来"是**玻璃的光学参数**，而 AGSL 的 `lightDirection` uniform
     * 必须与这里的渐变轴取同一个方向。两处各写一个 `-135f` 会漂移，
     * 而漂移的表现是"镜面亮在左上、边框却亮在右下"—— 只有真机截图能发现。
     */
    val DEFAULT_ANGLE_DEGREES: Float = GlassParams.DEFAULT_LIGHT_ANGLE_DEGREES

    /** 角度 → 单位方向向量。 */
    fun unitVector(angleDegrees: Float): Offset {
        val radians = angleDegrees * PI.toFloat() / 180f
        return Offset(cos(radians), sin(radians))
    }

    /**
     * 沿 [direction] 把 `width × height` 的矩形**投影出去**所需的半轴长。
     *
     * 推导：矩形中心到其沿方向最远的那个角的投影距离 =
     * `|w/2 · dx| + |h/2 · dy|`（矩形半宽/半高在方向上的分量之和）。
     * 用这个长度做渐变的一半轴，两端刚好覆盖整个矩形 —— 短了会在角落露出
     * 渐变的端点色，长了会把整段渐变压成一条几乎均匀的线（玻璃感消失）。
     *
     * 尺寸非正（首帧）时返回 0，调用方据此退化成"无渐变"而不是抛异常。
     */
    fun halfAxisLength(
        width: Float,
        height: Float,
        direction: Offset,
    ): Float {
        if (width <= 0f || height <= 0f) return 0f
        return abs(width * 0.5f * direction.x) + abs(height * 0.5f * direction.y)
    }

    /**
     * 渐变轴（起点 → 终点）：过矩形中心、沿 [direction] 的一条线段。
     *
     * 这就是"光从哪边来"在边框上的表达：起点在背光侧（暗），终点在迎光侧（亮）。
     * 与 `STAGE7` 那版写死的 `Offset.Zero → Offset(w, h)` 相比，本函数把
     * **"斜向"从一个巧合（矩形对角线恰好是 45° 只当 w == h）变成一个可解释的量**。
     */
    fun axis(
        width: Float,
        height: Float,
        direction: Offset,
    ): Axis {
        val center = Offset(width * 0.5f, height * 0.5f)
        val half = halfAxisLength(width, height, direction)
        return Axis(
            start = center - direction * half,
            end = center + direction * half,
        )
    }

    /** 渐变轴的两个端点（见 [axis]）。 */
    data class Axis(
        val start: Offset,
        val end: Offset,
    )
}
