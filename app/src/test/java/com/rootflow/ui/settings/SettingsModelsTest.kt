package com.rootflow.ui.settings

import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.event.RestoreMode
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.glass.GlassPolicy
import com.rootflow.domain.glass.GlassTier
import com.rootflow.domain.residue.ResidueCleanResult
import com.rootflow.domain.residue.ResidueReport
import com.rootflow.domain.residue.ResidueScan
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.settings.LogRetention
import com.rootflow.domain.settings.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 设置页纯投影（阶段 6d）。
 *
 * ## 为什么这一层必须穷举
 * 决策 B：本项目**没有** UI 自动化测试，因此"设置页该显示什么"完全由本文件的断言兜底。
 * 尤其是 [SettingsProjections.tripReasonLabel]：`safemode.flag` 里的成因键决定了用户
 * 看到的排查方向（超时 / 连续失败 / bootloop 的处置完全不同），漏一个分支就等于
 * 那一类熔断在 UI 上"没有解释"。
 */
class SettingsModelsTest {
    // ── 外观 ─────────────────────────────────────────────────────────────

    @Test
    fun `液态玻璃文案穷举三档与两种关闭原因`() {
        fun text(
            enabled: Boolean,
            tier: GlassTier,
            apiLevel: Int,
            lowRam: Boolean = false,
        ) = SettingsProjections.glassStatusText(
            enabled = enabled,
            tier = tier,
            apiLevel = apiLevel,
            lowRam = lowRam,
        )

        // 用户关掉：必须说清"这不是设备做不到，是你关的"
        assertEquals("已关闭（底栏用 Haze 毛玻璃）", text(enabled = false, tier = GlassTier.HAZE, apiLevel = 35))
        // 低端机：需求 §6 的规则必须被说出来
        assertEquals(
            "本机为低端设备：按需求默认不启用折射",
            text(enabled = true, tier = GlassTier.BLUR_ONLY, apiLevel = 35, lowRam = true),
        )
        // Tier 1
        assertEquals(
            "已启用 · AGSL 折射",
            text(enabled = true, tier = GlassTier.REFRACTION, apiLevel = 35),
        )
        // Tier 2 的两种成因要分开说：31–32 是系统版本，33+ 是设备能力
        assertEquals(
            "本系统版本（API 32）无 AGSL：降级为模糊 + 描边",
            text(enabled = true, tier = GlassTier.BLUR_ONLY, apiLevel = 32),
        )
        assertEquals(
            "设备图形能力不支持，降级为模糊 + 描边",
            text(enabled = true, tier = GlassTier.BLUR_ONLY, apiLevel = 35),
        )
        // Tier 3 的 30 以下
        assertEquals(
            "本系统版本（API 30）不支持，使用 Haze 材质",
            text(enabled = true, tier = GlassTier.HAZE, apiLevel = 30),
        )
    }

    @Test
    fun `液态玻璃文案与 GlassPolicy 的判定一致（同一套真相源）`() {
        // 本投影**不得**自己重算档位：它只翻译 GlassPolicy 的结论。
        // 这条断言以 33 为界把两边的结论绑在一起，防止将来有人在文案里写第二套判定。
        val tierAt35 =
            GlassPolicy.decide(
                apiLevel = 35,
                graphicsCapabilityOk = true,
                liquidGlassEnabled = true,
                lowRam = false,
                blurSupported = true,
            )
        assertEquals(GlassTier.REFRACTION, tierAt35)
        assertTrue(
            SettingsProjections
                .glassStatusText(enabled = true, tier = tierAt35, apiLevel = 35, lowRam = false)
                .contains("AGSL"),
        )
    }

