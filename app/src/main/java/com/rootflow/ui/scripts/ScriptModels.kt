package com.rootflow.ui.scripts

import com.rootflow.domain.model.Script
import com.rootflow.ui.master.ScriptRuntimeProjections
import com.rootflow.ui.master.ScriptRuntimeState

/*
 * 配置页的**纯数据模型 + 纯函数**（阶段 6c）。
 *
 * ## 为什么单独一个文件、且全部是纯函数
 * 本项目**没有 UI 自动化测试**（已批准决策 B：`ui-test-*` / `androidTest` / espresso
 * 全不在离线缓存），Composable 在纯 JVM 下不可测。因此"这一行该显示什么""这个输入算不算
 * 合法"这类判定必须**上移到纯函数**，否则它们的覆盖率是 0。
 * 与 6b 的 `ui/home/HomeModels.kt` 同款动机（那是本仓库先例）。
 *
 * 本文件不认识 `ViewModel`，也不认识 Compose —— 它只吃 `domain` 的 `Script`。
 *
 * 用块注释而非 KDoc：ktlint 的 `kdoc` 规则不允许"一个 KDoc 紧邻另一个 KDoc"
 * （本文件顶部若写成 KDoc，就会与下方 `TriggerCounts` 的 KDoc 相撞）。
 */

/**
 * 触发器计数的**三态**载体（`STAGE6C-PLAN.md §0.3 ①`）。
 *
 * ## 为什么不是一个 `Map<Long, Int>`
 * `scripts`（Room 热流）与计数快照（一次性查询）**到达时间不同**。若直接
 * `byId[id] ?: 0`，第一帧会把"**还不知道**"渲染成"**0 条触发器**" —— 那是**假信息**
 * （用户可能真有 3 条）。本仓库禁止"把未知显示成已知"。
 *
 * 因此计数必须带一个"快照到没到"的位：[loaded]。
 *
 * @property byId 脚本 id → 触发器条数。**只反映已成功读到的快照**
 * @property loaded 快照是否已经**成功**读过一次。
 *   - `false`：未知 ⇒ 行内显示 `…`，删除确认显示"（触发器数未知）"
 *   - `true`：下结论 ⇒ 在 [byId] 里就是真实条数，不在就是 0 条
 */
data class TriggerCounts(
    val byId: Map<Long, Int>,
    val loaded: Boolean,
) {
    companion object {
        /** 冷启动：还没读过任何快照 ⇒ 一律"未知"。 */
        val Unknown: TriggerCounts = TriggerCounts(byId = emptyMap(), loaded = false)
    }
}

/**
 * 列表页一行的**全部**显示信息（不可变投影，供纯 JVM 单测穷举）。
 *
 * @property id 脚本 id
 * @property name 主行文案；**空名回落 `#<id>`**（绝不显示空白行）
 * @property language `"shell"` / `"lua"` 原样透出（`lua` 由 UI 灰显，本类不做样式）
 * @property enabled 启用开关的当前位置
 * @property timeoutSec 超时的原始秒数（`0` = 用全局默认 60s）
 * @property triggerCount 触发器条数；`null` = **未知**（见 [TriggerCounts]）
 * @property deletable 长按是否允许删除（`lua` 行仍可删 —— 是否能删与语言无关）
 */
