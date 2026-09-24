package com.rootflow.data.run

import com.rootflow.domain.model.Script
import com.rootflow.runtime.ScriptEntity

/**
 * `domain.Script` → `runtime.ScriptEntity` 的映射（阶段 3d）。
 *
 * ## 为什么需要这一跳
 * `ScriptRepository.load` 返回的是 `domain` 视角的脚本（含 `createdAt` / `updatedAt` /
 * `contentSha256`），而 `ScriptRunCoordinator` → `runtime.ScriptRuntime.run` 要的是
 * `runtime.ScriptEntity`（**运行时入参**，1b 起冻结的契约）。
 *
 * 两个类型刻意不合并（`ScriptEntity` 的 KDoc 写明"不携带持久化注解，保持
 * `runtime/` 对存储层无依赖"），因此需要一个显式的翻译点——与
 * `data/log/LogMappers` 处理日志类型是同一手法。
 *
 * ## 映射表（逐一对应，缺一不可）
 * | `domain.Script` | `runtime.ScriptEntity` |
 * |---|---|
 * | `id` / `name` / `language` | 同名同义 |
 * | `enabled` | 同名（**调用方已在校验后才映射**，见 `TriggeredScriptRunner`） |
 * | `timeoutSec` / `autoDisableOnFail` / `runOnSafeMode` | 同名（阶段 4 才真正生效） |
 * | `content` | **正文**（经 root 通道读出，丢失即等于脚本静默不执行） |
 * | `createdAt` / `updatedAt` / `contentSha256` | runtime 不需要，不映射 |
 *
 * ## 为什么不做成 `Script.toRuntime()`
 * 那会让 `domain` 依赖 `runtime`——违反 `AGENTS.md` 的
 * `ui → domain → data / runtime` 依赖方向。映射函数必须待在 `data`。
 */
internal object ScriptEntityMapper {
    /** 逐字段映射；`content` 原样传递（**不得**做 trim 或转义，转义由 runtime 负责）。 */
    fun toRuntime(script: Script): ScriptEntity =
        ScriptEntity(
            id = script.id,
            name = script.name,
            language = script.language,
            enabled = script.enabled,
            timeoutSec = script.timeoutSec,
            autoDisableOnFail = script.autoDisableOnFail,
            runOnSafeMode = script.runOnSafeMode,
            content = script.content,
        )
}
