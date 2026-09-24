package com.rootflow.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [MainTabTransition] 的单测（阶段 8.2）。
 *
 * ## 为什么这个方向判定值得单测
 * "方向反了"是**只能在真机上肉眼看出来**的一类缺陷：它不崩、不报错、
 * 单元测试若只测"A→B 有动画"也照样绿。把方向抽成纯函数后，
 * 三 Tab 的全排列（含"非相邻跳跃"）可以在这里穷举钉死。
 *
 * ## 这里**不**测什么
 * 动画时长、曲线、以及"连续快速切换不叠加"——那些是 Compose 动画的运行时行为，
 * 纯 JVM 下测不了，由真机录像覆盖（**不得声称已单测**）。
 */
class MainTabTransitionTest {
    private val home = TabDestination.HOME
    private val scripts = TabDestination.SCRIPTS
    private val settings = TabDestination.SETTINGS

    @Test
    @DisplayName("★ 向右（主页→配置→设置）：新页从右侧进，方向 = +1")
    fun `moving right yields a positive direction`() {
        assertEquals(1, MainTabTransition.direction(current = home, target = scripts))
        assertEquals(1, MainTabTransition.direction(current = scripts, target = settings))
        // 非相邻跳跃也必须朝右（Home → Settings 跨两格）
        assertEquals(1, MainTabTransition.direction(current = home, target = settings))
    }

    @Test
    @DisplayName("★ 向左（设置→配置→主页）：新页从左侧进，方向 = -1")
    fun `moving left yields a negative direction`() {
        assertEquals(-1, MainTabTransition.direction(current = settings, target = scripts))
        assertEquals(-1, MainTabTransition.direction(current = scripts, target = home))
        assertEquals(-1, MainTabTransition.direction(current = settings, target = home))
    }

    @Test
    @DisplayName("同一个 Tab ⇒ 0（不产生方向，退化成瞬时）")
    fun `same tab yields zero`() {
        TabDestinations.ALL.forEach { tab ->
            assertEquals(0, MainTabTransition.direction(current = tab, target = tab))
        }
    }

    @Test
    @DisplayName("★ 穷举 3×3 全排列：符号与 Tab 顺序严格一致（这是本类的核心断言）")
    fun `every pair follows the tab order`() {
        val order = TabDestinations.ALL
        order.forEach { from ->
            order.forEach { to ->
                val expected = (order.indexOf(to) - order.indexOf(from)).coerceIn(-1, 1)
                assertEquals(
                    expected,
                    MainTabTransition.direction(current = from, target = to),
                    "方向与底栏顺序不一致：$from → $to",
                )
            }
        }
    }

    @Test
    @DisplayName("★ 用 ordinal 是不够的：方向必须以「底栏顺序表」为准")
    fun `direction follows the order list rather than the enum declaration`() {
        // 反转顺序表 ⇒ 所有方向取反。这条断言的存在意义是：
        // 若哪天有人把方向改成 `target.ordinal - current.ordinal`，它会红
        // （那时"枚举声明顺序与底栏顺序不一致"就会静默给错方向）。
        val reversed = TabDestinations.ALL.reversed()
        assertEquals(
            -1,
            MainTabTransition.direction(current = home, target = scripts, order = reversed),
        )
        assertEquals(
            1,
            MainTabTransition.direction(current = scripts, target = home, order = reversed),
        )
    }

    @Test
    @DisplayName("传进来的 Tab 不在顺序表里 ⇒ 0（不猜方向），不抛")
    fun `unknown tab degrades to no direction`() {
        assertEquals(0, MainTabTransition.direction(current = home, target = scripts, order = emptyList()))
        assertEquals(0, MainTabTransition.direction(current = home, target = scripts, order = listOf(settings)))
    }

    @Test
    @DisplayName("时长落在需求给的 250–300ms 区间内")
    fun `duration stays inside the requested window`() {
        assert(MainTabTransition.SLIDE_MILLIS in 250..300) {
            "过渡时长 ${MainTabTransition.SLIDE_MILLIS}ms 超出需求的 250–300ms"
        }
    }
}
