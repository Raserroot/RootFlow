package com.rootflow.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rootflow.BuildConfig
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.PermissionGrant
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

    /**
     * 打开**项目主页**（GitHub）。
     *
     * ## 为什么失败要提示（与 [openSettingsPage] 同款纪律）
     * 设备上没装浏览器、或该 Intent 被厂商裁掉时，`startActivity` 会抛
     * `ActivityNotFoundException`。静态失败在这里尤其糟：用户点了「项目主页」
     * 什么都没发生，只会以为这个入口是坏的。
     */
    fun openProjectHome() {
        val launched =
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_HOME_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        if (!launched) {
            scope.launch {
                snackbarHostState.showSnackbar("无法打开浏览器：$PROJECT_HOME_URL")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // ★ 11e 补丁6：`SnackbarHost` 是 `Scaffold` 的**浮层**，与 FAB 同一个坑 ——
        //   它不参与任何滚动容器的 `contentPadding`，而底栏是**浮在内容之上**的
        //   （在 `RootFlowMain` 里，不在本 Scaffold 内）⇒ Snackbar 被胶囊压住。
        //   量取同一个 `NavBarReservedSpace`（它已含底栏上下各 12dp 留白，**不要**再加边距）。
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = NavBarReservedSpace),
            )
        },
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

            // ★ 11e 补丁9：权限总览**独立成卡**（用户要求），位置在「日志」与「关于」之间
            //   —— 「关于」压轴。摘要行也跟着它走（见 `PermissionsSection` 的 KDoc）。
            PermissionsSection(
                rows = permissionRows,
                onOpenSettings = ::openSettingsPage,
            )

            AboutSection(
                versionName = BuildConfig.VERSION_NAME,
                residue = state.residue,
                busy = state.busy,
                onRequestResidueCleanup = { confirmingResidue = true },
                onOpenProjectHome = ::openProjectHome,
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

// ── 权限总览（11e 补丁9：从「关于」里拆出来的独立卡片） ────────────────────────

/**
 * 权限总览：摘要行 + 8 张卡片。
 *
 * ## 为什么从「关于」里拆出来（用户 2026-09-25 要求）
 * 它原本长在「关于」卡片里（那张卡同时装着版本号、卸载残留、权限总览、项目主页四件事）。
 * 拆开的理由不只是"太长"：**这两块回答的是不同问题** ——
 * 权限总览是"这台设备还缺什么"（要能一眼扫），关于是"这个 App 是什么"（查阅式）。
 *
 * ## 为什么摘要行也跟着搬过来
 * 「未授予的权限」本来就是这 8 项的汇总；留在「关于」会让两张卡都在讲权限。
 * 搬过来之后本卡的结构是「标题 → 摘要 → 逐项」：一句话说完，再展开。
 */
@Composable
private fun PermissionsSection(
    rows: List<PermissionRow>,
    onOpenSettings: (AndroidPermission) -> Unit,
) {
    SectionCard(title = "权限总览") {
        Text(
            text = "与 AndroidManifest 声明严格一致",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow(
            label = "未授予的权限",
            value =
                SettingsProjections
                    .pendingPermissions(rows)
                    .joinToString(separator = "、") { it.label }
                    .ifEmpty { "无" },
        )

        HorizontalDivider()

        // 用 `Column` + `spacedBy` 而不是给每张卡片写下边距：间距是"卡片之间"的关系，
        // 写在容器上才有唯一出处（与 `SectionCard` 把圆角收在一处是同款理由）。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                PermissionCardRow(row = row, onOpenSettings = onOpenSettings)
            }
        }
    }
}

// ── 关于（需求 §6 的版本信息 + §8 的卸载残留） ────────────────────────────────

