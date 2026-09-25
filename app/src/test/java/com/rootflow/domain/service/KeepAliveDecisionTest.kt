package com.rootflow.domain.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [KeepAliveDecision] 的单测（阶段 12c）。
 *
 * ## 它守的是什么
 * 判定是**纯函数**，因此这张判定表可以被穷举钉死。最要紧的是第一组用例：
 * **安全模式下，看门狗不拉服务** —— 那是本阶段的第 ① 项硬规则，
 * 也是"加看门狗"这件事唯一真正的新风险（每 15 分钟走一次那条路径）。
 *
 * ## 为什么不用 mock / 协程
 * `decide` 没有任何副作用、不挂起、不碰 Android。用例因此是纯粹的"输入 → 输出"，
 * 一条断言对应判定表的一行 —— 破坏其中任何一行，对应的那条立刻红。
 */
class KeepAliveDecisionTest {
    private fun decide(
        safeMode: Boolean?,
        lastHeartbeatMillis: Long,
        nowMillis: Long = NOW,
    ): KeepAliveVerdict =
        KeepAliveDecision.decide(
            KeepAliveInput(
                nowMillis = nowMillis,
                lastHeartbeatMillis = lastHeartbeatMillis,
                safeMode = safeMode,
            ),
        )

    // ------------------------------------------------------------ ★ 安全护栏（本阶段 ①）

    @Test
    @DisplayName("★ 安全模式否决一切：即使服务确实不在，也不拉服务")
    fun `safe mode never wakes the service`() {
        // 这一条就是 ① 的规则本身：`ForegroundServiceController.register()` 里
        // `daemonSupervisor.start()` 此前无条件执行，而看门狗会每 15 分钟走一次那条路径。
        // 若这条被破坏，熔断期间常驻监管与事件源会被周期性复活
        // （熔断第 2 步刚终止的脚本又被造出来）。
        assertEquals(KeepAliveAction.NOTHING, decide(safeMode = true, lastHeartbeatMillis = 0L).action)
        assertEquals(KeepAliveAction.NOTHING, decide(safeMode = true, lastHeartbeatMillis = NOW - 1L).action)
        assertEquals(KeepAliveAction.NOTHING, decide(safeMode = true, lastHeartbeatMillis = NOW).action)
    }

    @Test
    @DisplayName("安全模式优先于其它一切输入：判定不看心跳、也不看时间")
    fun `safe mode outranks the other inputs`() {
        // 同一个"服务不在"的输入，只改 safeMode 就应得到两个不同结论 ——
        // 这条断言把"护栏是**否决**而不是众多输入之一"写进了测试。
        assertEquals(KeepAliveAction.WAKE_SERVICE, decide(safeMode = false, lastHeartbeatMillis = 0L).action)
        assertEquals(KeepAliveAction.NOTHING, decide(safeMode = true, lastHeartbeatMillis = 0L).action)
    }

    // ------------------------------------------------------------ 三态里的"未知"

    @Test
    @DisplayName("安全模式状态未知 ⇒ 延后再判（绝不按'不在安全模式'处理）")
    fun `unknown safe mode defers`() {
        // 心跳广播会冷启动进程，而安全模式的恢复在 Application.onCreate 的异步启动链里。
        // 把"还没恢复"当成 false，就是让看门狗在**熔断中**的进程里把服务拉起来。
        assertEquals(KeepAliveAction.DEFER, decide(safeMode = null, lastHeartbeatMillis = 0L).action)
        assertEquals(KeepAliveAction.DEFER, decide(safeMode = null, lastHeartbeatMillis = NOW).action)
    }

    // ------------------------------------------------------------ 自愈

    @Test
    @DisplayName("本进程没见过活着的服务 ⇒ 拉起它（冷启动与 onDestroy 后的正常路径）")
    fun `a service that was never seen gets woken`() {
        assertEquals(KeepAliveAction.WAKE_SERVICE, decide(safeMode = false, lastHeartbeatMillis = 0L).action)
    }

    @Test
    @DisplayName("负值的心跳读数同样按'没见过'处理（不给未定义值留缝）")
    fun `a negative heartbeat counts as never seen`() {
        assertEquals(KeepAliveAction.WAKE_SERVICE, decide(safeMode = false, lastHeartbeatMillis = -1L).action)
    }

    @Test
    @DisplayName("服务在本进程报过活 ⇒ 什么都不做（安静但健康的服务不得被误拉）")
    fun `a service that reported in does nothing`() {
        // 这是"判据不是心跳超期"的关键证据：即使距上次报活很久很久，
        // 只要服务**还活着**（本进程见过它），看门狗就不该有任何动作。
        val verdict = decide(safeMode = false, lastHeartbeatMillis = NOW - 6L * 60L * 60L * 1000L)
        assertEquals(KeepAliveAction.NOTHING, verdict.action)
    }

    // ------------------------------------------------------------ 判读字段

    @Test
    @DisplayName("ageMillis：从未报活时是 null（不编 0），报过则是真实差值")
    fun `age is null when nothing was ever seen`() {
        assertNull(
            decide(safeMode = false, lastHeartbeatMillis = 0L).ageMillis,
            "0 与 null 语义不同：0 会被读成'刚刚报活'，而事实是'从未报活'",
        )
        assertEquals(2500L, decide(safeMode = false, lastHeartbeatMillis = NOW - 2500L).ageMillis)
    }

    @Test
    @DisplayName("判定结果原样带回输入（真机日志据此打 age / safeMode）")
    fun `the verdict carries the input through`() {
        val input = KeepAliveInput(nowMillis = NOW, lastHeartbeatMillis = NOW - 10L, safeMode = false)
        val verdict = KeepAliveDecision.decide(input)

        assertEquals(input, verdict.input)
    }

    private companion object {
        /**
         * 任意的"当前时刻"（单调时钟读数没有绝对意义，只参与差值）。
         *
         * 取值必须**大于**用例里任何"距上次报活"的跨度 —— 否则减法得到负数，
         * 而被测实现把 `<= 0` 视为"从未报活"，用例会以一条看起来毫不相关的断言失败
         * （本文件第一版就踩了：6 小时 > 1e6 毫秒）。
         */
        const val NOW: Long = 1_000_000_000L
    }
}
