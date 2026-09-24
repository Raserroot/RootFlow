package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `AndroidPermission` 目录 ↔ `AndroidManifest.xml` 一致性单测（阶段 3c.1，**决策 14 强制**）。
 *
 * ## 为什么必须有这个测试
 * [AndroidPermission] 是**权限目录的唯一真相源之一**，而清单是另一处。二者漂移的后果是
 * **静默的**：目录里多一项 → `PermissionStatusProvider` 报"缺权限"但用户无处授予；
 * 清单里少一项 → 系统永远不授予，事件源被权限门永久挡下。
 * 两种都不会编译失败，只会在真机上表现为"某个事件永不触发"。
 *
 * ## 覆盖方式
 * 直接读 `app/src/main/AndroidManifest.xml`（工作目录 = 模块目录，Gradle 单测的默认 cwd），
 * 解析全部 `<uses-permission android:name="...">`，与本目录逐项比对。
 * 同时钉死**每一条权限字符串字面量**——它是与系统交互的契约，拼错不会有任何编译期反馈。
 */
class AndroidPermissionCatalogTest {
    @Test
    fun `permission strings are pinned verbatim`() {
        assertEquals(
            "android.permission.RECEIVE_BOOT_COMPLETED",
            AndroidPermission.RECEIVE_BOOT_COMPLETED.permission,
        )
        assertEquals("android.permission.ACCESS_NETWORK_STATE", AndroidPermission.ACCESS_NETWORK_STATE.permission)
        assertEquals("android.permission.POST_NOTIFICATIONS", AndroidPermission.POST_NOTIFICATIONS.permission)
        assertEquals("android.permission.SCHEDULE_EXACT_ALARM", AndroidPermission.SCHEDULE_EXACT_ALARM.permission)
        assertEquals("android.permission.PACKAGE_USAGE_STATS", AndroidPermission.PACKAGE_USAGE_STATS.permission)
        // 阶段 5：需求 §7 前台服务与保活引导
        assertEquals("android.permission.FOREGROUND_SERVICE", AndroidPermission.FOREGROUND_SERVICE.permission)
        assertEquals(
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE.permission,
        )
        assertEquals(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.permission,
        )
    }

    @Test
    fun `the catalog covers the eight permissions of decision 10 plus stage 5`() {
        // 3c.1 的决策 10 建了 5 项；阶段 5（需求 §7）追加 3 项：
        // FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
        // 后三项**必须**进目录：只写清单会让下面的「清单 ⊆ 目录」断言失败，
        // 而那条断言正是防"清单里声明了、UI 却永远报不出来"的护栏。
        assertEquals(8, AndroidPermission.entries.size, "目录项数变化必须同步更新本断言与清单")
        assertEquals(
            setOf(
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            ),
            AndroidPermission.entries.map { it.permission }.toSet(),
        )
    }

    @Test
    fun `each permission declares a requirement tier`() {
        assertEquals(Requirement.INSTALL_TIME, AndroidPermission.RECEIVE_BOOT_COMPLETED.requirement)
        assertEquals(Requirement.INSTALL_TIME, AndroidPermission.ACCESS_NETWORK_STATE.requirement)
        assertEquals(Requirement.NOTIFICATIONS, AndroidPermission.POST_NOTIFICATIONS.requirement)
        assertEquals(Requirement.EXACT_ALARM, AndroidPermission.SCHEDULE_EXACT_ALARM.requirement)
        assertEquals(Requirement.USAGE_STATS, AndroidPermission.PACKAGE_USAGE_STATS.requirement)
        // 阶段 5：两条 FGS 权限是 normal 权限（安装即授予）；
        // 电池优化判的是**白名单状态**，必须走独立档位（否则会被误判成 GRANTED）
        assertEquals(Requirement.INSTALL_TIME, AndroidPermission.FOREGROUND_SERVICE.requirement)
        assertEquals(Requirement.INSTALL_TIME, AndroidPermission.FOREGROUND_SERVICE_SPECIAL_USE.requirement)
        assertEquals(
            Requirement.BATTERY_OPTIMIZATION,
            AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.requirement,
        )
    }

    @Test
    fun `fromPermission round trips every entry and rejects unknown strings`() {
        AndroidPermission.entries.forEach { permission ->
            assertEquals(permission, AndroidPermission.fromPermission(permission.permission))
        }
        assertEquals(null, AndroidPermission.fromPermission("android.permission.NOT_A_REAL_ONE"))
    }

