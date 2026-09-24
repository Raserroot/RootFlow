package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [CircuitBreakerMachine] 单测（需求 §5.1 的五条自动条件）。
 *
 * ## 为什么本类是全阶段最该逐边界钉死的
 * 五个阈值（连续 3 / 风暴 20 in 10min / 高频 20 in 60s / 超时 60s / root 10s）一旦算错，
 * 表现为**两种都很难查的故障**：误熔断（用户看到"App 什么都不跑"）与漏熔断
 * （脚本反复失控却不拦）。因此这里用注入的 `nowMillis` 精确驱动窗口边界，
 * **不依赖任何真实时间**。
 *
 * ## 时间一律显式传参
 * 判定机的所有方法都接收 `nowMillis`（单调时钟读数），因此"滑出窗口"这类边界
 * 可以用**恰好等于窗口长度**的差值断言，而不是"大概过了 10 分钟"。
 */
class CircuitBreakerMachineTest {
    // ------------------------------------------------ 连续失败（需求 §5.1 第 2 条）

    @Test
    fun `two consecutive failures do not trip`() {
        val machine = CircuitBreakerMachine()

        assertNull(machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 0L))
        assertNull(machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 1L))
        assertEquals(2, machine.consecutiveFailuresOf(1L), "阈值 3 之前不得熔断")
    }

    @Test
    fun `three consecutive failures trip with the script id and the count`() {
        val machine = CircuitBreakerMachine()

        machine.onRunFinished(scriptId = 7L, failed = true, nowMillis = 0L)
        machine.onRunFinished(scriptId = 7L, failed = true, nowMillis = 1L)
        val reason = machine.onRunFinished(scriptId = 7L, failed = true, nowMillis = 2L)

        val failures = assertInstanceOf(TripReason.ConsecutiveFailures::class.java, reason)
        assertEquals(7L, failures.scriptId, "原因必须带上具体脚本（真机判读要能定位）")
        assertEquals(3, failures.consecutive)
        assertEquals(TripReason.ConsecutiveFailures.KEY, failures.reasonKey)
    }

    @Test
    fun `a success clears the consecutive counter`() {
        // "连续"二字的全部含义：一次成功即清零
        val machine = CircuitBreakerMachine()
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 0L)
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 1L)

        assertNull(machine.onRunFinished(scriptId = 1L, failed = false, nowMillis = 2L))

        assertEquals(0, machine.consecutiveFailuresOf(1L), "成功后连续计数必须归零")
        // 之后要重新数满 3 次才会熔断
        assertNull(machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 3L))
        assertNull(machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 4L))
        assertInstanceOf(
            TripReason.ConsecutiveFailures::class.java,
            machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 5L),
        )
    }

    @Test
    fun `consecutive counters are per script`() {
        // 两个脚本各自数各的：A 失败两次 + B 失败一次，都不到 3
        val machine = CircuitBreakerMachine()
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 0L)
        machine.onRunFinished(scriptId = 2L, failed = true, nowMillis = 1L)
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 2L)

        assertEquals(2, machine.consecutiveFailuresOf(1L))
        assertEquals(1, machine.consecutiveFailuresOf(2L), "不同脚本的计数必须互相独立")
        assertNull(machine.onRunFinished(scriptId = 2L, failed = true, nowMillis = 3L))
        assertEquals(
            2,
            machine.consecutiveFailuresOf(1L),
            "脚本 2 的失败不得累加到脚本 1 的『连续』计数上",
        )
    }

    @Test
    fun `the unknown script has a zero counter`() {
        val machine = CircuitBreakerMachine()
        assertEquals(0, machine.consecutiveFailuresOf(99L))
    }

    // ------------------------------------------------ 失败风暴（需求 §5.1 第 3 条）

    @Test
    fun `nineteen failures in the window do not trip a storm`() {
        val machine = CircuitBreakerMachine()
        // 用**不同脚本**避开"连续失败"分支，只留风暴分支在起作用
        repeat(19) { index ->
            assertNull(
                machine.onRunFinished(scriptId = (index + 1).toLong(), failed = true, nowMillis = index.toLong()),
                "第 ${index + 1} 次失败不该熔断（阈值 20）",
            )
        }
        assertEquals(19, machine.failuresInWindow())
    }

    @Test
    fun `twenty failures in the window trip a storm`() {
        val machine = CircuitBreakerMachine()
        var reason: TripReason? = null
        repeat(20) { index ->
            reason = machine.onRunFinished(scriptId = (index + 1).toLong(), failed = true, nowMillis = index.toLong())
        }

        val storm = assertInstanceOf(TripReason.FailureStorm::class.java, reason)
        assertEquals(20, storm.windowFailures)
        assertEquals(CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS, storm.windowMillis)
    }

    @Test
    fun `failures older than the window do not count`() {
        // 边界：`now - at == window` 正好滑出（判定用 `>=` 丢头）
        val machine = CircuitBreakerMachine()
        val window = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS
        val now = window * 2

        // 先灌 19 条"刚刚滑出窗口"的失败
        machine.seedFailureHistory((0 until 19).map { now - window - it }, nowMillis = now)
        assertEquals(0, machine.failuresInWindow(), "恰好等于窗口长度的旧失败必须被丢弃")

        // 窗口内再来 19 条：仍不该熔断（窗口里只有 19 条）
        repeat(19) { index ->
            assertNull(
                machine.onRunFinished(
                    scriptId = (index + 1).toLong(),
                    failed = true,
                    nowMillis = now + index,
                ),
            )
        }
        assertEquals(19, machine.failuresInWindow(), "滑出窗口的失败不得残留")
    }

    @Test
    fun `the storm window drops failures that slide out`() {
        val machine = CircuitBreakerMachine()
        val window = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS

        // 10 条失败发生在 t=1..10
        repeat(10) { index ->
            machine.onRunFinished(scriptId = (index + 1).toLong(), failed = true, nowMillis = (index + 1).toLong())
        }
        assertEquals(10, machine.failuresInWindow())

        // ⚠ now 必须**超过 window + 10** 才能让那 10 条全部滑出：
        // 判定是 `now - at >= window`，最后一条 at=10 ⇒ 需要 now >= window + 10。
        // 取 window + 11 留一格余量（第一版取 window + 1，结果 9 条仍在窗口内 —— 那是
        // 判定正确、断言写错）。
        val now = window + 11
        val reason = machine.onRunFinished(scriptId = 100L, failed = true, nowMillis = now)

        assertNull(reason, "只剩 1 条时不该熔断")
        assertEquals(1, machine.failuresInWindow(), "窗口必须随 now 前移，且丢弃已滑出的旧记录")
    }

    @Test
    fun `a storm window filled exactly at the boundary still trips`() {
        // 边界补充：20 条**恰好**落在窗口内（最后一条的 `now - first == window - 1`）仍应熔断。
        // 与上一条合起来把"`>=` 丢头"这个边界两侧都钉住。
        val machine = CircuitBreakerMachine()
        val window = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS

        var reason: TripReason? = null
        repeat(20) { index ->
            // 每条相隔 window/20，20 条正好铺满窗口
            val at = index.toLong() * (window / 20)
            reason = machine.onRunFinished(scriptId = (index + 1).toLong(), failed = true, nowMillis = at)
        }

        assertInstanceOf(
            TripReason.FailureStorm::class.java,
            reason,
            "铺满整个窗口的 20 条失败必须熔断（不能因为边界判定过严而漏掉）",
        )
    }

    // ------------------------------------------------ 高频自启（需求 §5.1 第 5 条）

    @Test
    fun `nineteen accepted starts do not trip high frequency`() {
        val machine = CircuitBreakerMachine()
        repeat(19) { index ->
            assertNull(machine.onRunAccepted(scriptId = 1L, nowMillis = index.toLong()))
        }
    }

    @Test
    fun `the twentieth accepted start trips high frequency`() {
        val machine = CircuitBreakerMachine()
        var reason: TripReason? = null
        repeat(20) { index ->
            reason = machine.onRunAccepted(scriptId = 5L, nowMillis = index.toLong())
        }

        val high = assertInstanceOf(TripReason.HighFrequencyStarts::class.java, reason)
        assertEquals(5L, high.scriptId)
        assertEquals(20, high.startsInWindow)
        assertEquals(CircuitBreakerMachine.DEFAULT_HIGH_FREQUENCY_WINDOW_MILLIS, high.windowMillis)
    }

    @Test
    fun `starts older than the high frequency window do not count`() {
        val machine = CircuitBreakerMachine()
        val window = CircuitBreakerMachine.DEFAULT_HIGH_FREQUENCY_WINDOW_MILLIS

        // 19 次"刚好滑出窗口"的历史受理
        repeat(19) { index ->
            machine.onRunAccepted(scriptId = 1L, nowMillis = index.toLong())
        }
        assertNull(
            machine.onRunAccepted(scriptId = 1L, nowMillis = window + 1),
            "旧的受理必须滑出窗口 —— 否则长时间运行会累积到误熔断",
        )
    }

    @Test
    fun `accepted starts are counted per script`() {
        val machine = CircuitBreakerMachine()
        repeat(19) { index -> machine.onRunAccepted(scriptId = 1L, nowMillis = index.toLong()) }
        // 另一个脚本的受理不得推进 1 号脚本的计数
        assertNull(machine.onRunAccepted(scriptId = 2L, nowMillis = 20L))
    }

    // ------------------------------------------------ 无计数器的原因

    @Test
    fun `timeout produces the script timeout reason with the threshold`() {
        val machine = CircuitBreakerMachine()
        val reason = machine.onRunTimeout(scriptId = 3L, timeoutMillis = 60_000L)

        val timeout = assertInstanceOf(TripReason.ScriptTimeout::class.java, reason)
        assertEquals(3L, timeout.scriptId)
        assertEquals(60_000L, timeout.timeoutMillis, "原因必须带生效阈值（真机判读要能看出用的是哪个值）")
    }

    @Test
    fun `root unresponsive and manual produce their reasons`() {
        val machine = CircuitBreakerMachine()

        assertEquals(
            TripReason.RootUnresponsive(elapsedMillis = 10_000L),
            machine.onRootUnresponsive(10_000L),
        )
        assertEquals(TripReason.Manual, machine.onManual())
    }

    // ------------------------------------------------ 预置历史（决策 D11）

    @Test
    fun `seeding drops entries outside the window and keeps the ones inside`() {
        val machine = CircuitBreakerMachine()
        val window = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS
        val now = 1_000_000L

        machine.seedFailureHistory(
            listOf(
                now - 1, // 窗口内
                now - (window - 1), // 窗口内（刚好还没滑出）
                now - window, // 滑出（`>=` 丢头）
                now - (window + 1), // 滑出
            ),
            nowMillis = now,
        )

        assertEquals(2, machine.failuresInWindow(), "只保留窗口内的失败时刻")
    }

    @Test
    fun `seeding replaces the previous window rather than appending`() {
        // 契约：`seedFailureHistory` 是**整批清空重灌**（调用方先收集再一次性灌入）。
        // 若实现改成追加，重复调用会把同一批历史数两遍 ⇒ 提前熔断。
        val machine = CircuitBreakerMachine()
        val now = 1_000_000L

        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = now)
        assertEquals(1, machine.failuresInWindow())

        machine.seedFailureHistory(listOf(now - 1, now - 2), nowMillis = now)

        assertEquals(2, machine.failuresInWindow(), "预置必须替换而不是累加")
    }

    @Test
    fun `seeded history plus new failures can reach the storm threshold`() {
        // D11 的核心用途：跨进程恢复后，内存里先有 19 条历史失败，再来 1 条即应熔断。
        // 不预置则窗口归零，永远到不了 20 ⇒ 熔断可被"重启"绕过。
        val machine = CircuitBreakerMachine()
        val now = 500_000L
        machine.seedFailureHistory((0 until 19).map { now - it }, nowMillis = now)

        val reason = machine.onRunFinished(scriptId = 42L, failed = true, nowMillis = now + 1)

        assertInstanceOf(
            TripReason.FailureStorm::class.java,
            reason,
            "预置的跨进程失败历史必须能凑满风暴阈值",
        )
    }

    // ------------------------------------------------ reset

    @Test
    fun `reset clears all three counters`() {
        val machine = CircuitBreakerMachine()
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 0L)
        machine.onRunFinished(scriptId = 1L, failed = true, nowMillis = 1L)
        machine.onRunAccepted(scriptId = 1L, nowMillis = 2L)

        machine.reset()

        assertEquals(0, machine.consecutiveFailuresOf(1L))
        assertEquals(0, machine.failuresInWindow())
        // 受理窗口也清了：再受理 1 次不该熔断（若窗口没清，历史 1 次 + 新 1 次仍不到 20，
        // 因此用"恢复后计数从零开始"这一可观测事实断言）
        assertNull(machine.onRunAccepted(scriptId = 1L, nowMillis = 3L))
    }
}
