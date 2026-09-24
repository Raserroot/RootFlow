package com.rootflow.ui.scripts

import com.rootflow.domain.model.EventCatalog
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.model.TriggerParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 触发器参数草稿的**纯函数**单测（阶段 6e）。
 *
 * ## 为什么这些断言值钱
 * 本仓库没有 UI 测试（决策 B），参数区的校验逻辑若留在 Composable 里覆盖率就是 0。
 * 这里逐条钉死的是"什么能落库、什么必须被拒"——
 * 而**被拒后静默换成默认值**是本仓库最忌讳的形态（等于替用户改配置）。
 */
class TriggerParamsDraftTest {
    // ------------------------------------------------------------ 往返

    @Test
    @DisplayName("空草稿 ⇒ 默认参数：四值皆无，exact=false（最保守配置）")
    fun `an empty draft maps to the conservative defaults`() {
        val params = TriggerParamsDraft.Empty.toParams()
        assertNotNull(params)
        assertEquals(TriggerParams(), params)
    }

    @Test
    @DisplayName("exact 恒透传（它是显式开关，不是文本输入）")
    fun `exact is passed through untouched`() {
        assertEquals(
            true,
            TriggerParamsDraft.Empty
                .copy(exact = true)
                .toParams()
                ?.exact,
        )
        assertEquals(
            false,
            TriggerParamsDraft.Empty
                .copy(exact = false)
                .toParams()
                ?.exact,
        )
    }

    @Test
    @DisplayName("往返恒等：params → draft → params（逐字段）")
    fun `params survive a draft round trip`() {
        val original =
            TriggerParams(
                exact = true,
                intervalMinutes = 30,
                hourOfDay = 7,
                minuteOfHour = 5,
                payload = "{\"source\":\"ui\"}",
            )
        assertEquals(original, TriggerParamsDraft.of(original).toParams())
    }

    // ------------------------------------------------------------ 时刻

    @Test
    @DisplayName("时刻边界：0:0 与 23:59 合法")
    fun `time boundaries are accepted`() {
        val midnight = TriggerParamsDraft.Empty.copy(hourOfDay = "0", minuteOfHour = "0").toParams()
        assertEquals(0, midnight?.hourOfDay)
        assertEquals(0, midnight?.minuteOfHour)

        val last = TriggerParamsDraft.Empty.copy(hourOfDay = "23", minuteOfHour = "59").toParams()
        assertEquals(23, last?.hourOfDay)
        assertEquals(59, last?.minuteOfHour)
    }

    @Test
    @DisplayName("时刻越界：24 时 / 60 分 ⇒ **不落库**（`toParams` 返回 null）")
    fun `time out of range is refused`() {
        assertNull(TriggerParamsDraft.Empty.copy(hourOfDay = "24", minuteOfHour = "0").toParams())
        assertNull(TriggerParamsDraft.Empty.copy(hourOfDay = "0", minuteOfHour = "60").toParams())
        assertNull(TriggerParamsDraft.Empty.copy(hourOfDay = "-1", minuteOfHour = "0").toParams())
    }

    @Test
    @DisplayName("时刻**只填一半** ⇒ 不落库，但**不报错**（那是「还没配完」，不是错误）")
    fun `a half filled time is not an error`() {
        assertNull(TriggerParamsDraft.Empty.copy(hourOfDay = "7").toParams())
        assertNull(TriggerParamsDraft.Empty.copy(minuteOfHour = "30").toParams())

        // ★ 阶段 6e 真机项 11 的裁定：用户先填「时」再填「分」是**必然经过**的中间态，
        //   此时报错等于"在打字途中骂人"。它不是错误，由 TIME_INCOMPLETE 那条提示承担。
        assertEquals(
            emptyList<TriggerParamError>(),
            TriggerParamsDraft.Empty.copy(hourOfDay = "7").errors(),
            "半填的时刻不该给字段级错误",
        )
        assertEquals(emptyList<TriggerParamError>(), TriggerParamsDraft.Empty.copy(minuteOfHour = "30").errors())
    }

