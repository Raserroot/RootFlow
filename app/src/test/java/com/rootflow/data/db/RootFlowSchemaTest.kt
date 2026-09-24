package com.rootflow.data.db

import com.rootflow.data.db.entity.LogEntryRow
import com.rootflow.data.db.entity.RunRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * schema 导出与列清单一致性单测（阶段 3a）。
 *
 * ## 它防的是什么
 * 最常见的 Room 事故是**改了实体却忘了升 `VERSION` + 加迁移**——真机上表现为
 * `IllegalStateException: Room cannot verify the data integrity`，且往往在用户升级后才炸。
 *
 * 本项目**不引入 `room-testing`/`androidTest`**（见 3a 变更报告），也不引入
 * `kotlin-reflect`（其 2.2.20 不在离线缓存）。因此改为：
 * 读取 KSP 导出的 `app/schemas/<db>/<version>.json`，与**显式列清单**比对。
 *
 * ## 为什么列清单也能防漏
 * 每个表的清单用**命名参数调用实体构造函数**写成，例如
 * `ScriptRow(id = 0, name = "", …)` —— 一旦实体增删/重命名字段，本文件**编译失败**；
 * 而新增字段若不同步到这里，`assertEquals(列数)` 会失败。
 * 也就是说：实体改动无法在不碰本测试的情况下通过。
 *
 * ## 失败时的正确处理顺序
 * 1. 升 `RootFlowDatabase.VERSION`
 * 2. 在 `RootFlowMigrations.ALL` 登记 `Migration(n, n+1)`
 * 3. 重新构建以生成新的 schema JSON 并**入库**
 * 4. 更新本测试的列清单
 * **绝不允许**改用 `fallbackToDestructiveMigration` 绕过。
 */
class RootFlowSchemaTest {
    @Test
    fun `schema json is exported and committed`() {
        val file = schemaFile(RootFlowDatabase.VERSION)

        assertTrue(
            file.isFile,
            "缺少导出的 schema：${file.path}（应由 ksp arg room.schemaLocation 生成并入库）",
        )
        assertTrue(
            file.readText().contains("\"version\": ${RootFlowDatabase.VERSION}"),
            "schema 版本号与 RootFlowDatabase.VERSION 不一致",
        )
    }

    @Test
    fun `schema declares exactly the expected tables`() {
        val json = schemaJson()

        EXPECTED_COLUMNS.keys.forEach { table ->
            assertTrue(
                json.contains("\"tableName\": \"$table\""),
                "表 $table 未出现在导出的 schema 中",
            )
        }
        // Room 会额外创建 room_master_table 用于记录 identity hash；
        // 实体表数**与列清单同源**（`EXPECTED_COLUMNS`）—— 省得两处各写一个数字再漂移。
        assertEquals(
            EXPECTED_COLUMNS.size + 1,
            Regex("""CREATE TABLE IF NOT EXISTS""").findAll(json).count(),
            "应为 ${EXPECTED_COLUMNS.size} 张实体表（${EXPECTED_COLUMNS.keys}）+ 1 张 room_master_table",
        )
        assertTrue(json.contains("room_master_table"), "Room 的系统表缺失，schema 可能不完整")
    }

    @Test
    fun `schema columns match the entity field lists`() {
        val json = schemaJson()

        EXPECTED_COLUMNS.forEach { (table, expected) ->
            val actual = columnsOf(json, table)

            assertEquals(
                expected.toSet(),
                actual,
                "表 $table 的列与实体不一致；" +
                    "若刚改过实体，请升 VERSION + 加迁移 + 重新导出 schema + 更新本测试清单",
            )
        }
    }

    @Test
    fun `migration registry matches the current version`() {
        if (RootFlowDatabase.VERSION == 1) {
            assertTrue(
                RootFlowMigrations.ALL.isEmpty(),
                "VERSION 仍为 1，迁移表应为空；实际=${RootFlowMigrations.ALL.size}",
            )
        } else {
            assertTrue(
                RootFlowMigrations.ALL.isNotEmpty(),
                "VERSION=${RootFlowDatabase.VERSION} 但迁移表为空 —— 升级路径缺失",
            )
        }
    }

