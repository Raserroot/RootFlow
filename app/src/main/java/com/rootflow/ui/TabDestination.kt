package com.rootflow.ui

/**
 * 三个底部 Tab（需求 §6「三 Tab：主页 / 配置 / 设置」）。
 *
 * ## 为什么用 `enum` 而不是 `sealed class` + 字符串路由
 * 6a–6b 的导航是"平级三页"，没有参数、没有回退栈。用 enum 表达后：
 * - [TabDestinations.ALL] 就是底栏的渲染顺序，**不可能**出现"底栏少画一个 Tab"
 * - [TabDestinations.fromIndex] 是纯函数，可单测边界（见 `MainViewModelTest`）
 * - 6c 引入 `NavHost` 时，enum 直接充当路由的**起点**；若某个 Tab 需要子页面，
 *   再把该 Tab 的路由升级为带参数的 sealed 类型
 *
 * ## `logKey` 为什么与 `title` 分开
 * `title` 是**用户可见文案**（将来必然要翻译/改写），而 `logKey` 是真机
 * `findstr` 判读用的稳定键（`AGENT_PROTOCOL.md §7.3` 的 `UI_TAB_SELECTED tab=…`）。
 * 用 `title` 当日志键会让"改一次文案 → 真机验证命令全部失效"。
 *
 * ## 图标在哪
 * 图标由 [icon] 扩展函数提供，而它**必须**在另一个文件里
 * （`androidx.compose.material.icons.filled.List` 会遮蔽 `kotlin.collections.List`，
 * 导致本文件无法再声明返回 `List` 的东西）。原因与实测报错原文见 `TabIcons.kt`。
 *
 * ## ★ 为什么"顺序 / 默认值 / 下标查找"在 [TabDestinations] 而不是 companion
 * Kotlin 的 enum companion **不能引用 `entries`**：编译器对
 * `val ALL = entries.toList()` 报「Companion object of enum class … is uninitialized here」，
 * 改成 `get()` 也不行（同一个初始化顺序问题）。这不是风格问题，是硬约束。
 * 因此这三项放在同级对象 [TabDestinations] 里，语义上仍是"Tab 的集合级操作"。
 */
enum class TabDestination(
    val title: String,
    val logKey: String,
) {
    /** 主页：服务状态 / 环境信息 / 深色终端实时日志（6b）。 */
    HOME(
        title = "主页",
        logKey = "home",
    ),

    /** 配置：脚本列表 / 新建 / 编辑 / 触发器勾选（6c、6e）。 */
    SCRIPTS(
        title = "配置",
        logKey = "scripts",
    ),

    /** 设置：外观 / Root 驻留 / 安全熔断 / 日志 / 关于（6d）。 */
    SETTINGS(
        title = "设置",
        logKey = "settings",
    ),
}

/**
 * [TabDestination] 的集合级操作（底栏顺序 / 默认值 / 下标查找）。
 *
 * 与 enum 分开的理由见 [TabDestination] 的 KDoc。
 */
object TabDestinations {
    /** 底栏渲染顺序（**唯一真相源**：底栏与内容区都从这里取）。 */
    val ALL: List<TabDestination> = TabDestination.entries.toList()

    /** 默认 Tab（冷启动、或恢复失败时）。 */
    val DEFAULT: TabDestination = TabDestination.HOME

    /**
     * 由下标取 Tab；越界回落 [DEFAULT]。
     *
     * ## 为什么不直接 `entries[index]`
     * 这个下标可能来自 `savedInstanceState`（系统恢复的 Bundle），或 6c 之后的路由回退栈。
     * 它的内容**不受本应用控制**：旧版本写入的下标在新版本里可能越界
     * （Tab 增删、顺序调整）。`entries[index]` 会抛
     * `ArrayIndexOutOfBoundsException`，表现为"升级后一开 App 就崩"。
     * 回落默认值是**响亮地降级**：用户看到主页，而不是崩溃。
     */
    fun fromIndex(index: Int): TabDestination = ALL.getOrNull(index) ?: DEFAULT
}
