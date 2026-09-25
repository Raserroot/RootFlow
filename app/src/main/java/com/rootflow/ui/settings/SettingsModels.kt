package com.rootflow.ui.settings

import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.SettingsTargets
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.glass.GlassPolicy
import com.rootflow.domain.glass.GlassTier
import com.rootflow.domain.residue.ResidueCleanResult
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.settings.BlurPolicy
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.ThemeMode

/** 主题三选一的一项。 */
data class ThemeOption(
    val mode: ThemeMode,
    val label: String,
)

/**
 * 权限总览的一行。
 *
 * @property grant 三态结论（**保留原值**：UI 不得只看 `granted` 布尔 ——
 *   "不适用"与"未授予"要给出不同的引导，见 `PermissionGrant` 的 KDoc）
 * @property reason 平台/领域侧给的**原始**原因串（**原样显示，不翻译**）：它是真机判读的依据
 *   （`PERMISSION name=… grant=… reason=…` 同一份文本），翻译一次就会与日志对不上。
 * @property canOpenSettings 该档位下是否真有可跳的设置页（`SettingsTargets.resolve` +
 *   `isAvailableOnSdk` 共同决定；安装即授予的权限为 `false`）
 */
data class PermissionRow(
    val permission: AndroidPermission,
    val label: String,
    val grant: PermissionGrant,
    val statusText: String,
    val reason: String,
    val canOpenSettings: Boolean,
) {
    /** 是否已授予（`NOT_APPLICABLE` **不算**已授予）。 */
    val granted: Boolean get() = grant == PermissionGrant.GRANTED

    /**
     * 卡片标题：**去掉括号说明**后的权限名（`开机自启（接收 BOOT_COMPLETED）` → `开机自启`）。
     *
     * ## 为什么是派生属性、而不是去改 [label]
     * [label] 仍是"一行式"的完整标签，**「Root 驻留」那张卡片还在用它**（本次不动那处）。
     * 本属性只服务权限总览的卡片，因此不改数据源、也不牵动别的调用方。
     *
     * ## 括号里那截为什么**不再显示**
     * 它一度作为卡片副标题（灰字第二行），但用户 2026-09-25 明确要求去掉：
     * 「把下面的灰字详细给移除掉，不然感觉有点乱」。卡片因此变成**单行**，
     * 8 张也随之等高。
     */
    val title: String get() = label.substringBefore("（").trim()
}

/** 恢复动作的一个选项（两种模式各一条，见 `RestoreMode`）。 */
data class RestoreChoice(
    val mode: RestoreMode,
    val label: String,
    val detail: String,
)

/** 日志保留天数的一个档位。 */
data class LogRetentionOption(
    val days: Int,
    val label: String,
)

/**
 * 设置页的可视状态。
 *
 * ## 它只装"投影结果"，不装判定
 * 所有 `when` / 文案 / 可用性都在 [SettingsProjections] 的纯函数里（纯 JVM 可测）；
 * 本类是它们的容器。`ui` 层只负责把它画出来 —— 与 6b `HomeModels` / 6c `ScriptModels`
 * 同款纪律（决策 B：无 UI 测试，因此"该显示什么"必须能脱离 Compose 断言）。
 *
 * @property settings 用户设置（主题 / 模糊 / 动态取色）
 * @property service 前台服务状态（`Idle` / `Running(enabled,total,safeMode)`）
 * @property sources 逐源状态（顺序与 `EventSourceRegistry.status()` 同序，真机可逐行比对）
 * @property safeMode 是否处于安全模式（真相在 `CircuitBreaker`）
 * @property tripReason 熔断成因；`null` 且 `safeMode=true` = "确实在安全模式但成因不可还原"
 *   （见 `TripReason.fromKey` 的说明：带参成因跨重启只留 key + detail，**不伪造参数**）
 * @property permissionStates 权限快照（**原始** `Map`，不在这里投影成行 ——
 *   行投影需要 `Build.VERSION.SDK_INT`，那是设备事实，由 UI 层传入纯函数
 *   [SettingsProjections.permissionRows]，从而让本状态在纯 JVM 下可构造、可断言）
 * @property runCount 运行历史条数；`null` = 还没读到（**不得显示 0**：那是"假空"）
 * @property residue 残留扫描结果；`null` = 还没扫过（**不得显示"没有残留"**）
 * @property busy 是否有动作进行中（熔断/恢复/清理）——用于禁用按钮防连点
 */
data class SettingsUiState(
    val settings: RootFlowSettings = RootFlowSettings(),
    val service: ForegroundState = ForegroundState.Idle,
    val sources: List<EventSourceStatus> = emptyList(),
    val safeMode: Boolean = false,
    val tripReason: TripReason? = null,
    val permissionStates: Map<AndroidPermission, PermissionState> = emptyMap(),
    val runCount: Int? = null,
    val residue: ResidueScan? = null,
    val busy: Boolean = false,
)

