package com.rootflow.data.di

import android.util.Log
import com.rootflow.ui.AlertSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * UI 层 ViewModel 的告警落点装配（阶段 6e）。
 *
 * ## ★ 它修的是一个自 6b 起就存在的真缺陷（6e 真机验证抓到）
 * 四个 `@HiltViewModel`（`HomeViewModel` / `ScriptListViewModel` /
 * `ScriptEditorViewModel` / `SettingsViewModel`）都有一个告警缝，但此前写作
 * `internal var onWarning: (String) -> Unit = {}` —— 而**它们由 Dagger 构造**，
 * 没有任何生产代码给那个 `var` 赋值 ⇒ 缝**永远是 no-op**。
 *
 * 后果：这些 ViewModel 里的告警（"触发器写入失败""计数刷新失败""环境刷新失败"…）
 * 在真机 logcat 里**一行都看不到**。6e 的真机验证计划里写着"判读靠 `findstr` 这些告警"，
 * 而实测 `logcat -s RootFlow:V` 里 `SCRIPTS_*` **零命中** —— 计划与实现不符。
 *
 * 现在缝是**构造形参**（`AlertSink`），由本模块提供生产落点 ⇒ 把 `var` 改回去会编译不过。
 *
 * ## 为什么用 `@Provides` 而不是让 ViewModel 直接调 `Log.w`
 * `AGENT_PROTOCOL.md §5.10`：单测在纯 JVM 下跑，`android.util.Log` **未 stub**，
 * 在被测路径上直接 `Log.*` 会让用例因为"日志"而红（6b 实测：一行 `Log.i` 打红 8 条用例）。
 * 因此**实现侧一律只调注入的缝**，由本模块在生产侧把缝接到 `Log.w`；
 * 单测则直接传一个记录器实现进构造器。
 *
 * ## 为什么 `TAG = "RootFlow"` 与其它模块一致
 * 真机判读统一用 `logcat -s RootFlow:V`（`AGENT_PROTOCOL.md §7.3`）。
 * 换一个 tag 会让"按 tag 落盘"的规范失效 —— 那些告警会掉进全量日志里被刷掉。
 */
@Module
@InstallIn(SingletonComponent::class)
object ViewModelAlertModule {
    /** 生产落点：`W/RootFlow` 级别（告警不是普通信息，级别要与语义一致）。 */
    @Provides
    @Singleton
    fun provideAlertSink(): AlertSink = AlertSink { message -> Log.w(TAG, message) }

    private const val TAG = "RootFlow"
}
