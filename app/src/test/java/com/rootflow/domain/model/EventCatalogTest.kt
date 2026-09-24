package com.rootflow.domain.model

import com.rootflow.data.event.TriggerEventKeys
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.Requirement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [EventCatalog] 的单测（阶段 6e）。
 *
 * ## 这个类守的是"**双表不许漂移**"
 * `TriggerEventKeys` 是**持久化契约**（写进 `triggers.event_type`），`EventCatalog` 是
 * **UI 目录**（chip 标签、参数形态、权限标注）。两张表必须覆盖同一批事件 ——
 * 而"两张表各自自洽、接缝没人测"正是 `AGENT_PROTOCOL.md §8.0` 禁止的形态。
 * 因此这里有一条**穷举相等**断言。
 *
 * ## 为什么用 `entries` / `ALL` 穷举而不是逐条列举
 * 逐条列举的测试在**新增事件时不会变红** —— 它只证明"我列的那几条对"，不证明"没有漏"。
 * 这里的每一条都是用 `forEach` 覆盖全目录，因此新增一个事件就会被自动纳入。
 */
class EventCatalogTest {
    @Test
    @DisplayName("目录覆盖 14 个事件：需求 §2.1 的 12 个 + battery_okay + always_run")
    fun `the catalog covers twelve requirement events plus the recovery edge`() {
        assertEquals(14, EventCatalog.ALL.size, "需求 12 + battery_okay + always_run = 14")
        assertEquals(12, EventCatalog.ALL.count { it.fromRequirement })
        assertEquals(2, EventCatalog.ALL.count { !it.fromRequirement })
    }

    @Test
    @DisplayName("catalog 的事件键与 TriggerEventKeys.knownIds **穷举相等**（双表不许漂移）")
    fun `catalog ids match the persisted keys exactly`() {
        assertEquals(
            TriggerEventKeys.knownIds,
            EventCatalog.ids.toSet(),
            "UI 目录与持久化契约必须是同一批键：多了会写出调度器认不得的行，少了 UI 配不出来",
        )
    }

    @Test
    @DisplayName("eventId **无重复**（重复会让 chip 互相覆盖选中态）")
    fun `event ids are unique`() {
        assertEquals(EventCatalog.ids.size, EventCatalog.ids.toSet().size)
    }

