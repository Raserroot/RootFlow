package com.rootflow.domain.service

import com.rootflow.domain.event.EventSourceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 主页「服务状态卡 + 事件源状态列表」的数据来源（阶段 6b）。
 *
 * ## 为什么把「事件源列表」也放进这个端口（**已批准的 6b 决策**）
 * 事件源状态的真相在 `EventSourceRegistry`，而它在 `data/event/`。
 * `AGENTS.md` 的分层约束是 `ui → domain`，`ui/` **不得** import `data/`，
 * 因此 README 要求的「用 `EventSourceRegistry.status()`」必须经一个 `domain` 端口转出。
 * 三条候选路径：
 *
 * | 方案 | 代价 |
 * |---|---|
 * | 把 `EventSourceRegistry` 搬进 `domain/` | 改 3c.1 冻结物的公开位置 |
 * | 新开第 4 个端口 `EventSourceStatusProvider` | 多一层纯转发（端口只做类型搬运） |
 * | **并入本端口**（采纳） | 端口语义收窄为「前台服务 **+ 它拥有的**事件源」 |
 *
 * 采纳第三条的理由不是"少写一个文件"，而是**所有权本来就是同一个**：
 * `ForegroundServiceController` 是 `EventSourceRegistry.start()` / `stop()` 的
 * **唯一所有者**（阶段 5 的单所有者不变量，D4 关闭的前提）。
 * 把两者拆到两个端口，会允许"服务状态"与"事件源状态"来自两个不同的所有者，
 * 而那正是阶段 5 修掉的那个静默缺陷形态。
 *
 * ## 为什么是 `StateFlow` 而不是一次性查询
 * 主页需要"服务被拉起后卡片自己变正确"，而不是"用户下拉才刷新"。
 * `StateFlow` 的**拉模型**还顺带解决了冷启动：订阅者立即拿到当前值，
 * 不必依赖"注册恰好在订阅之前完成"。
 *
 * ## 背压策略
 * 两个流都是 `StateFlow`（"仅保留最新值"）。这不是疏漏：它们是**状态**而非事件序列，
 * 中间值对订阅者没有意义（UI 只需要"此刻是什么样"）。因此本端口的背压策略是
 * **合并到最新**，与 `EventBus` / `LogPipeline` 的"有界 + 丢弃计数"策略刻意不同
 * —— 后两者丢的是**事件**，丢了就必须可见；这里丢的是**过期的状态快照**。
 */
interface ServiceStateProvider {
    /**
     * 前台服务的当前状态。
     *
     * 未注册时为 [ForegroundState.Idle]（**如实**：不假装在运行）。
     * 取值口径与常驻通知**完全一致**（同一份 `currentState()` 计算），
     * 因此"通知说 6/7、卡片说 3/7"这种漂移在结构上不可能发生。
     */
    val state: StateFlow<ForegroundState>

    /**
     * 逐源状态快照，按 `sourceId` 升序（与 `EventSourceRegistry.status()` 同序，真机可逐行比对）。
     *
     * 未注册时为空列表 —— 不是"全部 NotStarted"，而是"没有任何东西在监听"：
     * 服务没起来时，事件源根本没有被编排过，报 [EventSourceState] 三态中的任何一态
     * 都会误导用户去查权限（决策 7 的"原因要具体"在这里表现为**不编造原因**）。
     *
     * 每项的 `state` 是 [com.rootflow.domain.event.EventSourceState] 三态：
     * `Running` / `NotStarted` / `Unavailable(reason)`，其中 `reason` 的具体程度
     * 直接决定 6d 设置页能否给出正确的授权引导。
     */
    val sources: StateFlow<List<EventSourceStatus>>
}
