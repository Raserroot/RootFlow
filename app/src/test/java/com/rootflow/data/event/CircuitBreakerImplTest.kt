package com.rootflow.data.event

import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.event.CircuitBreakerMachine
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.TripReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CircuitBreakerImpl] 单测（需求 §5.2 的六步动作 + §5.3 的恢复 + 决策 D11）。
 *
 * ## 本类的两条主线
 * 1. **六步动作的顺序与"一步失败不阻断后续"**：最坏中间态是"有 flag 但脚本还在跑"
 *    或"脚本杀光了但触发器还开着"——因此每一步都单独 `runCatching`，本类逐项注入失败来验
 * 2. **快照往返**：熔断时记下"哪些触发器原本启用"，恢复时**严格还原**。
 *    恢复成"全部启用"是比不恢复更糟的静默副作用（替用户打开了他没开的触发器）
 *
 * 时间与文件系统都用真实/内存替身，不触碰真机与 Android 运行时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircuitBreakerImplTest {
    init {
        CircuitBreakerFixture.installAndroidLogStubs()
    }

    // ------------------------------------------------ 六步动作

    @Test
    fun `a manual trip writes the flag first and logs last`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            assertTrue(fixture.flagExists(), "第 1 步：flag 必须落盘（存在即安全模式）")
            assertEquals(
                1,
                fixture.safeModeLogLines().size,
                "第 6 步：safemode.log 必须恰好追加一行",
            )
            assertTrue(
                fixture.safeModeLogLines().single().contains("reason=${TripReason.Manual.KEY}"),
                "日志必须带稳定 reasonKey（判读要能区分是哪条触发条件）：${fixture.safeModeLogLines()}",
            )
            assertEquals(listOf(TripReason.Manual), fixture.notifier.trips, "第 4/5 步：通知必须被调用")
        }

    @Test
    fun `a trip leaves every user subscription untouched and writes no snapshot`() =
        runTest {
            // ★ 语义（总开关重构）：熔断第 3 步的"禁用触发器"改成**内存级事件拦截**
            //   （`SafeModeDecision.shouldDispatchEvents` 让分发器丢弃整个事件），
            //   因此它**不碰用户的订阅数据**、也**不写 `safemode.state` 快照**。
            //   旧实现会在库里留下 `enabled = 0`：快照一旦损坏/丢失，用户恢复后订阅仍是禁用的
            //   —— 那条"恢复后数据仍被打坏"的路径随本改动从根上消失。
            val fixture =
                CircuitBreakerFixture(
                    triggers =
                        MutableTriggerRepository(
                            listOf(trigger(id = 1L), trigger(id = 2L, enabled = false), trigger(id = 3L)),
                        ),
                )
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            assertEquals(
                listOf(1L, 2L, 3L),
                fixture.triggers.enabledIds(),
                "第 3 步：**不得**改动任何订阅 —— 拦截是内存态的，用户数据是只读的",
            )
            assertNull(
                fixture.stateFile(),
                "不得写 safemode.state 快照：它记录的「原始启用状态」已无意义（没有东西被改过）",
            )
        }

    @Test
    fun `the trip terminates every registered run`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            fixture.sessions.register(runId = "run-a", scriptId = 1L)
            fixture.sessions.register(runId = "run-b", scriptId = 2L)
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            assertTrue(
                fixture.shell.commands.any { it.contains("/.rf_pgid_run-a") } &&
                    fixture.shell.commands.any { it.contains("/.rf_pgid_run-b") },
                "第 2 步：每一个已登记的运行都必须被终止（含跳过闸门的那些）：${fixture.shell.commands}",
            )
        }

    @Test
    fun `a repeated trip neither logs nor notifies again`() =
        runTest {
            // 幂等是**必须**的：真机判读按"熔断了没有"看日志与通知，
            // 重复触发若各留一行，回看时会误判成"熔断了两次"。
            // （旧版本这里断言"快照不被覆盖" —— 快照已随内存级拦截取消，没有可覆盖的东西了。）
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()
            breaker.tripManually()
            val subscriptionsAfterFirst = fixture.triggers.enabledIds()

            breaker.tripManually()

            assertEquals(1, fixture.safeModeLogLines().size, "第二次熔断不应再写一行日志")
            assertEquals(1, fixture.notifier.trips.size, "第二次熔断不应再通知")
            assertEquals(
                subscriptionsAfterFirst,
                fixture.triggers.enabledIds(),
                "第二次熔断同样不得动订阅",
            )
        }

    // ------------------------------------------------ 一步失败不阻断后续

    @Test
    fun `a broken trigger repository does not abort the trip`() =
        runTest {
            // 熔断的第 3 步**不再读写订阅表**（内存级拦截）⇒ 仓库整个坏掉也不该影响止血。
            // 旧版本验的是"读/写触发器失败不阻断后续步骤"，那条路径已不存在（好事：
            // 熔断这条故障路径上的 IO 越少，它自己失败的机会就越少）。现在验更强的性质。
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository())
            fixture.triggers.failAll = true
            fixture.triggers.failSave = true
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            assertTrue(fixture.flagExists(), "第 1 步已完成的成果必须保留")
            assertEquals(1, fixture.notifier.trips.size, "第 4/5 步仍必须执行")
            assertEquals(
                1,
                fixture.safeModeLogLines().size,
                "第 6 步仍必须执行 —— 否则熔断发生了却没有留痕",
            )
        }

    @Test
    fun `the trip log keeps its six-step shape with zero triggers disabled`() =
        runTest {
            // 六步动作的**形状**要稳定（真机判读按步骤序号与字段名比对）。
            // 第 3 步仍在日志里，且 `triggersDisabled=0` 在新语义下是**正确值**
            // （内存级拦截、没有任何订阅被改），不是"失败了 0 条" —— 判读时别当成错误。
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            val line = fixture.safeModeLogLines().single()
            assertTrue(line.contains("triggersDisabled=0"), "第 3 步的计数必须如实出现：$line")
            assertTrue(line.contains("terminated="), "第 2 步的计数也必须出现：$line")
            assertTrue(line.contains("reason="), "成因必须出现：$line")
        }

    // ------------------------------------------------ 运行结局（RunOutcomeSink）

    @Test
    fun `a null exit code is not counted as a failure`() =
        runTest {
            // 已批准的取舍：漏熔断 < 误熔断。
            // null 表示"没拿到"（被 kill / 取消 / 没跑到末尾），不得当成失败。
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = null)

            assertEquals(0, breaker.failureCountOf(1L), "exitCode=null 不得计入连续失败")
            assertFalse(breaker.safeMode.value, "不得因此熔断")
        }

    @Test
    fun `a zero exit code is a success`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()
            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = 1)
            assertEquals(1, breaker.failureCountOf(1L))

            breaker.onRunOutcome(runId = "r2", scriptId = 1L, exitCode = 0)

            assertEquals(0, breaker.failureCountOf(1L), "退出码 0 必须清零连续失败")
        }

    @Test
    fun `three failed runs trip with the consecutive failures reason`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = 1)
            breaker.onRunOutcome(runId = "r2", scriptId = 1L, exitCode = 1)
            breaker.onRunOutcome(runId = "r3", scriptId = 1L, exitCode = 1)

            assertTrue(breaker.safeMode.value, "连续 3 次失败必须熔断（需求 §5.1 第 2 条）")
            assertInstanceOf(
                TripReason.ConsecutiveFailures::class.java,
                breaker.tripReason.value,
            )
            assertTrue(fixture.flagExists())
        }

    @Test
    fun `a failed run probes root health and trips when unresponsive`() =
        runTest {
            // 需求 §5.1 第 4 条：失败结局是**唯一**的运行期 root 健康探测点
            val fixture =
                CircuitBreakerFixture(
                    probe =
                        FakeRootHealthProbe(
                            snapshots =
                                listOf(
                                    RootHealthProbe.Snapshot(
                                        responsive = false,
                                        elapsedMillis = 10_000L,
                                        detail = "timeout",
                                    ),
                                ),
                        ),
                )
            val breaker = fixture.newBreaker()

            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = 1)

            assertEquals(1, fixture.probe.probeCalls, "失败的运行必须探一次 root 健康")
            assertInstanceOf(TripReason.RootUnresponsive::class.java, breaker.tripReason.value)
        }

    @Test
    fun `a successful run does not probe root health`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = 0)

            assertEquals(0, fixture.probe.probeCalls, "成功不必探活（探针要发 su，不能进热路径）")
        }

    @Test
    fun `the port entry shares the same failure logic as the sink entry`() =
        runTest {
            // 两个入口必须收敛到同一段判定，否则"什么算失败"会有两套规则
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.onRunFinished(scriptId = 1L, exitCode = 1)
            breaker.onRunAccepted(scriptId = 1L)
            breaker.onRunFinished(scriptId = 1L, exitCode = 1)
            breaker.onRunFinished(scriptId = 1L, exitCode = null)

            assertEquals(2, breaker.failureCountOf(1L), "端口入口与 sink 入口共用计数（null 不计）")
        }

    // ------------------------------------------------ 超时与手动

    @Test
    fun `a timeout trips immediately without waiting for three failures`() =
        runTest {
            // 超时说明脚本已失控，不必再等连续 3 次（CircuitBreakerImpl 的既定语义）
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.onRunTimeout(scriptId = 1L, timeoutMillis = 60_000L)

            assertTrue(breaker.safeMode.value)
            val reason = assertInstanceOf(TripReason.ScriptTimeout::class.java, breaker.tripReason.value)
            assertEquals(60_000L, reason.timeoutMillis)
        }

    @Test
    fun `a manual trip reports the manual reason`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            breaker.tripManually()

            assertEquals(TripReason.Manual, breaker.tripReason.value)
            assertTrue(breaker.isSafeModeActive)
        }

    // ------------------------------------------------ 启动恢复

    @Test
    fun `restoring state from disk picks up an existing flag`() =
        runTest {
            // 关键：进程重启后 `_safeMode` 是 false，不恢复就等于"重启可绕过熔断"
            val fixture = CircuitBreakerFixture()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_FLAG] = "rootflow safemode\n"
            val breaker = fixture.newBreaker()

            val restored = breaker.restoreStateFromDisk()

            assertTrue(restored)
            assertTrue(breaker.safeMode.value, "flag 存在 ⇒ 必须立刻回到安全模式")
        }

    @Test
    fun `restoring state from disk leaves a clean install alone`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            val breaker = fixture.newBreaker()

            assertFalse(breaker.restoreStateFromDisk(), "无 flag 时不得进入安全模式")
            assertFalse(breaker.safeMode.value)
        }

    // ------------------------------------------------ 外部安全模式（只读不写）

    @Test
    fun `an external safe mode is detected without writing the flag`() =
        runTest {
            // 需求 §5.3 第 2 条 + TripReason.ExternalSafeMode 的契约：
            // flag 是"本应用熔断过"的证据，跟随外部状态去写它会让外部解除后仍需人工清 flag
            val fixture =
                CircuitBreakerFixture(
                    probe =
                        FakeRootHealthProbe(
                            external = TripReason.ExternalSafeMode(source = "persist.sys.safemode"),
                        ),
                )
            val breaker = fixture.newBreaker()

            val detected = breaker.probeExternalSafeMode()

            assertNotNull(detected)
            assertTrue(breaker.safeMode.value, "命中时进内存态")
            assertFalse(fixture.flagExists(), "**不得**写 safemode.flag")
            assertFalse(
                fixture.shell.files.containsKey(RootFlowPaths.SAFE_MODE_STATE),
                "外部探测也不得写快照（它不是本应用的熔断动作）",
            )
        }

    @Test
    fun `an external probe that misses leaves safe mode off`() =
        runTest {
            val fixture = CircuitBreakerFixture(probe = FakeRootHealthProbe(external = null))
            val breaker = fixture.newBreaker()

            assertNull(breaker.probeExternalSafeMode())
            assertFalse(breaker.safeMode.value)
        }

    // ------------------------------------------------ 恢复

    @Test
    fun `restoring leaves every user subscription untouched`() =
        runTest {
            // ★ 恢复路径同样不动订阅：熔断侧既然**从未改动**用户数据（内存级拦截），
            //   就没有任何东西需要还原。旧实现按 `safemode.state` 快照逐条还原，
            //   快照损坏时会把用户原本开着的订阅留在禁用态 —— 那条坏结局已随之消失。
            val fixture =
                CircuitBreakerFixture(
                    triggers =
                        MutableTriggerRepository(
                            listOf(trigger(id = 1L), trigger(id = 2L, enabled = false), trigger(id = 3L)),
                        ),
                )
            val breaker = fixture.newBreaker()
            breaker.tripManually()

            breaker.restore(RestoreMode.RestoreOriginal)

            assertEquals(
                listOf(1L, 2L, 3L),
                fixture.triggers.enabledIds(),
                "恢复不得增删改任何订阅（含原本就停用的 2 号：它的配置必须原样留着）",
            )
            assertFalse(fixture.flagExists(), "flag 必须删掉")
            assertFalse(breaker.safeMode.value)
            assertNull(breaker.tripReason.value)
            assertEquals(1, fixture.notifier.restores)
        }

    @Test
    fun `restore keep disabled also leaves subscriptions alone`() =
        runTest {
            // 两种恢复模式的差别只在**日志里如实记下用户选了什么**（将来若恢复
            // KeepDisabled 这类语义，从日志能看出当时的意图）；对数据都是只读的。
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()
            breaker.tripManually()

            breaker.restore(RestoreMode.KeepDisabled)

            assertEquals(listOf(1L), fixture.triggers.enabledIds(), "KeepDisabled 同样不得动订阅")
            assertFalse(fixture.flagExists(), "但仍必须退出安全模式")
        }

    @Test
    fun `a stale snapshot file neither blocks nor changes the restore`() =
        runTest {
            // 设备上可能留着旧版本写的 `safemode.state`。新实现**既不读它、也不依赖它**，
            // 但恢复路径仍会**尽力删掉**它 —— 留着会让用户以为"还有东西没还原"。
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()
            breaker.tripManually()
            fixture.shell.files[RootFlowPaths.SAFE_MODE_STATE] = "1:1\n"

            breaker.restore(RestoreMode.RestoreOriginal)

            assertEquals(listOf(1L), fixture.triggers.enabledIds(), "订阅不受旧快照影响")
            assertFalse(
                fixture.shell.files.containsKey(RootFlowPaths.SAFE_MODE_STATE),
                "恢复路径必须删掉旧快照（残留 = 用户以为还有东西没还原）",
            )
        }

    @Test
    fun `restore clears the counters so the next failure does not inherit history`() =
        runTest {
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()
            breaker.onRunOutcome(runId = "r1", scriptId = 1L, exitCode = 1)
            breaker.onRunOutcome(runId = "r2", scriptId = 1L, exitCode = 1)
            breaker.tripManually()

            breaker.restore(RestoreMode.KeepDisabled)

            assertEquals(0, breaker.failureCountOf(1L), "恢复后不得带着熔断期的计数继续跑")
        }

    @Test
    fun `restore outside safe mode is a no-op`() =
        runTest {
            val fixture = CircuitBreakerFixture(triggers = MutableTriggerRepository(listOf(trigger(id = 1L))))
            val breaker = fixture.newBreaker()

            breaker.restore(RestoreMode.RestoreOriginal)

            assertEquals(listOf(1L), fixture.triggers.enabledIds(), "不在安全模式时恢复不得改动触发器")
            assertEquals(0, fixture.notifier.restores)
        }

    // ------------------------------------------------ D11：跨进程失败风暴预置

    @Test
    fun `seeding keeps only in-window failures`() =
        runTest {
            val window = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS
            val wall = 2_000_000_000L
            val monotonic = 500_000L
            val fixture =
                CircuitBreakerFixture(
                    history =
                        InMemoryRunHistoryRepository(
                            listOf(
                                runSummary("in-1", startedAt = wall - 1, exitCode = 1),
                                runSummary("in-2", startedAt = wall - 2, exitCode = 7),
                                runSummary("ok", startedAt = wall - 3, exitCode = 0),
                                runSummary("unknown", startedAt = wall - 4, exitCode = null),
                                runSummary("stale", startedAt = wall - window - 1, exitCode = 1),
                            ),
                        ),
                )
            val breaker = fixture.newBreaker(wallClock = { wall }, monotonicClock = { monotonic })

            breaker.seedFailureHistoryFromHistory()

            assertEquals(
                2,
                breaker.failuresInWindow(),
                "只灌『窗口内的失败』：成功 / 无退出码 / 窗口外三类都必须被排除",
            )
        }

    @Test
    fun `a history read failure degrades instead of blocking startup`() =
        runTest {
            val fixture = CircuitBreakerFixture()
            fixture.history.failRecent = true
            val breaker = fixture.newBreaker()

            breaker.seedFailureHistoryFromHistory()

            assertEquals(
                0,
                breaker.failuresInWindow(),
                "读历史失败 ⇒ 退化为修复前的行为（窗口为空），而不是让启动崩掉",
            )
        }

    // ------------------------------------------------ 工具

    private fun CircuitBreakerFixture.newBreaker(
        wallClock: () -> Long = { 1_700_000_000_000L },
        monotonicClock: () -> Long = { 1_000_000L },
    ): CircuitBreakerImpl =
        CircuitBreakerImpl(
            fileStore = fileStore,
            sessionRegistry = sessions,
            processGroupManager = processGroupManager,
            notifier = notifier,
            rootHealthProbe = probe,
            runHistoryRepository = history,
            machine = CircuitBreakerMachine(),
            wallClock = wallClock,
            monotonicClock = monotonicClock,
        )
}
