package com.rootflow.domain.event

/**
 * 熔断判定的**纯状态机**（需求 §5.1 的五条自动条件；第 6 条"手动"无需状态）。
 *
 * ## 为什么是纯类而不是 Service
 * 五个阈值（连续 3 / 风暴 20 in 10min / 高频 20 in 60s / 超时 60s / root 10s）是**判定核心**，
 * 必须能在纯 JVM 下逐边界钉死。把它做成不可注入的状态机后：
 * - 单测不需要 Room、不需要 Android、不需要协程调度
 * - `CircuitBreakerImpl` 只负责"动作编排"（写 flag / 杀进程 / 禁触发器）
 *
 * ## 时间来源
 * 所有方法接收 `nowMillis`（单调时钟读数），**不在内部取时**——因此窗口边界可精确断言。
 *
 * ## 三条计数器的语义（勿混）
 * | 计数器 | 作用域 | 重置时机 |
 * |---|---|---|
 * | [consecutiveFailures] | 每脚本 | **成功即清零**（这正是"连续"的含义） |
 * | [recentFailures] | 全局滚动窗口 | 滑出窗口即失效（不显式清零） |
 * | [recentStarts] | 每脚本滚动窗口 | 同上 |
 *
 * ## 失败风暴的跨进程语义（重要）
 * 进程重启后内存归零会**让熔断被绕过**，因此 [seedFailureHistory] 允许启动时从
 * `RunHistory`（Room）灌入窗口内的历史失败时刻。调用方必须在**任何判定之前**调用它。
 * 其余两个计数器**故意不持久化**——"连续"/"窗口内"的定义本就以本次运行为准，
 * 持久化只会制造假状态（与 `RunAdmissionGate` 的决策 C 同款理由）。
 *
 * @param maxConsecutiveFailures 需求 §5.1：同一脚本连续失败 ≥ 3
 * @param failureStormThreshold 需求 §5.1：10 分钟内累计失败 ≥ 20
 * @param failureStormWindowMillis 需求 §5.1：10 分钟
 * @param highFrequencyThreshold 需求 §5.1：同一脚本 60s 内被触发 ≥ 20 次
 * @param highFrequencyWindowMillis 需求 §5.1：60 秒
 */
