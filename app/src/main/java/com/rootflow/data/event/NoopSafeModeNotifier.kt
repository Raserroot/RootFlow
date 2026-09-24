package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.SafeModeNotifier
import com.rootflow.domain.event.TripReason
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SafeModeNotifier] 的**阶段 4 空实现**。
 *
 * ## ★ 阶段 5 起它**不再被 DI 绑定**（保留的理由见下）
 * `CircuitBreakerModule.bindSafeModeNotifier` 已改为
 * [NotificationSafeModeNotifier]（真实通知，经 `SafeModeAlertSink` 端口下发，
 * 防环论证见该端口的 KDoc）。因此**生产路径上本类已不会被实例化**。
 *
 * 它仍留在 `main/` 而**没有**搬进 `test/`，理由是 `EndToEndWiringTest` 用它当
 * `SafeModeNotifier` 的测试替身（"只记日志"正是端到端用例需要的形态：
 * 真实通知会碰 `NotificationManager`，而该用例是纯 JVM）。
 * 搬进测试源集会让那次改动波及一个与本阶段无关的用例。
 *
 * **勿删、勿改绑**：删掉会让 `EndToEndWiringTest` 编译失败；改绑会让生产路径退化为"只记日志"，
 * 那正是"熔断了但用户毫无感知"（见下）。
 *
 * ## 为什么当初是空实现而不是"可选依赖"
 * 若把它做成可空参数，调用点会散落 `?.` 判断，将来漏接一处就变成
 * "**熔断了但用户毫无感知**"——正是熔断机制最不该有的失败形态。
 * 固定注入一个只记日志的实现，让"通知这步被调用过"在真机日志里可查。
 *
 * ## 阶段 5 需要做什么（交付边界）
 * | 需求步骤 | 阶段 4 | 阶段 5 |
 * |---|---|---|
 * | §5.2 第 4 步 前台服务进入安全模式 | 本类 `onTrip` 记一行日志；服务不存在 | 服务订阅 `CircuitBreaker.safeMode` 切换前台通知 |
 * | §5.2 第 5 步 高优先级通知 | 不做（`POST_NOTIFICATIONS` 真机仍为 `DENIED`） | 建 channel + `IMPORTANCE_HIGH` 通知 + 恢复 Action |
 */
@Singleton
class NoopSafeModeNotifier
    @Inject
    constructor() : SafeModeNotifier {
        override fun onTrip(reason: TripReason) {
            Log.w(
                TAG,
                "SAFEMODE_NOTIFY_TRIP reason=${reason.reasonKey} detail=${reason.detail} " +
                    "(notification arrives in stage 5)",
            )
        }

        override fun onRestore() {
            Log.i(TAG, "SAFEMODE_NOTIFY_RESTORE (notification arrives in stage 5)")
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
