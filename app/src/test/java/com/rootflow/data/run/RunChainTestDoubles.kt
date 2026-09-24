package com.rootflow.data.run

import com.rootflow.domain.event.EventChannel
import com.rootflow.domain.event.EventDelivery
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.model.AppSwitch
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.model.LogTail
import com.rootflow.domain.model.Script
import com.rootflow.domain.repository.LogBatchSink
import com.rootflow.domain.repository.LogPipeline
import com.rootflow.domain.repository.RunMeta
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.runtime.LogLine
import com.rootflow.runtime.RunContext
import com.rootflow.runtime.RunHandle
import com.rootflow.runtime.ScriptEntity
import com.rootflow.runtime.ScriptRuntime
import com.rootflow.runtime.createRunHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import com.rootflow.runtime.LogStream as RuntimeLogStream

// 3d 的测试替身集合（纯 JVM，无 Android 依赖）。
// 放在一个文件里是有意的：它们是 **3d 执行链路**的共用脚手架，读一次就能掌握全部注入面。

/**
 * 记录型脚本仓库假件。
 *
 * 用"按 id 预置结果"而不是 MockK：用例要覆盖 `load` 的**四种失败结果**，
 * 手写假件能把"返回什么"直接写在用例里（`put(1L, ScriptLoadResult.Missing)`），
 * 比 `coEvery { … } returnsMany …` 更易读，也不受 MockK 默认参数匹配问题影响。
 */
internal class FakeScriptRepository(
    private val results: MutableMap<Long, ScriptLoadResult> = mutableMapOf(),
) : ScriptRepository {
    /** `load` 被调用的 id 序列（用于断言"失败分支不得继续往下走"）。 */
    val loaded = mutableListOf<Long>()

    fun put(
        id: Long,
        result: ScriptLoadResult,
    ) {
        results[id] = result
    }

    override fun observeAll(): Flow<List<Script>> = flow { emit(emptyList()) }

    override suspend fun load(id: Long): ScriptLoadResult {
        loaded += id
        return results[id] ?: ScriptLoadResult.NotFound
    }

    override suspend fun save(script: Script): WriteResult<Script> = WriteResult.Ok(script)

    override suspend fun delete(id: Long): WriteResult<Unit> = WriteResult.Ok(Unit)
}

/**
 * 记录型日志管道假件（只保留 `startRun` 的入参并同步收集 source）。
 *
 * ## 为什么必须记录 `batchSink` 与 `runMeta`
 * 3d 方案 §8 的**候选 A** 全部价值就在"运行元与日志源在同一次调用里交给管道"。
 * 若不用例断言这一步，落库侧的 `scriptId` 来源就成了"看起来接上了"。
 *
 * 本假件**同步收集** source（生产实现是后台收集），因此用例可以用
 * `advanceUntilIdle()` 精确控制"脚本已跑完"这一时刻。
 */
internal class RecordingLogPipeline : LogPipeline {
    /** 一次 `startRun` 的入参快照。 */
    data class StartCall(
        val runId: String,
        val runMeta: RunMeta?,
        val batchSink: LogBatchSink?,
    )

    val starts = mutableListOf<StartCall>()

    /** 收集到的条目（按运行分组），便于断言"日志真的流过来了"。 */
    val collected = mutableMapOf<String, MutableList<LogEntry>>()

    override suspend fun startRun(
        runId: String,
        source: Flow<LogEntry>,
        runMeta: RunMeta?,
        batchSink: LogBatchSink?,
    ) {
        starts += StartCall(runId = runId, runMeta = runMeta, batchSink = batchSink)
        val sink = collected.getOrPut(runId) { mutableListOf() }
        source.collect { entry -> sink += entry }
    }

    override fun observe(runId: String): Flow<LogBatch> = flow { }

    override suspend fun tail(
        runId: String,
        limit: Int,
    ): LogTail = LogTail(runId = runId, entries = emptyList(), droppedEntries = 0L)

    override suspend fun markFinished(runId: String) = Unit

    override suspend fun release(runId: String) = Unit
}