/**
 * 设置页的全部纯投影（需求 §6 五组设置 / §8 卸载残留清理）。
 *
 * ## 为什么单独一个对象而不是散在 Composable 里
 * 本项目的既有纪律（`AGENTS.md` + 决策 B）：**没有 UI 自动化测试**，因此"该显示什么"
 * 必须能脱离 Compose 断言。把 `when` 与文案集中在这里 ⇒ 可用纯 JVM 单测穷举
 * （尤其 [tripReasonLabel] 必须覆盖 `TripReason` 的**全部** 8 种成因，
 * 与 `PermissionDecisionsTest` 的"每项都被分类"同款断言）。
 */
internal object SettingsProjections {
    /** 主题三选一（顺序即 UI 顺序：跟随系统 → 浅色 → 深色）。 */
    val THEME_OPTIONS: List<ThemeOption> =
        listOf(
            ThemeOption(ThemeMode.SYSTEM, "跟随系统"),
            ThemeOption(ThemeMode.LIGHT, "浅色"),
            ThemeOption(ThemeMode.DARK, "深色"),
        )

    /**
     * 恢复动作的两个选项。
     *
     * ## ★ 为什么 `detail` 不再提"还原触发器"（总开关重构后修正）
     * 熔断改成**内存级拦截**之后，`CircuitBreakerImpl.disableAllTriggers()` 与
     * `restoreTriggers()` **都是空实现** —— 熔断过程**从不改动用户数据**
     * （订阅表、脚本的启用位都不碰）。原来的文案"把触发器还原到熔断前的启用状态"
     * 描述的是一个**已经不存在的动作**，用户会以为自己的配置被改过又还原了。
     *
     * 两个选项的**运行时行为现在相同**；保留它们是为了让"用户当时选了哪种策略"
     * 留在日志里（见 `CircuitBreakerImpl.restore` 的 KDoc）。
     */
    val RESTORE_CHOICES: List<RestoreChoice> =
        listOf(
            RestoreChoice(
                mode = RestoreMode.RestoreOriginal,
                label = "退出安全模式",
                detail = "熔断期间你的脚本与事件订阅从未被改动，退出后按原样生效",
            ),
            RestoreChoice(
                mode = RestoreMode.KeepDisabled,
                label = "退出（策略：保持停止）",
                detail = "与上一项运行时行为一致；这个选择会记进日志，供将来语义变化时追溯",
            ),
        )

    /** 主题标签（`ThemeMode` 穷举）。 */
    fun themeLabel(mode: ThemeMode): String =
        when (mode) {
            ThemeMode.SYSTEM -> "跟随系统"
            ThemeMode.LIGHT -> "浅色"
            ThemeMode.DARK -> "深色"
        }

    // ── 卸载残留（需求 §8） ──────────────────────────────────────────────

    /**
     * 残留摘要文案。
     *
     * `null` = 还没扫过（**不得显示"没有残留"**：那是"扫过且干净"的结论，
     * 两者在 UI 上必须可区分 —— 同计数三态的 `…` 纪律）。
     */
    fun residueSummaryText(scan: ResidueScan?): String =
        when (scan) {
            null -> "读取中…"
            is ResidueScan.Unavailable -> "扫描不可用：${scan.reason}"
            is ResidueScan.Ok ->
                if (scan.report.clean) {
                    "没有残留"
                } else {
                    "发现 ${scan.report.total} 项残留" +
                        "（目录 ${scan.report.orphanScriptDirs.size} 个 / 触发器行 ${scan.report.orphanTriggerRows} 行）"
                }
        }

    /**
     * 残留清理结果文案。
     *
     * 三分支：本来就干净 / 全部清掉 / **部分成功**（逐条失败原因另行展示）。
     * `0` 项清理**不是**失败。
     */
    fun residueCleanResultText(result: ResidueCleanResult): String =
        when {
            result.removedTotal == 0 && result.succeeded -> "没有残留，无需清理"
            result.succeeded ->
                "已清理 ${result.removedTotal} 项残留" +
                    "（目录 ${result.removedScriptDirs.size} 个 / 触发器行 ${result.removedTriggerRows} 行）"
            else ->
                "部分成功：已清理 ${result.removedTotal} 项，${result.failures.size} 项失败" +
                    "（首条：${result.failures.first()}）"
        }

    // ── 日志（阶段 6d） ─────────────────────────────────────────────────

