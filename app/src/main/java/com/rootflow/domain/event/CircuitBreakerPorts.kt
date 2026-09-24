package com.rootflow.domain.event

import kotlinx.coroutines.flow.StateFlow

/**
 * 安全模式状态与熔断/恢复入口（需求 §5.2 / §5.3）。
 *
 * ## 为什么是 `domain` 端口
 * - `TriggerDispatcher` 与 `TriggeredScriptRunner`（都在 `data/`）要读**状态**
 * - 阶段 6 的 UI 要订阅同一个状态（banner）
 * - 阶段 5 的前台服务也要订阅（常驻通知）
 *
 * 三者都不能依赖"具体实现"，因此这里只声明端口；动作编排在
 * `data/event/CircuitBreakerImpl`。
 *
 * ## 为什么状态用 `StateFlow` 而不是 `EventBus`
 * 熔断本身若经事件总线广播，会与"分发"路径形成**循环可能**
 * （熔断 → 事件 → 分发 → 判安全模式 → 熔断 …）。`StateFlow` 是**拉模型**：
 * 订阅者自己取当前值，天然免疫该问题；且新订阅者立即拿到当前态（UI 冷启动正确）。
 */
interface CircuitBreaker {
    /**
     * 当前是否处于安全模式。
     *
     * 取值来源（优先级从高到低）：
     * 1. `safemode.flag` 文件存在（需求 §5.2 第 1 步的产物）
     * 2. 开机安全模式联动命中（需求 §5.3：`persist.sys.safemode` / Magisk safemode）
     * 3. 本进程内已熔断
     */
    val safeMode: StateFlow<Boolean>

    /** 当前安全模式的成因；不在安全模式时为 `null`。 */
    val tripReason: StateFlow<TripReason?>

    /**
     * 记录一次"脚本已被受理"（需求 §5.1 第 5 条的数据来源）。
     *
     * 调用点固定在 `TriggeredScriptRunner` 受理成功之后——**不在分发入口**，
     * 因为防抖与重入拒绝已经挡掉部分重复，把被拒的也算进来会让阈值失去意义。
     */
    suspend fun onRunAccepted(scriptId: Long)

    /**
     * 记录一次运行结局（需求 §5.1 第 2、3 条的数据来源）。
     *
     * ## ★ 为什么带 `exitCode` 而不是"让实现自己去查库"（阶段 4 收尾的已批准修正）
     * 早先的形态只有 `(scriptId, failed)`，`failed` 需要由调用方查
     * `RunHistoryRepository.find(runId).exitCode` 得出。但那有**已知竞态**：
     * `RunHistoryCollector` 是"有界队列 + 独立消费者"，`enqueue` 只 `trySend` 就返回，
     * 而回调挂在 `job.invokeOnCompletion` 上 ⇒ **早于消费者写库**，
     * `exitCode` 可能读到 `null`（后果是正常脚本被判失败 → 连续 3 次 → **误熔断**）。
     *
     * 现在退出码由 `ScriptRunCoordinator` 在收集 `runtime.LogLine` 时**就地捕获**并随参数带出，
     * 全程无 DB 往返、无异步窗口；判定"成败"的解释权留在实现里（见 `CircuitBreakerImpl`）。
     *
     * @param exitCode 本次运行的退出码；`null` = 没拿到（运行被 kill / 取消 / 没跑到末尾）。
     *   实现**不得**把 `null` 直接当成失败——漏熔断 < 误熔断。
     */
    suspend fun onRunFinished(
        scriptId: Long,
        exitCode: Int?,
    )

    /** 脚本超时（需求 §5.1 第 1 条）。 */
    suspend fun onRunTimeout(
        scriptId: Long,
        timeoutMillis: Long,
    )

    /** Root 无响应（需求 §5.1 第 4 条）。 */
    suspend fun onRootUnresponsive(elapsedMillis: Long)

