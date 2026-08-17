package com.rwmodstudio.feature.coord

/**
 * 将 Marker 被拖拽后的新 offset 写回源码。
 *
 * 规则：
 * - 默认情况下，如果 offset 表达式包含 `存活时间` 等动态部分，不修改（返回原 text）。
 * - 传入 allowDynamic=true 时，直接重写整个 offset 调用为静态数值。
 * - 会保留原调用是 `获取相对偏移` 还是 `获取绝对偏移`。
 * - 只会修改原调用中已经存在的参数，不会新增 x= / y= / 角度偏移= 等参数，
 *   因此不会出现“追加一段内容”的现象。
 */
fun applyMarkerOffsetToText(text: String, marker: CoordMarker, allowDynamic: Boolean = false): String {
    if (!allowDynamic && marker.isDynamic) return text
    if (marker.offsetCallRange.isEmpty()) return text

    val offsetCallText = text.substring(marker.offsetCallRange)
    val isAbsolute = offsetCallText.startsWith("获取绝对偏移")

    // 解析原调用中各参数的位置，只更新已存在的参数
    val args = parseOffsetCallArgs(offsetCallText)
    if (args.isEmpty()) return text

    val baseOffset = marker.offsetCallRange.first
    var newText = text

    // 从后往前替换，避免前面的替换影响后面的索引
    args.asReversed().forEach { (name, valueRangeInCall) ->
        val valueRangeInText = (baseOffset + valueRangeInCall.first) until (baseOffset + valueRangeInCall.last)
        val newValue = when (name) {
            "x" -> if (isAbsolute) formatCoordNumber(marker.finalX) else formatCoordNumber(marker.offsetX)
            "y" -> if (isAbsolute) formatCoordNumber(marker.finalY) else formatCoordNumber(marker.offsetY)
            "角度偏移" -> formatCoordNumber(marker.dirOffset)
            else -> return@forEach
        }
        newText = newText.replaceRange(valueRangeInText, newValue)
    }

    return newText
}

/**
 * 解析 offset 调用文本中每个命名参数的值在调用文本内的索引范围。
 * 返回 [(参数名, 值在调用文本中的 IntRange)]，按出现顺序排列。
 * 值范围会去掉前后空白，避免替换时破坏格式。
 */
private fun parseOffsetCallArgs(callText: String): List<Pair<String, IntRange>> {
    val openIdx = callText.indexOf('(')
    val closeIdx = callText.lastIndexOf(')')
    if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) return emptyList()

    val content = callText.substring(openIdx + 1, closeIdx)
    val parts = splitTopLevelArgs(content)
    val result = mutableListOf<Pair<String, IntRange>>()
    var pos = openIdx + 1

    parts.forEach { part ->
        val eqIdx = part.indexOf('=')
        if (eqIdx > 0) {
            val name = part.substring(0, eqIdx).trim()
            val rawValue = part.substring(eqIdx + 1)
            val valueStart = pos + eqIdx + 1
            val leadingSpaces = rawValue.takeWhile { it.isWhitespace() }.length
            val trailingSpaces = rawValue.takeLastWhile { it.isWhitespace() }.length
            val trimmedStart = valueStart + leadingSpaces
            val trimmedEnd = valueStart + rawValue.length - trailingSpaces
            if (trimmedEnd > trimmedStart) {
                result.add(name to (trimmedStart until trimmedEnd))
            }
        }
        pos += part.length + 1 // +1 为分隔逗号
    }

    return result
}

/**
 * 按顶层逗号分割参数，忽略嵌套括号内的逗号。
 */
private fun splitTopLevelArgs(text: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in text.indices) {
        when (text[i]) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) {
                parts.add(text.substring(start, i))
                start = i + 1
            }
        }
    }
    parts.add(text.substring(start))
    return parts
}

fun formatCoordNumber(value: Double): String {
    val rounded = kotlin.math.round(value)
    if (kotlin.math.abs(value - rounded) < 1e-6) {
        return rounded.toLong().toString()
    }
    return String.format("%.2f", value).trimEnd('0').trimEnd('.')
}

/**
 * 将代码中独立的 `存活时间` / `时间` 变量替换为当前时间数值，
 * 实现“时间滑块松手后将动态代码烘焙为静态代码”。
 * 不会替换 `存活时间(...)` 这种函数调用形式。
 */
fun bakeTimeIntoCode(text: String, time: Double): String {
    val timeValue = formatCoordNumber(time)
    // 匹配独立的“存活时间”或“时间”变量，不替换函数调用形式存活时间(...)
    val regex = Regex("""(?<![\\p{L}\\p{N}_])(存活时间|时间)(?![\\p{L}\\p{N}_])(?![(])""")
    return regex.replace(text, timeValue)
}
