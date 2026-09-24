package com.rootflow.data.event

import android.util.Log
import com.rootflow.service.receiver.AlarmActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 闹钟的启动对账与重同步（阶段 3d，3c.2 遗留 #3 的收口）。
 *
 * ## 它解决什么
 * `AlarmEventSource.sync()` 的"上一轮注册"是**进程内**记忆。进程被杀后重启，
 * 系统里仍留着上一进程注册的闹钟，而记忆为空 → **它们永远不会被取消**。
 * 唯一可行的对账起点是"**先全清，再按 Room 的全量重建**"（决策 **D-3d-1**）。
 *
 * ## 时序（`start()`）
 * ```
 * ① 对两个域各 cancelAll 一次   ← 覆盖"上一进程注册的旧闹钟"
 * ② provider.currentAlarms()    ← 从 Room 读本轮应有全量
 * ③ AlarmEventSource.sync(...)  ← 重建（内部仍会做冲突检测与 cancel→set）
 * ```
 * ①②③ 必须**严格按序**：先 sync 后 cancelAll 会把刚注册的闹钟一起清掉。
 *
 * ## 重同步触发点（3d 方案 §3）
 * - [start] 时一次（上面的三步）
 * - **每次 `TriggerDispatcher` 分发之后**一次（[requestSync]）：触发器可能刚被增删改，
 *   而 Room 侧没有"触发器变更"的通知通道（`TriggerRepository` 只有 `observeForScript`，
 *   全局观察要动 3a 端口，3d 明确不做）
 *
 * ## 为什么用 `Job` 串行化而不是 `Mutex`
 * [requestSync] 与 [start] 都可能被调用，且都可能被**连续触发**（一次事件分发后紧跟
 * 下一次）。用"上一个作业未完成则跳过本次"的**合并语义**（conflation）而不是排队：
 * 闹钟对账是幂等的**全量重建**，跑两次与跑一次结果相同，排队只会浪费一次全量 IO。
 *
 * ## 为什么失败只记日志
 * 闹钟对账失败不该影响事件分发主链路（`TriggeredScriptRunner` 已经受理了脚本）。
 * 但**不得静默**：每次对账的四个计数都打一行 `ALARM_SYNC_*` 日志，真机可 `findstr` 定位。
 *
 * @param scope 应用级作用域（`@EventDispatcherScope`）
 * @param onWarning 告警回调（生产接 `Log.w`；单测收集断言）
 */
