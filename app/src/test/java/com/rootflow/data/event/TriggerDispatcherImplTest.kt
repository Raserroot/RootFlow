package com.rootflow.data.event

import com.rootflow.data.run.FakeEventChannel
import com.rootflow.domain.event.EventDelivery
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.Trigger
import com.rootflow.domain.model.TriggerParams
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TriggerDispatcherImpl] 单测（3b 清单 7–16；**P5 起断言对象已变**）。
 *
 * ## ★ 断言对象从「启动了几次」变成「投递了几次」
 * P5 把本类的职责从"启动脚本"改成"把事件**投递**给正在运行的脚本"（方案 §2.1 的解读 C）。
 * 因此本文件里的 `runner.started` 全部换成了 `channel.delivered`：
 * - 每一项是 `(scriptId, line)`，`line` 就是投递的那一行（`<eventId> [payload]`）
 * - 原来两条"重入拒绝"用例**随语义一起消失** —— 投递不是启动，重入在投递路径上不成立。
 *   替身是三条更贴近新语义的：`NotRunning` 留痕、没送达也计入防抖、`NoReader` 与
 *   `NotRunning` **分开报**
 *
 * 依赖全部为手写假件，**纯 JVM、无 Robolectric**。时间用注入的 `clock` 直接控制，
 * 因此防抖窗口的边界断言是**确定的**（不依赖真实等待）。
 */
class TriggerDispatcherImplTest {
    // ---------------------------------------------------------------- 匹配与投递

