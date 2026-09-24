package com.rootflow.domain.event

import kotlinx.coroutines.flow.Flow

/**
 * 权限的「要求档」——决定用哪一条判定路径（阶段 3c.1，`PROJECT_STATE.md` 决策 10）。
 *
 * 本枚举是**纯领域概念**，不含任何 Android 常量，因此可被单测完整覆盖。
 */
enum class Requirement {
    /** 安装即授予（normal 权限），任何 API 档位都恒为已授予。 */
    INSTALL_TIME,

    /**
     * UsageStats 特殊权限（需求 §2.1 的 `app_foreground` 前置）。
     *
     * **API 29 起是本项目唯一使用 AppOps 通道的权限**。API 26–28 上需求 §2.1 指定的
     * 授权入口（"使用情况访问"设置页）**不存在**，因此该档位下不存在可用的授权路径。
     */
    USAGE_STATS,

    /** 精确闹钟（需求 §2.1 的 `time` 前置；D3 决定 v1 走非精确，但状态必须如实上报）。 */
    EXACT_ALARM,

    /** 通知（需求 §7 的前台服务通知前置）。 */
    NOTIFICATIONS,

    /**
     * 电池优化白名单（需求 §7「电池白名单引导」前置，阶段 5 新增）。
     *
     * **不是运行时权限**：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 声明后即授予，
     * 但"是否真的被系统排除在电池优化之外"是**另一个**系统状态
     * （`PowerManager.isIgnoringBatteryOptimizations`），只能由用户去设置页决定。
     * 因此该档位判的是**白名单状态**，不是权限本身——与 `USAGE_STATS` 走 AppOps 同一形态。
     *
     * ## 为什么本阶段不直接调 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * 按已批准的裁定 ③：只跳**设置列表页**（`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`），
     * 不调用"直接请求忽略"那个 action（它的政策适用范围很窄）。
     * 权限本身仍要声明并如实上报，否则清单/目录一致性单测会红，用户也无从知道当前状态。
     */
    BATTERY_OPTIMIZATION,
}

/**
 * 本阶段关心的权限目录（阶段 3c.1 建 5 项 → **阶段 5 扩到 8 项**）。
 *
 * ## 为什么 3c.1 就把 3c.2 的权限建进来
 * 决策 6/7 要求本 provider 供「事件源启动前判断权限」与「阶段 6 逐项引导用户授权」使用，
 * 而 3c.2 的 `app_foreground` / `time` 正需要 [PACKAGE_USAGE_STATS] / [SCHEDULE_EXACT_ALARM]。
 * 提前建目录可避免 3c.2 再改一次目录 + 清单 + 单测。
 *
 * ## 阶段 5 为什么必须再加 3 项（不是可选的顺手扩展）
 * 本目录与清单是**严格相等**的（见下），而需求 §7 要求清单声明
 * `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` /
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。**只加清单不加目录**会让
 * `AndroidPermissionCatalogTest` 的「清单 ⊆ 目录」断言失败——那条断言正是防止
 * "清单里声明了、UI 却永远报不出来"的护栏。因此三项一并进目录：
 * - [FOREGROUND_SERVICE] / [FOREGROUND_SERVICE_SPECIAL_USE]：normal 权限，安装即授予，
 *   如实上报 `GRANTED (install-time)`；系统里没有"授予它"的界面（`settingsTargetFor` 返回 `null`）
 * - [REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]：判**白名单状态**（[Requirement.BATTERY_OPTIMIZATION]），
 *   真机初始为 `DENIED`，由用户去设置页放行（裁定 ③）
 *
 * ## 与清单的一致性（硬约束）
 * 本目录的 [permission] 集合必须与 `AndroidManifest.xml` 的 `<uses-permission>` 声明范围
 * **严格一致**，由 `AndroidPermissionCatalogTest` 断言，防止"目录与清单漂移"。
 * **改这里就必须同步改清单**，反之亦然。
 *
 * ## 预期副作用（不是 bug）
 * 真机上 [POST_NOTIFICATIONS] 与 [REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] 会如实上报为未授予
 * （前者由阶段 5 的 `MainActivity` 一次性申请，后者只能去设置页），阶段 6 的 UI 据此提示授权。
 *
 * @property permission 平台权限字符串（与清单逐字一致）
 * @property requirement 判定档位
 */