data class ScriptRowUi(
    val id: Long,
    val name: String,
    val language: String,
    val enabled: Boolean,
    val timeoutSec: Int,
    val triggerCount: Int?,
    val deletable: Boolean,
    /** 运行方式（`scripts.resident`）：`true` = 常驻（服务起来就一直跑）。 */
    val resident: Boolean,
    /** 总闸当前是否打开（决定 [pausedByMaster] 与 [statusLabel] 的措辞）。 */
    val masterEnabled: Boolean,
    /** 运行态快照（此刻是否在跑 / 是否已放弃重启）。 */
    val runtime: ScriptRuntimeState,
) {
    /** 名称的展示形态（空名回落 `#<id>` 是**纯函数**，可被单测钉死）。 */
    val displayName: String
        get() = ScriptProjections.displayName(id = id, name = name)

    /** 语言 chip 是否可编辑（v1 只实现 shell，需求 §3.1 + §10）。 */
    val languageEditable: Boolean
        get() = ScriptProjections.isLanguageEditable(language)

    /** 超时文案（`0` ⇒ `默认 60s`，见 [ScriptProjections.timeoutLabel]）。 */
    val timeoutLabel: String
        get() = ScriptProjections.timeoutLabel(timeoutSec)

    /**
     * 触发器计数文案（三态，见 [TriggerCounts]）。
     *
     * `null`（未知）与 `0`（真的没有）必须是**不同**的字符串 —— 这正是 §0.3 ① 的要求。
     */
    val triggerSummary: String
        get() = ScriptProjections.triggerSummary(triggerCount)

    /** 删除确认里"将一并删除 N 条触发器"那一句；未知或 0 时**不编数字**。 */
    val deleteTriggerNotice: String
        get() = ScriptProjections.deleteTriggerNotice(triggerCount)

    /** 运行方式标签（`常驻` / `单次`）—— 用户可见的稳定字符串。 */
    val shapeLabel: String
        get() = if (resident) "常驻" else "单次"

    /**
     * 状态短句（`常驻 · 运行中` / `单次 · 未启用` / `常驻 · 已暂停` …）。
     *
     * 由 [ScriptRuntimeProjections.statusText] 算出来 —— 纯函数，可被单测穷举。
     */
    val statusLabel: String
        get() = ScriptRuntimeProjections.statusText(enabled, resident, masterEnabled, runtime)

    /**
     * 是否标注"被总开关暂停"。
     *
     * 方案 §5.1 的硬要求：总闸关闭时**列表仍可操作**，只标注暂停中 —— 因此这是一个
     * **附加**位，不替换 [statusLabel] 自身携带的信息（"脚本自己的开关还是开着的"）。
     */
    val pausedByMaster: Boolean
        get() = ScriptRuntimeProjections.pausedByMaster(enabled, masterEnabled)
}

/** 列表页的**全部**可观察状态（单一不可变值，同 6b 的 `HomeUiState`）。 */
data class ScriptListUiState(
    val rows: List<ScriptRowUi>,
    val loaded: Boolean,
    val busy: Boolean,
) {
    /** 是否为空列表（**只有加载完成后**才敢说"还没有脚本"）。 */
    val empty: Boolean
        get() = loaded && rows.isEmpty()

    companion object {
        /** 冷启动：还没读到 Room 的第一帧。 */
        val Initial: ScriptListUiState = ScriptListUiState(rows = emptyList(), loaded = false, busy = false)
    }
}

/**
 * 表单字段级校验结果（纯数据）。
 *
 * @property field 出错的字段（UI 用它决定把错误落在哪个输入框下面）
 * @property message 用户可见文案（**稳定字符串**，由单测钉死）
 * @property blocking 是否阻断保存。当前所有校验都是阻断的；保留该位是为了让
 *   "只提示不阻断"（如重名）将来能复用同一套结果类型而不必改调用方
 */
data class ScriptFormError(
    val field: ScriptFormField,
    val message: String,
    val blocking: Boolean,
)

/** 表单里可能出错的字段。 */
enum class ScriptFormField {
    NAME,
    BODY,
    TIMEOUT,
    LANGUAGE,
}

/**
 * 编辑器表单的**唯一可写状态**（不可变；`dirty` 由与初值比较得出，见 [ScriptForm.isDirtyAgainst]）。
 *
 * ## 为什么 `dirty` 是算出来的，而不是一个被维护的布尔
 * "改一个字再改回来"必须回到 `dirty = false`。用一个随每次 `onChange` 置位的布尔，
 * 用户就永远退不出"未保存"状态 —— 预测性返回会一直弹确认框，
 * 用户会以为 App 卡了（`STAGE6C-PLAN.md §3.4` 明写这一条是坏的）。
 *
 * @property id `0` = 新建
 */
