package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.fs.RootFileResult
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.data.run.RunOutcomeSink
import com.rootflow.data.run.RunSessionRegistry
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.CircuitBreakerMachine
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.SafeModeNotifier
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.runtime.ProcessGroupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全熔断的**编排实现**（阶段 4，需求 §5.2 的六步动作 + §5.3 的恢复）。
 *
 * ## 职责边界
 * | 组件 | 负责 |
 * |---|---|
 * | [CircuitBreakerMachine] | **判定**（五条自动条件的阈值与窗口，纯逻辑） |
 * | 本类 | **动作**（写 flag / 杀进程 / 禁触发器 / 通知 / 写日志 / 恢复） |
 * | [BootloopGuard] | 需求 §5.3 第 4 条的独立判定源（崩溃计数） |
 * | [SafeModeNotifier] | 对外通知（阶段 4 空实现，阶段 5 接前台服务与通知） |
 *
 * ## 六步动作的**顺序是硬要求**（需求 §5.2 明写"按顺序"）
 * ```
 * ① 写 safemode.flag        ← 先持久化状态：即使后续步骤崩了，重启后也知道自己该在安全模式
 * ② 终止所有运行中脚本      ← 阻止进一步破坏
 * ③ 禁用所有触发器（记原始状态）  ← 阻止再触发
 * ④ 前台服务进入安全模式    ← 阶段 4 只经 notifier 记日志
 * ⑤ 高优先级通知            ← 阶段 5
 * ⑥ 写 safemode.log         ← 留痕（放最后：它的内容包含前面几步的结果）
 * ```
 * 每步**独立 `runCatching`**：一步失败不得让后面的步骤不执行
 * （否则会出现"有 flag 但脚本还在跑"或"脚本杀光了但触发器还开着"的**最坏中间态**）。
 *
 * ## 幂等
 * `safeMode == true` 时重复 [tripInternal] **只记日志**，不重复执行六步
 * （重复杀进程无害，但重复写 `safemode.state` 会把"原始状态"覆盖成"全禁用后的状态"，
 * 于是恢复时**永远还原不回去**——这是必须防的真实缺陷）。
 *
 * ## 状态来源的优先级
 * `safeMode` 初值 = `safemode.flag` 文件是否存在（跨进程恢复的关键）；
 * 外部安全模式（`persist.sys.safemode` / Magisk）经 [probeExternalSafeMode] 进入内存态。
 *
 * ## 它同时是 `RunOutcomeSink`（阶段 4 接线）
 * `ScriptRunCoordinator` 不知道熔断器的存在，它只把"这次运行结束了、退出码是 N"报给
 * `RunOutcomeSink`。本类**直接实现该接口**并交给协调者，从而：
 * - 少一层适配器（否则要再写一个只有转发的类）
 * - `onRunTimeout` 的语义与 `RunOutcomeSink` 的 KDoc **逐字对应**（超时既报 timeout 也报 outcome）
 *
 * ## 为什么不再有 `scope` 形参（阶段 4 收尾）
 * 熔断的六步是**同步止血**：必须立刻落 flag、立刻杀进程。把它异步化会让
 * `SAFEMODE_TRIP_DONE`（含 `terminated` / `triggersDisabled` 计数）与真机日志失去因果关系，
 * 也会破坏"每步独立 `runCatching`、一步失败不影响后续"的结构。因此本类**不需要**作用域，
 * 原先未使用的 `scope` 形参已移除（它还会让 ktlint 报未使用）。
 *
 * ## 为什么不再有 `triggerRepository`（P6 收尾）
 * 它在**这个类里从未被读过一次**：六步动作的每一步都走 [fileStore] / [sessionRegistry] /
 * [processGroupManager] / [notifier] / [runHistoryRepository]。
 * 第 ③ 步 `disableAllTriggers()` 曾经要写库，但熔断改成**内存级拦截**之后它已是空实现
 * （见该方法的 KDoc：熔断不再改动用户的订阅数据）⇒ 这个形参退化成死依赖。
 * 与上面移除 `scope` 同款处理：留着会让"熔断器需要触发器表"这个印象以讹传讹，
 * 也让 Dagger 白白构造一个没人用的对象。
 */
