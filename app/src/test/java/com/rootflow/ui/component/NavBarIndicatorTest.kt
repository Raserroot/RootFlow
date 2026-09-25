package com.rootflow.ui.component

import androidx.compose.ui.geometry.Offset
import com.rootflow.ui.theme.NavBarLight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * [NavBarIndicator] / [NavBarBadge] 的纯逻辑单测（`STAGE11-PLAN.md §7` 第 1 步）。
 *
 * ## 为什么这个类值得写（不是"为覆盖率"）
 * 阶段 11 的交付物里，**这是唯一能被沙箱完全验证的部分**：
 * AGSL 的折射与菲涅尔、弹簧的回弹手感、拖拽的跟手程度，全部只有真机能验
 * （`STAGE11-PLAN.md §5` 的诚实声明）。把几何与规则抽成纯对象并在这里穷举，
 * 是为了让"只能靠真机的那部分"尽可能小 —— 与 `STAGE7` 把 [com.rootflow.ui.theme.NavBarLight]
 * 之外的判定抽成 `GlassPolicy` 是同一手法。
 */
class NavBarIndicatorTest {
    private val eps = 1e-4f

    @Test
    @DisplayName("等宽布局：第 i 项中心 = itemWidth * (i + 0.5)")
    fun `center of an item`() {
        assertEquals(180f, NavBarIndicator.centerX(index = 0, itemWidth = 360f), eps)
        assertEquals(540f, NavBarIndicator.centerX(index = 1, itemWidth = 360f), eps)
        assertEquals(900f, NavBarIndicator.centerX(index = 2, itemWidth = 360f), eps)
    }

    @Test
    @DisplayName("拖拽吸附：边界处归属**左**项（floor 语义），且两端 clamp")
    fun `nearest index snaps and clamps`() {
        val w = 360f
        val n = 3

        assertEquals(0, NavBarIndicator.nearestIndex(x = 0f, itemWidth = w, count = n))
        assertEquals(0, NavBarIndicator.nearestIndex(x = 359.9f, itemWidth = w, count = n), "刚过中线仍算左项")
        assertEquals(1, NavBarIndicator.nearestIndex(x = 360f, itemWidth = w, count = n), "整好落在边界归右项")
        assertEquals(2, NavBarIndicator.nearestIndex(x = 1080f, itemWidth = w, count = n))
    }

    @Test
    @DisplayName("拖拽吸附：拖出左右边界都 clamp（不得越界取下标）")
    fun `nearest index clamps when dragged outside`() {
        assertEquals(0, NavBarIndicator.nearestIndex(x = -5000f, itemWidth = 360f, count = 3))
        assertEquals(2, NavBarIndicator.nearestIndex(x = 99999f, itemWidth = 360f, count = 3))
    }

    @Test
    @DisplayName("拖拽吸附：退化输入（count 0 / itemWidth 0）回 0，不抛")
    fun `nearest index survives degenerate input`() {
        assertEquals(0, NavBarIndicator.nearestIndex(x = 500f, itemWidth = 360f, count = 0))
        assertEquals(0, NavBarIndicator.nearestIndex(x = 500f, itemWidth = 0f, count = 3))
        assertEquals(0, NavBarIndicator.nearestIndex(x = Float.NaN, itemWidth = 360f, count = 3), "NaN 不得变成越界下标")
    }

    @Test
    @DisplayName("速度 → 拉伸量：满量程 1、越界 clamp、方向无关")
    fun `normalized stretch is direction agnostic and clamped`() {
        assertEquals(0f, NavBarIndicator.normalizedStretch(0f), eps)
        assertEquals(0.5f, NavBarIndicator.normalizedStretch(300f), eps)
        assertEquals(1f, NavBarIndicator.normalizedStretch(600f), eps)
        assertEquals(1f, NavBarIndicator.normalizedStretch(99999f), eps, "超过满量程不再放大")
        assertEquals(1f, NavBarIndicator.normalizedStretch(-600f), eps, "向左甩同样被拉长")
    }

