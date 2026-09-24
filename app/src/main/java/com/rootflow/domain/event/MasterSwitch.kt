package com.rootflow.domain.event

import com.rootflow.domain.model.AppSwitch
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.StateFlow

/**
 * **总开关**（总闸）端口 —— 总开关重构 P3 的核心契约。
 *
 * ## 它在模型里的位置（方案 §3.2 的「两级开关」）
 * ```
 * app_switch.master_enabled   宿主是否在提供服务        ← 本端口管它
 * scripts.enabled             这个脚本要不要参与        ← 脚本行自己的开关
 * 有效运行条件                 masterEnabled && scripts.enabled（**派生，不落库**）
 * ```
 *
 * ## ★ 四条不变量与它们的落地位置（改本文件前必读）
 * | # | 不变量 | 落在哪里 |
 * |---|---|---|
 * | 1 | `enabled = false` ⇒ **任何**脚本都不得启动（含 `runOnSafeMode` 的） | `TriggeredScriptRunner.start` 的**最前**一道闸门（读 [enabled]，零 IO） |
 * | 2 | 拨动总闸**不修改** `scripts.enabled` 与 `script_events` | [setEnabled] 只写 `app_switch` 一行；由单测钉住另两张表一行未动 |
 * | 3 | 关闭总闸**必须终止**已在跑的常驻脚本 | `DaemonSupervisor.reconcile` 的 `desired` 集合（判据短路成空集 ⇒ 逐条取消监管 ⇒ 进程被终止） |
 * | 4 | 打开总闸 ⇒ 已启用脚本**立即**启动（不必等下次服务启动） | 同上：总闸是 `reconcile` 的一个**输入流**，变化即对账 |
 *
 * ## 为什么是 `StateFlow`（而不是每次 `suspend` 查库）
 * 不变量 1 落在**热路径**上（每一次脚本启动都要判一次）。判据若是"查 Room"，
 * 就等于给每次启动加一次磁盘 IO —— 而这条闸门是**最根本**的那道。
 * 内存快照 + [restoreFromStore] 一次性恢复，与 `CircuitBreaker.safeMode` 同款
 * （那个先例已在生产上跑了整个阶段 4~6）。
 *
 * ## 代价（如实登记）
 * 快照可能**短暂**落后于库（例如别处直接改了库）—— 本应用是单进程，
 * 唯一的写入路径是 [setEnabled]，它在**写库成功之后**才更新快照，因此
 * "快照说开着、库说关着"这种方向（危险方向）不会出现；反方向最多是
 * "刚拨开但快照尚未刷新"，时间尺度是一次 `upsert`。
 */
interface MasterSwitch {
    /**
     * 总闸的**内存快照**（热路径零 IO 读它）。初值恒为 `false` = 安全默认。
     *
     * 启动期必须由 [restoreFromStore] 从库恢复一次 —— 否则进程重启会让
     * "总闸关着"这个用户意图**静默失效**（与熔断的 `restoreStateFromDisk` 同一个坑型）。
     */
    val enabled: StateFlow<Boolean>

    /** [enabled] 的同步便捷读（与 `CircuitBreaker.isSafeModeActive` 同款）。 */
    val isEnabled: Boolean

    /**
     * 启动期**一次性**从库恢复总闸状态。
     *
     * 必须在**任何脚本可以跑之前**调用：进程重启后 [enabled] 是 `false`，
     * 因此"不恢复"的后果不是"总闸失效"而是**方向相反**的两种错：
     * 库说开着（用户明明开过）却什么都不跑，或库说关着却沿用了一个错误的开状态。
     *
     * 表中没有行时**按 [AppSwitch.SafeDefault] 处理并补写一行**（全新安装的合法状态，
     * 数据库回调可能尚未执行）。
     *
     * @return 恢复后的总闸状态
     */
    suspend fun restoreFromStore(): Boolean

    /**
     * 用户拨动总闸（P4 的 UI 调它）。
     *
     * ## 写序（不可调换）
     * **先写库、成功之后才更新 [enabled] 快照**。反过来会让一次失败的写库
     * 在内存里留下"已开启"—— 而 UI 的状态来自快照 ⇒ 用户看到"开了"、
     * 库里却是关的，且**没有任何日志说明为什么重启后又关了**。
     *
     * ## 幂等
     * 目标值与当前值相同时**不写库**（`updatedAt` 不被刷新、时间戳不被改写），
     * 只留一行 `MASTER_SWITCH_SET_NOOP` 日志并返回当前行。
     * 理由：`enabledAt` / `disabledAt` 是**用户动作的时间档案**，
     * 被一次次无意义的重复点击刷新会让它失去判读价值。
     *
     * @return 成功时返回**落库后**的那一行
     */
    suspend fun setEnabled(enabled: Boolean): WriteResult<AppSwitch>
}
