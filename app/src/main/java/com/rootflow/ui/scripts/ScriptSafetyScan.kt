package com.rootflow.ui.scripts

/**
 * 脚本正文的危险指令扫描（用户需求，2026-09-25）。
 *
 * ## 它是什么、不是什么
 * - **是**：保存前的最后一道"你确定吗"。命中 ⇒ 弹确认框，用户可以点「继续保存」放行。
 * - **不是**：沙箱、不是权限系统，**不阻断保存**。有 root 的脚本本来就能做任何事，
 *   这里只负责"让用户在写下去之前知道自己在写什么"。
 *
 * ## 为什么是模式匹配，而不是语义分析
 * 已批准的范围裁定：**只做文本模式匹配**。变量拼接（`T=/data; rm -rf $T`）能抓一部分
 * （见规则 `rm-recursive-variable-target`），但**不做**数据流追踪 —— 那需要真正的 shell
 * 解析器，收益远不及"让用户自己看一眼"。
 * ⇒ 本扫描**必然存在漏报**。这是有意的取舍，不是缺陷。
 *
 * ## 为什么排除整行注释
 * `# rm -rf /` 是注释，不是命令。不排除会让"把危险命令注释掉留作记录"这种完全正常的写法
 * 每次都弹窗，用户很快学会无脑点「继续保存」—— 那比不弹更糟。
 * **行尾**注释不在此列：`rm -rf /data # 清一下` 里的 `rm` 仍然是真命令。
 */
object ScriptSafetyScan {
    /**
     * 命中一条危险规则的结果（纯数据）。
     *
     * @property ruleId 规则稳定标识（**单测钉死**；改文案不改这个）
     * @property severity 严重度（决定弹窗的措辞与排序）
     * @property lineNumber 行号（**1 起**，与编辑器显示的行号一致）
     * @property excerpt 命中行原文（已截断，供弹窗展示"就是这一行"）
     * @property reason 用户可见的中文说明（**稳定字符串**）
     */
    data class DangerFinding(
        val ruleId: String,
        val severity: DangerSeverity,
        val lineNumber: Int,
        val excerpt: String,
        val reason: String,
    )

    /** 严重度。只有两档：真要命的，与"大概率会后悔的"。 */
    enum class DangerSeverity {
        /** 不可逆的设备 / 数据破坏（格盘、覆写块设备、删根目录）。 */
        HIGH,

        /** 大概率误伤或难恢复（放宽系统目录权限、关掉 SELinux 强制、重挂根分区）。 */
        MEDIUM,
    }

    /** 单行摘要的最大长度（超出截断，避免弹窗里出现一整段 base64）。 */
    private const val EXCERPT_MAX: Int = 120

    /** 规则形态。正则与文案绑在一起，避免"改了正则忘了改说明"。 */
    private class Rule(
        val id: String,
        val severity: DangerSeverity,
        val pattern: Regex,
        val reason: String,
    )

    /**
     * 匹配"递归删除"的开头：`rm -rf` / `rm -fr` / `rm -Rf` / `rm -fR` / `rm -r` / `rm -f`。
     *
     * ## 为什么先认 `rm` 再判目标，而不是一条正则到底
     * "危险不危险"取决于**目标**而不是 `rm` 本身：`rm -rf ./build` 完全正常，
     * `rm -rf /` 不可逆。拆成两步才能把"目标不同、严重度不同"表达清楚，
     * 也才不会对构建目录这种日常写法误报。
     */
    private val RECURSIVE_RM: Regex =
        Regex("""\brm\s+(?:-[a-zA-Z]*[rR][a-zA-Z]*-?|-[a-zA-Z]*-?[rR][a-zA-Z]*)\s+""")

