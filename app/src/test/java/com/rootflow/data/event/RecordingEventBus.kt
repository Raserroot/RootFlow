package com.rootflow.data.event

import com.rootflow.domain.event.EventBus
import com.rootflow.domain.model.SystemEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 记录型事件总线假件（3b 测试用）。
 *
 * 与 [InMemoryEventBus] 的区别：本类**不丢弃**、**不涉及通道满**，只记录收到的全部事件，
 * 便于断言"接收器到底发了什么"。需要验证背压/丢弃语义时用 [InMemoryEventBus] 本身。
 */
internal class RecordingEventBus : EventBus {
    val sent = mutableListOf<SystemEvent>()

    private val flow = MutableSharedFlow<SystemEvent>(replay = 0, extraBufferCapacity = 64)

    override fun send(event: SystemEvent): Boolean {
        sent += event
        return flow.tryEmit(event)
    }

    override fun events(): Flow<SystemEvent> = flow

    override fun droppedCount(): Long = 0L
}
