package com.rootflow.data.event

import com.rootflow.domain.event.EventBus

/**
 * 事件总线的**静态持有者**（阶段 3b）。
 *
 * ## 为什么需要它
 * [BootEventReceiver] 由系统实例化，**无法构造注入** `EventBus`；而 3b 明确
 * **不引入 Robolectric / JUnit4**（见 `PROJECT_STATE.md` 与阶段 3a 的依赖现实），
 * 因此也不能依赖 Hilt 的 `@AndroidEntryPoint` + 测试 runner 组合来测试接收器。
 *
 * 一个极小的可注入持有者同时解决两件事：
 * 1. 接收器在 `onReceive` 里拿到总线（`EventBusHolder.get()`）
 * 2. 单测可以**直接构造假 Context 调用 `onReceive`**，并预先 `install(假 bus)`
 *
 * ## 使用约定
 * - 生产：`RootFlowApp.onCreate()` 里 `EventBusHolder.install(eventBus)`（3d 接线时统一做）
 * - 单测：`install(fake)` → 断言 → `clear()`
 *
 * **不持有 Context**，因此不会泄漏。
 */
object EventBusHolder {
    @Volatile
    private var instance: EventBus? = null

    /** 安装总线实例（应用启动或单测）。 */
    fun install(bus: EventBus) {
        instance = bus
    }

    /** @return 当前总线；尚未安装时为 `null`（接收器必须容错，见其 KDoc）。 */
    fun get(): EventBus? = instance

    /** 清除（单测隔离用）。 */
    fun clear() {
        instance = null
    }
}