    /**
     * 一眼就是"根 / 系统盘 / 全盘"的目标（`rm` 之后紧跟的那个词）。
     *
     * ## 为什么这一条用普通字符串而不是 raw string（踩过坑，别改回去）
     * 同一条正则写成 `"""…"""` 编译不过：raw string 与本条里那几段"转义星号"的组合
     * 会让 Kotlin 词法分析器在字符串中间就判定"缺一个右花括号"，随后连锁误报成
     * 注释未闭合。改成普通字符串 + 转义之后，正则引擎收到的字符逐个相同，
     * 语义完全一致，而不再需要跟 raw string 的词法规则打交道。
     *
     * ⚠️ 顺带一条教训：本段注释本身也曾把编译器搞崩 —— KDoc 里**不能出现**星号紧邻斜杠的序列，
     * 那会提前闭合块注释。要描述这类模式时请用文字，勿粘贴正则原文。
     */
    private val CATASTROPHIC_TARGET: Regex =
        Regex(
            "^\\s*[\"']?(?:/|/\\*{0,2}|/\\*/\\*|/system|/data|/vendor|/boot|/efs|/persist" +
                "|/sdcard|/storage|/proc|/sys)[\"']?(?:\\s|$|;|&|\\|)",
        )

    /**
     * 目标里含 shell 变量 ⇒ 展开前无法判断它会变成什么。
     *
     * ## 为什么**不**把通配符也算进来（有意为之）
     * `rm -rf /tmp/＊`、`rm -rf ./build/＊` 是日常写法，把它们也报一次会让弹窗变成噪音，
     * 用户很快学会无脑点「继续保存」—— 那比不弹更糟。而真正灾难性的"删根目录带通配符"
     * 已被 [CATASTROPHIC_TARGET] 直接抓成高危，不需要靠这条兜。
     *
     * ## ⚠️ 写注释的硬教训（本文件为此编译失败过三次）
     * Kotlin 的块注释**支持嵌套**。注释里只要出现"斜杠紧跟星号"的序列，就会再开一层注释，
     * 于是整份文件以"注释未闭合"告终，而**报错行号指向的是前面某段代码**，极难定位。
     * ⇒ **禁止在注释里粘贴含该序列的路径原文**，用「星号」二字或全角符号代替。
     * 同理，星号紧跟斜杠的序列会**提前闭合**注释，同样禁止。
     *
     * ## ⚠️ 这里的 `$` 必须是**反斜杠加美元符**，不能是裸 `$`
     * 正则里裸 `$` 是**行尾锚点**而不是美元符 —— 第一版写成了裸 `$`，
     * 于是"目标含变量"这一条**永远匹配不上**（由 `ScriptSafetyScanTest` 抓出）。
     * raw string 内部不做反斜杠转义，所以 `\$` 原样交给正则引擎 ⇒ 才是字面美元符。
     *
     * （raw string 里也不能用花括号模板语法去构造 `$`：那生成的正是**裸** `$`，
     * 恰好就是上面那个错误。）
     */
    private val VARIABLE_TARGET: Regex = Regex("""^\s*["']?[^\s"']*\$[A-Za-z_{]""")

    private val RM_ROOT_RULE: Rule =
        Rule(
            id = "rm-recursive-root-or-system",
            severity = DangerSeverity.HIGH,
            pattern = RECURSIVE_RM,
            reason = "递归强制删除根目录或系统关键分区，数据不可恢复",
        )

    private val RM_VARIABLE_RULE: Rule =
        Rule(
            id = "rm-recursive-variable-target",
            severity = DangerSeverity.HIGH,
            pattern = RECURSIVE_RM,
            reason = "递归强制删除的目标含变量或通配符，展开后可能是任意路径",
        )

