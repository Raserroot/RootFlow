package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.TripReason
import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RootHealthProbeImpl] 单测（需求 §5.1 第 4 条 + §5.3 第 2 条）。
 *
 * ## 探针的正确性判据是"回显比对"，不是"没超时"
 * `exec` 返回 0 但输出为空同样说明通道不可用；把那种情形判成"健康"会让
 * Root 已经挂掉的设备继续被派活。因此三条用例分别覆盖：
 * 回显正确 ⇒ 健康 · 退出码非 0 ⇒ 不健康 · 外层超时 ⇒ 不健康。
 *
 * ## 外部安全模式探针：**失败即未命中**
 * 通道不可用时必须返回 `null`（不是"检测到安全模式"）：
 * 把不可用误判成安全模式会让 App **永远不跑脚本**，那是比漏检更糟的失败方向。
 *
 * ## 用虚拟时间驱动超时
 * `probe()` 内部是 `withTimeoutOrNull` + `exec(timeoutMillis)`，两者都吃协程时钟，
 * 因此 `runTest` 的虚拟时间足以覆盖超时分支——**不需要真的等 10 秒**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootHealthProbeTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @Test
    fun `a correct echo means responsive`() =
        runTest {
            val probe = RootHealthProbeImpl(managerReturning(ok(RootHealthProbeImpl.PROBE_TOKEN)))

            val snapshot = probe.probe()

            assertTrue(snapshot.responsive, "回显含 token ⇒ 通道健康")
            assertNull(snapshot.detail, "健康时不该带失败原因")
        }

    @Test
    fun `a non-zero exit code means unresponsive even with output`() =
        runTest {
            // 关键：不能只看"有没有输出"。退出码非 0 说明命令没真的跑到。
            val probe =
                RootHealthProbeImpl(
                    managerReturning(
                        ShellResult(stdout = RootHealthProbeImpl.PROBE_TOKEN, stderr = "denied", exitCode = 1),
                    ),
                )

            val snapshot = probe.probe()

            assertFalse(snapshot.responsive, "exitCode!=0 必须判为不响应")
            assertTrue(
                snapshot.detail?.contains("exitCode=1") == true,
                "失败原因必须可判读（AGENT_PROTOCOL.md §8.4：诊断日志要带 exitCode/stdout/stderr）：${snapshot.detail}",
            )
        }

    @Test
    fun `an empty echo means unresponsive`() =
        runTest {
            val probe = RootHealthProbeImpl(managerReturning(ok("")))

            val snapshot = probe.probe()

            assertFalse(snapshot.responsive, "命令成功但无回显 ⇒ 通道不可用")
        }

    @Test
    fun `a hanging exec is bounded by the outer timeout`() =
        runTest {
            // 10s 阈值由常量给出；用虚拟时间推进即可覆盖，不真等
            val manager = mockk<RootShellManager>()
            coEvery { manager.exec(any(), any()) } coAnswers {
                delay(RootHealthProbeImpl.OUTER_TIMEOUT_MILLIS * 4)
                ok(RootHealthProbeImpl.PROBE_TOKEN)
            }
            val probe = RootHealthProbeImpl(manager)

            val snapshot = probe.probe()

            assertFalse(snapshot.responsive, "超过阈值未返回 ⇒ 不响应")
            assertTrue(
                snapshot.detail?.contains("no response within") == true,
                "失败原因必须写明是超时：${snapshot.detail}",
            )
            // ⚠ 不断言 `elapsedMillis` 的**具体值**：实现用墙钟
            // （`System.currentTimeMillis`）度量耗时，而本用例跑在虚拟时间下，
            // 墙钟几乎不前进 ⇒ 断言"等于阈值"只会得到 0（第一版就踩了这个坑）。
            // 超时本身已由 `responsive=false` 与 detail 覆盖。
        }

    // ------------------------------------------------ 外部安全模式

    @Test
    fun `a propagating system property is detected`() =
        runTest {
            val probe =
                RootHealthProbeImpl(
                    managerControlling(
                        "1\nrf_probe_done\n",
                    ),
                )

            val reason = probe.detectExternalSafeMode()

            val external = assertInstanceOf(TripReason.ExternalSafeMode::class.java, reason)
            org.junit.jupiter.api.Assertions.assertEquals(
                RootHealthProbeImpl.SYSTEM_PROPERTY,
                external.source,
                "成因必须写明来源（真机要能区分系统属性与 Magisk 标志）",
            )
        }

    @Test
    fun `a magisk safemode marker is detected with its own source`() =
        runTest {
            val probe = RootHealthProbeImpl(managerControlling("/data/adb/magisk/safemode\nrf_probe_done\n"))

            val external =
                assertInstanceOf(
                    TripReason.ExternalSafeMode::class.java,
                    probe.detectExternalSafeMode(),
                )

            org.junit.jupiter.api.Assertions
                .assertEquals("magisk-safemode", external.source)
        }

    @Test
    fun `an empty property and no marker means not detected`() =
        runTest {
            val probe = RootHealthProbeImpl(managerControlling("\nrf_probe_done\n"))

            assertNull(probe.detectExternalSafeMode(), "两者都不命中 ⇒ 返回 null（不进入安全模式）")
        }

    @Test
    fun `a failed command means not detected rather than detected`() =
        runTest {
            // ★ 失败方向是**刻意的**：不可用 ⇒ 未命中。
            // 反向（判成命中）会让 App 永远不跑脚本，比漏检更糟。
            val probe =
                RootHealthProbeImpl(
                    managerControlling(stdout = "", exitCode = 1),
                )

            assertNull(
                probe.detectExternalSafeMode(),
                "通道不可用必须判为『未命中』—— 误判为命中会让 App 永远不跑脚本",
            )
        }

    @Test
    fun `the probe uses the control channel rather than the data channel`() =
        runTest {
            // 用数据通道会与正在跑的脚本抢作业（1c 的核心结论：同一 shell 一次只能跑一个作业）
            val manager = mockk<RootShellManager>()
            var controlCalls = 0
            var dataCalls = 0
            coEvery { manager.execControl(any(), any()) } coAnswers {
                controlCalls++
                ok("rf_probe_done")
            }
            coEvery { manager.exec(any(), any()) } coAnswers {
                dataCalls++
                ok(RootHealthProbeImpl.PROBE_TOKEN)
            }
            val probe = RootHealthProbeImpl(manager)

            probe.detectExternalSafeMode()

            org.junit.jupiter.api.Assertions
                .assertEquals(1, controlCalls, "外部安全模式探测必须走控制通道")
            org.junit.jupiter.api.Assertions
                .assertEquals(0, dataCalls, "不得占用数据通道（正在跑的脚本会被清空输出）")
        }

    // ------------------------------------------------ 工具

    private fun ok(stdout: String) = ShellResult(stdout = stdout, stderr = "", exitCode = 0)

    /** 一个恒返回 [result] 的 manager（覆盖 `exec` 与 `execControl` 两条通道）。 */
    private fun managerReturning(result: ShellResult): RootShellManager {
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), any()) } returns result
        coEvery { manager.execControl(any(), any()) } returns result
        return manager
    }

    /** 只控制通道有意义的 manager（外部探测用例用）。 */
    private fun managerControlling(
        stdout: String,
        exitCode: Int = 0,
    ): RootShellManager {
        val manager = mockk<RootShellManager>()
        coEvery { manager.execControl(any(), any()) } returns
            ShellResult(stdout = stdout, stderr = "", exitCode = exitCode)
        return manager
    }
}
