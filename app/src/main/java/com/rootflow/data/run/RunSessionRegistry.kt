package com.rootflow.data.run

import com.rootflow.domain.run.RunActivityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "当前在跑的运行"注册表（阶段 4，需求 §5.2 第 2 步的数据来源）。
 *
 * ## 为什么独立于 `RunAdmissionGate`（**已批准的修正**）
 * `RunAdmissionGate` 的职责是**准入决策**（重入 / 全局上限），它登记的是
 * `scriptId` 集合。而熔断要终止的是**具体运行**（按 `runId` 找 PGID），
 * 且必须覆盖**所有**已启动的运行——包括**跳过闸门**的那些
 * （安全模式下 `runOnSafeMode=true` 的放行，见 `SafeModeDecision`）。
 *
 * 若把 runId 塞进闸门，那些"跳过闸门"的运行就会**漏出集合** ⇒ 熔断时漏 kill
 * ⇒ 正是熔断要防的"脚本还在跑"。因此拆成独立注册表：
 * **只要运行被受理，就无条件登记**（与是否走闸门无关）。
 *
 * ## 与 `RunSession.job` 的配合
 * - 登记：`TriggeredScriptRunner.start` 拿到 `RunSession` 后**立即**注册
 * - 注销：挂在 `RunSession.job.invokeOnCompletion`（正常结束 / 异常 / 取消三条路径都触发）
 *
 * ## 线程安全
 * `register` / `unregister` 来自协程（可能不同线程），`activeRuns()` 来自熔断动作的
 * 编排协程。用 `synchronized`（与 `RunAdmissionGateImpl` 同款理由：**释放不能是可取消的操作**，
 * 而 `invokeOnCompletion` 回调本身就是同步的）。
 *
 * ## 阶段 6b：本类同时实现 [RunActivityProvider]
 * 主页的深色终端要订阅 `LogPipeline.observe(runId)`，而 `runId` 只有本类掌握。
 * 新增的 [latestRunId] 是**镜像字段**（`_runs` 是真相），因此：
 * - 熔断第 2 步（按 `runId` 终止每一个在跑的运行）的语义**一字未改**
 * - `activeRuns()` / `activeCount` 的既有调用方**零改动**
 *
 * 它的**单调语义**（运行结束不清空）与理由见 [RunActivityProvider.latestRunId] 的 KDoc
 * —— 那是已批准的 6b 决策，**不是遗漏**。
 */
