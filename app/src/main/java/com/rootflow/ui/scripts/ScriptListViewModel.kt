@file:Suppress("ktlint:standard:backing-property-naming")

package com.rootflow.ui.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.DaemonSupervisor
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.ScriptRunRegistry
import com.rootflow.domain.model.Script
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceStateProvider
import com.rootflow.ui.AlertSink
import com.rootflow.ui.master.GateInput
import com.rootflow.ui.master.MasterSwitchProjections
import com.rootflow.ui.master.ScriptRuntimeState
import com.rootflow.ui.master.WhyNotRunning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 配置页脚本列表的 ViewModel（阶段 6c）。
 *
 * ## 职责边界：它只认识 `domain` 端口
 * | 依赖 | 用途 |
 * |---|---|
 * | [ScriptRepository] | `observeAll()` 驱动列表热流；`save()` 就地 toggle；`delete()` |
 * | [TriggerRepository] | **计数快照**（`all()` 一次查询后按 `scriptId` 分组） |
 *
 * **没有任何 `data/` 或 `runtime/` 的 import**（`AGENTS.md` 分层约束）。
 *
 * ## ★ 计数为什么是"快照 + 返回时刷新"（已批准决策 3）
 * `TriggerRepository` **没有** `observeAll()`：全表热流在 domain 端口上不存在
 * （只有 `observeForScript(scriptId)` 按脚本的）。为一个纯计数再加一个全表观察端口，
 * 违反"不加无消费者端口"的纪律，且计数的变化点只有一个 —— 用户在编辑器里改触发器之后。
 * 因此：**进入列表时读一次快照 + 从编辑器返回时再读一次**（[refreshCounts]）。
 *
 * ## ★ 三态：不得把"还不知道"渲染成"0 条"（§0.3 ①）
 * `scripts`（Room 热流）与 `counts`（一次性查询）**到达时间不同**。
 * 列表行的计数因此是 `Int?`：`null` = **未知**（显示 `…`），`0` = 真的没有。
 * 详见 [TriggerCounts] 的 KDoc。
 *
 * ## 生命周期
 * 列表页的 ViewModel 挂在**它的路由**上（`ScriptsTab` 的 `NavBackStackEntry`），
 * 而不是 Activity：离开配置 Tab 再回来时列表会重新订阅（Room 热流立刻给出当前值），
 * 而**编辑器的"未保存改动"语义要求独立的、与路由同寿的作用域**（`PROJECT_STATE.md` 6c 开工前必读第 2 条）。
 *
 * @param scripts 脚本仓库端口
 * @param triggers 触发器仓库端口（只用于计数）
 */
