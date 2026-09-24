package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.RootHealthProbe
import com.rootflow.domain.event.TripReason
import com.rootflow.runtime.RootShellManager
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RootHealthProbe] 实现（阶段 4，需求 §5.1 第 4 条）。
 *
 * ## 探针形态
 * `su -c echo <token>`，比对回显是否包含 token：
 * - **必须比对回显**，不能只看"没超时"——`exec` 返回非 0 或空输出同样说明通道不可用
 * - token 用固定字面量即可（内容不重要，重要的是"命令真的跑到了"）
 *
 * ## 不引入新 Root 通道
 * 走 `RootShellManager.exec(…, timeoutMillis)`（**数据通道**）。
 * 用数据通道而非控制通道是有意的：**熔断场景下真正要保的是"脚本能不能跑"**，
 * 而脚本就跑在数据通道上；控制通道可用但数据通道卡住同样是"跑不了"。
 *
 * ## 双层超时
 * `exec` 自带 `timeoutMillis`，外层再包一层 `withTimeoutOrNull`：
 * 前者依赖 libsu 的实现（实测可能不总是及时生效），后者由协程保证。
 * 两层都超时才算"无响应"，因此**宁可多等一次也不会误判**。
 */
@Singleton
class RootHealthProbeImpl
    @Inject
    constructor(
        private val rootShellManager: RootShellManager,
    ) : RootHealthProbe {
        override suspend fun probe(): RootHealthProbe.Snapshot {
            val started = System.currentTimeMillis()
            val result =
                withTimeoutOrNull(OUTER_TIMEOUT_MILLIS) {
                    rootShellManager.exec(PROBE_COMMAND, timeoutMillis = OUTER_TIMEOUT_MILLIS)
                }
            val elapsed = System.currentTimeMillis() - started

            if (result == null) {
                Log.w(TAG, "ROOT_PROBE timeout afterMs=$elapsed")
                return RootHealthProbe.Snapshot(
                    responsive = false,
                    elapsedMillis = elapsed,
                    detail = "no response within ${OUTER_TIMEOUT_MILLIS}ms",
                )
            }

            val echoed = result.stdout.contains(PROBE_TOKEN)
            val responsive = result.exitCode == 0 && echoed
            Log.i(
                TAG,
                "ROOT_PROBE responsive=$responsive afterMs=$elapsed exitCode=${result.exitCode} " +
                    "echoed=$echoed",
            )
            return RootHealthProbe.Snapshot(
                responsive = responsive,
                elapsedMillis = elapsed,
                detail =
                    if (responsive) {
                        null
                    } else {
                        "exitCode=${result.exitCode} echoed=$echoed stderr=${result.stderr.take(120)}"
                    },
            )
        }

        /**
         * 探测外部安全模式（需求 §5.3 第 2 条）。
         *
         * ## 两条探针，一次 shell 往返
         * | 探针 | 命令 | 命中条件 |
         * |---|---|---|
         * | 系统属性 | `getprop persist.sys.safemode` | 输出为 `1` |
         * | Magisk/APatch 安全模式 | `ls /data/adb/magisk/safemode`（APatch 无此路径） | 输出含 `safemode` |
         *
         * 合并成**一条** `execControl`：两条分开调用会让本已敏感的启动路径多一次 root 往返。
         *
         * ## 为什么用 `execControl` 而不是 `exec`
         * 探针只读系统状态、**不与正在跑的脚本争数据通道**（1c 的核心结论：
         * 数据通道同一时刻只能跑一个作业）。用控制通道可避免"启动探针把脚本输出清空"。
         *
         * ## 失败即"未命中"
         * 通道不可用 / 命令报错时返回 `null`（不是"检测到安全模式"）：
         * **把不可用误判成安全模式会让 App 永远不跑脚本**，那是比漏检更糟的失败方向。
         */
        override suspend fun detectExternalSafeMode(): TripReason? {
            val result = rootShellManager.execControl(PROBE_EXTERNAL_SAFEMODE_COMMAND)
            if (result.exitCode != 0) {
                Log.w(TAG, "SAFEMODE_EXTERNAL_PROBE command failed exit=${result.exitCode}: ${result.stderr}")
                return null
            }

            val output = result.stdout
            val propertyHit = output.lineSequence().any { it.trim() == "1" }
            val magiskHit = output.contains(MAGISK_SAFEMODE_MARKER)
            if (!propertyHit && !magiskHit) return null

            return TripReason.ExternalSafeMode(
                source = if (magiskHit) "magisk-safemode" else SYSTEM_PROPERTY,
            )
        }

        companion object {
            const val TAG: String = "RootFlow"

            /** 需求 §5.1 第 4 条：`su -c echo` 超过 10s 未返回即视为无响应。 */
            const val OUTER_TIMEOUT_MILLIS: Long = 10_000L

            /** 探针命令与回显标记（用固定字面量，避免任何变量展开干扰）。 */
            const val PROBE_TOKEN: String = "rf_root_probe_ok"
            const val PROBE_COMMAND: String = "echo $PROBE_TOKEN"

            /** 需求 §5.3 第 2 条的系统属性名。 */
            const val SYSTEM_PROPERTY: String = "persist.sys.safemode"

            /** Magisk 安全模式标志文件路径（APatch 上不存在，`ls` 会失败但不影响属性探针）。 */
            const val MAGISK_SAFEMODE_PATH: String = "/data/adb/magisk/safemode"

            /** 命中标记：`ls` 成功时输出里必然含该子串。 */
            const val MAGISK_SAFEMODE_MARKER: String = "safemode"

            /**
             * 单条命令同时取两个探针结果。
             *
             * `getprop` 在前、`ls` 在后，两段都用 `;` 连接（**不用 `&&`**：
             * `ls` 失败不得让整条命令失败，否则属性探针的结果会被丢弃）。
             * 结尾 `echo` 一个哨兵，确保输出非空且能确认命令跑到了末尾——
             * 这与 `AGENT_PROTOCOL.md §7.1` 的"不静默返回成功"同款纪律。
             */
            const val PROBE_EXTERNAL_SAFEMODE_COMMAND: String =
                "getprop $SYSTEM_PROPERTY; ls $MAGISK_SAFEMODE_PATH 2>/dev/null; echo rf_probe_done"
        }
    }
