package com.rootflow.data.env

import android.os.Build
import android.util.Log
import com.rootflow.BuildConfig
import com.rootflow.domain.env.EnvironmentInfoProvider
import com.rootflow.domain.env.EnvironmentSnapshot
import com.rootflow.domain.env.RootEnvironmentProbe
import com.rootflow.domain.env.RootFlavor
import com.rootflow.runtime.RootShellManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EnvironmentInfoProvider] 的唯一实现（阶段 6b）。
 *
 * ## 分层落点
 * 本类是**唯一**读 `Build` / `BuildConfig` 与发 root 探针的地方。
 * UI 只消费 [EnvironmentSnapshot]，因此 `ui/` 既不出现 `android.os.Build`，
 * 也不出现 `com.rootflow.runtime`（`AGENTS.md`：Root 操作只能出现在 `runtime/`，
 * 而 `ui` 只能经 `domain` 端口取值）。
 *
 * ## 为什么 root 引用是 `RootShellManager` 而**不是** libsu
 * 与 `RootHealthProbeImpl` 同款纪律：libsu 的引用点全项目只有 `RootShellManager` 一处。
 * 本类只调它的 `execControl`（**控制通道**），理由有两条：
 * 1. 探针是只读的短命令，**不得与正在跑的脚本争数据通道**
 *    （1c 的核心结论：数据通道同一时刻只能承载一个作业，抢占会清空脚本输出）
 * 2. 探测可能在主页打开时发生，而那一刻脚本**可能正在跑**（终端正在显示它的日志）
 *
 * ## 为什么用 `execControl` 的默认超时
 * `DEFAULT_CONTROL_TIMEOUT_MILLIS = 10s`。本探针是 5 条只读命令，正常在百毫秒级；
 * 超时即视为"通道不可用"，按 [refresh] 的降级路径处理（**不抛**）。
 *
 * ## 并发与取消
 * [refresh] 用 [mutex] 串行化：主页进入 + 用户连点刷新会并发调用，
 * 而 root 往返是稀缺资源（真机实测数百毫秒），并发探测既浪费又会让日志难以判读。
 * 取消（`viewModelScope` 被销毁）时 `CancellationException` **原样上抛**，
 * 且此时**不写入**半成品快照。
 */
