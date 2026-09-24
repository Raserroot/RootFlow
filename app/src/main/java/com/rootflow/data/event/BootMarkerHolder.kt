package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.BootMarkerStore
import com.rootflow.domain.event.BootSemantics

/**
 * boot 补发标记的**静态持有者**（阶段 3d）。
 *
 * ## 为什么需要它（与 `EventBusHolder` 完全同款的理由）
 * `BootEventReceiver` 由系统实例化，**无法构造注入**（本项目不引入 Robolectric/JUnit4，
 * 因此也不能靠 Hilt 的测试 runner 组合）。而 D9 明确要求
 * **"广播到达时也应写标记"**——否则"广播先到、App 随后启动"会让启动路径**再补发一次** boot。
 *
 * ## 判定语义（关键，勿按直觉改：`elapsedRealtime` 会在重启时**归零**）
 * | 标记相对当前 `elapsed` | 含义 | 处置 |
 * |---|---|---|
 * | `bootId > elapsed` | 标记来自**上一个开机周期**（重启后 `elapsed` 变小） | 广播认领本周期 → **写** |
 * | `bootId ≤ elapsed` | 本周期已被 App 启动路径认领过 | **不写**（覆写会破坏判定） |
 * | `bootId == null` | 全新安装，从未写过标记 | 视为可认领 → **写** |
 *
 * 第 2 行是防"晚到的广播把标记推后"：启动路径每次启动都无条件把标记覆写为当时 `elapsed`，
 * 若广播也覆写，标记会一直 = 最近一次广播时刻，"同周期第二次启动不补发"仍然成立，
 * 但"广播先到 → 写标记 → 启动路径据此不补发"这条 D9 的幂等路径就会失效。
 *
 * ## 未安装时容错
 * 存储为空（应用尚未 `configure`）时只记一条 info，**不抛异常**——
 * 接收器崩溃会被系统记入日志并可能被限流。
 */
object BootMarkerHolder {
    @Volatile
    private var store: BootMarkerStore? = null

    /** 安装标记存储（`RootFlowApp.onCreate` 调用一次）。 */
    fun configure(store: BootMarkerStore) {
        this.store = store
    }

    /** 清除（单测隔离用）。 */
    fun clear() {
        store = null
    }

    /**
     * 广播路径的标记维护（见类 KDoc 的语义表）。
     *
     * @param elapsedRealtimeMillis 当前 `SystemClock.elapsedRealtime()`
     * @return `true` 表示本次广播认领了该开机周期（已写入标记）
     */
    fun markIfBootCycle(elapsedRealtimeMillis: Long): Boolean {
        val current = store
        if (current == null) {
            Log.i(TAG, "BOOT_MARKER_SKIPPED holder not configured (app not started yet)")
            return false
        }
        if (!BootSemantics.isFirstStartSinceBoot(elapsedRealtimeMillis, current.read())) {
            // 本周期已被 App 启动路径认领（标记 ≤ 当前 elapsed）：覆写会把标记推后，
            // 破坏"广播先到 → 启动路径不补发"的幂等路径。
            Log.i(TAG, "BOOT_MARKER_SKIPPED already claimed this boot cycle")
            return false
        }
        current.write(elapsedRealtimeMillis)
        Log.i(TAG, "BOOT_MARKER_WRITTEN_BY_BROADCAST elapsed=$elapsedRealtimeMillis")
        return true
    }

    private const val TAG = "RootFlow"
}
