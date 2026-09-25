package com.rootflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rootflow.domain.service.ForegroundState
import com.rootflow.ui.theme.HomeCardCornerRadius

/**
 * 服务状态卡（阶段 8 主页视觉重构；需求 §6「服务状态卡片」）。
 *
 * ## ★★ 11e 补丁2：用户裁定「完全照 LSPosed 复刻」（2026-09-25）
 *
 * 所有几何参数来自对参照截图的**逐像素测量**
 * （1080×2400 / `wm density` = 420 ⇒ **2.625 px/dp**）：
 *
 * | 项 | LSPosed 实测 | 本实现 |
 * |---|---|---|
 * | 版式 | **2 行**：粗体大字 + 常规小字，行距极紧 | `titleLarge` + Bold，`bodyMedium`，间距 2dp |
 * | 右侧图标 | **232px ≈ 88dp**，**溢出卡片右缘被裁掉** | 同（`offset` 12dp + `Card` 的 shape 裁剪） |
 * | 卡片底色 | `#FDF6F4` —— 即它的橙 `#F5A623` **约 6% 叠白** | `lerp(surface, accent, 0.08)` |
 * | 卡片圆角 | 大圆角（≈20dp） | `HomeCardCornerRadius`（20dp，**本来就一致**） |
 * | 文字色 | 纯黑（它只有浅色主题） | `onSurface` —— 浅色下≈黑，**深色下自动转白** |
 *
 * ### 与上一版的三个实质差别
 * 1. **三行 → 两行**：原来是「状态 / 版本号 / 设备摘要」。参照是两行，
 *    故后两者合并成第二行（`1.4.2 · API 36 · unknown`）。
 * 2. **图标不再有圆形软背景**：上一版是「72dp 圆形底板 + 36dp 图标」居中摆放；
 *    参照是**一个实色大图标直接画、右下角被卡片裁掉**。
 * 3. **底色不再是 MD3 的 `XContainer` 成对色**，改为「强调色 8% 叠 `surface`」。
 *    ⚠️ 这**推翻了 6a 的 F2 结论**（手写淡色在深色主题下会失去对比度）。
 *    处置：底色由 `surface` 混合而来（跟随主题明暗），文字用 `onSurface`
 *    而不是写死的黑 ⇒ 深色下不会变成"黑字黑底"。
 *
 * ## 图标为什么用 `Warning` 而不是 `Error`（**勿改**）
 * 本项目的图标集是 `material-icons-core`（决策 E；extended 的 AAR 是 34.9MB，
 * **不在**离线缓存里），而该集合**没有** `Error`（已解包核实：filled 下共 47 个图标）。
 *
 * @param service 前台服务状态（文案来源与常驻通知**同源**，见 `ForegroundServiceController`）
 * @param appVersion 应用版本（来自环境快照；读不到时由调用方传 `null`）
 * @param thirdLine 设备/方案摘要，形如 `API 36 · unknown`
 */
@Composable
internal fun ServiceStatusCard(
    service: ForegroundState,
    appVersion: String?,
    thirdLine: String,
    modifier: Modifier = Modifier,
) {
    val running = service is ForegroundState.Running
    val safeMode = (service as? ForegroundState.Running)?.safeMode == true

    val accent =
        when {
            safeMode -> ServiceStatusPalette.safeMode
            running -> ServiceStatusPalette.running
            else -> ServiceStatusPalette.stopped
        }
    val icon =
        when {
            safeMode -> Icons.Filled.Warning
            running -> Icons.Filled.CheckCircle
            else -> Icons.Filled.PlayArrow
        }

    val onCard = MaterialTheme.colorScheme.onSurface
    val container = lerp(MaterialTheme.colorScheme.surface, accent, CONTAINER_MIX)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = HomeCardCornerRadius,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            // ★ 右边**不留内边距**：图标要贴到卡片右缘并溢出去，留了 padding 就溢不动。
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
            ) {
                Text(
                    text = HomeProjections.serviceHeadline(service),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onCard,
                )
                Text(
                    text = subtitleOf(appVersion, thirdLine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onCard,
                )
            }
            // ★ 右侧大图标：**右下角溢出卡片、被 `Card` 的 shape 裁掉**（参照的做法）。
            //   用 `offset` 而不是加宽 Row —— offset 只改绘制位置、不改布局尺寸，
            //   因此"文字能占多少宽"不受图标影响，溢出那段由 `Card` 的 `clip` 兜住。
            Icon(
                imageVector = icon,
                // 纯装饰（两行文字已经说清了状态）⇒ 无 contentDescription，
                // 否则 talkback 会把同一件事念两遍。
                contentDescription = null,
                tint = accent,
                modifier =
                    Modifier
                        .size(ServiceIconSize)
                        .offset(x = ServiceIconOverflow, y = ServiceIconDrop),
            )
        }
    }
}

