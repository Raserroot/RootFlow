package com.rootflow.domain.model

import com.rootflow.domain.event.AndroidPermission

/**
 * 事件目录（阶段 6e）：13 个事件的**触发声明侧**元数据（需求 §2.3 的 UI 化）。
 *
 * ## 为什么需要它（而不是让 UI 直接遍历 `SystemEvent` 的常量）
 * 触发器 chip 要回答三个问题，而 `SystemEvent` 与 `TriggerEventKeys` **都不回答**：
 * ① 这个事件**要不要参数**（决定参数区渲染哪些输入框）；
 * ② 用户看到的**中文标签**是什么（键是持久化契约，`boot` 不该印在界面上）；
 * ③ 它依赖**哪一个**权限（需求 §2.1 的降级策略，见 `PROJECT_STATE.md` 的偏离项 **D14**）。
 *
 * ## 与 `TriggerEventKeys.knownIds` 的关系：**有意双表，断言相等**
 * `TriggerEventKeys` 是**持久化契约**（写进 `triggers.event_type`），本目录是**UI 目录**。
 * 两者必须逐值相等，由 `EventCatalogTest` 的**穷举相等**断言钉死：
 * `EventCatalog.ids == TriggerEventKeys.knownIds`。
 * 单项测试各自自洽、接缝没人测，正是 `AGENT_PROTOCOL.md §8.0` 禁止的形态。
 *
 * ## `eventId` 直接引用 [SystemEvent] 的常量（不写字面量）
 * 字面量散落会让"某处拼错"只在真机上暴露（`TriggerEventKeys` 的 KDoc 已写明这条纪律）。
 *
 * ## 覆盖范围：需求 §2.1 的 **12** 个 + [SystemEvent.BATTERY_OKAY]
 * 第 13 个键（`battery_okay`）是决策 3 追加的恢复边，需求表格里没有它。
 * 因此它的 [EventSpec.fromRequirement] 标为 `false`，UI 会照常渲染 chip
 * （少了它，按 `battery_low` 做过省电动作的用户脚本永远回不去）。
 */
object EventCatalog {
    /**
     * 事件的参数形态。
     *
     * 它决定参数区渲染哪一组输入框，以及"这个事件配完能不能跑"的判据
     * （见 `TriggerParamsDraft`）。**没有 `payload` 这一档**：
     * payload 是**全部 13 个事件通用**的（需求 §3.2 的 `ROOTFLOW_EVENT_PAYLOAD`），
     * 与事件形态正交 —— 把它塞进这个枚举会让"每个事件都要多写一次 payload"。
     */
    enum class ParamKind {
        /** 无专属参数（`boot` / `screen_*` / `unlock` / 电量类 / 电源类 / `wifi_changed`）。 */
        NONE,

        /** 每天固定时刻（`time`）。参数：`hourOfDay` + `minuteOfHour`（+ `exact`）。 */
        TIME,

        /** 固定间隔（`interval`）。参数：`intervalMinutes`。 */
        INTERVAL,

        /** 前台应用变化（`app_foreground` / `app_background`）。参数：包名（可选，空 = 不限定）。 */
        PACKAGE,
    }

    /**
     * 一个事件的声明侧元数据。
     *
     * @property eventId 稳定字符串键（**必须**取自 [SystemEvent] 的常量）
     * @property label chip 上的中文短标签（**唯一且非空**，由单测穷举钉死）
     * @property params 参数形态（见 [ParamKind]）
     * @property permission 依赖的权限；`null` = 与权限无关
     * @property permissionOnlyForExact 权限**仅**在"精确档"下才是硬前置。
     *   只有 `time` 为 `true`：D2/D3 决定 v1 默认非精确（`setInexactRepeating`），
     *   非精确路径**不需要** `SCHEDULE_EXACT_ALARM` ⇒ 未授权**不等于**该事件不可用。
     * @property fromRequirement 是否来自需求 §2.1 的表格（`battery_okay` 为 `false`）
     * @property note 用户可见的一句话说明（`null` = 不需要额外说明）
     */
    data class EventSpec(
        val eventId: String,
        val label: String,
        val params: ParamKind,
        val permission: AndroidPermission? = null,
        val permissionOnlyForExact: Boolean = false,
        val fromRequirement: Boolean = true,
        val note: String? = null,
    ) {
        /** 该事件是否带专属参数（UI 据此决定参数区是否有输入框，而不只是一个 payload 框）。 */
        val hasParams: Boolean get() = params != ParamKind.NONE
    }

