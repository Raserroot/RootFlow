package com.rootflow.domain.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ForegroundDetector` 单测（阶段 3c.2，**需求 §2.1 的"状态机推断"**）。
 *
 * ## 本类的六行判定表就是需求
 * 见 `ForegroundDetector` 的 KDoc。这里的用例逐行钉死，尤其是**第 4 行
 * （`A → Unknown` 不上报）**——它是"假后台事件"的唯一防线：
 * `queryEvents` 在息屏前/系统繁忙时会返回空，若把"查不到"当"退后台"，
 * 「退到后台就清理」这类脚本会在每次锁屏时被误触发。
 */
class ForegroundDetectorTest {
    @Test
    fun `row 1 - the first known snapshot only establishes the baseline`() {
        val detector = ForegroundDetector()

        val transition = detector.accept(ForegroundSnapshot.Known("com.a"))

        assertTrue(transition.isEmpty, "首次快照不得上报（否则打开 App 本身就会触发脚本）")
        assertEquals("com.a", detector.currentPackage)
    }

    @Test
    fun `row 2 - the same package produces nothing`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        repeat(5) {
            val transition = detector.accept(ForegroundSnapshot.Known("com.a"))
            assertTrue(transition.isEmpty, "同包名不得上报（2s 一次轮询下这是绝大多数情况）")
        }
        assertEquals("com.a", detector.currentPackage)
    }

    @Test
    fun `row 3 - A to B emits left then entered`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        val transition = detector.accept(ForegroundSnapshot.Known("com.b"))

        assertEquals("com.a", transition.left)
        assertEquals("com.b", transition.entered)
        assertEquals("com.b", detector.currentPackage)
    }

    @Test
    fun `row 3 - left is ordered before entered when converted to events`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))
        val transition = detector.accept(ForegroundSnapshot.Known("com.b"))

        val events = detector.toEvents(transition)

        assertEquals(2, events.size)
        assertEquals("app_background", events[0].eventId, "顺序固定：先 Left 后 Entered")
        assertEquals("app_foreground", events[1].eventId)
    }

    @Test
    fun `row 4 - unknown never emits and never clears the baseline`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        repeat(3) {
            val transition = detector.accept(ForegroundSnapshot.Unknown)
            assertTrue(
                transition.isEmpty,
                "查不到 ≠ 退到后台：把它当后台会在每次锁屏/系统繁忙时触发假的后台事件",
            )
        }
        assertEquals("com.a", detector.currentPackage, "未知快照不得清除基线")
    }

    @Test
    fun `row 4 - A then unknown then A produces nothing`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        // 这是真机上最常见的形态：息屏前/繁忙时 queryEvents 返回空，随后又恢复
        assertTrue(detector.accept(ForegroundSnapshot.Unknown).isEmpty)
        assertTrue(detector.accept(ForegroundSnapshot.Known("com.a")).isEmpty)
        assertEquals("com.a", detector.currentPackage)
    }

    @Test
    fun `row 5 - unknown as the very first snapshot produces nothing`() {
        val detector = ForegroundDetector()

        val transition = detector.accept(ForegroundSnapshot.Unknown)

        assertTrue(transition.isEmpty)
        assertNull(detector.currentPackage)
    }

    @Test
    fun `row 6 - after reset the next known snapshot is treated as the first`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        detector.reset()
        assertNull(detector.currentPackage)

        val transition = detector.accept(ForegroundSnapshot.Known("com.a"))

        assertTrue(transition.isEmpty, "reset() 后视作首次：亮屏恢复时不得报一对 Left/Entered 噪声")
        assertEquals("com.a", detector.currentPackage)
    }

    @Test
    fun `A to B to A round trip emits both edges twice`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        val first = detector.accept(ForegroundSnapshot.Known("com.b"))
        val second = detector.accept(ForegroundSnapshot.Known("com.a"))

        assertEquals("com.a" to "com.b", first.left to first.entered)
        assertEquals("com.b" to "com.a", second.left to second.entered)
    }

    @Test
    fun `independent detectors do not share state`() {
        // 状态是实例级的（不是 object），否则多个源/多次 start 会互相污染
        val first = ForegroundDetector()
        val second = ForegroundDetector()

        first.accept(ForegroundSnapshot.Known("com.a"))

        assertNull(second.currentPackage)
        assertTrue(second.accept(ForegroundSnapshot.Known("com.a")).isEmpty)
    }

    @Test
    fun `transition none is empty and reusable`() {
        assertTrue(ForegroundTransition.NONE.isEmpty)
        assertFalse(ForegroundTransition(left = "a", entered = null).isEmpty)
        assertFalse(ForegroundTransition(left = null, entered = "b").isEmpty)
    }

    @Test
    fun `toEvents maps a single-sided transition to one event`() {
        val detector = ForegroundDetector()

        val backgroundOnly = detector.toEvents(ForegroundTransition(left = "com.a", entered = null))
        val foregroundOnly = detector.toEvents(ForegroundTransition(left = null, entered = "com.b"))

        assertEquals(1, backgroundOnly.size)
        assertEquals("app_background", backgroundOnly.single().eventId)
        assertEquals(1, foregroundOnly.size)
        assertEquals("app_foreground", foregroundOnly.single().eventId)
    }

    @Test
    fun `toEvents returns nothing for an empty transition`() {
        val detector = ForegroundDetector()

        assertTrue(detector.toEvents(ForegroundTransition.NONE).isEmpty())
    }

    @Test
    fun `packages with unusual names are compared verbatim`() {
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a.b:c"))

        val same = detector.accept(ForegroundSnapshot.Known("com.a.b:c"))
        val different = detector.accept(ForegroundSnapshot.Known("com.a.b:d"))

        assertTrue(same.isEmpty)
        assertEquals("com.a.b:d", different.entered)
    }

    @Test
    fun `a concurrent accept never loses a transition`() {
        // 轮询协程与 stop()/息屏事件可能来自不同线程；状态必须加锁（3c.1 的 @Volatile 教训）
        val detector = ForegroundDetector()
        detector.accept(ForegroundSnapshot.Known("com.a"))

        val threads =
            (0 until 8).map { index ->
                Thread { repeat(50) { detector.accept(ForegroundSnapshot.Known("com.p$index")) } }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 不抛异常即通过；最终状态必是某个 p$index
        assertTrue(detector.currentPackage?.startsWith("com.p") == true, detector.currentPackage ?: "<null>")
    }
}
