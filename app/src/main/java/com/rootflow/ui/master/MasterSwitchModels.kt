package com.rootflow.ui.master

import com.rootflow.domain.model.Script
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 总开关（P4）的**纯数据模型 + 纯函数**。
 *
 * ## 为什么全部是纯函数（与 `ScriptModels` / `TriggerEditorModels` 同款理由）
 * 本项目**没有 UI 自动化测试**（决策 B：`ui-test-*` / `androidTest` / espresso 全不在离线缓存）。
 * Composable 在纯 JVM 下不可测 ⇒ "这条门控链该怎么判""这张卡该显示什么"这类判定
 * **必须上移到纯函数**，否则它们的覆盖率是 0。
 *
 * 用块注释而非 KDoc：ktlint 的 `kdoc` 规则不允许"一个 KDoc 紧邻另一个 KDoc"。
 */

// ---------------------------------------------------------------------------① 主页大开关

/**
 * 主页「总开关」卡片的一帧。
 *
 * @property scriptsEnabled 已启用（`scripts.enabled`）的脚本数
 * @property scriptsResident 其中运行方式是**常驻**的个数（总闸拨开后真正会跑的那些）
 * @property serviceRunning 前台服务是否在跑（常驻脚本的唯一所有者）
 * @property sourcesRunning / sourcesTotal 事件源 x/y（方案 §5.1 的"事件源 2/7"）
 * @property safeMode 安全模式（与总闸**正交**，但要同时显示 —— 用户看到"总闸开着却没跑"
 *   时，第一件要知道的就是它）
 */
data class MasterSwitchUi(
    val enabled: Boolean,
    val scriptsEnabled: Int,
    val scriptsResident: Int,
    val serviceRunning: Boolean,
    val sourcesRunning: Int,
    val sourcesTotal: Int,
    val safeMode: Boolean,
) {
    /** 副标题第一行：脚本统计。 */
    val scriptsLine: String
        get() = "已启用 $scriptsEnabled 个脚本 · 常驻 $scriptsResident 个"

    /**
     * 副标题第二行：服务与事件源。
     *
     * ## 为什么关闸时**不**谎报"服务未运行"
     * 总闸关闭**不停服务**（服务承载事件源与 UI 保活，停它会让界面失效）。
     * 因此这一行如实反映服务与事件源，与总闸状态**分开**呈现 ——
     * 把两件事挤进一行会让用户以为"关总闸 = 服务停了"（而那是错的）。
     */
    val serviceLine: String
        get() = (if (serviceRunning) "服务运行中" else "服务未运行") + " · 事件源 $sourcesRunning/$sourcesTotal"

    /**
     * 关闸时的一句说明（`null` = 不必显示）。
     *
     * 方案 §5.1 的硬要求：总开关关闭时**不得整块灰掉**，而是明确告诉用户"列表仍可操作"。
     */
    val offNotice: String?
        get() = if (enabled) null else "总开关关闭：脚本不会启动，但每个脚本的开关与配置都保留着，可随时修改"

    companion object {
        /**
         * 冷启动初值。
         *
         * ## 它不是"编出来的默认值"
         * `MasterSwitch` 的内存快照初值就是**关闭**（安全默认，见其 KDoc），
         * 恢复发生在启动链最前面。因此 UI 首帧若早于那一拍，"总开关关闭"恰好是
         * **当时的真相** —— 这与"服务未探测时显示探测中"是同一条纪律：
         * 显示真实状态，而不是一个体面的猜测。
         */
        val Initial: MasterSwitchUi =
            MasterSwitchUi(
                enabled = false,
                scriptsEnabled = 0,
                scriptsResident = 0,
                serviceRunning = false,
                sourcesRunning = 0,
                sourcesTotal = 0,
                safeMode = false,
            )
    }
}

// ---------------------------------------------------------------------------② 门控链

/** 门控链上某一层的判定结果。 */
enum class GateState {
    /** 这一层通过了。 */
    PASS,

