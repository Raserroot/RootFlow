package com.rootflow.domain.event

/**
 * "上次 App 启动时刻"的持久化端口（阶段 3d，D9 的第二半）。
 *
 * ## 存的是什么
 * 每次 App 启动时写入的 `SystemClock.elapsedRealtime()`（毫秒，单调、自开机起算），
 * 供 [BootSemantics.isFirstStartSinceBoot] 判定"距上次启动是否经历过重启"。
 *
 * ## 写入时机：**每次启动都无条件覆写**
 * **不是**"只在首次写"。若改成"仅首次"，`elapsed < bootId` 的回退判定会永久失效
 * （同一开机周期内的第二次启动会因为 `elapsed` 已超过首次写入值而被判成"非首次"，
 * 看似正确，但重启后的第一次启动同样会被判成"非首次"→ **boot 永不补发**）。
 *
 * ## 为什么不用 root 通道 / 不用 DataStore
 * - **不用 root 通道**：1c 实测一次 `su` 调用约 300ms，而本标记在**每次启动**都要读写，
 *   不该拖慢冷启动；且该标记属 App 私有状态，无需对 root 可见
 * - **不用 DataStore**：依赖虽在基线里，但**全项目零使用**，3d 不从零引入数据层新组件
 *
 * 因此实现（`data/event/FileBootMarkerStore`）用应用私有目录内的单行文本文件。
 *
 * ## 读失败的处理（本仓库反复强调的纪律）
 * 文件不存在是**正常路径**（全新安装），返回 `null` 即可；
 * 但文件存在却读不出来（IO 异常、内容损坏）**不得静默当作 `null`**——
 * 那与全新安装无法区分。实现必须经 [onWarning] 如实上报。
 */
interface BootMarkerStore {
    /**
     * 读取上次启动标记。
     *
     * @return `elapsedRealtime` 毫秒值；无记录或内容无法解析时 `null`
     */
    fun read(): Long?

    /**
     * 写入本次启动标记（**无条件覆写**，见类 KDoc）。
     *
     * @param elapsedRealtimeMillis 本次启动的 `SystemClock.elapsedRealtime()`
     */
    fun write(elapsedRealtimeMillis: Long)
}