    @Test
    @DisplayName("时刻**真的越界**才算错误（24 时 / 60 分）")
    fun `an out of range time is an error`() {
        assertTrue(
            TriggerParamsDraft.Empty
                .copy(hourOfDay = "24", minuteOfHour = "0")
                .errors()
                .any { it.field == TriggerParamsDraft.Companion.Field.TIME },
        )
        assertTrue(
            TriggerParamsDraft.Empty
                .copy(hourOfDay = "0", minuteOfHour = "60")
                .errors()
                .any { it.field == TriggerParamsDraft.Companion.Field.TIME },
        )
    }

    @Test
    @DisplayName("时刻非数字 ⇒ 不落库，且给字段级错误")
    fun `a non numeric time is refused with a field error`() {
        val draft = TriggerParamsDraft.Empty.copy(hourOfDay = "七", minuteOfHour = "30")
        assertNull(draft.toParams())
        val errors = draft.errors()
        assertTrue(errors.any { it.field == TriggerParamsDraft.Companion.Field.TIME }, "实际=$errors")
    }

    // ------------------------------------------------------------ 间隔

    @Test
    @DisplayName("间隔边界：1 与 1440 合法，0 / 1441 / 非数字 ⇒ 不落库")
    fun `interval boundaries are enforced`() {
        assertEquals(
            1,
            TriggerParamsDraft.Empty
                .copy(intervalMinutes = "1")
                .toParams()
                ?.intervalMinutes,
        )
        assertEquals(
            1440,
            TriggerParamsDraft.Empty
                .copy(intervalMinutes = "1440")
                .toParams()
                ?.intervalMinutes,
        )

        assertNull(TriggerParamsDraft.Empty.copy(intervalMinutes = "0").toParams(), "0 = 未配置，不是每 0 分钟")
        assertNull(TriggerParamsDraft.Empty.copy(intervalMinutes = "1441").toParams())
        assertNull(TriggerParamsDraft.Empty.copy(intervalMinutes = "abc").toParams())
    }

    @Test
    @DisplayName("间隔留空 ⇒ 未配置（null），不是「每 0 分钟」")
    fun `a blank interval means unconfigured`() {
        assertNull(
            TriggerParamsDraft.Empty
                .copy(intervalMinutes = "")
                .toParams()
                ?.intervalMinutes,
        )
        assertNull(
            TriggerParamsDraft.Empty
                .copy(intervalMinutes = "   ")
                .toParams()
                ?.intervalMinutes,
        )
    }

    @Test
    @DisplayName("isCompleteFor：time 要时刻、interval 要间隔，其余恒 true")
    fun `completeness depends on the event kind`() {
        assertFalse(TriggerParamsDraft.Empty.isCompleteFor(SystemEvent.TIME))
        assertTrue(TriggerParamsDraft.Empty.copy(hourOfDay = "7", minuteOfHour = "5").isCompleteFor(SystemEvent.TIME))

        assertFalse(TriggerParamsDraft.Empty.isCompleteFor(SystemEvent.INTERVAL))
        assertTrue(TriggerParamsDraft.Empty.copy(intervalMinutes = "30").isCompleteFor(SystemEvent.INTERVAL))

        assertTrue(TriggerParamsDraft.Empty.isCompleteFor(SystemEvent.BOOT), "无参事件永远算配全")
    }

    // ------------------------------------------------------------ payload

    @Test
    @DisplayName("payload 换行被**剥离**并回报（运行时 sanitizeEnv 会拒绝含换行的值）")
    fun `payload newlines are stripped and reported`() {
        val (draft, stripped) = TriggerParamsDraft.Empty.withPayload("a\nb\rc")
        assertEquals("abc", draft.payload)
        assertTrue(stripped, "剥离过就必须回报 —— 静默改用户输入是不可接受的")
    }

    @Test
    @DisplayName("payload 无换行 ⇒ 原样保留且不回报")
    fun `a clean payload is untouched`() {
        val raw = "{\"source\":\"ui\"}"
        val (draft, stripped) = TriggerParamsDraft.Empty.withPayload(raw)
        assertEquals(raw, draft.payload)
        assertFalse(stripped)
        assertEquals(raw, draft.toParams()?.payload, "JSON 负载必须逐字节保留")
    }

