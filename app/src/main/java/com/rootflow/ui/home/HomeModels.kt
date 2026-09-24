package com.rootflow.ui.home

import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.LogBatch
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceNotificationText
import com.rootflow.ui.master.MasterSwitchUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件源列表的一行（阶段 6b，纯数据）。
 *
 * ## 为什么不在 Composable 里直接对 `EventSourceStatus` 做 `when`
 * 判定（"这一行该显示什么文案"）是**唯一值得单测**的部分，而 Composable 在纯 JVM 下不可测
 * （本项目不引 Robolectric / Compose UI 测试，见已批准决策 B）。
 * 把它抽成不可变投影后，`HomeModelsTest` 可以穷举三态 × 缺权限/异常两类原因。
 *
 * @property sourceId 稳定标识（与真机日志 `EVENT_SOURCE id=…` 逐字一致，便于交叉判读）
 * @property running 是否正在转发事件（驱动状态点颜色）
 * @property detail 第二行文案；**永远非空**（"运行中" / 具体原因 / "未启动"）
 * @property unavailable 是否因故无法启动（驱动状态点用警示色，与"未启动"区分开）
 */
data class EventSourceRow(
    val sourceId: String,
    val running: Boolean,
    val detail: String,
    val unavailable: Boolean,
)

/**
 * 环境信息卡的一行（键值对，纯数据）。
 *
 * @property value `null` 表示该项**读不到**；UI 显示"读取中"而不是空白或假值
 */
data class EnvironmentRow(
    val label: String,
    val value: String?,
)

/** 终端显示范围（阶段 6b）。 */
enum class TerminalStreamFilter {
    ALL,
    STDOUT,
    STDERR,
    SYS,
    ;

    /**
     * 该行是否通过本过滤。
     *
     * 纯函数：`TerminalStreamFilter.ALL` 放行一切，其余按 [LogEntry.stream] 精确匹配。
     */
    fun accepts(entry: LogEntry): Boolean =
        when (this) {
            ALL -> true
            STDOUT -> entry.stream == LogStream.STDOUT
            STDERR -> entry.stream == LogStream.STDERR
            SYS -> entry.stream == LogStream.SYS
        }

    /** UI 上的短标签（稳定字符串，供单测钉死）。 */
    val label: String
        get() =
            when (this) {
                ALL -> "全部"
                STDOUT -> "OUT"
                STDERR -> "ERR"
                SYS -> "SYS"
            }
}

/**
 * 主页的**全部**可观察状态（阶段 6b，单一不可变值）。
 *
 * ## 为什么合成一个 data class 而不是四个独立的 StateFlow
 * Composable 只订阅**一个** `StateFlow` ⇒ 一次重组拿到一致的一帧。
 * 若四个流各自 `collectAsStateWithLifecycle`，就存在"服务卡已是 Running、
 * 事件源列表还是空"的中间帧（两个流分别发射），在真机上表现为一闪而过的错配。
 *
 * ## 为什么安全模式不在这里
 * 它是 [com.rootflow.domain.event.CircuitBreaker] 的既有状态（`StateFlow`），
 * 主页直接订阅即可。再抄一份进本类就**多了一个会漂移的真相** ——
 * 而"安全模式显示成未熔断"是后果很重的一类不一致。
 */
