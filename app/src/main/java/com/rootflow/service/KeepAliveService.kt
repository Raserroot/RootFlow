package com.rootflow.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.rootflow.data.service.ForegroundServiceController
import com.rootflow.data.service.ServiceNotificationChannels
import com.rootflow.data.service.ServiceNotificationIds
import com.rootflow.domain.service.ForegroundState
import com.rootflow.domain.service.ServiceNotificationModel
import com.rootflow.domain.service.ServiceNotificationText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 常驻前台服务（阶段 5，需求 §7）。
 *
 * ## 本类刻意极薄
 * `AGENTS.md`：**`service/` 只做系统集成，不写业务逻辑**。
 * 因此这里只有：前台化、把生命周期转发给 [ForegroundServiceController]、
 * 处理通知的「停止」按钮。所有判定都在控制器里（纯 Kotlin ⇒ 可纯 JVM 单测）。
 *
 * ## ★ `START_STICKY` **不是**开机自启（最容易被误读的一点）
 * | 事实 | 说明 |
 * |---|---|
 * | `START_STICKY` 的语义 | **进程被系统杀掉**之后，系统重建服务（`onStartCommand` 以 `null` intent 重新投递） |
 * | 它**不能**做什么 | 它**救不了从未启动过的进程**——重启设备后若没人拉起本应用，服务不存在，也就没有任何东西能被"粘性重建" |
 *
 * ⇒ **前台服务无法替代开机自启**。本设备上 `BOOT_COMPLETED` 被 ColorOS 在**分发阶段**
 * 拦截（阶段 3b 实测：142 个 receiver 被投递、rootflow 不在其中），因此开机路径**只能**由
 * **D9「App 启动补 boot 语义」**承担（阶段 3d 已实现，见 `RootFlowApp.sendBootIfFirstStartSinceBoot`）。
 * 二者分工：D9 负责"开机后第一次有人打开 App 时补上 boot 语义"，
 * 本服务负责"从那一刻起常驻，并在被系统杀掉后尽量回来"。
 *
 * ## `foregroundServiceType="specialUse"`（Android 14+ 硬要求）
 * 自 Android 14（API 34）起，前台服务**必须**声明类型，否则 `startForeground` 抛
 * `MissingForegroundServiceTypeException`。类型声明在清单里，而前台化调用传
 * [ServiceNotificationIds.FOREGROUND_TYPE_FROM_MANIFEST]（= `FOREGROUND_SERVICE_TYPE_MANIFEST`）
 * ⇒ **清单是类型的唯一真相源**，代码里不出现第二处 `specialUse` 字面量。
 *
 * 选 `specialUse` 的理由：本应用的用途（Root 脚本宿主的事件监听与保活）不落入任何预定义类型。
 * 平台对该类型的原文定义即"无法归入其它前台服务类型、也不能用 `JobInfo` API 的场景"。
 *
 * ## `POST_NOTIFICATIONS` 被拒时**不会**抛异常（见 `ServiceNotificationChannels` 的 KDoc）
 * `startForeground` 照常成功，服务照常是前台服务，只是通知不显示在抽屉里。
 * 因此**不得**把"通知没出现"读成"服务没起来"（`AGENT_PROTOCOL.md §8.2`）。
 *
 * ## 为什么 `onCreate` 里就前台化，而不是等 `register()` 之后
 * 若由 `startForegroundService` 拉起，系统要求 **5 秒内**调用 `startForeground`，
 * 否则抛 `ForegroundServiceDidNotStartInTimeException` 并把服务干掉。
 * 建渠道 + 前台化都是毫秒级，不能排在"启动事件源 + 读 Room"这类可能耗时的动作后面。
 * 引导通知的文案由 `ServiceNotificationText` 给出（`ForegroundState.Idle` → "服务未运行"），
 * `register()` 完成后立即被真实状态刷新掉。
 */
@AndroidEntryPoint
class KeepAliveService : Service() {
    @Inject
    lateinit var controller: ForegroundServiceController

    @Inject
    lateinit var channels: ServiceNotificationChannels

