package com.rootflow.ui.consent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootflow.ui.component.SectionCard

/**
 * 首启的**保活知情同意**页（阶段 12c，用户需求）。
 *
 * ## 为什么需要这一页（而不是"默认开启、设置里可关"）
 * 用户 2026-09-25 的原话是：
 * > 「必须做那个保活，毕竟都有 root 权限了，如果你怕的话，可以再用户第一次下载软件的时候，
 * > 进入 app 先弹出一个声明：表示有什么什么保活机制，需要同意才能进，不同意就退出」
 *
 * 这个方案把保活从"是否正当"变成一个**用户知情后的选择**，因此本页有两个硬要求：
 * 1. **逐条写清机制**，不写"为了更好的体验"这类空话 —— 用户要能看出自己在同意什么
 * 2. **不同意就退出**（`finish()`），而不是"关掉保活继续用"：保活是这个 App
 *    能工作的前提（常驻脚本、事件监听都建立在它之上），留一个"关了也能用"的假选项，
 *    用户只会得到一个什么都不跑的 App
 *
 * ## 三态分流（由 `RootFlowApp` 决定进哪一支）
 * | 取值 | 界面 | 为什么 |
 * |---|---|---|
 * | `null` | 加载态 | **"还没读到磁盘"**，既不进主界面也不判为拒绝（见 `RootFlowSettings.KeepAliveConsent`） |
 * | `false` | 本页 | 没问过、或问过被拒 |
 * | `true` | 主界面 | 已同意 |
 *
 * ## 返回键 = 不同意（**显式拦，不是无视**）
 * `BackHandler(enabled = true)` 拦下系统的返回动作，然后走与「不同意并退出」**同一条**路径。
 * 两种写法的差别很重要：
 * - 拦下后什么都不做 ⇒ 用户按返回**没有任何反应**，会以为 App 卡了
 * - 拦下后退出（本实现）⇒ 与 Android "返回 = 离开当前界面"的直觉一致
 *
 * 之所以必须**显式**拦：本页是首启的第一屏，系统返回的默认行为是"退出 Activity"，
 * 但那会绕过 [onDecline]（同意状态不会被记成"拒绝"）—— 于是下次启动又是一模一样的流程，
 * 而日志里看不出用户其实是拒绝过的。
 *
 * ## 系统交互不在本文件里
 * 本页只发出"同意 / 不同意"的**意图**：
 * - 写盘由 `MainViewModel.acceptKeepAliveConsent` / `declineKeepAliveConsent` 负责
 * - `finish()` 与"拉起前台服务 + 申请通知权限"由 `MainActivity` 的回调负责
 *   （沿用阶段 5/6a 的纪律：权限与系统生命周期交互留在 Activity，
 *   见 `MainActivity` 的类 KDoc —— 那条纪律没有因为这一页而放宽）
 *
 * @param writeFailed 同意**没能落盘**（回读校验失败）。为 `true` 时页面必须如实说明并让用户重试，
 *   否则"点了同意没反应"就是一次静默失败
 * @param onAccept 点了「我已知晓并同意」
 * @param onDecline 点了「不同意并退出」（或按了返回键）
 */
@Composable
internal fun KeepAliveConsentScreen(
    writeFailed: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 防连点：写盘是异步的，连点会重复提交（虽然幂等，但按钮看起来像没反应）。
    // 与项目其它地方的防连点纪律一致（`ScriptEditorViewModel.save` / `HomeViewModel` 的环境刷新）。
    var submitting by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onDecline() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "保活说明",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                "RootFlow 需要在后台一直运行，才能在你配置的时机自动执行脚本。" +
                    "这需要下面这些机制，请你先知悉。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = "1 · 前台服务常驻") {
            ConsentParagraph(
                "通知栏会常驻一条「RootFlow 运行中」的通知，系统据此允许本应用长期驻留。" +
                    "这条通知也带「停止服务」按钮，你随时可以停掉它。",
            )
        }

        SectionCard(title = "2 · 定时自愈") {
            ConsentParagraph(
                "每 15 分钟检查一次前台服务是否还在：服务被停掉、而应用进程仍在时会自动重新拉起；" +
                    "若系统拒绝后台重启，会发一条通知提醒你打开 App。\n\n" +
                    "注意：如果整个应用进程都被系统清理（例如从最近任务划掉），" +
                    "部分系统不会再为它启动后台进程 —— 这种情况下需要你打开一次 App 才能恢复监听。",
            )
        }

        SectionCard(title = "3 · 系统事件监听") {
            ConsentParagraph(
                "监听屏幕开关、解锁、电源插拔、电量变化、Wi-Fi 切换这几类系统事件，" +
                    "并按你为脚本配置的触发器转发给脚本。没有配置触发器的事件不会被使用。",
            )
        }

        SectionCard(title = "4 · 常驻脚本守护") {
            ConsentParagraph(
                "标记为「常驻」的脚本会被守护：它退出后自动重新拉起；" +
                    "连续快速崩溃 5 次后只停掉它自己，不会影响其它脚本。",
            )
        }

        SectionCard(title = "5 · 需要 root 权限") {
            ConsentParagraph(
                "脚本以 root 身份执行。未授予 root 时脚本不会运行（App 本身仍可正常打开与配置）。",
            )
        }

        SectionCard(title = "明确不做的事") {
            ConsentParagraph(
                "不做双进程互拉、1 像素页面、无声音乐这类规避系统限制的保活手段；" +
                    "不自动申请电池优化白名单（只提供跳去系统设置页的入口，放不放行由你决定）。",
            )
        }

        if (writeFailed) {
            Text(
                text = "同意没能保存到本机。请再点一次；如果始终如此，可能是存储空间不足。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDecline,
                enabled = !submitting,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "不同意并退出")
            }
            Button(
                onClick = {
                    submitting = true
                    onAccept()
                },
                // `writeFailed` 时重新可点（否则用户在失败后无法重试 —— 那是一条死路）
                enabled = !submitting || writeFailed,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "我已知晓并同意")
            }
        }

        if (submitting && !writeFailed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = "正在保存…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

/** 声明页里的一段正文（统一字号与颜色，避免每条各写一遍）。 */
@Composable
private fun ConsentParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 读取同意状态期间的**加载态**（阶段 12c）。
 *
 * ## 为什么不能在这一档直接进主界面，也不能判为拒绝
 * `null` 的语义是"**内存镜像还没读到磁盘**"（`RootFlowSettings.KeepAliveConsent.UNKNOWN`）：
 * 冷启动的头几百毫秒里，`SettingsRepository.settings` 还是默认值。
 * - 判为"未同意" ⇒ 已同意的老用户每次开 App 都要看一遍声明页（甚至误退出）
 * - 判为"已同意" ⇒ 全新安装的用户会绕过声明页，服务在未经同意的情况下被拉起来
 *
 * 两边的代价都不对等，因此唯一正确的动作是**等**：显示一个转圈，不做任何决定。
 */
@Composable
internal fun KeepAliveConsentLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在读取设置…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
