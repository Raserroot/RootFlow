package com.rootflow.domain.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `RootEnvironmentProbe` 的纯函数单测（阶段 6b）。
 *
 * ## 为什么这段逻辑值得单独测
 * 环境信息卡是主页上"唯一无法靠 UI 测试保证"的部分（已批准决策 B：不引 androidTest）。
 * 而它里面**唯一有分支**的东西就是这里的判定：读哪一行当 SELinux context、
 * 什么证据算 KernelSU、什么时候如实报 `Unknown`。
 * 把它做成只吃字符串的纯函数后，上面每一条都可以穷举。
 *
 * ## 本机的真实预期（写进断言，避免后续会话误以为探测失败）
 * 本机（OnePlus 8 / APatch）上 `/data/adb` **root 也进不去**（偏离项 D7），
 * 因此两个 `test -f` 都不命中、内核串里也没有 `APatch`/`KernelSU` 字样
 * ⇒ `rootFlavor == Unknown` 是**正确结果**。只有 SELinux context 能拿到
 * （`u:r:magisk:s0`，APatch 为兼容刻意复用的名字）。
 */
class RootEnvironmentProbeTest {
    @Test
    @DisplayName("探针命令的形态符合 §7.1（可内联，不需要推送脚本文件）")
    fun `the probe command stays shell-metacharacter free`() {
        val command = RootEnvironmentProbe.PROBE_COMMAND

        // 不含管道与重定向：§7.1 明令"只要命令里有 shell 元字符，就走脚本文件"。
        // 这里把判断全部放在 Kotlin 侧，正是为了让命令保持在可内联的形态。
        assertFalse(command.contains('|'), "不得使用管道。实际=$command")
        assertFalse(command.contains('>'), "不得使用重定向。实际=$command")
        assertFalse(command.contains('$'), "不得使用变量展开。实际=$command")

        // 必须逐段带哨兵：某段输出缺失与"探针跑了但没结果"要在解析层可区分
        assertTrue(command.contains(RootEnvironmentProbe.MARK_SELINUX_DONE), "缺 SELinux 段哨兵")
        assertTrue(command.contains(RootEnvironmentProbe.MARK_KERNEL_DONE), "缺内核段哨兵")
        assertTrue(command.contains(RootEnvironmentProbe.MARK_PROBE_DONE), "缺整体结束哨兵")

        // 两个存在性探针必须走 `test -f … && echo …` 形态
        assertTrue(command.contains("test -f ${RootEnvironmentProbe.MAGISK_KSJ_PATH}"), "缺 ksu 路径探针")
        assertTrue(command.contains("test -f ${RootEnvironmentProbe.KSUD_PATH}"), "缺 ksud 路径探针")
    }

    @Test
    @DisplayName("SELinux context 取首个 u: 行，并去掉结尾 NUL 与空白")
    fun `selinux context is read from the first u colon line`() {
        val stdout =
            "u:r:magisk:s0\u0000\n" +
                "${RootEnvironmentProbe.MARK_SELINUX_DONE}\n" +
                "Linux version 4.19.191-garbage (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_KERNEL_DONE}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        val probe = RootEnvironmentProbe.parse(stdout)

        assertEquals("u:r:magisk:s0", probe.selinuxContext, "必须剥掉 /proc 文件结尾的 NUL 字节")
        assertTrue(probe.completed, "看到结束哨兵 ⇒ completed=true")
    }

    @Test
    @DisplayName("★ 真机形态：哨兵与 context 粘在同一行时必须截断（阶段 8 实测暴露）")
    fun `selinux context is truncated when the marker is glued to it`() {
        // 真机实测（2026-09-20 阶段 8）：`/proc/self/attr/current` 的输出**不带换行**，
        // 于是紧邻的 `echo $MARK_SELINUX_DONE` 被粘成一行。
        // 下面的合成 stdout 就是设备上真实的形状（已按真机 dump 复刻）。
        val stdout =
            "u:r:magisk:s0" + RootEnvironmentProbe.MARK_SELINUX_DONE + "\n" +
                "Linux version 4.19.191-g0a1b2c3 (builder@host) #1 SMP PREEMPT\n" +
                "${RootEnvironmentProbe.MARK_KERNEL_DONE}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        val probe = RootEnvironmentProbe.parse(stdout)

        assertEquals(
            "u:r:magisk:s0",
            probe.selinuxContext,
            "哨兵**不得**出现在 context 里 —— 它曾经一路显示到主页信息卡上（用户可见的乱码）",
        )
        assertTrue(probe.completed, "同一行的哨兵仍必须被认出来")
    }

