package com.rootflow.data.event.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rootflow.data.event.AlarmHandle
import com.rootflow.service.receiver.AlarmFireReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AlarmHandle] 的 `AlarmManager` 适配（阶段 3c.2）。
 *
 * ## 按决策 13：本类不进单测
 * 它只做 Android API 直调（`AlarmManager` + `PendingIntent`），纯 JVM 下必然
 * `Method ... not mocked`。**其正确性由真机覆盖**：
 * 真机观测点是 `adb -s <serial> shell dumpsys alarm | findstr /C:"rootflow"`
 * （3d 有触发器数据后才会有非空输出；3c.2 为空是预期，见决策 8）。
 *
 * ## 两处必须注意的框架细节（均由 `javap` 在 `android-35` 桩 jar 上核实）
 * 1. **`setAndAllowWhileIdle` 而非 `setExactAndAllowWhileIdle`**：按 **D3**，
 *    v1 不申请 `SCHEDULE_EXACT_ALARM`，走非精确路径。桩 jar 两个方法都有，
 *    这里**故意**选非精确的那个。
 * 2. **`FLAG_IMMUTABLE`**：Android 12+ 要求 `PendingIntent` 显式声明可变性，
 *    否则 `setInexactRepeating` 抛 `IllegalArgumentException`。
 *    配合 `FLAG_UPDATE_CURRENT` 使用（同一 `(requestCode, action)` 复用同一个 PendingIntent）。
 *
 * ## 闹钟身份 = `(requestCode, action)`
 * [pendingIntentFor] 是唯一的构造点：`action` 放在 `Intent` 上，`requestCode` 传给
 * `PendingIntent.getBroadcast`。`cancel` 与 `set` 都经它构造，因此两者指向**同一个**
 * `PendingIntent` 身份——这是"先 cancel 后 set"能生效的前提（见 `AlarmEventSource` 的 KDoc）。
 *
 * @param context 应用上下文
 */
@Singleton
class AndroidAlarmHandle
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AlarmHandle {
        override fun cancel(
            requestCode: Int,
            action: String,
        ) {
            val manager = alarmManager() ?: return
            manager.cancel(pendingIntentFor(requestCode, action))
        }

        /**
         * 取消本应用的全部闹钟（阶段 3d，决策 **D-3d-1**）。
         *
         * ## 为什么 `action` 参数没有参与调用
         * `AlarmManager.cancelAll()` **不接受 action**：它取消的是本应用注册的**全部**
         * 闹钟。参数保留在签名里是因为**调用方的语义**需要区分两个域
         * （`AlarmSyncCoordinator` 对 `TIME` / `INTERVAL` 各调一次），
         * 且将来若改为 `PendingIntent` 级精确取消，这个参数就是现成的入口。
         *
         * 两次调用合起来才是"全部清空"，但这**不是冗余**：它让"两个域各自被清过"
         * 这件事在日志与单测里逐域可见（真机排障时能确认两个域都走过对账）。
         *
         * `cancelAll` 对不存在的闹钟是安全的（与 `cancel` 同款幂等语义）。
         */
        override fun cancelAll(action: String) {
            val manager = alarmManager() ?: return
            manager.cancelAll()
            Log.i(TAG, "ALARM_CANCEL_ALL action=$action (AlarmManager.cancelAll has no action scope)")
        }

        override fun setNext(
            requestCode: Int,
            action: String,
            atMillis: Long,
        ) {
            val manager = alarmManager() ?: return
            // 非精确（D3）：RTC_WAKEUP 保证息屏时也会唤醒投递
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                atMillis,
                pendingIntentFor(requestCode, action),
            )
        }

        override fun setRepeating(
            requestCode: Int,
            action: String,
            intervalMillis: Long,
        ) {
            val manager = alarmManager() ?: return
            // 非精确重复（D4）。触发时刻取"现在 + 间隔"，
            // 之后由系统按 intervalMillis 重复；意图明确，避免依赖 AlarmManager 的隐式语义。
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + intervalMillis,
                intervalMillis,
                pendingIntentFor(requestCode, action),
            )
        }

        private fun alarmManager(): AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        /**
         * 构造 `(requestCode, action)` 对应的广播 `PendingIntent`。
         *
         * `Intent` 目标是清单声明的 [AlarmFireReceiver]：
         * **显式** component + action 双保险——只给 action 时若被系统限制隐式广播会静默失效。
         */
        private fun pendingIntentFor(
            requestCode: Int,
            action: String,
        ): PendingIntent {
            val intent =
                Intent(context, AlarmFireReceiver::class.java).apply {
                    this.action = action
                }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
