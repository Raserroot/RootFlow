package com.rootflow.data.event

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全模式的**进程级只读快照**（阶段 12c 新增，为保活看门狗服务）。
 *
 * ## 为什么需要它（而不是让看门狗直接读 `CircuitBreaker.safeMode`）
 * 保活看门狗是被**闹钟广播**唤醒的，而广播会**冷启动进程**。此时：
 *
 * ```
 * 进程起来 → Application.onCreate（异步启动链）→ … → restoreSafeModeState() 读磁盘 flag
 *                    ↓ 同一时刻
 *            AlarmFireReceiver.onReceive → 看门狗判定"要不要拉服务"
 * ```
 *
 * 两条路**没有先后保证**。若看门狗直接读 `CircuitBreaker.safeMode`，它在启动链跑完之前
 * 拿到的是 `false`（`CircuitBreakerImpl` 的内存初值），于是**熔断期间会把服务拉起来** ——
 * 而那正是本轮 ① 要钉死的那条路（`PROJECT_STATE.md`「立即要做 · ①」的隐患）。
 *
 * ⇒ 判据必须是**三态**：`null` = "还没从磁盘恢复"，**不得折叠成 `false`**
 * （与 `PermissionGrant` / `GroupLiveness` / `EventSourceState` 同一条纪律）。
 * 看门狗见到 `null` 时的动作是"稍后再判"（`KeepAliveAction.DEFER`），
 * 代价只有 30 秒，方向也是保守的那一侧。
 *
 * ## 谁写它（两个更新点，缺一不可）
 * | 时点 | 位置 | 为什么 |
 * |---|---|---|
 * | 启动链恢复完成 | `RootFlowApp.restoreSafeModeState()` | 把磁盘 flag 的真相带进来（含"不在安全模式"这个**确定**的结论） |
 * | 运行期熔断状态变化 | `ForegroundServiceController` 的订阅回调 | 用户点「立即熔断」/「退出安全模式」之后，快照必须跟着变，否则它会永久停在启动那一刻 |
 *
 * ## 为什么它不是 `StateFlow`
 * 消费者只有一个（看门狗的判定），且在**广播回调**里读 —— 那里不适合收集流。
 * 值语义 + 线程安全足够；确有第二个消费者时再升级形态（不提前造能力）。
 */
@Singleton
class SafeModeSnapshot
    @Inject
    constructor() {
        private val lock = Any()
        private var known: Boolean? = null

        /**
         * 当前已知的安全模式状态；`null` = **还没从磁盘恢复**（三态，见类 KDoc）。
         *
         * 读它的人**不得**把 `null` 当成 `false`：那会让"未知"变成"确认不在熔断中"。
         */
        val current: Boolean?
            get() = synchronized(lock) { known }

        /** 记下一次**确定**的结论（启动恢复或运行期状态变化）。 */
        fun update(safeMode: Boolean) {
            synchronized(lock) { known = safeMode }
        }

        /** 回到"未知"（**仅供单测**在用例之间隔离；生产路径不得调用）。 */
        internal fun reset() {
            synchronized(lock) { known = null }
        }
    }