    /**
     * 手动熔断（需求 §5.1 第 6 条）。
     *
     * ## 调用点（阶段 6e 起）
     * **设置页**的「立即熔断」按钮（`SettingsViewModel.tripManually`，带二次确认）。
     * 阶段 4 期间它由 `RootFlowApp` 的临时验证入口驱动 —— 那个入口已在 6e 删除
     * （见 `PROJECT_STATE.md` 的「6e 收尾记录 · 清理裁定表」）。
     *
     * 本方法**没有**其他调用者：不要因为"看不到调用点"而删它，那是 UI 的正式入口。
     */
    suspend fun tripManually()

    /** Bootloop 兜底命中（需求 §5.3）。 */
    suspend fun tripForBootloop(crashes: Int)

    /**
     * 恢复（需求 §5.3 第 1 条：App 内恢复）。
     *
     * @param mode 见 [RestoreMode]
     */
    suspend fun restore(mode: RestoreMode)

    /**
     * 启动时一次性探测（需求 §5.3 第 2 条：开机安全模式联动）。
     *
     * 与 [restore] 相反：它**只读不写**——命中外部安全模式时进内存态，
     * **不**去创建 `safemode.flag`（那是熔断动作，不该由"检测到系统安全模式"触发）。
     *
     * @return 命中的成因；未命中返回 `null`
     */
    suspend fun probeExternalSafeMode(): TripReason?
}

/** 恢复方式（需求 §5.3 第 1 条：退出 / 保持禁用）。 */
enum class RestoreMode {
    /**
     * 恢复并把触发器**还原到熔断前的原始状态**。
     *
     * **不是**"全部启用"：熔断前本就禁用的触发器必须保持禁用，
     * 否则恢复动作会**替用户打开他没开的触发器**——那是比不恢复更糟的静默副作用。
     */
    RestoreOriginal,

    /** 退出安全模式，但**保持触发器全部禁用**（用户想先排查再逐条开）。 */
    KeepDisabled,
}

/**
 * Root 通道健康探针（需求 §5.1 第 4 条）。
 *
 * ## 为什么是端口
 * 探针要发 `su -c echo`，而 `AGENTS.md` 规定 Root 操作只能出现在 `runtime/`。
 * `domain` 只声明"要探活"，实现（`data/event/RootHealthProbeImpl`）经
 * `RootShellManager.exec(…, timeoutMillis)` 完成——`libsu` 的引用点仍收敛在一处。
 *
 * ## 为什么不加后台健康循环（**已批准的决定**）
 * 空闲期反复探测会与 `AGENT_PROTOCOL.md §9.4` 冲突（虚拟时间下 `advanceUntilIdle`
 * 可能因周期性超时永不返回），且**空闲期 root 挂掉不影响任何脚本**（没有脚本要跑）。
 * 因此探针只在两个时点被调用：**启动探针** 与 **运行结局**。
 */
interface RootHealthProbe {
    /** 探测一次。 */
    suspend fun probe(): Snapshot

    /**
     * 探测**外部安全模式**（需求 §5.3 第 2 条：`persist.sys.safemode` / Magisk safemode）。
     *
     * 与 [probe] 合并到同一端口的原因：两者都是"经 root 读一次系统状态"，
     * 共用同一个 `RootShellManager` 引用点。**分开成两个端口只会多一层无意义的转发。**
     *
     * @return 命中的原因；未命中返回 `null`
     */
    suspend fun detectExternalSafeMode(): TripReason?

    /**
     * @property responsive `su -c echo` 在阈值内返回且输出正确
     * @property elapsedMillis 实测耗时（超时则等于阈值）
     * @property detail 失败时的具体原因（写入 `safemode.log`）
     */
    data class Snapshot(
        val responsive: Boolean,
        val elapsedMillis: Long,
        val detail: String? = null,
    )
}