data class HomeUiState(
    val service: ForegroundState,
    val sources: List<EventSourceRow>,
    val environment: List<EnvironmentRow>,
    val environmentLoaded: Boolean,
    val environmentRefreshing: Boolean,
    val terminal: TerminalState,
    /**
     * 总开关卡片（P4）。
     *
     * ## 为什么它是 `HomeUiState` 的一部分（而不是 ViewModel 里另一个流）
     * 卡片要同时显示"总闸状态 + 脚本统计 + 服务 + 事件源"，而**服务与事件源本就在本类里**。
     * 拆成两条流会让"服务卡说运行中、总开关卡说未运行"这种**同屏不一致**成为可能 ——
     * 与 6b 定下"一次重组拿到一致的一帧"的理由完全相同。
     */
    val masterSwitch: MasterSwitchUi,
) {
    /** 事件源摘要（`已启用/总数`）；未注册时为 `null`（UI 显示"未绑定"而不是 0/0）。 */
    val sourceSummary: String?
        get() = (service as? ForegroundState.Running)?.let { "${it.enabled}/${it.total}" }

    /** 服务是否在运行。 */
    val serviceRunning: Boolean
        get() = service is ForegroundState.Running

    companion object {
        /** 冷启动初值：服务未知但**不假装**（Idle）、环境未探测、终端为空。 */
        val Initial: HomeUiState =
            HomeUiState(
                service = ForegroundState.Idle,
                sources = emptyList(),
                environment = emptyList(),
                environmentLoaded = false,
                environmentRefreshing = false,
                terminal = TerminalState.Empty,
                // 总闸初值取 `false` 是**如实**的：`MasterSwitch` 的内存快照初值就是关闭
                // （安全默认），恢复发生在启动链最前面。UI 首帧若早于那一拍，
                // 显示"总开关关闭"恰好是当时的真相，而不是一个编出来的值。
                masterSwitch = MasterSwitchUi.Initial,
            )
    }
}

/**
 * 深色终端的状态（阶段 6b，不可变）。
 *
 * ## 为什么 entries 有上限
 * 管道每次运行最多保留 `DEFAULT_RING_CAPACITY`（2000）条，但 **`release` / 淘汰之前**
 * 订阅者可能已经把更多批次收进本地列表（长跑脚本的批次是持续到来的）。
 * 无上限的列表在长跑脚本下会稳定增长到内存压力 —— 那是"UI 拖垮宿主"，
 * 与本项目"UI 卡顿绝不反压 runtime"的硬约束方向相反（虽然不含反压，
 * 但 OOM 一样会让宿主死掉）。因此本地列表与管道**同容量**截断，且**丢最旧**。
 *
 * @property runId 当前显示的运行；`null` = 还没有任何运行
 * @property entries 已收日志（时间序），最多 [MAX_ENTRIES] 条
 * @property droppedEntries 管道侧累计丢弃条目数（**不静默**：>0 时 UI 必须显式提示）
 * @property filter 当前显示范围
 */
