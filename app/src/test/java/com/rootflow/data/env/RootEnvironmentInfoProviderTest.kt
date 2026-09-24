package com.rootflow.data.env

import com.rootflow.domain.env.RootEnvironmentProbe
import com.rootflow.domain.env.RootFlavor
import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [RootEnvironmentInfoProvider] 的单测（阶段 6b）。
 *
 * ## 为什么用假 `RootShellManager` 而不是 `FakeRootShell`
 * 后者是"解释若干 shell 命令文本"的文件系统假件（为 3a 的读写往返写的）。
 * 环境探测只需要**一段预置 stdout**，用一个按命令打桩的 mockk 更直接，
 * 也更容易断言"究竟发了哪条命令"（那是本类与 `runtime/` 之间的唯一契约）。
 *
 * ## 覆盖的降级路径（这三条在真机上都不罕见）
 * 1. root 通道不可用（`exitCode = -3`，设备未 root 或用户拒绝授权）
 * 2. `execControl` 抛异常（实现层故障）
 * 3. 并发 `refresh()`（进入主页 + 用户点刷新）
 *
 * 三条都必须**产出快照而不是抛异常** —— 主页不能因为环境探测失败而崩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootEnvironmentInfoProviderTest {
    @Test
    @DisplayName("探测之前 snapshot 为 null（UI 显示「探测中」而不是假值）")
    fun `snapshot is null before the first refresh`() {
        val provider = newProvider(stdout = "")

        assertNull(provider.snapshot.value, "未探测必须是 null，不能是填了默认值的空快照")
    }

    @Test
    @DisplayName("refresh 产出快照并推送，且发的正是那条只读探针命令")
    fun `refresh publishes a snapshot and sends the read-only probe`() =
        runTest {
            var sentCommand: String? = null
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any(), any()) } answers {
                sentCommand = firstArg()
                ShellResult(stdout = deviceShape(), stderr = "", exitCode = 0)
            }
            val provider = RootEnvironmentInfoProvider(manager)

            provider.refresh()

            assertEquals(
                RootEnvironmentProbe.PROBE_COMMAND,
                sentCommand,
                "探针命令原文必须与纯函数里的常量一致（否则命令与解析会各自漂移）",
            )
            val snapshot = provider.snapshot.value
            assertNotNull(snapshot, "refresh 之后必须有快照")
            val value = snapshot ?: return@runTest

            assertEquals(RootFlavor.Unknown, value.rootFlavor, "本机形态 ⇒ 如实报 Unknown")
            assertEquals("u:r:magisk:s0", value.selinuxContext)
            assertTrue(value.appVersion.isNotBlank(), "版本号不得为空（读不到时是 unknown）")
            assertTrue(value.capturedAtMillis > 0L, "必须记录探测时刻（UI 据此说明这是快照而非实时）")
        }

    @Test
    @DisplayName("root 通道不可用（exitCode=-3）时降级，不抛")
    fun `an unavailable control channel degrades instead of throwing`() =
        runTest {
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any(), any()) } returns
                ShellResult(stdout = "", stderr = "control channel is not available", exitCode = -3)
            val provider = RootEnvironmentInfoProvider(manager)

            provider.refresh()

            val value = provider.snapshot.value
            assertNotNull(value, "通道不可用也必须给出快照（设备未 root 是正常用法）")
            val snapshot = value ?: return@runTest
            assertEquals(RootFlavor.Unknown, snapshot.rootFlavor)
            assertNull(snapshot.selinuxContext, "读不到 context 就是 null")
            assertTrue(snapshot.appVersion.isNotBlank(), "版本信息与 root 无关，仍应有效")
        }

    @Test
    @DisplayName("execControl 抛异常时降级，不抛")
    fun `a throwing shell degrades instead of throwing`() =
        runTest {
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any(), any()) } throws IllegalStateException("shell boom")
            val provider = RootEnvironmentInfoProvider(manager)

            provider.refresh()

            assertEquals(
                RootFlavor.Unknown,
                provider.snapshot.value?.rootFlavor,
                "异常路径也必须落一份快照（主页不能因为探测失败而空白）",
            )
        }

    @Test
    @DisplayName("并发 refresh 串行化：两个探测不重叠（互斥生效）")
    fun `concurrent refreshes never overlap`() =
        runTest {
            var controlCalls = 0
            var active = 0
            var maxActive = 0
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any(), any()) } coAnswers {
                controlCalls++
                active++
                maxActive = maxOf(maxActive, active)
                // 挂起一拍：让"并发"真的有机会发生（不挂起的话两个协程会自然错开，
                // 断言就测不出互斥是否生效）
                delay(10)
                active--
                ShellResult(stdout = deviceShape(), stderr = "", exitCode = 0)
            }
            val provider = RootEnvironmentInfoProvider(manager)

            // 注意：互斥**不**等于去重 —— 两次调用都会真的探测，只是不重叠。
            // "连点只探一次"是 ViewModel 侧的重入保护（见 HomeViewModelTest 对应用例）。
            val first = async { provider.refresh() }
            val second = async { provider.refresh() }
            advanceUntilIdle()
            first.await()
            second.await()

            assertEquals(2, controlCalls, "两次调用都应真的探测（互斥不是缓存）")
            assertEquals(1, maxActive, "两个探测绝不能同时压 root 通道")
            assertNotNull(provider.snapshot.value)
        }

    @Test
    @DisplayName("再次 refresh 覆盖快照（刷新按钮的语义）")
    fun `a second refresh replaces the snapshot`() =
        runTest {
            var call = 0
            val manager = mockk<RootShellManager>()
            coEvery { manager.execControl(any(), any()) } coAnswers {
                call++
                val stdout =
                    if (call == 1) {
                        deviceShape()
                    } else {
                        probeOutput(kernel = "Linux version 4.19.191-APatch-115032 (builder@host)")
                    }
                ShellResult(stdout = stdout, stderr = "", exitCode = 0)
            }
            val provider = RootEnvironmentInfoProvider(manager)

            provider.refresh()
            assertEquals(RootFlavor.Unknown, provider.snapshot.value?.rootFlavor)

            provider.refresh()
            assertEquals(
                RootFlavor.APatch,
                provider.snapshot.value?.rootFlavor,
                "第二次探测必须覆盖第一次的结果（否则刷新按钮毫无作用）",
            )
            assertEquals(2, call, "两次 refresh 必须是两次独立探测")
        }

    /** 用一个固定 stdout 构造 provider（省掉逐用例重复的打桩样板）。 */
    private fun newProvider(stdout: String): RootEnvironmentInfoProvider {
        val manager = mockk<RootShellManager>()
        coEvery { manager.execControl(any(), any()) } returns
            ShellResult(stdout = stdout, stderr = "", exitCode = 0)
        return RootEnvironmentInfoProvider(manager)
    }

    /**
     * 本机实测形态的探针输出：context 能读到、内核串无标记、两个路径探针都不命中。
     *
     * 放在 companion 里供各用例复用，避免每处各抄一遍而漂移
     * （真机输出形态一旦变化，只需要改这一处）。
     */
    private companion object {
        fun deviceShape(): String =
            probeOutput(kernel = "Linux version 4.19.191-g0a1b2c3 (builder@host) #1 SMP PREEMPT")

        /** 拼一份完整输出：SELinux 段 + 内核段 + 结束哨兵。 */
        fun probeOutput(kernel: String): String =
            "u:r:magisk:s0\u0000\n" +
                "${RootEnvironmentProbe.MARK_SELINUX_DONE}\n" +
                kernel + "\n" +
                "${RootEnvironmentProbe.MARK_KERNEL_DONE}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"
    }
}
