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
     * 保活看门狗的闹钟 action（阶段 12c）。
     *
     * ## ★ 为什么必须**另开一个域**，而不是复用 `INTERVAL`（对既定方案的一处细化，如实登记）
     * `PROJECT_STATE.md` 的「立即要做 · ②」写的是"复用既有 `com.rootflow.INTERVAL` 域与
     * `AlarmFireReceiver`"。落地时发现**前半句不成立**：
     * `AlarmFireReceiver.handleAction` 把 `INTERVAL` 映射成 `SystemEvent.Interval` 并投进 `EventBus`
     * ⇒ 若心跳也走这个 action，**每 15 分钟会凭空产生一条 `interval` 事件**，
     * 投给用户的常驻脚本（`EVENT_DELIVERED` / `EVENT_NOT_RUNNING` 一类日志会周期性出现），
     * 而它与用户配置的定时触发器**毫无关系**。那是"宿主内部机制泄漏成用户可见事件"。
     *
     * 后半句是成立的、也照做了：**复用 `AlarmFireReceiver` 这个接收器与整套闹钟基础设施**
     * （`AndroidAlarmHandle` / `(requestCode, action)` 身份 / 清单声明），
     * 因此仍然是**零新依赖**（原设计的重点就在这句）。新增的只是这一个字符串常量。
     *
     * ## 它不进 `EventBus`
     * 接收器对它的处理是"交给看门狗"，**不是** `SystemEvent` —— 心跳不是系统事件，
     * 「一直运行」那类脚本也不该看见它。
     */
    const val KEEPALIVE: String = "com.rootflow.KEEPALIVE"

    /**
     * 全部 action（阶段 3d 新增）：供 `AlarmSyncCoordinator` 逐域对账时遍历。
     *
     * ## 为什么必须由本对象提供而不是让调用方各自列举
     * `AlarmManager.cancelAll()` 不接受 action，但**每个域都要各清一次**才能覆盖全部
     * （决策 D-3d-1 的落地形态）。若调用方自己写 `listOf(TIME, INTERVAL)`，
     * 将来新增第三个闹钟域时就会漏清——而漏清的表现是"某个旧闹钟永远在触发"，
     * 属最难排查的一类静默失败。把清单收在这里，新增域只需改一处。
     *
     * ## [KEEPALIVE] 也在此列，且**不会把看门狗清掉后不补**
     * `cancelAll` 只管"清"，而它每进程**只跑一次**（`AlarmSyncCoordinator` 的真机根因修正），
     * 且时序上必然早于前台服务 `register()` 里的 `ensureScheduled()`
     * （`Application.onCreate` 先于 `MainActivity.onCreate`）
     * ⇒ 心跳闹钟在被清之后立刻（同一进程内、几秒内）被重排。
     * **把新域登记进来**恰恰是这份清单存在的意义（漏登记才是缺陷）。
     */
    val ALL: List<String> = listOf(TIME, INTERVAL, KEEPALIVE)
}
