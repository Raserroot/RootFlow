package com.rootflow.domain.event

/**
 * 「一直运行」的重启节奏（**纯逻辑，可单测**）。
 *
 * ## 为什么节奏要单独抽出来
 * "脚本退出后隔多久重启、重试几次后放弃"是**可以被穷举断言的判定**。
 * 它一旦出错，表现是"紧密重启把 CPU 打满"或"一个坏脚本把服务拖死"——
 * 两种都只在真机上、且要等一会儿才看得出来。抽成纯逻辑后，
 * 边界（第 4 次 / 第 5 次、间隔是否递增、是否封顶）能在毫秒级单测里钉死。
 *
 * ## 与熔断的关系（**用户 2026-09-20 的裁定**）
 * 常驻脚本的失败**不触发全局熔断**。理由：现有规则是"同一脚本连续失败 3 次
 * ⇒ 进安全模式、停全部触发器"，而常驻脚本"退出即重启"会**反复失败** ——
 * 照搬那条会让一个写错的常驻脚本把整个 App 冻住。
 * 取而代之的是本类的**局部收敛**：连续快速崩够 [maxRapidCrashes] 次就
 * **只停掉它自己**，并在日志与通知里说清原因。
 */
data class DaemonRestartPolicy(
    /** 首次退避时长。 */
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    /** 退避上限（指数增长必须封顶）。 */
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    /** 存活时长低于它 ⇒ 算"快速崩"。 */
    val rapidExitThresholdMillis: Long = DEFAULT_RAPID_EXIT_THRESHOLD_MILLIS,
    /** 连续快速崩到这一次就放弃重启。 */
    val maxRapidCrashes: Int = DEFAULT_MAX_RAPID_CRASHES,
) {
    init {
        require(baseDelayMillis > 0) { "baseDelayMillis 必须为正：0 会退化成紧密重启" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis 不得小于 baseDelayMillis" }
        require(rapidExitThresholdMillis >= 0) { "rapidExitThresholdMillis 不得为负" }
        require(maxRapidCrashes >= 1) { "maxRapidCrashes 至少为 1" }
    }

    /**
     * 本次存活是否算"稳住了"。
     *
     * ## 为什么要清零
     * 一个脚本可能"崩 3 次 → 修好 → 又崩 3 次"。计数表达的是**连续**快速崩，
     * 活过宽限期即证明它至少能跑起来，因此调用方应把计数归零。
     */
    fun isStable(runMillis: Long): Boolean = runMillis >= rapidExitThresholdMillis

    /** 连续快速崩已达上限 ⇒ 放弃重启（只停掉它自己，不触发全局熔断）。 */
    fun shouldGiveUp(rapidCrashesSoFar: Int): Boolean = rapidCrashesSoFar >= maxRapidCrashes

    /**
     * 下次重启前的等待时长；`null` = **放弃，不再重启**。
     *
     * @param rapidCrashesSoFar **包含本次在内**的连续快速崩次数
     */
    fun nextDelayMillis(rapidCrashesSoFar: Int): Long? {
        if (shouldGiveUp(rapidCrashesSoFar)) return null
        // 指数退避：第 1 次崩等 base，第 2 次等 2×base…每一步都先与上限比较，
        // 避免大指数下的乘法溢出（`Long` 溢出会变成负数 ⇒ 变成"立刻重启"）。
        var delay = baseDelayMillis
        repeat((rapidCrashesSoFar - 1).coerceAtLeast(0)) {
            delay = if (delay > maxDelayMillis / 2) maxDelayMillis else delay * 2
        }
        return delay.coerceAtMost(maxDelayMillis)
    }

    companion object {
        /**
         * 首次退避 1 秒。
         *
         * 1 秒足够让"刚崩的进程"把资源还回去，又短到用户感觉不出中断；
         * 更短（如 100ms）在真机上会看到明显的 CPU 尖峰。
         */
        const val DEFAULT_BASE_DELAY_MILLIS: Long = 1_000L

        /**
         * 退避上限 30 秒。
         *
         * ## 为什么必须封顶
         * 指数增长下第 10 次重试是 1s × 2⁹ ≈ 8.5 分钟 —— 那已经等于"悄悄不跑了"，
         * 而用户还以为它在跑。30 秒是"明显在重试、但不烧 CPU"的量级。
         */
        const val DEFAULT_MAX_DELAY_MILLIS: Long = 30_000L

        /**
         * 活不过 **5 秒**算"快速崩"。
         *
         * ## 为什么不是 0、也不是 60 秒
         * - **0**（退出即算崩）会把"正常跑完一轮就退出"的脚本误判成崩溃。但
         *   「一直运行」的语义本来就要求它一直活着，所以"活着"的判据必须给宽限期。
         * - **60s** 太宽：语法错的脚本每次都在 1 秒内挂掉，要等 60 秒才算一次，
         *   5 次就是 5 分钟才发现问题。
         *
         * 5 秒是"能启动、能加载解释器、能做点事"的下限。
         */
        const val DEFAULT_RAPID_EXIT_THRESHOLD_MILLIS: Long = 5_000L

        /** 连续快速崩 5 次后放弃（用户 2026-09-20 裁定）。 */
        const val DEFAULT_MAX_RAPID_CRASHES: Int = 5

        /** 默认策略实例（生产与单测共用的那一份）。 */
        val DEFAULT: DaemonRestartPolicy = DaemonRestartPolicy()
    }
}