    @Test
    @DisplayName("★ 速度 → 拉伸量：NaN / Infinity 必须归零（否则会一路进 scaleX 让指示器消失）")
    fun `normalized stretch neutralizes non finite velocity`() {
        assertEquals(0f, NavBarIndicator.normalizedStretch(Float.NaN), eps)
        assertEquals(0f, NavBarIndicator.normalizedStretch(Float.POSITIVE_INFINITY), eps)
        assertEquals(0f, NavBarIndicator.normalizedStretch(Float.NEGATIVE_INFINITY), eps)
    }

    @Test
    @DisplayName("形变倍率：静止恒等；满量程时宽度拉伸、高度反向压缩")
    fun `scale factors stretch width and squash height`() {
        assertEquals(1f, NavBarIndicator.widthScale(0f), eps)
        assertEquals(1f, NavBarIndicator.heightScale(0f), eps, "静止必须是恒等变换，否则指示器尺寸会漂移")

        assertEquals(1f + NavBarIndicator.MAX_STRETCH, NavBarIndicator.widthScale(1f), eps)
        assertEquals(
            1f - NavBarIndicator.MAX_STRETCH * NavBarIndicator.SQUASH_RATIO,
            NavBarIndicator.heightScale(1f),
            eps,
        )

        assertTrue(
            NavBarIndicator.heightScale(1f) < 1f,
            "压缩方向必须是减小的（负号写反会让形变看起来像整体放大）",
        )
    }

    @Test
    @DisplayName("形变倍率：越界与 NaN 的拉伸量都收敛到恒等")
    fun `scale factors sanitize their input`() {
        assertEquals(NavBarIndicator.widthScale(1f), NavBarIndicator.widthScale(9f), eps)
        assertEquals(NavBarIndicator.widthScale(0f), NavBarIndicator.widthScale(-9f), eps)
        assertEquals(NavBarIndicator.widthScale(0f), NavBarIndicator.widthScale(Float.NaN), eps)
        assertEquals(NavBarIndicator.heightScale(0f), NavBarIndicator.heightScale(Float.NaN), eps)
    }
}

/**
 * [NavBarLight] 的单测（`STAGE11-PLAN.md §1.4`：边框渐变轴的几何）。
 *
 * 这一项在阶段 11 里地位特殊：它是**光学增强中唯一可被纯 JVM 验证的部分**
 * —— Fresnel 与 vibrancy 都活在 AGSL 里，只有"光从哪个方向来、渐变画到哪"
 * 是 Kotlin 侧的算术。
 */
class NavBarLightTest {
    private val eps = 1e-3f

    @Test
    @DisplayName("★ 默认光源在左上（屏幕坐标 y 向下 ⇒ 负 y 才是上方）")
    fun `default light comes from the top left`() {
        val dir = NavBarLight.unitVector(NavBarLight.DEFAULT_ANGLE_DEGREES)

        assertTrue(dir.x < 0f, "x 分量为负才是左侧")
        assertTrue(dir.y < 0f, "y 分量为负才是上方（屏幕坐标 y 向下，写成正号高光会跑到右下角）")
        assertEquals(-0.7071f, dir.x, 1e-3f)
        assertEquals(-0.7071f, dir.y, 1e-3f)
    }

    @Test
    @DisplayName("角度 → 单位向量恒为单位长度（渐变轴的长度依赖它）")
    fun `unit vector always has unit length`() {
        listOf(-135f, -90f, 0f, 45f, 90f, 180f, 359f).forEach { angle ->
            val length = NavBarLight.unitVector(angle).getDistance()
            assertTrue(abs(length - 1f) < 1e-3f, "$angle° 的方向向量必须归一（实际 $length）")
        }
    }

