package com.rootflow.ui

import android.util.Log
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.RootFlowSettings.Companion.KeepAliveConsent
import com.rootflow.domain.settings.SettingsRepository
import com.rootflow.domain.settings.ThemeMode
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 首启知情同意的**写盘语义**单测（阶段 12c）。
 *
 * ## 为什么单独一个文件，而不是塞进 `MainViewModelTest`
 * `MainViewModelTest` 的既有用例是"纯读"路径（构造 + 切 Tab），从不执行协程；
 * 而本文件要验的正是"写盘 → 回读 → 置位"这条**异步**链，需要
 * `Dispatchers.setMain` + `advanceUntilIdle`（与 `ScriptEditorViewModelTest` 同款）。
 * 把两种纪律混在一个类里，会让"某一条用例为什么需要 setMain"变得不可见。
 *
 * ## 这里真正在守什么
 * | 断言 | 它防的失败形态 |
 * |---|---|
 * | 同意后能从磁盘读回 `true` | 声明页"点了同意但门不开" |
 * | 拒绝后落盘 `false` 而不是什么都不写 | "问过被拒"与"从没问过"在磁盘上不可区分（真机排障会失去一个事实） |
 * | **静默写失败被回读发现** | `SettingsRepository` 的契约是"写失败只告警、不抛" ⇒ 不回读的话 UI 上完全没反应 |
 * | 写抛异常时不崩、且同样置位 | 存储损坏时声明页变成一条死路（用户点了同意、永远停在原地） |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveConsentTest {
    init {
        // 纯 JVM 下 `Log.*` 会抛 `not mocked`（`AGENT_PROTOCOL.md §5.10`），
        // 而 `MainViewModel` 的两条同意路径都打日志（成功一行、失败一行）。
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @DisplayName("★ 同意：落盘为 true，且回读一致 ⇒ 不置失败位（门会开）")
    fun `accepting persists the consent and is read back`() =
        runTest(testDispatcher) {
            val repository = RecordingSettingsRepository()
            val viewModel = MainViewModel(repository)

            viewModel.acceptKeepAliveConsent()
            advanceUntilIdle()

            assertEquals(KeepAliveConsent.ACCEPTED, repository.settings.value.keepAliveConsent)
            assertEquals(listOf(true), repository.consentWrites)
            assertFalse(viewModel.consentWriteFailed.value, "写成功不得报失败（否则声明页会显示误导性的错误）")
        }

    @Test
    @DisplayName("拒绝：落盘为 false（'问过被拒'与'从没问过'在磁盘上是两件事）")
    fun `declining persists false rather than writing nothing`() =
        runTest(testDispatcher) {
            val repository = RecordingSettingsRepository()
            val viewModel = MainViewModel(repository)

            viewModel.declineKeepAliveConsent()
            advanceUntilIdle()

            assertEquals(KeepAliveConsent.DECLINED, repository.settings.value.keepAliveConsent)
            assertEquals(listOf(false), repository.consentWrites)
        }

    @Test
    @DisplayName("★ 静默写失败（不抛、没写进去）必须被回读发现，不得让声明页毫无反应")
    fun `a silent write failure is caught by the read back`() =
        runTest(testDispatcher) {
            // 这正是 `SettingsRepositoryImpl.edit` 的真实形态：写失败只 `Log.w`，不外抛。
            val repository = SilentlyDroppingSettingsRepository()
            val viewModel = MainViewModel(repository)

            viewModel.acceptKeepAliveConsent()
            advanceUntilIdle()

            assertTrue(
                viewModel.consentWriteFailed.value,
                "回读不一致必须置位 —— 否则用户看到的是'点了同意没反应'，而日志之外没有任何线索",
            )
            assertEquals(KeepAliveConsent.UNKNOWN, repository.settings.value.keepAliveConsent, "确实没写进去")
        }

    @Test
    @DisplayName("写抛异常：不崩，且同样置位（声明页不得变成死路）")
    fun `a throwing write is degraded to the failure flag`() =
        runTest(testDispatcher) {
            val repository = ThrowingSettingsRepository()
            val viewModel = MainViewModel(repository)

            viewModel.acceptKeepAliveConsent()
            advanceUntilIdle()

            assertTrue(viewModel.consentWriteFailed.value)
        }

    @Test
    @DisplayName("写入值是 true / false 之外的第三态**不可持久化**（不得把 null 写回去）")
    fun `only true or false are ever written`() =
        runTest(testDispatcher) {
            val repository = RecordingSettingsRepository()
            val viewModel = MainViewModel(repository)

            viewModel.acceptKeepAliveConsent()
            advanceUntilIdle()
            viewModel.declineKeepAliveConsent()
            advanceUntilIdle()

            assertEquals(listOf(true, false), repository.consentWrites, "写序列里不得出现第三种取值")
        }

    // ------------------------------------------------------------ 假件

    /** 会真正更新内存状态的假件（写成功路径）；`open` 是为了让两个失败形态各继承一处覆写。 */
    private open class RecordingSettingsRepository : SettingsRepository {
        protected val state = MutableStateFlow(RootFlowSettings())

        val consentWrites = mutableListOf<Boolean>()

        override val settings: StateFlow<RootFlowSettings> = state

        override suspend fun current(): RootFlowSettings = state.value

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override suspend fun setBlurEnabled(enabled: Boolean?) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setLogRetentionDays(days: Int) = Unit

        override suspend fun setKeepAliveConsent(consented: Boolean) {
            consentWrites += consented
            state.value = state.value.copy(keepAliveConsent = consented)
        }
    }

    /**
     * **写不进去但不抛**的假件 —— 对应 `SettingsRepositoryImpl.edit` 的真实形态
     * （`runCatching { dataStore.edit(...) }.onFailure { Log.w(...) }`）。
     */
    private class SilentlyDroppingSettingsRepository : RecordingSettingsRepository() {
        override suspend fun setKeepAliveConsent(consented: Boolean) {
            // 什么都不做：模拟"磁盘写失败被吞成一行告警"（不改状态，也不外抛）
        }
    }

    /** 写就抛的假件（存储损坏 / 端口实现异常）。 */
    private class ThrowingSettingsRepository : RecordingSettingsRepository() {
        override suspend fun setKeepAliveConsent(consented: Boolean) {
            error("datastore write failed")
        }
    }
}
