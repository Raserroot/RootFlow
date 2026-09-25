package com.rootflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rootflow.service.KeepAliveService
import com.rootflow.ui.RootFlowApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * RootFlow 唯一 Activity（单 Activity + Compose 架构）。
 *
 * ## ★ 阶段 6a 起它只剩三件事，顺序是硬约束
 * 1. **`enableEdgeToEdge()`**（需求 §6「边缘到边缘」）—— 必须在 `setContent` **之前**，
 *    否则第一帧会先按不透明系统栏布局，再跳一次，肉眼可见地闪一下
 * 2. **拉起前台服务**：本类是全应用**唯一**拉起 `KeepAliveService` 的入口。
 *    为什么在这里而不是 `Application.onCreate`：后者可能发生在**后台进程启动**路径上，
 *    此时 `startForegroundService` 会撞上 Android 12+ 的后台前台服务启动限制
 *    （`ForegroundServiceStartNotAllowedException`）；而 Activity 处于前台，
 *    是系统唯一无条件允许的启动时机
 * 3. **一次性申请 `POST_NOTIFICATIONS`**（API 33+）：需求 §7 的前置，且**没有它
 *    前台服务通知不会显示**。这是纯系统权限弹窗，**不是**一个 Compose 页面/UI 功能
 *    （阶段 5 已批准的裁定 ②）
 *
 * ## ★★ 阶段 12c：第 2、3 件事**挪到"用户确认同意之后"**（顺序变了，理由如下）
 * 在此之前，`onCreate` 里是**无条件** `KeepAliveService.start()`：
 * ```
 * enableEdgeToEdge() → KeepAliveService.start() → 申请通知权限 → setContent { … }
 * ```
 * 而首启的知情同意声明页（`ui/consent/`）要求"**同意之后** App 才有这个行为"。
 * 若服务仍在 `onCreate` 里无条件启动，那份声明就是**形式**：用户在声明页上还在读，
 * 服务（以及它带来的保活行为）已经在跑了。
 *
 * 现在的顺序：
 * ```
 * enableEdgeToEdge() → setContent { RootFlowApp(onKeepAliveGranted = …, onKeepAliveDeclined = …) }
 *                          ↓ 读到 keepAliveConsent == true 时（含刚点同意）
 *                       onKeepAliveGranted() → 拉服务 + 申请通知权限
 * ```
 * 两条纪律**没有**因此放宽：
 * - `registerForActivityResult` **仍在字段初始化**（必须在 `STARTED` 之前注册）——
 *   只是"什么时候 `launch`"变成了"同意之后"
 * - 系统交互**仍不搬进 Composable**：Composable 只发出"可以了"的信号（`onKeepAliveGranted`），
 *   真正的 `startForegroundService` / 权限申请都在本类里
 *
 * ### 为什么把"申请通知权限"也一起挪了（**这条登记为一次有意的行为变更**）
 * 它此前在每次启动时无条件检查并申请。而通知是**保活机制的一部分**
 * （声明页的第 1、2 条都写着"会常驻一条通知"）⇒ 在用户还没看到并同意那份声明之前
 * 弹系统权限框，属于越权。现在它随服务一起放在同意之后。
 *
 * ### 迟一点启动服务有没有代价
 * 有，但可忽略且方向正确：从冷启动到"读到同意"要多等一次 DataStore 首帧（几百毫秒），
 * 期间界面是加载态。这段时间**事件源尚未启动**（它们的所有者是前台服务），
 * 而代价只是"开机后这几百毫秒内的事件不投递" —— 与"未经同意就保活"相比，这个代价是对的。
 * 已同意过的用户行为不变：`consent == true` 一到，服务照常被拉起。
 *
 * 界面本身全部交给 [RootFlowApp]（`ui/` 包）。本类**不认识**主题、Tab 或任何布局，
 * 也不再直接 import 任何 Compose 组件（阶段 0–5 的 `Text("RootFlow")` 占位已移除）。
 *
 * ## ★ 阶段 6a 没有把权限申请搬进 Compose（**有意为之，勿"顺手"迁移**）
 * 一个自然的想法是把它挪进 `LaunchedEffect`，但那样会**丢掉它**：
 * - `registerForActivityResult` **必须**在 `STARTED` 之前调用，也就是 Activity 的字段初始化
 *   或 `onCreate` 里。放进 Composable 需要在 `rememberLauncherForActivityResult` 与
 *   "什么时候触发"之间再引入一份"是否已申请过"的状态，而那份状态一旦写错，
 *   失败表现是**静默的**：权限永远不弹、通知永远不显示，日志里什么都没有
 * - 权限申请是**系统交互**，不是界面状态。它没有"配置变更后要重建"的语义，
 *   而 Composable 会随重组反复执行——把一次性系统动作放进一个会重复执行的作用域，
 *   本身就是错的建模
 *
 * 阶段 6d 的正式形态是：设置页给出**逐项权限引导**（复用
 * `PermissionStatusProvider.settingsTargetFor`，决策 9 的描述符），
 * 而**首次申请仍留在本类**——两者不冲突：一个负责"第一次问"，一个负责"被拒之后怎么救"。
 *
 * ## ★ `POST_NOTIFICATIONS` 被拒**不会**让服务起不来（勿误判）
 * 拒绝只是"通知不显示在抽屉里"：`startForeground` 依然成功，服务依然是前台服务。
 * 因此本类**不**把申请结果与"要不要启动服务"绑定——两件事互不依赖
 * （详见 `data/service/ServiceNotificationChannels` 的类 KDoc）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * 通知权限申请器（API 33+）。
     *
     * 用 `RequestPermission` 契约而不是第三方库：它在 API 26–32 上会自动回落成
     * "检查即已授予"的行为，因此**不需要**在调用处再写版本分支。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "NOTIF_PERMISSION_RESULT granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ① 边缘到边缘（必须在任何内容之前；系统栏图标明暗由它自行跟随主题）
        enableEdgeToEdge()

        // ② 拉起前台服务与申请通知权限**都不再在这里**（阶段 12c）：
        //    它们挪到"读到用户同意之后"，见类 KDoc 的说明。
        //    `RootFlowApp` 只负责在正确的时机把信号发回来。
        setContent {
            RootFlowApp(
                onKeepAliveGranted = ::onKeepAliveConsentGranted,
                onKeepAliveDeclined = ::finish,
            )
        }
    }

    /**
     * 用户已同意保活（本次启动前同意过，或刚刚在声明页点了同意）。
     *
     * 两件事都是**系统交互**，因此留在 Activity 里（见类 KDoc 的两条纪律）：
     * ① 拉起前台服务 —— 此刻 Activity 处于前台，是系统无条件允许的启动时机
     * ② 申请通知权限 —— 通知是保活的一部分（声明页第 1、2 条），
     *    因此它也必须发生在用户看到并同意那份声明之后
     *
     * **幂等**：Activity 重建（旋转 / 进程内恢复）会让 `RootFlowApp` 的 `LaunchedEffect`
     * 再跑一次，而 `KeepAliveService.start` 与 `requestNotificationPermissionIfNeeded`
     * 各自都是幂等的（重复投递 `onStartCommand` 只是刷新通知；已授予的权限直接返回）。
     */
    private fun onKeepAliveConsentGranted() {
        KeepAliveService.start(context = this)
        requestNotificationPermissionIfNeeded()
    }

    /**
     * 只在"该权限存在且尚未授予"时申请一次。
     *
     * ## 为什么每次启动都检查而不是只申请一次
     * 用户可能随时在设置里撤销它。`checkSelfPermission` 便宜，而"忘了重新申请"的代价是
     * 用户永远看不到前台服务通知（且服务其实一直在跑，属静默状态）。
     *
     * ## 被永久拒绝后不再弹窗
     * 连续拒绝两次后系统会直接返回 `false` 而不显示弹窗，此时唯一的入口是设置页——
     * 那是阶段 6d 设置页的职责（`PermissionStatusProvider.settingsTargetFor` 已给出描述符）。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API 33 以下没有该运行时权限：服务通知无需授权即可显示
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "NOTIF_PERMISSION already granted")
            return
        }
        Log.i(TAG, "NOTIF_PERMISSION requesting")
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG: String = "RootFlow"
    }
}
