package com.rootflow.data.event

import android.util.Log
import com.rootflow.data.db.dao.AppSwitchDao
import com.rootflow.data.db.mapper.toDomain
import com.rootflow.data.db.mapper.toRow
import com.rootflow.domain.event.MasterSwitch
import com.rootflow.domain.model.AppSwitch
import com.rootflow.domain.repository.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MasterSwitch] 的实现（总开关重构 **P3**）。
 *
 * ## 它做的三件事
 * 1. **启动期恢复**：从 `app_switch` 读那一行（缺行则按 [AppSwitch.SafeDefault] 补一行）
 * 2. **拨动**：写库 → 成功后才更新内存快照 → 留痕
 * 3. **暴露快照**：[enabled] 供热路径零 IO 判定（不变量 1）
 *
 * ## ★ 写序：先库、后快照（不可调换）
 * 反过来（先改快照、再写库）的失败形态是**危险方向**的：库写失败但内存说"已开启"
 * ⇒ P4 的 UI 显示"开着"、而进程重启后又是关的，且**没有任何日志解释为什么**。
 * 现在的顺序在失败时留下的是**安全方向**的不一致（库没变、快照没变、日志有 `WRITE_FAILED`）。
 *
 * ## 为什么 `clock` 是属性而不是构造参数
 * 与 `DaemonSupervisorImpl.elapsedRealtimeMillis` 同款：本类的 `@Inject` 构造由 Dagger 装配，
 * 而 `() -> Long` 这种函数类型**无法被 Dagger 绑定**（没有可解析的 `@Provides`）。
 * 单元测试在构造之后替换它即可（`MasterSwitchImplTest` 就是这么做的）。
 *
 * ## 为什么读路径不写库
 * [restoreFromStore] 在缺行时会补一行（那是一次性的初始化），但 [setEnabled] 的判据
 * 读的是"当前行或 [AppSwitch.SafeDefault]"而**不**先补行 —— 写路径只做用户要求的那一次写入，
 * 否则"拨一次总闸"会变成两条 SQL，而其中一条在故障时会让判读多一种形态。
 */
@Singleton
class MasterSwitchImpl
    @Inject
    constructor(
        private val dao: AppSwitchDao,
    ) : MasterSwitch {
        private val _enabled = MutableStateFlow(false)

        override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

        override val isEnabled: Boolean
            get() = _enabled.value

        /** 测试缝：拨动时间戳的来源（真实实现用墙钟；单测替换成固定时钟）。 */
        internal var clock: () -> Long = System::currentTimeMillis

        /**
         * 启动期恢复（见 [MasterSwitch.restoreFromStore]）。
         *
         * ## 缺行为什么是"补一行"而不是"当成错误"
         * `app_switch` 是单行表：**没有行** = 全新安装（数据库回调尚未写入），
         * 是合法状态。迁移路径已保证升级用户有行（`MIGRATION_1_2` 写入 `(1,0,0,0,0)`），
         * 因此这里补出来的值与迁移写入的值**逐字段一致** —— 两条路径的起点相同。
         */
        override suspend fun restoreFromStore(): Boolean {
            val outcome =
                runCatching {
                    val existing = dao.get()
                    if (existing != null) {
                        existing.toDomain() to false
                    } else {
                        val seed = AppSwitch.SafeDefault.toRow()
                        dao.insertIfAbsent(seed)
                        // `insertIfAbsent` 返回 -1 表示"已被写入过"（并发或回调抢先）；
                        // 两种情况下表的最终内容都是那一行，故直接回读一次以拿到真相。
                        (dao.get()?.toDomain() ?: AppSwitch.SafeDefault) to true
                    }
                }.getOrElse { error ->
                    // 读库失败 ⇒ **保守方向**：维持关闭（安全默认），并留痕。
                    Log.w(
                        TAG,
                        "MASTER_SWITCH_RESTORE_FAILED: ${error.message ?: error::class.java.name} " +
                            "(staying disabled — safe default)",
                    )
                    AppSwitch.SafeDefault to false
                }

            val (row, inserted) = outcome
            _enabled.value = row.masterEnabled
            Log.i(
                TAG,
                "MASTER_SWITCH_RESTORED enabled=${row.masterEnabled} inserted=$inserted " +
                    "enabledAt=${row.enabledAt} disabledAt=${row.disabledAt} updatedAt=${row.updatedAt}",
            )
            return row.masterEnabled
        }

        /**
         * 拨动总闸（见 [MasterSwitch.setEnabled]）。
         *
         * 不变量 2 在这里成立：**只写 `app_switch` 一行**，`scripts.enabled` /
         * `script_events` 一个字节都不动（由 `MasterSwitchImplTest` 的用例钉住）。
         */
        override suspend fun setEnabled(enabled: Boolean): WriteResult<AppSwitch> {
            val current =
                runCatching { dao.get()?.toDomain() ?: AppSwitch.SafeDefault }
                    .getOrElse { error ->
                        Log.w(
                            TAG,
                            "MASTER_SWITCH_WRITE_FAILED enabled=$enabled: read failed " +
                                (error.message ?: error::class.java.name),
                        )
                        return WriteResult.Failed(
                            "master switch read failed: ${error.message ?: error::class.java.name}",
                        )
                    }

            // 幂等：同值不写库（时间戳是**用户动作的档案**，不该被重复点击刷新）
            if (current.masterEnabled == enabled) {
                Log.i(
                    TAG,
                    "MASTER_SWITCH_SET_NOOP enabled=$enabled " +
                        "(already in that state; timestamps untouched)",
                )
                // 快照仍要校正一次：调用方可能刚改过库（或本进程尚未恢复过）
                _enabled.value = enabled
                return WriteResult.Ok(current)
            }

            val now = clock()
            val next =
                current.copy(
                    masterEnabled = enabled,
                    enabledAt = if (enabled) now else current.enabledAt,
                    disabledAt = if (enabled) current.disabledAt else now,
                    updatedAt = now,
                )

            return runCatching {
                dao.upsert(next.toRow())
                // ★ 写库成功**之后**才动快照（见类 KDoc 的写序）
                _enabled.value = enabled
                Log.i(
                    TAG,
                    if (enabled) {
                        "MASTER_SWITCH_ENABLED at=$now (master switch on: resident scripts will start)"
                    } else {
                        "MASTER_SWITCH_DISABLED at=$now (master switch off: nothing may start, " +
                            "running daemons will be terminated)"
                    },
                )
                WriteResult.Ok(next)
            }.getOrElse { error ->
                Log.w(
                    TAG,
                    "MASTER_SWITCH_WRITE_FAILED enabled=$enabled: ${error.message ?: error::class.java.name} " +
                        "(snapshot untouched)",
                )
                WriteResult.Failed("master switch write failed: ${error.message ?: error::class.java.name}")
            }
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
