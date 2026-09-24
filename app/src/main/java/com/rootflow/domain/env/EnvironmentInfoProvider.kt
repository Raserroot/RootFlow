package com.rootflow.domain.env

import kotlinx.coroutines.flow.StateFlow

/**
 * Root 管理方案（阶段 6b）。
 *
 * ## 为什么 `Unknown` 是**正常取值**而不是错误
 * 本机（OnePlus 8 + APatch）的 `/data/adb/` 受 APatch 保护，**即使 root 也进不去**
 * （阶段 3a 真机探针实测，见 `PROJECT_STATE.md` 偏离项 D7）。
 * 因此基于路径存在性的判定在这台设备上**必然**返回 [Unknown]。
 *
 * 把"探测不到"折叠成某一个 flavor（例如因为 SELinux context 里带 `magisk` 就报 Magisk）
 * 是**静默错误**：APatch 为了兼容性刻意使用 `u:r:magisk:s0`，
 * 这个名字说明不了实现是哪个。宁可如实报 [Unknown]，并让 UI 同时显示
 * SELinux context 供人判断（见 [EnvironmentSnapshot.selinuxContext]）。
 */
enum class RootFlavor {
    Magisk,
    KernelSU,

    /** APatch / FolkPatch（本测试设备实际使用的方案）。 */
    APatch,

    /** 探测不到（路径不可访问 / root 通道不可用）——**如实上报，不猜**。 */
    Unknown,
    ;

    /** 稳定字符串键（日志判读与 UI 文案共用，**不得**用 `ordinal`）。 */
    val key: String
        get() =
            when (this) {
                Magisk -> "magisk"
                KernelSU -> "kernelsu"
                APatch -> "apatch"
                Unknown -> "unknown"
            }
}

/**
 * 主页环境信息卡的一次快照（阶段 6b，纯值对象）。
 *
 * ## 为什么字段是"已经算好的字符串"而不是各种 Android 对象
 * 本项目的单测基线是**纯 JVM**（不引 Robolectric，见 `AGENT_PROTOCOL.md §5.8`）。
 * `android.os.Build` 的字段在纯 JVM 下为 `null`，`Build.VERSION.SDK_INT` 为 `0`。
 * 若把 `Build` 读取写在 UI 里，"该显示什么"就只能靠真机肉眼验证。
 * 因此所有 Android 读取都收敛在 `data/env/RootEnvironmentInfoProvider`，
 * 产出这个**可纯 JVM 构造**的快照，UI 只做投影。
 *
 * @property appVersion `BuildConfig.VERSION_NAME`（**不硬编码**，与 `build.gradle.kts` 单一真相）
 * @property androidRelease `Build.VERSION.RELEASE`（如 `15`）
 * @property apiLevel `Build.VERSION.SDK_INT`
 * @property deviceModel `MANUFACTURER + " " + MODEL`（如 `OnePlus PLK110`）
 * @property primaryAbi 首选 ABI；取不到时为空串（**不填 `"?"`**，让 UI 决定怎么显示缺失）
 * @property rootFlavor 见 [RootFlavor]；探测不到时为 [RootFlavor.Unknown]
 * @property selinuxContext `/proc/self/attr/current` 的首行；读不到为 `null`
 * @property capturedAtMillis 本次探测的墙钟时刻（`System.currentTimeMillis()`），
 *   UI 据此显示"刚刚更新"而不是让用户以为它一直在实时刷新
 */
data class EnvironmentSnapshot(
    val appVersion: String,
    val androidRelease: String,
    val apiLevel: Int,
    val deviceModel: String,
    val primaryAbi: String,
    val rootFlavor: RootFlavor,
    val selinuxContext: String?,
    val capturedAtMillis: Long,
)

/**
 * 环境信息端口（阶段 6b）。
 *
 * ## 为什么是"拉到快照 + 显式刷新"而不是"持续可观察"
 * 环境信息（内核、设备型号、SELinux）在一次进程生命周期内**几乎不变**，
 * 而每次探测要走一次 **root 通道**（`su` 往返，真机实测约数百毫秒）。
 *
 * 若把它做成持续轮询的流，会同时踩两处纪律：
 * 1. **`AGENT_PROTOCOL.md §9.4`**：虚拟时间下"空闲轮询"会让 `advanceUntilIdle()` 永不返回
 * 2. **`AGENT_PROTOCOL.md §6` 的对照实验精神**：为一个常量反复付 root 往返的代价，
 *    收益为零，只增加真机日志噪声与"探测失败"的偶发面
 *
 * 因此本端口是**拉模型**：UI 进入主页时探一次，用户点刷新时再探一次。
 *
 * ## 为什么 `snapshot` 可为 `null`
 * `null` 表达"**尚未探测过**"，与"探测过但全部字段不可用"是两种不同状态
 * （后者的 `rootFlavor` 是 `Unknown`、`selinuxContext` 是 `null`，但版本号仍然有效）。
 * 折叠成"用默认值填充的空快照"会让 UI 显示一堆假值 —— 这正是本仓库反复禁止的形态。
 */