    /** 这一层把运行挡住了（**结论**由此得出）。 */
    BLOCKED,

    /** 这一层不构成阻挡（例如"运行方式是单次"），但值得如实显示。 */
    INFO,

    /** 还不知道（读不到 / 未探测）——**绝不谎报成 PASS**。 */
    UNKNOWN,
}

/**
 * 门控链的一行。
 *
 * @property detail 人话详情（`null` = 该层无需补充说明）
 */
data class GateCheck(
    val label: String,
    val state: GateState,
    val detail: String? = null,
)

/**
 * 「为什么没跑」的全部内容（方案 §5.3 + §1.5 的最小信息集）。
 *
 * ## 它为什么不是一句"未运行"
 * §1.5 明写：用户问"为什么没跑"时，必须能区分**没触发**与**被拒**，并且
 * **逐层给出 did not pass**，而不是一个笼统结论。因此 [checks] 是**完整的一串**，
 * [verdict] 只是"第一个把它挡住的层"的结论。
 *
 * @property verdict 结论（永远非空 —— 说不清时也要如实说"说不清"）
 * @property blockedAt 把它挡住的那一层（`null` = 没有任何一层在挡，它只是还没开始/在等待）
 */
data class WhyNotRunning(
    val title: String,
    val checks: List<GateCheck>,
    val verdict: String,
    val blockedAt: String?,
) {
    companion object {
        /** 结论文案：**说不清就说说不清**，不编一个听起来确定的答案。 */
        const val UNKNOWN_REASON: String = "原因未知（宿主状态读取不完整）"
    }
}

/**
 * 「为什么没跑」的输入（一次快照）。
 *
 * 每个字段都对应门控链上的一层；`null` 一律表示**未知**（而不是 false）。
 */
data class GateInput(
    val scriptName: String,
    val scriptEnabled: Boolean,
    val resident: Boolean,
    /** 总闸（`MasterSwitch.enabled`）。 */
    val masterEnabled: Boolean,
    /** 前台服务在跑（常驻脚本的唯一所有者）。 */
    val serviceRunning: Boolean,
    /** 熔断安全模式。 */
    val safeMode: Boolean,
    /** 该脚本此刻是否有运行中的实例（`ScriptRunRegistry.isRunning`）；`null` = **读不到**。 */
    val running: Boolean?,
    /** 该脚本是否在监工的监管名单里（`DaemonSupervisor`）。 */
    val supervised: Boolean,
    /** 已放弃重启的原因（`DaemonSupervisor.givenUpReasons()[id]`）；`null` = 没放弃过。 */
    val givenUpReason: String?,
    /** 上次成功（退出码 0）的时刻；`null` = 从未。 */
    val lastSuccessAt: Long?,
    /** 上次尝试的时刻；`null` = 从未跑过。 */
    val lastAttemptAt: Long?,
    /** 上次尝试的退出码；`null` = 没拿到（被 kill / 未跑到末尾）。 */
    val lastExitCode: Int?,
    val nowMillis: Long,
)

/**
 * 门控链与卡片的**纯函数投影**（无 Android 依赖 ⇒ 纯 JVM 可测）。
 */
object MasterSwitchProjections {
    /** 卡片一帧。 */
    fun card(
        masterEnabled: Boolean,
        scripts: List<Script>,
        serviceRunning: Boolean,
        sourcesRunning: Int,
        sourcesTotal: Int,
        safeMode: Boolean,
    ): MasterSwitchUi =
        MasterSwitchUi(
            enabled = masterEnabled,
            scriptsEnabled = scripts.count { it.enabled },
            scriptsResident = scripts.count { it.enabled && it.resident },
            serviceRunning = serviceRunning,
            sourcesRunning = sourcesRunning,
            sourcesTotal = sourcesTotal,
            safeMode = safeMode,
        )

