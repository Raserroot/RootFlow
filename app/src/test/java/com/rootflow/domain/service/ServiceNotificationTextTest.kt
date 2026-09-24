package com.rootflow.domain.service

import com.rootflow.domain.event.TripReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 通知文案与渠道的纯函数单测（阶段 5）。
 *
 * ## 为什么这些断言值钱
 * 通知是本阶段**唯一用户可见**的产物，而它的内容在真机上只能靠肉眼判断。
 * 若把文案写死在 `Service` 里，"安全模式显示的是原因还是原因键""通知被拒时有没有谎报"
 * 这两条就只能靠人工逐轮试——本节把判决权挪回可自动化的纯 JVM。
 */
class ServiceNotificationTextTest {
    @Test
    fun `running state reports the event source counts on the quiet channel`() {
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 7, total = 7, safeMode = false),
                reason = null,
            )

        assertEquals(ServiceChannels.FOREGROUND, model.channelId)
        assertEquals(ServiceNotificationText.TITLE_SERVICE, model.title)
        assertEquals("事件源 7/7 · 触发监听正常", model.text)
        assertFalse(model.alert)
    }

    @Test
    fun `partially started sources are reported honestly`() {
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundSourceCounts.threeOfSeven(),
                reason = null,
            )

        assertEquals("事件源 3/7 · 触发监听正常", model.text)
    }

    @Test
    fun `safe mode uses the alert channel and shows the reason detail`() {
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 7, total = 7, safeMode = true),
                reason = TripReason.Manual,
            )

        assertEquals(ServiceChannels.SAFE_MODE, model.channelId)
        assertEquals(ServiceNotificationText.TITLE_SAFE_MODE, model.title)
        // detail 是给人看的（含阈值与实测值）；reasonKey 是给机器看的
        assertTrue(model.text.contains(TripReason.Manual.detail), "正文必须含成因 detail，实际=${model.text}")
        assertTrue(model.alert)
    }

    @Test
    fun `safe mode with an unrecoverable reason says unknown instead of inventing one`() {
        // TripReason.fromKey 对带参成因刻意返回 null（不伪造参数）；文案必须如实降级
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 0, total = 7, safeMode = true),
                reason = null,
            )

        assertTrue(
            model.text.contains(ServiceNotificationText.REASON_UNKNOWN),
            "成因不可还原时必须显式说未知，实际=${model.text}",
        )
        // 不得退化成"运行正常"
        assertFalse(model.text.contains("触发监听正常"))
    }

    @Test
    fun `denied notification permission is stated rather than faked`() {
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 7, total = 7, safeMode = false),
                reason = null,
                notificationsGranted = false,
            )

        assertTrue(
            model.text.endsWith(ServiceNotificationText.NOTIFICATIONS_BLOCKED_SUFFIX),
            "通知被拒时必须显式说明，实际=${model.text}",
        )
    }

    @Test
    fun `unknown notification state does not fabricate a degradation warning`() {
        // 默认参数即"不确定"：API 33 以下没有该权限概念，不得平白多一句警告
        val model =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 7, total = 7, safeMode = false),
                reason = null,
            )

        assertFalse(model.text.contains(ServiceNotificationText.NOTIFICATIONS_BLOCKED_SUFFIX))
    }

    @Test
    fun `idle state reports the service as not running`() {
        val model = ServiceNotificationText.serviceNotification(state = ForegroundState.Idle, reason = null)

        assertEquals("服务未运行", model.text)
        // 未注册时仍走常驻渠道：它只会在极短的启动窗口内出现（服务尚未 register）
        assertEquals(ServiceChannels.FOREGROUND, model.channelId)
        assertFalse(model.alert)
    }

    @Test
    fun `safe mode alert always uses the high priority channel and carries both key and detail`() {
        val reason = TripReason.ScriptTimeout(scriptId = 4, timeoutMillis = 5_000)
        val model = ServiceNotificationText.safeModeAlert(reason)

        assertEquals(ServiceChannels.SAFE_MODE, model.channelId)
        assertEquals(ServiceNotificationText.TITLE_ALERT, model.title)
        assertTrue(model.alert, "告警必须标记为 alert（实现据此走高优先级）")
        assertTrue(model.text.contains(reason.detail), "正文必须含 detail")
        assertTrue(model.text.contains(reason.reasonKey), "正文必须含 reasonKey（真机 findstr 用）")
    }

    @Test
    fun `builders are pure functions`() {
        val state = ForegroundState.Running(enabled = 2, total = 7, safeMode = true)

        assertEquals(
            ServiceNotificationText.serviceNotification(state, TripReason.Manual),
            ServiceNotificationText.serviceNotification(state, TripReason.Manual),
        )
        // 同态重复构建必须相等：`ServiceNotifier.update` 的"内容未变不重复 post"依赖它
        assertEquals(
            ServiceNotificationText.safeModeAlert(TripReason.Manual),
            ServiceNotificationText.safeModeAlert(TripReason.Manual),
        )
    }

    @Test
    fun `channel ids are pinned verbatim and ALL covers both`() {
        // 这些字符串是跨阶段契约（阶段 6 的设置页、真机日志判读都引用它们）
        assertEquals("rootflow_service", ServiceChannels.FOREGROUND)
        assertEquals("rootflow_safemode", ServiceChannels.SAFE_MODE)
        assertEquals(2, ServiceChannels.ALL.size, "新增渠道必须同步进 ALL（建立与清理都遍历它）")
        assertEquals(listOf("rootflow_service", "rootflow_safemode"), ServiceChannels.ALL)
    }

    @Test
    fun `alert models and foreground models are never equal`() {
        // 两者走不同通知 id、不同渠道：若不相等性被破坏，安全模式告警会被常驻通知覆盖
        val alert = ServiceNotificationText.safeModeAlert(TripReason.Manual)
        val foreground =
            ServiceNotificationText.serviceNotification(
                state = ForegroundState.Running(enabled = 7, total = 7, safeMode = true),
                reason = TripReason.Manual,
            )

        assertTrue(alert != foreground)
        assertEquals(ServiceNotificationText.TITLE_ALERT, alert.title)
        assertEquals(ServiceNotificationText.TITLE_SAFE_MODE, foreground.title)
    }

    @Test
    fun `describe exposes the values that device verification greps`() {
        val described =
            ServiceNotificationText.describe(
                ServiceNotificationText.safeModeAlert(TripReason.Manual),
            )

        assertTrue(described.contains("channel=rootflow_safemode"))
        assertTrue(described.contains("alert=true"))
    }

    /** 只读测试数据：避免在每个用例里重复字面量。 */
    private object ForegroundSourceCounts {
        fun threeOfSeven(): ForegroundState.Running = ForegroundState.Running(enabled = 3, total = 7, safeMode = false)
    }
}