data class TerminalState(
    val runId: String?,
    val entries: List<LogEntry>,
    val droppedEntries: Long,
    val filter: TerminalStreamFilter,
) {
    /** 通过过滤的行（UI 直接用这个渲染）。 */
    val visibleEntries: List<LogEntry>
        get() = entries.filter { filter.accepts(it) }

    /** 运行标识短码（UUID 前 8 位）；无运行时为 `null`。 */
    val runShortId: String?
        get() = runId?.take(SHORT_ID_LENGTH)

    /**
     * 追加一批日志（**纯函数**，返回新状态）。
     *
     * ## 去重
     * `tail()` 与随后到达的首批批次可能**重叠**（首屏快照与增量之间的边界）。
     * 按 [LogEntry.sequence] 去重：已存在的序号直接丢弃。
     * 用 `sequence` 而不是 `text` —— 相同文本的重复行是**合法输出**
     * （脚本循环打印同一句话），按文本去重会真的丢数据。
     *
     * ## 截断
     * 超出 [MAX_ENTRIES] 时丢**最旧**的（与管道的环形缓冲策略一致）。
     * 注意局部截断**不计入** [droppedEntries]：那个字段的语义是"**管道**丢弃的条目数"，
     * 混入 UI 侧截断会让真机判读失去意义（两个来源的数字不能相加）。
     */
    fun append(batch: LogBatch): TerminalState {
        if (batch.entries.isEmpty() && !batch.runFinished) return this
        val known = entries.mapTo(mutableSetOf()) { it.sequence }
        val fresh = batch.entries.filter { known.add(it.sequence) }
        val merged = if (fresh.isEmpty()) entries else (entries + fresh).takeLast(MAX_ENTRIES)
        return copy(entries = merged, droppedEntries = batch.droppedEntries)
    }

    /** 切换到另一次运行：整体替换（**不**跨运行保留行）。 */
    fun forRun(
        runId: String,
        entries: List<LogEntry>,
        droppedEntries: Long,
    ): TerminalState =
        copy(
            runId = runId,
            entries = entries.takeLast(MAX_ENTRIES),
            droppedEntries = droppedEntries,
        )

    /** 切换显示范围（保留缓冲，只改过滤）。 */
    fun withFilter(filter: TerminalStreamFilter): TerminalState = copy(filter = filter)

    /**
     * 用 Room 里的完整历史补齐缓冲（阶段 6b 修复：终端在运行结束后变空）。
     *
     * ## 为什么需要它（真机缺陷）
     * `LogPipeline` 在运行收尾时**移除该运行的 state**（内存回收，阶段 2 起的契约）
     * ⇒ 之后 `tail()` 恒空、`observe()` 返回 `emptyFlow()`。
     * 而管道内部 ring 只有 2000 条、且**每个进程一份**，长跑脚本的早期行会被淘汰。
     * 唯一"运行结束后仍然存在且完整"的来源是 Room（阶段 3a 的 `run_log_entries`）
     * —— 这正是 `RunHistoryReader` 的 KDoc 早就写明的分工：
     * "已结束运行的日志从 Room 读取，运行中的走管道快照"。
     *
     * ## 语义：合并，不是替换（**与 [forRun] 的区别**）
     * - [forRun] 是"切换到另一次运行" ⇒ **整体替换**
     * - 本方法是"同一次运行，换一个更完整的来源" ⇒ **按 `sequence` 合并**
     *
     * 实时观察到的行与 Room 里的行**必然重叠**（同一批日志既推给订阅者又落了库）。
     * 按序号去重后合并，重复调用是**幂等**的 —— 这让"结束时补读"与"结束前已读"
     * 可以共存，不必判断谁先谁后。
     *
     * ## 顺序
     * 按 `sequence` 升序排列后再截断：要防止调用方传入乱序列表时把"最新"算错
     * （截断取的是**末尾**的 [MAX_ENTRIES] 条）。
     *
     * @param entries Room 里的该运行日志（完整历史或其尾部）
     * @return 合并后的新状态；[entries] 为空时返回 `this`（不产生无意义的状态变更）
     */
    fun mergedWithHistory(entries: List<LogEntry>): TerminalState {
        if (entries.isEmpty()) return this
        val bySequence = sortedMapOf<Long, LogEntry>()
        this.entries.forEach { bySequence[it.sequence] = it }
        entries.forEach { bySequence[it.sequence] = it }
        return copy(entries = bySequence.values.toList().takeLast(MAX_ENTRIES))
    }

    /** 缓冲里的最大序号；空缓冲返回 `-1`（Room 据此判断"还差多少没读到"）。 */
    val maxSequence: Long
        get() = entries.maxOfOrNull { it.sequence } ?: -1L

    companion object {
        /**
         * 本地缓冲上限，**与 `LogPipelineImpl.DEFAULT_RING_CAPACITY` 同值**。
         *
         * 这里刻意不 import `LogPipelineImpl`：那是 `data/` 的实现类，
         * 而本文件在 `ui/`（分层约束）。两处同值由单测的断言钉住
         * （`HomeModelsTest` 断言 2000，若哪天管道改容量，那条断言会提示这个耦合点）。
         */
        const val MAX_ENTRIES: Int = 2_000

        /** 运行标识显示长度（UUID 前 8 位足够区分同屏展示的历史）。 */
        const val SHORT_ID_LENGTH: Int = 8

        /** 冷启动：无运行、无行、默认显示全部。 */
        val Empty: TerminalState =
            TerminalState(
                runId = null,
                entries = emptyList(),
                droppedEntries = 0L,
                filter = TerminalStreamFilter.ALL,
            )
    }
}

/**
 * 主页的**纯投影函数**（阶段 6b）：domain 值 → UI 行。
 *
 * 全部放在这里而不是 Composable 内，理由同 [EventSourceRow]：
 * 这些判定是本阶段唯一可自动化验证的 UI 逻辑（无 Compose UI 测试）。
 */
