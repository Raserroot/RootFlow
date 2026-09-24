package com.rootflow.domain.event

/**
 * Bootloop 兜底的判定（需求 §5.3「连续 3 次 60s 内崩溃自动写 flag」）。
 *
 * ## 为什么单独抽成纯函数
 * 判定依赖"上一次启动是否留下**健康标记**"，而"健康"的写入时机在 Android 上很微妙
 * （见 [BootloopDecision] 的 KDoc）。把 `(bootId, marker, elapsed) → 结论` 抽纯之后，
 * 边界（重启归零 / 同周期累计 / 到达阈值 / 已健康）都能在纯 JVM 下钉死。
 *
 * ## ★ 与需求措辞的偏离（**已登记，勿当成 bug 修**）
 * 需求说"**60s 内崩溃**"——那要求能观测到"进程正常退出"。**Android 上做不到**：
 * - `Application.onTerminate()` 真机**永不调用**（它只在模拟器环境被调用）
 * - 进程被 kill / 被系统回收 / 被用户划掉时，**没有任何回调**
 *
 * 因此本实现改用**反向标记**：不在退出时清标记，而在"**活过阈值时间**"时置标记。
 * | 上次留下的标记 | 含义 | 处置 |
 * |---|---|---|
 * | 无标记（首启/清数据） | 无从判断 | 只记一次启动，**不计崩溃** |
 * | `healthy = true` | 上次活到了健康阈值 | 正常结束 ⇒ **清零**计数 |
 * | `healthy = false` 且同周期 | 上次没活到阈值就被结束 | **崩溃** ⇒ 计数 +1 |
 * | 周期不同（`elapsed` 回退） | 设备重启过 | 计数归零重来 |
 *
 * **语义等价性**：真实 bootloop 是"启动即崩"，它**必然**活不到健康阈值 ⇒ 一定被计数；
 * 而"用户正常开关 App"只要每次活过阈值就清零 ⇒ **不会误熔断**。
 * 代价：崩溃发生在阈值之后（例如启动 90s 后崩）**不会**被计入——但那不构成 bootloop。
 *
 * ## ★ 已知限制（**用户可见，勿当成 bug 修**）
 *
 * **快速连续启动 3 次可能被误判为崩溃。**
 *
 * 成因：本方案只能观测"上次有没有活到健康阈值"，**无法区分"崩溃"与"快速正常退出"**——
 * 一个用户若在 60s 内连续正常打开又关掉 App 三次，每次都没活到阈值，
 * 第三次启动就会被判为 bootloop 并进入安全模式。
 *
 * 这是 Android 侧**无法回避**的代价：
 * - `Application.onTerminate()` 真机永不调用（仅模拟器环境）
 * - `Activity.onDestroy()` 在进程被 kill / 系统回收 / 用户划掉时**不保证调用**
 * ⇒ 平台**根本没有**"进程正常退出"这一事件可供区分。
 *
 * **阈值 3 是权衡结果**：调大（如 5）会降低误判，但真实 bootloop 要多崩两次才被拦住；
 * 调小（2）会让"手滑连开两次"就熔断。3 次是"足以确认异常、又不至于误伤"的折中。
 *
 * **缓解**：
 * 1. 误判后**可人工一键恢复**（App 内恢复，或 root 侧 `rm -f` flag），不丢数据
 * 2. 阶段 6 的 UI 会在安全模式 banner 上**明确说明原因与恢复入口**，
 *    让用户看懂"为什么突然不跑了"
 * 3. 健康阈值（60s）内**只要 App 保持前台存活**即视为健康，
 *    因此"打开后停留一会儿再关"不会被计数
 *
 * 已登记在 `PROJECT_STATE.md` 的「已知限制」。
 */
object BootloopDecision {
    /** 上一次启动留下的标记。 */
    data class StartupMarker(
        /** 上次启动时的 `elapsedRealtime`（判断是否跨开机周期）。 */
        val bootId: Long,
        /** 上次启动是否活到了健康阈值。 */
        val healthy: Boolean,
    )

    /** 本次启动应如何处置崩溃计数。 */
    sealed interface Verdict {
        /** 保持计数不变（首启、或上次正常结束）。 */
        data object Unchanged : Verdict

        /** 上次异常结束：计数 +1。 */
        data object IncrementCrashCount : Verdict

        /** 跨开机周期：计数归零（本次为 0）。 */
        data object ResetForNewBoot : Verdict
    }

    /**
     * 判定本次启动的崩溃计数处置。
     *
     * @param elapsedRealtimeMillis 本次启动的单调时钟读数
     * @param marker 上次启动留下的标记；`null` = 无记录
     * @return 见 [Verdict]
     */
    fun evaluate(
        elapsedRealtimeMillis: Long,
        marker: StartupMarker?,
    ): Verdict {
        if (marker == null) return Verdict.Unchanged
        if (marker.bootId <= 0L) return Verdict.Unchanged
        // `elapsedRealtime` 在重启时归零 ⇒ 标记大于当前读数表示标记来自上一个开机周期
        if (elapsedRealtimeMillis < marker.bootId) return Verdict.ResetForNewBoot
        return if (marker.healthy) Verdict.Unchanged else Verdict.IncrementCrashCount
    }

    /**
     * 累计后的崩溃次数是否达到熔断阈值。
     *
     * @param crashCount 本次启动处置后的累计崩溃次数
     */
    fun shouldTrip(crashCount: Int): Boolean = crashCount >= DEFAULT_CRASH_THRESHOLD

    /** 需求 §5.3：连续 3 次崩溃。 */
    const val DEFAULT_CRASH_THRESHOLD: Int = 3
}