    @Test
    fun `catalog and AndroidManifest declare the same permission set`() {
        val manifest = readManifest()
        assumeTrue(manifest != null, "找不到 AndroidManifest.xml，跳过（cwd=${System.getProperty("user.dir")}）")
        val declared = parseUsePermissions(manifest.orEmpty())

        val catalog = AndroidPermission.entries.map { it.permission }.toSet()
        assertEquals(
            catalog,
            declared.intersect(catalog),
            "目录里的每一项都必须出现在清单里（否则系统永不授予 → 事件源被永久挡下）",
        )
        assertEquals(
            declared,
            catalog.intersect(declared),
            "清单里的每一项也必须登记进目录（否则该项状态无从上报）",
        )
    }

    @Test
    fun `manifest declares the two manifest-registered receivers and no screen receiver`() {
        val manifest = readManifest()
        assumeTrue(manifest != null, "找不到 AndroidManifest.xml，跳过")
        val text = manifest.orEmpty()

        assertTrue(text.contains("PowerEventReceiver"), "清单必须声明 PowerEventReceiver")
        assertTrue(text.contains("BatteryEventReceiver"), "清单必须声明 BatteryEventReceiver")
        assertTrue(text.contains("ACTION_POWER_CONNECTED"), "PowerEventReceiver 需声明接电 action")
        assertTrue(text.contains("ACTION_POWER_DISCONNECTED"), "PowerEventReceiver 需声明断电 action")
        assertTrue(text.contains("BATTERY_LOW"), "BatteryEventReceiver 需声明低电量 action")
        assertTrue(text.contains("BATTERY_OKAY"), "BatteryEventReceiver 需声明恢复 action（决策 3）")

        // screen_on / screen_off / unlock 只能动态注册：Android 8.0 起隐式广播禁令挡住了清单投递。
        // 若有人把它们加进清单，这里必须失败——那种"看起来更省事"的写法在真机上永远收不到。
        assertTrue(
            !text.contains("SCREEN_ON") && !text.contains("SCREEN_OFF") && !text.contains("USER_PRESENT"),
            "screen_on/screen_off/unlock 不得写进清单（只能动态注册）",
        )
    }

    @Test
    fun `manifest declares the foreground service of stage 5 with specialUse type`() {
        val manifest = readManifest()
        assumeTrue(manifest != null, "找不到 AndroidManifest.xml，跳过")
        val text = manifest.orEmpty()
        val block = serviceBlock(text, ".service.KeepAliveService")

        assertTrue(block != null, "清单必须声明 KeepAliveService（阶段 5 的前台服务）")
        val service = block.orEmpty()

        // specialUse 是 API 34+ 的硬要求：缺了它，startForeground 在 Android 14 上抛异常。
        // 平台原文（attrs_manifest.xml）：<flag name="specialUse" value="0x40000000" />
        assertTrue(
            service.contains("""android:foregroundServiceType="specialUse""""),
            "前台服务必须声明 foregroundServiceType=specialUse，实际块=[$service]",
        )
        // 服务只由本应用自己的 Activity/通知拉起，不得对外导出
        assertTrue(service.contains("""android:exported="false""""), "前台服务不得导出")
        // 从最近任务划掉 App 时保留服务（START_STICKY 语义的补充；见服务类 KDoc）
        assertTrue(
            service.contains("""android:stopWithTask="false""""),
            "stopWithTask 必须显式为 false，否则划掉任务会被连带停掉",
        )
        // 两者成对出现才有意义：specialUse 需要专门的权限，权限声明必须在清单里
        assertTrue(text.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(text.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    /** 读取模块根目录下的清单；读不到返回 `null`（由 `assumeTrue` 守卫跳过）。 */
    private fun readManifest(): String? {
        val candidates =
            listOf(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
            )
        return candidates
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
            ?.readText()
    }

    /** 粗暴但足够稳的解析：清单里 `<uses-permission android:name="X" />` 的 X。 */
    private fun parseUsePermissions(manifest: String): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

    /**
     * 取某个 `<service android:name="…">` 的整块原文。
     *
     * 与 `AlarmFireReceiverTest` 的接收器块解析同款手法：**只看这一段**，
     * 避免"别的服务声明了 exported=false"把断言蒙过去。
     *
     * **必须兼容自闭合写法** `<service … />`（本清单用的就是这种）：
     * 只在找不到 `</service>` 时退化为"到第一个 `>` 为止"，
     * 否则会返回整份清单的剩余部分，让断言失去靶点。
     */
    private fun serviceBlock(
        manifest: String,
        name: String,
    ): String? {
        val start = manifest.indexOf("""android:name="$name"""")
        if (start < 0) return null
        val open = manifest.lastIndexOf("<service", start)
        val close = manifest.indexOf("</service>", start)
        if (open < 0) return null
        val end = if (close >= 0) close else manifest.indexOf('>', start).takeIf { it >= 0 }?.plus(1) ?: return null
        return manifest.substring(open, end)
    }
}
