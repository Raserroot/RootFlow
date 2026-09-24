package com.rootflow.data.event

import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.SafeModeAlertSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 熔断告警的路由单测（阶段 5，需求 §5.2 第 4/5 步）。
 *
 * ## 为什么这条路由值得单独测
 * 它是"熔断了但用户毫无感知"这**唯一一种不可接受的失败形态**的防线
 * （`SafeModeNotifier` 的 KDoc 原文）。而它同时又是 Hilt 依赖环的高发处：
 * 一旦有人图省事把 `NotificationSafeModeNotifier` 改成直接依赖
 * `ForegroundServiceController`，本文件仍会绿（编译期就炸），因此这里额外钉死
 * "路由只经过 [SafeModeAlertSink] 端口"这件事的**行为**形态：
 * 假 sink 收不到就说明路由断了。
 */
class SafeModeAlertRoutingTest {
    private val sink = RecordingAlertSink()
    private val notifier = NotificationSafeModeNotifier(sink)

    @Test
    fun `trip reaches the sink with the exact reason`() {
        val reason = TripReason.ConsecutiveFailures(scriptId = 7, consecutive = 3)

        notifier.onTrip(reason)

        assertEquals(listOf(reason), sink.trips)
        assertTrue(sink.restores == 0)
    }

    @Test
    fun `restore reaches the sink`() {
        notifier.onRestore()

        assertEquals(1, sink.restores)
        assertTrue(sink.trips.isEmpty())
    }

    @Test
    fun `a failing sink never throws out of the notifier`() {
        // 告警发不出去是严重降级，但熔断的六步动作每步各自 runCatching：
        // 这里抛出会把"写 flag / 杀进程 / 禁触发器"的链条打断
        val exploding =
            object : SafeModeAlertSink {
                override fun onSafeModeAlert(reason: TripReason): Unit = throw IllegalStateException("boom")

                override fun onSafeModeRestore(): Unit = throw IllegalStateException("boom")
            }
        val safeNotifier = NotificationSafeModeNotifier(exploding)

        safeNotifier.onTrip(TripReason.Manual)
        safeNotifier.onRestore()
    }

    @Test
    fun `every trip reason is routable`() {
        // 六条触发条件 + bootloop + 外部安全模式都要能到通知层（漏一条就是一条静默的熔断）
        val reasons: List<TripReason> =
            listOf(
                TripReason.ScriptTimeout(scriptId = 1, timeoutMillis = 60_000),
                TripReason.ConsecutiveFailures(scriptId = 1, consecutive = 3),
                TripReason.FailureStorm(windowFailures = 20, windowMillis = 600_000),
                TripReason.RootUnresponsive(elapsedMillis = 10_000),
                TripReason.HighFrequencyStarts(scriptId = 1, startsInWindow = 20, windowMillis = 60_000),
                TripReason.Manual,
                TripReason.Bootloop(crashes = 3),
                TripReason.ExternalSafeMode(source = "persist.sys.safemode"),
            )

        reasons.forEach { notifier.onTrip(it) }

        assertEquals(reasons, sink.trips)
        assertEquals(TripReason.ALL_KEYS.toSet(), sink.trips.map { it.reasonKey }.toSet())
        assertFalse(sink.trips.any { it.detail.isBlank() }, "每条告警都要有可展示的 detail")
    }

    /** 记录型告警接收端（阶段 5 的最小假件）。 */
    private class RecordingAlertSink : SafeModeAlertSink {
        val trips: MutableList<TripReason> = mutableListOf()

        var restores: Int = 0
            private set

        override fun onSafeModeAlert(reason: TripReason) {
            trips += reason
        }

        override fun onSafeModeRestore() {
            restores++
        }
    }
}
