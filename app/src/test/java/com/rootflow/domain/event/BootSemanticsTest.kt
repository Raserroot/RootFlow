package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BootSemantics] 单测（阶段 3d，D9 的判定核心）。
 *
 * ## 为什么必须钉死 `elapsedRealtime` 的语义
 * 判定表建立在一个**反直觉**的事实上：`SystemClock.elapsedRealtime()` 在**重启时归零**，
 * 因此"标记大于当前读数"意味着"标记来自上一个开机周期"，而不是数据损坏。
 * 若把这条改成直觉版（"标记小于当前读数 ⇒ 新周期"），boot 补发会**永远不触发**——
 * 而失败表现是"开机自启静默失效"，真机上极难定位。故逐行钉死。
 */
class BootSemanticsTest {
    @Test
    fun `no marker means first start since boot`() {
        // 全新安装 / 用户清数据：必须补发（漏发会让"开机自启"静默失效）
        assertTrue(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 5_000L, bootId = null))
    }

    @Test
    fun `a marker from a previous boot cycle means first start since boot`() {
        // elapsed 在重启时归零 ⇒ 当前读数必然**小于**上一周期写入的标记
        assertTrue(
            BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 30_000L, bootId = 9_000_000L),
            "elapsed < bootId 只可能由重启造成",
        )
    }

    @Test
    fun `a marker from the same boot cycle means not the first start`() {
        // 同一次开机：第二次启动时 elapsed 必然更大
        assertFalse(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 600_000L, bootId = 30_000L))
    }

    @Test
    fun `exactly equal values are treated as the same cycle`() {
        // 边界：`<` 而非 `<=`。相等只可能出现在"标记就是本次写入"的情形（同周期），
        // 判 true 会在每次启动都补发一次 boot。
        assertFalse(
            BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 1_000L, bootId = 1_000L),
            "相等必须判同周期（否则每次启动都补发）",
        )
    }

    @Test
    fun `a zero marker is treated as a first start`() {
        // 0 是"写入未完成 / 内容被截断"的痕迹，不能当成有效标记（否则永远不补发）
        assertTrue(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 1L, bootId = 0L))
    }

    @Test
    fun `a negative marker is treated as a first start`() {
        // 负值不可能由真实 elapsedRealtime 产生 ⇒ 数据损坏。宁可多发一次也不漏发。
        assertTrue(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 1L, bootId = -1L))
        assertTrue(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 1L, bootId = Long.MIN_VALUE))
    }

    @Test
    fun `a fresh boot with a tiny elapsed is still a first start`() {
        // 刚开机几十秒：elapsed 很小，但标记来自上一周期（大值）→ 必须补发
        assertTrue(BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 45_000L, bootId = 86_400_000L))
    }

    @Test
    fun `the decision is a pure function of its two inputs`() {
        // 同一输入必须给出同一结论（真机日志里的 firstSinceBoot 与实际行为必须可对照）
        val first = BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 12L, bootId = 34L)
        val second = BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis = 12L, bootId = 34L)
        assertEquals(first, second)
    }

    @Test
    fun `the elapsed value domain is monotonic within a cycle and resets on reboot`() {
        // 把判定表的三种情形串成一次完整生命周期，确认没有互相矛盾的结论
        val startOfCycle1 = 20_000L
        val startOfCycle2 = 15_000L // 重启后 elapsed 归零再涨到 15s

        // 周期 1 的首次启动：无标记 → 补发，随后写标记 = startOfCycle1
        assertTrue(BootSemantics.isFirstStartSinceBoot(startOfCycle1, null))
        // 周期 1 的第二次启动（晚于标记）→ 不补发
        assertFalse(BootSemantics.isFirstStartSinceBoot(900_000L, startOfCycle1))
        // 重启：当前读数(15s) < 标记(20s) → 补发
        assertTrue(BootSemantics.isFirstStartSinceBoot(startOfCycle2, startOfCycle1))
        // 广播在启动路径之前认领了本周期（写标记 = startOfCycle2）→ 启动路径不再补发
        assertFalse(BootSemantics.isFirstStartSinceBoot(startOfCycle2 + 1_000L, startOfCycle2))
    }
}
