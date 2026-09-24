package com.rootflow.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rootflow.data.db.dao.AppSwitchDao
import com.rootflow.data.db.dao.RunDao
import com.rootflow.data.db.dao.RunLogDao
import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.data.db.dao.ScriptEventDao
import com.rootflow.data.db.entity.AppSwitchRow
import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow

/**
 * RootFlow 的 Room 数据库（阶段 3a）。
 *
 * ## schema 导出
 * [exportSchema] 开启，导出目录由 `app/build.gradle.kts` 的
 * `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` 指定。
 * **导出的 JSON 必须入库**——它是"实体改动是否伴随版本升级"的唯一可验证依据，
 * 也是将来写迁移测试的输入。
 *
 * ## 迁移
 * 见 [RootFlowMigrations]：**禁止破坏性迁移**，迁移对象集中在那一处登记。
 *
 * ## 版本
 * - `VERSION = 1`：阶段 3a 首次建立
 * - `VERSION = 2`：**总开关重构**（见 `docs/总开关机制-方案.md`）
 *   - `scripts` 新增 `resident`（运行方式：常驻 / 单次）
 *   - `triggers` → `script_events`（**去掉 `enabled`**：存在即订阅）
 *   - 新增 `app_switch`（全局单行总开关）
 */
@Database(
    entities = [
        ScriptRow::class,
        ScriptEventRow::class,
        AppSwitchRow::class,
        RunRow::class,
        LogEntryRow::class,
    ],
    version = RootFlowDatabase.VERSION,
    exportSchema = true,
)
abstract class RootFlowDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao

    abstract fun scriptEventDao(): ScriptEventDao

    abstract fun appSwitchDao(): AppSwitchDao

    abstract fun runDao(): RunDao

    abstract fun runLogDao(): RunLogDao

    companion object {
        /** 当前 schema 版本。改动实体后必须 +1 并在 [RootFlowMigrations.ALL] 登记迁移。 */
        const val VERSION: Int = 2

        /** 数据库文件名（相对 `/data/data/<pkg>/databases/`）。 */
        const val NAME: String = "rootflow.db"
    }
}
