package com.rootflow.domain.model

/**
 * 管道内的一条日志（`domain` 层的稳定表示）。
 *
 * ## 与 `runtime.LogLine` 的关系
 * `runtime.LogLine` 是运行时**线级**表示（`timestamp` / `stream` / `text`）；
 * 本类是**管道级**表示，额外携带归属与顺序：
 * - [runId]：归属哪次运行（管道同时服务多次运行）
 * - [sequence]：**由所在运行的批次序列决定**的单调序号
 *
 * 两者由 `data/log/LogMappers.kt` 映射，映射只负责字段转换；
 * **[sequence] 不经映射赋予，而是由 `LogPipelineImpl` 按运行内序号写入**
 * （映射函数产出的 `LogEntry` 该字段会被管道覆盖，见 `LogPipeline` KDoc）。
 *
 * @property runId 归属的运行标识
 * @property sequence 运行内单调递增序号，从 0 开始；用于订阅者检测漏行/漏批
 * @property timestamp 产生时间（毫秒，来自 `runtime.LogLine`）
 * @property stream 来源流
 * @property text 行内容；**不含**行尾换行符
 */
data class LogEntry(
    val runId: String,
    val sequence: Long,
    val timestamp: Long,
    val stream: LogStream,
    val text: String,
)