/**
 * 第二行：`1.4.2 · API 36 · unknown`（版本 + 设备摘要）。
 *
 * 两个来源**各自可能缺失**（版本读不到时调用方传 `null`，设备摘要里也会出现「未知」），
 * 因此按"有就拼、没有就不拼"处理；**两者都没有时如实说"读取中"**，
 * 不留空字符串 —— 空白会被读成"这台设备就是这样"。
 */
private fun subtitleOf(
    appVersion: String?,
    thirdLine: String,
): String {
    val parts =
        listOfNotNull(
            appVersion?.takeIf { it.isNotBlank() },
            thirdLine.takeIf { it.isNotBlank() },
        )
    return parts.joinToString(" · ").ifBlank { HomeProjections.VALUE_UNKNOWN }
}

/**
 * 右侧大图标的尺寸（88dp）。
 *
 * 参照截图里它是 232px；本机 `wm density = 420` ⇒ 2.625 px/dp ⇒ 232 / 2.625 ≈ 88。
 */
private val ServiceIconSize = 88.dp

/**
 * 图标向右溢出的距离（12dp）。
 *
 * 参照的 logo 一直画到**屏幕右缘**，而卡片右边界距屏幕右缘还有 32px(≈12dp)
 * —— 那一段就是被卡片裁掉、也是它看起来"大得装不下"的来源。
 */
private val ServiceIconOverflow = 12.dp

/**
 * 图标向下溢出的距离（8dp）。
 *
 * 参照的 logo 是**往右下角沉**的：它的竖向区间一直压到卡片下沿之外，
 * 文字两行因此整体偏上。只用 `x` 偏移会让图标垂直居中，
 * 那正是本版第一轮跑出来的样子 —— 骨架对了，重心还不对。
 */
private val ServiceIconDrop = 8.dp

/** 两行之间的间距。参照的两行几乎贴着，实测空隙 ≈1–2dp。 */
private val LINE_SPACING = 2.dp

/**
 * 强调色与 `surface` 的混合比例。
 *
 * ## 为什么是 0.05（第一轮用了 0.08，太深）
 * 参照的底色 `#FDF6F4` 与它自己的页面底 `#F7F7F7` **只差 6/255** —— 几乎是看不见的，
 * 它靠**图标颜色 + 文字**表达状态，底色只是极淡的信号。
 * 本项目的强调色偏蓝（蓝的 R 通道低，同样比例下混出来比橙深得多），
 * 0.08 会得到 `#EFF5FD` 这种一眼能看出色块的淡蓝 ⇒ 改用 0.05，落回"极淡"那一档。
 */
private const val CONTAINER_MIX = 0.05f

/**
 * 服务卡的**三态强调色**（11e 补丁2，用户裁定「完全照 LSPosed 复刻」）。
 *
 * ## 参照只有一态，本卡有三态
 * 它那套只表达"未安装"（橙 `#F5A623` + 6% 淡底）。RootFlow 要在**同一版式**下
 * 表达运行中 / 安全模式 / 未运行，因此按它的规则（**同色系淡底 + 实色大图标**）外推：
 *
 * | 状态 | 强调色 | 出处 |
 * |---|---|---|
 * | 运行中 | `#3B7DE4` | **本项目**底栏的选中蓝（`NavBarPalette` 同源，品牌一致） |
 * | 安全模式 | `#F5A623` | **直接取参照的橙** |
 * | 未运行 | `#7A7A7A` | **本项目**底栏的未选中灰（同源） |
 *
 * **这是有意的局部例外**：与 `NavBarPalette` 一样覆盖 MD3 动态取色 ——
 * 用户点名"照 LSPosed 来"，而它用的是固定色。
 * 底色由 [lerp] 与 `surface` 混合 ⇒ **明暗主题各自成立**；文字用 `onSurface`
 * 而不是写死的黑 ⇒ 深色下不会变成"黑字黑底"。
 */
private object ServiceStatusPalette {
    val running = Color(0xFF3B7DE4)
    val safeMode = Color(0xFFF5A623)
    val stopped = Color(0xFF7A7A7A)
}

/** 事件源行的状态圆点（8.dp）。 */
@Composable
internal fun StatusDot(color: Color) {
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
    )
}
