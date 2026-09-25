package com.rootflow.data.run

import android.util.Log
import com.rootflow.data.db.FakeDatabase
import com.rootflow.data.event.CircuitBreakerImpl
import com.rootflow.data.event.InMemoryEventBus
import com.rootflow.data.event.NoopSafeModeNotifier
import com.rootflow.data.event.RecordingOutcomeSink
import com.rootflow.data.event.TriggerDispatcherImpl
import com.rootflow.data.fs.FakeRootShell
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.log.LogPipelineImpl
import com.rootflow.data.script.ScriptRepositoryImpl
import com.rootflow.data.trigger.TriggerRepositoryImpl
import com.rootflow.domain.event.CircuitBreakerMachine
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.runtime.RunContext
import com.rootflow.runtime.ScriptEntity
import com.rootflow.runtime.ShellScriptRuntime
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.rootflow.runtime.LogStream as RuntimeLogStream

/**
 * 端到端接线单测（阶段 3d 的收官用例）。
 *
 * ## 它串起的链路（3d 方案 §1 的"链路总图"）
 * ```
 * EventBus.send(Boot)
 *   → TriggerDispatcherImpl.dispatch            （订阅总线 + 重入 + 防抖）
 *   → TriggeredScriptRunner.start               （装载 → enabled → 映射 → 闸门）
 *   → ScriptRunCoordinator.startLogging         （runtime.run + 映射 + 管道）
 *   → LogPipelineImpl（真实实现）                （批 + runMeta + 收尾批）
 *   → RunHistoryCollector（真实实现）            （有界队列 → 消费者）
 *   → RunHistoryWriter（真实实现）               （D6 退出码提取 + 保留策略）
 *   → FakeDatabase（内存 DAO）
 * ```
 *
 * ## 除两处外全是真实实现
 * - **脚本仓库**：真实的 `ScriptRepositoryImpl` + `FakeRootShell`（解释 root 命令的内存文件表）
 *   —— 这样"正文经 root 通道写入、装载时校验摘要"这条路也被覆盖
 * - **只有 `ShellScriptRuntime` 是 MockK**：它需要 libsu/真机。打桩只包住 `run`，
 *   返回的是由 [FakeScriptRuntime] 造的**一次性冷流句柄**（与生产契约一致）
 *
 * ## 依赖全部按协程测试约定注入
 * `LogPipelineImpl` 的 `dispatcher` 传 `StandardTestDispatcher(testScheduler)`
 * （`AGENT_PROTOCOL.md §9.3`：形参类型是 `CoroutineDispatcher` 时必须传它，
 * 否则管道会跑在 `Dispatchers.Default` 的真实线程上，`advanceUntilIdle()` 等不到）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndWiringTest {
    /** 端到端装配里"不与测试作用域同生共死"的作用域（落库消费者）；用例结束必须取消。 */
    private val harnessScopes = mutableListOf<CoroutineScope>()

    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        harnessScopes.forEach { it.cancel() }
        harnessScopes.clear()
    }

    @Test
    fun `a run writes the whole history end to end`() =
        runTest {
            val harness = harness()

            // 预置脚本正文（经 root 通道写文件，与生产路径一致）
            val scriptId = harness.saveScript(name = "e2e", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)

            // ★ P5：事件**不再启动脚本**，因此这里显式启动。
            //   "启动"（本用例）与"投递"（`TriggerDispatcherImplTest` 的 12 条 +
            //   本文件下面两条）现在是**两条独立链路** —— 旧版把它们串在一起来测，
            //   而串在一起来测恰恰是"改一处、另一处静默失效"的温床。
            harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            // ① 运行历史行落库
            val runId =
                harness.db.runs.keys
                    .single()
            val row = harness.db.runs.getValue(runId)
            assertEquals(scriptId, row.scriptId, "runs.script_id 必须来自 runMeta（候选 A）")
            assertEquals(SystemEvent.BOOT, row.triggerEvent)
            assertNotNull(row.finishedAt, "3a 遗留 #2：收尾批必须落 finished_at")
            assertEquals(0, row.exitCode, "D6：退出码应从 runtime 的 SYS 行提取")
            assertTrue(row.logEntryCount >= 3, "三次日志（SYS/STDOUT/SYS）都应落库：${row.logEntryCount}")

            // ② 日志行落库且内容正确
            val texts = harness.db.entriesOf(runId).map { it.text }
            assertTrue(texts.any { it.contains("started") }, "SYS 启动行缺失：$texts")
            assertTrue(texts.any { it == "hello" }, "脚本 stdout 缺失：$texts")
            assertTrue(texts.any { it.contains("exited with code 0") }, "SYS 退出行缺失：$texts")

            // ③ 闸门已释放（运行结束后可再次启动）
            assertEquals(0, harness.gate.activeCount)
        }

    @Test
    fun `the run context carries the event and the payload`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "e2e", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT, params = TriggerParams(payload = """{"src":"boot"}"""))

            // ★ P5：payload 在**事件路径**下是投递行的一部分
            //   （见 `TriggerDispatcherImplTest.the delivered line carries the payload after the event`）；
            //   本用例验证的是它经 `RunContext.env` 到达脚本那一跳 —— 与投递格式解耦。
            harness.runner.start(scriptId, SystemEvent.BOOT, payload = """{"src":"boot"}""")
            advanceUntilIdle()

            val (_, ctx) = harness.runtime.runs.single()
            assertEquals(SystemEvent.BOOT, ctx.triggerEvent)
            assertEquals("""{"src":"boot"}""", ctx.env[TriggeredScriptRunner.ENV_EVENT_PAYLOAD])
            assertEquals(scriptId.toString(), ctx.env[TriggeredScriptRunner.ENV_SCRIPT_ID])
        }

    @Test
    fun `a disabled script never runs`() =
        runTest {
            val harness = harness()
            // 前置：脚本本身是**停用**的（`scripts.enabled = 0`）。
            // 旧版本漏了这一步 —— 脚本是启用的、又订阅了 `boot`，于是它**必然运行**，
            // 于是这条"不得产生运行历史"必然失败。缺的是前置，不是要放宽的断言。
            val scriptId = harness.saveScript(name = "off", content = "echo hi", enabled = false)
            harness.saveTrigger(scriptId, SystemEvent.BOOT)

            val accepted = harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertFalse(accepted, "被停用的脚本必须被拒 —— 那是 runner 的准入闸门，与事件层无关")
            assertTrue(harness.db.runs.isEmpty(), "被禁用的脚本不得产生运行历史")
            assertTrue(harness.runtime.runs.isEmpty())
        }

    @Test
    fun `a script whose body vanished is not started and leaves no history`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "gone", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)
            // 删除正文文件，模拟"行还在、文件没了"
            harness.shell.files.remove("/data/local/tmp/rootflow/scripts/$scriptId/main.sh")

            val accepted = harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertFalse(accepted, "装载失败必须被拒")
            assertTrue(harness.runtime.runs.isEmpty(), "装载失败必须不启动")
            assertTrue(harness.db.runs.isEmpty(), "未启动就不得有历史行")
        }

    /**
     * 防抖在 P5 之后落在**投递**这一层 —— 它就是方案 §10 约束 ③ 要求的窗口合并
     * （投递 = 一次 `su` 往返）。
     *
     * `TriggerDispatcherImplTest` 用注入的 `clock` 精确测过边界；这条是**端到端**版本，
     * 验的是"事件真的走到总线、真的被合并掉一次"。
     */
    @Test
    fun `two events inside the debounce window are delivered once`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "e2e", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)
            // P5：事件只投给**正在运行的**脚本 ⇒ 先声明"它在跑"。
            //
            // ★ 这里用替身的 `openFor` 而**不是**真的 `runner.start(...)` + `Hanging`：
            //   后者会留下一个永不结束的协程，让 `runTest` 收尾时等它
            //   （实测 `UncompletedCoroutinesError`）。而"runner 启动 ⇒ 通道存在"
            //   那条链由 `TriggeredScriptRunnerTest.every run opens its own event channel…`
            //   覆盖 —— 这里要测的是**投递**，不是启动。
            harness.eventChannel.openFor(scriptId)
            // 让订阅与通道就位（`openFor` 同步生效，这一步是给总线订阅留一拍）
            advanceUntilIdle()

            harness.bus.send(SystemEvent.Boot)
            harness.bus.send(SystemEvent.Boot)
            advanceUntilIdle()

            assertEquals(
                listOf(scriptId to "boot"),
                harness.eventChannel.delivered,
                "500ms 防抖窗口内只投递一次（§10 约束 ③ 的窗口合并）",
            )
        }

    /** P5：订阅表决定**谁收到事件**（而不是"谁被启动"）。 */
    @Test
    fun `the subscription table decides who receives the event`() =
        runTest {
            val harness = harness()
            val bootScript = harness.saveScript(name = "boot", content = "echo boot")
            val screenScript = harness.saveScript(name = "screen", content = "echo screen")
            harness.saveTrigger(bootScript, SystemEvent.BOOT)
            harness.saveTrigger(screenScript, SystemEvent.SCREEN_OFF)
            // 两个脚本都得在跑（见上一条用例的说明：用 `openFor` 声明，避免 Hanging）
            harness.eventChannel.openFor(bootScript, screenScript)
            advanceUntilIdle()

            harness.bus.send(SystemEvent.Boot)
            advanceUntilIdle()

            assertEquals(
                listOf(bootScript to "boot"),
                harness.eventChannel.delivered,
                "只有订阅 boot 的那个脚本收到 —— 订阅表决定收件人",
            )
        }

    /**
     * ★ **P5 的语义切换本身**：事件**不再启动**任何脚本。
     *
     * 这条用例钉的是"旧能力确实消失了"：发一个 boot 事件，一个已启用、已订阅、正文完好的
     * 脚本**不会**因此运行。想让它跑，要么它自己常驻（`resident=true`，由监工拉起），
     * 要么用户手动运行 —— 而**都不是**"事件启动了它"。
     *
     * 旧版这里是 `a boot event runs the script and writes the whole history`；
     * 那条链路（事件 ⇒ `ScriptRunner.start`）在 P5 被**有意切断**，
     * 因此本用例是它的**反向继承者**。
     */
    @Test
    fun `an event does not start a script that is not running`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "e2e", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)

            harness.bus.send(SystemEvent.Boot)
            advanceUntilIdle()

            assertTrue(
                harness.eventChannel.delivered.isEmpty(),
                "脚本没在运行 ⇒ 没有通道 ⇒ 无人接收：${harness.eventChannel.delivered}",
            )
            assertTrue(harness.db.runs.isEmpty(), "★ 事件不得再启动脚本（P5 的语义切换）")
            assertTrue(harness.runtime.runs.isEmpty())
        }

    // ---------------------------------------------------------------- 阶段 4：安全模式端到端

    @Test
    fun `safe mode drops the event before any delivery`() =
        runTest {
            // 需求 §5.4「事件监听器注册但**不分发**」：入口丢弃。
            //
            // ★ P5 之后这条**必须让脚本正在运行**：事件现在只投给在跑的脚本，
            //   若让它处于"没在跑"，"没有投递"会因为**另一个原因**通过（假绿）。
            //   让它跑着，才能证明"丢弃发生在投递**之前**"。
            val harness = harness()
            val scriptId = harness.saveScript(name = "e2e", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)
            harness.runner.start(scriptId, SystemEvent.ALWAYS_RUN, payload = null)
            advanceUntilIdle()
            harness.breaker.tripManually()

            harness.bus.send(SystemEvent.Boot)
            advanceUntilIdle()

            assertTrue(
                harness.eventChannel.delivered.isEmpty(),
                "安全模式下事件必须被入口丢弃（连投递都不该发生）：${harness.eventChannel.delivered}",
            )
        }

    // ---------------------------------------------------------------- P3：总闸端到端

    /**
     * ★ **P3 不变量 1 的跨组件版本**：总闸关闭 ⇒ 整条链都不产生运行。
     *
     * 与上面"安全模式丢事件"的区别在**拦在哪一层**，两条都必须有：
     * - 安全模式在 **dispatcher** 丢（事件根本不进分发）
     * - 总闸在 **runner** 丢（准入门槛）—— 它覆盖"不经事件总线、直接调 runner"的入口：
     *   **手动运行**与**常驻脚本监管**，那两条路径都绕过 dispatcher
     *
     * ★ P5 之后本用例**不再**用"发事件"来测总闸：事件已经不启动脚本了，
     *   那样断言"没有运行"会**因为另一个原因**通过（假绿）。
     *   总闸在启动路径上的拦截点只有一个 —— `TriggeredScriptRunner.start` 的最前一道闸门，
     *   直接测它才测到东西。
     */
    @Test
    fun `the master switch blocks every run at the runner`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "off", content = "echo hi")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)
            harness.masterSwitch.set(false)

            val accepted = harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertFalse(accepted, "总闸关闭时任何启动路径都必须被拒（含手动运行 / 常驻监管）")
            assertTrue(harness.runtime.runs.isEmpty(), "被拒的调用不得触碰 runtime")
            assertTrue(harness.db.runs.isEmpty(), "也不得产生运行历史")
        }

    /**
     * ★ **P3 不变量 1 的例外检查**：`runOnSafeMode = true` 的救砖脚本**也**被总闸拦下。
     *
     * 依据是方案 §7 决策 5（用户裁定）：总开关是**绝对**闸门，与"安全模式"是两条
     * 独立规则；"安全优先"**不构成**对总闸的豁免。
     * 搞反的后果很具体：用户关掉总开关后救砖脚本仍会自动运行，而那与"别提供服务"直接冲突。
     */
    @Test
    fun `the master switch even blocks runOnSafeMode scripts`() =
        runTest {
            val harness = harness()
            val scriptId = harness.saveScript(name = "rescue", content = "echo rescue", runOnSafeMode = true)
            harness.breaker.tripManually()
            harness.masterSwitch.set(false)

            val accepted = harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertFalse(accepted, "总闸关闭时 runOnSafeMode 的脚本同样不得启动")
            assertTrue(harness.db.runs.isEmpty(), "不得落库")
        }

    @Test
    fun `a runOnSafeMode script runs in safe mode when reached directly`() =
        runTest {
            // 需求 §5.4 的例外：runOnSafeMode=true 的脚本**照常执行**。
            //
            // ⚠ 关键语义（真机判读时不要误判为缺陷）：
            // 事件分发在安全模式下被**入口整体丢弃**（`SafeModeDecision.shouldDispatchEvents`），
            // 因此豁免脚本**不能靠事件走到这里** —— 它的真实入口是
            // **阶段 6 的「手动运行」**（同一个 `TriggeredScriptRunner.start`）。
            // 本用例直接调 `start`，验证的正是那条路：豁免放行 + 跳过闸门 + 照常落库。
            val harness = harness()
            harness.breaker.tripManually()
            val scriptId = harness.saveScript(name = "rescue", content = "echo rescue", runOnSafeMode = true)

            val accepted = harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertTrue(accepted, "runOnSafeMode=true 在安全模式下必须被受理")
            assertTrue(harness.db.runs.isNotEmpty(), "豁免脚本必须照常运行并落库")
            assertEquals(1, harness.runtime.runs.size, "运行时确实被调用")
            assertEquals(
                scriptId,
                harness.outcomes.outcomes
                    .single()
                    .scriptId,
                "归属脚本必须与运行的那个一致",
            )
            assertEquals(
                0,
                harness.outcomes.outcomes
                    .single()
                    .exitCode,
                "结局回调必须带上退出码（安全模式路径同样如此）",
            )
        }

    @Test
    fun `safe mode blocks the same exempt script when it arrives as an event`() =
        runTest {
            // 上一条用例的**反面**：同一条豁免脚本若经事件到达，仍然走不进去 ——
            // 因为入口丢弃发生在"查触发器"之前，`runOnSafeMode` 在分发层不可见。
            // 把它写成独立用例，是为了让这条语义**有测试钉住**，而不是只写在 KDoc 里。
            //
            // ★ P5：同样必须让它在跑（见上一条的说明），否则断言假绿。
            val harness = harness()
            val scriptId = harness.saveScript(name = "rescue", content = "echo rescue", runOnSafeMode = true)
            harness.saveTrigger(scriptId, SystemEvent.BOOT)
            harness.runner.start(scriptId, SystemEvent.ALWAYS_RUN, payload = null)
            advanceUntilIdle()
            harness.breaker.tripManually()

            harness.bus.send(SystemEvent.Boot)
            advanceUntilIdle()

            assertTrue(
                harness.eventChannel.delivered.isEmpty(),
                "安全模式下事件一律不分发（含 runOnSafeMode 的脚本）：${harness.eventChannel.delivered}",
            )
        }

    @Test
    fun `a failed run with a null exit code is not counted as a failure`() =
        runTest {
            // 已批准的取舍（漏熔断 < 误熔断）：被 kill / 取消的运行没有退出行，
            // 交给熔断器的 exitCode 是 null，**不得**被当成失败——
            // 否则正常脚本会因这条路径被连计 3 次而误熔断。
            val killed =
                FakeScriptRuntime(
                    RuntimeOutcome.Completed(
                        listOf(com.rootflow.runtime.LogLine(1L, RuntimeLogStream.SYS, "script started")),
                    ),
                )
            val harness = harness(runtime = killed)
            val scriptId = harness.saveScript(name = "killed", content = "sleep 999")
            harness.saveTrigger(scriptId, SystemEvent.BOOT)

            // P5：事件不再启动脚本 ⇒ 显式启动（本用例验的是"结局计数"那条链路）
            harness.runner.start(scriptId, SystemEvent.BOOT, payload = null)
            advanceUntilIdle()

            assertNull(
                harness.outcomes.outcomes
                    .single()
                    .exitCode,
                "无退出行时上报 null（而不是伪造一个 0 或非 0）",
            )
            assertFalse(harness.breaker.safeMode.value, "exitCode=null 不得触发熔断")
            assertEquals(
                0,
                harness.breaker.failureCountOf(scriptId),
                "exitCode=null 不得计入连续失败（否则会误熔断）",
            )
        }

    @Test
    fun `the failure storm window is seeded from the run history`() =
        runTest {
            // 决策 D11：跨进程失败风暴。进程重启后内存窗口归零 ⇒ 必须从 Room 预置，
            // 否则"反复崩、每次崩前崩若干次"的循环永远到不了 20 次。
            //
            // 时基：注入固定时钟（`CircuitBreakerImpl` 的 internal 构造有测试缝），
            // 使"墙钟 - 单调"的偏移成为已知常数，从而**窗口边界可精确断言**。
            // 不这么做就得 `mockkStatic(SystemClock)`,而纯 JVM 下 `SystemClock` 未实现
            // （"not mocked"）、`System` 的静态占用又会污染整个 JVM。
            val harness = harness()
            val scriptId = harness.saveScript(name = "bad", content = "exit 1")
            // 20 条"窗口内失败" + 若干干扰项：已成功的、无退出码的、窗口外的
            repeat(20) { index ->
                harness.seedRun(runId = "hit-$index", scriptId = scriptId, exitCode = 1, ageMillis = index.toLong())
            }
            harness.seedRun(runId = "ok", scriptId = scriptId, exitCode = 0, ageMillis = 10L)
            harness.seedRun(runId = "unknown", scriptId = scriptId, exitCode = null, ageMillis = 10L)
            harness.seedRun(runId = "stale", scriptId = scriptId, exitCode = 1, ageMillis = STORM_WINDOW_MILLIS + 1)

            harness.breaker.seedFailureHistoryFromHistory()

            assertEquals(
                20,
                harness.breaker.failuresInWindow(),
                "只灌『窗口内的失败』：成功 / 无退出码 / 窗口外三类都必须被排除",
            )
        }

    private companion object {
        /** 判定机的风暴窗口（10 分钟）；用于构造"窗口外"的运行。 */
        const val STORM_WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /**
         * 固定墙钟（单测注入）。
         *
         * 取值任意，只要**显著大于**窗口长度即可；用固定值而非真实时间，
         * 使"窗口边界"的断言与运行机器的真实时间无关。
         */
        const val WALL_CLOCK: Long = 1_700_000_000_000L

        /**
         * 固定单调时钟。
         *
         * 偏移（`WALL_CLOCK - MONOTONIC_CLOCK`）在换算里被消掉，因此具体取值不影响断言；
         * 取一个"像真实开机时长"的正数即可。
         */
        const val MONOTONIC_CLOCK: Long = 5_000_000L
    }

    // ---------------------------------------------------------------- 装配

    private class Harness(
        val db: FakeDatabase,
        val shell: FakeRootShell,
        val runtime: FakeScriptRuntime,
        val bus: EventBus,
        val gate: RunAdmissionGateImpl,
        val repository: ScriptRepositoryImpl,
        val triggerRepository: TriggerRepositoryImpl,
        /** 真实的熔断器（阶段 4 起接入链路）。 */
        val breaker: CircuitBreakerImpl,
        /** 熔断器收到的运行结局（与 `db` 对照，证明"退出码无竞态"）。 */
        val outcomes: RecordingOutcomeSink,
        /** 投递终点：用于直接驱动"手动运行"这条路径（安全模式下豁免脚本的唯一入口）。 */
        val runner: TriggeredScriptRunner,
        /** 总闸假件（P3）：默认打开；端到端的"总闸关闭 ⇒ 事件不产生运行"用例拨它。 */
        val masterSwitch: FakeMasterSwitch,
        /** 事件通道假件（P5）：`open`/`deliver`/`close` 的调用都记在它上面。 */
        val eventChannel: FakeEventChannel,
    ) {
        suspend fun saveScript(
            name: String,
            content: String,
            enabled: Boolean = true,
            runOnSafeMode: Boolean = false,
            timeoutSec: Int = 0,
        ): Long {
            val saved =
                repository.save(
                    script(
                        name = name,
                        content = content,
                        enabled = enabled,
                        runOnSafeMode = runOnSafeMode,
                        timeoutSec = timeoutSec,
                    ),
                )
            val ok =
                saved as? com.rootflow.domain.repository.WriteResult.Ok
                    ?: error("save failed: $saved (commands=${shell.commands})")
            return ok.value.id
        }

        /**
         * 给脚本加一条事件订阅。
         *
         * 总开关重构后订阅是**集合替换**，因此这里是"读现有 → 追加 → 写回"
         * （端到端用例往往连续调用它加多条）。
         */
        suspend fun saveTrigger(
            scriptId: Long,
            eventType: String,
            params: TriggerParams = TriggerParams(),
        ) {
            val existing = triggerRepository.all().filter { it.scriptId == scriptId }
            triggerRepository.replaceForScript(
                scriptId = scriptId,
                triggers =
                    existing +
                        Trigger(
                            id = 0L,
                            scriptId = scriptId,
                            eventType = eventType,
                            params = params,
                            createdAt = 1L,
                        ),
            )
        }

        /**
         * 直接落一条"已结束"的运行历史（D11 的预置输入）。
         *
         * @param ageMillis 距"当前墙钟"多久之前开始（用于构造窗口内 / 窗口外的运行）
         */
        suspend fun seedRun(
            runId: String,
            scriptId: Long,
            exitCode: Int?,
            ageMillis: Long,
        ) {
            val startedAt = WALL_CLOCK - ageMillis
            db.runs[runId] =
                com.rootflow.data.db.entity.RunRow(
                    id = runId,
                    scriptId = scriptId,
                    triggerEvent = SystemEvent.BOOT,
                    startedAt = startedAt,
                )
            db.runDao.markFinished(
                runId = runId,
                finishedAt = startedAt,
                exitCode = exitCode,
                count = 0,
                dropped = 0,
            )
        }
    }

    private fun TestScope.harness(runtime: FakeScriptRuntime = FakeScriptRuntime()): Harness {
        val db = FakeDatabase()
        val shell = FakeRootShell()
        // 用**真实的** `InMemoryEventBus` 而不是 `RecordingEventBus`：
        // 后者的 `MutableSharedFlow(replay = 0)` 对"尚未订阅时发出的事件"完全不留存
        // （`InMemoryEventBus` 的 KDoc 记录了这条实测教训），会让本用例出现
        // "事件发了但没人收到"的假阴性。生产语义就是 `InMemoryEventBus`，此处不该用替身。
        val bus: EventBus = InMemoryEventBus()
        val gate = RunAdmissionGateImpl()
        val scriptRepository = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
        val triggerRepository = TriggerRepositoryImpl(db.scriptEventDao)
        // 总闸假件（P3）：默认打开 ⇒ 既有用例的语义一字不改；
        // "总闸关闭 ⇒ 事件整条链都不产生运行"由专门用例拨它。
        val masterSwitch = FakeMasterSwitch()
        // 事件通道假件（P5）：默认开得出通道 ⇒ 脚本环境里会有 ROOTFLOW_EVENT_FIFO。
        val eventChannel = FakeEventChannel()
        val writer = RunHistoryWriter(db.runDao, db.runLogDao, clock = { 5_000L })
        // 落库消费者**永不返回**（`for (pending in queue)`），因此给它一个"共用测试调度器、
        // 但不挂在测试作用域下"的独立作用域（见 `RunHistoryCollectorTest.fixture` 的详细说明：
        // 用 `this` 会让 runTest 等到超时；用 `backgroundScope` 则 advanceUntilIdle 推不动它）。
        val consumerScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        harnessScopes += consumerScope
        val collector = RunHistoryCollector(writer = writer, scope = consumerScope)
        // §9.3：必须传 StandardTestDispatcher，否则管道跑在真实线程上，advanceUntilIdle 等不到
        val pipeline = LogPipelineImpl(scope = this, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = ScriptRunCoordinator(shellRuntimeFor(runtime), pipeline, mockk(relaxed = true))

        // 阶段 4：**真实的**熔断器接入链路（不是替身）。
        // 这样 `ScriptRunCoordinator → RunOutcomeSink → CircuitBreakerMachine` 这一段
        // 在端到端用例里也是真的，而不只是"接线看起来接上了"。
        // 时钟注入固定值（internal 测试缝）：纯 JVM 下 `SystemClock` 未实现，
        // 而 `mockkStatic(System::class)` 会污染整个 JVM。
        val outcomes = RecordingOutcomeSink()
        val breaker =
            CircuitBreakerImpl(
                fileStore = RootFileStore(shell.manager),
                sessionRegistry = RunSessionRegistry(),
                processGroupManager = mockk(relaxed = true),
                notifier = NoopSafeModeNotifier(),
                rootHealthProbe =
                    object : RootHealthProbe {
                        // 探针恒"响应正常"：本用例要验的是链路接线，不是 root 健康判定
                        override suspend fun probe(): RootHealthProbe.Snapshot =
                            RootHealthProbe.Snapshot(responsive = true, elapsedMillis = 1L)

                        override suspend fun detectExternalSafeMode(): TripReason? = null
                    },
                runHistoryRepository = RunHistoryReader(db.runDao, db.runLogDao),
                machine = CircuitBreakerMachine(),
                wallClock = { WALL_CLOCK },
                monotonicClock = { MONOTONIC_CLOCK },
            )

        val runner =
            TriggeredScriptRunner(
                scriptRepository = scriptRepository,
                coordinator = coordinator,
                gate = gate,
                batchSink = collector,
                scope = this,
                circuitBreaker = breaker,
                masterSwitch = masterSwitch,
                eventChannel = eventChannel,
                sessionRegistry = RunSessionRegistry(),
                outcomeSink = outcomes,
            )
        // 调度器的订阅无限期挂起（`eventBus.events().collect`）⇒ 同样放独立作用域，
        // 否则 `runTest` 会等它到超时（`UncompletedCoroutinesError`）。
        val dispatcherScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        harnessScopes += dispatcherScope
        val dispatcher =
            TriggerDispatcherImpl(
                triggerRepository = triggerRepository,
                // ★ P5：dispatcher 不再拿 `scriptRunner` / `scriptRunRegistry` ——
                //   它现在只做投递（见 `TriggerDispatcherImpl` 的类 KDoc）
                eventChannel = eventChannel,
                eventBus = bus,
                scope = dispatcherScope,
                onWarning = { },
                circuitBreaker = breaker,
            )
        dispatcher.start()
        // 先让订阅真正建立，再发事件：本用例要测的是链路语义，不是"发送早于订阅"的时序
        // （后者由 `InMemoryEventBus` 的 replay 缓冲负责，另有专门用例覆盖）。
        runCurrent()

        return Harness(
            db,
            shell,
            runtime,
            bus,
            gate,
            repository = scriptRepository,
            triggerRepository = triggerRepository,
            breaker = breaker,
            outcomes = outcomes,
            runner = runner,
            masterSwitch = masterSwitch,
            eventChannel = eventChannel,
        )
    }

    /**
     * 把 [FakeScriptRuntime] 接到 `ShellScriptRuntime` 的位置上（同 `TriggeredScriptRunnerTest`）。
     *
     * 理由：`ScriptRunCoordinator` 的形参是**具体类**（1b 冻结形态），而 3d 不改 runtime
     * 公开接口。打桩只包住 `run`，其余全部真实。
     */
    private fun shellRuntimeFor(runtime: FakeScriptRuntime): ShellScriptRuntime {
        val shell = mockk<ShellScriptRuntime>()
        coEvery { shell.run(any(), any(), any()) } answers {
            // 11e 补丁4：runId 原样透传（`ScriptRuntime.run` 新增的第三个形参）。
            runtime.recordRun(firstArg<ScriptEntity>(), secondArg<RunContext>(), thirdArg<String>())
        }
        return shell
    }
}
