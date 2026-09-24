package com.rootflow.runtime

/**
 * 一次 shell 命令的执行结果。
 *
 * [stdout] / [stderr] 为 **多行合并后的单个字符串**（以 `\n` 连接）：调用方
 * （阶段 1a 的验证入口、后续阶段的日志管道）需要的是可直接打印 / 落盘的文本；
 * 结构化逐行流由阶段 2 的日志管道负责。
 *
 * @property stdout 标准输出（多行已合并）
 * @property stderr 标准错误（多行已合并）
 * @property exitCode 进程退出码，0 表示成功
 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    /** 命令是否成功（退出码为 0）。 */
    val isSuccess: Boolean
        get() = exitCode == 0
}
