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
 * ## ★ 阶段 6a 之后它只剩三件事（顺序是硬约束）
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
 * 界面本身全部交给 [RootFlowApp]（`ui/` 包）。本类**不认识**主题、Tab 或任何布局，
 * 也不再直接 import 任何 Compose 组件（阶段 0–5 的 `Text("RootFlow")` 占位已移除）。
 *
 * ## ★ 阶段 6a 没有把第 3 件事搬进 Compose（**有意为之，勿"顺手"迁移**）
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

        // ② 拉起前台服务（必须在主线程；服务内部自己完成建渠道 + 前台化）
        KeepAliveService.start(context = this)

        // ③ 申请通知权限（API 33+；未授予过才弹，避免每次启动都打扰用户）
        requestNotificationPermissionIfNeeded()

        setContent {
            RootFlowApp()
        }
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
