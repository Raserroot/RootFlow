package com.rootflow.data.log

import com.rootflow.domain.model.LogEntry

/**
 * 单次运行的日志环形缓冲（定容，溢出淘汰最旧）。
 *
 * ## 为什么用环形而不是 `ArrayDeque`
 * 日志是"只关心最近 N 条"的典型场景：容量固定、追加 O(1)、**无需搬移元素**。
 * 用 `ArrayDeque` 在超限时逐个 `removeFirst()` 也能工作，但每次都要维护下标；
 * 环形缓冲把"淘汰"变成覆盖写，语义更直白，且 `dropped` 计数天然可累加。
 *
 * ## 线程安全
 * 写入来自日志收集协程，读取来自 [tail] 调用方（可能是 UI 线程），
 * 因此全部方法用 `synchronized` 保护。实现内部**不自建协程**、不做 IO。
 *
 * @param capacity 容量（条目数）；必须为正
 */
internal class RunLogRing(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "RunLogRing capacity must be positive, was $capacity" }
    }

    private val buffer = arrayOfNulls<LogEntry>(capacity)
    private val lock = Any()

    /** 下一个写入位置（= 最旧元素的位置，当且仅当已满）。 */
    private var head = 0

    /** 当前元素个数（≤ [capacity]）。 */
    private var count = 0

    /** 因容量上限被淘汰的累计条目数。 */
    private var dropped = 0L

    /** 追加一条；缓冲已满时覆盖最旧的一条并累加 [droppedCount]。 */
    fun append(entry: LogEntry) {
        synchronized(lock) {
            buffer[head] = entry
            head = (head + 1) % capacity
            if (count < capacity) {
                count++
            } else {
                dropped++
            }
        }
    }

    /**
     * 按时间顺序返回现存条目。
     *
     * @param limit 最多返回多少条（取**最新**的 limit 条）；`limit <= 0` 表示不限
     */
    fun snapshot(limit: Int = 0): List<LogEntry> =
        synchronized(lock) {
            if (count == 0) return@synchronized emptyList()

            val take =
                when {
                    limit <= 0 -> count
                    limit >= count -> count
                    else -> limit
                }
            val start = (head - take + capacity) % capacity
            List(take) { offset -> buffer[(start + offset) % capacity]!! }
        }

    /** 因容量上限被淘汰的累计条目数。 */
    fun droppedCount(): Long = synchronized(lock) { dropped }

    /** 当前条目数。 */
    fun size(): Int = synchronized(lock) { count }
}
