package com.rootflow.ui.master

import com.rootflow.domain.model.Script
import com.rootflow.ui.scripts.testScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * [MasterSwitchProjections] / [ScriptRuntimeProjections] 的纯函数单测（总开关重构 **P4**）。
 *
 * ## 为什么这些判定必须在纯函数里测
 * 本项目**没有 UI 自动化测试**（决策 B），Composable 在纯 JVM 下不可测。
 * 因此"这张卡显示什么""这一行为什么没跑"的全部判定都上移到了被测对象里 ——
 * 本文件就是那些判定的覆盖率。
 *
 * ## 三个固定量（避免断言随环境漂移）
 * - [NOW] 固定"现在"
 * - [ZONE] 固定时区（`absoluteTime` 的"今天"依赖它）
 * - 门控链的输入用 [gate] 构造，默认"一个健康的常驻脚本"
 */
class MasterSwitchProjectionsTest {
    private val now = 1_780_000_000_000L
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun script(
        id: Long,
        enabled: Boolean = true,
        resident: Boolean = false,
    ): Script = testScript(id = id).copy(enabled = enabled, resident = resident)

    private fun gate(
        masterEnabled: Boolean = true,
        scriptEnabled: Boolean = true,
        resident: Boolean = true,
        serviceRunning: Boolean = true,
        safeMode: Boolean = false,
        running: Boolean? = false,
        supervised: Boolean = true,
        givenUpReason: String? = null,
        lastSuccessAt: Long? = null,
        lastAttemptAt: Long? = null,
        lastExitCode: Int? = null,
        nowMillis: Long = now,
        scriptName: String = "电池守护",
    ): GateInput =
        GateInput(
            scriptName = scriptName,
            scriptEnabled = scriptEnabled,
            resident = resident,
            masterEnabled = masterEnabled,
            serviceRunning = serviceRunning,
            safeMode = safeMode,
            running = running,
            supervised = supervised,
            givenUpReason = givenUpReason,
            lastSuccessAt = lastSuccessAt,
            lastAttemptAt = lastAttemptAt,
            lastExitCode = lastExitCode,
            nowMillis = nowMillis,
        )

    // ---------------------------------------------------------------- 卡片

    @Test
    fun `card counts only enabled scripts and requires both flags for resident`() {
        val card =
            MasterSwitchProjections.card(
                masterEnabled = true,
                scripts =
                    listOf(
                        script(1, enabled = true, resident = true),
                        script(2, enabled = true, resident = false),
                        script(3, enabled = false, resident = true),
                    ),
                serviceRunning = true,
                sourcesRunning = 2,
                sourcesTotal = 7,
                safeMode = false,
            )

        assertEquals(2, card.scriptsEnabled, "已启用的是 1、2 号（3 号停用不该计入）")
        assertEquals(1, card.scriptsResident, "常驻要求 enabled 且 resident —— 3 号虽 resident 但已停用")
        assertEquals("已启用 2 个脚本 · 常驻 1 个", card.scriptsLine)
        assertEquals("服务运行中 · 事件源 2/7", card.serviceLine)
        assertNull(card.offNotice, "总闸开着时不该有那句说明")
    }

    @Test
    fun `the off notice appears only when the switch is off`() {
        val off =
            MasterSwitchProjections.card(
                masterEnabled = false,
                scripts = emptyList(),
                serviceRunning = true,
                sourcesRunning = 0,
                sourcesTotal = 0,
                safeMode = false,
            )

        assertNotNull(off.offNotice, "关闸时必须有一句说明（方案 §5.1：不得整块灰掉、要留痕）")
        assertTrue(off.offNotice!!.contains("配置"), "必须说清配置仍保留：${off.offNotice}")
    }

    @Test
    fun `the service line is independent of the master switch`() {
        // 总闸关闭**不停服务**（服务承载事件源与保活）⇒ 两件事必须分开显示，
        // 否则用户会以为"关总闸 = 服务停了"（而那是错的）。
        val off =
            MasterSwitchProjections.card(
                masterEnabled = false,
                scripts = emptyList(),
                serviceRunning = true,
                sourcesRunning = 5,
                sourcesTotal = 7,
                safeMode = false,
            )

        assertEquals("服务运行中 · 事件源 5/7", off.serviceLine, "关闸不影响服务与事件源的如实显示")
    }

    // ---------------------------------------------------------------- 门控链：逐层

    @Test
    fun `safe mode is reported before the master switch`() {
        // 两者可能同时存在 ⇒ 先报安全模式。否则用户会去拨总闸，而那并不会让它跑起来。
        val why = MasterSwitchProjections.whyNotRunning(gate(safeMode = true, masterEnabled = false))

        assertEquals("安全模式", why.blockedAt, "最根本的那一层先报")
        assertTrue(why.verdict.contains("安全模式"), "结论必须指向安全模式：${why.verdict}")
    }