    @Test
    @DisplayName("本机实测形态：只能给出 Unknown（不是缺陷，是 D7 的必然结果）")
    fun `the real device shape yields Unknown honestly`() {
        val stdout =
            "u:r:magisk:s0\u0000\n" +
                "${RootEnvironmentProbe.MARK_SELINUX_DONE}\n" +
                "Linux version 4.19.191-g0a1b2c3 (builder@host) #1 SMP PREEMPT\n" +
                "${RootEnvironmentProbe.MARK_KERNEL_DONE}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        val probe = RootEnvironmentProbe.parse(stdout)

        assertEquals(RootFlavor.Unknown, probe.rootFlavor, "APatch 复用 magisk 之名，但不得据此断言 flavor")
        assertEquals("u:r:magisk:s0", probe.selinuxContext, "context 仍要照常给出，供人判断")
        assertTrue(probe.completed)
    }

    @Test
    @DisplayName("内核串含 KernelSU ⇒ KernelSU")
    fun `kernel marker wins for kernelsu`() {
        val stdout =
            "u:r:su:s0\n" +
                "Linux version 4.19.191-KernelSU-v0.9.5 (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertEquals(RootFlavor.KernelSU, RootEnvironmentProbe.parse(stdout).rootFlavor)
    }

    @Test
    @DisplayName("内核串含 APatch ⇒ APatch")
    fun `kernel marker wins for apatch`() {
        val stdout =
            "u:r:magisk:s0\n" +
                "Linux version 4.19.191-APatch-115032 (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertEquals(RootFlavor.APatch, RootEnvironmentProbe.parse(stdout).rootFlavor)
    }

    @Test
    @DisplayName("ksud 二进制命中 ⇒ KernelSU（存在性证据）")
    fun `ksud evidence yields kernelsu`() {
        val stdout =
            "u:r:su:s0\n" +
                "Linux version 4.19.191-generic (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_KSU_BIN}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertEquals(RootFlavor.KernelSU, RootEnvironmentProbe.parse(stdout).rootFlavor)
    }

    @Test
    @DisplayName("magisk/ksu 兼容路径命中 ⇒ KernelSU（该路径只属 KernelSU 生态）")
    fun `magisk ksu compatibility path yields kernelsu`() {
        val stdout =
            "u:r:su:s0\n" +
                "Linux version 4.19.191-generic (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_MAGISK_ALT}\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertEquals(RootFlavor.KernelSU, RootEnvironmentProbe.parse(stdout).rootFlavor)
    }

    @Test
    @DisplayName("空输出 ⇒ 全部字段降级、completed=false（不静默成功）")
    fun `empty output degrades without pretending`() {
        val probe = RootEnvironmentProbe.parse("")

        assertNull(probe.selinuxContext, "读不到就是 null，不填占位串")
        assertEquals(RootFlavor.Unknown, probe.rootFlavor)
        assertFalse(probe.completed, "没有结束哨兵就必须报 completed=false")
    }

    @Test
    @DisplayName("探针被中断（无结束哨兵）时 completed=false，但已读到的部分仍保留")
    fun `interrupted probe keeps what it read but reports incomplete`() {
        val stdout =
            "u:r:magisk:s0\n" +
                "Linux version 4.19.191-APatch-115032 (builder@host)\n"

        val probe = RootEnvironmentProbe.parse(stdout)

        assertFalse(probe.completed)
        assertEquals(RootFlavor.APatch, probe.rootFlavor, "已拿到的内核证据仍然有效")
        assertEquals("u:r:magisk:s0", probe.selinuxContext)
    }

    @Test
    @DisplayName("解析是幂等的（同一输入两次结果相同）")
    fun `parsing is idempotent`() {
        val stdout =
            "u:r:magisk:s0\n" +
                "Linux version 4.19.191-generic (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertEquals(RootEnvironmentProbe.parse(stdout), RootEnvironmentProbe.parse(stdout))
    }

    @Test
    @DisplayName("不带 u: 前缀的行不会被误当成 SELinux context")
    fun `a non selinux line is not mistaken for the context`() {
        val stdout =
            "Linux version 4.19.191-generic (builder@host)\n" +
                "${RootEnvironmentProbe.MARK_PROBE_DONE}\n"

        assertNull(RootEnvironmentProbe.parse(stdout).selinuxContext)
    }

    @Test
    @DisplayName("flavor 的稳定键与 UI 文案一致（不得用 ordinal）")
    fun `flavor keys are stable`() {
        assertEquals("magisk", RootFlavor.Magisk.key)
        assertEquals("kernelsu", RootFlavor.KernelSU.key)
        assertEquals("apatch", RootFlavor.APatch.key)
        assertEquals("unknown", RootFlavor.Unknown.key)
    }
}
