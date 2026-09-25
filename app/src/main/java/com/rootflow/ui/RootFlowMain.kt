package com.rootflow.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rootflow.domain.glass.GlassParams
import com.rootflow.domain.glass.GlassPolicy
import com.rootflow.domain.glass.GlassTier
import com.rootflow.domain.settings.BlurPolicy
import com.rootflow.domain.settings.RootFlowSettings
import com.rootflow.ui.component.AndroidGlassEffectFactory
import com.rootflow.ui.component.FloatingNavBar
import com.rootflow.ui.component.GlassEffectFactory
import com.rootflow.ui.component.GlassLayer
import com.rootflow.ui.component.LiquidGlassRenderer
import com.rootflow.ui.home.HomeScreen
import com.rootflow.ui.scripts.ScriptsTab
import com.rootflow.ui.settings.SettingsScreen
import com.rootflow.ui.theme.NavBarHeight
import com.rootflow.ui.theme.NavBarHorizontalPadding
import com.rootflow.ui.theme.NavBarVerticalPadding
import com.rootflow.ui.theme.RootFlowTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 应用根 Composable（阶段 6a 起：脚手架 + 悬浮胶囊底栏 + 三个 Tab）。
 *
 * ## 主题在这里落地，而不是在 `MainActivity`
 * `MainActivity` 只负责阶段 5 立下的三件事（拉起前台服务、申请通知权限、`setContent`）。
 * 主题读取属 **UI 逻辑**，因此放在本文件：[RootFlowTheme] 包住整个界面，
 * 设置值由 [MainViewModel] 提供。这样 `MainActivity` 不必持有任何与"长什么样"有关的知识。
 *
 * ## ★★ 阶段 7 的布局改造（方向 A，**这是液态玻璃能被看见的前提**）
 * ```
 * Box（根，铺满窗口，自己铺背景）
 *  ├─ ① 内容层（hazeSource + safeDrawing inset，**不再让位底栏**）
 *  ├─ ② 底部渐隐 scrim（保留）
 *  ├─ ③ 玻璃层（只占胶囊位置；内容是"被上移过的同一份页面内容"）
 *  └─ ④ FloatingNavBar（胶囊行 + 底面 + 描边；Tier 1/2 时**不调 hazeEffect**）
 * ```
 *
 * ### 6a 的让位为什么必须改（Phase 1a 的结构性发现）
 * 6a 起内容层整体带 `.padding(bottom = NavBarReservedSpace)`（88dp）
 * ⇒ **内容永远滚不到胶囊背后**，胶囊背后只有根 Box 的背景色。
 * 后果：需求 §6 的毛玻璃与阶段 7 的折射**都永远看不到效果**
 * （折射纯色与"一块不透明色块"没有区别）。
 *
 * ### 改法与理由（用户 2026-09-20 裁定：选 A，不选 C）
 * - **内容层去掉让位**，改由**各 Tab 的滚动容器自己加底部留白**
 *   （`NavBarReservedSpace`，消费者变成 `HomeScreen` / `ScriptListScreen` / `SettingsScreen`）：
 *   内容因此能从胶囊下方滚过（玻璃有东西可折射），而「最后一项静止时不被胶囊压住」
 *   这条可用性**仍然成立** —— 滚动容器的底部留白正是干这个的。
 * - **不选 C**（靠底栏自己的 scrim 遮住）：C 会让最后一项**永久**被胶囊遮挡，是可用性倒退。
 *
 * ## Haze 的 source 仍然挂在内容层（**不要挪到底栏里**）
 * Haze 的语义是「source 节点捕获自己身后的内容，effect 节点采样它」。底栏位于屏幕最下方，
 * 它**身后只有 App 自己的内容**，因此 source 必须挂在**页面内容那一层**。
 * Tier 2/3 的降级路径与 Tier 1 的离屏捕获**共用这一处**（`STAGE7-PLAN.md §1` 问题 2）。
 *
 * ## ★ 为什么是 `viewModel()` 而不是 `hiltViewModel()`（**§1.1 报告 2**）
 * 方案里写的是 `androidx.hilt.navigation.compose.hiltViewModel()`，但
 * **`androidx.hilt:hilt-navigation-compose` 不在本工作区的离线缓存里**（实测该目录不存在）。
 * 替代方案已用生成代码核实可行：`Hilt_MainActivity.getDefaultViewModelProviderFactory()`
 * 返回 `DefaultViewModelFactories.getActivityFactory(...)`，也就是说 **Activity 的默认
 * ViewModel 工厂本身就是 Hilt 工厂**，因此 `androidx.lifecycle.viewmodel.compose.viewModel()`
 * （由 `lifecycle-viewmodel-compose` 提供，**在缓存中**）能正常构造 `@HiltViewModel` 类。
 */