@Singleton
class RootEnvironmentInfoProvider
    @Inject
    constructor(
        private val rootShellManager: RootShellManager,
    ) : EnvironmentInfoProvider {
        private val mutex = Mutex()

        private val _snapshot: MutableStateFlow<EnvironmentSnapshot?> = MutableStateFlow(null)

        override val snapshot: StateFlow<EnvironmentSnapshot?> = _snapshot.asStateFlow()

        /**
         * 探测一次（见端口 KDoc 的三条契约：不抛、可取消、失败降级）。
         *
         * 失败（root 通道不可用 / 命令超时）**不是异常路径**：
         * 设备未 root 是完全正常的用法，此时版本信息依然有效，
         * 只有 [EnvironmentSnapshot.rootFlavor] 退化为 `Unknown`、`selinuxContext` 为 `null`。
         */
        override suspend fun refresh() {
            mutex.withLock {
                val started = System.currentTimeMillis()
                val probe =
                    try {
                        rootShellManager.execControl(RootEnvironmentProbe.PROBE_COMMAND)
                    } catch (cancellation: CancellationException) {
                        // 取消不是故障：不写快照，原样上抛（与 RootShellManager.ensureReady 同款）。
                        throw cancellation
                    } catch (error: Throwable) {
                        warnSafely("HOME_ENV_PROBE failed: ${describe(error)}")
                        null
                    }

                val parsed = probe?.let { RootEnvironmentProbe.parse(it.stdout) }
                val snapshot = buildSnapshot(parsed = parsed)
                _snapshot.value = snapshot

                val elapsed = System.currentTimeMillis() - started
                logSafely(
                    "HOME_ENV_PROBE flavor=${snapshot.rootFlavor.key} " +
                        "selinux=${snapshot.selinuxContext ?: "-"} exit=${probe?.exitCode ?: -99} " +
                        "completed=${parsed?.completed ?: false} elapsedMs=$elapsed",
                )
            }
        }

        // ------------------------------------------------------------- Build 投影
        //
        // ## 为什么整段包在 `runCatching` 里（**实测踩到的坑，勿拆开**）
        // `android.os.Build` 的字段在纯 JVM（android.jar 桩）下是 `null`，
        // 而 Kotlin 视其为平台类型 ⇒ **读取点会插 intrinsic null-check** ⇒ 直接抛 NPE，
        // 且**抛在读取那一行**（不是 `EnvironmentSnapshot` 构造函数里）。
        //
        // 实测：`Build.VERSION.RELEASE` 为 null 时，`androidReleaseOrUnknown()` 里的
        // `runCatching { Build.VERSION.RELEASE }` **拦不住** —— NPE 在 runCatching 之前
        // 就抛出来了（单测 `RootEnvironmentInfoProviderTest` 连红 5 条，
        // 报 `Parameter specified as non-null is null: parameter androidRelease`）。
        // 因此**必须在构造快照这一层兜底**，逐字段的 runCatching 只能处理"读到了 null"，
        // 处理不了"读取点自己抛"。
        //
        // 降级值刻意用 `unknown` / `0` / `""`，**不用** `"?"` —— `?` 看起来像真实数据，
        // 而 `unknown` 一眼可知是缺失。

        /** 构造快照；任何 `Build` 读取失败都退化为"版本信息不可用"，不影响 root 部分的结论。 */
        private fun buildSnapshot(parsed: RootEnvironmentProbe.Probe?): EnvironmentSnapshot =
            runCatching {
                EnvironmentSnapshot(
                    appVersion = BuildConfig.VERSION_NAME,
                    androidRelease = Build.VERSION.RELEASE,
                    apiLevel = Build.VERSION.SDK_INT,
                    deviceModel = deviceModel(),
                    primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    rootFlavor = parsed?.rootFlavor ?: RootFlavor.Unknown,
                    selinuxContext = parsed?.selinuxContext,
                    capturedAtMillis = System.currentTimeMillis(),
                )
            }.getOrElse { error ->
                warnSafely("HOME_ENV_PROBE build projection failed: ${describe(error)}")
                EnvironmentSnapshot(
                    appVersion = UNKNOWN,
                    androidRelease = UNKNOWN,
                    apiLevel = 0,
                    deviceModel = UNKNOWN,
                    primaryAbi = "",
                    rootFlavor = parsed?.rootFlavor ?: RootFlavor.Unknown,
                    selinuxContext = parsed?.selinuxContext,
                    capturedAtMillis = System.currentTimeMillis(),
                )
            }

        /** `MANUFACTURER + MODEL`；两者都读不到时给 [UNKNOWN]。 */
        private fun deviceModel(): String =
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
                .ifBlank { UNKNOWN }

        /**
         * 安全日志（`android.util.Log` 在纯 JVM 下**是抛异常的**：`Method … not mocked`）。
         *
         * ## 为什么必须包起来（实测）
         * 单测 `execControl 抛异常时降级，不抛` 原本在 catch 分支里直接 `Log.w`，
         * 结果被测代码**自己抛出了 `RuntimeException`** ⇒ "降级"路径反而成了崩溃路径。
         * 生产环境不会走到这里（`Log` 真实可用），但它让这条路径**不可测** ——
         * 而不可测的降级路径等于没有降级（本仓库反复踩过的形态）。
         */
        private fun logSafely(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        /** 同上，WARN 级别。 */
        private fun warnSafely(message: String) {
            runCatching { Log.w(TAG, message) }
        }

        private fun describe(error: Throwable): String =
            error::class.java.name + ": " + (error.message ?: "<no message>")

        private companion object {
            const val TAG: String = "RootFlow"

            /** 读不到时的降级文案（**不是** `"?"`：那看起来像真实数据）。 */
            const val UNKNOWN: String = "unknown"
        }
    }
