package com.rootflow.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rootflow.MainActivity
import com.rootflow.R
import com.rootflow.domain.service.ServiceChannels
import com.rootflow.domain.service.ServiceNotificationModel
import com.rootflow.service.KeepAliveService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知渠道的建立与通知实体的构造（阶段 5，需求 §7）。
 *
 * ## ★ `POST_NOTIFICATIONS` 被拒绝**不会**抛异常 —— 这是本阶段最容易被误判的一点
 * API 33+ 上 `POST_NOTIFICATIONS` 是运行时权限，但**拒绝它并不阻止前台服务运行**：
 * `startForeground(...)` 依然成功，服务依然是前台服务（`dumpsys activity services` 可见
 * `isForeground=true`），**只是通知不显示在抽屉里**。
 *
 * 由此产生三条必须遵守的推论：
 * 1. **不得**把"通知没出现"当成"服务没起来"——那是两个独立事实，见
 *    `AGENT_PROTOCOL.md §8.2`（同一现象可能有多层根因，必须逐层剥离）
 * 2. **真机验证必须先授权**（`adb shell pm grant … POST_NOTIFICATIONS`，见
 *    `STAGE5-DEVICE-VERIFICATION.md` 第 0 项），否则会得到
 *    "服务起来了但看不见通知"这一极易被误判为缺陷的现象
 * 3. 通知正文必须**如实说明**降级状态（见 `ServiceNotificationText`），
 *    不能因为通知看不见就假装"触发监听正常"
 *
 * ## 为什么 `ensureChannels` 与 `update` 分开
 * 渠道必须在**第一次 `startForeground` 之前**建好（否则 Android 8.0+ 会丢弃该通知并报错），
 * 而 `update` 会在服务存活期内被反复调用。把建立动作单独暴露，使"建渠道"与"刷内容"
 * 的时序在 `KeepAliveService.onCreate` 里显式可见，而不是藏在 Builder 的副作用里。
 *
 * ## 两个渠道的 id 与重要性
 * | 渠道 | id | 重要性 | 用途 |
 * |---|---|---|---|
 * | 常驻 | [ServiceChannels.FOREGROUND] | `IMPORTANCE_LOW` | 服务存活指标（无声、不可滑除） |
 * | 告警 | [ServiceChannels.SAFE_MODE] | `IMPORTANCE_HIGH` | 熔断告警（需求 §5.2 第 5 步） |
 *
 * 渠道重要性**创建后代码无法提高**（只有用户能改），因此绝不能把告警塞进 LOW 渠道。
 * 这也正是 `ServiceChannels` 要拆两个渠道的原因。
 *
 * ## 两条通知使用**不同 id** ⇒ 互不覆盖
 * [NOTIFICATION_ID_FOREGROUND] 与 [NOTIFICATION_ID_ALERT] 必须不同：
 * 同 id 会让后投递的那条替换前一条，于是熔断告警会把常驻通知"顶掉"，
 * 或恢复时常驻通知把告警顶掉——两种都会让用户看到与真实状态不符的通知。
 */
