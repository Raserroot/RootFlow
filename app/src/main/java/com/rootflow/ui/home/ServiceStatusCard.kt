package com.rootflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.rootflow.domain.service.ForegroundState
import com.rootflow.ui.theme.HomeCardCornerRadius

/**
 * 服务状态卡（阶段 8 主页视觉重构；需求 §6「服务状态卡片」）。
 *
 * ## 版式（对齐 LSPosed 参考图）
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ 服务运行中                          ╭──────╮  │  ← 第三行/副标题按层级降字号
 * │ 0.6.0-ui                            │  ✓   │  │  ← 右侧大图标（72dp），
 * │ API 35 · APatch                     ╰──────╯  │     超出内边距、贴到卡片右缘
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## 底色**随状态**（这是本卡的核心语义）
 * | 状态 | 底色 | 大图标 |
 * |---|---|---|
 * | 运行中 | `primaryContainer` | 对勾（`onPrimaryContainer`） |
 * | 安全模式 | `errorContainer` | 感叹号（`onErrorContainer`） |
 * | 停止 | `surfaceVariant` | 播放（`onSurfaceVariant`） |
 *
 * 三档都用 **MD3 的成对颜色**（`XContainer` + `onXContainer`）而不是手写色值：
 * 这样在**动态取色**（API 31+）与深色主题下对比度都由平台保证
 * —— 6a 的 F2 教训就是手写固定色在深色主题下等于没做。
 *
 * ## 图标为什么用 `Box` 而不是 `Icon` 直接放大
 * 参考图的图标是**贴到卡片右缘**的装饰元素。用一个固定尺寸的 `Box` 承载它，
 * 既保证了"圆心与三行文字的垂直中心对齐"，也让"图标该多大"成为一个显式常量
 * （改版式时改一处）。**不**让它真的溢出卡片去裁切：那需要 `clipToBounds` 的反向操作
 * （把子元素画到父边界外），在滚动容器里会与滚动裁剪打架，收益只是一个装饰角。
 *
 * @param service 前台服务状态（文案来源与常驻通知**同源**，见 `ForegroundServiceController`）
 * @param appVersion 应用版本（来自环境快照；读不到时由调用方传 `null`）
 * @param thirdLine 第三行：`API 35 · APatch` 之类的设备/方案摘要
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

    val container =
        when {
            safeMode -> MaterialTheme.colorScheme.errorContainer
            running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val onContainer =
        when {
            safeMode -> MaterialTheme.colorScheme.onErrorContainer
            running -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val icon =
        when {
            // ★ 用 `Warning` 而不是 `Error`：本项目的图标集是 `material-icons-core`
            //   （决策 E；extended 的 AAR 是 34.9MB，**不在**离线缓存里），
            //   而该集合**没有** `Error`（已解包核实：filled 下共 47 个图标）。**勿改成 Error。**
            safeMode -> Icons.Filled.Warning
            running -> Icons.Filled.CheckCircle
            else -> Icons.Filled.PlayArrow
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = HomeCardCornerRadius,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = HomeProjections.serviceHeadline(service),
                    style = MaterialTheme.typography.headlineSmall,
                    color = onContainer,
                )
                Text(
                    text = appVersion ?: HomeProjections.VALUE_UNKNOWN,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onContainer.copy(alpha = SECONDARY_ALPHA),
                )
                Text(
                    text = thirdLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = TERTIARY_ALPHA),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(BigIconSize)
                        .clip(CircleShape)
                        .background(onContainer.copy(alpha = ICON_BACKDROP_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    // 纯装饰（三行文字已经说清了状态）⇒ 无 contentDescription，
                    // 否则 talkback 会把同一件事念两遍。
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(BigIconSize / 2),
                )
            }
        }
    }
}

/**
 * 右侧大图标的尺寸（参考图里它约占卡片高度的 2/3）。
 *
 * 载体 `Box` 取 72.dp、里面的 `Icon` 取一半 —— 于是"圆形软背景 + 图标"形成一个
 * 有呼吸感的徽章，而不是一个贴边的实心大圆。
 */
private val BigIconSize = 72.dp

/**
 * 第二行（版本号）与第三行（设备摘要）的透明度。
 *
 * ## 为什么用同一色的 alpha 而不是换 `onSurfaceVariant`
 * 本卡的底色**随状态变**（primaryContainer / errorContainer / surfaceVariant），
 * 而 `onSurfaceVariant` 只对 surface 系保证对比度；在 errorContainer 上它可能偏灰发脏。
 * 用**本状态的 `onContainer`** 派生出两个层级，能保证三行文字始终同族、且对比度同源。
 */
private const val SECONDARY_ALPHA = 0.85f
private const val TERTIARY_ALPHA = 0.70f

/** 大图标背后那层圆形软背景的 alpha（太重会盖过底色，太轻等于没有）。 */
private const val ICON_BACKDROP_ALPHA = 0.16f

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