interface EnvironmentInfoProvider {
    /** 最近一次探测结果；`null` = 尚未探测（UI 应显示"探测中"）。 */
    val snapshot: StateFlow<EnvironmentSnapshot?>

    /**
     * 探测一次并更新 [snapshot]。
     *
     * **失败不抛**：root 通道不可用是**正常情况**（设备未 root、用户拒绝授权），
     * 此时如实产出"版本信息完整 + `rootFlavor=Unknown` + `selinuxContext=null`"的快照。
     * 让探测失败抛异常会逼调用方到处写 `try/catch`，而漏掉一处就是"主页崩了"。
     *
     * **可取消**：调用方（`viewModelScope`）取消时协程按 `CancellationException` 退出，
     * 且**不得**写入半成品快照。
     */
    suspend fun refresh()
}

/**
 * 环境探测的**纯函数**部分（阶段 6b）。
 *
 * ## 为什么与实现分开成一个 object
 * 判定逻辑（"这段 stdout 说明是哪个 flavor"）是**唯一值得单测**的部分，
 * 而它完全可以只吃字符串。放进实现类里就会与 `RootShellManager` / `BuildConfig`
 * 绑在一起，从而在纯 JVM 下不可测（与 `ui/theme/Theme.kt` 把 `apiLevel` 抽成参数同一取舍）。
 *
 * ## 探针命令的形态（`AGENT_PROTOCOL.md §7.1` 合规）
 * ```
 * cat /proc/self/attr/current; echo RF_MARK_SELINUX_DONE; cat /proc/version;
 * echo RF_MARK_KERNEL_DONE; test -f /data/adb/magisk/ksu && echo RF_MARK_MAGISK_ALT;
 * test -f /data/adb/ksud && echo RF_MARK_KSU_BIN; echo RF_MARK_PROBE_DONE
 * ```
 * - **只用 `;` 与一处 `&&`**，不含 `|` / `>` / `$` —— §7.1 明令"只要命令里有 shell 元字符，
 *   就走脚本文件"。这里刻意把**全部判定**放在 Kotlin 侧而不是 shell 侧
 *   （`if` / `grep` / 命令替换都会引入元字符），于是命令保持"单条可内联"的形态，
 *   **不需要推送脚本文件**
 * - 唯一那处 `&&` 是必要的：`test -f X && echo MARK` 才有意义
 *   （没有 `&&` 时 `echo` 会无条件执行，标记就失去信息量）。
 *   `test` 失败只让该条 `&&` 短路，**不影响**后续命令 —— 因为整体用 `;` 分隔
 * - 五个探针**一次 root 往返**取回：分五次 `exec` 会让本已敏感的启动路径多四次 su 往返
 * - 每个探针后紧跟一个**哨兵**，因此"某段输出缺失"与"探针跑了但没结果"
 *   在解析层是可区分的（不静默）
 *
 * 输出形态（本机实测预期）：
 * ```
 * u:r:magisk:s0
 * RF_MARK_SELINUX_DONE
 * Linux version 4.19.… (…)
 * RF_MARK_KERNEL_DONE
 * RF_MARK_PROBE_DONE
 * ```
 * 两个 `test` 的标记**不出现**（`/data/adb` 在 APatch 设备上 root 亦不可访问，D7）。
 */
object RootEnvironmentProbe {
    /** SELinux 探针段结束哨兵。 */
    const val MARK_SELINUX_DONE: String = "RF_MARK_SELINUX_DONE"

    /** 内核探针段结束哨兵。 */
    const val MARK_KERNEL_DONE: String = "RF_MARK_KERNEL_DONE"

    /** KernelSU 的兼容标记文件路径（存在 ⇒ KernelSU 生态）。 */
    const val MAGISK_KSJ_PATH: String = "/data/adb/magisk/ksu"

    /** KernelSU 的守护二进制路径。 */
    const val KSUD_PATH: String = "/data/adb/ksud"

    /** 命中 [MAGISK_KSJ_PATH] 时输出的标记。 */
    const val MARK_MAGISK_ALT: String = "RF_MARK_MAGISK_ALT"

    /** 命中 [KSUD_PATH] 时输出的标记。 */
    const val MARK_KSU_BIN: String = "RF_MARK_KSU_BIN"

    /** 探针整体结束哨兵（用于确认命令跑到了末尾，不静默）。 */
    const val MARK_PROBE_DONE: String = "RF_MARK_PROBE_DONE"

