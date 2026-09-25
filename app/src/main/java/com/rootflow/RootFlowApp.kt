package com.rootflow

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.rootflow.data.event.AlarmEventSource
import com.rootflow.data.event.AlarmSyncCoordinator
import com.rootflow.data.event.BootMarkerHolder
import com.rootflow.data.event.BootloopGuard
import com.rootflow.data.event.CircuitBreakerImpl
import com.rootflow.data.event.EventBusHolder
import com.rootflow.data.event.EventSourceRegistry
import com.rootflow.data.event.SafeModeSnapshot
import com.rootflow.data.event.TriggerScheduleProvider
import com.rootflow.data.service.KeepAliveHolder
import com.rootflow.domain.event.BootMarkerStore
import com.rootflow.domain.event.BootSemantics
import com.rootflow.domain.event.CircuitBreaker
import com.rootflow.domain.event.EventBus
import com.rootflow.domain.event.EventSourceState
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.event.PermissionGrant
import com.rootflow.domain.event.PermissionStatusProvider
import com.rootflow.domain.event.TriggerDispatcher
import com.rootflow.domain.model.SystemEvent
import com.rootflow.domain.repository.TriggerRepository
import com.rootflow.domain.service.KeepAliveWatchdog
import com.rootflow.runtime.LogLine
import com.rootflow.runtime.LogStream
import com.rootflow.runtime.ProcessGroupManager
import com.rootflow.runtime.RootShellManager
import com.rootflow.runtime.RunContext
import com.rootflow.runtime.ScriptEntity
import com.rootflow.runtime.ShellScriptRuntime
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * RootFlow 应用入口。
 *
 * ## 阶段 6e：本类从"验证脚手架"收敛回"生产启动器"
 * 6a–6d 期间，本类承载了 1a 起累积的 **14 个真机验证入口**（`verify*`）。
 * 触发器勾选 UI 落地后，它们的观测价值大多已被 UI 覆盖，因此 6e 按
 * `STAGE6E-PLAN.md §5.2` 的逐条裁定做了如下处置：
 *
 * | 处置 | 内容 |
 * |---|---|
 * | **拆分**（生产逻辑留、验证外壳去） | [restoreSafeModeState]（原 `verifySafeModeWiring` 的生产半）· [startRunWiring]（原 `verifyRunWiring` 的生产半） |
 * | **保留**（进 [runDiagnostics] 闸门，**默认不跑**） | `verifyRootChannel` / `diagnoseShellStreams` / `verifyScriptRuntime` / `verifyControlChannel` / `verifyProcessGroupKill` / `verifyEventSources` / `verifyStage4Fixtures`（造数，P4 保留） |
 * | **删除**（已有 UI 替代路径） | `verifyTriggerVisibility` / `verifyForceEnableTriggers` / `verifyFixtureTriggerScope` / `verifyTripSafeMode` / `verifyRestoreSafeMode` / `verifyExemptRun` |
 * | **删除**（验证数据） | `RunVerificationFixtures`（类 + 单测；其唯一调用点随 `verifyRunWiring` 的生产半拆分而去掉） |
 *
 * ### 为什么保留的那 6 个仍然值得留（每一条都有"没有替代路径"的理由）
 * | 入口 | 它证明什么 | 为什么 UI 替代不了 |
 * |---|---|---|
 * | `verifyRootChannel` | `su -c id` 拿到 uid=0 | 主页环境卡只有一帧的间接证据；它是**启动期**唯一直接探针 |
 * | `diagnoseShellStreams` | 本机 libsu 独立 stderr 通道返回 0B（阶段 1b 的环境特化结论） | 换 ROM / 换 libsu 版本时的第一诊断手段 |
 * | `verifyScriptRuntime` | 脚本端到端（stdout/stderr 分流 + 退出码） | 1b 的 golden 只保证**包裹形态**，不保证设备上真跑通 |
 * | `verifyControlChannel` | 数据/控制双通道运行期可发 kill | 需求 §4.1；UI 的"手动运行"也验不了"运行中 kill" |
 * | `verifyProcessGroupKill` | `kill -TERM -$pgid` → 探测 → 必要时 KILL | 需求 §4.2 的硬要求；1c 真机记录的判据靠它复现 |
 * | `verifyEventSources` | 事件源注册与逐源状态、8 项权限档位 | 12/13 个事件的**真机可用性**从未逐条验过；chip UI 只证明"能写进 Room" |
 *
 * ## 两条纪律（改本类前必读）
 * 1. **`onCreate` 的调用顺序是硬约束**（见该函数的注释）：事件总线 → 调度器 →
 *    boot 标记 → 安全模式恢复 → boot 补发。调换任何一步都会产生"脚本先跑、
 *    安全模式后恢复"这类竞态。
 * 2. **不要在这里给事件源 `start()` 兜底**（阶段 5 的所有权裁定，见 `onCreate` 的注释）。
 */
@HiltAndroidApp
class RootFlowApp : Application() {
    @Inject
    lateinit var rootShellManager: RootShellManager

    @Inject
    lateinit var shellScriptRuntime: ShellScriptRuntime

    /** 阶段 3c.1：事件总线（3b 已实现，本阶段起在真机上安装进 [EventBusHolder]）。 */
    @Inject
    lateinit var eventBus: EventBus

    /** 阶段 3c.1：推送型事件源注册表。 */
    @Inject
    lateinit var eventSourceRegistry: EventSourceRegistry

    /** 阶段 3c.1：权限状态（用于真机判读权限档位结论；阶段 5 起 8 项）。 */
    @Inject
    lateinit var permissionStatusProvider: PermissionStatusProvider

    /** 阶段 3c.2：闹钟源（诊断闸门用它调一次 `sync()`，使 `ALARM_SYNC` 可观测）。 */
    @Inject
    lateinit var alarmEventSource: AlarmEventSource

    /** 阶段 3c.2：闹钟数据来源（3d 起为真实实现：从 `TriggerRepository` 取数）。 */
    @Inject
    lateinit var triggerScheduleProvider: TriggerScheduleProvider

    /** 阶段 3d：端到端接线的事件调度器（3b 起存在，3d 起真正 `start()`）。 */
    @Inject
    lateinit var triggerDispatcher: com.rootflow.data.event.TriggerDispatcherImpl

    /** 阶段 3d：闹钟对账（启动先清后同步 + 分发后重同步）。 */
    @Inject
    lateinit var alarmSyncCoordinator: AlarmSyncCoordinator

    /** 阶段 3d：boot 标记（D9 的持久化半）。 */
    @Inject
    lateinit var bootMarkerStore: BootMarkerStore

    /**
     * 阶段 4：真机验证数据的幂等预置（**6e 起只在诊断闸门内被调用**）。
     *
     * ## 为什么 6e 保留它（**经批准的裁定 P4**，勿顺手删）
     * 阶段 4 有 4 项真机未补验（B1 脚本超时 / B3 高频自启 / C1 失败风暴跨进程 /
     * 第 9 项 root 无响应）。而本机 **toybox 无 `sqlite3`** 且**禁止从 PC 侧写库**
     * （6c 立的硬约束）⇒ 删掉它，那 4 项就从"一条 `setprop` 造数"退化成"手搓四组脚本"。
     * 它是 `verifyStage4Fixtures` 的**唯一数据来源**，因此两者必须同进同退。
     */
    @Inject
    lateinit var stage4VerificationFixtures: com.rootflow.data.run.Stage4VerificationFixtures

    /**
     * 阶段 4：熔断器实现（需要两个端口之外的能力：`restoreStateFromDisk` /
     * `attachBootloopGuard` / `seedFailureHistoryFromHistory`，它们不在
     * [CircuitBreaker] 端口上——端口只暴露"触发与恢复"，启动期接线不该进 domain 契约）。
     */
    @Inject
    lateinit var circuitBreakerImpl: CircuitBreakerImpl

    /** 阶段 4：Bootloop 兜底（需求 §5.3 第 4 条）。 */
    @Inject
    lateinit var bootloopGuard: BootloopGuard