    @Test
    fun `the master switch is the second layer`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(masterEnabled = false))

        assertEquals("总开关", why.blockedAt)
        assertTrue(why.verdict.contains("总开关"), why.verdict)
        // 前一层（安全模式）必须显示为通过，而不是被跳过
        assertEquals(GateState.PASS, why.checks.first { it.label == "安全模式" }.state)
    }

    @Test
    fun `a disabled script is reported at its own layer`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(scriptEnabled = false))

        assertEquals("脚本开关", why.blockedAt)
        assertEquals(GateState.PASS, why.checks.first { it.label == "总开关" }.state, "总闸是开着的，要如实说")
    }

    @Test
    fun `a given up daemon is reported with its reason`() {
        val why =
            MasterSwitchProjections.whyNotRunning(
                gate(givenUpReason = "连续快速崩 5 次（每次存活 < 200ms）"),
            )

        assertEquals("崩溃重试", why.blockedAt)
        assertTrue(why.verdict.contains("连续快速崩 5 次"), "原因必须原样透出：${why.verdict}")
    }

    @Test
    fun `a missing foreground service is reported`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(serviceRunning = false))

        assertEquals("前台服务", why.blockedAt)
        assertTrue(why.verdict.contains("服务"), why.verdict)
    }

    @Test
    fun `when every layer passes a supervised daemon is waiting`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(supervised = true, running = false))

        assertNull(why.blockedAt, "没有任何一层在挡它")
        assertTrue(why.verdict.contains("监管"), "结论要说清「它被监管着、只是还没跑起来」：${why.verdict}")
    }

    @Test
    fun `a running script reports running instead of a reason`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(running = true))

        assertNull(why.blockedAt)
        assertEquals("正在运行", why.verdict)
        assertTrue(why.title.endsWith("运行中"), why.title)
    }

    @Test
    fun `a one shot script explains that it never stays resident`() {
        val why = MasterSwitchProjections.whyNotRunning(gate(resident = false, running = false))

        assertNull(why.blockedAt, "单次脚本「不常驻」是设计，不是被挡住")
        assertTrue(why.verdict.contains("单次"), why.verdict)
    }

    @Test
    fun `an unreadable running state says so instead of claiming it is stopped`() {
        // 三态纪律：读不到 ⇒ 如实说读不到。把"读不到"渲染成"没在跑"是假信息。
        val why = MasterSwitchProjections.whyNotRunning(gate(running = null))

        val line = why.checks.first { it.label == "运行状态" }
        assertTrue(line.detail!!.contains("读取失败"), "必须如实说读不到：${line.detail}")
        assertTrue(why.title.contains("状态未知"), why.title)
    }

    @Test
    fun `the verdict is never empty`() {
        // 各种组合下结论都必须有一句人话（说不清时就如实说"原因未知"）
        val combos =
            listOf(
                gate(),
                gate(safeMode = true),
                gate(masterEnabled = false),
                gate(scriptEnabled = false),
                gate(resident = false),
                gate(givenUpReason = "x"),
                gate(serviceRunning = false),
                gate(running = null),
                gate(supervised = false, running = null),
            )

        combos.forEach { input ->
            val why = MasterSwitchProjections.whyNotRunning(input)
            assertTrue(why.verdict.isNotBlank(), "结论不得为空：$input")
            assertTrue(why.checks.isNotEmpty(), "门控链必须逐层列出")
        }
    }

    // ---------------------------------------------------------------- 状态短句

    @Test
    fun `status text covers the three running states`() {
        val running = ScriptRuntimeState(running = true, givenUpReason = null)
        val stopped = ScriptRuntimeState(running = false, givenUpReason = null)

        assertEquals("常驻 · 运行中", ScriptRuntimeProjections.statusText(true, true, true, running))
        assertEquals("常驻 · 已停止", ScriptRuntimeProjections.statusText(true, true, true, stopped))
        assertEquals(
            "常驻 · 状态未知",
            ScriptRuntimeProjections.statusText(true, true, true, ScriptRuntimeState.Unknown),
            "快照未加载 ⇒ 不得显示「已停止」",
        )
    }

    @Test
    fun `a disabled script reports its own switch before the master switch`() {
        // 「未启用」优先于「已暂停」：用户自己关掉的，就该说"未启用"。
        assertEquals(
            "常驻 · 未启用",
            ScriptRuntimeProjections.statusText(
                enabled = false,
                resident = true,
                masterEnabled = false,
                state = ScriptRuntimeState(running = false, givenUpReason = null),
            ),
        )
        assertEquals(
            "单次 · 未启用",
            ScriptRuntimeProjections.statusText(
                enabled = false,
                resident = false,
                masterEnabled = true,
                state = ScriptRuntimeState(running = false, givenUpReason = null),
            ),
        )
    }

    @Test
    fun `the master switch pauses even a running script`() {
        // 它可能刚好还没被停掉，但用户的意图是停 ⇒ 说"已暂停"比说"运行中"更接近事实。
        assertEquals(
            "常驻 · 已暂停",
            ScriptRuntimeProjections.statusText(
                enabled = true,
                resident = true,
                masterEnabled = false,
                state = ScriptRuntimeState(running = true, givenUpReason = null),
            ),
        )
    }

    @Test
    fun `a given up daemon wins over the running state`() {
        assertEquals(
            "常驻 · 已放弃重启",
            ScriptRuntimeProjections.statusText(
                enabled = true,
                resident = true,
                masterEnabled = true,
                state = ScriptRuntimeState(running = false, givenUpReason = "连续快速崩"),
            ),
        )
    }

    @Test
    fun `a single-shot script never reports a given-up restart`() {
        // 真机恢复现场时抓到：把脚本从常驻改回单次之后，那条历史 givenUp 记录还在内存里，
        // 而行上显示成了"常驻 · 已放弃重启" —— 与它当前的事实（单次）直接矛盾。
        // 单次脚本根本不参与监管，因此那一层对它**不适用**。
        assertEquals(
            "单次 · 已停止",
            ScriptRuntimeProjections.statusText(
                enabled = true,
                resident = false,
                masterEnabled = true,
                state = ScriptRuntimeState(running = false, givenUpReason = "连续快速崩 5 次"),
            ),
        )
    }

    @Test
    fun `is live needs all three conditions`() {
        val running = ScriptRuntimeState(running = true, givenUpReason = null)

        assertTrue(ScriptRuntimeProjections.isLive(true, true, running))
        assertFalse(ScriptRuntimeProjections.isLive(false, true, running), "脚本自己停用了")
        assertFalse(ScriptRuntimeProjections.isLive(true, false, running), "总闸关着")
        assertFalse(
            ScriptRuntimeProjections.isLive(true, true, ScriptRuntimeState.Unknown),
            "状态未知不算活着",
        )
    }

    @Test
    fun `paused by master requires the script itself to be enabled`() {
        assertTrue(ScriptRuntimeProjections.pausedByMaster(enabled = true, masterEnabled = false))
        assertFalse(ScriptRuntimeProjections.pausedByMaster(enabled = true, masterEnabled = true))
        assertFalse(
            ScriptRuntimeProjections.pausedByMaster(enabled = false, masterEnabled = false),
            "脚本自己已停用 ⇒ 说「未启用」就够了，不要再叠一层「被暂停」",
        )
    }

    // ---------------------------------------------------------------- 时间

    @Test
    fun `relative time treats zero and null as never`() {
        assertEquals("从未", MasterSwitchProjections.relativeTime(now, null))
        assertEquals("从未", MasterSwitchProjections.relativeTime(now, 0L), "库里的 0 表示「从未」")
        assertEquals("从未", MasterSwitchProjections.relativeTime(now, -1L))
    }

    @Test
    fun `relative time buckets`() {
        assertEquals("刚刚", MasterSwitchProjections.relativeTime(now, now - 30_000L))
        assertEquals("5 分钟前", MasterSwitchProjections.relativeTime(now, now - 5 * 60_000L))
        assertEquals("3 小时前", MasterSwitchProjections.relativeTime(now, now - 3 * 3_600_000L))
        assertEquals("2 天前", MasterSwitchProjections.relativeTime(now, now - 2 * 86_400_000L))
    }

    @Test
    fun `absolute time uses today only within the same local date`() {
        val noon = 1_780_000_000_000L
        assertEquals("从未", MasterSwitchProjections.absoluteTime(noon, null, zone))
        assertTrue(
            MasterSwitchProjections.absoluteTime(noon, noon - 3_600_000L, zone).startsWith("今天 "),
            "同一本地日期内显示「今天 HH:mm」",
        )
        assertTrue(
            MasterSwitchProjections.absoluteTime(noon, noon - 3 * 86_400_000L, zone).contains("-"),
            "跨天显示「MM-dd HH:mm」",
        )
    }

    @Test
    fun `last success text combines absolute and relative`() {
        val text = MasterSwitchProjections.lastSuccessText(now, now - 3 * 3_600_000L, zone)

        assertTrue(text.contains("今天"), text)
        assertTrue(text.contains("3 小时前"), text)
        assertEquals("从未", MasterSwitchProjections.lastSuccessText(now, null, zone))
    }

    @Test
    fun `last attempt detail never invents an exit code`() {
        val never = MasterSwitchProjections.lastAttemptDetail(gate(lastAttemptAt = null))
        assertEquals("从未", never)

        val unknownExit =
            MasterSwitchProjections.lastAttemptDetail(gate(lastAttemptAt = now - 60_000L, lastExitCode = null))
        assertTrue(unknownExit.contains("退出码未知"), "拿不到退出码时说不知道：$unknownExit")

        val ok = MasterSwitchProjections.lastAttemptDetail(gate(lastAttemptAt = now - 60_000L, lastExitCode = 0))
        assertTrue(ok.contains("退出码 0"), ok)

        val bad = MasterSwitchProjections.lastAttemptDetail(gate(lastAttemptAt = now - 60_000L, lastExitCode = 7))
        assertTrue(bad.contains("退出码 7"), bad)
    }
}
