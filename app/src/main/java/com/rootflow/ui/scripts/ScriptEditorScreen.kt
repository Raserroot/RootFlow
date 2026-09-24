package com.rootflow.ui.scripts

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.rootflow.domain.event.SettingsTarget
import com.rootflow.domain.event.SettingsTargets
import com.rootflow.domain.model.EventCatalog
import com.rootflow.ui.theme.NavBarReservedSpace
import androidx.compose.material.icons.Icons as IconsPackage
import androidx.compose.material.icons.filled.Check as CheckIcon

/**
 * 脚本编辑器（阶段 6c；**6e 起含触发器勾选**）。
 *
 * ## ★ 预测性返回（需求 §6，`STAGE6C-PLAN.md §3.4`）
 * ```kotlin
 * PredictiveBackHandler(enabled = dirty) { progress ->
 *     progress.collect { /* ★ 必须收完，否则抛 IllegalStateException（真机闪退） */ }
 *     /* 走到这里 = 手势已提交 → 弹确认：放弃修改 / 继续编辑 */
 * }
 * ```
 * **`enabled` 用 `dirty`，不用常量 `true`**：否则"没改任何东西也弹确认"，
 * 用户会以为 App 卡了。`dirty == false` ⇒ 完全不拦截，系统直接 pop。
 *
 * ### ★ `progress` 必须被收完（**批 2 项 4 的真机闪退根因**）
 * androidx 在调用 `onBack` 之后立刻 `check(completed) { "You must collect the progress
 * flow" }`，而 `completed` **只**由 flow 的 `onCompletion` 置位 ⇒ **不收就必崩**。
 * 6c 初版只写 `{ confirmingDiscard = true }`（既不收也不看），真机上"改一个字 → 按返回"
 * 直接 `IllegalStateException` 闪退，**确认框从未出现过**。
 *
 * ### ★ 触发器改动**不进** `dirty`（6e）
 * 触发器是立即写库的。把它算进 `dirty` 会让"只点了 chip、然后按返回"弹出
 * "放弃未保存的修改？"—— 而**根本没有未保存的修改**。因此触发器区自己声明
 * **"触发器的修改会立即保存。"**（见 [TriggerEditorProjections.PARAMS_FOOTNOTE]）。
 *
 * ## 本页只做投影与布局
 * 校验、`dirty` 判定、四种加载失败文案在 [ScriptFormValidation] / [ScriptEditorViewModel]；
 * chip 可用性、参数校验、全部触发器文案在 `TriggerEditorModels.kt` 的纯函数里
 * —— 因为本仓库**没有 UI 测试**（决策 B），逻辑留在 Composable 里等于覆盖率 0。
 *
 * @param backStackEntry 本页的 `NavBackStackEntry`：**必须**是它，不是 Activity
 *   （"未保存改动"要求 ViewModel 与这一次编辑会话同寿，见 [ScriptEditorViewModel] 的 KDoc）
 * @param navController 用于确认放弃后返回列表
 */
@Composable
internal fun ScriptEditorScreen(
    backStackEntry: NavBackStackEntry,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    // ★ `factory` 与 `extras` **缺一不可**（只给 owner 会闪退、只给 factory 会静默错页），
    //   两条的机理见 [hostViewModelFactory] 的 KDoc。
    val viewModel: ScriptEditorViewModel =
        viewModel(
            viewModelStoreOwner = backStackEntry,
            factory = hostViewModelFactory(),
            extras = backStackEntry.defaultViewModelCreationExtras,
        )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val triggerTotal by viewModel.triggerCount.collectAsStateWithLifecycle()
    val triggerState by viewModel.triggerEditor.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    /** 是否正在显示"放弃修改？"确认框。 */
    var confirmingDiscard by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // 保存成功后回到列表（列表是 Room 热流 ⇒ 新行/改动自动出现，不需要手动刷新）。
    LaunchedEffect(viewModel) {
        viewModel.saved.collect {
            navController.popBackStack()
        }
    }

    // ★ 从系统设置页返回时重刷权限（6e 的「去授权」回路）：
    //   权限变化**没有可靠的系统广播**（`PermissionStatusProvider` 决策 6），
    //   只能在这些时刻主动刷。本页不是 ViewModel 的 `init` 一次就够 ——
    //   用户可能在设置页里刚把「使用情况访问」打开。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    // ★ 预测性返回（见类 KDoc）。
    // `enabled = dirty`：没改东西就让系统直接 pop，不打扰用户。
    //
    // ⚠️ **必须把 `progress` 收完**，否则 androidx 在 lambda 返回时直接抛
    // `IllegalStateException: You must collect the progress flow` —— 真机实测是
    // **返回手势闪退**（批 2 项 4；确认框从未出现过）。
    // 两条出口都由 `collect` 表达，**不要** catch：取消时让它原样抛 `CancellationException`，
    // 吞掉反而会把"取消"变成 `check(completed)` 的崩溃（详见类 KDoc）。
    //
    // 这里仍然**不调用** `onBack()`：该 handler 会消费返回事件，pop 由对话框的
    // "放弃修改"自己调 `navController.popBackStack()`；在"用户已经完成手势"之后再调
    // `onBack()` 只会推进动画进度，是多余的。
    PredictiveBackHandler(enabled = dirty) { progress ->
        progress.collect {
            // 进度值不用于动画：本页只需要"手势结束"这个信号。
        }
        confirmingDiscard = true
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::save,
                // ★★ 阶段 7 方向 A 的**必要补偿**（与 `ScriptListScreen` 的 FAB 同款，勿删其一）：
                //   FAB 是 Scaffold 的**浮层**，不参与任何滚动容器的 contentPadding，
                //   因此它曾经是"靠 `RootFlowMain` 内容层那 88dp 让位"才没被胶囊盖住的。
                //   内容层让位下移到各 Tab 的滚动容器之后，**FAB 这一路没人补** ⇒
                //   真机实测它整枚落在胶囊之下（56dp 的按钮，胶囊顶边就在它上方 12px）。
                //   让位量取同一个 `NavBarReservedSpace` ⇒ 改底栏几何时两处一起走。
                //   注意 NavBarReservedSpace 已经含了底栏上下各 12dp 的留白，**不要**再加边距。
                modifier = Modifier.padding(bottom = NavBarReservedSpace),
                // 加载中/保存中/加载失败时不给保存：那条路径上的保存必然失败，
                // 让按钮可点只会制造一条"点了没反应"的困惑。
                // 注意**不**用 `!valid` 当条件：校验只在点按之后才跑（见 ScriptFormValidation 的 KDoc），
                // 用它禁用按钮会让"字段为空时按钮灰着、用户不知道为什么"。
                containerColor =
                    if (state.canSave) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ) {
                Icon(imageVector = IconsPackage.Filled.CheckIcon, contentDescription = "保存")
            }
        },
    ) { _ ->
        // `loadError` 是 `collectAsStateWithLifecycle` 的委托属性 ⇒ 无法 smart cast，
        // 因此先取到局部变量（这是 Kotlin 对 delegated property 的硬约束，不是风格选择）。
        val loadError = state.loadError
        when {
            state.loading -> LoadingState()
            loadError != null -> LoadErrorState(failure = loadError)
            else ->
                EditorForm(
                    state = state,
                    triggerState = triggerState,
                    triggerTotal = triggerTotal,
                    onNameChange = viewModel::onNameChange,
                    onBodyChange = viewModel::onBodyChange,
                    onTimeoutChange = viewModel::onTimeoutChange,
                    onEnabledChange = viewModel::onEnabledChange,
                    onRunOnSafeModeChange = viewModel::onRunOnSafeModeChange,
                    onResidentChange = viewModel::onResidentChange,
                    viewModel = viewModel,
                    onOpenSettings = { target -> context.openSettingsPage(target) },
                )
        }
    }

    if (confirmingDiscard) {
        DiscardDialog(
            onDiscard = {
                confirmingDiscard = false
                navController.popBackStack()
            },
            onKeepEditing = { confirmingDiscard = false },
        )
    }
}

