package com.rootflow.data.event

import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventChannel
import com.rootflow.domain.event.EventDelivery
import com.rootflow.domain.event.SafeModeDecision
import com.rootflow.domain.event.TriggerDispatcher
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.repository.TriggerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TriggerDispatcher] 实现（阶段 3b；**P5 起语义已变**）。
 *
 * ## ★ 它现在做什么：把事件**投递**给正在运行的脚本（不再是"启动脚本"）
 * 总开关重构采纳的解读 C：事件是**投递给常驻脚本的通知**，而**不是**"启动某个脚本的理由"。
 * 因此本类不再依赖 `ScriptRunner`，也不再判"重入" —— 投递不是启动，
 * "同一脚本不允许并发运行"这条约束在投递路径上不成立（它由 `RunAdmissionGate` 管启动）。
 *
 * ## 判定顺序（需求 §5.4 + P5）
 * ```
 * ① 安全模式？      SafeModeDecision.shouldDispatchEvents → 是则**整个事件丢弃**并记 SAFEMODE_DROPPED
 * 对每条「已启用 + 订阅该事件」的订阅：
 *   2. 防抖窗口？  同一 (scriptId, eventId) 在 debounceWindowMillis 内已投递过 → 丢弃并记日志
 *   3. 投递        EventChannel.deliver(scriptId, "<eventId> [payload]")
 *                  · Delivered  ⇒ EVENT_DELIVERED
 *                  · NotRunning ⇒ EVENT_NOT_RUNNING （**P5 之后最常见**：脚本没在跑）
 *                  · NoReader   ⇒ EVENT_NO_READER   （通道在、此刻没人读）
 *                  · Failed     ⇒ EVENT_DELIVER_FAILED
 * ```
 *
 * ## ★ 三种"没送达"必须分开报（勿折叠成一句 "delivered=false"）
 * | 结局 | 含义 | 排查方向 |
 * |---|---|---|
 * | `NotRunning` | 该脚本**没有**运行中的通道 | 它是不是单次脚本？是不是正在退避？总闸开着吗？ |
 * | `NoReader` | 通道在，但此刻没有进程在读 | 脚本是不是卡在别处、没回到 `read`？ |
 * | `Failed` | root 通道 / `mkfifo` 出了事 | 看 `su` 是否可用、`ipc` 目录权限 |
 *
 * 而且 `NotRunning` 是 **P5 之后最常见**的一种结局：单次脚本、正在退避等待重启的常驻脚本、
 * 通道开不出来的脚本都会落到这里。**它必须留痕** —— 否则用户看到的是
 * "事件明明发生了、什么也没发生"（本仓库反复禁止的静默失败形态）。
 *
 * ## 防抖窗口现在承担**合并**职责（方案 §10 约束 ③）
 * 投递 = 一次 `su` 往返。窗口内同 (脚本, 事件) 只投一次，正是那条约束要求的
 * "投递侧按事件类型做窗口合并" —— 它复用 3b 就有的防抖表，没有新增机制。
 * 三种成功/失败结局**都记入窗口**（`Failed` 除外）：不去重的话，
 * 一个没在运行的脚本会让每个事件都重新尝试一次投递。
 *
 * ## ★ 安全模式在**最前面**，且**不停止订阅**（需求 §5.4）
 * "安全模式下**事件监听器注册但不分发**"是需求原文，因此这里的动作是
 * **丢弃单个事件并返回**，而**不是** `stop()` —— 停了订阅会让安全模式**解除之后**
 * 事件再也进不来（恢复路径必须手动重启分发），那是需求没有要求的行为。
 *
 * 判定放在**查 Room 之前**：安全模式下这条路径每个事件都要走一次，
 * 提前返回能省掉一次查询与逐条投递的日志噪声。
 * 判定与"某脚本能否跑"共用 [SafeModeDecision]，但**不是同一层判定**
 * （`shouldDispatchEvents` 管事件、`shouldRun` 管脚本），不可互相替代。
 *
 * ## 为什么不抛异常
 * 单条投递失败（[ScriptRunner] 抛错）只记日志，继续处理其余触发器——
 * 需求 §2.2 要求"逐条投递到 ScriptRuntime"，一条坏触发器不该让整批事件丢失。
 * 同理，[TriggerRepository] 本身失败也不让 [dispatch] 抛出（记日志后返回）。
 *
 * ## 防抖表的内存形态
 * `lastDispatchAt[(scriptId, eventId)] = 时刻`。**不做淘汰**：条目数上界是
 * 「脚本数 × 事件类型数」（本项目量级为几十），长期运行也不会成为内存问题。
 *
 * @param scope 订阅总线用的作用域（生产为应用级；单测传 `TestScope`）
 * @param debounceWindowMillis 防抖窗口，需求 §2.2 默认 **500ms**
 * @param clock 取时函数；单测注入虚拟时间
 * @param onWarning 告警回调（拒绝、丢弃、投递失败都经它）
 * @param afterDispatch "分发后动作"的初始值（阶段 3d）。生产接线用
 *   [addPostDispatchAction] 在 `RootFlowApp.onCreate` 注册；单测可直接经此注入，
 *   从而不必构造真实的 `AlarmSyncCoordinator`。
 *
 * ## 为什么**没有** `@Inject` 构造（阶段 3d 移除）
 * 本类的构造带多个默认参数（`debounceWindowMillis` / `clock` / `onWarning` /
 * `afterDispatch`），而 **Kotlin 默认参数对 Dagger 不可见**：加了 `@Inject` 之后
 * Dagger 会要求绑定 `Long` / `Function0<Long>` / `Function1<String, Unit>`，
 * 于 `hiltJavaCompileDebug` 阶段报一串 `MissingBinding`（3d 实测）。
 * 因此改由 `EventModule.provideTriggerDispatcher` 显式装配——
 * 与 `LogPipelineImpl` / `RunHistoryWriter` 同款处理。
 */