@Composable
fun RootFlowApp(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()

    RootFlowTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
    ) {
        RootFlowMain(
            currentTab = currentTab,
            settings = settings,
            onSelectTab = viewModel::select,
            modifier = modifier,
        )
    }
}

/**
 * 不依赖 ViewModel 的主界面（**可预览、可测试**）。
 *
 * 拆出这一层的理由与 `AGENTS.md` 的分层约束一致：`RootFlowApp` 只做"接 Hilt"，
 * 真正的布局在纯参数函数里。本函数始终只认"当前 Tab + 设置 + 回调"三个参数。
 */
@Composable
internal fun RootFlowMain(
    currentTab: TabDestination,
    settings: RootFlowSettings,
    onSelectTab: (TabDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // `isLowRamDevice` 是设备常量，缓存它而不是整个判定结果：
    // 否则用户每切一次"模糊开关"，都要再去问一次 ActivityManager。
    val isLowRamDevice = remember(context) { context.isLowRamDevice() }
    val blurSupported =
        remember(settings.blurEnabled, isLowRamDevice) {
            BlurPolicy.effectiveBlur(
                userSetting = settings.blurEnabled,
                isLowRamDevice = isLowRamDevice,
                apiLevel = Build.VERSION.SDK_INT,
            )
        }
    // blurEnabled 交给 HazeState（Haze 的官方降级开关）与 hazeEffect 的 block（见 FloatingNavBar）。
    // ★ 注意 `hazeSource` 在 1.6.10 里**没有** `enabled` 参数（`hazeSource(state, zIndex, key)`），
    //   所以"双写"只存在于这两处；早先注释里写的"同时交给 hazeSource"是错的，已更正。
    val hazeState = rememberHazeState(blurEnabled = blurSupported)

    // ★ 阶段 7：三档判定（纯函数，`GlassTierTest` 穷举钉死）。
    //   `graphicsCapabilityOk = true` 的口径与理由见 [GRAPHICS_CAPABILITY_ASSUMED_OK]。
    val glassTier =
        remember(settings.liquidGlassEnabled, blurSupported, isLowRamDevice) {
            GlassPolicy.decide(
                apiLevel = Build.VERSION.SDK_INT,
                graphicsCapabilityOk = GRAPHICS_CAPABILITY_ASSUMED_OK,
                liquidGlassEnabled = settings.liquidGlassEnabled,
                lowRam = isLowRamDevice,
                blurSupported = blurSupported,
            )
        }

    // ★ 内容槽位：**同一份内容**既能画在正常位置、又能被画进玻璃层（只传一次、用两次）。
    //   它不新增任何状态：Composable 是纯渲染，两次绘制共用同一批 ViewModel（Activity 域）。
    //   ★ 阶段 8.2：新增 `tab` 形参 —— 过渡期 `AnimatedContent` 会**同时**持有新旧两个 Tab，
    //     因此这里必须能按"任意一个 Tab"渲染，而不是只认 `currentTab`。
    val contentSlot: @Composable (TabDestination) -> Unit = { tab ->
        when (tab) {
            TabDestination.HOME -> HomeScreen()
            // 6c：配置 Tab 内部自带 `NavHost`（列表 ↔ 编辑器），因此这里不再传 `tab`
            TabDestination.SCRIPTS -> ScriptsTab()
            // 6d：设置页也不需要知道"自己是哪个 Tab"。
            TabDestination.SETTINGS -> SettingsScreen()
        }
    }

    // 胶囊顶边在**根坐标**里的 y（测量回填，见 `GlassLayer` 的 KDoc）。
    // 初值 0 = "还没测到"：那时玻璃层什么都不画（首帧而已，下一帧就有值）。
    var barTopInRootPx by remember { mutableIntStateOf(0) }

    // 内容层原点在**根坐标**里的 y（= safeDrawing 的顶 inset）。玻璃层的对齐量要用它当基准
    // —— **不是**屏幕高度：内容层自己就是从这一点往下画的（见 `GlassLayer` 的「坑 2」）。
    var contentLayerOriginPx by remember { mutableIntStateOf(0) }

    val renderer = remember { LiquidGlassRenderer(effectFactory = glassEffectFactory()) }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    // ★ F1（真机暴露，勿删）：根容器必须自己铺满整块窗口。
    // `MaterialTheme` 只提供颜色值，**不绘制背景**；而 `MainActivity` 调了
    // `enableEdgeToEdge()` ⇒ 系统栏透明 ⇒ 没有被 App 绘制的区域会露出 `themes.xml` 的
    // `windowBackground`（平台黑主题 = 纯黑）。真机现象就是"底部一条黑带 +
    // 导航栏区域一块黑色圆角色块"。铺上 background 后，手势区与状态栏区都由 App 自己着色。
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        // ① 内容层：Haze 的 source + 状态栏/刘海让位。
        //    ★ 这里**不再** `padding(bottom = NavBarReservedSpace)`（阶段 7 方向 A）：
        //    让位改由各 Tab 的滚动容器承担，内容因此能从胶囊下方滚过。
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    // ★ 阶段 7：IME（软键盘）让位。见 AndroidManifest 里
                    //   `windowSoftInputMode="adjustResize"` 的说明 —— 两者缺一不可：
                    //   清单让**窗口**为键盘收缩，这里让**Compose 内容**跟着收缩。
                    //   没有它时键盘是"盖"在内容上的一层：焦点输入框会被压在键盘下面，
                    //   用户看到的现象就是"点了没法输入、下半屏也滑不动"。
                    //   ⚠️ 它**必须排在最前**（在 safeDrawing 之前）：`PADDING` 型 inset 消费者
                    //   会把自己的消耗量累加进 `consumedWindowInsets`，排后面会让
                    //   `safeDrawing` 把 IME 高度也减掉一次（双重让位）。
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // 内容层原点（根坐标）：玻璃层把它当作"内容坐标系的原点"来对齐
                    .onGloballyPositioned { coordinates ->
                        val top = coordinates.positionInRoot().y.toInt()
                        if (top != contentLayerOriginPx) contentLayerOriginPx = top
                    },
        ) {
            // ★★ 阶段 8.2：Tab 切换的横向滑动过渡（**只包内容，不碰底栏**）。
            //   方向跟随 Tab 顺序，判定是纯函数（`MainTabTransition.direction`，有单测）。
            //   为什么不用 `HorizontalPager`、以及过渡期玻璃层的代价，
            //   见 `MainTabTransition` 的 KDoc（**决策记录，勿重新讨论**）。
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { MainTabTransition.transitionSpec(initialState, targetState) },
                label = "mainTab",
            ) { tab ->
                contentSlot(tab)
            }
        }

        // ② 底部渐隐：长列表滚到底时，文字不会"贴"在玻璃胶囊上。
        //    ★ 保留（用户裁定）：它的语义是"内容淡出到背景里"，与玻璃无关。
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(ScrimHeight)
                    .background(brush = bottomScrimBrush()),
        )

        // ③ 玻璃层：离屏捕获 + AGSL 折射（仅 Tier 1/2）。
        //    ★ 与 FloatingNavBar 的**同一套几何**（同一组常量、同一套 inset 与 padding）——
        //      两者必须逐像素叠放，否则玻璃与胶囊会错位。
        if (glassTier != GlassTier.HAZE) {
            GlassLayer(
                tier = glassTier,
                renderer = renderer,
                contentOffsetPx = barTopInRootPx,
                contentLayerOriginPx = contentLayerOriginPx,
                // 玻璃层里的副本始终用**目标 Tab**（`currentTab`）：它是"静止帧上应该
                // 出现在胶囊背后的内容"。过渡期的不同步是已知代价，见 `MainTabTransition`。
                contentSlot = { contentSlot(currentTab) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = NavBarHorizontalPadding, vertical = NavBarVerticalPadding)
                        .height(NavBarHeight),
            )
        }

        // ④ 悬浮胶囊底栏（Tab 行 + 底面 + 描边 + 阶段 11 的指示器）
        FloatingNavBar(
            currentTab = currentTab,
            onSelect = onSelectTab,
            hazeState = hazeState,
            blurSupported = blurSupported,
            glassTier = glassTier,
            onCapsuleTopMeasured = { top -> barTopInRootPx = top },
            // ★ 阶段 11：角标**本阶段没有数据源**（`STAGE11-PLAN.md §3.2` 的空能力登记）。
            //   显式传空表而不是省略参数 —— 省略会让"这里其实什么都没接"被默认值掩盖，
            //   而 `always_run` chip 的教训（README「未验证」第 4 条）正是这么来的。
            badges = emptyMap(),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部渐隐层的高度。 */
private val ScrimHeight = 96.dp

/**
 * 渐隐层的渐变（**跟随主题**，F2）。
 *
 * ## 为什么不是固定黑（真机暴露后的修正）
 * 初版写的是 `Color.Black.copy(alpha = 0.18f)`，理由是"渐隐就是要压暗"。
 * 真机截图推翻了这个理由：**深色主题下压暗等于什么都没做**（页面底色本就是 `#262626`，
 * 再叠 18% 黑几乎不可见），而在浅色主题下它又会把一条灰黑带压在内容上，
 * 与"大圆角卡片 + 玻璃"的观感相反。
 *
 * 正确做法是用**页面底色**做渐隐：它的语义是"内容淡出到背景里"，
 * 因此终点必须是背景色本身——这样在两种主题下都成立，也不需要按亮度分支。
 *
 * 0.85f 而不是 1f：留一点透明度，让渐隐区里的内容还有一丝可见，
 * 避免滚到底时出现一条"内容被切断"的硬边。
 */
@Composable
private fun bottomScrimBrush(): Brush =
    Brush.verticalGradient(
        colors =
            listOf(
                Color.Transparent,
                MaterialTheme.colorScheme.background.copy(alpha = SCRIM_END_ALPHA),
            ),
    )

/** 渐隐终点的 alpha（见 [bottomScrimBrush] 的说明）。 */
private const val SCRIM_END_ALPHA = 0.85f

/**
 * effect 工厂：**只有 API 31+ 才存在 `RenderEffect`**。
 *
 * 26–30 上没有可用的实现 ⇒ 给一个恒返回 `null` 的空实现。
 * 之所以不在这里用 `Build.VERSION.SDK_INT >= 31` 去 `if/else` 一个真工厂：
 * 那样会在 26–30 上**仍然构造**一个 `AndroidGlassEffectFactory`（并因此加载
 * `RuntimeShader` 相关的类引用），而这个空实现让"低版本没有这条路"在类型层面就成立。
 *
 * 注：[GlassTier.HAZE] 档根本不会走到这里（调用方在 `glassTier != HAZE` 时才建玻璃层）。
 */
private fun glassEffectFactory(): GlassEffectFactory =
    if (Build.VERSION.SDK_INT >= MIN_RENDER_EFFECT_API) AndroidGlassEffectFactory() else NoGlassEffect

/**
 * 26–30 上的空工厂（那时没有 `RenderEffect`，玻璃层根本不会被创建）。
 *
 * 单例而不是每次 `object : …` 匿名实现：匿名实现无法在单测里断言，
 * 而"低版本必须走空实现"这条判定值得有一个可命名的对象。
 */
private object NoGlassEffect : GlassEffectFactory {
    override fun create(
        tier: GlassTier,
        size: IntSize,
        params: GlassParams,
    ): RenderEffect? = null
}

/** `RenderEffect` 的最低 API（与 `BlurPolicy.MIN_BLUR_API_LEVEL` 同源）。 */
private const val MIN_RENDER_EFFECT_API: Int = 31

/**
 * 「图形能力是否可用」这个形参在本项目的取值（**阶段 7 的诚实登记**）。
 *
 * ## 为什么写死 `true` 而不是真去查询
 * `STAGE7-PLAN.md §3` 明确：**没有查到"AGSL 需要哪个 GL ES 等级"的权威口径**，
 * 因此不把成因写进判定函数。真机上实际发生的是：
 * 1. `GlassPolicy.decide` 先用「API 33+」这道**确定**的硬门（`RuntimeShader` 是 API 33
 *    起的框架类，已解包 `android.jar` 核实类与方法名存在）；
 * 2. 真正兜底的是 `AndroidGlassEffectFactory` 里的 `runCatching { RuntimeShader(agsl) }`
 *    —— 构造失败 ⇒ 降级到纯 blur（不崩、不黑屏）。
 *
 * 也就是说：这个 `true` **不会**造成"误判成可用然后崩"。
 * 将来若补齐权威查询口径，只需把这里（与 `SettingsScreen` 的同名常量）换成真实查询结果，
 * **判定函数与降级路径都不用改**。
 */
private const val GRAPHICS_CAPABILITY_ASSUMED_OK: Boolean = true

/**
 * 设备是否为低内存（需求 §6「低端机默认关闭」模糊）。
 *
 * `ActivityManager.isLowRamDevice` 是 SDK 19+ 的普通 getter，读的是 `ro.config.low_ram`
 * —— 无需权限、不阻塞、不会抛。取不到时按"不是低端机"处理（保守方向：宁可多开模糊，
 * 也不要让高端机被误降级，因为需求只要求"低端机默认关闭"）。
 */
private fun Context.isLowRamDevice(): Boolean =
    (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false
