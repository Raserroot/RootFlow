package com.rootflow.data.repository

import com.rootflow.data.db.FakeDatabase
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.fs.FakeRootShell
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.data.run.RunHistoryBatch
import com.rootflow.data.run.RunHistoryReader
import com.rootflow.data.run.RunHistoryWriter
import com.rootflow.data.script.ScriptRepositoryImpl
import com.rootflow.data.trigger.TriggerRepositoryImpl
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.RunMeta
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.WriteResult
import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 仓库与运行历史写入器单测（阶段 3a）。
 *
 * 依赖：`FakeRootShell`（解释 root 命令、内存文件表）+ `FakeDatabase`（内存 DAO）。
 * **不需要真机、不需要 Robolectric**。
 */
class RepositoriesAndRunHistoryTest {
    // ---------------------------------------------------------------- ScriptRepository

    @Test
    fun `save then load keeps body and metadata in two places consistently`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager)) { 1_000L }

            val saved = repo.save(newScript(name = "hello", content = "echo hi"))
            assertTrue(saved is WriteResult.Ok, "保存应成功：$saved")
            val script = (saved as WriteResult.Ok).value

            // DB 只有元数据；正文在文件系统（需求 §3.2/§3.3）
            assertEquals("scripts/${script.id}/main.sh", db.scripts[script.id]?.contentPath)
            assertEquals("echo hi\n", shell.files[RootFlowPaths.scriptBody(script.id, "sh")])

            val loaded = repo.load(script.id)
            assertTrue(loaded is ScriptLoadResult.Ok, "装载应成功：$loaded")
            assertEquals("echo hi", (loaded as ScriptLoadResult.Ok).script.content, "正文应无损往返")
            assertEquals("hello", loaded.script.name)
        }

    @Test
    fun `save rolls back the row when the body write fails`() =
        runTest {
            val db = FakeDatabase()
            // 永远失败的控制通道：模拟 root 不可用 / 目录不可写
            val failingManager =
                mockk<RootShellManager>().also { manager ->
                    coEvery { manager.execControl(any()) } returns
                        ShellResult(stdout = "", stderr = "boom", exitCode = 1)
                }
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(failingManager))

            val failed = repo.save(newScript(name = "y", content = "echo y"))

            assertTrue(failed is WriteResult.Failed, "写入失败必须回报失败：$failed")
            assertTrue(
                db.scripts.isEmpty(),
                "失败后不得留下指向不存在文件的元数据行（悬挂元数据）",
            )
        }

    @Test
    fun `load reports Missing when the body file disappeared`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val id = (repo.save(newScript(name = "gone", content = "echo gone")) as WriteResult.Ok).value.id

            shell.files.remove(RootFlowPaths.scriptBody(id, "sh"))

            assertEquals(ScriptLoadResult.Missing, repo.load(id), "缺失必须是显式状态")
        }

    @Test
    fun `load reports Corrupted when the body was modified externally`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val id = (repo.save(newScript(name = "tampered", content = "echo original")) as WriteResult.Ok).value.id

            shell.files[RootFlowPaths.scriptBody(id, "sh")] = "echo tampered\n"

            val loaded = repo.load(id)
            assertTrue(loaded is ScriptLoadResult.Corrupted, "外部改动必须被检出：$loaded")
        }

    @Test
    fun `deleting a script removes its triggers and its body directory`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val triggerRepo = TriggerRepositoryImpl(db.scriptEventDao)
            val id = (repo.save(newScript(name = "s", content = "echo s")) as WriteResult.Ok).value.id
            triggerRepo.replaceForScript(id, listOf(Trigger(0, id, "boot", TriggerParams(), createdAt = 1L)))

            repo.delete(id)

            assertTrue(db.scripts.isEmpty(), "元数据行应被删除")
            assertTrue(db.scriptEvents.isEmpty(), "订阅应随脚本级联删除")
            assertTrue(
                shell.files.keys.none { it.startsWith(RootFlowPaths.scriptDir(id)) },
                "正文目录应被清空：${shell.files.keys}",
            )
        }

    // ---------------------------------------------------------------- TriggerRepository

    @Test
    fun `forEvent returns every subscription of that event`() =
        runTest {
            val db = FakeDatabase()
            val repo = TriggerRepositoryImpl(db.scriptEventDao)
            // `forEvent` 带 `AND enabled = 1`（`enabled = false` = 停用但保留配置）。
            // 本条喂进去的都是启用行，因此这里只验"按事件过滤"这条主线；
            // 禁用行不返回由 `RoomTriggerScheduleProviderTest` 钉住。
            repo.replaceForScript(1L, listOf(Trigger(0, 1L, "boot", TriggerParams(payload = "a"), createdAt = 1L)))
            repo.replaceForScript(
                2L,
                listOf(
                    Trigger(0, 2L, "boot", TriggerParams(), createdAt = 2L),
                    Trigger(0, 2L, "screen_off", TriggerParams(), createdAt = 3L),
                ),
            )

            val boot = repo.forEvent("boot")

            assertEquals(2, boot.size, "订阅了 boot 的两个脚本都应返回")
            assertEquals("a", boot.first { it.scriptId == 1L }.params.payload, "params JSON 应被解码")
            assertEquals(1, repo.forEvent("screen_off").size, "只返回订阅该事件的脚本")
            assertTrue(repo.forEvent("unlock").isEmpty(), "没人订阅的事件返回空")
        }

    @Test
    fun `params survive the json round trip through the database`() =
        runTest {
            val db = FakeDatabase()
            val repo = TriggerRepositoryImpl(db.scriptEventDao)
            val params =
                TriggerParams(
                    exact = true,
                    intervalMinutes = 30,
                    hourOfDay = 7,
                    minuteOfHour = 5,
                    payload = "x,y",
                )

            val saved =
                repo.replaceForScript(1L, listOf(Trigger(0, 1L, "time", params, createdAt = 1L)))
                    as WriteResult.Ok

            assertEquals(1, saved.value.size)
            assertTrue(saved.value.single().id > 0, "落库后应拿到自增 id")
            assertEquals(params, repo.forEvent("time").single().params)
        }

    /**
     * ★ **`replaceForScript` 的集合语义**（总开关重构的核心不变量）。
     *
     * 旧模型要"逐条 save/update 再删多余的"，因此**部分生效**是可能的失败形态。
     * 新模型是集合替换：一次调用保证"库里的订阅集合 == 传入的集合"。
     * 本用例钉住三件事：整体替换、重复行被去重、**不会动别的脚本**。
     */
    @Test
    fun `replaceForScript replaces the whole set and never touches other scripts`() =
        runTest {
            val db = FakeDatabase()
            val repo = TriggerRepositoryImpl(db.scriptEventDao)

            // 脚本 1 先订阅两个；脚本 2 订阅一个
            repo.replaceForScript(
                1L,
                listOf(
                    Trigger(0, 1L, "boot", TriggerParams(), createdAt = 1L),
                    Trigger(0, 1L, "unlock", TriggerParams(), createdAt = 1L),
                ),
            )
            repo.replaceForScript(2L, listOf(Trigger(0, 2L, "screen_off", TriggerParams(), createdAt = 1L)))

            // 把脚本 1 换成完全不同的集合
            repo.replaceForScript(
                1L,
                listOf(Trigger(0, 1L, "unlock", TriggerParams(), createdAt = 9L)),
            )

            assertEquals(
                listOf("unlock"),
                repo.observeForScript(1L).first().map { it.eventType },
                "脚本 1 的订阅应被**整体替换**（boot 消失）",
            )
            assertEquals(
                listOf("screen_off"),
                repo.observeForScript(2L).first().map { it.eventType },
                "★ 脚本 2 的订阅必须原封不动 —— 传进来的 scriptId 可能不属于本脚本，",
            )
            assertEquals(1, repo.countForScript(1L))
            assertEquals(1, repo.countForScript(2L))
        }

    /**
     * ★ **[Trigger.scriptId] 会被强制覆写为 [replaceForScript] 的 `scriptId` 参数**。
     *
     * 这是实现里一个**必要**的保证（不是形式参数）：调用方（编辑器）从 `all()` 读全库、
     * 只改自己那一份，因此传进来的 [Trigger.scriptId] 可能是**别的脚本**的 id。
     * 若不覆写，别的脚本的订阅会被**搬到**当前脚本名下
     * （表现为"改 A 脚本，B 脚本的订阅消失了"）——很难发现的一类数据损坏。
     */
    @Test
    fun `replaceForScript forces ownership to the given script id`() =
        runTest {
            val db = FakeDatabase()
            val repo = TriggerRepositoryImpl(db.scriptEventDao)
            repo.replaceForScript(2L, listOf(Trigger(0, 2L, "boot", TriggerParams(), createdAt = 1L)))

            // 传进来的行伪装成 scriptId = 2（别的脚本），但目标是脚本 1
            repo.replaceForScript(1L, listOf(Trigger(0, 2L, "boot", TriggerParams(), createdAt = 1L)))

            // 库里此刻应有**两行**：脚本 2 自己那条（原封不动）+ 脚本 1 刚写的那条（归属被强制覆写）。
            // 旧断言的 `listOf(1L)` 漏算了前者 —— 而"只剩一条"恰恰说明脚本 2 的订阅**被搬走了**，
            // 于是那条断言在本用例要防的缺陷真的发生时**也会失败**，但它同时在正确实现下失败
            // ⇒ 它测不出"覆写有没有生效"，只测出"库里恰好一行"。现在分开钉两件事。
            assertEquals(
                setOf(1L, 2L),
                repo.all().map { it.scriptId }.toSet(),
                "两行都必须存在：脚本 2 的订阅没被搬走，脚本 1 拿到的是自己那一份",
            )
            assertEquals(1, repo.countForScript(1L), "脚本 1 必须真的有那条订阅")
            assertEquals(
                1,
                repo.countForScript(2L),
                "★ 脚本 2 的订阅必须原封不动（不被搬走、也不被复制成两条）",
            )
        }

    @Test
    fun `corrupted params json falls back to defaults and warns`() =
        runTest {
            val db = FakeDatabase()
            val warnings = mutableListOf<String>()
            val repo = TriggerRepositoryImpl(db.scriptEventDao, onParamsWarning = warnings::add)
            // 直接塞一条损坏行（模拟手工改库 / 旧版本数据）
            db.scriptEventDao.insert(
                ScriptEventRow(
                    scriptId = 1L,
                    eventType = "boot",
                    params = "{ broken",
                    enabled = true,
                    createdAt = 1L,
                ),
            )

            val found = repo.forEvent("boot")

            assertEquals(TriggerParams(), found.single().params, "损坏记录必须回退默认值而不是崩")
            assertTrue(warnings.isNotEmpty(), "回退必须可见")
        }

    // ---------------------------------------------------------------- RunHistoryWriter

    @Test
    fun `writer registers the run row and persists entries`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, clock = { 5_000L })

            writer.record(batch(runId = "run-a", entries = entries(3), finished = false))

            assertEquals(1, db.runs.size)
            assertEquals(1L, db.runs["run-a"]?.scriptId)
            assertEquals("boot", db.runs["run-a"]?.triggerEvent)
            assertEquals(3, db.entriesOf("run-a").size)
            assertEquals(listOf(0L, 1L, 2L), db.entriesOf("run-a").map { it.sequence })
        }

    @Test
    fun `writer is idempotent for duplicated batches`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao)
            val duplicated = batch(runId = "run-b", entries = entries(3), finished = false)

            writer.record(duplicated)
            writer.record(duplicated)

            assertEquals(3, db.entriesOf("run-b").size, "(runId, sequence) 唯一索引必须让重复写入幂等")
        }

    @Test
    fun `writer caps entries per run and reports the loss`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, maxEntriesPerRun = 10)

            writer.record(batch(runId = "run-c", entries = entries(25), finished = true))

            assertEquals(10, db.entriesOf("run-c").size, "单 run 行数必须被上限截断")
            assertEquals(15, db.runs["run-c"]?.droppedLogEntries, "被丢弃的条数必须可见（不静默）")
        }

    @Test
    fun `writer extracts the exit code from the runtime SYS line`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, clock = { 7_777L })
            val entries =
                entries(1) +
                    LogEntry("run-d", 1, 2L, LogStream.SYS, "script -1 exited with code 143")

            writer.record(batch(runId = "run-d", entries = entries, finished = true))

            assertEquals(143, db.runs["run-d"]?.exitCode, "退出码应从 SYS 行提取（偏离项 D6）")
            assertEquals(7_777L, db.runs["run-d"]?.finishedAt)
        }

    @Test
    fun `writer warns when a finished run has no extractable exit code`() =
        runTest {
            val db = FakeDatabase()
            val warnings = mutableListOf<String>()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, onWarning = warnings::add)

            writer.record(batch(runId = "run-e", entries = entries(1), finished = true))

            assertEquals(null, db.runs["run-e"]?.exitCode)
            assertTrue(
                warnings.any { it.contains("exit code") },
                "D6 耦合点失效必须告警，不能静默写 null：$warnings",
            )
        }

    @Test
    fun `writer warns about pipeline level drops`() =
        runTest {
            val db = FakeDatabase()
            val warnings = mutableListOf<String>()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, onWarning = warnings::add)

            writer.record(
                RunHistoryBatch(
                    runId = "run-f",
                    meta = RunMeta(scriptId = 1L, triggerEvent = null),
                    entries = entries(1),
                    droppedEntries = 42,
                    runFinished = false,
                ),
            )

            assertTrue(
                warnings.any { it.contains("42") },
                "管道级丢弃必须单独告警（与上限截断区分开）：$warnings",
            )
        }

    @Test
    fun `retention keeps only the newest runs and cascades their logs`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao, retainedRuns = 3)

            repeat(5) { index ->
                writer.record(
                    batch(
                        runId = "run-$index",
                        entries = listOf(LogEntry("run-$index", 0, index.toLong(), LogStream.STDOUT, "line")),
                        finished = true,
                    ),
                )
            }

            assertEquals(3, db.runs.size, "只应保留最近 3 次运行")
            assertEquals(3, db.logEntries.size, "被淘汰运行的日志必须级联清除")
            assertTrue(db.runs.containsKey("run-4"), "保留的应是最新的：${db.runs.keys}")
        }

    // ---------------------------------------------------------------- RunHistoryReader

    @Test
    fun `reader can page logs of a finished run`() =
        runTest {
            val db = FakeDatabase()
            val writer = RunHistoryWriter(db.runDao, db.runLogDao)
            writer.record(batch(runId = "run-g", entries = entries(25), finished = true))
            val reader = RunHistoryReader(db.runDao, db.runLogDao)

            val page = reader.readLogs("run-g", limit = 10, offset = 10)

            assertEquals(10, page.size)
            assertEquals(listOf(10L, 11L, 12L), page.take(3).map { it.sequence })
            assertEquals(25, reader.countLogs("run-g"))
            assertEquals("run-g", reader.find("run-g")?.runId)
        }

    // ---------------------------------------------------------------- 工具

    private fun newScript(
        name: String,
        content: String,
        language: String = "shell",
    ): Script =
        Script(
            id = 0L,
            name = name,
            language = language,
            enabled = true,
            timeoutSec = 0,
            autoDisableOnFail = false,
            runOnSafeMode = false,
            content = content,
            contentSha256 = null,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun entries(count: Int): List<LogEntry> =
        List(count) { index ->
            LogEntry(
                runId = "placeholder",
                sequence = index.toLong(),
                timestamp = index.toLong(),
                stream = LogStream.STDOUT,
                text = "line-$index",
            )
        }

    private fun batch(
        runId: String,
        entries: List<LogEntry>,
        finished: Boolean,
    ): RunHistoryBatch =
        RunHistoryBatch(
            runId = runId,
            meta = RunMeta(scriptId = 1L, triggerEvent = "boot"),
            entries = entries.map { it.copy(runId = runId) },
            droppedEntries = 0,
            runFinished = finished,
        )
}