    @Test
    fun `delivers only the subscriptions returned for that event`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel)

            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(listOf(1L), channel.delivered.map { it.first }, "只投给订阅了该事件的脚本")
            assertEquals(
                listOf(SystemEvent.BOOT),
                channel.delivered.map { it.second },
                "投递的行就是事件键 —— 脚本侧 read -r ev 拿到的就是它",
            )
            // 查询用的键必须与 eventId 一致
            assertEquals(listOf(SystemEvent.BOOT), triggers.queried)
        }

    @Test
    fun `an event nobody subscribes to delivers nothing and leaves a trace`() =
        runTest {
            // forEvent 只返回启用的；这里模拟"一个都没启用"
            val triggers = fakeTriggers()
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel, warnings = warnings)

            dispatcher.dispatch(SystemEvent.Boot)

            assertTrue(channel.delivered.isEmpty(), "无匹配订阅时不得投递")
            // ★ 阶段 6b 真机缺陷的直接产物：零条时**必须留痕**。
            // 在此之前这条路径一行日志都没有（循环体为空），于是"事件到了、fixture 说齐全、
            // 什么都不跑"在真机日志里完全无法归因。走 `onWarning` 缝而不是 `Log.i`，
            // 是因为本文件的用例直接调 dispatch() 且单测里 android.util.Log 未 stub（§5.10）。
            assertTrue(
                warnings.any { it.contains("TRIGGER_LOOKUP") && it.contains(SystemEvent.BOOT) },
                "零条订阅必须留一行可判读的日志（否则是静默失败），实际=$warnings",
            )
        }

    @Test
    fun `all matching subscriptions are delivered in repository order`() =
        runTest {
            val triggers =
                fakeTriggers(
                    trigger(scriptId = 3L, eventType = SystemEvent.BOOT),
                    trigger(scriptId = 1L, eventType = SystemEvent.BOOT),
                    trigger(scriptId = 2L, eventType = SystemEvent.BOOT),
                )
            val channel = FakeEventChannel()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel)

            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(
                listOf(3L, 1L, 2L),
                channel.delivered.map { it.first },
                "顺序应保持仓库返回顺序（判读时「谁先被投递」要可复现）",
            )
        }

    // ------------------------------------------------ 防抖（P5：它就是 §10 要求的窗口合并）

    @Test
    fun `second delivery inside the debounce window is dropped`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            var now = 0L
            val dispatcher =
                newDispatcher(triggers = triggers, channel = channel, clock = { now }, warnings = warnings)

            dispatcher.dispatch(SystemEvent.Boot)
            now = 100L
            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(1, channel.delivered.size, "窗口内第二次必须被防抖丢弃")
            assertTrue(
                warnings.any { it.contains("debounced") },
                "防抖必须有可判读的留痕（否则会变成「事件丢了」）：$warnings",
            )
        }

    @Test
    fun `delivery after the debounce window succeeds`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            var now = 0L
            val dispatcher = newDispatcher(triggers = triggers, channel = channel, clock = { now })

            dispatcher.dispatch(SystemEvent.Boot)
            now = TriggerDispatcherImpl.DEFAULT_DEBOUNCE_WINDOW_MILLIS + 1
            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(2, channel.delivered.size, "窗口之外必须再次投递")
        }

    @Test
    fun `debounce is per script and per event`() =
        runTest {
            val triggers =
                fakeTriggers(
                    trigger(scriptId = 1L, eventType = SystemEvent.BOOT),
                    trigger(scriptId = 2L, eventType = SystemEvent.BOOT),
                )
            val channel = FakeEventChannel()
            var now = 0L
            val dispatcher = newDispatcher(triggers = triggers, channel = channel, clock = { now })

            // 第一个脚本投一次，第二个脚本在同一窗口内投一次
            dispatcher.dispatch(SystemEvent.Boot)
            now = 100L
            dispatcher.dispatch(SystemEvent.ScreenOff)

            assertEquals(2, channel.delivered.size, "不同事件的防抖计数必须互相独立")
            assertEquals(listOf(1L, 2L), channel.delivered.map { it.first })
        }

    // ---------------------------------------------------------------- 没送达的三种结局

    /**
     * ★ P5 之后的**常见**结局：那个脚本此刻没在运行（单次脚本 / 正在退避 / 通道开不出来）。
     *
     * 它必须留痕，且**不能**与"投递失败"混为一谈：这里通道回答了 `NotRunning`，
     * 说明宿主问过了、答案是"没人收"。用户需要知道的是"事件发生了但没人接"。
     */
    @Test
    fun `a script that is not running is reported instead of silently skipped`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            // 不 openFor ⇒ 这个脚本**没有通道**（等价于"它没在运行"）
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher =
                newDispatcher(
                    triggers = triggers,
                    channel = channel,
                    scriptsRunning = false,
                    warnings = warnings,
                )

            dispatcher.dispatch(SystemEvent.Boot)

            assertTrue(
                channel.delivered.isEmpty(),
                "没有通道 ⇒ 投递不会发生（`delivered` 只记送达尝试）：${channel.delivered}",
            )
            assertTrue(
                warnings.any { it.contains("EVENT_NOT_RUNNING") && it.contains("script=1") },
                "没在跑的脚本必须留痕（P5 之后这是最常见的结局）：$warnings",
            )
        }

    /**
     * **没送达的投递也计入防抖窗口**。
     *
     * 防抖的语义是"同一 (脚本, 事件) 短期内不重复**尝试**"。若失败的尝试不记账，
     * 一个没在运行的脚本会让每个事件都重新尝试一次投递 —— 而每次都是一次 `su` 往返，
     * 正是方案 §10 约束 ③ 要求合并掉的開销。
     */
    @Test
    fun `a not delivered attempt still counts into the debounce window`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            // 脚本没在跑（没有通道）⇒ 第一次投递就是 NotRunning ⇒ 但**它仍然记账**
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            var now = 0L
            val dispatcher =
                newDispatcher(
                    triggers = triggers,
                    channel = channel,
                    scriptsRunning = false,
                    clock = { now },
                    warnings = warnings,
                )

            dispatcher.dispatch(SystemEvent.Boot)
            now = 100L
            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(
                1,
                warnings.count { it.contains("EVENT_NOT_RUNNING") },
                "窗口内第二次**根本没走到投递**（防抖挡在它之前）⇒ NotRunning 只该出现一次：" +
                    "$warnings（不去重的话，一个没在运行的脚本会让每个事件都重付一次 su 往返）",
            )
        }

    /** `NoReader` 与 `NotRunning` **必须分开报**：前者的通道是存在的，只是没人读。 */
    @Test
    fun `no reader is reported separately from not running`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel().apply { deliveryResult = EventDelivery.NoReader }
            val warnings = mutableListOf<String>()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel, warnings = warnings)

            dispatcher.dispatch(SystemEvent.Boot)

            assertTrue(
                warnings.any { it.contains("EVENT_NO_READER") },
                "通道在、没人读 ⇒ 必须报 NoReader：$warnings",
            )
            assertTrue(
                warnings.none { it.contains("EVENT_NOT_RUNNING") },
                "不得报成 NotRunning —— 两者的排查方向不同（「没有门」vs「门开着没人应」）：$warnings",
            )
        }

    @Test
    fun `one failing delivery does not prevent the others`() =
        runTest {
            val triggers =
                fakeTriggers(
                    trigger(scriptId = 1L, eventType = SystemEvent.BOOT),
                    trigger(scriptId = 2L, eventType = SystemEvent.BOOT),
                    trigger(scriptId = 3L, eventType = SystemEvent.BOOT),
                )
            val channel = FakeEventChannel().apply { failForScripts += 2L }
            val warnings = mutableListOf<String>()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel, warnings = warnings)

            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(
                listOf(1L, 2L, 3L),
                channel.delivered.map { it.first },
                "三条都要被尝试（2 号失败不得影响 1/3 号）",
            )
            assertTrue(
                warnings.any { it.contains("EVENT_DELIVER_FAILED") && it.contains("script=2") },
                "失败必须点名是哪个脚本：$warnings",
            )
        }

    // ---------------------------------------------------------------- 查表与载荷

    @Test
    fun `repository failure does not propagate`() =
        runTest {
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher = newDispatcher(triggers = failingTriggers(), channel = channel, warnings = warnings)

            // 不抛即通过（需求 §2.2 的"逐条投递"前提是能拿到列表；拿不到就记日志返回，
            // 不让异常穿透到事件源——那会炸掉广播接收器）
            dispatcher.dispatch(SystemEvent.Boot)

            assertTrue(channel.delivered.isEmpty(), "查表失败 ⇒ 一条都不该投")
            assertTrue(
                warnings.any { it.contains("trigger lookup failed") },
                "查表失败必须留痕（否则是静默失败）：$warnings",
            )
        }

    /**
     * 投递行的格式（**P5 定死，勿改**）：无负载时**只有事件键**。
     *
     * 脚本侧 `read -r ev` 拿到的就是 `boot` —— 一个不带任何装饰的字符串，
     * `case "$ev" in boot) …;; esac` 直接可用。
     */
    @Test
    fun `the delivered line is just the event when there is no payload`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel)

            dispatcher.dispatch(SystemEvent.Boot)

            assertEquals(listOf(SystemEvent.BOOT), channel.delivered.map { it.second })
        }

    /**
     * 有负载时：`<eventId> <payload>`（空格分隔，payload 可含空格）。
     *
     * 这样 `read -r ev payload` **一行**就能同时拿到两者，而 payload 里的空格
     * 不会破坏解析（`read` 把剩余整行交给第二个变量）。编辑器里的 `EVENTS_READ_HINT`
     * 与这个格式一致。
     */
    @Test
    fun `the delivered line carries the payload after the event`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            val dispatcher = newDispatcher(triggers = triggers, channel = channel)

            dispatcher.dispatch(SystemEvent.Boot, payloadOverride = """{"reason":"manual","note":"a b"}""")

            assertEquals(
                listOf("""boot {"reason":"manual","note":"a b"}"""),
                channel.delivered.map { it.second },
                "eventId 在前、payload 在后（payload 里的空格原样保留）",
            )
        }

    // ---------------------------------------------------------------- 工具

    private fun trigger(
        scriptId: Long,
        eventType: String,
        enabled: Boolean = true,
    ): Trigger =
        Trigger(
            id = scriptId,
            scriptId = scriptId,
            eventType = eventType,
            params = TriggerParams(),
            enabled = enabled,
            createdAt = 0L,
        )

    /**
     * 造一个 dispatcher。
     *
     * ## 为什么默认把所有订阅者标成"正在运行"（`scriptsRunning = true`）
     * 真实实现里"投递"的前提是**那个脚本有通道**（没开过通道 ⇒ `NotRunning`）。
     * 本文件绝大多数用例关心的是"投递**之后**的行为"（防抖 / 载荷格式 / 三态留痕），
     * 让它们在默认情况下都能投得出去，断言才聚焦在各自要测的那件事上。
     *
     * 要测"脚本没在跑"这条 P5 核心语义的用例显式传 `scriptsRunning = false`
     * —— 那时候"没送达"是**被测对象**，不是噪声。
     */
    private fun TestScope.newDispatcher(
        triggers: FakeTriggerRepository,
        channel: FakeEventChannel = FakeEventChannel(),
        scriptsRunning: Boolean = true,
        clock: () -> Long = { 0L },
        warnings: MutableList<String> = mutableListOf(),
    ): TriggerDispatcherImpl =
        TriggerDispatcherImpl(
            triggerRepository = triggers,
            eventChannel = if (scriptsRunning) channel.apply { openFor(*triggers.scriptIds()) } else channel,
            eventBus = RecordingEventBus(),
            // 本测试全部直接调用 dispatch()，不经过总线订阅 ⇒ 作用域只被 **P8 的 boot 重试**
            // 用到（它 `launch` 到这个 TestScope 上，因此虚拟时间能推进它、`runTest` 也会等它）。
            scope = this,
            debounceWindowMillis = TriggerDispatcherImpl.DEFAULT_DEBOUNCE_WINDOW_MILLIS,
            clock = clock,
            onWarning = warnings::add,
        )

    // ---------------------------------------------------------------- P8：boot 的有界重试

    /**
     * boot 的收件人（常驻脚本）有一个**结构性**的时间差（见 `scheduleBootRetry` 的 KDoc）：
     * boot 在 `Application.onCreate` 的启动链里发出，而脚本要等前台服务注册 →
     * `DaemonSupervisor` 读 Room → 起 root 进程。这条用例钉住"等一会儿就能送到"。
     */
    @Test
    fun `boot is retried until the script comes up`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            // 首投时脚本还没起来 —— 真机上的**常态**
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher =
                newDispatcher(
                    triggers = triggers,
                    channel = channel,
                    scriptsRunning = false,
                    warnings = warnings,
                )

            dispatcher.dispatch(SystemEvent.Boot)
            assertTrue(
                warnings.any { it.startsWith("EVENT_NOT_RUNNING") },
                "前置：首投必须如实记 NotRunning：$warnings",
            )

            // 脚本起来了（真机上就是那几百毫秒之后）
            channel.openFor(1L)
            advanceTimeBy(TriggerDispatcherImpl.DEFAULT_BOOT_RETRY_INTERVAL_MILLIS + 1)
            runCurrent()

            assertTrue(
                warnings.any { it.startsWith("EVENT_BOOT_DELIVERED") && it.contains("script=1") },
                "脚本起来之后 boot 必须补投成功 —— 否则「开机时做点什么」根本不成立：$warnings",
            )
            assertTrue(
                channel.delivered.contains(1L to SystemEvent.BOOT),
                "实际送达的那一行要进 delivered：${channel.delivered}",
            )
        }

    /**
     * **只有 boot** 有重试。
     *
     * 别的事件投不出去就是**真的**投不出去（用户没开那个脚本 / 它已经退出了），
     * 等下去只会把"通知"悄悄变成"队列"—— 那正是方案 §2.1 明确否掉的语义。
     */
    @Test
    fun `other events are not retried`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.SCREEN_ON))
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher =
                newDispatcher(
                    triggers = triggers,
                    channel = channel,
                    scriptsRunning = false,
                    warnings = warnings,
                )

            dispatcher.dispatch(SystemEvent.ScreenOn)
            // 脚本随后起来了 —— 但事件**不该**被补投
            channel.openFor(1L)
            advanceTimeBy(TriggerDispatcherImpl.DEFAULT_BOOT_RETRY_INTERVAL_MILLIS * 5)
            runCurrent()

            assertTrue(
                warnings.none { it.startsWith("EVENT_DELIVERED") },
                "非 boot 事件不得被补投：$warnings",
            )
            assertTrue(
                channel.delivered.isEmpty(),
                "重试只属于 boot，别的投递尝试一条都不该有：${channel.delivered}",
            )
        }

    /** 重试**有界**：脚本一直不起来就如实放弃（不静默，也不无限等）。 */
    @Test
    fun `boot gives up after the bounded window`() =
        runTest {
            val triggers = fakeTriggers(trigger(scriptId = 1L, eventType = SystemEvent.BOOT))
            val channel = FakeEventChannel()
            val warnings = mutableListOf<String>()
            val dispatcher =
                newDispatcher(
                    triggers = triggers,
                    channel = channel,
                    scriptsRunning = false,
                    warnings = warnings,
                )

            dispatcher.dispatch(SystemEvent.Boot)
            // 脚本始终不起来 ⇒ 把整个窗口推完
            advanceTimeBy(
                TriggerDispatcherImpl.DEFAULT_BOOT_RETRY_INTERVAL_MILLIS *
                    (TriggerDispatcherImpl.DEFAULT_BOOT_RETRY_ATTEMPTS + 1),
            )
            runCurrent()

            assertTrue(
                warnings.any { it.startsWith("EVENT_BOOT_GIVEN_UP") && it.contains("script=1") },
                "窗口用尽必须留痕（否则用户只看到「什么都没发生」）：$warnings",
            )
        }

    private fun fakeTriggers(vararg items: Trigger): FakeTriggerRepository = FakeTriggerRepository(items.toList())

    private fun failingTriggers(): FakeTriggerRepository = FakeTriggerRepository(emptyList(), failLookup = true)

    private class FakeTriggerRepository(
        private val items: List<Trigger>,
        private val failLookup: Boolean = false,
    ) : TriggerRepository {
        val queried = mutableListOf<String>()

        /** 订阅表里出现过的 scriptId（供 [newDispatcher] 声明"这些脚本在跑"）。 */
        fun scriptIds(): LongArray = items.map { it.scriptId }.distinct().toLongArray()

        override fun observeForScript(scriptId: Long): Flow<List<Trigger>> =
            MutableStateFlow(items.filter { it.scriptId == scriptId })

        override fun observeAll(): Flow<List<Trigger>> = MutableStateFlow(items)

        override suspend fun all(): List<Trigger> = items

        override suspend fun forEvent(eventType: String): List<Trigger> {
            queried += eventType
            if (failLookup) throw IllegalStateException("db is on fire")
            return items.filter { it.eventType == eventType && it.enabled }
        }

        override suspend fun replaceForScript(
            scriptId: Long,
            triggers: List<Trigger>,
        ): WriteResult<List<Trigger>> = WriteResult.Ok(triggers)

        override suspend fun delete(id: Long): WriteResult<Unit> = WriteResult.Ok(Unit)

        override suspend fun countForScript(scriptId: Long): Int = items.count { it.scriptId == scriptId }
    }
}
