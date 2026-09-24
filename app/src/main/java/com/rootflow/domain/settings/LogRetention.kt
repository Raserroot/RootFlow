package com.rootflow.domain.settings

/**
 * 运行历史 / 日志的**保留策略**（阶段 6d，需求 §3.2「按设置保留 N 天」）。
 *
 * ## 需求口径澄清（**必须写进实现文档**）
 * 需求 §3.2 写的是 `logs/<id>/<timestamp>.log`（可选**文件日志**）"按设置保留 N 天"。
 * 但那份文件日志**自阶段 3a 起就未实现**（`PROJECT_STATE.md` 已登记），运行历史一直落在
 * Room 的 `runs` / `run_log_entries` 上。因此「保留天数」在本阶段**作用于 Room 运行历史**：
 * 清理 = 删除 `started_at` 早于 cutoff 的运行行，其日志行由 `ON DELETE CASCADE` 一并删除。
 *
 * ## 为什么档位是枚举式的（3/7/14/30）而不是自由输入
 * 这是**用户可见的磁盘占用承诺**：一个任意整数输入框会让"保留 3650 天"这类选择
 * 悄悄吃掉用户的存储，而 UI 上没有任何提示。固定档位让每个选项都能一句话说清代价。
 *
 * ## 为什么非法值回落默认而不是抛
 * 设置里存的是**磁盘上的历史数据**：旧版本可能写入过已下线的档位（或文件被改成垃圾）。
 * 抛出会让"设置页打不开 + 清理路径不可用"，比按默认值继续工作糟得多
 * （与 `ThemeMode.fromKey` 的回落同款纪律）。
 */
object LogRetention {
    /** 默认保留天数（需求未指定 ⇒ 取一个"能回看一周"的常规值）。 */
    const val DEFAULT_DAYS: Int = 7

    /** 可选档位（UI 顺序即此顺序）。 */
    val ALLOWED_DAYS: List<Int> = listOf(3, 7, 14, 30)

    /** 一天的毫秒数（保留策略的唯一时间单位）。 */
    const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /**
     * 把任意输入收敛到合法档位。
     *
     * @return [ALLOWED_DAYS] 中的值；`null` / 不在档位内 ⇒ [DEFAULT_DAYS]
     */
    fun sanitize(days: Int?): Int = if (days != null && days in ALLOWED_DAYS) days else DEFAULT_DAYS

    /** UI 文案（`3 天` / `7 天` …）。 */
    fun label(days: Int): String = "${sanitize(days)} 天"

    /**
     * 清理截止时间戳（早于此值的运行会被删除）。
     *
     * @param nowMillis 当前墙钟（`System.currentTimeMillis()`；由调用方传入以便单测）
     * @return 若 [days] 极大也不会回绕成负数（`coerceAtLeast(0)`）
     */
    fun cutoffMillis(
        nowMillis: Long,
        days: Int,
    ): Long = (nowMillis - sanitize(days) * MILLIS_PER_DAY).coerceAtLeast(0L)
}
