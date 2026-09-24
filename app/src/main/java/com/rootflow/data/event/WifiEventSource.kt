package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.AndroidPermission
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventSource
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.EventSourceStatus
import com.rootflow.domain.model.SystemEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络快照（阶段 3c.1）。
 *
 * ## 为什么是值对象而不是直接用 `NetworkCapabilities`
 * `NetworkCapabilities` 在纯 JVM 单测下不可构造（`Method <init> ... not mocked`），
 * 若把判定写在接收 Android 类型的代码里，[WifiStateSource] 的语义**完全不可测**。
 * 翻译（`NetworkCapabilities` → 本对象）收敛到 `AndroidNetworkMonitor`（决策 13：真机覆盖）。
 *
 * ## 为什么只有一个布尔（设计修正，勿回退）
 * 初版同时带 `wifiTransport` 与 `wifiConnected` 两个字段，结果二者**语义重叠且互相冲突**：
 * "非 Wi-Fi 传输"的快照在构造上根本无法同时表达"未连接"，命名歧义直接导致单测写错预期。
 * 现在的语义是单一的：
 * - **`null` 快照** = 当前默认网络**不是 Wi-Fi**（或默认网络已丢失）
 * - **[WifiNetworkSnapshot]** = 当前默认网络是 Wi-Fi，[connected] 表示它是否可达（已被系统判定可上网）
 *
 * 适配器负责这层判断（`AndroidNetworkMonitor` 用 `hasTransport(TRANSPORT_WIFI)` 决定要不要给快照），
 * 本源只消费"Wi-Fi 是否可用"这一个布尔。
 *
 * ## 为什么不用 `getTransportTypes()`
 * 本机 `platforms\android-35\android.jar` 是裁剪过的桩 jar：`NetworkCapabilities` 里
 * **没有** `getTransportTypes()` / `transportTypes`（已用 `javap` 核实）。而本源也只需要
 * "是不是 Wi-Fi"这一个语义位，故只用 `hasTransport` / `hasCapability`。
 *
 * @property connected Wi-Fi 是否可达（`hasTransport(TRANSPORT_WIFI)` 且已被系统判定可上网）
 */
data class WifiNetworkSnapshot(
    val connected: Boolean,
)

/**
 * 网络能力 → `wifi_changed` 语义（阶段 3c.1）。
 *
 * 生产实现见 `AndroidNetworkMonitor`；单测用假件覆盖全部用例。
 */
fun interface WifiStateSource {
    /**
     * 派生"Wi-Fi 是否已连接"。
     *
     * @param snapshot 原始快照；`null` 表示**当前默认网络不是 Wi-Fi**（默认网络已丢失，
     *   或已切换到移动数据），按"未连接"处理
     */
    fun isWifiConnected(snapshot: WifiNetworkSnapshot?): Boolean
}

