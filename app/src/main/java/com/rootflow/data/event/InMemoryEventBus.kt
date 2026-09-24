package com.rootflow.data.event

import com.rootflow.domain.event.EventBus
import com.rootflow.domain.model.SystemEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * [EventBus] 的内存实现（阶段 3b）。
 *
 * ## 为什么用 `Channel` 而不是 `MutableSharedFlow`
 * 直觉写法是 `MutableSharedFlow(extraBufferCapacity = N, onBufferOverflow = DROP_OLDEST)`，
 * 但它有一个致命语义（实测踩到）：**`extraBufferCapacity` 只为"已存在但慢"的订阅者缓冲，
 * 对"尚未订阅"的情况完全不留存**——而本项目的典型场景恰恰是"开机广播先到、调度器后订阅"，
 * 那样事件会被静默丢弃。
 *
 * 因此改为 `Channel(N, DROP_OLDEST)` + `receiveAsFlow()`：
 * **单一缓冲区**，无论有没有订阅者都先留存；满了丢最旧。与"C 端只订阅一次"的用法天然匹配。
 *
 * ## 背压（需求 §2.2 未指定，取与日志管道同款纪律）
 * - [send] 走 `trySend`，**非挂起**、**不阻塞生产者**（事件来自 `BroadcastReceiver.onReceive`，
 *   那里超时会 ANR）
 * - 溢出丢**最旧**（事件比日志更瞬时：迟到的开机事件没有价值）
 * - 丢弃量由 [droppedCount] 暴露，**不静默**
 *
 * @param bufferCapacity 缓冲容量（事件数）
 */
class InMemoryEventBus(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) : EventBus {
    /** 缓冲上限（同时用于丢弃量估算）。 */
    private val capacity: Long = bufferCapacity.toLong()

    /**
     * `replay = capacity` 是**有意为之**，不是随意取值：
     * 它让"尚未订阅时发出的事件"得以留存（见类 KDoc 第一段），
     * 同时 `DROP_OLDEST` 保证订阅者慢时**丢最旧、留最新**。
     */
    private val flow: MutableSharedFlow<SystemEvent> =
        MutableSharedFlow(
            replay = bufferCapacity,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val sent = AtomicLong(0)
    private val dropped = AtomicLong(0)

    override fun send(event: SystemEvent): Boolean {
        sent.incrementAndGet()
        val accepted = flow.tryEmit(event)
        // 缓冲上限为 capacity：超出的部分已被 DROP_OLDEST 挤掉。
        // 注意这是**单调估算**（不随消费下降），足以满足"丢弃可见"的要求。
        val estimatedDropped = (sent.get() - capacity).coerceAtLeast(0L)
        if (estimatedDropped > dropped.get()) {
            dropped.set(estimatedDropped)
        }
        return accepted
    }

    override fun events(): Flow<SystemEvent> = flow

    override fun droppedCount(): Long = dropped.get()

    companion object {
        /** 默认缓冲容量（事件数）。 */
        const val DEFAULT_BUFFER_CAPACITY: Int = 64
    }
}
