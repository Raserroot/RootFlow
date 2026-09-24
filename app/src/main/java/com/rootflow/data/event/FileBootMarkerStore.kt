package com.rootflow.data.event

import android.util.Log
import com.rootflow.domain.event.BootMarkerStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BootMarkerStore] 的文件实现（阶段 3d，D9 的持久化半）。
 *
 * ## 存储形态：应用私有目录内的**单行文本**
 * 文件内容就是一个十进制 `Long`（`SystemClock.elapsedRealtime()` 毫秒值）。
 * 刻意不用 JSON / DataStore / Room：
 * - 只有一个值，任何结构化格式都是多余的复杂度
 * - **不引入 DataStore 的新用法**（依赖在基线里但全项目零使用，3d 不从零引入数据层组件）
 * - **不走 root 通道**：1c 实测一次 `su` 调用约 300ms，而本标记**每次启动**都要读写
 *
 * ## 读失败的处理（不静默）
 * | 情况 | 返回 | 日志 |
 * |---|---|---|
 * | 文件不存在（**全新安装**，正常路径） | `null` | 无（这是预期状态，不值得告警） |
 * | IO 异常 | `null` | `BOOT_MARKER_READ_FAILED` 告警 |
 * | 内容无法解析为 `Long` | `null` | `BOOT_MARKER_CORRUPTED` 告警 |
 *
 * 后两种情况与"全新安装"都返回 `null`，因此 `BootSemantics` 会判 `true` 并补发 boot
 * ——**宁可多发一次**（代价是多跑一次脚本，由 500ms 防抖与标记收敛），
 * 也不漏发（代价是"开机自启"这条需求静默失效）。
 *
 * ## 写失败的处理
 * 写失败只告警、不抛出：它在 `Application.onCreate` 的关键路径上，
 * 抛异常会让 App 起不来——而 boot 补发的价值远低于"App 能启动"。
 * 代价是下次启动可能重复补发一次 boot（可接受，同上）。
 *
 * ## 为什么注入 `File` 而不是 `Context`
 * 本类只做文件 IO，不需要 `Context`。`File` 由 `BootModule` 从 `cacheDir`/
 * `filesDir` 派生，注入进来后本类可在纯 JVM 下单测（临时目录即可）。
 * 注意：**构造函数的入参类型必须是 `File`**，否则 Hilt 无法生成绑定。
 */
@Singleton
class FileBootMarkerStore
    @Inject
    constructor(
        private val markerFile: File,
    ) : BootMarkerStore {
        override fun read(): Long? {
            if (!markerFile.exists()) {
                // 全新安装：正常路径，不告警（见类 KDoc）
                return null
            }
            return try {
                val text = markerFile.readText().trim()
                val value = text.toLongOrNull()
                if (value == null) {
                    Log.w(TAG, "BOOT_MARKER_CORRUPTED file=${markerFile.name} content=[$text]")
                }
                value
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "BOOT_MARKER_READ_FAILED file=${markerFile.name}: " +
                        (error.message ?: error::class.java.name),
                )
                null
            }
        }

        override fun write(elapsedRealtimeMillis: Long) {
            try {
                markerFile.parentFile?.mkdirs()
                markerFile.writeText(elapsedRealtimeMillis.toString())
            } catch (error: Throwable) {
                // 不抛出：调用方在 Application.onCreate 的关键路径上（见类 KDoc）
                Log.w(
                    TAG,
                    "BOOT_MARKER_WRITE_FAILED file=${markerFile.name}: " +
                        (error.message ?: error::class.java.name),
                )
            }
        }

        private companion object {
            const val TAG: String = "RootFlow"
        }
    }
