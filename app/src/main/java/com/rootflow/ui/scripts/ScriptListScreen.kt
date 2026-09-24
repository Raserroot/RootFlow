package com.rootflow.ui.scripts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rootflow.ui.master.ScriptRuntimeProjections
import com.rootflow.ui.master.WhyNotRunningDialog
import com.rootflow.ui.theme.CardCornerRadius
import com.rootflow.ui.theme.NavBarReservedSpace
import androidx.compose.material.icons.Icons as IconsPackage
import androidx.compose.material.icons.filled.Add as AddIcon

/**
 * 脚本列表页（阶段 6c，需求 §6「配置：脚本列表、新建/导入/编辑」）。
 *
 * ## 本页只做**投影与布局**
 * 一切"该显示什么"的判定都在 [ScriptProjections] / [ScriptListViewModel]（纯 JVM 可测）。
 * 本文件里**没有**业务判断 —— 这是本项目"无 UI 自动化测试"（已批准决策 B）下
 * 让覆盖率不至于为 0 的唯一办法，与 6b 的 `HomeScreen` 同款纪律。
 *
 * ## 长按删除（已批准决策 2）
 * 用 `combinedClickable` 的 `onLongClick`，**不用**滑动删除：滑动会与 6e 的
 * 触发器勾选手势争抢，而长按在 Compose 里零额外依赖。
 *
 * ## 空态**不显示假数据**（同 6a 纪律）
 * `state.empty` 只在 `loaded == true` 时才为真（见 [ScriptListUiState]）：
 * "Room 还没回第一帧"与"真的一个脚本都没有"必须区分开，否则冷启动会闪一下
 * "还没有脚本" —— 那是假信息。
 *
 * @param onOpenEditor 点按某行 → 打开编辑器（`id` 为 `null` 表示新建）
 * @param viewModel **必须**由调用方传入，且必须与 `ScriptsTab` 刷计数用的是**同一个实例**
 *   （列表的计数新鲜度由调用方驱动，本页只投影）。
 *   **这里故意没有默认值**：`composable { }` 内的 `LocalViewModelStoreOwner` 是
 *   `NavBackStackEntry`，而 entry 的默认工厂不是 Hilt 工厂 —— 一旦写成
 *   `= viewModel()`，被使用时就会以 6c 编辑器同款方式闪退；即便补上 `factory`，
 *   拿到的也是 entry 作用域的另一份实例，会让 `refreshCounts()` 静默作用在没人渲染的实例上。
 */
@Composable
internal fun ScriptListScreen(
    onOpenEditor: (Long?) -> Unit,
    viewModel: ScriptListViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val whyNotRunning by viewModel.whyNotRunning.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示（删除部分成功 / 启用失败 / 计数不可用）。
    // 用 SharedFlow 而不是 StateFlow：提示是**事件**，状态化会让它在每次重组后重放。
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 内容区不再由 `RootFlowMain` 让位（阶段 7 方向 A），因此这里**不吃** Scaffold 的
        // innerPadding —— 让位已下移到本页的 `LazyColumn`（`ScriptList`）与 FAB（见下）。
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenEditor(null) },
                // ★★ 阶段 7 方向 A 的**必要补偿**（真机暴露，勿删）：
                //   FAB 是 Scaffold 的浮层，**不参与**滚动容器的 contentPadding，
                //   因此它曾经是"靠 `RootFlowMain` 内容层那 88dp 让位"才没被胶囊盖住的：
                //     · 改前：内容层 padding 把 Tab 视口上移 264px ⇒ FAB 底 ≈ root 1981，胶囊顶 2124 ⇒ 露在外面
                //     · 改后：视口回到全高 ⇒ FAB 落到 root 2136，而**胶囊顶是 2124**
                //       ⇒ 56dp 的 FAB 整体落在玻璃胶囊**之下**（真机实测：UI 树与像素里都没有它）
                //   所以让位必须由 FAB 自己承担，量取同一个 `NavBarReservedSpace`
                //   （与 `LazyColumn` 的 contentPadding 同源 ⇒ 改底栏高度时两处一起走）。
                modifier = Modifier.padding(bottom = NavBarReservedSpace),
            ) {
                Icon(imageVector = IconsPackage.Filled.AddIcon, contentDescription = "新建脚本")
            }
        },
    ) { _ ->
        if (state.empty) {
            EmptyState()
        } else {
            ScriptList(
                rows = state.rows,
                onOpen = onOpenEditor,
                onToggle = viewModel::toggleEnabled,
                onDelete = viewModel::requestDelete,
                onWhy = viewModel::openWhyNotRunning,
            )
        }
    }

    // ★ P4：「为什么没跑」（方案 §5.3）—— 点某行的状态文字打开。
    //   它是一个**只读**弹窗：打开时读一次全链（`openWhyNotRunning`），关闭不做任何写入。
    whyNotRunning?.let { data ->
        WhyNotRunningDialog(data = data, onDismiss = viewModel::dismissWhyNotRunning)
    }

    pendingDelete?.let { row ->
        DeleteConfirmDialog(
            row = row,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/**
 * 空态：**不显示假数据**，只给一句可操作的话 + 一条"下一步做什么"。
 *
 * 6e：补上"触发器在编辑器里配"这一句 —— 新用户的第一问是"我建了脚本，它什么时候跑"，
 * 而答案（勾事件）在下一个页面里。
 */
@Composable
private fun EmptyState() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有脚本",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "点右下角 + 新建",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "建好后在编辑器里勾选触发事件（开机 / 熄屏 / 定时…），脚本才会被唤醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 脚本列表。
 *
 * `key = { it.id }`：区分"这一行被复用了"与"这是另一条脚本"。
 * 少了它，开关与长按的按下态会**留在原位**而内容已经换成下一条（真机上表现为
 * "点第 3 行的开关，第 5 行的开关动了"）。
 */
@Composable
private fun ScriptList(
    rows: List<ScriptRowUi>,
    onOpen: (Long?) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (ScriptRowUi) -> Unit,
    onWhy: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // ★ 阶段 7（方向 A）：底栏的让位从「内容层整体 padding」下移到**本滚动容器**。
        //   两个作用缺一不可：
        //   ① 内容能滚到胶囊**下方**（玻璃才有东西可折射/模糊）；
        //   ② 最后一项静止时**不被胶囊压住**（bottom 留白 = 胶囊高 + 上下留白）。
        //   见 `NavBarReservedSpace` 的 KDoc。
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = NavBarReservedSpace,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = rows, key = { it.id }) { row ->
            // 阶段 6e：新增/删除行做位移+淡入淡出（`animateItem` 在 Compose 1.7 起是稳定 API，
            // 随 BOM 2025.09.00 已在，**不新增依赖**）。
            // 它只在"同一 key 的行出现/消失/换位"时动 —— 开关状态变化不触发动画。
            Box(modifier = Modifier.animateItem()) {
                ScriptRowItem(
                    row = row,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onWhy = onWhy,
                )
            }
        }
    }
}

