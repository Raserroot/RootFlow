package com.rootflow.ui.scripts

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/**
 * 配置 Tab 的导航宿主（阶段 6c，需求 §6「预测性返回」）。
 *
 * ## ★ `NavHost` 挂在 **SCRIPTS Tab 内部**，不提升到根（已批准决策 4）
 * 提升到根会把"Tab 切换"与"路由切换"揉成两套 back stack（**两个真相**），
 * 而 6a 已确立"Tab + 各自内容"的结构。挂在 Tab 内部的代价只有一个：
 * **切到别的 Tab 再切回来会回到列表页**（编辑器的回退栈在 Tab 离开时被销毁）。
 * 那是可接受的：Tab 切换本身就是"离开这一块功能"。
 *
 * ## 路由表
 * | 路由 | 页面 |
 * |---|---|
 * | `scripts` | [ScriptListScreen] |
 * | `scripts/editor` | [ScriptEditorScreen]（新建） |
 * | `scripts/editor/{id}` | [ScriptEditorScreen]（编辑） |
 *
 * ## 计数快照在"回到列表"时刷新（已批准决策 3）
 * `currentBackStackEntryFlow` 每次当前路由变化都会发射 ⇒ 回到 `scripts` 时刷一次。
 * **比 pop 回调可靠**：pop 回调只覆盖"从编辑器返回"这一条路径，
 * 而系统恢复 / 深层链接也会让当前路由变成 `scripts`（那时同样需要一次新鲜计数）。
 *
 * ## 转场（零新依赖）
 * `list ↔ editor` 用 `slideInHorizontally + fadeIn`，返回反向。
 * `compose-animation` 随 Compose BOM 已在，**不新增任何依赖**
 * （`STAGE6C-PLAN.md §8 第 7 条`）。
 */
@Composable
internal fun ScriptsTab(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // 当前路由回到列表 ⇒ 刷一次计数（见类 KDoc）。
    //
    // ★ 列表的 ViewModel 在这里取**一次**，再显式传给 `ScriptListScreen`。
    //   此处的 `LocalViewModelStoreOwner` 是 **Activity**（`ScriptsTab` 在 `NavHost` 之外），
    //   所以 `viewModel()` 走的是 Hilt 工厂，得到 **Activity 作用域**实例。
    //
    // ⚠️ 不要在 `composable { }` 里用 `viewModel()` 取它：那里的 owner 是
    //   `NavBackStackEntry`，而 entry 的默认工厂**不是 Hilt 工厂** —— 6c 编辑器闪退
    //   就是这个坑（见 `ScriptEditorScreen.hostViewModelFactory`）。给它补 `factory`
    //   也不对：那得到的是 **entry 作用域**的另一份实例，下面这行 `refreshCounts()`
    //   会作用在"没人渲染"的实例上（静默失效，比闪退更难发现）。
    //   所以 `ScriptListScreen` 的 `viewModel` 形参**故意没有默认值**，由编译器拦住。
    val listViewModel: ScriptListViewModel = viewModel()

    LaunchedEffect(navController, listViewModel) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (entry.destination.route == ScriptRoutes.LIST) {
                listViewModel.refreshCounts()
                // ★ P4：运行态快照与计数**同源同刷**（两个都是一次性查询的进程内快照）。
                //   漏掉这里的后果很具体：用户拨完脚本开关回到列表，状态文字仍显示旧值，
                //   而那个旧值看起来完全合理（"已停止"）—— 属于最难被发现的过期显示。
                listViewModel.refreshRuntime()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ScriptRoutes.LIST,
        modifier = modifier,
        enterTransition = { slideInHorizontally(initialOffsetX = { width -> width / 4 }) + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { width -> width / 4 }) + fadeOut() },
    ) {
        composable(route = ScriptRoutes.LIST) {
            ScriptListScreen(
                onOpenEditor = { id -> navController.navigate(ScriptRoutes.editor(id)) },
                viewModel = listViewModel,
            )
        }

        composable(route = ScriptRoutes.EDITOR_NEW) { entry ->
            ScriptEditorScreen(backStackEntry = entry, navController = navController)
        }

        composable(
            route = ScriptRoutes.EDITOR_ARG,
            arguments =
                listOf(
                    navArgument(ScriptRoutes.ARG_ID) {
                        type = NavType.StringType
                        // 可空：`scripts/editor/{id}` 与 `scripts/editor` 是两条独立路由，
                        // 因此这里实际总能拿到值；声明 nullable 只是让"参数缺失"这条
                        // 边界落到 `ScriptEditorArgs.parse(null)`（⇒ 新建）而不是崩。
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            ScriptEditorScreen(backStackEntry = entry, navController = navController)
        }
    }
}

/**
 * 配置 Tab 的路由常量（**唯一真相源**）。
 *
 * ## 为什么抽成常量而不是在 `navigate(...)` 里拼字符串
 * 路由字符串有两处消费（`composable(route = …)` 与 `currentBackStackEntryFlow`
 * 的对比），拼错任一处都不会编译失败 —— 表现是"点了行没反应"或"计数永不刷新"。
 * 抽成常量后，编译器替我们看着这两处。
 */
internal object ScriptRoutes {
    /** 路由参数名；与 [ScriptEditorViewModel.KEY_SCRIPT_ID] **必须同字**（两处都是常量，改一处会漏另一处）。 */
    const val ARG_ID: String = ScriptEditorViewModel.KEY_SCRIPT_ID

    /** 参数化路由的模板。 */
    const val EDITOR_ARG: String = "scripts/editor/{$ARG_ID}"

    /**
     * 新建脚本的**独立**路由（不带参数）。
     *
     * 不用 `scripts/editor/new` + `parse("new")` 那种"用一个魔法字符串表示没有参数"的写法：
     * 那会让"某个脚本的 id 恰好叫 new"成为一个理论上的歧义，而独立路由在类型上就没有这个洞。
     */
    const val EDITOR_NEW: String = "scripts/editor"

    /** 列表页路由。 */
    const val LIST: String = "scripts"

    /** 拼出编辑某脚本的路由。 */
    fun editor(id: Long?): String = if (id == null) EDITOR_NEW else "scripts/editor/$id"
}