    @Test
    @DisplayName("payload 空串 ⇄ null 同一个语义（空 payload 有明确含义：回退事件自带负载）")
    fun `an empty payload becomes null`() {
        assertNull(
            TriggerParamsDraft.Empty
                .copy(payload = "")
                .toParams()
                ?.payload,
        )
        assertEquals(
            "x",
            TriggerParamsDraft.Empty
                .copy(payload = "x")
                .toParams()
                ?.payload,
        )
    }

    @Test
    @DisplayName("payload 超长 ⇒ 字段级错误（但不阻断落库：Truncate 属于用户的事）")
    fun `an oversized payload only warns`() {
        val long = "x".repeat(TriggerParamsDraft.MAX_PAYLOAD_LENGTH + 1)
        val draft = TriggerParamsDraft.Empty.copy(payload = long)
        assertTrue(draft.errors().any { it.field == TriggerParamsDraft.Companion.Field.PAYLOAD })
        assertNotNull(draft.toParams(), "长度为策略而非正确性问题 ⇒ 仍可落库")
    }

    @Test
    @DisplayName("干净的草稿没有错误（错误只在真的错时出现）")
    fun `a valid draft has no errors`() {
        val draft =
            TriggerParamsDraft(
                hourOfDay = "7",
                minuteOfHour = "5",
                intervalMinutes = "30",
                payload = "ok",
            )
        assertEquals(emptyList<TriggerParamError>(), draft.errors())
    }
}

/**
 * [TriggerSelectionDiff] 的单测（阶段 6e）。
 *
 * ## 这里守的是"调用序列的确定性"
 * 真机判读靠 `findstr` 逐行比对日志，而"同样一次点击产出不同的写库顺序"会让比对失效。
 * 因此**顺序**与**内容**一样是契约。
 */
class TriggerSelectionDiffTest {
    private fun row(
        id: Long,
        eventType: String,
        enabled: Boolean = true,
        createdAt: Long = id,
    ) = TriggerRowUi(
        id = id,
        eventType = eventType,
        params = TriggerParams(),
        enabled = enabled,
        createdAt = createdAt,
    )

    @Test
    @DisplayName("空 → 空：没有任何要写的东西")
    fun `nothing to do when both sides are empty`() {
        val diff = TriggerSelectionDiff.compute(desired = emptySet(), existing = emptyList())
        assertTrue(diff.isEmpty)
        assertEquals(0, diff.totalWrites)
    }

