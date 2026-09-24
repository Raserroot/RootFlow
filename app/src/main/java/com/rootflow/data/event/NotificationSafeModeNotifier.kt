package com.rootflow.data.event

import com.rootflow.domain.event.SafeModeNotifier
import com.rootflow.domain.event.TripReason
import com.rootflow.domain.service.SafeModeAlertSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SafeModeNotifier] 的**真实实现**（阶段 5，需求 §5.2 第 4/5 步）。
 *
 * ## 它替换掉了什么
 * 阶段 4 的绑定是 [NoopSafeModeNotifier]（只记一行 `SAFEMODE_NOTIFY_TRIP` 日志）。
 * 那是有意的过渡形态——阶段 4 交付边界写明"第 4/5 步属阶段 5：服务订阅
 * `CircuitBreaker.safeMode` 切换前台通知、建 channel + `IMPORTANCE_HIGH` 通知"。
 * 本类即该边界的落点。
 *
 * ## ★ 为什么经 [SafeModeAlertSink] 而不是直接注入控制器（**防环**）
 * `CircuitBreakerImpl` 构造注入 `SafeModeNotifier`，而 `ForegroundServiceController`
 * 又订阅了 `CircuitBreaker.safeMode`。若本类直接依赖控制器，Hilt 图会出现
 * ```
 * CircuitBreakerImpl → SafeModeNotifier → ForegroundServiceController → CircuitBreaker
 * ```
 * 这个**环**，`hiltJavaCompileDebug` 会以 `Found a dependency cycle` 直接失败
 * （阶段 4 的 `BootloopGuard` 已经踩过同一坑型，当时的解法是 `onTrip = null` 交给
 * `RootFlowApp` 驱动）。把"发布告警"抽成 [SafeModeAlertSink] 端口后方向反转：
 * **控制器实现端口、熔断器只依赖端口**，依赖单向、图无环。
 * [SafeModeAlertSink] 的 KDoc 有完整的三方依赖图。
 *
 * ## 为什么本类几乎只有转发
 * 真正需要判定的东西有两处，都不在这里：
 * - "熔断该做什么" → `CircuitBreakerImpl` 的六步动作
 * - "通知该长什么样" → `ServiceNotificationText`（纯函数）+ 控制器的状态投影
 *
 * 本类**刻意不订阅** `safeMode`：若通知侧也订阅一次，就会出现两个真相源
 * （控制器按 `safeMode` 刷常驻通知、本类按同一个流发告警），恢复时两者的时序无法保证，
 * 会出现"告警已撤销但常驻通知还写着熔断"或反之的中间态。
 *
 * ## 异常纪律
 * 告警发不出去是**严重降级**，但绝不能让熔断的其余五步不执行——
 * `CircuitBreakerImpl` 每步各自 `runCatching`，本类也不得向外抛。
 */
@Singleton
class NotificationSafeModeNotifier
    @Inject
    constructor(
        private val sink: SafeModeAlertSink,
    ) : SafeModeNotifier {
        override fun onTrip(reason: TripReason) {
            runCatching { sink.onSafeModeAlert(reason) }
        }

        override fun onRestore() {
            runCatching { sink.onSafeModeRestore() }
        }
    }
