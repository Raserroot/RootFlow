package com.rootflow.domain.service

/**
 * 保活看门狗（阶段 12c，需求 §7「保活」的落地件）。
 *
 * ## 它做什么、不做什么
 * | 做 | 不做 |
 * |---|---|
 * | 每 [KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS] 醒一次，检查"前台服务还在不在" | 双进程互拉 |
 * | 服务不在 ⇒ 重新拉起（并在服务活着时**什么也不做**） | 1 像素 Activity / 无声音乐 |
 * | 服务在 ⇒ 只是记一次心跳（真机可判读） | 任何绕过系统限制的手段 |
 *
 * 上表右列是**设计裁定**（阶段 5 的 `KeepAliveHealthCheck` KDoc 已立），
 * 本轮落地时**一字未改**：本类的全部动作只有"重投常驻通知 + 必要时 `startForegroundService`"。
 *
 * ## 为什么判据不是"心跳超期"（**对原设计的一处细化，如实登记**）
 * 原设计写的是"服务心跳超期未续"。落地时发现它**测不出来**：
 * 服务是**事件驱动**的（状态变化才写心跳，例如"事件源 6/7 变 5/7"），
 * 一台安静的设备可以几十分钟没有任何状态变化 —— 而那时服务**完全健康**。
 * 用"时间差"判活会把"安静但健康"判成"死了"，然后每 15 分钟去拉一次已经活着的服务。
 *
 * 因此判据取一个**单调事实**：本进程内**有没有见过活着的服务**。
 * - `lastHeartbeatMillis == 0` ⇒ 本进程从没见过服务（进程刚被闹钟唤醒，或服务已 `onDestroy`）
 *   ⇒ 需要自愈
 * - `lastHeartbeatMillis > 0` ⇒ 见过 ⇒ 服务活着 ⇒ 什么都不做
 *
 * 时间戳仍然**照原设计记录**（[onServiceHeartbeat]），它的用途变成：
 * ① 真机判读（`ageMillis` 说明"上次报活是多久之前"）；② 让"未知"这件事有表达。
 * **它不参与"要不要拉服务"的判定** —— 那条判定的依据是上表的两行，在
 * [KeepAliveDecision] 里有穷举单测。
 *
 * ## 时钟
 * 实现侧的时钟**必须可注入**（纯 JVM 下 `SystemClock` 未实现 ——
 * 与 `DaemonSupervisorImpl` / `BootloopGuard` 同一条纪律）。
 *
 * ## ★ 已知边界（2026-09-25 真机实测，**与设备/OEM 行为有关，代码侧无法修复**）
 * | 场景 | 结果 |
 * |---|---|
 * | 服务被停/被杀，**应用进程仍在** | ✅ 已验证：心跳到点 ⇒ `KEEPALIVE_WAKE_REQUESTED` ⇒ 服务被拉起（`FGS_CREATED generation=2`） |
 * | 安全模式 + 服务不在 | ✅ 已验证：`KEEPALIVE_NOOP reason=safe mode`，**不拉服务**（① 的护栏） |
 * | **整个进程消失**（从最近任务划掉 / 系统深度清理） | ⚠️ **本机实测：系统不为这个广播启动 App 进程**（闹钟被消费、`AlarmManager` 统计记了 wakeup，但 `Application.onCreate` 都没跑，日志一行都没有） |
 *
 * 第三行与 `PROJECT_STATE.md` 记录的「ColorOS 在分发阶段拦截 `BOOT_COMPLETED`」是**同一类**
 * OEM 后台策略：**设备不认为这个应用有权被后台唤醒**。因此本端口的能力边界是
 * **"进程存活期内的服务级自愈"**，不是"进程级复活"。
 *
 * 由此产生的两条纪律：
 * 1. **不得**在任何用户可见文案里承诺"进程被杀也能自动恢复"（首启声明页第 2 条已按此改写）
 * 2. 进程级恢复仍由既有机制承担：`START_STICKY`（系统重建服务，阶段 5 已验）
 *    与**用户打开 App**（`MainActivity.onKeepAliveConsentGranted`）。本端口不重复它们。
 */
interface KeepAliveWatchdog {
    /**
     * 确保"下一次检查"已被安排（自续期）。**幂等**：同一个 `(requestCode, action)` 的
     * 闹钟会被替换而不是叠加。
     *
     * 调用点：前台服务每次 `register()`（服务起来就确保看门狗在跑）、
     * 以及每次心跳处理完之后（自续期）。
     */
    fun ensureScheduled()

    /**
     * 取消看门狗。**用户明确停止服务**时调用 —— 否则下一次心跳会把服务拉回来，
     * 那是"我明明关了它，它自己又起来了"，属本仓库最忌讳的形态。
     */
    fun cancel()

    /** 服务报活（前台服务每次状态变化调用）。 */
    fun onServiceHeartbeat()

    /**
     * 服务已停止（`onDestroy` / 用户点通知里的「停止服务」）。
     *
     * 语义是**清掉"本进程见过活着的服务"这条事实**，于是下一次心跳会正确地自愈一次 ——
     * 在用户主动停止的路径上必须先 [cancel]，否则就成了"用户停了、看门狗又拉回来"。
     */
    fun onServiceStopped()

    /**
     * 一次检查（闹钟到点时由 `AlarmFireReceiver` 调用，**在广播的 `onReceive` 里**）。
     *
     * @return 本次判定的可见结果（真机判读与单测断言都用它）；**不抛异常**
     */
    fun onHeartbeat(): KeepAliveVerdict
}

