package com.rootflow.data.event.android

import com.rootflow.data.event.BatteryEventSource
import com.rootflow.domain.event.EventSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * `battery_low` / `battery_okay` 源的 multibinding 贡献（阶段 3c.1，含决策 3 的恢复边）。
 *
 * ## 为什么单独一个模块（**诊断结论，勿"顺手合并回去"**）
 * 本条贡献原本写在 `AndroidEventSources` 的 `companion object` 里，作为该模块的**第 4 条**
 * `@Provides @IntoSet`。该形态下 KSP 报：
 * ```
 * e: [ksp] MultibindingAnnotationsProcessingStep was unable to process
 *     'provideScreenEventSource(...)' because 'BatteryEventSource' could not be resolved.
 * ```
 * 注意报错**串到了同模块的其它绑定**（连 `bindBroadcastRegistration` 都报同一条原因），
 * 而该类型明明存在于同一源码集内。
 *
 * ### 已做的对照实验（`AGENT_PROTOCOL.md §6.1`）
 * | 实验 | 结果 |
 * |---|---|
 * | 把该类与 `PowerEventSource` 放在同一文件 | 仍失败 |
 * | 把该类拆到独立文件 | 仍失败 |
 * | **把类改名为 `BatteryEventSource`**（provider 同步改名） | **仍失败，错误跟着 provider 位置走，不跟名字走** |
 * | **注释掉这一条 `@IntoSet` 贡献** | **`BUILD SUCCESSFUL`** |
 * | 删除全部构建产物 + `gradlew --stop` 后重编 | 仍失败（非陈旧缓存） |
 * | 全部 KDoc 移除（怀疑注释解析） | 仍失败（非注释问题） |
 * | 校验：UTF-8 无 BOM、纯 LF、`/*`↔`*/` 配平、花括号配平、无不可见字符 | 全部正常 |
 *
 * 即：**触发条件是"同一模块的 `companion object` 中第 4 条 `@IntoSet`"，与本类的内容无关**。
 * 拆成独立模块（Hilt 官方推荐的 multibinding 组织形态）后行为完全等价（贡献到同一个
 * `Set<EventSource>`）且可编译。
 *
 * ### 待办
 * 这是 KSP/Hilt 侧的异常，不是本项目的设计选择。**阶段 3c.2 若再新增事件源**，
 * 应先确认该现象是否仍复现（若不复现，可把本条贡献合并回 `AndroidEventSources`）；
 * 若仍复现，则**沿用"每源一个模块"或把源拆到独立模块**的形态，不要在一个
 * `companion object` 里堆第 4 条 `@IntoSet`。
 */
@Module
@InstallIn(SingletonComponent::class)
object BatteryEventSourceModule {
    /**
     * 清单注册的电量源。
     *
     * 必须显式列入 multibinding，否则不会进入 `Set<EventSource>`——
     * 那种错误**没有编译报错**，真机上只表现为"事件永不触发"。
     */
    @Provides
    @Singleton
    @IntoSet
    fun provideBatteryEventSource(source: BatteryEventSource): EventSource = source
}
