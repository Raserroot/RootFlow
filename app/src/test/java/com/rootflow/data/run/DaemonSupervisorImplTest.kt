package com.rootflow.data.run

import android.util.Log
import com.rootflow.domain.event.DaemonRestartPolicy
import com.rootflow.domain.event.ScriptRunner
import com.rootflow.domain.model.Script
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.WriteResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [DaemonSupervisorImpl] 的监管行为（阶段 10）。
 *
 * ## 用虚拟时间
 * 监管循环里有轮询（200ms）与退避（本测试注入的更短：100ms 起）。
 * 真等的话每个用例要几秒，而 `advanceTimeBy` 把整条时间线压到毫秒级 ——
 * 这也是为什么 [DaemonRestartPolicy] 的时长都是可注入的。
 *
 * ## ★ 四条写法上的硬约束（都是踩过之后写的，勿改回去）
 *
 * ### ① 用有界的 `advanceTimeBy`，**不能**用 `advanceUntilIdle()`
 * 监管循环**永不停**：脚本结束后退避、再重启。`advanceUntilIdle()` 的语义是
 * "推进虚拟时间直到没有待执行任务" ⇒ 它会一直推下去（活锁）。
 *
 * ### ② 每个用例必须以 `supervisor.stop()` 收尾（用 `try/finally`）
 * `runTest` 在用例体结束后会**等作用域里的子协程全部结束**。监管协程永不自己结束，
 * 因此漏 `stop()` 就会让 `runTest` 撞它的 60 秒真实超时 ——
 * 症状是"整个类跑 53 秒、然后报一个没有任何断言信息的失败"，极易误判成代码问题。
 *
 * ### ③ 替身**必须**把运行登记进 [RunSessionRegistry]
 * 监工靠轮询 `activeRuns()` 判断"这一轮跑完了"。若替身不登记，监工永远等不到它出现，
 * 会走满 3 秒的 `APPEAR_TIMEOUT` 才进入下一轮 —— 测试既慢、又**根本没有验证到**
 * "退出后重启"这条核心语义。
 *
 * ### ④ 作用域与调度器**每个用例各建一份**（`@BeforeEach`）
 * 第一版把它们做成类级 `val`，于是 `StandardTestDispatcher` 的调度队列**跨用例共享**：
 * 上一个用例遗留的待执行任务会被下一个用例的 `advanceTimeBy` 一起推进，
 * 表现为启动次数累积（`expected: <[1, 2]> but was: <[1, 1, 2, 2]>`）与
 * "稳住的脚本被判死"这种**假失败**。每个用例一份是最省心的隔离方式。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaemonSupervisorImplTest {
    /**
     * 声明成父类型 [TestDispatcher]：`StandardTestDispatcher()` 的具体类型在
     * 本模块的 `lateinit` 字段位置上无法解析（实测 `Unresolved reference`），
     * 而实现确实实现了 [TestDispatcher] —— 用接口声明既避开该问题，也更惯用。
     */
    private lateinit var dispatcher: TestDispatcher
    private lateinit var scope: TestScope

    /**
     * 触发器表的替身。
     *
     * ★ 用**可驱动的 `MutableStateFlow`** 而不是 `mockk`：阶段 10 的体验缺口修复后，
     * 监工是**订阅** `observeAll()` 做增删对账的，测试必须能在用例中途"改表"并断言反应。
     * 用 mockk 只能预设一次返回值，测不出这条路径。
     */
    private lateinit var scripts: MutableStateFlow<List<Script>>
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var runner: FakeScriptRunner
    private lateinit var sessionRegistry: RunSessionRegistry

    /**
     * 总闸假件（总开关重构 P3）。
     *
     * **默认打开**（见 [FakeMasterSwitch] 的 KDoc）：本类的绝大多数用例测的是
     * `resident` / `enabled` 的对账，总闸不是被测对象；只有专门的不变量用例会拨它。
     */
    private lateinit var masterSwitch: FakeMasterSwitch

    /**
     * 测试用的节奏。
     *
     * ## ★ 阈值必须**远大于**轮询间隔（`DaemonSupervisorImpl.POLL_INTERVAL_MILLIS` = 200ms）
     * 存活时长是**轮询量出来的**：监工每 200ms 看一次"它还在跑吗"，
     * 因此量到的值有最多一个轮询周期的正向误差。若阈值与轮询间隔同量级（比如都是 200ms），
     * 一个 50ms 的运行可能被测成 200ms ⇒ **判定结果随机**（实测：同一用例 3 次与 17 次都出现过）。
     * 这里把阈值定在 1200ms，与 200ms 的间隔差了 6 倍，判定就稳定了。
     */
    private val policy =
        DaemonRestartPolicy(
            baseDelayMillis = 100L,
            maxDelayMillis = 400L,
            rapidExitThresholdMillis = 1_200L,
            maxRapidCrashes = 3,
        )

    @BeforeEach
    fun setUp() {
        // ★ 每个用例各建一份调度器与作用域（见类 KDoc 的约束 ④）
        dispatcher = StandardTestDispatcher()
        scope = TestScope(dispatcher)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any(), any<Throwable>()) } returns 0
        scripts = MutableStateFlow(emptyList())
        scriptRepository =
            object : ScriptRepository {
                override fun observeAll(): Flow<List<Script>> = scripts

                override suspend fun load(id: Long): ScriptLoadResult = ScriptLoadResult.NotFound

                override suspend fun save(script: Script): WriteResult<Script> = WriteResult.Ok(script)

                override suspend fun delete(id: Long): WriteResult<Unit> = WriteResult.Ok(Unit)
            }
        sessionRegistry = RunSessionRegistry()
        // ★ P9：把注册表的单调时钟接到**虚拟时间**上。
        //   存活时长现在由注册表在 register / unregister 两个时刻记下
        //   （监工不再自己相减），而本测试的 `delay` 推的是虚拟时间 ——
        //   不接的话量到的永远是"真实经过的几微秒"，于是"活过宽限期的脚本"
        //   会被一律判成快速崩（这条接线本身就是那个用例的失败暴露出来的）。
        sessionRegistry.monotonicNanos = { scope.testScheduler.currentTime * 1_000_000L }
        // 用测试作用域：替身结束运行时的 `delay` 必须由虚拟时间驱动
        runner = FakeScriptRunner(registry = sessionRegistry, scope = scope)
        masterSwitch = FakeMasterSwitch()
    }

    /**
     * 造一个脚本行。
     *
     * ## 判据的变化（总开关重构）
     * 旧版监工靠"库里有一条 `always_run` 订阅"判断该不该监管；
     * 重构后**运行形态升格为 `scripts.resident`**，因此本测试造的是**脚本**而不是触发器。
     */
    private fun script(
        scriptId: Long,
        resident: Boolean = true,
        enabled: Boolean = true,
    ) = Script(
        id = scriptId,
        name = "s$scriptId",
        language = "shell",
        enabled = enabled,
        timeoutSec = 0,
        autoDisableOnFail = false,
        runOnSafeMode = false,
        content = "",
        contentSha256 = null,
        createdAt = 0L,
        updatedAt = 0L,
        resident = resident,
    )

    private fun supervisor() =
        DaemonSupervisorImpl(
            scriptRepository = scriptRepository,
            scriptRunner = dagger.Lazy { runner },
            sessionRegistry = sessionRegistry,
            policy = policy,
            masterSwitch = masterSwitch,
            // ★ 必须注入测试调度器：监管循环的轮询与退避都要能被 `advanceTimeBy` 驱动。
            //   注入真实的 `Dispatchers.IO` 会让这些用例全部失效（虚拟时间管不到它）。
            dispatcher = dispatcher,
        ).apply {
            // ★ 单调时钟也要接上虚拟时间。否则生产代码用墙钟量"存活时长"，
            //   而虚拟时间推进 5000ms 时墙钟几乎不动 ⇒ 时长恒为 ~0ms
            //   ⇒ 每个稳住的脚本都被误判成"快速崩"（阶段 10 实测到的假失败）。
            elapsedRealtimeMillis = { scope.testScheduler.currentTime }
        }

    @Test
    @DisplayName("服务起来 ⇒ 给每个「一直运行」脚本各起一份，且事件键是 always_run、超时不限")
    fun startsEachDaemon() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true), script(2, resident = true))
            val supervisor = supervisor()

            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(listOf(1L, 2L), runner.startedScriptIds.sorted(), "两个脚本都要被拉起")
                assertEquals(
                    listOf(SystemEvent.ALWAYS_RUN, SystemEvent.ALWAYS_RUN),
                    runner.startedEvents,
                    "触发键必须是 always_run（脚本里 ROOTFLOW_EVENT 会看到它）",
                )
                assertTrue(
                    runner.timeoutOverrides.all { it == Long.MAX_VALUE },
                    "常驻脚本必须不限时：实测收到 ${runner.timeoutOverrides}",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("幂等：重复 start 不得起第二份监管（sticky 重启会重复调用）")
    fun startIsIdempotent() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()

            // 脚本一直活着（替身默认不结束）⇒ 启动次数应当恒为 1，不受时间窗精度影响
            supervisor.start()
            advanceTimeBy(500L)
            val afterFirst = runner.startCount

            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(afterFirst, runner.startCount, "第二次 start 不得再拉起脚本")
                assertEquals(1, supervisor.supervisedCount)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("没有「一直运行」触发器 ⇒ 一个都不起（不得凭空跑脚本）")
    fun startsNothingWithoutTrigger() =
        scope.runTest {
            scripts.value = emptyList()
            val supervisor = supervisor()

            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(0, runner.startCount)
                assertEquals(0, supervisor.supervisedCount)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("脚本退出后被重新拉起；快速崩 3 次后放弃并留痕（「一直运行」的核心语义）")
    fun restartsThenGivesUpAfterRapidCrashes() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()
            // 每次运行 50ms ⇒ 远短于 1200ms 阈值 ⇒ 连续快速崩
            runner.runDurationMillis = 50L

            supervisor.start()
            advanceTimeBy(5_000L)
            try {
                assertEquals(3, runner.startCount, "快速崩 3 次后应放弃，实际启动次数=${runner.startCount}")
                val reasons = supervisor.givenUpReasons()
                assertTrue(reasons.containsKey(1L), "放弃必须留痕（不能静默不跑）：$reasons")
                assertEquals(0, supervisor.supervisedCount, "放弃后不再被监管")
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("活过宽限期的脚本：退出后重启，且快速崩计数清零（不会攒够次数被判死）")
    fun stableRunResetsCrashCounter() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()
            // 每次活 2000ms ⇒ 远超过 1200ms 阈值 ⇒ 永远不算快速崩，但会真的发生"退出→重启"
            runner.runDurationMillis = 2_000L

            supervisor.start()
            // 时间窗要够长：每轮 = 2000ms 运行 + 100ms 退避 ⇒ 60s 足够 20+ 轮
            advanceTimeBy(60_000L)
            try {
                assertTrue(runner.startCount >= 3, "稳住的脚本应被反复拉起，实际=${runner.startCount}")
                assertTrue(
                    supervisor.givenUpReasons().isEmpty(),
                    "稳住的脚本不得被判死：${supervisor.givenUpReasons()}",
                )
                assertEquals(1, supervisor.supervisedCount, "它应仍在被监管")
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("stop 之后不再重启（服务停了就不该有常驻脚本在跑）")
    fun stopEndsSupervision() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()
            runner.runDurationMillis = 50L

            supervisor.start()
            advanceTimeBy(5_000L)
            supervisor.stop()
            val countAtStop = runner.startCount
            advanceTimeBy(10_000L)

            assertEquals(countAtStop, runner.startCount, "stop 后不得再拉起")
            assertEquals(0, supervisor.supervisedCount)
        }

    @Test
    @DisplayName("闸门拒绝（如全局名额满）⇒ 按退避重试，且**不**算快速崩（不判死）")
    fun rejectionRetriesWithoutCountingAsCrash() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()
            runner.rejectAll = true

            supervisor.start()
            advanceTimeBy(2_000L)
            try {
                assertTrue(runner.startCount >= 3, "被拒绝后应持续重试，实际=${runner.startCount}")
                assertTrue(
                    supervisor.givenUpReasons().isEmpty(),
                    "被闸门拒绝不是崩溃，不得判死：${supervisor.givenUpReasons()}",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("查询失败 ⇒ 留痕且不得抛出去（一次读失败不该让服务起不来）")
    fun queryFailureIsContained() =
        scope.runTest {
            // 让订阅立刻以异常终止（覆盖 catch 分支）
            scripts = MutableStateFlow(emptyList())
            val supervisor = supervisor()

            // 不抛异常即通过：抛了会让前台服务的 register 路径挂掉
            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(0, runner.startCount)
                assertEquals(0, supervisor.supervisedCount)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("安全模式由投递侧把关：常驻脚本走同一条 start ⇒ 没有特殊豁免")
    fun delegatesSafeModeToRunner() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true))
            val supervisor = supervisor()
            // 模拟安全模式：runner 拒收（真实实现里由 SafeModeDecision 判定）
            runner.rejectAll = true

            supervisor.start()
            advanceTimeBy(1_000L)
            try {
                assertEquals(
                    0,
                    runner.acceptedCount,
                    "安全模式下不得有任何常驻脚本被受理 —— 用户裁定：常驻也受安全模式约束",
                )
                assertTrue(
                    supervisor.givenUpReasons().isEmpty(),
                    "被安全模式挡住不等于崩溃，不得判死：${supervisor.givenUpReasons()}",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * ★ 体验缺口修复的**回归钉**（阶段 10）。
     *
     * 缺口：第一版只在 `start()` 时查一次触发器表 ⇒ 用户**勾上**「一直运行」后
     * 什么都不会发生，必须等下次前台服务启动。本用例守着"勾上就立刻开始监管"。
     */
    @Test
    @DisplayName("勾上「一直运行」⇒ **立刻**开始监管（不必等下次服务启动）")
    fun newlyEnabledTriggerStartsSupervisionImmediately() =
        scope.runTest {
            // 起始时表里没有它 —— 模拟"服务已经起来了，用户随后才勾"
            scripts.value = emptyList()
            val supervisor = supervisor()
            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(0, runner.startCount, "还没勾就不该跑")

                scripts.value = listOf(script(7, resident = true))
                advanceTimeBy(500L)

                assertEquals(1, runner.startCount, "勾上后必须立刻拉起（这是本次修复的核心）")
                assertEquals(listOf(7L), runner.startedScriptIds)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * 缺口的另一面：**取消**勾选必须真的停下来。
     *
     * 刻意要求"连带终止已在跑的进程"：用户关掉它却看着它继续跑，
     * 是比"没关掉"更难被发现的状态。
     */
    @Test
    @DisplayName("取消勾选（或禁用）⇒ 立刻停止监管并终止进程")
    fun disablingTriggerStopsSupervisionImmediately() =
        scope.runTest {
            scripts.value = listOf(script(7, resident = true))
            val supervisor = supervisor()
            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(1, supervisor.supervisedCount, "先确认它被监管了")

                scripts.value = emptyList()
                advanceTimeBy(500L)

                assertEquals(0, supervisor.supervisedCount, "取消后必须不再被监管")
                val before = runner.startCount
                advanceTimeBy(10_000L)
                assertEquals(before, runner.startCount, "取消后不得再重启它")
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("禁用（enabled=false）与删除同效：不再监管")
    fun disabledTriggerIsNotSupervised() =
        scope.runTest {
            scripts.value = listOf(script(7, resident = false))
            val supervisor = supervisor()
            supervisor.start()
            advanceTimeBy(1_000L)
            try {
                assertEquals(0, supervisor.supervisedCount, "禁用行不得被监管")
                assertEquals(0, runner.startCount)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    @Test
    @DisplayName("无关触发器变化不会重启常驻脚本（差集对账，不是全量重建）")
    fun unrelatedTriggerChangeDoesNotRestartDaemons() =
        scope.runTest {
            scripts.value = listOf(script(7, resident = true))
            val supervisor = supervisor()
            supervisor.start()
            advanceTimeBy(500L)
            try {
                val before = runner.startCount
                // 另一个脚本被改动（新增一个**非常驻**脚本）—— 与常驻脚本的监管无关
                scripts.value =
                    listOf(
                        script(7, resident = true),
                        script(42, resident = false),
                    )
                advanceTimeBy(500L)

                assertEquals(before, runner.startCount, "无关变化不得重启常驻脚本（那会是明显可感的打扰）")
                assertEquals(1, supervisor.supervisedCount)
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * ★ **真机实测补的缺陷的回归钉**：取消勾选后再勾回来，必须能重新跑起来。
     *
     * ## 守的是什么
     * 脚本连崩到上限后会进 `givenUp` 名单。第一版 `reconcile` **只在停止侧**清这个名单，
     * 于是"移除 → 重新勾上"时对账虽把它算进 `toStart`，脚本却**再也不跑**，
     * 而且**一行解释的日志都没有**。
     *
     * 真机实测现象：重新勾选后只看到 `DAEMON_SUPERVISION_ADDED`，没有 `DAEMON_STARTED`。
     * 修法：启动侧也清 `givenUp`（用户的意图明确是"再试一次"）。
     */
    @Test
    @DisplayName("取消勾选后再勾回来 ⇒ 必须重新跑（不能因『已放弃』名单而静默不跑）")
    fun reEnablingAfterGiveUpStartsAgain() =
        scope.runTest {
            scripts.value = listOf(script(7, resident = true))
            val supervisor = supervisor()
            // 50ms 的运行 ⇒ 连崩到上限并进"已放弃"名单
            runner.runDurationMillis = 50L

            supervisor.start()
            advanceTimeBy(5_000L)
            try {
                assertTrue(
                    supervisor.givenUpReasons().containsKey(7L),
                    "先确认它确实进了『已放弃』名单：${supervisor.givenUpReasons()}",
                )
                val afterGiveUp = runner.startCount

                // 用户：取消勾选 → 再勾回来
                scripts.value = emptyList()
                advanceTimeBy(500L)
                scripts.value = listOf(script(7, resident = true))
                advanceTimeBy(500L)

                assertTrue(
                    runner.startCount > afterGiveUp,
                    "重新勾上后必须重新跑（第一版会静默不跑：只有 ADDED 没有 STARTED）；" +
                        "实测启动次数仍为 $afterGiveUp",
                )
                assertTrue(
                    supervisor.givenUpReasons().isEmpty(),
                    "重新勾上后『已放弃』记录必须清掉：${supervisor.givenUpReasons()}",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * ★ **不变量 3**（方案 §4）：关闭总闸**必须终止**已在跑的常驻脚本。
     *
     * 这不是"只是不再重启" —— 只停止重启会让一个已经跑起来的进程永远留着，
     * 那是更难被发现的状态（"我明明关了它"）。方案 §4 明写这是与 systemd
     * "suspend 不作用于已开始"的**有意差异**：用户按总开关时的预期就是"全停"。
     *
     * 判据落在 `supervisedCount`：生产实现里取消监管作业会让运行作用域被取消，
     * 从而走 kill 路径（与熔断第 2 步同一机制），不是只停掉循环。
     */
    @Test
    @DisplayName("P3 不变量 3：拨关总闸 ⇒ 已在跑的常驻脚本被终止")
    fun turningTheMasterSwitchOffTerminatesRunningDaemons() =
        scope.runTest {
            scripts.value = listOf(script(1, resident = true), script(2, resident = true))
            val supervisor = supervisor()

            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(2, supervisor.supervisedCount, "前置：两个常驻脚本都在被监管")

                masterSwitch.set(false)
                advanceTimeBy(500L)

                assertEquals(
                    0,
                    supervisor.supervisedCount,
                    "关闸后监管必须清空（进程随之被终止）：实测 ${supervisor.supervisedCount}",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * ★ **不变量 4**：打开总闸 ⇒ 已启用脚本**立即**启动，不必等下次服务启动。
     *
     * 为什么"立即"是硬要求：用户拨开总开关时的预期就是"现在开始干活"。
     * 若只在下次 `start()` 才生效，用户会以为开关坏了 —— 而日志里连一行都不会有
     * （这正是阶段 10 在"勾选 always_run 后没反应"上踩过的同一个坑型）。
     *
     * 顺带钉住：总闸只放开闸门，**不改变**脚本自己的判据 —— `resident = false`
     * 的单次脚本不会因为拨开总闸就变成常驻。
     */
    @Test
    @DisplayName("P3 不变量 4：拨开总闸 ⇒ 已启用脚本立即被拉起")
    fun turningTheMasterSwitchOnStartsDaemonsImmediately() =
        scope.runTest {
            masterSwitch.set(false)
            scripts.value = listOf(script(1, resident = true), script(2, resident = false))
            val supervisor = supervisor()

            supervisor.start()
            advanceTimeBy(500L)
            try {
                assertEquals(0, supervisor.supervisedCount, "前置：总闸关着 ⇒ 一个都不该起")
                assertTrue(
                    runner.startedScriptIds.isEmpty(),
                    "实测启动了 ${runner.startedScriptIds}",
                )

                masterSwitch.set(true)
                advanceTimeBy(500L)

                assertEquals(
                    listOf(1L),
                    runner.startedScriptIds,
                    "只有 resident=true 的 1 号该起；2 号是单次脚本，不因总闸而常驻",
                )
            } finally {
                supervisor.stop()
                runner.cancelRuns()
            }
        }

    /**
     * 可编排的投递替身。
     *
     * ## 它做三件事
     * 1. 记下**被调用了几次、传了什么参数**
     * 2. 按 [rejectAll] 决定是否受理
     * 3. 受理后**真的把运行登记进 [RunSessionRegistry]**，并在 [runDurationMillis] 后注销
     *    —— 这是必须的（见类 KDoc 的约束 ③）：监工靠 `activeRuns()` 判断这一轮结束。
     *
     * 真的起进程不属于 `DaemonSupervisorImpl` 的职责，
     * 由 `TriggeredScriptRunner` 的既有测试覆盖。
     *
     * @param scope **必须是测试作用域**：结束运行的 `delay` 要靠虚拟时间驱动，
     *   用真实调度器会让监工永远等不到这一轮结束。
     */
    private class FakeScriptRunner(
        private val registry: RunSessionRegistry,
        private val scope: CoroutineScope,
    ) : ScriptRunner {
        val startedScriptIds = mutableListOf<Long>()
        val startedEvents = mutableListOf<String>()
        val timeoutOverrides = mutableListOf<Long?>()

        var startCount = 0
            private set

        var acceptedCount = 0
            private set

        /** 拒绝一切受理（模拟闸门满 / 安全模式 / 脚本被禁用）。 */
        var rejectAll: Boolean = false

        /**
         * 受理后让这次运行活多久。
         *
         * **默认 `Long.MAX_VALUE`（不结束）**：这样"首次启动"类断言与时间窗无关 ——
         * 脚本一直活着 ⇒ 监工不会重启它 ⇒ 启动次数恒等于脚本数。
         * 需要观测"退出后重启"的用例再显式调小它。
         *
         * ⚠️ 用不结束的值时，用例收尾必须调 [cancelRuns]，否则 `runTest`
         * 会等这些永不结束的子协程而撞 60 秒真实超时。
         */
        var runDurationMillis: Long = Long.MAX_VALUE

        private var runSeq = 0
        private val runJobs = mutableListOf<Job>()

        /** 取消"仍在跑"的那些模拟运行（收尾用，见 [runDurationMillis] 的说明）。 */
        fun cancelRuns() {
            runJobs.forEach { it.cancel() }
            runJobs.clear()
        }

        override suspend fun start(
            scriptId: Long,
            triggerEvent: String,
            payload: String?,
            timeoutOverrideMillis: Long?,
        ): Boolean {
            startCount += 1
            if (rejectAll) return false
            startedScriptIds += scriptId
            startedEvents += triggerEvent
            timeoutOverrides += timeoutOverrideMillis
            acceptedCount += 1

            // 把这一轮登记成"正在跑"，随后按时长结束它。
            val runId = "fake-run-${runSeq++}"
            registry.register(runId, scriptId)
            runJobs +=
                scope.launch {
                    delay(runDurationMillis)
                    registry.unregister(runId)
                }
            return true
        }
    }
}
