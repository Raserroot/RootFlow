package com.rootflow.data.fs

/**
 * RootFlow 的 root 侧工作目录与路径拼装（阶段 3a）。
 *
 * ## 为什么不是需求 §3.2 的 `/data/adb/rootflow/`
 *
 * 需求 §3.2 规定工作根目录为 `/data/adb/rootflow/`。**该路径在本项目的测试设备
 * （OnePlus 8 + APatch）上物理不可用**：真机探针实测 `mkdir /data/rootflow_probe`
 * 返回 `Permission denied`，且路径矩阵确认 `/data/adb/`、`/data/data/<pkg>/`、
 * `/data/misc/`、`/data/`（根）**全部不可写**，仅 `/sdcard/` 与 `/data/local/tmp/` 可用。
 *
 * 因此实现使用 **`/data/local/tmp/rootflow/`**。该选择已登记为
 * `PROJECT_STATE.md` 的偏离项 **D7**（含"若将来支持 Magisk / KernelSU 应回退到
 * `/data/adb/rootflow/`"的兼容策略）。
 *
 * ## 为什么 DB 只存相对路径
 * 目录常量一旦因设备/Root 方案变化而调整，绝对路径入库就会全部失效。因此 Room 里
 * 只存相对路径（如 `scripts/1/main.sh`），绝对路径一律由本对象拼装。
 *
 * ## 权限加固
 * - 目录：`0700`（仅 root 可进入）
 * - 文件：`0600`（仅 root 可读）
 *
 * `/data/local/tmp` 本身是 `drwxrwx--x`（other 无写权限），但**同设备其它 App 可以读**
 * 其下 world-readable 的文件。加固成本极低，故一律收紧——脚本正文可能含用户敏感信息。
 */
object RootFlowPaths {
    /** 工作根目录。见类 KDoc：需求要求 `/data/adb/rootflow`，本设备不可用，故改用此处。 */
    const val ROOT: String = "/data/local/tmp/rootflow"

    /** 脚本目录（需求 §3.2：`scripts/<id>/`）。 */
    const val SCRIPTS: String = "$ROOT/scripts"

    /** 日志目录（需求 §3.2：`logs/<id>/<timestamp>.log`）。阶段 3 不写文件日志，见遗留项。 */
    const val LOGS: String = "$ROOT/logs"

    /**
     * 安全模式标志（需求 §5.2 第 1 步）。
     *
     * ## 路径：需求写 `/data/adb/rootflow/safemode.flag`，实现改用 [ROOT] 下
     * 与 **D7** 同一理由：真机探针实测 `/data/adb/` 在 OnePlus 8 + APatch 上
     * **连 root 都进不去**（`adb_data_file` + APatch 保护）。阶段 4 起真正读写该文件。
     *
     * ## 语义：**存在即安全模式**
     * 文件内容不承载任何状态（写一行说明即可），因此
     * `su -c ls` / `test -f` 就能判读，也便于用户手工 `rm` 恢复——
     * 这一点很重要：bootloop 兜底触发后**只能人工恢复**（见 `BootloopGuard` 的 KDoc）。
     */
    const val SAFE_MODE_FLAG: String = "$ROOT/safemode.flag"

    /**
     * 熔断前的触发器**原始启用状态**快照（阶段 4 新增）。
     *
     * ## 为什么不塞进 `safemode.flag`
     * flag 的契约是"存在即安全模式"（见上）。若把状态写进同一个文件，
     * 就出现"文件存在但内容解析失败"的中间态——那时**既无法恢复也无法确定是否该恢复**。
     * 拆成两个文件后：flag 决定"是否安全模式"，state 决定"怎么还原"；
     * state 丢失时恢复动作退化为"保持禁用"（**保守方向**，不会替用户打开他没开的触发器）。
     */
    const val SAFE_MODE_STATE: String = "$ROOT/safemode.state"

    /** 安全模式事件日志（需求 §5.2 第 6 步：`logs/safemode.log`）。 */
    const val SAFE_MODE_LOG: String = "$LOGS/safemode.log"

