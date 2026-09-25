package com.rootflow.data.service

import android.util.Log
import com.rootflow.data.event.FakeAlarmHandle
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.domain.service.KeepAliveAction
import com.rootflow.domain.service.KeepAliveHealthCheck
import com.rootflow.domain.service.KeepAliveWaker
import com.rootflow.service.receiver.AlarmActions
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [KeepAliveWatchdogImpl] 的单测（阶段 12c）。
 *
 * ## 为什么这个类值得被测
 * 它是"保活"这件事的**全部逻辑**：什么时候该拉服务、什么时候什么都不做、
 * 下一次检查排在什么时候、被系统拒绝时怎么办。
 * Android 侧只剩两行直调（`startForegroundService` / 通知），而**那两行的正确性由真机覆盖**。
 *
 * ## 三条纪律（都来自本仓库既有的教训）
 * 1. **必须自己打桩 `android.util.Log`**：纯 JVM 下 `Log.*` 会抛
 *    `Method i in android.util.Log not mocked`，而本类的每条路径都打日志
 *    （`AGENT_PROTOCOL.md §5.10`；同类问题在 `ForegroundServiceControllerTest` 上真实发生过）
 * 2. **两个时钟都要注入**：单调时钟判活、墙钟算闹钟时刻。不注入的话用例只能断言
 *    "排了一个闹钟"，断言不了"排在什么时候" —— 而"每 15 分钟一次"正是本功能的核心语义
 * 3. **`FakeAlarmHandle` 记录 `(requestCode, action)` 与 `atMillis`**
 *    ⇒ 可以同时验证"域隔离"与"时刻计算"，不需要真机 `dumpsys alarm` 才能确认
 */
class KeepAliveWatchdogImplTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val handle = FakeAlarmHandle()
    private val safeModeSnapshot = SafeModeSnapshot()

    /** 墙钟：只用于算闹钟的**绝对**触发时刻。 */
    private var wallClockMillis: Long = WALL_CLOCK_START

    /** 单调时钟：用于判定与 `ageMillis`。 */
    private var monotonicMillis: Long = MONOTONIC_START

    private val waker = RecordingWaker()

    private val watchdog: KeepAliveWatchdogImpl =
        KeepAliveWatchdogImpl(
            alarmHandle = handle,
            waker = waker,
            safeModeSnapshot = safeModeSnapshot,
        ).also { instance ->
            instance.wallClockMillis = { wallClockMillis }
            instance.elapsedRealtimeMillis = { monotonicMillis }
        }

    @BeforeEach
    fun setUp() {
        handle.calls.clear()
        waker.calls = 0
    }

    // ------------------------------------------------------------ 排闹钟

    @Test
    @DisplayName("ensureScheduled：排在'墙钟 + 一个检查间隔'上，域是 KEEPALIVE")
    fun `ensureScheduled arms the next check one interval ahead`() {
        watchdog.ensureScheduled()

        val call = handle.calls.single()
        assertEquals("setNext", call.op)
        assertEquals(AlarmActions.KEEPALIVE, call.action, "必须落在心跳自己的域上（不得复用 INTERVAL）")
        assertEquals(
            WALL_CLOCK_START + KeepAliveHealthCheck.CHECK_INTERVAL_MILLIS,
            call.atMillis,
            "间隔取 15 分钟；用单调时钟算绝对时刻会把闹钟排到错位的时间",
        )
    }

    @Test
    @DisplayName("cancel：撤掉的是心跳自己的那一支闹钟")
    fun `cancel removes exactly the heartbeat alarm`() {
        watchdog.cancel()

        val call = handle.calls.single()
        assertEquals("cancel", call.op)
        assertEquals(AlarmActions.KEEPALIVE, call.action)
    }

    // ------------------------------------------------------------ ★ 安全护栏（端到端）

    @Test
    @DisplayName("★ 安全模式：心跳到达也不拉服务（① 的规则在整条实现路径上成立）")
    fun `safe mode heartbeat never wakes the service`() {
        safeModeSnapshot.update(safeMode = true)

        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.NOTHING, verdict.action)
        assertEquals(0, waker.calls, "★ 安全模式下不得拉起前台服务（否则熔断会被周期性绕过）")
    }

    @Test
    @DisplayName("安全模式状态未知：不拉服务，并且只等 30 秒而不是 15 分钟")
    fun `an unknown safe mode defers with the short interval`() {
        // 什么都不做：快照还没被启动链填上（心跳广播冷启动进程的那一刻）
        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.DEFER, verdict.action)
        assertEquals(0, waker.calls, "判不了的时候不得拉服务（保守方向是'这次不拉'）")
        assertEquals(
            WALL_CLOCK_START + KeepAliveHealthCheck.DEFER_INTERVAL_MILLIS,
            handle.calls.last().atMillis,
            "补偿间隔必须远短于 15 分钟，否则'保守'会变成'错过一整轮'",
        )
    }

    // ------------------------------------------------------------ 自愈

    @Test
    @DisplayName("服务不在（本进程没见过）⇒ 拉起它，并要求下一次检查")
    fun `a heartbeat wakes the service when it was never seen`() {
        safeModeSnapshot.update(safeMode = false)

        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.WAKE_SERVICE, verdict.action)
        assertEquals(1, waker.calls, "自愈动作必须被执行")
        assertEquals(
            1,
            handle.count("setNext"),
            "唤醒之后必须**自续期** —— 漏掉它等于看门狗只响一次，而且完全静默",
        )
    }

    @Test
    @DisplayName("服务报过活（哪怕很久以前）⇒ 什么都不做，但仍然自续期")
    fun `a service that reported in is left alone`() {
        safeModeSnapshot.update(safeMode = false)
        watchdog.onServiceHeartbeat()
        // 让"距上次报活"走过一个很长的跨度：判定**不**因此改变（不是心跳超期判据）
        monotonicMillis += 6L * 60L * 60L * 1000L

        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.NOTHING, verdict.action)
        assertEquals(0, waker.calls, "服务活着的时候拉它只会造出一次多余的 onStartCommand")
        assertEquals(1, handle.count("setNext"), "什么都不做的分支同样必须自续期")
    }

    @Test
    @DisplayName("服务停止后再来心跳 ⇒ 恢复成'该自愈'")
    fun `after the service stopped the next heartbeat wakes it again`() {
        safeModeSnapshot.update(safeMode = false)
        watchdog.onServiceHeartbeat()
        watchdog.onServiceStopped()

        assertEquals(KeepAliveAction.WAKE_SERVICE, watchdog.onHeartbeat().action)
        assertEquals(1, waker.calls)
    }

    @Test
    @DisplayName("ageMillis 如实反映距上次报活的时长；从未报活时为 null")
    fun `age reflects the last report`() {
        safeModeSnapshot.update(safeMode = false)

        assertNull(watchdog.onHeartbeat().ageMillis, "从未报活 ⇒ null（不编 0）")

        watchdog.onServiceHeartbeat()
        monotonicMillis += 1234L
        assertEquals(1234L, watchdog.onHeartbeat().ageMillis)
    }

    // ------------------------------------------------------------ 失败与降级

    @Test
    @DisplayName("唤醒动作抛异常：心跳不崩，且依然自续期（否则看门狗会在一次失败后永久停摆）")
    fun `a failing waker does not stop the watchdog`() {
        safeModeSnapshot.update(safeMode = false)
        waker.failWith = IllegalStateException("system refused")

        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.WAKE_SERVICE, verdict.action)
        assertEquals(
            1,
            handle.count("setNext"),
            "唤醒失败**不能**带走自续期 —— 那会让一次系统拒绝变成永久停止检查",
        )
    }

    @Test
    @DisplayName("排闹钟失败：不崩，且不影响唤醒那一条路")
    fun `a failing scheduler does not crash`() {
        safeModeSnapshot.update(safeMode = false)
        handle.failOnOp = "setNext"

        watchdog.ensureScheduled()
        val verdict = watchdog.onHeartbeat()

        assertEquals(KeepAliveAction.WAKE_SERVICE, verdict.action, "排闹钟失败不该污染判定")
        assertEquals(1, waker.calls, "排闹钟失败不该影响唤醒这条路")
        // `FakeAlarmHandle` 的语义是"失败的那次调用**不留下记录**"（`maybeFail` 在 `calls +=` 之前），
        // 因此这里能断言的就是"没有成功记录" —— 本用例真正证明的是**没有抛出去**。
        assertEquals(0, handle.count("setNext"))
    }

    // ------------------------------------------------------------ 夹具

    /** 记录型自愈动作假件。 */
    private class RecordingWaker : KeepAliveWaker {
        var calls: Int = 0
        var failWith: Throwable? = null

        override fun wake() {
            calls++
            failWith?.let { throw it }
        }
    }

    private companion object {
        /** 任意的墙钟起点（真实值是 1.7e12 量级，这里只要够大且稳定）。 */
        const val WALL_CLOCK_START: Long = 1_700_000_000_000L

        /** 任意的单调时钟起点。 */
        const val MONOTONIC_START: Long = 5_000_000L
    }
}
