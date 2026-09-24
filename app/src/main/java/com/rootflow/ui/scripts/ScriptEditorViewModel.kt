@file:Suppress("ktlint:standard:backing-property-naming")

package com.rootflow.ui.scripts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.model.EventCatalog
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.ui.AlertSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 脚本编辑器的 ViewModel（阶段 6c；**6e 起接管触发器勾选**）。
 *
 * ## 路由参数从 [SavedStateHandle] 取（`STAGE6C-PLAN.md §3.1`）
 * `ScriptsTab` 的路由 `"scripts/editor/{id}"` 把 `id` 交给 `NavBackStackEntry`，
 * 路由参数经 entry 的 `CreationExtras`（`DEFAULT_ARGS_KEY`）进入 `SavedStateHandle`
 * ⇒ 本类只需读这一个键。
 *
 * **★ 但这不等于"不必管工厂"**：entry 的 `defaultViewModelProviderFactory` 是
 * `SavedStateViewModelFactory`，**不是** Hilt 工厂。6c 初版据此只传了 owner，
 * 真机点开编辑器即 `NoSuchMethodException: ScriptEditorViewModel.<init> []` 闪退。
 * 修法与机理见 `ScriptEditorScreen.hostViewModelFactory` 的 KDoc
 * （`factory` + `extras` 两者都必须在 `viewModel(...)` 里显式给出）。
 *
 * ## 它只认识 `domain` 端口
 * [ScriptRepository]（`load` / `save`）· [TriggerRepository]（触发器勾选的全部读写）·
 * [PermissionStatusProvider]（chip 的权限徽标与提示条）。**没有任何 `data/` / `runtime/` import**。
 *
 * ## 作用域：**必须**挂 `NavBackStackEntry`（`PROJECT_STATE.md` 6c 开工前必读第 2 条）
 * "未保存改动"的语义要求 ViewModel 与**那一次编辑会话**同寿：
 * 挂 Activity 会让"退出编辑器再进另一个脚本"复用同一个实例（脏状态串页）；
 * 挂列表路由则会被 pop 掉。由 `ScriptEditorScreen` 用
 * `viewModel(viewModelStoreOwner = backStackEntry, factory = …, extras = …)` 保证。
 *
 * @param savedStateHandle 路由参数载体（键见 [KEY_SCRIPT_ID]）
 * @param scripts 脚本仓库端口
 * @param triggers 触发器仓库端口（6e 起是**读写**）
 * @param permissionStatus 权限快照（chip 的 ⚠ 徽标；见 `PROJECT_STATE.md` 的偏离项 **D14**）
 * @param onWarning 告警落点（见 [ScriptEditorViewModel] 内 `onWarning` 属性的 KDoc）
 */
