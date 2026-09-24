package com.rootflow.domain.event

import com.rootflow.data.event.FakePermissionPrimitives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PermissionDecisions] 单测（阶段 3c.1，`PROJECT_STATE.md` 清单 §D 的 4 档判定矩阵）。
 *
 * ## 本类为什么是 3c.1 单测的重心
 * "4 档版本分支"是本阶段**唯一**允许出现 `sdkInt` 判定的地方（决策 13）。
 * 全部 Android 调用都经 [PermissionPrimitives] 注入，因此这里可以在**纯 JVM** 下
 * 穷举 `sdkInt` × 5 权限的全矩阵——这是适配器（不进单测）正确性的**唯一替代证据**。
 */
class PermissionDecisionsTest {
    /** 档位分界点两侧都取到：26(下界) / 28 / 29 / 30 / 31 / 32 / 33 / 34 / 35。 */
    private val allTiers = listOf(26, 28, 29, 30, 31, 32, 33, 34, 35)

    @Test
    fun `tier boundaries are pinned to the documented api levels`() {
        assertEquals(29, PermissionDecisions.TIER_USAGE_STATS_MIN_SDK)
        assertEquals(31, PermissionDecisions.TIER_EXACT_ALARM_MIN_SDK)
        assertEquals(33, PermissionDecisions.TIER_NOTIFICATIONS_MIN_SDK)
    }

    @Test
    fun `tierMinSdk maps every requirement`() {
        assertEquals(0, PermissionDecisions.tierMinSdk(Requirement.INSTALL_TIME))
        assertEquals(29, PermissionDecisions.tierMinSdk(Requirement.USAGE_STATS))
        assertEquals(31, PermissionDecisions.tierMinSdk(Requirement.EXACT_ALARM))
        assertEquals(33, PermissionDecisions.tierMinSdk(Requirement.NOTIFICATIONS))
    }

    @Test
    fun `install-time permissions are granted on every tier`() {
        allTiers.forEach { sdk ->
            // 刻意让运行时权限查询返回 false：normal 权限在生产恒为已授予，
            // 未授予时必须如实报 DENIED（而不是被"安装即授予"的假设掩盖）
            val granted =
                PermissionDecisions.evaluate(
                    AndroidPermission.RECEIVE_BOOT_COMPLETED,
                    sdk,
                    FakePermissionPrimitives(
                        runtimeGranted = setOf(AndroidPermission.RECEIVE_BOOT_COMPLETED.permission),
                    ),
                )
            assertEquals(PermissionGrant.GRANTED, granted.grant, "sdk=$sdk 应为 GRANTED")

            val denied =
                PermissionDecisions.evaluate(
                    AndroidPermission.RECEIVE_BOOT_COMPLETED,
                    sdk,
                    FakePermissionPrimitives(),
                )
            assertEquals(PermissionGrant.DENIED, denied.grant, "sdk=$sdk 未授予应为 DENIED")
        }
    }

    @Test
    fun `notifications are not applicable before api 33 and runtime-gated from 33`() {
        listOf(26, 28, 29, 30, 31, 32).forEach { sdk ->
            val state =
                PermissionDecisions.evaluate(
                    AndroidPermission.POST_NOTIFICATIONS,
                    sdk,
                    FakePermissionPrimitives(),
                )
            assertEquals(PermissionGrant.NOT_APPLICABLE, state.grant, "sdk=$sdk 应为 NOT_APPLICABLE")
            assertTrue(
                state.reason.startsWith(PermissionDecisions.REASON_NOT_AVAILABLE_PREFIX),
                "原因必须说明档位不可用：${state.reason}",
            )
        }

        listOf(33, 34, 35).forEach { sdk ->
            assertEquals(
                PermissionGrant.GRANTED,
                PermissionDecisions
                    .evaluate(
                        AndroidPermission.POST_NOTIFICATIONS,
                        sdk,
                        FakePermissionPrimitives(runtimeGranted = setOf("android.permission.POST_NOTIFICATIONS")),
                    ).grant,
                "sdk=$sdk 已授予",
            )
            assertEquals(
                PermissionGrant.DENIED,
                PermissionDecisions
                    .evaluate(
                        AndroidPermission.POST_NOTIFICATIONS,
                        sdk,
                        FakePermissionPrimitives(),
                    ).grant,
                "sdk=$sdk 未授予",
            )
        }
    }