    @Test
    @DisplayName("新增一个 ⇒ toCreate 恰一条")
    fun `adding an event produces one insert`() {
        val diff = TriggerSelectionDiff.compute(desired = setOf(SystemEvent.BOOT), existing = emptyList())
        assertEquals(listOf(SystemEvent.BOOT), diff.toCreate)
        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.duplicates.isEmpty())
    }

    @Test
    @DisplayName("库里已有 ⇒ **不重复写**（幂等的核心：连点两次不产生第二行）")
    fun `an already selected event is not rewritten`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = setOf(SystemEvent.BOOT),
                existing = listOf(row(id = 3, eventType = SystemEvent.BOOT)),
            )
        assertTrue(diff.isEmpty, "已经是目标状态就不该有任何写入")
    }

    @Test
    @DisplayName("取消勾选 ⇒ 按 id 删除（不是按事件键）")
    fun `deselecting deletes by id`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = emptySet(),
                existing = listOf(row(id = 7, eventType = SystemEvent.SCREEN_OFF)),
            )
        assertEquals(listOf(7L), diff.toDelete)
        assertTrue(diff.toCreate.isEmpty())
    }

    @Test
    @DisplayName("顺序**确定**：toCreate 按目录序，与入参集合的迭代序无关")
    fun `inserts follow the catalog order regardless of input order`() {
        val desired = setOf(SystemEvent.INTERVAL, SystemEvent.WIFI_CHANGED, SystemEvent.BOOT)
        val first = TriggerSelectionDiff.compute(desired = desired, existing = emptyList())
        val second =
            TriggerSelectionDiff.compute(
                desired = linkedSetOf(SystemEvent.WIFI_CHANGED, SystemEvent.BOOT, SystemEvent.INTERVAL),
                existing = emptyList(),
            )
        assertEquals(listOf(SystemEvent.BOOT, SystemEvent.INTERVAL, SystemEvent.WIFI_CHANGED), first.toCreate)
        assertEquals(first.toCreate, second.toCreate, "同样的意图必须产出同样的调用序列")
    }

    @Test
    @DisplayName("顺序**确定**：toDelete 与入参列表的顺序无关（按 id 升序）")
    fun `deletes are ordered by id regardless of input order`() {
        val existing =
            listOf(
                row(id = 9, eventType = SystemEvent.UNLOCK),
                row(id = 2, eventType = SystemEvent.BOOT),
                row(id = 5, eventType = SystemEvent.INTERVAL),
            )
        val diff = TriggerSelectionDiff.compute(desired = emptySet(), existing = existing)
        assertEquals(listOf(2L, 5L, 9L), diff.toDelete)
    }

    @Test
    @DisplayName("重复行：两条都启用 ⇒ 保留 id 最小的一条，另一条进 duplicates")
    fun `duplicate enabled rows keep the earliest`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = setOf(SystemEvent.BOOT),
                existing =
                    listOf(
                        row(id = 8, eventType = SystemEvent.BOOT),
                        row(id = 4, eventType = SystemEvent.BOOT),
                    ),
            )
        assertEquals(listOf(8L), diff.duplicates)
        assertTrue(diff.toCreate.isEmpty(), "已经有行了就不该再插一条")
        assertTrue(diff.toDelete.isEmpty(), "期望里还有它 ⇒ 不是删除")
        assertEquals(1, diff.totalWrites)
    }

    @Test
    @DisplayName("★ 重复行：一条禁用一条启用 ⇒ **保留启用的那条**（不能把唯一能跑的行删掉）")
    fun `duplicates prefer the enabled row`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = setOf(SystemEvent.BOOT),
                existing =
                    listOf(
                        row(id = 4, eventType = SystemEvent.BOOT, enabled = false),
                        row(id = 9, eventType = SystemEvent.BOOT, enabled = true),
                    ),
            )
        assertEquals(listOf(4L), diff.duplicates, "该删的是那条禁用的旧行（id 小但不是它该留）")
    }

    @Test
    @DisplayName("重复行 + 取消勾选 ⇒ 只保留的那条被删，其余进 duplicates")
    fun `deselecting with duplicates deletes the kept row and cleans the rest`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = emptySet(),
                existing =
                    listOf(
                        row(id = 1, eventType = SystemEvent.TIME),
                        row(id = 2, eventType = SystemEvent.TIME),
                    ),
            )
        assertEquals(listOf(1L), diff.toDelete)
        assertEquals(listOf(2L), diff.duplicates)
        assertEquals(2, diff.totalWrites)
    }

    @Test
    @DisplayName("期望集合里的**未知键**被忽略（不崩、不静默：进 unknown）")
    fun `unknown desired keys are reported not created`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = setOf(SystemEvent.BOOT, "no_such_event"),
                existing = emptyList(),
            )
        assertEquals(listOf(SystemEvent.BOOT), diff.toCreate)
        assertEquals(listOf("no_such_event"), diff.unknown)
        assertFalse(diff.isEmpty, "有效的那一条仍要写")
    }

    @Test
    @DisplayName("库里的**未知事件行**（历史脏键）在取消勾选时会被删掉")
    fun `unknown existing rows are deleted when not desired`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = emptySet(),
                existing = listOf(row(id = 11, eventType = "legacy_event")),
            )
        assertEquals(listOf(11L), diff.toDelete)
    }

    @Test
    @DisplayName("同时增删：两件事都在一次 diff 里（用户连点两次的净效果）")
    fun `a single diff can both insert and delete`() {
        val diff =
            TriggerSelectionDiff.compute(
                desired = setOf(SystemEvent.BOOT, SystemEvent.INTERVAL),
                existing =
                    listOf(
                        row(id = 1, eventType = SystemEvent.SCREEN_ON),
                        row(id = 2, eventType = SystemEvent.BOOT),
                    ),
            )
        assertEquals(listOf(SystemEvent.INTERVAL), diff.toCreate)
        assertEquals(listOf(1L), diff.toDelete)
        assertEquals(2, diff.totalWrites)
    }
}

/**
 * 触发器区的纯投影单测（阶段 6e）。
 *
 * 全部文案都是**稳定契约**：真机判读靠 `findstr` 它们，随手改字会让验证命令失效
 * （与 `TabContent.logKey` / `ScriptProjections` 同一条纪律）。
 */