@Singleton
class RunSessionRegistry
    @Inject
    constructor() : RunActivityProvider {
        /** `runId` → `scriptId`。由 [lock] 保护。 */
        private val runs = mutableMapOf<String, Long>()

        /**
         * `runId` → **登记时刻**（单调纳秒）。
         *
         * ## 为什么时长必须由这里量（P9 修掉的真缺陷）
         * `DaemonSupervisorImpl` 曾靠**轮询注册表**测"这一轮活了多久"：
         * 先等脚本"出现"，再等它"消失"，然后用两次 `elapsedRealtime` 相减。
         * 那有三个无法同时消除的误差：
         * 1. **量化**：`delay(POLL_INTERVAL)` 最多多算一个轮询周期
         * 2. **相位**：起点是"探测到它出现"，而它实际开始得更早（最多早一个周期）
         * 3. **看不见就整个丢失**：`echo` 型脚本活 200ms，若在两次轮询之间起止，
         *    **一次都没被观测到** ⇒ 走到 `APPEAR_TIMEOUT` ⇒ 报 `0ms` ⇒
         *    被 `DaemonRestartPolicy.isStable` 判成"快速崩"（真机日志：
         *    `DAEMON_APPEAR_TIMEOUT waited=3000ms`）
         *
         * 这三条都不是"调小轮询间隔"能解决的（间隔越小，开销越大，而窗口永远存在）。
         * **时长本来就不该被"观测"，它就是两个时刻的差** —— 而这两个时刻，
         * 本类在 `register` / `unregister` 里**天然就有**。
         */
        private val startedAtNanos = mutableMapOf<String, Long>()

        /**
         * `scriptId` → **最近一次**运行的真实存活时长（纳秒）。
         *
         * 保留"最近一次"而不是随 `unregister` 丢掉：读取方（监管循环）与写入方
         * （完成的运行）不共用一个协程 —— 它需要在"探测到脚本不再活跃"之后才来取，
         * 而那时 `unregister` 早已执行完（两者读的是同一把锁，因此**不存在竞态**）。
         */
        private val lastDurationNanos = mutableMapOf<Long, Long>()

        private val lock = Any()

        /**
         * **单调**时钟（纳秒）；测试缝：构造后由单测替换为虚拟时间。
         *
         * ## 为什么必须是可替换的（P9 的一个可测性要求）
         * 存活时长是"两个时刻的差"，而单测里的 `delay()` 走的是**虚拟时间** ——
         * 若这里硬读 `System.nanoTime()`，测试推 50ms 虚拟时间后量到的仍是
         * "两次调用之间真实经过的几微秒" ⇒ **修复测不出来**（断言会永远是 0）。
         *
         * 用 `var` 而不是构造参数注入：本类是 `@Inject` 无参构造，
         * 而 `() -> Long` 无法被 Dagger 绑定（同 `DaemonSupervisorImpl.elapsedRealtimeMillis`、
         * `BootloopGuard` 的处理方式与理由）。
         */
        internal var monotonicNanos: () -> Long = System::nanoTime

        /**
         * 最近一次被受理的运行标识（**单调**，见端口的 KDoc）。
         *
         * 用 `MutableStateFlow` 而不是普通 `var`：UI 要订阅它。
         * `StateFlow` 自带"同值不重复发射"与"订阅者立即拿到当前值"两条语义，
         * 恰好是终端所需的（新订阅者不该错过已经开始的那次运行）。
         */
        private val _latestRunId: MutableStateFlow<String?> = MutableStateFlow(null)

        override val latestRunId: StateFlow<String?> = _latestRunId.asStateFlow()

        /**
         * 登记一次运行（幂等：同 `runId` 重复登记覆盖同一键），并记下开始时刻。
         *
         * 开始时刻由本类自己取（`System.nanoTime()`：**单调**、纯 JVM 可用、
         * 不受系统时间调整影响）—— 调用方不必为此新增时钟依赖。
         */
        fun register(
            runId: String,
            scriptId: Long,
        ) {
            val now = monotonicNanos()
            synchronized(lock) {
                runs[runId] = scriptId
                startedAtNanos[runId] = now
            }
            // 放在锁外：StateFlow 的赋值是原子的，且订阅者回调可能重入本类
            // （当前没有这种订阅者，但不给未来留一个自锁陷阱）。
            _latestRunId.value = runId
        }

        /**
         * 注销（幂等：不存在的 `runId` 无副作用），并记下这次运行的**真实存活时长**。
         *
         * 时长在**这里**算，原因见 [startedAtNanos] 的 KDoc：这是唯一同时知道
         * "何时开始"与"何时结束"的地方，且不经过任何轮询。
         */
        fun unregister(runId: String) {
            val now = monotonicNanos()
            synchronized(lock) {
                val scriptId = runs.remove(runId)
                val started = startedAtNanos.remove(runId)
                if (scriptId != null && started != null) {
                    // `coerceAtLeast(0)`：单调时钟理论上不会回退，但"负的存活时长"
                    // 进了 `isStable` 会变成一个很难查的判据异常，这里直接收口。
                    lastDurationNanos[scriptId] = (now - started).coerceAtLeast(0L)
                }
            }
            // ★ 刻意**不**动 _latestRunId：单调语义，见 [RunActivityProvider.latestRunId]。
            //   在这里清空会让主页终端在运行收尾的瞬间整屏变空（管道同时回收了 state）。
        }

        /**
         * 某个脚本**最近一次**运行的真实存活时长（毫秒）；从未运行过时 `null`。
         *
         * 供 `DaemonSupervisorImpl` 的监管循环使用：它拿到的值来自
         * `register`/`unregister` 两个真实时刻，**不含轮询相位误差**，
         * 也不会因为"脚本太短命、没被轮询看见"而丢掉整次运行。
         */
        fun lastDurationMillis(scriptId: Long): Long? =
            synchronized(lock) { lastDurationNanos[scriptId]?.let { it / NANOS_PER_MILLI } }

        /** 当前在跑的 `(runId, scriptId)` 快照（熔断时逐条终止用；顺序不保证）。 */
        fun activeRuns(): Set<Pair<String, Long>> = synchronized(lock) { runs.map { it.key to it.value }.toSet() }

        /** 当前在跑数（真机判读 + 单测断言用）。 */
        val activeCount: Int
            get() = synchronized(lock) { runs.size }

        private companion object {
            /** 纳秒 → 毫秒（`System.nanoTime()` 的单位是纳秒）。 */
            const val NANOS_PER_MILLI: Long = 1_000_000L
        }
    }
