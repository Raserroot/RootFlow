package com.rootflow.domain.event

/**
 * 权限探测所需的 Android 框架原语（阶段 3c.1）。
 *
 * ## 这是唯一的「Android 注入缝」
 * [PermissionDecisions.evaluate] 是纯函数，但判定终究要读系统状态。本接口把"读"收敛成
 * 三个不含任何 Android 类型的方法，使**判定逻辑（含 4 档版本分支）可被纯 JVM 单测完整覆盖**；
 * 唯一实现 `data/event/android/AndroidPermissionStatusProvider` 里的 `PermissionPrimitives`
 * 才是碰 `Context` 的地方（**按决策 13 不进单测，正确性由真机覆盖**）。
 *
 * ## 调用契约（实现与调用方都必须遵守）
 * - [isUsageStatsAllowed] **只允许在 API ≥ 29 时调用**：29 以下系统没有该 AppOps 通道，
 *   实现应直接返回 `false`，而 [PermissionDecisions] 保证**根本不会问**它
 *   （由 `PermissionDecisionsTest` 用记录型假件断言"未被调用"）
 * - [canScheduleExactAlarms] **只允许在 API ≥ 31 时调用**（`AlarmManager.canScheduleExactAlarms`
 *   自 API 31 起存在）
 * - 三者都**不得抛异常**：探测失败必须返回保守值（`false`），由调用方转成 `DENIED` + 原因
 */
interface PermissionPrimitives {
    /** 运行时权限是否已授予（`INSTALL_TIME` 档位也走这里，normal 权限恒为 `true`）。 */
    fun isRuntimePermissionGranted(permission: String): Boolean

    /** UsageStats 特殊权限是否已授予（**仅 API ≥ 29 调用**）。 */
    fun isUsageStatsAllowed(): Boolean

    /** 精确闹钟是否可用（**仅 API ≥ 31 调用**）。 */
    fun canScheduleExactAlarms(): Boolean

    /**
     * 本应用是否已被排除在电池优化之外（阶段 5，**API 26+ 恒可调用**）。
     *
     * 它不是"权限是否授予"，而是**系统白名单状态**（`PowerManager.isIgnoringBatteryOptimizations`）：
     * 权限声明即授予，但白名单只能由用户在设置页决定。因此**每次 `refresh()` 都要重新问**
     * （用户随时可能去设置页改），不能缓存。
     *
     * 不得抛异常：探测失败必须返回保守值 `false`（= 未放行），由调用方转成 `DENIED` + 原因。
     */
    fun isIgnoringBatteryOptimizations(): Boolean
}

/**
 * 「4 档版本分支」的形式化（阶段 3c.1，`PROJECT_STATE.md` §D 判定矩阵）。
 *
 * ## 四档（`minSdk = 26`）
 * | 档 | API | 特征 |
 * |---|---|---|
 * | 1 | 26–28 | 无 UsageStats 授权入口；无精确闹钟概念；无通知运行时权限 |
 * | 2 | 29–30 | + UsageStats 走 AppOps |
 * | 3 | 31–32 | + `canScheduleExactAlarms()` |
 * | 4 | 33+ | + `POST_NOTIFICATIONS` 运行时权限 |
 *
 * 阶段 5 新增的 [Requirement.BATTERY_OPTIMIZATION] **不占新档**：它自 API 23 起可用，
 * 而本项目的 `minSdk = 26` ⇒ 四档之上它恒走同一条路径（由 `TIER_BATTERY_OPTIMIZATION_MIN_SDK`
 * 显式表达，避免靠读者自己记住 minSdk）。
 *
 * ## 为什么集中在这里
 * 这是**唯一**允许出现版本分支的地方。适配器里除 `AppOpsManager` 两个方法名的选择外，
 * **不得**再有任何 `if (sdkInt >= …)`（决策 13）；否则版本逻辑会散落到不可测的代码里。
 *
 * ## 全部是纯函数
 * 本对象不 import 任何 Android 类，[evaluate] 只吃 [sdkInt] 与 [primitives]，
 * 因此四档 × 5 权限的全矩阵可在纯 JVM 下单测穷举。
 */
object PermissionDecisions {
    /** UsageStats 走 AppOps 的最低 API（档 2 起点）。 */
    const val TIER_USAGE_STATS_MIN_SDK: Int = 29

    /** `canScheduleExactAlarms` 可用的最低 API（档 3 起点）。 */
    const val TIER_EXACT_ALARM_MIN_SDK: Int = 31

    /** `POST_NOTIFICATIONS` 成为运行时权限的最低 API（档 4 起点）。 */
    const val TIER_NOTIFICATIONS_MIN_SDK: Int = 33

    /**
     * 电池优化白名单的最低 API（阶段 5）。
     *
     * `PowerManager.isIgnoringBatteryOptimizations` 自 API 23 起存在，而项目 `minSdk = 26`
     * ⇒ **任何支持的档位都可调用**，取值即 `minSdk` 本身（写出来是为了让"无档位分支"
     * 这件事在代码里显式可见，而不是靠读者自己记住 minSdk）。
     */
    const val TIER_BATTERY_OPTIMIZATION_MIN_SDK: Int = 26

    /** 可用理由前缀：`NOT_APPLICABLE` 档位。 */
    const val REASON_NOT_AVAILABLE_PREFIX: String = "not available on API "