enum class AndroidPermission(
    val permission: String,
    val requirement: Requirement,
) {
    /** 需求 §2.1 `boot` 前置；normal 权限。 */
    RECEIVE_BOOT_COMPLETED(
        "android.permission.RECEIVE_BOOT_COMPLETED",
        Requirement.INSTALL_TIME,
    ),

    /** 需求 §2.1 `wifi_changed` 前置；normal 权限。 */
    ACCESS_NETWORK_STATE(
        "android.permission.ACCESS_NETWORK_STATE",
        Requirement.INSTALL_TIME,
    ),

    /** 需求 §7 前台服务通知前置；API 33+ 的运行时权限。 */
    POST_NOTIFICATIONS(
        "android.permission.POST_NOTIFICATIONS",
        Requirement.NOTIFICATIONS,
    ),

    /**
     * 需求 §2.1 `time` 前置。
     *
     * **声明即算申请**（`targetSdk ≥ 33` 时系统默认不授予）。D3「v1 默认非精确、暂不申请
     * 精确闹钟」指的是**不调用** `setExactAndAllowWhileIdle`，与本项**不矛盾**——
     * 声明只为让 provider 如实报告"未授予"。
     */
    SCHEDULE_EXACT_ALARM(
        "android.permission.SCHEDULE_EXACT_ALARM",
        Requirement.EXACT_ALARM,
    ),

    /** 需求 §2.1 `app_foreground` 前置；AppOps 特殊权限，无运行时弹窗。 */
    PACKAGE_USAGE_STATS(
        "android.permission.PACKAGE_USAGE_STATS",
        Requirement.USAGE_STATS,
    ),

    /**
     * 需求 §7 前台服务前置（阶段 5）。
     *
     * normal 权限、安装即授予 ⇒ 真机应恒报 `GRANTED (install-time)`。
     * 它**没有**运行时弹窗，也没有设置页入口（`settingsTargetFor` 返回 `null`）。
     */
    FOREGROUND_SERVICE(
        "android.permission.FOREGROUND_SERVICE",
        Requirement.INSTALL_TIME,
    ),

    /**
     * 需求 §7 `foregroundServiceType="specialUse"` 的前置（阶段 5，API 34+）。
     *
     * 本地 SDK 原文（`platforms/android-35/data/res/values/attrs_manifest.xml`）：
     * `<flag name="specialUse" value="0x40000000" />` 且
     * "Requires the app to hold the permission FOREGROUND_SERVICE_SPECIAL_USE"。
     *
     * 同为 normal 权限、安装即授予。**声明它是硬要求**：缺了它，
     * `startForeground(…, specialUse)` 在 API 34+ 上会抛 `SecurityException`。
     */
    FOREGROUND_SERVICE_SPECIAL_USE(
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        Requirement.INSTALL_TIME,
    ),

    /**
     * 需求 §7「电池白名单引导」前置（阶段 5）。
     *
     * 声明即授予（normal），但本项判的是**白名单状态**（见 [Requirement.BATTERY_OPTIMIZATION]）：
     * 未加入白名单时如实报 `DENIED`，阶段 6 的 UI 据此引导用户去设置页（裁定 ③：
     * 只跳设置列表页，不调 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
     */
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS(
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        Requirement.BATTERY_OPTIMIZATION,
    ),
    ;

    /**
     * 用户可见的**中文权限名**（阶段 6e 新增）。
     *
     * ## 为什么不能直接用 `permission` 字符串
     * `android.permission.PACKAGE_USAGE_STATS` 这个名字对用户**毫无意义** ——
     * 系统设置页里它叫「使用情况访问」。触发器 chip 的提示条必须说人话，
     * 否则用户拿着一个英文常量名去系统设置里找不到对应的开关。
     *
     * ## 为什么是**属性**而不是一处 `when`（决策 7 的延续）
     * 「目录里每一项都要有具体名称」这条要求，写成属性后可以由单测**穷举**断言
     * （`AndroidPermissionCatalogTest`：非空、无重复、不含 `android.permission` 前缀）；
     * 写成调用点的 `when` 就只能在每个调用点各测一次，漏一项没人发现。
     */
    val permissionLabel: String
        get() =
            when (this) {
                RECEIVE_BOOT_COMPLETED -> "开机自启"
                ACCESS_NETWORK_STATE -> "网络状态"
                POST_NOTIFICATIONS -> "通知权限"
                SCHEDULE_EXACT_ALARM -> "闹钟和提醒"
                PACKAGE_USAGE_STATS -> "使用情况访问"
                FOREGROUND_SERVICE -> "前台服务"
                FOREGROUND_SERVICE_SPECIAL_USE -> "前台服务（特殊用途）"
                REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> "电池优化白名单"
            }

    companion object {
        /** 按平台权限字符串反查。 */
        fun fromPermission(permission: String): AndroidPermission? = entries.firstOrNull { it.permission == permission }
    }
}

