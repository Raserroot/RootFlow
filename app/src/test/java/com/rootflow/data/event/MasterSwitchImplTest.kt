package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.db.FakeDatabase
import com.rootflow.data.db.dao.AppSwitchDao
import com.rootflow.data.db.entity.AppSwitchRow
import com.rootflow.data.db.entity.ScriptEventRow
import com.rootflow.data.db.entity.ScriptRow
import com.rootflow.domain.model.AppSwitch
import com.rootflow.domain.repository.WriteResult
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MasterSwitchImpl] 单测（总开关重构 **P3**）。
 *
 * ## 它钉的五件事
 * 1. **启动期恢复**：缺行 ⇒ 按安全默认补一行；有行 ⇒ 如实读回；读败 ⇒ 保守关闭且不抛
 * 2. **拨动的写序**：先写库、成功后才动内存快照
 * 3. **幂等**：同值不写库（时间戳是用户动作的档案，不该被重复点击刷新）
 * 4. **写败方向**：库写失败 ⇒ 快照**不得**先变成"已开启"（那是危险方向）
 * 5. ★ **不变量 2**：拨总闸**不修改** `scripts.enabled` 与 `script_events`
 *
 * ## 为什么用 `FakeDatabase` 而不是 MockK
 * 不变量 2 要求"另两张表一个字节都没动" —— 那需要一个**真的装着数据**的库。
 * `FakeDatabase` 的内存 DAO 让这条断言可以写成"拨动前后的表内容相等"，
 * 而不是"我没调用某个方法"（后者证明不了数据没被改）。
 */
