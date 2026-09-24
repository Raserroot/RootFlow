package com.rootflow.domain.event

import com.rootflow.domain.model.SystemEvent

/**
 * 前台应用的原始快照（阶段 3c.2）。
 *
 * 由 `data/event/UsageStatsReader` 从 `UsageStatsManager` 翻译而来；本类型**不含任何
 * Android 类型**，因此状态机可在纯 JVM 下单测（决策 13 的同款纪律）。
 */
sealed interface ForegroundSnapshot {
    /** 查到了当前前台包名。 */
    data class Known(
        val packageName: String,
    ) : ForegroundSnapshot

    /**
     * 本轮**查不到**前台应用。
     *
     * 出现原因有多种（息屏前、系统繁忙、权限被撤销、厂商 ROM 限制），
     * **不能**等同于"退到后台"——见 [ForegroundDetector] 的判定表第 4 行。
     */
    data object Unknown : ForegroundSnapshot
}

/**
 * 状态机的一次输出（阶段 3c.2）。
 *
 * ## 为什么是两个可空字段而不是单个值
 * `A → B` 的切换在语义上是**两条边**：A 退到后台（需求 §2.1 的 `app_background`）
 * 与 B 进入前台（`app_foreground`）。用单个值表达会迫使调用方自行推断缺的那条边，
 * 而"推断"正是本项目反复出问题的地方（见 §7.1 的教训）。
 */
data class ForegroundTransition(
    val left: String?,
    val entered: String?,
) {
    /** 无任何边（首次快照、同包名、未知快照）。 */
    val isEmpty: Boolean
        get() = left == null && entered == null

    companion object {
        /** 空输出（避免每次分配）。 */
        val NONE: ForegroundTransition = ForegroundTransition(left = null, entered = null)
    }
}

/**
 * `app_foreground` / `app_background` 的**纯状态机**（阶段 3c.2，需求 §2.1 的"状态机推断"）。
 *
 * ## 判定表（= `ForegroundDetectorTest` 的期望表）
 *
 * | # | 上次 [currentPackage] | 本次快照 | `left` | `entered` | 依据 |
 * |---|---|---|---|---|---|
 * | 1 | `null`（首次） | `Known(A)` | — | — | **首次快照不上报**：App 启动时前台包必然已知，若无条件上报会让"打开 App"本身触发脚本 |
 * | 2 | `A` | `Known(A)` | — | — | 同包名不变（2s 一次轮询下这是绝大多数情况） |
 * | 3 | `A` | `Known(B)` | `A` | `B` | A→B：**先 Left 后 Entered**（顺序固定，两个事件语义不同） |
 * | 4 | `A` | `Unknown` | — | — | **不断言退到后台**，见下 |
 * | 5 | `null` | `Unknown` | — | — | 首次 + 未知 |
 * | 6 | `A`（`reset()` 后） | `Known(A)` | — | — | [reset] 后视作首次 |
 *
 * ## 为什么 `Unknown` 不上报 `left`（判定表第 4 行，勿改）
 * `UsageStatsManager.queryEvents` 在若干时刻会返回空：**息屏前、系统繁忙、权限被撤销、
 * 部分厂商 ROM 的限制**。把"查不到"当成"退到后台"会在真机上产生**假的后台事件**——
 * 而假事件会直接触发用户脚本（例如"退到后台就清理内存"的脚本被误触发）。
 *
 * 真正的"退到后台"由判定表第 3 行的 `left` 边覆盖：用户切走 App 时**一定**有新的前台包，
 * 因此不会漏报真正的后台切换。**代价**：若用户回到桌面（Launcher 本身也是包），
 * `left` 会是"上一个 App"、`entered` 是 Launcher —— 这正是需求要的语义。
 *
 * ## 线程安全
 * [accept] 由轮询协程调用，[reset] 由 `stop()` / 息屏事件调用，**可能来自不同线程**。
 * 因此状态由 [lock] 保护：这里是 check-then-act（"读过再写"），
 * `@Volatile` 只保证可见性、不保证复合操作原子性（3c.1 的 `WifiEventSource` 同款教训）。
 */
class ForegroundDetector {
    private val lock = Any()

    private var lastPackage: String? = null

    /** 当前记录的前台包名；`null` 表示尚未建立基线。 */
    val currentPackage: String?
        get() = synchronized(lock) { lastPackage }

    /**
     * 吃进一份快照，产出需要上报的边。
     *
     * @return 无变化时返回 [ForegroundTransition.NONE]
     */
    fun accept(snapshot: ForegroundSnapshot): ForegroundTransition {
        synchronized(lock) {
            when (snapshot) {
                is ForegroundSnapshot.Known -> {
                    val previous = lastPackage
                    return when (previous) {
                        // 判定表 1：首次不上报，只建立基线
                        null -> {
                            lastPackage = snapshot.packageName
                            ForegroundTransition.NONE
                        }

                        // 判定表 2：同包名，无变化
                        snapshot.packageName -> ForegroundTransition.NONE

                        // 判定表 3：A→B，两条边，顺序固定（left 在前）
                        else -> {
                            lastPackage = snapshot.packageName
                            ForegroundTransition(left = previous, entered = snapshot.packageName)
                        }
                    }
                }

                // 判定表 4/5：未知不上报、也不改变基线
                // （保持基线的好处：息屏前后若是同一个 App，恢复轮询时不会误报一对 Left/Entered）
                ForegroundSnapshot.Unknown -> return ForegroundTransition.NONE
            }
        }
    }

    /**
     * 清除基线，使下一次 [accept] 重新走"首次不上报"。
     *
     * 调用时机：`stop()`、以及**息屏导致暂停轮询**时（用户可能在息屏期间换过 App，
     * 恢复后直接报一对 Left/Entered 反而是噪声）。
     */
    fun reset() {
        synchronized(lock) {
            lastPackage = null
        }
    }

    /** 把一次转变翻译成要发的事件（顺序：先 `app_background` 再 `app_foreground`）。 */
    fun toEvents(transition: ForegroundTransition): List<SystemEvent> {
        val events = mutableListOf<SystemEvent>()
        transition.left?.let { events += SystemEvent.AppBackground(packageName = it) }
        transition.entered?.let { events += SystemEvent.AppForeground(packageName = it) }
        return events
    }
}
