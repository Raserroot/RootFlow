package com.rootflow.ui.home

import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 主页纯函数与不可变状态的单测（阶段 6b）。
 *
 * ## 为什么这一份是 6b 的**主要**自动化护栏
 * 本项目不引 Compose UI 测试（已批准决策 B：`ui-test-*` / `androidTest` 全不在离线缓存），
 * 因此"卡片上显示什么"在自动化层面只能靠**把判定抽成纯函数**来覆盖。
 * [HomeProjections] 与 [TerminalState] 就是为此而存在的。
 */
class HomeModelsTest {
    // ------------------------------------------------------------ 事件源行投影

    @Test
    @DisplayName("三种事件源状态各自映射到正确的行（原因原样透出）")
    fun `event source rows project all three states`() {
        val rows =
            HomeProjections.eventSourceRows(
                listOf(
                    EventSourceStatus("battery", EventSourceState.Running),
                    EventSourceStatus("screen", EventSourceState.NotStarted),
                    EventSourceStatus(
                        "usage_stats",
                        EventSourceState.Unavailable("permission missing: android.permission.PACKAGE_USAGE_STATS"),
                    ),
                ),
            )

        assertEquals(3, rows.size)

        val running = rows[0]
        assertTrue(running.running, "Running 必须报 running=true")
        assertEquals("运行中", running.detail)
        assertFalse(running.unavailable)

        val notStarted = rows[1]
        assertFalse(notStarted.running)
        assertEquals("未启动", notStarted.detail)
        assertFalse(notStarted.unavailable, "「未启动」与「无法启动」必须可区分（决策 7）")

        val unavailable = rows[2]
        assertFalse(unavailable.running)
        assertTrue(unavailable.unavailable, "缺权限必须标记为 unavailable（UI 用警示色）")
        assertEquals(
            "permission missing: android.permission.PACKAGE_USAGE_STATS",
            unavailable.detail,
            "原因必须**原样**透出：6d 要按具体权限决定跳转目标，压缩成「权限不足」就定位不了",
        )
    }

