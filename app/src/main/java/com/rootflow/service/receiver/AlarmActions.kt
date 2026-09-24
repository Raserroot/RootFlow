package com.rootflow.service.receiver

/**
 * 闹钟 `PendingIntent` 的 action 常量（阶段 3c.2，**决策 5**）。
 *
 * ## 为什么放在 `service/` 而不是 `data/event/`
 * 这些字符串是**广播契约**：写入侧（`data/event/AlarmEventSource` 构造 `PendingIntent`）
 * 与接收侧（清单声明的 `AlarmFireReceiver`）**必须是同一对字面量**，
 * 且它们同时出现在 `AndroidManifest.xml` 的 `<intent-filter>` 里。
 * 放在 `service/`（Android 组件所在层）能让"清单 → 接收器 → 写入侧"三处的引用链最短。
 *
 * ## 为什么要与 `time` / `interval` 分开两个 action
 * `AlarmManager` 的闹钟身份是 **`(requestCode, action)` 组合**。同一脚本的 `time` 与
 * `interval` 触发器由 `scriptId` 折叠出**同一个 `requestCode`**
 * （见 `AlarmSchedule.deriveRequestCode`），只靠 action 区分。
 * 若两类共用一个 action，后注册的会**静默覆盖**前一个（本仓库反复禁止的静默失败）。
 *
 * ## 为什么这些常量是 `public`（无 `internal`）
 * `AndroidManifest.xml` 里的 `<action android:name="…">` 是**字符串字面量**，编译期无法引用
 * Kotlin 常量。因此三方（清单 / 接收器 / 写入侧）的一致性**不能**靠 `internal` 收口来保证；
 * 它由以下三道护栏共同保证：
 * 1. 本对象是写入侧与接收侧的**唯一来源**（两处都引用它，不是各写一遍）
 * 2. `AlarmFireReceiverTest` 与 `AlarmEventSourceTest` 各自钉死字面量值
 * 3. `AlarmFireReceiverTest` 另有一条"清单文件包含这两个 action"的断言（读 `AndroidManifest.xml`）
 */
object AlarmActions {
    /** `time` 事件的闹钟 action。 */
    const val TIME: String = "com.rootflow.TIME"

    /** `interval` 事件的闹钟 action。 */
    const val INTERVAL: String = "com.rootflow.INTERVAL"

    /**
     * 全部 action（阶段 3d 新增）：供 `AlarmSyncCoordinator` 逐域对账时遍历。
     *
     * ## 为什么必须由本对象提供而不是让调用方各自列举
     * `AlarmManager.cancelAll()` 不接受 action，但**两个域都要各清一次**才能覆盖全部
     * （决策 D-3d-1 的落地形态）。若调用方自己写 `listOf(TIME, INTERVAL)`，
     * 将来新增第三个闹钟域时就会漏清——而漏清的表现是"某个旧闹钟永远在触发"，
     * 属最难排查的一类静默失败。把清单收在这里，新增域只需改一处。
     */
    val ALL: List<String> = listOf(TIME, INTERVAL)
}
