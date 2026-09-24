package com.rootflow.service.receiver

import android.content.Intent
import android.util.Log
import com.rootflow.data.event.BootMarkerHolder
import com.rootflow.data.event.EventBusHolder
import com.rootflow.data.event.RecordingEventBus
import com.rootflow.domain.event.BootMarkerStore
import com.rootflow.domain.model.SystemEvent
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * [BootEventReceiver] 单测（3b 的广播部分；阶段 3d 追加 D9 标记用例）。
 *
 * ## 为什么不依赖 Robolectric（决策 2）
 * 项目单测基线是 JUnit5，而 Robolectric 需要 JUnit4 runner（阶段 3a 已查明其 jar 在离线
 * 缓存中亦不完整）。因此这里**直接调用接收器的可测入口 `handleAction`**，配合
 * [EventBusHolder] 注入假总线——纯 JVM 可跑。
 *
 * ## 只有 `android.util.Log` 需要 mock
 * `Log.i/w` 在未 mock 的 JVM 下会抛 `Method ... not mocked`，故用 `mockkStatic` 替换。
 * **接收器本身刻意不引用 `Intent.ACTION_BOOT_COMPLETED`**（静态字段无法 stub），
 * 改用自有常量；其与框架常量的一致性由 [action constant matches the framework] 单独守护。
 *
 * ## 阶段 3d：`handleAction` 增加 `elapsedRealtimeMillis` 形参
 * `SystemClock.elapsedRealtime()` 在纯 JVM 下同样 `not mocked`，因此时钟由调用方注入：
 * `onReceive` 传真实值，单测传固定值。
 *
 * ## 为什么本类必须 `@Execution(SAME_THREAD)`（**实测教训，勿删**）
 * [EventBusHolder] 与 [BootMarkerHolder] 都是**进程级可变单例**（接收器由系统实例化，
 * 只能经静态持有者拿依赖）。JUnit5 默认**并行执行**测试方法，两个用例会互相覆写持有者，
 * 3d 实测表现为"D9 标记用例随机失败"（一次运行里两条断言各自拿到对方写入的值）。
 * 本类串行后，`@AfterEach` 的 `clear()` 才能保证用例间隔离。
 */
@Execution(ExecutionMode.SAME_THREAD)
class BootEventReceiverTest {
    @AfterEach
    fun tearDown() {
        EventBusHolder.clear()
        BootMarkerHolder.clear()
    }

    @Test
    fun `action constant matches the framework`() {
        // 在能加载 Android 类的环境（真机/Robolectric）下比对；纯 JVM 下跳过。
        val frameworkValue =
            try {
                Intent.ACTION_BOOT_COMPLETED
            } catch (error: RuntimeException) {
                assumeTrue(false, "Android 框架类在纯 JVM 下不可用，跳过常量一致性断言：${error.message}")
                return
            }

        assertEquals(
            frameworkValue,
            BootEventReceiver.ACTION_BOOT_COMPLETED,
            "接收器持有的字面量必须与 Intent.ACTION_BOOT_COMPLETED 一致",
        )
    }

    @Test
    fun `boot completed is forwarded to the event bus`() {
        installLogStub()

        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BootEventReceiver().handleAction(BootEventReceiver.ACTION_BOOT_COMPLETED, ELAPSED)

        assertEquals(listOf(SystemEvent.Boot), bus.sent)
    }

    @Test
    fun `unexpected action is ignored`() {
        installLogStub()

        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BootEventReceiver().handleAction("android.intent.action.SCREEN_ON", ELAPSED)

        assertTrue(
            bus.sent.isEmpty(),
            "清单只注册 BOOT_COMPLETED；其它 action 必须被忽略而不是误当开机：${bus.sent}",
        )
    }

    @Test
    fun `null action is ignored`() {
        installLogStub()

        val bus = RecordingEventBus()
        EventBusHolder.install(bus)

        BootEventReceiver().handleAction(null, ELAPSED)

        assertTrue(bus.sent.isEmpty(), "空 action 必须被忽略")
    }

    @Test
    fun `missing bus does not crash the receiver`() {
        installLogStub()

        // 刻意不 install：模拟"应用尚未完成初始化就收到广播"
        BootEventReceiver().handleAction(BootEventReceiver.ACTION_BOOT_COMPLETED, ELAPSED)

        // 不抛异常即通过——广播接收器崩溃会被系统记入日志并可能被限流
        assertTrue(true)
    }

    // ---------------------------------------------------------------- D9 标记（阶段 3d）

    /**
     * D9 标记维护的**两条分支必须在同一个用例里断言**。
     *
     * 理由：`BootMarkerHolder` 是进程级单例，两条分支若拆成两个用例，它们的
     * "谁先写入标记"取决于调度顺序（3d 实测过一次随机失败）；放在一起后顺序由用例自身决定。
     *
     * 语义要点（勿按直觉改）：**`elapsedRealtime` 在重启时归零**，因此
     * `bootId > elapsed` 表示"标记来自上一个开机周期"，此时广播应认领本周期。
     */
    @Test
    fun `the broadcast claims the boot cycle only when the marker predates the reboot`() {
        installLogStub()
        EventBusHolder.install(RecordingEventBus())

        // 分支 1：标记来自上一开机周期（elapsed 已归零 ⇒ bootId > elapsed）→ 广播认领并写标记。
        // 这是 D9 幂等路径的关键一步：此后 App 启动会判 false，不再重复补发。
        val stale = FakeMarkerStore(bootId = 9_000L)
        BootMarkerHolder.configure(stale)
        BootEventReceiver().handleAction(BootEventReceiver.ACTION_BOOT_COMPLETED, 5_000L)
        assertEquals(5_000L, stale.written, "广播先到时必须写标记（D9：广播到达时也应写标记）")

        // 分支 2：本周期已被 App 启动路径认领（bootId ≤ elapsed）→ 不得覆写（否则标记被推后）
        val claimed = FakeMarkerStore(bootId = 1_000L)
        BootMarkerHolder.configure(claimed)
        BootEventReceiver().handleAction(BootEventReceiver.ACTION_BOOT_COMPLETED, 8_000L)
        assertEquals(null, claimed.written, "同周期内晚到的广播不得覆写标记")
    }

    @Test
    fun `a missing marker store does not crash the receiver`() {
        installLogStub()
        EventBusHolder.install(RecordingEventBus())
        // 刻意不 configure：模拟"标记持有者尚未安装"
        BootMarkerHolder.clear()

        BootEventReceiver().handleAction(BootEventReceiver.ACTION_BOOT_COMPLETED, ELAPSED)

        assertTrue(true, "标记持有者为空时接收器必须容错（广播接收器崩溃会被系统限流）")
    }

    /** 固定 `android.util.Log`；每个用例都要调用（`mockkStatic` 不跨用例持久）。 */
    private fun installLogStub() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    /** 记录型标记存储（纯 JVM）。 */
    private class FakeMarkerStore(
        private val bootId: Long?,
    ) : BootMarkerStore {
        var written: Long? = null
            private set

        override fun read(): Long? = bootId

        override fun write(elapsedRealtimeMillis: Long) {
            written = elapsedRealtimeMillis
        }
    }

    private companion object {
        /** 单测使用的固定单调时钟读数。 */
        const val ELAPSED = 1_234_567L
    }
}
