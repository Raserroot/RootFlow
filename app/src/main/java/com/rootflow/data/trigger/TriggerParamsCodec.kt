package com.rootflow.data.trigger

import com.rootflow.domain.model.TriggerParams

/**
 * [TriggerParams] 的极简 JSON 编解码（阶段 3a 手写）。
 *
 * ## 为什么手写而不用 kotlinx-serialization
 * `kotlinx-serialization` 需要 Gradle 编译器插件 `org.jetbrains.kotlin.plugin.serialization`，
 * 而本项目的构建是**离线**的（`--offline`，见 `AGENT_PROTOCOL.md §5.x`），该插件不在本地
 * 缓存中。引入它会让构建无法离线完成，风险远大于收益。
 *
 * 本类型只有 **5 个扁平的基础类型字段**，手写编解码完全可控，且由单测逐字段覆盖。
 * 若将来参数结构变复杂（嵌套对象/数组），应重新评估引入官方库。
 *
 * ## 容错原则（不崩、不静默）
 * [decode] 对**任何**格式错误都返回默认值 [TriggerParams] 并调用 [onWarning]，
 * **绝不抛异常**：一条损坏的触发器记录不应该让整个调度器停摆。
 * 但"回退了"这件事必须可见——所以有 [onWarning] 回调（由调用方决定记日志还是计数）。
 */
object TriggerParamsCodec {
    /** 遇到无法解析的内容时的告警文案前缀。 */
    const val WARNING_PREFIX: String = "TriggerParams:"

    /**
     * 编码为 JSON 对象字符串。
     *
     * 字段顺序固定（便于比对与阅读）；`null` 字段被省略（保持库内紧凑）。
     */
    fun encode(params: TriggerParams): String {
        val parts = mutableListOf<String>()
        parts += "\"exact\":${params.exact}"
        params.intervalMinutes?.let { parts += "\"intervalMinutes\":$it" }
        params.hourOfDay?.let { parts += "\"hourOfDay\":$it" }
        params.minuteOfHour?.let { parts += "\"minuteOfHour\":$it" }
        params.payload?.let { parts += "\"payload\":${quote(it)}" }
        return "{" + parts.joinToString(",") + "}"
    }

    /**
     * 解码 JSON 对象字符串。
     *
     * @param raw 原始 JSON；空白串或 `"{}"` 返回默认值且**不告警**（属正常空配置）
     * @param onWarning 需要告警时回调（文案已带 [WARNING_PREFIX] 前缀）
     */
    fun decode(
        raw: String,
        onWarning: (String) -> Unit = {},
    ): TriggerParams {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return TriggerParams()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            onWarning("$WARNING_PREFIX not a JSON object, falling back to defaults: $raw")
            return TriggerParams()
        }

        val tokens = topLevelTokens(trimmed)
        if (tokens == null) {
            onWarning("$WARNING_PREFIX unbalanced quotes, falling back to defaults: $raw")
            return TriggerParams()
        }

        var exact = false
        var intervalMinutes: Int? = null
        var hourOfDay: Int? = null
        var minuteOfHour: Int? = null
        var payload: String? = null

        tokens.forEach { token ->
            val key = unquote(token.first)
            val value = token.second
            when (key) {
                "exact" -> exact = parseBoolean(value) ?: exact
                "intervalMinutes" -> intervalMinutes = parseNumber(value)
                "hourOfDay" -> hourOfDay = parseNumber(value)
                "minuteOfHour" -> minuteOfHour = parseNumber(value)
                "payload" -> payload = parseString(value)
                else -> onWarning("$WARNING_PREFIX unknown key '$key' ignored")
            }
        }
        return TriggerParams(
            exact = exact,
            intervalMinutes = intervalMinutes,
            hourOfDay = hourOfDay,
            minuteOfHour = minuteOfHour,
            payload = payload,
        )
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 切出顶层 `key:value` 对。
     *
     * 手写解析只需处理"字符串内的冒号/逗号不算分隔符"这一件事——用 `inString` 状态机完成。
     * **不支持嵌套对象**（当前结构不需要；需要时应改用官方库）。
     *
     * @return `null` 表示引号不配平（视为损坏）
     */
    private fun topLevelTokens(json: String): List<Pair<String, String>>? {
        val inner = json.substring(1, json.length - 1)
        val tokens = mutableListOf<Pair<String, String>>()
        val current = StringBuilder()
        var inString = false
        var escaped = false

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isEmpty()) return
            val separator = indexOfTopLevelColon(text)
            if (separator < 0) return
            tokens += text.substring(0, separator).trim() to text.substring(separator + 1).trim()
        }

        inner.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && inString -> {
                    current.append(char)
                    escaped = true
                }
                char == '"' -> {
                    current.append(char)
                    inString = !inString
                }
                char == ',' && !inString -> flush()
                else -> current.append(char)
            }
        }
        flush()
        return if (inString) null else tokens
    }

    /** 在已切出的 `key:value` 文本里找**不在字符串内**的第一个冒号。 */
    private fun indexOfTopLevelColon(text: String): Int {
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                char == ':' && !inString -> return index
            }
        }
        return -1
    }

    private fun unquote(text: String): String = text.trim().trim('"')

    private fun parseBoolean(value: String): Boolean? =
        when (value.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun parseNumber(value: String): Int? = value.trim().toIntOrNull()

    private fun parseString(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("\"")) return null
        return unescape(trimmed.removeSurrounding("\""))
    }

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (val next = text[index + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        text.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(char)
            }
        }
        out.append('"')
        return out.toString()
    }
}
