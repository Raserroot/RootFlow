package com.rootflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rootflow.domain.model.LogEntry
import com.rootflow.domain.model.LogStream
import com.rootflow.ui.component.SectionCard
import com.rootflow.ui.theme.TerminalTextStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 深色终端卡（阶段 6b，需求 §6「深色终端实时日志」）。
 *
 * ## 固定深色（**不跟随主题**）
 * 需求点名的就是"**深色**终端"，因此面板底色与文字色是常量，不读 `MaterialTheme`。
 * 浅色主题下它是一块深色面板——这正是终端的观感（与系统终端一致）。
 * 若要跟随主题，安全模式与日志的红色高亮都要重新算对比度，收益为零。
 *
 * ## ★ auto-scroll：正向 + 手动 `scrollToItem`（**不用 `reverseLayout`**）
 * 已批准决策 D。理由：环形缓冲淘汰的是**最旧**条目，`reverseLayout` 下索引 0 在底部，
 * 于是"淘汰"与"生产"会同时改列表的**两端**，`LazyColumn` 的 key/anchor 会跳。
 *
 * ### 为什么用 `scrollToItem` 而不是 `animateScrollToItem`
 * 管道按 10Hz 推批（`batchFlushIntervalMillis = 100`）。滚动动画（默认 250ms+）
 * 会被下一批不断打断并重启 ⇒ 表现为**持续抖动**且永远追不上。
 * `scrollToItem` 是瞬时跳转，10Hz 下不可感知。
 *
 * ### 用户往上翻时必须停下（否则没法读历史）
 * `snapshotFlow { isScrollInProgress }` 在**每次滚动结束**时求值一次：
 * 那一刻如果最后一项可见 ⇒ 视为"贴底"，继续跟随；否则关掉跟随。
 *
 * **为什么程序滚动不会误关跟随**：`scrollToItem` 只改滚动位置而不产生手势输入，
 * 因此在程序跳转结束时"最后一项可见"为真 ⇒ 跟随保持开启。
 * 这条语义只能真机确认（它是本阶段唯一无法自动化的核心交互）。
 *
 * @param state 终端状态（不可变）
 * @param onSelectFilter 切换显示范围
 */
@Composable
internal fun LogTerminalCard(
    state: TerminalState,
    onSelectFilter: (TerminalStreamFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "运行日志",
        modifier = modifier,
        trailing = { TerminalSummary(state = state) },
    ) {
        StreamFilterRow(
            selected = state.filter,
            onSelect = onSelectFilter,
        )
        TerminalPanel(state = state)
    }
}

/** 卡片右上角的摘要：运行短码 + 行数 + 丢弃计数。 */
@Composable
private fun TerminalSummary(state: TerminalState) {
    val shortId = state.runShortId
    val text =
        when {
            shortId == null -> "暂无运行"
            state.droppedEntries > 0L -> "#$shortId · ${state.entries.size} 行 · 丢弃 ${state.droppedEntries}"
            else -> "#$shortId · ${state.entries.size} 行"
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (state.droppedEntries > 0L) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** 过滤行（ALL / OUT / ERR / SYS）。 */
@Composable
private fun StreamFilterRow(
    selected: TerminalStreamFilter,
    onSelect: (TerminalStreamFilter) -> Unit,
) {
    // 无涟漪：与底栏同款取舍（玻璃/深色面板上叠涟漪会显脏）。
    // `MutableInteractionSource` 必须 `remember`，否则每次重组都新建一个。
    val interactionSource = remember { MutableInteractionSource() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalStreamFilter.entries.forEach { filter ->
            val active = filter == selected
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (active) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ).clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(filter) },
                        ).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 深色面板本体。 */
@Composable
private fun TerminalPanel(state: TerminalState) {
    val listState = rememberLazyListState()
    val visible = state.visibleEntries
    var autoScroll by remember { mutableStateOf(true) }

    // ① 用户往上翻 ⇒ 关掉跟随；滑回底部 ⇒ 恢复跟随。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) return@collect
                autoScroll = listState.isAtBottom()
            }
    }

    // ② 新行到达且仍在跟随 ⇒ 瞬时跳到底部（见 KDoc 的"为什么不用 animate"）
    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TerminalHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(TerminalBackground),
    ) {
        if (visible.isEmpty()) {
            Text(
                text = if (state.runId == null) "暂无运行日志 · 触发一次事件或等待定时任务" else "该次运行暂无日志",
                style = MaterialTheme.typography.bodySmall,
                color = TerminalMuted,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = visible, key = { it.sequence }) { entry ->
                    TerminalLine(entry = entry)
                }
            }
        }
    }
}

/** 一行日志：时间戳 + 正文，按流着色。 */
@Composable
private fun TerminalLine(entry: LogEntry) {
    val rendered = remember(entry.sequence) { HomeProjections.terminalLine(entry) }
    Text(
        text = rendered,
        // ★ 阶段 8.1：终端行的字号改由 `TerminalTextStyle` 统一定义（13sp，等宽）。
        //   原先用 `bodySmall.copy(fontFamily = Monospace)` ⇒ 字号跟着正文 token 走，
        //   而那正是"想单独调终端却把说明文字一起改掉"的形态。
        style =
            TerminalTextStyle.copy(
                fontWeight = if (entry.stream == LogStream.SYS) FontWeight.Medium else FontWeight.Normal,
            ),
        color = terminalColor(entry.stream),
        // 长行**换行**而不是裁掉：日志可读性优先（脚本可能打印长 JSON）
        softWrap = true,
    )
}

/** 流 → 终端配色（固定色，不随主题，见 KDoc）。 */
private fun terminalColor(stream: LogStream): Color =
    when (stream) {
        LogStream.STDOUT -> TerminalStdout
        LogStream.STDERR -> TerminalStderr
        LogStream.SYS -> TerminalSys
    }

/**
 * listState 是否贴在底部。
 *
 * 「最后一项可见」而不是「`firstVisibleItemIndex == lastIndex`」：
 * 终端行会换行（`softWrap = true`），一行可能占多个 item 高度，
 * 用"首项即末项"判断会在多行日志上永远为假 ⇒ 用户滚到底也恢复不了跟随。
 * 空列表按"贴底"处理（没有内容可读，跟随应保持开启）。
 */
private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1
}

/** 终端面板高度（固定值：内层 `LazyColumn` 必须有界，见 `HomeScreen` 的布局说明）。 */
private val TerminalHeight = 280.dp

/** 终端底色（需求 §6「深色终端」）。 */
private val TerminalBackground = Color(0xFF101314)

/** STDOUT 正文色。 */
private val TerminalStdout = Color(0xFFE6E6E6)

/** STDERR 正文色。 */
private val TerminalStderr = Color(0xFFFF6B6B)

/** 运行时系统行（SYS）：与脚本输出区分开，避免误读成脚本打印的内容。 */
private val TerminalSys = Color(0xFF7FA8A0)

/** 次要文字（空态提示）。 */
private val TerminalMuted = Color(0xFF6B7280)
