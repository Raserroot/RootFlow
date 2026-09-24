package com.rootflow.data.event

import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.event.CircuitBreakerMachine
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 启动期安全模式判定的编排单测（阶段 4）。
 *
 * ## 对应交接清单里的 `BootModeProbeTest`
 * 名字在仓库里**零命中**（交接清单只写了它），因此按"启动期安全模式判定"理解：
 * 把 `RootFlowApp.verifySafeModeWiring` 的那段固定顺序（恢复 flag → 外部探测）抽出来
 * 用真实实现验一遍，覆盖**纯函数测不到**的那部分——**状态之间的相互影响**：
 *
 * | 场景 | 期望 |
 * |---|---|
 * | `safemode.flag` 存在 | 恢复后立刻处于安全模式（**重启不得绕过熔断**） |
 * | 外部安全模式命中 | 进内存态，**且不写 flag**（外部状态解除后不该留人工清 flag 的坑） |
 * | 两者都不命中 | 不处于安全模式，且不产生任何文件 |
 * | flag 在 + 外部也命中 | 仍为安全模式，成因取**先恢复的那个**（flag 优先，它是本应用的证据） |
 *
 * ## 为什么这条链路值得单独测
 * `RootFlowApp` 本体在单测里不可构造（`Application` 需要 Android 运行时），
 * 因此把判定顺序放在这里验，是这条逻辑**唯一**的纯 JVM 覆盖点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BootModeProbeTest {
    init {
        CircuitBreakerFixture.installAndroidLogStubs()
    }

    @Test
    fun `a flag on disk restores safe mode at startup`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] = "rootflow safemode\n"
            val breaker = fixture.newBreaker()

            val restored = breaker.restoreStateFromDisk()

            assertTrue(restored, "flag 存在必须回报 true（调用方据此记录日志与后续行为）")
            assertTrue(breaker.safeMode.value, "重启后必须立刻回到安全模式")
            assertTrue(breaker.isSafeModeActive)
        }

    @Test
    fun `an external safe mode enters memory state without creating a flag`() =
        runTest {
            val fixture =
                CircuitBreakerFixture(
                    probe =
                        FakeRootHealthProbe(
                            external = TripReason.ExternalSafeMode(source = "persist.sys.safemode"),
                        ),
                )
            val breaker = fixture.newBreaker()
            // 与生产一致：先恢复（无 flag），再探外部
            assertFalse(breaker.restoreStateFromDisk())

            val detected = breaker.probeExternalSafeMode()

            assertInstanceOf(TripReason.ExternalSafeMode::class.java, detected)
            assertTrue(breaker.safeMode.value)
            assertFalse(
                fixture.shell.files.containsKey(RootFlowPaths.SAFE_MODE_FLAG),
                "跟随外部状态**不得**写 flag：flag 是『本应用熔断过』的证据",
            )
        }

    @Test
    fun `a clean startup stays out of safe mode and writes nothing`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            val restored = breaker.restoreStateFromDisk()
            val external = breaker.probeExternalSafeMode()

            assertFalse(restored)
            assertNull(external)
            assertFalse(breaker.safeMode.value, "两者都不命中 ⇒ 正常启动")
            assertTrue(fixture.shell.files.isEmpty(), "启动期判定不得产生任何文件：${fixture.shell.files.keys}")
        }

    @Test
    fun `a flag wins over a simultaneous external hit`() =
        runTest {
            // 两者同时存在时，成因遵循"**先到者为准**"：flag 路径先跑，
            // 之后外部探测**不得**覆盖它。
            //
            // 为什么这条重要：两条路径的**恢复方式不同**（flag 意味着本应用熔断过，
            // 外部命中只是跟随系统状态）。若外部探测覆盖成因，真机判读与阶段 6 的
            // banner 都会把"本应用熔断的"误报成"跟随了外部状态"。
            val fixture =
                CircuitBreakerFixture(
                    probe = FakeRootHealthProbe(external = TripReason.ExternalSafeMode(source = "magisk-safemode")),
                )
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] =
                "rootflow safemode (exists = safe mode)\n" +
                "reason=${TripReason.Manual.KEY}\n" +
                "detail=${TripReason.Manual.detail}\n"
            val breaker = fixture.newBreaker()

            assertTrue(breaker.restoreStateFromDisk())
            assertEquals(
                TripReason.Manual,
                breaker.tripReason.value,
                "重启后成因必须从 flag 读回来（否则每次重启都丢失熔断原因）",
            )

            val external = breaker.probeExternalSafeMode()

            assertTrue(breaker.safeMode.value)
            assertInstanceOf(
                TripReason.ExternalSafeMode::class.java,
                external,
                "探测本身仍应如实回报本次命中的成因（调用方可能要记日志）",
            )
            assertEquals(
                TripReason.Manual,
                breaker.tripReason.value,
                "**内存态里的成因不得被外部探测覆盖** —— 先到者（flag）为准",
            )
        }

    @Test
    fun `a trip reason survives a simulated restart`() =
        runTest {
            // 熔断（写 flag）→ "重启"（新实例 + restoreStateFromDisk）
            // ⇒ 成因必须仍是当初那一个。这是"重启不得丢失熔断原因"的直接证据。
            val fixture = CircuitBreakerFixture()
            fixture.newBreaker().tripManually()

            // 模拟重启：同一份磁盘内容，全新的熔断器实例
            val restarted = fixture.newBreaker()
            restarted.restoreStateFromDisk()

            assertTrue(restarted.safeMode.value, "重启后必须仍处于安全模式")
            assertEquals(
                TripReason.Manual,
                restarted.tripReason.value,
                "无参成因必须能从 flag 完整还原",
            )
        }

    @Test
    fun `a parameterised trip reason is left unknown rather than fabricated`() =
        runTest {
            // 带参成因（如 ConsecutiveFailures(scriptId, consecutive)）在 flag 里只留了
            // 原因键与一行 detail ⇒ **不为它伪造参数**：造一个"看起来有证据、实际是编的"
            // 对象比返回 null 更糟。但"处于安全模式"这一结论必须保持不变。
            val fixture = CircuitBreakerFixture()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] =
                "rootflow safemode (exists = safe mode)\n" +
                "reason=${TripReason.ConsecutiveFailures.KEY}\n" +
                "detail=script 5 failed 3 times consecutively\n"
            val breaker = fixture.newBreaker()

            assertTrue(breaker.restoreStateFromDisk(), "读不懂成因**不得**否定『处于安全模式』")

            assertTrue(breaker.safeMode.value)
            assertNull(breaker.tripReason.value, "带参成因不得被伪造还原")
        }

    @Test
    fun `an unreadable flag still means safe mode`() =
        runTest {
            // flag 的契约是"存在即安全模式"，内容只是给人看的。
            // 文件被手工改坏时**绝不能**因此判定"不在安全模式"——那等于静默解除熔断。
            val fixture = CircuitBreakerFixture()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] = "garbage without any reason line\n"
            val breaker = fixture.newBreaker()

            assertTrue(breaker.restoreStateFromDisk())
            assertTrue(breaker.safeMode.value, "内容不可解析时仍必须处于安全模式")
            assertNull(breaker.tripReason.value)
        }

    @Test
    fun `restore after an external-only hit still clears safe mode`() =
        runTest {
            // 外部命中进的是内存态、没有 flag。恢复动作仍必须能把内存态清掉，
            // 否则用户"退出安全模式"之后仍然什么都不跑（且找不到原因）。
            val fixture =
                CircuitBreakerFixture(
                    probe =
                        FakeRootHealthProbe(
                            external = TripReason.ExternalSafeMode(source = "persist.sys.safemode"),
                        ),
                    triggers = MutableTriggerRepository(listOf(trigger(id = 1L))),
                )
            val breaker = fixture.newBreaker()
            breaker.probeExternalSafeMode()
            assertTrue(breaker.safeMode.value)

            breaker.restore(RestoreMode.KeepDisabled)

            assertFalse(breaker.safeMode.value, "外部命中后也必须能恢复")
            assertNull(breaker.tripReason.value)
        }

    @Test
    fun `safe mode blocks dispatch and runs only opted-in scripts`() =
        runTest {
            // 把"启动期判定"与"运行期效果"连起来验一次：
            // 判定出安全模式之后，两层判定的结论必须与 SafeModeDecision 一致。
            val fixture = CircuitBreakerFixture()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] = "rootflow safemode\n"
            val breaker = fixture.newBreaker()
            breaker.restoreStateFromDisk()

            assertFalse(
                com.rootflow.domain.event.SafeModeDecision
                    .shouldDispatchEvents(breaker.safeMode.value),
                "安全模式下事件不得分发",
            )
            assertFalse(
                com.rootflow.domain.event.SafeModeDecision.shouldRun(
                    safeMode = breaker.safeMode.value,
                    runOnSafeMode = false,
                ),
            )
            assertTrue(
                com.rootflow.domain.event.SafeModeDecision.shouldRun(
                    safeMode = breaker.safeMode.value,
                    runOnSafeMode = true,
                ),
                "豁免脚本仍可运行（入口是显式运行）",
            )
        }

    private fun CircuitBreakerFixture.newBreaker(): CircuitBreakerImpl =
        CircuitBreakerImpl(
            fileStore = fileStore,
            sessionRegistry = sessions,
            processGroupManager = processGroupManager,
            notifier = notifier,
            rootHealthProbe = probe,
            runHistoryRepository = history,
            machine = CircuitBreakerMachine(),
            wallClock = { 1_700_000_000_000L },
            monotonicClock = { 1_000_000L },
        )
}
