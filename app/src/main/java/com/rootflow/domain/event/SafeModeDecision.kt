package com.rootflow.domain.event

/**
 * 安全模式下的放行判定（需求 §5.4）。
 *
 * ## 为什么必须是纯函数且只有一处实现
 * "安全模式下脚本不执行（除 `runOnSafeMode=true`）"要在**两个**地方生效：
 * 1. `TriggerDispatcher.dispatch` —— 入口处提前丢弃（省掉一次 Room 查询与日志噪音）
 * 2. `TriggeredScriptRunner.start` —— **唯一投递终点**（阶段 6 的"手动运行"也走这里）
 *
 * 若两处各写一遍 `if`，迟早会漂移（一处改了另一处没改 = 部分脚本仍会执行，
 * 而这正是熔断要防的事）。因此判定收在本对象里，两处**调用同一函数**，
 * 并由 `SafeModeDecisionTest` 断言"两处引用同一实现"。
 *
 * ## 放行时**跳过准入闸门**（有意为之）
 * 安全模式下全局并发上限无意义（正常脚本全被挡下，能跑的本就只有
 * `runOnSafeMode=true` 的少数），且这些脚本恰恰是"救砖用"的——
 * 让它们因为闸门满而跑不起来，与本机制的目的相悖。
 * 但仍**必须注册进 `RunSessionRegistry`**，否则熔断时漏 kill（见其 KDoc）。
 */
object SafeModeDecision {
    /**
     * 是否允许本次运行。
     *
     * @param safeMode 当前是否处于安全模式
     * @param runOnSafeMode 该脚本是否声明"安全模式下仍执行"
     * @return `true` = 放行
     */
    fun shouldRun(
        safeMode: Boolean,
        runOnSafeMode: Boolean,
    ): Boolean = !safeMode || runOnSafeMode

    /**
     * 本次放行是否**跳过了准入闸门**。
     *
     * 语义：安全模式下的每一次放行都跳过闸门（见对象 KDoc 的说明）。
     * 单独抽成函数是为了让"跳过闸门"这件事**在调用点显式可见**，
     * 而不是散落在 `if (safeMode) …else…` 的分支里。
     */
    fun skipsAdmissionGate(safeMode: Boolean): Boolean = safeMode

    /**
     * 事件分发起点的快速判定（与 [shouldRun] 同源，仅语义更直白）。
     *
     * 入口侧只想知道"这个事件还要不要往下走"——安全模式下**任何事件都不分发**
     * （需求 §5.4「事件监听器注册但不分发」）。真正决定"某脚本能否跑"的是
     * [shouldRun]，两者**不是**同一层判定，不可互相替代。
     *
     * ## ★ 由此产生的一条用户可见语义（**判读时不要当成缺陷**）
     * 因为丢弃发生在**查触发器之前**，分发层**看不到** `runOnSafeMode`：即使某脚本
     * 声明了"安全模式下仍执行"，它的**事件触发**在安全模式期间同样不会发生。
     *
     * 豁免脚本的真实入口是**显式运行**（阶段 6 的「手动运行」按钮 →
     * 同一个 `TriggeredScriptRunner.start`），以及阶段 5 之后可能出现的其它直接入口。
     *
     * **为什么不做成"分发层也查一次 Room 过滤豁免触发器"**（已评估，不采纳）：
     * 1. 那就等于在安全模式下**继续查库并逐条判定**——恰是需求要避免的开销与噪音
     * 2. 语义上"某个事件的投递"与"某个脚本能否跑"是两层；让分发层知道脚本属性
     *    会把 `TriggerEntity` 的字段一路带上分发路径，破坏现有的单向依赖
     * 3. 阶段 6 的 UI 本来就有"手动运行"入口，豁免脚本的价值（救砖动作）
     *    不依赖事件触发
     *
     * 该语义由 `EndToEndWiringTest` 的两条**对立用例**钉死：
     * `a runOnSafeMode script runs in safe mode when reached directly` 与
     * `safe mode blocks the same exempt script when it arrives as an event`。
     */
    fun shouldDispatchEvents(safeMode: Boolean): Boolean = !safeMode
}
