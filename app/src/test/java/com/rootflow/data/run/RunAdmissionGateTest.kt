package com.rootflow.data.run

import com.rootflow.domain.event.RunAdmissionGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RunAdmissionGateImpl] 单测（阶段 3d，需求 §2.2 + 决策 C）。
 *
 * 覆盖两条约束（重入拒绝 / 全局上限 4）与释放语义。全部纯内存，无协程调度依赖
 * （`tryAcquire` / `release` 是同步的，见实现的 KDoc：刻意不用 `Mutex`，
 * 因为 `Job.invokeOnCompletion` 回调里无法安全地挂起）。
 */
class RunAdmissionGateTest {
    @Test
    fun `an idle gate accepts the first acquire`() {
        val gate = RunAdmissionGateImpl()

        val result = gate.tryAcquire(scriptId = 1L)

        assertEquals(RunAdmissionGate.AdmissionResult.Accepted, result)
        assertEquals(1, gate.activeCount)
    }

    @Test
    fun `a second acquire for the same script is a reentry rejection`() {
        val gate = RunAdmissionGateImpl()
        gate.tryAcquire(1L)

        val second = gate.tryAcquire(1L)

        assertEquals(
            RunAdmissionGate.AdmissionResult.ReentryRejected,
            second,
            "需求 §2.2：同一脚本不允许并发运行，直接拒绝（不是排队）",
        )
        assertEquals(1, gate.activeCount, "被拒绝的请求不得改变占用数")
    }

    @Test
    fun `the global limit is four concurrent runs`() {
        val gate = RunAdmissionGateImpl()

        val results = (1L..4L).map { gate.tryAcquire(it) }

        assertTrue(results.all { it == RunAdmissionGate.AdmissionResult.Accepted }, "前 4 个必须全部受理：$results")
        assertEquals(RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS, gate.activeCount)
        assertEquals(4, RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS, "需求 §2.2 的上限就是 4，不得悄悄改成别的数")
    }

    @Test
    fun `the fifth concurrent script hits the global limit`() {
        val gate = RunAdmissionGateImpl()
        (1L..4L).forEach { gate.tryAcquire(it) }

        val fifth = gate.tryAcquire(5L)

        assertEquals(RunAdmissionGate.AdmissionResult.GlobalLimitReached, fifth)
        assertEquals(4, gate.activeCount, "被上限拒绝的请求不得占用名额")
    }

    @Test
    fun `reentry takes precedence over the global limit`() {
        val gate = RunAdmissionGateImpl()
        (1L..4L).forEach { gate.tryAcquire(it) }

        // 1 号既在运行、又已满：必须报重入（调用方据此区分"这个脚本在跑"与"系统满了"）
        assertEquals(RunAdmissionGate.AdmissionResult.ReentryRejected, gate.tryAcquire(1L))
    }

    @Test
    fun `release frees the slot for another script`() {
        val gate = RunAdmissionGateImpl()
        (1L..4L).forEach { gate.tryAcquire(it) }

        gate.release(2L)

        assertEquals(3, gate.activeCount)
        assertEquals(RunAdmissionGate.AdmissionResult.Accepted, gate.tryAcquire(9L))
    }

    @Test
    fun `release lets the same script be admitted again`() {
        val gate = RunAdmissionGateImpl()
        gate.tryAcquire(1L)

        gate.release(1L)

        assertEquals(
            RunAdmissionGate.AdmissionResult.Accepted,
            gate.tryAcquire(1L),
            "未释放会让该脚本的重入拒绝永久生效——比不拒绝更糟",
        )
    }

    @Test
    fun `release is idempotent and safe for unknown scripts`() {
        val gate = RunAdmissionGateImpl()
        gate.tryAcquire(1L)

        gate.release(1L)
        gate.release(1L)
        gate.release(99L)

        assertEquals(0, gate.activeCount)
    }

    @Test
    fun `release of a non running script never drops another scripts slot`() {
        val gate = RunAdmissionGateImpl()
        gate.tryAcquire(1L)
        gate.tryAcquire(2L)

        gate.release(3L)

        assertEquals(2, gate.activeCount, "释放未占用的 id 不得影响已占用的名额")
    }

    @Test
    fun `isRunning reflects the current set`() =
        runTest {
            val gate = RunAdmissionGateImpl()
            assertFalse(gate.isRunning(1L))

            gate.tryAcquire(1L)
            assertTrue(gate.isRunning(1L), "TriggerDispatcher 依赖它做重入拒绝")

            gate.release(1L)
            assertFalse(gate.isRunning(1L))
        }

    @Test
    fun `concurrent acquires never exceed the limit`() =
        runTest {
            val gate = RunAdmissionGateImpl(maxConcurrentRuns = 4)

            // 20 个不同脚本并发抢 4 个名额：必须有且只有 4 个成功
            val results =
                (1L..20L)
                    .map { id -> async { gate.tryAcquire(id) } }
                    .awaitAll()

            val accepted = results.count { it == RunAdmissionGate.AdmissionResult.Accepted }
            val limited = results.count { it == RunAdmissionGate.AdmissionResult.GlobalLimitReached }
            assertEquals(4, accepted, "并发下也不得超限：$results")
            assertEquals(16, limited)
            assertEquals(4, gate.activeCount)
        }

    @Test
    fun `a custom limit is honoured`() {
        val gate = RunAdmissionGateImpl(maxConcurrentRuns = 1)

        assertEquals(RunAdmissionGate.AdmissionResult.Accepted, gate.tryAcquire(1L))
        assertEquals(RunAdmissionGate.AdmissionResult.GlobalLimitReached, gate.tryAcquire(2L))
    }

    @Test
    fun `activeScripts exposes the current set for diagnostics`() {
        val gate = RunAdmissionGateImpl()
        gate.tryAcquire(7L)
        gate.tryAcquire(8L)

        assertEquals(setOf(7L, 8L), gate.activeScripts())
    }

    @Test
    fun `the gate starts empty`() {
        // 决策 C：不持久化。进程重启后归零即可重新接纳（宁可放过，也不留下假状态）。
        val gate = RunAdmissionGateImpl()

        assertEquals(0, gate.activeCount)
        assertTrue(gate.activeScripts().isEmpty())
    }
}