class TriggerEditorProjectionsTest {
    @Test
    @DisplayName("计数行三态：未知显示 …（**不编 0**），已知显示 N/14")
    fun `the count line never fakes a zero`() {
        assertEquals("触发器（…/14）", TriggerEditorProjections.countLine(null))
        assertEquals("触发器（0/14）", TriggerEditorProjections.countLine(0))
        assertEquals("触发器（2/14）", TriggerEditorProjections.countLine(2))
        assertEquals(
            ScriptProjections.COUNT_UNKNOWN,
            TriggerEditorProjections.COUNT_UNKNOWN,
            "两处「未知」占位必须同值，否则同一屏会出现两种未知",
        )
    }

    @Test
    @DisplayName("time 摘要：两位补零 + 精确档标注")
    fun `the time summary is zero padded`() {
        assertEquals(
            "每天 07:05",
            TriggerEditorProjections.summary(SystemEvent.TIME, TriggerParams(hourOfDay = 7, minuteOfHour = 5)),
        )
        assertEquals(
            "每天 23:59（精确）",
            TriggerEditorProjections.summary(
                SystemEvent.TIME,
                TriggerParams(hourOfDay = 23, minuteOfHour = 59, exact = true),
            ),
        )
    }

    @Test
    @DisplayName("time 未配置时摘要说「未配置时刻」，不是「每天 00:00」")
    fun `an unconfigured time says so`() {
        val summary = TriggerEditorProjections.summary(SystemEvent.TIME, TriggerParams())
        assertTrue(summary.contains("未配置"), "实际=$summary")
        assertFalse(summary.contains("00:00"), "不得把「没配」显示成一个具体时刻")
    }

    @Test
    @DisplayName("interval 摘要：每 N 分钟 / 未配置")
    fun `the interval summary reports minutes`() {
        assertEquals(
            "每 30 分钟",
            TriggerEditorProjections.summary(SystemEvent.INTERVAL, TriggerParams(intervalMinutes = 30)),
        )
        assertTrue(
            TriggerEditorProjections
                .summary(SystemEvent.INTERVAL, TriggerParams())
                .contains("未配置"),
        )
    }

    @Test
    @DisplayName("app 事件摘要说明「全部应用」（包名筛选本阶段不做，必须如实说）")
    fun `app events say all applications`() {
        listOf(SystemEvent.APP_FOREGROUND, SystemEvent.APP_BACKGROUND).forEach { id ->
            assertEquals("全部应用", TriggerEditorProjections.summary(id, TriggerParams()), id)
        }
    }

    @Test
    @DisplayName("无参事件：有 payload 说「自定义负载」，否则「无参数」")
    fun `parameterless events distinguish a custom payload`() {
        assertEquals("无参数", TriggerEditorProjections.summary(SystemEvent.BOOT, TriggerParams()))
        assertEquals(
            "自定义负载",
            TriggerEditorProjections.summary(SystemEvent.BOOT, TriggerParams(payload = "x")),
        )
    }

    @Test
    @DisplayName("权限提示条：未授予时说清「配置会保存、但不会触发」，并给「去授权」")
    fun `the permission hint explains the consequence`() {
        val spec = EventCatalog.spec(SystemEvent.APP_FOREGROUND)
        assertNotNull(spec)
        val hint = TriggerEditorProjections.permissionHint(spec!!, TriggerChipAccess.NEEDS_PERMISSION)
        assertNotNull(hint)
        assertTrue(hint!!.contains("前台应用"), "文案必须点名是哪个事件：$hint")
        assertTrue(hint.contains("使用情况访问"), "必须给中文权限名而不是 android.permission.*：$hint")
        assertTrue(hint.contains("不会触发"), "必须说清后果：$hint")
        assertTrue(TriggerEditorProjections.canGrant(TriggerChipAccess.NEEDS_PERMISSION))
    }

