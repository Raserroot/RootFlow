package com.rootflow.data.event.android

import com.rootflow.data.event.AlarmEventSource
import com.rootflow.data.event.AlarmHandle
import com.rootflow.data.event.RoomTriggerScheduleProvider
import com.rootflow.data.event.TriggerScheduleProvider
import com.rootflow.data.event.UsageStatsPollingSource
import com.rootflow.data.event.UsageStatsReader
import com.rootflow.domain.event.EventSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * 阶段 3c.2 的 DI 装配：`usage_stats` 轮询源 + `alarm_time` / `alarm_interval` 两个闹钟源。
 *
 * ## 为什么单独一个模块（**诊断结论，勿"顺手合并回" `AndroidEventSources`**）
 * 3c.1 实测：把**第 4 条** `@Provides @IntoSet` 放进 `AndroidEventSources` 的
 * `companion object` 时，KSP 对该组合解析失败（报 `could not be resolved`，
 * 且错误会串到同模块的其余绑定上），移除该条贡献即 `BUILD SUCCESSFUL`——
 * 完整的 A/B 对照实验记录在 `BatteryEventSourceModule` 的 KDoc 里。
 *
 * `AndroidEventSources` 目前已有 3 条 `@IntoSet`（screen / wifi / power），
 * `BatteryEventSourceModule` 有 1 条。**本条模块再加 2 条**，保持"单个 companion object
 * 不超过 3 条 `@IntoSet`"的既有形态，避免重新触发该异常。
 *
 * ## 3c.2 之后的事件源总数（4 → 7）
 * `alarm_interval` · `alarm_time` · `battery` · `power` · `screen` · `usage_stats` · `wifi`
 * （`registry.status()` 按 `sourceId` 升序输出，真机可逐行比对）
 *
 * ## 一个类贡献两个源（实现决策 D-1）
 * `AlarmEventSource` 内聚两类闹钟的**共享逻辑**（`requestCode` 派生 / 冲突检测 /
 * `cancel→set` 顺序），但对外暴露 [AlarmEventSource.time] 与 [AlarmEventSource.interval]
 * **两个** `EventSource`——决策 7 要求**每个源**都能单独回答"是否启动 + 为什么没启动"，
 * 合成的单一源会让 `time` 与 `interval` 的状态无法分别上报。
 *
 * ## 按决策 13：本模块与适配器均不进单测
 * 绑定关系错的后果是**编译期/启动期失败**（Hilt 报 `MissingBinding`），
 * 不是运行期静默错误，因此真机启动一次即可确认。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmEventSourceModule {
    /** 前台读取端口：domain 端口 → `UsageStatsManager` 适配。 */
    @Binds
    @Singleton
    abstract fun bindUsageStatsReader(impl: AndroidUsageStatsReader): UsageStatsReader

    /** 闹钟句柄：domain 端口 → `AlarmManager` 适配。 */
    @Binds
    @Singleton
    abstract fun bindAlarmHandle(impl: AndroidAlarmHandle): AlarmHandle

    /**
     * 闹钟数据来源：**阶段 3d 起为真实实现**（`RoomTriggerScheduleProvider`）。
     *
     * 3c.2 绑的是空实现（决策 8：当时触发器的写入路径尚未接线）。3d 接通
     * `TriggerRepository` 后，`time` / `interval` 的真机验证前提（"Room 里有触发器数据"）
     * 才成立，`AlarmEventSource` 与其单测仍一行未改。
     */
    @Binds
    @Singleton
    abstract fun bindTriggerScheduleProvider(impl: RoomTriggerScheduleProvider): TriggerScheduleProvider

    companion object {
        /** `app_foreground` / `app_background` 轮询源（一个源发两个事件）。 */
        @Provides
        @Singleton
        @IntoSet
        fun provideUsageStatsPollingSource(source: UsageStatsPollingSource): EventSource = source

        /** `time` 闹钟源（`sourceId = "alarm_time"`；权限集为空，D3 走非精确）。 */
        @Provides
        @Singleton
        @IntoSet
        fun provideTimeAlarmSource(source: AlarmEventSource): EventSource = source.time

        // `interval` 闹钟源（`sourceId = "alarm_interval"`）在下面的独立模块里登记：
        // 本条 companion 已有 2 条 @IntoSet，第 3 条（interval）留给下面的模块，
        // 保持"每个 companion 最多 3 条"的形态（见类 KDoc 的 KSP 说明）。
    }
}

/**
 * `interval` 闹钟源的 `@IntoSet` 贡献（阶段 3c.2）。
 *
 * 单独成模块的理由见 [AlarmEventSourceModule] 的类 KDoc（KSP 对 companion 内
 * 多条 `@IntoSet` 的解析异常）。
 */
@Module
@InstallIn(SingletonComponent::class)
object IntervalAlarmSourceModule {
    /** `interval` 闹钟源（`sourceId = "alarm_interval"`；权限集为空，D4 走非精确重复）。 */
    @Provides
    @Singleton
    @IntoSet
    fun provideIntervalAlarmSource(source: AlarmEventSource): EventSource = source.interval
}
