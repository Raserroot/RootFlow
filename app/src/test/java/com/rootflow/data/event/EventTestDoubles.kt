package com.rootflow.data.event

import com.rootflow.domain.event.BroadcastRegistration
import com.rootflow.domain.event.PermissionPrimitives

// 3c.1 的测试替身集合（纯 JVM，无 Android 依赖）。
// 放在一个文件里是有意的：它们是**共用脚手架**，读一次就能掌握全部测试的注入面。

/**
 * 记录型权限原语假件。
 *
 * ## 为什么记录"调用次数"而不只记录返回值
 * 决策 10 的一条硬约束是：**`USAGE_STATS` 在 API 28 及以下不得调用 AppOps 通道**
 * （`PermissionPrimitives.isUsageStatsAllowed` 的调用契约）。只验证结果无法证明
 * "没被调用"，因此本假件记录次数，供 `PermissionDecisionsTest` 断言为 `0`。
 *
 * @param runtimeGranted 运行时权限的授予集合（normal 权限在生产恒为已授予）
 * @param usageStatsAllowed UsageStats（AppOps 通道）是否已授予
 * @param exactAlarmAllowed 精确闹钟是否可用
 * @param ignoringBatteryOptimizations 是否已被排除在电池优化之外（阶段 5：判的是**白名单状态**）
 */
internal class FakePermissionPrimitives(
    private val runtimeGranted: Set<String> = emptySet(),
    private val usageStatsAllowed: Boolean = false,
    private val exactAlarmAllowed: Boolean = false,
    private val ignoringBatteryOptimizations: Boolean = false,
) : PermissionPrimitives {
    /** `isUsageStatsAllowed` 被调用的次数。 */
    var usageStatsCalls: Int = 0
        private set

    /** `canScheduleExactAlarms` 被调用的次数。 */
    var exactAlarmCalls: Int = 0
        private set

    /** `isIgnoringBatteryOptimizations` 被调用的次数（阶段 5）。 */
    var batteryOptimizationCalls: Int = 0
        private set

    /** `isRuntimePermissionGranted` 收到的全部权限字符串（按调用顺序）。 */
    val runtimeQueries: MutableList<String> = mutableListOf()

    override fun isRuntimePermissionGranted(permission: String): Boolean {
        runtimeQueries += permission
        return permission in runtimeGranted
    }

    override fun isUsageStatsAllowed(): Boolean {
        usageStatsCalls++
        return usageStatsAllowed
    }

    override fun canScheduleExactAlarms(): Boolean {
        exactAlarmCalls++
        return exactAlarmAllowed
    }

    override fun isIgnoringBatteryOptimizations(): Boolean {
        batteryOptimizationCalls++
        return ignoringBatteryOptimizations
    }
}

/**
 * 记录型动态注册假件。
 *
 * 记录 `register` / `unregister` 的**调用次数**——`ScreenEventSourceTest` 的"幂等"用例
 * 正是靠这个断言（重复 `start()` 不得重复注册）。
 *
 * [now] 可注入，用于 `ScreenEventReceiver` 的"一次性告警窗口"用例（虚拟时间，不 sleep）。
 */
internal class FakeBroadcastRegistration(
    var now: Long = 1_000L,
) : BroadcastRegistration {
    var registerCalls: Int = 0
        private set

    var unregisterCalls: Int = 0
        private set

    /** 最近一次注册的 action 列表。 */
    var lastActions: List<String> = emptyList()
        private set

    /** 注册时交给接收器的回调；`stop()` 后必须不再转发（由被测代码自行判断）。 */
    var sink: ((String) -> Unit)? = null
        private set

    override fun register(
        actions: Collection<String>,
        onAction: (String) -> Unit,
    ) {
        registerCalls++
        lastActions = actions.toList()
        sink = onAction
        isRegistered = true
    }

    override fun unregister() {
        unregisterCalls++
        isRegistered = false
    }

    override var isRegistered: Boolean = false
        private set

    override fun elapsedRealtimeMillis(): Long = now
}

/**
 * 记录型网络监控假件。
 *
 * [probeResult] 是 `probe()` 的返回值；[emit] 用于把一次"回调"推进被测对象
 * （`null` 表示默认网络丢失，即 `onLost`）。
 */
internal class FakeNetworkMonitor(
    var probeResult: WifiNetworkSnapshot? = null,
) : WifiNetworkMonitor {
    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    private var callback: ((WifiNetworkSnapshot?) -> Unit)? = null

    override fun probe(): WifiNetworkSnapshot? = probeResult

    override fun start(onChanged: (WifiNetworkSnapshot?) -> Unit) {
        startCalls++
        callback = onChanged
    }

    override fun stop() {
        stopCalls++
        callback = null
    }

    /** 模拟一次系统回调。 */
    fun emit(snapshot: WifiNetworkSnapshot?) {
        callback?.invoke(snapshot)
    }

    /** 回调是否仍被持有（`stop()` 后应为 `false`）。 */
    fun hasCallback(): Boolean = callback != null
}
