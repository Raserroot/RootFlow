package com.rootflow.data.run

import android.util.Log
import com.rootflow.data.event.FakeCircuitBreaker
import com.rootflow.data.event.RecordingOutcomeSink
import com.rootflow.data.log.LogPipelineImpl
import com.rootflow.domain.event.RunAdmissionGate
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogTail
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunMeta
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.runtime.RunContext
import com.rootflow.runtime.RunHandle
import com.rootflow.runtime.ScriptEntity
import com.rootflow.runtime.ShellScriptRuntime
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.rootflow.runtime.LogStream as RuntimeLogStream

/**
 * [TriggeredScriptRunner] 单测（阶段 3d 的核心执行链路）。
 *
 * ## 覆盖重点（3d 方案 §10 的 14 条）
 * 1. `load` 的**四种失败**各自不启动，且占用名额为 0
 * 2. `enabled=false` 拒绝（需求 §4.4）
 * 3. 闸门的两种拒绝（重入 / 全局满）
 * 4. 成功路径：`runMeta` 带上 scriptId/triggerEvent、`batchSink` 透传、正文与环境变量映射正确
 * 5. **结束释放闸门**（未释放会让重入拒绝永久生效——比不拒绝更糟）
 * 6. 启动路径抛错也要归还名额
 *
 * ## 注入形态：`ShellScriptRuntime` 用 MockK，其余全是手写假件
 * `ScriptRunCoordinator` 的构造形参类型是**具体类** `ShellScriptRuntime`（1b 起的既有形态，
 * 本阶段不改 runtime 公开接口），因此这里用 MockK 把它的 `run` 打桩成"返回一个假运行时
 * 造的句柄"——这样被覆盖的仍是**真实的** `ScriptRunCoordinator` 与 `LogPipeline` 调用路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TriggeredScriptRunnerTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    // ---------------------------------------------------------------- ⓪ 总闸（P3）

    /**
     * ★ **不变量 1**（总开关重构 P3）：总闸关闭 ⇒ 任何脚本都不得启动，
     * 而且这道闸门在**装载之前** —— 连一次经 root 通道的正文读取都不做。
     *
     * 断言 `repository.loaded` 为空才是本用例的核心：它把"闸门排在第几行"从一条
     * 代码约定变成一条**可测的事实**。谁要是把总闸检查挪到 `load` 之后，
     * 本用例立刻失败 —— 而那正是"拒绝一次启动还白付一次 su 往返"的退化。
     */
    @Test
    fun `the master switch rejects before any load happens`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(1L))) }
            val fixture = fixture(repo, masterSwitch = FakeMasterSwitch(initiallyEnabled = false))

            val accepted = fixture.runner.start(1L, "boot", null)

            assertFalse(accepted, "总闸关闭 ⇒ 必须拒绝")
            assertTrue(
                fixture.repository.loaded.isEmpty(),
                "★ 闸门必须在 load **之前**：实测装载了 ${fixture.repository.loaded}",
            )
            assertTrue(fixture.runtime.runs.isEmpty(), "不得触碰 runtime")
            assertEquals(0, fixture.gate.activeCount, "不得占用闸门名额")
        }

    // ---------------------------------------------------------------- ★ P5：事件通道

    /**
     * ★ **每次运行都开一条通道，且它的 runId 与本次运行完全一致**。
     *
     * 通道路径含 `runId`（`ipc/<runId>.q`）⇒ 真机判读时 logcat 里的 runId 与设备上的
     * FIFO 文件名**一眼能对上**。而"按 runId 而不是按 scriptId"是为了**跨轮次不串队列**：
     * 常驻脚本重启后拿到的是一条全新管道，读不到上一轮积压的遗留事件。
     */
    @Test
    fun `every run opens its own event channel and injects the fifo path`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(1L))) }
            val channel = FakeEventChannel(openResult = "/data/local/tmp/rootflow/ipc/abc.q")
            // 用 `Hanging` 让"这次运行仍在进行中"成为可观测状态 —— 本用例要断言
            // **通道的 runId 与本次运行的 runId 一致**，而运行一结束注册表就把它清了。
            // 收集协程放 `backgroundScope`（§9.2）：它会一直挂起。
            val fixture =
                fixture(
                    repo,
                    eventChannel = channel,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            val accepted = fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            assertTrue(accepted)
            assertEquals(1, channel.opened.size, "一次运行开一条通道")
            val opened = channel.opened.single()
            assertEquals(
                opened.first,
                fixture.sessions
                    .activeRuns()
                    .single()
                    .first,
                "★ 通道的 runId 必须与本次运行的 runId 一致（判读要对得上）",
            )
            assertEquals(
                1L,
                opened.second,
                "★ 通道必须记在它所属的 scriptId 下 —— 投递侧（dispatcher）只知道 scriptId",
            )
            assertEquals(
                "/data/local/tmp/rootflow/ipc/abc.q",
                fixture.runtime.runs
                    .single()
                    .second.env[TriggeredScriptRunner.ENV_EVENT_FIFO],
                "FIFO 路径必须注入脚本环境 —— 脚本靠它 read 后续事件",
            )
        }

    // ★ 11e 补丁4 的「会话 / 闸门必须活到运行结束」这条语义，**已由上面既有的**
    //   `a run whose source hangs keeps the gate held` 与
    //   `a script is rejected while its own run is still in flight` 覆盖，
    //   此处不再重复断言。
    //
    //   它们此前一直是**假绿**：管道假件当时在 `startRun` 里同步收集 source，
    //   恰好替生产把那层包装 job 撑住了。补齐"假件与生产同形"之后，
    //   把 `RunSession.job` 换成立刻完成的空作业会让本文件 **4 条用例同时失败**
    //   （已实测），这正是本缺陷的回归防护。

    /**
     * ★ **`runId` 必须原样成为 `RunHandle.id`**（11e 补丁4 的第二处缺陷）。
     *
     * 此前 `ShellScriptRuntime.run` 调 `createRunHandle { }` **不传 id**，于是句柄自己
     * 造了一个随机 UUID，而日志 / `RunSessionRegistry` / `terminateRun` 用的是另一个
     * `runId`。后果是 `terminateRun(runId)` 永远找不到 `.rf_pgid_<id>` 文件 ——
     * **超时终止与熔断的"杀进程"全是空操作**，日志上却只显示"pgid 未解析"，
     * 看起来像设备问题。
     *
     * 现在由调度侧生成 `runId` 并贯穿到底，这条断言把两者钉在一起。
     */
    @Test
    fun `the session runId is handed down to the runtime unchanged`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(2L, okScript(script(2L))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            fixture.runner.start(2L, "boot", null)
            advanceUntilIdle()

            val sessionRunId =
                fixture.sessions
                    .activeRuns()
                    .single()
                    .first
            assertEquals(
                sessionRunId,
                fixture.runtime.runIds.single(),
                "★ 会话的 runId 必须原样传给 runtime（它同时决定 .rf_pgid_<id> 的文件名）",
            )
        }

    /**
     * 开不出通道时**不注入**该变量。
     *
     * 写一个空串会让脚本无法区分"宿主没提供通道"与"通道路径是空的"：前者应当让它走
     * "没有事件可读"的分支，后者会让它去读一个空路径。**通道开不出来不得阻止脚本运行**
     * —— 常驻脚本本身仍然有价值（它自己的循环），事件只是附加的通知。
     */
    @Test
    fun `a channel that cannot be opened leaves the fifo variable unset`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(1L))) }
            val fixture = fixture(repo, eventChannel = FakeEventChannel(openResult = null))

            val accepted = fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            assertTrue(accepted, "通道开不出来**不得**阻止脚本运行")
            val env =
                fixture.runtime.runs
                    .single()
                    .second.env
            assertFalse(
                env.containsKey(TriggeredScriptRunner.ENV_EVENT_FIFO),
                "没有通道 ⇒ 不得写该键（否则脚本分不清「没通道」与「路径是空的」）",
            )
        }

    /** 运行结束 ⇒ 关掉**它自己**那条通道（挂在收尾钩子上，与 unregister 同寿）。 */
    @Test
    fun `the channel is closed when the run finishes`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(1L))) }
            val channel = FakeEventChannel()
            val fixture = fixture(repo, eventChannel = channel)

            fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            assertEquals(1, channel.closed.size, "运行结束必须关通道")
            assertEquals(
                channel.opened.single().first,
                channel.closed.single(),
                "关的必须是**它自己**那条（按 runId 对上）",
            )
        }

    // ---------------------------------------------------------------- ① 装载失败

    @Test
    fun `missing body file does not start the script`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, ScriptLoadResult.Missing) })

            val accepted = fixture.runner.start(1L, "boot", null)

            assertFalse(accepted, "正文文件缺失时不得启动")
            assertTrue(fixture.runtime.runs.isEmpty(), "不得触碰 runtime")
            assertEquals(0, fixture.gate.activeCount, "失败分支不得占用闸门名额")
        }

    @Test
    fun `corrupted body does not start the script`() =
        runTest {
            val repo =
                FakeScriptRepository().apply {
                    put(1L, ScriptLoadResult.Corrupted(expected = "sha256:aa", actual = "sha256:bb"))
                }
            val fixture = fixture(repo)

            assertFalse(fixture.runner.start(1L, "boot", null))
            assertTrue(fixture.runtime.runs.isEmpty())
            assertEquals(0, fixture.gate.activeCount)
        }

    @Test
    fun `not found script does not start the script`() =
        runTest {
            val fixture = fixture(FakeScriptRepository())

            assertFalse(fixture.runner.start(42L, "boot", null))
            assertEquals(listOf(42L), fixture.repository.loaded)
            assertTrue(fixture.runtime.runs.isEmpty())
        }

    @Test
    fun `unavailable root channel does not start the script`() =
        runTest {
            val repo =
                FakeScriptRepository().apply {
                    put(1L, ScriptLoadResult.Unavailable(reason = "cat failed (exit=1): denied"))
                }
            val fixture = fixture(repo)

            assertFalse(fixture.runner.start(1L, "boot", null))
            assertTrue(fixture.runtime.runs.isEmpty())
        }

    // ---------------------------------------------------------------- ② enabled

    @Test
    fun `a disabled script is rejected`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(enabled = false))) }
            val fixture = fixture(repo)

            val accepted = fixture.runner.start(1L, "boot", null)

            assertFalse(accepted, "需求 §4.4：必须检查脚本 enabled")
            assertTrue(fixture.runtime.runs.isEmpty(), "被禁用的脚本不得启动")
            assertEquals(0, fixture.gate.activeCount, "拒绝分支不得占用名额")
        }

    // ---------------------------------------------------------------- ③ 闸门

    @Test
    fun `a reentry rejection does not start the script`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })
            // 预占：模拟该脚本已在运行
            assertEquals(RunAdmissionGate.AdmissionResult.Accepted, fixture.gate.tryAcquire(1L))

            val accepted = fixture.runner.start(1L, "boot", null)

            assertFalse(accepted, "重入必须被拒绝（需求 §2.2）")
            assertTrue(fixture.runtime.runs.isEmpty())
            assertEquals(1, fixture.gate.activeCount, "拒绝不得改变原有占用")
        }

    @Test
    fun `a global limit rejection does not start the script`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(5L, okScript(script(id = 5L))) }
            val fixture = fixture(repo, maxConcurrentRuns = 2)
            fixture.gate.tryAcquire(1L)
            fixture.gate.tryAcquire(2L)

            val accepted = fixture.runner.start(5L, "boot", null)

            assertFalse(accepted, "全局满时不得启动")
            assertTrue(fixture.runtime.runs.isEmpty())
        }

    // ---------------------------------------------------------------- ④ 成功路径

    @Test
    fun `the happy path starts the script and reports the run meta`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            val accepted = fixture.runner.start(1L, "boot", payload = """{"a":1}""")
            advanceUntilIdle()

            assertTrue(accepted)
            val start = fixture.pipeline.starts.single()
            assertEquals(
                RunMeta(scriptId = 1L, triggerEvent = "boot"),
                start.runMeta,
                "候选 A：运行元必须与日志源在同一次 startRun 里交给管道",
            )
            // 阶段 4：`batchSink` 现在**原样**透传（结局上报走 source 的 `onCompletion`，
            // 不再需要包装 sink 一层——包装方式曾在"退出码捕获"上引入过一个隐蔽缺陷，
            // 见 `ScriptRunCoordinator.startLogging` 的注释）。
            assertSame(fixture.sink, start.batchSink, "落库订阅者必须原样透传给管道")
            assertEquals(
                3,
                fixture.pipeline.collected.values
                    .single()
                    .size,
                "三条日志都应经过管道",
            )
        }

    @Test
    fun `the domain script is mapped to a runtime entity including the body`() =
        runTest {
            val repo =
                FakeScriptRepository().apply {
                    put(7L, okScript(script(id = 7L, name = "nightly", content = "echo body")))
                }
            val fixture = fixture(repo)

            fixture.runner.start(7L, "interval", null)
            advanceUntilIdle()

            val (entity, _) = fixture.runtime.runs.single()
            assertEquals(7L, entity.id)
            assertEquals("nightly", entity.name)
            assertEquals("shell", entity.language)
            assertEquals("echo body", entity.content, "正文不得丢失（丢了脚本会静默什么都不做）")
            assertTrue(entity.enabled)
        }

    @Test
    fun `the run context carries the trigger event and the environment`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            fixture.runner.start(1L, "boot", payload = """{"source":"boot"}""")
            advanceUntilIdle()

            val (_, ctx) = fixture.runtime.runs.single()
            assertEquals("boot", ctx.triggerEvent)
            assertEquals("1", ctx.env[TriggeredScriptRunner.ENV_SCRIPT_ID])
            assertEquals("boot", ctx.env[TriggeredScriptRunner.ENV_EVENT])
            assertEquals(
                """{"source":"boot"}""",
                ctx.env[TriggeredScriptRunner.ENV_EVENT_PAYLOAD],
                "需求 §3.2：事件负载经 ROOTFLOW_EVENT_PAYLOAD 注入",
            )
        }

    @Test
    fun `a null payload leaves the payload variable unset`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            fixture.runner.start(1L, "boot", payload = null)
            advanceUntilIdle()

            val (_, ctx) = fixture.runtime.runs.single()
            assertNull(
                ctx.env[TriggeredScriptRunner.ENV_EVENT_PAYLOAD],
                "无负载时不得写入空串（脚本无法区分『没有负载』与『负载是空串』）",
            )
        }

    // ---------------------------------------------------------------- ⑤ 闸门释放

    @Test
    fun `the gate is released when the run finishes`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            fixture.runner.start(1L, "boot", null)
            assertEquals(1, fixture.gate.activeCount, "受理后必须占用名额")

            advanceUntilIdle()

            assertEquals(0, fixture.gate.activeCount, "运行结束必须归还名额，否则重入拒绝会永久生效")
            assertFalse(fixture.gate.isRunning(1L))
        }

    @Test
    fun `a completed run can be started again`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()
            val second = fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            assertTrue(second, "第一次运行结束后必须能再次启动（否则闸门泄漏）")
            assertEquals(2, fixture.runtime.runs.size)
        }

    @Test
    fun `a script is rejected while its own run is still in flight`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript()) }
            // 让脚本"卡住"：源在第一行后挂起，收集协程不会结束 → 闸门保持占用。
            // 收集协程放 backgroundScope：它会一直挂起，放前台会让 runTest 永不结束（§9.2）。
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            assertTrue(fixture.runner.start(1L, "boot", null))
            runCurrent()

            assertFalse(
                fixture.runner.start(1L, "boot", null),
                "运行中再次触发必须被重入拒绝（需求 §2.2）",
            )
            assertEquals(1, fixture.gate.activeCount)
        }

    @Test
    fun `a run whose source hangs keeps the gate held`() =
        runTest {
            // 源永久挂起 ⇒ 收集协程不结束 ⇒ 闸门必须保持占用（否则同一脚本会被并发启动两次）
            val fixture =
                fixture(
                    FakeScriptRepository().apply { put(1L, okScript()) },
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            assertTrue(fixture.runner.start(1L, "boot", null))
            runCurrent()

            assertEquals(1, fixture.gate.activeCount, "运行进行中必须占用名额")
        }

    @Test
    fun `a source failure inside the pipeline is recorded rather than swallowed`() =
        runTest {
            // 覆盖"脚本源抛错"的**生产归宿**：`LogPipelineImpl` 的收集协程 catch 把它记成
            // 一条 SYS 行并收敛（阶段 2 语义：脚本失败本身由 runtime 的退出行体现）。
            //
            // 两处必须按协程测试约定（`AGENT_PROTOCOL.md §9`）写，否则断言会在管道跑完前执行：
            // - §9.3：形参类型是 `CoroutineDispatcher`，必须传 `StandardTestDispatcher(testScheduler)`；
            //   **传 null 会让管道跑在 `Dispatchers.Default` 的真实线程上**，`advanceUntilIdle()`
            //   等不到它（3d 实测：captured 恒为空，看起来像"功能没做"）
            // - 前台作用域：管道会捕获源异常，作业正常完成，不会挂起 `runTest`
            val pipeline = LogPipelineImpl(scope = this, dispatcher = StandardTestDispatcher(testScheduler))
            val runtime = FakeScriptRuntime(RuntimeOutcome.Fails())
            val captured = mutableListOf<LogBatch>()
            val coordinator =
                ScriptRunCoordinator(
                    shellScriptRuntime = shellRuntimeFor(runtime),
                    logPipeline = pipeline,
                    processGroupManager = mockk(relaxed = true),
                )
            val session =
                coordinator.startLogging(
                    script =
                        ScriptEntity(
                            id = 1L,
                            name = "s",
                            language = "shell",
                            enabled = true,
                            timeoutSec = 0,
                            autoDisableOnFail = false,
                            runOnSafeMode = false,
                            content = "echo hi",
                        ),
                    ctx = RunContext(triggerEvent = "boot", env = emptyMap()),
                    scope = this,
                    batchSink =
                        object : LogBatchSink {
                            override fun onBatch(
                                meta: RunMeta?,
                                batch: LogBatch,
                            ) {
                                captured += batch
                            }
                        },
                )

            advanceUntilIdle()

            // 用 sink 而不是 tail：运行结束后管道会回收 state，`tail()` 返回空
            // （阶段 2 遗留项；历史由 Room 承担）。sink 才是"这一批到底有没有推出来"的证据。
            assertTrue(
                captured.any { batch -> batch.entries.any { it.text.contains("source FAILED") } },
                "源故障必须变成一条可见的 SYS 行，而不是静默消失：$captured",
            )
            assertTrue(captured.any { it.runFinished }, "源故障必须收敛成收尾批（runFinished=true）")
            assertTrue(session.job.isCompleted, "管道吞掉源异常后作业应正常完成（不会炸掉调用方作用域）")
        }

    @Test
    fun `the runner returns the admission decision to the dispatcher`() =
        runTest {
            // `start` 返回 `true` 的语义是"已受理"；唯一会让它返回 `false` 的是**受理前**的拒绝
            // （见上面各分支）。这里用挂起结局让"运行仍在进行中"可控。
            val fixture =
                fixture(
                    FakeScriptRepository().apply { put(1L, okScript()) },
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            assertTrue(fixture.runner.start(1L, "boot", null), "空闲时应受理")
            runCurrent()

            assertFalse(fixture.runner.start(1L, "boot", null), "受理后同一脚本立即再次触发必被重入拒绝")
        }

    // ---------------------------------------------------------------- ⑥ 阶段 4：安全模式

    @Test
    fun `safe mode rejects a script that does not opt in`() =
        runTest {
            // safeMode=true，脚本 runOnSafeMode=false（默认）⇒ 需求 §5.4：不执行
            val fixture =
                fixture(
                    FakeScriptRepository().apply { put(1L, okScript()) },
                    breaker = FakeCircuitBreaker(initialSafeMode = true),
                )

            val accepted = fixture.runner.start(1L, "boot", null)

            assertFalse(accepted, "安全模式下未声明 runOnSafeMode 的脚本必须被拒绝")
            assertTrue(fixture.runtime.runs.isEmpty(), "被拒绝的脚本不得触碰 runtime")
            assertEquals(0, fixture.gate.activeCount, "拒绝分支不得占名额")
            assertTrue(fixture.breaker.accepted.isEmpty(), "被拒的运行不得计入『已受理』")
        }

    @Test
    fun `safe mode admits an opted-in script and skips the admission gate`() =
        runTest {
            // 安全模式 + runOnSafeMode=true ⇒ 放行，且**跳过闸门**（SafeModeDecision 的已批准语义）。
            // 用挂起结局让"运行仍在进行中"可控，从而能断言运行期的闸门占用为 0。
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(runOnSafeMode = true))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                    breaker = FakeCircuitBreaker(initialSafeMode = true),
                )

            assertTrue(fixture.runner.start(1L, "boot", null), "runOnSafeMode=true 必须放行")
            runCurrent()

            assertEquals(0, fixture.gate.activeCount, "跳过闸门 ⇒ 不得占名额（占满了会让救砖脚本跑不起来）")
            assertEquals(1, fixture.runtime.runs.size, "放行的脚本必须真的跑到 runtime")
            assertEquals(1, fixture.breaker.accepted.size, "放行后必须计入『已受理』")
            assertEquals(
                "true",
                fixture.runtime.runs
                    .single()
                    .second.env[TriggeredScriptRunner.ENV_SAFE_MODE],
            )
        }

    @Test
    fun `a skipped gate is not released at the end of the run`() =
        runTest {
            // 跳过闸门却仍调用 release 会**误放别人的名额**（release 按 scriptId 计数），
            // 后果是另一个脚本被并发启动两次。本用例把"被占用"的现场摆出来，
            // 断言那个占用**没有**被误释放。
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(runOnSafeMode = true))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                    breaker = FakeCircuitBreaker(initialSafeMode = true),
                )
            // 另一个脚本占着名额（模拟"正常脚本正在跑"）
            assertEquals(RunAdmissionGate.AdmissionResult.Accepted, fixture.gate.tryAcquire(99L))

            fixture.runner.start(1L, "boot", null)
            runCurrent()

            assertEquals(1, fixture.gate.activeCount, "跳过闸门的运行结束后，别人的占用必须原样保留")
            assertTrue(fixture.gate.isRunning(99L), "99 号的名额不得被误释放")
        }

    @Test
    fun `a safe mode run is registered and unregistered through the run lifecycle`() =
        runTest {
            // 熔断的第 2 步按 runId 终止**每一个**在跑的运行（含跳过闸门的那些），
            // 因此注册必须无条件发生；漏注销则会让熔断去 kill 一个早已结束的运行。
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(runOnSafeMode = true))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                    breaker = FakeCircuitBreaker(initialSafeMode = true),
                )

            fixture.runner.start(1L, "boot", null)
            runCurrent()

            assertEquals(1, fixture.sessions.activeCount, "受理后必须登记运行")
            assertEquals(
                setOf(1L),
                fixture.sessions
                    .activeRuns()
                    .map { it.second }
                    .toSet(),
            )
        }

    @Test
    fun `a completed run is unregistered`() =
        runTest {
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript()) })

            fixture.runner.start(1L, "boot", null)
            assertEquals(1, fixture.sessions.activeCount, "受理后必须登记运行")

            advanceUntilIdle()

            assertEquals(0, fixture.sessions.activeCount, "运行结束必须注销（否则熔断会 kill 已结束的运行）")
        }

    @Test
    fun `the run outcome carries the exit code from the log line`() =
        runTest {
            // 这条断言是"退出码无竞态"的**直接证据**：默认日志的末行是
            // `script 1 exited with code 0`，协调者必须在收集时就地捕获并随回调带出，
            // 而不是等落库消费者写完之后去查库。
            //
            // ⚠ 脚本 id 必须显式给成 1：`script()` 测试构造器的默认 `id = 0`
            // 是"尚未入库"的语义，而假件仓库按 1 建键。用默认值会让
            // `runtime.ScriptEntity.id = 0`，于是断言 `scriptId` 时看到的是构造器的
            // 默认值而不是真实链路的值（本用例第一版就踩了这个坑）。
            val fixture = fixture(FakeScriptRepository().apply { put(1L, okScript(script(id = 1L))) })

            fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            val outcome = fixture.outcomes.outcomes.single()
            assertEquals(1L, outcome.scriptId, "归属脚本必须是真实 id（脚本会把它写进环境变量）")
            assertEquals(0, outcome.exitCode, "退出码必须随回调带出（无 DB 往返）")
        }

    @Test
    fun `a non-zero exit code is reported as-is`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript()) }
            val failing =
                FakeScriptRuntime(
                    RuntimeOutcome.Completed(
                        listOf(
                            com.rootflow.runtime.LogLine(1L, RuntimeLogStream.SYS, "script 1 started"),
                            com.rootflow.runtime.LogLine(2L, RuntimeLogStream.SYS, "script 1 exited with code 7"),
                        ),
                    ),
                )
            val fixture = fixture(repo, runtime = failing)

            fixture.runner.start(1L, "boot", null)
            advanceUntilIdle()

            assertEquals(
                7,
                fixture.outcomes.outcomes
                    .single()
                    .exitCode,
                "非 0 退出码必须原样上报给熔断器",
            )
        }

    // ---------------------------------------------------------------- ⑦ 阶段 4：超时换算

    @Test
    fun `timeoutSec of zero falls back to the global default`() =
        runTest {
            // 需求 §3.3 说 0 = 不限，但 §5.1 说"全局默认 60s" —— 冲突时以 §5.1 为准（已确认）。
            // 依据：熔断语境下不允许无超时，挂死的脚本会让整个熔断机制失效。
            //
            // ⚠ 运行必须**仍在进行中**才能观察到看门狗：用 `Completed` 结局的话，
            // 作业会在虚拟时间的第一次推进里结束，`invokeOnCompletion` 随即取消看门狗
            // ——那是**正确行为**（见 `startTimeoutWatchdog` 的 KDoc），但验不到超时。
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(timeoutSec = 0))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            fixture.runner.start(1L, "boot", null)
            advanceTimeBy(TriggeredScriptRunner.DEFAULT_TIMEOUT_MILLIS + 1)
            runCurrent()

            assertEquals(
                60_000L,
                fixture.outcomes.timeouts
                    .single()
                    .timeoutMillis,
                "timeoutSec=0 必须回落到全局默认 60s，而不是『不限』",
            )
        }

    @Test
    fun `an explicit timeoutSec is converted to milliseconds`() =
        runTest {
            val repo = FakeScriptRepository().apply { put(1L, okScript(script(timeoutSec = 5))) }
            val fixture =
                fixture(
                    repo,
                    scope = backgroundScope,
                    runtime = FakeScriptRuntime(RuntimeOutcome.Hanging(defaultLines().first())),
                )

            fixture.runner.start(1L, "boot", null)
            // 阈值之前不得超时
            advanceTimeBy(4_999L)
            runCurrent()
            assertTrue(fixture.outcomes.timeouts.isEmpty(), "未到阈值不得报超时")

            advanceTimeBy(2L)
            runCurrent()
            assertEquals(
                5_000L,
                fixture.outcomes.timeouts
                    .single()
                    .timeoutMillis,
                "timeoutSec 必须换算成毫秒",
            )
        }

    // ---------------------------------------------------------------- 工具

    private class Fixture(
        val runner: TriggeredScriptRunner,
        val repository: FakeScriptRepository,
        val runtime: FakeScriptRuntime,
        val pipeline: RecordingLogPipeline,
        val gate: RunAdmissionGateImpl,
        val sink: NoopSink,
        val breaker: FakeCircuitBreaker,
        val sessions: RunSessionRegistry,
        val outcomes: RecordingOutcomeSink,
    )

    private fun TestScope.fixture(
        repo: FakeScriptRepository,
        maxConcurrentRuns: Int = RunAdmissionGate.DEFAULT_MAX_CONCURRENT_RUNS,
        runtime: FakeScriptRuntime = FakeScriptRuntime(),
        scope: CoroutineScope? = null,
        breaker: FakeCircuitBreaker = FakeCircuitBreaker(),
        masterSwitch: FakeMasterSwitch = FakeMasterSwitch(),
        eventChannel: FakeEventChannel = FakeEventChannel(),
    ): Fixture {
        val gate = RunAdmissionGateImpl(maxConcurrentRuns)
        val sink = NoopSink
        // 11e 补丁4：管道假件现在需要作用域（它与生产同形：后台收集并交出作业）。
        // 用**与 runner 同一个** scope —— 生产上两者都是应用级作用域。
        val runScope = scope ?: this
        val pipeline = RecordingLogPipeline(runScope)
        val sessions = RunSessionRegistry()
        val outcomes = RecordingOutcomeSink()
        val coordinator =
            ScriptRunCoordinator(
                shellScriptRuntime = shellRuntimeFor(runtime),
                logPipeline = pipeline,
                processGroupManager = mockk(relaxed = true),
            )
        val runner =
            TriggeredScriptRunner(
                scriptRepository = repo,
                coordinator = coordinator,
                gate = gate,
                batchSink = sink,
                // 默认用测试作用域：`runTest` 会在结束前等齐它的子协程（= 运行已收尾）。
                // 会永久挂起或抛错的用例显式传 `backgroundScope`（协程测试约定 §9.2）。
                scope = runScope,
                circuitBreaker = breaker,
                masterSwitch = masterSwitch,
                eventChannel = eventChannel,
                sessionRegistry = sessions,
                outcomeSink = outcomes,
            )
        return Fixture(runner, repo, runtime, pipeline, gate, sink, breaker, sessions, outcomes)
    }

    /**
     * 把 [FakeScriptRuntime] 接到 `ShellScriptRuntime` 的位置上。
     *
     * 之所以需要这层 MockK：`ScriptRunCoordinator` 的形参类型是**具体类**
     * `ShellScriptRuntime`（1b 冻结的构造形态；3d 的"不改 runtime 公开接口"约束
     * 不允许把它改成接口）。打桩只包住 `run`，其余调用路径（协调者、管道、
     * 日志映射）都是真实实现，因此本文件验证的是**真实的接线**而不是假接线。
     */
    private fun shellRuntimeFor(runtime: FakeScriptRuntime): ShellScriptRuntime {
        val shell = mockk<ShellScriptRuntime>()
        coEvery { shell.run(any(), any(), any()) } answers {
            val script = firstArg<ScriptEntity>()
            val ctx = secondArg<RunContext>()
            // 11e 补丁4：**runId 必须原样传下去** —— "它就是 RunHandle.id"这条断言
            // 靠这里成立（此前留空 ⇒ 假件自己造 UUID ⇒ 与日志/terminate 的 runId 对不上）。
            val runId = thirdArg<String>()
            runtime.recordRun(script, ctx, runId)
        }
        return shell
    }

    /** 记录型落库订阅者（本文件只验证"透传"；落库语义由 `RunHistoryCollectorTest` 覆盖）。 */
    private object NoopSink : LogBatchSink {
        override fun onBatch(
            meta: RunMeta?,
            batch: LogBatch,
        ) = Unit
    }

    /** `startRun` 直接抛错的管道（保留给"受理后、拿到会话前失败"的将来用例）。 */
    @Suppress("unused")
    private class ThrowingPipeline : LogPipeline {
        override suspend fun startRun(
            runId: String,
            source: Flow<LogEntry>,
            runMeta: RunMeta?,
            batchSink: LogBatchSink?,
        ): Job = throw IllegalStateException("pipeline refused")

        override fun observe(runId: String): Flow<LogBatch> = flow { }

        override suspend fun tail(
            runId: String,
            limit: Int,
        ): LogTail = LogTail(runId = runId, entries = emptyList(), droppedEntries = 0L)

        override suspend fun markFinished(runId: String) = Unit

        override suspend fun release(runId: String) = Unit
    }

    private companion object {
        /** 让 `RunHandle` 类型在本文件的断言里可见（避免全限定名噪音）。 */
        @Suppress("unused")
        val handleType: Class<RunHandle> = RunHandle::class.java
    }
}
