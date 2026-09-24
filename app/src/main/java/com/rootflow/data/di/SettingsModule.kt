package com.rootflow.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.rootflow.data.settings.SettingsRepositoryImpl
import com.rootflow.data.settings.SettingsStore
import com.rootflow.data.settings.createSettingsDataStore
import com.rootflow.domain.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 设置项的 DI 装配（阶段 6）。
 *
 * ## 为什么 `DataStore` 必须 `@Provides` 且必须单例（**不是优化，是硬要求**）
 * DataStore 明确禁止同一个文件同时存在两个活跃实例，违反时抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * Hilt 的 `@Singleton` 正是"全进程唯一实例"的保证——若哪天有人绕过本模块自行
 * `createSettingsDataStore(...)`，那个异常会立刻出现（这是好事：**响亮地失败**，
 * 而不是两个实例互相覆盖对方的写入）。
 *
 * ## 为什么用 `filesDir/datastore/` 而不是 `filesDir/` 根目录
 * DataStore 的惯例子目录；也避免将来别的文件（导出、缓存）与设置文件混在同一层。
 * 路径来自 [SettingsStore]，**不在本模块另起字面量**。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    /** 设置读写端口。 */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    companion object {
        /**
         * 设置用的 DataStore（**全进程唯一**，见类 KDoc）。
         *
         * 内部用无 `scope` 的工厂重载：DataStore 自己持有 IO 作用域，
         * 寿命与进程一致，没有"必须在某处取消"的作业。
         */
        @Provides
        @Singleton
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = createSettingsDataStore(SettingsStore.fileIn(context.filesDir))
    }
}