    @Test
    @DisplayName("半轴长 = 矩形在光向上的投影（两端刚好覆盖，不多不少）")
    fun `half axis covers the rectangle projection`() {
        // 300x100、光向 -45°（右上）⇒ |150·0.70711| + |50·0.70711| = 141.4214
        // 期望值写足位数、容差放到 1e-2（相对误差 7e-5）：写成 141.42f 会被 0.0014 的
        // 真实差异判失败 —— 那不是常量算错，是期望值的有效位不够。
        val dir = NavBarLight.unitVector(-45f)
        assertEquals(141.4214f, NavBarLight.halfAxisLength(300f, 100f, dir), 1e-2f)
    }

    @Test
    @DisplayName("★ 渐变轴：过中心、沿光向、两端对称（背光侧 → 迎光侧）")
    fun `gradient axis is centered and aligned with the light`() {
        val dir = NavBarLight.unitVector(NavBarLight.DEFAULT_ANGLE_DEGREES)
        val axis = NavBarLight.axis(width = 1000f, height = 100f, direction = dir)

        // 中心 = 两端中点
        assertEquals(500f, (axis.start.x + axis.end.x) / 2f, eps)
        assertEquals(50f, (axis.start.y + axis.end.y) / 2f, eps)

        // 终点在光来的那一侧（左上 ⇒ 起点在右下）
        assertTrue(axis.end.x < axis.start.x, "终点必须在左侧")
        assertTrue(axis.end.y < axis.start.y, "终点必须在上方")

        // 轴长为半轴的两倍
        assertEquals(
            NavBarLight.halfAxisLength(1000f, 100f, dir) * 2f,
            (axis.end - axis.start).getDistance(),
            eps,
        )
    }

    @Test
    @DisplayName("尺寸非正（首帧）时半轴为 0，轴退化成一点而不是负数")
    fun `degenerate size yields a zero length axis`() {
        val dir = NavBarLight.unitVector(NavBarLight.DEFAULT_ANGLE_DEGREES)
        assertEquals(0f, NavBarLight.halfAxisLength(0f, 100f, dir), eps)
        assertEquals(0f, NavBarLight.halfAxisLength(100f, 0f, dir), eps)
        assertEquals(0f, NavBarLight.halfAxisLength(-10f, -10f, dir), eps)

        val degenerate = NavBarLight.axis(0f, 0f, dir)
        assertEquals(degenerate.start, degenerate.end, "退化时不得出现反向轴（会让渐变反转）")
    }

    @Test
    @DisplayName("水平光 ⇒ 轴完全水平（y 不参与），这是「光向可解释」的最小证据")
    fun `horizontal light produces a horizontal axis`() {
        val axis = NavBarLight.axis(width = 200f, height = 80f, direction = Offset(1f, 0f))
        assertEquals(axis.start.y, axis.end.y, eps)
        assertEquals(0f, axis.start.x, eps)
        assertEquals(200f, axis.end.x, eps)
    }
}

/**
 * [NavBarBadge] 的显示规则单测（`STAGE11-PLAN.md §3.1`）。
 */
class NavBarBadgeTest {
    @Test
    @DisplayName("null / 0 / 负数 ⇒ 不显示")
    fun `hidden for non positive counts`() {
        assertNull(NavBarBadge.text(null))
        assertNull(NavBarBadge.text(0))
        assertNull(NavBarBadge.text(-1))
        assertNull(NavBarBadge.text(Int.MIN_VALUE))
    }

    @Test
    @DisplayName("1..99 原样显示，边界两端都在")
    fun `shows plain numbers up to the cap`() {
        assertEquals("1", NavBarBadge.text(1))
        assertEquals("42", NavBarBadge.text(42))
        assertEquals("99", NavBarBadge.text(NavBarBadge.MAX_DISPLAYED))
    }

    @Test
    @DisplayName("★ 超过上限统一写成 99+，Int.MAX_VALUE 也不回绕")
    fun `caps above the limit`() {
        assertEquals("99+", NavBarBadge.text(100))
        assertEquals("99+", NavBarBadge.text(12345))
        assertEquals("99+", NavBarBadge.text(Int.MAX_VALUE), "极值必须走同一条分支，不得 +1 回绕成负数")
    }
}
