package com.rootflow.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.rootflow.data.event.BootMarkerHolder
import com.rootflow.data.event.EventBusHolder
import com.rootflow.domain.model.SystemEvent

/**
 * 开机完成广播接收器（需求 §2.1 的 `boot` 事件源）。
 *
 * ## 只做一件事：把事件丢进总线
 * `onReceive` 有严格的时间上限（超时会 ANR）。因此这里**不查数据库、不读文件、不启脚本**，
 * 只做 `EventBusHolder.get()?.send(Boot)`。真正的匹配与投递由
 * `TriggerDispatcherImpl` 在自己的协程里做。
 *
 * ## 为什么必须打日志
 * 真机验证时如果只看到"没有触发"，无法区分是**广播没到**还是**总线/调度器没工作**。
 * `BOOT_RECEIVED` 这行是这条链路的第一个可观测点。
 *
 * ## 不监听 `LOCKED_BOOT_COMPLETED`（3a 已定）
 * 需求 §2.1 只要求 `ACTION_BOOT_COMPLETED`。直接启动（Direct Boot）会让 Room 在
 * credential-encrypted 存储上不可用，收益不抵复杂度。因此**不声明** `android:directBootAware`。
 *
 * ## 可测性设计：逻辑放在 [handleAction]
 * 本项目单测是**纯 JVM（无 Robolectric）**，而 `android.content.Intent.getAction()` 在
 * 未 mock 的 JVM 下会抛 `RuntimeException: Method getAction ... not mocked`。
 * 因此 `onReceive` 只负责**取出 action 字符串**，全部判定与发送都在 [handleAction] 里，
 * 单测直接调它即可，无需触碰任何 Android 框架方法。
 *
 * ## 总线未安装时容错
 * [EventBusHolder] 为空（应用尚未 `install`）时只记警告，**不抛异常**——
 * 广播接收器崩溃会记入系统日志并可能被系统限流。
 */
class BootEventReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // `SystemClock.elapsedRealtime()` 在纯 JVM 单测下不可用（`Method ... not mocked`），
        // 因此只在**这里**取一次真实时钟，判定逻辑留在 handleAction 里由单测注入固定值。
        handleAction(intent.action, elapsedRealtimeMillis = SystemClock.elapsedRealtime())
    }

    /**
     * 处理一个 action 字符串（[onReceive] 的实际逻辑，也是单测入口）。
     *
     * @param action 广播 action；`null` 视为异常输入
     * @param elapsedRealtimeMillis 单调时钟读数（阶段 3d）：供 [BootMarkerHolder] 判定
     *   "本周期是否已发过 boot"。由 [onReceive] 传真实值，单测传固定值。
     */
    internal fun handleAction(
        action: String?,
        elapsedRealtimeMillis: Long,
    ) {
        Log.i(TAG, "BOOT_RECEIVED from=${action ?: "<null>"}")

        if (action != ACTION_BOOT_COMPLETED) {
            // 清单只注册了 BOOT_COMPLETED；出现其它 action 说明清单/调用方与预期不符，
            // 记下来而不是静默忽略。
            Log.w(TAG, "BOOT_RECEIVED unexpected action=${action ?: "<null>"}, ignored")
            return
        }

        val bus = EventBusHolder.get()
        if (bus == null) {
            Log.w(TAG, "BOOT_RECEIVED but EventBusHolder is empty; boot event dropped")
            return
        }

        // 阶段 3d（D9）：广播路径也要维护"boot 已在本周期发出"的标记，
        // 否则 App 下次启动会**再补发一次** boot。详见 BootMarkerHolder 的 KDoc。
        BootMarkerHolder.markIfBootCycle(elapsedRealtimeMillis)

        bus.send(SystemEvent.Boot)
        Log.i(TAG, "EVENT_BUS sent=${SystemEvent.Boot.eventId}")
    }

    internal companion object {
        const val TAG = "RootFlow"

        /**
         * `ACTION_BOOT_COMPLETED` 的字面量副本。
         *
         * **为什么不直接用 `Intent.ACTION_BOOT_COMPLETED`**：那是 Android 框架的**静态字段**，
         * 在纯 JVM 单测（本项目不引入 Robolectric）下访问会抛
         * `RuntimeException: Method <clinit> ... not mocked`，而 MockK **无法 stub 静态字段**
         * （实测报 `Missing mocked calls inside every { } block`）。
         * 因此这里持有字面量，并由单测断言它**等于** `Intent.ACTION_BOOT_COMPLETED`——
         * 那个断言跑在能加载 Android 类的环境（真机/Robolectric）时提供契约保护，
         * 纯 JVM 下则以 `assumeTrue` 跳过（见 [BootEventReceiverTest]）。
         */
        const val ACTION_BOOT_COMPLETED: String = "android.intent.action.BOOT_COMPLETED"
    }
}
