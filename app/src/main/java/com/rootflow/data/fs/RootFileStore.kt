package com.rootflow.data.fs

import com.rootflow.runtime.RootShellManager
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * root 侧文件的读写结果。
 *
 * **绝不返回"空内容表示失败"**：空正文会让脚本静默地什么也不做——这正是本仓库反复
 * 踩到的静默失败坑型（见 `AGENT_PROTOCOL.md §8`）。失败必须是**可区分的显式状态**。
 */
sealed interface RootFileResult<out T> {
    /** 成功。 */
    data class Ok<T>(
        val value: T,
    ) : RootFileResult<T>

    /** 文件或目录不存在。 */
    data object Missing : RootFileResult<Nothing>

    /**
     * 读到内容，但内容摘要与期望不符（文件被外部改动 / 上次写入不完整）。
     *
     * @property expected 记录在 DB 中的摘要（`sha256:` 前缀）
     * @property actual 实际读到的摘要
     */
    data class Corrupted(
        val expected: String,
        val actual: String,
    ) : RootFileResult<Nothing>

    /** root 通道不可用或命令执行失败。 */
    data class Unavailable(
        val reason: String,
    ) : RootFileResult<Nothing>
}

/**
 * 经**控制通道**读写 root 侧文件（阶段 3a）。
 *
 * ## 为什么必须走 root 通道
 * 工作目录在 `/data/local/tmp/rootflow/`，App 进程（`u0_aXXX`）无法读写
 * （`/data/local/tmp` 是 `drwxrwx--x`，other 无写权限）。因此所有文件操作都经
 * [RootShellManager.execControl] 以 root 身份执行。
 *
 * 用**控制通道**而非数据通道：控制通道是独立 shell，不与正在运行的脚本抢作业
 * （阶段 1c 的核心结论）。
 *
 * ## 写入为什么用 base64
 * 需求 §3.2 要求「所有用户输入写入文件前必须做 shell 转义（base64 或 heredoc）」。
 * 经 root 通道写文件时无法依赖交互 shell 的引号语义，base64 可让任意字节安全穿越：
 * base64 字母表只含 `A–Za–z0–9+/=`，放进单引号即可。
 *
 * **注意**：这与"正文内联执行"用的 POSIX `'\''` 转义是**两套不同机制**，二者并存
 * （见 `PROJECT_STATE.md` 偏离项 D1）：**传输到文件用 base64，内联执行用 `'\''`**。
 *
 * ## 行尾规范化
 * `cat` 经 shell 取回的 stdout 会丢失末尾换行，导致"写→读"往返不逐字节相等。
 * 为保证往返确定，写入时**规范化**为"恰好一个结尾换行"，读取时**去掉一个结尾换行**。
 * 二者互为逆操作，且 shell 脚本以换行结尾本是惯例。
 */