@HiltViewModel
class ScriptEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val scripts: ScriptRepository,
        private val triggers: TriggerRepository,
        private val permissionStatus: PermissionStatusProvider,
        alertSink: AlertSink = AlertSink { },
    ) : ViewModel() {
        /**
         * 告警落点（构造注入；生产由 DI 接到 `Log.w`，单测注入记录器）。
         *
         * 理由同 `HomeViewModel.onWarning`（`AGENT_PROTOCOL.md §5.10`：
         * 单测里 `android.util.Log` 未 stub，被测路径上直接 `Log.*` 会让用例因"日志"而红）。
         *
         * ## ★ 阶段 6e：它此前是 `internal var`，于是**生产侧从未接线**
         * `@HiltViewModel` 由 Dagger 构造，没人写那个 `var` ⇒ 永远是 no-op，
         * 真机上"触发器写入失败"这类告警**一行都看不到**（6e 真机验证项 1 抓到）。
         * 现在缝是构造形参 [AlertSink] ⇒ 改回 `var` 会编译不过。
         *
         * ## 为什么要 `-> Unit` 这个别名，而不是把调用点写成 `alertSink.warn(…)`
         * 调用点是热路径上的一行告警，全项目 60+ 处同形写法；`.warn(…)` 只是噪声。
         * 缝的**类型**（`AlertSink`）才是要显式的那部分。
         */
        private val onWarning: (String) -> Unit = alertSink::warn

        /**
         * 参数输入的防抖时长缝（`internal`，仅单测用）；生产为
         * [PARAMS_DEBOUNCE_MS_DEFAULT]。
         *
         * ## ★ 为什么它是 `var` 而不是构造器形参（**Dagger 不允许**，实测报错）
         * 初版把它写成带默认值的构造器形参 `debounceMs: Long = 400L`。
         * Kotlin 默认值对 Dagger **不存在**：`@Inject` 构造器上每个形参都必须可注入，
         * 于是 Hilt 在 `hiltJavaCompileDebug` 阶段报
         * `[Dagger/MissingBinding] java.lang.Long cannot be provided without an @Inject
         * constructor or an @Provides-annotated method`。
         * 而为一个"只在单测里要改的时延"去加 `@Provides @Named` 绑定，会把测试细节
         * 泄漏到生产装配里 —— 所以走与 [onWarning] 同款的注入缝
         * （本仓库既有的做法：`LogPipelineImpl.onWarning` / `RootEnvironmentInfoProvider` 等）。
         */
        internal var debounceMs: Long = PARAMS_DEBOUNCE_MS_DEFAULT

        /** 路由解析结果（`invalid = true` 时 UI 会提示"已按新建打开"）。 */
        val key: ScriptEditorKey = ScriptEditorArgs.parse(savedStateHandle.get<String>(KEY_SCRIPT_ID))

        /** 当前脚本 id（`null` = 新建）；触发器只能挂在已落库的脚本上。 */
        private val scriptId: Long? = key.scriptId

        /** 表单初值：新建用 [ScriptForm.New]，编辑在 [Script] 装载完成后填入。 */
        private val initialForm: ScriptForm = ScriptForm.New

        private val _form: MutableStateFlow<ScriptForm> = MutableStateFlow(initialForm)

        /** 当前表单（唯一可写点；对外只读，见 [uiState]）。 */
        val form: StateFlow<ScriptForm> = _form.asStateFlow()

        private val _loading: MutableStateFlow<Boolean> = MutableStateFlow(key.scriptId != null)

        private val _saving: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val _loadError: MutableStateFlow<ScriptLoadFailure?> = MutableStateFlow(null)

        private val _errors: MutableStateFlow<List<ScriptFormError>> = MutableStateFlow(emptyList())

        private val _triggerTotal: MutableStateFlow<Int?> = MutableStateFlow(null)

        /**
         * 该脚本的触发器**总数**（`countForScript` 兜底查询；`null` = 还没读到）。
         *
         * ## 为什么 6e 仍保留它（触发器已经有一条 Room 热流了）
         * 编辑器的摘要行以**热流**为准（`triggerEditor.rows`，见 [triggerCount]）；本字段是
         * `countForScript` 的兜底 —— 热流还没回第一帧时用它，且它独立于热流给出一个
         * **可对照的第二次读数**（真机项 3 的"计数 +1"判据就是拿它跟列表页快照对账的）。
         */
        val triggerTotal: StateFlow<Int?> = _triggerTotal.asStateFlow()

        private val _messages: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = MESSAGE_BUFFER)

        /** 一次性提示（Snackbar）：保存失败、四种装载失败、路由参数无效、触发器写失败。 */
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        /**
         * **测试可观测的最后一条提示**（`StateFlow` 会向新订阅者重放当前值）。
         *
         * ## ★ 为什么需要它（实测踩到的死角）
         * [messages] 是 `replay = 0` 的热流，而"路由参数无效"这条提示写在 `init` 的
         * **同步**段里 —— 那时还没有任何订阅者（测试也还没拿到 ViewModel 的引用），
         * 于是 `tryEmit` 的返回值是"无人接收"，那条提示**永远无法被断言**。
         * 同理，[onWarning] 缝也接不住它（缝只能在构造之后赋值）。
         *
         * ## 为什么由 [emitMessage] **同步**写入，而不是再起一条镜像协程
         * 镜像协程同样要等到第一次调度才订阅 ⇒ 同样漏掉 `init` 里那一条
         * （**实测**：断言拿到 `null`）。同步写入没有这个窗口，且顺序与"提示已发出"严格一致。
         *
         * ## 它不影响生产语义
         * 本字段只被测试读；生产 UI 订阅的仍是 [messages]（`replay = 0`
         * ⇒ 提示不会在每次重组后被重放）。
         */
        private val _lastMessage: MutableStateFlow<String?> = MutableStateFlow(null)

        val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

        /**
         * 保存成功（含新建落库后的 `id`）；UI 据此导航回列表。
         *
         * 用 `replay = 1` 而 [messages] 用 `replay = 0`：保存是**必然发生一次的终态**
         * （UI 因配置变更重建后仍应返回列表），而提示是**事件**（重放会让同一条 Snackbar
         * 反复出现）。二者的差别在这里，不在"随手给的容量"。
         */
        private val _saved: MutableSharedFlow<Long> = MutableSharedFlow(replay = 1)

        val saved: SharedFlow<Long> = _saved.asSharedFlow()

        /** 表单初值（保存后会被替换成刚落库的形态；`dirty` 的参照物）。 */
        private var baseline: ScriptForm = initialForm

        /** 既有脚本的 `createdAt`（更新时原样带回，新建时由仓库覆盖为当前时刻）。 */
        private var createdAt: Long = 0L

        // ------------------------------------------------------------------ 6e：触发器状态

        /**
         * 库里该脚本的触发器热流。
         *
         * ## `flatMapLatest` 而不是"订阅一次再 filter"
         * `observeForScript(scriptId)` 本身就是按脚本过滤的 Room 查询流（端口契约）。
         * 这里用 `flowOf(null)` 覆盖"新建脚本"：那时**没有**可观察的脚本 id，
         * 而 `null` 与"空列表"必须可区分（前者 = 还没读到，后者 = 确实一条都没有）。
         */
        private val rowsFlow: Flow<List<TriggerRowUi>?> =
            if (scriptId == null) {
                flowOf(null)
            } else {
                triggers.observeForScript(scriptId).map { rows -> rows.map { it.toUi() } }
            }

        private val _rows: MutableStateFlow<List<TriggerRowUi>?> = MutableStateFlow(null)

        private val _drafts: MutableStateFlow<Map<Long, TriggerParamsDraft>> = MutableStateFlow(emptyMap())

        private val _expanded: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet())

        private val _permissions: MutableStateFlow<Map<AndroidPermission, PermissionGrant>> =
            MutableStateFlow(emptyMap())

        private val _permissionLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val _triggerBusy: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val _paramsNotice: MutableStateFlow<String?> = MutableStateFlow(null)

        /**
         * 每个触发器一条"防抖 + 有效性"作业。
         *
         * ## 为什么"取消上一条"就够了（不需要额外的 epoch 计数）
         * 两个必须防住的交错：
         * ① **连打**：同一行的两次按键 ⇒ 取消旧作业、起新作业 ⇒ 只写最后一次（防抖的本意）；
         * ② **行被删掉后仍在飞的写**：`delay` 还没结束，用户取消了勾选（`delete` 已执行）
         *    ⇒ 新作业**开工前**会重新读 `_rows`，发现该 id 不在了就**直接返回**。
         * 因此"先读行、再写"这一步必须在协程内部（不是提交时快照），否则 ② 会写出
         * 一条**孤儿参数**（行已删、参数却更新了 —— 而 UI 显示的是 Room 热流，用户看不见它，
         * 直到某天重新勾选该事件才"复活"成旧参数）。
         */
        private val paramsJobs: MutableMap<Long, Job> = mutableMapOf()

        /** 触发器区状态（chip 选中态 + 参数草稿 + 权限徽标）。 */
        val triggerEditor: StateFlow<TriggerEditorUiState> =
            combine(
                combine(_rows, _drafts, _expanded) { rows, drafts, expanded -> Triple(rows, drafts, expanded) },
                combine(
                    _permissions,
                    _permissionLoaded,
                    _triggerBusy,
                    _paramsNotice,
                ) { permissions, loaded, busy, notice ->
                    PermissionAndFlags(
                        permissions = permissions,
                        loaded = loaded,
                        busy = busy,
                        notice = notice,
                    )
                },
            ) { rowsAndDrafts, flags ->
                TriggerEditorUiState(
                    rows = rowsAndDrafts.first,
                    drafts = rowsAndDrafts.second,
                    expanded = rowsAndDrafts.third,
                    permissions = flags.permissions,
                    permissionLoaded = flags.loaded,
                    busy = flags.busy,
                    paramsNotice = flags.notice,
                    canEdit = scriptId != null,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = TriggerEditorUiState.Initial.copy(canEdit = scriptId != null),
            )

        /**
         * 编辑器摘要行用的计数（三态）。
         *
         * **热流优先**：`rows == null`（还没读到第一帧）时回落到 [triggerTotal]
         * （`countForScript` 的兜底读数），两者都未知 ⇒ `null` ⇒ UI 显示 `…`，**不编 0**。
         */
        val triggerCount: StateFlow<Int?> =
            combine(_rows, _triggerTotal) { rows, total -> rows?.size ?: total }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )

        /**
         * 编辑器全部状态。
         *
         * ## `dirty` 是**算出来的**（`form != baseline`），不是一个被维护的布尔
         * 用一个随每次 `onChange` 置位的布尔，用户"改一个字再改回来"就永远退不出
         * 未保存状态 —— 预测性返回会一直弹确认框，用户会以为 App 卡了
         * （`STAGE6C-PLAN.md §3.4` 明写这是坏形态）。
         *
         * 保存成功后 [baseline] 被换成**落库后的形态** ⇒ dirty **自然复位**，
         * 不需要一个额外的"复位"动作（那种动作总会漏掉某条路径）。
         *
         * ## ★ 触发器改动**不进** `dirty`（6e 的硬约束）
         * 触发器是**立即写库**的（`STAGE6E-PLAN.md §2.4`）。若把它算进 `dirty`，
         * 用户"只点了触发器、然后按返回"会看到"放弃未保存的修改？" ——
         * 而**根本没有未保存的修改**（触发器已经在库里了）。那是一条会诱导用户
         * 点"放弃"的假提示。
         */
        val uiState: StateFlow<ScriptEditorUiState> =
            combine(
                _form,
                _loading,
                _saving,
                _loadError,
                _errors,
            ) { form, loading, saving, loadError, errors ->
                ScriptEditorUiState(
                    form = form,
                    loading = loading,
                    saving = saving,
                    loadError = loadError,
                    errors = errors,
                    dirty = form != baseline,
                    isNew = key.scriptId == null,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    ScriptEditorUiState(
                        form = initialForm,
                        loading = key.scriptId != null,
                        saving = false,
                        loadError = null,
                        errors = emptyList(),
                        dirty = false,
                        isNew = key.scriptId == null,
                    ),
            )

        /**
         * 是否有未保存改动（驱动预测性返回与"放弃修改"确认框）。
         *
         * **唯一真相是 `uiState.value.dirty`**，这里只做一次 `map`，不另存一份可变状态
         * —— 否则"表单一改、dirty 忘了更新"会成为一个静默的不同步点，
         * 而它的后果是"未保存的改动被系统返回手势悄悄丢掉"。
         */
        val dirty: StateFlow<Boolean> =
            uiState
                .map { it.dirty }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = false,
                )

        init {
            if (key.invalid) {
                onWarning("SCRIPTS_EDITOR_ROUTE_INVALID arg=${savedStateHandle.get<String>(KEY_SCRIPT_ID)}")
                emitMessage(ROUTE_INVALID_MESSAGE)
            }
            // 触发器热流：**先订阅**（新事件一落库就要出现在 chip 上），再谈其它
            viewModelScope.launch {
                rowsFlow.collect { rows -> _rows.value = rows }
            }
            refreshPermissions()
            key.scriptId?.let { id -> load(id) }
        }

        // ------------------------------------------------------------------ 编辑动作

        fun onNameChange(value: String) {
            update { it.copy(name = value) }
        }

        fun onBodyChange(value: String) {
            update { it.copy(content = value) }
        }

        fun onTimeoutChange(value: String) {
            // 只保留数字与负号：输入法的键盘类型控制不了粘贴，
            // 而在这里放行非法字符只会让校验在保存时才报错（迟到的反馈）。
            val filtered = value.filter { it.isDigit() || it == '-' }
            update { it.copy(timeoutSec = filtered) }
        }

        fun onEnabledChange(value: Boolean) {
            update { it.copy(enabled = value) }
        }

        // ★ P9：`onAutoDisableOnFailChange` 已删除（连同 UI 上的那个开关）。
        //   它写进模型的字段**从来没被任何运行期代码读过**，而且其阈值与熔断完全重合
        //   ⇒ 真去实现也是死代码。字段本身保留（见 `ScriptForm.autoDisableOnFail` 的 KDoc）。

        fun onRunOnSafeModeChange(value: Boolean) {
            update { it.copy(runOnSafeMode = value) }
        }

        /**
         * 切换**运行方式**（P4 / 方案 §5.2 的二选一）。
         *
         * `true` = 常驻，`false` = 单次。它与「启用」是两件事：
         * 「启用」决定这个脚本参不参与，运行方式决定它**怎么跑**
         * （方案 §5.1 明令不得用一个开关同时表达这两件事）。
         */
        fun onResidentChange(value: Boolean) {
            update { it.copy(resident = value) }
        }

        /**
         * 保存。
         *
         * ## 三个失败面分开处理，**全部不静默**（`STAGE6C-PLAN.md §3.3`）
         * | # | 面 | 处理 |
         * |---|---|---|
         * | ① | 本地校验失败 | 填 [uiState] 的 `errors`，**不调仓库** |
         * | ② | `WriteResult.Failed` | 表单**原样保留**（用户输入不能丢）+ Snackbar |
         * | ③ | `load` 失败（编辑既有脚本时） | 早于保存发生，`loadError` 已挡住保存 |
         *
         * ## 防连点
         * `saving` 在**启动协程之前**同步置位 —— 与 6b 的环境刷新是同一个教训
         * （`HomeViewModel.requestEnvironmentRefresh`：置位写进 `launch` 内部时，
         * 连续点击会在同一个调度间隙内全部通过 `if` 判断）。这里若漏掉，
         * 连点两次会**插两条脚本**。
         */
        fun save() {
            if (_saving.value) return
            val current = _form.value
            val errors = ScriptFormValidation.validate(current)
            _errors.value = errors
            if (errors.any { it.blocking }) {
                onWarning("SCRIPTS_SAVE_BLOCKED fields=${errors.filter { it.blocking }.map { it.field }}")
                emitMessage(errors.first { it.blocking }.message)
                return
            }
            _saving.value = true
            viewModelScope.launch {
                try {
                    val payload =
                        ScriptFormValidation.toScript(
                            form = current,
                            createdAt = createdAt,
                            updatedAt = 0L,
                            contentSha256 = null,
                        )
                    when (val result = scripts.save(payload)) {
                        is WriteResult.Ok -> {
                            val stored = result.value
                            // ★ P6：成功路径也要留痕。
                            //   三个失败面（BLOCKED / FAILED / CRASHED）本来都有日志，
                            //   唯独成功是静默的 ⇒ 真机判读"这次保存到底成没成"只能事后读库反推
                            //   （P4 那次 `resident` 被静默丢掉，就是这么排查的）。
                            //   与 `SCRIPTS_TRIGGER_* ok` 同款：成功也走这条缝。
                            onWarning(
                                "SCRIPTS_SAVE ok id=${stored.id} name=${stored.name} " +
                                    "resident=${stored.resident} enabled=${stored.enabled}",
                            )
                            // 保存成功后把初值换成**落库后的形态** ⇒ dirty 自然复位
                            // （新建脚本的 id 也从 0 变成真实 id，第二次保存会是 update 而不是 insert）。
                            baseline = ScriptForm.of(stored)
                            _form.value = baseline
                            createdAt = stored.createdAt
                            _errors.value = emptyList()
                            _saved.tryEmit(stored.id)
                        }

                        is WriteResult.Failed -> {
                            onWarning("SCRIPTS_SAVE_FAILED id=${current.id}: ${result.reason}")
                            // 表单**原样保留**：用户的输入不能因为一次写盘失败就消失。
                            emitMessage("保存失败：${result.reason}")
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_SAVE_CRASHED id=${current.id}: ${describe(error)}")
                    emitMessage("保存失败：${describe(error)}")
                } finally {
                    // `finally` 而非成功路径：失败后必须能再保存（否则按钮永久禁用）。
                    _saving.value = false
                }
            }
        }

        // ------------------------------------------------------------------ 6e：触发器动作

        /**
         * 勾选 / 取消勾选一个事件（**立即写库**，不做乐观更新）。
         *
         * ## 为什么 UI 不做乐观置位
         * chip 的选中态来自 `observeForScript` 的 Room 热流（与 6c 的 toggle 同款纪律）。
         * 乐观置位会造出"界面上开着、库里是关的"这一中间态 —— 阶段 6b 的真机缺陷
         * （触发器 `enabled=0` 导致 dispatch 零匹配，而 App 表现完全正常）正是它。
         * 失败时 chip **自然弹回**库里的状态，那就是"回滚"，不需要写回滚代码。
         *
         * ## 为什么一次点击可能写多条
         * `TriggerSelectionDiff` 会顺手清理**重复行**（同一脚本同一事件多行 = 数据损坏）。
         * 清理成功时给一条提示：不静默 —— 用户会看到"行数少了"。
         */
        fun toggleTriggerSelection(eventType: String) {
            if (scriptId == null) {
                emitMessage(TriggerEditorProjections.NEW_SCRIPT_NOTICE)
                return
            }
            if (_triggerBusy.value) return
            val current = _rows.value ?: return
            val desired = current.map { it.eventType }.toMutableSet()
            val adding = eventType !in desired
            if (adding) desired += eventType else desired -= eventType
            val diff = TriggerSelectionDiff.compute(desired = desired, existing = current)
            if (diff.isEmpty) return
            _paramsNotice.value = null
            applyDiff(
                diff = diff,
                action = if (adding) "select" else "deselect",
                eventLabel = EventCatalog.label(eventType),
            )
        }

        /** 展开 / 收起某行的参数区（纯 UI 态，**不写库**）。 */
        fun toggleParams(triggerId: Long) {
            _expanded.value =
                if (triggerId in _expanded.value) {
                    _expanded.value - triggerId
                } else {
                    _expanded.value + triggerId
                }
        }

        /** 写入 payload（**换行在纯函数里剔除**，剔除过就给一次提示）。 */
        fun onTriggerPayloadChange(
            triggerId: Long,
            raw: String,
        ) {
            val current = draftFor(triggerId) ?: return
            val (next, stripped) = current.withPayload(raw)
            if (stripped) _paramsNotice.value = TriggerEditorProjections.PAYLOAD_NEWLINE_STRIPPED
            scheduleParams(triggerId = triggerId, draft = next)
        }

        /** 写入「时」。 */
        fun onTriggerHourChange(
            triggerId: Long,
            raw: String,
        ) {
            val current = draftFor(triggerId) ?: return
            scheduleParams(triggerId = triggerId, draft = current.copy(hourOfDay = digitsOnly(raw)))
        }

        /** 写入「分」。 */
        fun onTriggerMinuteChange(
            triggerId: Long,
            raw: String,
        ) {
            val current = draftFor(triggerId) ?: return
            scheduleParams(triggerId = triggerId, draft = current.copy(minuteOfHour = digitsOnly(raw)))
        }

        /** 写入「间隔（分钟）」。 */
        fun onTriggerIntervalChange(
            triggerId: Long,
            raw: String,
        ) {
            val current = draftFor(triggerId) ?: return
            scheduleParams(triggerId = triggerId, draft = current.copy(intervalMinutes = digitsOnly(raw)))
        }

        /** 切换「精确」开关（`TriggerParams.exact`；D3 决定 v1 默认非精确）。 */
        fun onTriggerExactChange(
            triggerId: Long,
            exact: Boolean,
        ) {
            val current = draftFor(triggerId) ?: return
            scheduleParams(triggerId = triggerId, draft = current.copy(exact = exact))
        }

        /**
         * 切换某一行的启用开关（**原地改那一行的 `enabled`，整份集合的成员不动**）。
         *
         * ## ★ 语义（总开关重构的最终取值）
         * `script_events.enabled` 是**保留字段**：`false` = **停用但保留配置**
         * （投递侧的过滤在 `ScriptEventDao.forEvent` 的 `AND enabled = 1`）。
         * 因此本方法**只切这一行**：
         * - `enabled = true` ⇒ 该行恢复投递
         * - `enabled = false` ⇒ 该行停止投递，**params 仍留在库里**
         *
         * "库里有没有这一行"是另一件事，由 [toggleTriggerSelection] 管。
         *
         * ## ★ 旧实现的两个缺陷（此处是修正记录，回归由 `ScriptEditorViewModelTest` 钉住）
         * 曾写成 `if (enabled) rows + row.toTrigger(scriptId) else rows.filterNot { it.id == triggerId }`：
         * 1. `rows + …` 会**多插一行** —— `rows` 是库里当前全部行、**已包含该 id**
         *    ⇒ 同一事件两行，正是 [TriggerSelectionDiff] 要清理的重复行（数据损坏形态）
         * 2. `else` 走删除 ⇒ "停用"被实现成"丢弃配置"，与 `enabled = false` 的语义不符
         * 3. 配合当时 [Trigger.toUi] 写死 `enabled = true`，`enabled = true` 分支**永远不可达**
         *    ⇒ 用户**只能关、不能开**
         */
        fun onTriggerEnabledChange(
            triggerId: Long,
            enabled: Boolean,
        ) {
            if (_triggerBusy.value) return
            val row = _rows.value?.firstOrNull { it.id == triggerId } ?: return
            if (row.enabled == enabled) return
            writeTriggerRows(
                label = row.label,
                mutate = { rows ->
                    rows.map { existing ->
                        if (existing.id == triggerId) existing.copy(enabled = enabled) else existing
                    }
                },
                action = if (enabled) "subscribe" else "unsubscribe",
            )
        }

        /**
         * 立即落库某行**尚未到期**的参数改动。
         *
         * ## 为什么必须有这条出口（否则会**丢最后一次输入**）
         * 防抖作业挂在 `viewModelScope` 上 ⇒ 用户"输完立刻按返回 / 切 Tab"时
         * `onCleared` 会取消它，最后那次输入**落在内存里、永远不落库**。
         * UI 在参数区所在的 composable 上挂 `DisposableEffect { onDispose { flush() } }`
         * 覆盖这条路径（`STAGE6E-PLAN.md §2.3`）。
         */
        fun flushPendingParams(triggerId: Long) {
            val pending = paramsJobs[triggerId] ?: return
            if (!pending.isActive) return
            val draft = _drafts.value[triggerId] ?: return
            pending.cancel()
            paramsJobs -= triggerId
            // 必须起一条新协程：本函数是**非 suspend** 的（UI 的 `onDispose` 里调用），
            // 而 `writeParams` 要读 Room。取消旧作业与起新作业都在同一帧内完成。
            viewModelScope.launch {
                writeParams(triggerId = triggerId, draft = draft, immediate = true)
            }
        }

        /**
         * 重新探测权限并按需刷新徽标。
         *
         * 时机：`init` 一次 + **从系统设置页返回**（UI 的 `LifecycleEventEffect(ON_RESUME)`）。
         * 权限变化**没有可靠的系统广播**（`PermissionStatusProvider` 决策 6），
         * 因此只能在这些时刻主动刷。
         *
         * ## ★ 为什么**不**用 `withContext(Dispatchers.Default)` 去读快照（实测踩到）
         * 初版把 `permissionStatus.current()` 包在 `withContext(Dispatchers.Default)` 里
         * （直觉是"别在主线程读系统状态"）。两个后果都是坏的：
         * ① `PermissionStatusProvider` 的接口契约已写明**两个方法都不得抛**、
         *    `current()` 只返回内存快照（Android 侧的探测发生在 `refresh()` 里），
         *    因此这里根本没有需要挪走的阻塞工作；
         * ② `Dispatchers.Default` **不在 `runTest` 的虚拟时间调度器上** ⇒ 单测里
         *    `advanceUntilIdle()` 不会等它，"权限刷新后徽标变了"这条断言变成**碰运气**
         *    （实测：`expected: <NOT_APPLICABLE> but was: <NEEDS_PERMISSION>`）。
         * 把它去掉之后，两条路径共用同一个调度器，用例才是确定的。
         */
        fun refreshPermissions() {
            viewModelScope.launch {
                try {
                    runCatching { permissionStatus.refresh() }
                        .onFailure { error ->
                            onWarning("SCRIPTS_TRIGGER_PERMISSION_REFRESH_FAILED ${describe(error)}")
                        }
                    val snapshot = permissionStatus.current()
                    val grants = snapshot.mapValues { (_, state) -> state.grant }
                    _permissions.value = grants
                    // 只要 current() 成功返回就置 loaded：**空快照也代表"探测过了"**
                    // （与"还没探测"必须可区分 —— 否则 chip 永远显示"状态未知"）。
                    _permissionLoaded.value = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_TRIGGER_PERMISSION_READ_FAILED ${describe(error)}")
                    _permissionLoaded.value = false
                }
            }
        }

        // ------------------------------------------------------------------ 内部

        private fun update(transform: (ScriptForm) -> ScriptForm) {
            _form.value = transform(_form.value)
        }

        /**
         * 写库前取回本编辑器的脚本 id。
         *
         * ## 为什么这里可以"抛"（而不是返回可空）
         * 触发器 UI 在 `scriptId == null`（新建）时**整体不可编辑**（`canEdit = false`，
         * 所有动作入口都先 `if (scriptId == null) return`）。因此走到写库这一步必然非空。
         * 用 `error` 而不是 `?: 0L`：真到了这里就是**代码缺陷**，而"写一条 `script_id = 0`
         * 的孤儿触发器"是一条会静默留在库里、且永远不会有脚本认领的垃圾数据 ——
         * 宁可当场炸掉（会被 `writeRow` 的 catch 收成一条用户可见的失败提示），
         * 也不要往库里写脏行。
         */
        private fun requireScriptId(): Long =
            scriptId ?: error("trigger write attempted on an unsaved script (route id was null)")

        /** 取草稿：没有则用库里的值反推（参数区展开即所见即所得）。 */
        private fun draftFor(triggerId: Long): TriggerParamsDraft? {
            _drafts.value[triggerId]?.let { return it }
            val row = _rows.value?.firstOrNull { it.id == triggerId } ?: return null
            return TriggerParamsDraft.of(row.params)
        }

        /**
         * 记录草稿并安排一次落库（防抖）。
         *
         * 草稿**立即**进状态（UI 立刻显示用户键入的字符），写库延后 [debounceMs]。
         */
        private fun scheduleParams(
            triggerId: Long,
            draft: TriggerParamsDraft,
        ) {
            _drafts.value = _drafts.value + (triggerId to draft)
            paramsJobs[triggerId]?.cancel()
            paramsJobs[triggerId] =
                viewModelScope.launch {
                    delay(debounceMs)
                    writeParams(triggerId = triggerId, draft = draft, immediate = false)
                }
        }

        /**
         * 真正把草稿写进库。
         *
         * ## 行必须在**开工时**重新读一次
         * 见 [paramsJobs] 的 KDoc：`delay` 期间用户可能已经取消了勾选（行被删），
         * 那时继续写会留下一条**孤儿参数**。
         *
         * @param immediate `true` = 由 [flushPendingParams] 触发（提示文案不变，仅用于日志归因）
         */
        private suspend fun writeParams(
            triggerId: Long,
            draft: TriggerParamsDraft,
            immediate: Boolean,
        ) {
            if (_triggerBusy.value) return
            val row = _rows.value?.firstOrNull { it.id == triggerId } ?: return
            val params = draft.toParams()
            if (params == null) {
                // 非法输入**不落库**（把 Illegal 静默换成默认值等于替用户改配置）
                onWarning("SCRIPTS_TRIGGER_PARAMS_INVALID id=$triggerId event=${row.eventType} immediate=$immediate")
                return
            }
            if (params == row.params) return
            writeTriggerRows(
                label = row.label,
                mutate = { rows ->
                    rows.map { existing ->
                        if (existing.id == triggerId) existing.copy(params = params) else existing
                    }
                },
                action = "params",
            )
        }

        /**
         * 应用一次勾选差异（**覆盖式写入，单次调用**）。
         *
         * ## ★ 与旧实现的关键差别（总开关重构）
         * 旧实现把差异拆成"逐条 create / 逐条 delete / 逐条去重"，**每条一次数据库往返**，
         * 失败还会**部分生效**（因此要专门提示"有 N/M 项未生效"）。
         *
         * 新模型「存在即订阅」让整件事变成**集合替换**：
         * 读全库 → 只改当前脚本那一份 → [TriggerRepository.replaceForScript] 一次写回。
         * 于是"部分生效"这个失败形态**从根上消失**（要么整体成功、要么整体失败）。
         *
         * ## 为什么从 `triggers.all()` 读全量、而不是按脚本读
         * [replaceForScript] 在实现里**强制覆盖 `scriptId`**（见其 KDoc），
         * 因此传全库列表进去不会把别的脚本的订阅搬过来。
         * 用全库读法的好处是**不需要"按脚本读"的挂起方法**（那是为了 `observeForScript`
         * 那类订阅场景存在的），少一个 API 面。
         */
        private fun applyDiff(
            diff: TriggerSelectionDiff,
            action: String,
            eventLabel: String,
        ) {
            _triggerBusy.value = true
            viewModelScope.launch {
                try {
                    val scriptId = requireScriptId()
                    val existing = triggers.all().filter { it.scriptId == scriptId }
                    val deleteIds = (diff.toDelete + diff.duplicates).toSet()

                    val desired =
                        existing
                            .filter { it.id !in deleteIds }
                            .toMutableList()

                    diff.toCreate.forEach { eventType ->
                        desired +=
                            Trigger(
                                id = 0L,
                                scriptId = scriptId,
                                eventType = eventType,
                                params = TriggerParams(),
                                createdAt = 0L,
                            )
                    }

                    when (val result = triggers.replaceForScript(scriptId = scriptId, triggers = desired)) {
                        is WriteResult.Ok -> {
                            val cleaned = diff.duplicates.size
                            if (cleaned > 0) emitMessage(TriggerEditorProjections.duplicatesCleaned(cleaned))
                            onWarning(
                                "SCRIPTS_TRIGGER_${action.uppercase()} ok event=$eventLabel " +
                                    "subscriptions=${result.value.size} " +
                                    "(create=${diff.toCreate.size} delete=${diff.toDelete.size} " +
                                    "dupes=${diff.duplicates.size})",
                            )
                        }

                        is WriteResult.Failed -> {
                            onWarning(
                                "SCRIPTS_TRIGGER_${action.uppercase()} FAILED event=$eventLabel: ${result.reason}",
                            )
                            emitMessage(TriggerEditorProjections.saveFailed(eventLabel, result.reason))
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_TRIGGER_${action.uppercase()} CRASHED event=$eventLabel: ${describe(error)}")
                    emitMessage(TriggerEditorProjections.saveFailed(eventLabel, describe(error)))
                } finally {
                    _triggerBusy.value = false
                }
            }
        }

        /**
         * 订阅写库的统一出口：**读全量 → 变换 → 覆盖写回**。
         *
         * ## 为什么是"读-改-写"而不是"改哪行写哪行"
         * 新模型下订阅是一份**集合**（存在即订阅）。集合的修改天然是
         * "取当前集合 → 变换 → 写回"，而 [TriggerRepository.replaceForScript]
         * 恰好提供这个原子口子。
         *
         * 代价（如实登记）：**每次都读一遍全库订阅**。可接受：编辑器不在热路径上
         * （用户在点开关时才触发），而换来的是一次写入即一致、没有"部分生效"。
         *
         * @param mutate 在当前脚本的订阅集合上做变换（保持 id 的那一项不变）
         */
        private fun writeTriggerRows(
            label: String,
            mutate: (List<Trigger>) -> List<Trigger>,
            action: String,
        ) {
            val scriptId = runCatching { requireScriptId() }.getOrElse { return }
            writeRow(
                label = label,
                write = {
                    val current = triggers.all().filter { it.scriptId == scriptId }
                    triggers.replaceForScript(scriptId = scriptId, triggers = mutate(current))
                },
                action = action,
            )
        }

        /** 写库收口：成功与失败都在这里上报（`replaceForScript` 的返回类型）。 */
        private fun writeRow(
            label: String,
            write: suspend () -> WriteResult<List<Trigger>>,
            action: String,
        ) {
            _triggerBusy.value = true
            viewModelScope.launch {
                try {
                    when (val result = write()) {
                        is WriteResult.Ok ->
                            onWarning("SCRIPTS_TRIGGER_${action.uppercase()} ok subscriptions=${result.value.size}")

                        is WriteResult.Failed -> {
                            onWarning("SCRIPTS_TRIGGER_${action.uppercase()} FAILED label=$label: ${result.reason}")
                            emitMessage(TriggerEditorProjections.saveFailed(label, result.reason))
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_TRIGGER_${action.uppercase()} CRASHED label=$label: ${describe(error)}")
                    emitMessage(TriggerEditorProjections.saveFailed(label, describe(error)))
                } finally {
                    _triggerBusy.value = false
                }
            }
        }

        /**
         * 装载既有脚本。
         *
         * 四种失败各自映射到一条**独立文案**（[ScriptLoadFailure]）：
         * 它们的排查方向完全不同（文件被删 / 被外部改 / 元数据不在 / 通道不可用），
         * 折叠成"加载失败"会让真机排障从一次 `findstr` 变成一轮猜测。
         */
        private fun load(id: Long) {
            viewModelScope.launch {
                _loading.value = true
                try {
                    when (val result = scripts.load(id)) {
                        is ScriptLoadResult.Ok -> {
                            baseline = ScriptForm.of(result.script)
                            _form.value = baseline
                            createdAt = result.script.createdAt
                            _loadError.value = null
                        }

                        ScriptLoadResult.Missing -> fail(ScriptLoadFailure.MISSING, "load=$id missing")

                        ScriptLoadResult.NotFound -> fail(ScriptLoadFailure.NOT_FOUND, "load=$id not-found")

                        is ScriptLoadResult.Corrupted ->
                            fail(
                                ScriptLoadFailure.CORRUPTED,
                                "load=$id corrupted expected=${result.expected} actual=${result.actual}",
                            )

                        is ScriptLoadResult.Unavailable ->
                            fail(ScriptLoadFailure.UNAVAILABLE, "load=$id ${result.reason}")
                    }
                    refreshTriggerCount(id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    fail(ScriptLoadFailure.UNAVAILABLE, "load=$id crashed ${describe(error)}")
                } finally {
                    _loading.value = false
                }
            }
        }

        private fun fail(
            failure: ScriptLoadFailure,
            log: String,
        ) {
            onWarning("SCRIPTS_EDITOR_LOAD_FAILED $log")
            _loadError.value = failure
            emitMessage(failure.message)
        }

        /**
         * 触发器计数的**兜底读数**（`countForScript`）。
         *
         * 走 `countForScript`（`PersistencePorts.kt:116`）—— 它本来就是为这种"只读地报一个数"
         * 准备的，**不为它新增端口**。6e 起摘要行以 Room 热流为准（见 [triggerCount]），
         * 本方法保留为"热流未到时的第二次读数"。
         *
         * ## 失败**不阻塞编辑**
         * 计数只是摘要，读不到就显示"触发器：…"，绝不能让一个统计查询把编辑器卡住。
         */
        private fun refreshTriggerCount(id: Long) {
            viewModelScope.launch {
                try {
                    _triggerTotal.value = triggers.countForScript(id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onWarning("SCRIPTS_EDITOR_TRIGGER_COUNT_FAILED id=$id: ${describe(error)}")
                    _triggerTotal.value = null
                }
            }
        }

        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        /**
         * 发一条用户可见的提示（**唯一出口**）。
         *
         * 两处写入是刻意的：`_messages` 给生产 UI（事件语义），[_lastMessage] 给测试
         * （状态语义，见其 KDoc）。让调用方各自 `tryEmit` 会让两处漂移 —— 而漂移的表现是
         * "界面上弹了提示、测试里断言不到"，正是本轮实测踩到的形态。
         */
        private fun emitMessage(message: String) {
            _messages.tryEmit(message)
            _lastMessage.value = message
        }

        /** 只保留数字（键盘类型控制不了粘贴；负号在参数里没有意义）。 */
        private fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

        companion object {
            /**
             * 路由参数名（与 `ScriptsTab` 的 `"scripts/editor/{id}"` 逐字对应）。
             *
             * 抽成常量而不是各处写字面量：两处拼写不一致的表现是"编辑器永远按新建打开"，
             * 而**新建与编辑在界面上长得一样**，这类缺陷在真机上极难发现。
             */
            const val KEY_SCRIPT_ID: String = "id"

            /**
             * 路由参数无效时的用户可见提示（**稳定字符串**，由单测逐字钉死）。
             *
             * 抽成常量是因为它有两处消费：发出提示与单测断言。写成字面量则改一处漏一处，
             * 而漏掉的那处表现为"断言忽然失败"（看起来像功能坏了）。
             */
            const val ROUTE_INVALID_MESSAGE: String = "路由参数无效，已按新建打开"

            /**
             * 参数输入的防抖时长（`STAGE6E-PLAN.md §2.3`）。
             *
             * 取值理由：低于 300ms 会让连续输入产生大量写；高于 700ms 会让"输完点返回"
             * 的用户感到丢字。400ms 是两者之间，且**失焦与收起参数区会立即 flush**
             * （不靠这个数字兜住正确性）。
             */
            const val PARAMS_DEBOUNCE_MS_DEFAULT: Long = 400L

            /** 一次性提示的缓冲容量（理由同 `ScriptListViewModel.MESSAGE_BUFFER`）。 */
            private const val MESSAGE_BUFFER: Int = 8
        }
    }

/**
 * 库内订阅 → 编辑器投影（**跨层转换的唯一落点**）。
 *
 * 放在这里而不是 `data/` 或 `domain/`：它消费的是 `domain` 的 [Trigger]，
 * 产出的是 `ui` 的 [TriggerRowUi]，因此只能住在 `ui`。
 *
 * ## `enabled` **如实投影**（★ 2026-09 修正：曾写死 `true`，那是一处真缺陷）
 * 旧版本把这里写成 `enabled = true`，理由是"存在即订阅"。**那个理由是错的**：
 * `script_events.enabled` 是本次重构**明确保留**的字段（见 [Trigger] KDoc 的三态语义），
 * 而 `ScriptEventDao.forEvent` 的 SQL 带 `AND enabled = 1` —— 即
 * **行存在但 `enabled = 0` 时事件根本不会投递**，UI 却会把它画成"订阅中"。
 *
 * 后果是 6b 记录过的真机缺陷形态原样复活（App 表现完全正常、事件零匹配）：
 * - 「已禁用：事件到达时不会被调度」**永远不显示**（[TriggerRowUi.disabledNotice] 恒 `null`）
 * - `onTriggerEnabledChange(id, true)` 被 `row.enabled == enabled` 提前吞掉 ⇒ **用户无法重新启用**
 *
 * ⇒ 投影必须如实传递 `enabled`。**行是否存在**（未订阅的 chip 由 `EventCatalog` 补）
 * 与**行是否生效**是两件不同的事，不能用一个 `true` 抹平。
 */
internal fun Trigger.toUi(): TriggerRowUi =
    TriggerRowUi(
        id = id,
        eventType = eventType,
        params = params,
        enabled = enabled,
        createdAt = createdAt,
    )

/**
 * `combine` 的辅助载体：把"权限 + 三个标志位"打包成一个类型，
 * 从而让 `triggerEditor` 的状态组装停在 `combine` 的 5 参重载以内。
 *
 * 用 `private data class` 而不是嵌套 `combine` 三次：嵌套的元组会退化成
 * `Pair<Pair<…>>`，读起来无法对应字段名，而这里每个字段都会直接落到
 * `TriggerEditorUiState` 上，名字必须看得见。
 */
private data class PermissionAndFlags(
    val permissions: Map<AndroidPermission, PermissionGrant>,
    val loaded: Boolean,
    val busy: Boolean,
    val notice: String?,
)
