package com.rootflow.data.event.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.rootflow.data.event.UsageStatsRead
import com.rootflow.data.event.UsageStatsReader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [UsageStatsReader] 的 `UsageStatsManager` 适配（阶段 3c.2）。
 *
 * ## 按决策 13：本类不进单测
 * 它只做 Android API 直调（`queryEvents` + 事件游标），纯 JVM 下必然 `Method ... not mocked`，
 * 而本项目不引入 Robolectric。**其正确性由真机覆盖**：
 * 真机观测点是 `UsageStatsPollingSource` 的 `APP_FOREGROUND_RECEIVED edge=…` 与
 * `EVENT_BUS sent=app_foreground|app_background`（切换 App 即可触发）。
 *
 * ## 两处必须注意的框架细节（都由 `javap` 在 `android-35` 桩 jar 上核实）
 * 1. `UsageEvents` **只有** `getNextEvent(Event)` 这个"写入传入对象"的重载，
 *    **没有**返回新对象的形态。因此必须复用一个 `Event` 实例并逐条读。
 * 2. `UsageEvents.Event` 的 `getPackageName()` / `getEventType()` 在该 jar 中存在；
 *    本适配器只读这两个 + 时间戳，不依赖更高 API 的字段。
 *
 * ## 游标策略
 * 内部维护 `lastReadMillis`：每次读取后推进到本次查询的结束时刻（[System.currentTimeMillis]），
 * 因此**不会重复读到同一批事件**。`reset()` 把它设回"现在"，从而丢弃历史事件——
 * 这样 App 启动时不会把"上一次运行期间"的前台切换当成新事件重放。
 *
 * ## 为什么用墙钟而不是 `SystemClock.elapsedRealtime`
 * `UsageStatsManager.queryEvents(begin, end)` 的时间轴是**墙钟**（`currentTimeMillis` 系）。
 * 单调时钟只在判断"是否息屏/是否首次启动"这类**本进程内**的时序时才用（如 D9 的补 boot 语义）。
 *
 * @param context 应用上下文
 * @param clock 墙钟取时（经 `internal` 次构造函数注入，见该类内说明）
 */
@Singleton
class AndroidUsageStatsReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : UsageStatsReader {
        /**
         * 测试缝：注入墙钟。
         *
         * **不把 `clock` 放进 `@Inject` 构造**：Kotlin 默认参数对 Dagger 不可见，
         * 它会尝试注入 `Function0<Long>` 而报 `MissingBinding`（3c.1 起反复踩到的坑）。
         */
        internal constructor(
            context: Context,
            clock: () -> Long,
        ) : this(context = context) {
            this.clock = clock
        }

        private var clock: () -> Long = System::currentTimeMillis

        private val lock = Any()

        private var lastReadMillis: Long = clock()

        override val cursorMillis: Long
            get() = synchronized(lock) { lastReadMillis }

        override fun read(currentMillis: Long): UsageStatsRead {
            val manager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return UsageStatsRead.Unavailable

            val begin = synchronized(lock) { lastReadMillis }
            val events =
                try {
                    manager.queryEvents(begin, currentMillis)
                } catch (error: Throwable) {
                    // 权限被运行期撤销 / 系统服务异常：如实返回"不可用"，由源的告警路径处理
                    Log.w(TAG, "USAGE_STATS_QUERY_FAILED: ${error.message ?: error::class.java.name}")
                    return UsageStatsRead.Unavailable
                }
                    ?: return UsageStatsRead.Unavailable

            val latest = latestForegroundPackage(events)
            synchronized(lock) {
                // 推进游标（即使本轮无事件也要推进，否则下一轮会重复扫描同一窗口）
                lastReadMillis = currentMillis
            }
            return UsageStatsRead.Foreground(packageName = latest)
        }

        override fun reset() {
            synchronized(lock) {
                lastReadMillis = clock()
            }
        }

        /**
         * 取本批事件里**最后一个**"进入前台"的包名。
         *
         * @return `null` 表示本批没有"进入前台"事件（正常情况——2s 一次的轮询里多数轮次为空）
         */
        private fun latestForegroundPackage(events: UsageEvents): String? {
            // 复用同一个 Event 实例（框架只提供写入式读数）
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTimestamp = Long.MIN_VALUE

            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) continue
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val stamp = event.timeStamp
                if (stamp >= latestTimestamp) {
                    latestTimestamp = stamp
                    latestPackage = event.packageName
                }
            }
            return latestPackage
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
