package com.rootflow.data.service

import android.util.Log
import com.rootflow.domain.service.ServiceNotificationModel
import com.rootflow.domain.service.ServiceNotificationText
import com.rootflow.domain.service.ServiceNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServiceNotifier] 的 Android 实现（阶段 5）。
 *
 * ## 幂等由"内容比对"实现，而不是靠调用方自觉
 * `ForegroundServiceController` 会在**每次**状态变化时调用 [update]（包括与上次等价的变化，
 * 例如事件源数量抖动后回到原值）。若这里无条件 `notify(...)`，通知栏会反复重投，
 * 在真机日志里表现为连续的 `NOTIF_POSTED`，让判读失去意义。
 * 因此本类记住**上一次投递的模型**，内容相同即跳过——判定逻辑（`equals`）来自
 * `ServiceNotificationModel` 是 data class 这一事实，无需额外代码。
 *
 * ## 为什么不缓存 NotificationManager
 * 它可能为 `null`（极少数被裁剪的系统镜像），每次取一次的开销可忽略（系统服务查找有缓存），
 * 而缓存 `null` 会让服务永久失去通知能力。
 *
 * ## 异常纪律（契约见 `ServiceNotifier`）
 * 每个方法整体 `runCatching`：通知失败**绝不能让服务起不来**。
 * 脚本宿主的第一职责是把服务跑起来；通知失败只是可见性降级，且必然经 `Log.w` 留痕。
 */
@Singleton
class ServiceNotifierImpl
    @Inject
    constructor(
        private val channels: ServiceNotificationChannels,
    ) : ServiceNotifier {
        /** 上一次已投递的**常驻通知**内容；`null` = 还没投过。 */
        private var lastForeground: ServiceNotificationModel? = null

        override fun ensureChannels() {
            runCatching { channels.ensureChannels() }
                .onFailure { Log.w(TAG, "NOTIF_CHANNEL_FAILED: ${it.message ?: it::class.java.name}") }
        }

        override fun update(model: ServiceNotificationModel) {
            if (model == lastForeground) {
                // 内容未变：不重复 post（否则通知栏与日志都会被噪声淹没）
                return
            }
            runCatching {
                val manager = channels.notificationManager()
                if (manager == null) {
                    Log.w(TAG, "NOTIF_POST_SKIPPED reason=no NotificationManager")
                    return
                }
                val notification = channels.buildForegroundNotification(model = model, ongoing = true)
                manager.notify(ServiceNotificationIds.FOREGROUND, notification)
                lastForeground = model
                Log.i(
                    TAG,
                    "NOTIF_POSTED id=${ServiceNotificationIds.FOREGROUND} " +
                        ServiceNotificationText.describe(model) + " visible=${channels.notificationsVisible()}",
                )
            }.onFailure { Log.w(TAG, "NOTIF_POST_FAILED: ${it.message ?: it::class.java.name}") }
        }

        override fun alert(model: ServiceNotificationModel) {
            runCatching {
                val manager = channels.notificationManager()
                if (manager == null) {
                    Log.w(TAG, "NOTIF_ALERT_SKIPPED reason=no NotificationManager")
                    return
                }
                val notification = channels.buildAlertNotification(model)
                manager.notify(ServiceNotificationIds.ALERT, notification)
                Log.w(
                    TAG,
                    "NOTIF_POSTED id=${ServiceNotificationIds.ALERT} " +
                        ServiceNotificationText.describe(model) + " visible=${channels.notificationsVisible()}",
                )
            }.onFailure { Log.w(TAG, "NOTIF_ALERT_FAILED: ${it.message ?: it::class.java.name}") }
        }

        override fun cancelAlert() {
            runCatching {
                channels.notificationManager()?.cancel(ServiceNotificationIds.ALERT)
                Log.i(TAG, "NOTIF_ALERT_CANCELLED id=${ServiceNotificationIds.ALERT}")
            }.onFailure { Log.w(TAG, "NOTIF_ALERT_CANCEL_FAILED: ${it.message ?: it::class.java.name}") }
        }

        /** 见 `ServiceNotifier.notificationsVisible` 的 KDoc（只影响文案，不参与控制流）。 */
        override fun notificationsVisible(): Boolean =
            runCatching { channels.notificationsVisible() }.getOrDefault(true)

        /** 上一次已投递的常驻通知内容（真机判读与单测断言用）。 */
        internal fun lastForegroundNotification(): ServiceNotificationModel? = lastForeground

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