    /**
     * 阶段 4：安全熔断器**端口**（启动期接线只读它的状态与两个动作）。
     *
     * 与 [circuitBreakerImpl] 的分工：端口上是"触发与恢复"（`tripForBootloop` /
     * `probeExternalSafeMode` / `safeMode` / `tripReason`），实现类上才是启动期专用能力
     * （`restoreStateFromDisk` / `attachBootloopGuard` / `seedFailureHistoryFromHistory`）。
     * 之所以不把这些塞进端口：那会让 domain 契约承担"启动顺序"这种实现细节。
     */
    @Inject
    lateinit var circuitBreaker: CircuitBreaker

    /**
     * **P3（总开关重构）：总闸端口**。
     *
     * 启动期只做一件事：把库里的 `master_enabled` 恢复进内存快照（[restoreMasterSwitchState]）。
     * 之后的读全发生在热路径上（`TriggeredScriptRunner` 的准入闸门、`DaemonSupervisor` 的对账），
     * 因此这里注入**端口**而不是实现类 —— 启动期不需要实现类的任何额外能力。
     *
     * 与 [circuitBreakerImpl] 形成对照：那一个确实有端口上没有的启动期能力
     * （`restoreStateFromDisk` / `attachBootloopGuard` / `seedFailureHistoryFromHistory`），
     * 所以它必须注入实现类；总闸没有这种需求，注入端口即可（少暴露一层）。
     */
    @Inject
    lateinit var masterSwitch: MasterSwitch

    /**
     * 阶段 4：进程组管理（**唯一实例**，PGID 文件落 App 私有 `cacheDir`）。
     *
     * 在此之前本类是 `private val … by lazy { ProcessGroupManager(rootShellManager) { File(cacheDir, …) } }`
     * —— 那等于**第二个**实例，而脚本执行时用的是 Hilt 注入的那个。
     * 两个实例若各自持有不同路径，熔断的第 2 步（终止所有运行中脚本）会读到不存在的
     * PGID 文件而**静默失效**。现在全项目只有 `CircuitBreakerModule` 一处路径来源。
     */
    @Inject
    lateinit var processGroupManager: ProcessGroupManager

    /** 阶段 4：主键查询触发器（诊断闸门逐条回报还原结果用）。 */
    @Inject
    lateinit var triggerRepository: TriggerRepository

    /**
     * 阶段 12c：保活看门狗（与前台服务互相知道对方，见 `KeepAliveService` 的 KDoc）。
     *
     * 本类对它的全部义务只有一件：**装进 [KeepAliveHolder]** ——
     * 心跳由闹钟广播送达，而接收器无法构造注入。
     * 安装必须**早于任何广播可能到达**（与 [EventBusHolder.install] 同一条理由）。
     */
    @Inject
    lateinit var keepAliveWatchdog: KeepAliveWatchdog

    /**
     * 阶段 12c：安全模式的三态快照（见 `SafeModeSnapshot` 的 KDoc）。
     *
     * 本类负责**第一个写入点**：启动链里 `restoreStateFromDisk()` 跑完之后，
     * 把那个确定结论交给它。第二个写入点在 `ForegroundServiceController` 的状态发布里。
     */
    @Inject
    lateinit var safeModeSnapshot: SafeModeSnapshot

    /**
     * 诊断闸门的一次性作用域。
     *
     * ## ★ 它**不**承载生产逻辑（阶段 6e 收敛后的形态）
     * 6e 之前，本作用域承载全部 `verify*` 入口；现在**只有** [runDiagnostics] 会用到它，
     * 而 `runDiagnostics` 默认为空（需 `setprop debug.rf.diagnostics 1`）。
     * 生产启动路径（[restoreSafeModeState] / [startRunWiring] / [sendBootIfFirstStartSinceBoot]）
     * **全部走 `onCreate` 的同步路径**，不依赖本作用域 —— 否则"诊断开关关着"会让生产行为消失。
     *
     * 注意：此作用域**不是** Application 级长期作用域；诊断入口是一次性短任务，
     * 随进程结束而终止（`AGENTS.md`：所有长期运行任务必须有明确的生命周期作用域）。
     */
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 启动链的作用域（阶段 6e 新增）。
     *
     * ## 它承载什么
     * `restoreSafeModeState()` → `sendBootIfFirstStartSinceBoot()` → `startRunWiring()`
     * 三步，**顺序执行**（理由见 [onCreate] 的启动链注释）。
     *
     * ## 为什么不能省掉它、直接在 `onCreate` 里同步调用
     * 这三步是 `suspend`（要读 Room、要 `su` 探针），而 `Application.onCreate` 在**主线程**上 ——
     * 同步调用等于把磁盘 I/O 与 root 往返压进冷启动路径（ANR 风险）。
     *
     * ## 为什么不能挂在 [diagnosticsScope] 上
     * 那是诊断入口的车道，而**诊断入口默认不跑**。生产启动链挂上去会让
     * "开关没开"变成"生产逻辑没执行"——这正是 6e 从 `runGated` 里搬出来的原因。
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 长驻探测作业的**独立车道**（阶段 1c 第二步修复新增）。
     *
     * 存在理由：数据通道与控制通道是**两条** shell（见 [RootShellManager] 类文档），
     * "运行期 kill" 本身就要求被 kill 的脚本正在跑。因此 `verifyControlChannel` 里的
     * 60 秒探测作业**必须与诊断入口并行**——但它**不能**跑在 [diagnosticsScope] 上：
     * [diagnosticsGate] 会把闸门内的任务排成一条队列，若该长作业占用闸门所在车道，
     * 后续入口将排队等它 60 秒，而 `Job.cancel()` **无法中断阻塞中的
     * [RootShellManager.exec]**（libsu 的 `exec()` 是阻塞调用，取消只是放弃结果）。
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 诊断入口的**串行闸门**（阶段 1c 第二步修复新增）。
     *
     * 保护对象是**诊断入口本身**，不是单个数据通道作业。
     *
     * 为什么必须串行：`onCreate` 原先把 5 个 `verify*` 并发 launch 到同一作用域，
     * 而它们都会压同一条 libsu 数据通道（`Shell.getShell()` 全局单例）。而
     * [RootShellManager] 类文档已写明：libsu 的 `ShellImpl.execTask` 是 `synchronized`
     * 且每次作业前 `cleanInputStream(STDOUT/STDERR)` —— **同一个 shell 上不可能并发
     * 两个作业**，否则正在运行的脚本输出会被清空。
     *
     * 真机实证（阶段 1c 第二步失败）：1b 自检运行期间另一个作业启动，导致 1b 自检的
     * 尾段中断 —— `rm -f /tmp/.rf_err_<handle.id>` 未执行（该文件在真机上残留，内容
     * 恰为 `oops\n`，文件名 UUID 与 logcat 的 `SCRIPT_VERIFY handle=…` 逐字符一致），
     * 于是 stderr 分流与 `__RF_EXIT__` 标记双双缺失，退出码回退到一个无意义的值。
     */
    private val diagnosticsGate = Mutex()

