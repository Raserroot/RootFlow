package com.rootflow.data.service

import com.rootflow.domain.service.KeepAliveWatchdog

/**
 * 保活看门狗的**静态持有者**（阶段 12c）。
 *
 * ## 为什么需要它（与 `EventBusHolder` 完全同款的理由）
 * 心跳是由**闹钟广播**送达的，而接收器（`AlarmFireReceiver`）由系统实例化、
 * **无法构造注入**。本项目的既有处置是"极小的静态持有者"：
 * 生产在 `RootFlowApp.onCreate()` 里 install，单测里 install 一个假件再断言。
 *
 * ## 与 `EventBusHolder` 的一个差别：这里**允许为空**
 * 心跳到达时 Holder 理应已装好（`Application.onCreate` 一定早于广播分发），
 * 但"理应"不是"保证" —— 若为空，接收器**只记一行警告**，不做任何事：
 * 那种情况下 App 连 Hilt 图都还没建起来，拉服务本身也不成立。
 * **不得**为了"让它一定能工作"而在这里 `new` 一个实现（那会绕开 DI，
 * 造出第二个看门狗实例，两份 `lastHeartbeatMillis` 互相覆盖）。
 *
 * ## 不持有 Context
 * 与 `EventBusHolder` 同款：因此不会泄漏。
 */
object KeepAliveHolder {
    @Volatile
    private var instance: KeepAliveWatchdog? = null

    /** 安装看门狗实例（应用启动或单测）。 */
    fun install(watchdog: KeepAliveWatchdog) {
        instance = watchdog
    }

    /** @return 当前看门狗；尚未安装时为 `null`（调用方必须容错，见类 KDoc）。 */
    fun get(): KeepAliveWatchdog? = instance

    /** 清除（单测隔离用）。 */
    fun clear() {
        instance = null
    }
}