/**
 * `wifi_changed` 事件源（阶段 3c.1，动态注册路线 + **决策 4 / 决策 12**）。
 *
 * ## 决策 12：按 `connected` 变化去重，**不按 transport 过滤**
 *
 * 决策 4 指出：`ConnectivityManager.NetworkCallback` 的语义是"**默认网络**变化"，
 * 不区分传输类型，因此不过滤会把"切到移动数据"误报为 Wi-Fi 变化。
 *
 * 但**纯按 `TRANSPORT_WIFI` 过滤会丢掉真实的 Wi-Fi 断开**：
 * `registerDefaultNetworkCallback` 的回调（尤其 `onLost`）触发时默认网络**已经不是 Wi-Fi**，
 * 于是 `hasTransport(TRANSPORT_WIFI) == false` → `connected = false` 被静默丢弃
 * → `wifi_changed` 只剩"连上"、没有"断开"，**又是一个单向事件**
 * （与决策 3 要修的 `battery_low` 缺恢复边同类缺陷）。
 *
 * 因此本源上报的判定条件是**派生 `connected` 与上次上报值是否不同**，而不是 transport：
 *
 * | 场景 | 派生 `connected` | 是否上报 |
 * |---|---|---|
 * | 切到移动数据、Wi-Fi 仍连着 | `true`（未变） | **不上报**（决策 4 要防的误报，仍被挡住） |
 * | Wi-Fi 断开、默认网络变蜂窝 | `true → false` | **上报**（断开边不丢） |
 * | Wi-Fi 重新连上 | `false → true` | 上报 |
 * | 同一状态重复回调 | 未变 | 不上报（去重） |
 *
 * ## 线程安全
 * `NetworkCallback` 在 `ConnectivityManager` 的**系统线程**回调，而 [start] / [stop] 由
 * 注册表在另一线程调用。因此 [lastReported] / [started] 一律由 [lock] 保护——
 * **不能用 `@Volatile`**：这里是 check-then-act（"读过再写"），`@Volatile` 只保证可见性、
 * 不保证复合操作的原子性，两个线程可能各自看到旧值而重复上报或漏报。
 *
 * ## 启动基线
 * [start] 先用 [WifiNetworkMonitor.probe] 探一次当前状态，并**无条件写入基线**
 * （`lastReported = 当前值`，含 `false`），再开始接收回调：
 * - 已连 Wi-Fi → 额外发一条 `connected = true`，让脚本知道"启动那一刻 Wi-Fi 是通的"
 * - 未连 Wi-Fi → **不发事件**（启动即产生噪声没有价值），但基线已是 `false`，
 *   因此紧随其后的 `onLost` 不会被误判成"状态变化"而重复上报
 *
 * 这条细节曾是一个真实缺陷：基线只在"已连"时写入（`lastReported` 留 `null`）会让
 * 未连状态下的首次 `onLost` 判成 `false != null` 而多发一条事件。
 * `WifiEventSourceTest` 的 "onLost right after an offline baseline is not reported as a change"
 * 即为此设的护栏。
 *
 * @param monitor 网络回调抽象
 * @param bus 事件总线
 * @param stateSource 能力 → 语义的派生（纯函数）
 */
