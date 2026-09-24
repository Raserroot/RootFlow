package com.rootflow.domain.model

/**
 * 某次运行的日志快照（环形缓冲中的现存内容）。
 *
 * 用途：UI 首屏渲染、配置变更（旋转）后回填。
 * 与 [LogBatch] 的区别：本类是**一次性快照**，不是流；拿到之后仍需订阅
 * `LogPipeline.observe(runId)` 才能收到后续增量。
 *
 * @property runId 归属的运行标识
 * @property entries 现存日志，按时间顺序；条目数不超过环形缓冲容量
 * @property droppedEntries 因环形缓冲容量上限而被淘汰的**累计条目数**；
 *   大于 0 说明 [entries] 不是该运行的完整历史（UI 应显式提示"更早的日志已滚出"）
 */
data class LogTail(
    val runId: String,
    val entries: List<LogEntry>,
    val droppedEntries: Long,
)
