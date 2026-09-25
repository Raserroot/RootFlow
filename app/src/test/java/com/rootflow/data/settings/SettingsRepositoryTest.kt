package com.rootflow.data.settings

import android.util.Log
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.RootFlowSettings.Companion.KeepAliveConsent
import com.rootflow.domain.settings.ThemeMode
import io.mockk.every
import io.mockk.mockkStatic
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
 * ## ★ 必须自己打桩 `android.util.Log`（2026-09-25 修，勿删）
 * 本类 KDoc 原先写着"不需要 mock `Log`，因为 `android.util.Log` 是返回默认值的 no-op stub"
 * —— **那句话是错的**，而且实测踩到了：
 *
 * - 本项目的单测**没有**开 `returnDefaultValues`（`AGENT_PROTOCOL.md §5.10`），
 *   任何 `Log.*` 调用都会抛 `RuntimeException: Method w in android.util.Log not mocked`
 * - `SettingsRepositoryImpl` 的**失败路径**会 `Log.w`（`edit` 的 `onFailure` /
 *   `readFailureFallback`），于是这里必须打桩
 * - **不打桩时的表现极具误导性**：`edit()` 里那句 `Log.w` 从 `onFailure` 抛出来，
 *   栈指向 `SettingsRepositoryImpl.kt:135`（一行日志），看起来像"DataStore 写失败"，
 *   而真相是"日志打不出来"
 * - 更糟的是**顺序依赖**：全量跑时别处已装好静态桩 ⇒ 本类照常绿；单独跑本类必炸。
 *   与 `ForegroundServiceControllerTest` 曾经的形态**完全同型**（11e 补丁5 登记过）
 */
class SettingsRepositoryTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private fun repositoryIn(directory: File): SettingsRepositoryImpl =
        SettingsRepositoryImpl(createSettingsDataStore(SettingsStore.fileIn(directory)))

    /**
     * 等到第一个满足条件的发射（**中间帧照收**）。
     *
     * ## 为什么需要它（12b 起，勿改回"逐帧硬断言"）
     * `writes are pushed to subscribers` 原先是"两次 `awaitItem()` 分别是 LIGHT / false"。
     * 那条写法隐含一个前提：**内存默认值与磁盘初值相等** ⇒ 镜像收集推的第一帧会被
     * `MutableStateFlow` 去重掉。
     *
     * 12b 把 `keepAliveConsent` 的键缺失映射成 `false`（"还没问过 ⇒ 要问"），
     * 于是 `toSettings()` 的结果**不再等于** `RootFlowSettings()` ⇒ **多出一帧真实发射**
     * （`null` → `false`）⇒ 硬断言的帧号全部错位。
     *
     * 这里改为"等到出现该值"，不依赖帧数：将来再有同类语义细化也不会假红。
     */
    private suspend fun TurbineTestContext<RootFlowSettings>.awaitMatch(
        predicate: (RootFlowSettings) -> Boolean,
    ): RootFlowSettings {
        var item = awaitItem()
        while (!predicate(item)) {
            item = awaitItem()
        }
        return item
    }

    @Test
    @DisplayName("首次读取得到全默认值（唯一例外：同意门的三态，见下）")
    fun `fresh store yields defaults`(
        @TempDir directory: File,
    ) = runTest {
        val repository = repositoryIn(directory)

        val settings = repository.current()

        assertEquals(
            RootFlowSettings(),
            settings.copy(keepAliveConsent = KeepAliveConsent.UNKNOWN),
            "除同意门之外，磁盘全空时每个字段都必须是内存默认值",
        )
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertNull(settings.blurEnabled, "未选择过时 blurEnabled 必须是 null（三态，不得折叠成 false）")
        assertEquals(true, settings.dynamicColor)
        // ★ 12b：`keepAliveConsent` **故意不与内存默认值相同**。
        //   两个 `Boolean?` 的 `null` 语义不同：`blurEnabled = null` 是"用户没选过"（可持久化的第三态，
        //   磁盘上表现为删键）；`keepAliveConsent = null` 只是"**内存镜像还没读到磁盘**"。
        //   磁盘上没有这个键 ⇒ "还没问过用户" ⇒ 必须落成 `false`（要问）。
        //   留成 `null` 会让全新安装（或从同意门上线前的版本升级上来）的用户**永远停在加载态**。
        assertEquals(
            KeepAliveConsent.DECLINED,
            settings.keepAliveConsent,
            "键缺失必须映射成 false（还没问过 ⇒ 要问），见 SettingsRepositoryImpl.toSettings",
        )
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
            // 首帧可能是内存默认值，也可能是镜像收集稍后推来的磁盘真相（12b 起两者不再相等，
            // 见 awaitMatch 的 KDoc）。本用例要证明的是"写入之后订阅者能看到新值"，
            // 与"第几帧"无关 —— 因此两张都用 awaitMatch 落定。
            repository.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitMatch { it.themeMode == ThemeMode.LIGHT }.themeMode)

            repository.setDynamicColor(false)
            assertEquals(false, awaitMatch { !it.dynamicColor }.dynamicColor)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("映射函数：键缺失 / 未知键值都回落默认，不抛")
    fun `toSettings falls back for unknown values`() {
        // 直接测映射函数：这是"下一次改键名"时的唯一安全带
        val settings = emptyPreferences().toSettings()

        assertEquals(
            RootFlowSettings(),
            settings.copy(keepAliveConsent = KeepAliveConsent.UNKNOWN),
            "除同意门之外，缺失的键必须逐项回落内存默认值",
        )
        assertEquals(
            KeepAliveConsent.DECLINED,
            settings.keepAliveConsent,
            "唯一例外是同意门：键缺失 ⇒ false（还没问过 ⇒ 要问），见 toSettings 的说明",
        )
    }
}