@Composable
private fun AboutSection(
    versionName: String,
    residue: ResidueScan?,
    busy: Boolean,
    onRequestResidueCleanup: () -> Unit,
    onOpenProjectHome: () -> Unit,
) {
    SectionCard(title = "关于") {
        KeyValueRow(label = "应用版本", value = versionName)

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

        // ★ 11e 补丁7：项目主页入口（用户提议）。
        //   做成**可点整行**而不是按钮：本卡片其余内容都是「标签 : 值」的行式阅读节奏，
        //   插一个按钮进去会打断它；而"整行可点 + 右侧箭头"是同一节奏的自然延伸
        //   （下面的 `PermissionActionRow` 同样是"行 + 行尾动作"，不是一个独立按钮）。
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenProjectHome)
                    .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "项目主页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = PROJECT_HOME_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// ── 权限总览的卡片式行（11e 补丁8） ─────────────────────────────────────────────

/**
 * 「关于」里权限总览的一行 —— **卡片式**（用户 2026-09-25 参照 OS々 的形态指定）。
 *
 * ## 形态
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ (✔)  开机自启                      [已授予]  › │   ← 圆底图标 / 标题 / 胶囊 / 箭头
 * │      接收 BOOT_COMPLETED                      │   ← 副标题（没有括号的权限不显示这行）
 * └──────────────────────────────────────────────┘
 * ```
 * **整行可点** —— 不再单独放「去设置」按钮（参照 App 就是这么做的，且省掉一个常驻控件）。
 *
 * ## 与 [PermissionActionRow] 的分工（**两处互不替代**）
 * - [PermissionActionRow]：「Root 驻留」那张卡片用的**行内按钮**形态，本次**不动**
 * - 本组件：「关于」的权限总览专用
 * 不合并的理由是信息密度不同：Root 驻留是"配合上面的摘要读"，一行越短越好；
 * 权限总览是 8 项并排自检，需要能一眼扫出哪几项没到位。
 *
 * ## 颜色（用户指定）
 * 已授予 = **绿**，未授予 = **橙**，`NOT_APPLICABLE` = 中性灰。
 *
 * ## 为什么未授予用橙而不是红
 * 用户原话「未授权橙色吧」，理由也站得住：**"没授权"不等于"出错了"**
 * —— 它只是"还没做"，与 `error` 语义（失败）不是一回事。红色留给真正的失败。
 *
 * ## 没有可跳页时不可点、也不画箭头
 * `canOpenSettings` 为 `false`（安装即授予的权限、或该 SDK 档位没有对应设置页）时，
 * 不加 `clickable`：给一个点了没反应的入口等于骗用户（决策 9）。
 */
@Composable
private fun PermissionCardRow(
    row: PermissionRow,
    onOpenSettings: (AndroidPermission) -> Unit,
) {
    val accent = permissionAccent(row.grant)
    val shape = RoundedCornerShape(PERMISSION_CARD_CORNER)
    val clickable = row.canOpenSettings

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                // ★ 用 `clickable(enabled = …)` 而不是条件构造 Modifier：
                //   两者视觉完全一致（禁用态不产生涟漪），但前者让"这行点不点得动"
                //   在代码里是一个显式参数，而不是藏在 `if` 的两个分支里。
                //   代价：禁用态在无障碍语义上仍被标为可点击 —— 而 `canOpenSettings = false`
                //   的只有"安装即授予"的那几项，它们本来也没有可跳的页面，取舍可接受。
                .clickable(enabled = clickable) { onOpenSettings(row.permission) }
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                ).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(PERMISSION_ICON_BADGE)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = PERMISSION_ACCENT_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // ★ 11e 补丁9：用**每项自己的**语义图标（原来是统一的 CheckCircle / Warning）。
                //   状态改由 `tint`（绿 / 橙）与右侧胶囊承担。
                imageVector = permissionIcon(row.permission),
                // 纯装饰：右边的胶囊已经把状态说清楚了，念两遍是噪音。
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(PERMISSION_ICON_SIZE),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // ★ 单行 + 省略：真机（density 560）实测「电池优化白名单」7 个字会把标题**折行**，
            //   于是卡片高矮不一。这里不靠"再缩字号"解决（`titleSmall` 16sp 已是本页正文档），
            //   而是**保证绝不折行** —— 装不下就省略号，卡片高度因此恒定。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = PERMISSION_ACCENT_BG_ALPHA))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = row.statusText,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }

        if (clickable) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PERMISSION_CHEVRON_SIZE),
            )
        }
    }
}