class CircuitBreakerMachine(
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    private val failureStormThreshold: Int = DEFAULT_FAILURE_STORM_THRESHOLD,
    private val failureStormWindowMillis: Long = DEFAULT_FAILURE_STORM_WINDOW_MILLIS,
    private val highFrequencyThreshold: Int = DEFAULT_HIGH_FREQUENCY_THRESHOLD,
    private val highFrequencyWindowMillis: Long = DEFAULT_HIGH_FREQUENCY_WINDOW_MILLIS,
) {
    private val lock = Any()

    /** 每脚本的连续失败次数；成功即移除该键。 */
    private val consecutiveFailures = mutableMapOf<Long, Int>()

    /** 全局失败时刻（滚动窗口；只保留窗口内的）。 */
    private val recentFailures = ArrayDeque<Long>()

    /** 每脚本的"受理"时刻（滚动窗口；只保留窗口内的）。 */
    private val recentStarts = mutableMapOf<Long, ArrayDeque<Long>>()

    /**
     * 记录一次**受理**（"高频自启"的数据来源）。
     *
     * 计数点是"脚本真的被启动"，而不是"事件到达"——防抖与重入拒绝已经挡掉了
     * 一部分重复，把被拒绝的也算进来会让阈值失去意义。
     *
     * @param nowMillis 单调时钟读数
     * @return 命中判定时返回原因，否则 `null`
     */
    fun onRunAccepted(
        scriptId: Long,
        nowMillis: Long,
    ): TripReason? =
        synchronized(lock) {
            val starts = recentStarts.getOrPut(scriptId) { ArrayDeque() }
            starts.addLast(nowMillis)
            prune(starts, nowMillis, highFrequencyWindowMillis)

            if (starts.size >= highFrequencyThreshold) {
                TripReason.HighFrequencyStarts(
                    scriptId = scriptId,
                    startsInWindow = starts.size,
                    windowMillis = highFrequencyWindowMillis,
                )
            } else {
                null
            }
        }

    /**
     * 记录一次运行结局。
     *
     * @param failed `true` 表示本次运行失败（非 0 退出码 / 被超时终止）
     * @return 命中判定时返回原因，否则 `null`
     */
    fun onRunFinished(
        scriptId: Long,
        failed: Boolean,
        nowMillis: Long,
    ): TripReason? =
        synchronized(lock) {
            if (!failed) {
                // 成功即清零"连续失败"——这是"连续"二字的全部含义
                consecutiveFailures.remove(scriptId)
                return@synchronized null
            }

            val consecutive = (consecutiveFailures[scriptId] ?: 0) + 1
            consecutiveFailures[scriptId] = consecutive

            recentFailures.addLast(nowMillis)
            prune(recentFailures, nowMillis, failureStormWindowMillis)

            when {
                consecutive >= maxConsecutiveFailures ->
                    TripReason.ConsecutiveFailures(scriptId = scriptId, consecutive = consecutive)

                recentFailures.size >= failureStormThreshold ->
                    TripReason.FailureStorm(
                        windowFailures = recentFailures.size,
                        windowMillis = failureStormWindowMillis,
                    )

                else -> null
            }
        }

    /**
     * 脚本超时（看门狗到达阈值时调用）。
     *
     * 不依赖计数器：是否超时由**调用方的看门狗**判定（它掌握运行起点与 `timeoutSec`），
     * 本方法只负责产出原因。
     */
    fun onRunTimeout(
        scriptId: Long,
        timeoutMillis: Long,
    ): TripReason = TripReason.ScriptTimeout(scriptId = scriptId, timeoutMillis = timeoutMillis)

    /** Root 探针超时/失败。 */
    fun onRootUnresponsive(elapsedMillis: Long): TripReason = TripReason.RootUnresponsive(elapsedMillis = elapsedMillis)

    /** 手动触发。 */
    fun onManual(): TripReason = TripReason.Manual

    /**
     * 用历史失败时刻**预置**风暴窗口（跨进程恢复）。
     *
     * @param failureTimestamps 窗口内的失败时刻（单调时钟读数）
     * @param nowMillis 当前单调时钟读数；超出窗口的会被丢弃
     */
    fun seedFailureHistory(
        failureTimestamps: Collection<Long>,
        nowMillis: Long,
    ) {
        synchronized(lock) {
            recentFailures.clear()
            failureTimestamps.sorted().forEach { at ->
                if (nowMillis - at < failureStormWindowMillis) {
                    recentFailures.addLast(at)
                }
            }
        }
    }

    /** 已累计的连续失败次数（真机判读 + 单测断言用）。 */
    fun consecutiveFailuresOf(scriptId: Long): Int = synchronized(lock) { consecutiveFailures[scriptId] ?: 0 }

    /** 当前风暴窗口内的失败数（真机判读 + 单测断言用）。 */
    fun failuresInWindow(): Int = synchronized(lock) { recentFailures.size }

    /** 全部清零（恢复安全模式后调用：不应带着熔断期的计数继续跑）。 */
    fun reset() {
        synchronized(lock) {
            consecutiveFailures.clear()
            recentFailures.clear()
            recentStarts.clear()
        }
    }

    /** 丢弃窗口外的旧记录（[deque] 内的时刻单调递增，因此从头丢即可）。 */
    private fun prune(
        deque: ArrayDeque<Long>,
        nowMillis: Long,
        windowMillis: Long,
    ) {
        while (deque.isNotEmpty() && nowMillis - deque.first() >= windowMillis) {
            deque.removeFirst()
        }
    }

    companion object {
        /** 需求 §5.1：同一脚本连续失败 ≥ 3。 */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES: Int = 3

        /** 需求 §5.1：10 分钟内累计失败 ≥ 20。 */
        const val DEFAULT_FAILURE_STORM_THRESHOLD: Int = 20

        /** 需求 §5.1：10 分钟。 */
        const val DEFAULT_FAILURE_STORM_WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /** 需求 §5.1：同一脚本 60s 内被触发 ≥ 20 次。 */
        const val DEFAULT_HIGH_FREQUENCY_THRESHOLD: Int = 20

        /** 需求 §5.1：60 秒。 */
        const val DEFAULT_HIGH_FREQUENCY_WINDOW_MILLIS: Long = 60 * 1000L
    }
}
