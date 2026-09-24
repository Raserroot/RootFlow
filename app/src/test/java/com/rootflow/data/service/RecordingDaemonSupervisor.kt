package com.rootflow.data.service

import com.rootflow.domain.event.DaemonSupervisor

/**
 * [DaemonSupervisor] 的**记录式替身**（阶段 10）。
 *
 * ## 为什么单独放在生产包旁边而不是塞进某个测试文件
 * 有 4 个测试类要构造 `ForegroundServiceController`（它现在的构造参数里含监管器）。
 * 把替身放进其中一个测试文件会让另外三个都去 import 那个文件的私有类 ——
 * 而它们本来就是各自独立的用例。放这里让"监管器被起/停了几次"成为可复用的事实。
 *
 * ## 为什么记录次数而不是布尔
 * 真正要守的性质是**幂等与配对**：`register()` 起一次、`unregister()` 停一次、
 * 熔断时也停一次。只记布尔就分不出"停了两次"与"停了一次"。
 */
class RecordingDaemonSupervisor : DaemonSupervisor {
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    /** 是否正在监管（模拟真实实现的状态机，便于断言"服务停后不再监管"）。 */
    var running: Boolean = false
        private set

    /** 模拟[com.rootflow.domain.event.DaemonSupervisor.start] 抛错。 */
    var failOnStart: Boolean = false

    override fun start() {
        startCalls += 1
        if (failOnStart) throw IllegalStateException("daemon supervisor start failed (test)")
        running = true
    }

    override fun stop() {
        stopCalls += 1
        running = false
    }

    override val supervisedCount: Int
        get() = if (running) 1 else 0

    override fun supervisedIds(): Set<Long> = if (running) setOf(SUPERVISED_ID) else emptySet()

    override fun givenUpReasons(): Map<Long, String> = emptyMap()

    companion object {
        /** 替身假装在监管的那个脚本 id（`1` 是各测试夹具里最常用的那个）。 */
        const val SUPERVISED_ID: Long = 1L
    }
}
