package com.rootflow.ui.scripts

import com.rootflow.data.db.FakeDatabase
import com.rootflow.data.fs.FakeRootShell
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.data.script.ScriptRepositoryImpl
import com.rootflow.data.trigger.TriggerRepositoryImpl
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.WriteResult
import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `save → 文件系统 → load` 的**跨界往返**单测（阶段 6c）。
 *
 * ## ★ 这个测试类是 `AGENT_PROTOCOL.md §8.0` 的直接落点，不是"又一组仓库单测"
 * `ScriptRepository.save` 的契约是**跨两个存储**的：
 * ```
 * Room（元数据）  +  /data/local/tmp/rootflow/scripts/<id>/main.sh（正文）
 * ```
 * 而 6b 的真机缺陷（D10：`RunContext.env` 从未注入脚本）正是同一形态 ——
 * **写入侧与消费侧各自断言、中间那一跳没人测**，三处全绿而功能是坏的：
 *
 * | 位置 | 断言 | 结果 |
 * |---|---|---|
 * | 写入侧 | `save` 返回 `Ok` | ✅ 绿，但证明不了文件真的落了盘 |
 * | 读取侧 | `load` 解析出正文 | ✅ 绿，但用的是自己塞的桩 |
 * | **接缝** | **写入侧产出的东西真的出现在读取侧** | ❌ **6c 补上** |
 *
 * 因此本类**必须**用真实 [ScriptRepositoryImpl] + [FakeRootShell]（解释
 * `mkdir -p` / `base64 -d` / `cat` / `rm -rf` 并维护内存文件表）+ [FakeDatabase]，
 * 从 `save` 一路走到 `load`。断言落在**最终产物**（文件表里真的有那份正文）上，
 * 而不是"`save` 返回了 Ok"。
 */
