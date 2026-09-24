package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SettingsTargets] 单测（阶段 3c.1，**决策 9**）。
 *
 * ## 为什么这些断言必须存在
 * 决策 9 把「跳哪个设置页」从 Android `Intent` 里剥离成纯描述符，**唯一目的就是让这条判定可测**。
 * 若这里只断言"非 null"，那么把 `USAGE_ACCESS_SETTINGS` 写成 `APP_NOTIFICATION_SETTINGS`
 * 这类错误就要等真机上用户点了没反应才发现。
 */
class SettingsTargetsTest {
    @Test
    fun `setting action literals are pinned`() {
        // 这些字符串是平台契约，拼错不会编译失败、只会静默跳到错误的页面
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", SettingsTargets.ACTION_USAGE_ACCESS_SETTINGS)
        assertEquals(
            "android.settings.APP_NOTIFICATION_SETTINGS",
            SettingsTargets.ACTION_APP_NOTIFICATION_SETTINGS,
        )
        assertEquals(
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            SettingsTargets.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        )
        // 阶段 5（裁定 ③）：只给**列表页**，不给"直接请求忽略"那个 action
        assertEquals(
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            SettingsTargets.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
    }

    @Test
    fun `usage stats resolves to the usage access settings page`() {
        val target = SettingsTargets.resolve(AndroidPermission.PACKAGE_USAGE_STATS)

        assertEquals(SettingsTargets.ACTION_USAGE_ACCESS_SETTINGS, target?.action)
        // 该页面不接受 package: 形式的数据 URI
        assertEquals(false, target?.packageUri)
        assertTrue(target?.reason?.isNotBlank() == true)
    }

    @Test
    fun `notifications resolve to the app notification settings page`() {
        val target = SettingsTargets.resolve(AndroidPermission.POST_NOTIFICATIONS)

        assertEquals(SettingsTargets.ACTION_APP_NOTIFICATION_SETTINGS, target?.action)
        assertEquals(true, target?.packageUri, "通知设置页只列本应用，必须带 package: URI")
    }

    @Test
    fun `exact alarm resolves to the request page`() {
        val target = SettingsTargets.resolve(AndroidPermission.SCHEDULE_EXACT_ALARM)

        assertEquals(SettingsTargets.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, target?.action)
        assertEquals(true, target?.packageUri)
    }

    @Test
    fun `battery optimization resolves to the settings list and never to the direct request action`() {
        val target = SettingsTargets.resolve(AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

        assertEquals(SettingsTargets.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, target?.action)
        assertEquals(false, target?.packageUri, "列表页不接受 package: URI")
        assertTrue(target?.reason?.isNotBlank() == true)
        // ★ 裁定 ③ 的核心断言：**不得**出现"直接请求忽略电池优化"的那个 action
        assertTrue(
            target?.action != "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "只允许跳设置列表页，不得直接请求白名单豁免",
        )
    }

    @Test
    fun `install-time permissions have no settings page`() {
        // 安装即授予：系统里没有「授予它」的界面，返回 null 而不是给一个跳不动的 action
        assertNull(SettingsTargets.resolve(AndroidPermission.RECEIVE_BOOT_COMPLETED))
        assertNull(SettingsTargets.resolve(AndroidPermission.ACCESS_NETWORK_STATE))
        // 阶段 5：两条前台服务权限同属"安装即授予"，无设置页
        assertNull(SettingsTargets.resolve(AndroidPermission.FOREGROUND_SERVICE))
        assertNull(SettingsTargets.resolve(AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE))
    }

    @Test
    fun `every permission is classified`() {
        AndroidPermission.entries.forEach { permission ->
            val resolvable = SettingsTargets.resolve(permission) != null
            assertEquals(
                permission.requirement != Requirement.INSTALL_TIME,
                resolvable,
                "$permission 的可跳转性必须与它的档位一致",
            )
        }
    }

    @Test
    fun `exact alarm settings page only exists from api 31`() {
        assertEquals(false, SettingsTargets.isAvailableOnSdk(AndroidPermission.SCHEDULE_EXACT_ALARM, 30))
        assertEquals(true, SettingsTargets.isAvailableOnSdk(AndroidPermission.SCHEDULE_EXACT_ALARM, 31))
        assertEquals(true, SettingsTargets.isAvailableOnSdk(AndroidPermission.SCHEDULE_EXACT_ALARM, 35))
    }

    @Test
    fun `usage stats and notification pages exist on every supported tier`() {
        listOf(26, 29, 31, 33, 35).forEach { sdk ->
            assertEquals(
                true,
                SettingsTargets.isAvailableOnSdk(AndroidPermission.PACKAGE_USAGE_STATS, sdk),
                "sdk=$sdk",
            )
            assertEquals(
                true,
                SettingsTargets.isAvailableOnSdk(AndroidPermission.POST_NOTIFICATIONS, sdk),
                "sdk=$sdk",
            )
        }
    }

    @Test
    fun `install-time permissions never report an available settings page`() {
        assertEquals(false, SettingsTargets.isAvailableOnSdk(AndroidPermission.RECEIVE_BOOT_COMPLETED, 35))
        assertEquals(false, SettingsTargets.isAvailableOnSdk(AndroidPermission.ACCESS_NETWORK_STATE, 35))
    }

    @Test
    fun `resolve is a pure function`() {
        // 同一输入必须给出**等价**描述符（data class 的 equals），否则 UI 侧 diff 会误判
        assertEquals(
            SettingsTargets.resolve(AndroidPermission.PACKAGE_USAGE_STATS),
            SettingsTargets.resolve(AndroidPermission.PACKAGE_USAGE_STATS),
        )
        assertEquals(
            SettingsTargets.resolve(AndroidPermission.SCHEDULE_EXACT_ALARM),
            SettingsTargets.resolve(AndroidPermission.SCHEDULE_EXACT_ALARM),
        )
    }
}
