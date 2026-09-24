package com.rootflow.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootflow.ui.component.SectionCard

/**
 * 主页「总开关」卡片（P4 / 方案 §5.1）—— **唯一的全局开关**，因此它是本页最大的交互件。
 *
 * ## 本组件只做投影与布局
 * 一切"该显示什么"的判定都在 [MasterSwitchProjections]（纯函数，纯 JVM 可测）。
 * 这里没有业务判断 —— 与 `HomeScreen` / `ScriptListScreen` 同款纪律（决策 B 下
 * 让 UI 覆盖率不至于为 0 的唯一办法）。
 *
 * ## ★ 三条反模式（方案 §5.1 明令避免，勿改回去）
 * | 反模式 | 本组件的做法 |
 * |---|---|
 * | 总开关关闭时**整块灰掉** | 卡片**照常显示** [MasterSwitchUi.scriptsLine] / [MasterSwitchUi.serviceLine]，只多一行 [MasterSwitchUi.offNotice] 说明"配置仍保留、可继续修改" |
 * | 关闭状态**不留痕** | 留痕发生在 `MasterSwitchImpl`（`MASTER_SWITCH_DISABLED`），本组件不重复记 |
 * | 用**一个**开关同时表达"启用"与"运行中" | 卡片只有"总闸"这一个布尔；每个脚本的运行状态在**脚本行**上（`ScriptRuntimeProjections`），两者从不在同一处合并 |
 *
 * ## 为什么失败提示不做成 Snackbar
 * 主页没有 `Scaffold`（阶段 8 的无 AppBar 版式）。为一条提示把它引进来是本末倒置，
 * 而"拨了开关没反应"又必须被解释 —— 因此 [notice] 是卡片下方的一行红字，
 * 由 `HomeViewModel.masterSwitchNotice` 供给（成功时清空）。
 *
 * @param state 卡片一帧
 * @param notice 一次性提示（写入失败的原因）；`null` = 无
 * @param onToggle 拨动总闸（**不做乐观更新**：开关位置永远来自 [state]）
 */
@Composable
internal fun MasterSwitchCard(
    state: MasterSwitchUi,
    notice: String?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "总开关", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (state.enabled) "已打开" else "已关闭",
                    style = MaterialTheme.typography.titleLarge,
                    color =
                        if (state.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    text = state.scriptsLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.serviceLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = onToggle)
        }

        // 安全模式与总闸**正交**：总闸开着也可能什么都不跑（熔断先挡住了）。
        // 不显示这一行会让用户去反复拨总闸 —— 而那并不会让它跑起来。
        if (state.safeMode) {
            Text(
                text = "注意：当前处于安全模式 —— 即使总开关打开，脚本与事件也不会运行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.offNotice?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        notice?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
