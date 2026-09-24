package com.rootflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 脚本元数据行（需求 §3.3）。
 *
 * ## 正文**不入库**（需求 §3.2/§3.3）
 * 正文存文件系统（`/data/local/tmp/rootflow/scripts/<id>/main.sh`），本表只存
 * [contentPath]（**相对路径**）与 [contentSha256]。
 *
 * - 相对路径：目录常量可能因设备/Root 方案变化而调整，绝对路径入库会全部失效
 * - 摘要：让"文件与 DB 不一致"可被**显式检测**，而不是静默读到空内容
 *
 * ## 命名
 * 本类不叫 `ScriptEntity`——`com.rootflow.runtime.ScriptEntity` 已占用该名。
 * `data` 层的 Room 实体一律以 `Row` 结尾，与 `runtime`/`domain` 的 `*Entity` 区分。
 */
@Entity(
    tableName = "scripts",
    indices = [Index(value = ["name"])],
)
data class ScriptRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    /** `"shell"` 或 `"lua"`（需求 §3.1：v1 只实现 shell）。 */
    val language: String,
    val enabled: Boolean,
    /**
     * **运行方式**：`true` = 常驻（服务起来就一直跑、退出自动拉起）；
     * `false` = 单次（服务起来跑一遍就结束）。
     *
     * ## 来历（总开关重构）
     * 这是旧的「一直运行」触发器（`always_run`）**升格**成的脚本字段。
     * 升格的理由：它描述的是**脚本自身的运行形态**，而不是"某个事件发生了" ——
     * 把它留在触发器表里会让"触发器"这个概念同时承载两种语义。
     *
     * ## ★ 为什么**必须**有 `defaultValue = "0"`（实测撞出来的，勿删）
     * SQLite 的 `ALTER TABLE … ADD COLUMN` 对**非空表**加 `NOT NULL` 列时
     * **要求**给出默认值，否则报
     * `Cannot add a NOT NULL column with default value NULL`（sqlite 3.45 实测）。
     *
     * 若这里不写 `defaultValue`，Room 期望的 DDL 就是 `resident INTEGER NOT NULL`
     * （无 DEFAULT），而迁移为了能执行**必须**写 `NOT NULL DEFAULT 0`
     * ⇒ 两边 DDL 不一致 ⇒ Room 打开库时**schema 校验失败**。
     *
     * 更糟的是：如果为了"两边一致"而放弃默认值，那么**任何已有脚本的用户**
     * 一升级就会在 `ALTER TABLE` 那一步崩 —— 即"升级即崩溃"。
     *
     * ⇒ 结论：`defaultValue = "0"` 是**必需项**，实体与迁移必须同时带上它。
     * （`INSERT` 侧仍由 Room 强制提供该值，因此"忘了给新行赋值"依然会当场暴露。）
     */
    @ColumnInfo(name = "resident", defaultValue = "0")
    val resident: Boolean,
    /** 单次运行超时秒数；`0` = 不限（需求 §3.3）。 */
    val timeoutSec: Int,
    val autoDisableOnFail: Boolean,
    val runOnSafeMode: Boolean,
    /** 正文文件的**相对**路径，如 `scripts/1/main.sh`。 */
    @ColumnInfo(name = "content_path")
    val contentPath: String,
    /** 正文摘要 `sha256:<hex>`。 */
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * **脚本 × 事件**的订阅行（原 `triggers` 表，总开关重构后改名 `script_events`）。
 *
 * ## 与旧 `triggers` 表的差别
 * 表名改成 `script_events`（它描述的是"脚本 × 事件"的订阅关系，而不是"触发器"），
 * 索引从 `(event_type, enabled)` 复合改成单列 `event_type`。
 *
 * ## ★ `enabled` **保留**（一次设计修正，理由见 `enabled` 字段的 KDoc）
 * 曾计划删掉它、用"行存在与否"表达开关。实施中发现那会把"停用但保留配置"这个
 * **用户仍然需要**的状态抹掉，并牵连几十处测试重写。
 * ⇒ 保留列，但把它当普通数据字段，由查询显式过滤。
 *
 * ## 语义（重构后）
 * "事件"不再是"启动这个脚本"的理由，而是**投递给正在运行的这个脚本的通知**。
 * 因此本表只在 [resident][ScriptRow.resident] = `true` 时有意义
 * —— 单次脚本没有常驻进程可投递。
 *
 * ## 迁移（v1 → v2）
 * 旧表的**已启用**行（`enabled = 1`）成为这里的行；被禁用的旧行**不迁移**
 * （用户的意图就是"这些不生效"，迁移报告会逐条列出）。
 * 旧 `always_run` 行**不进入本表** —— 它升格为 `scripts.resident`。
 *
 * ## `params` 仍是 JSON 字符串
 * 不用 TypeConverter：它就是普通 TEXT 列，编解码在 `data/trigger/TriggerParamsCodec.kt`。
 * 附带收益：JSON 内部结构演进不触发 Room schema 变更。
 */
