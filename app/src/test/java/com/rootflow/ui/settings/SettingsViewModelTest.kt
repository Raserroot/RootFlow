package com.rootflow.ui.settings

import app.cash.turbine.test
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.residue.ResidueReport
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.ThemeMode
import com.rootflow.ui.AlertSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 设置页 ViewModel（阶段 6d）。
 *
 * ## 本文件钉死的四条纪律
 * 1. **三态不被折叠**：毛玻璃的 `null`（跟随设备）必须原样写到仓库，不能变成 `false`
 * 2. **危险动作防重入**：`busy` 期间连点第二次不得真的执行第二遍
 * 3. **失败可见**：写设置 / 熔断 / 恢复失败都必须进告警缝 + Snackbar，不静默
 * 4. **恢复模式原样传达**：`RestoreOriginal` 与 `KeepDisabled` 不是同一件事
 *
 * ## 协程纪律（`AGENT_PROTOCOL.md §9`）
 * `viewModelScope` 需要 `Main`（纯 JVM 下没有）⇒ `Dispatchers.setMain(StandardTestDispatcher)`
 * 与 `runTest(testDispatcher)` 共用同一个虚拟时钟（6b `HomeViewModelTest` 建立的设施）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var settings: FakeSettingsRepository
    private lateinit var service: FakeServiceStateProvider
    private lateinit var permissions: FakePermissionStatusProvider
    private lateinit var breaker: GatedFakeCircuitBreaker
    private lateinit var runHistory: FakeRunHistoryRepository
    private lateinit var residue: FakeResidueCleaner
    private lateinit var warnings: MutableList<String>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settings = FakeSettingsRepository()
        service = FakeServiceStateProvider()
        permissions = FakePermissionStatusProvider()
        breaker = GatedFakeCircuitBreaker()
        runHistory = FakeRunHistoryRepository()
        residue = FakeResidueCleaner()
        warnings = mutableListOf()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel =
        SettingsViewModel(
            settingsRepository = settings,
            serviceState = service,
            permissionStatus = permissions,
            circuitBreaker = breaker,
            runHistory = runHistory,
            residueCleaner = residue,
            // 阶段 6e：告警落点改为**构造形参** `AlertSink`（生产由 DI 接到 `Log.w`）
            alertSink = AlertSink { message -> warnings += message },
        )

    // ------------------------------------------------------------ 初始投影

    @Test
    @DisplayName("四个端口的当前值被投影到 uiState")
    fun `the ui state projects all four ports`() =
        runTest(testDispatcher) {
            settings = FakeSettingsRepository(RootFlowSettings(themeMode = ThemeMode.DARK, blurEnabled = true))
            val states =
                mapOf(
                    AndroidPermission.POST_NOTIFICATIONS to
                        PermissionState(AndroidPermission.POST_NOTIFICATIONS, PermissionGrant.DENIED, "not granted"),
                )
            permissions.setStates(states)
            service.setState(ForegroundState.Running(enabled = 6, total = 7, safeMode = true))
            service.setSources(
                listOf(runningSource("screen"), unavailableSource("usage_stats", "permission missing: X")),
            )
            breaker = GatedFakeCircuitBreaker(initialSafeMode = true, initialReason = TripReason.Bootloop(crashes = 3))

            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(ThemeMode.DARK, state.settings.themeMode)
            assertEquals(true, state.settings.blurEnabled)
            assertTrue(state.safeMode)
            assertEquals(TripReason.Bootloop(crashes = 3), state.tripReason)
            assertEquals(1, state.permissionStates.size)
            assertEquals(2, state.sources.size)
            assertEquals(ForegroundState.Running(enabled = 6, total = 7, safeMode = true), state.service)
            assertFalse(state.busy)
        }

    // ------------------------------------------------------------ 外观写路径

    @Test
    @DisplayName("外观三项各自写到正确的方法，且毛玻璃的 null 不被折叠")
    fun `appearance writes reach the repository unchanged`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setThemeMode(ThemeMode.LIGHT)
            vm.setDynamicColor(false)
            vm.setBlurEnabled(true)
            vm.setBlurEnabled(null)
            advanceUntilIdle()

            assertEquals(listOf(ThemeMode.LIGHT), settings.themeWrites)
            assertEquals(listOf(false), settings.dynamicColorWrites)
            assertEquals(
                listOf(true, null),
                settings.blurWrites,
                "null = 恢复「跟随设备判定」，与 false（用户明确关）必须原样区分",
            )
            assertTrue(warnings.isEmpty())
        }

    @Test
    @DisplayName("设置写失败：告警可见且 UI 不崩")
    fun `a failed write is visible and does not crash`() =
        runTest(testDispatcher) {
            settings = FakeSettingsRepository(failWrites = true)
            val vm = viewModel()

            // ★ 用 Turbine 收 `messages`：`tryEmit` 到订阅者是**异步**送达，
            //   本文件最初手写 `backgroundScope.launch { collect }` 时实测丢过值
            //   （告警缝有、Snackbar 却没有 —— 那正是"静默"的一种形态）。
            vm.messages.test {
                vm.setThemeMode(ThemeMode.DARK)
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("主题 写入失败"), "失败也必须让用户看见：$message")
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, warnings.size, "失败必须进告警缝（不静默）：$warnings")
            assertEquals(ThemeMode.SYSTEM, vm.uiState.value.settings.themeMode, "写失败不得假装成功")
        }

    // ------------------------------------------------------------ 安全熔断

    @Test
    @DisplayName("立即熔断：调用一次 + 结果可见")
    fun `manual trip calls the port once and reports success`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.messages.test {
                vm.tripManually()
                advanceUntilIdle()
                assertEquals("已进入安全模式：触发器已停用", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, breaker.manualTrips)
            assertTrue(vm.uiState.value.safeMode, "熔断后状态必须变成安全模式")
            assertFalse(vm.busy.value)
        }

    @Test
    @DisplayName("busy 期间连点第二次不会真的执行第二遍")
    fun `a second tap while busy is rejected`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            breaker.gate = gate
            val vm = viewModel()
            advanceUntilIdle()

            vm.tripManually()
            vm.tripManually()
            runCurrent()
            assertTrue(vm.busy.value, "动作悬挂期间 busy 必须为 true")
            assertEquals(0, breaker.manualTrips, "尚未放行")

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, breaker.manualTrips, "第二次点击必须被 busy 挡掉（否则会写两次 flag / 两次通知）")
            assertFalse(vm.busy.value, "动作结束后 busy 必须复位")
        }

    @Test
    @DisplayName("恢复：两种模式原样传达；文案不再承诺改动用户数据")
    fun `restore passes the exact mode and reports it honestly`() =
        runTest(testDispatcher) {
            breaker = GatedFakeCircuitBreaker(initialSafeMode = true, initialReason = TripReason.Manual)
            val vm = viewModel()

            vm.messages.test {
                vm.restore(RestoreMode.RestoreOriginal)
                advanceUntilIdle()
                val first = awaitItem()

                vm.restore(RestoreMode.KeepDisabled)
                advanceUntilIdle()
                val second = awaitItem()

                // 熔断改内存级拦截后，两个模式**运行时行为相同**（都不碰用户数据）⇒
                // 文案不得再声称"还原了触发器"或"触发器保持禁用"：
                // 那是给用户一个"我的配置被改过"的错觉。
                assertFalse(first.contains("触发器"), "不得声称还原了触发器：$first")
                assertFalse(second.contains("触发器"), "不得声称触发器保持禁用：$second")
                assertNotEquals(first, second, "第二条要说明「策略已记入日志」，否则两个选项看起来完全一样")
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf(RestoreMode.RestoreOriginal, RestoreMode.KeepDisabled), breaker.restores)
            assertFalse(vm.uiState.value.safeMode)
        }

    @Test
    @DisplayName("危险动作失败：告警 + 文案 + busy 复位（finally）")
    fun `a failed dangerous action is visible and resets busy`() =
        runTest(testDispatcher) {
            breaker.actionThrows = true
            val vm = viewModel()

            vm.messages.test {
                vm.tripManually()
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("熔断失败"), "失败必须让用户看见：$message")
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, warnings.size, "失败必须进告警缝：$warnings")
            assertEquals(0, breaker.manualTrips)
            assertFalse(vm.busy.value, "即使失败，busy 也必须复位 —— 否则按钮永远点不动")
        }

    // ------------------------------------------------------------ 权限

    @Test
    @DisplayName("从系统设置页返回时重探权限；重探失败只告警")
    fun `refresh permissions probes once and warns on failure`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.refreshPermissions()
            advanceUntilIdle()
            assertEquals(1, permissions.refreshCalls)

            permissions.refreshThrows = true
            vm.refreshPermissions()
            advanceUntilIdle()
            assertEquals(2, permissions.refreshCalls)
            assertTrue(warnings.single().contains("权限刷新失败"), "重探失败必须可见（否则 UI 一直显示旧状态）")
        }

    // ------------------------------------------------------------ 日志（保留与清理）

    @Test
    @DisplayName("保留天数原样交给仓库，非法档位的收敛由实现契约负责")
    fun `retention days reach the repository unchanged`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setLogRetentionDays(30)
            vm.setLogRetentionDays(999)
            advanceUntilIdle()

            assertEquals(
                listOf(30, 999),
                settings.retentionWrites,
                "ViewModel 不做业务收敛：它把值原样交给端口（收敛是 SettingsRepository 的契约，" +
                    "由 SettingsRepositoryTest 用真实 DataStore 验证）",
            )
            assertEquals(
                LogRetention.DEFAULT_DAYS,
                settings.settings.value.logRetentionDays,
                "假件按同一契约收敛 ⇒ VM 状态里看到的是合法档位",
            )
        }

    @Test
    @DisplayName("清理按设置的保留天数算截止时间，并如实报告条数")
    fun `cleanup uses the configured retention and reports the result`() =
        runTest(testDispatcher) {
            val now = System.currentTimeMillis()
            settings = FakeSettingsRepository(RootFlowSettings(logRetentionDays = 3))
            runHistory =
                FakeRunHistoryRepository(
                    seedStartedAt =
                        listOf(
                            now - 4L * LogRetention.MILLIS_PER_DAY, // 过期
                            now - 1L * LogRetention.MILLIS_PER_DAY, // 保留期内
                        ),
                )
            val vm = viewModel()

            vm.messages.test {
                vm.cleanupLogs()
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("已清理 1 条"), "必须如实报告删了几条：$message")
                cancelAndIgnoreRemainingEvents()
            }

            val cutoff = runHistory.deletedCutoffs.single()
            val expected = now - 3L * LogRetention.MILLIS_PER_DAY
            assertTrue(
                kotlin.math.abs(cutoff - expected) < CLEANUP_CLOCK_TOLERANCE_MILLIS,
                "截止时间必须按设置的 3 天算：cutoff=$cutoff expected≈$expected",
            )
            assertEquals(1, vm.uiState.value.runCount, "清理后条数应刷新为剩余 1 条")
        }

    @Test
    @DisplayName("清理失败：告警 + 文案 + busy 复位")
    fun `a failed cleanup is visible and resets busy`() =
        runTest(testDispatcher) {
            runHistory = FakeRunHistoryRepository(seedStartedAt = listOf(0L))
            runHistory.deleteThrows = true
            val vm = viewModel()

            vm.messages.test {
                vm.cleanupLogs()
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("清理失败"), "失败必须让用户看见：$message")
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, warnings.size, "失败必须进告警缝：$warnings")
            assertFalse(vm.busy.value, "失败后 busy 也必须复位")
        }

    @Test
    @DisplayName("条数读失败时保持未知（不回落成 0）")
    fun `run count stays unknown when reading fails`() =
        runTest(testDispatcher) {
            runHistory = FakeRunHistoryRepository(seedStartedAt = listOf(0L, 1L))
            runHistory.countThrows = true
            val vm = viewModel()
            advanceUntilIdle()

            vm.refreshRunCount()
            advanceUntilIdle()

            assertEquals(null, vm.uiState.value.runCount, "读失败不得显示 0（0 是「确实没有历史」）")
            assertTrue(warnings.single().contains("运行历史条数读取失败"))
        }

    // ------------------------------------------------------------ 卸载残留（需求 §8）

    @Test
    @DisplayName("未扫描时 residue 保持 null（不谎报「没有残留」）")
    fun `residue stays unknown until scanned`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(null, vm.uiState.value.residue, "没扫过就宣布「没有残留」是假信息")

            vm.refreshResidue()
            advanceUntilIdle()
            assertEquals(1, residue.scans)
            assertTrue(vm.uiState.value.residue is ResidueScan.Ok)
        }

    @Test
    @DisplayName("清理：文案带项数，且清理后**重扫**（UI 数字跟着更新）")
    fun `clean reports the result and rescans`() =
        runTest(testDispatcher) {
            residue =
                FakeResidueCleaner(
                    initialReport = ResidueReport(orphanScriptDirs = listOf(6L, 9L), orphanTriggerRows = 1),
                ).apply { reportAfterClean = ResidueReport(emptyList(), 0) }
            val vm = viewModel()

            vm.messages.test {
                vm.cleanResidue()
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("已清理 3 项残留"), "必须如实报项数：$message")
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, residue.cleans)
            assertEquals(
                1,
                residue.scans,
                "清理后必须重扫一次（真实实现里 clean() 自己也会先扫，但那一次不经本假件计数 —— " +
                    "这里钉的是 ViewModel 侧的「清理完再扫一次」）",
            )
            val scan = vm.uiState.value.residue
            assertTrue(scan is ResidueScan.Ok && scan.report.clean, "重扫后应显示已干净：$scan")
        }

    @Test
    @DisplayName("清理失败：告警 + 文案 + busy 复位")
    fun `a failed residue clean is visible`() =
        runTest(testDispatcher) {
            residue = FakeResidueCleaner(ResidueReport(listOf(7L), 0))
            residue.cleanThrows = true
            val vm = viewModel()

            vm.messages.test {
                vm.cleanResidue()
                advanceUntilIdle()
                val message = awaitItem()
                assertTrue(message.contains("清理残留失败"), "失败必须让用户看见：$message")
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, warnings.size, "失败必须进告警缝：$warnings")
            assertFalse(vm.busy.value, "失败后 busy 也必须复位")
        }

    private companion object {
        /** 清理截止时间用真实时钟计算 ⇒ 断言留 5s 容差（避免在慢 CI 上假红）。 */
        const val CLEANUP_CLOCK_TOLERANCE_MILLIS: Long = 5_000L
    }
}