data class ScriptForm(
    val id: Long,
    val name: String,
    val language: String,
    val enabled: Boolean,
    val timeoutSec: String,
    /**
     * 连续失败自动禁用（**P9 起没有 UI 入口，且从未生效过**）。
     *
     * ## 为什么不删这个字段
     * 旧数据里有它（`scripts` 表的既有列）。删列要付一次迁移的代价，
     * 而"移除一个从未生效的开关"不值得付。字段继续被如实搬运（读出来 + 原样写回），
     * 只是不再有入口，因此它**不会被用户改成 `true`**，也不会被无端清成 `false`。
     *
     * ## 为什么不去实现它（**不是"没时间"**）
     * 需求给它的阈值是"连续失败 ≥ 3"（`REQUIREMENTS.md:205`），而熔断的触发条件
     * （同文件 §5.1）是"同一脚本连续失败 ≥ 3 次" —— **完全相同**
     * ⇒ 全局熔断（停所有脚本 + 进安全模式）必然抢先触发 ⇒ 那段代码**永不可达**。
     * 要让它有意义，得先改需求（阈值小于熔断，或改熔断规则），那是产品决策。
     */
    val autoDisableOnFail: Boolean,
    val runOnSafeMode: Boolean,
    /**
     * 运行方式（`scripts.resident`）——**P4 新增的 UI 入口**。
     *
     * `true` = 常驻（服务起来就一直跑，退出自动拉起）；`false` = 单次（跑一遍）。
     *
     * ## 为什么它必须在本轮出现在 UI 上
     * 该字段是总开关重构带来的（P2 落库、P3 接线），但在 P4 之前**没有任何 UI 入口**。
     * 少了它，用户根本接触不到"常驻 / 单次"这个概念，而总开关拨开后什么都不会跑
     * （迁移后的老脚本 `resident = 0`）—— 总开关就成了一个**空开关**。
     */
    val resident: Boolean,
    val content: String,
) {
    /**
     * 与另一个表单是否**逐字段相同**。
     *
     * 只服务于一件事：`dirty = form != baseline`（见 [ScriptEditorUiState] 的 KDoc）。
     * 之所以是个显式方法而不是让调用方写 `==`：字节码上二者等价，
     * 但"这里在做的是未保存改动判定"这件事必须写在名字里 —— 它决定了预测性返回的行为。
     */
    fun sameAs(other: ScriptForm): Boolean = this == other

    companion object {
        /**
         * 新建时的初值（`enabled = true`：新建脚本的意图就是让它生效）。
         *
         * `resident = true`：方案 §7 决策 4（用户已确认按建议执行）——
         * **新脚本默认常驻**。理由：常驻是总开关语义（事件=投递给正在运行的脚本）
         * 唯一有意义的形态；把默认设成单次会让"新建一个脚本、拨开总闸、什么都不发生"
         * 成为新用户的第一次体验。
         */
        val New: ScriptForm =
            ScriptForm(
                id = 0L,
                name = "",
                language = ScriptProjections.SHELL_LANGUAGE,
                enabled = true,
                timeoutSec = "0",
                autoDisableOnFail = false,
                runOnSafeMode = false,
                resident = true,
                content = "",
            )

        /** 由已落库的 [Script] 生成表单（编辑路径的唯一入口）。 */
        fun of(script: Script): ScriptForm =
            ScriptForm(
                id = script.id,
                name = script.name,
                language = script.language,
                enabled = script.enabled,
                timeoutSec = script.timeoutSec.toString(),
                autoDisableOnFail = script.autoDisableOnFail,
                runOnSafeMode = script.runOnSafeMode,
                resident = script.resident,
                content = script.content,
            )
    }
}

/**
 * 编辑器的**全部**可观察状态。
 *
 * ## 为什么 `loadError` 是**四值枚举**而不是 `String?`（方案 §3.1）
 * `ScriptLoadResult` 的四种失败（`Missing` / `Corrupted` / `NotFound` / `Unavailable`）
 * 排查方向**完全不同**（文件被删 / 被外部改 / 元数据不在 / root 通道不可用）。
 * 折叠成一句"加载失败"会让真机排障从一次 `findstr` 变成一轮猜测。
 */