    @Test
    @DisplayName("空列表投影为空（服务未运行时不给任何行）")
    fun `empty statuses project to an empty list`() {
        assertTrue(HomeProjections.eventSourceRows(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ 安全模式文案

    @Test
    @DisplayName("成因可还原时用它；不可还原时如实说未知（不编原因）")
    fun `safe mode detail falls back to the honest unknown text`() {
        assertEquals("超时 120s", HomeProjections.safeModeDetail("超时 120s"))
        assertEquals(
            com.rootflow.domain.service.ServiceNotificationText.REASON_UNKNOWN,
            HomeProjections.safeModeDetail(null),
            "成因不可还原时必须说未知，不得用 reasonKey 编一个",
        )
    }

    // ------------------------------------------------------------ 终端行格式

    @Test
    @DisplayName("时间戳固定为 HH:mm:ss.SSS（等宽对齐，不跟随区域设置）")
    fun `timestamps use a fixed width format`() {
        // 2026-09-19 12:34:56.789 UTC+8 的具体时刻不重要，格式才重要
        val formatted = HomeProjections.formatTimestamp(1_756_000_000_000L)

        assertEquals(12, formatted.length, "HH:mm:ss.SSS 恰好 12 个字符，实际=$formatted")
        assertTrue(formatted.matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3}""")), "格式不符：$formatted")
    }

    @Test
    @DisplayName("终端行 = 时间戳 + 空格 + 原文，且不含换行")
    fun `terminal lines concatenate timestamp and text`() {
        val entry = entry(sequence = 0L, text = "hello world", stream = LogStream.STDOUT)

        val line = HomeProjections.terminalLine(entry)

        assertTrue(line.endsWith("hello world"), "必须保留原文，实际=$line")
        assertFalse(line.contains('\n'), "行内不得出现换行（LogEntry.text 的契约是不含行尾换行符）")
    }

    // ------------------------------------------------------------ 过滤

    @Test
    @DisplayName("过滤：ALL 放行一切，其余按流精确匹配")
    fun `stream filters select by stream`() {
        val out = entry(0L, "out", LogStream.STDOUT)
        val err = entry(1L, "err", LogStream.STDERR)
        val sys = entry(2L, "sys", LogStream.SYS)

        assertTrue(TerminalStreamFilter.ALL.accepts(out))
        assertTrue(TerminalStreamFilter.ALL.accepts(err))
        assertTrue(TerminalStreamFilter.ALL.accepts(sys))

        assertTrue(TerminalStreamFilter.STDOUT.accepts(out))
        assertFalse(TerminalStreamFilter.STDOUT.accepts(err))
        assertFalse(TerminalStreamFilter.STDOUT.accepts(sys))

        assertTrue(TerminalStreamFilter.STDERR.accepts(err))
        assertFalse(TerminalStreamFilter.STDERR.accepts(out))

        assertTrue(TerminalStreamFilter.SYS.accepts(sys))
        assertFalse(TerminalStreamFilter.SYS.accepts(out))
    }

    @Test
    @DisplayName("visibleEntries 只受过滤影响，缓冲本身不变")
    fun `visible entries respect the filter without mutating the buffer`() {
        val state =
            TerminalState.Empty
                .forRun(
                    runId = "run-1",
                    entries =
                        listOf(
                            entry(0L, "out", LogStream.STDOUT),
                            entry(1L, "err", LogStream.STDERR),
                        ),
                    droppedEntries = 0L,
                )

        assertEquals(2, state.visibleEntries.size)
        assertEquals(1, state.withFilter(TerminalStreamFilter.STDERR).visibleEntries.size)
        assertEquals(2, state.entries.size, "过滤不得改动 entries")
        assertEquals(TerminalStreamFilter.ALL, state.filter, "原状态不得被改动（不可变）")
    }

    // ------------------------------------------------------------ append 语义

    @Test
    @DisplayName("append 按 sequence 去重（tail 与首批会重叠）")
    fun `append deduplicates by sequence`() {
        val state = TerminalState.Empty.forRun("run-1", listOf(entry(0L, "a", LogStream.STDOUT)), 0L)

        val next =
            state.append(
                batch(sequence = 1L, entries = listOf(entry(0L, "a", LogStream.STDOUT), entry(1L, "b"))),
            )

        assertEquals(listOf(0L, 1L), next.entries.map { it.sequence }, "序号 0 不得重复出现")
        assertEquals(listOf("a", "b"), next.entries.map { it.text })
    }

    @Test
    @DisplayName("append 保留相同文本的不同行（按文本去重会真的丢数据）")
    fun `append keeps duplicate texts with distinct sequences`() {
        val state = TerminalState.Empty.forRun("run-1", emptyList(), 0L)

        val next =
            state.append(
                batch(0L, listOf(entry(0L, "same", LogStream.STDOUT), entry(1L, "same", LogStream.STDOUT))),
            )

        assertEquals(2, next.entries.size, "脚本循环打印同一句话是合法输出，不得去重")
    }

    @Test
    @DisplayName("append 超出上限时丢最旧（与管道环形缓冲同策略）")
    fun `append truncates the oldest beyond the cap`() {
        val cap = TerminalState.MAX_ENTRIES
        val overflow = cap + 5
        val state = TerminalState.Empty.forRun("run-1", emptyList(), 0L)

        val next = state.append(batch(0L, List(overflow) { entry(it.toLong(), "line-$it", LogStream.STDOUT) }))

        assertEquals(cap, next.entries.size, "本地缓冲必须与管道同容量截断")
        assertEquals(5L, next.entries.first().sequence, "丢的是**最旧**的 5 条")
        assertEquals((overflow - 1).toLong(), next.entries.last().sequence)
    }

    @Test
    @DisplayName("droppedEntries 透出管道侧的丢弃计数（不静默）")
    fun `append surfaces the pipeline drop counter`() {
        val state = TerminalState.Empty.forRun("run-1", emptyList(), 0L)

        val next = state.append(batch(3L, listOf(entry(9L, "x", LogStream.STDOUT)), dropped = 42L))

        assertEquals(42L, next.droppedEntries, "丢弃必须可见：UI 据此提示「更早的日志已滚出」")
    }

    @Test
    @DisplayName("runFinished 的空收尾批不改变可见内容，但更新丢弃计数")
    fun `an empty finishing batch does not clear the buffer`() {
        val state = TerminalState.Empty.forRun("run-1", listOf(entry(0L, "a", LogStream.STDOUT)), 0L)

        val next = state.append(LogBatch("run-1", 1L, emptyList(), 7L, runFinished = true))

        assertEquals(1, next.entries.size, "收尾批不得清空已显示的行")
        assertEquals(7L, next.droppedEntries)
    }

    @Test
    @DisplayName("forRun 切换到另一次运行时不保留上一次的行")
    fun `forRun replaces the buffer instead of merging`() {
        val first = TerminalState.Empty.forRun("run-1", listOf(entry(0L, "old", LogStream.STDOUT)), 3L)

        val second = first.forRun("run-2", listOf(entry(0L, "new", LogStream.STDOUT)), 0L)

        assertEquals(listOf("new"), second.entries.map { it.text }, "跨运行不合并（合并会让行归属丢失）")
        assertEquals(0L, second.droppedEntries, "丢弃计数按运行重置（管道也是按运行的）")
        assertEquals("run-2", second.runId)
    }

    @Test
    @DisplayName("mergedWithHistory：按序号去重合并（幂等），并可补齐管道没给的行")
    fun `mergedWithHistory deduplicates by sequence and fills gaps`() {
        // 终端里已有 0、1（实时观察到的），Room 给出 0..3（完整历史）
        val state =
            TerminalState.Empty
                .forRun("run-1", listOf(entry(0L, "a", LogStream.STDOUT), entry(1L, "b", LogStream.STDOUT)), 0L)

        val merged = state.mergedWithHistory((0L..3L).map { entry(it, "line-$it", LogStream.STDOUT) })

        assertEquals(listOf(0L, 1L, 2L, 3L), merged.entries.map { it.sequence }, "必须按序号升序且不重复")
        assertEquals(4, merged.entries.size, "Room 独有的行必须补进来")
        assertEquals(merged, merged.mergedWithHistory((0L..3L).map { entry(it, "line-$it", LogStream.STDOUT) }))
    }

    @Test
    @DisplayName("mergedWithHistory：空历史不改动状态；乱序历史也会被排序")
    fun `mergedWithHistory handles empty and unordered input`() {
        val state = TerminalState.Empty.forRun("run-1", listOf(entry(5L, "e", LogStream.STDOUT)), 0L)

        assertEquals(state, state.mergedWithHistory(emptyList()), "空历史必须原样返回（不产生无意义变更）")

        val unordered =
            state.mergedWithHistory(listOf(entry(9L, "j", LogStream.STDOUT), entry(7L, "h", LogStream.STDOUT)))

        assertEquals(listOf(5L, 7L, 9L), unordered.entries.map { it.sequence }, "合并后必须有序")
    }

    @Test
    @DisplayName("maxSequence 用于判断 Room 还差多少没读到")
    fun `maxSequence reflects the newest buffered entry`() {
        assertEquals(-1L, TerminalState.Empty.maxSequence, "空缓冲返回 -1（不是 0，0 是合法序号）")
        assertEquals(7L, TerminalState.Empty.forRun("run-1", listOf(entry(3L, "x"), entry(7L, "y")), 0L).maxSequence)
    }

    @Test
    @DisplayName("runShortId 取前 8 位；无运行时为 null")
    fun `run short id is the first eight characters`() {
        val state = TerminalState.Empty.forRun("0123456789abcdef", emptyList(), 0L)

        assertEquals("01234567", state.runShortId)
        assertNull(TerminalState.Empty.runShortId, "没有运行时不得编一个短码")
    }

    @Test
    @DisplayName("本地缓冲上限与管道环形容量一致（耦合点被断言钉住）")
    fun `local cap matches the pipeline ring capacity`() {
        // 两处同值是有意的（见 TerminalState.MAX_ENTRIES 的 KDoc）。这里断言字面量，
        // 若哪天管道改了容量，这条会失败并提示去核对 UI 侧。
        assertEquals(2_000, TerminalState.MAX_ENTRIES)
    }

    // ------------------------------------------------------------ HomeUiState

    @Test
    @DisplayName("初始态：服务 Idle、无源、环境未加载、终端空")
    fun `initial state is honest about not knowing anything`() {
        val initial = HomeUiState.Initial

        assertFalse(initial.serviceRunning)
        assertNull(initial.sourceSummary, "未运行时不得显示 0/0（那会被读成「一个源都没起来」）")
        assertFalse(initial.environmentLoaded)
        assertTrue(initial.environment.isEmpty())
        assertNull(initial.terminal.runId)
    }

    @Test
    @DisplayName("sourceSummary 只在 Running 时给出 enabled/total")
    fun `source summary only exists while running`() {
        val running =
            HomeUiState.Initial.copy(
                service =
                    com.rootflow.domain.service.ForegroundState
                        .Running(enabled = 6, total = 7, safeMode = false),
            )

        assertEquals("6/7", running.sourceSummary)
        assertTrue(running.serviceRunning)
    }

    // ------------------------------------------------------------ 阶段 8：主页视觉重构的投影

    @Test
    @DisplayName("阶段 8：服务状态卡的主标题穷举三态")
    fun `service headline covers the three states`() {
        assertEquals(
            "服务运行中",
            HomeProjections.serviceHeadline(
                com.rootflow.domain.service.ForegroundState
                    .Running(enabled = 7, total = 7, safeMode = false),
            ),
        )
        assertEquals(
            "安全模式",
            HomeProjections.serviceHeadline(
                com.rootflow.domain.service.ForegroundState
                    .Running(enabled = 0, total = 7, safeMode = true),
            ),
        )
        assertEquals(
            "服务未运行",
            HomeProjections.serviceHeadline(com.rootflow.domain.service.ForegroundState.Idle),
        )
    }

    @Test
    @DisplayName("阶段 8：状态卡第三行由 apiLevel + rootFlavor 拼成，缺一不编造")
    fun `status third line never invents a value`() {
        assertEquals("API 35 · APatch", HomeProjections.statusThirdLine(apiLevel = 35, rootFlavor = "APatch"))
        assertEquals("API 35 · Root 未知", HomeProjections.statusThirdLine(apiLevel = 35, rootFlavor = null))
        assertEquals("API 35 · Root 未知", HomeProjections.statusThirdLine(apiLevel = 35, rootFlavor = "  "))
        assertEquals("API 未知 · unknown", HomeProjections.statusThirdLine(apiLevel = null, rootFlavor = "unknown"))
        assertEquals("API 未知 · Root 未知", HomeProjections.statusThirdLine(apiLevel = null, rootFlavor = null))
    }

    @Test
    @DisplayName("阶段 8：信息卡按参考图顺序重排，并把 ABI 并进设备行")
    fun `info rows follow the reference order and merge abi into the device row`() {
        val raw =
            listOf(
                EnvironmentRow(label = "设备", value = "OnePlus PLK110"),
                EnvironmentRow(label = "Android", value = "15 (API 35)"),
                EnvironmentRow(label = "ABI", value = "arm64-v8a"),
                EnvironmentRow(label = "应用版本", value = "0.8.0-ui"),
                EnvironmentRow(label = "Root", value = "unknown"),
                EnvironmentRow(label = "SELinux", value = "u:r:magisk:s0"),
            )

        val rows = HomeProjections.environmentInfoRows(raw)

        assertEquals(
            listOf("Android 版本", "设备", "Root 方案", "SELinux", "App 版本"),
            rows.map { it.label },
            "顺序是设计的一部分（参考图），改它等于改版式",
        )
        assertEquals("15 (API 35)", rows[0].value)
        assertEquals("OnePlus PLK110 · arm64-v8a", rows[1].value, "ABI 并进设备行（参考图的写法）")
        assertEquals("u:r:magisk:s0", rows[3].value)
        assertEquals("0.8.0-ui", rows[4].value)
    }

    @Test
    @DisplayName("阶段 8：设备行在缺 ABI / 缺设备 / 全缺时的三种退化")
    fun `device row degrades honestly`() {
        fun deviceRow(vararg rows: EnvironmentRow) =
            HomeProjections.environmentInfoRows(rows.toList()).first { it.label == "设备" }.value

        assertEquals("OnePlus PLK110", deviceRow(EnvironmentRow("设备", "OnePlus PLK110")))
        assertEquals("arm64-v8a", deviceRow(EnvironmentRow("ABI", "arm64-v8a")))
        assertNull(deviceRow(EnvironmentRow("设备", null)), "全缺 ⇒ null（UI 显示「读取中」，不留空）")
        assertNull(deviceRow(EnvironmentRow("ABI", "   ")), "空白串等同于没读到")
    }

    @Test
    @DisplayName("阶段 8：★ 事件源用常量总量 7，服务未运行时不得显示 0 / 0")
    fun `event source summary uses the constant total`() {
        fun row(
            running: Boolean,
            unavailable: Boolean = false,
        ) = EventSourceRow(sourceId = "s", running = running, detail = "d", unavailable = unavailable)

        assertEquals("7 / 7 可用", HomeProjections.eventSourceSummary(List(7) { row(running = true) }))
        assertEquals(
            "6 / 7 可用",
            HomeProjections.eventSourceSummary(List(6) { row(true) } + row(running = false)),
        )
        assertEquals(
            "0 / 7 可用",
            HomeProjections.eventSourceSummary(emptyList()),
            "服务未运行时 rows 为空 —— 显示 0 / 7（不是 0 / 0，那个数字看起来像事实但是错的）",
        )
    }

    @Test
    @DisplayName("阶段 8：不可用项计数与警示文案（0 个时不显示空警示）")
    fun `event source warning only appears when something is unavailable`() {
        fun running() = EventSourceRow(sourceId = "s", running = true, detail = "运行中", unavailable = false)

        fun unavailable() =
            EventSourceRow(
                sourceId = "s",
                running = false,
                detail = "permission missing: android.permission.PACKAGE_USAGE_STATS",
                unavailable = true,
            )

        assertNull(HomeProjections.eventSourceWarning(listOf(running(), running())))
        assertEquals("⚠ 1 个不可用", HomeProjections.eventSourceWarning(listOf(running(), unavailable())))
        assertEquals("⚠ 2 个不可用", HomeProjections.eventSourceWarning(listOf(unavailable(), unavailable())))
        assertEquals(0, HomeProjections.eventSourceUnavailableCount(listOf(running())))
    }

    @Test
    @DisplayName("阶段 8：★ API 等级从投影后的字符串里解析，解析不出就返回 null（不编数字）")
    fun `api level is parsed from the projected android row`() {
        assertEquals(
            35,
            HomeProjections.apiLevelOf(listOf(EnvironmentRow("Android", "15 (API 35)"))),
        )
        assertEquals(
            36,
            HomeProjections.apiLevelOf(listOf(EnvironmentRow("Android", "16 (API 36.0)"))),
            "小数也能解析出整数部分（API 36.0 是设备的真实写法）",
        )
        assertNull(HomeProjections.apiLevelOf(listOf(EnvironmentRow("Android", "15"))), "没有 API 字样 ⇒ null")
        assertNull(HomeProjections.apiLevelOf(listOf(EnvironmentRow("Android", null))))
        assertNull(HomeProjections.apiLevelOf(emptyList()), "环境未探测时为空列表")
    }

    @Test
    @DisplayName("阶段 8：rowValue 只查找不重算，取不到时 null（并滤掉空白串）")
    fun `row value lookup is a pure lookup`() {
        val rows = listOf(EnvironmentRow("App 版本", "0.8.0-ui"), EnvironmentRow("SELinux", "   "))

        assertEquals("0.8.0-ui", HomeProjections.rowValue(rows, "App 版本"))
        assertNull(HomeProjections.rowValue(rows, "SELinux"), "空白串按「没读到」处理")
        assertNull(HomeProjections.rowValue(rows, "不存在"))
    }

    // ------------------------------------------------------------ 构造助手

    private fun entry(
        sequence: Long,
        text: String,
        stream: LogStream = LogStream.STDOUT,
    ): LogEntry =
        LogEntry(
            runId = "run-1",
            sequence = sequence,
            timestamp = 1_756_000_000_000L + sequence,
            stream = stream,
            text = text,
        )

    private fun batch(
        sequence: Long,
        entries: List<LogEntry>,
        dropped: Long = 0L,
    ): LogBatch =
        LogBatch(
            runId = "run-1",
            sequence = sequence,
            entries = entries,
            droppedEntries = dropped,
            runFinished = false,
        )
}
