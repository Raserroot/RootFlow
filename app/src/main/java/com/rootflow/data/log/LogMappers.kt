package com.rootflow.data.log

import com.rootflow.domain.model.LogEntry
import com.rootflow.runtime.LogLine

/**
 * `runtime` 与 `domain` 的日志类型映射（阶段 2 引入的唯一"跨层翻译点"）。
 *
 * ## 为什么需要这一层
 * `domain` 刻意**不复用** `runtime.LogLine` / `runtime.LogStream`（理由见
 * `domain/model/LogStream.kt` 的 KDoc）。两个枚举目前一一对应，但它们是**独立演化**的：
 * 将来任一侧加取值，编译期就会在这里暴露不匹配，而不是让错误悄悄流到 UI。
 *
 * ## `sequence` 的处理
 * [toDomain] 把 `sequence` 置为 [SEQUENCE_PLACEHOLDER]——真实序号由
 * `LogPipelineImpl` 按运行内单调递增写入并覆盖。映射函数不持有运行状态，
 * 因此**不应**在这里编造序号。
 *
 * 注：本文件内的枚举转换一律写全限定名。`runtime` 与 `domain` 各有一个
 * `LogStream`，若用 import + 扩展函数会因"被 import 的类名遮蔽同名声明"而冲突。
 */
internal object LogMappers {
    /** [toDomain] 产出的占位序号；会被管道覆盖。 */
    const val SEQUENCE_PLACEHOLDER: Long = -1L

    /** `runtime.LogLine` → `domain.LogEntry`；[runId] 由调用方给定。 */
    fun toDomain(
        line: LogLine,
        runId: String,
    ): LogEntry =
        LogEntry(
            runId = runId,
            sequence = SEQUENCE_PLACEHOLDER,
            timestamp = line.timestamp,
            stream = toDomainStream(line.stream),
            text = line.text,
        )

    /**
     * `domain.LogEntry` → `runtime.LogLine`。
     *
     * 阶段 2 暂无生产调用方（数据流方向是 runtime → domain）；保留它的用途是
     * **双向映射的自检**：单测用它保证两个枚举不会单向漂移。
     */
    fun toRuntime(entry: LogEntry): LogLine =
        LogLine(
            timestamp = entry.timestamp,
            stream = toRuntimeStream(entry.stream),
            text = entry.text,
        )

    /** `runtime.LogStream` → `domain.LogStream`。 */
    fun toDomainStream(stream: com.rootflow.runtime.LogStream): com.rootflow.domain.model.LogStream =
        when (stream) {
            com.rootflow.runtime.LogStream.STDOUT -> com.rootflow.domain.model.LogStream.STDOUT
            com.rootflow.runtime.LogStream.STDERR -> com.rootflow.domain.model.LogStream.STDERR
            com.rootflow.runtime.LogStream.SYS -> com.rootflow.domain.model.LogStream.SYS
        }

    /** `domain.LogStream` → `runtime.LogStream`。 */
    fun toRuntimeStream(stream: com.rootflow.domain.model.LogStream): com.rootflow.runtime.LogStream =
        when (stream) {
            com.rootflow.domain.model.LogStream.STDOUT -> com.rootflow.runtime.LogStream.STDOUT
            com.rootflow.domain.model.LogStream.STDERR -> com.rootflow.runtime.LogStream.STDERR
            com.rootflow.domain.model.LogStream.SYS -> com.rootflow.runtime.LogStream.SYS
        }
}