    @Test
    fun `entity constructors keep their expected field names`() {
        // 这段代码本身就是"实体字段名清单"的编译期护栏：
        // 字段被重命名/删除/改类型时，本测试无法编译 —— 强制实体改动必须显式经过这里。
        ScriptRow(
            id = 0L,
            name = "",
            language = "shell",
            enabled = true,
            resident = false,
            timeoutSec = 0,
            autoDisableOnFail = false,
            runOnSafeMode = false,
            contentPath = "",
            contentSha256 = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
        ScriptEventRow(
            id = 0L,
            scriptId = 0L,
            eventType = "",
            params = "{}",
            enabled = true,
            createdAt = 0L,
        )
        RunRow(
            id = "",
            scriptId = 0L,
            triggerEvent = null,
            startedAt = 0L,
            finishedAt = null,
            exitCode = null,
            logEntryCount = 0,
            droppedLogEntries = 0,
        )
        LogEntryRow(
            id = 0L,
            runId = "",
            sequence = 0L,
            timestamp = 0L,
            stream = "SYS",
            text = "",
        )
        // 断言本身无意义（值为空）；价值全在编译期。
        assertTrue(true)
    }

    // ---------------------------------------------------------------- 工具

    private fun schemaJson(): String = schemaFile(RootFlowDatabase.VERSION).readText()

    /** 取某张表在 schema JSON 里声明的列名。 */
    private fun columnsOf(
        json: String,
        tableName: String,
    ): Set<String> {
        val tableIndex = json.indexOf("\"tableName\": \"$tableName\"")
        require(tableIndex >= 0) { "table $tableName not found in schema" }
        // 导出的 JSON 是带换行的美化格式（"fields": 与其后的 [ 不同行），故用空白容忍的正则
        val fieldsMatch = Regex(""""fields":\s*\[""").find(json, tableIndex)
        require(fieldsMatch != null) { "no fields section for $tableName" }
        val fieldsEnd = json.indexOf(']', fieldsMatch.range.first)
        val section = json.substring(fieldsMatch.range.first, fieldsEnd)
        return Regex(""""columnName":\s*"([^"]+)"""")
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * 定位导出的 schema 文件。
     *
     * 单测工作目录可能是仓库根或 `app/`（取决于 Gradle 调用方式），故**逐级向上**查找，
     * 不写死相对路径。
     */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/${RootFlowDatabase::class.java.name}/$version.json"
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            File(directory, relative).takeIf { it.isFile }?.let { return it }
            File(directory, "app/$relative").takeIf { it.isFile }?.let { return it }
            directory = directory.parentFile
        }
        return File(relative)
    }

    private companion object {
        /**
         * 表名 → 列名清单。
         *
         * 规则：标了 `@ColumnInfo(name = …)` 的用注解名；未标的用属性名。
         * 本项目的实体除 `id` 外都显式指定了 snake_case 列名。
         */
        val EXPECTED_COLUMNS: Map<String, List<String>> =
            mapOf(
                "scripts" to
                    listOf(
                        "id",
                        "name",
                        "language",
                        "enabled",
                        "resident",
                        "timeoutSec",
                        "autoDisableOnFail",
                        "runOnSafeMode",
                        "content_path",
                        "content_sha256",
                        "created_at",
                        "updated_at",
                    ),
                // 总开关重构：`triggers` 改名 `script_events`（索引也从复合改为单列）
                "script_events" to
                    listOf(
                        "id",
                        "script_id",
                        "event_type",
                        "params",
                        "enabled",
                        "created_at",
                    ),
                // 总开关本体（单行表）
                "app_switch" to
                    listOf(
                        "id",
                        "master_enabled",
                        "enabled_at",
                        "disabled_at",
                        "updated_at",
                    ),
                "runs" to
                    listOf(
                        "id",
                        "script_id",
                        "trigger_event",
                        "started_at",
                        "finished_at",
                        "exit_code",
                        "log_entry_count",
                        "dropped_log_entries",
                    ),
                "run_log_entries" to
                    listOf(
                        "id",
                        "run_id",
                        "sequence",
                        "timestamp",
                        "stream",
                        "text",
                    ),
            )
    }
}
