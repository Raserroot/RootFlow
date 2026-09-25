package com.rootflow.data.service

import android.os.SystemClock
import android.util.Log
import com.rootflow.data.event.AlarmHandle
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.domain.service.KeepAliveAction
import com.rootflow.domain.service.KeepAliveDecision
import com.rootflow.domain.service.KeepAliveHealthCheck
import com.rootflow.domain.service.KeepAliveInput
import com.rootflow.domain.service.KeepAliveVerdict
import com.rootflow.domain.service.KeepAliveWaker
import com.rootflow.domain.service.KeepAliveWatchdog
import com.rootflow.service.receiver.AlarmActions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保活看门狗的实现（阶段 12c）。
 *
 * ## 形态：一个一次性闹钟 + 自续期（**零新依赖**）
 * ```
 * 服务 register()      → ensureScheduled()：排一个 15 分钟后的闹钟（域 = com.rootflow.KEEPALIVE）
 * 闹钟到点            → AlarmFireReceiver 收到 → 看门狗 onHeartbeat()
 *                        ├─ 服务不在 ⇒ 拉起它（KeepAliveWaker）
 *                        └─ 无论如何 ⇒ 再排下一个（自续期，这是"一直有下一次检查"的全部机制）
 * ```
 * **不引入 WorkManager**（阶段 5 的裁定 ① 以为那条路需要它 —— 见 `KeepAliveHealthCheck` 的 KDoc）。
 * `AlarmManager` 与 `AlarmFireReceiver` 都是项目里已有的东西。
 *
 * ## 三个依赖，每个都对着一条纪律
 * | 依赖 | 作用 | 为什么是它 |
 * |---|---|---|
 * | [AlarmHandle] | 排 / 撤闹钟 | 既有端口（`AndroidAlarmHandle` 已是唯一 Android 适配点） |
 * | [KeepAliveWaker] | "把服务拉起来" | 抽成缝 ⇒ 本类**可纯 JVM 单测** |
 * | [SafeModeSnapshot] | 安全模式**三态** | 冷启动竞态下"还不知道"必须是第三种取值，见其类 KDoc |
 *
 * ## ★ 时钟必须注入（两个）
 * | 缝 | 用途 | 为什么不能用另一个 |
 * |---|---|---|
 * | [elapsedRealtimeMillis] | 判定的时间基准、`ageMillis` 日志 | **单调**：墙钟可被回拨（用户改时间 / NTP），而"服务上次报活是多久之前"必须单调（`DaemonSupervisorImpl` / `BootloopGuard` 同款理由） |
 * | [wallClockMillis] | 算闹钟的**触发时刻** | `AlarmManager` 的 `RTC_WAKEUP` 认的是墙钟；拿 `elapsedRealtime` 去排会把闹钟排到"开机后 3 小时"这种错位时刻 |
 *
 * 两个都用 `internal var` 而不是构造形参：本类是 `@Inject` 构造，而 `() -> Long` 这类
 * 函数类型**无法被 Dagger 绑定**（Kotlin 默认值对 Dagger 也不存在）—— 与
 * `DaemonSupervisorImpl.elapsedRealtimeMillis` / `ScriptEditorViewModel.debounceMs` 同款处置。
 *
 * ## 线程
 * 三个可变来源都可能来自不同线程：闹钟广播（主线程）、服务状态变化、
 * 以及测试里的直接调用。`lastHeartbeatMillis` 由 [lock] 保护 —— **不用 `@Volatile`**：
 * 判定要读"心跳 + 是否已见过服务"，分离的可变性会造出 check-then-act 竞态
 * （`WifiEventSource` 决策 12 的同一条结论）。
 */
