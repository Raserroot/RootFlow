package com.rootflow.data.fs

import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.ShellResult
import io.mockk.coEvery
import io.mockk.mockk
import java.util.Base64

/**
 * 测试用的"假 shell"（阶段 3a）。
 *
 * ## 为什么不直接断言命令字符串
 * 只断言"命令里含 `chmod 600`"证明不了写入真的能读回。本类解释
 * `mkdir -p` / `base64 -d` / `cat` / `test -f` / `rm -rf` 并维护内存文件表，
 * 于是"写 → 读"成为**真的往返**。
 *
 * 它不是 toybox 的模拟器：只支持本测试用到的那几条命令形态，遇到不认识的就返回非 0
 * 并给出原因——**不静默返回成功**（否则测试会假绿）。
 */
internal class FakeRootShell {
    /** 内存文件表：绝对路径 → 内容（写入时已按 [RootFileStore] 规则规范化）。 */
    val files = linkedMapOf<String, String>()

    /** 收到过的全部命令，供断言命令形态（如 chmod / base64）用。 */
    val commands = mutableListOf<String>()

    /** 打桩后的 [RootShellManager]；所有 `execControl` 都由本类解释。 */
    val manager: RootShellManager =
        mockk<RootShellManager>().also { manager ->
            coEvery { manager.execControl(any()) } answers {
                val command = firstArg<String>()
                commands += command
                execute(command)
            }
        }

    private fun execute(command: String): ShellResult {
        val body = command.trim()

        // 写入：mkdir … && echo '<b64>' | base64 -d > '<path>' && chmod 600 '<path>'
        Regex("""base64 -d > '([^']+)'""").find(body)?.let { match ->
            val path = match.groupValues[1]
            val payload =
                Regex("""echo '([A-Za-z0-9+/=]*)' \| base64 -d""").find(body)?.groupValues?.get(1)
                    ?: return ShellResult(stdout = "", stderr = "no base64 payload found", exitCode = 1)
            files[path] = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            return ShellResult(stdout = "", stderr = "", exitCode = 0)
        }

        Regex("""^cat '([^']+)'""").find(body)?.let { match ->
            val content = files[match.groupValues[1]]
            // 模拟 shell：命令替换会吃掉输出末尾的换行（readText 依赖这一行为做规范化）
            return ShellResult(stdout = content?.trimEnd('\n') ?: "", stderr = "", exitCode = 0)
        }

        Regex("""test -f '([^']+)'""").find(body)?.let { match ->
            return if (files.containsKey(match.groupValues[1])) {
                ShellResult(stdout = "RF_EXISTS", stderr = "", exitCode = 0)
            } else {
                ShellResult(stdout = "", stderr = "", exitCode = 1)
            }
        }

        Regex("""^rm -rf '([^']+)'""").find(body)?.let { match ->
            val dir = match.groupValues[1]
            files.keys.filter { it.startsWith("$dir/") }.forEach { files.remove(it) }
            return ShellResult(stdout = "", stderr = "", exitCode = 0)
        }

        Regex("""^rm -f '([^']+)'""").find(body)?.let { match ->
            files.remove(match.groupValues[1])
            return ShellResult(stdout = "", stderr = "", exitCode = 0)
        }

        // 追加（阶段 4）：`printf '%s\n' '<escaped>' >> '<path>'`（`RootFileStore.appendLine` 的产物）。
        //
        // 必须与上面的 `base64 -d >` 分开匹配：appendLine **刻意不用 base64**
        // （它只写自造的单行文本，见其 KDoc），而是按 POSIX 惯用法把 `'` 转义成 `'\''`。
        // 因此这里要做的正是**反转义**——不做就会让 `safemode.log` 的用例读到转义后的
        // 字面量而**假绿**（断言"含某串"仍可能通过，但内容已经不是用户看到的那份）。
        Regex("""printf '%s\\n' '(.*)' >> '([^']+)'""").find(body)?.let { match ->
            val payload = match.groupValues[1].replace("""'\''""", "'")
            val path = match.groupValues[2]
            files[path] = (files[path] ?: "") + payload + "\n"
            return ShellResult(stdout = "", stderr = "", exitCode = 0)
        }

        if (body.startsWith("mkdir -p")) {
            return ShellResult(stdout = "", stderr = "", exitCode = 0)
        }

        return ShellResult(stdout = "", stderr = "unsupported in FakeRootShell: $body", exitCode = 1)
    }
}
