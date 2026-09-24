package com.rootflow.data.event

import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.SystemEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `power_connected` / `power_disconnected` 事件源（阶段 3c.1，**清单注册路线**）。
 *
 * ## 为什么 `start()` / `stop()` 是无操作
 * 需求 §2.1 指定这两项用**静态 `BroadcastReceiver`**（`ACTION_POWER_CONNECTED` /
 * `ACTION_POWER_DISCONNECTED` 不在 Android 8.0 的隐式广播禁令之列，清单声明可正常收到）。
 * 系统在事件发生时自行实例化 `PowerEventReceiver`，因此这里没有需要在运行期注册的东西。
 *
 * ## 那这个类存在的意义是什么
 * `EventSourceRegistry.status()` 要求**每个源**都能回答"是否启动 + 为什么没启动"（决策 7）。
 * 若清单注册的事件没有对应的源对象，它们就会从状态报告中**整体消失**——
 * 阶段 6 的 UI 与真机排查都会漏掉这一半事件。
 * 因此本类是一个**诚实的状态占位**：恒报 `EventSourceState.Running`，并说明它就是清单注册的。
 *
 * ## 实际生效前提
 * 清单注册的接收器只要求"应用进程存在时被系统拉起"；**进程被回收后事件即失效**
 * （与 `ScreenEventSource` 同命），要真正常驻仍需阶段 5 的前台服务。
 */
@Singleton
class PowerEventSource
    @Inject
    constructor() : EventSource {
        override val sourceId: String = SOURCE_ID

        /**
         * 电源插拔由**清单注册**的接收器产出（本源的 `start()` 是 no-op）。
         *
         * 声明它不是为了"按需启"（清单接收器**无法**运行时启停），而是为了让
         * `EventSourceRegistry` 的账目**如实**：这两个事件确实由本源产出。
         * 也正因如此，本源的 `status()` 恒为 `Running` —— 它一直可用。
         */
        override val providesEvents: Set<String> =
            setOf(SystemEvent.POWER_CONNECTED, SystemEvent.POWER_DISCONNECTED)

        override fun start() {
            // 清单注册：系统负责实例化接收器，无需（也无法）在运行期注册。
        }

        override fun stop() {
            // 同上：不能反注册一个由系统按清单实例化的接收器。
        }

        override fun status(): EventSourceStatus =
            EventSourceStatus(
                sourceId = sourceId,
                state = EventSourceState.Running,
            )

        internal companion object {
            /** 稳定源标识。 */
            const val SOURCE_ID: String = "power"
        }
    }
