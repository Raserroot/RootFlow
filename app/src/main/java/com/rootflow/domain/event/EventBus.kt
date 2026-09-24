package com.rootflow.domain.event

import com.rootflow.domain.model.SystemEvent
import kotlinx.coroutines.flow.Flow

/**
 * 事件总线（需求 §2.2：「统一 EventBus（`SharedFlow<SystemEvent>`）」）。
 *
 * ## 背压策略（与日志管道同款纪律，`AGENTS.md` 要求）
 * - [send] 是**非挂起**的：事件来自系统回调（`BroadcastReceiver.onReceive` 必须秒级返回）
 *   与 3c 的事件源线程。**UI 或数据库慢绝不能卡住广播接收器**（会直接导致 ANR）
 * - 内部通道容量固定 + **溢出丢弃最旧**；丢弃量由 [droppedCount] 暴露，**不静默丢数据**
 *
 * ## 与触发器的关系
 * 总线**不认识** Room，也不做匹配：它只负责"把事件广播出去"。
 * 匹配、防抖、重入、投递都在 [TriggerDispatcher]。
 */
interface EventBus {
    /**
     * 广播一个事件。
     *
     * **不需要挂起**，也**不会**因为订阅者慢而阻塞调用方。
     *
     * @param event 待广播的事件
     * @return `true` 表示已入队；`false` 表示因通道满而挤掉了最旧的**已入队事件**
     *   （本次事件仍然入队成功——与 `MutableSharedFlow` 的 `DROP_OLDEST` 语义一致）
     */
    fun send(event: SystemEvent): Boolean

    /** 订阅事件流。热流：只推送订阅之后的事件。 */
    fun events(): Flow<SystemEvent>

    /** 因通道溢出而被挤掉的累计事件数（**不静默**）。 */
    fun droppedCount(): Long
}