/**
 * 判定结论（阶段 3c.1）。
 *
 * ## 为什么是三态而不是布尔
 * 「该 API 档位下不存在此权限」与「有该权限但被用户拒绝」是**完全不同**的两件事：
 * 前者行为上不应提示用户去授权（**无处可授**），后者必须提示并给跳转。
 * `PROJECT_STATE.md` 决策 D 明令：**不得折叠成布尔**。
 */
enum class PermissionGrant {
    /** 已授予。 */
    GRANTED,

    /** 该档位下存在此权限，但未授予。 */
    DENIED,

    /** 该 API 档位下此权限不适用（系统未提供，或语义上不存在）。 */
    NOT_APPLICABLE,
}

/**
 * 单项权限的状态快照。
 *
 * @property permission 对应的目录项
 * @property grant 判定结论
 * @property reason 人类可读的原因，**必须具体**（决策 7：不得只给笼统结论）
 */
data class PermissionState(
    val permission: AndroidPermission,
    val grant: PermissionGrant,
    val reason: String,
)

/**
 * 设置页跳转目标（阶段 3c.1，**决策 9**）。
 *
 * ## 为什么是描述符而不是 `android.content.Intent`
 * `Intent` 在纯 JVM 单测下不可实例化（`Method <init> ... not mocked`），直接返回 `Intent`
 * 会让"哪一项该跳哪个设置页"这条关键判定**只能靠 `assumeTrue` 跳过**——等于不可测。
 * 描述符同时满足 `ui → domain` 分层：**domain 只描述"去哪"，阶段 6 的 UI 决定"怎么去"**。
 *
 * @property action 设置页的 action 字符串
 * @property packageUri 是否必须以 `package:<pkg>` 形式携带包名（部分设置页要求）
 * @property reason 为何需要跳转（供 UI 文案与真机判读）
 */
data class SettingsTarget(
    val action: String,
    val packageUri: Boolean,
    val reason: String,
)

/**
 * 权限状态提供者（阶段 3c.1）。
 *
 * ## 为什么不注册系统监听器（决策 6）
 * 权限变化**没有可靠的系统广播**，因此 [observe] 基于 `StateFlow`，只在 [refresh] 时重算。
 * 刷新时机：
 * - `start()` / `stop()` 时各刷一次（由 [EventSourceRegistry] 调用）
 * - 阶段 6 的 UI **从设置页返回时主动调 [refresh]**
 *
 * ## 实现契约
 * - [observe] 必须由 `StateFlow` 支撑：**相同快照不得重复发射**（否则 UI 会无谓重组）
 * - [refresh] 在状态未变时**不得**产生一次新发射（同款理由）
 * - 三个方法都不得抛异常：探测失败必须转成 `DENIED` + 具体 `reason`
 */
interface PermissionStatusProvider {
    /** 当前全部权限状态快照。 */
    fun current(): Map<AndroidPermission, PermissionState>

    /** 观察状态变化。热流，带当前值（`StateFlow` 语义）。 */
    fun observe(): Flow<Map<AndroidPermission, PermissionState>>

    /** 重新探测全部权限并按需更新 [observe]。 */
    fun refresh()

    /** 需要用户手动授权的项 → 设置页跳转目标；无需跳转返回 `null`。 */
    fun settingsTargetFor(permission: AndroidPermission): SettingsTarget?
}
