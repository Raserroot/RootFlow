package com.rootflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rootflow.ui.master.MasterSwitchCard
import com.rootflow.ui.theme.BannerCornerRadius
import com.rootflow.ui.theme.NavBarReservedSpace

/**
 * 主页（需求 §6：服务状态卡片、环境信息卡片、深色终端实时日志）。
 *
 * ## ★★ 阶段 8 的视觉重构（对齐 LSPosed 参考图 `ref-lsposed.png`）
 * ```
 * 安全模式 banner（条件显示）
 * RootFlow                        ← 大字号标题（替代 AppBar）
 * ┌──────────────────────────┐
 * │ 服务运行中          ╭───╮ │   ← 底色随状态：primaryContainer / errorContainer / surfaceVariant
 * │ 0.6.0-ui            │ ✓ │ │
 * │ API 35 · unknown    ╰───╯ │
 * └──────────────────────────┘
 * ┌──────────────────────────┐
 * │ Android 版本              │   ← label 大字 + value 灰色小字
 * │ 15 (API 35)              │      **无分隔线**，全靠 20dp 留白分组
 * │ 设备                      │
 * │ OnePlus PLK110 · arm64…  │
 * │ Root 方案 / SELinux / …   │
 * │ 事件源 6 / 7 可用 ⚠ 1 个不可用 │
 * └──────────────────────────┘
 * ┌──────────────────────────┐
 * │ 运行日志（终端，结构不变）  │
 * └──────────────────────────┘
 * ```
 *
 * 变化点（相对 6b）：① 新增顶部大标题；② 服务卡改为**底色随状态 + 右侧大图标 + 三行**；
 * ③ 原「环境信息卡 + 事件源卡」**合并为一张行式信息卡**；④ 终端位置不变（仍在最后）。
 *
 * ## 为什么标题不用 `TopAppBar`
 * 需求 §6 的参照物（LSPosed / Magisk）都是**无 AppBar** 的大标题版式：
 * 一个大字号站名 + 卡片流。`TopAppBar` 会引入一层容器高度与滚动行为
 * （`enterAlwaysScrollBehavior` 之类），而本页并不需要它。
 *
 * ## 数据流**完全不变**（阶段 8 的硬约束）
 * 本页仍然只订阅 `HomeViewModel.uiState` / `safeMode` / `safeModeDetail`，
 * 三个 domain 端口（`ServiceStateProvider` / `EnvironmentInfoProvider` / `RunActivityProvider`）
 * 与投影层一行未动。新版的版式全部由 `HomeProjections` 的**新增纯函数**供给，
 * 因此"卡片显示什么"依然可以在纯 JVM 下断言。
 *
 * ## ★ 布局：整页 `LazyColumn` + 终端**固定高度**
 * 终端自身是 `LazyColumn`（要独立滚动与 auto-scroll）。把内层 `LazyColumn` 放进
 * 外层滚动容器而**不给高度**，内层会得到 `Constraints.Infinity` 而直接抛异常；
 * 即便不抛，同向嵌套滚动也会让手势归属不确定（用户想滚终端却滚了页面）。
 * 因此终端是 280.dp 的固定面板，页面整体仍可滚动（小屏上卡片不会被终端挤掉）。
 *
 * ## ViewModel 的作用域（**沿用 6a 的已批准决策**）
 * `viewModel()` 拿到的是 **Activity 作用域**实例（走 `Hilt_MainActivity` 的默认工厂）。
 * 对本页是正确的：日志终端的收集作业**不该**在切 Tab 时被取消。
 * `hiltViewModel()` 因 `androidx.hilt:hilt-navigation-compose` 不在离线缓存而不可用。
 *
 * @param viewModel 主页 ViewModel（默认由 Hilt 的 Activity 工厂构造）
 */