@Entity(
    tableName = "script_events",
    foreignKeys = [
        ForeignKey(
            entity = ScriptRow::class,
            parentColumns = ["id"],
            childColumns = ["script_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["event_type"]), Index(value = ["script_id"])],
)
data class ScriptEventRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "script_id")
    val scriptId: Long,
    /** `SystemEvent` 的**稳定字符串键**（如 `"boot"`、`"screen_off"`）。 */
    @ColumnInfo(name = "event_type")
    val eventType: String,
    /** 事件参数，JSON 字符串；解析失败时由上层回退为默认值并记警告。 */
    val params: String,
    /**
     * 该订阅是否生效。
     *
     * ## 为什么**保留**这一列（一次设计修正的记录）
     * 最初的方案是把 `enabled` **删掉**，用"行存在与否"表达开关
     * （"存在即订阅"，见 [ScriptEventRow] 的类 KDoc）。
     *
     * 实施到测试层时发现这条路会把一个**仍然有用的功能**演化成几十处测试重写：
     * 熔断、编辑器、调度器都依赖"**保留一条被禁用的订阅**"这个状态
     * （用户的意思是"这条先别跑，但我不想丢配置"）。
     * 删掉它等于把"停用"与"删除"合并 —— 那对用户是**信息丢失**，不是简化。
     *
     * ⇒ 修正为：**保留 `enabled`**，但把它当作**普通数据字段**（不再是"两种表达同一件事"
     * 的歧义源），并让查询显式过滤（[com.rootflow.data.db.dao.ScriptEventDao.forEvent]）。
     * 唯一保留的简化是：**订阅不再需要 UI 层的三态开关**，由集合勾选表达。
     */
    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * **总开关**（全局唯一一行，`id = 1`）。
 *
 * ## 为什么是"单行表"而不是 DataStore
 * 它必须与 `scripts` / `script_events` 在**同一个事务**里被读写：
 * 用户拨总开关时要连带处理"哪些脚本该起/该停"，跨存储会丢掉这个原子性。
 *
 * ## 为什么只有一个布尔
 * `masterEnabled` 表达【用户意图】，**不表达**"此刻在不在跑" ——
 * 后者是**派生状态**（`masterEnabled && script.enabled`），**不落库**。
 * 这与 systemd 的 `is-enabled` / `is-active` 正交性一致：
 * 把两种语义挤进一个布尔，必然会出现"我想让它跑"与"它此刻在跑"不可分辨的 UI。
 *
 * @property masterEnabled 总闸。`false` ⇒ 任何脚本都不得启动，已在跑的被终止。
 * @property enabledAt 最近一次拨到 `true` 的时刻；`0` = 从未拨开过。
 * @property disabledAt 最近一次拨到 `false` 的时刻；`0` = 从未拨关过。
 * @property updatedAt 本行最后更新时间（判读用，避免"看不出谁改的"）。
 */
@Entity(tableName = "app_switch")
data class AppSwitchRow(
    @PrimaryKey
    val id: Long = SINGLETON_ID,
    @ColumnInfo(name = "master_enabled")
    val masterEnabled: Boolean,
    @ColumnInfo(name = "enabled_at")
    val enabledAt: Long,
    @ColumnInfo(name = "disabled_at")
    val disabledAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        /** 单行表的主键恒为该值；DAO 只读写这一行。 */
        const val SINGLETON_ID: Long = 1L
    }
}

/**
 * 一次运行的历史行（需求 §4.4「退出时记录 exit code」）。
 *
 * [id] 直接用 `runId`（UUID 字符串）作为主键，与日志管道的 `runId` 一致，无需自增列。
 *
 * **不设 `scriptId → scripts` 外键**：脚本被删除后运行历史仍应保留（排障需要）。
 * 因此脚本删除不会级联清掉历史。
 */
@Entity(
    tableName = "runs",
    indices = [Index(value = ["script_id", "started_at"]), Index(value = ["started_at"])],
)
data class RunRow(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "script_id")
    val scriptId: Long,
    /** 与 `RunContext.triggerEvent` 对应；`null` = 手动/自检触发。 */
    @ColumnInfo(name = "trigger_event")
    val triggerEvent: String?,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    /** 退出码；来源见 `PROJECT_STATE.md` 偏离项 D6（从 SYS 行正则提取）。 */
    @ColumnInfo(name = "exit_code")
    val exitCode: Int? = null,
    @ColumnInfo(name = "log_entry_count")
    val logEntryCount: Int = 0,
    /** 因单 run 行数上限被丢弃的日志条数（**不静默**）。 */
    @ColumnInfo(name = "dropped_log_entries")
    val droppedLogEntries: Int = 0,
)

/**
 * 一行运行日志。
 *
 * 唯一索引 `(run_id, sequence)` 让**重复写入天然幂等**
 * （`OnConflictStrategy.IGNORE` + 阶段 2 的条目序号运行内唯一）。
 */
@Entity(
    tableName = "run_log_entries",
    foreignKeys = [
        ForeignKey(
            entity = RunRow::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id", "sequence"], unique = true)],
)
data class LogEntryRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "run_id")
    val runId: String,
    /** 阶段 2 的条目序号：运行内单调、从 0 开始（含已被批次丢弃的条目）。 */
    val sequence: Long,
    val timestamp: Long,
    /** `STDOUT` / `STDERR` / `SYS`（存枚举名而非序号，避免重排错位）。 */
    val stream: String,
    val text: String,
)