    @Test
    fun `主题三选一覆盖 ThemeMode 全部取值且标签不重复`() {
        val modes = SettingsProjections.THEME_OPTIONS.map { it.mode }
        assertEquals(ThemeMode.entries.toList(), modes, "选项顺序即 UI 顺序，且必须覆盖全部取值")
        assertEquals(
            modes.size,
            SettingsProjections.THEME_OPTIONS
                .map { it.label }
                .distinct()
                .size,
            "标签不得重复（否则用户无法区分哪一个是当前项）",
        )
    }

    @Test
    fun `主题标签穷举`() {
        assertEquals("跟随系统", SettingsProjections.themeLabel(ThemeMode.SYSTEM))
        assertEquals("浅色", SettingsProjections.themeLabel(ThemeMode.LIGHT))
        assertEquals("深色", SettingsProjections.themeLabel(ThemeMode.DARK))
    }

    @Test
    fun `毛玻璃文案保持三态且不折叠成布尔`() {
        // 用户明确开 + API 支持
        assertEquals(
            "已开启",
            SettingsProjections.blurStatusText(
                userSetting = true,
                effective = true,
                isLowRamDevice = false,
                apiLevel = 35,
            ),
        )
        // 用户明确开 + API 不支持（硬门：不能说"已开启"，那是自欺）
        assertEquals(
            "本系统版本不支持模糊（回落半透明纯色）",
            SettingsProjections.blurStatusText(
                userSetting = true,
                effective = false,
                isLowRamDevice = false,
                apiLevel = 30,
            ),
        )
        // 用户明确关
        assertEquals(
            "已关闭（使用半透明纯色）",
            SettingsProjections.blurStatusText(
                userSetting = false,
                effective = false,
                isLowRamDevice = false,
                apiLevel = 35,
            ),
        )
        // 从未选择 + 设备判定为开
        assertEquals(
            "跟随设备：已开启",
            SettingsProjections.blurStatusText(
                userSetting = null,
                effective = true,
                isLowRamDevice = false,
                apiLevel = 35,
            ),
        )
        // 从未选择 + 低端机默认关（需求 §6 的那条规则必须说出来）
        assertEquals(
            "跟随设备：按低端机默认关闭",
            SettingsProjections.blurStatusText(
                userSetting = null,
                effective = false,
                isLowRamDevice = true,
                apiLevel = 35,
            ),
        )
        // 从未选择 + 非低端机但 API 不足
        assertEquals(
            "跟随设备：已关闭",
            SettingsProjections.blurStatusText(
                userSetting = null,
                effective = false,
                isLowRamDevice = false,
                apiLevel = 30,
            ),
        )
    }

    // ── Root 驻留 ────────────────────────────────────────────────────────

    @Test
    fun `服务状态文案如实区分 Idle 与 Running`() {
        assertEquals("未运行", SettingsProjections.serviceStatusText(ForegroundState.Idle))
        assertEquals(
            "运行中 · 事件源 6/7",
            SettingsProjections.serviceStatusText(ForegroundState.Running(enabled = 6, total = 7, safeMode = false)),
        )
    }

    @Test
    fun `权限三态文案互不相同`() {
        val texts =
            listOf(
                SettingsProjections.permissionStatusText(PermissionGrant.GRANTED),
                SettingsProjections.permissionStatusText(PermissionGrant.DENIED),
                SettingsProjections.permissionStatusText(PermissionGrant.NOT_APPLICABLE),
            )
        assertEquals(3, texts.distinct().size, "「不适用」与「未授予」必须可区分（决策 D：不得折叠）")
        assertEquals(listOf("已授予", "未授予", "不适用"), texts)
    }

    @Test
    fun `每一项权限都有标签且总览按目录顺序全列`() {
        val rows = SettingsProjections.permissionRows(states = emptyMap(), sdkInt = 35)
        assertEquals(AndroidPermission.entries.size, rows.size, "必须全列（权限总览的作用就是如实）")
        assertEquals(AndroidPermission.entries.toList(), rows.map { it.permission })
        assertTrue(rows.none { it.label.isBlank() }, "每一项都必须有中文标签")
        assertTrue(rows.all { it.statusText == "未探测" }, "没有快照时必须说「未探测」，不得编造状态")
    }