data class ScriptEditorUiState(
    val form: ScriptForm,
    val loading: Boolean,
    val saving: Boolean,
    val loadError: ScriptLoadFailure?,
    val errors: List<ScriptFormError>,
    val dirty: Boolean,
    val isNew: Boolean,
    /**
     * 非 `null` ⇒ 正在等用户确认"这份脚本里有危险指令"（用户需求，2026-09-25）。
     *
     * ## 它不参与 [canSave]
     * 弹窗本身就是"保存被挂起"的表达：置上它之后保存**不再被重复触发**，
     * 用户点「继续保存」才会真正落库。把它塞进 `canSave` 会让保存按钮在弹窗期间变灰，
     * 而那时用户看的是弹窗、不是按钮，纯属多余的状态。
     */
    val pendingDanger: ScriptDangerPrompt? = null,
) {
    /** 阻断保存的第一条错误（UI 的 Snackbar 文案）。 */
    val blockingError: ScriptFormError?
        get() = errors.firstOrNull { it.blocking }

    /** 当前是否可以保存（供 FAB / 按钮的 enabled）。 */
    val canSave: Boolean
        get() = !loading && !saving && loadError == null

    /**
     * 只读触发器摘要行（`STAGE6C-PLAN.md §3.2`）。
     *
     * 触发器勾选推 6e（已批准决策 1），6c 只显示这一行 + 一句"下一批提供"。
     *
     * @param total 触发器总数；`null` = 还没读到 ⇒ 说"未知"，**不编 0**
     */
    fun triggerSummaryLine(total: Int?): String =
        when (total) {
            null -> "触发器：$COUNT_UNKNOWN"
            0 -> "触发器：未配置"
            else -> "触发器：$total 个"
        }

    private companion object {
        /** 与 [ScriptProjections.COUNT_UNKNOWN] 同值（同一份"未知"占位，避免两处漂移）。 */
        const val COUNT_UNKNOWN: String = ScriptProjections.COUNT_UNKNOWN
    }
}

/**
 * "这份脚本里有危险指令"的待确认提示（用户需求，2026-09-25）。
 *
 * ## 它携带的是**扫描结果**，不是"要不要保存"的意图
 * 点「继续保存」之后走的是**同一条 `save()` 路径**（只是跳过这道闸门），
 * 因此这里不需要记住任何保存参数 —— 表单始终是唯一真相源。
 * 把它设计成"请求对象"会让"用户在弹窗里改了表单"这类不存在的情况看起来可能发生。
 *
 * @property findings 命中的全部危险指令（**至少一条**；空列表不该构造出本对象）
 */
data class ScriptDangerPrompt(
    val findings: List<ScriptSafetyScan.DangerFinding>,
) {
    /** 最高严重度（决定弹窗标题措辞：真要命的用"高危"，其余用"需留意"）。 */
    val highest: ScriptSafetyScan.DangerSeverity
        get() = findings.maxByOrNull { it.severity.ordinal }?.severity ?: ScriptSafetyScan.DangerSeverity.MEDIUM

    /** 命中的**行号**升序列表（弹窗里逐行列出，用户能直接定位）。 */
    val lines: List<Int>
        get() = findings.map { it.lineNumber }.distinct().sorted()
}

