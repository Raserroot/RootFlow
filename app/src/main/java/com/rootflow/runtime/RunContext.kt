package com.rootflow.runtime

/**
 * 一次运行所处的上下文。
 *
 * @property triggerEvent 触发本次运行的事件 ID；`null` 表示手动/自检触发。
 *   v1 用 `String`（如 `"boot"`、`"screen_off"`）；阶段 3 建立事件系统后会换成
 *   强类型 `SystemEvent`，届时本字段类型随之调整。
 * @property env 注入脚本进程的环境变量（如 `ROOTFLOW_SCRIPT_ID`）。
 *   阶段 1b 仅承载字段并原样透传，真正的环境变量注入在阶段 1b 之后的运行时完善。
 */
data class RunContext(
    val triggerEvent: String?,
    val env: Map<String, String>,
)
