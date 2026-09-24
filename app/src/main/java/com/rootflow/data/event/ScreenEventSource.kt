package com.rootflow.data.event

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.domain.event.BroadcastRegistration
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.SystemEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `screen_on` / `screen_off` / `unlock` 事件源（阶段 3c.1，**动态注册路线**）。
 *
 * ## 为什么必须动态注册
 * 需求 §2.1 明写这三项"只能动态注册"。框架在 `AndroidManifest.xml` 里对
 * `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT` 的**隐式广播投递有禁令**
 * （Android 8.0 后台限制），清单声明收不到——因此**不得**把它们写进清单
 * （`AndroidManifest.xml` 只声明 `power_*` / `battery_*` 两个接收器）。
 *
 * ## 生效范围（需求偏离 D4）
 * 需求要求"须驻留前台服务"。前台服务属**阶段 5**，故阶段 3 阶段本源的注册挂在
 * `Application` 域：**进程被系统回收后这三个事件即失效**。阶段 6 的 UI 不得据此认为
 * 事件已全覆盖（`PROJECT_STATE.md` D4）。
 *
 * ## 幂等与线程
 * [start] / [stop] 由注册表在**同一线程**调用；注册状态由 [BroadcastRegistration] 自身持有，
 * 本源只做"已注册则跳过"的判重，不额外维护可变状态。
 *
 * @param registration 动态注册抽象（生产实现委托 `Context.registerReceiver`）
 * @param bus 事件总线
 */
@Singleton
class ScreenEventSource
    @Inject
    constructor(
        private val registration: BroadcastRegistration,
        private val bus: EventBus,
    ) : EventSource {
        override val sourceId: String = SOURCE_ID

        /** 亮屏 / 熄屏 / 解锁都由这一个广播接收器产出（见 `handleAction`）。 */
        override val providesEvents: Set<String> =
            setOf(SystemEvent.SCREEN_ON, SystemEvent.SCREEN_OFF, SystemEvent.UNLOCK)

        override fun start() {
            if (registration.isRegistered) return
            val receiver = ScreenEventReceiver(bus, registration)
            // 记录实际注册的 action：真机 `findstr /C:"SCREEN_REGISTER"` 可确认三个 action 都在
            Log.i(TAG, "SCREEN_REGISTER actions=${ScreenEventReceiver.ACTIONS.joinToString(",")}")
            registration.register(ScreenEventReceiver.ACTIONS, receiver::handleAction)
        }

        override fun stop() {
            if (!registration.isRegistered) return
            registration.unregister()
            Log.i(TAG, "SCREEN_UNREGISTER")
        }

        override fun status(): EventSourceStatus =
            EventSourceStatus(
                sourceId = sourceId,
                state =
                    if (registration.isRegistered) {
                        EventSourceState.Running
                    } else {
                        EventSourceState.NotStarted
                    },
            )

        internal companion object {
            /** 稳定源标识（真机日志与 [EventSourceStatus.sourceId] 共用）。 */
            const val SOURCE_ID: String = "screen"

            const val TAG: String = "RootFlow"
        }
    }

/**
 * `screen_on` / `screen_off` / `unlock` 的动态接收器（阶段 3c.1）。
 *
 * ## 与 `BootEventReceiver` 的三条纪律一致
 * 1. **不引用 `Intent.ACTION_*` 静态字段**（纯 JVM 下 Android 静态字段不可用，且 MockK 无法
 *    stub 静态字段），改用自有常量，其正确性由真机行为保证
 * 2. `onReceive` 只取 action **字符串**，全部判定在 [handleAction] 里
 * 3. **不查数据库、不启脚本**：只做一次 `bus.send(...)`（`onReceive` 有时限，超时 ANR）
 *
 * ## 为什么可以构造注入 `EventBus`（与清单接收器的关键差异）
 * 本接收器由 [ScreenEventSource] 在运行期 `new` 出来，**不是**系统实例化的，
 * 因此不需要 `EventBusHolder` 那套全局单例——直接持有总线引用更干净，单测也不必碰全局状态。
 *
 * ## 「一次性告警」窗口（[WARN_WINDOW_MILLIS]）
 * 周期性的亮/熄屏（用户一天开关屏上百次）会让"未知 action"反复到达；
 * 若无条件打警告，日志会被刷爆并淹没真正的诊断信息。
 * 因此**每个未知 action 在一个窗口内只告警一次**（用 [BroadcastRegistration.elapsedRealtimeMillis]
 * 取单调时间，见该接口 KDoc）。
 *
 * @param bus 事件总线
 * @param registration 仅用于取单调时间（告警窗口）
 * @param onWarning 告警回调；默认接 `Log.w`
 */
internal class ScreenEventReceiver(
    private val bus: EventBus,
    private val registration: BroadcastRegistration,
    private val onWarning: (String) -> Unit = { message -> Log.w(TAG, message) },
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        handleAction(intent.action ?: return)
    }

    /**
     * 处理一个 action（[onReceive] 的实际逻辑，也是单测入口）。
     *
     * @param action 广播 action；空串按未知处理
     */
    internal fun handleAction(action: String) {
        Log.i(TAG, "SCREEN_RECEIVED action=$action")

        val event =
            when (action) {
                ACTION_SCREEN_ON -> SystemEvent.ScreenOn
                ACTION_SCREEN_OFF -> SystemEvent.ScreenOff
                ACTION_USER_PRESENT -> SystemEvent.Unlock
                else -> {
                    warnUnknownActionOnce(action)
                    return
                }
            }

        bus.send(event)
        Log.i(TAG, "EVENT_BUS sent=${event.eventId}")
    }

    /** 未知 action：同一 action 在一个 [WARN_WINDOW_MILLIS] 窗口内只告警一次。 */
    private fun warnUnknownActionOnce(action: String) {
        val now = registration.elapsedRealtimeMillis()
        val last = lastUnknownActionAt[action]
        if (last != null && now - last < WARN_WINDOW_MILLIS) return

        lastUnknownActionAt[action] = now
        onWarning("SCREEN_RECEIVED unexpected action=$action, ignored")
    }

    /** 未知 action → 上次告警时刻（单调时间）。条目数上界 = 未知 action 种类数，无需淘汰。 */
    private val lastUnknownActionAt = mutableMapOf<String, Long>()

    internal companion object {
        const val TAG: String = "RootFlow"

        /** `ACTION_SCREEN_ON` 的字面量副本（理由见类 KDoc 纪律 1）。 */
        const val ACTION_SCREEN_ON: String = "android.intent.action.SCREEN_ON"

        /** `ACTION_SCREEN_OFF` 的字面量副本。 */
        const val ACTION_SCREEN_OFF: String = "android.intent.action.SCREEN_OFF"

        /** `ACTION_USER_PRESENT` 的字面量副本。 */
        const val ACTION_USER_PRESENT: String = "android.intent.action.USER_PRESENT"

        /** 全部关注的 action，顺序固定（真机日志可逐项核对）。 */
        val ACTIONS: List<String> =
            listOf(ACTION_SCREEN_ON, ACTION_SCREEN_OFF, ACTION_USER_PRESENT)

        /** 未知 action 的告警窗口（毫秒）。 */
        const val WARN_WINDOW_MILLIS: Long = 60_000L
    }
}
