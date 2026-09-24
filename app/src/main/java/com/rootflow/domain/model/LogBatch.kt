package com.rootflow.domain.model

/**
 * 一批日志（管道向订阅者推送的最小单位）。
 *
 * ## 为什么批量而不是逐行
 * `runtime` 的 `Flow<LogLine>` 每行发射一次；若直接透传给 UI，高速脚本（如循环打印）
 * 会以数千 Hz 驱动界面重组。管道按"**满 200 条**或**距上次 flush ≥ 100ms**"聚合后推送
 * （取值见 `LogPipelineImpl` 的伴生常量），把 UI 更新频率压到 ~10Hz 量级。
 *
 * ## 丢行必须可见（硬约束）
 * 当订阅者消费不及、管道内部通道溢出时，策略是 **丢弃最旧的批次**，
 * 并把累计丢弃的**条目数**记录在后续批次的 [droppedEntries] 中。
 * 绝不静默丢数据——"静默失败"是本项目反复踩过的坑型（见 `AGENT_PROTOCOL.md §8`）。
 *
 * @property runId 归属的运行标识
 * @property sequence 批次序号，同一运行内从 0 单调递增；订阅者据此检测漏批
 * @property entries 本批日志，按时间顺序；已填好 `sequence` 字段
 * @property droppedEntries 截至本批为止，因通道溢出被丢弃的**累计条目数**（0 表示无丢弃）
 * @property runFinished 本批是否为该运行的**最后一批**；为 `true` 后不再有该 run 的批次
 */
data class LogBatch(
    val runId: String,
    val sequence: Long,
    val entries: List<LogEntry>,
    val droppedEntries: Long,
    val runFinished: Boolean,
)