class TriggerDispatcherImpl(
    private val triggerRepository: TriggerRepository,
    /**
     * **投递通道**（P5）。
     *
     * ## ★ 这里以前是一个 `ScriptRunner`（"启动脚本"），现在是 `EventChannel`（"投递事件"）
     * 这不是换个接口，而是**语义换了**：事件不再是"启动这个脚本的理由"，而是
     * "投递给**正在运行的**那个脚本的通知"（方案 §2.1 的解读 C）。
     * 因此：
     * - `ScriptRunner` 与 `ScriptRunRegistry`（重入拒绝）**都不再需要**
     *   —— "投递"不是"启动"，重入这个概念在投递路径上不成立
     * - 没在运行的脚本**收不到事件**，而且是**常见**结局（见 [EventDelivery.NotRunning]）
     */
    private val eventChannel: EventChannel,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val debounceWindowMillis: Long = DEFAULT_DEBOUNCE_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onWarning: (String) -> Unit = {},
    private val circuitBreaker: CircuitBreaker? = null,
    afterDispatch: () -> Unit = {},
    /**
     * **boot 投不出去时的重试次数 / 间隔**（P8）。
     *
     * 带默认值在这里是**安全的**：本类由 `EventModule` **手动装配**，不是 `@Inject` 构造
     * （类 KDoc 记了原因），因此"Kotlin 默认参数对 Dagger 不可见"那条限制不适用。
     * 单测可以把它压到 1~2 次，不必等真实的 6 秒窗口。
     */
    private val bootRetryAttempts: Int = DEFAULT_BOOT_RETRY_ATTEMPTS,
    private val bootRetryIntervalMillis: Long = DEFAULT_BOOT_RETRY_INTERVAL_MILLIS,
) : TriggerDispatcher {
    private val running = AtomicBoolean(false)

    private val lastDispatchAt = mutableMapOf<DispatchKey, Long>()

    /** 阶段 3d：每次分发后要执行的动作（`AlarmSyncCoordinator.requestSync`）。 */
    private val postDispatchActions = mutableListOf(afterDispatch)

    /**
     * 追加一个"分发后动作"（阶段 3d）。
     *
     * ## 为什么用注册而不是直接构造注入 `AlarmSyncCoordinator`
     * 那样会形成 DI 环：`AlarmSyncCoordinator → AlarmEventSource`（取数/对账），
     * 而 `AlarmEventSource` 由 `EventSourceRegistry` 持有，registry 又在 `RootFlowApp`
     * 里启动——把同步依赖硬连进来会让启动顺序难以推理。注册式让接线点集中在
     * `RootFlowApp.onCreate`，依赖方向保持单向。
     *
     * @param action 分发后动作；**同步执行**，必须轻量（在调度器的协程内调用）
     */
    fun addPostDispatchAction(action: () -> Unit) {
        postDispatchActions += action
    }

    @Volatile
    private var subscription: Job? = null

    /**
     * 开始订阅总线并分发事件。
     *
     * 幂等：重复调用只生效一次。生产路径由 `EventModule` 在应用启动时调用一次。
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        subscription =
            scope.launch {
                eventBus
                    .events()
                    .catch { error ->
                        // 总线异常不得让调度器静默死掉：记日志后结束本次订阅。
                        onWarning("event bus failed: ${error.message ?: error::class.java.name}")
                    }.collect { event -> dispatch(event) }
            }
    }

    /** 停止订阅（测试与将来"安全模式"暂停分发时使用）。 */
    fun stop() {
        subscription?.cancel()
        subscription = null
        running.set(false)
    }

    override suspend fun dispatch(
        event: SystemEvent,
        payloadOverride: String?,
    ) {
        // ① 安全模式：**整个事件丢弃**（需求 §5.4「事件监听器注册但不分发」）。
        //    放在查 Room **之前**——安全模式下每个事件都走这条路，提前返回省掉一次
        //    查询与逐条投递的日志噪声，也让真机判读时 SAFEMODE_DROPPED 一定出现在
        //    任何 trigger lookup 之前（顺序本身是可判读的证据）。
        //
        //    `circuitBreaker` 可为空：单测与"不关心熔断的装配"无需构造它。
        val breaker = circuitBreaker
        if (breaker != null && !SafeModeDecision.shouldDispatchEvents(breaker.safeMode.value)) {
            onWarning(
                "SAFEMODE_DROPPED event=${event.eventId} reason=${breaker.tripReason.value?.reasonKey ?: "unknown"} " +
                    "(listeners stay registered; dispatch resumes after restore)",
            )
            return
        }

        val triggers =
            try {
                triggerRepository.forEvent(event.eventId)
            } catch (error: Throwable) {
                // 需求 §2.2 的"逐条投递"前提是能拿到列表；拿不到就记日志返回，
                // 不让异常穿透到事件源（那会炸掉广播接收器）。
                onWarning(
                    "trigger lookup failed for ${event.eventId}: " +
                        (error.message ?: error::class.java.name),
                )
                return
            }

        // ★ 零订阅时必须留痕（阶段 6b 真机缺陷的直接产物）。
        //
        // 为什么仍然需要这一行：循环体为空 ⇒ **整条 dispatch 路径一行日志都不打**。
        // 真机现场是"事件到了、fixture 说齐全、什么都跑"，两个日志之间没有任何可判读的
        // 中间态（本仓库禁止的静默失败形态）。
        //
        // ## 阶段 10（总开关重构）后这条告警的含义**变窄了**
        // 旧实现的 SQL 带 `AND enabled = 1`，因此"行存在但被禁用"与"行根本不存在"
        // **都表现为零条列表** —— 那条歧义是当时最难判读的一点。
        // 新模型**没有 `enabled` 列**（存在即订阅），歧义**从根上消失**：
        // 零条就是零条，不再有两种成因。因此下面的文案也去掉了
        // "it may be missing OR disabled" 的第二种可能。
        //
        // 为什么走 `onWarning` 而**不是** `Log.i`（§5.10）：
        // 本类在 `data/`，但 `TriggerDispatcherImplTest` 的 18 个用例直接调 `dispatch()`
        // 且只注入 `onWarning` 缝（`onWarning = warnings::add`）—— 单测里
        // `android.util.Log` **未 stub**，直接写 `Log.i` 会让 18 个用例全部抛
        // `Method i in android.util.Log not mocked`。这不是风格问题，是会不会打红既有测试的问题。
        //
        // 只在 `isEmpty()` 时打：热路径零噪声（正常情况每次事件都有一条触发器）。
        //
        // ★ 阶段 6e 真机验证（项 1）抓到：这段文案当时还在指引
        // `setprop debug.rf.force_enable_triggers 1` 与 `RootFlowApp` 的 TRIGGER_LOOKUP 诊断
        // —— **两者都已在 6e 删除**。一条把排障者引向不存在开关的告警比没有告警更坏，
        // 因此改成指引**用户实际有的东西**：编辑器的触发器区 + 列表页的计数。
        //
        // 为什么不在这里补回"该事件有几行"的计数：本类的 `forEvent` 是调度热路径，
        // 为一个告警再查一次全表会把每次分发都变成两次查询。三数字对照已由
        // `debug.rf.diagnostics` 闸门内的 `verifyEventSources` 覆盖（真机诊断时才付这个代价）。
        if (triggers.isEmpty()) {
            onWarning(
                "TRIGGER_LOOKUP event=${event.eventId} rowsForEvent=0 " +
                    "(no subscription for this event; check the event chips in the script editor)",
            )
        }

        // 阶段 3d：分发后动作（闹钟重同步）。放在查表之后、逐条投递之前——
        // 它的语义是"触发器可能刚被改动，闹钟该对账了"，与本次投递成败无关。
        // 任何动作抛错都不得影响投递主链路（否则一个坏接线点会让所有脚本停摆）。
        postDispatchActions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                onWarning(
                    "post-dispatch action failed for ${event.eventId}: " +
                        (error.message ?: error::class.java.name),
                )
            }
        }

        val eventPayload = payloadOverride ?: event.payloadJson()
        val now = clock()

        triggers.forEach { trigger ->
            val key = DispatchKey(trigger.scriptId, event.eventId)

            val previous = lastDispatchAt[key]
            if (previous != null && now - previous < debounceWindowMillis) {
                onWarning(
                    "debounced: script ${trigger.scriptId} event=${event.eventId} " +
                        "already dispatched ${now - previous}ms ago " +
                        "(window=${debounceWindowMillis}ms)",
                )
                return@forEach
            }

            // 负载优先级（阶段 3d 修正）：显式覆盖 > `TriggerRow.params.payload` > 事件自带。
            //
            // 需求 §2.3 把 `params` 定义为触发器的参数载体，`payload` 就在其中
            // （`TriggerParams.payload` 的 KDoc 写明"阶段 3d 使用"）。3b 的实现只用了
            // 事件自带的 `event.payloadJson()`，而绝大多数事件（`time` / `interval` /
            // `screen_*` …）恒为 `null` ⇒ **触发器配的负载永远到不了脚本**
            // （3d 端到端用例实测发现）。
            val payload = eventPayload ?: trigger.params.payload

            // ★ P5：投递的**行格式**定死在这里（脚本侧按它解析）：
            // ```
            // <eventId>                 ← 无负载时
            // <eventId> <payload>       ← 有负载时（payload 可含空格）
            // ```
            // 这样 `read -r ev payload` 一行就能同时拿到两者，而 payload 里的空格
            // 不会破坏解析（`read` 把**剩余整行**交给第二个变量）。编辑器里的
            // `EVENTS_READ_HINT` 文案与它一致。
            val line = if (payload == null) event.eventId else "${event.eventId} $payload"

            val delivery =
                try {
                    eventChannel.deliver(scriptId = trigger.scriptId, line = line)
                } catch (error: Throwable) {
                    // 通道实现不该抛（它自己收口成 `Failed`），这里是最后一道防线：
                    // 一条投递炸掉不该让整批事件丢失（与旧版"逐条投递"同一条纪律）。
                    EventDelivery.Failed("delivery threw: ${error.message ?: error::class.java.name}")
                }

            when (delivery) {
                EventDelivery.Delivered -> {
                    onWarning("EVENT_DELIVERED script=${trigger.scriptId} event=${event.eventId} line=$line")
                    lastDispatchAt[key] = now
                }

                // ★ P5 之后**最常见**的一种结局：那个脚本此刻没在运行（单次脚本、
                //   正在退避等待重启的常驻脚本、通道开不出来）。它必须留痕 ——
                //   否则用户看到的是"事件明明发生了，什么也没发生"（本仓库禁止的静默失败）。
                EventDelivery.NotRunning -> {
                    onWarning(
                        "EVENT_NOT_RUNNING script=${trigger.scriptId} event=${event.eventId} " +
                            "(the script is not running; events are delivered only to running scripts)",
                    )
                    lastDispatchAt[key] = now
                    // ★ P8：**只有 boot** 有"收件人正在启动"这个结构性时间差，给它一次有界重试。
                    //   其它事件此时没在跑就是真的没在跑（用户没开它 / 它已经退出了）。
                    if (event.eventId == SystemEvent.BOOT) {
                        scheduleBootRetry(scriptId = trigger.scriptId, line = line)
                    }
                }

                // 通道在、只是此刻没有进程在读它（脚本刚好在两次 `read` 之间）。
                // 与 NotRunning 分开报：判读方向不同（"通知到了门口没人开门" vs "没有门"）。
                EventDelivery.NoReader -> {
                    onWarning(
                        "EVENT_NO_READER script=${trigger.scriptId} event=${event.eventId} " +
                            "(channel exists but nobody is reading it right now)",
                    )
                    lastDispatchAt[key] = now
                }

                is EventDelivery.Failed -> {
                    onWarning(
                        "EVENT_DELIVER_FAILED script=${trigger.scriptId} event=${event.eventId}: " +
                            delivery.reason,
                    )
                }
            }
        }
    }

    /**
     * boot 投不出去时的**有界重试**（P8）。
     *
     * ## 为什么只有 boot 有这个待遇
     * boot 的收件人有一个**结构性的时间差**：事件在 `Application.onCreate` 的启动链里发出
     * （纯内存操作，几十毫秒），而要收它的常驻脚本得等前台服务注册 → `DaemonSupervisor`
     * 读 Room → 起一个 root 进程 —— 差着几百毫秒到几秒。
     *
     * 真机实测（2026-09-24 20:04:31，同一次启动）：
     * ```
     * 20:04:31.279  启动链 ② 补发 boot（本次不是重启后首启，skip）
     * 20:04:31.552  FGS_REGISTER begin              ← 晚 273ms
     *               （常驻脚本的 DAEMON_STARTED 还要更晚：读 Room + 起 root 进程）
     * ```
     * ⇒ 没有本方法时，`boot` **必然**撞上 `EVENT_NOT_RUNNING` ——
     * 也就是"开机时做点什么"这条用法根本不成立。
     *
     * 别的事件没有这个问题：它们都发生在 App 已经跑起来之后。
     *
     * ## 为什么是"有界"
     * 无界重试会同时踩 `AGENT_PROTOCOL §9.4`（虚拟时间下 `advanceUntilIdle()` 活锁）
     * 与"事件是**通知**、不是**队列**"的语义。超窗口就如实记 `EVENT_BOOT_GIVEN_UP`，不静默。
     *
     * ## 为什么跑在独立协程
     * [dispatch] 此刻正在遍历一批订阅；在这里 `delay` 会把整批投递一起卡住。
     */
    private fun scheduleBootRetry(
        scriptId: Long,
        line: String,
    ) {
        scope.launch {
            repeat(bootRetryAttempts) { attempt ->
                delay(bootRetryIntervalMillis)
                val next =
                    try {
                        eventChannel.deliver(scriptId = scriptId, line = line)
                    } catch (error: Throwable) {
                        EventDelivery.Failed(error.message ?: error::class.java.name)
                    }
                when (next) {
                    EventDelivery.Delivered -> {
                        onWarning(
                            "EVENT_BOOT_DELIVERED script=$scriptId attempt=${attempt + 1} line=$line",
                        )
                        return@launch
                    }

                    // 还没起来（NotRunning）/ 起来了但此刻没在读（NoReader）：都再等一轮
                    EventDelivery.NotRunning, EventDelivery.NoReader -> Unit

                    is EventDelivery.Failed -> {
                        onWarning("EVENT_BOOT_RETRY_ABORTED script=$scriptId reason=${next.reason}")
                        return@launch
                    }
                }
            }
            onWarning(
                "EVENT_BOOT_GIVEN_UP script=$scriptId attempts=$bootRetryAttempts " +
                    "(the script never came up; boot is a notification, not a queue)",
            )
        }
    }

    /** 防抖表的键：同一脚本的同一事件。 */
    private data class DispatchKey(
        val scriptId: Long,
        val eventId: String,
    )

    companion object {
        /** 需求 §2.2：`debounceWindow` 默认 500ms。 */
        const val DEFAULT_DEBOUNCE_WINDOW_MILLIS: Long = 500L

        /**
         * boot 重试的**默认**次数与间隔（P8）：12 × 500ms = **6 秒**窗口。
         *
         * 取值理由：常驻脚本从"服务注册"到"进程真的在读 FIFO"包含读 Room + 起 root 进程，
         * 真机上是亚秒级到数秒级 —— 6 秒覆盖它；又不至于让"总闸关着""脚本没设成常驻"的用户
         * 白等太久（那两种情况下重试必然耗尽，日志会给 `EVENT_BOOT_GIVEN_UP`，同样可判读）。
         */
        const val DEFAULT_BOOT_RETRY_ATTEMPTS: Int = 12

        /** boot 重试间隔（毫秒）；与 [DEFAULT_BOOT_RETRY_ATTEMPTS] 共同决定上界。 */
        const val DEFAULT_BOOT_RETRY_INTERVAL_MILLIS: Long = 500L
    }
}