    @Test
    fun `exact alarm is not applicable before api 31 and checked from 31`() {
        listOf(26, 28, 29, 30).forEach { sdk ->
            val state =
                PermissionDecisions.evaluate(
                    AndroidPermission.SCHEDULE_EXACT_ALARM,
                    sdk,
                    FakePermissionPrimitives(exactAlarmAllowed = true),
                )
            assertEquals(PermissionGrant.NOT_APPLICABLE, state.grant, "sdk=$sdk 应为 NOT_APPLICABLE")
        }

        listOf(31, 32, 33, 35).forEach { sdk ->
            assertEquals(
                PermissionGrant.GRANTED,
                PermissionDecisions
                    .evaluate(
                        AndroidPermission.SCHEDULE_EXACT_ALARM,
                        sdk,
                        FakePermissionPrimitives(exactAlarmAllowed = true),
                    ).grant,
                "sdk=$sdk 可用",
            )
            assertEquals(
                PermissionGrant.DENIED,
                PermissionDecisions
                    .evaluate(
                        AndroidPermission.SCHEDULE_EXACT_ALARM,
                        sdk,
                        FakePermissionPrimitives(exactAlarmAllowed = false),
                    ).grant,
                "sdk=$sdk 不可用（targetSdk 34 的默认状态）",
            )
        }
    }

    @Test
    fun `usage stats uses app ops from api 29 and never asks below it`() {
        // 档 2+：走 AppOps
        listOf(29, 30, 31, 33, 35).forEach { sdk ->
            val primitives = FakePermissionPrimitives(usageStatsAllowed = true)
            val state = PermissionDecisions.evaluate(AndroidPermission.PACKAGE_USAGE_STATS, sdk, primitives)
            assertEquals(PermissionGrant.GRANTED, state.grant, "sdk=$sdk")
            assertEquals(1, primitives.usageStatsCalls, "sdk=$sdk 必须恰好问一次 AppOps")
        }

        // 档 1：**不得**调用 AppOps 通道（29 以下系统没有该方法）
        listOf(26, 27, 28).forEach { sdk ->
            val primitives = FakePermissionPrimitives()
            val state = PermissionDecisions.evaluate(AndroidPermission.PACKAGE_USAGE_STATS, sdk, primitives)
            assertEquals(PermissionGrant.DENIED, state.grant, "sdk=$sdk 无授权入口，必须 DENIED")
            assertEquals(
                0,
                primitives.usageStatsCalls,
                "sdk=$sdk 不得调用 AppOps 通道（API 29 起才存在）：$primitives",
            )
            assertEquals(
                PermissionDecisions.REASON_USAGE_STATS_NO_ENTRY,
                state.reason,
                "原因必须点明「没有授权入口」而不是笼统的未授予",
            )
        }
    }

    @Test
    fun `usage stats below 29 still honours a pre-granted platform permission`() {
        // API 28 及以下 PACKAGE_USAGE_STATS 曾是普通权限：已授予就应报 GRANTED，不得漏报
        val primitives =
            FakePermissionPrimitives(runtimeGranted = setOf(AndroidPermission.PACKAGE_USAGE_STATS.permission))
        val state = PermissionDecisions.evaluate(AndroidPermission.PACKAGE_USAGE_STATS, 28, primitives)

        assertEquals(PermissionGrant.GRANTED, state.grant)
        assertEquals(0, primitives.usageStatsCalls, "档 1 仍不得走 AppOps")
    }

    @Test
    fun `exact alarm primitive is never queried below api 31`() {
        listOf(26, 29, 30).forEach { sdk ->
            val primitives = FakePermissionPrimitives(exactAlarmAllowed = true)
            PermissionDecisions.evaluate(AndroidPermission.SCHEDULE_EXACT_ALARM, sdk, primitives)
            assertEquals(0, primitives.exactAlarmCalls, "sdk=$sdk 不得调用 canScheduleExactAlarms")
        }
    }