object HomeProjections {
    /**
     * 事件源状态 → 列表行。
     *
     * ## 决策 7 的正面落实
     * `Unavailable.reason` **原样透出**，不做任何"美化"或截断到固定文案
     * （例如把 `permission missing: android.permission.X` 压成"权限不足"）。
     * 原因：6d 的设置页要按**具体权限**决定跳转目标，而用户反馈截图里
     * "权限不足"这四个字无法定位是哪一项。真机判读同样依赖它。
     */
    fun eventSourceRows(statuses: List<EventSourceStatus>): List<EventSourceRow> =
        statuses.map { status ->
            when (val state = status.state) {
                EventSourceState.Running ->
                    EventSourceRow(
                        sourceId = status.sourceId,
                        running = true,
                        detail = "运行中",
                        unavailable = false,
                    )

                EventSourceState.NotStarted ->
                    EventSourceRow(
                        sourceId = status.sourceId,
                        running = false,
                        detail = "未启动",
                        unavailable = false,
                    )

                is EventSourceState.Unavailable ->
                    EventSourceRow(
                        sourceId = status.sourceId,
                        running = false,
                        detail = state.reason,
                        unavailable = true,
                    )
            }
        }

    /** 安全模式 banner 的正文（成因不可还原时**如实说未知**，不编原因）。 */
    fun safeModeDetail(detail: String?): String = detail ?: ServiceNotificationText.REASON_UNKNOWN

    // ── 阶段 8：主页视觉重构的纯投影（**全部可单测**，Composable 里不做判定）──────────

    /** 读不到的值统一显示这个（**不得**留空白：空白会被读成"设备就是这样"）。 */
    const val VALUE_UNKNOWN: String = "读取中"

    /** 事件源总量（与需求 §2.1 的事件数一致；用于"N / 7 可用"）。 */
    const val EVENT_SOURCE_TOTAL: Int = 7

    /**
     * 服务状态卡的**主标题**（大字那一行）。
     *
     * ## 为什么不带"事件源 x/y"
     * 事件源已由信息卡的独立一行承载（阶段 8 的版式）。主标题只回答"服务在不在跑"，
     * 把计数塞进来会让 22sp 的大字变成一个复合句，参考图里的主标题就是**一个状态词**。
     */
    fun serviceHeadline(service: ForegroundState): String =
        when {
            service !is ForegroundState.Running -> "服务未运行"
            service.safeMode -> "安全模式"
            else -> "服务运行中"
        }

    /**
     * 状态卡第三行：`API 35 · APatch`。
     *
     * ## 取值来源（**不新造数据**）
     * `apiLevel` 与 `rootFlavor` 都来自已有的环境快照，与信息卡里的行是同一份值
     * —— 于是"状态卡说 APatch、信息卡说 unknown"在结构上不可能发生。
     */
    fun statusThirdLine(
        apiLevel: Int?,
        rootFlavor: String?,
    ): String {
        val api = apiLevel?.let { "API $it" } ?: "API 未知"
        val flavor = rootFlavor?.takeIf { it.isNotBlank() } ?: "Root 未知"
        return "$api · $flavor"
    }

    /**
     * 环境快照 → 信息卡的行（**阶段 8 的展示顺序**）。
     *
     * ## 顺序是设计的一部分（勿随手调）
     * 与参考图一致：**Android 版本 → 设备 → Root 方案 → SELinux → App 版本**。
     * 前四项是"这台机器的客观事实"，App 版本放最后（它最少被查）。
     *
     * ## ABI 为什么移到设备行里
     * 参考图把架构写成"设备"那行的括号补充（`一加 15` / `arm64-v8a (4K)`）。
     * 本项目同理：`OnePlus PLK110 · arm64-v8a` 一行说清"这是什么设备"，
     * 比单开一行 ABI 更省一次扫视。
     */
    fun environmentInfoRows(rows: List<EnvironmentRow>): List<EnvironmentRow> {
        fun valueOf(label: String): String? = rows.firstOrNull { it.label == label }?.value
        val device = valueOf("设备")
        val abi = valueOf("ABI")
        return listOf(
            EnvironmentRow(
                label = "Android 版本",
                value = valueOf("Android"),
            ),
            EnvironmentRow(
                label = "设备",
                value =
                    when {
                        device == null && abi == null -> null
                        abi.isNullOrBlank() -> device
                        device.isNullOrBlank() -> abi
                        else -> "$device · $abi"
                    },
            ),
            EnvironmentRow(label = "Root 方案", value = valueOf("Root")),
            EnvironmentRow(label = "SELinux", value = valueOf("SELinux")),
            EnvironmentRow(label = "App 版本", value = valueOf("应用版本")),
        )
    }

