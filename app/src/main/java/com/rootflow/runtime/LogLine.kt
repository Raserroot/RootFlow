package com.rootflow.runtime

/**
 * 日志行的来源。
 *
 * 注意：与 [ShellResult] 只区分 stdout/stderr 不同，这里多一个 [SYS] —— 用于承载
 * **运行时自身**产生的行（启动、退出、取消），它们不来自脚本进程。
 */
enum class LogStream {
    /** 脚本进程的标准输出。 */
    STDOUT,

    /** 脚本进程的标准错误。 */
    STDERR,

    /** 运行时自身产生的系统行（起止、取消等），非脚本输出。 */
    SYS,
}

/**
 * 一条日志。
 *
 * @property timestamp 产生该行的时间（`System.currentTimeMillis()`）
 * @property stream 来源流，见 [LogStream]
 * @property text 行内容；**不含**行尾换行符
 */
data class LogLine(
    val timestamp: Long,
    val stream: LogStream,
    val text: String,
)