@HiltViewModel
class ScriptListViewModel
    @Inject
    constructor(
        private val scripts: ScriptRepository,
        private val triggers: TriggerRepository,
        /** 总闸（P4）：行状态要区分"脚本自己的开关关了"与"被总开关暂停了"。 */
        private val masterSwitch: MasterSwitch,
        /** 监工（P4）：「为什么没跑」要看得见"在不在监管名单"与"为什么被放弃重启"。 */
        private val daemonSupervisor: DaemonSupervisor,
        /** 运行态（P4）："此刻有没有运行中的实例"。 */
        private val runRegistry: ScriptRunRegistry,
        /** 运行历史（P4）：「为什么没跑」里的"上次成功 / 上次尝试"。 */
        private val runHistory: RunHistoryRepository,
        /** 服务状态（P4）：常驻脚本由前台服务承载，服务不在就没有监管。 */
        private val serviceState: ServiceStateProvider,
        /** 熔断（P4）：安全模式是门控链的**第一层**。 */
        private val circuitBreaker: CircuitBreaker,
        alertSink: AlertSink = AlertSink { },
    ) : ViewModel() {
        /**
         * 告警落点（构造注入；生产由 DI 接到 `Log.w`，单测注入记录器）。
         *
         * 理由同 `HomeViewModel.onWarning`：本项目的单测在纯 JVM 下跑，
         * `android.util.Log` **没有被 stub**（`AGENT_PROTOCOL.md §5.10`），
         * 在被测路径上直接 `Log.*` 会让用例因为"日志"而红。
         * 而告警本身不能删（那会变成静默失败），因此注入一条缝让"确实告警了"可断言。
         *
         * ★ 阶段 6e：它此前是 `internal var` ⇒ **生产侧从未接线**（Dagger 构造，没人写它），
         * 真机上一行告警都没有。改为构造形参后，改回 `var` 会编译不过。
         */
        private val onWarning: (String) -> Unit = alertSink::warn

        /**
         * Room 脚本热流（**只订阅一次**）。
         *
         * 两处消费（列表本体与"是否读到过第一帧"）都挂在这一个上游上：
         * 每次 `observeAll()` 都建一条新的 Room 查询流，订阅两次就是两条。
         */
        private val scriptRows: Flow<List<Script>> = scripts.observeAll()

        /** 列表的原始元数据（Room 热流投影；**不含正文**，见 `ScriptRepository.observeAll`）。 */
        private val scriptList: StateFlow<List<Script>> =
            scriptRows.stateIn(
                scope = viewModelScope,
                // Eagerly：列表是配置页的主内容，切换到本 Tab 就该立刻有值
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

        /**
         * 是否已经从 Room 读到过第一帧。
         *
         * ## 为什么必须有这个位
         * "Room 还没回第一帧"与"库里真的一个脚本都没有"在 `List<Script>` 上**不可区分**
         * （两者都是 `emptyList()`）。少了它，冷启动的第一帧会闪一下"还没有脚本"
         * —— 那是**假信息**（用户可能有 20 个脚本），与 §0.3 ① 对计数要求的纪律同源。
         */
        private val listLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val _counts: MutableStateFlow<TriggerCounts> = MutableStateFlow(TriggerCounts.Unknown)

        /** 计数快照（三态，见 [TriggerCounts]）。 */
        val counts: StateFlow<TriggerCounts> = _counts.asStateFlow()

        /**
         * 运行态快照（P4，**三态**，见 [ScriptRuntimeStates]）。
         *
         * ## 为什么它也是"快照 + 返回时刷新"（与计数同一个理由）
         * 两个数据源的性质与 `TriggerCounts` 完全同款：
         * - `ScriptRunRegistry.isRunning` 是 **suspend 一次查询**（内存读，没有 Flow）
         * - `DaemonSupervisor.supervisedIds()` / `givenUpReasons()` 是**同步内存快照**（也没有 Flow）
         *
         * 为它们各加一个热流端口会违反"不加无消费者端口"的纪律（消费者只有这一处 UI），
         * 而这三样都是**进程内瞬态**：真正会改变它们的事件（服务启停、脚本启停）
         * 都伴随着列表侧的重新进入 ⇒ 那一刻刷新即可。
         */
        private val _runtime: MutableStateFlow<ScriptRuntimeStates> = MutableStateFlow(ScriptRuntimeStates.Unknown)

        /** 运行态快照（三态，见 [ScriptRuntimeStates]）。 */
        val runtime: StateFlow<ScriptRuntimeStates> = _runtime.asStateFlow()

        /**
         * 时间来源（测试缝）。
         *
         * 「为什么没跑」要把"上次成功"渲染成"3 小时前"，而那是**相对当前时刻**算的。
         * 直接调 `System.currentTimeMillis()` 会让断言随真实时间漂移
         * （与 `DaemonSupervisorImpl.elapsedRealtimeMillis` 同款处理）。
         */
        internal var clock: () -> Long = System::currentTimeMillis

        private val _busy: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /** 是否有一次删除/写入正在进行（UI 据此避免连点）。 */
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        private val _messages: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = MESSAGE_BUFFER)

        /**
         * 一次性提示（Snackbar）。
         *
         * 用 `SharedFlow` 而不是 `StateFlow`：提示是**事件**，不是状态。
         * `StateFlow` 会在重组后重放同一条提示（用户会看到同一条 Snackbar 反复出现）。
         */
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        /** 删除确认框的目标（`null` = 不显示确认框）。 */
        private val _pendingDelete: MutableStateFlow<ScriptRowUi?> = MutableStateFlow(null)

        val pendingDelete: StateFlow<ScriptRowUi?> = _pendingDelete.asStateFlow()

        /** 列表页全部状态（单一不可变值 ⇒ 一次重组拿到一致的一帧）。 */
        val uiState: StateFlow<ScriptListUiState> =
            combine(
                combine(scriptList, _counts, listLoaded) { list, counts, loaded ->
                    Triple(list, counts, loaded)
                },
                combine(_busy, masterSwitch.enabled, _runtime) { busy, enabled, runtime ->
                    RowFlags(busy = busy, masterEnabled = enabled, runtime = runtime)
                },
            ) { core, flags ->
                ScriptListUiState(
                    rows =
                        core.first.map { script ->
                            ScriptProjections.row(
                                script = script,
                                count = core.second.forScript(script.id),
                                masterEnabled = flags.masterEnabled,
                                runtime = flags.runtime.forScript(script.id),
                            )
                        },
                    loaded = core.third,
                    busy = flags.busy,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ScriptListUiState.Initial,
            )

        /** 「为什么没跑」的内容（`null` = 不显示弹窗）。 */
        private val _whyNotRunning: MutableStateFlow<WhyNotRunning?> = MutableStateFlow(null)

        val whyNotRunning: StateFlow<WhyNotRunning?> = _whyNotRunning.asStateFlow()

        /** 关闭「为什么没跑」弹窗。 */
        fun dismissWhyNotRunning() {
            _whyNotRunning.value = null
        }

        init {
            // "读到过第一帧"与"计数快照"各起一条作业，互不阻塞：
            // 计数要等一次 DB 查询，而列表**不该**因此晚一帧出现。
            viewModelScope.launch {
                scriptRows.collect { listLoaded.value = true }
            }
            // ★ P4：运行态必须**等第一帧之后**再刷。
            //
            //   真机实测（P4）：切到配置页时 Room 还没回第一帧，`refreshRuntime()` 会如实记一行
            //   `SCRIPTS_RUNTIME_REFRESH_SKIPPED` 并返回 —— 而**没有任何机制会再补一次**
            //   ⇒ 运行态永久停在"未知"，列表一直显示"状态未知"（除非用户恰好又切一次 Tab）。
            //   `first()` 挂起直到**第一次发射**（空库也会发射 `emptyList`）⇒ 那个窗口就不存在了。
            viewModelScope.launch {
                scriptRows.first()
                refreshRuntime()
            }
            refreshCounts()
        }

        /**
         * 刷新**运行态**快照（P4）。
         *
         * 调用时机：`init` + **从编辑器返回**（与 [refreshCounts] 同款），
         * 以及**点开「为什么没跑」之前**（那一刻用户问的正是"它现在跑没跑"）。
         *
         * ## 还没读到第一帧时**不落快照**
         * 那时 `scriptList.value` 是空的，算出来的是一个"每个脚本都不在跑"的空快照 ——
         * 而它是**假信息**（真实情况可能是有脚本正在跑）。因此直接返回，
         * 让快照停在"未知"上（行内显示"状态未知"而不是"已停止"）。
         */
        fun refreshRuntime() {
            viewModelScope.launch {
                try {
                    if (scriptList.value.isEmpty() && !listLoaded.value) {
                        onWarning("SCRIPTS_RUNTIME_REFRESH_SKIPPED reason=no first frame from room yet")
                        return@launch
                    }
                    val givenUp = daemonSupervisor.givenUpReasons()
                    val byId =
                        scriptList.value.associate { script ->
                            script.id to
                                ScriptRuntimeState(
                                    running = runRegistry.isRunning(script.id),
                                    givenUpReason = givenUp[script.id],
                                )
                        }
                    _runtime.value = ScriptRuntimeStates(byId = byId, loaded = true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // 与计数同款：**不清空旧值**（清空会把已知信息变成未知）
                    onWarning("SCRIPTS_RUNTIME_REFRESH_FAILED loaded=${_runtime.value.loaded}: ${describe(error)}")
                }
            }
        }

        /**
         * 打开「为什么没跑」（P4 / 方案 §5.3）。
         *
         * ## 为什么这里**重新读一遍**而不是用 [runtime] 的快照
         * 用户点这一下，问的正是"它现在到底跑没跑"。用一份可能已经过期的快照回答，
         * 会把整个功能变成误导 —— 而它的全部价值就在于**准确归因**。
         *
         * ## 任何一步读失败都不编结论
         * 读失败时该层保持 `null`/未知，由投影如实写成"读取失败：不知道此刻是否在跑"
         * 或 [WhyNotRunning.UNKNOWN_REASON]。**绝不**把读不到当成"没在跑"。
         */
        fun openWhyNotRunning(scriptId: Long) {
            viewModelScope.launch {
                val script = scriptList.value.firstOrNull { it.id == scriptId }
                if (script == null) {
                    onWarning("SCRIPTS_WHY_MISSING id=$scriptId reason=row vanished from the hot list")
                    return@launch
                }
                val running = runCatching { runRegistry.isRunning(scriptId) }.getOrNull()
                val givenUp = runCatching { daemonSupervisor.givenUpReasons() }.getOrDefault(emptyMap())
                val supervised =
                    runCatching { daemonSupervisor.supervisedIds().contains(scriptId) }.getOrDefault(false)
                val recent = runCatching { runHistory.recentForScript(scriptId, WHY_HISTORY_LIMIT) }.getOrNull()
                val lastAttempt = recent?.firstOrNull()

                _whyNotRunning.value =
                    MasterSwitchProjections.whyNotRunning(
                        GateInput(
                            scriptName = ScriptProjections.displayName(id = script.id, name = script.name),
                            scriptEnabled = script.enabled,
                            resident = script.resident,
                            masterEnabled = masterSwitch.enabled.value,
                            serviceRunning = serviceState.state.value is ForegroundState.Running,
                            safeMode = circuitBreaker.safeMode.value,
                            running = running,
                            supervised = supervised,
                            givenUpReason = givenUp[scriptId],
                            lastSuccessAt = recent?.firstOrNull { it.exitCode == 0 }?.startedAt,
                            lastAttemptAt = lastAttempt?.startedAt,
                            lastExitCode = lastAttempt?.exitCode,
                            nowMillis = clock(),
                        ),
                    )
            }
        }

        /**
         * 刷新触发器计数快照。
         *
         * 调用时机（只有两处）：`init` 与**从编辑器返回**（`ScriptsTab` 的 pop 回调）。
         *
         * ## 失败时不静默，且**不清空旧值**
         * - 首次失败 ⇒ `loaded` 保持 `false`，全部显示"未知" + 一条"触发器计数不可用"
         * - 后续失败 ⇒ `loaded` 保持 `true` **且保留旧值**（清空会把已知信息变成未知），
         *   只加一条"显示的是上次结果"
         *
         * 另：刷新**不做重入保护**。这不是疏漏 —— 每次刷新都是等值的一次只读查询，
         * 重复执行只是多一次 `all()`，而"因为怕重入而漏掉返回时的刷新"会让计数**长期停在旧值**
         * （那正是这个快照机制唯一的失败形态）。
         */
        fun refreshCounts() {
            viewModelScope.launch {
                try {
                    val snapshot = triggers.all()
                    val grouped = snapshot.groupingBy { it.scriptId }.eachCount()
                    _counts.value = TriggerCounts(byId = grouped, loaded = true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    val hadSnapshot = _counts.value.loaded
                    onWarning("SCRIPTS_COUNT_REFRESH_FAILED hadSnapshot=$hadSnapshot: ${describe(error)}")
                    // 只发提示；`_counts` 原样不动（见 KDoc：不清空旧值）
                    _messages.tryEmit(ScriptProjections.countsUnavailable(hadSnapshot))
                }
            }
        }

        /**
         * 就地切换启用开关。
         *
         * ## 为什么先写库再改 UI（而不是乐观更新）
         * 列表是 `observeAll()` 的**热流**，UI 永远显示库里的真相。乐观更新会引入
         * "界面上开着、库里是关的"这一中间态，而阶段 6b 的真机缺陷（触发器 `enabled=0`
         * 导致 dispatch 零匹配，`PROJECT_STATE.md`「6b 项 2」）正是这种不一致造成的。
         * 因此这里**不做乐观更新**：写入成功 → Room 热流自然推送新值；
         * 写入失败 → 开关**不动**（回滚是"没有动过"，不是"动完再动回来"），并 Snackbar 告警。
         */
        fun toggleEnabled(
            scriptId: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                val target =
                    scriptList.value.firstOrNull { it.id == scriptId }
                        ?: run {
                            onWarning("SCRIPTS_TOGGLE_MISSING id=$scriptId reason=row vanished from the hot list")
                            return@launch
                        }
                _busy.value = true
                try {
                    // 列表不携带正文（`observeAll` 的有意设计）⇒ 这里不能直接 `save(target)`：
                    // 那会把正文写成空串，**静默清空用户的脚本**。因此按 id 走 `load` 取回正文，
                    // 只改 `enabled` 后写回。
                    val loaded = scripts.load(scriptId)
                    if (loaded !is ScriptLoadResult.Ok) {
                        onWarning("SCRIPTS_TOGGLE_LOAD_FAILED id=$scriptId result=${loaded::class.simpleName}")
                        _messages.tryEmit(toggleFailureMessage(loaded))
                        return@launch
                    }
                    when (val result = scripts.save(loaded.script.copy(enabled = enabled))) {
                        is WriteResult.Ok -> Unit
                        is WriteResult.Failed -> {
                            onWarning("SCRIPTS_TOGGLE_FAILED id=$scriptId: ${result.reason}")
                            _messages.tryEmit("启用状态未修改：${result.reason}")
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_TOGGLE_CRASHED id=$scriptId: ${describe(error)}")
                    _messages.tryEmit("启用状态未修改：${describe(error)}")
                } finally {
                    _busy.value = false
                }
            }
        }

        /** 长按某行 → 弹删除确认（**不立即删**，已批准决策 2）。 */
        fun requestDelete(row: ScriptRowUi) {
            _pendingDelete.value = row
        }

        /** 关闭删除确认框（用户点"取消"或点外部）。 */
        fun cancelDelete() {
            _pendingDelete.value = null
        }

        /**
         * 确认删除。
         *
         * ## `Failed` = **部分成功**（§0.3 ②）
         * `ScriptRepository.delete` 先删元数据行（级联删触发器）再删正文目录，
         * **文件删除失败不阻塞行删除**。⇒ `Failed` 时行**已经没了**，
         * 文案必须是"已删除但清理失败"，见 [ScriptProjections.deletePartialSuccess]。
         */
        fun confirmDelete() {
            val target = _pendingDelete.value ?: return
            _pendingDelete.value = null
            viewModelScope.launch {
                _busy.value = true
                try {
                    when (val result = scripts.delete(target.id)) {
                        is WriteResult.Ok -> Unit
                        is WriteResult.Failed -> {
                            onWarning("SCRIPTS_DELETE_PARTIAL id=${target.id}: ${result.reason}")
                            _messages.tryEmit(ScriptProjections.deletePartialSuccess(result.reason))
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_DELETE_CRASHED id=${target.id}: ${describe(error)}")
                    _messages.tryEmit(ScriptProjections.deletePartialSuccess(describe(error)))
                } finally {
                    _busy.value = false
                }
            }
        }

        // ------------------------------------------------------------------ 内部

        private fun toggleFailureMessage(result: ScriptLoadResult): String =
            when (result) {
                is ScriptLoadResult.Ok -> ""
                ScriptLoadResult.Missing -> "启用状态未修改：正文文件不存在"
                is ScriptLoadResult.Corrupted -> "启用状态未修改：正文与记录摘要不符（文件被外部改动）"
                ScriptLoadResult.NotFound -> "启用状态未修改：脚本元数据不存在"
                is ScriptLoadResult.Unavailable -> "启用状态未修改：root 通道不可用（${result.reason}）"
            }

        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        private companion object {
            /**
             * 一次性提示的缓冲容量。
             *
             * 取 8 的理由：同一屏上最多可能有"删除部分成功"+"启用失败"+"计数不可用"三条，
             * 而 `tryEmit` 在缓冲满时会**丢弃**（不阻塞 UI）。容量大于"一屏能触发的提示条数"，
             * 就够了；再大只是掩盖"用户在疯狂连点"这一信号。
             */
            const val MESSAGE_BUFFER: Int = 8

            /**
             * 「为什么没跑」回看多少条运行历史。
             *
             * 取 5：要回答的是"**上次成功**是什么时候"，而它可能不是**上一次**尝试
             * （中间夹着若干次失败）。5 条足以覆盖"最近几次连续失败"这种最常见的情形，
             * 又不必把历史翻个大半（那是历史页的职责）。找不到成功就如实显示"从未"。
             */
            const val WHY_HISTORY_LIMIT: Int = 5
        }
    }

/**
 * 取某脚本的触发器计数（**三态**）。
 *
 * @param snapshotLoaded 快照是否已成功读过一次（`TriggerCounts.loaded`）
 * @return `null` = 未知；`0` 或 `N` = 已知
 *
 * ## 为什么 `snapshotLoaded == true` 但 id 不在 map 里 ⇒ `0`
 * 快照是**全表**读的（`TriggerRepository.all()`），因此"快照已加载但该 id 不在里面"
 * 只有一个含义：该脚本确实没有触发器。
 *
 * 唯一的小窗口是"脚本刚建、计数快照还没刷新" —— 那时该 id 也不在 map 里，
 * 但我们显示的是 `0` 而不是 `…`。这个窗口的代价是"刚建的脚本短暂显示未配置触发器"，
 * 而"未配置触发器"对新建脚本**本来也几乎总是对的**（新建脚本没有触发器）。
 * 反过来若为此永久显示 `…`，用户就永远看不到 `0` 与"未知"的区别了。
 */
private fun TriggerCounts.forScript(scriptId: Long): Int? {
    if (!loaded) return null
    return byId[scriptId] ?: 0
}

/**
 * 运行态快照（P4，**三态**载体，与 [TriggerCounts] 同款设计）。
 *
 * @property byId 脚本 id → 运行态。**只反映已成功读到的快照**
 * @property loaded 快照是否已经**成功**读过一次
 *   - `false`（未知）⇒ 行内显示"状态未知"，**不显示"已停止"**
 *   - `true`（下结论）⇒ 在 [byId] 里就是真实运行态；不在就是"没在跑且没放弃过"
 */
data class ScriptRuntimeStates(
    val byId: Map<Long, ScriptRuntimeState>,
    val loaded: Boolean,
) {
    companion object {
        /** 冷启动：还没读过任何快照 ⇒ 一律"未知"。 */
        val Unknown: ScriptRuntimeStates = ScriptRuntimeStates(byId = emptyMap(), loaded = false)
    }
}

/**
 * 取某脚本的运行态（三态：快照未加载 ⇒ [ScriptRuntimeState.Unknown]）。
 *
 * ## 为什么 `loaded == true` 但 id 不在 map 里 ⇒「没在跑」
 * 快照是按**全部脚本**算的（来源是 `scriptList`），因此"快照已加载但该 id 不在里面"
 * 只有一个含义：该脚本既没在跑、也没被放弃重启。
 *
 * 推理与 [TriggerCounts.forScript] 完全同款（那里解释了同一个窗口的代价）。
 */
private fun ScriptRuntimeStates.forScript(scriptId: Long): ScriptRuntimeState {
    if (!loaded) return ScriptRuntimeState.Unknown
    return byId[scriptId] ?: ScriptRuntimeState(running = false, givenUpReason = null)
}

/**
 * `combine` 的辅助载体：把"忙碌位 + 总闸 + 运行态快照"打包成一个类型。
 *
 * 用 `private data class` 而不是嵌套 `Triple`：母 `combine` 只能到 5 参，
 * 而这三个值要一起参与行投影 —— 打包后字段名看得见，读起来不必数位置。
 * 与 `ScriptEditorViewModel.PermissionAndFlags` 同款做法。
 */
private data class RowFlags(
    val busy: Boolean,
    val masterEnabled: Boolean,
    val runtime: ScriptRuntimeStates,
)
