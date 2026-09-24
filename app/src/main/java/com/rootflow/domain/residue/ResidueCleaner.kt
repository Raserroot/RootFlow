package com.rootflow.domain.residue

/**
 * 卸载残留的扫描结果（需求 §8「**卸载残留：设置页提供清理**」）。
 *
 * ## 什么算"残留"
 * | 形态 | 来源 | 为什么清不掉 |
 * |---|---|---|
 * | **孤儿正文目录** `scripts/<id>/` | 库里有行、终端用户直接删了 App 数据 / 走了非 Room 路径删除 / 卸载重装 | 正文在**文件系统**，与 Room 是两处存储（需求 §3.2 的两处真相） |
 * | **孤儿触发器行** `triggers.script_id` 在 `scripts` 里不存在 | 理论上有 `ON DELETE CASCADE` 不该出现；但"非 Room 路径写入 / 旧版本残留"都可能留下 | 级联只在**经 Room 删除**时生效 |
 *
 * ## 为什么只按"数字目录名"判定孤儿
 * `scripts/` 下的条目名就是脚本 id（`RootFlowPaths.scriptDir`）。非数字条目不是本项目写的，
 * **既不认领、也不删除**（认领它就没有依据；删掉别人放的东西比留着更糟）——
 * 对孤儿触发器行同样"只计数、不擅自处置"（那是同一纪律）。
 *
 * @property orphanScriptDirs 孤儿目录对应的 id（升序）
 * @property orphanTriggerRows 孤儿触发器**行数**
 */
data class ResidueReport(
    val orphanScriptDirs: List<Long>,
    val orphanTriggerRows: Int,
) {
    /** 残留总项数（目录按个、触发器按行）。 */
    val total: Int get() = orphanScriptDirs.size + orphanTriggerRows

    /** 是否**没有**残留。 */
    val clean: Boolean get() = total == 0
}

/** 扫描结果：要么拿到报告，要么说清为什么拿不到（**不静默**）。 */
sealed interface ResidueScan {
    data class Ok(
        val report: ResidueReport,
    ) : ResidueScan

    /** 扫描不可用（root 控制通道不可用 / 命令失败 / 库读取失败）。 */
    data class Unavailable(
        val reason: String,
    ) : ResidueScan
}

/**
 * 清理结果。
 *
 * ## 为什么失败要**逐条**列出而不是一个布尔
 * 清理是多步操作（N 个目录 + M 行触发器），任何一步都可能失败（权限 / 进程占用 / 控制通道超时）。
 * 折叠成"成功/失败"会让"删了 3 个、剩 1 个删不掉"变成一句无法排查的结论
 * （同 6c 的「部分成功」纪律）。
 *
 * @property removedScriptDirs 已删除的孤儿目录 id
 * @property removedTriggerRows 已删除的孤儿触发器行数
 * @property failures 逐项失败原因（空 = 全部成功）
 */
data class ResidueCleanResult(
    val removedScriptDirs: List<Long>,
    val removedTriggerRows: Int,
    val failures: List<String>,
) {
    /** 实际清掉的项数。 */
    val removedTotal: Int get() = removedScriptDirs.size + removedTriggerRows

    /** 是否**全部**成功（`removedTotal == 0 && failures.isEmpty()` 也算：本来就干净）。 */
    val succeeded: Boolean get() = failures.isEmpty()
}

/**
 * 卸载残留清理端口（阶段 6d，需求 §8）。
 *
 * ## 为什么是 `domain` 端口而不是让 UI 直接操作
 * 扫描/删除要**经 root 通道**读 `scripts/`（`AGENTS.md`：Root 操作集中在 `runtime/`，
 * UI 不得触碰），也要读 Room。两条路径都属实现细节，端口只回答
 * "现在有多少残留""清掉了多少、哪几条没清掉"。
 *
 * ## 为什么 `clean()` 自己先扫一遍（而不是让调用方传扫描结果）
 * 调用方在"扫描 → 用户点确认"之间可能过了很久（甚至横跨一次 App 重启），
 * 传进来的列表会过期；而过期的删除列表会**误删刚被用户重新创建的脚本目录**。
 * 因此 `clean()` 在**动作发生时**重新扫描，只删那一刻确认是孤儿的项。
 */
interface ResidueCleaner {
    /** 只读扫描（设置页显示"当前 N 项残留"用）。 */
    suspend fun scan(): ResidueScan

    /** 扫描并删除。失败**逐条**上报，不中断其余项。 */
    suspend fun clean(): ResidueCleanResult
}