    /**
     * 在 [diagnosticsGate] 内执行一个诊断入口，保证数据通道作业串行。
     *
     * 约定：闸门内**不得** `join()` 长驻作业（会让后续入口饿死）；
     * 长驻作业请交给 [probeScope]。取锁顺序固定（外层单锁），不存在死锁。
     *
     * 阶段 1c 第 1 步诊断（2026-09-19）：真机日志显示多个入口仍并行，
     * 而本地最小复现证明本结构串行。为把「闸门是否真的排他」从推断变成事实，
     * 此处加三行日志：`GATE_TRY`（提交后、取锁前）、`GATE_ENTER`（进入临界区）、
     * `GATE_EXIT`（离开临界区）。判读：
     * - `GATE_ENTER` / `GATE_EXIT` 必须严格配对，下一个 `GATE_ENTER` 不得早于
     *   上一个 `GATE_EXIT`；若早于，即为闸门失效的**直接证据**；
     * - 三者都带 `name` 与线程名，可同时判定「是否被重复调用」与「跑在哪个线程」。
     */
    private fun runGated(
        name: String,
        block: suspend () -> Unit,
    ): Job =
        diagnosticsScope.launch {
            Log.i(TAG, "GATE_TRY name=$name thread=${Thread.currentThread().name}")
            diagnosticsGate.withLock {
                Log.i(TAG, "GATE_ENTER name=$name thread=${Thread.currentThread().name}")
                try {
                    block()
                } finally {
                    Log.i(TAG, "GATE_EXIT name=$name thread=${Thread.currentThread().name}")
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        // ===== 阶段 3d：端到端正式接线（非验证代码）=====
        //
        // ## 顺序是硬约束（勿调换）
        // ① `EventBusHolder.install` 必须**在任何广播可能到达之前**：进程启动后系统随时可能
        //    投递电源/电量/闹钟广播，若那时 holder 还是空的，事件会被丢弃（接收器只记 warning）
        // ② `dispatcher.start()` 必须**早于任何 `send()`**：调度器要先订阅总线才能收到事件
        //    （`InMemoryEventBus` 虽用 `replay = capacity` 兜底，但依赖缓冲而不是显式顺序
        //    会让"事件为什么没触发"多一种可能）
        // ③ D9 的 boot 补发放在**②之后、且在所有启动期作业之后**：先订阅再补发
        // ④ 闹钟对账放在安全模式恢复之后：它要读 Room，且失败不应影响 boot 语义
        EventBusHolder.install(eventBus)
        // ★ 阶段 12c：看门狗同理必须在**任何广播可能到达之前**装好。
        //   心跳闹钟可能在本进程刚被唤起时就到（那正是它被设计出来的场景）
        //   ⇒ 晚装一步就是"这次心跳被丢弃"（接收器只记一行警告）。
        KeepAliveHolder.install(keepAliveWatchdog)
        triggerDispatcher.start()

        // 阶段 3d（D9）：App 启动补 boot 语义 —— 本设备上唯一可靠的 boot 路径
        // （ColorOS 在分发阶段拦截 BOOT_COMPLETED，见 PROJECT_STATE.md 的「设备环境特性」）。
        // `elapsedRealtime` 单调、自开机起算：只在重启后回退，故可判定"距上次启动是否重启过"。
        //
        // ★ 阶段 4：**标记仍然在这里配**（它是 D9 自己的持久化半，与熔断无关），
        // 但 `sendBootIfFirstStartSinceBoot()` 的**调用点在整个启动链的更后面**
        // （见下面 `LAUNCH_CHAIN`）—— 因为 boot 事件必须在 `restoreStateFromDisk()` 之后发出，
        // 否则重启后的第一次启动会绕过安全模式。
        BootMarkerHolder.configure(bootMarkerStore)

        // ===== 阶段 5：事件源的启停**已移出 Application**（勿调回！）=====
        //
        // 阶段 3c.1~4 在此处无条件 `eventSourceRegistry.start()`（进程域）。
        // 需求 §2.1 明写 `screen_on` / `screen_off` 这类**动态注册**的源
        // 「只能动态注册，**须驻留前台服务**」，而阶段 3 没有前台服务，
        // 只能挂在 Application 域 —— 那正是偏离项 **D4**（"进程被系统回收后即失效"）。
        //
        // 阶段 5 引入 `KeepAliveService` 后，启停的唯一所有者是
        // `ForegroundServiceController`（`register()` / `unregister()`），D4 正面关闭。
        //
        // ★ 为什么**不能**在这里保留一次 `start()` 兜底：`EventSourceRegistryImpl` 的
        //    `started` 标志让第二次调用变成空操作 ⇒ 服务的 `start()` 会被这里"吃掉"，
        //    于是"服务停了但事件源还活着"——正是 D4 要修的形态，且**完全静默**。
        //    `ForegroundServiceControllerTest` 有断言守着这条所有权。
        //
        // 另注：服务由 `MainActivity` 拉起（用户可见的入口），**不在这里**启动——
        // `Application.onCreate` 可能发生在后台进程启动路径上，此时
        // `startForegroundService` 会撞上 Android 12+ 的后台前台服务启动限制。

        // ===== 阶段 6e：启动链（**同步、有序、无条件**）=====
        //
        // ## ★ 为什么 6e 把它从 `runGated` 里搬了出来
        // 6e 之前这两步跑在 `runGated("verifySafeModeWiring", then = { boot })` 里，
        // 而 `runGated` 是**异步**的（launch 到 diagnosticsScope）。那时它必须异步，
        // 因为要跟其它验证入口排队。现在验证入口默认不跑（诊断闸门关着），
        // 生产逻辑继续挂在闸门上只有一个后果：**"诊断开关"成了生产行为的前提**。
        // 因此 6e 把生产链改回同步顺序执行：
        //
        // ⓪ **P3：[restoreMasterSwitchState]** —— 总闸决定"此刻允许不允许跑脚本"，
        //    因此**必须最先**恢复。晚一步的后果不是"总闸失效"，而是一帧**误拒**：
        //    下一步会立刻发出 boot 事件，而准入闸门读的是**内存快照** —— 若快照此刻
        //    还是初值 `false`，"总闸开着 + 订阅了 boot"的脚本这一次就**不会跑**
        //    （日志里的拒绝理由看着完全正确，结论却是错的）。
        // ① [restoreSafeModeState]：进程重启后 `_safeMode` 是 `false`，不先恢复，
        //    **重启就成了绕过熔断的手段** —— 必须早于任何脚本可以执行
        // ② [sendBootIfFirstStartSinceBoot]（D9）：必须在 ① **之后**发出，
        //    否则重启后的第一次启动会绕过安全模式（那正是熔断最不该漏的那一刻）
        // ③ [startRunWiring]：闹钟对账要读 Room，且失败不应影响 boot 语义
        // ④ [runDiagnostics]：**默认不做任何事**（需 `setprop debug.rf.diagnostics 1`）
        //
        // ## 为什么 ⓪①②③ 走一条协程（而不是各自 `launch`）
        // 它们**必须按顺序**发生：`sendBootIfFirstStartSinceBoot()` 不得早于
        // `restoreStateFromDisk()`（否则重启后第一次启动会绕过熔断）。
        // 放进同一条协程是最直接的顺序保证 —— 分成多个 `launch` 就会回到 6e 之前
        // 那条"靠闸门排队才碰巧有序"的脆弱形态。
        //
        // 这条协程跑在主线程之外（Room 读写 + `su` 探针），因此**不阻塞** `onCreate`：
        // 系统投递的广播由 `EventBusHolder` / 接收器各自处理，不依赖本链完成。
        startupScope.launch {
            restoreMasterSwitchState()
            restoreSafeModeState()
            sendBootIfFirstStartSinceBoot()
            startRunWiring()
        }
        runDiagnostics()
    }

    /**
     * 启动期**总闸恢复**（总开关重构 **P3** 的生产语义）。
     *
     * ## 它为什么必须存在（不能靠"快照初值 false 也行"）
     * 快照初值是 `false`（安全默认）⇒ 不恢复的话，**用户上次明明开着总闸，
     * 本次启动却什么都不跑**，而且日志里每一行看着都对（准入闸门拒绝的理由是
     * "总闸关闭"，那是一句真话）。恢复一次就把"库里的用户意图"搬进内存。
     *
     * ## 判读（真机 `findstr`）
     * - `MASTER_SWITCH_RESTORED enabled=true inserted=false …` —— 用户上次开着，本次继续
     * - `MASTER_SWITCH_RESTORED enabled=false inserted=true …` —— 全新安装，按安全默认补了一行
     * - `MASTER_SWITCH_RESTORE_FAILED … (staying disabled — safe default)` —— 读库失败：
     *   保守方向（维持关闭），且**不阻止**后续启动链
     * - `MASTER_SWITCH_STARTUP done enabled=…` —— 本步的收口
     */
    private suspend fun restoreMasterSwitchState() {
        // `restoreFromStore()` 内部已把异常收成"安全默认 + 留痕"，这里的 runCatching 是
        // 第二道保险（与 `restoreSafeModeState` 同款形态）：启动链上的一步**不得**让整条链断掉。
        val enabled =
            runCatching { masterSwitch.restoreFromStore() }
                .onFailure { Log.w(TAG, "MASTER_SWITCH_STARTUP restore failed: ${it.message}") }
                .getOrDefault(false)
        Log.i(TAG, "MASTER_SWITCH_STARTUP done enabled=$enabled")
    }

    /**
     * 启动期**安全熔断接线**（生产语义，非验证代码）。
     *
     * ## 顺序理由（逐步见 [onCreate] 的注释）
     * 恢复状态 → 接 bootloop 钩子 → 评估启动 → 灌失败历史 → 探测外部安全模式。
     *
     * ## 判读（真机 `findstr`）
     * - `SAFEMODE_RESTORED_FROM_FLAG path=…` —— 上次熔断过，本次开机**立即**回到安全模式
     * - `BOOTLOOP_EVALUATE … verdict=… crashCount=N` —— 崩溃计数处置
     * - `BOOTLOOP_TRIPPED crashes=3 reason=bootloop` —— 兜底熔断
     * - `SAFEMODE_SEED_FAILURES seeded=N windowMillis=600000` —— 跨进程失败历史预置
     * - `SAFEMODE_EXTERNAL_PROBE not detected` / `detected key=external-safe-mode`
     * - `SAFEMODE_STARTUP done restored=… bootloopTripped=… …`
     */
    private suspend fun restoreSafeModeState() {
        Log.i(TAG, "SAFEMODE_STARTUP begin")

        // ① 从磁盘恢复安全模式（重启不得绕过熔断）
        val restored =
            runCatching { circuitBreakerImpl.restoreStateFromDisk() }
                .onFailure { Log.w(TAG, "SAFEMODE_STARTUP restore failed: ${it.message}") }
                .getOrDefault(false)

        // ② 接 bootloop 钩子（必须在 evaluateStartup 之前）
        circuitBreakerImpl.attachBootloopGuard(bootloopGuard)

        // ③ 评估本次启动的崩溃计数（可能直接熔断）
        val tripped =
            runCatching { bootloopGuard.evaluateStartup() }
                .onFailure { Log.w(TAG, "SAFEMODE_STARTUP bootloop evaluate failed: ${it.message}") }
                .getOrDefault(false)
        if (tripped) {
            // guard 只判定；动作在这里驱动（避免 CircuitBreakerImpl ↔ BootloopGuard 构造环）
            circuitBreaker.tripForBootloop(crashes = bootloopGuard.crashCount)
        }

        // ④ 跨进程失败历史 → 风暴窗口
        runCatching { circuitBreakerImpl.seedFailureHistoryFromHistory() }
            .onFailure { Log.w(TAG, "SAFEMODE_STARTUP seed failures failed: ${it.message}") }

        // ⑤ 外部安全模式（只读不写 flag）
        val external =
            runCatching { circuitBreaker.probeExternalSafeMode() }
                .onFailure { Log.w(TAG, "SAFEMODE_STARTUP external probe failed: ${it.message}") }
                .getOrNull()

        Log.i(
            TAG,
            "SAFEMODE_STARTUP done restored=$restored bootloopTripped=$tripped " +
                "external=${external?.reasonKey ?: "-"} safeMode=${circuitBreaker.safeMode.value} " +
                "tripReason=${circuitBreaker.tripReason.value?.reasonKey ?: "-"}",
        )

        // ★ 阶段 12c：把"确定的"安全模式结论交给看门狗快照（**三态**，见 SafeModeSnapshot 的 KDoc）。
        //   为什么必须在这里写：心跳广播会冷启动进程，而看门狗判定时本方法可能还没跑完 ——
        //   快照留在 `null` 时它选择"不拉服务、30 秒后再看一次"（保守），
        //   而写完之后它才可能正确地自愈。**这是"未知"与"确认不在熔断中"的分界线。**
        //   运行期变化由 `ForegroundServiceController` 的每次状态发布刷新（第二个写入点）。
        safeModeSnapshot.update(circuitBreaker.safeMode.value)
        Log.i(TAG, "KEEPALIVE_SAFE_MODE_SNAPSHOT safeMode=${circuitBreaker.safeMode.value}")
    }

    /**
     * D9 的启动侧实现：判定"距上次开机后是否首次启动"，是则补发一条 `SystemEvent.Boot`。
     *
     * ## 顺序（3b 明确要求：**先订阅再补发**）
     * [BootSemantics] 的判定必须在 [TriggerDispatcher.start] **之后**执行——
     * 否则事件会在无人订阅时发出（`InMemoryEventBus` 的 `replay` 虽能兜住，但仍应显式保证顺序）。
     *
     * ## 写标记的时机
     * 无论是否补发都**无条件覆写**（[BootMarkerStore] 的契约）：只在首次写会让
     * "同周期内第二次启动"与"重启后第一次启动"无法区分。
     *
     * ## ★ 它是**生产逻辑**，不是验证入口（阶段 6e 去掉临时标注的理由）
     * 本设备上系统 `BOOT_COMPLETED` 被 ColorOS 在分发阶段拦截（3b 的 `-unverified` 遗留），
     * 因此这条路径是**唯一可靠的 boot 语义**（`ROADMAP.md`：D9 是必要设计，不是兜底）。
     * 谁要删它，先回答"那本机怎么触发 boot 脚本"。
     */
    private fun sendBootIfFirstStartSinceBoot() {
        val elapsed = SystemClock.elapsedRealtime()
        val previous = bootMarkerStore.read()
        val firstSinceBoot = BootSemantics.isFirstStartSinceBoot(elapsed, previous)
        bootMarkerStore.write(elapsed)
        Log.i(
            TAG,
            "BOOT_SEMANTICS elapsed=$elapsed previous=${previous ?: "<none>"} " +
                "firstSinceBoot=$firstSinceBoot",
        )

        if (!firstSinceBoot) {
            Log.i(TAG, "BOOT_SUPPLEMENT skipped (same boot cycle)")
            return
        }
        eventBus.send(SystemEvent.Boot)
        Log.i(TAG, "BOOT_SUPPLEMENT sent=${SystemEvent.Boot.eventId}")
    }

    /**
     * 阶段 3d 的正式接线（**生产语义**）：闹钟对账 + 注册"分发后重同步"钩子。
     *
     * ## 内容
     * 1. `AlarmSyncCoordinator.start()` → 两个 action 各 `cancelAll` → 从 Room 读全量
     *    → `AlarmEventSource.sync()`
     * 2. 注册"分发后重同步"钩子：触发器可能刚被增删改（**6e 起用户在 UI 里就能改**），
     *    闹钟该对账了
     *
     * ## 判读（真机 `findstr`）
     * - `ALARM_CANCEL_ALL_DONE actions=[com.rootflow.TIME, com.rootflow.INTERVAL]`
     * - `ALARM_PLAN_LOADED triggers=N alarms=M`
     * - `ALARM_SYNC_DONE reason=start registered=[…]`
     *
     * ## ★ 阶段 6e：函数名与日志去掉了 `verify` 字样
     * 原名 `verifyRunWiring`、日志前缀 `RUN_WIRING_VERIFY`，容易被读成"验证代码"而误删 ——
     * 而它**两条都是生产逻辑**（3d 修掉的那个"每次分发都 cancelAll"缺陷就在这条路径上）。
     */
    private suspend fun startRunWiring() {
        Log.i(TAG, "RUN_WIRING begin")

        // 闹钟对账（真实语义）。异常在 coordinator 内转为告警，不会让 App 起不来。
        alarmSyncCoordinator.start()

        // 分发后重同步：注册式接线（见 TriggerDispatcherImpl.addPostDispatchAction 的 KDoc）
        triggerDispatcher.addPostDispatchAction { alarmSyncCoordinator.requestSync(reason = "after-dispatch") }

        Log.i(TAG, "RUN_WIRING done")
    }

    /**
     * 阶段 6e：**开发诊断闸门**（默认不做任何事）。
     *
     * `setprop debug.rf.diagnostics 1` ⇒ 本次启动跑 [diagnosticsList] 里的 6 个入口。
     *
     * ## 为什么合并成**一个**开关（而不是 6e 之前那样各有一个）
     * 6e 之前有 6 个 `debug.rf.*` 开关（trip / restore / presets / bootloop / exempt /
     * force_enable / only_fixture / reset_crash）。它们各自都带一句"用完务必清 0"——
     * 因为**留着的副作用会污染下一次验证**（全开触发器、每次启动熔断…）。
     * 合并成一个"这些入口整体跑不跑"的开关，就没有"漏关某一个"这个失败形态了。
     *
     * ## `debug.*` 属性落 tmpfs、**重启不保留**
     * 正合"只在这一次诊断里开"的语义（见 `STAGE5-PLAN.md` 的裁定）。
     *
     * ## 判读
     * 不设属性时：日志里**不应出现**任何 `GATE_TRY` / `SCRIPT_VERIFY` / `EXEC_DIAG` /
     * `EVENT_SOURCE_VERIFY` / `CONTROL_CHANNEL` / `PG_VERIFY` 行。
     * 设了之后：`GATE_ENTER` / `GATE_EXIT` 成对出现，且下一个 `GATE_ENTER`
     * 不早于上一个 `GATE_EXIT`（闸门排他的直接证据）。
     */
    private fun runDiagnostics() {
        diagnosticsScope.launch {
            if (!onOffProperty(DEBUG_DIAGNOSTICS_PROP)) {
                Log.i(
                    TAG,
                    "DIAGNOSTICS skipped reason=debug switch off " +
                        "(set `$DEBUG_DIAGNOSTICS_PROP` to 1 and restart to run the 6 dev entries)",
                )
                return@launch
            }
            Log.i(TAG, "DIAGNOSTICS begin entries=${diagnosticEntries.size}")
            diagnosticEntries.forEach { (name, entry) ->
                runGated(name) { entry() }
            }
        }
    }

    /**
     * 诊断入口表（**顺序 = 执行顺序**）。
     *
     * 每个入口都经 [runGated] 串行（理由见 [diagnosticsGate] 的 KDoc）。
     * 保留理由逐条写在类 KDoc 的表里 —— **不要**因为"看起来像验证代码"而顺手删：
     * 它们各自覆盖一条没有 UI 替代路径的能力。
     */
    private val diagnosticEntries: List<Pair<String, suspend () -> Unit>> =
        listOf(
            "verifyRootChannel" to { verifyRootChannel() },
            "diagnoseShellStreams" to { diagnoseShellStreams() },
            "verifyScriptRuntime" to { verifyScriptRuntime() },
            "verifyControlChannel" to { verifyControlChannel() },
            "verifyProcessGroupKill" to { verifyProcessGroupKill() },
            "verifyEventSources" to { verifyEventSources() },
            // 阶段 4 的造数入口（P4 保留）：只在 `debug.rf.presets` / `debug.rf.bootloop` /
            // `debug.rf.reset_crash` 显式置 1 时才真的写库；否则只打 skip 行。
            "verifyStage4Fixtures" to { verifyStage4Fixtures() },
            // 总开关重构的 P1 技术验证（见 `docs/总开关机制-方案.md` §8）。
            "verifyEventIpcFifo" to { verifyEventIpcFifo() },
        )

    /**
     * **总开关重构 · P1 技术验证**：App 的 root 通道能否向 FIFO 投递事件。
     *
     * ## 为什么必须由 App 自己跑（不能靠 adb 模拟）
     * P0 已实测（见 `docs/总开关机制-方案.md` §8 的表格）：
     * - **root 域（`u:r:magisk:s0`）读写 FIFO 可用** ✅
     * - **`shell` 域写 FIFO 被 SELinux 拦** ❌（同目录同上下文，普通文件能写、FIFO 不能）
     * - **无读端时写入会阻塞** ⚠️（`open(O_WRONLY)` 挂住，必须靠 `timeout` 兜）
     *
     * ⇒ App 进程是 **`u:r:untrusted_app:s0`**，**绝不能直接打开 FIFO**，
     * 唯一路径是 `su -c '…'` 让 magisk 域的 root shell 去写。
     * 而"App 经 libsu 驱动 root shell 写 FIFO"这一环**无法用 adb 模拟**
     * （release 包不可 debuggable ⇒ `run-as` 不可用）⇒ 只能在 App 内验证。
     *
     * ## 判读（三条都要过）
     * 1. `FIFO_ID_WRITER` 里出现 `context=u:r:magisk:s0` ⇒ 写端确实在 root 域
     * 2. `FIFO_APP_WRITE_RC=0` ⇒ **App 经 libsu 投递成功**（本验证的核心结论）
     * 3. `FIFO_READBACK_HELLO=P1_OK` ⇒ **常驻脚本侧能读到**（整条链路闭环）
     *
     * 任一条不满足 ⇒ 退 C2（每脚本独立事件文件 + `tail -F`）。
     *
     * ## 两条硬约束（P0 实测的教训，写进命令里）
     * - **必须显式 `chmod 666`**：`mkfifo -m 666` 会被 umask 削成 `0644`
     * - **写操作必须包 `timeout`**：无读端时 `open(O_WRONLY)` 会挂住，拖死 libsu 的 exec
     */
    private suspend fun verifyEventIpcFifo() {
        val dir = "/data/local/tmp/rootflow/p1"
        val fifo = "$dir/ev.fifo"
        val payload = "P1_OK"
        Log.i(TAG, "FIFO_BEGIN dir=$dir")

        // ① 预检：root 身份 + 上下文（确认写端确实在 magisk 域）
        val who = rootShellManager.exec("id")
        Log.i(TAG, "FIFO_ID_WRITER rc=${who.exitCode} out=${who.stdout.trim()} err=${who.stderr.trim()}")

        // ② 建 FIFO：显式 chmod（`-m` 会被 umask 削）
        val setup =
            rootShellManager.exec(
                "mkdir -p $dir && rm -f $fifo && mkfifo $fifo && chmod 666 $fifo && ls -la $fifo",
            )
        Log.i(TAG, "FIFO_SETUP rc=${setup.exitCode} out=${setup.stdout.trim()} err=${setup.stderr.trim()}")

        // ③ 起读端（root 侧，后台跑并写回读到的内容）
        //    读端用 `timeout` 兜底，否则它自己也会永远挂在 open(O_RDONLY) 上。
        val readerCmd =
            "sh -c 'timeout 8 cat $fifo > $dir/readback.txt 2>/dev/null' & " +
                "sh -c 'sleep 1; echo $payload | timeout 4 tee $fifo > /dev/null; echo WRITER_RC=\$?'"
        val write = rootShellManager.exec(readerCmd, timeoutMillis = FIFO_PROBE_TIMEOUT_MILLIS)
        Log.i(
            TAG,
            "FIFO_APP_WRITE rc=${write.exitCode} out=${write.stdout.trim()} err=${write.stderr.trim()}",
        )

        // ④ 读回验证（链路闭环）：常驻脚本侧读到的就是这里的内容
        val readback = rootShellManager.exec("cat $dir/readback.txt 2>/dev/null")
        val got = readback.stdout.trim()
        Log.i(TAG, "FIFO_READBACK_HELLO=$got")

        // ⑤ 收尾：不留探针文件在设备上
        rootShellManager.exec("rm -f $fifo $dir/readback.txt && rmdir $dir 2>/dev/null; true")

        val pass = got == payload
        if (pass) {
            Log.i(TAG, "FIFO_VERDICT PASS app can deliver events via root shell")
        } else {
            Log.w(TAG, "FIFO_VERDICT FAIL readback='$got' expected='$payload' => fall back to C2")
        }
        Log.i(TAG, "FIFO_END")
    }

    /**
     * 阶段 4 真机验证数据的预置入口（**6e 起只在诊断闸门内**；见 `diagnosticEntries`）。
     *
     * ## 三件事，各有独立开关（互不牵连）
     * 1. `debug.rf.presets=1` ⇒ 写成四个脚本与各自触发器（A3/B1/B2/B3 的前提）
     *    - 幂等：已存在则只补缺的那一项（`Stage4VerificationFixtures` 的逐项粒度）
     *    - **不覆盖已有脚本的字段**：`timeoutSec` / `runOnSafeMode` 属用户配置
     * 2. `debug.rf.bootloop=1` ⇒ 把崩溃计数预置到"再启动一次即熔断"（C2 的前提）
     *    - 由 **App 自己写** `SharedPreferences`，避免 root/`run-as` 手写导致的
     *      属主变化与"读到默认值"这类**静默**失败
     * 3. `debug.rf.reset_crash=1` ⇒ 清 bootloop 崩溃标记（C2 之后的**必需收尾**：
     *    留着它会让后续每次启动都再熔断一次，表现为"设备莫名其妙又进安全模式"）
     *
     * ## 判读（真机 `findstr`）
     * - `STAGE4_FIXTURE <name>: script created id=… timeoutSec=… runOnSafeMode=…`
     * - `STAGE4_FIXTURE <name>: trigger created id=… event=…`
     * - `STAGE4_FIXTURE skipped: all scripts and triggers already present`
     * - `STAGE4_FIXTURE bootloop preset bootId=… crashCount=2 healthy=false`
     * - `STAGE4_VERIFY done presets=… bootloop=… resetCrash=…`
     */
    private suspend fun verifyStage4Fixtures() {
        Log.i(TAG, "STAGE4_VERIFY begin")

        val presets = onOffProperty(DEBUG_PRESETS_PROP)
        if (presets) {
            runCatching { stage4VerificationFixtures.ensureFixtures() }
                .onFailure { Log.w(TAG, "STAGE4_VERIFY fixtures failed: ${it.message}") }
        }

        val bootloop = onOffProperty(DEBUG_BOOTLOOP_PROP)
        if (bootloop) {
            runCatching { stage4VerificationFixtures.presetBootloopForNextStart() }
                .onFailure { Log.w(TAG, "STAGE4_VERIFY bootloop preset failed: ${it.message}") }
        }

        // 清空 bootloop 崩溃标记（C2 留下的 `crashCount=2 + healthy=false` 会让
        // **下一次启动**再熔断一次，即使 `debug.rf.bootloop` 已置 0）。
        val resetCrash = onOffProperty(DEBUG_RESET_CRASH_PROP)
        if (resetCrash) {
            runCatching { stage4VerificationFixtures.resetCrashMarker() }
                .onFailure { Log.w(TAG, "STAGE4_VERIFY crash reset failed: ${it.message}") }
        }

        Log.i(
            TAG,
            "STAGE4_VERIFY done presets=$presets bootloop=$bootloop resetCrash=$resetCrash " +
                "(presets=`$DEBUG_PRESETS_PROP`, bootloop=`$DEBUG_BOOTLOOP_PROP`, " +
                "resetCrash=`$DEBUG_RESET_CRASH_PROP`)",
        )
    }

    /** 读一个 `debug.*` 开关是否等于 `1`（读不到按关处理，不抛）。 */
    private suspend fun onOffProperty(prop: String): Boolean =
        runCatching { rootShellManager.execControl("getprop $prop").stdout.trim() == "1" }.getOrDefault(false)

    /**
     * **事件源与权限档位诊断**（`PROJECT_STATE.md` 决策 11）。
     *
     * ## 为什么必须保留这个入口
     * `screen_on` / `screen_off` / `unlock` 是**动态注册**的：不启动进程就没有任何可观测点。
     * 且 12/13 个事件的**真机可用性**从未逐条验过（3c.1/3c.2 只覆盖 4 源 3 action）。
     * 6e 的 chip UI 只证明"配置能写进 Room"，**不证明"事件真能触发"** ——
     * 删掉本入口，任何事件相关的结论都只能靠推断。
     *
     * ## ★ 阶段 5：本入口的时序（判读时必须知道，否则会误判为"源全挂了"）
     * 事件源的启停已移交 `KeepAliveService` / `ForegroundServiceController`（D4 关闭）。
     * `Application.onCreate` **先于** `MainActivity.onCreate` 执行，而服务由后者拉起 ⇒
     * 本入口在主线程首次启动时通常看到**全部 `started=false reason=not started`**。
     * **那不是缺陷**：几毫秒后服务注册，日志里会出现
     * `FGS_REGISTRY_STARTED sources=…:on,…`（阶段 5 的权威判据）。
     * 真机验证因此以 `FGS_REGISTRY_STARTED` 为准，本入口只用于看权限档位。
     *
     * ## 判读方式（真机）
     * - `PERMISSION name=… grant=… reason=…` —— 权限档位结论（决策 10；阶段 5 起 8 项）
     * - `PERMISSION_SETTINGS name=… target=…` —— 该项的跳转目标（`-` = 无入口）
     * - `EVENT_SOURCE id=… started=… reason=…` —— 逐源状态（决策 7：原因具体到权限）
     * - 之后再手动锁屏/解锁/插拔电源/开关 Wi-Fi，应看到 `SCREEN_RECEIVED` / `POWER_RECEIVED` /
     *   `BATTERY_RECEIVED` 与成对的 `EVENT_BUS sent=…`
     */
    private suspend fun verifyEventSources() {
        Log.i(TAG, "EVENT_SOURCE_VERIFY begin")

        permissionStatusProvider.refresh()
        permissionStatusProvider.current().forEach { (permission, state) ->
            Log.i(
                TAG,
                "PERMISSION name=${permission.permission} grant=${state.grant} reason=${state.reason}",
            )
            // NOT_APPLICABLE 说明该档位下无处授权；其余需要用户手动授权的项给出跳转目标
            val target =
                if (state.grant == PermissionGrant.NOT_APPLICABLE) {
                    null
                } else {
                    permissionStatusProvider.settingsTargetFor(permission)
                }
            Log.i(
                TAG,
                "PERMISSION_SETTINGS name=${permission.permission} target=${target?.action ?: "-"}",
            )
        }

        eventSourceRegistry.status().forEach { status ->
            val running = status.state.isRunning
            val reason =
                when (val state = status.state) {
                    is EventSourceState.Unavailable -> state.reason
                    EventSourceState.Running -> "-"
                    EventSourceState.NotStarted -> "not started"
                }
            Log.i(TAG, "EVENT_SOURCE id=${status.sourceId} started=$running reason=$reason")
        }

        // 阶段 3c.2 起本条为**观测**：真实对账已由 `startRunWiring` 的
        // `AlarmSyncCoordinator.start()` 完成（它内部同样调 `sync()`）。
        // 这里再调一次是幂等的（`sync()` 按上一轮快照对账），且让 `ALARM_SYNC_VERIFY`
        // 这行在真机上确定出现，便于与 `ALARM_SYNC_DONE` 交叉核对。
        val plan = alarmEventSource.sync(triggerScheduleProvider.currentAlarms())
        Log.i(
            TAG,
            "ALARM_SYNC_VERIFY registered=${plan.registered} cancelled=${plan.cancelled} " +
                "conflicts=${plan.skippedConflicts} invalid=${plan.invalid} " +
                "note=pull-from-room-via-RoomTriggerScheduleProvider",
        )

        Log.i(TAG, "EVENT_SOURCE_VERIFY done")
    }

    /**
     * 阶段 1c **第一步**真机诊断入口：确认「第二条 shell（控制通道）」能建立，
     * 且能在数据通道被阻塞作业占用时，经控制通道终止**整个进程组**。
     *
     * ## 阶段 1c 第 3 步改造（Bug 3 修复）
     *
     * 原实现自带一套「裸 `kill -TERM` → 自带 `ps | grep` 探测 → **无条件** `kill -KILL`」，
     * 完全不经过 [ProcessGroupManager]：因此「TERM 已让整组消失时应跳过 KILL」这条逻辑
     * **在这条路径上从未存在过**，真机表现就是 `alive_after_TERM=[0]` 之后仍发出 KILL 并报
     * `No such process`（纯噪声）。现改为调用被测实现 [ProcessGroupManager.terminateRun]，
     * 使真机验证的正是生产语义。
     *
     * 保留的独立观测：`alive_after_TERM` / `alive_after_KILL` 仍打印，但它们**不驱动**
     * 任何 kill 决策，仅作交叉验证（口径与 [ProcessGroupManager] 的按-PGID 反查不同）。
     */
    private suspend fun verifyControlChannel() {
        val runId = "1c-ctrl"
        val beginMs = System.currentTimeMillis()
        Log.i(TAG, "CONTROL_CHANNEL_BEGIN")
        val ready = rootShellManager.ensureControlReady()
        val elapsed = System.currentTimeMillis() - beginMs
        Log.i(
            TAG,
            "CONTROL_CHANNEL_RESULT ready=$ready elapsedMs=$elapsed " +
                "available=${rootShellManager.controlChannelAvailable} " +
                "controlPid=${rootShellManager.controlShellPidOrNull()}",
        )
        if (!ready) {
            // 走到这里说明重试耗尽：按方案降级（不称 1c 完成）。
            Log.w(TAG, "CONTROL_CHANNEL_DEGRADED runtime kill unavailable")
            return
        }

        // 数据通道上启动一个长驻**进程组**；PGID 文件由 ProcessGroupManager 统一管理
        // （路径与读取方式不再由本入口自建）。正文经 sh -c 'sleep 60 & wait' 进入独立进程组。
        val inner = shellScriptRuntime.wrapForShellForTest("sleep 60")
        val command = processGroupManager.wrap(inner, runId)
        val pgidPath = processGroupManager.shellPathFor(runId)
        processGroupManager.cleanup(runId)

        // 作业与诊断入口**并行**（运行期 kill 要求脚本正在跑），故走 probeScope 独立车道。
        val scriptJob =
            probeScope.launch {
                val started = System.currentTimeMillis()
                val result = rootShellManager.exec(command)
                Log.i(
                    TAG,
                    "SCRIPT_JOB_FINISHED afterMs=${System.currentTimeMillis() - started} " +
                        "exitCode=${result.exitCode}",
                )
            }

        // 经**控制通道**终止整组：内部为 TERM → 宽限 3s → 探测 → 必要时 KILL → 再探测。
        val report =
            withTimeoutOrNull(VERIFY_TERMINATE_TIMEOUT_MILLIS) {
                processGroupManager.terminateRun(runId)
            }
        if (report == null) {
            Log.w(
                TAG,
                "CONTROL_VERIFY_ABORT terminate timed out after ${VERIFY_TERMINATE_TIMEOUT_MILLIS}ms",
            )
            scriptJob.cancel()
            processGroupManager.cleanup(runId)
            return
        }
        Log.i(
            TAG,
            "CONTROL_VERIFY_TERMINATE pgidResolved=${report.pgidResolved} " +
                "termSent=${report.termSent} killed=${report.killed} " +
                "goneAfterTerm=${report.goneAfterTerm} probeFailed=${report.probeFailed}",
        )

        // 独立口径观测（不驱动决策）：是否还有 sleep 60 存活。
        val aliveAfterTerm =
            rootShellManager.execControl("ps -A -o pid,args | grep 'sleep 60' | grep -v grep | wc -l")
        Log.i(TAG, "CONTROL_VERIFY alive_after_TERM=[${aliveAfterTerm.stdout.trim()}]")

        delayMs(500)
        val aliveAfterKill =
            rootShellManager.execControl("ps -A -o pid,args | grep 'sleep 60' | grep -v grep | wc -l")
        Log.i(TAG, "CONTROL_VERIFY alive_after_KILL=[${aliveAfterKill.stdout.trim()}]")

        scriptJob.cancel()
        processGroupManager.cleanup(runId)
        Log.i(TAG, "CONTROL_VERIFY_DONE pgidFile=$pgidPath")
    }

    /**
     * 阶段 1c **第二步**真机诊断入口：`ProcessGroupManager` 的完整链路。
     *
     * 与 [verifyControlChannel] 的分工：此处专门验证 [ProcessGroupManager] ——
     * `setsid` 包装 + PGID 文件 + `readPgidWithRetry` + `terminate`（含 TERM→探测→KILL 递进）。
     *
     * 关键观察点（与真机第一步的偏差修正对应）：
     * - TERM 已杀光整组时，**不得**再发 KILL（第一步实测 KILL 返回 `No such process`）
     * - 运行退出码应为 143（128+15）
     */
    private suspend fun verifyProcessGroupKill() {
        val runId = "1c-verify"
        Log.i(TAG, "PG_VERIFY_BEGIN runId=$runId")

        val inner = shellScriptRuntime.wrapForShellForTest("echo PG_OUT; sleep 60")
        val command = processGroupManager.wrap(inner, runId)
        val started = System.currentTimeMillis()

        // 数据通道上后台执行（阻塞调用，故必须与下面的 PGID 读取并行）。
        // 走 probeScope 独立车道：本作业在闸门内启动但运行在闸门外，因此不会
        // 被闸门排队，也不会阻塞后续入口。`cancel()` 只放弃结果，不中断阻塞中的 exec。
        val job =
            probeScope.launch {
                val result = rootShellManager.exec(command)
                Log.i(
                    TAG,
                    "PG_VERIFY_SCRIPT_DONE afterMs=${System.currentTimeMillis() - started} " +
                        "exitCode=${result.exitCode} stdout=[${escape(result.stdout)}]",
                )
            }

        val pgid = processGroupManager.readPgidWithRetry(runId, attempts = PGID_READ_ATTEMPTS)
        Log.i(TAG, "PG_VERIFY_PGID=$pgid")
        if (pgid <= 0) {
            Log.w(TAG, "PG_VERIFY_ABORT pgid unresolved")
            job.cancel()
            return
        }

        Log.i(TAG, "PG_VERIFY_ALIVE_BEFORE=${processGroupManager.isGroupAlive(pgid)}")
        // 有界兜底：与 verifyControlChannel 共用同一上限（防读 PGID 卡死时干等）。
        val report =
            withTimeoutOrNull(VERIFY_TERMINATE_TIMEOUT_MILLIS) {
                processGroupManager.terminate(pgid)
            }
        if (report == null) {
            Log.w(TAG, "PG_VERIFY_ABORT terminate timed out after ${VERIFY_TERMINATE_TIMEOUT_MILLIS}ms")
            job.cancel()
            processGroupManager.cleanup(runId)
            return
        }
        Log.i(
            TAG,
            "PG_VERIFY_TERMINATE pgidResolved=${report.pgidResolved} termSent=${report.termSent} " +
                "killed=${report.killed} goneAfterTerm=${report.goneAfterTerm} " +
                "probeFailed=${report.probeFailed}",
        )
        Log.i(TAG, "PG_VERIFY_ALIVE_AFTER=${processGroupManager.isGroupAlive(pgid)}")

        // 终止后作业应很快返回；此处等待是**有界**的（进程组已被 terminate 处理）。
        job.join()
        processGroupManager.cleanup(runId)
        Log.i(TAG, "PG_VERIFY_DONE")
    }

    /** 诊断用的毫秒等待；诊断入口是短生命周期的一次性任务，用阻塞等待最简单。 */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun delayMs(ms: Long) {
        Thread.sleep(ms)
    }

    /**
     * 阶段 1a 真机诊断入口：调用 [RootShellManager.ensureReady] 与 `exec("id")`，
     * 把 stdout 打到 logcat（tag `RootFlow`）。
     *
     * ## 为什么保留（而不是交给主页「环境信息卡」）
     * 主页那张卡是**只读一帧的间接证据**；本入口是**启动期唯一直接的 root 探针**，
     * 且它是"装完包先确认 root 通不通"的第一条命令（换机 / 换 Root 方案时尤其如此）。
     */
    private suspend fun verifyRootChannel() {
        Log.i(TAG, "APP_ONCREATE begin root verify")
        val ready = rootShellManager.ensureReady()
        Log.i(TAG, "ROOT_VERIFY ready=$ready state=${rootShellManager.state.value}")
        val result = rootShellManager.exec("id")
        if (result.isSuccess) {
            Log.i(TAG, "ROOT_VERIFY uid=${result.stdout.trim()}")
        } else {
            Log.i(
                TAG,
                "ROOT_VERIFY failed exitCode=${result.exitCode} stderr=${result.stderr}",
            )
        }
    }

    /**
     * 阶段 1b stderr 排查探针。
     *
     * 目的：判定 stderr 是在 libsu 管道层丢失，还是在 shell 层根本没写进 stderr。
     * 三条探针分别是：
     * 1. `raw`：脚本内 `>&2` → 期望出现在 stderr 通道。
     * 2. `combined`：本方案假设 —— stderr 合并进 stdout，用互不相同的占位标记区分两路，
     *    命令永远成功，退出码由脚本自己 echo 出来（避免污染 libsu 的 `$?` 收尾）。
     * 3. `file`：把 stderr 写进临时文件再 `cat`，同时 echo 一个计数供切分。
     *
     * ## 为什么保留
     * 本机（OnePlus 8 / APATCH / libsu 5.2.2）"交互式 su shell 的 stderr 不可用"
     * 是一条**环境特化结论**（`ROADMAP.md` 的「平台约束」一节），而阶段 1b 的
     * 脚本层中转 workaround 正是它逼出来的。换 ROM / 换 Root 方案 / 升级 libsu 时，
     * 第一件要重做的事就是跑这三条探针。
     */
    private suspend fun diagnoseShellStreams() {
        val raw = "printf \"R1\\nR2\\n\"; printf \"E1\\nE2\\n\" >&2; exit 0"
        probe("raw", raw)

        val combined =
            "printf \"R1\\nR2\\n\"; printf \"E1\\nE2\\n\" >&2; echo \"__RF_EC__$?\""
        probe("combined", combined)

        val file =
            "printf \"F1\\nF2\\n\"; printf \"G1\\nG2\\n\" >&2; " +
                "echo \"__RF_EC__$?\"; wc -l < /data/local/tmp/rf_err.txt"
        probe("file", file)
    }

    /** 执行单条探针并打印原始两路内容（含字节数与转义后的原文）。 */
    private suspend fun probe(
        label: String,
        content: String,
    ) {
        val result = rootShellManager.exec("sh -c '$content'")
        Log.i(
            TAG,
            "EXEC_DIAG[$label] out=${result.stdout.length}B err=${result.stderr.length}B " +
                "code=${result.exitCode} success=${result.isSuccess}",
        )
        Log.i(TAG, "EXEC_DIAG[$label] OUT=[${escape(result.stdout)}]")
        Log.i(TAG, "EXEC_DIAG[$label] ERR=[${escape(result.stderr)}]")
    }

    /** 把换行转义成可见形式，便于在 logcat 单行内看清边界。 */
    private fun escape(text: String): String = text.replace("\n", "\\n").replace("\r", "\\r")

    /**
     * 阶段 1b 真机诊断入口：跑一段内联自检脚本，把每一条 [LogLine] 打到 logcat。
     *
     * 自检脚本行为：stdout 3 行、stderr 1 行、退出码 0。
     * 期望 logcat 中出现：2 条 SYS 头部行（started / exec）+ 3 条 STDOUT + 1 条 STDERR
     * + 1 条 SYS 退出行。
     *
     * 正文由 [ShellScriptRuntime] 以 `sh -c '<正文>'` 包裹后提交，因此这里可以
     * 自由使用 shell 语法（`>&2` 重定向、`exit`）。`printf` 的格式串用双引号包裹，
     * 以便在 logcat 中直观看出 `\n` 是转义而非真实换行。
     *
     * ## 为什么保留
     * `ShellScriptLocalShellTest` 的 golden 只保证**包裹形态**逐字节正确，
     * 而"在真机上真的跑起来、stdout/stderr 分流对不对、退出码解析对不对"
     * 只有本入口能一次性回答（它也是 1b 唯一的正式验证手段）。
     */
    private suspend fun verifyScriptRuntime() {
        val script =
            ScriptEntity(
                id = SELF_TEST_SCRIPT_ID,
                name = "stage-1b-selftest",
                language = "shell",
                enabled = true,
                timeoutSec = 0,
                autoDisableOnFail = false,
                runOnSafeMode = false,
                content = SELF_TEST_SCRIPT,
            )
        Log.i(TAG, "SCRIPT_VERIFY begin script=${script.name}")

        // ★ 11e 补丁4：runId 现在由调用方给出 —— 它同时决定 `.rf_pgid_<id>` /
        //   `.rf_err_<id>` 的文件名与日志里那个 id。自检路径自己造一个即可
        //   （它不落库、不参与熔断），下面那条日志把它与 `handle.id` 一起打出来，
        //   真机上"两者是否相等"因此可以一眼判读。
        val runId = UUID.randomUUID().toString()
        val handle =
            shellScriptRuntime.run(
                script = script,
                ctx = RunContext(triggerEvent = null, env = emptyMap()),
                runId = runId,
            )
        // handle.id 会出现在这条日志里：它同时是 /tmp/.rf_err_<handle.id> 的文件名组成部分，
        // 用于把真机上残留的中转文件与具体运行一一对应（阶段 1c 第二步诊断手段）。
        Log.i(TAG, "SCRIPT_VERIFY runId=$runId handle=${handle.id} startedAt=${handle.startedAt}")

        handle.output.collect { line: LogLine ->
            Log.i(TAG, "SCRIPT_LOG stream=${line.stream} text=${line.text}")
        }
        Log.i(TAG, "SCRIPT_VERIFY done stream=${LogStream.SYS} count=ok")
    }

    private companion object {
        /** logcat tag；诊断输出统一以此 tag 打印。 */
        const val TAG = "RootFlow"

        /** 自检脚本在报告中的标识（非持久化，仅用于日志可读性）。 */
        const val SELF_TEST_SCRIPT_ID = -1L

        /** 阶段 1b 自检脚本正文：stdout 3 行、stderr 1 行、退出码 0。 */
        const val SELF_TEST_SCRIPT =
            "printf \"one\\ntwo\\nthree\\n\"; printf \"oops\\n\" >&2; exit 0"

        /** 1c 诊断入口读取 pgid 文件的最大尝试次数。 */
        const val PGID_READ_ATTEMPTS = 10

        /**
         * **开发诊断总开关**（阶段 6e 新增，替代 6e 之前那 8 个 `debug.rf.*` 开关）。
         *
         * `setprop debug.rf.diagnostics 1` ⇒ 本次启动跑 `diagnosticEntries` 里的 6 个入口。
         * `debug.*` 属性落 tmpfs，**重启不保留** —— 正合"只在这一次诊断里开"的语义。
         *
         * ## 为什么合成一个
         * 6e 之前每个入口一个开关，每个都带一句"用完务必清 0"，因为**留着的副作用会污染
         * 下一次验证**（`force_enable_triggers` 会全开触发器、`trip` 会每次启动熔断…）。
         * 合成一个之后，"漏关某一个"这个失败形态就不存在了。
         */
        const val DEBUG_DIAGNOSTICS_PROP = "debug.rf.diagnostics"

        /**
         * P1 探针里 libsu `exec` 的等待上限（见 [verifyEventIpcFifo]）。
         *
         * 比探针脚本内的 `timeout 8` 宽 3 秒：让**脚本内**的 timeout 先生效
         * —— 那正是要验证的兜底机制；若让 libsu 的等待先超时，它只能放弃**等待**、
         * 无法中断已在跑的原生命令（见 `RootShellManager.exec` 的 KDoc）。
         */
        const val FIFO_PROBE_TIMEOUT_MILLIS: Long = 11_000L

        /**
         * 阶段 4 **造数**开关（A3/B1/B2/B3 的前提）。
         *
         * `setprop debug.rf.presets 1` 打开。一次性使用：预置是**幂等**的，
         * 跑过一次之后即使忘了关也不会有副作用（只会打 `STAGE4_FIXTURE <name>: … present`）。
         *
         * 与 [DEBUG_BOOTLOOP_PROP] / [DEBUG_RESET_CRASH_PROP] 一并在
         * `verifyStage4Fixtures` 里被读（该入口只在诊断闸门打开时才跑）。
         */
        const val DEBUG_PRESETS_PROP = "debug.rf.presets"

        /**
         * Bootloop 预置开关（C2 的前提）。
         *
         * `setprop debug.rf.bootloop 1` ⇒ 把崩溃计数写到"再启动一次即熔断"。
         * **务必用完清 0**：它每次启动都会重置计数，留着会让后续每次启动都快速熔断。
         */
        const val DEBUG_BOOTLOOP_PROP = "debug.rf.bootloop"

        /**
         * Bootloop 崩溃标记清场开关（C2 之后的必需收尾）。
         *
         * `setprop debug.rf.reset_crash 1` ⇒ 本次启动清空 crashMarker
         * （`crashCount=0` + 清掉"未健康"标记）。
         *
         * **为什么必需**：C2 预置的 `crashCount=2 + healthy=false` 会持久留在
         * SharedPreferences 里，把 `debug.rf.bootloop` 置 0 也**拦不住**下一次启动
         * 再 +1 熔断。留着它会让后续任何验证项都在启动瞬间进安全模式。
         */
        const val DEBUG_RESET_CRASH_PROP = "debug.rf.reset_crash"

        /**
         * 1c 诊断入口对「读 PGID + 终止递进」整体的兜底上限。
         *
         * 最坏路径上界：读 PGID 重试 20×200ms = 4s + TERM 宽限 3s + 两次探测（现已常数级）
         * + KILL 稳定 0.5s ≈ 8s，故 15s 留近一倍余量。两个诊断入口共用同一常量。
         */
        const val VERIFY_TERMINATE_TIMEOUT_MILLIS = 15_000L
    }
}
