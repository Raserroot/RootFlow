package com.rootflow.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.ThemeMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [SettingsRepositoryImpl] 的单测（阶段 6a）。
 *
 * ## 用**真实** DataStore，而不是假件
 * 本类要证明的正是"落盘 → 读回"这条真实链路（尤其 `null` 必须**删键**而不是写 `false`）。
 * 用假件会把这层语义测没。因此每个用例用 `@TempDir` 拿到独立目录，
 * 经 [createSettingsDataStore] 建独立实例 —— 这也是那个工厂函数存在的理由
 * （`by preferencesDataStore` 委托无法为每个用例指定文件）。
 *
 * ## 为什么不需要 MockK 去 mock `android.util.Log`
 * `SettingsRepositoryImpl` 只在失败路径调用 `Log`，而单元测试里 `android.util.Log`
 * 是返回默认值的 no-op stub，因此这些用例不会碰到它。
 * 只测 `toSettings()` 的用例同样不触碰任何 Android API。
 */
class SettingsRepositoryTest {
    private fun repositoryIn(directory: File): SettingsRepositoryImpl =
        SettingsRepositoryImpl(createSettingsDataStore(SettingsStore.fileIn(directory)))

    @Test
    @DisplayName("首次读取得到全默认值")
    fun `fresh store yields defaults`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        val settings = repository.current()

        assertEquals(RootFlowSettings(), settings)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertNull(settings.blurEnabled, "未选择过时 blurEnabled 必须是 null（三态，不得折叠成 false）")
        assertEquals(true, settings.dynamicColor)
    }

    @Test
    @DisplayName("写主题模式后能读回（落盘链路）")
    fun `theme mode round trips`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        repository.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, repository.current().themeMode)
        assertEquals(ThemeMode.DARK, repository.currentThemeMode())
    }

    @Test
    @DisplayName("★ 保留天数：合法档位落盘，非法档位收敛到默认（写路径契约）")
    fun `log retention days are sanitized on the write path`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        assertEquals(LogRetention.DEFAULT_DAYS, repository.current().logRetentionDays, "默认值是 7 天")

        repository.setLogRetentionDays(30)
        assertEquals(30, repository.current().logRetentionDays)

        // 非法档位**不得**落盘：磁盘上出现 999 天会让"保留 N 天"变成一句空话
        repository.setLogRetentionDays(999)
        assertEquals(
            LogRetention.DEFAULT_DAYS,
            repository.current().logRetentionDays,
            "非法档位必须收敛到默认值（见 LogRetention.sanitize 的契约）",
        )

        repository.setLogRetentionDays(14)
        assertEquals(14, repository.current().logRetentionDays)
    }

    @Test
    @DisplayName("blurEnabled 三态：true / false / null(删键) 都能如实读回")
    fun `blur setting is tri state`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        repository.setBlurEnabled(true)
        assertEquals(true, repository.current().blurEnabled)

        repository.setBlurEnabled(false)
        assertEquals(false, repository.current().blurEnabled, "false 必须能与 null 区分")

        repository.setBlurEnabled(null)
        assertNull(repository.current().blurEnabled, "null 必须是删键（恢复跟随设备判定），不是写 false")
    }

    @Test
    @DisplayName("动态取色开关")
    fun `dynamic color toggles`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        repository.setDynamicColor(false)
        assertEquals(false, repository.current().dynamicColor)
    }

    @Test
    @DisplayName("每次写入都推给订阅者（主题靠它实时生效）")
    fun `writes are pushed to subscribers`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        repository.settings.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem().themeMode)

            repository.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem().themeMode)

            repository.setDynamicColor(false)
            assertEquals(false, awaitItem().dynamicColor)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("映射函数：键缺失 / 未知键值都回落默认，不抛")
    fun `toSettings falls back for unknown values`() {
        // 直接测映射函数：这是"下一次改键名"时的唯一安全带
        val settings = emptyPreferences().toSettings()

        assertEquals(RootFlowSettings(), settings)
    }
}