@Singleton
class WifiEventSource
    @Inject
    constructor(
        private val monitor: WifiNetworkMonitor,
        private val bus: EventBus,
    ) : EventSource {
        /**
         * 派生函数的**测试缝**（`internal`，仅单测用）。
         *
         * 生产构造固定用默认派生 [DEFAULT_WIFI_STATE_SOURCE]。
         * **不把带默认值的参数放进 `@Inject` 构造**：Kotlin 的默认参数对 Dagger 不可见，
         * 它会尝试为 `WifiStateSource` 找一个 `@Provides` 而报 `MissingBinding`（已实测）。
         */
        internal constructor(
            monitor: WifiNetworkMonitor,
            bus: EventBus,
            stateSource: WifiStateSource,
        ) : this(monitor = monitor, bus = bus) {
            this.stateSource = stateSource
        }

        /** 网络能力 → Wi-Fi 连接语义。生产用默认派生；单测可覆盖（见上面的测试缝）。 */
        private var stateSource: WifiStateSource = DEFAULT_WIFI_STATE_SOURCE
        override val sourceId: String = SOURCE_ID

        /** Wi-Fi 连接变化由本源的网络回调产出。 */
        override val providesEvents: Set<String> = setOf(SystemEvent.WIFI_CHANGED)

        /** 需求 §2.1：`wifi_changed` 需要 `ACCESS_NETWORK_STATE`。 */
        override val requiredPermissions: Set<AndroidPermission> =
            setOf(AndroidPermission.ACCESS_NETWORK_STATE)

        /** 保护 [started] 与 [lastReported]（理由见类 KDoc「线程安全」）。 */
        private val lock = Any()

        private var started: Boolean = false

        /**
         * 上次上报的 `connected` 值；`null` = 尚未建立基线（仅存在于 `start()` 之前的窗口）。
         *
         * 存**派生布尔**而非原始快照是有意为之：若存快照，一次"Wi-Fi 已断开但默认网络仍是
         * Wi-Fi 传输"的回调会把状态重新派生为 `true`，凭空造出一个假的"重新连上"事件。
         *
         * [start] 会**无条件**写入基线（含 `false`），因此 `null` 不会与"未连接"混淆——
         * 这是"启动后第一次 `onLost` 不得重复上报"的前提。
         */
        private var lastReported: Boolean? = null

        override fun start() {
            val baseline =
                synchronized(lock) {
                    if (started) return
                    started = true
                    val current = stateSource.isWifiConnected(monitor.probe())
                    // 基线**无论是否已连接都要写入**：它代表"启动那一刻的真实状态"。
                    // 若只在"已连"时写基线（`lastReported` 留 null），则未连 Wi-Fi 时
                    // 紧接着的第一次 `onLost` 会被判成"状态变化"（false != null）而多发一条
                    // `wifi_changed(false)` —— 这正是"重复上报"缺陷，由单测钉死。
                    lastReported = current
                    current
                }

            monitor.start(::onCapabilitiesChanged)
            if (baseline) {
                // 只在"已连 Wi-Fi"时给出基线事件：让脚本知道启动时 Wi-Fi 是通的。
                // 未连时不发事件（启动即产生噪声没有价值，且会与后续真实变化混淆）。
                sendEvent(connected = true, phase = "baseline")
            }
        }

        override fun stop() {
            val wasStarted =
                synchronized(lock) {
                    val previous = started
                    started = false
                    lastReported = null
                    previous
                }
            if (!wasStarted) return
            monitor.stop()
        }

        override fun status(): EventSourceStatus =
            EventSourceStatus(
                sourceId = sourceId,
                state =
                    if (synchronized(lock) { started }) {
                        EventSourceState.Running
                    } else {
                        EventSourceState.NotStarted
                    },
            )

        /** 收到一次能力变化（[WifiNetworkMonitor] 回调，见类 KDoc「线程安全」）。 */
        internal fun onCapabilitiesChanged(snapshot: WifiNetworkSnapshot?) {
            val next = stateSource.isWifiConnected(snapshot)
            val shouldSend =
                synchronized(lock) {
                    if (!started) return
                    if (next == lastReported) return
                    lastReported = next
                    true
                }
            if (shouldSend) {
                sendEvent(connected = next, phase = "change")
            }
        }

        private fun sendEvent(
            connected: Boolean,
            phase: String,
        ) {
            val event = SystemEvent.WifiChanged(connected)
            bus.send(event)
            Log.i(TAG, "EVENT_BUS sent=${event.eventId} connected=$connected phase=$phase")
        }

        internal companion object {
            /** 稳定源标识。 */
            const val SOURCE_ID: String = "wifi"

            const val TAG: String = "RootFlow"

            /**
             * 默认派生：`null`（默认网络不是 Wi-Fi）或快照自身携带的可达位。
             *
             * `connected` 由适配器按 `hasTransport(TRANSPORT_WIFI)` +
             * `hasCapability(NET_CAPABILITY_INTERNET)` 计算（见 `AndroidNetworkMonitor`）
             * ——那些常量在纯 JVM 下不可用，故判定留在适配器，本源只消费布尔语义位。
             */
            val DEFAULT_WIFI_STATE_SOURCE: WifiStateSource =
                WifiStateSource { snapshot -> snapshot?.connected == true }
        }
    }

/**
 * 默认网络回调抽象（阶段 3c.1）。
 *
 * 唯一实现 `data/event/android/AndroidNetworkMonitor` 委托
 * `ConnectivityManager.registerDefaultNetworkCallback`（**按决策 13 不进单测**）。
 */
interface WifiNetworkMonitor {
    /** 探测当前状态；返回 `null` 表示当前无默认网络。 */
    fun probe(): WifiNetworkSnapshot?

    /**
     * 开始接收默认网络变化。
     *
     * @param onChanged 变化回调；参数为 `null` 表示默认网络丢失（`onLost`）
     */
    fun start(onChanged: (WifiNetworkSnapshot?) -> Unit)

    /** 停止接收。**未启动时调用必须安全。** */
    fun stop()
}
