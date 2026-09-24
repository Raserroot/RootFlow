package com.rootflow.domain.model

/**
 * 日志行的来源。
 *
 * **这是 `domain` 自有的类型，不复用 `com.rootflow.runtime.LogStream`。**
 * 理由：`runtime` 的类型是"运行时线级表示"的实现细节；若 `domain` 直接复用，
 * 阶段 6 的 `ui` 就必须 import `com.rootflow.runtime`，实质打破 `ui → domain`
 * 的分层意图。两者由 `data/log/LogMappers.kt` 做一对映射。
 *
 * 取值与 `runtime.LogStream` 一一对应，语义完全一致。
 */
enum class LogStream {
    /** 脚本进程的标准输出。 */
    STDOUT,

    /** 脚本进程的标准错误。 */
    STDERR,

    /** 运行时自身产生的系统行（起止、取消、管道内部故障等），非脚本输出。 */
    SYS,
}
