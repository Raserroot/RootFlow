package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.fs.RootFlowPaths
import com.rootflow.domain.event.EventChannel
import com.rootflow.domain.event.EventDelivery
import com.rootflow.runtime.RootShellManager
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EventChannel] 的 FIFO 实现（P5 / 方案 §2.3 的 C1）。
 *
 * ## 三条实测约束（P0/P1 撞出来的，**勿省**）
 * | # | 约束 | 依据 | 落地 |
 * |---|---|---|---|
 * | ① | FIFO 权限必须**显式 `chmod 666`** | `mkfifo -m 666` 被 umask 削成 `0644`，而实测 `prw-r--r--` 时 `shell` 域写入失败 | [open] 的命令里永远跟一个 `chmod` |
 * | ② | 所有写操作必须**包 `timeout`** | 无读端时 `open(O_WRONLY)` **挂住**（实测 RC=124） | [deliver] 固定写成 `timeout N sh -c '… > $FIFO'` |
 * | ③ | 投递 = 一次 `su` 调用 ⇒ **必须合并** | App 是 `untrusted_app`，不能直接打开 FIFO | 合并由调用方（`TriggerDispatcherImpl` 的防抖窗口）负责，本类只负责"一次投递" |
 *
 * ## ★ 为什么走**控制通道**（`execControl`）而不是数据通道
 * `RootShellManager` 的类文档写明：libsu 的数据通道**同一 shell 上不可能并发两个作业**
 * （`execTask` 是 `synchronized`，且每次作业前 `cleanInputStream(STDOUT/STDERR)`）。
 * 而投递事件时**正有一个脚本在这个通道上跑**（那正是要投递给它的原因）——
 * 走数据通道会把正在运行的脚本输出清空。
 *
 * 控制通道本来就是为"运行期的第二个动作"（kill）建的，投递与它同级：都是
 * **不打断正在跑的作业**的旁路动作。
 *
 * ## ★ 为什么用 base64 而不是直接 `echo '事件行'`
 * 事件行是 JSON（含引号、可能含用户 payload）。把它拼进 `su -c` 的命令串要过
 * **两层 shell 解析**，任何一处转义失误都会变成命令注入或静默截断。
 * base64 的字符集是 `[A-Za-z0-9+/=]` —— **零引号风险**，代价是多一个 `base64 -d`
 * （toybox 自带，脚本正文写入路径已在用同一招）。
 */
