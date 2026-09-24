package com.rootflow.runtime

/**
 * 待执行的脚本实体。
 *
 * 本类在阶段 1b 作为**运行时入参**存在；阶段 3 引入 Room 后，`ScriptEntity`
 * 会由持久化层构造（字段一一对应 `ScriptEntity` 表），因此这里不携带任何
 * 持久化注解，保持 `runtime/` 对存储层无依赖。
 *
 * @property id 脚本唯一标识（阶段 3 起为 Room 主键）
 * @property name 脚本显示名
 * @property language 脚本语言；v1 只支持 `"shell"`
 * @property enabled 是否启用（阶段 1b 不做判定，仅承载字段）
 * @property timeoutSec 单次运行超时秒数（阶段 1b **不实现**，留待阶段 1c）
 * @property autoDisableOnFail 连续失败后自动禁用（阶段 1b **不实现**，留待阶段 4）
 * @property runOnSafeMode 安全模式下是否仍执行（阶段 1b **不实现**，留待阶段 4）
 * @property content 脚本正文。会被 [ShellScriptRuntime] 以 `sh -c '<正文>'` 的形式
 *   提交给 shell（正文内单引号按 `'\''` 转义），因此正文可自由使用 shell 语法：
 *   分号、`>&2` 等重定向、`exit`、变量展开。**注意** `exit` 只会终止该子 shell，
 *   不会终止 libsu 的交互 shell（这一点很关键：libsu 依赖交互 shell 存活来回收
 *   退出码）。
 */
data class ScriptEntity(
    val id: Long,
    val name: String,
    val language: String,
    val enabled: Boolean,
    val timeoutSec: Int,
    val autoDisableOnFail: Boolean,
    val runOnSafeMode: Boolean,
    val content: String,
)
