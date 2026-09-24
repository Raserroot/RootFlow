package com.rootflow.domain.event

import com.rootflow.domain.event.PermissionDecisions.REASON_NOT_AVAILABLE_PREFIX

/**
 * 权限 → 设置页跳转目标（阶段 3c.1，**决策 9**）。
 *
 * ## 职责边界（决策 9 的核心）
 * 本对象**只回答"去哪个设置页"**，产出 [SettingsTarget] 描述符——**不构造
 * `android.content.Intent`**。后者属阶段 6 的 UI 层：
 * - `Intent` 在纯 JVM 下单测不可实例化 → 若在此构造，"哪一项该跳哪"这条判定就不可测
 * - `ui → domain` 分层要求 domain 不认识 Android 框架类型
 *
 * 阶段 6 的用法（示例，不在本阶段实现）：
 * ```kotlin
 * val target = permissionStatus.settingsTargetFor(AndroidPermission.PACKAGE_USAGE_STATS)
 * if (target != null) {
 *     val intent = Intent(target.action)
 *     if (target.packageUri) intent.data = Uri.fromParts("package", packageName, null)
 *     startActivity(intent)
 * }
 * ```
 *
 * ## 为什么没有 `PACKAGE_USAGE_STATS` 之外的 AppOps 跳转
 * `SCHEDULE_EXACT_ALARM` 与 `PACKAGE_USAGE_STATS` 都是**特殊权限**，没有运行时弹窗，
 * 只能靠设置页；`POST_NOTIFICATIONS` 在 API 33+ 有运行时弹窗，仅在被永久拒绝后
 * 才需要跳设置页，故这里一律给出设置页目标（多给一个入口无害，少给会卡死用户）。
 */
object SettingsTargets {
    /** "使用情况访问"设置页（API 26+ 恒存在）。 */
    const val ACTION_USAGE_ACCESS_SETTINGS: String = "android.settings.USAGE_ACCESS_SETTINGS"

    /** 应用通知设置页（API 26+ 恒存在）。 */
    const val ACTION_APP_NOTIFICATION_SETTINGS: String = "android.settings.APP_NOTIFICATION_SETTINGS"

    /** 精确闹钟授权页（**API 31+**；低版本无此页）。 */
    const val ACTION_REQUEST_SCHEDULE_EXACT_ALARM: String =
        "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"

    /**
     * 电池优化**设置列表页**（阶段 5；API 26+ 恒存在）。
     *
     * ## ★ 为什么不带 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（已批准的裁定 ③）
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限允许应用直接弹"是否忽略电池优化"的对话框
     * （`android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI）。
     * 但该 action 的**政策适用范围很窄**（只对闹钟/通话类等特定用途开放），
     * 用它换来的只是"少点一次"，却把一个可争议的调用写进产品。
     *
     * 因此这里只给**列表页**：用户进去自己找到本应用并放行，多一次点击，但路径干净。
     * 权限本身仍然声明（见 `AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），
     * 因为不声明就无法**如实上报**"当前有没有被放行"。
     */
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: String =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"

    /**
     * 解析跳转目标。
     *
     * @param permission 目录项
     * @return 需要跳转时的描述符；**安装即授予的权限返回 `null`**（无处可去）
     */
    fun resolve(permission: AndroidPermission): SettingsTarget? =
        when (permission) {
            AndroidPermission.PACKAGE_USAGE_STATS ->
                SettingsTarget(
                    action = ACTION_USAGE_ACCESS_SETTINGS,
                    packageUri = false,
                    reason = "usage-stats access is a special permission; only the settings page can grant it",
                )

            AndroidPermission.POST_NOTIFICATIONS ->
                SettingsTarget(
                    action = ACTION_APP_NOTIFICATION_SETTINGS,
                    packageUri = true,
                    reason = "notification permission must be granted from the app notification settings",
                )

            AndroidPermission.SCHEDULE_EXACT_ALARM ->
                SettingsTarget(
                    action = ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    packageUri = true,
                    reason = "exact-alarm access is a special permission; only the settings page can grant it",
                )

            AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
                SettingsTarget(
                    action = ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    // 该列表页不接受 package: 形式的数据 URI（与 USAGE_ACCESS_SETTINGS 同类）
                    packageUri = false,
                    reason =
                        "battery-optimization whitelist is user-controlled; " +
                            "open the settings list instead of requesting the exemption directly",
                )

            // 安装即授予：系统里没有"授予它"的界面，返回 null 而不是给一个跳不动的 action
            AndroidPermission.RECEIVE_BOOT_COMPLETED,
            AndroidPermission.ACCESS_NETWORK_STATE,
            // 阶段 5 新增的两条 FGS 权限同属"安装即授予"：无设置页可跳
            AndroidPermission.FOREGROUND_SERVICE,
            AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE,
            -> null
        }

    /**
     * 需要跳转但当前版本档位无此设置页时的保守结论。
     *
     * 用途：阶段 6 在 API 30 及以下点击 `SCHEDULE_EXACT_ALARM` 的提示时，
     * 应显示"本系统版本无需/不支持"而不是崩在 `ActivityNotFoundException`。
     */
    fun isAvailableOnSdk(
        permission: AndroidPermission,
        sdkInt: Int,
    ): Boolean =
        when (permission) {
            // 精确闹钟设置页自 API 31 起存在；低版本精确闹钟不需要授权
            AndroidPermission.SCHEDULE_EXACT_ALARM ->
                sdkInt >= PermissionDecisions.TIER_EXACT_ALARM_MIN_SDK

            // 通知设置页自 API 26 起存在（= minSdk），无档位差异
            AndroidPermission.POST_NOTIFICATIONS,
            AndroidPermission.PACKAGE_USAGE_STATS,
            // 电池优化列表页自 API 23 起存在 ⇒ 本项目任何档位都有（阶段 5）
            AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            -> true

            AndroidPermission.RECEIVE_BOOT_COMPLETED,
            AndroidPermission.ACCESS_NETWORK_STATE,
            AndroidPermission.FOREGROUND_SERVICE,
            AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE,
            -> false
        }

    /** 供真机判读的 `NOT_APPLICABLE` 文案（与 [PermissionDecisions] 同口径）。 */
    fun notApplicableReason(sdkInt: Int): String = REASON_NOT_AVAILABLE_PREFIX + sdkInt
}