/**
 * 危险指令弹窗的**文案投影**（纯函数，用户需求 2026-09-25）。
 *
 * ## 为什么文案不写在 Composable 里
 * 本仓库**没有 UI 测试**（决策 B：`ui-test-*` / espresso 全不在离线缓存）
 * ⇒ 写在 Composable 里的分支**覆盖率恒为 0**。而这里的分支不是装饰：
 * 标题按严重度分两档、正文要列出命中位置、命中过多时要折叠 ——
 * 每一条错了都会被用户看成一次误报或一次漏报说明。
 * 搬到纯函数里之后，全部可被 `ScriptDangerProjectionsTest` 穷举。
 *
 * ## 两档标题的用词
 * 「高危」与「需留意」不是同义修辞：前者对应**不可逆**的破坏（格盘、覆写块设备、删根），
 * 后者对应"大概率会后悔"。用同一个标题会让用户对两类命中产生同一种反应，
 * 而 `ScriptSafetyScan` 分两档的全部意义就是让它们被区别对待。
 */
object ScriptDangerProjections {
    /**
     * 正文里**最多列出**几条命中。
     *
     * 取值理由：弹窗正文超过三屏就没人读了，而用户此刻需要的是"知道有危险、并能定位"，
     * 不是逐条审计。取 3 条 + 一行"另有 N 处"；完整命中始终能在正文里直接看到
     * （扫描器是按行匹配的，用户按行号一比就对上）。
     */
    const val MAX_LISTED: Int = 3

    /** 高危命中的标题。 */
    const val TITLE_HIGH: String = "脚本包含高危指令"

    /** 中危命中的标题。 */
    const val TITLE_MEDIUM: String = "脚本包含需要留意的指令"

    /** 「继续保存」——**动词短语**，不是"确定"（见下）。 */
    const val CONFIRM_LABEL: String = "继续保存"

    /** 「让我再想想」——用户需求的原话，保留。 */
    const val DISMISS_LABEL: String = "让我再想想"

    /**
     * 标题。
     *
     * 用**动词/结果**措辞而不是"警告/错误"：用户此刻要做的判断是
     * "我要不要把它存下去"，标题应当直接指向那个动作的后果。
     */
    fun title(highest: ScriptSafetyScan.DangerSeverity): String =
        if (highest == ScriptSafetyScan.DangerSeverity.HIGH) TITLE_HIGH else TITLE_MEDIUM

    /**
     * 正文：逐条列出"第几行 · 为什么危险"，并附上该行原文。
     *
     * ## 为什么要带原文摘录
     * 只说"第 3 行有危险指令"会让用户来回翻找；而脚本里同一行号在编辑与保存之间
     * **不会变**（扫描发生在保存那一刻的正文上），所以"行号 + 原文"是精确指认。
     * 摘录已由扫描器截断（`ScriptSafetyScan.EXCERPT_MAX`），不会把一整段 base64 塞进弹窗。
     *
     * @param prompt 至少一条命中（空列表不应构造出该对象，见其 KDoc）
     */
    fun body(prompt: ScriptDangerPrompt): String {
        val listed = prompt.findings.take(MAX_LISTED)
        val text =
            listed.joinToString(separator = "\n\n") { finding ->
                "第 ${finding.lineNumber} 行 · ${finding.reason}\n    ${finding.excerpt}"
            }
        val remaining = prompt.findings.size - listed.size
        return if (remaining > 0) "$text\n\n…另有 $remaining 处未展开" else text
    }

    /**
     * 弹窗下方的**一句说明**（按钮之上的那行小字）。
     *
     * ## 它必须说清"这个弹窗不是安全承诺"
     * `ScriptSafetyScan` 是**文本模式匹配**：变量拼装、动态生成的命令它抓不到
     * （已批准的范围取舍，见其类 KDoc）。若这里不写，用户会把它读成"扫描过了 = 安全"，
     * 而下一次真正的危险正好落在漏报里时，这个弹窗就成了**误导**。
     */
    fun footnote(): String = "这是文本匹配检查，只能认出常见写法；它不影响你保存，也不会执行任何东西。"
}

/**
 * 编辑器加载失败的四种形态（各有一条独立文案，见 [ScriptEditorUiState] 的 KDoc）。
 *
 * ## 为什么文案里**不含** `Unavailable` 的 `reason`
 * `reason` 逐次不同（`cat failed (exit=1): …`），把它拼进文案会让真机 `findstr`
 * 失去稳定的搜索目标。原始 reason 走 `onWarning` 缝（真机日志）与 Snackbar（用户可见）。
 */