/**
 * 权限 → **它自己的图标**（11e 补丁9，用户要求）。
 *
 * ## 为什么每项一个图形，而不是统一的对勾 / 感叹号
 * 用户原话：「单一的【✔】太普通了……可以按照每个不同的权限给不同的那个图标」。
 * 更实际的收益：8 项并排时，**图形比中文标签更快被扫到** —— "哪一项是网络、哪一项是电池"
 * 不需要逐行读字。
 *
 * ## 那么"授没授"由什么表达
 * **颜色**（绿 / 橙，见 [permissionAccent]）与**右侧胶囊**（已授予 / 未授予）。
 * 也就是把原来"图标兼表状态"拆成两件事：**图形说是什么，颜色说怎么样**。
 *
 * ## 图标从哪来
 * `PermissionIcons.kt` —— 从官方 `material-icons-extended` 提取的**逐字节一致**的图形
 * （core 只有 49 个，没有电源 / 网络 / 闹钟 / 电池）。该文件头解释了为什么不直接引依赖。
 */
private fun permissionIcon(permission: AndroidPermission): ImageVector =
    when (permission) {
        AndroidPermission.RECEIVE_BOOT_COMPLETED -> PermissionIconBoot
        AndroidPermission.ACCESS_NETWORK_STATE -> PermissionIconNetwork
        AndroidPermission.POST_NOTIFICATIONS -> PermissionIconNotification
        AndroidPermission.SCHEDULE_EXACT_ALARM -> PermissionIconAlarm
        AndroidPermission.PACKAGE_USAGE_STATS -> PermissionIconUsage
        AndroidPermission.FOREGROUND_SERVICE -> PermissionIconService
        AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE -> PermissionIconServiceSpecial
        AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> PermissionIconBattery
    }

/**
 * 权限状态的强调色。
 *
 * ## 为什么写死色值，而不是用 MD3 的 `error` / `primary`
 * 用户点名了配色（**绿 / 橙**），而 MD3 的语义色里没有"绿"这一档
 * （`primary` 在本项目的默认配色下是紫色）。与 `NavBarPalette` 同一条纪律：
 * **底栏与权限总览是本项目仅有的两处"用户点名了颜色"的地方**，其余界面仍完全跟随动态取色。
 *
 * ## 为什么深色档单独给
 * `#2E7D32` 在深色表面上对比度不足（会糊成一片暗绿），因此深色档换亮一档。
 * `NOT_APPLICABLE` 用 `onSurfaceVariant`：它既不是"已给"也不是"待给"，用状态色会误导。
 */
@Composable
private fun permissionAccent(grant: PermissionGrant): Color {
    val dark = isSystemInDarkTheme()
    return when (grant) {
        PermissionGrant.GRANTED -> if (dark) PERMISSION_GRANTED_DARK else PERMISSION_GRANTED_LIGHT
        PermissionGrant.DENIED -> if (dark) PERMISSION_DENIED_DARK else PERMISSION_DENIED_LIGHT
        PermissionGrant.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 权限卡片的圆角（对齐参照 App 的大圆角）。 */
private val PERMISSION_CARD_CORNER = 20.dp

/** 左侧图标容器的直径（真机实测收到 32dp 才够中间列放下 7 个汉字的权限名）。 */
private val PERMISSION_ICON_BADGE = 32.dp

/** 状态图标本身的尺寸（容器 32dp 时按 18dp 更协调）。 */
private val PERMISSION_ICON_SIZE = 18.dp

/** 右侧跳转箭头的尺寸。 */
private val PERMISSION_CHEVRON_SIZE = 18.dp

/** 强调色作为底（圆底 / 胶囊）时的 alpha：够看出色相，又不至于盖过白底。 */
private const val PERMISSION_ACCENT_BG_ALPHA = 0.14f

/** 已授予（浅色 / 深色）。 */
private val PERMISSION_GRANTED_LIGHT = Color(0xFF2E7D32)
private val PERMISSION_GRANTED_DARK = Color(0xFF81C784)

/** 未授予（浅色 / 深色）。 */
private val PERMISSION_DENIED_LIGHT = Color(0xFFE65100)
private val PERMISSION_DENIED_DARK = Color(0xFFFFB74D)

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

/**
 * 项目主页（用户 2026-09-25 提议加到设置页「关于」卡片里）。
 *
 * 指向**公开仓库** —— 即本 App 的 Release 发布源。
 *
 * ⚠️ **不要**改成开发仓库的路径：两者内容不同。公开仓库是**有意构建的干净快照**，
 * 不含开发期的过程文档（`PROJECT_STATE.md` / `AGENT_PROTOCOL.md` / `STAGE*-PLAN.md` 等），
 * 也不含任何真机截图。
 */
private const val PROJECT_HOME_URL: String = "https://github.com/Raserroot/RootFlow"

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