/**
 * 单行脚本。
 *
 * ## 为什么 `lua` 行**只灰显副标题、不隐藏整行**
 * v1 只实现 shell（需求 §3.1 + §10），但库里可能已有 `lua` 行（导入 / 手工改库 /
 * 将来版本）。把它藏起来会让用户"脚本凭空消失"；显示出来并标明不可编辑才是如实的。
 * 删除**不受语言限制**：不能执行不等于不能删。
 *
 * ## 开关**没有本地状态**（重要）
 * 开关显示的一直是**库里**的值（列表是 Room 热流），拨动只是**发起一次写入**；
 * 写入成功由热流推送新值，写入失败则开关**原样不动**（见 [ScriptListViewModel.toggleEnabled]）。
 * 因此这里既没有 `remember` 的选中态，也没有回滚代码 —— 没有第二处真相，就没有回滚。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptRowItem(
    row: ScriptRowUi,
    onOpen: (Long?) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (ScriptRowUi) -> Unit,
    onWhy: (Long) -> Unit,
) {
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpen(row.id) },
                    onLongClick = { onDelete(row) },
                ),
        shape = CardCornerRadius,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // ★ P4：状态行（方案 §5.1 的"常驻 · 运行中"）兼"为什么没跑"的入口。
                //
                //   为什么入口挂在这一行而不是长按菜单：用户看到"已停止"时的第一反应
                //   就是想点它问为什么。长按已经给了删除，再塞一层菜单会让两者都难用。
                //   子元素自己的 `clickable` 会消费点击，因此不会连带触发整行的"打开编辑器"。
                Row(
                    modifier = Modifier.clickable { onWhy(row.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(row),
                    )
                    Text(
                        text = "ⓘ 为什么没跑？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    text = row.language + " · " + row.timeoutLabel + " · " + row.triggerSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.languageEditable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            // `lua` 灰显：v1 不可执行（需求 §10 明确不做 Lua 运行时）
                            MaterialTheme.colorScheme.outline
                        },
                )
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = { checked -> onToggle(row.id, checked) },
            )
        }
    }
}

/**
 * 状态文字的颜色。
 *
 * 这里只做**样式映射**（`true` → primary / `false` → onSurfaceVariant）；
 * "这一行算不算活着"的判定在 `ScriptRuntimeProjections.isLive`（纯函数，可被单测穷举）。
 */
@Composable
private fun statusColor(row: ScriptRowUi): Color =
    if (ScriptRuntimeProjections.isLive(row.enabled, row.masterEnabled, row.runtime)) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * 删除确认框（已批准决策 2：长按 → 确认）。
 *
 * ## 正文里的 N 必须如实（§0.3 ①②）
 * `triggerCount == null`（未知）时说"（触发器数未知）"，**绝不编 0** ——
 * 说 0 会让用户以为"删了没影响"，而实际会级联删掉 N 条触发器。
 * `N == 0` 时那一句整个不显示（说"将一并删除 0 条"是噪音）。
 */
@Composable
private fun DeleteConfirmDialog(
    row: ScriptRowUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = ScriptProjections.deleteDialogTitle()) },
        text = {
            Text(
                text =
                    ScriptProjections.deleteDialogBody(
                        displayName = row.displayName,
                        triggerCount = row.triggerCount,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}
