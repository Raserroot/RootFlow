package com.rootflow.ui.scripts

import androidx.lifecycle.SavedStateHandle
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionState
import com.rootflow.domain.model.Script
import com.rootflow.domain.repository.ScriptLoadResult
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.WriteResult
import com.rootflow.ui.AlertSink
import com.rootflow.ui.settings.FakePermissionStatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ScriptEditorViewModel] 的单测（阶段 6c）。
 *
 * ## 覆盖的核心语义
 * 1. **四种加载失败各一条文案**（折叠成"加载失败"会让真机排障变成一轮猜测）
 * 2. **校验失败不调仓库**（否则会出现"保存被拒但库里多了半条记录"）
 * 3. **`dirty` 算出来**：改回原值必须回到 `false`（否则预测性返回一直弹框）
 * 4. **保存失败表单保留**、**保存失败后可再保存**（不粘住）
 * 5. 防连点：`saving` 期间重复提交被忽略（否则插两条）
 *
 * ## 路由参数
 * 直接构造 `SavedStateHandle`（`NavBackStackEntry` 的默认工厂在生产路径上做的
 * 就是"把路由参数塞进 `SavedStateHandle`"这一件事）。真正的参数解析边界
 * （`"abc"` / `"-1"` / `"0"`）由纯函数测试 [ScriptEditorArgsTest] 穷举。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptEditorViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var scripts: FakeScriptRepository
    private lateinit var triggers: FakeTriggerRepository

    /**
     * 权限假件（阶段 6e）。
     *
     * 复用设置页那份 `ui.settings.FakePermissionStatusProvider` —— 两份假件会让
     * "同一个端口在两个页面表现不同"成为一个静默的测试偏差（本仓库反复禁止的形态）。
     * 默认空快照 = 已探测但目录里什么都没有 ⇒ 全部事件无徽标。
     */
    private lateinit var permissions: FakePermissionStatusProvider

    /**
     * ViewModel 的告警记录（**构造形参** `onWarning` 的落点，阶段 6e 起）。
     *
     * ## ★ 为什么告警列表必须**先于构造**存在
     * `ScriptEditorViewModel` 的 `init` **在构造期间**就会告警（无效路由参数、四种加载失败）。
     * 6b 的老写法 `.also { it.onWarning = … }` 是在**构造之后**才接线的
     * ⇒ `init` 里那几条告警落进默认 no-op，断言拿到空列表。
     *
     * 6e 把 `onWarning` 改成**构造形参**之后，这条纪律不再靠"写法技巧"维持：
     * 缝在构造调用的实参里就被传进去了 —— **把它改回 `var` 会编译不过**。
     *
     * 本字段（`WarningSink` 实例）仍需**字段初始化**，因为它要在 `newViewModel()` 之前存在
     * 才能被 lambda 捕获。
     *
     * 这不是"测试写法"的细节，而是**接缝必须早于被测对象开始工作**这一条通用纪律 ——
     * 同一形态在真机上就是"日志开关晚于故障打开"，症状是"日志里什么都没有"
     * （6e 真机验证抓到的"生产从未接线"正是它的一个真实变体）。
     */
    private val sink = WarningSink()

    private val warnings: MutableList<String> get() = sink.messages

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scripts = FakeScriptRepository()
        triggers = FakeTriggerRepository()
        permissions = FakePermissionStatusProvider()
        sink.clear()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ 新建

    @Test
    @DisplayName("新建：默认表单（id=0 / shell / 启用 / timeout=0 / 正文空），不调 load")
    fun `a new script starts from the default form`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isNew)
            assertEquals(0L, state.form.id)
            assertEquals("shell", state.form.language)
            assertTrue(state.form.enabled, "新建脚本的意图就是让它生效")
            assertEquals("0", state.form.timeoutSec)
            assertEquals("", state.form.content)
            assertFalse(state.dirty, "刚打开时没有任何未保存改动")
            assertFalse(state.loading)
        }

    // ------------------------------------------------------------ 编辑装载

    @Test
    @DisplayName("编辑：load 成功 ⇒ 表单填满（含正文）")
    fun `editing fills the form from the loaded script`() =
        runTest(testDispatcher) {
            scripts.stored.value =
                listOf(
                    testScript(
                        id = 5,
                        name = "夜间备份",
                        timeoutSec = 120,
                        autoDisableOnFail = true,
                        runOnSafeMode = true,
                        content = "echo night",
                    ),
                )
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isNew)
            assertEquals(5L, state.form.id)
            assertEquals("夜间备份", state.form.name)
            assertEquals("120", state.form.timeoutSec)
            assertTrue(state.form.autoDisableOnFail)
            assertTrue(state.form.runOnSafeMode)
            assertEquals("echo night", state.form.content)
            assertFalse(state.dirty, "装载完成的表单不是「未保存改动」")
            assertNull(state.loadError)
        }

    @Test
    @DisplayName("★ 四种加载失败各有一条独立文案")
    fun `each load failure maps to its own message`() =
        runTest(testDispatcher) {
            val cases =
                listOf(
                    ScriptLoadResult.Missing to ScriptLoadFailure.MISSING,
                    ScriptLoadResult.Corrupted("sha256:a", "sha256:b") to ScriptLoadFailure.CORRUPTED,
                    ScriptLoadResult.NotFound to ScriptLoadFailure.NOT_FOUND,
                    ScriptLoadResult.Unavailable("cat failed (exit=1)") to ScriptLoadFailure.UNAVAILABLE,
                )

            cases.forEach { (result, expected) ->
                scripts = FakeScriptRepository()
                sink.clear()
                scripts.stored.value = listOf(testScript(id = 5))
                scripts.loadResults[5L] = result

                val viewModel = newViewModel(idArg = "5")
                advanceUntilIdle()

                assertEquals(expected, viewModel.uiState.value.loadError, "结果=$result")
                assertTrue(
                    warnings.any { it.contains("SCRIPTS_EDITOR_LOAD_FAILED") },
                    "装载失败必须告警（四种成因都要留痕），实际=$warnings",
                )
                assertFalse(viewModel.uiState.value.canSave, "装载失败时不得允许保存（正文都没读到）")
            }
        }

    @Test
    @DisplayName("四条失败文案互不相同（折叠成一句就无法定位）")
    fun `the four failure messages are pairwise distinct`() =
        runTest(testDispatcher) {
            val messages = ScriptLoadFailure.entries.map { it.message }
            assertEquals(messages.size, messages.toSet().size, "四种成因的文案必须互不相同：$messages")
            messages.forEach { message ->
                assertTrue(message.isNotBlank())
            }
        }

    // ------------------------------------------------------------ 校验

    @Test
    @DisplayName("名称为空白 ⇒ 校验失败且**不调 save**")
    fun `a blank name blocks the save before it reaches the repository`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onBodyChange("echo hi")

            viewModel.save()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errors
                    .any { it.field == ScriptFormField.NAME },
                "必须把错误落在名称字段上",
            )
            assertTrue(scripts.saveCalls.isEmpty(), "本地校验失败不得调用仓库")
        }

    @Test
    @DisplayName("正文为空白 ⇒ 校验失败且不调 save")
    fun `a blank body blocks the save`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")

            viewModel.save()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errors
                    .any { it.field == ScriptFormField.BODY },
            )
            assertTrue(scripts.saveCalls.isEmpty())
        }

    @Test
    @DisplayName("timeoutSec 非法 ⇒ 校验失败且不调 save")
    fun `a negative timeout blocks the save`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")
            viewModel.onBodyChange("echo hi")
            viewModel.onTimeoutChange("-5")

            viewModel.save()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errors
                    .any { it.field == ScriptFormField.TIMEOUT },
            )
            assertTrue(scripts.saveCalls.isEmpty())
        }

    @Test
    @DisplayName("合法 ⇒ save 恰一次，且名称已 trim")
    fun `a valid form saves exactly once with a trimmed name`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("  备份  ")
            viewModel.onBodyChange("echo hi")
            viewModel.onTimeoutChange("0")

            viewModel.save()
            advanceUntilIdle()

            val saved = scripts.saveCalls.single()
            assertEquals("备份", saved.name, "首尾空格不该入库（否则列表里两条「看起来一样」的脚本无法区分）")
            assertEquals("echo hi", saved.content)
            assertEquals(0, saved.timeoutSec)
        }

    // ------------------------------------------------------------ ★ 危险指令闸门（12a 的 UI 接入）

    /** 必然命中的正文（`rm -rf /` 是扫描器的最高危规则）。 */
    private val dangerousBody = "rm -rf /"

    @Test
    @DisplayName("★ 危险正文：保存被挂起（弹确认），**不落库**")
    fun `a dangerous body opens the gate instead of saving`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("清理")
            viewModel.onBodyChange(dangerousBody)

            viewModel.save()
            advanceUntilIdle()

            val prompt =
                requireNotNull(viewModel.uiState.value.pendingDanger) { "命中危险指令必须弹确认框" }
            assertEquals(listOf(1), prompt.lines, "弹窗要能精确指到行号（用户按行找得到）")
            assertTrue(scripts.saveCalls.isEmpty(), "★ 用户没确认之前，一个字节都不得落库")
            assertTrue(
                warnings.any { it.contains("SCRIPTS_SAVE_DANGER_GATE") },
                "闸门必须留痕，否则真机上'为什么这次没存下去'无从判读",
            )
        }

    @Test
    @DisplayName("★ 继续保存：确认后落库一次，弹窗关闭，且失败面仍走同一条路径")
    fun `confirming the danger saves exactly once through the same path`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("清理")
            viewModel.onBodyChange(dangerousBody)
            viewModel.save()
            advanceUntilIdle()
            assertTrue(scripts.saveCalls.isEmpty())

            viewModel.confirmDangerAndSave()
            advanceUntilIdle()

            assertEquals(1, scripts.saveCalls.size, "确认之后必须真的落库（扫描器不阻断保存，用户裁定）")
            assertEquals(dangerousBody, scripts.saveCalls.single().content, "存下去的必须是原正文，不得被改写")
            assertNull(viewModel.uiState.value.pendingDanger, "确认后弹窗必须关闭")
            assertTrue(
                warnings.any { it.contains("SCRIPTS_SAVE_DANGER_CONFIRMED") },
                "确认也要留痕（与 GATE / DISMISSED 一起构成完整的三态日志）",
            )
        }

    @Test
    @DisplayName("让我再想想：不落库、弹窗关闭、正文与未保存状态都保留")
    fun `dismissing the danger keeps the form and saves nothing`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("清理")
            viewModel.onBodyChange(dangerousBody)
            viewModel.save()
            advanceUntilIdle()

            viewModel.dismissDanger()
            advanceUntilIdle()

            assertTrue(scripts.saveCalls.isEmpty(), "再想想 = 什么都不写")
            assertNull(viewModel.uiState.value.pendingDanger)
            assertEquals(dangerousBody, viewModel.uiState.value.form.content, "正文必须原样留着（这正是'再想想'的意思）")
            assertTrue(viewModel.dirty.value, "改动仍未保存 ⇒ dirty 必须保持为真（否则返回时不会提示）")
            assertTrue(warnings.any { it.contains("SCRIPTS_SAVE_DANGER_DISMISSED") })
        }

    @Test
    @DisplayName("安全正文：不弹窗（闸门不得变成噪音）")
    fun `a safe body never opens the gate`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("备份")
            viewModel.onBodyChange("cp -a /data/local/tmp/a /data/local/tmp/b\nls -l /sdcard")

            viewModel.save()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.pendingDanger, "常见写法不得弹窗 —— 弹窗变噪音后真正的危险会被一起放过")
            assertEquals(1, scripts.saveCalls.size)
        }

    @Test
    @DisplayName("★ 危险正文 + 字段非法：先报字段错误，**不弹危险框**（顺序）")
    fun `validation outranks the danger gate`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            // 名称留空（字段级阻断）+ 正文危险
            viewModel.onBodyChange(dangerousBody)

            viewModel.save()
            advanceUntilIdle()

            assertNull(
                viewModel.uiState.value.pendingDanger,
                "字段级错误更前置：先告诉用户'存不了'，而不是先弹一个'你要不要存'",
            )
            assertTrue(
                viewModel.uiState.value.errors
                    .any { it.field == ScriptFormField.NAME },
            )
            assertTrue(scripts.saveCalls.isEmpty())
        }

    @Test
    @DisplayName("lua 语言被拒（v1 只实现 shell）")
    fun `lua is rejected by validation`() =
        runTest(testDispatcher) {
            val form = ScriptForm.New.copy(name = "x", content = "echo hi", language = "lua")

            val errors = ScriptFormValidation.validate(form)

            assertTrue(
                errors.any { it.field == ScriptFormField.LANGUAGE },
                "lua 必须被拒（需求 §3.1 + §10），实际=$errors",
            )
        }

    // ------------------------------------------------------------ dirty

    @Test
    @DisplayName("★ dirty 随字段置位，改回原值必须复位（否则预测性返回一直弹框）")
    fun `dirty clears when the form returns to its baseline`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 5, name = "原名", content = "echo old"))
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            assertFalse(viewModel.dirty.value)

            viewModel.onNameChange("改名")
            advanceUntilIdle()
            assertTrue(viewModel.dirty.value, "改过就必须是 dirty")

            viewModel.onNameChange("原名")
            advanceUntilIdle()
            assertFalse(
                viewModel.dirty.value,
                "改回原值必须复位 —— 否则用户永远退不出「未保存」，返回手势每次弹框（方案 §3.4）",
            )
        }

    @Test
    @DisplayName("保存成功后 dirty 复位（不留残余的未保存状态）")
    fun `dirty resets after a successful save`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")
            viewModel.onBodyChange("echo hi")
            advanceUntilIdle()
            assertTrue(viewModel.dirty.value)

            viewModel.save()
            advanceUntilIdle()

            assertFalse(viewModel.dirty.value, "保存成功后不得再报「未保存」")
        }

    // ------------------------------------------------------------ 保存失败面

    @Test
    @DisplayName("save Failed ⇒ 表单保留 + 告警（用户的输入不能丢）")
    fun `a failed save keeps the form and warns`() =
        runTest(testDispatcher) {
            scripts.saveFailure = "disk full"
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")
            viewModel.onBodyChange("echo hi")

            viewModel.save()
            advanceUntilIdle()

            assertEquals("x", viewModel.uiState.value.form.name, "失败后表单必须原样保留")
            assertEquals("echo hi", viewModel.uiState.value.form.content)
            assertTrue(warnings.any { it.contains("SCRIPTS_SAVE_FAILED") }, "实际=$warnings")
        }

    @Test
    @DisplayName("保存失败后可以再保存（不粘住）")
    fun `a failed save does not stick`() =
        runTest(testDispatcher) {
            scripts.saveFailure = "disk full"
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")
            viewModel.onBodyChange("echo hi")
            viewModel.save()
            advanceUntilIdle()
            assertEquals(1, scripts.saveCalls.size)

            scripts.saveFailure = null
            viewModel.save()
            advanceUntilIdle()

            assertEquals(2, scripts.saveCalls.size, "失败后必须还能再保存")
            assertFalse(viewModel.uiState.value.saving, "saving 必须在 finally 里复位")
        }

    @Test
    @DisplayName("新建保存成功后携带新 id（第二次保存走 update 而不是 insert）")
    fun `a new script gets a real id after saving`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()
            viewModel.onNameChange("x")
            viewModel.onBodyChange("echo hi")

            viewModel.save()
            advanceUntilIdle()

            val form = viewModel.uiState.value.form
            assertTrue(form.id > 0L, "保存后表单必须拿到真实 id（否则连点两次会插两条），实际=${form.id}")
            assertFalse(viewModel.uiState.value.dirty)
        }

    @Test
    @DisplayName("保存抛异常 ⇒ 降级为告警，不让编辑器崩")
    fun `a crashing save is degraded to a warning`() =
        runTest(testDispatcher) {
            val crashingViewModel =
                ScriptEditorViewModel(
                    savedStateHandle = SavedStateHandle(),
                    scripts = CrashingScriptRepository(),
                    triggers = triggers,
                    permissionStatus = permissions,
                    alertSink = AlertSink { message -> sink.messages += message },
                )
            crashingViewModel.onNameChange("x")
            crashingViewModel.onBodyChange("echo hi")

            crashingViewModel.save()
            advanceUntilIdle()

            assertTrue(warnings.any { it.contains("SCRIPTS_SAVE_CRASHED") }, "实际=$warnings")
            assertFalse(crashingViewModel.uiState.value.saving, "异常路径也必须复位 saving")
        }

    // ------------------------------------------------------------ 触发器摘要

    @Test
    @DisplayName("编辑器读一次触发器总数（只读摘要，勾选推 6e）")
    fun `the editor reads the trigger count once`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 5))
            triggers.triggers.value = List(2) { index -> testTrigger(id = index + 1L, scriptId = 5) }
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            assertEquals(2, viewModel.triggerTotal.value)
            assertEquals(1, triggers.countCalls, "只读一次（本页不能改触发器，无需订阅）")
        }

    @Test
    @DisplayName("触发器计数读失败不阻塞编辑（摘要说未知，正文照常可改）")
    fun `a failing trigger count does not block editing`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 5, content = "echo hi"))
            triggers.countFailure = IllegalStateException("db boom")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            assertNull(viewModel.triggerTotal.value, "读不到就必须说未知，不编 0")
            assertEquals(
                "触发器：" + ScriptProjections.COUNT_UNKNOWN,
                viewModel.uiState.value.triggerSummaryLine(viewModel.triggerTotal.value),
            )
            viewModel.onBodyChange("echo changed")
            // `dirty` 是 map 出来的流 ⇒ 必须让虚拟时间推进到它发射（不同步更新）
            advanceUntilIdle()
            assertTrue(viewModel.dirty.value, "计数失败不得影响编辑")
            assertTrue(warnings.any { it.contains("SCRIPTS_EDITOR_TRIGGER_COUNT_FAILED") }, "实际=$warnings")
        }

    // ------------------------------------------------------------ 路由参数提示

    @Test
    @DisplayName("路由参数无效 ⇒ 按新建打开 + 发出可见提示（不崩、不静默）")
    fun `an invalid route argument falls back to a new script`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = "abc")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isNew, "无效参数必须回落新建")
            assertNull(viewModel.uiState.value.loadError, "无效参数不是装载失败")
            assertEquals(
                ScriptEditorViewModel.ROUTE_INVALID_MESSAGE,
                viewModel.lastMessage.value,
                "无效参数必须给一条用户可见的提示，而不是静默当没事发生",
            )
        }

    // ------------------------------------------------------------ 6e：触发器勾选

    /**
     * 6e 的触发器用例共用的种子：**已保存的脚本 5 + 它的一条 `boot` 触发器**。
     *
     * 用 `suspend` 而不是普通函数：写库与首帧热流都要经 `advanceUntilIdle()` 才稳定。
     */
    private fun seedSavedScriptWithTriggers(vararg eventTypes: String): List<com.rootflow.domain.model.Trigger> {
        scripts.stored.value = listOf(testScript(id = 5))
        val seeded =
            eventTypes.mapIndexed { index, eventType ->
                testTrigger(id = (index + 1).toLong(), scriptId = 5, eventType = eventType)
            }
        triggers.triggers.value = seeded
        return seeded
    }

    @Test
    @DisplayName("6e：首帧之前 rows 为 null（未加载 ≠ 一条都没有）")
    fun `the trigger editor starts unloaded`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = "5")
            // 刻意**不** advanceUntilIdle：这就是"还没读到第一帧"
            assertEquals(null, viewModel.triggerEditor.value.rows, "冷启动必须能表达「还不知道」")
            assertFalse(viewModel.triggerEditor.value.loaded)
        }

    @Test
    @DisplayName("6e：热流到位后 chip 选中态与库一致，canEdit=true")
    fun `the chips reflect the stored rows`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers("boot", "screen_off")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            val state = viewModel.triggerEditor.value
            assertTrue(state.loaded)
            assertEquals(setOf("boot", "screen_off"), state.selected)
            assertEquals(2, state.count)
            assertTrue(state.canEdit, "已保存的脚本可以配触发器")
        }

    @Test
    @DisplayName("6e：新建脚本 canEdit=false（触发器必须有 scriptId 才能落库）")
    fun `a new script cannot edit triggers`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()

            assertFalse(viewModel.triggerEditor.value.canEdit)
        }

    @Test
    @DisplayName("6e：新建脚本上点 chip ⇒ 只给提示，**不写库**")
    fun `toggling on a new script only warns`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel(idArg = null)
            advanceUntilIdle()

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()

            assertTrue(triggers.saveCalls.isEmpty(), "还没有 scriptId，写了就是孤儿行")
            assertEquals(TriggerEditorProjections.NEW_SCRIPT_NOTICE, viewModel.lastMessage.value)
        }

    @Test
    @DisplayName("6e：勾选 ⇒ 库里恰多一条生效的订阅（enabled / createdAt / id 都已就位）")
    fun `selecting writes exactly one enabled row`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers()
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()

            // ★ 断言对象是**库里的集合**（新语义的唯一真相）。旧模型断言的是
            //   "逐条 save 的调用形状"——那形状已经不存在了（一次覆盖式写入）。
            val stored = triggers.triggers.value
            assertEquals(1, stored.size, "恰一条：$stored")
            val saved = stored.single()
            assertEquals(5L, saved.scriptId)
            assertEquals("boot", saved.eventType)
            assertTrue(saved.enabled, "新勾选的订阅默认启用")
            assertTrue(saved.createdAt > 0L, "createdAt 必须被补上（0 会被 Room 当成未设置）")
            assertTrue(saved.id > 0L, "落库后必须拿到自增 id")
            assertEquals(setOf("boot"), viewModel.triggerEditor.value.selected, "热流推回后 chip 应选中")
        }

    @Test
    @DisplayName("6e：取消勾选 ⇒ 该事件的行不再存在（整份集合被替换，不是逐条删）")
    fun `deselecting deletes the stored row`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers("boot")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()

            assertTrue(triggers.triggers.value.isEmpty(), "库里的订阅集合必须变空：${triggers.triggers.value}")
            assertTrue(
                viewModel.triggerEditor.value.selected
                    .isEmpty(),
            )
        }

    @Test
    @DisplayName("6e：连点两次同一 chip ⇒ 回到原状态（幂等，不留垃圾行）")
    fun `double toggling returns to the original state`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers()
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()
            assertTrue(triggers.triggers.value.isNotEmpty(), "前置：第一次点击建立了订阅")

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()

            assertTrue(
                viewModel.triggerEditor.value.selected
                    .isEmpty(),
            )
            assertTrue(
                triggers.triggers.value.isEmpty(),
                "两次点击后库里必须**干干净净**（这正是集合替换要保证的：不留垃圾行）：" +
                    "${triggers.triggers.value}",
            )
        }

    @Test
    @DisplayName("6e：写库失败 ⇒ chip **不置位**（不乐观更新）+ 一条含事件名的提示")
    fun `a failing insert leaves the chip unselected`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers()
            triggers.failSaveForEvents += "boot"
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()

            assertTrue(
                viewModel.triggerEditor.value.selected
                    .isEmpty(),
                "库里没这一行 ⇒ chip 必须是未选中（乐观更新会造出「界面开着、库里关着」）",
            )
            assertTrue(triggers.triggers.value.isEmpty(), "整体失败 ⇒ 一条都不该落库：${triggers.triggers.value}")
            // ★ 文案契约变了：集合替换下失败是**整体**的，因此没有"有 N/M 项未生效"这种说法
            //   （那是旧的逐条写入才需要的措辞）。现在必须说清"这一条没存上 + 原因"。
            val message = viewModel.lastMessage.value ?: ""
            assertTrue(message.contains("未保存"), "必须说清没存上：实际=$message")
            assertTrue(message.contains("开机"), "提示必须点名是哪个事件：实际=$message")
            assertFalse(message.contains("未生效"), "旧的部分失败措辞不该再出现：实际=$message")
            assertTrue(
                warnings.any { it.contains("SCRIPTS_TRIGGER_SELECT FAILED") && it.contains("event=boot") },
                "告警必须点名是哪个事件（提示文案给事件名，告警给事件键）：实际=$warnings",
            )
        }

    @Test
    @DisplayName("6e：参数写入**不**让表单变脏（触发器是立即保存的）")
    fun `editing trigger params never marks the form dirty`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.dirty)

            viewModel.onTriggerIntervalChange(seeded.single().id, "30")
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.dirty,
                "触发器不进 dirty：否则只点了触发器就会弹「放弃未保存的修改？」",
            )
            assertEquals(
                30,
                triggers.saveCalls
                    .single()
                    .params.intervalMinutes,
            )
        }

    @Test
    @DisplayName("6e：参数防抖 —— 399ms 不写、400ms 才写；连打只写最后一次")
    fun `parameter writes are debounced`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            viewModel.debounceMs = ScriptEditorViewModel.PARAMS_DEBOUNCE_MS_DEFAULT
            val id = seeded.single().id

            viewModel.onTriggerIntervalChange(id, "1")
            viewModel.onTriggerIntervalChange(id, "12")
            advanceTimeBy(ScriptEditorViewModel.PARAMS_DEBOUNCE_MS_DEFAULT - 1)
            runCurrent()
            assertTrue(triggers.saveCalls.isEmpty(), "防抖窗口内不得写库")

            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals(1, triggers.saveCalls.size, "连打只该写一次")
            assertEquals(
                12,
                triggers.saveCalls
                    .single()
                    .params.intervalMinutes,
                "写的是最后一次输入",
            )
        }

    @Test
    @DisplayName("6e：flushPendingParams ⇒ 不等防抖立即写（覆盖「输完立刻返回」）")
    fun `flushing writes the pending draft immediately`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            viewModel.debounceMs = ScriptEditorViewModel.PARAMS_DEBOUNCE_MS_DEFAULT
            val id = seeded.single().id

            viewModel.onTriggerIntervalChange(id, "45")
            // 不推进虚拟时间：此刻防抖作业还在 delay 里
            viewModel.flushPendingParams(id)
            advanceUntilIdle()

            assertEquals(
                45,
                triggers.saveCalls
                    .single()
                    .params.intervalMinutes,
            )
        }

    @Test
    @DisplayName("6e：非法参数（间隔 0）**不落库**，并留一条告警")
    fun `an invalid interval is refused without writing`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.onTriggerIntervalChange(seeded.single().id, "0")
            advanceUntilIdle()

            assertTrue(triggers.saveCalls.isEmpty(), "非法值不得被静默换成默认值写进去")
            assertTrue(
                warnings.any { it.contains("SCRIPTS_TRIGGER_PARAMS_INVALID") },
                "实际=$warnings",
            )
        }

    @Test
    @DisplayName("6e：payload 换行被剔除且给提示，落库的是剔除后的值")
    fun `payload newlines are stripped before persisting`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("boot")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.onTriggerPayloadChange(seeded.single().id, "a\nb")
            advanceUntilIdle()

            assertEquals(
                "ab",
                triggers.saveCalls
                    .single()
                    .params.payload,
            )
            assertEquals(
                TriggerEditorProjections.PAYLOAD_NEWLINE_STRIPPED,
                viewModel.triggerEditor.value.paramsNotice,
            )
        }

    @Test
    @DisplayName("6e：行被删掉后仍在飞的参数写入**不产生孤儿参数**")
    fun `a pending param write is dropped when the row disappears`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            viewModel.debounceMs = ScriptEditorViewModel.PARAMS_DEBOUNCE_MS_DEFAULT
            val id = seeded.single().id

            viewModel.onTriggerIntervalChange(id, "30")
            // 防抖窗口内用户取消了勾选（行从库里消失）
            triggers.triggers.value = emptyList()
            advanceUntilIdle()

            assertTrue(triggers.saveCalls.isEmpty(), "行已不在 ⇒ 不得再写参数（否则是看不见的孤儿参数）")
        }

    @Test
    @DisplayName("6e：禁用行仍算**已选中**，并如实标注「已禁用」")
    fun `a disabled row stays selected and is labelled`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("boot").map { it.copy(enabled = false) }
            triggers.triggers.value = seeded
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            val state = viewModel.triggerEditor.value
            assertEquals(setOf("boot"), state.selected, "行在库里 ⇒ chip 是选中的")
            val row = state.rowOf("boot")
            assertEquals(TriggerRowUi.DISABLED_NOTICE, row?.disabledNotice, "禁用必须显式说出来")
        }

    @Test
    @DisplayName("6e：启用/停用只切那一行的 enabled（不增行、不丢配置、同值不写库）")
    fun `enabling a disabled row writes once and is idempotent`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("boot").map { it.copy(enabled = false) }
            triggers.triggers.value = seeded
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            val id = seeded.single().id

            viewModel.onTriggerEnabledChange(id, true)
            advanceUntilIdle()
            assertEquals(
                1,
                triggers.triggers.value.size,
                "★ 只切那一行，**不得多插一行**（旧实现的 `rows + row.toTrigger(…)` 会造出同一事件两行）：" +
                    "${triggers.triggers.value}",
            )
            assertTrue(
                triggers.triggers.value
                    .single()
                    .enabled,
                "落库后该行必须恢复投递",
            )

            viewModel.onTriggerEnabledChange(id, true)
            advanceUntilIdle()
            assertEquals(1, triggers.saveCalls.size, "已经是那个值就不该再写一次")

            // ★ 「停用但保留配置」：关掉之后**行还在、params 还在**（旧实现会直接删掉整行）
            val paramsBefore =
                triggers.triggers.value
                    .single()
                    .params
            viewModel.onTriggerEnabledChange(id, false)
            advanceUntilIdle()
            val off = triggers.triggers.value.single()
            assertFalse(off.enabled, "停用必须落库")
            assertEquals(paramsBefore, off.params, "停用**不得**丢掉配置（这正是保留 enabled 字段的理由）")
            assertTrue(
                viewModel.triggerEditor.value.selected
                    .isNotEmpty(),
                "行仍在库里 ⇒ chip 仍算已选中（它与「未订阅」是两件事）",
            )
        }

    @Test
    @DisplayName("6e：权限未授予 ⇒ chip 可点、带 ⚠ 判定，且 NOT_APPLICABLE 不给跳转")
    fun `permission state drives the chip badge`() =
        runTest(testDispatcher) {
            permissions.setStates(
                mapOf(
                    AndroidPermission.PACKAGE_USAGE_STATS to
                        PermissionState(
                            permission = AndroidPermission.PACKAGE_USAGE_STATS,
                            grant = PermissionGrant.DENIED,
                            reason = "appops denied",
                        ),
                ),
            )
            seedSavedScriptWithTriggers()
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            assertEquals(TriggerChipAccess.NEEDS_PERMISSION, viewModel.triggerEditor.value.accessOf("app_foreground"))
            assertEquals(TriggerChipAccess.OK, viewModel.triggerEditor.value.accessOf("boot"))
            assertTrue(permissions.refreshCalls >= 1, "构造时必须探一次权限")

            permissions.setStates(
                mapOf(
                    AndroidPermission.PACKAGE_USAGE_STATS to
                        PermissionState(
                            permission = AndroidPermission.PACKAGE_USAGE_STATS,
                            grant = PermissionGrant.NOT_APPLICABLE,
                            reason = "api < 29",
                        ),
                ),
            )
            viewModel.refreshPermissions()
            advanceUntilIdle()
            assertEquals(
                TriggerChipAccess.NOT_APPLICABLE,
                viewModel.triggerEditor.value.accessOf("app_foreground"),
            )
        }

    @Test
    @DisplayName("6e：权限探测抛异常 ⇒ UNKNOWN（**不谎称已授予**）")
    fun `a failing probe degrades to unknown`() =
        runTest(testDispatcher) {
            permissions.refreshThrows = true
            seedSavedScriptWithTriggers()
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            assertEquals(
                TriggerChipAccess.UNKNOWN,
                viewModel.triggerEditor.value.accessOf("app_foreground"),
                "探测失败只能说未知",
            )
            assertEquals(
                TriggerChipAccess.OK,
                viewModel.triggerEditor.value.accessOf("boot"),
                "与权限无关的事件不受影响",
            )
        }

    @Test
    @DisplayName("6e：展开/收起是纯 UI 态（不写库、可多个同时展开）")
    fun `expanding params is pure ui state`() =
        runTest(testDispatcher) {
            val seeded = seedSavedScriptWithTriggers("boot", "interval")
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            viewModel.toggleParams(seeded[0].id)
            viewModel.toggleParams(seeded[1].id)
            advanceUntilIdle()
            assertEquals(setOf(seeded[0].id, seeded[1].id), viewModel.triggerEditor.value.expanded)
            assertTrue(triggers.saveCalls.isEmpty(), "展开不该写库")

            viewModel.toggleParams(seeded[0].id)
            // ★ 必须 `advanceUntilIdle()` 再断言：`triggerEditor` 是 `combine` 出来的派生
            //   `StateFlow`，三次同步 toggleParams 落在**同一个调度间隙**里，
            //   不推进调度器时读到的是合并过程中的一帧（实测：`[1, 2]` 而不是 `[2]`）。
            advanceUntilIdle()
            assertEquals(setOf(seeded[1].id), viewModel.triggerEditor.value.expanded)
        }

    @Test
    @DisplayName("6e：摘要计数以 Room 热流为准（写完之后计数跟着变）")
    fun `the summary count follows the hot flow`() =
        runTest(testDispatcher) {
            seedSavedScriptWithTriggers()
            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()
            assertEquals(0, viewModel.triggerCount.value)

            viewModel.toggleTriggerSelection("boot")
            advanceUntilIdle()
            assertEquals(1, viewModel.triggerCount.value)
        }

    // ------------------------------------------------------------ 构造助手

    private fun newViewModel(idArg: String?): ScriptEditorViewModel {
        val handle =
            if (idArg == null) {
                SavedStateHandle()
            } else {
                SavedStateHandle(mapOf(ScriptEditorViewModel.KEY_SCRIPT_ID to idArg))
            }
        // 6e：`permissionStatus` 是第 4 个必填依赖（chip 的 ⚠ 徽标，见 D14）；
        //      `debounceMs = 0` 让参数写入走**即时**路径 —— 6c 的既有用例一条都不涉及
        //      参数输入，这个取值只影响 6e 新增的用例（它们自己会显式改回去验防抖）。
        //
        // ★ 6e：`onWarning` 现在是**构造形参**（生产由 DI 接到 `Log.w`），
        //   因此不再需要"构造后 attach"那套技巧 —— 见下方 [WarningSink] 的 KDoc。
        return ScriptEditorViewModel(
            savedStateHandle = handle,
            scripts = scripts,
            triggers = triggers,
            permissionStatus = permissions,
            alertSink = AlertSink { message -> sink.messages += message },
        ).also { it.debounceMs = 0L }
    }

    /**
     * 告警落点（**字段初始化**，因此早于任何 `newViewModel()` 调用存在）。
     *
     * ## ★ 6e 之前的形态与它的问题（这条 KDoc 保留了当时的推理，因为它是**对的**）
     * 此前 `onWarning` 是 `internal var`，测试靠 `sink.attach(ScriptEditorViewModel(...))`
     * 在构造**之后**赋值。Kotlin 的实参求值顺序决定了：**`init` 里同步打出的告警
     * 落进默认 no-op，永远断言不到**（当时那条"路由参数无效"的告警因此只能靠
     * 用户可见状态来断言）。
     *
     * ## 6e 之后
     * `onWarning` 成为构造形参 ⇒ 缝在**第一行代码执行之前**就已接好，
     * `init` 里的告警也能被断言。`attach` 因此被删掉 —— 它存在的唯一理由消失了。
     */
    private class WarningSink {
        val messages: MutableList<String> = mutableListOf()

        fun clear() {
            messages.clear()
        }
    }

    /**
     * ★ 让"构造期间就会告警"这条事实**在测试里可见**。
     *
     * 它守的是"缝必须早于被测对象开始工作"：6e 之前靠 `attach` 的求值顺序技巧
     * （而 `init` 里**同步**那一条实际上接不住）；6e 之后 `onWarning` 是构造形参，
     * 因此这条断言才**第一次真正成立**。
     */
    @Test
    @DisplayName("★ 构造期间的告警也必须被接住（init 里的失败不能落进 no-op）")
    fun `warnings raised during construction are captured`() =
        runTest(testDispatcher) {
            scripts.stored.value = listOf(testScript(id = 5))
            scripts.loadResults[5L] = ScriptLoadResult.Missing

            val viewModel = newViewModel(idArg = "5")
            advanceUntilIdle()

            assertTrue(
                warnings.any { it.contains("SCRIPTS_EDITOR_LOAD_FAILED") },
                "装载失败发生在 init 期间；告警缝必须早于构造接好，否则它是静默的。实际=$warnings",
            )
            assertEquals(ScriptLoadFailure.MISSING, viewModel.uiState.value.loadError)
        }

    /** `save` 直接抛异常的仓库（覆盖 ViewModel 的最后一道防线）。 */
    private class CrashingScriptRepository : ScriptRepository {
        override fun observeAll(): Flow<List<Script>> = flowOf(emptyList())

        override suspend fun load(id: Long): ScriptLoadResult = ScriptLoadResult.NotFound

        override suspend fun save(script: Script): WriteResult<Script> = throw IllegalStateException("boom")

        override suspend fun delete(id: Long): WriteResult<Unit> = WriteResult.Ok(Unit)
    }
}
