package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.event.ForegroundDetector
import com.rootflow.domain.event.ForegroundSnapshot
import com.rootflow.domain.model.SystemEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次前台查询的结果（阶段 3c.2）。
 *
 * 与 [ForegroundSnapshot] 的区别：本类型含"**查不到的原因**"。查询不可用（权限被撤销、
 * 系统服务不可用）属**运行期降级**，必须可区分于"这一轮恰好没有事件"——
 * 前者要告警，后者是正常的（2s 一次的轮询里绝大多数轮次都查不到新事件）。
 */
sealed interface UsageStatsRead {
    /**
     * 查询成功。
     *
     * @property packageName 当前前台包名；`null` = **本轮没有新事件**（不是"没有前台应用"）
     */
    data class Foreground(
        val packageName: String?,
    ) : UsageStatsRead

    /** 查询不可用（权限被撤销 / 服务缺失）。 */
    data object Unavailable : UsageStatsRead
}

/**
 * 前台应用的读取端口（阶段 3c.2 的 Android 注入缝）。
 *
 * ## 为什么游标（`cursorMillis`）由读取方持有
 * `UsageStatsManager.queryEvents(begin, end)` 需要"上次读到哪"。游标是**与实现绑定的**
 * 细节（真实实现要知道上次查询的时间窗），因此由实现自己维护，调用方只需传"现在几点"。
 * 这样 `UsageStatsPollingSource` 不必关心时间窗逻辑，纯 JVM 单测里也无需伪造时间窗。
 *
 * 唯一实现 `data/event/android/AndroidUsageStatsReader` 直调 `UsageStatsManager`，
 * **按决策 13 不进单测**（其行为由真机覆盖）。
 */
interface UsageStatsReader {
    /** 上次读取到的时刻（毫秒）；首次读取前实现应返回一个"足够旧"的起点。 */
    val cursorMillis: Long

    /**
     * 读取自 [cursorMillis] 至 [currentMillis] 之间的前台变化。
     *
     * **不得抛异常**：不可用必须返回 [UsageStatsRead.Unavailable]。
     */
    fun read(currentMillis: Long): UsageStatsRead

    /** 重置游标（`start()` 时调用，避免把上一次运行的历史事件当成新事件重放）。 */
    fun reset()
}

/**
 * `app_foreground` / `app_background` 的轮询源（阶段 3c.2）。
 *
 * ## 为什么一个源发两个事件（决策评估结论）
 * 两条边由**同一次轮询、同一个状态机**产出（`A→B` 同时给出 `left` 与 `entered`）。
 * 拆成两个源会让同一次轮询跑两遍或共享状态，反而不真实。代价是
 * **缺 `PACKAGE_USAGE_STATS` 时两个事件一起不可用**——这正是需求 §2.1 的降级策略
 * （"无权限时该事件不可用，UI 灰显并给授权跳转"），由阶段 6 按 `PermissionGrant` 逐项提示。
 *
 * ## 权限门控
 * [requiredPermissions] 声明 `PACKAGE_USAGE_STATS`，由 `EventSourceRegistry` 在
 * `start()` 时统一检查（决策 7：源因缺权限不启动，原因具体到权限字符串）。
 * **本源自身不重复判定权限**——否则同一规则会有两处真相。
 *
 * ## 息屏暂停（需求 §2.1：「1~2s，**仅前台时轮询**」）
 * 本源订阅 `EventBus.events()` 的屏幕事件：
 * - `ScreenOff` → **取消轮询作业**并 `detector.reset()`
 * - `ScreenOn` → 重新起轮询
 *
 * **暂停期间不发 `AppBackground`**：息屏不是用户"切走了应用"，
 * 发后台事件会让"退到后台就清理"这类脚本在每次锁屏时被误触发。
 * 同理 `detector.reset()` 让亮屏后的**首次**快照不上报（用户可能在息屏期间换过 App，
 * 恢复后立刻报一对 Left/Entered 是噪声）。
 *
 * ## 与 §9.4 的关系（虚拟时间下不得空闲自旋）
 * 轮询循环是 `while (isActive) { read; delay(interval) }`：**无输入时它靠 `delay` 让出**，
 * 且只有 `stop()` / 息屏能终止它。它**不使用** `withTimeoutOrNull` 空转，
 * 因此不会像阶段 2 的 `flushLoop` 那样在虚拟时间下活锁。
 *
 * ## 构造函数形态（勿改成带默认值的 `@Inject` 构造）
 * `pollIntervalMillis` / `clock` 走 `internal` 次构造函数：Kotlin 默认参数对 Dagger 不可见，
 * 放进 `@Inject` 构造会报 `MissingBinding`（3c.1 实测教训）。
 *
 * @param reader 前台读取端口
 * @param eventBus 事件总线（发事件 + 订阅屏幕事件）
 * @param scope 轮询作用域（生产复用 `@EventDispatcherScope`）
 * @param detector 纯状态机
 */