enum class ScriptLoadFailure {
    MISSING,
    CORRUPTED,
    NOT_FOUND,
    UNAVAILABLE,
    ;

    /** 用户可见文案（**稳定字符串**，逐条不同 —— 见 [ScriptEditorUiState] 的 KDoc）。 */
    val message: String
        get() =
            when (this) {
                MISSING -> "正文文件不存在（元数据在库中）"
                CORRUPTED -> "正文与记录摘要不符（文件被外部改动）"
                NOT_FOUND -> "脚本元数据不存在"
                UNAVAILABLE -> "root 通道不可用"
            }
}

/**
 * 编辑器路由参数的解析结果（`STAGE6C-PLAN.md §4`）。
 *
 * @property scriptId `null` = 新建；非空 = 编辑该 id
 * @property invalid 参数**存在但不可解析**（`"abc"` / `"-1"` / `"1.5"` / `"0"`）。
 *   UI 据此提示"路由参数无效，已按新建打开"，而**不是**静默地当没事发生
 */
data class ScriptEditorKey(
    val scriptId: Long?,
    val invalid: Boolean,
)

/**
 * 路由参数 → 编辑器入口（**纯函数**）。
 *
 * ## 契约（方案 §4 原文）
 * | 输入 | 结果 |
 * |---|---|
 * | `null` / 空白 | 新建，`invalid = false`（这是"新建"这条路由的正常形态） |
 * | `"7"` | 编辑 7 |
 * | `"abc"` / `"-1"` / `"1.5"` / `"0"` | **回落新建**且 `invalid = true`（**不崩**） |
 *
 * ## 为什么 `"0"` 也算无效（方案未列，实现时定的判据）
 * `Script.id = 0` 是 `domain` 的**哨兵值**："尚未入库"（见 `Script` 的 KDoc，
 * `ScriptRepository.save` 正是按 `id == 0L` 分派 insert / update）。
 * 若把路由里的 `"0"` 当成"编辑 0 号脚本"，`load(0)` 只会得到 `NotFound`，
 * 于是用户看到"脚本元数据不存在"——**一个由路由解析错误伪装成的数据丢失**。
 * 明确判为无效参数，提示的成因才是对的。
 */
object ScriptEditorArgs {
    /** 路由里"新建"这条路径的固定段（与 `ScriptsTab` 的 `route` 常量同源）。 */
    const val NEW_KEY: String = "new"

    fun parse(idArg: String?): ScriptEditorKey {
        val raw = idArg?.trim()
        if (raw.isNullOrEmpty() || raw == NEW_KEY) return ScriptEditorKey(scriptId = null, invalid = false)
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed <= 0L) return ScriptEditorKey(scriptId = null, invalid = true)
        return ScriptEditorKey(scriptId = parsed, invalid = false)
    }
}

/**
 * 配置页的**纯投影函数**（阶段 6c）：`domain` 值 → UI 文案。
 *
 * 全部放在这里而不是 Composable 内，理由见本文件顶部 KDoc。
 */
object ScriptProjections {
    /** v1 唯一实现的语言（需求 §3.1 + §10）。 */
    const val SHELL_LANGUAGE: String = "shell"

    /** `timeoutSec == 0` 的提示文案（需求 §5.1：全局默认 60s）。 */
    const val DEFAULT_TIMEOUT_LABEL: String = "默认 60s"

    /** 计数未知时的占位（**不是** `0`，见 [TriggerCounts]）。 */
    const val COUNT_UNKNOWN: String = "…"

