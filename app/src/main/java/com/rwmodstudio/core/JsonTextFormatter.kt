package com.rwmodstudio.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON 文本的解析 / 美化结果 */
data class JsonFormatResult(
    /** 美化后的文本；解析失败时为 null */
    val formatted: String?,
    /** 失败提示（含行列号）；成功时为 null */
    val error: String?
)

/**
 * JSON / JSONC 的轻量解析与美化（基于已有的 kotlinx-serialization-json，不新增依赖）。
 *
 * - JSONC 会先按字符串边界安全地去掉 `//` 行注释、块注释与尾随逗号；
 * - 解析失败时把 SerializationException 里的 offset 换算成行列，给出明确提示；
 * - 解析失败不影响查看原文（调用方始终保留原始文本）。
 */
object JsonTextFormatter {

    private val parser = Json { isLenient = false }
    private val pretty = Json { prettyPrint = true }
    private val offsetRegex = Regex("offset (\\d+)")

    fun parse(text: String, allowComments: Boolean): JsonFormatResult {
        if (text.isBlank()) {
            return JsonFormatResult(null, "内容为空，不是有效的 JSON")
        }
        val candidate = if (allowComments) stripCommentsAndTrailingCommas(text) else text
        return try {
            val element = parser.parseToJsonElement(candidate)
            JsonFormatResult(pretty.encodeToString(element), null)
        } catch (e: Exception) {
            JsonFormatResult(null, buildErrorMessage(e.message, text))
        }
    }

    /** 只校验、不生成美化文本（打开文件时自动检查用，避免多存一份大字符串） */
    fun check(text: String, allowComments: Boolean): String? {
        if (text.isBlank()) return "内容为空，不是有效的 JSON"
        val candidate = if (allowComments) stripCommentsAndTrailingCommas(text) else text
        return try {
            parser.parseToJsonElement(candidate)
            null
        } catch (e: Exception) {
            buildErrorMessage(e.message, text)
        }
    }

    private fun buildErrorMessage(rawMessage: String?, text: String): String {
        val message = rawMessage?.takeIf { it.isNotBlank() } ?: "JSON 语法错误"
        val offset = offsetRegex.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return message
        val (line, column) = lineColumnAt(text, offset)
        return "第 $line 行第 $column 列附近：$message"
    }

    private fun lineColumnAt(text: String, offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until safe) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (safe - lineStart + 1)
    }

    /**
     * 去掉 JSONC 的注释与尾随逗号。
     *
     * 字符串字面量内部原样保留（含转义），被注释掉的字符替换成空格而非删除，
     * 这样报错 offset 仍能对应回原始文本的行列号。
     */
    private fun stripCommentsAndTrailingCommas(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                    while (i < input.length && input[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < input.length) {
                        if (input[i] == '*' && i + 1 < input.length && input[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (input[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return removeTrailingCommas(out.toString())
    }

    /** 删除 `,` 与 `}` / `]` 之间只有空白时的多余逗号（字符串内不处理） */
    private fun removeTrailingCommas(input: String): String {
        val chars = input.toCharArray()
        var inString = false
        var escaped = false
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < chars.size && chars[j].isWhitespace()) j++
                if (j < chars.size && (chars[j] == '}' || chars[j] == ']')) {
                    chars[i] = ' '
                }
            }
            i++
        }
        return String(chars)
    }
}