@Singleton
class UsageStatsPollingSource
    @Inject
    constructor(
        private val reader: UsageStatsReader,
        private val eventBus: EventBus,
        private val scope: CoroutineScope,
        private val detector: ForegroundDetector,
    ) : EventSource {
        /** 测试缝：注入轮询间隔与时钟（生产走 `@Inject` 主构造）。 */
        internal constructor(
            reader: UsageStatsReader,
            eventBus: EventBus,
            scope: CoroutineScope,
            detector: ForegroundDetector,
            pollIntervalMillis: Long,
            clock: () -> Long,
        ) : this(reader = reader, eventBus = eventBus, scope = scope, detector = detector) {
            this.pollIntervalMillis = pollIntervalMillis
            this.clock = clock
        }

        private var pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS

        private var clock: () -> Long = System::currentTimeMillis

        /** 保护 [started] / [screenJob] / [pollJob]（`start`/`stop` 可能来自不同线程）。 */
        private val lock = Any()

        private var started: Boolean = false

        private var screenJob: Job? = null

        private var pollJob: Job? = null

        override val sourceId: String = SOURCE_ID

        /**
         * 前 / 后台变化由本源的轮询产出。
         *
         * ⚠ **不包含** `screen_on` / `screen_off`：本源订阅那两个事件只是为了
         * "熄屏暂停轮询"（省电），并**不产出**它们 —— 写进来会让它被无关的订阅唤醒，
         * 而它每一次唤醒都是一次 2 秒周期的轮询。
         */
        override val providesEvents: Set<String> =
            setOf(SystemEvent.APP_FOREGROUND, SystemEvent.APP_BACKGROUND)

        /** 需求 §2.1：`app_foreground`/`app_background` 需要 `PACKAGE_USAGE_STATS`。 */
        override val requiredPermissions: Set<AndroidPermission> =
            setOf(AndroidPermission.PACKAGE_USAGE_STATS)

        override fun start() {
            synchronized(lock) {
                if (started) return
                started = true
                // 重置游标与状态机：避免把"上一次运行期间"的历史事件当成新事件重放，
                // 也让亮屏/重启后的首次快照不上报（与息屏暂停同一语义）。
                reader.reset()
                detector.reset()

                screenJob =
                    scope.launch {
                        eventBus
                            .events()
                            .filter { it is SystemEvent.ScreenOn || it is SystemEvent.ScreenOff }
                            .collect { event -> onScreenEvent(event) }
                    }

                // 初始按"屏幕亮着"起轮询；若实际息屏，第一条 ScreenOff 会立刻把它停下。
                startPolling()
                Log.i(TAG, "POLL_STARTED intervalMs=$pollIntervalMillis")
            }
        }

        override fun stop() {
            synchronized(lock) {
                if (!started) return
                started = false
                screenJob?.cancel()
                screenJob = null
                stopPolling()
                detector.reset()
                reader.reset()
                Log.i(TAG, "POLL_STOPPED")
            }
        }

        override fun status(): EventSourceStatus =
            EventSourceStatus(
                sourceId = sourceId,
                state =
                    if (synchronized(lock) { started }) {
                        EventSourceState.Running
                    } else {
                        EventSourceState.NotStarted
                    },
            )

        /** 当前是否正在轮询（供单测与 `status()` 判读；与 `started` 不同：息屏时 `false`）。 */
        internal val isPolling: Boolean
            get() = synchronized(lock) { pollJob?.isActive == true }

        /** 处理一次屏幕事件：息屏暂停、亮屏恢复。 */
        private fun onScreenEvent(event: SystemEvent) {
            synchronized(lock) {
                if (!started) return
                when (event) {
                    is SystemEvent.ScreenOff -> {
                        if (pollJob == null) return
                        // 息屏：停下轮询。**不发 AppBackground**（息屏 ≠ 用户切走应用），
                        // reset 让亮屏后的首次快照不上报。
                        stopPolling()
                        detector.reset()
                        Log.i(TAG, "POLL_PAUSED reason=screen_off")
                    }

                    is SystemEvent.ScreenOn -> {
                        if (pollJob != null) return
                        startPolling()
                        Log.i(TAG, "POLL_RESUMED reason=screen_on")
                    }

                    else -> Unit
                }
            }
        }

        /** 起轮询作业（调用方须持有 [lock]）。幂等。 */
        private fun startPolling() {
            if (pollJob != null) return
            pollJob =
                scope.launch {
                    while (isActive) {
                        pollOnce()
                        delay(pollIntervalMillis)
                    }
                }
        }

        /** 停轮询作业（调用方须持有 [lock]）。幂等。 */
        private fun stopPolling() {
            pollJob?.cancel()
            pollJob = null
        }

        /**
         * 轮询一轮。
         *
         * **不抛异常**：读取失败（[UsageStatsRead.Unavailable]）只记警告——
         * 权限被运行期撤销时，源应静默降级而不是炸掉作用域（`SupervisorJob` 之下也应如此，
         * 因为一次失败不该让整个源永久停止轮询）。
         */
        internal fun pollOnce() {
            val snapshot =
                try {
                    when (val read = reader.read(clock())) {
                        is UsageStatsRead.Foreground -> read.foregroundSnapshot()
                        UsageStatsRead.Unavailable -> {
                            Log.w(TAG, "POLL_UNAVAILABLE reason=usage_stats_unavailable")
                            return
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "POLL_FAILED: ${error.message ?: error::class.java.name}")
                    return
                }

            val transition = detector.accept(snapshot)
            transition.left?.let { left ->
                logAndSend(SystemEvent.AppBackground(packageName = left), "left")
            }
            transition.entered?.let { entered ->
                logAndSend(SystemEvent.AppForeground(packageName = entered), "entered")
            }
        }

        /** 发事件 + 打一行真机可判读的日志。 */
        private fun logAndSend(
            event: SystemEvent,
            edge: String,
        ) {
            eventBus.send(event)
            Log.i(TAG, "APP_FOREGROUND_RECEIVED edge=$edge event=${event.eventId} payload=${event.payloadJson()}")
        }

        /** `UsageStatsRead.Foreground` → 状态机快照。 */
        private fun UsageStatsRead.Foreground.foregroundSnapshot(): ForegroundSnapshot =
            packageName
                ?.let { ForegroundSnapshot.Known(packageName = it) }
                ?: ForegroundSnapshot.Unknown

        internal companion object {
            /** 稳定源标识。 */
            const val SOURCE_ID: String = "usage_stats"

            const val TAG: String = "RootFlow"

            /** 需求 §2.1：`app_foreground` 轮询 1~2s。取上限 2000ms（省电优先）。 */
            const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 2_000L
        }
    }