@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val safeMode by viewModel.safeMode.collectAsStateWithLifecycle()
    val safeModeDetail by viewModel.safeModeDetail.collectAsStateWithLifecycle()
    val masterSwitchNotice by viewModel.masterSwitchNotice.collectAsStateWithLifecycle()

    // 进主页探一次环境。Key 用 `Unit`：只在**首次进入组合**时跑一次。
    // 重入保护在 ViewModel 里（那才是唯一该管这件事的地方），这里不做第二道守卫。
    LaunchedEffect(Unit) { viewModel.requestEnvironmentRefresh() }

    val infoRows = HomeProjections.environmentInfoRows(state.environment)

    Column(modifier = modifier.fillMaxSize()) {
        if (safeMode) {
            SafeModeBanner(detail = safeModeDetail)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // ★ 阶段 7（方向 A）：底栏的让位从「内容层整体 padding」下移到**本滚动容器**。
            //   两个作用缺一不可：
            //   ① 内容能滚到胶囊**下方**（玻璃才有东西可折射/模糊）；
            //   ② 最后一项静止时**不被胶囊压住**（bottom 留白 = 胶囊高 + 上下留白）。
            contentPadding =
                PaddingValues(
                    start = SCREEN_HORIZONTAL_PADDING,
                    end = SCREEN_HORIZONTAL_PADDING,
                    top = 0.dp,
                    bottom = NavBarReservedSpace,
                ),
            verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
        ) {
            item(key = "title") {
                PageTitle()
            }
            // ★ P4：总开关紧跟标题（方案 §5.1 的"总开关的家"）。
            //   放在服务卡**之前**：它是全局唯一的开关，用户进主页第一眼要看到"现在开不开"，
            //   而不是先读一段服务状态。
            item(key = "masterSwitch") {
                MasterSwitchCard(
                    state = state.masterSwitch,
                    notice = masterSwitchNotice,
                    onToggle = viewModel::setMasterSwitch,
                )
            }
            item(key = "service") {
                ServiceStatusCard(
                    service = state.service,
                    appVersion = HomeProjections.rowValue(infoRows, "App 版本"),
                    thirdLine =
                        HomeProjections.statusThirdLine(
                            // ★ 必须传**原始** environment 行：`apiLevelOf` 找的是 `"Android"`
                            //   这个 label，而 `infoRows` 已经把它改名为 `"Android 版本"`
                            //   （真机实测：传 infoRows 会得到"API 未知"）。
                            apiLevel = HomeProjections.apiLevelOf(state.environment),
                            rootFlavor = HomeProjections.rowValue(state.environment, "Root"),
                        ),
                )
            }
            item(key = "info") {
                InfoCard(
                    rows = infoRows,
                    eventRows = state.sources,
                    loaded = state.environmentLoaded,
                    refreshing = state.environmentRefreshing,
                    onRefresh = viewModel::requestEnvironmentRefresh,
                    // 阶段 8 不做跳转（那要动设置页的锚点，属明令不动的范围）
                    // ⇒ 如实传 null（本行因此不可点），而不是给一个点了没反应的入口。
                    onOpenEventSources = null,
                )
            }
            item(key = "terminal") {
                LogTerminalCard(
                    state = state.terminal,
                    onSelectFilter = viewModel::selectFilter,
                )
            }
        }
    }
}

/**
 * 页面大标题（替代 AppBar）。
 *
 * ## 尺寸与留白（阶段 8 规格）
 * `headlineMedium`（28sp）+ medium 字重；与内容同边距（16dp）；
 * 上方 16dp、下方一个卡片间距。状态栏让位由 `RootFlowMain` 的
 * `windowInsetsPadding(safeDrawing)` 统一负责，本页**不再重复吃 inset**。
 */
@Composable
private fun PageTitle() {
    Text(
        text = "RootFlow",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = TITLE_TOP_PADDING, bottom = TITLE_BOTTOM_PADDING),
    )
}

/** 屏幕左右边距（标题与卡片**同一套**，否则标题看起来会比卡片缩进）。 */
private val SCREEN_HORIZONTAL_PADDING = 16.dp

/** 卡片之间的间距。 */
private val CARD_SPACING = 12.dp

/** 标题上方的留白（状态栏让位之后再加这一段，标题才不会贴着状态栏）。 */
private val TITLE_TOP_PADDING = 16.dp

/**
 * 标题下方的留白。
 *
 * 24.dp 比卡片间距（12.dp）大一倍：标题与第一张卡之间要能"断"开，
 * 否则大标题会被读成第一张卡的标题。
 */
private val TITLE_BOTTOM_PADDING = 24.dp

/**
 * 安全模式横幅（需求 §5.4「UI 顶部 banner」）。
 *
 * ## 成因取不到时**如实说未知**
 * `safeModeDetail` 为 `null` 时显示 `ServiceNotificationText.REASON_UNKNOWN`
 * （由 `HomeViewModel.safeModeDetail` 的投影保证）。**不得**用 `reasonKey` 编一个原因。
 *
 * ## 为什么用 `errorContainer` 而不是固定红色
 * 安全模式是"已受控的异常状态"，不是崩溃：底色用 MD3 的 `errorContainer`
 * （在浅/深与动态取色下都保证对比度），文字用 `onErrorContainer`。
 * 手写红色会在深色主题下失去对比度保证（6a 的 F2 教训）。
 */
@Composable
private fun SafeModeBanner(detail: String?) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = BannerCornerRadius, bottomEnd = BannerCornerRadius))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = SCREEN_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "已进入安全模式 · 脚本已全部停止",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "原因：" + HomeProjections.safeModeDetail(detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