    /**
     * 事件源那一行的值：`6 / 7 可用`。
     *
     * ## 为什么用**总量常量**而不是 `rows.size`
     * `rows` 只在服务运行时才有内容；服务未运行时它是空的，用 `rows.size` 会得到
     * `0 / 0`——那是一个**看起来像事实的错误数字**。总量是需求 §2.1 定下的常量（7），
     * 与运行与否无关。
     */
    fun eventSourceSummary(rows: List<EventSourceRow>): String {
        val available = rows.count { it.running }
        return "$available / $EVENT_SOURCE_TOTAL 可用"
    }

    /** 不可用的事件源条数（0 时 UI 不显示警示）。 */
    fun eventSourceUnavailableCount(rows: List<EventSourceRow>): Int = rows.count { it.unavailable }

    /** 事件源行的警示文案；无不可用项时返回 `null`（**不显示空警示**）。 */
    fun eventSourceWarning(rows: List<EventSourceRow>): String? =
        eventSourceUnavailableCount(rows).takeIf { it > 0 }?.let { "⚠ $it 个不可用" }

    /**
     * 按 label 取一次投影后的值（供状态卡复用信息卡的数据）。
     *
     * 只做一次 `firstOrNull` 查找，**不重算**任何东西 —— 于是状态卡与信息卡
     * 永远显示同一个值（"两处真相"在结构上不可能出现）。
     */
    fun rowValue(
        rows: List<EnvironmentRow>,
        label: String,
    ): String? = rows.firstOrNull { it.label == label }?.value?.takeIf { it.isNotBlank() }

    /**
     * 从 `EnvironmentRow`（**原始行**，不是投影后的）里取 API 等级。
     *
     * ## 为什么要解析字符串
     * 快照里的 `apiLevel: Int` 在投影成 UI 行时已经与 `androidRelease` 拼成
     * `"15 (API 35)"`（`HomeViewModel.environmentRows`），投影层拿不到原始 Int。
     * 与其为这一个数字把 `HomeUiState` 扩一个字段（那会改动数据流，本阶段的硬约束是
     * **数据流不变**），不如在这里解析它 —— 并且**解析失败返回 `null`**，
     * 由 [statusThirdLine] 显示"API 未知"，绝不编一个数字。
     */
    fun apiLevelOf(rows: List<EnvironmentRow>): Int? =
        rows
            .firstOrNull { it.label == "Android" }
            ?.value
            ?.let {
                API_PATTERN
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

    /** 从 `"15 (API 35)"` 里取 `35`。 */
    private val API_PATTERN = Regex("""API\s+(\d+)""")

    /**
     * 时间戳 → `HH:mm:ss.SSS`（终端行前缀）。
     *
     * ## 为什么用固定 `Locale.US` 与固定格式
     * 终端的时间戳是**给人做时序对照**用的（与 logcat 对齐），
     * 跟随系统区域设置会让同一份日志在不同设备上出现不同宽度，
     * 破坏等宽对齐。`SimpleDateFormat` 每次新建（本函数每次重组会调用多次）—
     * 若改成共享实例，就必须处理它的非线程安全问题，得不偿失。
     */
    fun formatTimestamp(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))

    /** 单行终端文本（**行内不含换行**：`LogEntry.text` 的契约是不含行尾换行符）。 */
    fun terminalLine(entry: LogEntry): String = formatTimestamp(entry.timestamp) + " " + entry.text
}
