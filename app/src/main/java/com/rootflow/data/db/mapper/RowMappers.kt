package com.rootflow.data.db.mapper

import com.rootflow.data.db.entity.AppSwitchRow
import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.model.AppSwitch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.model.RunSummary
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams

/**
 * Room 行 ↔ `domain` 模型的映射（阶段 3a）。
 *
 * ## 为什么单列一个文件
 * 阶段 2 已有 `data/log/LogMappers.kt`（`runtime.LogLine` ↔ `domain.LogEntry`）。
 * 本文件是**存储层**的映射，职责不同（多了 `contentPath`、`params` JSON 等持久化关切），
 * 因此另立文件而非混入。
 *
 * ## 关于 `runtime.ScriptEntity`
 * 本文件**不产出** `runtime.ScriptEntity`——那属"装载给运行时执行"的职责，由阶段 3d 的
 * 执行路径在需要时组装（`Script.content` → `runtime.ScriptEntity.content`）。这样 `data`
 * 的持久化映射与 `runtime` 的执行映射各自独立，避免一个类同时背两种关切。
 *
 * 元数据 → domain（**不含正文**；列表页用，避免逐条读文件）。
 */
fun ScriptRow.toDomainWithoutContent(): Script =
    Script(
        id = id,
        name = name,
        language = language,
        enabled = enabled,
        timeoutSec = timeoutSec,
        autoDisableOnFail = autoDisableOnFail,
        runOnSafeMode = runOnSafeMode,
        resident = resident,
        content = "",
        contentSha256 = contentSha256,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** 元数据 + 已读到的正文 → domain。 */
fun ScriptRow.toDomainWithContent(content: String): Script = toDomainWithoutContent().copy(content = content)

/** domain → Row。正文不入库，因此只接收路径与摘要。 */
fun Script.toRow(
    contentPath: String,
    contentSha256: String?,
): ScriptRow =
    ScriptRow(
        id = id,
        name = name,
        language = language,
        enabled = enabled,
        timeoutSec = timeoutSec,
        autoDisableOnFail = autoDisableOnFail,
        runOnSafeMode = runOnSafeMode,
        resident = resident,
        contentPath = contentPath,
        contentSha256 = contentSha256,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** 触发器行 → domain；[params] 的 JSON 由调用方解码（便于注入告警回调）。 */
fun ScriptEventRow.toDomain(params: TriggerParams): Trigger =
    Trigger(
        id = id,
        scriptId = scriptId,
        eventType = eventType,
        params = params,
        enabled = enabled,
        createdAt = createdAt,
    )

/** domain → 触发器行；`params` 的 JSON 由调用方编码。 */
fun Trigger.toRow(paramsJson: String): ScriptEventRow =
    ScriptEventRow(
        id = id,
        scriptId = scriptId,
        eventType = eventType,
        params = paramsJson,
        enabled = enabled,
        createdAt = createdAt,
    )

/** 运行行 → domain 摘要。 */
fun RunRow.toSummary(): RunSummary =
    RunSummary(
        runId = id,
        scriptId = scriptId,
        triggerEvent = triggerEvent,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exitCode = exitCode,
        logEntryCount = logEntryCount,
        droppedLogEntries = droppedLogEntries,
    )

/** 日志行 → domain 条目；`stream` 名无法识别时回退 [LogStream.SYS] 并保留原文。 */
fun LogEntryRow.toDomain(): LogEntry =
    LogEntry(
        runId = runId,
        sequence = sequence,
        timestamp = timestamp,
        stream = runCatching { LogStream.valueOf(stream) }.getOrDefault(LogStream.SYS),
        text = text,
    )

/** domain 条目 → 日志行。 */
fun LogEntry.toRow(): LogEntryRow =
    LogEntryRow(
        runId = runId,
        sequence = sequence,
        timestamp = timestamp,
        stream = stream.name,
        text = text,
    )

/** 脚本正文的扩展名（与 [RootFlowPaths.extensionFor] 同源，避免两处规则）。 */
fun Script.bodyExtension(): String = RootFlowPaths.extensionFor(language)

/**
 * 总开关行 → domain。
 *
 * 单行表 ⇒ 没有"取哪一行"的问题；主键常量在两端各有一份，由
 * [AppSwitch.SINGLETON_ID] 与 `AppSwitchRow.SINGLETON_ID` 同值保证（schema 测试钉住列清单，
 * 取值一致性由 `MasterSwitchImplTest` 的往返用例钉住）。
 */
fun AppSwitchRow.toDomain(): AppSwitch =
    AppSwitch(
        masterEnabled = masterEnabled,
        enabledAt = enabledAt,
        disabledAt = disabledAt,
        updatedAt = updatedAt,
    )

/** domain → 总开关行（主键**恒为** [AppSwitch.SINGLETON_ID]，不取调用方的 id）。 */
fun AppSwitch.toRow(): AppSwitchRow =
    AppSwitchRow(
        id = AppSwitch.SINGLETON_ID,
        masterEnabled = masterEnabled,
        enabledAt = enabledAt,
        disabledAt = disabledAt,
        updatedAt = updatedAt,
    )
