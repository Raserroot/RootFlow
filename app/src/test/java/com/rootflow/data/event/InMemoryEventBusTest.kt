package com.rootflow.data.event

import com.rootflow.domain.model.SystemEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InMemoryEventBus] 单测（3b 清单 4–6）。
 *
 * 重点验证需求 §2.2 的背压语义：**发送方永不阻塞**、溢出**丢最旧**、丢弃**可见**。
 *
 * ## 写这类用例的稳定写法（本文件试了三版才定）
 * 用**前台 `launch` + `take(n)`**：
 * - `take(n)` 让收集在拿到 n 条后**自然结束**，因此不会挂住 `runTest`，也不需要手工取消
 * - 前台作用域才能被 `advanceUntilIdle()` 驱动
 *
 * **不可靠的写法（勿再用）**：`backgroundScope.launch(StandardTestDispatcher(testScheduler))`
 * 收集 `SharedFlow` 的重放——实测订阅者可能收不到任何事件。
 *
 * ## 关于 `replay = capacity`
 * 本实现用 `replay = capacity` 让"先发后订"也能收到（开机广播的真实场景：事件先到、
 * 调度器后订阅）。这正是 `MutableSharedFlow(extraBufferCapacity = …)` 会**静默丢事件**的地方，
 * 见 [InMemoryEventBus] 类 KDoc。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryEventBusTest {
    @Test
    fun `subscriber receives events sent while it keeps up`() =
        runTest {
            val bus = InMemoryEventBus()
            val received = mutableListOf<SystemEvent>()
            // 先发后订：依赖 replay 缓冲
            bus.send(SystemEvent.Boot)
            bus.send(SystemEvent.ScreenOn)

            val job = launch { bus.events().take(2).toList(received) }
            advanceUntilIdle()
            job.join()

            assertEquals(listOf(SystemEvent.Boot, SystemEvent.ScreenOn), received)
            assertEquals(0L, bus.droppedCount(), "未溢出时不得报告丢弃")
        }

    @Test
    fun `events sent before anyone subscribes are still delivered`() =
        runTest {
            // 开机广播的真实场景：事件先到，调度器后订阅。
            val bus = InMemoryEventBus(bufferCapacity = 4)

            repeat(10) { bus.send(SystemEvent.Boot) }

            val received = mutableListOf<SystemEvent>()
            val job = launch { bus.events().take(4).toList(received) }
            advanceUntilIdle()
            job.join()

            assertEquals(4, received.size, "只应保留缓冲上限内的条目：${received.size}")
            assertTrue(bus.droppedCount() > 0, "超出容量的部分必须可见：${bus.droppedCount()}")
        }

    @Test
    fun `overflow keeps the newest events`() =
        runTest {
            val bus = InMemoryEventBus(bufferCapacity = 4)

            // 灌 100 条（状态交替），远超容量 4
            repeat(100) { bus.send(SystemEvent.WifiChanged(connected = it % 2 == 0)) }

            val received = mutableListOf<SystemEvent>()
            val job = launch { bus.events().take(4).toList(received) }
            advanceUntilIdle()
            job.join()

            assertEquals(4, received.size, "最多保留容量条：${received.size}")
            // 保留的必须是**最新**的：第 99 条 index%2 != 0 → connected=false
            assertEquals(
                SystemEvent.WifiChanged(connected = false),
                received.last(),
                "DROP_OLDEST 必须保留最新事件",
            )
        }

    @Test
    fun `sending never blocks even with no subscriber`() =
        runTest {
            val bus = InMemoryEventBus(bufferCapacity = 4)

            // 无订阅者时也不得挂起、不得抛异常——onReceive 超时会 ANR。
            // 若 send 会阻塞，本用例会直接超时失败。
            repeat(1_000) { bus.send(SystemEvent.Boot) }

            assertTrue(bus.droppedCount() > 0, "大量发送且无订阅者时应有丢弃")
        }
}
