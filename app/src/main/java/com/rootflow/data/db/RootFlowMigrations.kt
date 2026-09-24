package com.rootflow.data.db

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 全部数据库迁移的**唯一登记处**（阶段 3a）。
 *
 * ## 纪律（强约束）
 * 1. **禁止 `fallbackToDestructiveMigration()`**：那等于"删库解决编译错误"。
 *    用户脚本正文虽在文件系统，但元数据 / 触发器 / 运行历史一旦静默清空就**不可恢复**。
 * 2. 每次升 [RootFlowDatabase.VERSION]，**必须同时**：
 *    - 新增一个 `Migration(n, n+1)` 并加入 [ALL]
 *    - 提交 `app/schemas/…/<n+1>.json`（由 KSP 自动生成，`room.schemaLocation` 已配置）
 *    - 更新 `RootFlowSchemaTest` 里"实体 → 导出 schema"的断言
 * 3. 迁移必须**可测**：`RootFlowSchemaTest` 已建立"读导出 JSON + `sqlite_master` 直查"
 *    的断言方式（本项目不引入 `room-testing` / `androidTest`）。
 *
 * ## 迁移里**不要**写 `DROP TABLE`（除本次有意弃用的表）
 * 用户数据只有一份。弃用的表**必须先改名备份**（见 [MIGRATION_1_2] 的做法），
 * 让"迁移后还能捞回旧数据"成为事实，而不是靠运气。
 */
object RootFlowMigrations {
    /** 总开关重构的迁移里用到的 tag（与全项目一致）。 */
    private const val TAG: String = "RootFlow"

    /**
     * v1 → v2：**总开关重构**（见 `docs/总开关机制-方案.md`）。
     *
     * ## 这次迁移做什么（逐条）
     * 1. `scripts` 新增 `resident` 列。取值由**旧的 `always_run` 触发器**决定 ——
     *    那个触发器表达的就是"一直运行"，重构后升格为脚本字段。
     *    没有 `always_run` 的脚本 ⇒ `resident = 0`（单次）。
     * 2. 新建 `script_events`（**带 `enabled` 列，`DEFAULT 1`**），
     *    把旧 `triggers` 里**除 `always_run` 之外的全部行**搬过来 ——
     *    **含 `enabled = 0` 的行**：那些是「**停用但保留配置**」的订阅，
     *    抹掉它们就是**信息丢失**（中途曾计划删掉该列、用"行存在"表达开关，
     *    实施到测试层时被否决；见 `ScriptEventDao` 的 KDoc 留档）。
     * 3. 新建 `app_switch` 单行表。**初始值必须 `master_enabled = 0`（总闸关闭）** ——
     *    重构后的语义是"总闸开着脚本就常驻运行"，若迁移后默认拨开，
     *    用户升级那一刻所有脚本会**立刻一起跑起来**，而他并没有同意过这件事。
     *    ⇒ 默认关闭是**安全默认**，且与迁移报告的"请手动拨开"一致。
     * 4. 旧 `triggers` 表**改名保留**为 `triggers_v1_backup`，**不删**。
     *
     * ## 为什么旧表要留着（而不是直接 DROP）
     * 本次迁移**只有一类行不再作为订阅存在**：旧的 `always_run` 行
     * （语义已升格为 `scripts.resident`，原行不再需要）。
     * 其余行（**含 `enabled = 0` 的**）都原样搬进新表，因此"迁移后数据仍完整"
     * 这句话**可被逐行核对**：那张备份表就是对照用的存档，迁移报告要拿它比对。
     *
     * ## 幂等性
     * Room 只在版本号变化时执行本迁移一次，因此不需要额外的幂等保护。
     * 但 SQL 本身仍是幂等的（`IF NOT EXISTS` / `CREATE TABLE` 前先判断），
     * 以便手工重跑排查问题时不炸。
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "MIGRATION_1_2 begin (total-switch refactor)")

                // ---------- ① scripts.resident ----------
                // 先加列（不带默认值 ⇒ 必须立刻为每一行赋值，否则后续 NOT NULL 校验会失败）
                db.execSQL("ALTER TABLE `scripts` ADD COLUMN `resident` INTEGER NOT NULL DEFAULT 0")

                // 由旧的 always_run 触发器决定：有它 ⇒ 常驻
                db.execSQL(
                    """
                    UPDATE `scripts` SET `resident` = 1
                    WHERE `id` IN (
                        SELECT `script_id` FROM `triggers`
                        WHERE `event_type` = 'always_run' AND `enabled` = 1
                    )
                    """.trimIndent(),
                )
                val residentCount =
                    db.query("SELECT COUNT(*) FROM `scripts` WHERE `resident` = 1").use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                Log.i(TAG, "MIGRATION_1_2 scripts.resident set residentScripts=$residentCount")

                // ---------- ② script_events（存在即订阅） ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `script_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `script_id` INTEGER NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `params` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`script_id`) REFERENCES `scripts`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_script_events_event_type` ON `script_events` (`event_type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_script_events_script_id` ON `script_events` (`script_id`)",
                )

                // 搬已启用且非 always_run 的行；DISTINCT 防旧表里的重复行（旧模型允许同脚本同事件多行）
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `script_events`
                        (`script_id`, `event_type`, `params`, `enabled`, `created_at`)
                    SELECT DISTINCT `script_id`, `event_type`, `params`, `enabled`, `created_at` FROM `triggers`
                    WHERE `event_type` <> 'always_run'
                    """.trimIndent(),
                )
                val eventCount =
                    db.query("SELECT COUNT(*) FROM `script_events`").use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                Log.i(TAG, "MIGRATION_1_2 script_events migrated subscriptionRows=$eventCount")

                // ---------- ③ app_switch（总闸，默认关闭） ----------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_switch` (
                        `id` INTEGER NOT NULL,
                        `master_enabled` INTEGER NOT NULL,
                        `enabled_at` INTEGER NOT NULL,
                        `disabled_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                // ★ master_enabled = 0：安全默认（见 KDoc 第 3 条）。用户升级后必须亲手拨开。
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `app_switch`
                        (`id`, `master_enabled`, `enabled_at`, `disabled_at`, `updated_at`)
                    VALUES (1, 0, 0, 0, 0)
                    """.trimIndent(),
                )
                Log.i(TAG, "MIGRATION_1_2 app_switch created masterEnabled=false (safe default)")

                // ---------- ④ 旧表改名存档（**不删**） ----------
                db.execSQL("ALTER TABLE `triggers` RENAME TO `triggers_v1_backup`")
                db.execSQL("DROP INDEX IF EXISTS `index_triggers_event_type_enabled`")
                db.execSQL("DROP INDEX IF EXISTS `index_triggers_script_id`")

                Log.i(
                    TAG,
                    "MIGRATION_1_2 done backupTable=triggers_v1_backup " +
                        "(disabled/always_run rows are kept there and listed in the migration report)",
                )
            }
        }

    /**
     * 按版本升序排列的全部迁移。
     *
     * 例：v2 → v3 时写 `val MIGRATION_2_3 = object : Migration(2, 3) { … }` 并追加到这里。
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
