package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [DaemonRestartPolicy] 的判定边界（阶段 10）。
 *
 * ## 为什么这些边界值得逐条钉死
 * 它一旦出错，表现是"紧密重启把 CPU 打满"或"一个坏脚本把服务拖死" ——
 * 两种都只在真机上、且要等一会儿才看得出来。纯逻辑单测能在毫秒级覆盖全部边界。
 */
class DaemonRestartPolicyTest {
    private val policy = DaemonRestartPolicy.DEFAULT

    @Test
    @DisplayName("默认值：首次退避 1 秒、上限 30 秒、快速崩阈值 5 秒、上限 5 次")
    fun defaults() {
        assertEquals(1_000L, DaemonRestartPolicy.DEFAULT_BASE_DELAY_MILLIS)
        assertEquals(30_000L, DaemonRestartPolicy.DEFAULT_MAX_DELAY_MILLIS)
        assertEquals(5_000L, DaemonRestartPolicy.DEFAULT_RAPID_EXIT_THRESHOLD_MILLIS)
        assertEquals(5, DaemonRestartPolicy.DEFAULT_MAX_RAPID_CRASHES)
    }

    @Test
    @DisplayName("存活时长达到阈值 ⇒ 算稳住（调用方据此把连续快速崩计数清零）")
    fun stableWhenSurvivesThreshold() {
        assertFalse(policy.isStable(runMillis = 4_999L), "差 1ms 仍算快速崩")
        assertTrue(policy.isStable(runMillis = 5_000L), "刚好到阈值即算稳住")
        assertTrue(policy.isStable(runMillis = 60_000L))
    }

    @Test
    @DisplayName("退避是指数增长且每一步都封顶（不溢出、不会等到天荒地老）")
    fun backoffGrowsAndCaps() {
        // 第 1/2/3/4 次快速崩：1s → 2s → 4s → 8s
        assertEquals(1_000L, policy.nextDelayMillis(rapidCrashesSoFar = 1))
        assertEquals(2_000L, policy.nextDelayMillis(rapidCrashesSoFar = 2))
        assertEquals(4_000L, policy.nextDelayMillis(rapidCrashesSoFar = 3))
        assertEquals(8_000L, policy.nextDelayMillis(rapidCrashesSoFar = 4))
    }

    @Test
    @DisplayName("达到 maxRapidCrashes ⇒ 返回 null（放弃重启，不再增长）")
    fun givesUpAtLimit() {
        assertNull(policy.nextDelayMillis(rapidCrashesSoFar = 5), "第 5 次就该放弃")
        assertNull(policy.nextDelayMillis(rapidCrashesSoFar = 6))
        assertTrue(policy.shouldGiveUp(rapidCrashesSoFar = 5))
        assertFalse(policy.shouldGiveUp(rapidCrashesSoFar = 4))
    }

    @Test
    @DisplayName("封顶：把上限压到很低时，退避不得越过它（也不会变成负数）")
    fun capsAtMaxDelay() {
        val tight =
            DaemonRestartPolicy(
                baseDelayMillis = 100L,
                maxDelayMillis = 300L,
                rapidExitThresholdMillis = 10L,
                maxRapidCrashes = 20,
            )
        assertEquals(100L, tight.nextDelayMillis(1))
        assertEquals(200L, tight.nextDelayMillis(2))
        assertEquals(300L, tight.nextDelayMillis(3), "2×200 越过上限 ⇒ 收到 300")
        assertEquals(300L, tight.nextDelayMillis(4))
        // 大指数下不得因乘法溢出变成负数（负数延迟 = 紧密重启）
        assertEquals(300L, tight.nextDelayMillis(19))
    }

    @Test
    @DisplayName("构造期校验：baseDelay 必须为正（0 会退化成紧密重启）")
    fun rejectsZeroBaseDelay() {
        assertThrows(IllegalArgumentException::class.java) {
            DaemonRestartPolicy(baseDelayMillis = 0L)
        }
    }

    @Test
    @DisplayName("构造期校验：上限不得小于 baseDelay")
    fun rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            DaemonRestartPolicy(baseDelayMillis = 5_000L, maxDelayMillis = 1_000L)
        }
    }

    @Test
    @DisplayName("构造期校验：maxRapidCrashes 至少为 1（0 会让它一次都不重试）")
    fun rejectsZeroMaxCrashes() {
        assertThrows(IllegalArgumentException::class.java) {
            DaemonRestartPolicy(maxRapidCrashes = 0)
        }
    }

    @Test
    @DisplayName("宽限期为 0 时：任何退出都算快速崩（这是可配置的，不是默认）")
    fun zeroThresholdCountsEverythingAsRapid() {
        val strict = DaemonRestartPolicy(rapidExitThresholdMillis = 0L)
        assertTrue(strict.isStable(runMillis = 0L), "阈值 0 ⇒ 0ms 也算达到阈值")
    }
}