    @Test
    fun `安装即授予的权限不给跳转按钮`() {
        val rows = SettingsProjections.permissionRows(states = emptyMap(), sdkInt = 35).associateBy { it.permission }
        assertFalse(rows.getValue(AndroidPermission.RECEIVE_BOOT_COMPLETED).canOpenSettings)
        assertFalse(rows.getValue(AndroidPermission.FOREGROUND_SERVICE).canOpenSettings)
        assertTrue(rows.getValue(AndroidPermission.POST_NOTIFICATIONS).canOpenSettings)
        assertTrue(rows.getValue(AndroidPermission.PACKAGE_USAGE_STATS).canOpenSettings)
        assertTrue(rows.getValue(AndroidPermission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).canOpenSettings)
    }

    @Test
    fun `精确闹钟的跳转按 API 档位开关`() {
        val at30 = SettingsProjections.permissionRows(emptyMap(), sdkInt = 30).associateBy { it.permission }
        val at31 = SettingsProjections.permissionRows(emptyMap(), sdkInt = 31).associateBy { it.permission }
        assertFalse(at30.getValue(AndroidPermission.SCHEDULE_EXACT_ALARM).canOpenSettings, "API 30 没有该设置页")
        assertTrue(at31.getValue(AndroidPermission.SCHEDULE_EXACT_ALARM).canOpenSettings)
    }

    @Test
    fun `待授权清单只包含未授予且有可跳页的项`() {
        val states =
            mapOf(
                AndroidPermission.POST_NOTIFICATIONS to
                    PermissionState(
                        AndroidPermission.POST_NOTIFICATIONS,
                        PermissionGrant.DENIED,
                        "runtime permission not granted",
                    ),
                AndroidPermission.PACKAGE_USAGE_STATS to
                    PermissionState(AndroidPermission.PACKAGE_USAGE_STATS, PermissionGrant.GRANTED, "GRANTED (appops)"),
                AndroidPermission.RECEIVE_BOOT_COMPLETED to
                    PermissionState(
                        AndroidPermission.RECEIVE_BOOT_COMPLETED,
                        PermissionGrant.NOT_APPLICABLE,
                        "not applicable",
                    ),
                AndroidPermission.SCHEDULE_EXACT_ALARM to
                    PermissionState(
                        AndroidPermission.SCHEDULE_EXACT_ALARM,
                        PermissionGrant.DENIED,
                        "special permission",
                    ),
            )
        val rows = SettingsProjections.permissionRows(states, sdkInt = 35)
        val pending = SettingsProjections.pendingPermissions(rows).map { it.permission }

        assertTrue(pending.contains(AndroidPermission.POST_NOTIFICATIONS))
        assertFalse(pending.contains(AndroidPermission.PACKAGE_USAGE_STATS), "已授予的不进清单")
        assertFalse(pending.contains(AndroidPermission.RECEIVE_BOOT_COMPLETED), "不适用 ≠ 未授予，且无处可授")
        assertTrue(pending.contains(AndroidPermission.SCHEDULE_EXACT_ALARM), "API 31+ 有该设置页 ⇒ 应进清单")

        val at30 =
            SettingsProjections
                .pendingPermissions(
                    SettingsProjections.permissionRows(states, sdkInt = 30),
                ).map { it.permission }
        assertFalse(at30.contains(AndroidPermission.SCHEDULE_EXACT_ALARM), "API 30 无该页 ⇒ 不该引导")
    }

    // ── 安全熔断 ─────────────────────────────────────────────────────────

