package com.rootflow.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 用户设置的读写端口（阶段 6）。
 *
 * ## 为什么是 `domain` 端口而不是让 UI 直接用 DataStore
 * `AGENTS.md` 的分层约束是 `ui → domain → data`。UI 只认识 [RootFlowSettings] 这个纯数据类；
 * DataStore 的类型（`Preferences`、`DataStore<Preferences>`）全部留在 `data/settings/`，
 * 与 `LogPipeline` 不认识 `runtime.LogLine` 是同一手法。
 *
 * ## 为什么是 `StateFlow` 而不是 `Flow`
 * 主题必须在**第一帧之前**就有值（否则会闪一次错误的配色），因此实现侧必须持有当前值；
 * `StateFlow` 让"取当前值"与"订阅变化"用同一个对象表达，UI 不必先 `first()` 再 `collect`。
 *
 * ## 实现契约
 * - [settings] 必须**立即**带当前值（`StateFlow` 语义），且**相同值不得重复发射**
 * - 读失败（文件损坏 / 无权限）**不得抛异常**：回落到 [RootFlowSettings] 的默认值并告警
 * - [update] 的异常**不得外溢**到 UI（设置写失败比"设置页崩了"轻得多）
 */
interface SettingsRepository {
    /** 当前设置；订阅即得当前值。 */
    val settings: StateFlow<RootFlowSettings>

    /**
     * 一次性读当前设置。
     *
     * ## ★ 唯一的"阻塞主线程"用武之地（有意为之，勿改成 suspend 后直接调用）
     * `MainActivity.onCreate` 是 `Application.onCreate` 之后、**Compose 第一帧之前**唯一的
     * 同步窗口。主题若不在这一刻确定，用户会看到一次"先浅色再跳深色"的闪烁。
     * 因此这个方法是 `suspend` 的，**由调用方决定**是否用 `runBlocking` 包一层
     * （见 `MainActivity`；那里有超时守卫）。
     * 其余所有读写路径（设置页、开关）都走 [settings] / [update]，**不阻塞**。
     *
     * @return 当前设置；读失败时返回默认值（**不抛**）
     */
    suspend fun current(): RootFlowSettings

    /** 读某个主题模式；用于浅色/深色/跟随系统的切换。 */
    suspend fun currentThemeMode(): ThemeMode = current().themeMode

    /** 写主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode)

    /** 写毛玻璃开关；`null` = 恢复"跟随设备判定"。 */
    suspend fun setBlurEnabled(enabled: Boolean?)

    /** 写动态取色开关。 */
    suspend fun setDynamicColor(enabled: Boolean)

    /**
     * 写运行历史 / 日志的保留天数（阶段 6d）。
     *
     * ## 契约：非法值按 [LogRetention.DEFAULT_DAYS] 落盘，不抛
     * 见 [LogRetention.sanitize]：设置里存的是磁盘上的历史数据，旧版本可能写过已下线的档位。
     * 写路径**收敛一次**，读路径（`toSettings`）也收敛一次 —— 两道都做，
     * 是为了让"键被外部改坏"这种手改场景同样安全。
     */
    suspend fun setLogRetentionDays(days: Int)

    // ★ 阶段 11e：`setLiquidGlassEnabled` **已移除**（用户指令）。
    //   液态玻璃不再是底栏的材质，写入路径自然也不该存在。
    //   原先的契约说明（"不做三态：设备能力由 GlassPolicy 在渲染时判定"）随特性一起失效。

    /**
     * 订阅当前设置（[settings] 的便捷别名，供 ViewModel 直接使用）。
     *
     * 保留此方法是为了让"订阅"与"取当前值"在调用点读起来是两件事，
     * 与 `PermissionStatusProvider.current()/observe()` 的既有形态一致。
     */
    fun observe(): Flow<RootFlowSettings> = settings
}