    /** 保留天数档位（顺序即 UI 顺序；档位定义在 `LogRetention`，**不在这里另立一份**）。 */
    val LOG_RETENTION_OPTIONS: List<LogRetentionOption> =
        LogRetention.ALLOWED_DAYS.map { days -> LogRetentionOption(days = days, label = LogRetention.label(days)) }

    /**
     * 保留策略的一句话说明（放在档位下方）。
     *
     * 必须点明**清理的对象**：需求 §3.2 的"按设置保留 N 天"针对的是可选**文件日志**，
     * 而那份日志未实现 ⇒ 实际作用在 Room 运行历史上（见 `LogRetention` 的口径澄清）。
     * 说成"日志文件保留 N 天"会是一句实现不了的承诺。
     */
    fun retentionSummaryText(days: Int): String = "保留最近 ${LogRetention.sanitize(days)} 天的运行记录与日志（更早的会在清理时删除）"

    /** 运行历史条数文案；`null` = 还没读到（**不得显示 0**）。 */
    fun runCountText(runCount: Int?): String = runCount?.let { "当前 $it 条运行记录" } ?: "读取中…"

    /**
     * 清理结果文案。
     *
     * `deleted == 0` 是**正常结果**（保留期内没有过期数据），必须与"清理失败"区分开：
     * 前者说"没有可清理的"，不得写成"清理失败"（同 6c 的"部分成功"纪律）。
     */
    fun cleanupResultText(
        deleted: Int,
        remaining: Int?,
    ): String =
        when {
            deleted == 0 -> "没有超出保留期的历史，无需清理"
            remaining != null -> "已清理 $deleted 条运行记录，剩余 $remaining 条"
            else -> "已清理 $deleted 条运行记录"
        }

    /**
     * 毛玻璃行的状态文案（**三态**，不得折叠成布尔）。
     *
     * `userSetting == null` = 用户从未选择过 ⇒ 文案必须说清"这是设备判定的结果"，
     * 而不是让用户以为自己选过（需求 §6「低端机默认关闭」）。
     */
    fun blurStatusText(
        userSetting: Boolean?,
        effective: Boolean,
        isLowRamDevice: Boolean,
        apiLevel: Int,
    ): String =
        when {
            // 硬门：API < 31 时 Haze 只能回落纯色 tint，说"已开启"就是自欺（BlurPolicy 的 KDoc）
            userSetting == true && apiLevel < 31 -> "本系统版本不支持模糊（回落半透明纯色）"
            userSetting == true && effective -> "已开启"
            userSetting == false -> "已关闭（使用半透明纯色）"
            // 跟随设备
            effective -> "跟随设备：已开启"
            isLowRamDevice -> "跟随设备：按低端机默认关闭"
            else -> "跟随设备：已关闭"
        }

    /**
     * 液态玻璃（实验）行的状态文案（阶段 7）。
     *
     * ## 判定来源
     * 档位由 `GlassPolicy.decide` 给出（**不是**本函数自己重算），本函数只把它翻译成人话。
     * 这样"能不能用折射"这条判定只有一个真相源（`GlassTierTest` 穷举钉死）。
     *
     * ## 为什么 `HAZE` 的话要分两种
     * `HAZE` 既可能是"用户自己关的"，也可能是"这台设备／这个系统版本做不到"。
     * 说成同一句会让用户以为开关坏了（诚实性纪律：不得谎称已生效，也不得谎称未生效）。
     */
    fun glassStatusText(
        enabled: Boolean,
        tier: GlassTier,
        apiLevel: Int,
        lowRam: Boolean,
    ): String =
        when {
            !enabled -> "已关闭（底栏用 Haze 毛玻璃）"
            lowRam -> "本机为低端设备：按需求默认不启用折射"
            tier == GlassTier.REFRACTION -> "已启用 · AGSL 折射"
            apiLevel < BlurPolicy.MIN_BLUR_API_LEVEL -> "本系统版本（API $apiLevel）不支持，使用 Haze 材质"
            apiLevel < GlassPolicy.MIN_REFRACTION_API_LEVEL ->
                "本系统版本（API $apiLevel）无 AGSL：降级为模糊 + 描边"
            else -> "设备图形能力不支持，降级为模糊 + 描边"
        }

    /** 服务状态文案。`Idle` 如实说"未运行"，不假装在跑。 */
    fun serviceStatusText(state: ForegroundState): String =
        when (state) {
            ForegroundState.Idle -> "未运行"
            is ForegroundState.Running -> "运行中 · 事件源 ${state.enabled}/${state.total}"
        }

    /** 安全模式状态文案。 */
    fun safeModeStatusText(
        safeMode: Boolean,
        reason: TripReason?,
    ): String =
        when {
            !safeMode -> "正常（未熔断）"
            reason != null -> "安全模式：${tripReasonLabel(reason)}"
            // 与主页 banner 同口径：不说"原因未知"就够了吗？不 —— 必须点明"flag 存在即安全模式"，
            // 否则用户会以为是检测失败（`safemode.flag` 是熔断过的**证据**）
            else -> "安全模式：成因不可还原（flag 存在即安全模式）"
        }

