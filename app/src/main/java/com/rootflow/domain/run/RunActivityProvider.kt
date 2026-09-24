package com.rootflow.domain.run

import kotlinx.coroutines.flow.StateFlow

/**
 * 「最近一次被受理的运行」的只读来源（阶段 6b）。
 *
 * ## 存在的理由
 * 主页的深色终端要订阅 `LogPipeline.observe(runId)` / `tail(runId)`，而 `runId` 是
 * **运行时才产生**的 UUID（`ScriptRunCoordinator.startLogging` 内部生成）。
 * UI 侧唯一能拿到它的途径就是运行集合。`RunSessionRegistry` 已经在登记
 * `runId → scriptId`，因此由它实现本端口是**零新增状态**（见其 `activeRuns()`）。
 *
 * ## ★ 单调语义（**6b 已批准决策，勿改成"运行结束即清空"**）
 * [latestRunId] 指向**最近一次被受理**的运行，运行结束后**保持不变**。
 *
 * 若在运行收尾时把它置 `null`，日志终端会在**运行结束的那一刻整屏变空**：
 * `LogPipeline` 在运行收尾时会移除该运行的 state（内存回收），此后
 * `tail(runId)` 返回空 `LogTail`、`observe(runId)` 返回 `emptyFlow()`
 * （`LogPipelineImpl` 的既有契约，阶段 2 起冻结）。
 * 于是"用户刚想看结果，屏幕却空了"——而那恰恰是最需要看日志的时刻。
 *
 * 单调语义还有第二个好处：它与"运行被取消"是可区分的。若用 `null` 表示"结束了"，
 * 那"结束"与"从未运行过"会撞成同一个取值，UI 无法给出正确文案。
 *
 * **代价（如实登记）**：终端显示的是"上一次运行的回放"，不是"此刻正在跑的输出"。
 * 因此 UI 文案必须写明是哪一次运行（runId 短码 + 行数），**不得**让用户以为它在实时跟随。
 * 6c 引入运行历史页后，"实时 vs 历史"的分工会由那个页面承担。
 *
 * ## 与 `RunSessionRegistry` 的关系
 * 本端口**只读**。登记/注销仍由 `TriggeredScriptRunner` 无条件执行
 * （那是熔断第 2 步按 `runId` 找 PGID 的依据，与 UI 无关）。
 * UI **不得**用它做任何准入判断 —— 准入是 `RunAdmissionGate` 的职责。
 */
interface RunActivityProvider {
    /**
     * 最近一次被受理运行的标识；从未有运行被受理时为 `null`。
     *
     * 单调不回退：一旦非 `null`，只可能被**更新**为新的 `runId`，不会被清空。
     */
    val latestRunId: StateFlow<String?>
}
