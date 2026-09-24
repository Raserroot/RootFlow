package com.rootflow.ui.scripts

import com.rootflow.data.event.FakeCircuitBreaker
import com.rootflow.data.event.InMemoryRunHistoryRepository
import com.rootflow.data.run.FakeMasterSwitch
import com.rootflow.data.run.RunAdmissionGateImpl
import com.rootflow.data.service.RecordingDaemonSupervisor
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.ui.AlertSink
import com.rootflow.ui.settings.FakeServiceStateProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ScriptListViewModel] 的单测（阶段 6c）。
 *
 * ## 覆盖的核心语义
 * 1. **计数三态**（§0.3 ①）："还不知道"绝不能被渲染成"0 条"
 * 2. **删除 `Failed` = 部分成功**（§0.3 ②）：文案里不得出现"删除失败"
 * 3. 开关**不乐观更新**：写入失败 ⇒ 开关原样、告警可见
 * 4. 计数刷新失败 ⇒ **保留旧值**（清空是把已知变未知）
 *
 * ## 协程纪律（`AGENT_PROTOCOL.md §9`）
 * `viewModelScope` 需要 `Main`（纯 JVM 下没有）⇒ `Dispatchers.setMain(StandardTestDispatcher)`
 * 让它与 `runTest` 共用同一个虚拟时钟（6b 的 `HomeViewModelTest` 已建立该设施）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptListViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var scripts: FakeScriptRepository
    private lateinit var triggers: FakeTriggerRepository

    /** P4：总闸假件（**默认打开** ⇒ 既有用例的行为一字不改）。 */
    private lateinit var masterSwitch: FakeMasterSwitch

    /** P4：监工假件（「为什么没跑」要读"在不在监管名单"与"为什么被放弃重启"）。 */
    private lateinit var daemonSupervisor: RecordingDaemonSupervisor

    /** P4：运行态（真实现：内存闸门，零依赖）。 */
    private lateinit var runRegistry: RunAdmissionGateImpl

    /** P4：运行历史（「为什么没跑」里的"上次成功 / 上次尝试"）。 */
    private lateinit var runHistory: InMemoryRunHistoryRepository

    /** P4：服务状态（常驻脚本由前台服务承载）。 */
    private lateinit var serviceState: FakeServiceStateProvider

    /** P4：熔断（门控链的第一层）。 */
    private lateinit var breaker: FakeCircuitBreaker

    /** ViewModel 的告警记录（`internal var onWarning` 是测试缝）。 */
    private lateinit var warnings: MutableList<String>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scripts = FakeScriptRepository()
        triggers = FakeTriggerRepository()
        masterSwitch = FakeMasterSwitch()
        daemonSupervisor = RecordingDaemonSupervisor()
        runRegistry = RunAdmissionGateImpl()
        runHistory = InMemoryRunHistoryRepository()
        serviceState = FakeServiceStateProvider()
        breaker = FakeCircuitBreaker()
        warnings = mutableListOf()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ 列表与空态

    @Test
    @DisplayName("列表由 observeAll 投影，且带出名称/语言/超时/计数")
    fun `the list is projected from the repository hot flow`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "备份", timeoutSec = 30))
            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded, "Room 回过一帧后必须置位 loaded")
            val row = state.rows.single()
            assertEquals(1L, row.id)
            assertEquals("备份", row.displayName)
            assertEquals("shell", row.language)
            assertEquals("30s", row.timeoutLabel)
        }

    @Test
    @DisplayName("空列表 → 空态；但 Room 未回第一帧时不得说空")
    fun `the empty state requires a loaded list`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            // 还没 advance：第一帧都不在
            assertFalse(viewModel.uiState.value.loaded, "尚未收到第一帧时 loaded 必须是 false")
            assertFalse(
                viewModel.uiState.value.empty,
                "「还没读到」与「真的没有」必须可分 —— 否则冷启动会闪一下「还没有脚本」",
            )

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loaded)
            assertTrue(viewModel.uiState.value.empty, "确实没有脚本时才允许显示空态")
        }

    @Test
    @DisplayName("操作热流后列表自动更新（不手动刷新）")
    fun `rows follow the hot flow after a write`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
            )

            scripts.stored.value = listOf(testScript(id = 7, name = "later"))
            advanceUntilIdle()

            assertEquals(
                listOf(7L),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
        }

    // ------------------------------------------------------------ 计数三态（§0.3 ①）

    @Test
    @DisplayName("★ 计数快照未到时显示未知（…），不得显示 0")
    fun `an unknown trigger count is never rendered as zero`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            // 把计数查询挂住，制造"列表已到、计数未到"的真实窗口
            val gate = CompletableDeferred<Unit>()
            triggers.pendingGate = gate
            val viewModel = newViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.rows
                    .single()
            assertNull(row.triggerCount, "快照未到时计数必须是 null（未知）")
            assertEquals(ScriptProjections.COUNT_UNKNOWN, row.triggerSummary)
            assertNull(row.triggerCount, "未知与 0 是两件事")

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                0,
                viewModel.uiState.value.rows
                    .single()
                    .triggerCount,
                "快照到达后才是真实的 0",
            )
        }

    @Test
    @DisplayName("计数快照到达后显示真实数字")
    fun `a loaded snapshot yields real counts`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1), testScript(id = 2))
            triggers.triggers.value =
                listOf(
                    testTrigger(id = 1, scriptId = 1),
                    testTrigger(id = 2, scriptId = 1, eventType = "screen_off"),
                    testTrigger(id = 3, scriptId = 2),
                )
            val viewModel = newViewModel()
            advanceUntilIdle()

            val rows = viewModel.uiState.value.rows
            assertEquals(2, rows.first { it.id == 1L }.triggerCount)
            assertEquals(1, rows.first { it.id == 2L }.triggerCount)
            assertEquals("2 个触发器", rows.first { it.id == 1L }.triggerSummary)
        }

    @Test
    @DisplayName("快照已加载但该 id 不在其中 ⇒ 0（全表读，不在就是没有）")
    fun `an absent id in a loaded snapshot means zero`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 9))
            val viewModel = newViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.rows
                    .single()
            assertEquals(0, row.triggerCount)
            assertEquals("未配置触发器", row.triggerSummary)
        }

    @Test
    @DisplayName("首次计数失败 ⇒ countsLoaded 保持 false + 告警（不静默）")
    fun `a first snapshot failure keeps the counts unknown and warns`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            triggers.failWith = IllegalStateException("db boom")
            val viewModel = newViewModel()
            advanceUntilIdle()

            assertNull(
                viewModel.uiState.value.rows
                    .single()
                    .triggerCount,
                "首次失败后不得编造计数（应保持「未知」）",
            )
            assertTrue(
                warnings.any { it.contains("SCRIPTS_COUNT_REFRESH_FAILED") },
                "计数失败必须告警，实际=$warnings",
            )
        }

    @Test
    @DisplayName("★ 刷新失败保留旧值（清空会把已知信息变成未知）")
    fun `a refresh failure keeps the previous counts`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            triggers.triggers.value = List(3) { index -> testTrigger(id = index + 1L, scriptId = 1) }
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertEquals(
                3,
                viewModel.uiState.value.rows
                    .single()
                    .triggerCount,
            )

            triggers.failWith = IllegalStateException("db boom")
            viewModel.refreshCounts()
            advanceUntilIdle()

            assertEquals(
                3,
                viewModel.uiState.value.rows
                    .single()
                    .triggerCount,
                "刷新失败必须保留旧值，而不是把已知的 3 变成未知",
            )
            assertTrue(warnings.any { it.contains("hadSnapshot=true") }, "告警里应记为「已有快照」，实际=$warnings")
        }

    @Test
    @DisplayName("返回列表时刷新计数（快照机制的唯一刷新点）")
    fun `returning to the list refreshes the counts`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertEquals(1, triggers.allCalls, "构造时必须读一次快照")

            triggers.triggers.value = listOf(testTrigger(id = 1, scriptId = 1))
            viewModel.refreshCounts()
            advanceUntilIdle()

            assertEquals(2, triggers.allCalls, "返回列表必须再读一次")
            assertEquals(
                1,
                viewModel.uiState.value.rows
                    .single()
                    .triggerCount,
            )
        }

    // ------------------------------------------------------------ 开关（不乐观更新）

    @Test
    @DisplayName("开关成功：save 收到反转值（且正文没有被清空）")
    fun `toggling saves the inverted value with the body intact`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "s", enabled = true, content = "echo keep"))
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.toggleEnabled(scriptId = 1L, enabled = false)
            advanceUntilIdle()

            val saved = scripts.saveCalls.single()
            assertEquals(false, saved.enabled)
            assertEquals(
                "echo keep",
                saved.content,
                "列表不带正文 ⇒ 必须先 load 再写回；直接把列表里的空正文 save 回去会静默清空用户的脚本",
            )
            assertEquals(
                false,
                viewModel.uiState.value.rows
                    .single()
                    .enabled,
                "热流推送新值后开关才动",
            )
        }

    @Test
    @DisplayName("★ 开关失败：开关原地不动 + 告警（不静默）")
    fun `a failing toggle leaves the switch untouched and warns`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, enabled = true))
            scripts.saveFailure = "disk full"
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.toggleEnabled(scriptId = 1L, enabled = false)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.rows
                    .single()
                    .enabled,
                "写入失败后开关必须仍是库里的值",
            )
            assertTrue(
                warnings.any { it.contains("SCRIPTS_TOGGLE_FAILED") },
                "写入失败必须告警，实际=$warnings",
            )
            assertFalse(viewModel.busy.value, "失败后 busy 必须复位（否则界面永久卡住）")
        }

    @Test
    @DisplayName("开关遇到 load 失败：不动库、给出可区分的成因")
    fun `a toggle whose load fails reports the specific reason`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, enabled = true))
            scripts.loadResults[1L] = ScriptLoadResult.Missing
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.toggleEnabled(scriptId = 1L, enabled = false)
            advanceUntilIdle()

            assertTrue(scripts.saveCalls.isEmpty(), "读不到正文时绝不能写回（会把正文清空）")
            assertTrue(
                warnings.any { it.contains("SCRIPTS_TOGGLE_LOAD_FAILED") },
                "四种 load 失败必须可区分，实际=$warnings",
            )
        }

    @Test
    @DisplayName("开关遇到仓库抛异常：降级为告警，不让界面崩")
    fun `a crashing load is degraded to a warning`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            scripts.loadThrows = IllegalStateException("boom")
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.toggleEnabled(scriptId = 1L, enabled = false)
            advanceUntilIdle()

            assertTrue(warnings.any { it.contains("SCRIPTS_TOGGLE_CRASHED") }, "实际=$warnings")
            assertFalse(viewModel.busy.value)
        }

    // ------------------------------------------------------------ 删除确认

    @Test
    @DisplayName("长按 → 确认框带 N；确认 → 调 delete")
    fun `long press asks for confirmation and delete is called on confirm`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "危险"))
            triggers.triggers.value = List(3) { index -> testTrigger(id = index + 1L, scriptId = 1) }
            val viewModel = newViewModel()
            advanceUntilIdle()
            val row =
                viewModel.uiState.value.rows
                    .single()

            viewModel.requestDelete(row)
            advanceUntilIdle()
            assertNotNull(viewModel.pendingDelete.value, "长按必须弹确认，而不是立即删除")
            assertTrue(scripts.deleteCalls.isEmpty(), "确认之前不得删除")

            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf(1L), scripts.deleteCalls)
            assertNull(viewModel.pendingDelete.value, "确认后确认框必须关闭")
        }

    @Test
    @DisplayName("N == 0 时不显示「将一并删除」那一句")
    fun `zero triggers hides the cascade sentence`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "空"))
            val viewModel = newViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.rows
                    .single()
            val body = ScriptProjections.deleteDialogBody(row.displayName, row.triggerCount)

            assertFalse(body.contains("将一并删除"), "0 条触发器时说「将一并删除 0 条」是噪音：$body")
            assertTrue(body.contains("空"), "正文必须带名称：$body")
        }

    @Test
    @DisplayName("★ 计数未知时确认框说「触发器数未知」，不编 0")
    fun `an unknown count is not invented as zero in the dialog`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "x"))
            val gate = CompletableDeferred<Unit>()
            triggers.pendingGate = gate
            val viewModel = newViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.rows
                    .single()
            val body = ScriptProjections.deleteDialogBody(row.displayName, row.triggerCount)

            assertTrue(body.contains("触发器数未知"), "未知必须如实说未知：$body")
            assertFalse(body.contains("将一并删除 0"), "不得把未知编成 0：$body")
        }

    @Test
    @DisplayName("★ 删除返回 Failed：文案是「部分成功」，不得出现「删除失败」")
    fun `a failed delete is reported as a partial success`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1, name = "残留"))
            scripts.deleteFailure = "rm -rf failed (exit=1): denied"
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.requestDelete(
                viewModel.uiState.value.rows
                    .single(),
            )
            advanceUntilIdle()

            viewModel.confirmDelete()
            advanceUntilIdle()

            // 行删除是真相源：Failed 时行**已经没了**（列表是热流 ⇒ 弹提示时行已消失）
            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
                "元数据行应已被删除（文件失败不阻塞行删除）",
            )
            val message = ScriptProjections.deletePartialSuccess("rm -rf failed (exit=1): denied")
            assertTrue(message.contains("已删除"), "必须说清「行已删」：$message")
            assertFalse(message.contains("删除失败"), "说「删除失败」会让用户以为行还在、反复重删：$message")
            assertTrue(warnings.any { it.contains("SCRIPTS_DELETE_PARTIAL") }, "实际=$warnings")
        }

    @Test
    @DisplayName("取消删除：什么都不做")
    fun `cancelling the dialog deletes nothing`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 1))
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.requestDelete(
                viewModel.uiState.value.rows
                    .single(),
            )
            viewModel.cancelDelete()
            advanceUntilIdle()

            assertNull(viewModel.pendingDelete.value)
            assertTrue(scripts.deleteCalls.isEmpty())
            assertEquals(1, viewModel.uiState.value.rows.size, "行必须还在")
        }

    // ------------------------------------------------------------ 构造助手

    private fun newViewModel(): ScriptListViewModel =
        ScriptListViewModel(
            scripts = scripts,
            triggers = triggers,
            masterSwitch = masterSwitch,
            daemonSupervisor = daemonSupervisor,
            runRegistry = runRegistry,
            runHistory = runHistory,
            serviceState = serviceState,
            circuitBreaker = breaker,
            // 阶段 6e：告警落点改为**构造形参** `AlertSink`（生产由 DI 接到 `Log.w`）
            alertSink = AlertSink { message -> warnings += message },
        )
}
