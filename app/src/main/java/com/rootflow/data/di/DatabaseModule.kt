package com.rootflow.data.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.rootflow.data.db.RootFlowDatabase
import com.rootflow.data.db.RootFlowMigrations
import com.rootflow.data.db.dao.AppSwitchDao
import com.rootflow.data.db.dao.RunDao
import com.rootflow.data.db.dao.RunLogDao
import com.rootflow.data.db.dao.ScriptDao
import com.rootflow.data.db.dao.ScriptEventDao
import com.rootflow.data.fs.RootFileStore
import com.rootflow.data.run.RunHistoryReader
import com.rootflow.data.run.RunHistoryWriter
import com.rootflow.data.script.ScriptRepositoryImpl
import com.rootflow.data.trigger.TriggerRepositoryImpl
import com.rootflow.domain.repository.RunHistoryRepository
import com.rootflow.domain.repository.ScriptRepository
import com.rootflow.domain.repository.TriggerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 持久化层的 DI 装配（阶段 3a）。
 *
 * ## 为什么用模块而不是纯构造注入
 * 与阶段 2 的 `LogModule` 同理：[RootFlowDatabase] 需要 `Context` 且必须**单例**
 * （Room 明确要求同一数据库只建一个实例，多实例会导致 WAL 锁竞争与缓存不一致）。
 *
 * ## 数据库构造要点
 * - `exportSchema = true`（见 [RootFlowDatabase]），schema JSON 入库
 * - `addMigrations(*RootFlowMigrations.ALL)`：**显式登记，禁止破坏性迁移**
 * - 单例由 [SingletonComponent] + `@Singleton` 保证
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** 数据库单例。 */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): RootFlowDatabase =
        Room
            .databaseBuilder(context, RootFlowDatabase::class.java, RootFlowDatabase.NAME)
            .addMigrations(*RootFlowMigrations.ALL)
            .build()

    @Provides
    fun provideScriptDao(database: RootFlowDatabase): ScriptDao = database.scriptDao()

    @Provides
    fun provideScriptEventDao(database: RootFlowDatabase): ScriptEventDao = database.scriptEventDao()

    /**
     * 总开关 DAO（P3 补）。
     *
     * ## 为什么之前一直没暴露（留档，供同类问题参考）
     * `app_switch` 的表、DAO、实体在 P2（数据模型 + 迁移）就位了，但**没有任何消费者**
     * ⇒ Dagger 从不解析它 ⇒ `MissingBinding` 直到 P3 把 `MasterSwitchImpl` 接上才出现。
     * 只在 `hiltJavaCompileDebug` 阶段报（Kotlin 编译看不出来），这也是本仓库
     * 反复记下的坑型：**新增绑定要连消费者一起加，否则"编译通过"证明不了装配可用**。
     */
    @Provides
    fun provideAppSwitchDao(database: RootFlowDatabase): AppSwitchDao = database.appSwitchDao()

    @Provides
    fun provideRunDao(database: RootFlowDatabase): RunDao = database.runDao()

    @Provides
    fun provideRunLogDao(database: RootFlowDatabase): RunLogDao = database.runLogDao()

    /**
     * 脚本仓库。
     *
     * 显式装配而非依赖默认参数，是因为 [ScriptRepositoryImpl] 的 `clock` 有默认值——
     * Hilt 无法为"带默认值的函数类型参数"生成绑定，必须在这里给出。
     */
    @Provides
    @Singleton
    fun provideScriptRepository(
        scriptDao: ScriptDao,
        fileStore: RootFileStore,
    ): ScriptRepository = ScriptRepositoryImpl(scriptDao = scriptDao, fileStore = fileStore)

    /**
     * 脚本 × 事件订阅仓库。
     *
     * `onParamsWarning` 接到 Android 日志：params JSON 解析失败**不崩**，但必须可见
     * （见 [TriggerRepositoryImpl] KDoc）。
     */
    @Provides
    @Singleton
    fun provideTriggerRepository(scriptEventDao: ScriptEventDao): TriggerRepository =
        TriggerRepositoryImpl(
            scriptEventDao = scriptEventDao,
            onParamsWarning = { message -> Log.w(TAG, message) },
        )

    /** 运行历史写入器。告警接日志，避免 D6 耦合点失效时静默。 */
    @Provides
    @Singleton
    fun provideRunHistoryWriter(
        runDao: RunDao,
        runLogDao: RunLogDao,
    ): RunHistoryWriter =
        RunHistoryWriter(
            runDao = runDao,
            runLogDao = runLogDao,
            onWarning = { message -> Log.w(TAG, message) },
        )

    /** 运行历史只读仓库。 */
    @Provides
    @Singleton
    fun provideRunHistoryRepository(
        runDao: RunDao,
        runLogDao: RunLogDao,
    ): RunHistoryRepository = RunHistoryReader(runDao = runDao, runLogDao = runLogDao)

    private const val TAG = "RootFlow"
}
