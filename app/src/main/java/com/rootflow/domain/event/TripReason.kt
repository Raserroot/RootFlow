package com.rootflow.domain.event

/**
 * 熔断原因（需求 §5.1 的六条触发条件）。
 *
 * ## 为什么是密封类而不是枚举
 * 部分原因天然带证据（哪个脚本、多少毫秒），需要一并落进 `safemode.log` 供事后追责；
 * 枚举 + 旁挂字段会退化成"永远有一半字段是 null"（与 `SystemEvent` 同一取舍）。
 *
 * ## `reasonKey` 必须是稳定字符串
 * 它会被写进 `safemode.log` 与真机日志，**不能用 `toString()`**（类名重构即漂移），
 * 也不该用序号。字面量由单测逐值钉死。
 */
sealed interface TripReason {
    /** 稳定字符串键（写入 `safemode.log` 与 logcat）。 */
    val reasonKey: String

    /** 供人阅读的具体说明（含阈值与实测值，便于真机判读）。 */
    val detail: String

    /**
     * 脚本运行超时（需求 §5.1 第 1 条）。
     *
     * @param scriptId 超时的脚本
     * @param timeoutMillis 生效的超时阈值（脚本自身 `timeoutSec` 或全局默认 60s）
     */
    data class ScriptTimeout(
        val scriptId: Long,
        val timeoutMillis: Long,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "script $scriptId exceeded timeout ${timeoutMillis}ms"

        companion object {
            const val KEY: String = "script-timeout"
        }
    }

    /**
     * 同一脚本连续失败（需求 §5.1 第 2 条：连续失败 ≥ 3）。
     *
     * @param scriptId 连续失败的脚本
     * @param consecutive 达到的连续失败次数
     */
    data class ConsecutiveFailures(
        val scriptId: Long,
        val consecutive: Int,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "script $scriptId failed $consecutive times consecutively"

        companion object {
            const val KEY: String = "consecutive-failures"
        }
    }

    /**
     * 全局失败风暴（需求 §5.1 第 3 条：10 分钟内累计失败 ≥ 20）。
     *
     * @param windowFailures 窗口内的失败总数
     * @param windowMillis 窗口长度
     */
    data class FailureStorm(
        val windowFailures: Int,
        val windowMillis: Long,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "$windowFailures failures within ${windowMillis}ms"

        companion object {
            const val KEY: String = "failure-storm"
        }
    }

    /**
     * Root shell 无响应（需求 §5.1 第 4 条：`su -c echo` 超过 10s 未返回）。
     *
     * @param elapsedMillis 实测耗时（超时的下限值）
     */
    data class RootUnresponsive(
        val elapsedMillis: Long,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "root shell did not respond within ${elapsedMillis}ms"

        companion object {
            const val KEY: String = "root-unresponsive"
        }
    }

    /**
     * 高频自启（需求 §5.1 第 5 条：同一脚本 60s 内被触发 ≥ 20 次）。
     *
     * @param startsInWindow 窗口内的触发次数
     * @param windowMillis 窗口长度
     */
    data class HighFrequencyStarts(
        val scriptId: Long,
        val startsInWindow: Int,
        val windowMillis: Long,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "script $scriptId started $startsInWindow times within ${windowMillis}ms"

        companion object {
            const val KEY: String = "high-frequency"
        }
    }

    /** 手动触发（需求 §5.1 第 6 条：用户点击「立即熔断」）。 */
    data object Manual : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "manually triggered"

        const val KEY: String = "manual"
    }

    /**
     * Bootloop 兜底（需求 §5.3：连续 3 次 60s 内崩溃）。
     *
     * 需求把它列在**恢复路径**里，但它的动作与其它触发条件完全一致（写 flag 进安全模式），
     * 因此统一建模为一条触发原因，由 `BootloopGuard` 产出。
     */
    data class Bootloop(
        val crashes: Int,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "detected $crashes short-lived startups in this boot cycle"

        companion object {
            const val KEY: String = "bootloop"
        }
    }

    /**
     * 开机安全模式联动（需求 §5.3 第 2 条：检测 `persist.sys.safemode` 或 Magisk safemode）。
     *
     * ## 与其它原因的区别：它**不是**本应用熔断的结果
     * 而是"设备/其它 Root 方案已处于安全模式" ⇒ 本应用**跟随**进入安全模式。
     * 因此动作上有一处刻意的差别：**不写 `safemode.flag`**
     * （flag 是"本应用熔断过"的证据；跟随外部状态去写它，会让外部状态解除后本应用
     * 仍然"以为自己熔断过"，只能人工清 flag）。
     *
     * @param source 命中来源（`persist.sys.safemode` / `magisk-safemode`）
     */
    data class ExternalSafeMode(
        val source: String,
    ) : TripReason {
        override val reasonKey: String = KEY
        override val detail: String = "external safe mode detected via $source"

        companion object {
            const val KEY: String = "external-safe-mode"
        }
    }

    companion object {
        /** 全部稳定键（单测逐值钉死，也供文档引用）。 */
        val ALL_KEYS: List<String> =
            listOf(
                ScriptTimeout.KEY,
                ConsecutiveFailures.KEY,
                FailureStorm.KEY,
                RootUnresponsive.KEY,
                HighFrequencyStarts.KEY,
                Manual.KEY,
                Bootloop.KEY,
                ExternalSafeMode.KEY,
            )

        /**
         * 由稳定键还原成因（阶段 4）。
         *
         * ## 为什么需要它
         * 熔断时 [reasonKey] 会被写进 `safemode.flag`。进程重启后
         * `CircuitBreakerImpl.restoreStateFromDisk()` 能看出"处于安全模式"，
         * 但**若不把成因读回来**，阶段 6 的 banner 与真机判读就拿不到"当初为什么熔断"——
         * 而恢复路径的排查完全依赖这个信息（超时 / 连续失败 / 手动 / bootloop 的处置不同）。
         *
         * ⇒ 不读回来等于**每次重启都丢失熔断原因**，这是真实的信息损失。
         *
         * ## 为什么只对"无参"成因做精确还原
         * 带参数的成因（如 `ConsecutiveFailures(scriptId, consecutive)`）在 flag 里只留了
         * 原因键与一行人类可读的 `detail`。**不为它们伪造参数**：那会造出一个
         * "看起来有证据、实际是编的"对象，比返回 `null` 更糟。因此：
         * - 无参成因（[Manual] / [ExternalSafeMode]）→ 完整还原（`source` 从 `detail` 解析失败时留空）
         * - 带参成因 → 返回 `null`，由调用方按"成因未知但确在安全模式"处理
         *
         * @param key [reasonKey] 的字面量
         * @param detail flag 里的 `detail=` 一行（可选，仅供 [ExternalSafeMode.source] 还原）
         * @return 能安全还原的成因；否则 `null`
         */
        fun fromKey(
            key: String,
            detail: String? = null,
        ): TripReason? =
            when (key) {
                Manual.KEY -> Manual
                ExternalSafeMode.KEY -> ExternalSafeMode(source = detail?.substringAfter("via ", "") ?: "")
                else -> null
            }
    }
}
