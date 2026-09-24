package com.rootflow.service.receiver

import android.util.Log
import com.rootflow.data.event.EventBusHolder
import com.rootflow.data.event.RecordingEventBus
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [AlarmFireReceiver] 单测（阶段 3c.2）。
 *
 * 与 `BootEventReceiverTest` 同构：直接调用可测入口 `handleAction(action)`，
 * 配合 [EventBusHolder] 注入假总线——纯 JVM 可跑，无需 Robolectric。
 *
 * ## 常量一致性如何守护（本阶段的关键问题）
 * `AndroidManifest.xml` 里的 `<action android:name="…">` 是**字符串字面量**，
 * 编译期无法引用 Kotlin 常量，因此三方（清单 / 接收器 / 写入侧）的一致性无法靠类型系统保证。
 * 本类用**两道断言**守住它：
 * 1. [action constants are the platform-shared literals]：钉死字面量值，
 *    并与写入侧共用的 [AlarmActions] 比对（两者是同一个对象，防止有人各写一遍）
 * 2. [the manifest declares both alarm actions]：**读 `AndroidManifest.xml` 原文**，
 *    确认两个 action 都在清单里——这是"字面量写错导致闹钟永远收不到"的唯一防线
 */
class AlarmFireReceiverTest {
    @AfterEach
    fun tearDown() {
        EventBusHolder.clear()
    }

    @Test
    fun `time action is forwarded to the event bus`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        AlarmFireReceiver().handleAction(AlarmActions.TIME)

        assertEquals(listOf(SystemEvent.Time), bus.sent)
    }

    @Test
    fun `interval action is forwarded to the event bus`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        AlarmFireReceiver().handleAction(AlarmActions.INTERVAL)

        assertEquals(listOf(SystemEvent.Interval), bus.sent)
    }

    @Test
    fun `the two actions are not confusable`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        AlarmFireReceiver().handleAction(AlarmActions.TIME)
        AlarmFireReceiver().handleAction(AlarmActions.INTERVAL)

        assertEquals(listOf(SystemEvent.Time, SystemEvent.Interval), bus.sent)
        assertTrue(SystemEvent.Time.eventId != SystemEvent.Interval.eventId)
    }

    @Test
    fun `unknown and null actions are ignored`() {
        installLogStub()
        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        AlarmFireReceiver().handleAction("com.rootflow.SOMETHING_ELSE")
        AlarmFireReceiver().handleAction(null)
        AlarmFireReceiver().handleAction("android.intent.action.SCREEN_ON")

        assertTrue(bus.sent.isEmpty(), "非闹钟 action 不得被误当成 time/interval：${bus.sent}")
    }

    @Test
    fun `missing bus does not crash the receiver`() {
        installLogStub()

        // 刻意不 install：闹钟到点时 App 可能尚未完成初始化
        AlarmFireReceiver().handleAction(AlarmActions.TIME)

        assertTrue(true)
    }

    @Test
    fun `action constants are the platform-shared literals`() {
        // 写入侧（AlarmEventSource 经 AlarmActions 构造 PendingIntent）与接收侧必须是同一对字面量
        assertEquals("com.rootflow.TIME", AlarmActions.TIME)
        assertEquals("com.rootflow.INTERVAL", AlarmActions.INTERVAL)
    }

    @Test
    fun `the manifest declares both alarm actions`() {
        val manifest = readManifest()
        assumeTrue(manifest != null, "找不到 AndroidManifest.xml，跳过（cwd=${System.getProperty("user.dir")}）")
        val text = manifest.orEmpty()

        // PendingIntent 用显式 component 构造，因此只需 action 在清单里能匹配 intent-filter
        assertTrue(text.contains("AlarmFireReceiver"), "清单必须声明 AlarmFireReceiver")
        assertTrue(
            text.contains("""android:name="${AlarmActions.TIME}""""),
            "清单的 intent-filter 必须包含 ${AlarmActions.TIME}（字面量写错会让闹钟永远收不到）",
        )
        assertTrue(
            text.contains("""android:name="${AlarmActions.INTERVAL}""""),
            "清单的 intent-filter 必须包含 ${AlarmActions.INTERVAL}",
        )
    }

    /**
     * **结构**断言（阶段 3d 补齐，3c.2 遗留的缺口）。
     *
     * ## 为什么必须补
     * 3c.2 的收尾记录明确登记过：上面那条用例是"**存在性**断言，不校验 intent-filter 结构
     * ——若有人把 action 挪到别的 receiver 下，它仍会通过"。
     * 本用例把"这两个 action 属于 `AlarmFireReceiver` 自己"与"它确实是 `exported=false`"
     * 一起钉死：
     * - action 被挪走 → `alarmReceiverBlock` 里找不到 action → 失败
     * - `exported` 被误改成 `true` → 失败（那会让任意应用伪造闹钟事件，等于打开注入面）
     *
     * ## 为什么 `exported` 必须是 `false`，而闹钟仍能到达
     * `AlarmManager` 投递走的是 **`PendingIntent`**（携带本应用自身身份），
     * 而非外部应用直接 `sendBroadcast`，因此**非导出不构成阻挡**。
     * 反过来，`exported="true"` 会让任何应用都能伪造 `com.rootflow.INTERVAL`。
     * **副作用（登记在 `AGENT_PROTOCOL.md §7.3`）**：`adb shell am broadcast -n …`
     * 以 shell(uid=2000) 身份发送，会被非导出挡住，**因此不能用它验证本接收器**。
     */
    @Test
    fun `the alarm receiver owns both actions and stays non-exported`() {
        val manifest = readManifest()
        assumeTrue(manifest != null, "找不到 AndroidManifest.xml，跳过（cwd=${System.getProperty("user.dir")}）")
        val block = alarmReceiverBlock(manifest.orEmpty())
        assumeTrue(block != null, "清单里找不到 AlarmFireReceiver 的 <receiver> 块，跳过")

        val receiver = block.orEmpty()
        assertTrue(
            receiver.contains("""android:name="${AlarmActions.TIME}""""),
            "两个 action 必须属于 AlarmFireReceiver 自己（不可挪到别的 receiver 下）：$receiver",
        )
        assertTrue(
            receiver.contains("""android:name="${AlarmActions.INTERVAL}""""),
            "两个 action 必须属于 AlarmFireReceiver 自己：$receiver",
        )
        assertTrue(
            receiver.contains("""android:exported="false""""),
            "AlarmFireReceiver 必须保持 exported=false（true 会让任意应用伪造闹钟事件）：$receiver",
        )
    }

    /**
     * 截出 `AlarmFireReceiver` 那个 `<receiver …>…</receiver>` 块的原文。
     *
     * 用**朴素的状态扫描**而不是正则：清单里有嵌套（`<intent-filter>` 里还有 `<action>`），
     * 正则容易跨块误匹配——那正是这条护栏要防的失效模式。
     *
     * @return 块原文；找不到时 `null`（由 `assumeTrue` 守卫跳过）
     */
    private fun alarmReceiverBlock(manifest: String): String? {
        val start = manifest.indexOf("""android:name=".service.receiver.AlarmFireReceiver"""")
        if (start < 0) return null
        val open = manifest.lastIndexOf("<receiver", start)
        val end = manifest.indexOf("</receiver>", start)
        if (open < 0 || end < 0) return null
        return manifest.substring(open, end)
    }

    /** 读取模块根目录下的清单；读不到返回 `null`（由 `assumeTrue` 守卫跳过）。 */
    private fun readManifest(): String? =
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()

    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }
}
