package com.rootflow.ui.theme

import androidx.compose.ui.unit.dp

// 底栏（悬浮胶囊）的**几何常量**（阶段 7 归拢；数值全部是 6a/6b/6e 已确认的既有值）。
//
// ## 为什么要单独成文件（不是"整理癖"）
// 阶段 7 的布局改造（方向 A）之后，底栏的几何被**三个地方**同时消费：
// 1. `RootFlowMain` —— 玻璃层的定位（与胶囊逐像素叠放）与 `NavBarReservedSpace` 的计算
// 2. `FloatingNavBar` —— 胶囊自己的尺寸、留白与模糊半径
// 3. 各 Tab 的滚动容器 —— `contentPadding(bottom = NavBarReservedSpace)`
//
// 这三处只要有一处写成字面量，就会出现"改了底栏高度、Tab 底部留白没跟着变"这类
// **只会在真机截图上发现**的漂移（与 `CardCornerRadius` 的 KDoc 同一条理由）。
//
// ## ★ 哪些值**不得**改动
// `NavBarBlurRadius` / `NAV_BAR_NOISE_FACTOR` / `NAV_BAR_FALLBACK_ALPHA` 是 6b 真机确认过的观感值：
// 模糊管线本身在真机上被确认"参数是对的、仍不得调整"（`PROJECT_STATE.md`「6b 收尾记录」）。
// 阶段 7 用 AGSL 取代"模糊"这一步，但**不删这三档降级路径**（已批准决策 P1）。
//
// 注：本节用行注释而不是 KDoc —— 文件首个声明自带 KDoc，两个 KDoc 相邻会被
// ktlint 的 `no-consecutive-comments` 拦下。

/**
 * 底栏胶囊的圆角（**需求 §6 点名的数值**）。
 *
 * 单独成常量而不是让 `FloatingNavBar` 写 `MaterialTheme.shapes.large`：
 * 需求把 28.dp 写死在底栏这一项上，而 `large` 是"大容器"的语义档位，
 * 两者的耦合是**巧合**而非契约。写成显式常量后，
 * 若将来圆角体系整体变大，底栏是否跟着变是一个**需要决定的动作**，而不是一个静默的副作用。
 */
internal val NavBarCornerRadius = 28.dp

/** 底栏胶囊的高度（不含导航栏 inset）。 */
internal val NavBarHeight = 64.dp

/**
 * 底栏的纵向留白（胶囊上下各一份）。
 *
 * 6a 起是 `padding(horizontal = 20.dp, vertical = 12.dp)` 里的字面量；
 * 阶段 7 提为常量，因为[玻璃层的定位][com.rootflow.ui.GlassLayer]必须用同一套几何，
 * 否则玻璃与胶囊会错位 —— 而"错位"看起来正是"折射做错了"。
 */
internal val NavBarVerticalPadding = 12.dp

/** 底栏的横向留白（同 [NavBarVerticalPadding] 的由来）。 */
internal val NavBarHorizontalPadding = 20.dp

/**
 * 底栏模糊半径（阶段 11d：**24 → 8 dp**）。
 *
 * ★ **两处消费，必须取同一个值**：
 * - Tier 2 的 `RenderEffect.createBlurEffect`
 * - Tier 1 的 `GlassParams.blurRadius`（链里的 inner blur）
 *
 * 取同一个值是为了让"跨档切换时糊的程度不跳一下"（`STAGE7-PLAN.md §3` 硬要求 3）。
 *
 * ## ★★ 为什么从 24 降到 8（阶段 11d，用户实测反馈「液态玻璃还是没用」）
 * 24dp 是 **6b 的"毛玻璃"时代**确认的值 —— 那时底栏要的就是"一坨糊掉的半透明"。
 * 但**液态玻璃不是毛玻璃**：它靠**边缘折射**立起来，模糊只是配角。
 *
 * 对参照对象 `OPCameraPro 3.2.10` 的 `LiquidGlassWatermarkEffect` 实测其取向是
 * **`blurRadius = 0.8`、`tintAlpha = 0.012`** —— 几乎不模糊、几乎不遮挡。
 * 24dp 的模糊会把胶囊背后的文字**完全糊成均匀的一片灰**，
 * 于是"玻璃"读起来就是"一块不透明的板" —— 这正是用户看到的现象。
 *
 * 8dp 的取舍：仍能柔化背后的高频细节（文字不会与图标打架），
 * 但**保留了可辨认的形状** —— 那是"透过玻璃看见东西"的必要条件。
 *
 * ## 与 `STAGE7-PLAN §8` 第 10 条的关系
 * 那条写的是"不改 `NAV_BAR_BLUR_RADIUS` / `NOISE_FACTOR` / `FALLBACK_ALPHA`"，
 * 但它是**阶段 7 的范围约束**，前提是"阶段 7 只做折射、不动 6b 的观感"。
 * 阶段 11d 的用户诉求正是"液态玻璃要看得出来" ⇒ **该前提已不成立**，
 * 本条与 [GLASS_SCRIM_ALPHA] 一并经用户指令解锁。
 * **[NAV_BAR_NOISE_FACTOR] 与 [NAV_BAR_FALLBACK_ALPHA] 未动**（它们只作用于降级档）。
 */