    /**
     * 逐层判定「为什么没跑」。
     *
     * ## 判定顺序（**有意如此，勿随意调换**）
     * ```
     * ① 安全模式      ← 最根本：熔断是"出了事，先全停"，此时总闸开着也跑不了
     * ② 总开关
     * ③ 脚本开关
     * ④ 已放弃重启    ← 前三层都通过才可能轮到它（它是"跑过然后不跑了"）
     * ⑤ 前台服务      ← 常驻脚本的唯一所有者；服务不在，监管无从谈起
     * ⑥ 运行方式      ← 单次脚本不常驻：这是 INFO 而不是阻挡
     * ⑦ 此刻是否在跑  ← 在跑 ⇒ 结论是"运行中"，不是"没跑"
     * ⑧ 等待/未知
     * ```
     * 前三层是**级联的闸门**（任一层关闭，后面都无从生效）；把安全模式排在总开关**之前**
     * 是因为真机上两者可能同时存在，而先报"总开关关闭"会让用户去拨总闸 —— 那也不会让它跑起来。
     */
    fun whyNotRunning(input: GateInput): WhyNotRunning {
        val checks = mutableListOf<GateCheck>()
        var blocked: String? = null

        fun add(
            label: String,
            state: GateState,
            detail: String? = null,
        ) {
            checks += GateCheck(label = label, state = state, detail = detail)
        }

        // ① 安全模式
        if (input.safeMode) {
            add("安全模式", GateState.BLOCKED, "熔断后事件被入口整体丢弃、常驻脚本已被终止")
            blocked = "安全模式"
        } else {
            add("安全模式", GateState.PASS, "未处于安全模式")
        }

        // ② 总开关
        if (blocked == null && !input.masterEnabled) {
            add("总开关", GateState.BLOCKED, "已关闭 —— 任何脚本都不得启动")
            blocked = "总开关"
        } else {
            add("总开关", GateState.PASS, if (input.masterEnabled) "已打开" else "已打开（安全模式先挡住了）")
        }

        // ③ 脚本开关
        if (blocked == null && !input.scriptEnabled) {
            add("脚本开关", GateState.BLOCKED, "已停用 —— 它不参与运行，但配置保留着")
            blocked = "脚本开关"
        } else {
            add("脚本开关", GateState.PASS, if (input.scriptEnabled) "已启用" else "已启用（上层先挡住了）")
        }

        // ④ 已放弃重启（前三层都通过才可能轮到它 —— 它是"跑过然后不跑了"）
        if (blocked == null && input.givenUpReason != null) {
            add("崩溃重试", GateState.BLOCKED, "已放弃重启：${input.givenUpReason}")
            blocked = "崩溃重试"
        } else {
            add(
                "崩溃重试",
                GateState.PASS,
                input.givenUpReason?.let { "已放弃重启：$it（但上层先挡住了，那不是本次的结论）" }
                    ?: "没有放弃过重启",
            )
        }

        // ⑤ 前台服务
        if (blocked == null && !input.serviceRunning) {
            add("前台服务", GateState.BLOCKED, "服务未运行 —— 常驻脚本由它承载，服务不在就没有监管")
            blocked = "前台服务"
        } else {
            add("前台服务", GateState.PASS, if (input.serviceRunning) "运行中" else "未运行（上层先挡住了）")
        }

        // ⑥ 运行方式（常驻 / 单次）—— **不构成阻挡**，但它是"为什么没跑"最常见的一种解释
        add(
            "运行方式",
            GateState.INFO,
            if (input.resident) "常驻（服务起来就一直跑，退出自动拉起）" else "单次 —— 不会常驻，只在服务启动时跑一遍",
        )

        // ⑦ 此刻是否在跑（**三态**：读不到时如实说读不到，不编"没在跑"）
        add(
            "运行状态",
            if (input.running == true) GateState.PASS else GateState.INFO,
            when (input.running) {
                true -> "有一个运行中的实例"
                false -> "此刻没有在跑的实例"
                null -> "读取失败：不知道此刻是否在跑"
            },
        )

        // 上次成功 / 上次尝试（§1.5 第 4、5 项）
        add("上次成功", GateState.INFO, relativeTime(input.nowMillis, input.lastSuccessAt))
        add(
            "上次尝试",
            GateState.INFO,
            lastAttemptDetail(input),
        )

        val verdict =
            when {
                // 结论 = **层名 + 那一层的人话详情**。
                // 只给详情会缺"是哪一层"（用户看到的是"已关闭 —— 任何脚本都不得启动"，
                // 却不知道那说的是总开关还是脚本开关）；只给层名又会丢掉细节
                // （而细节正是判读的依据，例如"已放弃重启：连续快速崩 5 次"）。
                blocked != null ->
                    checks
                        .firstOrNull { it.state == GateState.BLOCKED }
                        ?.let { "${it.label}：${it.detail ?: ""}" }
                        ?: WhyNotRunning.UNKNOWN_REASON

                input.running == true -> "正在运行"
                !input.resident -> "它是单次脚本：不会常驻，只在服务启动时跑一遍"
                input.supervised -> "已被监管：正在等待启动或重启（退避中）"
                else -> WhyNotRunning.UNKNOWN_REASON
            }

        return WhyNotRunning(
            title =
                "${input.scriptName} · " +
                    when (input.running) {
                        true -> "运行中"
                        false -> "未运行"
                        null -> "状态未知"
                    },
            checks = checks,
            verdict = verdict,
            blockedAt = blocked,
        )
    }