    /** 与 [RECURSIVE_RM] 无关、但本身就是破坏性操作的固定规则。 */
    private val RULES: List<Rule> =
        listOf(
            Rule(
                id = "device-overwrite-dd",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""\bdd\b[^\n]*\bof=\s*/dev/(?:block|mmcblk|sd)"""),
                reason = "用 dd 直接覆写块设备分区，会不可逆地破坏系统或用户数据",
            ),
            Rule(
                id = "device-overwrite-redirect",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""(?<![0-9])>{1,2}\s*/dev/(?:block|mmcblk|sd)"""),
                reason = "把输出重定向到块设备，等同于直接覆写分区表或数据",
            ),
            Rule(
                id = "filesystem-format",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""\b(?:mkfs(?:\.\w+)?|mke2fs|make_ext4fs)\b"""),
                reason = "格式化文件系统，目标分区上的数据会全部丢失",
            ),
            Rule(
                id = "fastboot-erase",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""\bfastboot\b[^\n]*\b(?:erase|format|flash\s+userdata)"""),
                reason = "fastboot 擦除 / 格式化分区，属于刷机级操作",
            ),
            Rule(
                id = "pm-destructive",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""\bpm\s+(?:uninstall|clear)\b"""),
                reason = "卸载应用或清空应用数据（`pm clear` 不可撤销）",
            ),
            Rule(
                id = "package-remove-path",
                severity = DangerSeverity.HIGH,
                pattern = Regex("""\brm\b[^\n]*?/data/(?:data|system|app|priv-app)/"""),
                reason = "直接删除应用安装目录或应用数据目录，可能导致系统无法开机",
            ),
            Rule(
                id = "selinux-permissive",
                severity = DangerSeverity.MEDIUM,
                pattern = Regex("""\bsetenforce\s+0\b"""),
                reason = "关闭 SELinux 强制模式，会显著降低系统安全性",
            ),
            Rule(
                id = "remount-root-rw",
                severity = DangerSeverity.MEDIUM,
                pattern = Regex("""\bmount\b[^\n]*\bremount\b[^\n]*\b(?:rw|/|/system|/vendor)"""),
                reason = "把系统分区重新挂载为可写，之后对它的改动可能让系统无法启动",
            ),
            Rule(
                id = "chmod-world-writable-root",
                severity = DangerSeverity.MEDIUM,
                pattern = Regex("""\bchmod\s+(?:-[a-zA-Z]+\s+)*0?777\s+/(?:\s|${'$'}|\*)"""),
                reason = "把根目录放开为所有人可写，任何应用都能改系统文件",
            ),
        )

    /**
     * 扫描脚本正文。
     *
     * @return 按**行号升序**排列的命中列表；同一行**最多一条**（取最先命中的那档）。
     *   空列表 = 没扫到问题（**不代表脚本安全**，见类 KDoc 的漏报说明）
     */
    fun scan(content: String): List<DangerFinding> {
        val findings = mutableListOf<DangerFinding>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            // 整行注释直接放过（见类 KDoc 的理由）
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("#")) return@forEachIndexed

            val rule = matchLine(trimmed) ?: return@forEachIndexed
            findings +=
                DangerFinding(
                    ruleId = rule.id,
                    severity = rule.severity,
                    lineNumber = index + 1,
                    excerpt = excerptOf(rawLine),
                    reason = rule.reason,
                )
        }
        return findings
    }

    /**
     * 判定单行是否命中。
     *
     * `rm` 的组合判定放在最前面：它比 [RULES] 里任何一条都更具体，
     * 放到后面会被宽松规则抢先，用户看到的就会是含糊的那条说明。
     */
    private fun matchLine(line: String): Rule? {
        recursiveRmFinding(line)?.let { return it }
        return RULES.firstOrNull { rule -> rule.pattern.containsMatchIn(line) }
    }

    /**
     * `rm -rf <目标>` 的两档判定（**危险程度由目标决定**）。
     *
     * 两档之间按"更危险者优先"，不重复报：`rm -rf /$HOME` 只报根目录那条。
     */
    private fun recursiveRmFinding(line: String): Rule? {
        val match = RECURSIVE_RM.find(line) ?: return null
        val target = line.substring(match.range.last + 1)

        if (CATASTROPHIC_TARGET.containsMatchIn(target)) return RM_ROOT_RULE
        if (VARIABLE_TARGET.containsMatchIn(target)) return RM_VARIABLE_RULE
        return null
    }

    /** 截断过长的行（弹窗里不需要看完整段）。 */
    private fun excerptOf(line: String): String {
        val trimmed = line.trim()
        return if (trimmed.length <= EXCERPT_MAX) trimmed else trimmed.take(EXCERPT_MAX) + "…"
    }
}
