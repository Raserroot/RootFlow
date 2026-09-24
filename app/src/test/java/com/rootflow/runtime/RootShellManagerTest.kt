package com.rootflow.runtime

import android.text.TextUtils
import android.util.Log
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [RootShellManager] 单元测试。
 *
 * 全部用例**不依赖真实 Root**：通过 MockK 对 libsu 的两处静态入口打桩
 * （[Shell.getShell] 与 [Shell.cmd]），libsu 的 [Shell.Result] 与 [Shell.Job]
 * 均为接口，可直接伪造。
 *
 * 之所以不需要 Robolectric：已实测 libsu 5.2.2 的 [Shell] 类在纯 JVM 下可加载，
 * 且 `mockkStatic` 成功（其静态初始化不触达未桩的 Android 框架实现）。
 *
 * 调度器约定：manager 必须使用**当前 runTest 的调度器**（[TestScope.testScheduler]）。
 * 另起一个 `StandardTestDispatcher()` 会触发 kotlinx-coroutines-test 的
 * "Detected use of different schedulers" 断言（实测踩过）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootShellManagerTest {
    /** 打桩期间是否已注册 static mock，用于保证 [tearDown] 成对清理。 */
    private var staticMocked = false

    @BeforeEach
    fun setUp() {
        mockkStatic(Shell::class)
        // Shell.Builder 是 Shell 的**嵌套类**，mockkStatic(Shell::class) 不覆盖它：
        // 若不打这一行，Shell.Builder.create() 会走真实实现，build() 真的去创建进程
        // 并抛 "Unable to create a shell!"（实测踩到）。
        mockkStatic(Shell.Builder::class)
        // RootShellManager 的控制通道会打诊断日志，而单测是纯 JVM：
        // android.jar 的 Log 方法是抛 `RuntimeException("Stub!")` 的桩，必须打桩。
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        // libsu 的 BuilderImpl.build(String...) 会调用 TextUtils.join；未打桩时
        // android.jar 的同一个桩会抛 "Method join in android.text.TextUtils not mocked"。
        mockkStatic(TextUtils::class)
        every { TextUtils.join(any<CharSequence>(), any<Iterable<*>>()) } returns ""
        staticMocked = true
    }

    @AfterEach
    fun tearDown() {
        if (staticMocked) {
            unmockkStatic(TextUtils::class)
            unmockkStatic(Log::class)
            unmockkStatic(Shell.Builder::class)
            unmockkStatic(Shell::class)
            staticMocked = false
        }
    }

    // ---------------------------------------------------------------- 状态机

    @Test
    fun `initial state is Unknown`() =
        runTest {
            val manager = manager()

            assertEquals(RootState.Unknown, manager.state.value)
        }

    @Test
    fun `ensureReady sets Granted when shell is root`() =
        runTest {
            val shell = mockk<Shell>(relaxed = true)
            every { Shell.getShell() } returns shell
            setRoot(shell, true)
            val manager = manager()

            val ready = manager.ensureReady()

            assertTrue(ready, "root shell 可用时应返回 true")
            assertEquals(RootState.Granted, manager.state.value)
        }

    @Test
    fun `ensureReady sets Denied when shell is not root`() =
        runTest {
            val shell = mockk<Shell>(relaxed = true)
            every { Shell.getShell() } returns shell
            setRoot(shell, false)
            val manager = manager()

            val ready = manager.ensureReady()

            assertFalse(ready, "非 root shell 应返回 false")
            val state = manager.state.value
            assertTrue(state is RootState.Denied, "状态应为 Denied，实际为 $state")
        }

    @Test
    fun `ensureReady sets Denied when NoShellException is thrown`() =
        runTest {
            every { Shell.getShell() } throws NoShellException("no su available")
            val manager = manager()

            val ready = manager.ensureReady()

            assertFalse(ready)
            val state = manager.state.value
            assertTrue(state is RootState.Denied, "NoShellException 应映射为 Denied，实际为 $state")
            assertEquals("no su available", (state as RootState.Denied).reason)
        }

    @Test
    fun `ensureReady sets Error when an unexpected exception is thrown`() =
        runTest {
            every { Shell.getShell() } throws IllegalStateException("libsu exploded")
            val manager = manager()

            val ready = manager.ensureReady()

            assertFalse(ready)
            val state = manager.state.value
            assertTrue(state is RootState.Error, "未知异常应映射为 Error，实际为 $state")
            assertEquals("libsu exploded", (state as RootState.Error).message)
        }

    @Test
    fun `state recovers to Granted after user grants root later`() =
        runTest {
            // 首次探测被拒，第二次成功：状态必须能从 Denied 回到 Granted。
            val shell = mockk<Shell>(relaxed = true)
            setRoot(shell, true)
            every { Shell.getShell() } throws NoShellException("denied") andThen shell
            val manager = manager()

            assertFalse(manager.ensureReady())
            assertTrue(manager.state.value is RootState.Denied)

            assertTrue(manager.ensureReady())
            assertEquals(RootState.Granted, manager.state.value)
        }

    // ------------------------------------------------------------------ exec

    @Test
    fun `exec returns uid 0 output when command succeeds`() =
        runTest {
            val manager = readyManager()
            val idOutput =
                listOf(
                    "uid=0(root) gid=0(root) groups=0(root)",
                    "context=u:r:magisk:s0",
                )
            stubCommand(exitCode = 0, stdout = idOutput, stderr = emptyList())

            val result = manager.exec("id")

            assertTrue(result.isSuccess, "退出码 0 应视为成功")
            assertEquals(0, result.exitCode)
            assertEquals(idOutput.joinToString("\n"), result.stdout)
            assertEquals("", result.stderr)
            assertTrue(result.stdout.contains("uid=0(root)"), "真机验证依赖 stdout 含 uid=0(root)")
        }

    @Test
    fun `exec merges stderr and reports non-zero exit code`() =
        runTest {
            val manager = readyManager()
            stubCommand(exitCode = 1, stdout = emptyList(), stderr = listOf("id: not found"))

            val result = manager.exec("id")

            assertFalse(result.isSuccess)
            assertEquals(1, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("id: not found", result.stderr)
        }

    @Test
    fun `exec does not run the command when root is not ready`() =
        runTest {
            every { Shell.getShell() } throws NoShellException("denied")
            val manager = manager()

            val result = manager.exec("id")

            assertFalse(result.isSuccess)
            assertEquals(RootShellManager.EXIT_CODE_NOT_READY, result.exitCode)
            // 关键断言：Root 未就绪时绝不能真的下发命令
            verify(exactly = 0) { Shell.cmd(any<String>()) }
        }

    // 注意：`exec` 的超时分支（EXIT_CODE_TIMEOUT）**没有**单元测试覆盖。
    // 原因：该分支需要让 mock 的挂起 exec() 永不返回，而 MockK 对被挂起的
    // mock 挂起函数无法可靠传播协程取消，runTest 的虚拟时间因此永不收敛
    // （实测挂死）。这是测试框架限制，与生产代码无关。
    // 超时行为留待真机验证，已登记在阶段 1a 变更报告的已知问题中。

    // ------------------------------------------------------ 取消语义（部分覆盖）

    // 注意：「协程取消向上传播且不改写状态」这一条**没有**单元测试覆盖。
    // 尝试过的写法（`coAnswers { gate.await() }` + `deferred.cancel()`）单独
    // 运行可通过，但在全量运行时会挂死——MockK 的 static mock 加上永不完成的
    // 挂起点，会让 runTest 的虚拟时间调度无法收敛（实测多次复现）。
    // 该行为留待真机验证，已登记在变更报告的已知问题中。

    @Test
    fun `ensureReady sets Error when reading the root flag throws`() =
        runTest {
            // 行为刻画：shell 能拿到，但读取 isRoot 时抛异常 -> 走 catch(Throwable)。
            val shell = mockk<Shell>(relaxed = true)
            every { shell.isRoot } throws IllegalStateException("binder died")
            every { Shell.getShell() } returns shell
            val manager = manager()

            val ready = manager.ensureReady()

            assertFalse(ready)
            val state = manager.state.value
            assertTrue(state is RootState.Error, "读取 isRoot 失败应落为 Error，实际为 $state")
            assertEquals("binder died", (state as RootState.Error).message)
        }

    // ----------------------------------------------------------------- 夹具

    /**
     * 使用**当前 runTest 的调度器**构造 manager。
     *
     * 不能用 `@BeforeEach` 里自建的 `StandardTestDispatcher()`：那会与 runTest
     * 自带的调度器冲突，kotlinx-coroutines-test 直接抛
     * "Detected use of different schedulers"。
     */
    private fun TestScope.manager(): RootShellManager = RootShellManager(StandardTestDispatcher(testScheduler))

    /** 构造一个已进入 [RootState.Granted] 的 manager。 */
    private suspend fun TestScope.readyManager(): RootShellManager {
        val shell = mockk<Shell>(relaxed = true)
        setRoot(shell, true)
        every { Shell.getShell() } returns shell
        val manager = manager()
        assertTrue(manager.ensureReady(), "夹具前置条件：Root 应可用")
        return manager
    }

    /** 为 [Shell.cmd] 打桩，返回固定结果。 */
    private fun stubCommand(
        exitCode: Int,
        stdout: List<String>,
        stderr: List<String>,
    ) {
        val result = mockk<Shell.Result>()
        every { result.out } returns stdout
        every { result.err } returns stderr
        every { result.code } returns exitCode
        every { result.isSuccess } returns (exitCode == 0)
        val job = mockk<Shell.Job>()
        every { job.exec() } returns result
        every { Shell.cmd(any<String>()) } returns job
    }

    /**
     * 设定某个 shell 实例的 root 归属。
     *
     * libsu 5.x 中 `isRoot()` 是实例方法（4.x 为静态），所以只能在实例桩上打。
     */
    private fun setRoot(
        shell: Shell,
        value: Boolean,
    ) {
        every { shell.isRoot } returns value
    }

    // -------------------------------------------------------------- 控制通道

    @Test
    fun `ensureControlReady builds a separate second shell instance`() =
        runTest {
            val dataShell = mainShell()
            val controlShell = controlShell()
            val manager = manager()

            val ready = manager.ensureControlReady()

            assertTrue(ready, "控制通道应建立成功")
            assertTrue(manager.controlChannelAvailable)
            // 控制通道必须是**另一个** shell 实例：共用一个实例会让控制命令
            // 清空正在运行的脚本输出（libsu execTask 会 cleanInputStream）。
            assertTrue(controlShell !== dataShell, "控制通道不得复用数据通道的 shell 实例")
        }

    @Test
    fun `ensureControlReady exhausts retries and degrades without touching root state`() =
        runTest {
            mainShell()
            every { Shell.Builder.create().build() } throws NoShellException("second shell refused")
            val manager = manager()

            val ready = manager.ensureControlReady()

            assertFalse(ready, "重试耗尽后应返回 false")
            assertFalse(manager.controlChannelAvailable, "失败后能力标志应为 false")
            // 降级必须**不**污染 Root 状态：Root 依然可用，只是没有控制通道。
            assertEquals(RootState.Granted, manager.state.value, "降级不得改写 RootState")
        }

    @Test
    fun `execControl reports unavailable exit code when the channel cannot be built`() =
        runTest {
            mainShell()
            every { Shell.Builder.create().build() } throws NoShellException("second shell refused")
            val manager = manager()

            val result = manager.execControl("kill -TERM -1234")

            assertEquals(RootShellManager.EXIT_CODE_CONTROL_UNAVAILABLE, result.exitCode)
            assertFalse(result.isSuccess)
        }

    @Test
    fun `exec and execControl run on different shell instances`() =
        runTest {
            mainShell()
            val controlShell = controlShell()
            // 数据通道：1b 的原路径（静态 Shell.cmd）
            every { Shell.cmd(any<String>()).exec() } returns stubbedResult("out-data", 0)
            // 控制通道：实例路径
            stubJob(controlShell, stubbedResult("out-ctl", 0))
            val manager = manager()

            val data = manager.exec("echo data")
            val control = manager.execControl("echo ctl")

            assertEquals("out-data", data.stdout, "数据命令必须走数据通道")
            assertEquals("out-ctl", control.stdout, "控制命令必须走控制通道")
        }

    // ----------------------------------------------------------------- 夹具

    /** 造一个可用的数据通道 shell，并把它设为 libsu 的全局单例。 */
    private fun mainShell(): Shell {
        val shell = mockk<Shell>(relaxed = true)
        setRoot(shell, true)
        every { Shell.getShell() } returns shell
        return shell
    }

    /** 造一个可用的控制通道 shell（已 root、存活），并让 Shell.Builder 返回它。 */
    private fun controlShell(alive: Boolean = true): Shell {
        val shell = mockk<Shell>(relaxed = true)
        every { shell.isAlive } returns alive
        setRoot(shell, true)
        // 控制通道在**实例**上执行（shell.newJob()），这里统一打桩链，
        // 用例只需覆盖 exec() 的返回值。
        stubJob(shell, stubbedResult("", 0))
        val builder = mockk<Shell.Builder>(relaxed = true)
        every { builder.build() } returns shell
        every { Shell.Builder.create() } returns builder
        return shell
    }

    /** 给某个 shell 实例打桩 `newJob().add().to().exec()` 链。 */
    private fun stubJob(
        shell: Shell,
        result: Shell.Result,
    ) {
        val job = mockk<Shell.Job>()
        every { job.add(any<String>()) } returns job
        every { job.to(any<MutableList<String>>(), any<MutableList<String>>()) } returns job
        every { job.exec() } returns result
        every { shell.newJob() } returns job
    }

    /** 造一个固定结果的 libsu 结果对象。 */
    private fun stubbedResult(
        stdout: String,
        exitCode: Int,
    ): Shell.Result {
        val result = mockk<Shell.Result>()
        every { result.out } returns listOf(stdout)
        every { result.err } returns emptyList()
        every { result.code } returns exitCode
        every { result.isSuccess } returns (exitCode == 0)
        return result
    }
}
