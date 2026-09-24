package com.rootflow.domain.model

/**
 * 脚本（`domain` 层的稳定表示）。
 *
 * 与 `runtime.ScriptEntity` 的关系：`runtime` 的是**运行时入参**，本类是**domain/UI 视角**
 * 的脚本（含创建/更新时间）。二者由 `data/db/mapper/ScriptRowMappers.kt` 转换。
 *
 * @property id 主键；`0` 表示尚未入库
 * @property content 正文（来自文件系统，**不入库**，见 `REQUIREMENTS.md §3.2`）
 * @property contentSha256 正文摘要；用于检测文件被外部改动
 */
data class Script(
    val id: Long,
    val name: String,
    val language: String,
    val enabled: Boolean,
    val timeoutSec: Int,
    val autoDisableOnFail: Boolean,
    val runOnSafeMode: Boolean,
    val content: String,
    val contentSha256: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * **运行方式**：`true` = 常驻（服务起来就一直跑、退出自动拉起）；`false` = 单次。
     *
     * ## 为什么放在**最后一个参数且带默认值**
     * 它是总开关重构新增的字段。放在末尾并给默认值，能让**既有构造点全部继续编译**
     * （构造点很多：编辑器草稿、验证夹具、各测试替身），
     * 而 `RowMappers` 从数据库读时**始终显式传入真实值**，因此不存在"默认值掩盖了真值"。
     * 这与 [Trigger] 去掉 `enabled` 是同一件事的两面：运行形态属于脚本，订阅才属于事件。
     */
    val resident: Boolean = false,
)

/**
 * 事件参数（需求 §2.3 的 `params: String (JSON)` 的结构化形态）。
 *
 * **默认值即"最保守的配置"**：非精确闹钟、无间隔、无时刻、无负载。
 *
 * 手写 JSON 编解码见 `data/trigger/TriggerParamsCodec.kt`——本项目**不引入
 * `kotlinx-serialization`**，因为其 Gradle 编译器插件在离线缓存中不存在，会导致构建
 * 无法离线完成（详见阶段 3a 变更报告）。
 *
 * @property exact 是否使用精确闹钟。按 `PROJECT_STATE.md` 偏离项 **D3**，v1 默认 `false`
 *   （不申请 `SCHEDULE_EXACT_ALARM`），精确路径留待将来
 * @property intervalMinutes 仅 `interval` 事件使用；`null` 表示未配置
 * @property hourOfDay / minuteOfHour 仅 `time` 事件使用
 * @property payload 事件负载，注入 `ROOTFLOW_EVENT_PAYLOAD`（阶段 3d 使用）
 */
data class TriggerParams(
    val exact: Boolean = false,
    val intervalMinutes: Int? = null,
    val hourOfDay: Int? = null,
    val minuteOfHour: Int? = null,
    val payload: String? = null,
)

/**
 * **脚本 × 事件**订阅（需求 §2.3）。**唯一真相源 = Room**（表 `script_events`）。
 *
 * ## `enabled` 的语义（一次设计修正的结果）
 * 曾计划**删掉** `enabled`、用"行存在与否"表达开关（"存在即订阅"）。
 * 实施到测试层时确认那条路会把「**停用但保留配置**」这个用户仍需要的状态抹掉，
 * 并牵连几十处既有断言重写 ⇒ **保留字段**，只把它降级为普通数据字段：
 *
 * - `enabled = true`：生效，事件到来时被投递给该脚本
 * - `enabled = false`：**保留配置但停用** —— 行还在，只是不参与投递
 * - 行不存在：从未配置过该事件
 *
 * 三态各有用处，**不是"两种表达同一件事"**（那是我最初判断错的地方）。
 * 查询侧的过滤在 `ScriptEventDao.forEvent`（`AND enabled = 1`）。
 *
 * ## 语义（重构后）
 * 事件**不再是"启动这个脚本"的理由**，而是**投递给正在运行的这个脚本的通知**。
 * 因此本模型只在 [Script.resident] = `true` 时有意义 —— 单次脚本没有常驻进程可投递。
 *
 * @property eventType `SystemEvent` 的稳定字符串键（如 `"boot"`、`"screen_off"`）
 */
data class Trigger(
    val id: Long,
    val scriptId: Long,
    val eventType: String,
    val params: TriggerParams,
    /** 是否生效；`false` = 保留配置但停用（见类 KDoc 的三态说明）。 */
    val enabled: Boolean = true,
    val createdAt: Long,
)

/** 一次运行的历史摘要（需求 §4.4）。 */
data class RunSummary(
    val runId: String,
    val scriptId: Long,
    val triggerEvent: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val exitCode: Int?,
    val logEntryCount: Int,
    /** 因单 run 行数上限被丢弃的日志条数（**不静默**）。 */
    val droppedLogEntries: Int,
)

/**
 * **总开关**（`domain` 层的稳定表示；表 `app_switch` 的**唯一一行**）。
 *
 * ## 为什么只有一个布尔（+ 三个时间戳）
 * [masterEnabled] 表达**【用户意图】**，**不表达**"此刻在不在跑" ——
 * 后者是**派生状态**（`masterEnabled && script.enabled`），**不落库**。
 * 这与 systemd 的 `is-enabled` / `is-active` 正交性一致：把两种语义挤进一个布尔，
 * 必然出现"我想让它跑"与"它此刻在跑"不可分辨的 UI。
 *
 * ## 两级开关 + 派生条件（方案 §3.2）
 * | 状态 | 含义 | 谁决定 |
 * |---|---|---|
 * | [masterEnabled] | 宿主是否在提供服务 | 用户在**主页**拨总闸 |
 * | [Script.enabled] | 这个脚本要不要参与 | 用户在**脚本行**拨开关 |
 * | 有效运行条件 | `masterEnabled && script.enabled` | **派生，不落库** |
 *
 * @property enabledAt 最近一次拨到 `true` 的时刻；`0` = 从未拨开过
 * @property disabledAt 最近一次拨到 `false` 的时刻；`0` = 从未拨关过
 * @property updatedAt 本行最后更新时间（判读用：避免"看不出谁改的"）
 */
data class AppSwitch(
    val masterEnabled: Boolean,
    val enabledAt: Long,
    val disabledAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /** 单行表的主键恒为该值（与 `AppSwitchRow.SINGLETON_ID` 同源）。 */
        const val SINGLETON_ID: Long = 1L

        /**
         * **安全默认**：总闸关闭、三个时间戳全 `0`。
         *
         * 取值与迁移写入的初始行（`MIGRATION_1_2` 的 `VALUES (1, 0, 0, 0, 0)`）**逐字段一致**：
         * 全新安装与升级后的第一帧必须看到同一个状态，否则"装完什么都不跑"与
         * "升级后什么都不跑"会变成两种需要分别排查的现象。
         *
         * 它同时也是「表里还没有行」时的回落值 —— 那是全新安装的合法状态
         * （数据库回调尚未写入），不是错误。
         */
        val SafeDefault: AppSwitch =
            AppSwitch(masterEnabled = false, enabledAt = 0L, disabledAt = 0L, updatedAt = 0L)
    }
}