    /**
     * 熔断成因的中文标签。
     *
     * ## 未知键必须原样带出
     * `safemode.flag` 可能由**更新前的版本**写入（键在未来/过去存在过），
     * 因此这里对未识别的 `reasonKey` **不编造**中文，而是原样显示键值 —— 便于排查。
     *
     * ## 为什么有"按稳定键"的重载
     * `TripReason` 是 **sealed**（且 sealed 接口**不能跨模块继承**），
     * 单元测试无法造一个"未来版本的成因对象"来验兜底分支。因此把判定下沉到
     * **稳定键**这一层：测试可以直接喂任意键字符串（见 `SettingsModelsTest`），
     * 而生产路径仍走 `TripReason` 重载。
     */
    fun tripReasonLabel(reason: TripReason): String = tripReasonLabel(reason.reasonKey)

    /** 按稳定键取文案（可测入口；见 [tripReasonLabel] 的重载说明）。 */
    fun tripReasonLabel(reasonKey: String): String =
        when (reasonKey) {
            TripReason.ScriptTimeout.KEY -> "脚本超时"
            TripReason.ConsecutiveFailures.KEY -> "同一脚本连续失败"
            TripReason.FailureStorm.KEY -> "失败风暴（窗口内累计失败过多）"
            TripReason.RootUnresponsive.KEY -> "Root 无响应"
            TripReason.HighFrequencyStarts.KEY -> "脚本高频自启"
            TripReason.Manual.KEY -> "手动熔断"
            TripReason.Bootloop.KEY -> "连续异常启动（bootloop 兜底）"
            TripReason.ExternalSafeMode.KEY -> "系统/其它 Root 方案已处于安全模式"
            else -> "未知成因（key=$reasonKey）"
        }

    /** 权限标签（8 项穷举；改目录必须同步改这里，单测有"每项都有标签"断言）。 */
    fun permissionLabel(permission: AndroidPermission): String =
        when (permission) {
            AndroidPermission.RECEIVE_BOOT_COMPLETED -> "开机自启（接收 BOOT_COMPLETED）"
            AndroidPermission.ACCESS_NETWORK_STATE -> "网络状态读取"
            AndroidPermission.POST_NOTIFICATIONS -> "通知（前台服务常驻 / 熔断告警）"
            AndroidPermission.SCHEDULE_EXACT_ALARM -> "精确闹钟"
            AndroidPermission.PACKAGE_USAGE_STATS -> "使用情况访问（前台 App 检测）"
            AndroidPermission.FOREGROUND_SERVICE -> "前台服务"
            AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE -> "前台服务（specialUse）"
            AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> "电池优化白名单"
        }

    /** 权限结论文案（**三态**：已授予 / 未授予 / 不适用）。 */
    fun permissionStatusText(grant: PermissionGrant): String =
        when (grant) {
            PermissionGrant.GRANTED -> "已授予"
            PermissionGrant.DENIED -> "未授予"
            PermissionGrant.NOT_APPLICABLE -> "不适用"
        }

    /**
     * 权限总览（按 [AndroidPermission] 的声明顺序，**8 项全列**）。
     *
     * @param states `PermissionStatusProvider.current()` 的快照
     * @param sdkInt `Build.VERSION.SDK_INT`（**显式传入**，便于单测穷举 30/31 两档）
     */
    fun permissionRows(
        states: Map<AndroidPermission, PermissionState>,
        sdkInt: Int,
    ): List<PermissionRow> =
        AndroidPermission.entries.map { permission ->
            val state = states[permission]
            PermissionRow(
                permission = permission,
                label = permissionLabel(permission),
                grant = state?.grant ?: PermissionGrant.DENIED,
                statusText = state?.let { permissionStatusText(it.grant) } ?: "未探测",
                reason = state?.reason ?: "",
                canOpenSettings =
                    SettingsTargets.resolve(permission) != null &&
                        SettingsTargets.isAvailableOnSdk(permission, sdkInt),
            )
        }

    /**
     * 需要引导授权的权限（`DENIED` **且**有可跳的设置页）。
     *
     * 用于"Root 驻留"与"关于"的摘要行：`NOT_APPLICABLE` 的项不提示（无处可授），
     * 安装即授予的项也不会出现在这里。
     */
    fun pendingPermissions(rows: List<PermissionRow>): List<PermissionRow> =
        rows.filter { it.grant == PermissionGrant.DENIED && it.canOpenSettings }
}