    /**
     * 单条只读探针命令（形态与理由见本 object 的 KDoc）。
     *
     * ## 为什么它在其它常量**之后**声明（勿上移）
     * Kotlin 的 `const val` 初始化器只能引用**已声明**的常量。
     * 本常量引用了上面全部 6 个哨兵/路径常量，因此必须排在它们后面 ——
     * 上移会得到一串 `Variable 'MARK_…' must be initialized`（实测）。
     */
    const val PROBE_COMMAND: String =
        "cat /proc/self/attr/current; echo $MARK_SELINUX_DONE; " +
            "cat /proc/version; echo $MARK_KERNEL_DONE; " +
            "test -f $MAGISK_KSJ_PATH && echo $MARK_MAGISK_ALT; " +
            "test -f $KSUD_PATH && echo $MARK_KSU_BIN; " +
            "echo $MARK_PROBE_DONE"

    /** `cat /proc/self/attr/current` 的输出行前缀（本探针输出里唯一以 `u:` 开头的行）。 */
    private const val SELINUX_PREFIX = "u:"

    /** 内核版本串里的 KernelSU 标记。 */
    private const val KERNELSU_MARKER = "KernelSU"

    /** 内核版本串里的 APatch 标记。 */
    private const val APATCH_MARKER = "APatch"

    /**
     * 探针输出的解析结果（纯值对象）。
     *
     * @property selinuxContext `/proc/self/attr/current` 的首个 `u:` 行；读不到为 `null`
     * @property rootFlavor 判定结果；证据不足时为 [RootFlavor.Unknown]
     * @property completed 是否看到 [MARK_PROBE_DONE]（`false` ⇒ 探针被中断，结论不可信）
     */
    data class Probe(
        val selinuxContext: String?,
        val rootFlavor: RootFlavor,
        val completed: Boolean,
    )

    /**
     * 解析探针输出（**纯函数**，可穷举单测）。
     *
     * ## 判定顺序与理由
     * 1. 内核版本串含 `KernelSU` → [RootFlavor.KernelSU]（内核内方案，标记最硬）
     * 2. 内核版本串含 `APatch` → [RootFlavor.APatch]（同上）
     * 3. 命中 [MARK_KSU_BIN] / [MARK_MAGISK_ALT] → [RootFlavor.KernelSU]
     *    （只有 KernelSU 生态有这两个路径；Magisk 自身没有可可靠探测的标记文件）
     * 4. 其余 → [RootFlavor.Unknown]
     *
     * ## 为什么**不**用 SELinux context 判定 flavor
     * APatch 刻意复用 `u:r:magisk:s0` 做兼容（本机实测就是这个值），
     * Magisk 与 APatch 在这一项上**不可区分**。拿它判定等于把"猜"写进代码，
     * 而猜错的后果是用户在排查时被指向错误的方案文档。
     * context 仍然照常返回，由 UI 并排显示，供人判断。
     *
     * ## 为什么 APatch 在**本机**判不出来（如实登记）
     * APatch 没有任何可从 `/proc` 读取的、稳定的方案标记，且 `/data/adb` 在 APatch 设备上
     * root 也进不去（D7）⇒ 四条证据全部拿不到 ⇒ **只能给出 [RootFlavor.Unknown]**。
     * 这是**正确**结果，不是缺陷：UI 会显示 `Unknown` + SELinux context。
     *
     * @param stdout 探针的完整 stdout
     */
    fun parse(stdout: String): Probe {
        val lines = stdout.lineSequence().map { it.replace("\u0000", "").trim() }.toList()

        val selinux =
            lines
                .firstOrNull { it.startsWith(SELINUX_PREFIX) && it.length > SELINUX_PREFIX.length }
                // ★★ 真机实测（阶段 8）：`/proc/self/attr/current` 的输出**不一定带换行**，
                //    于是紧跟其后的 `echo $MARK_SELINUX_DONE` 会被粘成一行：
                //    `u:r:magisk:s0RF_MARK_SELINUX_DONE`。
                //    旧实现只做了 `trim()` ⇒ **把哨兵当成了 context 的一部分**并一路显示到 UI
                //    （阶段 8 的主页信息卡把它暴露了出来）。
                //    这里按下标截断，而不是按"是否等于哨兵"判断 —— 后者只覆盖"哨兵独占一行"。
                //    单测里的合成 stdout 是**带换行**的，所以这条路径一直没被覆盖到。
                ?.let { caret ->
                    val markerAt = caret.indexOf(MARK_SELINUX_DONE)
                    if (markerAt >= 0) caret.take(markerAt).trim() else caret
                }?.takeIf { it.length > SELINUX_PREFIX.length }
        val kernelLine = lines.firstOrNull { it.contains("Linux version") } ?: ""
        val completed = lines.any { it == MARK_PROBE_DONE }
        val ksuEvidence = lines.any { it == MARK_KSU_BIN } || lines.any { it == MARK_MAGISK_ALT }

        val flavor =
            when {
                kernelLine.contains(KERNELSU_MARKER) -> RootFlavor.KernelSU
                kernelLine.contains(APATCH_MARKER) -> RootFlavor.APatch
                ksuEvidence -> RootFlavor.KernelSU
                else -> RootFlavor.Unknown
            }
        return Probe(selinuxContext = selinux, rootFlavor = flavor, completed = completed)
    }
}
