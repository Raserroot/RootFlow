package com.rootflow.data.event

import com.rootflow.domain.event.BootloopDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BootloopGuard] 单测（需求 §5.3 第 4 条 + 已批准的反向标记修正）。
 *
 * ## 本类钉死的语义（三条，都是"错了会误熔断"的地方）
 * 1. **首启不计崩溃**：没有任何历史标记时必须"只记一次启动"，否则新装 App 一启动就离熔断近一步
 * 2. **健康阈值内活下来就清零**：这是"正常开关 App 不会被误判"的唯一保障
 * 3. **跨开机周期归零**：`elapsedRealtime` 回退意味着设备重启过，旧的崩溃计数与本次无关
 *
 * 时间全部用 `advanceTimeBy` 驱动虚拟时钟（`AGENT_PROTOCOL.md §9`：
 * 中途观测用 `runCurrent()`，**不**用 `advanceUntilIdle()` —— 后者会把 60s 的
 * 健康标记 `delay` 也推完，使"尚未置健康"这一中间态无法观测）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BootloopGuardTest {
    init {
        CircuitBreakerFixture.installAndroidLogStubs()
    }

    // ------------------------------------------------ 首启与计数处置

    @Test
    fun `the very first start does not count as a crash`() =
        runTest {
            val store = InMemoryCrashMarkerStore()
            val guard = newGuard(store = store, elapsed = 10_000L)

            val tripped = guard.evaluateStartup()

            assertFalse(tripped, "无历史标记时不得熔断")
            assertEquals(0, guard.crashCount, "首启不得计崩溃（否则新装 App 一启动就接近熔断）")
            assertEquals(0, store.readCrashCount())
        }

    @Test
    fun `a previous unhealthy start increments the crash count`() =
        runTest {
            // 上次启动留下"healthy=false 且同周期" ⇒ 上次没活到阈值 ⇒ 计一次崩溃
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 0)
            val guard = newGuard(store = store, elapsed = 5_000L)

            guard.evaluateStartup()

            assertEquals(1, guard.crashCount, "上次没活到健康阈值必须计一次崩溃")
            assertEquals(1, store.readCrashCount(), "计数必须落盘（跨进程恢复靠它）")
        }

    @Test
    fun `a previous healthy start keeps the count unchanged`() =
        runTest {
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = true, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)

            guard.evaluateStartup()

            assertEquals(2, guard.crashCount, "上次活到了阈值 ⇒ 正常结束 ⇒ 计数不变")
        }

    @Test
    fun `a healthy start does not clear the count by itself`() =
        runTest {
            // 契约：healthy 只是"不 +1"，清零要靠"跨周期"或人工恢复。
            // 若实现改成"健康即清零"，那么"崩-崩-正常-崩-崩-崩"这种模式永远攒不到 3。
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = true, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)

            guard.evaluateStartup()

            assertEquals(2, store.readCrashCount(), "健康的启动只是不计数，不清零")
        }

    @Test
    fun `a new boot cycle resets the count`() =
        runTest {
            // `elapsedRealtime` 在重启时归零 ⇒ 标记的 bootId 大于当前读数 = 上周期
            val store = InMemoryCrashMarkerStore(bootId = 999_000L, healthy = false, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 1_000L)

            guard.evaluateStartup()

            assertEquals(0, guard.crashCount, "跨开机周期必须归零重来（旧计数与本次无关）")
            assertEquals(0, store.readCrashCount())
        }

    // ------------------------------------------------ 熔断

    @Test
    fun `reaching the threshold trips exactly once`() =
        runTest {
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)

            assertTrue(guard.evaluateStartup(), "达到阈值必须返回 true（调用方据此执行熔断动作）")
            assertTrue(guard.tripped)
            // ★ 阶段 5 修复后：熔断那一刻就把计数清掉，因此内存里的读数回到 0。
            //   熔断**当时**的计数由 `BOOTLOOP_TRIPPED crashes=3` 与
            //   `TripReason.Bootloop(crashes)` 承载（见下方 `the trip reason carries the crash count`），
            //   不再依赖熔断**之后**的 `crashCount` 读数——那正是真机缺陷的来源。
            assertEquals(0, guard.crashCount, "熔断后必须已清零（否则下次启动立即再熔断）")
        }

    @Test
    fun `a repeated evaluate does not trip twice`() =
        runTest {
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val trips = mutableListOf<com.rootflow.domain.event.TripReason>()
            val guard =
                BootloopGuard(
                    store = store,
                    onTrip = { reason -> trips += reason },
                    scope = backgroundScope,
                    elapsedRealtime = { 5_000L },
                    healthThresholdMillis = null,
                )

            assertTrue(guard.evaluateStartup())
            // 修复后第二次 evaluate 的语义变了：计数已在熔断时清零，
            // 因此本次算出 1、不再达到阈值 ⇒ 返回 false。
            // 幂等性因此**更强**了：不再依赖 `tripped` 内存标志，而是状态本身已回到安全区
            // （`tripped` 仍保留为同进程内的兜底，防止未来有人在清零前插入别的调用）。
            assertFalse(guard.evaluateStartup(), "熔断并清零后再评估不得再次熔断")

            assertEquals(1, trips.size, "熔断动作只允许触发一次（重复写入会覆盖原始状态证据）")
        }

    @Test
    fun `the trip reason carries the crash count`() =
        runTest {
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val reasons = mutableListOf<com.rootflow.domain.event.TripReason>()
            val guard =
                BootloopGuard(
                    store = store,
                    onTrip = { reason -> reasons += reason },
                    scope = this,
                    elapsedRealtime = { 5_000L },
                    healthThresholdMillis = null,
                )

            guard.evaluateStartup()

            val bootloop =
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                    com.rootflow.domain.event.TripReason.Bootloop::class.java,
                    reasons.single(),
                )
            assertEquals(3, bootloop.crashes, "原因必须带崩溃次数（真机判读要能看出证据）")
        }

    @Test
    fun `a null onTrip hook does not swallow the trip`() =
        runTest {
            // `onTrip` 可空（避免 CircuitBreakerImpl ↔ BootloopGuard 的构造环）。
            // 关键：钩子缺失**不得**让熔断被静默跳过——返回值仍为 true，由调用方驱动动作。
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val guard = newGuardWithoutHook(store = store, elapsed = 5_000L)
            assertTrue(guard.evaluateStartup(), "没有钩子时返回值必须仍为 true")
        }

    // ------------------------------------------------ 健康标记

    @Test
    fun `the startup marker is written before anything else can fail`() =
        runTest {
            // "本次启动进行中"必须在判定后**立即**落盘：否则这次启动崩了，
            // 下次启动读到的是更早的标记，可能整轮漏计。
            val store = InMemoryCrashMarkerStore()
            val guard = newGuard(store = store, elapsed = 42L)

            guard.evaluateStartup()

            assertEquals(42L, store.recordedBootId(), "必须写下本次启动的标识")
            assertEquals(false, store.isHealthy(), "刚启动时尚未证明健康 ⇒ healthy=false")
        }

    @Test
    fun `surviving the health threshold marks the start as healthy`() =
        runTest {
            val store = InMemoryCrashMarkerStore()
            val guard = newGuard(store = store, elapsed = 42L)

            guard.evaluateStartup()
            assertEquals(false, store.isHealthy(), "阈值之前不得置健康")

            advanceTimeBy(BootloopGuard.DEFAULT_HEALTH_THRESHOLD_MILLIS + 1)
            runCurrent()

            assertEquals(true, store.isHealthy(), "活过健康阈值必须置 healthy=true（下次启动据此清零）")
            assertEquals(42L, store.recordedBootId())
        }

    @Test
    fun `a tripped guard does not schedule a health mark`() =
        runTest {
            // 已熔断就不该再置"健康"：那会让下次启动读到 healthy=true 而把计数清零，
            // 于是"反复崩溃"的设备每次开机都能把证据抹掉。
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)

            guard.evaluateStartup()
            advanceTimeBy(BootloopGuard.DEFAULT_HEALTH_THRESHOLD_MILLIS + 1)
            runCurrent()

            assertFalse(store.isHealthy() ?: false, "熔断后不得置健康标记")
        }

    // ------------------------------------------------ 熔断后清零（阶段 5 真机缺陷的回归护栏）

    @Test
    fun `tripping clears the persisted crash count but keeps the startup marker`() =
        runTest {
            // ★ 真机缺陷（批 2 第 4 项）：熔断后计数留在磁盘上 ⇒ 下次启动算出 4 ⇒
            //   shouldTrip 立即成立 ⇒ **每次启动都熔断一次**（表现为"设备一直进安全模式"）。
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)

            assertTrue(guard.evaluateStartup(), "计数 2 + 上次不健康 ⇒ 本次应为 3 并熔断")

            assertEquals(0, store.readCrashCount(), "熔断后磁盘上的计数必须归零（否则每次启动都会再熔断）")
            assertEquals(0, guard.crashCount, "内存计数必须同步归零，否则日志与判读都会说谎")
            // 启动标记必须**保留**：刚熔断就崩的那一次仍要被"下一次启动"计入
            assertEquals(5_000L, store.recordedBootId(), "不得连启动标记一起清掉")
            assertEquals(false, store.isHealthy(), "熔断时不得置健康")
        }

    @Test
    fun `counts carry over across guards while markers survive a trip`() =
        runTest {
            val store = InMemoryCrashMarkerStore()
            var boot = 10_000L

            repeat(BootloopDecision.DEFAULT_CRASH_THRESHOLD) { index ->
                val guard = newGuard(store = store, elapsed = boot)
                assertFalse(guard.evaluateStartup(), "第 ${index + 1} 次启动不应熔断（阈值是 3）")
                assertEquals(index, guard.crashCount, "跨 guard 的计数必须连续累加，否则永远到不了阈值")
                boot += 1_000L
            }

            val tripping = newGuard(store = store, elapsed = boot)
            assertTrue(tripping.evaluateStartup(), "第 4 次启动（计数 3）必须熔断")

            // 熔断 → 清零 → 下次启动重新从 1 开始（而不是 4）
            boot += 1_000L
            val afterTrip = newGuard(store = store, elapsed = boot)
            assertFalse(afterTrip.evaluateStartup(), "熔断后清零 ⇒ 不得每次启动都熔断")
            assertEquals(1, afterTrip.crashCount, "下次启动应为 1（清零后的第一次），而不是 4")
        }

    // ------------------------------------------------ reset

    @Test
    fun `reset clears the count and the marker`() =
        runTest {
            val store = InMemoryCrashMarkerStore(bootId = 1_000L, healthy = false, crashCount = 2)
            val guard = newGuard(store = store, elapsed = 5_000L)
            guard.evaluateStartup()
            assertTrue(guard.tripped)

            guard.reset()

            assertEquals(0, guard.crashCount)
            assertFalse(guard.tripped, "恢复后必须允许下次启动重新判定")
            assertNull(store.readStartupMarker(), "标记必须清掉")
        }

    // ------------------------------------------------ 工具

    /**
     * 构造被测对象（挂一个记录型钩子，便于断言"熔断动作被触发了几次"）。
     *
     * 用 `internal` 测试构造注入固定时钟与阈值；`healthThresholdMillis = null` 表示
     * "用生产默认值（60s）"——这样用例断言的就是**生产阈值**下的行为。
     * `scope` 一律用 `backgroundScope`：健康标记作业会 `delay(60s)` 后永久不返回，
     * 放前台作用域会让 `runTest` 等到超时（`AGENT_PROTOCOL.md §9.2`）。
     */
    private fun TestScope.newGuard(
        store: InMemoryCrashMarkerStore,
        elapsed: Long,
    ): BootloopGuard =
        BootloopGuard(
            store = store,
            onTrip = { },
            scope = backgroundScope,
            elapsedRealtime = { elapsed },
            healthThresholdMillis = null,
        )

    /**
     * "没有钩子"的变体（`onTrip = null`）。
     *
     * 这个分支必须单独覆盖：`onTrip` 可空是为了打断
     * `CircuitBreakerImpl ↔ BootloopGuard` 的构造环，而钩子缺失**不得**让熔断被静默跳过。
     *
     * 走 `internal` 构造（而不是不带时钟缝的主构造）：`evaluateStartup` 在未达阈值时
     * **一定会读时钟**（`scheduleHealthMark` 要用它写健康标记的 bootId），
     * 纯 JVM 下 `SystemClock` 不可用 ⇒ 用主构造的版本会抛 "not mocked"（第一版就踩了）。
     *
     * ⚠ 注意此处的 `onTrip` 类型是**非空**的 `suspend (TripReason) -> Unit`：
     * Kotlin 的重载解析会把 `onTrip = null` 优先匹配到主构造
     * （它的 `onTrip` 是可空类型），而不是这个 5 参次构造。
     */
    private fun TestScope.newGuardWithoutHook(
        store: InMemoryCrashMarkerStore,
        elapsed: Long,
    ): BootloopGuard =
        BootloopGuard(
            store = store,
            onTrip = noopTrip,
            scope = backgroundScope,
            elapsedRealtime = { elapsed },
            healthThresholdMillis = null,
        )

    /** 与 `onTrip = null` **等价**的空钩子：不观测、不转发，因此不会掩盖任何熔断。 */
    private val noopTrip: suspend (com.rootflow.domain.event.TripReason) -> Unit = { }
}
