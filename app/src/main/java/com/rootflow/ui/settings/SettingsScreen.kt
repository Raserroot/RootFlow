package com.rootflow.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rootflow.BuildConfig
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.SettingsTargets
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.settings.BlurPolicy
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.ThemeMode
import com.rootflow.ui.component.KeyValueRow
import com.rootflow.ui.component.SectionCard
import com.rootflow.ui.theme.NavBarReservedSpace
import kotlinx.coroutines.launch

/**
 * 设置页（阶段 6d，需求 §6「外观、Root 驻留、安全熔断、日志、关于」+ §8「卸载残留：设置页提供清理」）。
 *
 * ## 本页只做投影与布局
 * 一切"该显示什么"的判定都在 [SettingsProjections]（纯 JVM 可测）与 [SettingsViewModel] 里。
 * 本文件里**没有**业务判断 —— 与 6b `HomeScreen` / 6c `ScriptListScreen` 同款纪律
 * （决策 B：无 UI 自动化测试，因此判定必须能脱离 Compose 断言）。
 *
 * ## 设备事实在 UI 层取，不进 ViewModel
 * `isLowRamDevice` / `Build.VERSION.SDK_INT` / `BuildConfig.VERSION_NAME` 都是平台读取点：
 * 取在 Composable 里（`remember` 缓存），再交给纯函数投影。这样 ViewModel 保持纯 JVM 可测。
 *
 * ## 危险动作的二次确认留在本页
 * 「立即熔断」与「恢复」都会真的改状态（写 `safemode.flag`；**不再**改动触发器启用位 ——
 * 熔断改内存级拦截后，它**从不碰用户数据**），
 * 因此**必须**先过 [AlertDialog]；`SettingsViewModel` 的方法一经调用即执行，不自己弹框。
 *
 * ## 权限页跳转的三条约束（决策 9 + 裁定 ③）
 * 1. `SettingsTargets` 只给**描述符**，"怎么去"由本页构造 `Intent`（domain 不认识 Android 类型）
 * 2. 电池优化只跳**设置列表页**，不调 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * 3. 跳转前先问 `isAvailableOnSdk`：低版本没有该页时要提示，而不是崩在 `ActivityNotFoundException`
 *
 * @param viewModel 设置页 ViewModel（Activity 作用域；本页不在 `NavHost` 内，无 6c 的 entry 工厂坑）
 */
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 设备常量（缓存：用户每切一次开关都去问 ActivityManager 没有意义）
    val isLowRamDevice = remember(context) { context.isLowRamDevice() }
    val sdkInt = Build.VERSION.SDK_INT

    var confirmingTrip by remember { mutableStateOf(false) }
    var confirmingRestore by remember { mutableStateOf(false) }
    var confirmingCleanup by remember { mutableStateOf(false) }
    var confirmingResidue by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // 决策 6：权限变化没有可靠广播 ⇒ 从系统设置页**返回时**主动重探。
    // 否则用户授完权回来，本页仍显示"未授予"（那是假信息）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
        // 运行历史条数同理：清理过 / 跑过脚本之后回来，这个数必须是新的
        viewModel.refreshRunCount()
        // 残留是"磁盘 + 库"的差集：跑过脚本、删过脚本之后都可能变，回来就重扫
        viewModel.refreshResidue()
    }

    val permissionRows = SettingsProjections.permissionRows(state.permissionStates, sdkInt)

    /** 跳系统设置页；失败**不静默**（低版本无此页 / 厂商裁掉该 Activity 都会走到这里）。 */
    fun openSettingsPage(permission: AndroidPermission) {
        val target = SettingsTargets.resolve(permission) ?: return
        val launched =
            runCatching {
                val intent =
                    Intent(target.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (target.packageUri) {
                    intent.data = Uri.fromParts("package", context.packageName, null)
                }
                // ★ 真机实测（本机 ColorOS / API 35，2026-09-20）：
                //   通知设置页**只给 `package:` data 会解析不到 Activity**
                //   （App 内 `result code=-91`；命令行 `am start -d package:…` 同样 unable to resolve）；
                //   **正式契约是 `EXTRA_APP_PACKAGE`** —— 换 extra 后立刻打开
                //   `com.oplus.notificationmanager/AppNotificationSettingsActivity`。
                //   其余三个 action 的 `package:` 形式实测可解析（电池列表页 / 使用情况访问 / 精确闹钟），
                //   见 `STAGE6D-DEVICE-VERIFICATION.md` 项 4。
                if (target.action == SettingsTargets.ACTION_APP_NOTIFICATION_SETTINGS) {
                    intent.data = null
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }.isSuccess
        if (!launched) {
            scope.launch {
                snackbarHostState.showSnackbar("无法打开该系统设置页：${SettingsProjections.permissionLabel(permission)}")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppearanceSection(
                settings = state.settings,
                isLowRamDevice = isLowRamDevice,
                sdkInt = sdkInt,
                onThemeMode = viewModel::setThemeMode,
                onDynamicColor = viewModel::setDynamicColor,
                onBlurEnabled = viewModel::setBlurEnabled,
            )

            RootResidencySection(
                service = state.service,
                sources = state.sources,
                notificationRow = permissionRows.firstOrNull { it.permission == AndroidPermission.POST_NOTIFICATIONS },
                batteryRow =
                    permissionRows.firstOrNull {
                        it.permission == AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    },
                onOpenSettings = ::openSettingsPage,
            )

            CircuitBreakerSection(
                safeMode = state.safeMode,
                tripReason = state.tripReason,
                busy = state.busy,
                onRequestTrip = { confirmingTrip = true },
                onRequestRestore = { confirmingRestore = true },
            )

            LogSection(
                retentionDays = state.settings.logRetentionDays,
                runCount = state.runCount,
                busy = state.busy,
                onRetentionChange = viewModel::setLogRetentionDays,
                onRequestCleanup = { confirmingCleanup = true },
            )

            AboutSection(
                versionName = BuildConfig.VERSION_NAME,
                rows = permissionRows,
                residue = state.residue,
                busy = state.busy,
                onOpenSettings = ::openSettingsPage,
                onRequestResidueCleanup = { confirmingResidue = true },
            )

            // ★ 阶段 7（方向 A）：底栏的让位从「内容层整体 padding」下移到**本滚动容器**。
            //   `verticalScroll` 没有 `contentPadding` 参数，因此用**末尾 Spacer**：
            //   两个作用缺一不可：
            //   ① 内容能滚到胶囊**下方**（玻璃才有东西可折射/模糊）；
            //   ② 最后一项静止时**不被胶囊压住**。
            //   它必须是最后一个子项（否则等于没加）。见 `NavBarReservedSpace` 的 KDoc。
            Spacer(modifier = Modifier.height(NavBarReservedSpace))
        }
    }

    if (confirmingTrip) {
        TripConfirmDialog(
            onConfirm = {
                confirmingTrip = false
                viewModel.tripManually()
            },
            onDismiss = { confirmingTrip = false },
        )
    }

    if (confirmingRestore) {
        RestoreConfirmDialog(
            choices = SettingsProjections.RESTORE_CHOICES,
            onChoose = { mode ->
                confirmingRestore = false
                viewModel.restore(mode)
            },
            onDismiss = { confirmingRestore = false },
        )
    }

    if (confirmingCleanup) {
        CleanupConfirmDialog(
            retentionDays = state.settings.logRetentionDays,
            onConfirm = {
                confirmingCleanup = false
                viewModel.cleanupLogs()
            },
            onDismiss = { confirmingCleanup = false },
        )
    }

    if (confirmingResidue) {
        ResidueConfirmDialog(
            residue = state.residue,
            onConfirm = {
                confirmingResidue = false
                viewModel.cleanResidue()
            },
            onDismiss = { confirmingResidue = false },
        )
    }
}

// ── 外观（需求 §6：深色/浅色/跟随系统 + 动态配色 + 性能降级） ────────────────────

@Composable
private fun AppearanceSection(
    settings: RootFlowSettings,
    isLowRamDevice: Boolean,
    sdkInt: Int,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onBlurEnabled: (Boolean?) -> Unit,
) {
    val blurEffective =
        BlurPolicy.effectiveBlur(
            userSetting = settings.blurEnabled,
            isLowRamDevice = isLowRamDevice,
            apiLevel = sdkInt,
        )
    // ★ 阶段 11e：`glassTier` 的本地判定**已移除**（液态玻璃不再存在，也就没有"档位"要显示）。
    //   注意 `blurEffective` 仍然保留 —— 它是**毛玻璃**的判定，与液态玻璃无关。

    SectionCard(title = "外观") {
        Text(
            text = "主题",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsProjections.THEME_OPTIONS.forEach { option ->
                FilterChip(
                    selected = settings.themeMode == option.mode,
                    onClick = { onThemeMode(option.mode) },
                    label = { Text(text = option.label) },
                )
            }
        }

        HorizontalDivider()

        SettingSwitchRow(
            label = "动态取色（Material You）",
            // API 31 以下系统不提供动态配色：置灰 + 说明，而不是让开关看起来能用
            enabled = sdkInt >= DYNAMIC_COLOR_MIN_SDK,
            checked = settings.dynamicColor && sdkInt >= DYNAMIC_COLOR_MIN_SDK,
            supporting =
                if (sdkInt >= DYNAMIC_COLOR_MIN_SDK) {
                    null
                } else {
                    "本系统版本（API $sdkInt）不支持动态取色，使用品牌配色"
                },
            onCheckedChange = onDynamicColor,
        )

        HorizontalDivider()

        SettingSwitchRow(
            label = "毛玻璃（底栏模糊）",
            enabled = true,
            checked = blurEffective,
            supporting =
                SettingsProjections.blurStatusText(
                    userSetting = settings.blurEnabled,
                    effective = blurEffective,
                    isLowRamDevice = isLowRamDevice,
                    apiLevel = sdkInt,
                ),
            onCheckedChange = onBlurEnabled,
        )

        // 三态复位入口：用户一旦手动选过，就再也回不到"跟随设备"——那是一个单向门。
        if (settings.blurEnabled != null) {
            TextButton(onClick = { onBlurEnabled(null) }) {
                Text(text = "恢复「跟随设备判定」")
            }
        }

        // ★ 阶段 11e：「液态玻璃（实验）」开关**整个移除**（用户指令）。
        //   底栏此后只有毛玻璃一种材质，留着这个开关只会让人以为"打开它能看到什么"。
        //   它的判定链（`GlassPolicy.decide`）与状态文案（`SettingsProjections.glassStatusText`）
        //   一并从本页摘除；后者的实现保留在 `SettingsModels.kt` 里作为留档。
    }
}

// ── Root 驻留（需求 §7：前台服务 + 通知 + 电池白名单） ───────────────────────────

@Composable
private fun RootResidencySection(
    service: ForegroundState,
    sources: List<EventSourceStatus>,
    notificationRow: PermissionRow?,
    batteryRow: PermissionRow?,
    onOpenSettings: (AndroidPermission) -> Unit,
) {
    SectionCard(title = "Root 驻留") {
        KeyValueRow(
            label = "前台服务",
            value = SettingsProjections.serviceStatusText(service),
        )

        // 不可用的事件源要**说出原因**（决策 7：不得只给笼统结论）。
        // 只列前两条并给总数：这一行的作用是"点醒用户去授权"，不是替主页的事件源列表。
        val unavailable = sources.filter { it.state is EventSourceState.Unavailable }
        if (unavailable.isNotEmpty()) {
            val first = unavailable.first()
            val reason = (first.state as EventSourceState.Unavailable).reason
            KeyValueRow(
                label = "事件源不可用",
                value = "${unavailable.size} 个（如 ${first.sourceId}：$reason）",
                valueColor = MaterialTheme.colorScheme.error,
            )
        }

        notificationRow?.let { row ->
            HorizontalDivider()
            PermissionActionRow(
                row = row,
                actionLabel = "去授权",
                onOpenSettings = onOpenSettings,
            )
        }

        batteryRow?.let { row ->
            PermissionActionRow(
                row = row,
                actionLabel = "打开电池优化设置",
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

// ── 安全熔断（需求 §5：状态 / 立即熔断 / 恢复） ────────────────────────────────

@Composable
private fun CircuitBreakerSection(
    safeMode: Boolean,
    tripReason: TripReason?,
    busy: Boolean,
    onRequestTrip: () -> Unit,
    onRequestRestore: () -> Unit,
) {
    SectionCard(title = "安全熔断") {
        KeyValueRow(
            label = "当前状态",
            value = SettingsProjections.safeModeStatusText(safeMode, tripReason),
            valueColor =
                if (safeMode) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        tripReason?.let { reason ->
            KeyValueRow(label = "成因明细", value = reason.detail)
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRequestTrip,
                enabled = !busy && !safeMode,
            ) {
                Text(text = "立即熔断")
            }
            OutlinedButton(
                onClick = onRequestRestore,
                enabled = !busy && safeMode,
            ) {
                Text(text = "退出安全模式")
            }
        }
        Text(
            text =
                if (safeMode) {
                    "熔断期间触发器全部停用；恢复时按你选择的模式处理启用状态。"
                } else {
                    "熔断会立刻停用全部触发器并写 safemode.flag（救砖用）。"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 日志（需求 §3.2 的"按设置保留 N 天"，口径见 LogRetention） ────────────────

@Composable
private fun LogSection(
    retentionDays: Int,
    runCount: Int?,
    busy: Boolean,
    onRetentionChange: (Int) -> Unit,
    onRequestCleanup: () -> Unit,
) {
    SectionCard(title = "日志") {
        KeyValueRow(
            label = "运行历史",
            value = SettingsProjections.runCountText(runCount),
        )

        Text(
            text = "保留天数",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsProjections.LOG_RETENTION_OPTIONS.forEach { option ->
                FilterChip(
                    selected = retentionDays == option.days,
                    onClick = { onRetentionChange(option.days) },
                    label = { Text(text = option.label) },
                )
            }
        }
        Text(
            text = SettingsProjections.retentionSummaryText(retentionDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        OutlinedButton(
            onClick = onRequestCleanup,
            enabled = !busy,
        ) {
            Text(text = "立即清理")
        }
        Text(
            text = "清理只删除超出保留期的运行记录与其日志；保留期内的一条不动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 关于（需求 §6 + §8 的权限总览） ───────────────────────────────────────────

@Composable
private fun AboutSection(
    versionName: String,
    rows: List<PermissionRow>,
    residue: ResidueScan?,
    busy: Boolean,
    onOpenSettings: (AndroidPermission) -> Unit,
    onRequestResidueCleanup: () -> Unit,
) {
    SectionCard(title = "关于") {
        KeyValueRow(label = "应用版本", value = versionName)
        KeyValueRow(
            label = "未授予的权限",
            value =
                SettingsProjections
                    .pendingPermissions(rows)
                    .joinToString(separator = "、") { it.label }
                    .ifEmpty { "无" },
        )

        HorizontalDivider()

        // 需求 §8「卸载残留：设置页提供清理」
        KeyValueRow(
            label = "卸载残留",
            value = SettingsProjections.residueSummaryText(residue),
            valueColor =
                if (residue is ResidueScan.Ok && !residue.report.clean) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        OutlinedButton(
            onClick = onRequestResidueCleanup,
            enabled = !busy,
        ) {
            Text(text = "清理残留")
        }
        Text(
            text =
                "清理会删除 scripts/ 下没有对应脚本的目录，以及指向不存在脚本的触发器行；" +
                    "非本项目写入的条目一律不动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text(
            text = "权限总览（与 AndroidManifest 声明严格一致）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            PermissionActionRow(
                row = row,
                actionLabel = "去设置",
                onOpenSettings = onOpenSettings,
                compact = true,
            )
        }
    }
}

// ── 复用行 ────────────────────────────────────────────────────────────────────

/**
 * 带标签的开关行。
 *
 * @param enabled `false` = 该档位下不可用（置灰），**不是**"关着"
 * @param supporting 副标题；`null` 表示这行不需要解释
 */
@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    supporting: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            supporting?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 权限行：状态 + （可选）跳转按钮。
 *
 * ## 为什么按钮只在 `canOpenSettings` 时可点
 * 安装即授予的权限**没有**"授予它"的界面；给一个点了没反应的按钮等于骗用户
 * （决策 9：`SettingsTargets.resolve` 对这类权限返回 `null`）。
 */
@Composable
private fun PermissionActionRow(
    row: PermissionRow,
    actionLabel: String,
    onOpenSettings: (AndroidPermission) -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (row.reason.isEmpty()) row.statusText else "${row.statusText} · ${row.reason}",
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (row.granted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        if (!compact && row.canOpenSettings) {
            // ★ 已授予时不能说"去授权"：那会让用户以为还没授权（诚实性，与"不静默"同一纪律）。
            //   按钮仍然保留 —— 用户可能需要进系统页调渠道 / 电池白名单，而不是只能授权。
            TextButton(onClick = { onOpenSettings(row.permission) }) {
                Text(text = if (row.granted) "打开系统设置" else actionLabel)
            }
        }
    }
}

// ── 二次确认 ─────────────────────────────────────────────────────────────────

/**
 * 「立即熔断」确认框。
 *
 * 文案说明**后果**（触发器全部停用）而不是问"确定吗" —— 后者在危险动作上等于没问。
 */
@Composable
private fun TripConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "立即熔断？") },
        text = { Text(text = "将立刻停用全部触发器并进入安全模式（写入 safemode.flag）。脚本不再被任何事件唤醒。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "熔断") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/**
 * 「恢复」确认框（两种模式二选一）。
 *
 * ## 为什么两个按钮都是动作而不是"确定/取消"
 * `RestoreOriginal` 与 `KeepDisabled` 的**后果不同**（前者还原启用状态，后者全禁用），
 * 折叠成"确定"会让用户按下之后不知道自己得到了哪一种。
 */
@Composable
private fun RestoreConfirmDialog(
    choices: List<RestoreChoice>,
    onChoose: (RestoreMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "退出安全模式？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                choices.forEach { choice ->
                    Column {
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = choice.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(choices.first().mode) }) {
                Text(text = choices.first().label)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onChoose(choices.last().mode) }) {
                    Text(text = choices.last().label)
                }
                TextButton(onClick = onDismiss) { Text(text = "取消") }
            }
        },
    )
}

/**
 * 「立即清理」确认框。
 *
 * 文案点明**删什么**（超出保留期的运行记录 + 其日志）与**不删什么**（保留期内的）。
 * 只说"确定要清理吗"等于没问 —— 用户无法判断代价。
 */
@Composable
private fun CleanupConfirmDialog(
    retentionDays: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "清理运行历史？") },
        text = {
            Text(
                text =
                    "将删除早于 ${LogRetention.sanitize(retentionDays)} 天的运行记录及其日志行；" +
                        "保留期内的一条都不会动。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "清理") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/**
 * 「清理残留」确认框。
 *
 * 文案带上**当前扫描到的项数**（未扫过时如实说"尚未扫描"）：用户据此判断这次点击的代价
 * —— 只说"确定要清理吗"等于没问。
 */
@Composable
private fun ResidueConfirmDialog(
    residue: ResidueScan?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detail =
        when (residue) {
            null -> "尚未扫描（点「清理残留」会先扫一遍再删）。"
            is ResidueScan.Unavailable -> "上次扫描不可用：${residue.reason}（点「清理残留」会重新扫描）。"
            is ResidueScan.Ok ->
                if (residue.report.clean) {
                    "当前没有残留（点「清理残留」会重新扫描确认）。"
                } else {
                    "将删除 ${residue.report.total} 项：" +
                        "${residue.report.orphanScriptDirs.size} 个孤儿正文目录 + " +
                        "${residue.report.orphanTriggerRows} 行孤儿触发器。"
                }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "清理卸载残留？") },
        text = { Text(text = "$detail\n\n删除前会重新扫描一次：只删那一刻确认是孤儿的条目。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "清理") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/** 动态取色的最低 API（`Build.VERSION_CODES.S`，此处写字面量以免引入版本常量分支）。 */
private const val DYNAMIC_COLOR_MIN_SDK: Int = 31

// ★ 阶段 11e：`GRAPHICS_CAPABILITY_ASSUMED_OK` 随「液态玻璃（实验）」开关一并摘除（用户指令）。
//   那个写死的 `true` 只喂给 `GlassPolicy.decide(graphicsCapabilityOk = ...)`，而该调用点
//   已从本页移除；底栏此后只有毛玻璃一种材质，本页不再有需要「假设图形能力」的判定。
//   那段「能力无法权威查询、只能靠构造失败兜底」的诚实登记保留在 `GlassTier.kt` 文件头。

/**
 * 设备是否为低内存（需求 §6「低端机默认关闭」模糊）。
 *
 * 与 `RootFlowMain` 的同名私有函数**刻意各留一份**：两处的调用点与缓存策略不同
 * （本页只用于文案，主页用于真正决定模糊是否生效）。抽成公共工具会让
 * "谁在读设备能力"变得隐晦，而 `ActivityManager` 的读取点在真机排查时是要能一眼找到的。
 */
private fun Context.isLowRamDevice(): Boolean =
    (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false