/** 装载中：给一个转圈而不是空白（空白会被读成"这个脚本是空的"）。 */
@Composable
private fun LoadingState() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "正在读取脚本正文…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * 装载失败：**按四种成因给四条不同文案**（`STAGE6C-PLAN.md §3.1`）。
 *
 * 不折叠成一句"加载失败"：四种成因的排查方向完全不同（文件被删 / 被外部改 /
 * 元数据不在 / root 通道不可用），折叠会让真机排障从一次 `findstr` 变成一轮猜测。
 * 这一页**不给编辑表单**：正文都没读到就编辑，保存会把空正文写回去 —— 那是静默清空用户脚本。
 */
@Composable
private fun LoadErrorState(failure: ScriptLoadFailure) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "无法打开该脚本",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 表单本体（需求 §3.3 全字段 + 6e 的触发器区）。
 *
 * ## 校验错误只落在对应字段下面
 * 一次保存可能同时有多条错误（名称为空 + 正文为空 + 超时非法）。
 * 只看第一条会让用户"改一个、再报一个"，来回三次才存下去。
 */
@Composable
private fun EditorForm(
    state: ScriptEditorUiState,
    triggerState: TriggerEditorUiState,
    triggerTotal: Int?,
    onNameChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onTimeoutChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRunOnSafeModeChange: (Boolean) -> Unit,
    /** P4：运行方式（常驻 / 单次）—— 见 [RunModeSelector]。 */
    onResidentChange: (Boolean) -> Unit,
    viewModel: ScriptEditorViewModel,
    onOpenSettings: (SettingsTarget) -> Unit,
) {
    val form = state.form

    /**
     * 事件区是否展开（**默认折叠**，方案 §5.2）。
     *
     * 纯 UI 态（`remember`）：不进 ViewModel、**不进 `dirty`** ——
     * 展开/收起一次不该让"未保存改动"的确认框弹出来。
     * 进程被杀后回到折叠态是可接受的：它不承载任何用户配置。
     */
    var eventsExpanded by remember { mutableStateOf(false) }

    // ★ 阶段 7（方向 A）：底栏的让位从「内容层整体 padding」下移到**本滚动容器**。
    //   这是方向 A 的**第四处**（前三处是主页 / 配置页 / 设置页的滚动容器）——
    //   编辑器同样吃 `RootFlowMain` 的内容层，因此同样必须自己补。
    //   判据：最后一项（"先保存脚本，再配置触发器"与触发器 chip 组）静止时**不被胶囊压住**。
    //   用**末尾 Spacer** 而不是 contentPadding：`Column` + `verticalScroll` 没有
    //   `contentPadding` 形参（与 `SettingsScreen` 同款写法）。
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // ★ 阶段 7：IME 让位。清单已声明 `adjustResize`，但 Compose 侧还要自己认领
                //   这份 inset（否则键盘弹出时它盖住的是"翻不到的最后几行"）。
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            label = { Text(text = "名称") },
            singleLine = true,
            isError = state.errors.any { it.field == ScriptFormField.NAME },
            supportingText =
                state.errors.firstOrNull { it.field == ScriptFormField.NAME }?.let { error ->
                    { Text(text = error.message) }
                },
            modifier = Modifier.fillMaxWidth(),
        )

        // 语言：**只读**（需求 §3.1 + §10：v1 只实现 shell，Lua 运行时不做）。
        ReadOnlyRow(
            label = "语言",
            value =
                form.language +
                    if (ScriptProjections.isLanguageEditable(form.language)) "" else "（v1 不支持编辑）",
        )

        SwitchRow(label = "启用", checked = form.enabled, onCheckedChange = onEnabledChange)

        OutlinedTextField(
            value = form.timeoutSec,
            onValueChange = onTimeoutChange,
            label = { Text(text = "超时（秒）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = state.errors.any { it.field == ScriptFormField.TIMEOUT },
            supportingText = {
                val error = state.errors.firstOrNull { it.field == ScriptFormField.TIMEOUT }
                Text(
                    text =
                        error?.message
                            ?: if (form.timeoutSec.trim() == "0") {
                                // 需求 §5.1：0 的语义是"用全局默认 60s"。显示裸 0 会被读成"不限"，
                                // 而实际会在 60 秒被熔断 —— 那是误导性文案（见 ScriptProjections.timeoutLabel）。
                                ScriptProjections.DEFAULT_TIMEOUT_LABEL
                            } else {
                                "超过该秒数将被安全熔断终止"
                            },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ★ P4：运行方式（方案 §5.2 的"新增的二选一"）。
        //   放在超时之后、两个开关之前：它是**形态**选择（这个脚本怎么跑），
        //   而下面两条是**行为微调**（失败了怎么办 / 安全模式下要不要跑）。
        RunModeSelector(resident = form.resident, onResidentChange = onResidentChange)

        // ★ P9：这里原本还有一行「连续失败自动禁用」开关，**已移除**。
        //   理由不是"懒得做"，而是它的阈值（连续失败 ≥ 3）与熔断的
        //   "同一脚本连续失败 ≥ 3 次"（`REQUIREMENTS.md` §5.1）**完全相同** ⇒
        //   熔断（全局保命，停所有脚本）必然抢先触发 ⇒ 实现它等于写一段
        //   **永不可达**的代码。
        //   字段与模型**保留**（旧数据不丢，将来若改需求可直接接上），只是不再给入口。
        //   要让它有意义，必须先改需求（阈值小于熔断，或改熔断规则）—— 那是产品决策。
        SwitchRow(
            label = "安全模式下仍运行",
            checked = form.runOnSafeMode,
            onCheckedChange = onRunOnSafeModeChange,
        )

        OutlinedTextField(
            value = form.content,
            onValueChange = onBodyChange,
            label = { Text(text = "脚本正文") },
            // 等宽：shell 正文的对齐靠空格，非等宽字体下用户看不出缩进错位。
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = BODY_MIN_LINES,
            isError = state.errors.any { it.field == ScriptFormField.BODY },
            supportingText =
                state.errors.firstOrNull { it.field == ScriptFormField.BODY }?.let { error ->
                    { Text(text = error.message) }
                },
            modifier = Modifier.fillMaxWidth(),
        )

        // ===== P5：事件通知降级为「高级、可选」（方案 §5.2）=====
        //
        // ★ **默认折叠是设计要点，不是省空间**：新用户看到的是"写脚本 + 拨开关"，
        //   不需要理解 13 个事件就能跑起来 —— 那正是本方案要正面满足的诉求。
        //   语义上也成立：事件现在只是"投递给常驻脚本的通知"，
        //   一个纯 `while true; do …; done` 的脚本**根本不需要**订阅任何事件。
        HorizontalDivider()
        EventsSectionHeader(
            expanded = eventsExpanded,
            // 计数走三态（`null` ⇒ `…`）：收起时也要能看到概况，但**不编 0**
            summary = TriggerEditorProjections.eventsSectionSummary(triggerState.count),
            onToggle = { eventsExpanded = !eventsExpanded },
        )
        AnimatedVisibility(visible = eventsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = TriggerEditorProjections.EVENTS_SECTION_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 只读摘要行保留（6c 起就有，是列表页计数的对照读数）；
                // 下面是 6e 的 chip 组 + 参数区。
                ReadOnlyRow(
                    label = "触发器",
                    value = state.triggerSummaryLine(triggerTotal),
                )
                TriggerSection(
                    state = triggerState,
                    triggerTotal = triggerTotal,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                )
                // 取值方式写在**最下面**（展开后一路读下来才需要它）。
                // P5 起事件经 FIFO 投递 ⇒ 这一行就是脚本侧的写法。
                Text(
                    text = TriggerEditorProjections.EVENTS_READ_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // ★ 阶段 7（方向 A）：底栏让位的**末尾留白**（必须是最后一个子项，否则等于没加）。
        //   与 `SettingsScreen` 同款写法；量取同一个 `NavBarReservedSpace`。
        Spacer(modifier = Modifier.height(NavBarReservedSpace))
    }
}

/**
 * 事件区的折叠标题（P5 / 方案 §5.2）。
 *
 * ## 为什么它是**默认折叠**的（设计要点，勿改成默认展开）
 * 方案 §5.2 的原话：「事件区**默认折叠且默认空** ⇒ 新用户看到的是"写脚本 + 拨开关"，
 * **不需要理解 14 个事件**就能跑起来。这就是诉求的正面满足。」
 *
 * 语义上也说得通：C 的模型里事件只是**投递给常驻脚本的通知** ——
 * 一个纯 `while true; do …; done` 的脚本完全不需要订阅任何事件。
 *
 * ## 为什么摘要（"已订阅 2 个"）必须留在标题行
 * 收起之后 chip 不可见。少了这一行，一个"上次配过 3 个事件"的用户会以为配置丢了
 * —— 而它其实好好地在库里。三态由 [TriggerEditorProjections.eventsSectionSummary] 保证
 * （计数未知时显示 `…`，**不编 `0`**）。
 *
 * @param expanded 当前是否展开
 * @param summary 标题右侧的概况
 * @param onToggle 点标题行切换
 */
@Composable
private fun EventsSectionHeader(
    expanded: Boolean,
    summary: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 三角指示方向（▸ 收起 / ▾ 展开）：与 Material 的 `IconButton` 相比，
        // 用字符就够 —— 本页已经引了图标字体，但一个三角不值得再引一个 `Icons` 引用。
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = TriggerEditorProjections.EVENTS_SECTION_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 触发器区（**6e 的唯一新 UI 块**）。
 *
 * 结构（自上而下）：
 * ```
 * 触发器（N/13）                 ← 计数三态（未知显示 …，不编 0）
 * [ 开机 ][ 亮屏 ][ 熄屏 ] …      ← FlowRow + FilterChip（13 个）
 * 已选：开机 · 作息                ← 选中行的参数摘要（点击展开/收起）
 *   └─ 参数区（AnimatedVisibility）
 * 触发器的修改会立即保存。          ← 立即保存语义的告知
 * ```
 *
 * ## ★ 为什么参数区在 chip 组**下方**，而不是插进 chip 流里
 * `FlowRow` 的换行由**全部子项宽度**决定。把参数区插进 chip 流 ⇒ 一展开就把后面所有 chip
 * 挤到下一行，**用户正要点的 chip 会跑位**（误点）。放在下方时布局只在自己那一段变化。
 *
 * ## ★ 为什么 chip 不灰显（需求 §2.1 说"灰显"，此处**有意偏离** → `PROJECT_STATE.md` 的 D14）
 * ① "先配 `app_foreground` → 去授权 → 回来直接跑"是常见路径，灰显会堵死；
 * ② "灰显却是已选中"（上次在授权状态下配的，权限后来被回收）会被读成"我的触发器丢了"。
 * 因此：**可点 + ⚠ 徽标 + 提示条 + 去授权**。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerSection(
    state: TriggerEditorUiState,
    triggerTotal: Int?,
    viewModel: ScriptEditorViewModel,
    onOpenSettings: (SettingsTarget) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TriggerEditorProjections.countLine(state.count ?: triggerTotal),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp), strokeWidth = 2.dp)
            }
        }

        if (!state.canEdit) {
            // 新建脚本：触发器**必须有** `scriptId` 才能落库 ⇒ 先保存再配。
            // 不给一排"点了没反应"的 chip：那次点击的唯一可能结果是失败提示。
            Text(
                text = TriggerEditorProjections.NEW_SCRIPT_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EventCatalog.ALL.forEach { spec ->
                val access = state.accessOf(spec.eventId)
                FilterChip(
                    selected = spec.eventId in state.selected,
                    // ★ 不灰显（D14）：未授权只加 ⚠，仍然可点、仍然写库
                    onClick = { viewModel.toggleTriggerSelection(spec.eventId) },
                    label = {
                        Text(
                            text =
                                spec.label +
                                    if (access == TriggerChipAccess.OK) "" else TriggerEditorProjections.WARN_SUFFIX,
                        )
                    },
                )
            }
        }

        val selectedRows = state.rows.orEmpty()
        if (selectedRows.isEmpty() && state.loaded) {
            Text(
                text = "还没有勾选任何事件：勾一个 chip 即可创建触发器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        selectedRows.forEach { row ->
            SelectedTriggerRow(
                row = row,
                state = state,
                viewModel = viewModel,
                onOpenSettings = onOpenSettings,
            )
        }

        Text(
            text = TriggerEditorProjections.PARAMS_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一个已选中的事件：摘要行（可点开参数）+ 参数区。 */
@Composable
private fun SelectedTriggerRow(
    row: TriggerRowUi,
    state: TriggerEditorUiState,
    viewModel: ScriptEditorViewModel,
    onOpenSettings: (SettingsTarget) -> Unit,
) {
    val expanded = row.id in state.expanded
    val spec = EventCatalog.spec(row.eventType)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        row.summary +
                            (row.disabledNotice?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.disabledNotice != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        TextButton(onClick = { viewModel.toggleParams(row.id) }) {
            Text(text = if (expanded) "收起参数" else "配置参数")
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            // ★ 防抖作业挂在 viewModelScope 上 ⇒ 离开本段时必须 flush，
            //   否则"输完立刻按返回"会丢最后一次输入（见 `flushPendingParams` 的 KDoc）。
            DisposableEffect(row.id, viewModel) {
                onDispose { viewModel.flushPendingParams(row.id) }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                spec?.let { spec ->
                    TriggerPermissionNotice(
                        spec = spec,
                        access = state.accessOf(spec.eventId),
                        onOpenSettings = onOpenSettings,
                    )
                }

                TriggerParamsEditor(
                    row = row,
                    state = state,
                    viewModel = viewModel,
                )

                LabelledSwitch(
                    label = "启用",
                    checked = row.enabled,
                    onCheckedChange = { enabled -> viewModel.onTriggerEnabledChange(row.id, enabled) },
                )
                Text(
                    text = TriggerEditorProjections.SAFE_MODE_FOOTNOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.paramsNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 参数输入（**按事件形态渲染**；见 `EventCatalog.ParamKind`）。
 *
 * payload 对**全部 13 个事件**都出现（需求 §3.2 的 `ROOTFLOW_EVENT_PAYLOAD`），
 * 它与事件形态正交 —— 因此它不跟 `when (kind)` 绑在一起。
 */
@Composable
private fun TriggerParamsEditor(
    row: TriggerRowUi,
    state: TriggerEditorUiState,
    viewModel: ScriptEditorViewModel,
) {
    val draft = state.draftOf(row)
    val errors = draft.errors()
    val spec = EventCatalog.spec(row.eventType)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (spec?.params) {
            EventCatalog.ParamKind.TIME -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberField(
                        value = draft.hourOfDay,
                        label = "时",
                        error = errors.firstOrNull { it.field == TriggerParamsDraft.Companion.Field.TIME }?.message,
                        onValueChange = { raw -> viewModel.onTriggerHourChange(row.id, raw) },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = draft.minuteOfHour,
                        label = "分",
                        error = null,
                        onValueChange = { raw -> viewModel.onTriggerMinuteChange(row.id, raw) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (draft.hourOfDay.isBlank() || draft.minuteOfHour.isBlank()) {
                    Hint(text = TriggerEditorProjections.TIME_INCOMPLETE)
                }
                LabelledSwitch(
                    label = "精确（需「闹钟和提醒」权限）",
                    checked = draft.exact,
                    onCheckedChange = { exact -> viewModel.onTriggerExactChange(row.id, exact) },
                )
            }

            EventCatalog.ParamKind.INTERVAL -> {
                NumberField(
                    value = draft.intervalMinutes,
                    label = "间隔（分钟）",
                    error = errors.firstOrNull { it.field == TriggerParamsDraft.Companion.Field.INTERVAL }?.message,
                    onValueChange = { raw -> viewModel.onTriggerIntervalChange(row.id, raw) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.intervalMinutes.isBlank()) {
                    Hint(text = TriggerEditorProjections.INTERVAL_INCOMPLETE)
                }
            }

            EventCatalog.ParamKind.PACKAGE ->
                // ★ **本阶段不做包名筛选**：`TriggerParams` 只有 5 个扁平字段
                //   （3a 冻结的持久化契约），**没有** `packageName`，而 6e 不加迁移。
                //   因此这里如实说明，而不是给一个存不下来的输入框 ——
                //   那正是本仓库最忌讳的"静默失效"。
                Hint(text = PACKAGE_FILTER_NOT_SUPPORTED)

            EventCatalog.ParamKind.NONE, null -> Hint(text = TriggerEditorProjections.NO_EVENT_PARAMS)
        }

        OutlinedTextField(
            value = draft.payload,
            onValueChange = { raw -> viewModel.onTriggerPayloadChange(row.id, raw) },
            label = { Text(text = "事件负载（ROOTFLOW_EVENT_PAYLOAD）") },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = errors.any { it.field == TriggerParamsDraft.Companion.Field.PAYLOAD },
            supportingText = {
                Text(
                    text =
                        errors
                            .firstOrNull { it.field == TriggerParamsDraft.Companion.Field.PAYLOAD }
                            ?.message
                            ?: TriggerEditorProjections.PAYLOAD_HINT,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 权限提示条（D14：可点 + ⚠ + 提示 + 去授权）。 */
@Composable
private fun TriggerPermissionNotice(
    spec: EventCatalog.EventSpec,
    access: TriggerChipAccess,
    onOpenSettings: (SettingsTarget) -> Unit,
) {
    val hint = TriggerEditorProjections.permissionHint(spec, access) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        spec.permission?.let { permission ->
            if (TriggerEditorProjections.canGrant(access)) {
                val target = SettingsTargets.resolve(permission)
                if (target != null) {
                    TextButton(onClick = { onOpenSettings(target) }) {
                        Text(text = "去授权")
                    }
                }
            }
        }
    }
}

/** 数字输入框（键盘类型 + 只保留数字由 ViewModel 侧的纯函数负责）。 */
@Composable
private fun NumberField(
    value: String,
    label: String,
    error: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null,
        supportingText = error?.let { message -> { Text(text = message) } },
        modifier = modifier,
    )
}

/** 一句灰色提示。 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 带标签的开关行（与表单其余开关同款）。 */
@Composable
private fun LabelledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // `weight` 让长标签（「精确（需「闹钟和提醒」权限）」）换行而不是把开关挤出屏幕
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 等宽正文的最小行数。取 8：一屏能同时看到"表单其余字段 + 一段像样的脚本"。 */
private const val BODY_MIN_LINES: Int = 8

/**
 * "包名筛选本阶段不做"的说明文案。
 *
 * `TriggerParams` 没有 `packageName` 字段（3a 的持久化契约），6e 不加迁移
 * ⇒ 如实说明，不给假输入框。
 * 该能力仍在 3d 遗留项里（`payloadJson()` 的运行时负载：`AppForeground.packageName`）。
 */
private const val PACKAGE_FILTER_NOT_SUPPORTED: String =
    "暂不支持按包名筛选：触发参数里没有该字段（需先扩展持久化契约）。勾选后任何应用切换都会触发。"

/** 只读的一行（语言、触发器摘要）。 */
@Composable
private fun ReadOnlyRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 带标签的开关行。 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 运行方式二选一（P4；方案 §5.2 的 `( ● 常驻 ) ( ○ 单次 )`）。
 *
 * ## 为什么是"二选一"而不是一个开关
 * 这两个是**并列的两种形态**，不是"某功能开不开"。用一个开关表达会让"关"
 * 被读成"这个脚本不启用" —— 而"启不启用"由上面那个「启用」开关表达。
 * 方案 §5.1 明列的第三条反模式正是"用**一个**开关同时表达两件事"。
 *
 * ## 为什么副文案随选择变化
 * 用户不该为了知道差别去读文档：常驻 = 服务起来就一直跑（脚本里写循环）；
 * 单次 = 服务启动时跑一遍就结束。这一行就是那两条说明，随选中的项显示。
 *
 * @param resident `true` = 常驻
 */
@Composable
private fun RunModeSelector(
    resident: Boolean,
    onResidentChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "运行方式",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = resident,
                onClick = { onResidentChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = RUN_MODE_OPTIONS),
            ) {
                Text(text = "常驻")
            }
            SegmentedButton(
                selected = !resident,
                onClick = { onResidentChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = RUN_MODE_OPTIONS),
            ) {
                Text(text = "单次")
            }
        }
        Text(
            text =
                if (resident) {
                    "服务起来就一直跑，退出自动拉起（脚本里写 while 循环）"
                } else {
                    "服务启动时跑一遍就结束"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 运行方式的选项数（两个 `SegmentedButton` 的 `itemShape(index, count)` 必须共用它）。 */
private const val RUN_MODE_OPTIONS = 2

/**
 * "放弃修改？"确认框。
 *
 * ## 为什么两个按钮的文案是动词短语而不是"确定/取消"
 * 用户此刻面对的是"我刚输入的脚本要不要留"。`确定/取消` 在这个语境下**无法判断**
 * 哪个是"留"—— 而选错的代价是用户手打的脚本没了。
 */
@Composable
private fun DiscardDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(text = "放弃未保存的修改？") },
        text = { Text(text = "本次编辑的内容尚未保存。") },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(text = "放弃修改") }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(text = "继续编辑") }
        },
    )
}

/**
 * 打开系统设置页（**只由 UI 构造 `Intent`**，`domain` 只给描述符 —— 决策 9）。
 *
 * ## 为什么带 `FLAG_ACTIVITY_NEW_TASK`
 * `LocalContext` 在 Compose 里可能是 `ContextThemeWrapper` 而不是 Activity；
 * 不带这个 flag 的 `startActivity` 在那条路径上会抛 `AndroidRuntimeException`。
 * 设置页的同一处写法已真机验证过（6d 批 2 项 4）。
 *
 * ## 通知设置页的坑（**6d 修过，勿重蹈**）
 * `ACTION_APP_NOTIFICATION_SETTINGS` 只认 `EXTRA_APP_PACKAGE`，给 `package:` data URI
 * 会在真机上 `unable to resolve Intent`。本页**不**碰通知页（那是设置页的事），
 * 但 `SettingsTarget` 的 `packageUri` 字段语义照旧由 `SettingsTargets` 保证。
 */
private fun android.content.Context.openSettingsPage(target: SettingsTarget) {
    val intent =
        Intent(target.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (target.packageUri) intent.data = Uri.parse("package:$packageName")
    runCatching { startActivity(intent) }
}

/**
 * 取**宿主 Activity 的默认 ViewModel 工厂** —— 在 `@AndroidEntryPoint` 下它就是 Hilt 工厂。
 *
 * ## 为什么必须显式拿它（阶段 6c 真机闪退的根因）
 * `NavBackStackEntry` 的 `defaultViewModelProviderFactory` 是 `SavedStateViewModelFactory`，
 * **不是** Hilt 工厂。初版写的是 `viewModel(viewModelStoreOwner = backStackEntry)`，
 * 于是 `ViewModelProvider` 拿 entry 的默认工厂去反射构造 `@HiltViewModel` 类：
 * 找不到单参 `SavedStateHandle` 构造 → 退到无参构造 → `NoSuchMethodException: <init> []`
 * → `RuntimeException: Cannot create an instance of class ScriptEditorViewModel` → **闪退**。
 *
 * 官方正解是 `androidx.hilt.navigation.compose.hiltViewModel()`，但该构件**不在本工作区
 * 离线缓存**（`RootFlowMain` 的 §1.1 报告 2）。它内部做的正是本函数这件事：
 * `context.findActivity().defaultViewModelProviderFactory`。
 *
 * ## 为什么 `extras` 也要显式给（Hilt 字节码核实过，不是推测）
 * Hilt 2.58 的 `HiltViewModelFactory$2.create(modelClass, extras)` 用**传入的 extras**
 * 调 `SavedStateHandleSupport.createSavedStateHandle(extras)`；
 * 而 `NavBackStackEntry.defaultViewModelCreationExtras` 同时携带
 * `SAVED_STATE_REGISTRY_OWNER_KEY` / `VIEW_MODEL_STORE_OWNER_KEY`（= 该 entry）
 * 与 `DEFAULT_ARGS_KEY`（= 路由参数）⇒ **路由里的 `id` 才会进 `SavedStateHandle`**。
 * 少给 `extras` 不会崩，但编辑器会"永远新建"——静默错页比闪退更难发现，故两者一起给。
 */
@Composable
private fun hostViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        val activity =
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<ComponentActivity>()
                .firstOrNull()
        checkNotNull(activity) {
            "ScriptEditorScreen 需要宿主 ComponentActivity（@AndroidEntryPoint）提供 Hilt 工厂"
        }.defaultViewModelProviderFactory
    }
}