    @Test
    fun `熔断成因文案覆盖全部 8 种且互不相同`() {
        val reasons =
            listOf(
                TripReason.ScriptTimeout(scriptId = 1, timeoutMillis = 5_000),
                TripReason.ConsecutiveFailures(scriptId = 1, consecutive = 3),
                TripReason.FailureStorm(windowFailures = 20, windowMillis = 600_000),
                TripReason.RootUnresponsive(elapsedMillis = 10_000),
                TripReason.HighFrequencyStarts(scriptId = 1, startsInWindow = 20, windowMillis = 60_000),
                TripReason.Manual,
                TripReason.Bootloop(crashes = 3),
                TripReason.ExternalSafeMode(source = "persist.sys.safemode"),
            )
        assertEquals(
            TripReason.ALL_KEYS.toSet(),
            reasons.map { it.reasonKey }.toSet(),
            "本用例必须覆盖 ALL_KEYS 的全部键（漏一个 = 那一类熔断在 UI 上没有解释）",
        )
        val labels = reasons.map { SettingsProjections.tripReasonLabel(it) }
        assertEquals(labels.size, labels.distinct().size, "文案必须互不相同（否则用户分不清是哪一类）")
        assertTrue(labels.none { it.startsWith("未知成因") }, "已建模的成因不得落进兜底分支")
    }

    // ── 日志（阶段 6d） ──────────────────────────────────────────────────

