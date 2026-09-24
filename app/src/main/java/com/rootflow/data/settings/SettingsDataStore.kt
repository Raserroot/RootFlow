package com.rootflow.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * 设置用的 DataStore 实例（阶段 6，需求 §0 的 `DataStore`）。
 *
 * ## ★ 为什么用 `PreferenceDataStoreFactory.create` 而不是 `preferencesDataStore` 委托
 * `by preferencesDataStore(name)` 只能声明在 `Context` 的扩展属性上——它是一个**全局单例
 * 委托**，测试无法为每个用例指定独立文件（多个用例共用一个文件会互相污染，
 * 且 DataStore 明确禁止同一文件同时存在两个活跃实例，否则抛
 * `IllegalStateException: There are multiple DataStores active for the same file`）。
 *
 * 改为工厂函数后：
 * - 生产：由 `SettingsModule` 用 `context.filesDir` 建**唯一**实例
 * - 单测：用 `@TempDir` 建独立文件，用例之间零干扰（`SettingsRepositoryTest` 即此形态）
 *
 * ## 为什么不用带 `scope` 参数的那个重载（**1.1.7 里本来也没有**）
 * 1.2.1 才有 `create(…, scope = …)`。而本项目锁在 1.1.7（离线缓存现实，见
 * `libs.versions.toml` 的 A1 报告），且无 `scope` 的重载内部用 DataStore 自己的 IO 作用域，
 * 正是设置项想要的寿命：进程级，随进程结束而结束，没有"必须在某处取消"的作业。
 *
 * @param file 落盘路径；生产为 `filesDir/datastore/rootflow_settings.preferences_pb`
 */
internal fun createSettingsDataStore(file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { file })

/**
 * 设置文件的路径与名字（**唯一真相源**）。
 *
 * 生产与单测都从这里取，避免"改了实现里的名字、别处还写着旧的"这类静默错配
 * （与 `SharedPrefsCrashMarkerStore.PREFS_NAME` 同款纪律）。
 */
internal object SettingsStore {
    /** 文件名（DataStore 会自动补 `.preferences_pb` 后缀）。 */
    const val FILE_NAME: String = "rootflow_settings.preferences_pb"

    /** 相对 `filesDir` 的子目录。 */
    const val DIRECTORY: String = "datastore"

    /** 生产路径：`filesDir/datastore/rootflow_settings.preferences_pb`。 */
    fun fileIn(filesDir: File): File = File(File(filesDir, DIRECTORY), FILE_NAME)
}