@Singleton
class ServiceNotificationChannels
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** 建立全部渠道（幂等）。 */
        fun ensureChannels() {
            val manager = notificationManager()
            if (manager == null) {
                Log.w(TAG, "NOTIF_CHANNEL_SKIPPED reason=no NotificationManager")
                return
            }
            ServiceChannels.ALL.forEach { id ->
                val channel =
                    NotificationChannel(
                        id,
                        channelName(id),
                        channelImportance(id),
                    ).apply { description = channelDescription(id) }
                manager.createNotificationChannel(channel)
                Log.i(TAG, "NOTIF_CHANNEL_CREATED id=$id importance=${channelImportance(id)}")
            }
        }

        /**
         * 系统通知服务；取不到时返回 `null`（极少数被裁剪的系统镜像）。
         *
         * **不缓存**：缓存 `null` 会让服务永久失去通知能力，而系统服务查找本身有缓存，
         * 每次取的开销可忽略。
         */
        fun notificationManager(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

        /**
         * 构造常驻通知的实体。
         *
         * 带一个「停止」动作（需求 §7 没有要求，但**常驻通知没有退出手段**是产品级缺陷：
         * 用户只能去系统设置里强制停止应用）。动作走显式 component 的
         * `startService` 而不是广播：清单里没有任何导出的接收器，也不必新增一个。
         *
         * @param model 内容描述（由 domain 的纯函数产出）
         * @param ongoing 是否不可滑除；服务前台化时必须为 `true`
         */
        fun buildForegroundNotification(
            model: ServiceNotificationModel,
            ongoing: Boolean,
        ): Notification =
            base(model)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .addAction(0, context.getString(R.string.notification_action_stop), stopServiceIntent())
                .build()

        /**
         * 构造安全模式告警通知的实体。
         *
         * `setAutoCancel(true)`：用户点开即视为已看到，不必再留一条重复的告警
         * （常驻通知仍会显示熔断态，信息不会丢）。
         */
        fun buildAlertNotification(model: ServiceNotificationModel): Notification =
            base(model)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()

        /** 两条通知共用的公共部分（小图标、点击进入 App、渠道）。 */
        private fun base(model: ServiceNotificationModel): NotificationCompat.Builder =
            NotificationCompat
                .Builder(context, model.channelId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(model.title)
                .setContentText(model.text)
                // 内容可能较长（含成因 detail），展开后完整可读
                .setStyle(NotificationCompat.BigTextStyle().bigText(model.text))
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(!model.alert)

        /**
         * 点击通知 → 打开唯一的 Activity。
         *
         * 用 `FLAG_IMMUTABLE`（Android 12+ 要求显式声明可变性；本 Intent 不需要被改写）
         * 与 `FLAG_UPDATE_CURRENT`（同一 PendingIntent 复用，避免每次刷新都新建）。
         */
        private fun openAppIntent(): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_APP,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * 「停止」动作 → [KeepAliveService.start] 带 [KeepAliveService.ACTION_STOP]。
         *
         * 用 `getService`（不是 `getBroadcast`）：服务已在清单里声明，无需新增导出接收器；
         * 且 `PendingIntent` 的显式 component 语义让投递与"谁发的"无关。
         */
        private fun stopServiceIntent(): PendingIntent {
            val intent =
                Intent(context, KeepAliveService::class.java)
                    .setAction(KeepAliveService.ACTION_STOP)
            return PendingIntent.getService(
                context,
                REQUEST_CODE_STOP_SERVICE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * 当前通知是否**真的能显示**（供通知正文如实降级用）。
         *
         * API 33 以下 `POST_NOTIFICATIONS` 不存在概念，恒为 `true`；
         * API 33+ 由 `NotificationManagerCompat.areNotificationsEnabled()` 回答，
         * 它同时覆盖"权限被拒"与"用户关闭了本应用全部通知"两种情况。
         *
         * **只用于文案**，不参与任何控制流：即便返回 `false`，服务照常前台化
         * （见类 KDoc 的三条推论）。
         */
        fun notificationsVisible(): Boolean =
            runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                .getOrDefault(true)

        private fun channelName(id: String): CharSequence =
            when (id) {
                ServiceChannels.SAFE_MODE -> context.getString(R.string.notification_channel_safe_mode)
                else -> context.getString(R.string.notification_channel_service)
            }

        private fun channelDescription(id: String): String =
            when (id) {
                ServiceChannels.SAFE_MODE -> context.getString(R.string.notification_channel_safe_mode_desc)
                else -> context.getString(R.string.notification_channel_service_desc)
            }

        private fun channelImportance(id: String): Int =
            if (id == ServiceChannels.SAFE_MODE) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_LOW
            }

        private companion object {
            const val TAG: String = "RootFlow"

            /** 点击"打开 App"的 PendingIntent 请求码（固定值即可，无冲突来源）。 */
            const val REQUEST_CODE_OPEN_APP: Int = 0

            /** 「停止服务」动作的 PendingIntent 请求码（与上面必须不同，否则会互相替换）。 */
            const val REQUEST_CODE_STOP_SERVICE: Int = 1
        }
    }

/**
 * 通知 id 的**唯一来源**（阶段 5）。
 *
 * 与 `ServiceChannels` 一样属于跨阶段契约（真机日志引用它们），因此单独钉成常量：
 * - 常驻与告警**必须不同 id**，否则互相覆盖（见 [ServiceNotificationChannels] 的类 KDoc）
 * - 前台化时用的是常驻 id：`ServiceCompat.startForeground(service, NOTIFICATION_ID_FOREGROUND, …)`
 *   与后续 `notify(NOTIFICATION_ID_FOREGROUND, …)` 刷新必须是**同一个 id**，
 *   否则刷新的那条只是普通通知，而前台服务仍挂着最初那条
 */
object ServiceNotificationIds {
    /** 常驻通知 id（服务存活指标）。 */
    const val FOREGROUND: Int = 0x5246

    /** 安全模式告警 id。 */
    const val ALERT: Int = 0x5247

    /**
     * 前台服务的类型：**交给框架按清单读**。
     *
     * ## 为什么不写 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
     * 那样代码里就出现了**第二处** `specialUse` 真相（清单一处、代码一处），
     * 两者漂移的后果是运行期 `SecurityException`，而且只有真机才会暴露。
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST] 的语义正是"以清单声明为准"，
     * 于是清单成为唯一真相源（`AndroidPermissionCatalogTest` 已断言清单里确有 `specialUse`）。
     */
    const val FOREGROUND_TYPE_FROM_MANIFEST: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
}