    /**
     * 名称的展示形态：trim 后为空则回落 `#<id>`。
     *
     * 为什么不显示空串：列表行会变成一条"没有名字的灰条"，用户无法判断那是脚本还是渲染错误。
     */
    fun displayName(
        id: Long,
        name: String,
    ): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) "#$id" else trimmed
    }

    /** 语言是否可编辑（v1 只有 shell）。 */
    fun isLanguageEditable(language: String): Boolean = language.equals(SHELL_LANGUAGE, ignoreCase = true)

    /**
     * 超时字段的展示文案。
     *
     * ## 为什么 `0` 不显示成"0"或"不限"
     * 需求 §5.1 规定 `timeoutSec = 0` 的语义是"**用全局默认**（60s）"，
     * 而需求 §3.3 的字段注释恰好也写着 "0 = 不限" —— 两者冲突时以实现为准：
     * 阶段 4/5 的超时判定走的就是"0 回落 60s"。
     * 显示裸 `0` 会被用户读成"不限"，而实际会在 60 秒被熔断 —— 那是**误导性文案**。
     */
    fun timeoutLabel(timeoutSec: Int): String = if (timeoutSec == 0) DEFAULT_TIMEOUT_LABEL else "${timeoutSec}s"

    /**
     * 触发器计数文案（三态）。
     *
     * @param count `null` = 未知（快照未到或该 id 不在快照里）
     */
    fun triggerSummary(count: Int?): String =
        when (count) {
            null -> COUNT_UNKNOWN
            0 -> "未配置触发器"
            else -> "$count 个触发器"
        }

    /**
     * 删除确认里的"将一并删除 N 条触发器"。
     *
     * `null`（未知）时说"触发器数未知"，**不编 0** —— 说 0 会让用户以为删了没影响。
     */
    fun deleteTriggerNotice(count: Int?): String =
        when (count) {
            null -> "（触发器数未知）"
            0 -> ""
            else -> "将一并删除 $count 条触发器"
        }

    /**
     * 删除返回 `Failed` 时的 Snackbar 文案（`STAGE6C-PLAN.md §0.3 ②`）。
     *
     * ## ★ 为什么必须写成"部分成功"
     * `ScriptRepository.delete` 的语义是：**先删元数据行**（`ON DELETE CASCADE`
     * 一并删触发器），再删正文目录；**文件删除失败不阻塞行删除**，但会返回 `Failed`
     * 以便告警。⇒ 拿到 `Failed` 时 **Room 里那一行已经没了**，而列表是 `observeAll`
     * 的热流，Snackbar 弹出时行**已经消失**。
     *
     * 若文案写成"删除失败"，用户会以为行还在、反复重删 —— 而第二次删的是一行不存在的记录。
     */
    fun deletePartialSuccess(reason: String): String = "脚本已删除，但正文文件清理失败：$reason（可在设置页清理残留）"

    /** 列表行由 [Script] + 计数 + **总闸 + 运行态**投影而来（`count == null` 即未知）。 */
    fun row(
        script: Script,
        count: Int?,
        masterEnabled: Boolean = true,
        runtime: ScriptRuntimeState = ScriptRuntimeState.Unknown,
    ): ScriptRowUi =
        ScriptRowUi(
            id = script.id,
            name = script.name,
            language = script.language,
            enabled = script.enabled,
            timeoutSec = script.timeoutSec,
            triggerCount = count,
            deletable = true,
            resident = script.resident,
            masterEnabled = masterEnabled,
            runtime = runtime,
        )

    /** 删除确认框的标题。 */
    fun deleteDialogTitle(): String = "删除脚本？"

    /** 删除确认框的正文（名称 + 可选的一并删除提示）。 */
    fun deleteDialogBody(
        displayName: String,
        triggerCount: Int?,
    ): String {
        val notice = deleteTriggerNotice(triggerCount)
        return if (notice.isEmpty()) "将删除「$displayName」。" else "将删除「$displayName」。$notice"
    }

    /**
     * 计数快照刷新失败时的提示。
     *
     * **两种失败不同**：首次失败 ⇒ 全部显示"未知"；已有旧值 ⇒ 保留旧值。
     * 清空旧值会把"已知"变成"未知"，那是**主动丢信息**。
     */
    fun countsUnavailable(hadSnapshot: Boolean): String =
        if (hadSnapshot) {
            "触发器计数刷新失败，显示的是上次结果"
        } else {
            "触发器计数不可用"
        }
}