    @Test
    @DisplayName("NOT_APPLICABLE：说「本系统版本不提供入口」，且**不给**跳转按钮")
    fun `not applicable is explained without a button`() {
        val spec = EventCatalog.spec(SystemEvent.APP_FOREGROUND)!!
        val hint = TriggerEditorProjections.permissionHint(spec, TriggerChipAccess.NOT_APPLICABLE)
        assertTrue(hint!!.contains("不可用"), "实际=$hint")
        assertFalse(
            TriggerEditorProjections.canGrant(TriggerChipAccess.NOT_APPLICABLE),
            "无处可授就不该给按钮（点了只会到一个没有该开关的页面）",
        )
    }

    @Test
    @DisplayName("UNKNOWN：**不得谎称已授予**，也不给跳转")
    fun `an unknown state never claims the permission is granted`() {
        val spec = EventCatalog.spec(SystemEvent.APP_FOREGROUND)!!
        val hint = TriggerEditorProjections.permissionHint(spec, TriggerChipAccess.UNKNOWN)
        assertTrue(hint!!.contains("未知"), "实际=$hint")
        assertFalse(TriggerEditorProjections.canGrant(TriggerChipAccess.UNKNOWN))
    }

    @Test
    @DisplayName("OK / 无权限事件：**没有**提示条（不能给一个永远亮着的警告）")
    fun `a granted permission produces no hint`() {
        val spec = EventCatalog.spec(SystemEvent.APP_FOREGROUND)!!
        assertNull(TriggerEditorProjections.permissionHint(spec, TriggerChipAccess.OK))
        val noPermission = EventCatalog.spec(SystemEvent.BOOT)!!
        assertNull(TriggerEditorProjections.permissionHint(noPermission, TriggerChipAccess.NEEDS_PERMISSION))
    }

    @Test
    @DisplayName("单条失败文案带事件中文名（用户要知道是哪一条没存上）")
    fun `a single failure names the event`() {
        val message = TriggerEditorProjections.saveFailed(label = "间隔", reason = "db locked")
        assertTrue(message.contains("间隔"))
        assertTrue(message.contains("db locked"))
    }

    @Test
    @DisplayName("固定说明齐全（立即保存 / 熔断语义 / 新建脚本 / payload 提示）")
    fun `the fixed notices are all present and non blank`() {
        assertTrue(TriggerEditorProjections.PARAMS_FOOTNOTE.contains("立即保存"))
        assertTrue(TriggerEditorProjections.SAFE_MODE_FOOTNOTE.contains("安全熔断"))
        assertTrue(TriggerEditorProjections.NEW_SCRIPT_NOTICE.contains("先保存脚本"))
        assertTrue(TriggerEditorProjections.PAYLOAD_HINT.contains("空"))
        assertTrue(TriggerEditorProjections.PAYLOAD_NEWLINE_STRIPPED.contains("换行"))
    }

    // ---------------------------------------------------------------- P5：事件区折叠（§5.2）

    @Test
    @DisplayName("P5：折叠摘要三态 —— 计数未知显示 … 而不是 0")
    fun `the events section summary keeps three states`() {
        assertEquals(
            "…",
            TriggerEditorProjections.eventsSectionSummary(null),
            "计数未知 ⇒ 省略号（与 TriggerCounts 同款三态纪律：不编 0）",
        )
        assertEquals("未订阅", TriggerEditorProjections.eventsSectionSummary(0))
        assertEquals("已订阅 1 个", TriggerEditorProjections.eventsSectionSummary(1))
        assertEquals("已订阅 3 个", TriggerEditorProjections.eventsSectionSummary(3))
    }

    @Test
    @DisplayName("P5：折叠区文案齐全（标题必须写明「可选」，提示必须给出取值方式）")
    fun `the events section copy is complete`() {
        assertTrue(
            TriggerEditorProjections.EVENTS_SECTION_TITLE.contains("可选"),
            "标题必须写明「可选」—— 否则新用户会以为没勾事件脚本就不会跑：" +
                TriggerEditorProjections.EVENTS_SECTION_TITLE,
        )
        assertTrue(
            TriggerEditorProjections.EVENTS_SECTION_HINT.contains("投给"),
            "必须说清这些勾选做什么用：" + TriggerEditorProjections.EVENTS_SECTION_HINT,
        )
        assertTrue(
            TriggerEditorProjections.EVENTS_READ_HINT.contains("ROOTFLOW_EVENT_FIFO"),
            "必须给出脚本侧的取值方式（P5 起事件经 FIFO 投递）：" +
                TriggerEditorProjections.EVENTS_READ_HINT,
        )
    }

