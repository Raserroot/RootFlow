package com.rootflow.data.service

import android.content.Context
import android.util.Log
import com.rootflow.domain.service.KeepAliveWaker
import com.rootflow.domain.service.ServiceNotificationText
import com.rootflow.domain.service.ServiceNotifier
import com.rootflow.service.KeepAliveService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 看门狗自愈动作的 Android 实现（阶段 12c）：把前台服务拉起来。
 *
 * ## ★ 为什么必须有"降级为通知"这一半（**真机大概率会走到这条路上**）
 * Android 14（API 34）起，**从后台启动前台服务**被严格限制：豁免清单里
 * "由**精确**闹钟唤起"这一条**不覆盖**本项目 ——
 * D3 决定 v1 不申请 `SCHEDULE_EXACT_ALARM`，心跳用的是
 * `setAndAllowWhileIdle`（非精确，见 `AndroidAlarmHandle.setNext`）。
 *
 * 也就是说：**心跳广播能把进程叫起来，但 `startForegroundService` 很可能被系统拒绝**
 * （`ForegroundServiceStartNotAllowedException`）。若这里只写"尽力而为 + 一行日志"，
 * 那么"服务没恢复"这件事对用户是**完全不可见**的 ——
 * 而用户此刻以为 App 一直在监听（脚本不跑、事件不响应，却毫无提示）。
 * 这正是本仓库反复禁止的静默失败形态。
 *
 * ⇒ 降级路径 = **投一条如实的提醒通知**（`ServiceNotificationText.serviceDown`）：
 * 走常驻通知 id，因此**服务一旦恢复就被自动覆盖**（不需要任何撤销逻辑）；
 * 通知带 `openAppIntent()`（点击打开 App），而"用户打开 App"同时也是
 * **系统明确允许**的启动前台服务时机 ⇒ 恢复动作对用户是"点一下"。
 *
 * ## 按决策 13：本类不进单测
 * 它只做 Android API 直调（`startForegroundService` + 通知投递），纯 JVM 下必然
 * `Method ... not mocked`。**判定与自续期逻辑全在 `KeepAliveWatchdogImpl` 里**（有单测），
 * 本类只负责"把那个决定变成一次系统调用"。
 *
 * ## 真机判读
 * | 日志 | 含义 |
 * |---|---|
 * | `KEEPALIVE_WAKE_REQUESTED` | 服务启动请求已被系统接受 |
 * | `FGS_START_REQUEST_FAILED`（由 `KeepAliveService.start` 打） | 系统拒绝的具体原因 |
 * | `KEEPALIVE_WAKE_BLOCKED` | 本次自愈失败、已降级为通知（**不是**静默） |
 *
 * @param context 应用上下文（服务启动需要）
 * @param notifier 降级路径用的通知端口（**只在这一条路径上用**，见类 KDoc）
 */
@Singleton
class AndroidKeepAliveWaker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val notifier: ServiceNotifier,
    ) : KeepAliveWaker {
        override fun wake() {
            if (KeepAliveService.start(context)) {
                Log.i(TAG, "KEEPALIVE_WAKE_REQUESTED via=keep-alive-service")
                return
            }

            // 走到这里说明系统拒绝了这次后台启动（原因已由 KeepAliveService.start 打在上一条日志里）。
            Log.w(
                TAG,
                "KEEPALIVE_WAKE_BLOCKED by=system; falling back to a persistent notification " +
                    "(non-exact alarm is not exempt from the background FGS launch restriction)",
            )
            runCatching {
                notifier.update(
                    ServiceNotificationText.serviceDown(
                        notificationsGranted = notifier.notificationsVisible(),
                    ),
                )
            }.onFailure { Log.w(TAG, "KEEPALIVE_FALLBACK_NOTIFY_FAILED ${it::class.java.name}: ${it.message}") }
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