    @Test
    fun `battery optimization tier reflects the whitelist state and not the permission grant`() {
        // 阶段 5（需求 §7）：该权限**声明即授予**，判的必须是白名单状态。
        // 若误用 INSTALL_TIME 档，真机永远报 GRANTED —— 用户就永远看不到"去放行"的引导。
        val notWhitelisted =
            PermissionDecisions.evaluate(
                AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                35,
                FakePermissionPrimitives(ignoringBatteryOptimizations = false),
            )
        val whitelisted =
            PermissionDecisions.evaluate(
                AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                35,
                FakePermissionPrimitives(ignoringBatteryOptimizations = true),
            )

        assertEquals(PermissionGrant.DENIED, notWhitelisted.grant)
        assertEquals(PermissionGrant.GRANTED, whitelisted.grant)
        assertTrue(
            notWhitelisted.reason.contains(AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.permission),
            "未放行的原因必须点名到具体权限：${notWhitelisted.reason}",
        )
    }

    @Test
    fun `battery optimization is probed on every tier without sdk branches`() {
        // 该 API 自 Android 6 起存在，项目 minSdk = 26 ⇒ 任何档位都必须真的去问一次
        allTiers.forEach { sdk ->
            val primitives = FakePermissionPrimitives(ignoringBatteryOptimizations = true)
            PermissionDecisions.evaluate(
                AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                sdk,
                primitives,
            )
            assertEquals(1, primitives.batteryOptimizationCalls, "sdk=$sdk 必须恰好探测一次白名单状态")
        }
    }

    @Test
    fun `only the battery optimization tier touches the whitelist probe`() {
        // 其余权限不得顺带探测白名单（多一次系统调用，且会让"谁该跳设置页"的判据变模糊）
        val primitives =
            FakePermissionPrimitives(
                runtimeGranted = AndroidPermission.entries.map { it.permission }.toSet(),
                usageStatsAllowed = true,
                exactAlarmAllowed = true,
            )
        AndroidPermission.entries
            .filter { it.requirement != Requirement.BATTERY_OPTIMIZATION }
            .forEach { permission -> PermissionDecisions.evaluate(permission, 35, primitives) }

        assertEquals(0, primitives.batteryOptimizationCalls)
    }

    @Test
    fun `every evaluated state carries the same permission back`() {
        allTiers.forEach { sdk ->
            AndroidPermission.entries.forEach { permission ->
                val state = PermissionDecisions.evaluate(permission, sdk, FakePermissionPrimitives())
                assertEquals(permission, state.permission, "sdk=$sdk 的状态必须回指同一目录项")
                assertTrue(state.reason.isNotBlank(), "sdk=$sdk 的原因不得为空（决策 7）")
            }
        }
    }

    @Test
    fun `grant is never collapsed into a boolean`() {
        // 三态必须可区分：同一权限在"档位不可用"与"被拒"下结论不同（决策 D 的硬要求）
        val notApplicable =
            PermissionDecisions.evaluate(AndroidPermission.POST_NOTIFICATIONS, 30, FakePermissionPrimitives())
        val denied =
            PermissionDecisions.evaluate(AndroidPermission.POST_NOTIFICATIONS, 33, FakePermissionPrimitives())

        assertEquals(PermissionGrant.NOT_APPLICABLE, notApplicable.grant)
        assertEquals(PermissionGrant.DENIED, denied.grant)
        assertTrue(
            notApplicable.grant != denied.grant,
            "NOT_APPLICABLE 与 DENIED 必须是不同结论，否则阶段 6 会给用户一个跳不动的授权入口",
        )
    }

    @Test
    fun `reasons are specific enough to be shown to the user`() {
        val granted =
            PermissionDecisions.evaluate(
                AndroidPermission.ACCESS_NETWORK_STATE,
                34,
                FakePermissionPrimitives(runtimeGranted = setOf(AndroidPermission.ACCESS_NETWORK_STATE.permission)),
            )
        assertEquals(PermissionDecisions.REASON_GRANTED_INSTALL_TIME, granted.reason)

        val denied =
            PermissionDecisions.evaluate(
                AndroidPermission.ACCESS_NETWORK_STATE,
                34,
                FakePermissionPrimitives(),
            )
        assertTrue(
            denied.reason.contains(AndroidPermission.ACCESS_NETWORK_STATE.permission),
            "未授予的原因必须包含权限字符串：${denied.reason}",
        )
    }
}
