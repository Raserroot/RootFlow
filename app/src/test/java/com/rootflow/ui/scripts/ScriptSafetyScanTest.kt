package com.rootflow.ui.scripts

import com.rootflow.ui.scripts.ScriptSafetyScan.DangerSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 危险指令扫描（用户需求，2026-09-25）。
 *
 * ## 为什么这一层必须穷举正例**与反例**
 * 本扫描的失败有**两个方向**：
 * - **漏报**（该弹的没弹）—— 用户被静默地放进危险区
 * - **误报**（不该弹的弹了）—— 弹窗变成噪音，用户学会无脑点「继续保存」，
 *   于是**真正的危险也被一起放过**
 * 后者更隐蔽，所以本文件里"不该命中"的用例与"该命中"的同等重要。
 *
 * ## 已声明接受的漏报（**不是缺陷，勿当 bug 修**）
 * 变量拼接的**数据流**不做追踪：`T=/data; rm -rf $T` 只会命中"目标含变量"那一档，
 * 而不是被认成"删 /data"。见 [ScriptSafetyScan] 的类 KDoc。
 */
class ScriptSafetyScanTest {
    private fun ids(content: String): List<String> = ScriptSafetyScan.scan(content).map { it.ruleId }

    private fun assertHits(
        ruleId: String,
        vararg commands: String,
    ) {
        commands.forEach { command ->
            assertEquals(listOf(ruleId), ids(command), "应命中 $ruleId：$command")
        }
    }

    private fun assertSilent(vararg commands: String) {
        commands.forEach { command ->
            assertTrue(ids(command).isEmpty(), "不应命中任何规则：$command")
        }
    }

    // ── rm 递归删除：危险程度由**目标**决定，不由 rm 本身决定 ──────────────

    @Test
    fun `rm 递归删除打在根或系统分区上是高危`() {
        assertHits(
            "rm-recursive-root-or-system",
            "rm -rf /",
            "rm -rf /*",
            "rm -rf /data",
            "rm -rf /system",
            "rm -rf /sdcard",
            "rm -rf /storage",
            "rm -rf /vendor",
            "rm -rf /boot",
            "sudo rm -rf /",
        )
    }

    @Test
    fun `rm 标志的顺序与大小写都不影响判定`() {
        assertHits(
            "rm-recursive-root-or-system",
            "rm -rf /",
            "rm -fr /",
            "rm -Rf /",
            "rm -fR /",
            "rm -r /",
            "rm -R /",
        )
    }

    @Test
    fun `目标含变量时按未知路径报`() {
        assertHits(
            "rm-recursive-variable-target",
            "rm -rf \$TARGET",
            "rm -rf \"\$TARGET\"",
            "rm -rf \${DIR}/x",
        )
    }

    @Test
    fun `正常清理不该弹窗`() {
        // 这一组是**误报防护**：它们全是日常写法，弹一次就是噪音
        assertSilent(
            "rm -rf ./build",
            "rm -rf build/",
            "rm -rf /tmp/cache",
            "rm -rf /tmp/*",
            "rm -rf ./app/build",
            "rm -f old.log",
            "rm file.txt",
        )
    }

    // ── 设备与文件系统 ───────────────────────────────────────────────────

    @Test
    fun `覆写块设备与格式化是高危`() {
        assertHits(
            "device-overwrite-dd",
            "dd if=/dev/zero of=/dev/block/by-name/system",
            "dd if=/dev/zero of=/dev/block/sda1 bs=4096",
        )
        assertHits(
            "device-overwrite-redirect",
            "echo x > /dev/block/by-name/boot",
            "cat img >> /dev/block/mmcblk0",
        )
        assertHits(
            "filesystem-format",
            "mkfs.ext4 /dev/block/sda1",
            "mkfs /dev/block/sda1",
            "mke2fs -t ext4 /dev/block/sda1",
        )
    }