    @Test
    fun `保留档位直接来自 LogRetention 且标签不重复`() {
        assertEquals(
            LogRetention.ALLOWED_DAYS,
            SettingsProjections.LOG_RETENTION_OPTIONS.map { it.days },
            "档位的唯一真相源是 LogRetention —— 这里不得另立一份",
        )
        val labels = SettingsProjections.LOG_RETENTION_OPTIONS.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `保留说明点明清理对象是运行记录`() {
        val text = SettingsProjections.retentionSummaryText(14)
        assertTrue(text.contains("14 天"))
        assertTrue(
            text.contains("运行记录"),
            "需求 §3.2 指的是可选文件日志（未实现）⇒ 必须说清实际作用在运行历史上：$text",
        )
    }

    @Test
    fun `条数未知时说读取中而不是 0`() {
        assertEquals("读取中…", SettingsProjections.runCountText(null))
        assertEquals("当前 12 条运行记录", SettingsProjections.runCountText(12))
        assertEquals("当前 0 条运行记录", SettingsProjections.runCountText(0), "0 是「确实没有」，要如实显示")
    }

    @Test
    fun `清理结果三分支互不相同且 0 条不说失败`() {
        val none = SettingsProjections.cleanupResultText(deleted = 0, remaining = 5)
        val some = SettingsProjections.cleanupResultText(deleted = 3, remaining = 2)
        val someUnknown = SettingsProjections.cleanupResultText(deleted = 3, remaining = null)

        assertTrue(none.contains("无需清理"))
        assertFalse(none.contains("失败"), "「没有可清理的」不是失败（同 6c 的「部分成功」纪律）")
        assertTrue(some.contains("已清理 3 条"))
        assertTrue(some.contains("剩余 2 条"))
        assertNotEquals(some, someUnknown, "条数读不到时文案要退化，不能编一个剩余数")
    }

    // ── 卸载残留（需求 §8） ──────────────────────────────────────────────

    @Test
    fun `残留摘要四态互不相同`() {
        val unknown = SettingsProjections.residueSummaryText(null)
        val unavailable = SettingsProjections.residueSummaryText(ResidueScan.Unavailable("root 通道不可用"))
        val clean = SettingsProjections.residueSummaryText(ResidueScan.Ok(ResidueReport(emptyList(), 0)))
        val dirty =
            SettingsProjections.residueSummaryText(
                ResidueScan.Ok(ResidueReport(orphanScriptDirs = listOf(6L, 9L), orphanTriggerRows = 1)),
            )

        assertEquals("读取中…", unknown)
        assertTrue(unavailable.contains("扫描不可用"), "不可用要说原因，不得显示成「没有残留」")
        assertEquals("没有残留", clean)
        assertTrue(dirty.contains("3 项残留"))
        assertTrue(dirty.contains("目录 2 个"))
        assertTrue(dirty.contains("触发器行 1 行"))
        assertEquals(4, listOf(unknown, unavailable, clean, dirty).distinct().size, "四态必须可区分")
    }

    @Test
    fun `残留清理结果三分支 0 项不说失败`() {
        val nothing = SettingsProjections.residueCleanResultText(ResidueCleanResult(emptyList(), 0, emptyList()))
        val all =
            SettingsProjections.residueCleanResultText(
                ResidueCleanResult(removedScriptDirs = listOf(6L), removedTriggerRows = 2, failures = emptyList()),
            )
        val partial =
            SettingsProjections.residueCleanResultText(
                ResidueCleanResult(
                    removedScriptDirs = listOf(6L),
                    removedTriggerRows = 0,
                    failures = listOf("目录 scripts/9：rm -rf failed (exit=1)"),
                ),
            )

        assertTrue(nothing.contains("无需清理"))
        assertFalse(nothing.contains("失败"))
        assertTrue(all.contains("已清理 3 项残留"))
        assertTrue(partial.contains("部分成功"), "有失败项时必须说「部分成功」（同 6c 纪律）")
        assertTrue(partial.contains("已清理 1 项"))
        assertTrue(partial.contains("scripts/9"), "首条失败原因要带出来，否则无法排查")
    }

    @Test
    fun `未知成因键原样带出不编造中文`() {
        // `TripReason` 是 sealed ⇒ 测试无法造"未来版本的成因对象"；
        // 因此这里直接喂稳定键（生产路径走 `TripReason` 重载，两者共用同一判定）
        assertEquals(
            "未知成因（key=some-future-reason）",
            SettingsProjections.tripReasonLabel("some-future-reason"),
        )
        // 已建模的键不得落进兜底分支
        TripReason.ALL_KEYS.forEach { key ->
            assertFalse(
                SettingsProjections.tripReasonLabel(key).startsWith("未知成因"),
                "ALL_KEYS 里的 $key 必须有中文文案",
            )
        }
    }

    @Test
    fun `安全模式文案区分正常 有成因 成因不可还原`() {
        assertEquals("正常（未熔断）", SettingsProjections.safeModeStatusText(safeMode = false, reason = null))
        assertEquals(
            "安全模式：手动熔断",
            SettingsProjections.safeModeStatusText(safeMode = true, reason = TripReason.Manual),
        )
        val unknownReason = SettingsProjections.safeModeStatusText(safeMode = true, reason = null)
        assertTrue(unknownReason.contains("不可还原"), "跨重启丢失成因时必须点明，而不是假装正常")
        assertTrue(unknownReason.contains("flag"), "要让用户知道 flag 是熔断过的证据")
    }

    @Test
    fun `恢复选项覆盖两种模式且都不承诺改动用户数据`() {
        val modes = SettingsProjections.RESTORE_CHOICES.map { it.mode }
        assertEquals(RestoreMode.entries.toList(), modes)

        // 熔断改成内存级拦截后，"禁用触发器 / 还原触发器"两步都是空实现 ⇒
        // 任何一条文案都**不得**再提"触发器"：那描述的是一个已经不存在的动作，
        // 用户会以为自己的配置被改过又还原了。
        SettingsProjections.RESTORE_CHOICES.forEach { choice ->
            assertFalse(
                choice.detail.contains("触发器"),
                "文案不得再提「触发器」—— 熔断从不改动它：${choice.detail}",
            )
        }
        val details = SettingsProjections.RESTORE_CHOICES.map { it.detail }
        assertEquals(2, details.distinct().size, "两条说明仍要能区分开（第二条点明「策略只进日志」）")
        assertTrue(
            details.first().contains("从未被改动"),
            "必须说清配置没被动过，否则用户会以为熔断改过他的设置",
        )
    }
}
