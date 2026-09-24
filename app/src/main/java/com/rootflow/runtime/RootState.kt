package com.rootflow.runtime

/**
 * Root 通道的可用状态。
 *
 * 状态机：
 * ```
 * Unknown ──ensureReady()──> Granted | Denied | Error
 * ```
 * 初始为 [Unknown]；[RootShellManager] 每次调用 `ensureReady()` 都会重新求值，
 * 因此状态可从 [Denied] / [Error] 回到 [Granted]（例如用户在 Root 管理器中
 * 补授权后）。
 */
sealed class RootState {
    /** 尚未探测过 Root 状态。 */
    data object Unknown : RootState()

    /** Root 可用（已获得 su 通道）。 */
    data object Granted : RootState()

    /** Root 不可用：su 未授权、被拒绝，或设备本身无 Root。 */
    data class Denied(
        val reason: String,
    ) : RootState()

    /** 探测过程本身失败（libsu 初始化异常等），与"被拒绝"区分开。 */
    data class Error(
        val message: String,
    ) : RootState()
}
