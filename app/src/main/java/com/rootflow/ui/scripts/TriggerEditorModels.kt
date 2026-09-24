package com.rootflow.ui.scripts

import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.SettingsTarget
import com.rootflow.domain.model.EventCatalog
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.TriggerParams

/*
 * 触发器勾选 UI 的**纯数据模型 + 纯函数**（阶段 6e）。
 *
 * ## 为什么继续放在 `ui/scripts/`（而不是新开一个"trigger"包）
 * 这一整块是**脚本编辑器的一部分**：它的入口是编辑器的"触发器"区，它读写的
 * 是同一个 `TriggerRepository`，它的状态进的是同一个 `ScriptEditorViewModel`。
 * 拆成两个包会让"编辑器的 dirty 不含触发器改动"这条语义跨包、更难看见。
 *
 * ## 为什么全部是纯函数
 * 本仓库**没有 UI 自动化测试**（决策 B：`ui-test-*` / `androidTest` / espresso 全不在离线缓存）。
 * Composable 在纯 JVM 下不可测 ⇒ "这个 chip 该不该带 ⚠""这段参数能不能落库"这类判定
 * **必须上移到纯函数**，否则它们的覆盖率是 0。与 `ScriptModels.kt` 同款动机。
 *
 * 用块注释而非 KDoc：ktlint 的 `kdoc` 规则不允许"一个 KDoc 紧邻另一个 KDoc"。
 */

// ---------------------------------------------------------------------------① 库内一行的投影

/**
 * 一行触发器在编辑器里的投影。
 *
 * @property enabled `false` **且该行存在** = 被安全熔断禁用（或用户自己关的）。
 *   这与"chip 未选中"是**两件不同的事**：未选中 = 库里没有行；
 *   `enabled = false` = 行在、但 dispatch 查表时匹配不到。
 *   阶段 6b 项 2 的真机缺陷正是这个形态（App 表现完全正常、事件零匹配），
 *   因此参数区必须把它显式说出来（见 [TriggerRowUi.disabledNotice]）。
 */
data class TriggerRowUi(
    val id: Long,
    val eventType: String,
    val params: TriggerParams,
    val enabled: Boolean,
    val createdAt: Long,
) {
    /** chip 标签（未知键回落成键本身，见 [EventCatalog.label]）。 */
    val label: String get() = EventCatalog.label(eventType)

    /** 被禁用时的提示；启用时为 `null`。 */
    val disabledNotice: String? get() = if (enabled) null else DISABLED_NOTICE

    /** 参数摘要（选中行的第二行文字，见 [TriggerEditorProjections.summary]）。 */
    val summary: String get() = TriggerEditorProjections.summary(eventType, params)

    companion object {
        /** 用户可见文案（**稳定字符串**，真机判读靠 `findstr` 它）。 */
        const val DISABLED_NOTICE: String = "已禁用：事件到达时不会被调度"
    }
}

// ---------------------------------------------------------------------------② 选择差异

/**
 * 一次"勾选状态"变更要落库的动作（**纯函数产出，顺序确定**）。
 *
 * ## 不变量（全部由 `TriggerSelectionDiffTest` 钉死）
 * 1. [toCreate] / [toDelete] 按 [EventCatalog] 的**目录序**排序 —— 不按 `Map` 迭代序、
 *    不按 Room 返回序。同样的输入必须永远产出同样的调用序列，否则真机日志无法逐行比对。
 * 2. 合并键是 `eventType`（**不是 id**）：同一脚本同一事件出现多行是数据损坏，
 *    `TriggerRepository` 没有唯一约束可依赖。
 * 3. 重复行的保留策略：**优先保留 `enabled = true` 的那条**（避免把唯一一条能跑的行删掉），
 *    同档内取 `id` 最小（最早创建的）。其余进 [duplicates]。
 * 4. 期望集合里的**未知键**被忽略（`EventCatalog.spec` 返回 `null`）：不崩、不静默 ——
 *    由调用方记 [unknown]。
 */