internal val NavBarBlurRadius = 8.dp

/**
 * 噪声系数（需求 §6 点名 `noiseFactor`）。
 *
 * 0.12f 的作用是给纯色模糊加一点颗粒，避免大面积模糊在大屏上出现色带。
 * **不可调到 0**：那样玻璃会退化成"高斯模糊的塑料片"。
 */
internal const val NAV_BAR_NOISE_FACTOR = 0.12f

/**
 * 底栏降级底色与 Haze `fallbackTint` 的 alpha（需求 §6 原文：`surface.copy(alpha = 0.92f)`）。
 *
 * 两个用途共用一个常量是**有意的**：它们表达的是同一个视觉意图
 * （"这里是半透明的表面色"），分成两个常量只会让将来调整时漏掉一处。
 *
 * ★ Tier 1/2（`RenderEffect` 路线）**不**用这个 0.92 底面，改用
 * [GLASS_SCRIM_ALPHA] —— 理由见那一处的 KDoc（0.92 会把折射压成看不见）。
 * **本常量本身不得改动**（6b 已确认）。
 */
internal const val NAV_BAR_FALLBACK_ALPHA = 0.92f

/**
 * Tier 1 / Tier 2 玻璃**底面遮罩**的 alpha（阶段 7 新增）。
 *
 * ## 为什么它不是 [NavBarFallbackAlpha]（这是本阶段一处需要说明的选择）
 * `NAV_BAR_FALLBACK_ALPHA = 0.92f` 是**降级底色**的语义：那时底栏后面没有任何
 * 被模糊过的内容（Haze 关闭 / API < 31），需要一层足够实的膜来保证文字可读性。
 *
 * 而 Tier 1 的底栏后面是一条**已经被 blur 过、并被折射过**的内容
 * （链：`createChainEffect(refraction, blur)`）。若仍铺 0.92 的实色，
 * 折射只有 8% 的可见度 —— 那等于"做了折射但看不见"，
 * 而阶段 7 的验收判据恰恰是「玻璃透出内容」「能看出折射位移」。
 *
 * 取 0.10 的依据（**阶段 11d 从 0.22 下调**）：
 * 拿参照对象 `OPCameraPro 3.2.10` 的 `LiquidGlassWatermarkEffect` 对比 ——
 * 它的 `tintAlpha` 是 **0.012**（几乎不遮）。0.22 会把手边的 [NavBarBlurRadius] 再压一道，
 * 两者叠加的结果就是"背后的内容完全看不见" ⇒ 用户判定"液态玻璃没用"。
 * 0.10 保留"压一点亮度、保证图标文字可读"的作用，但**让背后的形状透出来**。
 */
internal const val GLASS_SCRIM_ALPHA = 0.10f

/**
 * 滚动内容需要为浮起底栏让出的高度 = 胶囊高度 + 上下各一份纵向留白。
 *
 * ## ★ 阶段 7：这个常量**没有消失**，只是换了消费者（方向 A）
 * 6a 起它作用在 `RootFlowMain` 的**内容层**上（`padding(bottom = …)`）——
 * 那正是 Phase 1a 查出的结构性问题的根源：内容因此**永远滚不到胶囊背后**，
 * 玻璃没有东西可折射。
 *
 * 方向 A（用户 2026-09-20 裁定）把它下移到**各 Tab 的滚动容器**（`LazyColumn` 的
 * `contentPadding` bottom / `verticalScroll` 末尾的 `Spacer`）。语义**完全一样**：
 * 「内容能滚到胶囊下方（玻璃有东西可折射）」+「最后一项静止时不被胶囊压住」。
 *
 * 之所以仍然集中在一个常量里：让位的**总量**是底栏的几何属性；三个 Tab 各自引用它，
 * 而不是各自抄一个 `88.dp`（那样改一处就会漏两处）。
 *
 * ## ⚠️ 声明顺序（与 `AGENT_PROTOCOL.md §5.11` 同款的坑）
 * Kotlin 的**顶层属性初始化器按文件内的声明顺序求值**：本属性引用了
 * [NavBarHeight] 与 [NavBarVerticalPadding]，因此必须排在它们**之后**（本文件末尾）。
 * 上移会得到 `Variable 'NavBarHeight' must be initialized` —— 而报错位置指向**使用处**，
 * 与根因（声明顺序）隔着一个屏幕。**勿上移。**
 */
internal val NavBarReservedSpace = NavBarHeight + NavBarVerticalPadding * 2