/**
 * 自愈动作的执行者（"把服务拉起来"）。
 *
 * ## 为什么抽成端口而不是让看门狗直接调 `KeepAliveService.start`
 * `KeepAliveWatchdogImpl` 因此**可以纯 JVM 单测**（这正是它值得有一组用例的原因）。
 * Android 侧的实现只有一行 —— `KeepAliveService.start(context)`（`data/service/` 里）。
 *
 * ## 为什么只拉服务、不在这里重投通知
 * 拉服务时系统会投递 `onStartCommand`，而服务自己会用
 * `controller.currentNotification()` 重新前台化 —— 那条路径**已经存在**（阶段 5）。
 * 在这里再投一次通知就出现了**第二个通知真相源**，两者迟早不一致
 * （`SafeModeAlertSink` 的 KDoc 记过同一类判断）。
 */
fun interface KeepAliveWaker {
    /** 请求把前台服务拉起来（幂等：已在前台时只是重新投递一次 `onStartCommand`）。 */
    fun wake()
}

/** 一次检查的判定结果。只有三分支，且**互不重叠**。 */
enum class KeepAliveAction {
    /** 什么都不做（服务活着，或处于安全模式）。 */
    NOTHING,

    /** 重新拉起前台服务（服务不在）。 */
    WAKE_SERVICE,

    /**
     * 现在**判不了**（安全模式状态还没从磁盘恢复），稍后再看一次。
     *
     * ## 为什么有这一档，而不是"未知就当正常"
     * 心跳广播会**冷启动进程**，而安全模式的恢复发生在 `Application.onCreate` 的异步启动链里。
     * 若把"还没恢复"当成"不在安全模式"，看门狗就可能在一个**其实处于熔断**的进程里把服务拉起来 ——
     * 那正是本阶段要钉死的那条路（①）。
     * 反向折叠（未知当安全模式）的代价只是"这次不拉、30 秒后再看一次"，**代价小得多**。
     */
    DEFER,
}

/**
 * 判定输入（**纯数据**，由实现侧从"时钟 + 记忆 + 熔断状态"装配出来）。
 *
 * @property nowMillis 单调时钟读数（毫秒）
 * @property lastHeartbeatMillis 服务最后一次报活的时刻；`0` = 本进程内从未见过活着的服务
 * @property safeMode 熔断状态；**三态**——`null` = 启动期尚未从磁盘恢复（见 [KeepAliveAction.DEFER]）
 */
data class KeepAliveInput(
    val nowMillis: Long,
    val lastHeartbeatMillis: Long,
    val safeMode: Boolean?,
)

/** 判定结果（供接收器打日志与单测断言；**不含任何动作副作用**）。 */
data class KeepAliveVerdict(
    val action: KeepAliveAction,
    val input: KeepAliveInput,
) {
    /** 距上次报活的时长（毫秒）；从未报活时为 `null`（**不编 0**，两者语义不同）。 */
    val ageMillis: Long?
        get() = if (input.lastHeartbeatMillis <= 0L) null else input.nowMillis - input.lastHeartbeatMillis
}

/**
 * 看门狗的**判定纯函数**（阶段 12c 的护栏落点）。
 *
 * ## ★ 安全护栏（本阶段第 ① 项的硬规则，勿绕过）
 * > **安全模式下，看门狗不拉服务。**
 *
 * ### 为什么必须有这条规则（不是"从容错角度更保险"而已）
 * `ForegroundServiceController.register()` 无条件调 `daemonSupervisor.start()`，
 * 而 `DaemonSupervisorImpl.start()` 内部**没有任何安全模式判断**。今天走那条路的是
 * `START_STICKY`（系统重建服务 ⇒ `onStartCommand(null)` ⇒ `register()`）——**偶发**。
 * 加看门狗之后它会**每 15 分钟走一次**：熔断期间服务若被杀，看门狗把它拉起来，
 * 于是常驻监管与事件源**周期性复活**一次。
 *
 * 不会真的绕过熔断（每个脚本执行前还有 `SafeModeDecision.shouldRun` 兜着），
 * 但"安全模式 = 全部停下来"这条语义**不能靠两层闸门恰好都对**来成立 ——
 * 熔断的六步动作第 2 步就是"终止所有运行中的脚本"，而这里会周期性把它们重新造出来。
 *
 * ### 顺序
 * 表中第 1 行**优先于**其余所有行：安全模式是一个**否决**，不是众多输入之一。
 * 也就是说，即使服务确实死了、心跳确实过期，安全模式下也**不拉**——
 * 恢复运行能力的唯一入口是"退出安全模式"（`onSafeModeRestore` 会重启监管）。
 *
 * @see KeepAliveAction 三个分支的语义
 */
object KeepAliveDecision {
    /**
     * 判定这次心跳该做什么。
     *
     * 判定表（= `KeepAliveDecisionTest` 的期望表）：
     *
     * | # | safeMode | lastHeartbeat | 动作 | 依据 |
     * |---|---|---|---|---|
     * | 1 | `true` | 任意 | [KeepAliveAction.NOTHING] | **安全护栏**：安全模式下不拉服务 |
     * | 2 | `null`（未恢复） | 任意 | [KeepAliveAction.DEFER] | 判不了 ⇒ 稍后再判（不得把未知折叠成已知） |
     * | 3 | `false` | `0`（本进程没见过服务） | [KeepAliveAction.WAKE_SERVICE] | 自愈 |
     * | 4 | `false` | `> 0` | [KeepAliveAction.NOTHING] | 服务活着 ⇒ 一个动作都不做 |
     */
    fun decide(input: KeepAliveInput): KeepAliveVerdict {
        val action =
            when {
                input.safeMode == true -> KeepAliveAction.NOTHING
                input.safeMode == null -> KeepAliveAction.DEFER
                input.lastHeartbeatMillis <= 0L -> KeepAliveAction.WAKE_SERVICE
                else -> KeepAliveAction.NOTHING
            }
        return KeepAliveVerdict(action = action, input = input)
    }
}