data class TriggerSelectionDiff(
    val toCreate: List<String>,
    val toDelete: List<Long>,
    val duplicates: List<Long>,
    val unknown: List<String> = emptyList(),
) {
    /** 是否有任何要写库的动作。 */
    val isEmpty: Boolean get() = toCreate.isEmpty() && toDelete.isEmpty() && duplicates.isEmpty()

    /** 要写库的总条数（含清理重复行）。 */
    val totalWrites: Int get() = toCreate.size + toDelete.size + duplicates.size

    companion object {
        /**
         * 计算差异。
         *
         * @param desired UI 期望的选中事件键集合
         * @param existing 库里当前的全部触发器（本脚本的；`id` 非 0）
         */
        fun compute(
            desired: Set<String>,
            existing: List<TriggerRowUi>,
        ): TriggerSelectionDiff {
            val known = desired.filter { EventCatalog.spec(it) != null }
            val unknown = desired.filter { EventCatalog.spec(it) == null }

            // 同一个 eventType 去重：保留策略见类 KDoc 第 3 条
            val keepByType = mutableMapOf<String, TriggerRowUi>()
            val duplicates = mutableListOf<Long>()
            existing
                .sortedWith(compareBy({ EventCatalog.orderOf(it.eventType) }, { it.createdAt }, { it.id }))
                .forEach { row ->
                    val current = keepByType[row.eventType]
                    if (current == null) {
                        keepByType[row.eventType] = row
                    } else if (!current.enabled && row.enabled) {
                        // 后到的那条是启用的 ⇒ 换它当主行，原主行进重复
                        keepByType[row.eventType] = row
                        duplicates += current.id
                    } else {
                        duplicates += row.id
                    }
                }

            val toDelete =
                keepByType
                    .filterKeys { it !in known }
                    .values
                    .map { it.id }

            val toCreate = known.filter { it !in keepByType.keys }

            return TriggerSelectionDiff(
                toCreate = toCreate.sortedBy { EventCatalog.orderOf(it) },
                toDelete = toDelete.sorted(),
                duplicates = duplicates.sorted(),
                unknown = unknown.sortedBy { EventCatalog.orderOf(it) },
            )
        }
    }
}

// ---------------------------------------------------------------------------③ 参数编辑草稿

/**
 * 参数区的编辑草稿（**全部是字符串**）。
 *
 * ## 为什么是字符串而不是 `TriggerParams`
 * 用户正在打字时 `"0"`、`"07"`、`""` 都是**合法的中间态**，而 `Int?` 表达不了它们
 * （`""` 与 `null` 在 `Int?` 上是同一个值，于是"清空输入框"会立刻变成"未配置"，
 * 参数区会在用户打字的中途跳变）。校验与转换由 [toParams] 在**提交时**做。
 *
 * ## 只有 4 个字段 —— 因为 `TriggerParams` 只有 5 个扁平字段
 * `exact` 是显式开关（不是文本），其余 4 个各占一个输入框。
 * **没有"包名"**：`TriggerParams` 里**没有** `packageName` 字段（它是 3a 冻结的持久化契约，
 * 本阶段**不加迁移**）。因此 `app_foreground` 的"包名筛选"在本阶段**不做** ——
 * 让用户填一个存不下来的框，就是本仓库最忌讳的"静默失效"。
 *
 * ## `payload` 的换行是**硬禁忌**（不是风格）
 * `ShellScriptRuntime.sanitizeEnv` 会拒绝含 `\n` / `\r` 的 env 值并逐条告警 —— 因为
 * POSIX 单引号串无法表示换行，拼进去会把一条 `export` 拆成两条命令（**命令注入**）。
 * 因此 [withPayload] **在 onChange 里就把换行剔掉**，而不是等用户配完再报错：
 * 后者会让"我配的 payload 没到脚本"成为一个只能去 logcat 找的谜。
 */
