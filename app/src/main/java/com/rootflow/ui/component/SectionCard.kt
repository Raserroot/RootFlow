package com.rootflow.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rootflow.ui.theme.HomeCardCornerRadius

/**
 * 主页卡片的统一外壳（阶段 6b）。
 *
 * ## 为什么需要它（而不是每个卡片自己写 `Card`）
 * 需求 §6 的观感要求是「参考 LSPosed / Magisk：**大圆角卡片**、MD3 动态配色」。
 * 大圆角是**跨卡片的设计契约**：三张卡片各写一次 `shape = RoundedCornerShape(24.dp)`
 * 就是三个会漂移的字面量（改一处忘两处，视觉上表现为"卡片圆角不一致"，
 * 而那种差异只在真机截图里才看得出来）。
 *
 * ## 为什么用 `CardDefaults.cardColors` 而不是自定义容器色
 * MD3 的 `surfaceContainer` 系列已经按"卡片浮在背景之上"的语义算好了浅/深两套对比度。
 * 手写 `surface.copy(alpha = …)` 会在动态取色（API 31+）下失去那个保证
 * —— 6a 的 F2 就是这么踩的（固定黑在深色主题下等于没做）。
 *
 * @param title 卡片标题（单行）
 * @param trailing 标题右侧的可选内容（如刷新按钮、状态摘要）
 * @param content 卡片正文（垂直排列，间距由调用方用 `Arrangement` 控制）
 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        // 阶段 8：主页卡片改用 20.dp（对齐 LSPosed 参考图）。
        // 配置页的脚本行仍用 24.dp 的 `CardCornerRadius` —— 两处的分工见该常量的 KDoc。
        shape = HomeCardCornerRadius,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * 卡片内的一行「键 : 值」（环境信息卡与事件源列表共用）。
 *
 * ## 为什么值可空
 * `null` 表示"这项读不到"（例如纯 JVM 下的 `Build` 字段）。UI 显示 `读取中`，
 * **不显示空白**——空白会让人以为"设备就是这样"，而实际上是我们没读到。
 * 与 `EnvironmentRow` 的 KDoc 同一条纪律。
 *
 * @param label 左侧标签
 * @param value 右侧值；`null` 显示 [unavailableText]
 * @param valueColor 值文字颜色（状态色由调用方给）
 */
@Composable
internal fun KeyValueRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    unavailableText: String = "读取中",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value ?: unavailableText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}