    @Test
    @DisplayName("中文标签 **非空且唯一**（两个 chip 同名会让用户无法分辨）")
    fun `labels are non blank and unique`() {
        EventCatalog.ALL.forEach { spec ->
            assertTrue(spec.label.isNotBlank(), "标签不能为空：${spec.eventId}")
        }
        val labels = EventCatalog.ALL.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "标签重复：$labels")
    }

    @Test
    @DisplayName("标签**不等于**事件键：键是持久化契约，不该印在界面上")
    fun `labels never expose the raw event id`() {
        EventCatalog.ALL.forEach { spec ->
            assertFalse(
                spec.label == spec.eventId,
                "chip 上应显示中文标签而不是键：${spec.eventId}",
            )
        }
    }

    @Test
    @DisplayName("带参事件恰好 3 个：time / interval / 两个 apps 形态 —— 数量与形态都对")
    fun `param kinds match the event semantics`() {
        assertEquals(
            EventCatalog.ParamKind.TIME,
            EventCatalog.spec(SystemEvent.TIME)?.params,
        )
        assertEquals(
            EventCatalog.ParamKind.INTERVAL,
            EventCatalog.spec(SystemEvent.INTERVAL)?.params,
        )
        listOf(SystemEvent.APP_FOREGROUND, SystemEvent.APP_BACKGROUND).forEach { id ->
            assertEquals(EventCatalog.ParamKind.PACKAGE, EventCatalog.spec(id)?.params, id)
        }
        val withParams = EventCatalog.ALL.filter { it.hasParams }.map { it.eventId }
        assertEquals(
            listOf(
                SystemEvent.APP_FOREGROUND,
                SystemEvent.APP_BACKGROUND,
                SystemEvent.TIME,
                SystemEvent.INTERVAL,
            ),
            withParams,
        )
    }

    @Test
    @DisplayName("权限标注**穷举**：只有 apps 与 time 三处，其余事件一律无权限依赖")
    fun `permission annotations are exhaustive`() {
        val annotated = EventCatalog.ALL.filter { it.permission != null }
        assertEquals(
            listOf(SystemEvent.APP_FOREGROUND, SystemEvent.APP_BACKGROUND, SystemEvent.TIME),
            annotated.map { it.eventId },
        )
        EventCatalog.ALL.filter { it.permission == null }.forEach { spec ->
            assertFalse(spec.permissionOnlyForExact, "无权限的事件不该标精确档：${spec.eventId}")
        }
    }

    @Test
    @DisplayName("time 的权限**仅精确档**需要（非精确档照常触发，见 D2/D3）")
    fun `the exact alarm permission is exact-only`() {
        val time = EventCatalog.spec(SystemEvent.TIME)
        assertNotNull(time)
        assertEquals(AndroidPermission.SCHEDULE_EXACT_ALARM, time?.permission)
        assertTrue(time?.permissionOnlyForExact == true, "非精确路径不需要该权限 ⇒ 不等于事件不可用")

        val apps = EventCatalog.spec(SystemEvent.APP_FOREGROUND)
        assertEquals(AndroidPermission.PACKAGE_USAGE_STATS, apps?.permission)
        assertFalse(apps?.permissionOnlyForExact == true, "使用情况访问是**始终**需要的")
    }

    @Test
    @DisplayName("权限目录项与判定档位自洽（USAGE_STATS / EXACT_ALARM 都是特殊档）")
    fun `annotated permissions use the matching requirement tier`() {
        assertEquals(
            Requirement.USAGE_STATS,
            AndroidPermission.PACKAGE_USAGE_STATS.requirement,
        )
        assertEquals(
            Requirement.EXACT_ALARM,
            AndroidPermission.SCHEDULE_EXACT_ALARM.requirement,
        )
    }

    @Test
    @DisplayName("非需求列出的事件都必须各自说明理由（battery_okay 决策 3 / always_run 阶段 10）")
    fun `the extra events explain why they exist`() {
        val extras = EventCatalog.ALL.filter { !it.fromRequirement }
        // 阶段 10 起有两个：battery_okay（决策 3）+ always_run（「一直运行」）
        assertEquals(
            listOf(SystemEvent.BATTERY_OKAY, SystemEvent.ALWAYS_RUN),
            extras.map { it.eventId },
            "非需求列出的事件集合是**契约**：新增一个就必须显式更新这里",
        )
        extras.forEach { extra ->
            assertNotNull(extra.note, "追加的事件必须有一句说明，否则下一个人会以为它是笔误")
            assertTrue(extra.note?.isNotBlank() == true, "说明不能是空串：${extra.eventId}")
        }
        val batteryOkay = extras.single { it.eventId == SystemEvent.BATTERY_OKAY }
        assertTrue(batteryOkay.note?.contains("决策 3") == true, "实际=${batteryOkay.note}")
    }

    @Test
    @DisplayName("顺序稳定：同一份目录两次读取逐项相等（写库顺序与日志比对都靠它）")
    fun `the catalog order is stable`() {
        assertEquals(EventCatalog.ALL.map { it.eventId }, EventCatalog.ids)
        assertEquals(EventCatalog.ids, EventCatalog.ALL.map { it.eventId })
        assertEquals(0, EventCatalog.orderOf(SystemEvent.BOOT), "开机排第一（用户最常想到的）")
    }

    @Test
    @DisplayName("未知键：spec 返回 null、label 回落键本身、orderOf 排到末尾（不崩、不插队）")
    fun `unknown ids degrade without throwing`() {
        assertNull(EventCatalog.spec("no_such_event"))
        assertEquals("no_such_event", EventCatalog.label("no_such_event"))
        assertEquals(EventCatalog.ALL.size, EventCatalog.orderOf("no_such_event"))
        assertTrue(
            EventCatalog.orderOf("no_such_event") > EventCatalog.orderOf(SystemEvent.WIFI_CHANGED),
            "未知键不得插到已知事件之前",
        )
    }
}