@Singleton
class AlarmSyncCoordinator
    @Inject
    constructor(
        private val provider: TriggerScheduleProvider,
        private val alarmEventSource: AlarmEventSource,
        private val alarmHandle: AlarmHandle,
        private val scope: CoroutineScope,
    ) {
        /** 单测缝：注入告警回调（生产走 `Log.w`）。 */
        internal constructor(
            provider: TriggerScheduleProvider,
            alarmEventSource: AlarmEventSource,
            alarmHandle: AlarmHandle,
            scope: CoroutineScope,
            onWarning: (String) -> Unit,
        ) : this(provider, alarmEventSource, alarmHandle, scope) {
            this.onWarning = onWarning
        }

        private var onWarning: (String) -> Unit = { message -> Log.w(TAG, message) }

        private val lock = Any()

        /** 正在进行的对账作业（合并语义：非空即表示"已有一次在跑"）。 */
        private var inFlight: Job? = null

        /**
         * 本进程是否已执行过"清空全部闹钟"。
         *
         * **每进程只清一次**是关键：`cancelAll` 会清掉**全部**闹钟（含刚下发、正在倒计时的），
         * 若每次分发都清，`interval` 闹钟永远等不到触发（见 [runSync] 的真机根因说明）。
         */
        private var clearedThisProcess: Boolean = false

        /** 累计完成的同步次数（真机判读 + 单测断言）。 */
        var completedSyncs: Int = 0
            private set

        /**
         * 启动对账：先 `cancelAll`（两个域各一次），再从 Room 全量重建。
         *
         * **挂起**（不 fire-and-forget）：调用方（`RootFlowApp.onCreate`）在 boot 补发
         * 之前需要确定"闹钟已就绪"。异常在此**不外抛**——对账失败不该让 App 起不来。
         */
        suspend fun start() {
            runSync(reason = "start")
        }

        /**
         * 请求一次重同步（非挂起，供事件分发后的回调调用）。
         *
         * 合并语义：已有对账在跑时**跳过**本次（见类 KDoc）。
         */
        fun requestSync(reason: String) {
            synchronized(lock) {
                if (inFlight?.isActive == true) {
                    Log.i(TAG, "ALARM_SYNC_SKIPPED reason=$reason (another sync in flight)")
                    return
                }
                inFlight = scope.launch { runSync(reason) }
            }
        }

        /**
         * 同步执行一次完整对账；异常转告警，不上抛。
         *
         * ## `cancelAll` **只在本进程第一次**执行（2026-09-19 真机根因修正，勿改回去）
         *
         * ### 缺陷现象
         * 原实现**每次** `runSync` 都先 `cancelAll` 再重建，而它同时挂在
         * `TriggerDispatcher` 的 `after-dispatch` 钩子上 ⇒ **每一次事件分发**
         * （boot / wifi / screen / power / battery …）都会经历一遍
         * "清空全部闹钟 → 重新 `set`"。
         *
         * 而 `setInexactRepeating` 的 `when = now + interval`：**每次 set 都把倒计时归零**。
         * 于是 60 秒间隔的 `interval` 闹钟**永远在将要触发前被自己清掉**——
         * 真机 `dumpsys alarm` 的墓碑（`Batch{ Stats }`）给出了铁证：
         * ```
         * Reason=alarm_cancelled rtc=2026-09-19 15:54:01.733   ← 与 boot 广播同一毫秒
         *   tag=*walarm*:com.rootflow.INTERVAL
         *   policyWhenElapsed: requester=-15m29s761ms            ← 被取消时已过期 15 分钟
         * ```
         * 表现为"闹钟注册成功、App 常驻、却永不触发"，曾被误判为 receiver/PendingIntent 故障
         * （那 5 处逐条核实**全部正确**）。
         *
         * ### 修正语义
         * `cancelAll` 的**设计目的**是清掉"上一个进程注册的、本进程已无记忆的"闹钟
         * （`AlarmEventSource.lastRegistered` 是进程内记忆）。因此它只需
         * **每进程执行一次**；此后同一进程内的重复对账由 `AlarmEventSource.sync()` 自己的
         * "陈旧取消 + `lastSeenTrigger` 幂等"负责——**那条路径不会重置未变动闹钟的倒计时**。
         *
         * @param reason 触发本次对账的原因（仅用于日志）
         */
        private suspend fun runSync(reason: String) {
            try {
                // ① 先清：**仅本进程第一次**（跨进程对账），决策 D-3d-1
                if (!clearedThisProcess) {
                    AlarmActions.ALL.forEach { action -> alarmHandle.cancelAll(action) }
                    clearedThisProcess = true
                    Log.i(TAG, "ALARM_CANCEL_ALL_DONE actions=${AlarmActions.ALL} reason=$reason")
                } else {
                    Log.i(TAG, "ALARM_CANCEL_ALL_SKIPPED reason=$reason (already cleared this process)")
                }

                // ② 取本轮应有全量（Room）
                val alarms = provider.currentAlarms()

                // ③ 重建：`sync()` 内部按上一轮快照对账，**未变动的闹钟不会被重新 set**
                //    （因此不会像 cancelAll 那样把倒计时归零）
                val plan = alarmEventSource.sync(alarms)
                completedSyncs++
                Log.i(
                    TAG,
                    "ALARM_SYNC_DONE reason=$reason registered=${plan.registered} " +
                        "cancelled=${plan.cancelled} conflicts=${plan.skippedConflicts} invalid=${plan.invalid}",
                )
            } catch (error: Throwable) {
                onWarning(
                    "alarm sync failed (reason=$reason): ${error.message ?: error::class.java.name}",
                )
            }
        }

        /** 本进程是否已执行过"清空全部闹钟"（每进程**只应一次**，见 [runSync]）。 */
        internal val hasClearedThisProcess: Boolean
            get() = synchronized(lock) { clearedThisProcess }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