    /**
     * 是否已经前台化过。
     *
     * sticky 重启后系统会再次投递 `onStartCommand`（同一实例），此时**必须重新前台化**：
     * 服务被系统回收过一次，前台状态已经丢了。`startForeground` 本身是幂等的
     * （同 id 重复调用只是刷新通知），但仍记一个标志，让日志能区分"首次"与"重启"。
     */
    private var promoted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // 每次 onCreate 都 +1：sticky 重启是**同一进程内 onCreate 再次调用**
        // （Application.onCreate 不会重跑），因此这个计数就是"系统把我拉回来了几次"的证据
        val generation = ++generationCounter
        Log.i(TAG, "FGS_CREATED generation=$generation")

        channels.ensureChannels()
        promote(
            text =
                ServiceNotificationText.serviceNotification(
                    state = ForegroundState.Idle,
                    reason = null,
                    notificationsGranted = channels.notificationsVisible(),
                ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // intent 为 null 是 START_STICKY 重启的正常形态，**不是**异常输入
        val action = intent?.action
        Log.i(TAG, "FGS_STARTED action=${action ?: "<sticky-null>"} startId=$startId")

        if (action == ACTION_STOP) {
            Log.i(TAG, "FGS_STOPPED reason=user")
            controller.unregister()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        controller.register()
        // 用控制器算好的模型重新前台化：同 id 覆盖 ⇒ 用户看到的仍是同一条通知
        controller.currentNotification()?.let { model -> promote(model) }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "FGS_DESTROYED generation=$generationCounter")
        controller.unregister()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 本服务没有绑定语义（不需要 `Binder`）。
     *
     * 返回 `null` 是合法的：只有用 `bindService` 的客户端才会拿到 `null` 并需要处理它，
     * 而本应用从不需要绑定（Activity 用 `startForegroundService`、通知用 `PendingIntent`）。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 前台化（幂等）。
     *
     * 失败时**只记日志**，不抛：前台化失败的原因（背景启动限制 `ForegroundServiceStartNotAllowedException`、
     * 类型权限缺失 `SecurityException`）都不是"服务自己能修复"的，
     * 而把它抛出去会让进程崩在系统手里（用户看到"App 已停止"），比"服务存在但非前台"更糟。
     * 真机判读靠 `FGS_START_FAILED`。
     */
    private fun promote(text: ServiceNotificationModel) {
        val first = !promoted
        runCatching {
            val notification = channels.buildForegroundNotification(model = text, ongoing = true)
            ServiceCompat.startForeground(
                this,
                ServiceNotificationIds.FOREGROUND,
                notification,
                ServiceNotificationIds.FOREGROUND_TYPE_FROM_MANIFEST,
            )
            promoted = true
            Log.i(
                TAG,
                "FGS_STARTED foregroundId=${ServiceNotificationIds.FOREGROUND} " +
                    "type=manifest first=$first",
            )
        }.onFailure {
            Log.w(TAG, "FGS_START_FAILED reason=${it::class.java.name}: ${it.message}")
        }
    }

    companion object {
        private const val TAG: String = "RootFlow"

        /**
         * 用户主动停止（通知按钮）。
         *
         * 用**显式 component** 的 `PendingIntent` 投递（见 `ServiceNotifierImpl` 之外的
         * 通知动作接线），因此不依赖任何隐式广播解析。
         */
        const val ACTION_STOP: String = "com.rootflow.action.STOP_SERVICE"

        /**
         * `onCreate` 被调用的累计次数（进程内）。
         *
         * 存在理由：真机上"服务被系统杀掉后有没有回来"只能靠**这个计数增加**来判定
         * （`Application.onCreate` 不会重跑，无法用它的日志判断）。它是
         * `STAGE5-DEVICE-VERIFICATION.md` 第 3 项的判据（`FGS_CREATED generation=2`）。
         */
        private var generationCounter: Int = 0

        /** 供测试/判读读取（不改动计数）。 */
        fun generation(): Int = generationCounter

        /**
         * 拉服务起来的便捷入口（`MainActivity` 与通知按钮共用一条路径）。
         *
         * 传 `action = null` 表示"只是确保它在跑"，不触发任何控制动作。
         */
        fun start(
            context: Context,
            action: String? = null,
        ) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (action != null) intent.action = action
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "FGS_START_REQUEST_FAILED reason=${it::class.java.name}: ${it.message}") }
        }
    }
}
