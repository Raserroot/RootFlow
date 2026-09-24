package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SafeModeDecision] 单测（需求 §5.4）。
 *
 * ## 本类的存在意义：把"两层判定"钉开
 * `shouldRun`（某脚本能否跑）与 `shouldDispatchEvents`（某事件要不要分发）
 * **不是**同一层判定。它们很容易被后续会话"合并简化"成一个函数——
 * 而那样做的后果是 `runOnSafeMode` 的豁免语义会在分发层被静默吞掉，
 * 或者反过来：事件被分发、被拒的脚本仍然产生一遍 Room 查询与日志噪声。
 *
 * 因此这里用**真值表**把两者逐项钉死，并专门断言"它们在同一输入下可以不同"。
 */
class SafeModeDecisionTest {
    // ------------------------------------------------ shouldRun（脚本层）

    @Test
    fun `outside safe mode everything runs`() {
        assertTrue(SafeModeDecision.shouldRun(safeMode = false, runOnSafeMode = false))
        assertTrue(SafeModeDecision.shouldRun(safeMode = false, runOnSafeMode = true))
    }

    @Test
    fun `in safe mode only opted-in scripts run`() {
        assertFalse(
            SafeModeDecision.shouldRun(safeMode = true, runOnSafeMode = false),
            "需求 §5.4：安全模式下除 runOnSafeMode=true 外一律不执行",
        )
        assertTrue(
            SafeModeDecision.shouldRun(safeMode = true, runOnSafeMode = true),
            "runOnSafeMode=true 是需求明确的例外（救砖脚本）",
        )
    }

    @Test
    fun `skipping the admission gate happens exactly in safe mode`() {
        // 安全模式下的每一次放行都跳过闸门（能跑的本就只有少数救砖脚本，
        // 让它们因闸门满而跑不起来与机制目的相悖）
        assertTrue(SafeModeDecision.skipsAdmissionGate(safeMode = true))
        assertFalse(
            SafeModeDecision.skipsAdmissionGate(safeMode = false),
            "非安全模式必须走闸门 —— 否则全局并发上限（需求 §2.2）形同失效",
        )
    }

    // ------------------------------------------------ shouldDispatchEvents（分发层）

    @Test
    fun `events are dispatched only outside safe mode`() {
        assertTrue(SafeModeDecision.shouldDispatchEvents(safeMode = false))
        assertFalse(
            SafeModeDecision.shouldDispatchEvents(safeMode = true),
            "需求 §5.4：事件监听器注册但**不分发**",
        )
    }

    // ------------------------------------------------ 两层不可互相替代

    @Test
    fun `the two layers are not interchangeable`() {
        // 同一输入（安全模式）下两层的结论**可以不同**：
        // 事件层一律丢弃，脚本层却可能放行（豁免脚本）。
        // 若有人把它们合并成一个函数，这条断言会立刻失败。
        val safeMode = true

        assertFalse(
            SafeModeDecision.shouldDispatchEvents(safeMode),
            "分发层：安全模式下任何事件都不下发",
        )
        assertTrue(
            SafeModeDecision.shouldRun(safeMode = safeMode, runOnSafeMode = true),
            "脚本层：豁免脚本仍可运行（其入口是显式运行，不是事件）",
        )
    }

    @Test
    fun `safe mode is the only input that changes either decision`() {
        // 真值表全覆盖：4 种组合 × 2 个函数，结论只由 safeMode 决定
        // （`shouldRun` 还额外受 runOnSafeMode 影响，但仅在 safeMode=true 时）
        val runs =
            listOf(false, true).flatMap { safeMode ->
                listOf(false, true).map { runOnSafeMode ->
                    SafeModeDecision.shouldRun(safeMode, runOnSafeMode)
                }
            }
        assertTrue(
            runs == listOf(true, true, false, true),
            "shouldRun 的真值表必须是 (false,false)=T (false,true)=T (true,false)=F (true,true)=T，实际=$runs",
        )
    }
}