    /** 「上次尝试」的一行文字（含退出码；退出码缺失时**如实说不知道**）。 */
    fun lastAttemptDetail(input: GateInput): String {
        val whenText = relativeTime(input.nowMillis, input.lastAttemptAt)
        if (input.lastAttemptAt == null) return whenText
        val code =
            when (val exit = input.lastExitCode) {
                null -> "退出码未知（被终止或未跑到末尾）"
                0 -> "退出码 0"
                else -> "退出码 $exit"
            }
        return "$whenText · $code"
    }

    /**
     * 相对时间（"3 小时前"）。`null` / 非法值 ⇒「从未」。
     *
     * ## 为什么不显示"1970 年"
     * 三个时间戳在库里以 `0` 表示"从未"（见 `AppSwitch` / `Script` 的 KDoc）。
     * 把 `0` 当成真实时刻会显示"56 年前"，那是**假信息**，比"从未"糟得多。
     */
    fun relativeTime(
        nowMillis: Long,
        atMillis: Long?,
    ): String {
        if (atMillis == null || atMillis <= 0L) return "从未"
        val delta = nowMillis - atMillis
        if (delta < 0L) return "刚刚"
        val minutes = delta / MILLIS_PER_MINUTE
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "$minutes 分钟前"
            minutes < MINUTES_PER_DAY -> "${minutes / 60L} 小时前"
            else -> "${minutes / MINUTES_PER_DAY} 天前"
        }
    }

    /**
     * 绝对时刻（"今天 09:14" / "09-23 18:20" / "从未"）。
     *
     * [zone] 是形参而不是取 `ZoneId.systemDefault()`：单测要能断言，而"今天"这个词
     * 依赖**时区**——把它藏进实现里会让断言随机器时区漂移。
     */
    fun absoluteTime(
        nowMillis: Long,
        atMillis: Long?,
        zone: ZoneId,
    ): String {
        if (atMillis == null || atMillis <= 0L) return "从未"
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val clock = TIME_FORMATTER.format(at)
        return if (at.toLocalDate() == now.toLocalDate()) "今天 $clock" else "${DATE_FORMATTER.format(at)} $clock"
    }

    /** 「为什么没跑」里"上次成功"的完整文字（绝对 + 相对）。 */
    fun lastSuccessText(
        nowMillis: Long,
        atMillis: Long?,
        zone: ZoneId,
    ): String {
        if (atMillis == null || atMillis <= 0L) return "从未"
        return "${absoluteTime(nowMillis, atMillis, zone)}（${relativeTime(nowMillis, atMillis)}）"
    }

    private const val MILLIS_PER_MINUTE: Long = 60_000L
    private const val MINUTES_PER_DAY: Long = 1_440L

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
}