class MasterSwitchImplTest {
    init {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    /** 固定的"现在"（避免断言依赖真实时钟）。 */
    private val now = 1_780_000_000_000L

    private fun impl(
        dao: AppSwitchDao,
        clock: Long = now,
    ): MasterSwitchImpl = MasterSwitchImpl(dao).apply { this.clock = { clock } }

    /** 主键恒为 1（单行表）；用例里不写裸字面量。 */
    private val singletonId = AppSwitch.SINGLETON_ID

    // ---------------------------------------------------------------- 启动期恢复

    @Test
    fun `a fresh install seeds the safe default and stays off`() =
        runTest {
            val db = FakeDatabase()
            val switch = impl(db.appSwitchDao)

            val enabled = switch.restoreFromStore()

            assertEquals(false, enabled, "全新安装必须从**关闭**起步（安全默认）")
            assertEquals(false, switch.enabled.value, "快照也必须关闭")
            val row = db.appSwitch.getValue(singletonId)
            assertEquals(false, row.masterEnabled, "补出来的那一行必须是关闭")
            assertEquals(0L, row.enabledAt, "从未拨开过 ⇒ enabledAt = 0（与迁移写入的初始行逐字段同值）")
            assertEquals(0L, row.disabledAt)
            assertEquals(0L, row.updatedAt)
        }

    @Test
    fun `restoring picks up a previously enabled switch`() =
        runTest {
            val db = FakeDatabase()
            db.appSwitch[singletonId] =
                AppSwitchRow(
                    masterEnabled = true,
                    enabledAt = 111L,
                    disabledAt = 0L,
                    updatedAt = 111L,
                )
            val switch = impl(db.appSwitchDao)

            val enabled = switch.restoreFromStore()

            assertEquals(true, enabled, "库里有行 ⇒ 如实恢复（否则重启会让用户意图静默失效）")
            assertEquals(true, switch.enabled.value)
            assertEquals(1, db.appSwitch.size, "已有一行时**不得**再插一行")
            assertEquals(111L, db.appSwitch.getValue(singletonId).enabledAt)
        }

    @Test
    fun `a read failure degrades to the safe default instead of throwing`() =
        runTest {
            val switch = impl(failingDao())

            val enabled = switch.restoreFromStore()

            assertEquals(false, enabled, "读库失败 ⇒ 保守方向：维持关闭，且**不抛**（启动链不得因此断掉）")
            assertEquals(false, switch.enabled.value)
        }

    // ---------------------------------------------------------------- 拨动

    @Test
    fun `turning it on writes the row and flips the snapshot`() =
        runTest {
            val db = FakeDatabase()
            val switch = impl(db.appSwitchDao)
            switch.restoreFromStore()

            val result = switch.setEnabled(enabled = true)

            assertTrue(result is WriteResult.Ok, "拨动成功必须返回 Ok：实际=$result")
            val row = db.appSwitch.getValue(singletonId)
            assertEquals(true, row.masterEnabled, "必须落库")
            assertEquals(now, row.enabledAt, "拨开的时刻要记下来（主页要显示「最近启用」）")
            assertEquals(now, row.updatedAt)
            assertEquals(0L, row.disabledAt, "从未拨关过 ⇒ disabledAt 保持 0")
            assertEquals(true, switch.enabled.value, "快照必须跟上（热路径零 IO 读它）")
        }

    @Test
    fun `turning it off records disabledAt and keeps enabledAt`() =
        runTest {
            val db = FakeDatabase()
            db.appSwitch[singletonId] =
                AppSwitchRow(masterEnabled = true, enabledAt = 500L, disabledAt = 0L, updatedAt = 500L)
            val switch = impl(db.appSwitchDao)
            switch.restoreFromStore()

            switch.setEnabled(enabled = false)

            val row = db.appSwitch.getValue(singletonId)
            assertEquals(false, row.masterEnabled)
            assertEquals(now, row.disabledAt, "拨关的时刻要记下来")
            assertEquals(500L, row.enabledAt, "上一次拨开的时刻**不得**被抹掉（它是档案）")
            assertEquals(now, row.updatedAt)
            assertEquals(false, switch.enabled.value)
        }

    @Test
    fun `setting the same value does not touch the timestamps`() =
        runTest {
            val db = FakeDatabase()
            db.appSwitch[singletonId] =
                AppSwitchRow(masterEnabled = true, enabledAt = 500L, disabledAt = 0L, updatedAt = 500L)
            // 一个"更晚"的时钟：若实现真的写了库，updatedAt 会变成它
            val switch = impl(db.appSwitchDao, clock = now + 999_999L)
            switch.restoreFromStore()

            val result = switch.setEnabled(enabled = true)

            assertTrue(result is WriteResult.Ok, "同值也返回 Ok（它不是错误）：实际=$result")
            assertEquals(500L, db.appSwitch.getValue(singletonId).updatedAt, "幂等：不得刷新时间戳")
            assertEquals(500L, db.appSwitch.getValue(singletonId).enabledAt)
            assertEquals(true, switch.enabled.value)
            assertEquals(1, db.appSwitch.size)
        }

    @Test
    fun `a failed write leaves the snapshot untouched`() =
        runTest {
            val switch = impl(failingDao())
            switch.restoreFromStore()
            assertEquals(false, switch.enabled.value, "前置：关闭")

            val result = switch.setEnabled(enabled = true)

            assertTrue(result is WriteResult.Failed, "写败必须如实回报：实际=$result")
            assertEquals(
                false,
                switch.enabled.value,
                "★ 写库失败 ⇒ 快照**不得**变成『已开启』：那会让 UI 显示开着、而库里是关的",
            )
        }

    // ---------------------------------------------------------------- ★ 不变量 2

    /**
     * ★ **不变量 2**（方案 §4）：拨动总闸**不修改** `scripts.enabled` 与 `script_events`。
     *
     * ## 为什么这条必须单独钉
     * "关闭总闸"与"停用每个脚本"在**行为上**看起来很像，但它们是**两件不同的事**：
     * - 总闸关闭 ⇒ 什么都不跑，但每个脚本的开关**保持用户设的位置**
     * - 若把关闭实现成"逐个把 `scripts.enabled` 改成 0"，用户重新拨开总闸后
     *   **所有脚本都变成停用**了 —— 而他从没关过任何一个
     *
     * 那正是本方案反复引用的红线（§1.2）：**暂停是对象上的状态，对象本身永不被删、不被改写**。
     */
    @Test
    fun `flipping the switch never touches scripts or subscriptions`() =
        runTest {
            val db = FakeDatabase()
            db.scriptDao.insert(
                ScriptRow(
                    id = 0L,
                    name = "zz6e",
                    language = "shell",
                    enabled = true,
                    resident = true,
                    timeoutSec = 0,
                    autoDisableOnFail = false,
                    runOnSafeMode = false,
                    contentPath = "scripts/7/main.sh",
                    contentSha256 = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            db.scriptEventDao.insert(
                ScriptEventRow(
                    scriptId = 7L,
                    eventType = "boot",
                    params = "{}",
                    enabled = false,
                    createdAt = 2L,
                ),
            )
            // 拨动前拍快照（"数据没被改"要用**内容**证明，不是"我没调那个方法"）
            val scriptsBefore = db.scripts.values.toList()
            val eventsBefore = db.scriptEvents.values.toList()

            val switch = impl(db.appSwitchDao)
            switch.restoreFromStore()
            switch.setEnabled(enabled = true)
            switch.setEnabled(enabled = false)

            assertEquals(scriptsBefore, db.scripts.values.toList(), "★ scripts 表必须一个字节都没动")
            assertEquals(eventsBefore, db.scriptEvents.values.toList(), "★ script_events 表同样")
            assertEquals(1, db.scripts.size)
            val scriptRow = db.scripts.values.single()
            val eventRow = db.scriptEvents.values.single()
            assertEquals(true, scriptRow.enabled, "脚本自己的开关保持用户设的位置")
            assertEquals(false, eventRow.enabled, "订阅自己的开关也保持原样")
        }

    // ---------------------------------------------------------------- 工具

    /** 永远失败的 DAO（模拟库不可写 / 磁盘满）。只实现本类用到的四个方法。 */
    private fun failingDao(): AppSwitchDao =
        object : AppSwitchDao {
            override fun observe(id: Long): Flow<AppSwitchRow?> = MutableStateFlow(null)

            override suspend fun get(id: Long): AppSwitchRow? = null

            override suspend fun upsert(row: AppSwitchRow) {
                error("db is on fire")
            }

            override suspend fun insertIfAbsent(row: AppSwitchRow): Long = error("db is on fire")
        }
}
