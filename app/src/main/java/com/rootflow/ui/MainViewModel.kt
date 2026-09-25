package com.rootflow.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.RootFlowSettings.Companion.KeepAliveConsent
import com.rootflow.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面的 ViewModel（阶段 6a；阶段 12c 起兼管**首启知情同意**的写入）。
 *
 * ## 它只管三件事，且都是"跨 Tab 的全局状态"
 * | 状态 | 为什么在这里 |
 * |---|---|
 * | 当前 Tab | 底栏与内容区**必须读同一个真相**；放进某个 Tab 自己的 ViewModel 会让底栏反向依赖那个 Tab |
 * | 设置（主题 / 模糊 / 动态取色 / 日志保留 / **保活同意**） | 主题是**整个 Activity** 的属性，不属于任何单个 Tab |
 * | 同意结果的**写入口**（阶段 12c） | 声明页是**首启的第一屏**，不属于任何 Tab；而"写入 + 回读校验"要求一个跨重组存活的作用域 |
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
 * 同意门另有两条：`KEEPALIVE_CONSENT_WRITTEN consent=…` 与
 * `KEEPALIVE_CONSENT_NOT_PERSISTED consent=…`（后者即"点了同意但没写进去"）。
 *
 * @param settingsRepository 设置端口（`ui → domain`；UI 不认识 DataStore）。
 *   **它是字段而不是构造形参**：同意门需要在 `viewModelScope` 里回读它
 *   （`settings` 只做转发，不持有仓库引用）
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        /** 当前设置。源在 `SettingsRepository`，本类只做转发。 */
        val settings: StateFlow<RootFlowSettings> =
            settingsRepository.settings.stateIn(
                scope = viewModelScope,
                // Eagerly：主题必须**立刻**可用（首帧之前），不能等第一个订阅者到来
                started = SharingStarted.Eagerly,
                initialValue = RootFlowSettings(),
            )

        /**
         * 保活同意**写盘失败**（阶段 12c，首启声明页）。
         *
         * ## 为什么需要这个标志（否则是一次静默失败）
         * `SettingsRepository.setKeepAliveConsent` 的契约是"写失败只告警、不抛"
         * （设置写失败比"设置页崩了"轻得多，见其 KDoc）。于是"点了同意但没写进去"这个形态
         * 在 UI 上表现为**什么都没发生**：声明页还在、再点还是一样，
         * 而日志之外没有任何东西告诉用户"你的同意没被记住"。
         *
         * ## 怎么发现的：**回读**
         * 写完再读一次（`current()` 直读 DataStore，不是内存镜像 —— 镜像的更新要等一次异步收集）。
         * 读回的值与刚写入的不一致 ⇒ 置位，声明页据此显示一句如实提示。
         * 这是零接口改动的方案：端口契约不变，失败可见性由调用方补上。
         */
        private val _consentWriteFailed: MutableStateFlow<Boolean> = MutableStateFlow(false)

        val consentWriteFailed: StateFlow<Boolean> = _consentWriteFailed.asStateFlow()

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

        // ------------------------------------------------------------------ 首启知情同意（阶段 12c）

        /**
         * 用户点了「我已知晓并同意」。
         *
         * 写盘成功之后，`settings.keepAliveConsent` 会变成 `true`，
         * 于是 UI 的三态分流切到主界面，主界面那侧的 `LaunchedEffect` 才会去拉服务 ——
         * **"先落盘、再拉起服务"** 这个顺序是首启声明能成立的前提
         * （若先拉服务后落盘，用户在这一瞬间杀进程就会留下"没同意但已在保活"的状态）。
         */
        fun acceptKeepAliveConsent() = writeKeepAliveConsent(KeepAliveConsent.ACCEPTED)

        /**
         * 用户点了「不同意并退出」。
         *
         * 仍然落盘（`false`）：它让"问过、被拒绝"与"从没问过"在磁盘上是两件事
         * （真机判读与将来的产品判断都需要这个区别）。两者在 UI 上的动作相同 —— 都是退出。
         *
         * ## ⚠️ 一处**如实的边界**：`finish()` 可能早于这次写盘
         * 调用方（`RootFlowApp` 的 `onDecline`）在发出写请求之后**立即**执行 `finish()`，
         * 而 `viewModelScope` 会随 Activity 销毁被取消 ⇒ 这次写有可能**来不及落盘**。
         *
         * **不为此加"等写完再退出"**：那样用户会盯着一个"退不出去"的界面，
         * 而写盘失败时更糟（"不同意还退不掉"）。代价也确实是零 ——
         * 磁盘上无论 `false` 还是键缺失，[toSettings] 映射出的取值**都是 `false`**，
         * 下次启动的行为完全一致。**区别只在诊断信息，不在行为。**
         */
        fun declineKeepAliveConsent() = writeKeepAliveConsent(KeepAliveConsent.DECLINED)

        /**
         * 写同意结果并**回读校验**（见 [consentWriteFailed] 的 KDoc）。
         *
         * 只写 `true` / `false`，不写 `null`：`null` 是内存镜像的初始态
         * （"还没读到磁盘"），不是一个可持久化的用户选择。
         */
        private fun writeKeepAliveConsent(consented: Boolean) {
            viewModelScope.launch {
                runCatching { settingsRepository.setKeepAliveConsent(consented) }
                    .onFailure { Log.w(TAG, "KEEPALIVE_CONSENT_WRITE_FAILED consent=$consented: ${it.message}") }

                // 回读：`current()` 直读 DataStore（内存镜像的更新要等一次异步收集，立刻读会假失败）
                val applied =
                    runCatching { settingsRepository.current().keepAliveConsent == consented }
                        .getOrDefault(false)
                _consentWriteFailed.value = !applied
                if (applied) {
                    Log.i(TAG, "KEEPALIVE_CONSENT_WRITTEN consent=$consented")
                } else {
                    Log.w(
                        TAG,
                        "KEEPALIVE_CONSENT_NOT_PERSISTED consent=$consented " +
                            "(read-back mismatch; the gate stays closed and says so)",
                    )
                }
            }
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
