@file:Suppress("ktlint:standard:backing-property-naming")

package com.rootflow.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.env.EnvironmentInfoProvider
import com.rootflow.domain.env.EnvironmentSnapshot
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.model.LogTail
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.domain.run.RunActivityProvider
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceStateProvider
import com.rootflow.ui.AlertSink
import com.rootflow.ui.master.MasterSwitchProjections
import com.rootflow.ui.master.MasterSwitchUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主页的 ViewModel（阶段 6b）。
 *
 * ## 职责边界：它只认识 `domain` 端口
 * | 依赖 | 用途 |
 * |---|---|
 * | [ServiceStateProvider] | 服务状态卡 + 事件源列表 |
 * | [EnvironmentInfoProvider] | 环境信息卡 |
 * | [RunActivityProvider] | 终端要订阅哪个 `runId` |
 * | [LogPipeline] | 终端的首屏快照（`tail`）与增量（`observe`） |
 * | [CircuitBreaker] | 安全模式 banner（**不新增端口**：它的状态本就是 `domain` 的 `StateFlow`） |
 *
 * **没有任何 `data/` 或 `runtime/` 的 import** —— 这是 `AGENTS.md` 分层约束的落点，
 * 也是"root 通道只在 `runtime/` + `data/`"这条纪律在 UI 侧的体现。
 * `RootFlowApp`/`MainActivity` 侧无需改动：本类由 Hilt 生成的 Activity 工厂构造
 * （6a 已核实 `Hilt_MainActivity.getDefaultViewModelProviderFactory()` 返回 Hilt 工厂，
 * 见 `RootFlowApp` 的 KDoc；`hiltViewModel()` 因 `hilt-navigation-compose` 不在离线缓存而不可用）。
 *
 * ## 为什么状态合成**一个** `HomeUiState`
 * 见 [HomeUiState] 的 KDoc：避免"服务卡已运行、源列表还是空"的中间帧。
 *
 * ## ★ 终端的两处硬语义（都有具体的失败形态，勿"简化"）
 *
 * ### ① 有界等待管道登记（[RUN_WAIT_ATTEMPTS] / [RUN_WAIT_INTERVAL_MILLIS]）
 * `TriggeredScriptRunner` 里 `coordinator.startLogging()` **不挂起**：它 `scope.launch`
 * 了收集作业就返回，随后才 `sessionRegistry.register(runId)`。因此 UI 观察到新 `runId` 时，
 * `LogPipeline.runs[runId]` **可能还不存在** ⇒ `observe(runId)` 返回 `emptyFlow()`
 * 且 `tail()` 恒空（`LogPipelineImpl` 的既有契约）。不在这个窗口里重试，
 * 终端会**永远是空的**，而失败表现正是本仓库最忌讳的形态：日志里什么都没有。
 *
 * 等待是**有界**的（10 × 200ms = 2s）：超过就如实放弃并打 `HOME_TERMINAL_NO_RUN`。
 * 无界重试会让 `runTest` 的虚拟时间活锁（`AGENT_PROTOCOL.md §9.4`）。
 *
 * ### ② 先 `tail` 再 `observe`（顺序不可换）
 * `observe` 是热流（`replay = 0`）⇒ 只推送**订阅之后**产生的批次。
 * 反过来先订阅再取 tail，两者之间到达的批次会被丢进"已读"——
 * 表现为终端中间**缺一段**。先取快照再订阅，缺的只有快照与订阅之间那几毫秒，
 * 而那一段会被管道的环形缓冲在 `tail` 里带上（`tail` 取的是**最新** N 条）。
 *
 * ## 生命周期
 * 收集作业挂在 [viewModelScope]，随 ViewModel 一起取消（`AGENTS.md`：
 * 所有长期运行任务必须有明确的生命周期作用域）。
 * 新运行到来时会先取消上一个收集作业 —— 否则两次运行的行会交错进同一个列表。
 *
 * ### ③ 运行**结束后**必须落到 Room（**真机缺陷的修复，勿删**）
 * `LogPipeline` 在运行收尾时**移除该运行的 state**（内存回收，阶段 2 起的契约）
 * ⇒ 之后 `tail()` 恒空、`observe()` 返回 `emptyFlow()`。
 *
 * 真机现象（阶段 6b 项 2）：脚本 1 秒跑完，而 UI 是在运行**结束之后**才开始收
 * （`latestRunId` 的发射与收集协程的调度之间有一拍）⇒ 终端卡长期显示
 * `#短码 · 0 行 / 该次运行暂无日志`，而 Room 里其实有全部日志。
 *
 * 因此本类同时依赖 [RunHistoryRepository]（阶段 3a 的只读端口，`RunHistoryReader`
 * 的 KDoc 早就写明这个分工："已结束运行的日志从 Room 读取，运行中的走管道快照"）：
 * - 首屏：管道 `tail()` 命中就用它；**管道为空时先查 Room 再下结论**（[awaitRun] → [awaitHistory]）
 * - 收尾：以**收尾批**或"管道已空"为提示，等 `runs.finished_at` 落库后读 Room 补齐
 *   （[syncFromHistory]）—— 合并按 `sequence` 去重，因此与实时行天然幂等
 *
 * **为什么不是"始终只读 Room"**：运行中 Room 是**分批异步落库**的，只读它会让实时日志
 * 滞后一个批次（100ms 级），而终端的核心价值恰是"实时"。
 *
 * @param serviceState 服务与事件源状态端口
 * @param runActivity 最近一次运行的标识
 * @param environment 环境信息端口
 * @param logPipeline 日志管道（**实时**来源；绝不碰 `RunHandle.output`）
 * @param runHistory 运行历史（**结束后**的完整来源）
 * @param circuitBreaker 安全模式（banner 的数据源）
 * @param onWarning 告警落点（见 [onWarning] 的 KDoc）
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        serviceState: ServiceStateProvider,
        runActivity: RunActivityProvider,
        private val environment: EnvironmentInfoProvider,
        private val logPipeline: LogPipeline,
        private val runHistory: RunHistoryRepository,
        circuitBreaker: CircuitBreaker,
        /** 总闸端口（P4）：读 [MasterSwitch.enabled] 驱动开关，[MasterSwitch.setEnabled] 是唯一写入点。 */
        private val masterSwitch: MasterSwitch,
        /** 脚本仓库：总开关卡片要显示"已启用 N 个 · 常驻 M 个"。 */
        private val scripts: ScriptRepository,
        alertSink: AlertSink = AlertSink { },
    ) : ViewModel() {
        /**
         * 告警落点（构造注入；生产由 DI 接到 `Log.w`，单测注入记录器）。
         *
         * ## ★ 为什么 ViewModel 里**不能**直接调 `android.util.Log`（实测踩到）
         * 本项目的单测在纯 JVM 下跑，`android.util.Log` **没有被 stub** ⇒ 任何 `Log.*`
         * 调用都抛 `RuntimeException: Method w in android.util.Log not mocked`。
         *
         * 后果是**成对**的，两边都不可接受：
         * - 若在被测路径上打日志，用例会因为"日志"而红，而不是因为行为错
         * - 若为了躲开它而**删掉**告警，那条路径就变成静默失败（本仓库反复禁止的形态）
         *
         * 因此改成**注入的缝**，与 `LogPipelineImpl.onWarning` /
         * `EventSourceRegistryImpl.onWarning` / `TriggerDispatcherImpl.onWarning` 同款形态。
         *
         * ## ★ 阶段 6e 真机验证抓到：它此前是 `internal var`，于是**生产侧从未接线**
         * 这四个 `@HiltViewModel` 由 Dagger 构造，没人写那个 `var` ⇒ 它永远是默认 no-op，
         * 真机上**一行告警都没有**（6e 的触发器写入告警因此完全不可见）。
         * 现在缝是**构造形参**：DI 必须给出落点（[com.rootflow.data.di.ViewModelAlertModule]
         * 接 `Log.w`），单测把记录器直接传进来。**改回 `var` 会编译不过**
         * —— 让类型系统替我们守住这条，而不是靠注释。
         *
         * ## 为什么留一个 `(String) -> Unit` 的别名而调用点不写 `alertSink.warn(…)`
         * 调用点是热路径上的**一行告警**（`onWarning("SCRIPTS_…")`），
         * 全项目有 60+ 处同形写法；换成 `.warn(…)` 只是噪声，不增加任何信息。
         * 缝的**类型**（[AlertSink]）才是要显式的那部分，名字保留旧写法。
         */
        private val onWarning: (String) -> Unit = alertSink::warn

        /** 终端状态（唯一可写点；对外只读）。 */
        private val _terminal: MutableStateFlow<TerminalState> = MutableStateFlow(TerminalState.Empty)

        /** 环境探测是否正在跑（驱动刷新按钮的转圈）。 */
        private val _environmentRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /** 当前正在收集的运行的作业；新运行到来时取消它。 */
        private var terminalJob: Job? = null

        /**
         * 当前应显示的运行标识。
         *
         * 与 `_terminal.value.runId` **分开**存在：后者在收集开始前是 `null`
         * （还没有快照），而"该收哪次运行"在这一刻就已经确定了。
         * 用一个字段而不是读 `runActivity.latestRunId.value`，是为了让
         * "谁该写终端"这个判定只依赖本类的状态（否则测试里必须让 provider 的
         * 发射与协程调度严格对齐才能复现取消路径）。
         */
        private var currentRunId: String? = null

        /**
         * 总开关卡片的一帧（P4）。
         *
         * 把"总闸 + 脚本统计 + 服务 + 事件源 + 安全模式"合成**一个**值：
         * 这五样在卡片上同时出现，分开订阅会让它们来自不同的帧
         * （"服务卡说运行中、总开关卡说未运行"正是要避免的同屏不一致）。
         */
        private val masterSwitchUi: Flow<MasterSwitchUi> =
            combine(
                masterSwitch.enabled,
                scripts.observeAll(),
                serviceState.state,
                serviceState.sources,
                circuitBreaker.safeMode,
            ) { enabled, scriptList, service, sources, safeMode ->
                MasterSwitchProjections.card(
                    masterEnabled = enabled,
                    scripts = scriptList,
                    serviceRunning = service is ForegroundState.Running,
                    sourcesRunning = HomeProjections.eventSourceRows(sources).count { it.running },
                    sourcesTotal = sources.size,
                    safeMode = safeMode,
                )
            }

        /** 主页全部状态（一次重组拿到一致的一帧，见 [HomeUiState]）。 */
        val uiState: StateFlow<HomeUiState> =
            combine(
                combine(
                    serviceState.state,
                    serviceState.sources,
                    environment.snapshot,
                    _environmentRefreshing,
                    _terminal,
                ) { service, sources, snapshot, refreshing, terminal ->
                    HomeUiState(
                        service = service,
                        sources = HomeProjections.eventSourceRows(sources),
                        environment = environmentRows(snapshot),
                        environmentLoaded = snapshot != null,
                        environmentRefreshing = refreshing,
                        terminal = terminal,
                        // 卡片那一项由外层 `combine` 的 `copy` 覆盖；此处先放初值
                        // （`combine` 的两个上游不会在同一帧到达，用初值占位是必要的，
                        //  而它是"安全默认"而不是一个体面的猜测）。
                        masterSwitch = MasterSwitchUi.Initial,
                    )
                },
                masterSwitchUi,
            ) { base, card -> base.copy(masterSwitch = card) }
                .stateIn(
                    scope = viewModelScope,
                    // 6b 只有主页这一个订阅者（`RootFlowApp` 不读它），
                    // 因此不需要"离开页面后继续跑"的行为。若 6c 有第二个订阅者，
                    // 届时评估 `WhileSubscribed` 的超时参数。
                    started = SharingStarted.Eagerly,
                    initialValue = HomeUiState.Initial,
                )

        /**
         * 总开关的一次性提示（`null` = 无）。
         *
         * ## 为什么不是 Snackbar
         * 主页没有 `Scaffold`（阶段 8 的无 AppBar 版式），为一条提示引入它是本末倒置。
         * 而"拨了开关没反应"必须被解释 —— 因此把它做成卡片下方的一行。
         *
         * ## 成功时**清空**（而不是留着上一条失败）
         * 否则"上一次写入失败"会一直挂在卡片上，用户第二次拨成功之后仍然看到它。
         */
        private val _masterSwitchNotice: MutableStateFlow<String?> = MutableStateFlow(null)

        val masterSwitchNotice: StateFlow<String?> = _masterSwitchNotice.asStateFlow()

        /**
         * 拨动总开关（P4 的唯一写入点）。
         *
         * ## 为什么不乐观更新
         * 与 `ScriptListViewModel.toggleEnabled` 同款纪律：开关的位置由
         * `MasterSwitch.enabled`（内存快照）驱动，而快照**只在写库成功后**才变
         * （`MasterSwitchImpl` 的写序）。因此这里**不做乐观置位** ——
         * 失败时开关自然弹回原位，那就是"回滚"，不需要写回滚代码。
         */
        fun setMasterSwitch(enabled: Boolean) {
            viewModelScope.launch {
                when (val result = masterSwitch.setEnabled(enabled)) {
                    is WriteResult.Ok -> {
                        _masterSwitchNotice.value = null
                        onWarning("HOME_MASTER_SWITCH_SET enabled=$enabled ok")
                    }

                    is WriteResult.Failed -> {
                        _masterSwitchNotice.value = "总开关未修改：${result.reason}"
                        onWarning("HOME_MASTER_SWITCH_FAILED enabled=$enabled: ${result.reason}")
                    }
                }
            }
        }

        /** 当前是否处于安全模式。 */
        val safeMode: StateFlow<Boolean> = circuitBreaker.safeMode

        /** 安全模式的成因文案（`null` = 成因不可还原，UI 显示"未知"而不是编一个）。 */
        val safeModeDetail: StateFlow<String?> =
            combine(circuitBreaker.tripReason, circuitBreaker.safeMode) { reason, _ ->
                reason?.let { HomeProjections.safeModeDetail(it.detail) }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

        init {
            // 进入主页即探一次环境（拉模型，见 EnvironmentInfoProvider 的 KDoc）。
            requestEnvironmentRefresh()

            viewModelScope.launch {
                runActivity.latestRunId.collect { runId ->
                    // 上一个运行的收集必须停：否则两次运行的行会混进同一个列表，
                    // 而"混日志"在真机判读时是不可逆的污染（行的归属丢了）。
                    terminalJob?.cancel()
                    // 先认领新运行，再起收集：顺序反了的话，被取消的旧收集可能在
                    // 取消生效前最后一次通过 stillCurrent 检查，把终端改回旧运行的内容。
                    currentRunId = runId
                    terminalJob = runId?.let { id -> launch { collectRun(id) } }
                }
            }
        }

        /**
         * 请求刷新环境信息（UI 的刷新按钮与 `init` 共用这一条路径）。
         *
         * ## ★ 重入保护必须在**启动协程之前**（真机暴露的缺陷形态）
         * 若把 `_environmentRefreshing.value = true` 放进 `launch { }` 内部，
         * 那么连续三次点击会在**同一个调度间隙**内全部通过 `if` 判断，
         * 于是排出**三串** root 往返（真机每次数百毫秒，且日志里出现三条
         * `HOME_ENV_PROBE` 而无法判断哪条是用户真正触发的）。
         *
         * 实测：单测 `手动刷新再探一次，且探测中重入被拒` 连红（`refreshCalls` 期望 1、实际 3）。
         * 修法就是把置位提到 `launch` **之外**（同步执行的那一段）。
         */
        fun requestEnvironmentRefresh() {
            if (_environmentRefreshing.value) return
            _environmentRefreshing.value = true
            viewModelScope.launch {
                try {
                    environment.refresh()
                } catch (cancellation: CancellationException) {
                    // 取消（离开页面）不是故障：原样上抛，不记 warning。
                    throw cancellation
                } catch (error: Throwable) {
                    // 端口的契约是"不抛"，这里是最后一道防线：主页绝不能因为探测失败而崩。
                    onWarning("HOME_ENV_REFRESH failed: ${describe(error)}")
                } finally {
                    // `finally` 而非成功路径：抛异常时转圈必须停，否则按钮永远转着。
                    _environmentRefreshing.value = false
                }
            }
        }

        /** 切换终端显示范围（ALL / OUT / ERR / SYS）。 */
        fun selectFilter(filter: TerminalStreamFilter) {
            _terminal.value = _terminal.value.withFilter(filter)
        }

        // ------------------------------------------------------------------ 内部

        /**
         * 收集一次运行的日志（见类 KDoc 的 ①② 两处硬语义）。
         *
         * 竞态保护：每次写入前检查自己仍是当前作业，且协程仍活跃。
         * 少了这道检查，被取消的旧收集可能在取消生效前**最后写一次**，
         * 把已经切到新运行的终端改回旧运行的内容（间歇性、真机极难复现的形态）。
         */
        private suspend fun collectRun(runId: String) {
            val tail = awaitRun(runId) ?: return
            if (!stillCurrent(runId)) return
            // ★ 首屏写入必须**合并**而不是替换（阶段 6b 修复，勿改回 `forRun(tail.entries)`）。
            //
            // 背景：`awaitRun` 在管道为空时会走 `awaitHistory`，而那一步已经把 Room 的完整
            // 历史合并进了 `_terminal`。若这里再用"只含管道内容"的 `tail.entries` 去 `forRun`，
            // 就会把刚补出来的历史**整批丢掉** —— 终端又变回 0 行（真机缺陷复现）。
            // `forRun` 只负责"切换到新 runId + 重置丢弃计数"，行内容一律经
            // `mergedWithHistory`（按 sequence 去重 ⇒ 幂等）。
            _terminal.value =
                _terminal.value
                    .forRun(
                        runId = runId,
                        entries = emptyList(),
                        droppedEntries = tail.droppedEntries,
                    ).mergedWithHistory(tail.entries)
            if (!stillCurrent(runId)) return

            // ② 增量：observe 是热流（replay = 0）⇒ 必须在 tail 之后立刻订阅，否则丢批。
            //
            // ★ `batch.runFinished` 时**就地**去 Room 补齐（而不是等 collect 返回）——
            //   这是实测踩到的关键点：`MutableSharedFlow` **永不完成**，所以 `collect { }`
            //   **不会返回**。把补齐逻辑写在 collect 之后 = **死代码**，运行结束后终端仍是空的
            //   （测试里表现为 `readCalls=0`）。
            //
            // 就地补齐是安全的：收尾批之后**不会再有批次**（管道已收敛），
            // 因此阻塞收集器不丢任何数据。
            try {
                logPipeline.observe(runId).collect { batch ->
                    if (!stillCurrent(runId)) return@collect
                    _terminal.value = _terminal.value.append(batch)
                    if (batch.runFinished) {
                        syncFromHistory(runId, runFinished = true)
                    }
                }
            } catch (cancellation: CancellationException) {
                // 新运行到来 / ViewModel 销毁时取消本次收集：正常路径，不上报。
                throw cancellation
            } catch (error: Throwable) {
                // 管道自身的故障不该让主页崩（管道内部已有自己的收敛逻辑）。
                onWarning(
                    "HOME_TERMINAL_FAILED runId=${runId.take(TerminalState.SHORT_ID_LENGTH)}: ${describe(error)}",
                )
            }

            // ③ 兜底：只有 collect **真的返回**时才会走到这里（热流正常情况下永不返回，
            //    因此这是防御性代码，覆盖"管道实现哪天变成有限流"这一变化）。
            //    真正的补齐发生在上面收尾批那一处 —— 那里才是可达路径。
            if (stillCurrent(runId)) {
                syncFromHistory(runId, runFinished = true)
            }
        }

        /**
         * 从 Room 补齐该运行的完整日志（阶段 6b 修复的核心）。
         *
         * ## 为什么不能只读一次（**落库是异步的**）
         * `RunHistoryCollector` 是"有界队列 + 独立消费者"，`onRunFinished` 回调
         * **早于**消费者写库（阶段 4 收尾的已批准修正里专门写过这个竞态）。
         * 因此"管道说结束了"不等于"Room 已经写全" —— 只读一次可能拿到**后半截缺失**的
         * 历史，而那种缺陷表现为"日志少了几行"，比"整屏空"更难发现。
         *
         * 权威截止信号是 **`runs.finished_at` 非空**（`RunHistoryWriter.markFinished` 的产物）：
         * 它落了 ⇒ 全部批次都已提交。因此这里：
         * 1. 有界轮询 `find(runId)`，等 `finished_at` 落库（[HISTORY_SYNC_ATTEMPTS] ×
         *    [HISTORY_SYNC_INTERVAL_MILLIS]，**必然终止** ⇒ 不违反 §9.4）
         * 2. 读到**尾部** [HISTORY_PAGE_LIMIT] 条（与 [TerminalState.MAX_ENTRIES] 等量，
         *    多读无益：合并时仍会截断）
         * 3. 合并（按 `sequence` 去重 ⇒ 幂等，重复调用安全）
         *
         * ## 轮询失败/超时不是错误
         * 落库可能因存储故障没发生（`RunHistoryWriter` 有自己的告警）。此时如实保留
         * 管道已有的内容并**不做静默丢弃** —— 只是没有更完整的历史可补。
         *
         * @param runFinished 是否已经观察到管道的收尾批（决定是否值得等落库）
         */
        private suspend fun syncFromHistory(
            runId: String,
            runFinished: Boolean,
        ) {
            var fromHistory = 0
            repeat(HISTORY_SYNC_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                val history =
                    runCatching { runHistory.readLogs(runId, limit = HISTORY_PAGE_LIMIT, offset = 0) }
                        .onFailure {
                            onWarning(
                                "HOME_HISTORY_READ_FAILED runId=${runId.take(TerminalState.SHORT_ID_LENGTH)}: " +
                                    describe(it),
                            )
                        }.getOrNull()
                if (history != null && history.isNotEmpty()) {
                    if (stillCurrent(runId)) {
                        _terminal.value = _terminal.value.mergedWithHistory(history)
                    }
                    fromHistory = history.size
                    // 落库标记已落 ⇒ 不会再有新行；否则再等一轮（有界）
                    val finishedAt = runCatching { runHistory.find(runId)?.finishedAt }.getOrNull()
                    if (finishedAt != null) return
                } else {
                    // Room 里一条都没有：可能是"还没开始写"或"根本没落库"。
                    // 运行时未收尾 ⇒ 直接放弃（不会有内容）；已收尾 ⇒ 再等一轮（写库在途）。
                    if (!runFinished) return
                }
                if (attempt < HISTORY_SYNC_ATTEMPTS - 1) delay(HISTORY_SYNC_INTERVAL_MILLIS)
            }
            if (fromHistory == 0) {
                onWarning(
                    "HOME_HISTORY_EMPTY runId=${runId.take(TerminalState.SHORT_ID_LENGTH)} " +
                        "afterMs=${HISTORY_SYNC_ATTEMPTS * HISTORY_SYNC_INTERVAL_MILLIS} " +
                        "reason=run log was neither buffered by the pipeline nor persisted to room",
                )
            }
        }

        /**
         * 有界等待管道登记该运行（见类 KDoc ①）。
         *
         * @return 首屏快照；超过 [RUN_WAIT_ATTEMPTS] 次仍未登记则 `null`（**不静默**：打警告）
         */
        private suspend fun awaitRun(runId: String): LogTail? {
            repeat(RUN_WAIT_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                // 首次也先查一次：绝大多数情况下 startLogging 已经跑完，
                // 这次查询即命中，不会白白等一个 200ms 的周期。
                val tail = runCatching { logPipeline.tail(runId) }.getOrNull()
                if (tail != null && tail.entries.isNotEmpty()) return tail
                // 空 tail 有两种可能：运行尚未登记，或运行已登记但还没产生任何行。
                // 两者无法从 `tail` 的返回值区分（`LogPipeline` 的既有契约），
                // 因此按"再等一轮"处理 —— 代价只是一次 200ms 延迟，而
                // 误判成"没有运行"会让终端整场空白（严重得多）。
                if (attempt < RUN_WAIT_ATTEMPTS - 1) delay(RUN_WAIT_INTERVAL_MILLIS)
            }
            // ★ 管道侧彻底没有内容（阶段 6b 修复）：**先看 Room 再下结论**。
            //
            // 真机现场就落在这里：脚本 1 秒跑完、管道随即移除 state，而 UI 是在
            // 运行结束之后才开始收（`latestRunId` 的发射与收集协程的调度之间有一拍）
            // ⇒ 上面 10 次轮询全部落在"运行已结束"之后 ⇒ 旧实现直接返回空快照，
            // 终端显示"0 行"，而 Room 里其实有完整日志。
            //
            // 这里同步读一次（`awaitHistory`）而不是交给调用方：调用方拿到 `null` 会
            // 直接 `return`，那才是"整场空白"的真正成因。
            val fromHistory = awaitHistory(runId)
            if (fromHistory != null) return fromHistory

            if (stillCurrent(runId)) {
                onWarning(
                    "HOME_TERMINAL_EMPTY runId=${runId.take(TerminalState.SHORT_ID_LENGTH)} " +
                        "afterMs=${RUN_WAIT_ATTEMPTS * RUN_WAIT_INTERVAL_MILLIS} " +
                        "reason=neither the log pipeline nor room holds a log for this run",
                )
            }
            // 如实返回空快照：两侧都没有内容时显示"暂无日志"比显示"上一个运行的内容"更接近事实。
            return LogTail(runId = runId, entries = emptyList(), droppedEntries = 0L)
        }

        /**
         * 有界等待 Room 里的该运行日志（**运行已结束时才可能命中**）。
         *
         * ## 为什么与 [syncFromHistory] 分开
         * 本方法在**首屏**路径上（必须在 `observe` 之前拿到快照），因此它只做
         * "有界取一次 + 不等待落库标记"；`syncFromHistory` 是**收尾补齐**路径，
         * 它要等 `finished_at` 以拿到完整历史。两者共用同一个读取上限。
         *
         * @return 合并后的快照；Room 里没有该运行（或读失败）时 `null`
         */
        private suspend fun awaitHistory(runId: String): LogTail? {
            repeat(HISTORY_SYNC_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                val history =
                    runCatching { runHistory.readLogs(runId, limit = HISTORY_PAGE_LIMIT, offset = 0) }
                        .getOrNull()
                if (!history.isNullOrEmpty()) {
                    // 首屏不需要去重合并（终端此刻是空的、且 runId 刚切换）——
                    // 但仍走 mergedWithHistory 保持"永远按 sequence 有序去重"这一条不变式。
                    if (stillCurrent(runId)) {
                        _terminal.value = _terminal.value.mergedWithHistory(history)
                    }
                    return LogTail(
                        runId = runId,
                        entries = _terminal.value.entries,
                        droppedEntries = _terminal.value.droppedEntries,
                    )
                }
                if (attempt < HISTORY_SYNC_ATTEMPTS - 1) delay(HISTORY_SYNC_INTERVAL_MILLIS)
            }
            return null
        }

        /**
         * 本次收集是否仍是当前应显示的那一次。
         *
         * 两个条件都要满足：**该运行仍是当前运行**，且**协程仍活跃**。
         * 只看后者的写法（`|| isActive`）是恒真的——那样被取消的旧收集
         * 仍可能写一次终端，把界面改回旧运行的内容。
         */
        private fun stillCurrent(runId: String): Boolean = currentRunId == runId && viewModelScope.isActive

        /** 环境快照 → 卡片行（`null` = 尚未探测，UI 显示"探测中"）。 */
        private fun environmentRows(snapshot: EnvironmentSnapshot?): List<EnvironmentRow> {
            if (snapshot == null) return emptyList()
            return listOf(
                EnvironmentRow(label = "设备", value = snapshot.deviceModel),
                EnvironmentRow(label = "Android", value = "${snapshot.androidRelease} (API ${snapshot.apiLevel})"),
                EnvironmentRow(label = "ABI", value = snapshot.primaryAbi.ifBlank { null }),
                EnvironmentRow(label = "应用版本", value = snapshot.appVersion),
                EnvironmentRow(label = "Root", value = snapshot.rootFlavor.key),
                EnvironmentRow(label = "SELinux", value = snapshot.selinuxContext),
            )
        }

        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        private companion object {
            /**
             * 有界等待管道登记的重试次数（10 × 200ms = 2s）。
             *
             * 取值理由：正常路径下 `startLogging` 在 `register` **之前**就已把 state
             * 放进管道（`LogPipelineImpl.startRun` 的 `putIfAbsent` 是同步的），
             * 因此第一次查询通常即命中；2s 只用于覆盖"两个调度器上的作业尚未被调度"
             * 这一残余窗口。再长就是在掩盖真正的故障。
             */
            const val RUN_WAIT_ATTEMPTS: Int = 10

            /** 有界等待的轮询间隔（毫秒）。 */
            const val RUN_WAIT_INTERVAL_MILLIS: Long = 200L

            /**
             * 从 Room 补读时单次读取的上限（条数）。
             *
             * 与 [TerminalState.MAX_ENTRIES] **同值**：合并时无论如何都会截断到那个上限，
             * 多读纯属浪费（每多读一条都要过一次 Room → domain 的映射）。
             */
            const val HISTORY_PAGE_LIMIT: Int = TerminalState.MAX_ENTRIES

            /**
             * 等待 `runs.finished_at` 落库的轮询次数（5 × 300ms = 1.5s）。**必然终止**。
             *
             * 取值理由：`RunHistoryCollector` 是"有界队列 + 独立消费者"，写库**异步于**
             * 管道收尾（阶段 4 收尾已登记的竞态）。真机上正常在百毫秒内落完，1.5s 是很宽的余量；
             * 再长就是在掩盖存储故障，而超时本身不是错误（只少一份更完整的历史）。
             */
            const val HISTORY_SYNC_ATTEMPTS: Int = 5

            /** 等待落库的轮询间隔（毫秒）。 */
            const val HISTORY_SYNC_INTERVAL_MILLIS: Long = 300L
        }
    }
