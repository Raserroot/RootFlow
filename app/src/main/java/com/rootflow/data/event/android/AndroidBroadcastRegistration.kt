package com.rootflow.data.event.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import com.rootflow.domain.event.BroadcastRegistration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BroadcastRegistration] 的 `Context` 适配（阶段 3c.1）。
 *
 * ## 按决策 13：本类不进单测
 * 它只做 Android API 直调（`registerReceiver` / `unregisterReceiver`），纯 JVM 下必然
 * `Method ... not mocked`，而本项目不引入 Robolectric。**其正确性由真机覆盖**：
 * 真机观测点是 `ScreenEventSource` 的 `SCREEN_REGISTER actions=…` 与锁屏/解锁产生的
 * `SCREEN_RECEIVED` + `EVENT_BUS sent=…`。
 *
 * ## `RECEIVER_NOT_EXPORTED` 是安全要求，不是可选项
 * Android 14（API 34）起，动态注册接收器**必须**显式声明是否导出，否则抛
 * `SecurityException`。本源只关心系统广播（`ACTION_SCREEN_ON` 等是 protected broadcast），
 * 因此固定用 `RECEIVER_NOT_EXPORTED`——**不得**改成 `RECEIVER_EXPORTED`：
 * 那会让任意应用能伪造这些 action 来触发用户脚本。
 *
 * @param context 应用上下文（`Application` 级，注册随进程存活）
 */
@Singleton
class AndroidBroadcastRegistration
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BroadcastRegistration {
        private val lock = Any()

        /** 当前已注册的接收器；`null` = 未注册。 */
        private var receiver: BroadcastReceiver? = null

        override fun register(
            actions: Collection<String>,
            onAction: (String) -> Unit,
        ) {
            synchronized(lock) {
                if (receiver != null) return

                val created =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            // 只把 action 字符串交出去：判定逻辑在纯 JVM 可测的代码里（见 SourceActionSink）
                            onAction(intent.action ?: "")
                        }
                    }

                val filter = IntentFilter()
                actions.forEach { action -> filter.addAction(action) }

                try {
                    context.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
                    receiver = created
                } catch (error: Throwable) {
                    // 注册失败必须可见：否则事件源会静默"启动成功但收不到任何广播"。
                    // 不抛出去——由 ScreenEventSource.start() 的调用链（注册表的异常门）兜住，
                    // 这里只保证 receiver 字段保持 null，使 isRegistered 如实为 false。
                    Log.w(TAG, "BROADCAST_REGISTER_FAILED actions=$actions: ${error.message}")
                }
            }
        }

        override fun unregister() {
            synchronized(lock) {
                val current = receiver ?: return
                receiver = null
                try {
                    context.unregisterReceiver(current)
                } catch (error: Throwable) {
                    // 反注册失败只记日志：状态已经置空，重复 stop() 不会再尝试。
                    Log.w(TAG, "BROADCAST_UNREGISTER_FAILED: ${error.message}")
                }
            }
        }

        override val isRegistered: Boolean
            get() = synchronized(lock) { receiver != null }

        override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

        private companion object {
            const val TAG = "RootFlow"
        }
    }