    /**
     * **事件投递通道**目录（P5 / 方案 §2.3 的 C1）：每个**运行中**的脚本一个 FIFO。
     *
     * 与 `scripts/`、`logs/` 平级而不是塞进某个脚本目录：它按 `runId` 组织，
     * 而 `runId` 与 `scriptId` 是多对一（同一脚本会有很多次运行）。
     */
    const val IPC: String = "$ROOT/ipc"

    /**
     * 某次运行的**事件 FIFO** 路径。
     *
     * ## ★ 为什么按 `runId` 而不是 `scriptId`
     * FIFO 是**单读者**语义：多个进程读同一条管道会**争抢**同一行（事件被其中一个吃掉，
     * 其余的永远等不到）。而"一次运行 = 一个脚本进程 = 一个读者"正好一一对应。
     *
     * 更关键的是**跨轮次**：常驻脚本退出后监工会拉起新一轮（新 `runId`）——
     * 按 `runId` 建通道，新一轮拿到的是一条**全新**的管道，上一轮积压的事件不会被它读到；
     * 按 `scriptId` 建就会把两轮运行串在同一条队列上（新进程读到上一个进程的遗留事件，
     * 而那是**陈旧**的触发原因）。
     *
     * `runId` 是 `RunSession` 生成的 UUID 字符串，可直接作文件名（无路径分隔符）。
     */
    fun eventFifo(runId: String): String = "$IPC/$runId.q"

    /**
     * FIFO 的权限：**必须 666**（P0 实测算术，勿改成 600）。
     *
     * ## 依据（`docs/总开关机制-方案.md` §10 约束 ①）
     * `mkfifo -m 666` 会被 umask 削成 `0644`，而实测 `prw-r--r--` 时 **`shell` 域写入失败**
     * ⇒ 建 FIFO 的命令里**永远**跟一个显式 `chmod`，不要依赖 `-m`。
     *
     * ## 为什么写入侧明明是 root（root 无视权限位）
     * 写端是 `su` 起的 root shell，它确实不看权限位。但读端是**脚本进程**，而脚本的
     * 上下文取决于它是被谁起的（本应用经 root shell 启动 ⇒ 通常也是 root/magisk 域）。
     * 显式 666 让"谁都能读写"成为事实，从而不必假设读端一定在哪个域 ——
     * 这正是 P0 那次失败换来的教训：**别猜上下文，把权限位摆平**。
     */
    const val FIFO_MODE: String = "666"

    /** 目录权限：仅 root。 */
    const val DIR_MODE: String = "700"

    /** 文件权限：仅 root。 */
    const val FILE_MODE: String = "600"

    /** 某脚本的目录绝对路径。 */
    fun scriptDir(scriptId: Long): String = "$SCRIPTS/$scriptId"

    /** 某脚本正文的**绝对**路径。 */
    fun scriptBody(
        scriptId: Long,
        extension: String,
    ): String = "${scriptDir(scriptId)}/main.$extension"

    /** 某脚本正文的**相对**路径（入库用，见类 KDoc）。 */
    fun scriptBodyRelative(
        scriptId: Long,
        extension: String,
    ): String = "scripts/$scriptId/main.$extension"

    /** 某脚本 `meta.json` 的绝对路径（需求 §3.2）。 */
    fun scriptMeta(scriptId: Long): String = "${scriptDir(scriptId)}/meta.json"

    /** 某脚本 `meta.json` 的相对路径（入库用）。 */
    fun scriptMetaRelative(scriptId: Long): String = "scripts/$scriptId/meta.json"

    /**
     * 脚本正文的文件扩展名。
     *
     * 需求 §3.1：v1 只实现 Shell，Lua 接口预留。未知语言回退 `sh`——**回退而非抛异常**，
     * 因为该值只影响文件名，不影响执行语义（正文由 `ShellScriptRuntime` 内联下发）。
     */
    fun extensionFor(language: String): String =
        when (language.lowercase()) {
            "lua" -> "lua"
            else -> "sh"
        }
}