@Singleton
class FifoEventChannelImpl
    @Inject
    constructor(
        private val shellManager: RootShellManager,
    ) : EventChannel {
        private val lock = Any()

        /**
         * `scriptId → 它当前那条通道的 runId`（由 [open] / [close] 维护）。
         *
         * ## 为什么需要它（投递侧只知道 scriptId）
         * 事件到达时，`TriggerDispatcher` 手上只有"这个事件 + 一批订阅了它的 `scriptId`"，
         * 而通道路径含的是 **`runId`**（每次运行一个）。让 dispatcher 自己去查
         * `RunSessionRegistry` 会多一条依赖边，而这张映射**本来就该由持有通道的一方维护**：
         * 开通道时写入、关通道时清除，查询只发生在投递那一步。
         *
         * ## 并发
         * `open` / `deliver` / `close` 来自不同协程（runner 的启动与收尾、dispatcher 的分发），
         * 因此由 [lock] 保护。同一脚本**不会**同时有两条通道（`RunAdmissionGate` 的重入拒绝
         * 保证了这一点），所以"一键一值"是安全的；[close] 仍按 `runId` 反查删除，
         * 即使将来允许并发也只会清掉属于那一次运行的那条。
         */
        private val channelByScript = mutableMapOf<Long, String>()

        override suspend fun open(
            runId: String,
            scriptId: Long,
        ): String? {
            val fifo = RootFlowPaths.eventFifo(runId)
            // `test -p` 收尾：确认建出来的**确实是 FIFO**（而不是被 umask/已存在文件顶掉）。
            // 少这一步的话，"通道建好了"只是一句自述。
            val command =
                "mkdir -p ${RootFlowPaths.IPC} && rm -f $fifo && " +
                    "mkfifo $fifo && chmod ${RootFlowPaths.FIFO_MODE} $fifo && test -p $fifo"
            val result =
                runCatching { shellManager.execControl(command) }
                    .getOrElse { error ->
                        Log.w(TAG, "EVENT_CHANNEL_OPEN_FAILED script=$scriptId runId=$runId: ${describe(error)}")
                        return null
                    }
            if (result.exitCode != 0) {
                Log.w(
                    TAG,
                    "EVENT_CHANNEL_OPEN_FAILED script=$scriptId runId=$runId exit=${result.exitCode} " +
                        "err=${result.stderr.trim()} (script will not receive ROOTFLOW_EVENT_FIFO)",
                )
                return null
            }
            synchronized(lock) { channelByScript[scriptId] = runId }
            Log.i(
                TAG,
                "EVENT_CHANNEL_OPENED script=$scriptId runId=$runId fifo=$fifo mode=${RootFlowPaths.FIFO_MODE}",
            )
            return fifo
        }

        override suspend fun deliver(
            scriptId: Long,
            line: String,
        ): EventDelivery {
            val runId =
                synchronized(lock) { channelByScript[scriptId] }
                    // 没有通道 ⇒ 该脚本此刻没在运行（或它的通道没开出来）。
                    // 这是 P5 之后最常见的一种结局，**不是错误**：事件不再启动脚本。
                    ?: return EventDelivery.NotRunning
            return deliverTo(runId = runId, line = line)
        }

        /** 真正写 FIFO（按 `runId`）。[deliver] 只负责"先查出该脚本那条通道"。 */
        private suspend fun deliverTo(
            runId: String,
            line: String,
        ): EventDelivery {
            val fifo = RootFlowPaths.eventFifo(runId)
            val encoded = Base64.getEncoder().encodeToString(line.toByteArray(Charsets.UTF_8))
            val command =
                "timeout $DELIVER_TIMEOUT_SECONDS sh -c \"echo $encoded | base64 -d > $fifo\""
            val result =
                runCatching { shellManager.execControl(command) }
                    .getOrElse { error -> return EventDelivery.Failed(describe(error)) }
            return when (result.exitCode) {
                0 -> EventDelivery.Delivered

                // `timeout` 命中：**没有读端**（通道在，只是没人读）。
                // 与 `NotRunning` 不同：这里脚本确实在监管名单里，只是刚好没在 `read`。
                TIMEOUT_EXIT_CODE -> EventDelivery.NoReader

                else ->
                    EventDelivery.Failed(
                        "exit=${result.exitCode} ${result.stderr.trim()}".trim(),
                    )
            }
        }

        override suspend fun close(runId: String) {
            // 先清映射：之后到达的事件会落到 `NotRunning`，而不是写进一条正在被删的管道。
            synchronized(lock) { channelByScript.entries.removeIf { it.value == runId } }
            // 幂等：`rm -f` 对不存在的文件不报错 ⇒ 收尾路径重复调用安全。
            // 成功不打日志（每次运行都会走到这里，与 DAEMON_STARTED 同量级的噪声），
            // 失败才留痕 —— 残留的 FIFO 会在下一次运行的 `rm -f` 里被清掉，不是灾难。
            runCatching { shellManager.execControl("rm -f ${RootFlowPaths.eventFifo(runId)}") }
                .onFailure { Log.w(TAG, "EVENT_CHANNEL_CLOSE_FAILED runId=$runId: ${describe(it)}") }
        }

        private fun describe(error: Throwable): String =
            error::class.java.simpleName + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /**
             * 投递的 `timeout`（秒）。
             *
             * 取 2：方案 §10 约束 ② 要求"N 取小值（如 2–3 秒）"。
             * 下限考量：一次 `su` 往返本身约 300ms 量级，再加上 root shell 的调度，
             * 1 秒可能把"读端稍慢"误判成"没有读端"。2 秒是这个量级的宽裕余量，
             * 又远小于用户能感知的卡顿。
             */
            const val DELIVER_TIMEOUT_SECONDS: Int = 2

            /** `timeout` 命中超时的退出码（P0 实测 `RC=124`）。 */
            const val TIMEOUT_EXIT_CODE: Int = 124
        }
    }