/**
 * 假脚本运行时：`run()` 只创建句柄，日志在**第一次收集时**发射。
 *
 * 与生产契约一致（`ScriptRuntime` 的 KDoc：`run` 不执行脚本，执行发生在收集时）。
 *
 * [outcome] 决定句柄被收集时的行为（见 [RuntimeOutcome]）：正常跑完 / 永久挂起 / 抛错。
 */
internal class FakeScriptRuntime(
    val outcome: RuntimeOutcome = RuntimeOutcome.Completed(defaultLines()),
) : ScriptRuntime {
    /** 每次 `run` 的入参（断言映射与环境变量注入）。 */
    val runs = mutableListOf<Pair<ScriptEntity, RunContext>>()

    override suspend fun run(
        script: ScriptEntity,
        ctx: RunContext,
    ): RunHandle = recordRun(script, ctx)

    /**
     * 记录入参并产出一个句柄（不经过 `run`）。
     *
     * 存在的理由：`ShellScriptRuntime` 是**具体类**且本阶段不改 runtime 公开接口，
     * 因此单测经 MockK 打桩 `ShellScriptRuntime.run`，再调本方法拿到句柄并记录入参。
     */
    fun recordRun(
        script: ScriptEntity,
        ctx: RunContext,
    ): RunHandle {
        runs += script to ctx
        val current = outcome
        // 注意 `createRunHandle` 的 lambda 是 `suspend RunHandle.(FlowCollector<LogLine>) -> Unit`：
        // **接收者是 RunHandle，收集器是形参**，因此必须显式接收形参再 `emit`。
        return createRunHandle { collector ->
            when (current) {
                is RuntimeOutcome.Completed -> current.lines.forEach { line -> collector.emit(line) }
                // 第一行之后永久挂起：使"这次运行仍在进行中"成为可观测状态
                is RuntimeOutcome.Hanging -> {
                    collector.emit(current.firstLine)
                    awaitCancellation()
                }
                is RuntimeOutcome.Fails -> throw current.error
            }
        }
    }

    override suspend fun kill(
        handle: RunHandle,
        force: Boolean,
    ) = Unit
}

/** [FakeScriptRuntime] 的四种执行结局。 */
internal sealed interface RuntimeOutcome {
    /** 正常跑完，发射 [lines]。 */
    data class Completed(
        val lines: List<LogLine>,
    ) : RuntimeOutcome

    /** 只发射 [firstLine] 后永久挂起（运行保持"进行中"）。 */
    data class Hanging(
        val firstLine: LogLine,
    ) : RuntimeOutcome

    /** 句柄被收集时抛错（覆盖"源故障"路径）。 */
    data class Fails(
        val error: Throwable = IllegalStateException("runtime exploded"),
    ) : RuntimeOutcome
}

/** 默认日志：SYS 启动行 + STDOUT + 带退出码的 SYS 行（D6 的提取来源）。 */
internal fun defaultLines(): List<LogLine> =
    listOf(
        LogLine(1L, RuntimeLogStream.SYS, "script 1 started (trigger=boot)"),
        LogLine(2L, RuntimeLogStream.STDOUT, "hello"),
        LogLine(3L, RuntimeLogStream.SYS, "script 1 exited with code 0"),
    )

/**
 * 便捷构造：一个**尚未入库**的 domain 脚本（`id = 0`）。
 *
 * ## 默认 `id = 0` 是有意的（3d 实测教训）
 * `ScriptRepository.save` 的契约是"**`id == 0` 才走新增**"（`ScriptRepositoryImpl.save`）。
 * 若把默认值写成非 0，任何把它交给真实仓库的用例都会走**更新**分支，
 * 于是得到 `Failed(reason=script N not found)` —— 表现为"仓库功能坏了"，
 * 实则是测试构造错了对象。需要特定 id 的用例显式传 `id = …`（那是"已入库脚本"的语义）。
 *
 * ## 阶段 4 新增两个形参
 * [runOnSafeMode] 与 [timeoutSec] 此前是 `ScriptEntity` 上"只承载不生效"的字段
 * （1b 起如此），阶段 4 起真正生效，因此构造器必须能在用例里驱动它们。
 * 默认值取"最保守"：不在安全模式运行、超时 0（按需求 §5.1 回落到全局 60s）。
 */