@Singleton
class RootFileStore
    @Inject
    constructor(
        private val rootShellManager: RootShellManager,
    ) {
        /**
         * 写入文本文件（自动建目录、设权限）。
         *
         * @param path 绝对路径
         * @param content 文本内容；会按类 KDoc 所述规范化结尾换行
         * @return 写入内容的 `sha256:<hex>` 摘要（供 DB 记录，用于将来检测外部改动）
         */
        suspend fun writeText(
            path: String,
            content: String,
        ): RootFileResult<String> {
            val normalized = content.trimEnd('\n') + "\n"
            val payload = encodeBase64(normalized)
            val dir = path.substringBeforeLast('/')
            val command =
                "mkdir -p '$dir' && chmod ${RootFlowPaths.DIR_MODE} '$dir' && " +
                    "echo '$payload' | base64 -d > '$path' && " +
                    "chmod ${RootFlowPaths.FILE_MODE} '$path'"
            val result = rootShellManager.execControl(command)
            return if (result.exitCode == 0) {
                RootFileResult.Ok(sha256Of(normalized))
            } else {
                RootFileResult.Unavailable("write failed (exit=${result.exitCode}): ${result.stderr}")
            }
        }

        /**
         * 读取文本文件。
         *
         * @param path 绝对路径
         * @param expectedSha256 DB 中记录的摘要；非空时做一致性校验，不符返回
         *   [RootFileResult.Corrupted]
         */
        suspend fun readText(
            path: String,
            expectedSha256: String? = null,
        ): RootFileResult<String> {
            val result = rootShellManager.execControl("cat '$path' 2>/dev/null")
            if (result.exitCode != 0) {
                return RootFileResult.Unavailable("cat failed (exit=${result.exitCode}): ${result.stderr}")
            }
            if (result.stdout.isEmpty()) {
                // 空输出有两种可能：文件不存在，或文件确实是空的。用一次存在性探测区分——
                // 绝不能把"文件不存在"当成"脚本内容为空"（见 RootFileResult KDoc）。
                val exists = rootShellManager.execControl("test -f '$path' && echo RF_EXISTS")
                if (!exists.stdout.contains(EXISTS_MARKER)) {
                    return RootFileResult.Missing
                }
            }
            val content = result.stdout.trimEnd('\n')
            if (expectedSha256 != null) {
                val actual = sha256Of(content.trimEnd('\n') + "\n")
                if (actual != expectedSha256) {
                    return RootFileResult.Corrupted(expected = expectedSha256, actual = actual)
                }
            }
            return RootFileResult.Ok(content)
        }

        /** 删除文件（不删除其父目录；删空目录由 [deleteScriptDirectory] 负责）。 */
        suspend fun deleteFile(path: String): RootFileResult<Unit> {
            val result = rootShellManager.execControl("rm -f '$path'")
            return if (result.exitCode == 0) {
                RootFileResult.Ok(Unit)
            } else {
                RootFileResult.Unavailable("rm failed (exit=${result.exitCode}): ${result.stderr}")
            }
        }

        /** 文件是否存在（阶段 4：`safemode.flag` 的判读）。 */
        suspend fun exists(path: String): Boolean =
            rootShellManager.execControl("test -f '$path' && echo $EXISTS_MARKER").stdout.contains(EXISTS_MARKER)

        /**
         * **追加**一行文本（阶段 4 新增，需求 §5.2 第 6 步的 `logs/safemode.log`）。
         *
         * ## 为什么不用 base64（与 [writeText] 的差别）
         * [writeText] 用 base64 是为了让**任意字节**安全穿越 root 通道（用户脚本文本不可控）。
         * 本方法只写**自造的单行文本**（熔断原因与时刻），因此走更简单的路径：
         * 先按 POSIX `'\''` 转义单引号，再 `printf '%s\n' '…' >> path`。
         *
         * **仍然做转义**：`detail` 里可能出现脚本名等不可控内容，
         * 少了转义就变成命令注入面（需求 §8「命令注入」）。
         *
         * 追加语义是必需的：`safemode.log` 要保留**历次**熔断/恢复记录，
         * 覆盖写会让"反复熔断"这一关键信号丢失。
         */
        suspend fun appendLine(
            path: String,
            line: String,
        ): RootFileResult<Unit> {
            val escaped = line.replace("'", "'\\''")
            val dir = path.substringBeforeLast('/')
            val command =
                "mkdir -p '$dir' && chmod ${RootFlowPaths.DIR_MODE} '$dir' && " +
                    "printf '%s\\n' '$escaped' >> '$path' && chmod ${RootFlowPaths.FILE_MODE} '$path'"
            val result = rootShellManager.execControl(command)
            return if (result.exitCode == 0) {
                RootFileResult.Ok(Unit)
            } else {
                RootFileResult.Unavailable("append failed (exit=${result.exitCode}): ${result.stderr}")
            }
        }

        /**
         * 删除某脚本的整个目录。
         *
         * 删除脚本时应调用本方法而非逐个删文件：`meta.json` / 将来的附加资源都在同一目录下。
         */
        suspend fun deleteScriptDirectory(scriptId: Long): RootFileResult<Unit> {
            val result = rootShellManager.execControl("rm -rf '${RootFlowPaths.scriptDir(scriptId)}'")
            return if (result.exitCode == 0) {
                RootFileResult.Ok(Unit)
            } else {
                RootFileResult.Unavailable("rm -rf failed (exit=${result.exitCode}): ${result.stderr}")
            }
        }

        /** 确保根目录与 `scripts/` 存在并具有正确权限（应用启动时调用一次）。 */
        suspend fun ensureBaseDirectories(): RootFileResult<Unit> {
            val command =
                "mkdir -p '${RootFlowPaths.SCRIPTS}' '${RootFlowPaths.LOGS}' && " +
                    "chmod ${RootFlowPaths.DIR_MODE} '${RootFlowPaths.ROOT}' " +
                    "'${RootFlowPaths.SCRIPTS}' '${RootFlowPaths.LOGS}'"
            val result = rootShellManager.execControl(command)
            return if (result.exitCode == 0) {
                RootFileResult.Ok(Unit)
            } else {
                RootFileResult.Unavailable("ensureBaseDirectories failed (exit=${result.exitCode}): ${result.stderr}")
            }
        }

        /** 计算 `sha256:<hex>` 摘要（与写入/校验使用同一算法）。 */
        fun sha256Of(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return SHA_PREFIX + digest.joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun encodeBase64(content: String): String =
            java.util.Base64
                .getEncoder()
                .encodeToString(content.toByteArray(Charsets.UTF_8))

        private companion object {
            /** 存在性探测的标记；`test -f` 成功才会打印。 */
            const val EXISTS_MARKER = "RF_EXISTS"

            /** 摘要前缀；入库与比对都带此前缀，避免与裸 hex 混淆。 */
            const val SHA_PREFIX = "sha256:"
        }
    }