    @Test
    @DisplayName("重复行清理提示带条数（用户会看到行数变少，必须解释）")
    fun `the duplicate cleanup message carries the count`() {
        assertTrue(TriggerEditorProjections.duplicatesCleaned(2).contains("2"))
    }
}

/**
 * 底栏弹性参数的单测（阶段 6e）。
 *
 * 动画值抽成纯函数的原因与其它投影一致：Composable 在纯 JVM 下不可测（决策 B）。
 */
class NavBarElasticTest {
    @Test
    @DisplayName("静止 ⇒ 常态宽度；完全按下 ⇒ 拉宽 15%")
    fun `progress drives the width fraction`() {
        assertEquals(
            0.85f,
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(0f),
            0.0001f,
        )
        assertEquals(
            1.0f,
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(1f),
            0.0001f,
        )
        assertEquals(
            0.925f,
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(0.5f),
            0.0001f,
        )
    }

    @Test
    @DisplayName("常量关系：常态 + 增益 == 1（防止将来只改一个而「没拉满」或「溢出屏幕」）")
    fun `the rest fraction plus the gain is exactly one`() {
        assertEquals(
            1.0f,
            com.rootflow.ui.component.NavBarElastic.REST_WIDTH_FRACTION +
                com.rootflow.ui.component.NavBarElastic.PRESS_WIDTH_GAIN,
            0.0001f,
        )
    }

    @Test
    @DisplayName("越界进度被 clamp（`fillMaxWidth` 的 fraction 有 require(in 0..1)，越界会抛）")
    fun `out of range progress is clamped`() {
        assertEquals(
            0.85f,
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(-1f),
            0.0001f,
        )
        assertEquals(
            1.0f,
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(2f),
            0.0001f,
        )
        assertEquals(
            0f,
            com.rootflow.ui.component.NavBarElastic
                .liftDp(-3f),
            0.0001f,
        )
        assertEquals(
            com.rootflow.ui.component.NavBarElastic.LIFT_DP,
            com.rootflow.ui.component.NavBarElastic
                .liftDp(9f),
            0.0001f,
        )
    }

    @Test
    @DisplayName("宽度随进度**单调不减**（动画中途不会出现「先变窄」的抖动）")
    fun `the width is monotonic in progress`() {
        var previous =
            com.rootflow.ui.component.NavBarElastic
                .widthFraction(0f)
        var progress = 0.05f
        while (progress <= 1f) {
            val current =
                com.rootflow.ui.component.NavBarElastic
                    .widthFraction(progress)
            assertTrue(current >= previous, "progress=$progress 处宽度回退了")
            previous = current
            progress += 0.05f
        }
    }

    @Test
    @DisplayName("上浮：静止 0、按下 LIFT_DP，且与宽度用同一个进度")
    fun `the lift follows the same progress`() {
        assertEquals(
            0f,
            com.rootflow.ui.component.NavBarElastic
                .liftDp(0f),
            0.0001f,
        )
        assertEquals(
            com.rootflow.ui.component.NavBarElastic.LIFT_DP,
            com.rootflow.ui.component.NavBarElastic
                .liftDp(1f),
            0.0001f,
        )
    }

    @Test
    @DisplayName("★ 回退后的静止态常量：横向 20dp / 纵向 12dp / 上浮 0（生产用的是这三个）")
    fun `the rolled back rest geometry is fixed`() {
        val elastic = com.rootflow.ui.component.NavBarElastic
        // 用户裁定回退弹性（2026-09-20）：底栏恢复 6a 起的固定几何，且**无动画**。
        // 这三条断言守的就是"回退后不会悄悄又被改成动态值"。
        assertEquals(20f, elastic.REST_HORIZONTAL_PADDING_DP, 0.0001f)
        assertEquals(12f, elastic.REST_VERTICAL_PADDING_DP, 0.0001f)
        assertEquals(0f, elastic.REST_LIFT_DP, 0.0001f)
        assertEquals(
            elastic.REST_LIFT_DP,
            elastic.liftDp(0f),
            0.0001f,
            "静止态的上浮必须与生产常量一致（否则回退不彻底）",
        )
    }
}
