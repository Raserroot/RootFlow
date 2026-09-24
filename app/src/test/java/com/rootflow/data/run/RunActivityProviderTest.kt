package com.rootflow.data.run

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `RunSessionRegistry` 的 `RunActivityProvider` 实现（阶段 6b）。
 *
 * ## 这份测试守的是**单调语义**（已批准的 6b 决策）
 * `latestRunId` 在运行结束后**不得**回到 `null`。若哪天有人"顺手"在 `unregister`
 * 里加了清空，主页的日志终端会在运行收尾的瞬间整屏变空
 * （管道同时回收了 state ⇒ `tail` 空、`observe` 空流），
 * 而那个缺陷只在真机上"刚好盯着屏幕看运行结束"时才复现。
 *
 * ## 为什么用 `runTest` 而不是普通函数
 * `_latestRunId` 的订阅者语义（立即拿到当前值、同值不重复发射）由 `StateFlow` 保证，
 * 但"订阅后能收到新值"这条要真跑协程才对得上。用 `runTest` + 直接读 `.value`
 * 保持与项目其余测试一致（`AGENT_PROTOCOL.md §9`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunActivityProviderTest {
    @Test
    @DisplayName("从未有运行时为 null")
    fun `starts as null`() {
        val registry = RunSessionRegistry()

        assertNull(registry.latestRunId.value, "还没有运行被受理时必须是 null")
    }

    @Test
    @DisplayName("register 之后可读到该 runId")
    fun `register publishes the run id`() {
        val registry = RunSessionRegistry()

        registry.register("run-1", scriptId = 7L)

        assertEquals("run-1", registry.latestRunId.value)
        assertEquals(1, registry.activeCount, "登记行为本身不变（熔断依赖它）")
    }

    @Test
    @DisplayName("unregister 之后仍然可读（单调：终端不得变空）")
    fun `unregister keeps the run id`() {
        val registry = RunSessionRegistry()
        registry.register("run-1", scriptId = 7L)

        registry.unregister("run-1")

        assertNotNull(
            registry.latestRunId.value,
            "运行结束不得清空 latestRunId —— 否则主页终端会在收尾瞬间变空（6b 已批准决策）",
        )
        assertEquals("run-1", registry.latestRunId.value)
        assertEquals(0, registry.activeCount, "在跑集合必须真的清空（与单调语义是两件事）")
    }

    @Test
    @DisplayName("第二次 register 覆盖为新的 runId")
    fun `a second run replaces the published id`() {
        val registry = RunSessionRegistry()
        registry.register("run-1", scriptId = 7L)
        registry.unregister("run-1")

        registry.register("run-2", scriptId = 7L)

        assertEquals("run-2", registry.latestRunId.value)
    }

    @Test
    @DisplayName("同 runId 重复 register 不产生额外发射（StateFlow 去重）")
    fun `re-registering the same id does not re-emit`() =
        runTest {
            val registry = RunSessionRegistry()
            val seen = mutableListOf<String?>()

            // 订阅者用 backgroundScope（§9.2：StateFlow 的 collect 永不返回；
            // 放前台会让 runTest 挂住）
            backgroundScope.launch {
                registry.latestRunId.collect { seen += it }
            }
            runCurrent()

            registry.register("run-1", scriptId = 1L)
            runCurrent()
            registry.register("run-1", scriptId = 1L)
            runCurrent()

            assertEquals(listOf(null, "run-1"), seen, "同值重复登记不得再发射一次")
        }

    // ---------------------------------------------------------------- P9：真实存活时长

    /**
     * 时长必须由 `register` / `unregister` **两个真实时刻**决定，且**按脚本**记。
     *
     * 这条守的是阶段 10 那个"`runMillis` 量不准"的缺陷：监工曾靠**轮询**测时长，
     * 短命脚本可能在两次轮询之间起止、一次都没被看见 ⇒ 被记成 `0ms` ⇒ 误判"快速崩"。
     */
    @Test
    @DisplayName("unregister 记下真实存活时长（监工不再靠轮询计时）")
    fun `unregister records the real duration`() =
        runTest {
            val registry = RunSessionRegistry()
            // 测试缝：把单调时钟接到虚拟时间上。
            // 不接的话 `delay` 推的是虚拟时间、`System.nanoTime` 走真实时间，
            // 量出来永远是"几微秒" —— **这个修复就测不出来了**。
            registry.monotonicNanos = { testScheduler.currentTime * NANOS_PER_MILLI }

            assertNull(
                registry.lastDurationMillis(7L),
                "从未运行过必须是 null 而不是 0 —— 0 会被监工读成「它活了 0ms」并判快速崩",
            )

            registry.register("run-1", scriptId = 7L)
            advanceTimeBy(250L)
            registry.unregister("run-1")

            assertEquals(
                250L,
                registry.lastDurationMillis(7L),
                "时长 = 两个真实时刻的差，与轮询相位、轮询间隔都无关",
            )
            assertEquals(0, registry.activeCount, "注销行为本身不变（熔断依赖它）")
        }

    @Test
    @DisplayName("时长按脚本记，多个脚本互不覆盖")
    fun `duration is tracked per script`() =
        runTest {
            val registry = RunSessionRegistry()
            registry.monotonicNanos = { testScheduler.currentTime * NANOS_PER_MILLI }

            registry.register("a", 1L)
            advanceTimeBy(100L)
            registry.unregister("a")
            registry.register("b", 2L)
            advanceTimeBy(400L)
            registry.unregister("b")

            assertEquals(100L, registry.lastDurationMillis(1L))
            assertEquals(400L, registry.lastDurationMillis(2L), "第二个脚本不得读到第一个的时长")
        }

    private companion object {
        /** 纳秒 → 毫秒（与 `RunSessionRegistry` 内部的换算一致）。 */
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
