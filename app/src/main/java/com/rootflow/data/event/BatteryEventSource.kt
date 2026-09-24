package com.rootflow.data.event

import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.SystemEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `battery_low` / `battery_okay` 事件源（阶段 3c.1，**清单注册路线**）。
 *
 * ## `battery_okay` 是需求 §2.1 未列但必须建的事件（决策 3）
 * 需求只列了 `battery_low`。**缺了恢复边会让"低电量"状态无法复位**：
 * 用户脚本若按 `battery_low` 做了省电动作（关同步、降亮度、停后台任务），
 * 没有 `battery_okay` 就永远回不去。因此本类对应**两条**广播：
 * `ACTION_BATTERY_LOW` 对应 `battery_low`，`ACTION_BATTERY_OKAY` 对应 `battery_okay`。
 *
 * ## 与 `PowerEventSource` 同构
 * 同为清单注册，`start()` / `stop()` 无操作（理由见 `PowerEventSource` 的类文档）；
 * 本类存在的意义是让 `EventSourceRegistry.status()` 覆盖到这两个事件。
 *
 * ## multibinding 贡献为何不在这里
 * 它的 `@IntoSet` 贡献在 `data/event/android/BatteryEventSourceModule.kt`——
 * 该处 KDoc 记录了 KSP 异常的完整对照实验，**合并回去前请先读那段**。
 */
@Singleton
class BatteryEventSource
    @Inject
    constructor() : EventSource {
        override val sourceId: String = SOURCE_ID

        /**
         * 电量低 / 恢复由**清单注册**的接收器产出（本源的 `start()` 是 no-op）。
         *
         * 与 `PowerEventSource` 同理：声明它是为了账目**如实**，而不是为了按需启
         * （清单接收器无法运行时启停；本源的 `status()` 恒为 `Running`）。
         */
        override val providesEvents: Set<String> =
            setOf(SystemEvent.BATTERY_LOW, SystemEvent.BATTERY_OKAY)

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
            const val SOURCE_ID: String = "battery"
        }
    }
