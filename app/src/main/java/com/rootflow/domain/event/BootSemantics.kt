package com.rootflow.domain.event

/**
 * "距上次开机后是否首次启动"的判定（阶段 3d，`PROJECT_STATE.md` 偏离项 **D9**）。
 *
 * ## 为什么需要它（D9 的背景，不复述决策过程）
 * 需求 §2.1 规定 `boot` 由静态 `BroadcastReceiver` 监听 `ACTION_BOOT_COMPLETED`。
 * 但本项目的测试设备（OnePlus 8 + ColorOS）**在分发阶段就拦截了本应用**——
 * 设备对 142 个 receiver 投递了该广播，rootflow 不在其中，且手动启动过也不投递。
 * 因此"App 启动时补 boot 语义"是本设备上**唯一可靠的 boot 路径**，不是兜底。
 *
 * ## 判定依据：`elapsedRealtime` 而不是墙钟
 * `SystemClock.elapsedRealtime()` **单调、自开机起算**，只在重启时**归零**。
 * 墙钟（`System.currentTimeMillis()`）可被用户或 NTP 任意改动，
 * 3b 设想的"存 wall-clock 开机时刻"跨重启不可比，无法判定。
 *
 * | 情况 | 判定 | 依据 |
 * |---|---|---|
 * | 无记录（含**全新安装**） | `true` | 首次启动应补 boot |
 * | `elapsed < bootId` | `true` | 重启后 `elapsed` 归零 ⇒ 标记来自**上一个开机周期** |
 * | `elapsed >= bootId` | `false` | 同一次开机周期内的后续启动 |
 *
 * `bootId` 为负数或 0 说明记录被损坏或从未正确写入（见 [BootMarkerStore]）：
 * 此时**必须**判 `true` 并如实告警——漏发一次 boot 会让"开机自启"这条需求静默失效，
 * 而多发一次的代价只是多跑一次脚本（由 `TriggerDispatcher` 的 500ms 防抖与
 * 本标记共同收敛）。
 *
 * ## 幂等边界（诚实说明）
 * 本判定只能回答"**距上次记录的启动之后，是否经历过一次重启**"，因此：
 * - 同一开机周期内的**第二次 App 启动**：`elapsed > bootId` → 不补发 ✅
 * - **广播先到、App 随后启动**：广播经 `BootMarkerHolder` 写入标记
 *   （此时 `elapsed < bootId`，因为重启后 `elapsed` 已归零）→ 启动路径判 `false` → **不补发** ✅
 *   —— 这正是 D9 要求的"两条路径幂等"
 * - 若某次启动时**标记已被清空**（用户清数据）：判 `true` → 补发一次，属可接受的多发
 */
object BootSemantics {
    /**
     * 判断本次启动是否需要补发 `boot`。
     *
     * @param elapsedRealtimeMillis 本次启动的 `SystemClock.elapsedRealtime()`
     * @param bootId 上次启动时写入的标记（`null` = 无记录：全新安装或被清除）
     * @return `true` 表示应补发一条 `SystemEvent.Boot`
     */
    fun isFirstStartSinceBoot(
        elapsedRealtimeMillis: Long,
        bootId: Long?,
    ): Boolean {
        if (bootId == null) return true
        if (bootId <= 0L) return true
        return elapsedRealtimeMillis < bootId
    }
}
