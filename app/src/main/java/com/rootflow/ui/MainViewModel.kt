package com.rootflow.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 主界面的 ViewModel（阶段 6a）。
 *
 * ## 它只管两件事，且都是"跨 Tab 的全局状态"
 * | 状态 | 为什么在这里 |
 * |---|---|
 * | 当前 Tab | 底栏与内容区**必须读同一个真相**；放进某个 Tab 自己的 ViewModel 会让底栏反向依赖那个 Tab |
 * | 设置（主题 / 模糊 / 动态取色） | 主题是**整个 Activity** 的属性，不属于任何单个 Tab |
 *
 * 各 Tab 自己的数据（脚本列表、实时日志、环境信息）在 6b/6c 各自的 ViewModel 里，
 * **不往上堆**：否则这个类会迅速变成"知道一切"的上帝对象。
 *
 * ## 为什么 Tab 状态同时有 `StateFlow` 与 `mutableStateOf` 两份表示
 * - [currentTab]（`StateFlow`）是**对外契约**：可被纯 JVM 单测直接断言，也是 6b 之后
 *   "某个 Tab 里跳去另一个 Tab"这类跨页面动作的入口
 * - [currentTabState]（Compose state）是**给 UI 读的**：Tab 切换只该让内容区重组，
 *   而 `collectAsStateWithLifecycle` 会让整个 `RootFlowMain`（含底栏）一起重组
 *
 * 两者由 [select] / [selectFromIndex] **单一写入口**（[applyTab]）同步写入。
 * 不提供第二个写路径，就不会出现"两个真相不一致"（这是本设计唯一的风险点，
 * 因此写成私有方法而不是让调用方各自赋值）。
 *
 * ## 真机判读标记（`AGENT_PROTOCOL.md §7.3`）
 * `UI_TAB_SELECTED tab=<key> from=<key>` 只在**真的发生切换**时打一行。
 * 重复点同一个 Tab 不打日志：否则"点 10 次同一 Tab"与"切了 10 次"在日志里无法区分。
 *
 * @param settingsRepository 设置端口（`ui → domain`；UI 不认识 DataStore）
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        /** 当前设置。源在 `SettingsRepository`，本类只做转发。 */
        val settings: StateFlow<RootFlowSettings> =
            settingsRepository.settings.stateIn(
                scope = viewModelScope,
                // Eagerly：主题必须**立刻**可用（首帧之前），不能等第一个订阅者到来
                started = SharingStarted.Eagerly,
                initialValue = RootFlowSettings(),
            )

        private val _currentTab = MutableStateFlow(TabDestinations.DEFAULT)

        /** 当前 Tab（对外契约）。 */
        val currentTab: StateFlow<TabDestination> = _currentTab.asStateFlow()

        /** 当前 Tab（Compose 直接读）。与 [currentTab] 由 [applyTab] 同步写入。 */
        var currentTabState: TabDestination by mutableStateOf(TabDestinations.DEFAULT)
            private set

        /**
         * 切换 Tab（用户点击底栏的唯一入口）。
         *
         * @return 是否真的发生了切换（`false` = 点的就是当前 Tab）
         */
        fun select(tab: TabDestination): Boolean {
            val previous = currentTabState
            if (previous == tab) return false
            applyTab(tab = tab, previous = previous)
            return true
        }

        /**
         * 按下标切换 Tab。
         *
         * ## 为什么需要它（而不只是 [select]）
         * 6c 引入 `NavHost` 后，系统恢复 / 深层链接会**以路由字符串或下标**的形式回来。
         * 6a 先把"下标 → Tab"这条唯一的转换路径固定下来并单测其越界行为
         * （见 [TabDestinations.fromIndex]），免得将来在 Composable 里散落 `entries[i]`
         * ——那时越界就是崩溃，而不是回落。
         *
         * @return 是否真的发生了切换；越界下标回落 [TabDestinations.DEFAULT] 且**不抛**
         */
        fun selectFromIndex(index: Int): Boolean = select(TabDestinations.fromIndex(index))

        /** 两个真相的**唯一**同步点。 */
        private fun applyTab(
            tab: TabDestination,
            previous: TabDestination,
        ) {
            _currentTab.value = tab
            currentTabState = tab
            Log.i(TAG, "UI_TAB_SELECTED tab=${tab.logKey} from=${previous.logKey}")
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