data class TriggerParamsDraft(
    val hourOfDay: String = "",
    val minuteOfHour: String = "",
    val intervalMinutes: String = "",
    val payload: String = "",
    val exact: Boolean = false,
) {
    /**
     * 写入 payload：**剥离换行**。
     *
     * @return `first` = 新草稿；`second` = 是否**剔除过**字符（UI 据此提示一次，不静默）
     */
    fun withPayload(raw: String): Pair<TriggerParamsDraft, Boolean> {
        val cleaned = raw.replace("\n", "").replace("\r", "")
        return copy(payload = cleaned) to (cleaned != raw)
    }

    /**
     * 该草稿对事件 [eventType] 是否"配全了"（决定参数区那一句提醒）。
     *
     * `time` 需要时+分、`interval` 需要间隔；其余事件恒为 `true`
     * （payload 是**可选**的，空 payload 有明确语义：回退到 `event.payloadJson()`）。
     */
    fun isCompleteFor(eventType: String): Boolean =
        when (eventType) {
            SystemEvent.TIME -> toParams()?.hourOfDay != null
            SystemEvent.INTERVAL -> toParams()?.intervalMinutes != null
            else -> true
        }

    /**
     * 草稿 → 持久化形态。
     *
     * 契约（`TriggerParamsDraftTest` 穷举）：
     * | 字段 | 规则 |
     * |---|---|
     * | `hourOfDay` / `minuteOfHour` | **必须同时有效**（0..23 / 0..59）；只填一个 ⇒ `null`（未配置时刻） |
     * | `intervalMinutes` | 1..1440；`0` / `1441` / 非数字 / 空 ⇒ `null`（未配置间隔） |
     * | `payload` | 空 ⇒ `null`（**空串与 null 是同一个语义**，与 `TriggerParamsCodec` 的省略一致） |
     * | `exact` | 恒透传（它是显式开关，不是文本） |
     *
     * @return 可落库的 [TriggerParams]；**存在无法解析的非空输入**时返回 `null`
     *   （调用方据此提示并**跳过写库**，不把非法值静默换成默认值）
     */
    fun toParams(): TriggerParams? {
        val hour = parseIntOrNull(hourOfDay)
        val minute = parseIntOrNull(minuteOfHour)
        val hasTimeInput = hourOfDay.isNotBlank() || minuteOfHour.isNotBlank()
        if (hasTimeInput) {
            if (hour == null || minute == null) return null
            if (hour !in HOUR_RANGE || minute !in MINUTE_RANGE) return null
        }

        val interval = parseIntOrNull(intervalMinutes)
        if (intervalMinutes.isNotBlank() && (interval == null || interval !in INTERVAL_RANGE)) return null

        return TriggerParams(
            exact = exact,
            intervalMinutes = interval,
            hourOfDay = if (hasTimeInput) hour else null,
            minuteOfHour = if (hasTimeInput) minute else null,
            payload = payload.ifEmpty { null },
        )
    }

    /** 字段级错误（参数区下方就地显示；空列表 = 没有错误）。 */
    fun errors(): List<TriggerParamError> {
        val errors = mutableListOf<TriggerParamError>()

        // ★ 时刻**只填一半不是错误**（阶段 6e 真机验证项 11 的裁定）：
        //   用户先填「时」再填「分」是**必然经过**的中间态，此时报错会在打字途中骂人。
        //   它不是错误，是"还没配完" ⇒ 由 `TriggerEditorProjections.TIME_INCOMPLETE`
        //   那条提示承担，且 `toParams()` 对半填返回 null（不落库），语义不变。
        //   只有**真的越界**才算错误。
        val hour = parseIntOrNull(hourOfDay)
        val minute = parseIntOrNull(minuteOfHour)
        if (hourOfDay.isNotBlank() && hour != null && hour !in HOUR_RANGE) {
            errors += TriggerParamError(Field.TIME, "「时」超出范围（0–23）")
        }
        if (minuteOfHour.isNotBlank() && minute != null && minute !in MINUTE_RANGE) {
            errors += TriggerParamError(Field.TIME, "「分」超出范围（0–59）")
        }
        if (hourOfDay.isNotBlank() && hour == null) {
            errors += TriggerParamError(Field.TIME, "「时」必须是数字")
        }
        if (minuteOfHour.isNotBlank() && minute == null) {
            errors += TriggerParamError(Field.TIME, "「分」必须是数字")
        }

        val intervalRaw = intervalMinutes.trim()
        if (intervalRaw.isNotEmpty()) {
            val interval = parseIntOrNull(intervalMinutes)
            when {
                interval == null ->
                    errors += TriggerParamError(Field.INTERVAL, "间隔必须是整数（分钟）")

                interval !in INTERVAL_RANGE ->
                    errors +=
                        TriggerParamError(
                            Field.INTERVAL,
                            "间隔超出范围（$INTERVAL_MIN–$INTERVAL_MAX 分钟）",
                        )
            }
        }

        if (payload.length > MAX_PAYLOAD_LENGTH) {
            errors +=
                TriggerParamError(
                    Field.PAYLOAD,
                    "负载过长（上限 $MAX_PAYLOAD_LENGTH 字符）",
                )
        }

        return errors
    }

    companion object {
        /** 参数区字段（决定错误落在哪个输入框下面）。 */
        enum class Field {
            TIME,
            INTERVAL,
            PAYLOAD,
        }

        /** `时` 的合法范围。 */
        val HOUR_RANGE: IntRange = 0..23

        /** `分` 的合法范围。 */
        val MINUTE_RANGE: IntRange = 0..59

        /** `intervalMinutes` 的下限（0 = 未配置，不是"每 0 分钟"）。 */
        const val INTERVAL_MIN: Int = 1

        /** `intervalMinutes` 的上限（24 小时；更长的周期应该用 `time`）。 */
        const val INTERVAL_MAX: Int = 1440

        /** `intervalMinutes` 的合法范围。 */
        val INTERVAL_RANGE: IntRange = INTERVAL_MIN..INTERVAL_MAX

        /** payload 长度上限（`TriggerParamsCodec` 不限制，这里是 UI 侧的防御性封顶）。 */
        const val MAX_PAYLOAD_LENGTH: Int = 1024

        /** 空草稿（同一事件未被编辑过时的初值）。 */
        val Empty: TriggerParamsDraft = TriggerParamsDraft()

        /** 由库里的一行反推草稿（参数区展开时的初值 ⇒ 所见即所得）。 */
        fun of(params: TriggerParams): TriggerParamsDraft =
            TriggerParamsDraft(
                hourOfDay = params.hourOfDay?.toString() ?: "",
                minuteOfHour = params.minuteOfHour?.toString() ?: "",
                intervalMinutes = params.intervalMinutes?.toString() ?: "",
                payload = params.payload ?: "",
                exact = params.exact,
            )

        private fun parseIntOrNull(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    }
}

/** 参数区的一条字段级错误。 */
data class TriggerParamError(
    val field: TriggerParamsDraft.Companion.Field,
    val message: String,
)

// ---------------------------------------------------------------------------④ 权限联合

/**
 * 某个事件"依赖的权限"的联合（快照 + 是否已探测）。
 *
 * ## 为什么有 `loaded` 这一位
 * `PermissionStatusProvider.current()` 在探测前返回**空 Map**，而"空 Map"与
 * "全部已授予"在 `Map<AndroidPermission, PermissionState>` 上不可区分。
 * 少了这一位，冷启动的第一帧会把"还不知道"渲染成"一切正常" —— 即 D14 里
 * 明令禁止的**谎称已授予**。
 */
data class TriggerPermissionStatus(
    val permission: AndroidPermission?,
    val grant: PermissionGrant?,
    val target: SettingsTarget?,
    val loaded: Boolean,
)

/**
 * chip 的可用性结论（**不是布尔**：三态 + 未知，与 `PermissionGrant` 同款纪律）。
 *
 * 「该 API 档位下不存在此权限」与「有该权限但被用户拒绝」是**完全不同**的两件事：
 * 前者**无处可授**（跳设置页没有意义），后者必须给跳转。
 */
enum class TriggerChipAccess {
    /** 依赖的权限已授予，或该事件与权限无关。 */
    OK,

    /** 未授予，但**存在**授权路径 ⇒ ⚠ + 「去授权」。 */
    NEEDS_PERMISSION,

    /** 该 API 档位下权限不适用（如 API 26–28 没有"使用情况访问"设置页）⇒ ⚠ + 说明，**不给跳转**。 */
    NOT_APPLICABLE,

    /** 还没探测到 ⇒ ⚠ + 「权限状态未知」（**不谎称已授予**）。 */
    UNKNOWN,
}

// ---------------------------------------------------------------------------⑤ 编辑器状态 + 投影

/**
 * 编辑器的触发器区状态（**全部可观察状态**，单一不可变值）。
 *
 * @property rows 库里该脚本的全部触发器；`null` = **还没读到第一帧**
 *   （与"确实一条都没有"必须可区分 —— 与 [TriggerCounts] 的三态同源）
 * @property drafts 参数草稿：`triggerId → 草稿`。**只在参数区被编辑过之后存在**；
 *   写库成功后**保留**（用户的输入不因一次写盘就消失），下次展开仍是它
 * @property expanded 正在展开参数区的触发器 id（可多个：用户可能想对照两个事件的参数）
 * @property permissions 权限快照（`PermissionStatusProvider.current()` 的原样）
 * @property permissionLoaded 权限快照是否已成功读过一次
 * @property paramsNotice 参数区顶部的一条提示（换行被剔除等；`null` = 无）
 * @property canEdit `false` = 新建脚本（还没有 `scriptId`，触发器无处挂）
 */
data class TriggerEditorUiState(
    val rows: List<TriggerRowUi>?,
    val drafts: Map<Long, TriggerParamsDraft>,
    val expanded: Set<Long>,
    val permissions: Map<AndroidPermission, PermissionGrant>,
    val permissionLoaded: Boolean,
    val busy: Boolean,
    val paramsNotice: String?,
    val canEdit: Boolean,
) {
    /** 是否已读到第一帧（`rows != null`）。 */
    val loaded: Boolean get() = rows != null

    /** 已选中的事件键集合（供 chip 选中态；未知键也保留 —— 库里有什么就显示什么）。 */
    val selected: Set<String> get() = rows?.map { it.eventType }?.toSet() ?: emptySet()

    /** 库里有多少行（`null` = 未知，UI 显示 `…`）。 */
    val count: Int? get() = rows?.size

    /** 取某事件的库内行（重复行时取第一条 —— 与 [TriggerSelectionDiff] 的保留策略一致）。 */
    fun rowOf(eventType: String): TriggerRowUi? = rows?.firstOrNull { it.eventType == eventType }

    /** 取某行的编辑草稿（没有草稿则用库里的值反推 ⇒ 参数区展开即所见即所得）。 */
    fun draftOf(row: TriggerRowUi): TriggerParamsDraft = drafts[row.id] ?: TriggerParamsDraft.of(row.params)

    /** chip 的可用性（三态 + 未知，见 [TriggerChipAccess]）。 */
    fun accessOf(eventType: String): TriggerChipAccess {
        val spec = EventCatalog.spec(eventType) ?: return TriggerChipAccess.OK
        val permission = spec.permission ?: return TriggerChipAccess.OK
        if (!permissionLoaded) return TriggerChipAccess.UNKNOWN
        return when (permissions[permission]) {
            PermissionGrant.GRANTED -> TriggerChipAccess.OK
            PermissionGrant.DENIED -> TriggerChipAccess.NEEDS_PERMISSION
            PermissionGrant.NOT_APPLICABLE -> TriggerChipAccess.NOT_APPLICABLE
            // 目录里缺这一项（探测异常）⇒ 与"没探测过"同档，绝不谎称已授予
            null -> TriggerChipAccess.UNKNOWN
        }
    }

    /** 某事件的权限联合（参数区提示条用）。 */
    fun permissionOf(eventType: String): TriggerPermissionStatus {
        val spec = EventCatalog.spec(eventType) ?: return TriggerPermissionStatus(null, null, null, true)
        val permission = spec.permission ?: return TriggerPermissionStatus(null, null, null, true)
        return TriggerPermissionStatus(
            permission = permission,
            grant = permissions[permission],
            target = null,
            loaded = permissionLoaded,
        )
    }

    companion object {
        /** 冷启动：还没读到 Room 的第一帧。 */
        val Initial: TriggerEditorUiState =
            TriggerEditorUiState(
                rows = null,
                drafts = emptyMap(),
                expanded = emptySet(),
                permissions = emptyMap(),
                permissionLoaded = false,
                busy = false,
                paramsNotice = null,
                canEdit = false,
            )
    }
}

/**
 * 触发器区的**纯投影函数**：状态 → 文案。
 *
 * 全部放在这里而不是 Composable 内（理由见本文件顶部）。所有用户可见文案都是
 * **稳定字符串**：真机判读靠 `findstr` 它们，随手改字会让验证命令失效
 * （与 `TabContent.logKey` / `ScriptProjections` 同一条纪律）。
 */
object TriggerEditorProjections {
    /** 计数未知时的占位（与 [ScriptProjections.COUNT_UNKNOWN] 同值，避免两处漂移）。 */
    const val COUNT_UNKNOWN: String = ScriptProjections.COUNT_UNKNOWN

    /** 未选事件 chip 的标签后缀（未授权时）。 */
    const val WARN_SUFFIX: String = " ⚠"

    /** 参数区的固定说明（D14 的缓解措施之一，也是"立即保存"语义的告知）。 */
    const val PARAMS_FOOTNOTE: String = "触发器的修改会立即保存。"

    /**
     * 安全熔断语义的固定说明（**P8 修正**；6b 项 2 的教训，见 [TriggerRowUi] 的 KDoc）。
     *
     * ## 原文案说的是一个**不存在的动作**
     * 旧值："安全熔断会临时禁用全部触发器；恢复时按其熔断前状态还原。"
     * 而熔断改成**内存级拦截**后，`CircuitBreakerImpl.disableAllTriggers()` 与
     * `restoreTriggers()` **都是空实现** —— 它从不改动用户的订阅。
     * 这句话会让用户以为自己的配置被改过又还原了，与 P7 修掉的
     * `RESTORE_CHOICES` / `restore()` 是同一类"文案说谎"。
     */
    const val SAFE_MODE_FOOTNOTE: String = "安全熔断期间事件不再投递，但你的订阅不会被改动。"

    /** 新建脚本时的禁用说明（触发器必须有 `scriptId` 才能落库）。 */
    const val NEW_SCRIPT_NOTICE: String = "先保存脚本，再配置触发器"

    /** 被剔除换行时的一次性提示（**必须说出来**，不静默改用户输入）。 */
    const val PAYLOAD_NEWLINE_STRIPPED: String = "负载中的换行已移除：POSIX 单引号串无法表示换行，注入会被运行时拒绝"

    /** 空 payload 的语义说明。 */
    const val PAYLOAD_HINT: String = "空 = 使用事件自带负载（若该事件没有则展开为空串）"

    /** 没有专属参数的事件，参数区那行字。 */
    const val NO_EVENT_PARAMS: String = "该事件没有专属参数"

    /** `time` 未填时刻时的提醒（**警告不阻断**：用户可以先把 chip 选上再配）。 */
    const val TIME_INCOMPLETE: String = "未填「时」「分」时该触发器不会按时刻触发"

    /** `interval` 未填间隔时的提醒。 */
    const val INTERVAL_INCOMPLETE: String = "未填间隔时该触发器不会触发"

    /**
     * 参数摘要（选中行的第二行）。
     *
     * 六种形态，**互斥且穷举**；未知事件键回落"无参数"（不显示空白）。
     */
    fun summary(
        eventType: String,
        params: TriggerParams,
    ): String =
        when (eventType) {
            SystemEvent.TIME -> {
                val hour = params.hourOfDay
                val minute = params.minuteOfHour
                if (hour == null || minute == null) {
                    "每天 ——（未配置时刻）"
                } else {
                    "每天 " + two(hour) + ":" + two(minute) + if (params.exact) "（精确）" else ""
                }
            }

            SystemEvent.INTERVAL -> {
                val interval = params.intervalMinutes
                if (interval == null) "间隔 ——（未配置）" else "每 $interval 分钟"
            }

            // 包名筛选本阶段不做（TriggerParams 没有对应字段），因此不在这里编一个摘要
            SystemEvent.APP_FOREGROUND, SystemEvent.APP_BACKGROUND -> "全部应用"

            else -> if (params.payload != null) "自定义负载" else "无参数"
        }

    /** chip 的计数行：`触发器（N/14）`；未知时 `触发器（…/14）`（**不显示假 0**）。 */
    fun countLine(count: Int?): String = "触发器（${count ?: COUNT_UNKNOWN}/${EventCatalog.ALL.size}）"

    /**
     * 权限提示条的文案（`null` = 不需要提示）。
     *
     * **必须具体到权限名**（决策 7：不得只给笼统结论），且
     * 未探测到时**不得谎称已授予**。
     */
    fun permissionHint(
        spec: EventCatalog.EventSpec,
        access: TriggerChipAccess,
    ): String? {
        if (access == TriggerChipAccess.OK) return null
        val permissionName = spec.permission?.permissionLabel ?: return null
        return when (access) {
            TriggerChipAccess.OK -> null
            TriggerChipAccess.NEEDS_PERMISSION ->
                "「${spec.label}」需要「$permissionName」权限，当前未授予。配置会保存，但事件在授权前不会触发。"

            TriggerChipAccess.NOT_APPLICABLE ->
                "本系统版本不提供「$permissionName」的授权入口，该事件在当前设备上不可用。"

            TriggerChipAccess.UNKNOWN ->
                "「$permissionName」的权限状态未知（探测未完成或失败），无法断言已授予。"
        }
    }

    /** 「去授权」按钮是否可用（`NOT_APPLICABLE` / `UNKNOWN` 时无处可去 ⇒ 不给按钮）。 */
    fun canGrant(access: TriggerChipAccess): Boolean = access == TriggerChipAccess.NEEDS_PERMISSION

    /** 写库成功但清理了重复行时的提示（不静默：用户会看到"少了一行"）。 */
    fun duplicatesCleaned(count: Int): String = "已清理 $count 条重复的触发器记录"

    /** 单个事件写失败时的提示（含事件中文名 ⇒ 用户知道是哪一条）。 */
    fun saveFailed(
        label: String,
        reason: String,
    ): String = "「$label」未保存：$reason"

    /** 两位补零（`07:30` 而不是 `7:30` —— 摘要要能一眼对齐）。 */
    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

    // ---------------------------------------------------------------- P5：事件区降级为"高级、可选"

    /**
     * 事件区的折叠标题（方案 §5.2）。
     *
     * ## 为什么要有"（高级，可选）"这四个字
     * 方案对它的定位是：事件**不再是"启动脚本的理由"**，而是**投递给常驻脚本的通知** ——
     * 一个只写"while true; do …; done"的用户**根本不需要订阅任何事件**。
     * 标题里写明"高级、可选"，新用户才不会以为"没勾事件 = 脚本不会跑"。
     */
    const val EVENTS_SECTION_TITLE: String = "事件通知（高级，可选）"

    /** 展开后的第一行：说清这些勾选**做什么用**。 */
    const val EVENTS_SECTION_HINT: String = "常驻运行时会把这些事件投给脚本："

    /**
     * 取值方式的提示（P5 起事件经 FIFO 投递）。
     *
     * 折叠起来之后用户看不到 chip，但**展开时**必须能一眼知道脚本那侧怎么写 ——
     * 否则"配了事件却没反应"的第一反应会是"这个功能坏了"。
     *
     * `$ROOTFLOW_EVENT_FIFO` 在 Kotlin 里要转义 `\$`（否则会被当成模板插值）。
     */
    const val EVENTS_READ_HINT: String = "脚本里用 read -r ev < \$ROOTFLOW_EVENT_FIFO 取事件"

    /**
     * 折叠状态下标题右侧的摘要（**收起也能看到概况**）。
     *
     * @param subscribed 已订阅的事件数；`null` = **计数未知**（三态，不编 `0`）
     */
    fun eventsSectionSummary(subscribed: Int?): String =
        when (subscribed) {
            null -> "…"
            0 -> "未订阅"
            1 -> "已订阅 1 个"
            else -> "已订阅 $subscribed 个"
        }
}