@Singleton
class KeepAliveWatchdogImpl
    @Inject
    constructor(
        private val alarmHandle: AlarmHandle,
        private val waker: KeepAliveWaker,
        private val safeModeSnapshot: SafeModeSnapshot,
    ) : KeepAliveWatchdog,
        KeepAliveHealthCheck {
        /** 单调时钟缝（见类 KDoc 的表）。 */
        internal var elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime

        /** 墙钟缝（**只**用于算闹钟的绝对触发时刻，见类 KDoc 的表）。 */
        internal var wallClockMillis: () -> Long = System::currentTimeMillis

        private val lock = Any()

        /**
         * 服务最后一次报活的时刻（单调时钟）；`0` = **本进程内从未见过活着的服务**。
         *
         * 这个 `0` 是看门狗的全部判据来源（见 `KeepAliveDecision` 的 KDoc 与判定表）：
         * 闹钟广播会冷启动进程 ⇒ 新进程里它必然是 `0` ⇒ 正确地自愈一次；
         * 而服务被 `onDestroy` 时由 [onServiceStopped] 清回 `0`。
         */
        private var lastHeartbeatMillis: Long = 0L

        override fun ensureScheduled() {
            scheduleNext(
                intervalMillis = KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS,
                reason = "service registered",
            )
        }

        override fun cancel() {
            runCatching { alarmHandle.cancel(KEEPALIVE_REQUEST_CODE, AlarmActions.KEEPALIVE) }
                .onFailure { Log.w(TAG, "KEEPALIVE_CANCEL_FAILED ${describe(it)}") }
            Log.i(TAG, "KEEPALIVE_CANCELLED reason=user stopped the service")
        }

        override fun onServiceHeartbeat() {
            val now = elapsedRealtimeMillis()
            val first =
                synchronized(lock) {
                    val wasUnknown = lastHeartbeatMillis <= 0L
                    lastHeartbeatMillis = now
                    wasUnknown
                }
            // 只在"第一次见到"时打一行：本条会被每次状态变化调用（可能很密），
            // 而真机判读需要的正是"本进程见过服务"这个**事实**，不是每次刷新的流水。
            if (first) Log.i(TAG, "KEEPALIVE_SERVICE_SEEN at=$now")
        }

        override fun onServiceStopped() {
            val seenAt =
                synchronized(lock) {
                    val previous = lastHeartbeatMillis
                    lastHeartbeatMillis = 0L
                    previous
                }
            if (seenAt > 0L) {
                Log.i(
                    TAG,
                    "KEEPALIVE_SERVICE_STOPPED wasSeenAt=$seenAt " +
                        "livedMillis=${elapsedRealtimeMillis() - seenAt}",
                )
            }
        }

        /**
         * 一次检查（闹钟到点）。
         *
         * 判定本身是纯函数（[KeepAliveDecision]），这里只负责：装配输入 → 执行动作 → **自续期**。
         *
         * ## 自续期必须**无条件**发生（包括 DEFER）
         * 漏掉它就等于"看门狗只响一次"——而失败表现是**完全静默**的：
         * 第一次心跳之后一切看起来都正常，只是再也没有第二次检查。
         * 三条分支里唯一没有自续期的是"根本不排"这条路径 —— 那只有 [cancel] 走。
         */
        override fun onHeartbeat(): KeepAliveVerdict {
            val input =
                KeepAliveInput(
                    nowMillis = elapsedRealtimeMillis(),
                    lastHeartbeatMillis = synchronized(lock) { lastHeartbeatMillis },
                    safeMode = safeModeSnapshot.current,
                )
            val verdict = KeepAliveDecision.decide(input)

            Log.i(
                TAG,
                "KEEPALIVE_HEARTBEAT action=${verdict.action} ageMillis=${verdict.ageMillis ?: -1L} " +
                    "safeMode=${describeSafeMode(input.safeMode)}",
            )

            when (verdict.action) {
                KeepAliveAction.WAKE_SERVICE -> {
                    Log.w(TAG, "KEEPALIVE_WAKE reason=service not seen in this process")
                    runCatching { waker.wake() }
                        .onFailure { Log.w(TAG, "KEEPALIVE_WAKE_FAILED ${describe(it)}") }
                }

                // 判不了（安全模式状态还没恢复）：**不拉服务**，只把下一次检查提前。
                // 方向是保守的那一侧 —— 见 KeepAliveAction.DEFER 的 KDoc。
                KeepAliveAction.DEFER -> Log.i(TAG, "KEEPALIVE_DEFERRED reason=safe mode state unknown yet")

                KeepAliveAction.NOTHING ->
                    Log.i(
                        TAG,
                        "KEEPALIVE_NOOP reason=" +
                            if (input.safeMode == true) "safe mode" else "service alive",
                    )
            }

            scheduleNext(
                intervalMillis =
                    if (verdict.action == KeepAliveAction.DEFER) {
                        KeepAliveHealthCheck.DEFER_INTERVAL_MILLIS
                    } else {
                        KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS
                    },
                reason = "self renew after ${verdict.action}",
            )
            return verdict
        }

        /** 排下一次检查（替换语义由 `AlarmHandle` 的 `(requestCode, action)` 身份保证）。 */
        private fun scheduleNext(
            intervalMillis: Long,
            reason: String,
        ) {
            val atMillis = wallClockMillis() + intervalMillis
            runCatching { alarmHandle.setNext(KEEPALIVE_REQUEST_CODE, AlarmActions.KEEPALIVE, atMillis) }
                .onFailure { Log.w(TAG, "KEEPALIVE_SCHEDULE_FAILED ${describe(it)}") }
                .onSuccess {
                    Log.i(TAG, "KEEPALIVE_SCHEDULED at=$atMillis in=$intervalMillis reason=$reason")
                }
        }

        private fun describeSafeMode(safeMode: Boolean?): String =
            when (safeMode) {
                true -> "safe"
                false -> "normal"
                null -> "unknown"
            }

        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /**
             * 心跳闹钟的 `requestCode`。
             *
             * `AlarmManager` 的闹钟身份是 **`(requestCode, action)` 组合**，而心跳用的是
             * **独立的 action 域**（`AlarmActions.KEEPALIVE`）⇒ 与用户触发器派生的
             * `requestCode`（`AlarmSchedule.deriveRequestCode`，对自增的 `scriptId` 恒为正数）
             * 不会相撞。取 `0` 是"不与正数相撞"的最小值。
             */
            const val KEEPALIVE_REQUEST_CODE: Int = 0
        }
    }
