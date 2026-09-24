package com.rootflow.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootflow.ui.theme.HomeCardCornerRadius

/**
 * 信息卡（阶段 8 主页视觉重构；替代原「环境信息卡 + 事件源卡」两张）。
 *
 * ## 版式（对齐 LSPosed 参考图）
 * ```
 * Android 版本
 * 15 (API 35)
 *
 * 设备
 * OnePlus PLK110 · arm64-v8a
 *
 * Root 方案
 * unknown
 * …
 * ```
 * **label 大字在上、value 灰色小字在下**，行间靠**留白**分隔（**无分隔线**）。
 * 参考图的观感正来自这一点：一张卡里 6 组键值，全是留白，没有一条线。
 *
 * ## 为什么把两卡合成一张
 * 原「环境信息」与「事件源」是两张卡，各有一个标题行。合并后信息密度更接近参考图，
 * 且**事件源**作为其中一行，正好可以承接"点它去设置页看详情"这条后续入口
 * （`onOpenEventSources`，点击回调由调用方注入 ⇒ 本组件不 import 导航）。
 *
 * ## 三态（**不合并**，沿用 6b 的纪律）
 * | 状态 | 判据 | 显示 |
 * |---|---|---|
 * | 未探测 | `loaded == false` | "探测中…"（**不显示假值**） |
 * | 探测中（刷新） | `refreshing == true` | 保留上一份数据 + 转圈 |
 * | 已探测 | 行非空 | 逐行 label/value |
 *
 * @param rows 环境行（**调用方应先过 `HomeProjections.environmentInfoRows` 重排**）
 * @param eventRows 事件源行（只用于"N / 7 可用"与警示，不再逐个列出）
 * @param loaded 环境是否已探测
 * @param refreshing 是否正在重新探测
 * @param onRefresh 重新探测；`null` 表示不提供该入口
 * @param onOpenEventSources 点"事件源"那一行的回调；`null` 表示不可点
 */
@Composable
internal fun InfoCard(
    rows: List<EnvironmentRow>,
    eventRows: List<EventSourceRow>,
    loaded: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenEventSources: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = HomeCardCornerRadius,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
        ) {
            if (!loaded) {
                Text(
                    text = "探测中…（首次进入主页时经 root 通道读取一次）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { row ->
                    InfoRow(label = row.label, value = row.value)
                }
            }

            // 事件源行：值取自**常量总量**（7），不是 rows.size —— 服务未运行时 rows 为空，
            // 用 size 会得到"0 / 0 可用"那个看起来像事实的错误数字（见 HomeProjections）。
            InfoRow(
                label = "事件源",
                value = HomeProjections.eventSourceSummary(eventRows),
                warning = HomeProjections.eventSourceWarning(eventRows),
                onClick = onOpenEventSources,
            )

            // 重新探测入口：一次探测要走一次 root 往返（真机数百毫秒），
            // 因此是**手动**而不是自动轮询（理由见 EnvironmentInfoProvider 的 KDoc）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (refreshing) {
                    // 不带进度的指示器是稳定 API；带 `progress` 的那个在 material3 上标了
                    // `@ExperimentalMaterial3Api`，为一个转圈引入 opt-in 注解不划算。
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (refreshing) "重新探测中…" else "重新探测环境信息",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 信息卡的一行：label（大字）+ value（灰色小字）。
 *
 * ## 为什么 value 读不到时**不留空**
 * `null` 表示"这项没读到"，显示 [HomeProjections.VALUE_UNKNOWN]。
 * 留空会让人以为"设备就是这样"——与 `EnvironmentRow.value` 的 KDoc 同一条纪律。
 *
 * @param warning 非空时附在 value 后面（同一行），用 `error` 色 —— 与 value 分行会让
 *   "有几个不可用"看起来像另一项信息
 */
@Composable
private fun InfoRow(
    label: String,
    value: String?,
    warning: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickableRow(onClick)
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = value ?: HomeProjections.VALUE_UNKNOWN,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            warning?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 可点行的最小实现（**不引 ripple**）。
 *
 * 用 `clickable` + `indication = null`：卡片内的行点击不需要水波纹
 * （参考图的观感是"安静的信息卡"），且这样不必为一行引入 `MutableInteractionSource` 的状态管理。
 * 阶段 8 只有"事件源"一行可点，将来若需要按下态再统一处理。
 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
    )

/**
 * 行间距（**参考图的核心观感**：无分隔线，全靠留白）。
 *
 * 20.dp 是"能一眼分组、又不显得散"的量级；改小会让 6 行糊成一片。
 */
private val ROW_SPACING = 20.dp
