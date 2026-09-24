package com.rootflow.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.residue.ResidueCleaner
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ServiceStateProvider
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.SettingsRepository
import com.rootflow.domain.settings.ThemeMode
import com.rootflow.ui.AlertSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页的 ViewModel（阶段 6d）。
 *
 * ## 它只认识 `domain` 端口，且**不碰任何 Android 类型**
 * 四个端口：[SettingsRepository]（外观三项）· [ServiceStateProvider]（服务状态）·
 * [PermissionStatusProvider]（8 项权限）· [CircuitBreaker]（安全模式 + 熔断/恢复）。
 * 设备事实（`isLowRamDevice` / `Build.VERSION.SDK_INT` / `BuildConfig.VERSION_NAME`）
 * **一律不进本类**：它们由 UI 层取，再交给 `SettingsProjections` 的纯函数投影。
 * 这样本类可以在纯 JVM 下完整构造与断言（决策 B：无 UI 测试，判定必须可单测）。
 *
 * ## 作用域：Activity（默认 `viewModel()` 的 owner）
 * 与 `MainViewModel` / `HomeViewModel` 同款。设置页**不在** `NavHost` 里
 * （`NavHost` 只挂在配置 Tab 内部），因此这里用 `viewModel()` 拿到的就是 Hilt 工厂构造的
 * Activity 作用域实例 —— **不存在** 6c 编辑器那个"entry 的默认工厂不是 Hilt 工厂"的坑。
 *
 * ## 危险动作（熔断 / 恢复）的三条纪律
 * 1. **二次确认在 UI 层**：本类的方法一经调用就真的执行，不自己弹框
 * 2. `busy` 期间拒绝重入（连点两次"立即熔断"会写两次 flag / 两次通知）
 * 3. 失败**必须可见**：走 [messages]（Snackbar）+ [onWarning] 缝，不静默
 *
 * @param settingsRepository 外观三项的读写端口
 * @param serviceState 前台服务状态（只读投影）
 * @param permissionStatus 权限快照与刷新（从系统设置页返回时由 UI 触发 [refreshPermissions]）
 * @param circuitBreaker 安全模式状态与熔断/恢复入口
 * @param onWarning 告警落点（见 [alertSink] 的 KDoc）
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        serviceState: ServiceStateProvider,
        private val permissionStatus: PermissionStatusProvider,
        private val circuitBreaker: CircuitBreaker,
        private val runHistory: RunHistoryRepository,
        private val residueCleaner: ResidueCleaner,
        alertSink: AlertSink = AlertSink { },
    ) : ViewModel() {
        /**
         * 告警落点（构造注入；生产由 DI 接到 `Log.w`，单测注入记录器）。
         *
         * 理由同 `HomeViewModel.onWarning` / `ScriptEditorViewModel.onWarning`
         * （`AGENT_PROTOCOL.md §5.10`：单测里 `android.util.Log` 未 stub，
         * 被测路径上直接 `Log.*` 会让用例因"日志"而红）。
         * 生产侧的"用户可见"由 [messages] 的 Snackbar 承担；本缝是**日志侧**的可见性
         * —— 6e 真机验证抓到它此前是 `internal var` ⇒ 生产从未接线 ⇒ 设备上零告警。
         */
        private val onWarning: (String) -> Unit = alertSink::warn

        private val _busy: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /**
         * 运行历史条数；`null` = 还没读到。
         *
         * ★ **不得用 0 表示"没读到"**：`0` 是"确实没有历史"，两者在 UI 上必须可区分
         * （同 6c 计数三态的 `…` 纪律）。
         *
         * 命名不带下划线：本类只把它组合进 [uiState]，**没有**对应的公开只读属性
         * —— ktlint 的 backing-property 约定要求 `_x` 必须有配对的 `x`，
         * 而为了迁就命名去暴露一个用不到的公开流是本末倒置（改这里不要改回 `runCountState`）。
         */
        private val runCountState: MutableStateFlow<Int?> = MutableStateFlow(null)

        /**
         * 残留扫描结果；`null` = 还没扫过。
         *
         * ★ **不得用"空报告"表示"没扫过"**：那会让 UI 在扫描完成前就宣布"没有残留"
         * （同 [runCountState] 的纪律）。命名不带下划线，理由同上。
         */
        private val residueState: MutableStateFlow<ResidueScan?> = MutableStateFlow(null)

        private val _messages: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = MESSAGE_BUFFER)

        /** 一次性提示（熔断/恢复的结果、设置写失败）。 */
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        /**
         * 设置页状态。
         *
         * `combine` 分三步而不是一次七个：Kotlin 的 `combine` 只有到 5 个参数的重载，
         * 而 vararg 版本要求所有流同一类型（这里类型各不相同）。
         */
        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.settings,
                serviceState.state,
                serviceState.sources,
                permissionStatus.observe(),
                circuitBreaker.safeMode,
            ) { settings, service, sources, permissions, safeMode ->
                SettingsUiState(
                    settings = settings,
                    service = service,
                    sources = sources,
                    permissionStates = permissions,
                    safeMode = safeMode,
                )
            }.combine(circuitBreaker.tripReason) { state, reason ->
                state.copy(tripReason = reason)
            }.combine(_busy) { state, busy ->
                state.copy(busy = busy)
            }.combine(runCountState) { state, runCount ->
                state.copy(runCount = runCount)
            }.combine(residueState) { state, residue ->
                state.copy(residue = residue)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = SettingsUiState(),
            )

        /** 是否正在执行危险动作（UI 据此禁用按钮）。 */
        val busy: StateFlow<Boolean> = _busy

        // ── 外观 ─────────────────────────────────────────────────────────────

        /** 写主题模式（浅色/深色/跟随系统）。 */
        fun setThemeMode(mode: ThemeMode) = writeSetting("主题") { settingsRepository.setThemeMode(mode) }

        /** 写动态取色开关。 */
        fun setDynamicColor(enabled: Boolean) = writeSetting("动态取色") { settingsRepository.setDynamicColor(enabled) }

        /**
         * 写毛玻璃开关。
         *
         * @param enabled `true`/`false` = 用户明确选择；`null` = **恢复"跟随设备判定"**
         *   （`RootFlowSettings.blurEnabled` 的三态语义，不得折叠成布尔）
         */
        fun setBlurEnabled(enabled: Boolean?) = writeSetting("毛玻璃") { settingsRepository.setBlurEnabled(enabled) }

        /**
         * 写「液态玻璃（实验）」开关（阶段 7）。
         *
         * 关掉 ⇒ 底栏立刻回到 Haze 材质（`GlassPolicy.decide` 的硬门 1）。
         * **它是实验性效果的唯一退路**，因此必须能被用户关掉（已批准决策 P5）。
         */
        fun setLiquidGlassEnabled(enabled: Boolean) =
            writeSetting("液态玻璃") { settingsRepository.setLiquidGlassEnabled(enabled) }

        // ── 安全熔断 ─────────────────────────────────────────────────────────

        /**
         * 立即熔断（需求 §5.1 第 6 条）。
         *
         * 二次确认由 UI 负责；本方法一经调用即执行。
         */
        fun tripManually() =
            runDangerous(failure = "熔断失败") {
                circuitBreaker.tripManually()
                "已进入安全模式：触发器已停用"
            }

        /**
         * 退出安全模式（需求 §5.3 第 1 条）。
         *
         * ## ★ [mode] 现在**只进日志**（总开关重构后修正）
         * 熔断改成内存级拦截之后，`CircuitBreakerImpl` 的"禁用/还原触发器"两步**都是空实现** ——
         * 熔断过程从不改动用户数据（订阅表与脚本启用位都不碰）。
         * 因此两个模式**运行时行为相同**，`mode` 的作用是把"用户当时选了哪种策略"记进日志。
         *
         * @param mode 见 [RestoreMode]；两个取值当前**不改动任何用户数据**
         */
        fun restore(mode: RestoreMode) =
            runDangerous(failure = "恢复失败") {
                circuitBreaker.restore(mode)
                when (mode) {
                    RestoreMode.RestoreOriginal -> "已退出安全模式：脚本与事件订阅原样生效"
                    RestoreMode.KeepDisabled -> "已退出安全模式：配置未改动（保持停止的策略已记入日志）"
                }
            }

        // ── 日志（阶段 6d：保留天数 + 立即清理） ──────────────────────────────

        /**
         * 写保留天数。
         *
         * 非法档位由 `LogRetention.sanitize` 收敛（写路径与读路径各收敛一次，见端口契约）。
         */
        fun setLogRetentionDays(days: Int) = writeSetting("日志保留") { settingsRepository.setLogRetentionDays(days) }

        /**
         * 读一次运行历史条数（设置页每次显示时刷新）。
         *
         * 失败只告警并把条数留在 `null`（UI 显示"读取中…"）——
         * **不得**回落成 0（那是"确实没有历史"，与"没读到"是两件事）。
         */
        fun refreshRunCount() {
            viewModelScope.launch {
                runCatching { runHistory.countAll() }
                    .onSuccess { count -> runCountState.value = count }
                    .onFailure { error -> warn("运行历史条数读取失败：${error.describe()}") }
            }
        }

        /**
         * 立即清理超出保留期的运行历史（需求 §3.2 的口径见 `LogRetention`）。
         *
         * ## 截止时间在**动作发生时**计算，不用缓存值
         * 用户在设置页停留期间时间在走；用打开页面那一刻算出的 cutoff 会把
         * "刚好过期"的一批漏掉。每次点击都重新算。
         *
         * ## `deleted == 0` 是正常结果
         * 文案由 `SettingsProjections.cleanupResultText` 给出，**不得**说成"清理失败"。
         */
        fun cleanupLogs() =
            runDangerous(failure = "清理失败") {
                val cutoff =
                    LogRetention.cutoffMillis(
                        nowMillis = System.currentTimeMillis(),
                        days = settingsRepository.settings.value.logRetentionDays,
                    )
                val deleted = runHistory.deleteOlderThan(cutoff)
                val remaining = runCatching { runHistory.countAll() }.getOrNull()
                runCountState.value = remaining
                SettingsProjections.cleanupResultText(deleted = deleted, remaining = remaining)
            }

        // ── 卸载残留（需求 §8） ───────────────────────────────────────────────

        /**
         * 扫一次残留（设置页每次显示时刷新）。
         *
         * 端口本身返回"可用/不可用"的显式结果；这里再兜一层 `runCatching` 是为了
         * 实现换了但契约破了时不至于把异常抛到 UI（那时用户看到的会是"设置页崩了"）。
         */
        fun refreshResidue() {
            viewModelScope.launch {
                residueState.value =
                    runCatching { residueCleaner.scan() }
                        .getOrElse { error -> ResidueScan.Unavailable("扫描残留失败：${error.describe()}") }
            }
        }

        /**
         * 清理残留（需求 §8）。
         *
         * ## 清理完**立刻重扫**
         * 结果里的"已清理 N 项"讲的是这次动作，而 UI 上的"当前 N 项残留"要反映**现在**。
         * 两者用同一个数字会让"部分成功"看起来像"已经干净了"。
         */
        fun cleanResidue() =
            runDangerous(failure = "清理残留失败") {
                val result = residueCleaner.clean()
                residueState.value =
                    runCatching { residueCleaner.scan() }
                        .getOrElse { error -> ResidueScan.Unavailable("扫描残留失败：${error.describe()}") }
                SettingsProjections.residueCleanResultText(result)
            }

        // ── 权限 ─────────────────────────────────────────────────────────────

        /**
         * 重新探测权限。
         *
         * 调用时机（决策 6：权限变化没有可靠广播）：**从系统设置页返回时**由 UI 触发
         * （`LifecycleEventEffect(ON_RESUME)`）——否则用户授完权回来，本页还显示"未授予"。
         */
        fun refreshPermissions() {
            runCatching { permissionStatus.refresh() }.onFailure { error ->
                warn("权限刷新失败：${error.describe()}")
            }
        }

        // ── 内部 ─────────────────────────────────────────────────────────────

        /**
         * 写设置：失败**不抛给 UI**（端口契约），但仍要**可见**。
         *
         * 端口契约（`SettingsRepository` KDoc）已保证读失败回默认值、写异常不外溢；
         * 这里再兜一层是为了"实现换了但契约破了"时不至于静默。
         */
        private fun writeSetting(
            label: String,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                runCatching { block() }.onFailure { error ->
                    val text = "$label 写入失败：${error.describe()}"
                    warn(text)
                    _messages.tryEmit(text)
                }
            }
        }

        /**
         * 危险动作的统一包装：防重入 + 结果可见 + 异常不外溢。
         *
         * @param action 动作本身；**返回**给用户看的那句成功文案
         *   （清理需要把"删了多少条"带出来，因此成功文案由动作产生而不是外部常量）
         */
        private fun runDangerous(
            failure: String,
            action: suspend () -> String,
        ) {
            viewModelScope.launch {
                if (_busy.value) return@launch
                _busy.value = true
                try {
                    _messages.tryEmit(action())
                } catch (cancellation: CancellationException) {
                    // 协程取消不是失败：原样上抛（吞掉会让作用域无法正常收敛）
                    throw cancellation
                } catch (error: Throwable) {
                    val text = "$failure：${error.describe()}"
                    warn(text)
                    _messages.tryEmit(text)
                } finally {
                    _busy.value = false
                }
            }
        }

        private fun warn(message: String) {
            onWarning(message)
        }

        private fun Throwable.describe(): String = message ?: this::class.java.simpleName

        private companion object {
            const val MESSAGE_BUFFER: Int = 8
        }
    }
