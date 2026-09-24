package com.rootflow.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 「为什么没跑」弹窗（P4 / 方案 §5.3 + §1.5 的**最小信息集**）。
 *
 * ## 它为什么是一串而不是一句话
 * §1.5 明写：用户问"为什么没跑"时，必须能区分**没触发**与**被拒**，且要
 * **逐层给出 did not pass**，而不是一个笼统结论。因此这里把 [WhyNotRunning.checks]
 * **全部**列出来，再给一行**结论**——结论是"第一个挡住它的那一层"，
 * 而不是把整条链折叠成"未运行"。
 *
 * ## 为什么用 `AlertDialog` 而不是底部弹窗
 * 本页（列表 / 主页）都没有 `Scaffold` 的 bottom-sheet 宿主，而 `AlertDialog` 是
 * 项目里已有的用法（删除确认框）。为"弹窗放哪儿"引入一个宿主容器，收益仅是外形。
 *
 * ## 本组件只做投影与布局
 * 判定全在 [MasterSwitchProjections.whyNotRunning]（纯函数，纯 JVM 可测）。
 *
 * @param data 已经算好的一帧
 * @param onDismiss 关闭（不做任何写入 —— 这个弹窗**没有副作用**）
 */
@Composable
internal fun WhyNotRunningDialog(
    data: WhyNotRunning,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = data.title) },
        text = {
            Column(
                // 门控链有 9 层，小屏上会超出一屏 ⇒ 必须可滚（否则结论看不见，
                // 而结论恰恰是这个弹窗存在的理由）。
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                data.checks.forEach { check ->
                    GateCheckRow(check = check)
                }
                Text(
                    text = "结论：${data.verdict}",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (data.blockedAt != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "知道了") }
        },
    )
}

/** 门控链的一行：符号 + 层名 + 人话详情。 */
@Composable
private fun GateCheckRow(check: GateCheck) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = gateMark(check.state),
            style = MaterialTheme.typography.bodyMedium,
            color = gateColor(check.state),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = check.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            check.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 各层的符号。
 *
 * 用**纯文本**符号而不是 emoji：emoji 的字形宽度与基线随系统字体变化，
 * 在逐行对齐的列表里会让每一行的首列错位（而这一列的作用恰是"一眼扫出哪层没过"）。
 */
private fun gateMark(state: GateState): String =
    when (state) {
        GateState.PASS -> "✓"
        GateState.BLOCKED -> "✕"
        GateState.INFO -> "·"
        GateState.UNKNOWN -> "?"
    }

/** 各层的颜色（`BLOCKED` 用 error 色 —— 它就是"结论"的来源）。 */
@Composable
private fun gateColor(state: GateState): Color =
    when (state) {
        GateState.PASS -> MaterialTheme.colorScheme.primary
        GateState.BLOCKED -> MaterialTheme.colorScheme.error
        GateState.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        GateState.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
