package com.rootflow.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底栏图标（需求 §6）。
 *
 * ## ★ 为什么图标单独一个文件（**实测踩坑记录，勿合并回 `TabDestination.kt`**）
 * `androidx.compose.material.icons.automirrored.filled.List` 是一个**属性**，
 * 名字与 `kotlin.collections.List` 完全相同。Kotlin 的 import 优先级高于默认导入，
 * 因此只要在某个文件里 import 了它（**加 `as` 别名也一样**），该文件里所有
 * `List<…>` 类型引用都会被解析到图标上。
 *
 * 后果：`enum class TabDestination` 的合成 `values()` 返回
 * `List<TabDestination>` ⇒ 编译器在**图标那一行**报
 * ```
 * e: TabDestination.kt:… Unresolved reference.
 *    None of the following candidates is applicable because of a receiver type mismatch
 * ```
 * 报错位置指向图标、错误信息谈的是 receiver 类型，**与根因（类型被遮蔽）看起来毫无关系**，
 * 实测为此连试两轮（别名 / 全限定名都无效——`Icons.AutoMirrored.Filled.List` 仍然要那个 import 才可见）。
 *
 * 解法是把"必须 import 那个名字"的代码隔离进单独文件（本文件），
 * 且本文件**不引用** `kotlin.collections.List`。
 * `TabDestination.kt` 因此可以自由使用 `List` 类型。
 *
 * ## 为什么用 `AutoMirrored`
 * `Icons.Filled.List` 已标记 `@Deprecated`（编译期警告：
 * "Use the AutoMirrored version at Icons.AutoMirrored.Filled.List"）。
 * 本项目是 RTL-aware 的（清单里 `android:supportsRtl="true"`），
 * 列表图标在阿拉伯语等 RTL 语言下应当镜像，因此 AutoMirrored 版本才是正确选择——
 * 既消掉警告，也真的修掉一个 RTL 下的显示问题。
 *
 * ## 为什么 core 图标集里它最贴合"配置 / 脚本列表"
 * 本阶段只用 `material-icons-core`（`extended` 的 AAR 是 34.9MB，见 `libs.versions.toml`）。
 * 其余候选是 `Menu`（语义是"展开菜单"）与 `Build`（扳手，语义是"构建"），
 * 都不如 `List` 贴合"脚本列表"。
 *
 * ## 用法
 * [TabDestination.icon] 是本文件唯一的公开出口：底栏只调它，
 * 因此"哪个 Tab 用哪个图标"这条判定**只有一处**。
 */
internal fun TabDestination.icon(): ImageVector =
    when (this) {
        TabDestination.HOME -> Icons.Filled.Home
        TabDestination.SCRIPTS -> Icons.AutoMirrored.Filled.List
        TabDestination.SETTINGS -> Icons.Filled.Settings
    }