@Singleton
class CircuitBreakerImpl
    @Inject
    constructor(
        private val fileStore: RootFileStore,
        private val sessionRegistry: RunSessionRegistry,
        private val processGroupManager: ProcessGroupManager,
        private val notifier: SafeModeNotifier,
        private val rootHealthProbe: RootHealthProbe,
        private val runHistoryRepository: RunHistoryRepository,
    ) : CircuitBreaker,
        RunOutcomeSink {
        /** 测试缝：注入自定义阈值的判定机（生产用需求 §5.1 的默认阈值）。 */
        internal constructor(
            fileStore: RootFileStore,
            sessionRegistry: RunSessionRegistry,
            processGroupManager: ProcessGroupManager,
            notifier: SafeModeNotifier,
            rootHealthProbe: RootHealthProbe,
            runHistoryRepository: RunHistoryRepository,
            machine: CircuitBreakerMachine,
            wallClock: () -> Long,
            monotonicClock: () -> Long,
        ) : this(
            fileStore = fileStore,
            sessionRegistry = sessionRegistry,
            processGroupManager = processGroupManager,
            notifier = notifier,
            rootHealthProbe = rootHealthProbe,
            runHistoryRepository = runHistoryRepository,
        ) {
            this.machine = machine
            this.wallClock = wallClock
            this.monotonicClock = monotonicClock
        }

        private var machine: CircuitBreakerMachine = CircuitBreakerMachine()

        /**
         * **墙钟**（`RunSummary.startedAt` 的时基）。
         *
         * 单独抽成属性而不是直接调 `System.currentTimeMillis()`：D11 的时基换算
         * 需要**两个时钟的读数**，而纯 JVM 单测里 `SystemClock` 未实现（"not mocked"），
         * 且 `mockkStatic(System::class)` 会污染 JVM 全局 —— 因此两种时钟都必须可注入。
         */
        private var wallClock: () -> Long = System::currentTimeMillis

        /** **单调时钟**（判定机所有窗口的时基，`SystemClock.elapsedRealtime`）。 */
        private var monotonicClock: () -> Long = android.os.SystemClock::elapsedRealtime

        private val _safeMode = MutableStateFlow(false)
        private val _tripReason = MutableStateFlow<TripReason?>(null)

        override val safeMode: StateFlow<Boolean> = _safeMode.asStateFlow()

        override val tripReason: StateFlow<TripReason?> = _tripReason.asStateFlow()

        /** Bootloop 兜底命中时由 `RootFlowApp` 接线（避免本类直接依赖 `BootloopGuard` 形成环）。 */
        private var bootloopGuard: BootloopGuard? = null

        /** 接线 [BootloopGuard]（在其 `evaluateStartup` 之前调用一次）。 */
        fun attachBootloopGuard(guard: BootloopGuard) {
            bootloopGuard = guard
        }

        /** 供 `RootFlowApp` 判断"启动时是否已处于安全模式"（读 flag 的结果）。 */
        val isSafeModeActive: Boolean
            get() = _safeMode.value

        /**
         * 某脚本当前的连续失败次数（**真机判读 + 单测断言用**）。
         *
         * 暴露它的理由：判定机的计数器是私有的，而"连续失败"是需求 §5.1 第 2 条的
         * **直接判据**。没有这个读口，真机上只能靠"熔断了没有"间接推断，
         * 而"记了 2 次但没熔断"与"一次都没记"在日志上无法区分。
         */
        fun failureCountOf(scriptId: Long): Int = machine.consecutiveFailuresOf(scriptId)

        /** 当前失败风暴窗口内的失败数（真机判读 + 单测断言用，理由同上）。 */
        fun failuresInWindow(): Int = machine.failuresInWindow()

        /**
         * 启动时**一次性**从磁盘恢复安全模式状态。
         *
         * 必须在任何脚本可跑之前调用——进程重启后 `_safeMode` 是 `false`，
         * 若不恢复，重启就成了"绕过熔断"的手段。
         *
         * @return `true` 表示当前处于安全模式
         */
        suspend fun restoreStateFromDisk(): Boolean {
            val flagExists = fileStore.exists(RootFlowPaths.SAFE_MODE_FLAG)
            if (flagExists) {
                _safeMode.value = true
                // ★ 把成因也从 flag 里读回来（阶段 4 修正）。
                // flag 的内容是熔断时写的三行：说明 / `reason=<key>` / `detail=<text>`。
                // 不读回来就等于**每次重启都丢失熔断原因**：真机判读与阶段 6 的 banner
                // 只能看到"在安全模式"，却不知道是超时、连续失败还是手动触发的——
                // 而恢复时的排查方向完全依赖它。
                _tripReason.value = readTripReasonFromFlag()
                Log.w(
                    TAG,
                    "SAFEMODE_RESTORED_FROM_FLAG path=${RootFlowPaths.SAFE_MODE_FLAG} " +
                        "reason=${_tripReason.value?.reasonKey ?: "<unrecoverable>"}",
                )
            }
            return flagExists
        }

        /**
         * 从 `safemode.flag` 解析熔断成因。
         *
         * **解析失败不是错误**：flag 的契约是"**存在即安全模式**"（内容只是给人看的），
         * 因此文件被手工编辑、被截断、或成因本身带参数（无法从一行文本复原）时，
         * 都返回 `null` 并继续——**绝不因为读不懂内容而否定"处于安全模式"这件事**。
         */
        private suspend fun readTripReasonFromFlag(): TripReason? =
            when (val result = fileStore.readText(RootFlowPaths.SAFE_MODE_FLAG)) {
                is RootFileResult.Ok -> {
                    val lines = result.value.lineSequence().toList()
                    val key = lines.firstOrNull { it.startsWith(REASON_PREFIX) }?.removePrefix(REASON_PREFIX)?.trim()
                    val detail =
                        lines
                            .firstOrNull { it.startsWith(DETAIL_PREFIX) }
                            ?.removePrefix(DETAIL_PREFIX)
                            ?.trim()
                    if (key.isNullOrEmpty()) {
                        null
                    } else {
                        TripReason.fromKey(key = key, detail = detail).also { restored ->
                            if (restored == null) {
                                Log.i(
                                    TAG,
                                    "SAFEMODE_TRIP_REASON key=$key not recoverable from disk " +
                                        "(parameterised reason); safe mode still active",
                                )
                            }
                        }
                    }
                }

                // 下面三种都只记录，不改变"处于安全模式"这一结论
                RootFileResult.Missing -> null

                is RootFileResult.Corrupted,
                is RootFileResult.Unavailable,
                -> {
                    Log.w(TAG, "SAFEMODE_TRIP_REASON unreadable ($result); safe mode still active")
                    null
                }
            }

        // ------------------------------------------------------------------ 触发条件

        override suspend fun onRunAccepted(scriptId: Long) {
            machine.onRunAccepted(scriptId, now())?.let { reason -> tripInternal(reason) }
        }

        /**
         * 启动时用运行历史**预置**失败风暴窗口（跨进程恢复，决策 **D11**）。
         *
         * ## 为什么必须有它
         * `CircuitBreakerMachine` 的风暴窗口是**内存态**，进程重启即归零 ⇒
         * "反复崩、每次崩前崩若干次"的循环**永远到不了 20 次**，熔断被绕过。
         * 这在真机验证 §7 第 8 项（**失败风暴需跨进程**）上会直接表现为"验不出来"。
         *
         * ## 时基换算（本方法唯一的难点）
         * `RunSummary.startedAt` 是**墙钟**（`System.currentTimeMillis`），而判定机全部用
         * **单调时钟**（`SystemClock.elapsedRealtime`）。两者的差在一次开机周期内是常数：
         * ```
         * offset        = 墙钟 - 单调              （每次调用算一次；跨重启自动正确）
         * 单调时刻       = 墙钟 - offset
         * 窗口下界(墙钟) = 窗口下界(单调) + offset
         * ```
         * 因此这里**先按墙钟过滤、再统一换算**，绝不把两个时基的读数放进同一个比较。
         *
         * ## 只灌"失败"的运行
         * `exitCode != 0` 才算失败；`exitCode == null`（被取消 / 无退出行）**不计**——
         * 与 [onRunOutcome] 同一取舍（漏熔断 < 误熔断）。
         *
         * ## 调用时机
         * 必须在**任何判定之前**（判定机 KDoc 明写）。生产路径在 `RootFlowApp.onCreate` 里
         * `restoreStateFromDisk()` 之后、事件源启动之前调用一次。
         */
        suspend fun seedFailureHistoryFromHistory() {
            val startedWallClock = wallClock()
            val nowMillis = monotonicClock()
            val offset = startedWallClock - nowMillis
            val windowStartWallClock = nowMillis - STORM_WINDOW_MILLIS + offset

            val timestamps =
                runCatching {
                    runHistoryRepository
                        .recent(HISTORY_SEED_LIMIT)
                        .filter { summary -> summary.startedAt >= windowStartWallClock }
                        .filter { summary -> summary.exitCode != null && summary.exitCode != 0 }
                        .map { summary -> summary.startedAt - offset }
                }.getOrElse { error ->
                    // 读历史失败不得阻止启动：最坏结果是"本次开机拿不到跨进程失败历史"，
                    // 即**退化**到修复前的行为，不是新缺陷。
                    Log.w(
                        TAG,
                        "SAFEMODE_SEED_SKIPPED history read failed: ${error.message ?: error::class.java.name}",
                    )
                    emptyList()
                }

            machine.seedFailureHistory(timestamps, nowMillis)
            Log.i(
                TAG,
                "SAFEMODE_SEED_FAILURES seeded=${timestamps.size} windowMillis=$STORM_WINDOW_MILLIS " +
                    "asked=$HISTORY_SEED_LIMIT offsetMillis=$offset",
            )
        }

        override suspend fun onRunTimeout(
            scriptId: Long,
            timeoutMillis: Long,
        ) {
            // 超时本身按"一次失败"计入计数（需求 §5.1 第 1 条与第 2 条是叠加关系），
            // 同时**直接**触发熔断——超时说明脚本已失控，不必再等连续 3 次
            tripInternal(machine.onRunTimeout(scriptId, timeoutMillis))
        }

        // ------------------------------------------------------ RunOutcomeSink（阶段 4 接线）

        /**
         * 一次运行结束（由 `ScriptRunCoordinator` 在作业收尾时回调）。
         *
         * ## 失败判定**只用内存里的 `exitCode`**，不查 Room（已批准的修正）
         * 早先的设计是"查 `RunHistoryRepository.find(runId).exitCode`"，但那有**已知竞态**：
         * `RunHistoryCollector` 是"有界队列 + 独立消费者"，`enqueue` 只 `trySend` 就返回，
         * 而本回调挂在 `job.invokeOnCompletion` 上 —— **早于消费者写库**，
         * 于是 `exitCode` 可能读到 `null`。
         *
         * 现在的取值路径**完全无竞态**：`ScriptRunCoordinator` 在收集 `runtime.LogLine` 时
         * 就地捕获退出码（那是 `ShellScriptRuntime` 解析 `__RF_EXIT__` 的**唯一**产物），
         * 随回调直接带过来，不经过任何异步写入。
         *
         * ## `exitCode == null` 只告警、**不计失败**（已批准的取舍）
         * 两条路径会给出 `null`：① 运行被 kill / 取消（`ShellScriptRuntime` 的 KDoc 明写
         * "收集被取消时退出行不会发射"）；② 脚本没跑到末尾。
         *
         * 把它们当失败会导致**误熔断**（正常脚本因竞态被判失败 → 连续 3 次 → 进安全模式），
         * 而误熔断比漏熔断更坏：用户会看到"App 打开后什么都不跑"却查不出原因。
         * 真正因超时失控的运行**另有** [onRunTimeout] → 直接熔断，不依赖本方法的兜底。
         */
        override suspend fun onRunOutcome(
            runId: String,
            scriptId: Long,
            exitCode: Int?,
        ) {
            applyRunOutcome(runId = runId, scriptId = scriptId, exitCode = exitCode)
        }

        /**
         * 运行结局的**唯一**处理实现（`RunOutcomeSink` 与 `CircuitBreaker.onRunFinished` 共用）。
         *
         * 两处入口收敛到这里，是为了不让"什么算失败"出现两套判定——
         * 那正是 `SafeModeDecision` 的 KDoc 反复警告的漂移形态。
         */
        private suspend fun applyRunOutcome(
            runId: String,
            scriptId: Long,
            exitCode: Int?,
        ) {
            if (exitCode == null) {
                Log.w(
                    TAG,
                    "RUN_OUTCOME_UNKNOWN runId=$runId script=$scriptId " +
                        "(no exit line captured — killed/cancelled run; not counted as a failure)",
                )
                return
            }

            val failed = exitCode != 0
            machine.onRunFinished(scriptId, failed = failed, now())?.let { reason -> tripInternal(reason) }

            // 运行结局是**唯一**的 root 健康探测点之一（另一个是启动探针）：
            // 刻意不加后台健康循环（见 RootHealthProbe 的 KDoc：§9.4 优先——
            // 虚拟时间下的周期性超时会让 `advanceUntilIdle` 永不返回）
            if (failed) {
                val snapshot = rootHealthProbe.probe()
                if (!snapshot.responsive) {
                    tripInternal(TripReason.RootUnresponsive(elapsedMillis = snapshot.elapsedMillis))
                }
            }
        }

        /**
         * [CircuitBreaker.onRunFinished] 的端口实现。
         *
         * 端口上的 `exitCode` 与 [RunOutcomeSink.onRunOutcome] 携带的是**同一个值**，
         * 只是入口不同：运行链走 `RunOutcomeSink`（带 `runId`，便于日志定位），
         * 端口留给"不经运行链的调用方"（阶段 6 的手动运行可直接调它）。
         * 两者收敛到 [applyRunOutcome]，避免出现两套"什么算失败"的判定。
         */
        override suspend fun onRunFinished(
            scriptId: Long,
            exitCode: Int?,
        ) {
            applyRunOutcome(runId = NO_RUN_ID, scriptId = scriptId, exitCode = exitCode)
        }

        override suspend fun onRootUnresponsive(elapsedMillis: Long) {
            tripInternal(machine.onRootUnresponsive(elapsedMillis))
        }

        override suspend fun tripManually() {
            tripInternal(machine.onManual())
        }

        override suspend fun tripForBootloop(crashes: Int) {
            tripInternal(TripReason.Bootloop(crashes = crashes))
        }

        // ------------------------------------------------------------------ 恢复

        override suspend fun restore(mode: RestoreMode) {
            if (!_safeMode.value) {
                Log.i(TAG, "SAFEMODE_RESTORE skipped: not in safe mode")
                return
            }

            Log.w(TAG, "SAFEMODE_RESTORE begin mode=$mode")

            // ① 触发器：按快照还原（或保持禁用）
            val restored =
                runCatching { restoreTriggers(mode) }
                    .onFailure { Log.w(TAG, "SAFEMODE_RESTORE trigger step failed: ${it.message}") }
                    .getOrDefault(0)

            // ② 清 flag 与 state（**先清 state 再清 flag**：若中途失败，
            //    留下"flag 在但 state 没了"比"state 在但 flag 没了"更安全——
            //    前者下次恢复退化为 KeepDisabled，后者会以为已恢复却仍在禁触发器）
            runCatching { fileStore.deleteFile(RootFlowPaths.SAFE_MODE_STATE) }
            runCatching { fileStore.deleteFile(RootFlowPaths.SAFE_MODE_FLAG) }

            // ③ 内存态与计数器
            machine.reset()
            // 恢复路径把 bootloop 的两个字段**都**清零：
            // 计数（`resetCrashCount` 的语义）+ 启动标记（`clear`）。
            // 注意：`BootloopGuard.evaluateStartup` 现在会在**熔断那一刻**就自行清零计数
            // （阶段 5 修复），因此这里的 reset 是**双保险**——它仍然必需，
            // 因为用户可能是在"计数已累到 3 但尚未启动"的窗口里手动恢复的。
            bootloopGuard?.reset()
            _safeMode.value = false
            _tripReason.value = null
            notifier.onRestore()

            runCatching {
                fileStore.appendLine(
                    RootFlowPaths.SAFE_MODE_LOG,
                    "restore mode=$mode triggersRestored=$restored at=${System.currentTimeMillis()}",
                )
            }

            Log.i(TAG, "SAFEMODE_RESTORE done mode=$mode triggersRestored=$restored")
        }

        override suspend fun probeExternalSafeMode(): TripReason? {
            val snapshot = rootHealthProbe.detectExternalSafeMode()
            if (snapshot == null) {
                Log.i(TAG, "SAFEMODE_EXTERNAL_PROBE not detected")
                return null
            }
            // 只进内存态，**不写 flag**：那是熔断动作，不该由"检测到系统安全模式"触发
            _safeMode.value = true
            // ★ 已经在安全模式时**不覆盖成因**（阶段 4 修正）。
            // 场景：本应用自己熔断过（或从 flag 恢复）之后，外部安全模式恰好也命中。
            // 若此时用 ExternalSafeMode 覆盖成因，真机判读与阶段 6 的 banner 都会
            // 把"这次是本应用熔断的"误报成"跟随了外部状态"——两者的恢复路径不同，
            // 混淆会让用户按错误的方式排查。因此成因遵循"**先到者为准**"。
            if (_tripReason.value == null) {
                _tripReason.value = snapshot
            } else {
                Log.i(
                    TAG,
                    "SAFEMODE_EXTERNAL_PROBE detected key=${snapshot.reasonKey} but safe mode was " +
                        "already active with reason=${_tripReason.value?.reasonKey} (reason kept as-is)",
                )
            }
            Log.w(TAG, "SAFEMODE_EXTERNAL_PROBE detected key=${snapshot.reasonKey} detail=${snapshot.detail}")
            return snapshot
        }

        // ------------------------------------------------------------------ 六步动作

        /**
         * 执行熔断（幂等）。这是**唯一**写 [TripReason.Bootloop] 等状态的入口。
         */
        private suspend fun tripInternal(reason: TripReason) {
            if (_safeMode.value) {
                Log.i(TAG, "SAFEMODE_TRIP ignored (already in safe mode) reason=${reason.reasonKey}")
                return
            }
            _safeMode.value = true
            _tripReason.value = reason
            Log.w(TAG, "SAFEMODE_TRIPPED reason=${reason.reasonKey} detail=${reason.detail}")

            // ① 写 safemode.flag（先持久化，见类 KDoc）
            runCatching {
                fileStore.writeText(
                    RootFlowPaths.SAFE_MODE_FLAG,
                    "rootflow safemode (exists = safe mode)\nreason=${reason.reasonKey}\ndetail=${reason.detail}",
                )
            }.onFailure { Log.w(TAG, "SAFEMODE_TRIP step1 (flag) failed: ${it.message}") }

            // ② 终止所有运行中脚本（按 runId 逐条，覆盖"跳过闸门"的运行）
            val terminated =
                runCatching { terminateAllRunning() }
                    .onFailure { Log.w(TAG, "SAFEMODE_TRIP step2 (terminate) failed: ${it.message}") }
                    .getOrDefault(0)

            // ③ 禁用所有触发器（记原始状态）
            val disabled =
                runCatching { disableAllTriggers() }
                    .onFailure { Log.w(TAG, "SAFEMODE_TRIP step3 (disable triggers) failed: ${it.message}") }
                    .getOrDefault(0)

            // ④⑤ 通知/前台服务（阶段 4 = 空实现）
            runCatching { notifier.onTrip(reason) }
                .onFailure { Log.w(TAG, "SAFEMODE_TRIP step4/5 (notify) failed: ${it.message}") }

            // ⑥ 写 safemode.log（含前几步结果，故放最后）
            runCatching {
                fileStore.appendLine(
                    RootFlowPaths.SAFE_MODE_LOG,
                    "trip reason=${reason.reasonKey} detail=${reason.detail} " +
                        "terminated=$terminated triggersDisabled=$disabled at=${System.currentTimeMillis()}",
                )
            }.onFailure { Log.w(TAG, "SAFEMODE_TRIP step6 (log) failed: ${it.message}") }

            Log.w(
                TAG,
                "SAFEMODE_TRIP_DONE reason=${reason.reasonKey} terminated=$terminated triggersDisabled=$disabled",
            )
        }

        /**
         * 终止所有在跑的运行。
         *
         * 用 [RunSessionRegistry]（而非 `RunAdmissionGate`）是**已批准的修正**：
         * 闸门只登记走过准入的脚本，安全模式下被放行的 `runOnSafeMode` 脚本会漏掉。
         */
        private suspend fun terminateAllRunning(): Int {
            val active = sessionRegistry.activeRuns()
            if (active.isEmpty()) return 0
            Log.w(TAG, "SAFEMODE_TERMINATING count=${active.size} runs=${active.map { it.first }}")
            var terminated = 0
            active.forEach { (runId, _) ->
                runCatching {
                    processGroupManager.terminateRun(runId)
                    processGroupManager.cleanup(runId)
                }.onSuccess {
                    terminated++
                }.onFailure { Log.w(TAG, "SAFEMODE_TERMINATE failed run=$runId: ${it.message}") }
            }
            return terminated
        }

        /**
         * **熔断第 3 步：拦截事件分发（内存级，不碰数据库）**。
         *
         * ## ★ 语义变化（总开关重构，v1 → v2）
         * 旧实现在这里**把全部触发器写成 `enabled = false` 并落库**，恢复时再按
         * `safemode.state` 快照逐条还原。那套需要 `triggers.enabled` 列 —— 而新模型
         * **「存在即订阅」没有 `enabled`**，因此"禁用触发器"这件事在数据层**已不存在**。
         *
         * 新实现改成**内存级拦截**：
         * - 熔断时置 `_safeMode = true`，而 `SafeModeDecision.shouldDispatchEvents` 让
         *   `TriggerDispatcherImpl` **直接丢弃整个事件**（阶段 4 起就是这样，见其 KDoc）
         * - ⇒ 事件不再分发给任何脚本，**无需改动用户的订阅数据**
         *
         * ## 为什么这样更好（不只是"因为列没了"）
         * 1. **不再有"熔断改了用户数据"这件事**：旧实现会在库里留下 `enabled = 0`，
         *    若快照文件损坏/丢失，用户恢复后订阅仍是禁用的（[RestoreMode.KeepDisabled] 的保守方向）。
         *    现在熔断**完全不动用户数据** ⇒ 这类"恢复后数据仍被打坏"的形态从根上消失。
         * 2. **无 IO**：旧实现要写一个快照文件 + N 条 UPDATE。熔断是故障路径，
         *    此时做越多 IO 越可能自己失败（本文件后续就有 `runCatching` 兜它）。
         * 3. **常驻脚本另有闸门**：`DaemonSupervisor` 由 `ForegroundServiceController.onSafeModeAlert`
         *    直接停掉，与事件拦截是两条独立且都必要的路（前者停"已经在跑的"，后者挡"将要来的"）。
         *
         * ## `safemode.state` 快照文件：**不再写入**
         * 它记录的"触发器原始启用状态"已无意义（没有可被改动的启用状态）。
         * 该路径（`RootFlowPaths.SAFE_MODE_STATE`）保留定义（历史值与兼容读），
         * 但**不再写**；恢复路径仍会尽力删除它，以清理旧版本留下的残file。
         *
         * @return 恒为 `0`（保留返回值以保持六步动作的日志形状不变）
         */
        private suspend fun disableAllTriggers(): Int {
            Log.i(
                TAG,
                "SAFEMODE_INTERCEPT_EVENTS active=true (in-memory gate; user subscriptions untouched)",
            )
            return 0
        }

        /**
         * **恢复步骤：无需还原任何触发器**（总开关重构后）。
         *
         * ## 为什么是空实现
         * 熔断侧改成**内存级拦截**后（见 [disableAllTriggers]），熔断过程**不再改动
         * 用户的订阅数据** —— 既然没改，就没有要还原的东西。
         *
         * ## 为什么保留方法而不是删掉
         * 1. 六步动作的**形状**要稳定（日志判读按步骤序号比对）
         * 2. [mode] 仍要**如实记进日志**：用户点"退出安全模式"时选的模式会影响他的预期，
         *    将来若恢复 `KeepDisabled` 这类语义，从日志里能看出当时选了什么
         * 3. 删了会让"恢复路径到底做过什么"少一段可判读的证据
         *
         * ## 仍会清理旧的 `safemode.state`
         * 旧版本可能留下了该文件；恢复路径**尽力删除**它（见 [CircuitBreakerImpl.restore]），
         * 避免它长期留在设备上造成"还有东西没还原"的误解。
         *
         * @return 恒为 `0`（保留返回值以保持日志形状不变）
         */
        private suspend fun restoreTriggers(mode: RestoreMode): Int {
            Log.i(
                TAG,
                "SAFEMODE_RESTORE noop mode=$mode " +
                    "(event interception is in-memory; user subscriptions were never modified)",
            )
            return 0
        }

        /** 单调时钟读数（`CrashMarkerStore` 与判定机共用同一时基语义）。 */
        private fun now(): Long = monotonicClock()

        private companion object {
            const val TAG: String = "RootFlow"

            /** `safemode.flag` 里成因键的行前缀（与 [tripInternal] 的写入格式一一对应）。 */
            const val REASON_PREFIX: String = "reason="

            /** `safemode.flag` 里人类可读说明的行前缀。 */
            const val DETAIL_PREFIX: String = "detail="

            /** 端口入口（`onRunFinished`）不带 `runId` 时的日志占位（便于判读"从哪条路来"）。 */
            const val NO_RUN_ID: String = "-"

            /** 风暴窗口（需求 §5.1：10 分钟）。与 `CircuitBreakerMachine` 的默认值同源。 */
            const val STORM_WINDOW_MILLIS: Long = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_WINDOW_MILLIS

            /**
             * 预置历史时最多回看多少条运行。
             *
             * 取 [CircuitBreakerMachine.DEFAULT_FAILURE_STORM_THRESHOLD] 的 10 倍：风暴阈值是 20 次，
             * 回看 200 条足以覆盖"窗口内 20 次失败"，同时避免大表全扫。
             */
            const val HISTORY_SEED_LIMIT: Int = CircuitBreakerMachine.DEFAULT_FAILURE_STORM_THRESHOLD * 10
        }
    }
