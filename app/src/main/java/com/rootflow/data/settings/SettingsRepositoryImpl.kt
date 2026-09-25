package com.rootflow.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.domain.settings.SettingsRepository
import com.rootflow.domain.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] 的 DataStore 实现（阶段 6）。
 *
 * ## 为什么构造注入 `DataStore<Preferences>` 而不是 `Context`
 * 这样单测可以直接喂一个指向临时目录的 DataStore（见 [createSettingsDataStore] 的 KDoc），
 * 而生产侧的唯一实例由 `SettingsModule` 提供。类本身**不认识 Android 框架**
 * （只用到纯 JVM 可用的 `android.util.Log`）。
 *
 * ## 读失败的处理（**不静默、也不崩**）
 * DataStore 在读损坏文件时抛 `IOException`。设置损坏**绝不能**让 App 起不来
 * （用户会连设置页都进不去，也就无法自救），因此 [catch] 后回落到
 * `emptyPreferences()` ⇒ 全默认值，并**记一行 warning**：
 * 静默回落会让"设置总是丢失"变成无法排查的幽灵问题（`AGENT_PROTOCOL.md §8` 的纪律）。
 *
 * ## 写失败的处理
 * DataStore 的 `edit` 失败会抛异常。设置写失败比"设置页崩了"轻得多，因此同样只告警。
 * 注意是**告警而非吞掉**：日志里必须留下痕迹。
 *
 * ## 内存镜像（[_settings]）
 * 需求要求主题在**第一帧之前**可用，而 `DataStore.data` 的首次发射要等一次磁盘读。
 * [init] 里的常驻收集把每次发射（含初始值）写进 [_settings]，
 * 于是 [settings] 在任何时刻都有值——包括首次读取尚未完成的窗口期
 * （此时是 [RootFlowSettings] 的默认值，与"还没读过"等价）。
 */
@Singleton
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        private val _settings = MutableStateFlow(RootFlowSettings())

        override val settings: StateFlow<RootFlowSettings> = _settings.asStateFlow()

        init {
            // 常驻收集：DataStore.data 是冷流，必须有人收着才能持续把磁盘真相同步进内存镜像。
            // 不用 viewModelScope / 生命周期作用域：设置的真相应在**全应用**范围内一致，
            // 而不是"当前这个界面活着时才有"。作业随进程结束而结束，无需外部取消。
            mirrorScope.launch {
                dataStore.data
                    .catch { error -> emit(readFailureFallback(error)) }
                    .onEach { preferences -> _settings.value = preferences.toSettings() }
                    .collect { /* 副作用已在 onEach 完成 */ }
            }
        }

        override suspend fun current(): RootFlowSettings =
            runCatching {
                dataStore.data
                    .catch { error -> emit(readFailureFallback(error)) }
                    .first()
                    .toSettings()
            }.getOrElse { error ->
                Log.w(TAG, "settings current() failed, using defaults: ${describe(error)}")
                RootFlowSettings()
            }

        override suspend fun currentThemeMode(): ThemeMode = current().themeMode

        override suspend fun setThemeMode(mode: ThemeMode) {
            edit { preferences -> preferences[SettingsKeys.THEME_MODE] = mode.key }
        }

        override suspend fun setBlurEnabled(enabled: Boolean?) {
            edit { preferences ->
                if (enabled == null) {
                    // null = 恢复"跟随设备判定"：必须**删除键**而不是写 false，
                    // 否则三态就退化成了布尔（见 RootFlowSettings 的 KDoc）。
                    preferences.remove(SettingsKeys.BLUR_ENABLED)
                } else {
                    preferences[SettingsKeys.BLUR_ENABLED] = enabled
                }
            }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            edit { preferences -> preferences[SettingsKeys.DYNAMIC_COLOR] = enabled }
        }

        override suspend fun setLogRetentionDays(days: Int) {
            // 收敛一次再落盘：非法档位不该被写进磁盘（见 SettingsRepository 的契约说明）
            edit { preferences -> preferences[SettingsKeys.LOG_RETENTION_DAYS] = LogRetention.sanitize(days) }
        }

        // ★ 阶段 11e：`setLiquidGlassEnabled` 与 `SettingsKeys.LIQUID_GLASS_ENABLED` 的读取
        //   均已移除（用户指令）。磁盘上可能仍留有旧键 —— **不做迁移、也不清理**：
        //   DataStore 里的孤儿键无副作用，而"为删一个键写一次迁移"的代价不值得
        //   （与 `PROJECT_STATE.md` 里"崩了就崩了、不留半成品迁移"的纪律一致）。

        // ------------------------------------------------------------------ 内部

        /** 读失败时的兜底值（同时留一行日志，见类 KDoc）。 */
        private fun readFailureFallback(error: Throwable): Preferences {
            Log.w(TAG, "settings read failed, falling back to defaults: ${describe(error)}")
            return emptyPreferences()
        }

        /** 统一的写入路径：异常只告警（见类 KDoc）。 */
        private suspend fun edit(block: (MutablePreferences) -> Unit) {
            runCatching { dataStore.edit(block) }
                .onFailure { error -> Log.w(TAG, "settings write failed: ${describe(error)}") }
        }

        private fun describe(error: Throwable): String =
            error::class.java.simpleName + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /**
             * 内存镜像的收集作用域。
             *
             * 用 `Dispatchers.IO` 而不是注入限定符作用域：这是**进程级、必然比进程短命**的作业
             * （随进程一起消失），没有"谁来取消"的问题；借 `LogModule` 的日志管道作用域反而会让
             * "设置的真相绑在日志的寿命上"这种因果关系无法解释。
             */
            val mirrorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

/**
 * 键的唯一真相源。
 *
 * 映射函数 [toSettings] 与写入路径引用**同一组** [Preferences.Key] 实例：
 * 两份字面量（哪怕字符串完全一样）是"改了名字却只改一处"这类静默错配的温床。
 */
internal object SettingsKeys {
    val THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    val BLUR_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("blur_enabled")
    val DYNAMIC_COLOR: Preferences.Key<Boolean> = booleanPreferencesKey("dynamic_color")

    /** 运行历史保留天数（阶段 6d；档位与收敛规则见 `LogRetention`）。 */
    val LOG_RETENTION_DAYS: Preferences.Key<Int> = intPreferencesKey("log_retention_days")

    // ★ 阶段 11e：`LIQUID_GLASS_ENABLED` 键**已移除**（用户指令）。
    //   磁盘上若留有旧键，**不迁移、不清理** —— DataStore 里的孤儿键无副作用，
    //   而"为删一个键写一次迁移"的代价不值得。
}

/**
 * `Preferences` → [RootFlowSettings]（**唯一映射点**）。
 *
 * 键缺失 / 未知字符串 → 回落默认值，不抛异常：
 * 这是"下一次版本改了键名"时的唯一安全带。
 */
internal fun Preferences.toSettings(): RootFlowSettings =
    RootFlowSettings(
        themeMode = ThemeMode.fromKey(this[SettingsKeys.THEME_MODE]),
        blurEnabled = this[SettingsKeys.BLUR_ENABLED],
        dynamicColor = this[SettingsKeys.DYNAMIC_COLOR] ?: true,
        // 读路径也收敛：键被外部改坏 / 旧版本写过已下线档位时，这里兜住（见 LogRetention.sanitize）
        logRetentionDays = LogRetention.sanitize(this[SettingsKeys.LOG_RETENTION_DAYS]),
    )
