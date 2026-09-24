package com.rootflow.data.di

import com.rootflow.data.event.MasterSwitchImpl
import com.rootflow.domain.event.MasterSwitch
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 总开关（总闸）的依赖装配（总开关重构 **P3**）。
 *
 * ## 为什么单开一个模块（而不是塞进 `CircuitBreakerModule`）
 * 两者都是"宿主级闸门"，但**正交且互不替代**：
 * | | `CircuitBreaker`（安全模式） | `MasterSwitch`（总闸） |
 * |---|---|---|
 * | 触发者 | 故障（连续失败 / 超时 / bootloop / 手动） | 用户（主页大开关） |
 * | 语义 | "出了事，先全停" | "我现在不想让它提供服务" |
 * | 恢复 | 需人工退出安全模式 | 拨回来即可，状态全保留 |
 *
 * 放在同一个模块里会让"改熔断的行为"与"改总闸的行为"共用一份文件与一条审阅路径
 * —— 而它们的裁定来源完全不同（前者是需求 §5，后者是 `docs/总开关机制-方案.md`）。
 *
 * ## 与 `DatabaseModule` 的关系
 * [MasterSwitchImpl] 的 `@Inject` 构造只依赖 `AppSwitchDao`，而后者已由
 * `DatabaseModule` 提供 ⇒ 本模块只需一条 `@Binds`，没有额外的 `@Provides`。
 *
 * ## 依赖环检查（Hilt 的 `Found a dependency cycle` 只在 `hiltJavaCompileDebug` 暴露）
 * `MasterSwitch` 的消费者是 `TriggeredScriptRunner` 与 `DaemonSupervisorImpl`；
 * 而 [MasterSwitchImpl] 只依赖 DAO。**没有任何反向边** ⇒ 不存在环。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MasterSwitchModule {
    /** 总闸端口。 */
    @Binds
    @Singleton
    abstract fun bindMasterSwitch(impl: MasterSwitchImpl): MasterSwitch
}
