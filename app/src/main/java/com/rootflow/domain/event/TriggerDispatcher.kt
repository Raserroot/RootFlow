package com.rootflow.domain.event

import com.rootflow.domain.model.SystemEvent

/**
 * 触发调度器（需求 §2.2 + §4.4 的前两步）。
 *
 * 职责链：
 * ```
 * 事件 → 取「已启用 + 订阅该事件」的触发器（TriggerRepository.enabledForEvent）
 *      → 逐条：重入拒绝？ → 防抖窗口内？ → ScriptRunner.start(scriptId, eventId, payload)
 * ```
 *
 * 单独暴露 [dispatch]（而不只提供"订阅总线"的入口）的原因：阶段 4 的**手动触发**与
 * 「立即熔断」也需要走同一条判定链，届时直接调 [dispatch] 即可，不必伪造一个总线事件。
 */
interface TriggerDispatcher {
    /**
     * 处理一个事件。
     *
     * **不抛异常**：单条触发器投递失败只记日志，不影响其余触发器（需求 §2.2 的"逐条投递"）。
     *
     * @param event 待处理事件
     * @param payloadOverride 覆盖事件自带的负载；`null` 表示使用 `event.payloadJson()`
     */
    suspend fun dispatch(
        event: SystemEvent,
        payloadOverride: String? = null,
    )
}