/**
 * 安全模式的对外通知（需求 §5.2 第 4、5 步）。
 *
 * ## 阶段 4 只做接口，阶段 5 才实现（已批准的交付边界）
 * - 第 4 步「前台服务进入安全模式」→ 阶段 5 的服务订阅 [CircuitBreaker.safeMode] 即可，
 *   本接口留出 `onTrip` 钩子让服务有机会立刻切换前台通知
 * - 第 5 步「高优先级通知」→ 阶段 5 实现（需要 `POST_NOTIFICATIONS`，真机当前为 `DENIED`）
 *
 * 阶段 4 的绑定是 `NoopSafeModeNotifier`（只记日志）。**刻意不做成可选参数**：
 * 那样调用点会散落 `?` 判断，将来漏接一处就变成"熔断了但用户毫无感知"。
 */
interface SafeModeNotifier {
    /** 熔断发生。 */
    fun onTrip(reason: TripReason)

    /** 恢复完成。 */
    fun onRestore()
}

/**
 * Bootloop 兜底的启动标记存储（需求 §5.3 第 4 条）。
 *
 * ## 为什么与 `BootMarkerStore` **不复用**（已批准的决定）
 * 两者语义不同、寿命不同：
 * | 存储 | 回答的问题 | 每次启动的行为 |
 * |---|---|---|
 * | `BootMarkerStore`（D9） | "距上次启动是否经历过重启" | **无条件覆写**为当前 `elapsed` |
 * | `CrashMarkerStore`（本端口） | "本次开机有几次异常启动 + 上次是否健康" | **读改写**（健康时置位，异常时计数） |
 *
 * D9 的"无条件覆写"会**抹掉**崩溃计数所需的"上次是否健康"，共用必然互相破坏。
 *
 * ## 实现用 SharedPreferences（而非 DataStore）
 * - `DataStore` 在基线里但**全项目零使用**，不凭空引入（与 D9 同款纪律）
 * - `SharedPreferences.edit().commit()` 是**同步**写：崩溃前也能落盘，
 *   这正是崩溃计数需要的性质（`apply()` 的异步写有丢窗口）
 */
interface CrashMarkerStore {
    /** 读上次启动留下的标记；无记录返回 `null`。 */
    fun readStartupMarker(): BootloopDecision.StartupMarker?

    /**
     * 写本次启动的标记：`healthy = false`（尚未证明健康）。
     *
     * 必须**在 `onCreate` 尽早调用**——它同时承担"标记本次启动进行中"的角色。
     */
    fun markStartupInProgress(bootId: Long)

    /** 置"本次启动已健康"（活过阈值后调用）。 */
    fun markHealthy(bootId: Long)

    /** 读累计崩溃次数（当前开机周期内）。 */
    fun readCrashCount(): Int

    /** 写累计崩溃次数。 */
    fun writeCrashCount(count: Int)

    /**
     * **只**清零崩溃计数，**保留**"本次启动进行中"的标记（阶段 5 修复，见下）。
     *
     * ## 为什么必须有它（真机暴露的缺陷：熔断后计数不归零）
     * `BootloopGuard.evaluateStartup()` 在**熔断那一刻**必须先写一次
     * `markStartupInProgress()` + `writeCrashCount(3)`（"先写再判"是它对崩溃的正确处置）。
     * 但若熔断之后**不清零**，那个 `3` 会**永久留在磁盘上**：
     * 下一次启动算出 `4` → `shouldTrip` 立即成立 → **每次启动都熔断一次**。
     * 真机现象正是"设备莫名其妙一直进安全模式，日志里只有一行 `BOOTLOOP_TRIPPED`"。
     *
     * ## 为什么不能直接用 [clear]
     * [clear] 会连 `startup_boot_id` / `startup_healthy` 一起删掉，
     * 于是"本次启动进行中"这一状态**不再存在于磁盘**——刚熔断完就崩的话，
     * 那次崩溃不会被下一次启动计入。只清计数则保留该状态，语义更准。
     */
    fun resetCrashCount()

    /** 清零（恢复安全模式时调用）。 */
    fun clear()
}
