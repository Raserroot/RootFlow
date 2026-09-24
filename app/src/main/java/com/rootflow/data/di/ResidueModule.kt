package com.rootflow.data.di

import com.rootflow.data.residue.ResidueCleanerImpl
import com.rootflow.domain.residue.ResidueCleaner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 卸载残留清理的 DI 装配（阶段 6d，需求 §8）。
 *
 * ## 为什么单独一个模块而不是塞进 `DatabaseModule`
 * 本实现同时依赖 **root 通道**（`RootShellManager` / `RootFileStore`）与 **Room**
 * （`ScriptDao` / `TriggerRepository`）。放进 `DatabaseModule` 会让"数据库模块知道怎么删文件"
 * ——那条依赖关系在读代码时无法自解释（与 6b 把事件源状态并入 `ServiceStateProvider`
 * 的判断相反：那里是"同一所有者"，这里是"两个不同来源"）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ResidueModule {
    /** 残留清理端口（设置页「关于」组用）。 */
    @Binds
    @Singleton
    abstract fun bindResidueCleaner(impl: ResidueCleanerImpl): ResidueCleaner
}