    /**
     * 目录顺序 = **UI 展示顺序 = 写库顺序**（三者同一个来源）。
     *
     * ## 为什么顺序必须固定在这里
     * `TriggerSelectionDiff` 按目录序产出"要新增哪些 / 要删哪些"，
     * 而真机判读靠 `findstr` 比对日志行 —— **顺序不确定就无法逐行比对**
     * （`Map` 的迭代序、Room 的返回序都不保证）。因此顺序是**契约**，由单测钉死。
     *
     * 顺序按"用户最可能先想到的"排：开机 → 屏幕 → 前台 → 定时 → 电源 → 电量 → 网络。
     */
    val ALL: List<EventSpec> =
        listOf(
            EventSpec(
                eventId = SystemEvent.BOOT,
                label = "开机",
                params = ParamKind.NONE,
                note = "本机 ColorOS 在分发阶段拦截系统广播，开机事件由 App 启动时补发（D9）",
            ),
            EventSpec(
                eventId = SystemEvent.SCREEN_ON,
                label = "亮屏",
                params = ParamKind.NONE,
                note = "需前台服务驻留（需求 §2.1）",
            ),
            EventSpec(
                eventId = SystemEvent.SCREEN_OFF,
                label = "熄屏",
                params = ParamKind.NONE,
            ),
            EventSpec(
                eventId = SystemEvent.UNLOCK,
                label = "解锁",
                params = ParamKind.NONE,
            ),
            EventSpec(
                eventId = SystemEvent.APP_FOREGROUND,
                label = "前台应用",
                params = ParamKind.PACKAGE,
                permission = AndroidPermission.PACKAGE_USAGE_STATS,
            ),
            EventSpec(
                eventId = SystemEvent.APP_BACKGROUND,
                label = "退到后台",
                params = ParamKind.PACKAGE,
                permission = AndroidPermission.PACKAGE_USAGE_STATS,
                note = "由前台状态机推断（与「前台应用」同一个轮询源）",
            ),
            EventSpec(
                eventId = SystemEvent.TIME,
                label = "定时",
                params = ParamKind.TIME,
                permission = AndroidPermission.SCHEDULE_EXACT_ALARM,
                permissionOnlyForExact = true,
            ),
            EventSpec(
                eventId = SystemEvent.INTERVAL,
                label = "间隔",
                params = ParamKind.INTERVAL,
            ),
            EventSpec(
                eventId = SystemEvent.BATTERY_LOW,
                label = "低电量",
                params = ParamKind.NONE,
            ),
            EventSpec(
                eventId = SystemEvent.BATTERY_OKAY,
                label = "电量恢复",
                params = ParamKind.NONE,
                fromRequirement = false,
                note = "需求 §2.1 未列，按决策 3 追加（缺了恢复边，低电量动作回不去）",
            ),
            EventSpec(
                eventId = SystemEvent.POWER_CONNECTED,
                label = "接电源",
                params = ParamKind.NONE,
            ),
            EventSpec(
                eventId = SystemEvent.POWER_DISCONNECTED,
                label = "断电源",
                params = ParamKind.NONE,
            ),
            EventSpec(
                eventId = SystemEvent.WIFI_CHANGED,
                label = "WiFi 变化",
                params = ParamKind.NONE,
            ),
            // ★ 「一直运行」排在**最后**（用户 2026-09-20 追加）。
            //   它不是外部事件，因此刻意不插进上面按"事件类别"排的序列里；
            //   放末尾也让它成为 chip 组里**一眼可辨**的那一个。
            //
            // ★★ P8 补记：**它的语义已经搬到 `scripts.resident`**
            //   总开关重构后，"常驻与否"是脚本自己的字段（编辑器里的「运行方式」），
            //   而 `DaemonSupervisorImpl.reconcile` 的判据是
            //   `scripts.filter { it.enabled && it.resident }` —— **完全不读这条订阅**。
            //   因此这个 chip 现在**勾不勾都不影响是否常驻**（旧的 `always_run` 订阅行
            //   由 `MIGRATION_1_2` 转换成了 `resident` 字段，原行不再需要）。
            //   `note` 必须如实说清这一点，否则用户会以为"勾了它才会常驻"。
            //   （是否彻底移除该 chip 属于产品决策，留给用户定；本轮只保证文案不说谎。）
            EventSpec(
                eventId = SystemEvent.ALWAYS_RUN,
                label = "一直运行",
                params = ParamKind.NONE,
                fromRequirement = false,
                note =
                    "常驻与否现在由编辑器的「运行方式 = 常驻」决定；" +
                        "本项是旧版本留下的入口，勾不勾都不影响是否常驻" +
                        "（连续快速崩 5 次后只停掉它自己，不会冻结整个 App）",
            ),
        )

    /** 全部已知键（顺序 = [ALL] 的顺序）。 */
    val ids: List<String> = ALL.map { it.eventId }

    private val byId: Map<String, EventSpec> = ALL.associateBy { it.eventId }

    /** 按 [eventId] 取元数据；未知键返回 `null`（**不抛异常** —— 库里可能有历史脏键）。 */
    fun spec(eventId: String): EventSpec? = byId[eventId]

    /** chip 的展示标签；未知键回落成键本身（宁可露出 `boot`，也不显示空白 chip）。 */
    fun label(eventId: String): String = byId[eventId]?.label ?: eventId

    /**
     * 目录序的下标（排序用）。
     *
     * 未知键排到**末尾**（`ALL.size`）而不是 0 或 -1：
     * 它不该插到"开机"前面（用户会以为那是第一个事件），也不该因排序抛异常。
     */
    fun orderOf(eventId: String): Int = ids.indexOf(eventId).let { if (it < 0) ALL.size else it }
}