class ScriptRepositoryRoundTripTest {
    @Test
    @DisplayName("★ save(新) → 文件系统里真有 main.sh（内容为规范化后的正文）→ load 读回同一 content")
    fun `saving a new script writes the body to the filesystem and load reads it back`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager)) { 1_000L }

            val saved = repo.save(newScript(name = "备份", content = "echo hi"))
            assertTrue(saved is WriteResult.Ok, "保存应成功：$saved")
            val script = (saved as WriteResult.Ok).value

            // ① 元数据进了 Room，且指向**真实相对路径**（不是插行时的占位路径）
            val row = db.scripts.getValue(script.id)
            assertEquals("scripts/${script.id}/main.sh", row.contentPath, "必须回填真实路径，而不是 scripts/pending/main.sh")
            assertEquals(
                "echo hi\n",
                shell.files[RootFlowPaths.scriptBody(script.id, "sh")],
                "★ 正文必须真的落进文件系统（这是 6b 的 D10 教训要求的「中间那一跳」断言）",
            )

            // ② 从**同一个仓库**读回：这一段证明写入侧与读取侧接得上
            val loaded = repo.load(script.id)
            assertTrue(loaded is ScriptLoadResult.Ok, "装载应成功：$loaded")
            assertEquals("echo hi", (loaded as ScriptLoadResult.Ok).script.content, "正文应无损往返")
            assertEquals("备份", loaded.script.name)

            // ③ 摘要必须与文件内容一致（否则下一次 load 会报 Corrupted —— 一条假阳性缺陷）
            val reread = repo.load(script.id)
            assertTrue(reread is ScriptLoadResult.Ok, "同一份文件读两次都该是 Ok（摘要必须自洽）：$reread")
        }

    @Test
    @DisplayName("★ save(更新) → 正文被覆盖 + contentSha256 更新（不是只改元数据）")
    fun `updating a script overwrites the body and refreshes the digest`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val original = (repo.save(newScript(name = "s", content = "echo v1")) as WriteResult.Ok).value
            val digestBefore = db.scripts.getValue(original.id).contentSha256

            val updated =
                repo.save(original.copy(name = "s2", content = "echo v2")) as WriteResult.Ok

            assertEquals(
                "echo v2\n",
                shell.files[RootFlowPaths.scriptBody(original.id, "sh")],
                "★ 正文必须被真的覆盖（只更新元数据会让脚本行为与界面不一致）",
            )
            assertNotEquals(digestBefore, db.scripts.getValue(original.id).contentSha256, "摘要必须随正文更新")
            // 元数据也要落库：旧断言写的是 `enabled == false`（写反了 —— 本次保存没碰 enabled，
            // `newScript` 建的是启用态，它只会保持 `true`）。用"改名真的生效"来钉同一件事。
            val after = db.scripts.getValue(original.id)
            assertEquals("s2", after.name, "元数据（改名）也必须落库，不能只改正文")
            assertTrue(after.enabled, "更新不得顺手改动启用状态（本次保存没碰过它）")
            assertEquals("echo v2", (repo.load(original.id) as ScriptLoadResult.Ok).script.content)
            assertEquals(updated.value.id, original.id, "更新不得换 id")
        }

    @Test
    @DisplayName("★ save(更新) 必须落库**全部**可编辑字段（resident 曾被字段白名单静默丢掉）")
    fun `updating a script persists every editable field`() =
        runTest {
            // ## 这条用例的来历（P4 真机验证抓到的真缺陷）
            // `updateExisting` 曾写成一个**字段白名单**：
            // `existing.copy(name = …, enabled = …, timeoutSec = …, autoDisableOnFail = …,
            // runOnSafeMode = …, contentSha256 = …, updatedAt = …)`。
            // `resident`（总开关重构新增的字段）不在名单里 ⇒ 真机上「拨到常驻 → 保存 →
            // 列表仍显示单次」，而**保存是"成功"的、一行告警都没有**。
            // 因此这条用例逐字段核对：以后再加可编辑字段，忘了接线就会在这里红。
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val original = (repo.save(newScript(name = "s", content = "echo v1")) as WriteResult.Ok).value

            val edited =
                original.copy(
                    name = "s2",
                    enabled = false,
                    timeoutSec = 42,
                    autoDisableOnFail = true,
                    runOnSafeMode = true,
                    resident = true,
                    content = "echo v2",
                )
            assertTrue(repo.save(edited) is WriteResult.Ok, "保存应成功")

            val row = db.scripts.getValue(original.id)
            assertEquals("s2", row.name)
            assertEquals(false, row.enabled)
            assertEquals(42, row.timeoutSec)
            assertEquals(true, row.autoDisableOnFail)
            assertEquals(true, row.runOnSafeMode)
            assertEquals(
                true,
                row.resident,
                "★ 运行方式必须落库 —— 它曾在字段白名单里被静默丢掉（保存成功、库里没变）",
            )
            assertEquals(original.createdAt, row.createdAt, "创建时间不得被更新改掉")
        }

    @Test
    @DisplayName("★ 新建时正文写失败 → 元数据**回滚**（不留指向不存在文件的悬挂行）")
    fun `a failed body write during insert rolls the metadata row back`() =
        runTest {
            val db = FakeDatabase()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(failingManager()))

            val failed = repo.save(newScript(name = "y", content = "echo y"))

            assertTrue(failed is WriteResult.Failed, "写入失败必须回报失败：$failed")
            assertTrue(
                db.scripts.isEmpty(),
                "失败后不得留下指向不存在文件的元数据行（否则列表里有一条打不开的脚本）",
            )
        }

    @Test
    @DisplayName("★ 更新时正文写失败 → 旧行与旧摘要都不动")
    fun `a failed body write during update leaves the old row untouched`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val original = (repo.save(newScript(name = "keep", content = "echo v1")) as WriteResult.Ok).value
            val rowBefore = db.scripts.getValue(original.id)
            val digestBefore = rowBefore.contentSha256

            val failingRepo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(failingManager()))
            val failed = failingRepo.save(original.copy(name = "changed", content = "echo v2"))

            assertTrue(failed is WriteResult.Failed, "写入失败必须回报失败：$failed")
            val rowAfter = db.scripts.getValue(original.id)
            assertEquals("keep", rowAfter.name, "更新失败时旧元数据必须原样保留")
            assertEquals(digestBefore, rowAfter.contentSha256, "摘要不得被改成一个指向不存在内容的哈希")
            assertEquals(
                "echo v1\n",
                shell.files[RootFlowPaths.scriptBody(original.id, "sh")],
                "旧正文也要还在",
            )
        }

    @Test
    @DisplayName("★ delete → 行消失 + 触发器级联消失 + 正文目录被清空")
    fun `deleting a script cascades to triggers and clears the body directory`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))
            val triggerRepo = TriggerRepositoryImpl(db.scriptEventDao)
            val id = (repo.save(newScript(name = "s", content = "echo s")) as WriteResult.Ok).value.id
            triggerRepo.replaceForScript(
                id,
                listOf(
                    Trigger(0, id, "boot", TriggerParams(), createdAt = 1L),
                    Trigger(0, id, "screen_off", TriggerParams(), createdAt = 2L),
                ),
            )
            assertEquals(2, triggerRepo.countForScript(id), "前置：订阅已建")

            val result = repo.delete(id)

            assertTrue(result is WriteResult.Ok, "删除应成功：$result")
            assertTrue(db.scripts.isEmpty(), "元数据行应被删除")
            assertTrue(db.scriptEvents.isEmpty(), "订阅应由 ON DELETE CASCADE 一并删除")
            assertEquals(0, triggerRepo.countForScript(id))
            assertTrue(
                shell.files.keys.none { it.startsWith(RootFlowPaths.scriptDir(id)) },
                "正文目录应被清空：${shell.files.keys}",
            )
            // 删除后 load 必须是显式 NotFound，而不是"读到一个空脚本"
            assertEquals(
                ScriptLoadResult.NotFound,
                repo.load(id),
                "删除后不得还能 load（空正文会被上层当成「脚本什么也不做」）",
            )
        }

    @Test
    @DisplayName("lua 语言的正文落在 main.lua（扩展名与语言同源）")
    fun `the body extension follows the language`() =
        runTest {
            val db = FakeDatabase()
            val shell = FakeRootShell()
            val repo = ScriptRepositoryImpl(db.scriptDao, RootFileStore(shell.manager))

            val id =
                (
                    repo.save(newScript(name = "l", content = "print('hi')", language = "lua")) as WriteResult.Ok
                ).value.id

            assertEquals("scripts/$id/main.lua", db.scripts.getValue(id).contentPath)
            assertTrue(shell.files.containsKey(RootFlowPaths.scriptBody(id, "lua")), "实际文件表=${shell.files.keys}")
            assertNull(shell.files[RootFlowPaths.scriptBody(id, "sh")], "不得同时留下 .sh 副本")
        }

    // ------------------------------------------------------------ 工具

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
            resident = false,
            timeoutSec = 0,
            autoDisableOnFail = false,
            runOnSafeMode = false,
            content = content,
            contentSha256 = null,
            createdAt = 0L,
            updatedAt = 0L,
        )

    /**
     * 永远失败的控制通道（模拟 root 不可用 / 目录不可写）。
     *
     * 用 MockK 而不是给 `FakeRootShell` 加一个开关：本类要测的是**仓库的失败语义**
     * （回滚），而不是文件系统的行为 —— `FakeRootShell` 的职责是"解释正常的命令"。
     */
    private fun failingManager(): RootShellManager =
        mockk<RootShellManager>().also { manager ->
            coEvery { manager.execControl(any()) } returns
                ShellResult(stdout = "", stderr = "boom", exitCode = 1)
        }
}
