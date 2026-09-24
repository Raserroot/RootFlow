package com.rootflow.ui.home

import app.cash.turbine.test
import com.rootflow.data.run.FakeMasterSwitch
import com.rootflow.domain.env.EnvironmentInfoProvider
import com.rootflow.domain.env.EnvironmentSnapshot
import com.rootflow.domain.env.RootFlavor
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.model.LogTail
import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.repository.RunMeta
import com.rootflow.domain.run.RunActivityProvider
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceNotificationText
import com.rootflow.domain.service.ServiceStateProvider
import com.rootflow.ui.AlertSink
import com.rootflow.ui.scripts.FakeScriptRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [HomeViewModel] 的单测（阶段 6b）。
 *
 * ## ★ 本文件引入了项目里第一处 `Dispatchers.setMain`（**测试侧新增设施，已登记**）
 * `viewModelScope` 硬引用 `Dispatchers.Main.immediate`（`lifecycle-viewmodel-ktx` 的
 * `CloseableCoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`），
 * 而纯 JVM 下没有 `Main` ⇒ 一构造 ViewModel 就抛
 * `Module with the Main dispatcher had failed to initialize`。
 *
 * 项目里此前**没有任何**测试碰过 `viewModelScope`（`MainViewModelTest` 只读
 * `StateFlow`，不触发它），因此这是 6b 新增的测试基础设施。
 * `Dispatchers.setMain` 由 `kotlinx-coroutines-test` 提供（该构件在离线缓存中），
 * **不需要**新依赖；**不用** `androidx.arch.core:core-testing` 的
 * `InstantTaskExecutorRule`（那个构件不在缓存里，且本项目不引 Robolectric）。
 *
 * `setMain` 用 `StandardTestDispatcher`（§9.3）：它让 `viewModelScope` 与 `runTest`
 * 共用同一个虚拟时钟，于是 `advanceUntilIdle()` 能推动 ViewModel 内部的收集协程与
 * 三处有界等待的 `delay`。
 *
 * ## 覆盖的核心语义
 * 1. **终端订阅顺序**（先 `tail` 再 `observe`）—— 顺序反了会丢批
 * 2. **有界等待**（管道尚未登记该运行时重试，而不是整场空白）
 * 3. **运行切换**（新运行到来时旧收集必须停，且不保留旧行）
 * 4. **环境三态**（未探测 → 探测中 → 已加载）
 * 5. **投影**（安全模式文案、事件源原因、过滤）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var serviceState: FakeServiceStateProvider
    private lateinit var runActivity: FakeRunActivityProvider
    private lateinit var environment: FakeEnvironmentInfoProvider
    private lateinit var pipeline: FakeLogPipeline
    private lateinit var history: FakeRunHistoryRepository
    private lateinit var breaker: FakeCircuitBreaker

    /** P4：总闸端口假件（默认打开；拨闸用例自己控制它）。 */
    private lateinit var masterSwitch: FakeMasterSwitch

    /** P4：总开关卡片要读脚本统计（已启用 N 个 · 常驻 M 个）。 */
    private lateinit var scripts: FakeScriptRepository

    /**
     * ViewModel 的告警记录（`HomeViewModel.onWarning` 是 `internal` 的测试缝）。
     *
     * 存在的理由：ViewModel 里**不能**直接调 `android.util.Log`（纯 JVM 下未 stub，
     * 会抛 `Method … not mocked`），但"放弃等日志"这类事情又必须**不静默**。
     * 注入这条缝之后，"确实告警了"成为可断言的（而不是靠读日志）。
     */
    private lateinit var warnings: MutableList<String>

    @BeforeEach
    fun setUp() {
        // viewModelScope 需要 Main；用同一个 TestDispatcher，与 runTest 共用虚拟时钟。
        Dispatchers.setMain(testDispatcher)
        serviceState = FakeServiceStateProvider()
        runActivity = FakeRunActivityProvider()
        environment = FakeEnvironmentInfoProvider()
        pipeline = FakeLogPipeline()
        history = FakeRunHistoryRepository()
        breaker = FakeCircuitBreaker()
        masterSwitch = FakeMasterSwitch()
        scripts = FakeScriptRepository()
        warnings = mutableListOf()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ 冷启动

    @Test
    @DisplayName("冷启动：服务 Idle、无事件源、环境探测已发起、终端为空")
    fun `cold start asks for the environment once and shows nothing else`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(ForegroundState.Idle, state.service)
            assertFalse(state.serviceRunning)
            assertTrue(state.sources.isEmpty())
            assertNull(state.terminal.runId, "没有任何运行时终端不得指向某个 runId")
            assertEquals(1, environment.refreshCalls, "进入主页必须探一次环境（拉模型）")
            assertFalse(state.environmentRefreshing, "探测结束后必须停止转圈")
            assertTrue(pipeline.tailCalls.isEmpty(), "没有运行时不得去查 tail")
        }

    @Test
    @DisplayName("环境探测完成前显示未加载（不给假值）")
    fun `environment is not loaded until the provider produces a snapshot`() =
        runTest(testDispatcher) {
            environment.autoComplete = false
            val viewModel = newViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.environmentLoaded)
            assertTrue(
                viewModel.uiState.value.environment
                    .isEmpty(),
            )

            environment.emit(sampleSnapshot())
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.environmentLoaded)
            assertEquals(6, state.environment.size, "六行：设备/Android/ABI/版本/Root/SELinux")
            assertEquals("OnePlus PLK110", state.environment.first().value)
        }

    // ------------------------------------------------------------ 终端

    @Test
    @DisplayName("运行出现：先 tail 回填，再订阅增量（顺序是硬约束）")
    fun `a new run backfills from tail and then appends batches`() =
        runTest(testDispatcher) {
            pipeline.nextTail =
                LogTail(
                    runId = RUN_ID,
                    entries = listOf(entry(0L, "first", LogStream.STDOUT)),
                    droppedEntries = 0L,
                )
            val viewModel = newViewModel()
            advanceUntilIdle()

            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            assertEquals(listOf("tail:$RUN_ID", "observe:$RUN_ID"), pipeline.calls, "tail 必须先于 observe")

            pipeline.emit(RUN_ID, batch(1L, listOf(entry(1L, "second", LogStream.STDOUT))))
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(RUN_ID, terminal.runId)
            assertEquals(listOf("first", "second"), terminal.entries.map { it.text })
        }

    @Test
    @DisplayName("管道尚未登记该运行时重试，而不是整场空白（有界等到 tail 非空）")
    fun `a not yet registered run is retried instead of showing an empty terminal`() =
        runTest(testDispatcher) {
            // 第一次 tail 空（正是 TriggeredScriptRunner 里 register 早于 startRun 的窗口），
            // 第二次才拿到内容。
            pipeline.tails += LogTail(RUN_ID, emptyList(), 0L)
            pipeline.tails += LogTail(RUN_ID, listOf(entry(0L, "late", LogStream.STDOUT)), 0L)
            val viewModel = newViewModel()
            advanceUntilIdle()

            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            assertEquals(
                listOf("late"),
                viewModel.uiState.value.terminal.entries
                    .map { it.text },
            )
            assertTrue(pipeline.tailCalls.size >= 2, "必须重试过，实际=${pipeline.tailCalls.size} 次")
        }

    @Test
    @DisplayName("管道与 Room 都没有内容时如实留空（有界放弃，不无界重试）")
    fun `an unregistered run gives up after the bounded wait`() =
        runTest(testDispatcher) {
            pipeline.nextTail = null // 管道永远空
            // Room 也空（未 seed）—— 阶段 6b 起"两侧都没有"才算真的没有
            val viewModel = newViewModel()
            advanceUntilIdle()

            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(RUN_ID, terminal.runId, "仍指向该运行（注册表登记过它）")
            assertTrue(terminal.entries.isEmpty(), "两侧都收不到日志就显示空，而不是编造内容")
            assertEquals(
                10,
                pipeline.tailCalls.count { it == RUN_ID },
                "有界等待必须把 10 次重试全部用完才放弃（不是 1 次就认输）",
            )
            assertTrue(history.readCalls > 0, "管道为空后必须查过 Room（阶段 6b 修复的核心）")
            assertTrue(
                warnings.any { it.contains("HOME_TERMINAL_EMPTY") },
                "两侧都没有内容时必须告警（不静默），实际告警=$warnings",
            )
        }

    @Test
    @DisplayName("★ 运行已结束（管道 state 被回收）时，终端必须从 Room 补出内容")
    fun `the terminal falls back to room when the pipeline state is gone`() =
        runTest(testDispatcher) {
            // 真机现场：脚本 1 秒跑完 ⇒ 管道收尾时移除 state ⇒ tail 恒空、observe 空流。
            // UI 是在运行**结束之后**才开始收的，因此管道侧一条都拿不到。
            pipeline.nextTail = null // 永远空：模拟管道已回收 state
            history.seed(
                RUN_ID,
                listOf(entry(0L, "persisted-0", LogStream.STDOUT), entry(1L, "persisted-1", LogStream.STDERR)),
            )
            history.markFinished(RUN_ID)
            val viewModel = newViewModel()
            advanceUntilIdle()

            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(RUN_ID, terminal.runId)
            assertEquals(
                listOf("persisted-0", "persisted-1"),
                terminal.entries.map { it.text },
                "管道没有内容时必须从 Room 补出完整日志（否则终端恒显示 0 行 —— 真机缺陷）",
            )
            assertTrue(history.readCalls > 0, "必须真的查过运行历史")
        }

    @Test
    @DisplayName("★ 收尾批到达后从 Room 补齐，且与已观察到的行按序号去重")
    fun `the terminal merges room history after the run finishes`() =
        runTest(testDispatcher) {
            // 管道只来得及推前两行（ring 淘汰/结束前未订阅都可能造成），Room 里有全部四行
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "p0", LogStream.STDOUT)), 0L)
            history.seed(
                RUN_ID,
                (0L..3L).map { entry(it, "p$it", LogStream.STDOUT) },
            )
            history.markFinished(RUN_ID)
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()
            val afterAwait =
                "readCalls=${history.readCalls} findCalls=${history.findCalls} " +
                    "entries=${viewModel.uiState.value.terminal.entries.map { it.text }}"

            pipeline.emit(
                RUN_ID,
                batch(1L, listOf(entry(0L, "p0", LogStream.STDOUT), entry(1L, "p1")), dropped = 0L, finished = true),
            )
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(
                listOf("p0", "p1", "p2", "p3"),
                terminal.entries.map { it.text },
                "按 sequence 去重合并：既不丢 Room 独有的行，也不重复已观察到的行（首屏时 $afterAwait）",
            )
            assertEquals(listOf(0L, 1L, 2L, 3L), terminal.entries.map { it.sequence }, "必须按序号升序")
        }

    @Test
    @DisplayName("收尾后落库在途：以 finished_at 为截止信号做有界等待，等齐再补齐")
    fun `the fallback waits for the room write to settle`() =
        runTest(testDispatcher) {
            // 走**收尾批**这条真实路径（首屏有内容 ⇒ 会进入 observe ⇒ 收到 runFinished 后就地补齐）
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "live", LogStream.STDOUT)), 0L)
            history.seed(RUN_ID, (0L..2L).map { entry(it, "p$it", LogStream.STDOUT) })
            // finished_at 前两次查询返回 null ⇒ 模拟 RunHistoryCollector 的异步写库
            history.finishAfterReads = 2
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            pipeline.emit(RUN_ID, batch(1L, listOf(entry(0L, "live", LogStream.STDOUT)), finished = true))
            advanceUntilIdle()

            assertEquals(
                listOf("p0", "p1", "p2"),
                viewModel.uiState.value.terminal.entries
                    .map { it.text },
                "必须以 finished_at 为截止信号等到落库写全，而不是只读一次拿到半截历史",
            )
            assertTrue(history.findCalls >= 2, "必须真的轮询过 finished_at，实际=${history.findCalls}")
        }

    @Test
    @DisplayName("未收尾（运行中）时不无谓轮询 Room：直接放弃等待")
    fun `an unfinished run does not poll the room`() =
        runTest(testDispatcher) {
            pipeline.nextTail = null
            // Room 里没有内容，且我们从未观察到收尾批 ⇒ 不该反复查
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.terminal.entries
                    .isEmpty(),
            )
        }

    @Test
    @DisplayName("两侧都没有内容时如实留空并告警（不静默）")
    fun `an empty run is reported honestly`() =
        runTest(testDispatcher) {
            pipeline.nextTail = null
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(RUN_ID, terminal.runId, "仍指向该运行（注册表登记过它）")
            assertTrue(terminal.entries.isEmpty(), "收不到日志就显示空，而不是编造内容")
            assertTrue(
                warnings.any { it.contains("HOME_TERMINAL_EMPTY") },
                "两侧都没有时必须告警（不静默），实际=$warnings",
            )
        }

    @Test
    @DisplayName("tail 与首批重叠的行按 sequence 去重")
    fun `overlapping tail and first batch are deduplicated`() =
        runTest(testDispatcher) {
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "a", LogStream.STDOUT)), 0L)
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            // 管道在订阅之后推的首批可能仍含序号 0（快照与增量的边界）
            pipeline.emit(RUN_ID, batch(1L, listOf(entry(0L, "a", LogStream.STDOUT), entry(1L, "b", LogStream.STDOUT))))
            advanceUntilIdle()

            assertEquals(
                listOf(0L, 1L),
                viewModel.uiState.value.terminal.entries
                    .map { it.sequence },
            )
        }

    @Test
    @DisplayName("丢弃计数从批次透出到 UI（不静默）")
    fun `dropped entries reach the ui state`() =
        runTest(testDispatcher) {
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "a", LogStream.STDOUT)), 0L)
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            pipeline.emit(RUN_ID, batch(1L, listOf(entry(1L, "b", LogStream.STDOUT)), dropped = 9L))
            advanceUntilIdle()

            assertEquals(9L, viewModel.uiState.value.terminal.droppedEntries)
        }

    @Test
    @DisplayName("新运行到来：终端换到新运行，不保留旧行")
    fun `a new run replaces the terminal content`() =
        runTest(testDispatcher) {
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "old", LogStream.STDOUT)), 0L)
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()
            assertEquals(
                listOf("old"),
                viewModel.uiState.value.terminal.entries
                    .map { it.text },
            )

            pipeline.nextTail = LogTail(RUN_ID_2, listOf(entry(0L, "new", LogStream.STDOUT)), 0L)
            runActivity.publish(RUN_ID_2)
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(RUN_ID_2, terminal.runId)
            assertEquals(listOf("new"), terminal.entries.map { it.text }, "跨运行不合并")
        }

    @Test
    @DisplayName("运行结束（provider 不变）不会清空终端")
    fun `the terminal keeps its content after the run ends`() =
        runTest(testDispatcher) {
            pipeline.nextTail = LogTail(RUN_ID, listOf(entry(0L, "kept", LogStream.STDOUT)), 0L)
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()

            // 单调语义：运行收尾时 RunSessionRegistry **不**清空 latestRunId，
            // 因此这里模拟"什么都没发生"，终端必须保持内容。
            advanceUntilIdle()

            assertEquals(
                listOf("kept"),
                viewModel.uiState.value.terminal.entries
                    .map { it.text },
            )
        }

    @Test
    @DisplayName("切换过滤只改可见集合")
    fun `selecting a filter narrows the visible entries`() =
        runTest(testDispatcher) {
            pipeline.nextTail =
                LogTail(
                    RUN_ID,
                    listOf(entry(0L, "out", LogStream.STDOUT), entry(1L, "err", LogStream.STDERR)),
                    0L,
                )
            val viewModel = newViewModel()
            advanceUntilIdle()
            runActivity.publish(RUN_ID)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.terminal.visibleEntries.size)

            viewModel.selectFilter(TerminalStreamFilter.STDERR)
            advanceUntilIdle()

            val terminal = viewModel.uiState.value.terminal
            assertEquals(TerminalStreamFilter.STDERR, terminal.filter)
            assertEquals(listOf("err"), terminal.visibleEntries.map { it.text })
            assertEquals(2, terminal.entries.size, "过滤不得丢弃缓冲")
        }

    // ------------------------------------------------------------ 环境刷新

    @Test
    @DisplayName("手动刷新再探一次，且探测中重入被拒")
    fun `manual refresh probes again and rejects reentry`() =
        runTest(testDispatcher) {
            // 用闸门让"探测中"成为一个真实存在的窗口：否则假件的 refresh() 立刻返回，
            // 转圈标志根本来不及为 true，用例就测不出重入保护（实测踩到）。
            val gate = CompletableDeferred<Unit>()
            environment.gate = gate
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertEquals(1, environment.refreshCalls, "进入主页必须发起一次探测")
            assertTrue(viewModel.uiState.value.environmentRefreshing, "探测进行中必须显示转圈")

            // 探测进行中：连点不得排出第二串 root 往返（置位在 launch 之外才成立）
            viewModel.requestEnvironmentRefresh()
            viewModel.requestEnvironmentRefresh()
            advanceUntilIdle()
            assertEquals(1, environment.refreshCalls, "探测中不得重入")

            // 放行第一次探测
            environment.gate = null
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.environmentRefreshing, "探测结束后必须停止转圈")

            viewModel.requestEnvironmentRefresh()
            advanceUntilIdle()
            assertEquals(2, environment.refreshCalls, "探测结束后必须能再刷")
        }

    @Test
    @DisplayName("探测抛异常时转圈必须停（按钮不能永远转）")
    fun `a failing refresh still clears the spinner`() =
        runTest(testDispatcher) {
            environment.failWith = IllegalStateException("probe boom")
            val viewModel = newViewModel()
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.environmentRefreshing,
                "异常路径也必须在 finally 里复位转圈状态",
            )
            assertTrue(
                warnings.any { it.contains("HOME_ENV_REFRESH failed") },
                "探测失败必须告警（不静默），实际告警=$warnings",
            )
        }

    // ------------------------------------------------------------ 安全模式 banner

    @Test
    @DisplayName("安全模式与成因投影到 banner（成因缺失时如实说未知）")
    fun `safe mode and its reason are projected`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertFalse(viewModel.safeMode.value, "默认不得报安全模式")

            breaker.safeModeFlow.value = true
            breaker.reasonFlow.value = TripReason.Manual
            advanceUntilIdle()

            assertTrue(viewModel.safeMode.value)
            assertEquals(TripReason.Manual.detail, viewModel.safeModeDetail.value)

            // 成因不可还原（例如只看到 flag 文件）：必须说未知，不得编一个
            breaker.reasonFlow.value = null
            advanceUntilIdle()

            val detail = viewModel.safeModeDetail.value
            assertNull(detail, "成因缺失时 Value 为 null，由 UI 用 REASON_UNKNOWN 文案兜底")
            assertEquals(
                ServiceNotificationText.REASON_UNKNOWN,
                HomeProjections.safeModeDetail(detail),
            )
        }

    @Test
    @DisplayName("safeMode 是可直接订阅的状态流（banner 靠它）")
    fun `safe mode is observable`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.safeMode.test {
                assertFalse(awaitItem(), "订阅立即拿到当前值（冷启动 banner 不会漏）")
                breaker.safeModeFlow.value = true
                advanceUntilIdle()
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------ 事件源与服务状态

    @Test
    @DisplayName("服务状态与事件源原因一起投影到 UI 状态")
    fun `service state and source reasons are projected together`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            serviceState.publishState(
                state = ForegroundState.Running(enabled = 1, total = 2, safeMode = false),
                sources =
                    listOf(
                        EventSourceStatus("screen", EventSourceState.Running),
                        EventSourceStatus(
                            "usage_stats",
                            EventSourceState.Unavailable("permission missing: android.permission.PACKAGE_USAGE_STATS"),
                        ),
                    ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.serviceRunning)
            assertEquals("1/2", state.sourceSummary)
            assertEquals(2, state.sources.size)
            assertTrue(state.sources[1].unavailable)
            assertTrue(
                state.sources[1].detail.startsWith("permission missing:"),
                "具体权限必须原样到达 UI，实际=${state.sources[1].detail}",
            )
        }

    // ------------------------------------------------------------ 构造助手

    private fun newViewModel(): HomeViewModel =
        HomeViewModel(
            serviceState = serviceState,
            runActivity = runActivity,
            environment = environment,
            logPipeline = pipeline,
            runHistory = history,
            circuitBreaker = breaker,
            // ★ 阶段 6e：告警落点改成**构造形参** `AlertSink`（生产由 DI 接到 `Log.w`）。
            //   此前是 `.also { it.onWarning = … }` —— 构造**之后**才接线，
            //   于是 `init` 里同步打出的告警落进默认 no-op，永远断言不到。
            alertSink = AlertSink { message -> warnings += message },
            masterSwitch = masterSwitch,
            scripts = scripts,
        )

    private fun sampleSnapshot(): EnvironmentSnapshot =
        EnvironmentSnapshot(
            appVersion = "0.6.0-ui",
            androidRelease = "15",
            apiLevel = 35,
            deviceModel = "OnePlus PLK110",
            primaryAbi = "arm64-v8a",
            rootFlavor = RootFlavor.Unknown,
            selinuxContext = "u:r:magisk:s0",
            capturedAtMillis = 1_756_000_000_000L,
        )

    private fun entry(
        sequence: Long,
        text: String,
        stream: LogStream = LogStream.STDOUT,
    ): LogEntry =
        LogEntry(
            runId = RUN_ID,
            sequence = sequence,
            timestamp = 1_756_000_000_000L + sequence,
            stream = stream,
            text = text,
        )

    private fun batch(
        sequence: Long,
        entries: List<LogEntry>,
        dropped: Long = 0L,
        finished: Boolean = false,
    ): LogBatch =
        LogBatch(
            runId = RUN_ID,
            sequence = sequence,
            entries = entries,
            droppedEntries = dropped,
            runFinished = finished,
        )

    // ------------------------------------------------------------ 假件

    /**
     * 服务状态端口假件（可主动发布，模拟服务注册/停止）。
     *
     * `@Suppress` 说明：ktlint 的 `backing-property-naming` 要求 `_foo` 必须有一个
     * 同名的**非 override** 属性。本假件实现的是端口接口，`state` / `sources` 是
     * `override`，规则识别不到匹配项。改写名字（如 `stateFlow`）会让"这就是端口的
     * backing 字段"这一意图变得含糊，因此这里按规则原意局部豁免。
     */
    @Suppress("ktlint:standard:backing-property-naming")
    private class FakeServiceStateProvider : ServiceStateProvider {
        private val _state = MutableStateFlow<ForegroundState>(ForegroundState.Idle)
        private val _sources = MutableStateFlow<List<EventSourceStatus>>(emptyList())

        override val state: StateFlow<ForegroundState> = _state

        override val sources: StateFlow<List<EventSourceStatus>> = _sources

        fun publishState(
            state: ForegroundState,
            sources: List<EventSourceStatus>,
        ) {
            _state.value = state
            _sources.value = sources
        }
    }

    /**
     * 运行活动端口假件（**单调**：publish 之后不回退，与真实实现一致）。
     *
     * `@Suppress` 理由同 [FakeServiceStateProvider]。
     */
    @Suppress("ktlint:standard:backing-property-naming")
    private class FakeRunActivityProvider : RunActivityProvider {
        private val _latestRunId = MutableStateFlow<String?>(null)

        override val latestRunId: StateFlow<String?> = _latestRunId

        fun publish(runId: String) {
            _latestRunId.value = runId
        }
    }

    /**
     * 环境信息端口假件。
     *
     * `@Suppress` 理由同 [FakeServiceStateProvider]。
     */
    @Suppress("ktlint:standard:backing-property-naming")
    private class FakeEnvironmentInfoProvider : EnvironmentInfoProvider {
        private val _snapshot = MutableStateFlow<EnvironmentSnapshot?>(null)

        override val snapshot: StateFlow<EnvironmentSnapshot?> = _snapshot

        var refreshCalls: Int = 0
            private set

        /** `false` 时 `refresh()` 不产出快照（模拟"探测还没回来"）。 */
        var autoComplete: Boolean = true

        /** 非 null 时 `refresh()` 会先挂起等它完成（用于制造"探测中"的真实窗口）。 */
        var gate: CompletableDeferred<Unit>? = null

        /** 非 null 时 `refresh()` 抛出它（覆盖 ViewModel 的最后一道防线）。 */
        var failWith: Throwable? = null

        override suspend fun refresh() {
            refreshCalls++
            gate?.await()
            failWith?.let { throw it }
        }

        fun emit(snapshot: EnvironmentSnapshot) {
            _snapshot.value = snapshot
        }
    }

    /**
     * 日志管道假件。
     *
     * 记录**调用顺序**（[calls]）是本假件的核心价值：`tail` 必须先于 `observe`，
     * 而顺序错了的后果是"终端中间缺一段"——在真机上极难归因。
     */
    private class FakeLogPipeline : LogPipeline {
        /** `tail` 的应答队列；用完后**重复最后一个**（便于"一直是某个值"的用例）。 */
        val tails: ArrayDeque<LogTail?> = ArrayDeque()

        /** 单次应答（优先于 [tails]）；`null` 表示"查不到该运行"。 */
        var nextTail: LogTail? = null

        val tailCalls: MutableList<String> = mutableListOf()
        val calls: MutableList<String> = mutableListOf()

        private val streams = mutableMapOf<String, MutableSharedFlow<LogBatch>>()

        override suspend fun startRun(
            runId: String,
            source: Flow<LogEntry>,
            runMeta: RunMeta?,
            batchSink: com.rootflow.domain.repository.LogBatchSink?,
        ): Unit = Unit

        override fun observe(runId: String): Flow<LogBatch> {
            calls += "observe:$runId"
            // 惰性建流：ViewModel 在 `tail` 之后立刻调用本方法，而用例往往在那之后才
            // `emit`。若这里对未登记的 runId 返回 `emptyFlow()`，所有增量都会静默丢失
            // （测试假绿）。真实 `LogPipelineImpl` 也总是已有 state 才被订阅，
            // 因此"订阅即建流"与生产行为一致。
            return streams.getOrPut(runId) { MutableSharedFlow(extraBufferCapacity = 64) }
        }

        override suspend fun tail(
            runId: String,
            limit: Int,
        ): LogTail {
            tailCalls += runId
            calls += "tail:$runId"
            val single = nextTail
            if (single != null) return single
            if (tails.isNotEmpty()) {
                // 单元素时重复使用（"永远返回同一个 tail"），多元素时逐个消费
                if (tails.size == 1) return tails.first() ?: emptyTail(runId)
                return tails.removeFirst() ?: emptyTail(runId)
            }
            return emptyTail(runId)
        }

        private fun emptyTail(runId: String): LogTail =
            LogTail(runId = runId, entries = emptyList(), droppedEntries = 0L)

        override suspend fun markFinished(runId: String): Unit = Unit

        override suspend fun release(runId: String): Unit = Unit

        /** 让 `observe(runId)` 返回一个可推送的流（未注册时是 `emptyFlow`）。 */
        fun registerStream(runId: String) {
            streams.getOrPut(runId) { MutableSharedFlow(extraBufferCapacity = 64) }
        }

        suspend fun emit(
            runId: String,
            batch: LogBatch,
        ) {
            registerStream(runId)
            streams.getValue(runId).emit(batch)
        }
    }

    /**
     * 运行历史（Room）假件 —— 阶段 6b 修复的核心依赖。
     *
     * ## 它模拟的关键性质
     * 1. **运行结束后仍然有内容**（真实 `run_log_entries` 不随管道回收而消失）
     * 2. **落库是异步的**：`finishedAt` 在 [finishAfterReads] 次读取之后才出现，
     *    用于复现"管道说结束了但 Room 还没写全"的竞态
     * 3. 读取次数可断言：用来钉"首屏查管道、结束后才查 Room"这条分工，
     *    而不是每批都打一次 DB
     *
     * `readLogs` 永远返回 `rows` 的**尾部**（与真实分页语义一致）。
     */
    private class FakeRunHistoryRepository : RunHistoryRepository {
        /** 该 runId 的完整日志（模拟已落库的内容）。 */
        val rows = linkedMapOf<String, List<LogEntry>>()

        /** 已 `markFinished` 的 runId（`finished_at` 非空）。 */
        private val finished = mutableSetOf<String>()

        /** `finished_at` 还要再读几次才出现（模拟写库在途）；0 = 立即就绪。 */
        var finishAfterReads: Int = 0

        var readCalls: Int = 0
            private set

        var findCalls: Int = 0
            private set

        fun seed(
            runId: String,
            entries: List<LogEntry>,
        ) {
            rows[runId] = entries
        }

        fun markFinished(runId: String) {
            finished += runId
        }

        override suspend fun recent(limit: Int): List<RunSummary> = emptyList()

        override suspend fun recentForScript(
            scriptId: Long,
            limit: Int,
        ): List<RunSummary> = emptyList()

        override suspend fun find(runId: String): RunSummary? {
            findCalls++
            if (finishAfterReads > 0) {
                finishAfterReads--
                return null
            }
            if (runId !in finished) return null
            return RunSummary(
                runId = runId,
                scriptId = 1L,
                triggerEvent = "boot",
                startedAt = 1L,
                finishedAt = 2L,
                exitCode = 0,
                logEntryCount = rows[runId]?.size ?: 0,
                droppedLogEntries = 0,
            )
        }

        override suspend fun readLogs(
            runId: String,
            limit: Int,
            offset: Int,
        ): List<LogEntry> {
            readCalls++
            return rows[runId].orEmpty().takeLast(limit)
        }

        override suspend fun countLogs(runId: String): Int = rows[runId]?.size ?: 0

        // 阶段 6d 新增的两个方法：本用例集（主页）不涉及保留策略清理，
        // 因此如实给出"什么都没删"与真实条数，而不是抛 UnsupportedOperationException
        // —— 后者会让"将来某个流程顺带调到它"变成崩溃，而不是一个可解释的空操作。
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0

        override suspend fun countAll(): Int = rows.size
    }

    /** 熔断器假件（只实现主页用到的两个状态）。 */
    private class FakeCircuitBreaker : CircuitBreaker {
        val safeModeFlow = MutableStateFlow(false)
        val reasonFlow = MutableStateFlow<TripReason?>(null)

        override val safeMode: StateFlow<Boolean> = safeModeFlow

        override val tripReason: StateFlow<TripReason?> = reasonFlow

        override suspend fun onRunAccepted(scriptId: Long): Unit = unsupported()

        override suspend fun onRunFinished(
            scriptId: Long,
            exitCode: Int?,
        ): Unit = unsupported()

        override suspend fun onRunTimeout(
            scriptId: Long,
            timeoutMillis: Long,
        ): Unit = unsupported()

        override suspend fun onRootUnresponsive(elapsedMillis: Long): Unit = unsupported()

        override suspend fun tripManually(): Unit = unsupported()

        override suspend fun tripForBootloop(crashes: Int): Unit = unsupported()

        override suspend fun restore(mode: RestoreMode): Unit = unsupported()

        override suspend fun probeExternalSafeMode(): TripReason? = unsupported()

        private fun unsupported(): Nothing =
            throw NotImplementedError("HomeViewModel must not drive the circuit breaker")
    }

    private companion object {
        const val RUN_ID: String = "11111111-2222-3333-4444-555555555555"
        const val RUN_ID_2: String = "99999999-8888-7777-6666-555555555555"
    }
}