internal fun script(
    id: Long = 0L,
    name: String = "s",
    enabled: Boolean = true,
    content: String = "echo hi",
    runOnSafeMode: Boolean = false,
    timeoutSec: Int = 0,
): Script =
    Script(
        id = id,
        name = name,
        language = "shell",
        enabled = enabled,
        timeoutSec = timeoutSec,
        autoDisableOnFail = false,
        runOnSafeMode = runOnSafeMode,
        content = content,
        contentSha256 = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

/** 便捷构造：`ScriptLoadResult.Ok` 包一个脚本。 */
internal fun okScript(script: Script = script()): ScriptLoadResult = ScriptLoadResult.Ok(script)

/** domain 条目列表的便捷构造（落库用例用）。 */
internal fun entries(
    count: Int,
    runId: String = "run-x",
    stream: LogStream = LogStream.STDOUT,
): List<LogEntry> =
    List(count) { index ->
        LogEntry(
            runId = runId,
            sequence = index.toLong(),
            timestamp = index.toLong(),
            stream = stream,
            text = "line-$index",
        )
    }

/**
 * 总闸假件（总开关重构 **P3**）。
 *
 * ## ★ 默认值是 `initiallyEnabled = true` —— 这是**有意**的
 * 生产的安全默认是**关闭**（`AppSwitch.SafeDefault` / 迁移写入 `master_enabled = 0`），
 * 而这个替身默认**打开**，因为绝大多数既有用例测的是别的语义：
 * - `DaemonSupervisorImplTest`：`resident` / `enabled` 的对账
 * - `TriggeredScriptRunnerTest`：装载失败 / 安全模式 / 准入闸门
 * - `EndToEndWiringTest`：整条链路的接线
 *
 * 那些用例里总闸**不是被测对象**；让它们默认跑在"总闸关闭"下会把 40+ 条用例
 * 一次性变成"什么都没发生"，而那不说明任何一个被测语义有问题。
 * 总闸关闭的后果由**专门的不变量用例**钉住（`DaemonSupervisorImplTest` /
 * `TriggeredScriptRunnerTest` 各一条），而不是靠替身的默认值。
 *
 * ## 为什么可变而不是"构造时定死"
 * 不变量 3/4 要求"拨动总闸"这件事**能被用例驱动** —— 那正是 `StateFlow` 的语义。
 */
internal class FakeMasterSwitch(
    initiallyEnabled: Boolean = true,
) : MasterSwitch {
    private val _enabled = MutableStateFlow(initiallyEnabled)

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override val isEnabled: Boolean
        get() = _enabled.value

    /** `restoreFromStore` 的调用次数（启动链只在最前面调一次）。 */
    var restoreCalls: Int = 0
        private set

    /** `setEnabled` 收到的值序列（顺序敏感：用来断言"拨了几次、拨成什么"）。 */
    val setCalls: MutableList<Boolean> = mutableListOf()

    /** 非 null 时下一次 `setEnabled` 返回它（**不改快照**，与真实实现的写败语义一致）。 */
    var failNextWrite: String? = null

    override suspend fun restoreFromStore(): Boolean {
        restoreCalls++
        return _enabled.value
    }

    override suspend fun setEnabled(enabled: Boolean): WriteResult<AppSwitch> {
        setCalls += enabled
        failNextWrite?.let { reason ->
            failNextWrite = null
            return WriteResult.Failed(reason)
        }
        _enabled.value = enabled
        return WriteResult.Ok(AppSwitch(masterEnabled = enabled, enabledAt = 0L, disabledAt = 0L, updatedAt = 0L))
    }

    /** 用例侧直接拨闸（模拟用户动作，或"别人改了这条流"）。 */
    fun set(value: Boolean) {
        _enabled.value = value
    }
}

/**
 * 事件通道假件（P5）。
 *
 * ## 为什么默认"开得出通道"
 * 绝大多数既有用例测的不是通道本身。若默认开不出来，那些用例里的脚本环境会缺
 * `ROOTFLOW_EVENT_FIFO` —— 而它们并不关心那个变量，只会让断言莫名少一项。
 * "开不出来"这条分支由专门的用例显式构造（"没有通道时不注入该变量"）。
 *
 * ## 为什么 [delivered] 记录 `(runId, line)` 而不是只记 line
 * 同一次投递的**归属**是语义的一部分：投错 runId 意味着事件送到了上一轮运行的通道里
 * （而那正是 `RootFlowPaths.eventFifo` 按 runId 建通道要防的事）。
 */
internal class FakeEventChannel(
    private val openResult: String? = "/data/local/tmp/rootflow/ipc/fake-run.q",
) : EventChannel {
    /**
     * `open` 收到的 `(runId, scriptId)`（顺序敏感）。
     *
     * 两个都记：`runId` 用来断言"通道 id 与本次运行的 runId 一致"（判读要对得上），
     * `scriptId` 用来断言"投递侧能按脚本找到通道"（那是 P5b 的关键映射）。
     */
    val opened = mutableListOf<Pair<String, Long>>()

    /**
     * **已有通道**的 scriptId。
     *
     * ## ★ 这是本替身最容易写歪的地方（第一版就写歪了，被一条端到端用例抓到）
     * 真实实现里，投递先查"这个脚本有没有通道"：**没有 ⇒ `NotRunning`**。
     * 第一版替身**无条件**记录并返回 `Delivered` ⇒ 于是"脚本没在跑 ⇒ 投递不到"
     * 这条最核心的 P5 语义**在替身层面被抹掉了**，端到端用例里那条断言必然失败
     * （而且失败信息会指向被测代码，指向错误的排查方向）。
     *
     * 现在它与真实实现同语义：**没开过通道的脚本收不到投递**。
     * 只关心"投递了几次"的用例用 [openFor] 声明"这些脚本在跑"。
     */
    private val channels = mutableSetOf<Long>()

    /** `deliver` 收到的 `(scriptId, line)`（顺序敏感；**只有送达尝试才会被记下**）。 */
    val delivered = mutableListOf<Pair<Long, String>>()

    /** `close` 收到的 runId。 */
    val closed = mutableListOf<String>()

    /** `deliver` 的返回值（默认送达）。 */
    var deliveryResult: EventDelivery = EventDelivery.Delivered

    /**
     * 命中这些 `scriptId` 时 `deliver` 返回 [EventDelivery.Failed]。
     *
     * 存在的理由与 `TriggerDispatcherImplTest` 的一条用例直接相关：
     * "一条投递炸掉不得让整批事件丢失"。用一个**按脚本**的失败集合（而不是"第 N 次失败"），
     * 断言才能精确到"哪一个脚本的那一次"—— 与 `FakeTriggerRepository.failSaveForEvents`
     * 同款理由（按对象而非按次数，避免顺序一变就测错对象）。
     */
    val failForScripts: MutableSet<Long> = mutableSetOf()

    /** 便捷：声明"这些脚本此刻正在运行"（等价于它们各自开过一条通道）。 */
    fun openFor(vararg scriptIds: Long) {
        channels += scriptIds.toList()
    }

    override suspend fun open(
        runId: String,
        scriptId: Long,
    ): String? {
        opened += runId to scriptId
        if (openResult == null) return null
        channels += scriptId
        return openResult
    }

    override suspend fun deliver(
        scriptId: Long,
        line: String,
    ): EventDelivery {
        // 与真实实现同语义：没有通道 ⇒ NotRunning（**不**记进 `delivered`）。
        if (scriptId !in channels) return EventDelivery.NotRunning
        delivered += scriptId to line
        if (scriptId in failForScripts) {
            return EventDelivery.Failed("channel exploded for $scriptId")
        }
        return deliveryResult
    }

    override suspend fun close(runId: String) {
        closed += runId
        // 与真实实现一致：关通道之后该脚本不再有通道（下一次投递会得到 NotRunning）。
        opened.firstOrNull { it.first == runId }?.let { (_, scriptId) -> channels -= scriptId }
    }
}
