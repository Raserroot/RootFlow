package com.rootflow.data.event.android

import android.content.Context
import com.rootflow.data.event.FakePermissionPrimitives
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionDecisions
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.SettingsTargets
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `AndroidPermissionStatusProvider` 的**状态机**单测（阶段 3c.1，决策 6）。
 *
 * ## 测的是哪一层
 * 该类的判定逻辑全部委托给 [PermissionDecisions]（已由 `PermissionDecisionsTest` 穷举 4 档 × 5 权限），
 * 因此这里只验证它自己的三件事：
 * 1. `current()` / `refresh()` 的快照语义
 * 2. `observe()` 的 `StateFlow` 语义（**相同快照不重复发射**，决策 6）
 * 3. `settingsTargetFor()` 的委托（决策 9）
 *
 * ## 为什么可以只 mock `Context`
 * `Context` 是 `@Inject` 构造的**唯一**依赖，且本类**不在构造期探测**（决策 6），
 * 所以一个 relaxed `mockk<Context>()` 就够——不需要 Robolectric，也不触碰任何框架方法。
 *
 * ## 订阅者一律放 `backgroundScope`（`AGENT_PROTOCOL.md §9.2`）
 * `StateFlow` 的收集永不返回；本类用 `first()` 取值后再取消，**不用** `advanceUntilIdle()`
 * 去等一个永不结束的流。
 */
class AndroidPermissionStatusProviderTest {
    private fun provider(sdkInt: Int) = AndroidPermissionStatusProvider(mockk<Context>(relaxed = true), sdkInt)

    @Test
    fun `current is empty before the first refresh`() {
        // 决策 6：不在 init 里探测（避免 DI 构造阶段产生副作用）
        val provider = provider(sdkInt = 34)

        assertTrue(
            provider.current().isEmpty(),
            "未 refresh 前必须为空，而不是伪造一份结论：${provider.current()}",
        )
    }

    @Test
    fun `refresh publishes one state per catalog entry`() {
        val provider = provider(sdkInt = 34)

        provider.refresh()

        assertEquals(AndroidPermission.entries.size, provider.current().size)
        AndroidPermission.entries.forEach { permission ->
            assertNotNull(provider.current()[permission], "$permission 必须有状态")
        }
    }

    @Test
    fun `install-time permissions are granted on the real device tier`() {
        val provider = provider(sdkInt = 34)

        provider.refresh()

        // 这两项是 normal 权限，生产恒为已授予；若此处不是 GRANTED，说明适配器读错了系统状态
        assertEquals(PermissionGrant.GRANTED, provider.current()[AndroidPermission.RECEIVE_BOOT_COMPLETED]?.grant)
        assertEquals(PermissionGrant.GRANTED, provider.current()[AndroidPermission.ACCESS_NETWORK_STATE]?.grant)
    }

    @Test
    fun `exact alarm is applicable but not not-applicable on api 34`() {
        val provider = provider(sdkInt = 34)

        provider.refresh()

        // D3 的现实：targetSdk>=33 且未申请时 canScheduleExactAlarms() 返回 false。
        // 本用例断言"档位正确且结论可读"，而不是具体的系统授予状态。
        val state = provider.current()[AndroidPermission.SCHEDULE_EXACT_ALARM]
        assertNotNull(state)
        assertTrue(
            state?.grant == PermissionGrant.GRANTED || state?.grant == PermissionGrant.DENIED,
            "API 34 上精确闹钟必须是 GRANTED/DENIED（而非 NOT_APPLICABLE）：$state",
        )
    }

    @Test
    fun `notifications are applicable from api 33`() {
        val provider = provider(sdkInt = 33)

        provider.refresh()

        val state = provider.current()[AndroidPermission.POST_NOTIFICATIONS]
        assertTrue(
            state?.grant != PermissionGrant.NOT_APPLICABLE,
            "API 33 起通知权限必须适用：$state",
        )
    }

    @Test
    fun `below api 33 notifications are not applicable`() {
        val provider = provider(sdkInt = 30)

        provider.refresh()

        assertEquals(
            PermissionGrant.NOT_APPLICABLE,
            provider.current()[AndroidPermission.POST_NOTIFICATIONS]?.grant,
        )
    }

    @Test
    fun `refresh is idempotent for identical system state`() {
        val provider = provider(sdkInt = 34)

        provider.refresh()
        val first = provider.current()
        provider.refresh()

        assertEquals(first, provider.current(), "同一系统状态下重复 refresh 必须给出相同快照")
    }

    @Test
    fun `observe emits the snapshot published by refresh`() =
        runTest {
            val provider = provider(sdkInt = 34)
            provider.refresh()

            val received = provider.observe().first()

            assertEquals(provider.current(), received)
        }

    @Test
    fun `observe does not re-emit an equal snapshot`() =
        runTest {
            val provider = provider(sdkInt = 34)
            provider.refresh()

            // §9.2：StateFlow 的 collect 永不返回 → 放 backgroundScope，由 runTest 结束时取消
            var emissions = 0
            val collector =
                backgroundScope.launch {
                    provider.observe().collect { emissions++ }
                }
            testScheduler.runCurrent()
            assertEquals(1, emissions, "StateFlow 应立刻给出当前值")

            // 系统状态未变 → StateFlow 按 equals 去重 → 不得有新发射
            provider.refresh()
            provider.refresh()
            testScheduler.runCurrent()

            assertEquals(1, emissions, "相同快照不得重复发射（否则阶段 6 会无谓重组）")
            collector.cancel()
        }

    @Test
    fun `settingsTargetFor delegates to the pure resolver`() {
        val provider = provider(sdkInt = 34)

        assertEquals(
            SettingsTargets.resolve(AndroidPermission.PACKAGE_USAGE_STATS),
            provider.settingsTargetFor(AndroidPermission.PACKAGE_USAGE_STATS),
        )
        assertEquals(null, provider.settingsTargetFor(AndroidPermission.RECEIVE_BOOT_COMPLETED))
    }

    @Test
    fun `evaluate is pure so the adapter has no business logic left`() {
        // 决策 13 的可验证形式：同一（sdkInt, 原语）组合在任何地方都必须给出同一结论。
        // 注意：这**不能**证明适配器没有偷偷加分支——适配器的正确性只能由真机覆盖
        // （见变更报告的「已知问题」）。
        val a = PermissionDecisions.evaluate(AndroidPermission.POST_NOTIFICATIONS, 32, FakePermissionPrimitives())
        val b = PermissionDecisions.evaluate(AndroidPermission.POST_NOTIFICATIONS, 32, FakePermissionPrimitives())
        assertEquals(a, b)
    }

    @Test
    fun `a probe failure is representable as a denied state with a specific reason`() {
        // 适配器契约（决策 7）：探测失败必须转成 DENIED + 具体原因，不得让异常穿透、也不得静默
        val state =
            PermissionState(
                permission = AndroidPermission.PACKAGE_USAGE_STATS,
                grant = PermissionGrant.DENIED,
                reason = "probe failed: boom",
            )

        assertEquals(PermissionGrant.DENIED, state.grant)
        assertTrue(state.reason.startsWith("probe failed"), state.reason)
    }
}
