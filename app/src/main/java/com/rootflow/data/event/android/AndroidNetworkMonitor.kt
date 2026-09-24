package com.rootflow.data.event.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.rootflow.data.event.WifiNetworkMonitor
import com.rootflow.data.event.WifiNetworkSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WifiNetworkMonitor] 的 `ConnectivityManager` 适配（阶段 3c.1）。
 *
 * ## 按决策 13：本类不进单测
 * `NetworkCapabilities` 在纯 JVM 下不可构造，故"能力 → 语义"的翻译落在这里（真机覆盖），
 * 而**判定 `connected` 变化去重的逻辑在 `WifiEventSource`**（纯 JVM，10 条用例覆盖）。
 *
 * ## 为什么用 `registerDefaultNetworkCallback`
 * 需求 §2.1 指定 `ConnectivityManager.NetworkCallback`。用"默认网络"回调（而非
 * `registerNetworkCallback` + 全量网络）是因为 `wifi_changed` 的语义关心的是**当前在用**的
 * 网络是不是 Wi-Fi，而不是"设备上是否存在某个 Wi-Fi 网络"。
 *
 * ## `NET_CAPABILITY_INTERNET` 的含义（重要）
 * `TRANSPORT_WIFI` 只说明"链路是 Wi-Fi"（连上了热点但可能没有外网）；
 * `NET_CAPABILITY_INTERNET` 说明"该网络被系统认为能上网"。
 * 本源上报的 `connected` 取两者**同时成立**——对用户脚本而言，
 * "连着热点但没有外网"与"没连"在行为上等价，且避免脚本在断网的 Wi-Fi 上做联网动作。
 *
 * ## 线程
 * 回调在 `ConnectivityManager` 的**系统线程**上执行；本类不持有可变状态，
 * 状态与线程安全全部由 `WifiEventSource` 负责（见其类 KDoc）。
 */
@Singleton
class AndroidNetworkMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WifiNetworkMonitor {
        private val lock = Any()

        /** 当前注册的回调；`null` = 未注册。 */
        private var callback: ConnectivityManager.NetworkCallback? = null

        override fun probe(): WifiNetworkSnapshot? {
            val manager = connectivityManager() ?: return null
            val active = manager.activeNetwork ?: return null
            val capabilities = manager.getNetworkCapabilities(active) ?: return null
            return capabilities.toSnapshot()
        }

        override fun start(onChanged: (WifiNetworkSnapshot?) -> Unit) {
            synchronized(lock) {
                if (callback != null) return
                val manager = connectivityManager() ?: return

                val created =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            // onAvailable 只说明"网络可用"，能力要另取；取不到就按无变化处理
                            val caps = manager.getNetworkCapabilities(network) ?: return
                            onChanged(caps.toSnapshot())
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            onChanged(networkCapabilities.toSnapshot())
                        }

                        override fun onLost(network: Network) {
                            // 默认网络丢失：必须上报，否则"Wi-Fi 断开"这条边会永远丢失（决策 12）
                            onChanged(null)
                        }
                    }

                try {
                    manager.registerDefaultNetworkCallback(created)
                    callback = created
                } catch (error: Throwable) {
                    Log.w(TAG, "NETWORK_CALLBACK_REGISTER_FAILED: ${error.message}")
                }
            }
        }

        override fun stop() {
            synchronized(lock) {
                val current = callback ?: return
                callback = null
                try {
                    connectivityManager()?.unregisterNetworkCallback(current)
                } catch (error: Throwable) {
                    Log.w(TAG, "NETWORK_CALLBACK_UNREGISTER_FAILED: ${error.message}")
                }
            }
        }

        private fun connectivityManager(): ConnectivityManager? =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        /**
         * `NetworkCapabilities` → [WifiNetworkSnapshot]。
         *
         * **语义（与 `WifiEventSource` 的 KDoc 对齐）**：返回 `null` 表示"当前默认网络不是 Wi-Fi"。
         * 这一步的过滤是决策 4 的落点——不过滤就会把"切到移动数据"误报为 Wi-Fi 变化；
         * 而**断开边不会因此丢失**：`onLost` 与"默认网络换成蜂窝"都归到"非 Wi-Fi"，
         * 由 `WifiEventSource` 的 `connected` 变化去重统一上报（决策 12）。
         *
         * **只用 `hasTransport` / `hasCapability`**：本机 `android-35` 桩 jar 里没有
         * `getTransportTypes()`（见 `WifiNetworkSnapshot` 的 KDoc）。
         */
        private fun NetworkCapabilities.toSnapshot(): WifiNetworkSnapshot? {
            if (!hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            return WifiNetworkSnapshot(
                connected =
                    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
