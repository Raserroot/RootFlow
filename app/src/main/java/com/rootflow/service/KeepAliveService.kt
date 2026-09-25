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
import com.rootflow.domain.service.KeepAliveWatchdog
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
     * 保活看门狗（阶段 12c）。
     *
     * 服务与它互相知道对方：服务 `register()` 时让看门狗**排下一次检查**，
     * 服务停止时让看门狗知道"本进程里已经没有活着的服务了"（[KeepAliveWatchdog.onServiceStopped]）。
     * 反向的那条边（看门狗把服务拉起来）不在这里 —— 它走
     * `AlarmFireReceiver` → `KeepAliveHolder` → 看门狗，因为那一刻**本服务并不存在**。
     */
    @Inject
    lateinit var keepAliveWatchdog: KeepAliveWatchdog

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
            // ★ 阶段 12c：用户**明确停止**服务 ⇒ 必须撤掉看门狗。
            //   少了这一行，15 分钟后心跳会把服务拉回来 —— 用户看到的是
            //   "我明明点了停止，它自己又起来了"，属本仓库最忌讳的形态。
            //   顺带清掉"本进程见过服务"这条事实，让下一次心跳的判定留在正确的状态上。
            runCatching { keepAliveWatchdog.cancel() }
                .onFailure { Log.w(TAG, "FGS_STOP_CANCEL_WATCHDOG_FAILED ${describe(it)}") }
            keepAliveWatchdog.onServiceStopped()
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
        // ★ 阶段 12c：让看门狗知道"本进程里已经没有活着的服务"。
        //   这是它能判出"该自愈了"的唯一来源（判定表见 `KeepAliveDecision`）——
        //   漏掉这一行，服务被停掉之后看门狗会以为它还活着，**永不尝试拉起**，
        //   而且日志里一行都不会有。
        keepAliveWatchdog.onServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 用户从最近任务划掉 App（阶段 12c 落地；此前是空实现）。
     *
     * ## 这里**不停服务** —— 这是有意的，也是清单写 `stopWithTask="false"` 的原因
     * "划掉任务"表达的是"我不想看到这个界面"，不是"我不想让它跑"：
     * 常驻脚本与事件监听的价值恰恰在于**没有界面时也在工作**。
     * 真正表达"停"的入口是常驻通知上的「停止服务」按钮（`ACTION_STOP`）。
     *
     * ## 那这里做什么（**只做两件"提醒与自愈"的事，不做任何黑产保活**）
     * 1. **确保看门狗在跑**：某些 ROM 在划掉任务时会连带取消该应用的全部闹钟，
     *    而那正是"服务被系统杀掉之后能回来"的唯一机制 ⇒ 重新排一次（幂等）
     * 2. **重投常驻通知**：同一场景下通知有时会被一并清掉，而它是用户唯一的状态指示
     *
     * 不做的事（与设计表一致）：不启第二个进程、不建 1 像素 Activity、不放无声音乐。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "FGS_TASK_REMOVED reason=task swiped away (service stays: stopWithTask=false)")

        runCatching { keepAliveWatchdog.ensureScheduled() }
            .onFailure { Log.w(TAG, "FGS_TASK_REMOVED ensure watchdog failed: ${describe(it)}") }

        controller.currentNotification()?.let { model -> promote(model) }

        super.onTaskRemoved(rootIntent)
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
         * 拉服务起来的便捷入口（`MainActivity`、通知按钮、保活看门狗共用一条路径）。
         *
         * 传 `action = null` 表示"只是确保它在跑"，不触发任何控制动作。
         *
         * ## 为什么返回 `Boolean`（阶段 12c 新增）
         * 看门狗需要知道**这次自愈请求有没有被系统接受**：Android 14+ 会拒绝
         * "由非精确闹钟在后台启动前台服务"（见 `AndroidKeepAliveWaker` 的 KDoc），
         * 而那条路径不能静默 —— 请求被拒时 waker 会降级成一条如实通知。
         *
         * **它的语义只是"请求已被系统接受"**，不是"服务已经在前台"：
         * 前台化仍可能失败（背景限制 / 类型权限），那由服务自己的
         * `FGS_START_FAILED` 记录。两件事不要混为一谈（`AGENT_PROTOCOL.md §8.2`）。
         *
         * @return `true` = 启动请求已受理；`false` = 被系统拒绝（原因已记入 `FGS_START_REQUEST_FAILED`）
         */
        fun start(
            context: Context,
            action: String? = null,
        ): Boolean {
            val intent = Intent(context, KeepAliveService::class.java)
            if (action != null) intent.action = action
            return runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "FGS_START_REQUEST_FAILED reason=${it::class.java.name}: ${it.message}") }
                .isSuccess
        }
    }

    /** 异常摘要（含类名：`ForegroundServiceStartNotAllowedException` 这类信息只在类名里有）。 */
    private fun describe(error: Throwable): String = error::class.java.name + ": " + (error.message ?: "<no message>")
}
