package com.rootflow.ui

/**
 * UI 层 ViewModel 的**告警落点**（阶段 6e）。
 *
 * ## 为什么是一个接口，而不是 `(String) -> Unit`
 * 两个独立理由，任何一个都足够：
 *
 * ### ① 函数类型做不了 Dagger 的绑定键（实测报错，不是推测）
 * 初版是把 `(String) -> Unit` 直接 provide 出来：
 * ```kotlin
 * @Provides @Singleton @ViewModelAlertSink
 * fun provideViewModelAlertSink(): (String) -> Unit = { Log.w(TAG, it) }
 * ```
 * `hiltJavaCompileDebug` 直接失败：
 * ```
 * [Dagger/MissingBinding] @ViewModelAlertSink kotlin.jvm.functions.Function1<
 *     ? super java.lang.String, kotlin.Unit> cannot be provided
 * ```
 * 原因是**KSP 生成的是 `Factory<Function1<String, Unit>>`，而注入点的类型是
 * `Function1<? super String, Unit>`** —— Java 泛型的**不变性**让这两者不是同一个类型键。
 * `@Provides` 方法确实被生成了，绑定就是匹配不上，且报错只说"没有 @Provides 方法"，
 * 完全没提类型不匹配（误导性极强，故记在此处）。
 *
 * ### ② 它是**接缝的形状**，必须是一个有名字的类型
 * `(String) -> Unit` 在类型上跟本仓库其它 `onWarning` 形参（`LogPipelineImpl` /
 * `TriggerDispatcherImpl` / `RunHistoryWriter`）**完全一样**，无法区分落点。
 * 六个 ViewModel 同时依赖一个裸函数类型，等于把"这些告警去哪"从类型系统里抹掉。
 *
 * ## 为什么接口放在 `ui/`（而不是 `data/di/`）
 * 分层的真正约束是"**调用方不依赖实现**"：`ui` 只认识 [AlertSink]，由 `data/di` 提供
 * 落到 `Log.w` 的实现（`ViewModelAlertModule`）。把接口放在 `data/di` 会让
 * 六个 `ui` 类**反向 import `data`** —— 那是真的违反 `AGENTS.md` 的依赖方向。
 * 这与 `domain` 端口同款思路（`domain` 只描述"要什么"，实现住在 `data`）。
 */
fun interface AlertSink {
    /** 记录一条**不该静默**的告警（生产实现落到 `Log.w`）。 */
    fun warn(message: String)
}
