package com.rootflow.data.event.android

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionDecisions
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionPrimitives
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.event.SettingsTarget
import com.rootflow.domain.event.SettingsTargets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PermissionStatusProvider] 的 `Context` 适配（阶段 3c.1，**决策 6 / 决策 9 / 决策 10**）。
 *
 * ## 按决策 13：本类不进单测
 * 它只做 Android API 直调。**全部判定逻辑已在 `PermissionDecisions` 里**（纯函数，被
 * `PermissionDecisionsTest` 穷举四档 × 5 权限）。本类里唯一残留的版本分支是
 * [AndroidPermissionPrimitives.isUsageStatsAllowed] 的两个 AppOps 方法名选择
 * （`unsafeCheckOpNoThrow` 自 API 29 起存在，无法用别的方式表达）。
 *
 * ## 为什么不注册系统监听器（决策 6）
 * 权限变化**没有可靠的系统广播**。因此 [observe] 由 `StateFlow` 支撑，只在 [refresh] 时重算；
 * 相同快照不会重复发射（`MutableStateFlow` 按 `equals` 去重），从而不会引发阶段 6 的无谓重组。
 *
 * ## 刷新时机（决策 6）
 * `EventSourceRegistry.start()` / `stop()` 各一次；阶段 6 的 UI 从设置页返回时主动调 [refresh]。
 * **本类不在 `init` 里探测**——那会让 DI 构造阶段产生副作用（与 `EventModule` 只构造不启动同款纪律）。
 *
 * ## `refresh()` 的原子性
 * 探测与发布由 [lock] 串起来：若无锁，两个线程并发 `refresh()` 可能以"较旧的结果"覆盖
 * "较新的结果"，UI 便会显示过期状态。
 *
 * @param context 应用上下文
 * @param sdkInt 当前 API 档位（生产恒为 `Build.VERSION.SDK_INT`）
 */
@Singleton
class AndroidPermissionStatusProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PermissionStatusProvider {
        /**
         * API 档位的**测试缝**（`internal`，仅单测用）。
         *
         * 生产恒用 `Build.VERSION.SDK_INT`。**不把 `sdkInt` 放进 `@Inject` 构造**：
         * Kotlin 默认参数对 Dagger 不可见，它会尝试注入 `java.lang.Integer` 而报
         * `MissingBinding`（已实测）。
         */
        internal constructor(
            context: Context,
            sdkInt: Int,
        ) : this(context = context) {
            this.sdkInt = sdkInt
        }

        private var sdkInt: Int = Build.VERSION.SDK_INT
        private val lock = Any()

        private val state =
            MutableStateFlow<Map<AndroidPermission, PermissionState>>(emptyMap())

        /** 复用一份原语实现，避免每次 [refresh] 重新分配。 */
        private val primitives: PermissionPrimitives by lazy {
            AndroidPermissionPrimitives(context = context, sdkInt = sdkInt)
        }

        override fun current(): Map<AndroidPermission, PermissionState> = state.value

        override fun observe(): Flow<Map<AndroidPermission, PermissionState>> = state.asStateFlow()

        override fun refresh() {
            synchronized(lock) {
                // 逐项探测；任一项抛异常都不得让整次刷新失败（否则其余权限状态会一起丢）
                val evaluated: Map<AndroidPermission, PermissionState> =
                    AndroidPermission.entries.associateWith { permission ->
                        runCatching {
                            PermissionDecisions.evaluate(
                                permission = permission,
                                sdkInt = sdkInt,
                                primitives = primitives,
                            )
                        }.getOrElse { error ->
                            PermissionState(
                                permission = permission,
                                grant = PermissionGrant.DENIED,
                                reason = "probe failed: " + (error.message ?: error::class.java.name),
                            )
                        }
                    }
                // MutableStateFlow 按 equals 去重：状态未变时不会产生新发射
                state.value = evaluated
            }
        }

        override fun settingsTargetFor(permission: AndroidPermission): SettingsTarget? =
            SettingsTargets.resolve(permission)
    }

/**
 * [PermissionPrimitives] 的真实实现（唯一触碰 `Context` 的地方）。
 *
 * ## 为什么把 AppOps 的 mode 与常量 `MODE_ALLOWED_MARKER` 比较
 * `AppOpsManager.MODE_ALLOWED` 的取值就是 `0`。本类在编译期当然能引用该框架常量，
 * 但为让"这个 0 是什么"在代码里自解释、且不引入只在编译期存在的隐式依赖，
 * 这里显式声明常量并在 KDoc 注明来源。**该常量与框架常量的一致性由真机覆盖**
 * （本类按决策 13 不进单测）。
 */
internal class AndroidPermissionPrimitives(
    private val context: Context,
    private val sdkInt: Int,
) : PermissionPrimitives {
    override fun isRuntimePermissionGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun isUsageStatsAllowed(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode =
            if (sdkInt >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        return mode == MODE_ALLOWED_MARKER
    }

    override fun canScheduleExactAlarms(): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * 电池优化白名单状态（阶段 5，需求 §7）。
     *
     * **只问本应用**：`isIgnoringBatteryOptimizations` 接受任意包名，但本项目的语义只有
     * "本应用有没有被放行"。**不缓存**：用户随时可能去设置页改，而本方法的唯一调用点是
     * `refresh()`（决策 6 的刷新时机）。
     *
     * 取不到 `PowerManager` 时返回 `false`（= 未放行，保守方向：宁可提示用户去放行，
     * 也不谎报"已优化"）。
     */
    override fun isIgnoringBatteryOptimizations(): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private companion object {
        /** `AppOpsManager.MODE_ALLOWED` 的取值（见类 KDoc）。 */
        const val MODE_ALLOWED_MARKER: Int = 0
    }
}