/**
 * 表单校验（**纯函数**；`STAGE6C-PLAN.md §3.2/§3.3` 的落地）。
 *
 * ## 校验只在保存时跑，不在每次按键时跑
 * 每按一个字符就报"名称不能为空"是"边打字边骂人"。UI 在 [validate] 被调用（保存）
 * 之后才把 [ScriptFormError] 显示出来。
 *
 * ## 错误文案是**稳定契约**
 * 它们会被单测逐字钉死（`ScriptFormValidationTest`）：真机排障靠 `findstr` 这些文案，
 * 随手改一个字就会让验证命令失效（与 `TabDestination.logKey` 同一条理由）。
 */
object ScriptFormValidation {
    /** 名称/正文各自的长度上限（防御性；不是需求条款，故只做宽松封顶）。 */
    const val MAX_NAME_LENGTH: Int = 120

    /** 正文长度上限（256 KB；脚本正文是 KB 级，见 `ScriptRepository` 的 KDoc）。 */
    const val MAX_BODY_LENGTH: Int = 256 * 1024

    /**
     * 校验表单。
     *
     * @return 全部错误（**不是**第一条）；空列表 = 可以保存
     */
    fun validate(form: ScriptForm): List<ScriptFormError> {
        val errors = mutableListOf<ScriptFormError>()

        when {
            form.name.isBlank() ->
                errors +=
                    ScriptFormError(
                        field = ScriptFormField.NAME,
                        message = "名称不能为空",
                        blocking = true,
                    )

            form.name.trim().length > MAX_NAME_LENGTH ->
                errors +=
                    ScriptFormError(
                        field = ScriptFormField.NAME,
                        message = "名称过长（上限 $MAX_NAME_LENGTH 字符）",
                        blocking = true,
                    )
        }

        if (form.content.isBlank()) {
            errors +=
                ScriptFormError(
                    field = ScriptFormField.BODY,
                    message = "脚本正文不能为空",
                    blocking = true,
                )
        } else if (form.content.length > MAX_BODY_LENGTH) {
            errors +=
                ScriptFormError(
                    field = ScriptFormField.BODY,
                    message = "脚本正文过长（上限 $MAX_BODY_LENGTH 字符）",
                    blocking = true,
                )
        }

        val timeout = form.timeoutSec.trim().toIntOrNull()
        if (timeout == null || timeout < 0) {
            errors +=
                ScriptFormError(
                    field = ScriptFormField.TIMEOUT,
                    message = "超时必须是 ≥ 0 的整数（0 = $DEFAULT_TIMEOUT_LABEL）",
                    blocking = true,
                )
        }

        if (!ScriptProjections.isLanguageEditable(form.language)) {
            errors +=
                ScriptFormError(
                    field = ScriptFormField.LANGUAGE,
                    message = "v1 只支持 shell 脚本",
                    blocking = true,
                )
        }

        return errors
    }

    private const val DEFAULT_TIMEOUT_LABEL: String = ScriptProjections.DEFAULT_TIMEOUT_LABEL

    /**
     * 表单 → 待落库的 [Script]。
     *
     * **只在 [validate] 通过后调用**。名称 trim（用户看不见的首尾空格不该入库，
     * 否则列表里两条"看起来一样"的脚本无法区分）。
     *
     * @param createdAt / [updatedAt] 由调用方从既有记录带过来（新建时由仓库覆盖为当前时刻）
     */
    fun toScript(
        form: ScriptForm,
        createdAt: Long,
        updatedAt: Long,
        contentSha256: String?,
    ): Script =
        Script(
            id = form.id,
            name = form.name.trim(),
            language = form.language,
            enabled = form.enabled,
            timeoutSec = form.timeoutSec.trim().toIntOrNull() ?: 0,
            autoDisableOnFail = form.autoDisableOnFail,
            runOnSafeMode = form.runOnSafeMode,
            resident = form.resident,
            content = form.content,
            contentSha256 = contentSha256,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
