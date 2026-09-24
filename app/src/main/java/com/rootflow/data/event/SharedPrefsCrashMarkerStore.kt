package com.rootflow.data.event

import android.content.SharedPreferences
import android.util.Log
import com.rootflow.domain.event.BootloopDecision
import com.rootflow.domain.event.CrashMarkerStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CrashMarkerStore] 的 `SharedPreferences` 实现（阶段 4，需求 §5.3 第 4 条）。
 *
 * ## 为什么用 `commit()` 而不是 `apply()`
 * `apply()` 是**异步**落盘：进程若在它完成前被杀（**正是崩溃场景**），写入会丢，
 * 崩溃计数随之归零 ⇒ bootloop 兜底**在最需要它的场景下失效**。
 * `commit()` 同步写、返回成功与否，代价是几毫秒磁盘 IO——
 * 本类只在**启动**（1 次）与**健康置位**（1 次）与**崩溃累计**（极少）时写，
 * 不在热路径上，该代价可以接受。
 *
 * ## 为什么不用 DataStore
 * 与 D9 的 `BootMarkerStore` 同款纪律：`DataStore` 依赖在基线里但**全项目零使用**，
 * 阶段 4/5 不凭空引入数据层新组件；且 `DataStore` 的写是挂起的，在"崩溃前必须落盘"
 * 这个场景上反而不如 `commit()` 直接。
 *
 * ## 读失败/损坏的处理（不静默）
 * 键缺失视为"无记录"（正常路径）；类型不符或读取抛异常时**记日志并当作无记录**——
 * 与 `FileBootMarkerStore` 同款取舍：**宁可少计一次崩溃，也不让 App 起不来**。
 */
@Singleton
class SharedPrefsCrashMarkerStore
    @Inject
    constructor(
        private val prefs: SharedPreferences,
    ) : CrashMarkerStore {
        override fun readStartupMarker(): BootloopDecision.StartupMarker? {
            val bootId = readLong(KEY_STARTUP_BOOT_ID) ?: return null
            if (bootId <= 0L) return null
            return BootloopDecision.StartupMarker(
                bootId = bootId,
                healthy = readBoolean(KEY_STARTUP_HEALTHY) ?: false,
            )
        }

        override fun markStartupInProgress(bootId: Long) {
            write { editor ->
                editor
                    .putLong(KEY_STARTUP_BOOT_ID, bootId)
                    // 尚未证明健康：**必须在同一次提交里**与 bootId 一起写，
                    // 否则"写了 bootId 但没写 healthy"的中间态会把一次正常启动误判成崩溃
                    .putBoolean(KEY_STARTUP_HEALTHY, false)
            }
        }

        override fun markHealthy(bootId: Long) {
            write { editor ->
                editor
                    .putLong(KEY_STARTUP_BOOT_ID, bootId)
                    .putBoolean(KEY_STARTUP_HEALTHY, true)
            }
        }

        override fun readCrashCount(): Int = readInt(KEY_CRASH_COUNT) ?: 0

        override fun writeCrashCount(count: Int) {
            write { editor -> editor.putInt(KEY_CRASH_COUNT, count) }
        }

        /**
         * 只删计数键（阶段 5 修复）。
         *
         * **刻意不动 `KEY_STARTUP_BOOT_ID` / `KEY_STARTUP_HEALTHY`**：
         * 那两个键表达的是"本次启动是否已证明健康"，与崩溃计数无关；
         * 一并删掉会让"熔断后立刻崩溃"这一次不再被下次启动计入（见端口 KDoc）。
         */
        override fun resetCrashCount() {
            write { editor -> editor.remove(KEY_CRASH_COUNT) }
        }

        override fun clear() {
            write { editor ->
                editor
                    .remove(KEY_STARTUP_BOOT_ID)
                    .remove(KEY_STARTUP_HEALTHY)
                    .remove(KEY_CRASH_COUNT)
            }
        }

        // ------------------------------------------------------------------ 内部

        private fun readLong(key: String): Long? =
            try {
                if (prefs.contains(key)) prefs.getLong(key, 0L) else null
            } catch (error: ClassCastException) {
                Log.w(TAG, "crash marker '$key' has unexpected type: ${error.message}")
                null
            }

        private fun readBoolean(key: String): Boolean? =
            try {
                if (prefs.contains(key)) prefs.getBoolean(key, false) else null
            } catch (error: ClassCastException) {
                Log.w(TAG, "crash marker '$key' has unexpected type: ${error.message}")
                null
            }

        private fun readInt(key: String): Int? =
            try {
                if (prefs.contains(key)) prefs.getInt(key, 0) else null
            } catch (error: ClassCastException) {
                Log.w(TAG, "crash marker '$key' has unexpected type: ${error.message}")
                null
            }

        /** 同步提交；失败只告警（调用方在 `onCreate` 关键路径上，不能抛出）。 */
        private fun write(block: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
            try {
                val committed = block(prefs.edit()).commit()
                if (!committed) {
                    Log.w(TAG, "crash marker commit returned false (storage unavailable?)")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "crash marker write failed: ${error.message ?: error::class.java.name}")
            }
        }

        companion object {
            const val TAG: String = "RootFlow"

            /** `SharedPreferences` 文件名（应用私有目录，**不走 root 通道**）。 */
            const val PREFS_NAME: String = "rootflow_crash_marker"

            const val KEY_STARTUP_BOOT_ID: String = "startup_boot_id"
            const val KEY_STARTUP_HEALTHY: String = "startup_healthy"
            const val KEY_CRASH_COUNT: String = "crash_count"
        }
    }