    /** 可用理由前缀：运行时权限未授予。 */
    const val REASON_NOT_GRANTED_PREFIX: String = "not granted: "

    /** 可用理由：API 26–28 没有 UsageStats 授权入口。 */
    const val REASON_USAGE_STATS_NO_ENTRY: String =
        "no usage-stats authorization entry before API $TIER_USAGE_STATS_MIN_SDK"

    /** 已授予时的统一理由（便于真机 `findstr` 判读）。 */
    const val REASON_GRANTED: String = "granted"

    /** 已授予（安装即授予）时的理由。 */
    const val REASON_GRANTED_INSTALL_TIME: String = "granted (install-time)"

    /**
     * 判定单项权限。
     *
     * @param permission 目录项
     * @param sdkInt 当前 `Build.VERSION.SDK_INT`（显式传入以便穷举档位）
     * @param primitives 框架原语（按上文契约调用）
     */
    fun evaluate(
        permission: AndroidPermission,
        sdkInt: Int,
        primitives: PermissionPrimitives,
    ): PermissionState {
        val grant =
            when (permission.requirement) {
                Requirement.INSTALL_TIME ->
                    if (primitives.isRuntimePermissionGranted(permission.permission)) {
                        PermissionGrant.GRANTED
                    } else {
                        PermissionGrant.DENIED
                    }

                Requirement.NOTIFICATIONS ->
                    if (sdkInt < TIER_NOTIFICATIONS_MIN_SDK) {
                        PermissionGrant.NOT_APPLICABLE
                    } else if (primitives.isRuntimePermissionGranted(permission.permission)) {
                        PermissionGrant.GRANTED
                    } else {
                        PermissionGrant.DENIED
                    }

                Requirement.EXACT_ALARM ->
                    if (sdkInt < TIER_EXACT_ALARM_MIN_SDK) {
                        PermissionGrant.NOT_APPLICABLE
                    } else if (primitives.canScheduleExactAlarms()) {
                        PermissionGrant.GRANTED
                    } else {
                        PermissionGrant.DENIED
                    }

                Requirement.USAGE_STATS ->
                    if (sdkInt >= TIER_USAGE_STATS_MIN_SDK) {
                        // 档 2+：唯一走 AppOps 的通道
                        if (primitives.isUsageStatsAllowed()) {
                            PermissionGrant.GRANTED
                        } else {
                            PermissionGrant.DENIED
                        }
                    } else if (primitives.isRuntimePermissionGranted(permission.permission)) {
                        // 档 1：PACKAGE_USAGE_STATS 在 API 28 及以下**曾经**是普通权限，
                        // 授予即 GRANTED（保留这条分支以免漏报）
                        PermissionGrant.GRANTED
                    } else {
                        PermissionGrant.DENIED
                    }

                // 阶段 5：判的是**系统白名单状态**，不是权限本身（声明即授予，
                // 用户是否放行只有系统知道）。无档位分支：minSdk=26 起该 API 恒可用。
                Requirement.BATTERY_OPTIMIZATION ->
                    if (primitives.isIgnoringBatteryOptimizations()) {
                        PermissionGrant.GRANTED
                    } else {
                        PermissionGrant.DENIED
                    }
            }

        return PermissionState(
            permission = permission,
            grant = grant,
            reason = unavailableReason(permission, sdkInt, grant),
        )
    }

    /**
     * 档位推导：该 [Requirement] 从哪个 API 起才"存在"。
     *
     * @return 需要的 API 级别；`0` 表示任何档位都适用
     */
    fun tierMinSdk(requirement: Requirement): Int =
        when (requirement) {
            Requirement.INSTALL_TIME -> 0
            Requirement.USAGE_STATS -> TIER_USAGE_STATS_MIN_SDK
            Requirement.EXACT_ALARM -> TIER_EXACT_ALARM_MIN_SDK
            Requirement.NOTIFICATIONS -> TIER_NOTIFICATIONS_MIN_SDK
            Requirement.BATTERY_OPTIMIZATION -> TIER_BATTERY_OPTIMIZATION_MIN_SDK
        }

    /**
     * 生成 `GRANTED` / `DENIED` / `NOT_APPLICABLE` 对应的理由文案。
     *
     * **纯函数**：把"为什么是这个结论"的选择从适配器里移出来，使其可单测且全项目口径一致。
     */
    fun unavailableReason(
        permission: AndroidPermission,
        sdkInt: Int,
        grant: PermissionGrant,
    ): String =
        when (grant) {
            PermissionGrant.GRANTED ->
                if (permission.requirement == Requirement.INSTALL_TIME) {
                    REASON_GRANTED_INSTALL_TIME
                } else {
                    REASON_GRANTED
                }

            PermissionGrant.NOT_APPLICABLE ->
                REASON_NOT_AVAILABLE_PREFIX + sdkInt +
                    " (requires API " + tierMinSdk(permission.requirement) + ")"

            PermissionGrant.DENIED ->
                when {
                    permission.requirement == Requirement.USAGE_STATS &&
                        sdkInt < TIER_USAGE_STATS_MIN_SDK -> REASON_USAGE_STATS_NO_ENTRY

                    else -> REASON_NOT_GRANTED_PREFIX + permission.permission
                }
        }
}
