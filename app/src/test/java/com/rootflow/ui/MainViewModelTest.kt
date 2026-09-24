package com.rootflow.ui

import app.cash.turbine.test
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.SettingsRepository
import com.rootflow.domain.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [MainViewModel] 的单测（阶段 6a）。
 *
 * ## 为什么 ViewModel 能纯 JVM 测
 * 它只依赖 `domain` 的 [SettingsRepository] 端口与 `android.util.Log`
 * （后者在纯 JVM 下是 no-op stub，可安全调用）。
 * 因此这里不需要 Robolectric —— 与项目既有的
 * `ForegroundServiceControllerTest` / `LogPipelineImplTest` 同一形态。
 *
 * ## 这里真正在守什么
 * 1. **两个真相的同步**：`currentTab`（StateFlow）与 `currentTabState`（Compose state）
 *    必须始终一致。它们是两个字段，只有 [MainViewModel.select] 一个写入口——
 *    这条断言就是那个不变量的护栏
 * 2. **越界下标不崩**：恢复路径传进来的下标可能越界（Tab 增删 / 顺序调整），
 *    必须回落默认值而不是抛 `ArrayIndexOutOfBoundsException`
 */
class MainViewModelTest {
    @Test
    @DisplayName("默认落在主页")
    fun `starts on the default tab`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            assertEquals(TabDestinations.DEFAULT, viewModel.currentTab.value)
            assertEquals(TabDestinations.DEFAULT, viewModel.currentTabState)
        }

    @Test
    @DisplayName("三个 Tab 都能切，且两份状态始终一致")
    fun `select switches tab and keeps both representations in sync`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            TabDestinations.ALL.forEach { tab ->
                val changed = viewModel.select(tab)
                if (tab != TabDestinations.DEFAULT) {
                    assertTrue(changed, "切到 $tab 应报告发生了切换")
                }
                assertEquals(tab, viewModel.currentTab.value, "StateFlow 未同步")
                assertEquals(tab, viewModel.currentTabState, "Compose state 未同步")
            }
        }

    @Test
    @DisplayName("重复点同一个 Tab 不算切换")
    fun `selecting the current tab is a no-op`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            assertFalse(viewModel.select(TabDestinations.DEFAULT), "点的就是当前 Tab，应返回 false")
            assertTrue(viewModel.select(TabDestination.SETTINGS))
            assertFalse(viewModel.select(TabDestination.SETTINGS), "重复点同一个 Tab 应为 no-op")
            assertEquals(TabDestination.SETTINGS, viewModel.currentTab.value)
        }

    @Test
    @DisplayName("currentTab 是可观察的状态流")
    fun `currentTab emits every transition`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            viewModel.currentTab.test {
                assertEquals(TabDestinations.DEFAULT, awaitItem())
                viewModel.select(TabDestination.SCRIPTS)
                assertEquals(TabDestination.SCRIPTS, awaitItem())
                viewModel.select(TabDestination.SETTINGS)
                assertEquals(TabDestination.SETTINGS, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("越界下标回落默认 Tab，不抛异常")
    fun `out of range index falls back to the default tab`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            viewModel.select(TabDestination.SETTINGS)
            // 负数与超大下标都来自"旧版本写进 savedInstanceState 的值"
            assertTrue(viewModel.selectFromIndex(-1), "越界应回落默认 Tab（与当前不同 ⇒ true）")
            assertEquals(TabDestinations.DEFAULT, viewModel.currentTab.value)
            assertEquals(TabDestinations.DEFAULT, viewModel.currentTabState)

            assertFalse(viewModel.selectFromIndex(Int.MAX_VALUE), "已回到默认 Tab ⇒ 无切换")
            assertEquals(TabDestinations.DEFAULT, viewModel.currentTab.value)
        }

    @Test
    @DisplayName("合法下标按顺序映射到三个 Tab")
    fun `valid indexes map onto ALL in order`() =
        runTest {
            val viewModel = MainViewModel(FakeSettingsRepository())

            TabDestinations.ALL.forEachIndexed { index, expected ->
                viewModel.selectFromIndex(index)
                assertEquals(expected, viewModel.currentTab.value, "下标 $index 映射错误")
                assertEquals(expected, viewModel.currentTabState, "下标 $index 未同步到 Compose state")
            }
        }

    @Test
    @DisplayName("设置从端口转发出来（主题靠它生效）")
    fun `settings are forwarded from the repository`() =
        runTest {
            val repository = FakeSettingsRepository()
            val viewModel = MainViewModel(repository)

            assertEquals(ThemeMode.SYSTEM, viewModel.settings.value.themeMode)

            repository.emit(RootFlowSettings(themeMode = ThemeMode.DARK, blurEnabled = false))

            viewModel.settings.test {
                assertEquals(ThemeMode.DARK, awaitItem().themeMode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** `SettingsRepository` 的内存假件（不触碰 DataStore）。 */
    private class FakeSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(RootFlowSettings())

        override val settings: StateFlow<RootFlowSettings> = state

        override suspend fun current(): RootFlowSettings = state.value

        override suspend fun setThemeMode(mode: ThemeMode) {
            state.value = state.value.copy(themeMode = mode)
        }

        override suspend fun setBlurEnabled(enabled: Boolean?) {
            state.value = state.value.copy(blurEnabled = enabled)
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            state.value = state.value.copy(dynamicColor = enabled)
        }

        /** 阶段 6d 新增的接口方法：主界面用例不涉及保留天数，如实写入即可。 */
        override suspend fun setLogRetentionDays(days: Int) {
            state.value = state.value.copy(logRetentionDays = days)
        }

        /** 阶段 7 新增的接口方法：主界面用例不涉及液态玻璃开关，如实写入即可。 */
        override suspend fun setLiquidGlassEnabled(enabled: Boolean) {
            state.value = state.value.copy(liquidGlassEnabled = enabled)
        }

        fun emit(settings: RootFlowSettings) {
            state.value = settings
        }
    }
}