    @Test
    fun `fastboot 擦除与 pm 破坏性操作是高危`() {
        assertHits(
            "fastboot-erase",
            "fastboot erase userdata",
            "fastboot format system",
            "fastboot flash userdata img",
        )
        assertHits(
            "pm-destructive",
            "pm uninstall com.example.app",
            "pm clear com.example.app",
        )
        // 不带递归标志的 rm 才会走到这条规则：带 -r 的 `rm -rf /data/...` 已先被
        // `rm-recursive-root-or-system` 接住（那一档更危险，见 matchLine 的顺序说明）。
        assertHits(
            "package-remove-path",
            "rm /data/app/com.example/base.apk",
            "rm /data/data/com.example/db.sqlite",
        )
    }

    // ── 降低系统防护 ────────────────────────────────────────────────────

    @Test
    fun `放宽系统防护是中危`() {
        val findings =
            listOf(
                "setenforce 0",
                "mount -o remount,rw /system",
                "chmod 777 /",
                "chmod -R 777 /",
            ).map { command ->
                val hit = ScriptSafetyScan.scan(command)
                assertEquals(1, hit.size, "应命中一条：$command")
                hit.first()
            }

        findings.forEach { finding ->
            assertEquals(DangerSeverity.MEDIUM, finding.severity, finding.ruleId)
        }
        assertEquals(
            listOf("selinux-permissive", "remount-root-rw", "chmod-world-writable-root", "chmod-world-writable-root"),
            findings.map { it.ruleId },
        )
    }

    // ── 注释与行号 ──────────────────────────────────────────────────────

    @Test
    fun `整行注释不报 但行尾注释不豁免`() {
        assertSilent(
            "# rm -rf /",
            "  # rm -rf /",
            "#dd if=/dev/zero of=/dev/block/sda",
        )
        // 行尾注释是真命令：`rm` 前面那一段仍然会执行
        assertHits("rm-recursive-root-or-system", "rm -rf / # 清一下")
    }

    @Test
    fun `行号是 1 起且与实际行一致`() {
        val script =
            """
            #!/system/bin/sh
            echo hello
            rm -rf /data
            echo done
            setenforce 0
            """.trimIndent()

        val findings = ScriptSafetyScan.scan(script)
        assertEquals(2, findings.size)
        assertEquals(3, findings[0].lineNumber)
        assertEquals("rm-recursive-root-or-system", findings[0].ruleId)
        assertEquals(5, findings[1].lineNumber)
        assertEquals("selinux-permissive", findings[1].ruleId)
    }

    @Test
    fun `同一行只报一条 且摘要保留原文`() {
        val findings = ScriptSafetyScan.scan("rm -rf /data && pm clear com.x")
        assertEquals(1, findings.size)
        assertEquals("rm-recursive-root-or-system", findings.single().ruleId)
        assertTrue(findings.single().excerpt.contains("rm -rf /data"))
    }

    @Test
    fun `超长行被截断`() {
        // 用非空白字符填充：摘要会先 trim，用空格填充的话根本到不了截断那一步
        val long = "rm -rf /data # " + "x".repeat(400)
        val finding = ScriptSafetyScan.scan(long).single()
        assertTrue(finding.excerpt.endsWith("…"), "应截断")
        assertTrue(finding.excerpt.length <= 121)
    }

    @Test
    fun `空脚本与纯注释不产生命中`() {
        assertTrue(ScriptSafetyScan.scan("").isEmpty())
        assertTrue(ScriptSafetyScan.scan("\n\n").isEmpty())
        assertTrue(ScriptSafetyScan.scan("#!/system/bin/sh\n# nothing here\n").isEmpty())
    }

    @Test
    fun `说明文案非空且稳定`() {
        val finding = ScriptSafetyScan.scan("rm -rf /").single()
        assertEquals("递归强制删除根目录或系统关键分区，数据不可恢复", finding.reason)
    }
}