/**
 * 脚本行在**运行态**上的投影（列表行右侧的状态文字）。
 *
 * @property running **三态**：`true` = 此刻有运行中的实例；`false` = 确定没有；
 *   `null` = **还不知道**（运行态快照尚未读到）。
 *   与 `TriggerCounts` 同款纪律：把"还不知道"渲染成"已停止"是**假信息**。
 * @property givenUpReason 已放弃重启的原因（`null` = 没有放弃过）
 */
data class ScriptRuntimeState(
    val running: Boolean?,
    val givenUpReason: String?,
) {
    companion object {
        /** 冷启动：运行态未知（**不是**"没在跑"）。 */
        val Unknown: ScriptRuntimeState = ScriptRuntimeState(running = null, givenUpReason = null)
    }
}

/**
 * 一行脚本的**状态文字**（方案 §5.1 的"常驻 · 运行中"/"单次 · 未启用"/"常驻 · 已停止"）。
 *
 * ## 为什么状态由**多个字段**算出来，而不是一个字段
 * 方案 §5.1 的反模式明写：**不得**用一个开关同时表达"启用"与"运行中"。
 * 因此这里把"用户意图"（`enabled` / `resident`）与"当下事实"（`running` /
 * `givenUp` / 总闸是否暂停了它）**分开**计算，再组合成一句人能读的短句。
 */
object ScriptRuntimeProjections {
    /** 状态短句（列表行右侧）。 */
    fun statusText(
        enabled: Boolean,
        resident: Boolean,
        masterEnabled: Boolean,
        state: ScriptRuntimeState,
    ): String {
        val shape = if (resident) "常驻" else "单次"
        if (!enabled) return "$shape · 未启用"
        // ★ "已放弃重启"**只对常驻脚本有意义**（单次脚本根本不参与监工的监管）。
        //   少了 `resident &&`，一个刚被改回单次的脚本仍会显示"常驻 · 已放弃重启" ——
        //   与它当前的事实（单次）直接矛盾，而且那个 givenUp 记录还是上一次常驻时留下的
        //   （p4 真机恢复现场时抓到：改回单次之后行上仍写着"常驻"）。
        if (resident && state.givenUpReason != null) return "常驻 · 已放弃重启"
        val fact =
            when {
                // 总闸关闭时**不得**说"运行中"：它可能刚好还没被停掉，但用户的意图是停
                !masterEnabled -> "已暂停"
                state.running == true -> "运行中"
                state.running == false -> "已停止"
                // 三态：快照还没读到 ⇒ 如实说未知，不编"已停止"
                else -> "状态未知"
            }
        return "$shape · $fact"
    }

    /**
     * 是否显示"被总开关暂停"的标注。
     *
     * 这一条独立于 [statusText]，因为方案 §5.1 要求"总开关关闭时**列表仍可操作**，
     * 只标注暂停中" —— 标注是**附加**信息，不该替换掉脚本自身的状态。
     */
    fun pausedByMaster(
        enabled: Boolean,
        masterEnabled: Boolean,
    ): Boolean = enabled && !masterEnabled

    /**
     * 该行此刻是否"活着"（运行中且没有任何一层挡着）。
     *
     * 用途单一：状态文字的**主色强调**。它是纯函数而不是写进 Composable 的 `if`，
     * 理由与本文件其余投影相同 —— UI 判定必须可被纯 JVM 用例穷举。
     * 样式映射（`true` → primary / `false` → onSurfaceVariant）留在 Composable 里，
     * 那是**主题**的事，不是业务的事。
     */
    fun isLive(
        enabled: Boolean,
        masterEnabled: Boolean,
        state: ScriptRuntimeState,
    ): Boolean = enabled && masterEnabled && state.running == true
}
