package com.rwmodstudio.feature.coord

import kotlin.math.cos
import kotlin.math.sin

private const val DEG_TO_RAD = kotlin.math.PI / 180.0
private const val RAD90 = kotlin.math.PI / 2.0

data class CoordSelf(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var dir: Double = 0.0
)

data class CoordTarget(
    var name: String = "攻击中",
    var x: Double = 200.0,
    var y: Double = 0.0,
    var dir: Double = 0.0
)

/**
 * 资源块：可在表达式中作为变量滑动的自定义数值。
 */
data class CoordResource(
    val name: String,
    val min: Double,
    val max: Double,
    val value: Double
)

/**
 * 一个 Marker 对应文本中一处 `创建标记(...).获取相对偏移(...)` 或 `创建标记(...).获取绝对偏移(...)`。
 */
data class CoordMarker(
    val index: Int,
    val fullRange: IntRange,
    val offsetCallRange: IntRange,

    val baseXExpr: String,
    val baseYExpr: String,
    val baseDirExpr: String,

    val offsetXExpr: String,
    val offsetYExpr: String,
    val dirOffsetExpr: String,

    var baseX: Double = 0.0,
    var baseY: Double = 0.0,
    var baseDir: Double = 0.0,

    var offsetX: Double = 0.0,
    var offsetY: Double = 0.0,
    var dirOffset: Double = 0.0,

    var finalX: Double = 0.0,
    var finalY: Double = 0.0,
    var finalDir: Double = 0.0
) {
    val isDynamic: Boolean
        get() = baseXExpr.contains("存活时间") || baseYExpr.contains("存活时间") ||
                baseDirExpr.contains("存活时间") ||
                offsetXExpr.contains("存活时间") || offsetYExpr.contains("存活时间") ||
                dirOffsetExpr.contains("存活时间")
}

/**
 * 从文本中提取所有 Marker 定义。
 */
fun parseCoordMarkers(text: String): List<CoordMarker> {
    val markers = mutableListOf<CoordMarker>()
    var searchStart = 0
    var index = 0
    while (true) {
        val markerMatch = findCall(text, "创建标记", searchStart) ?: break
        val afterMarker = markerMatch.contentEnd
        val dotOffset = text.findNextNonWs(afterMarker + 1)
        if (dotOffset == -1 || text.getOrNull(dotOffset) != '.') {
            searchStart = markerMatch.contentEnd
            continue
        }
        val offsetMatch = findCall(text, "获取相对偏移", dotOffset + 1)
            ?: findCall(text, "获取绝对偏移", dotOffset + 1)
        if (offsetMatch == null || offsetMatch.nameStart != dotOffset + 1) {
            searchStart = markerMatch.contentEnd
            continue
        }

        val createArgs = parseNamedArgs(markerMatch.content)
        val offsetArgs = parseNamedArgs(offsetMatch.content)

        val baseXExpr = createArgs["x"] ?: ""
        val baseYExpr = createArgs["y"] ?: ""
        val baseDirExpr = createArgs["dir"] ?: "0"

        val offsetXExpr = offsetArgs["x"] ?: offsetArgs.values.toList().getOrNull(1) ?: "0"
        val offsetYExpr = offsetArgs["y"] ?: offsetArgs.values.toList().getOrNull(0) ?: "0"
        val dirOffsetExpr = offsetArgs["角度偏移"] ?: "0"

        markers.add(
            CoordMarker(
                index = index++,
                fullRange = markerMatch.start until offsetMatch.end,
                offsetCallRange = offsetMatch.start until offsetMatch.end,
                baseXExpr = baseXExpr,
                baseYExpr = baseYExpr,
                baseDirExpr = baseDirExpr,
                offsetXExpr = offsetXExpr,
                offsetYExpr = offsetYExpr,
                dirOffsetExpr = dirOffsetExpr
            )
        )
        searchStart = offsetMatch.end
    }
    return markers
}

/**
 * 自动发现表达式中使用的目标名（如 `攻击中.x`、`self.攻击中.x` 中的 `攻击中`）。
 */
fun discoverTargetNames(text: String): Set<String> {
    val names = mutableSetOf<String>()
    val regex = Regex("""(?<!\.)\b([\u4e00-\u9fa5_a-zA-Z][\u4e00-\u9fa5_a-zA-Z0-9]*)\s*\.\s*(x|y|dir)\b""")
    regex.findAll(text).forEach { match ->
        val name = match.groupValues[1].trim()
        if (name != "self") names.add(name)
    }
    // 接近单位的 tag/relation 字符串里也可能出现名称，但这里只把显式属性访问的作为目标
    return names
}

/**
 * 根据文本生成默认目标列表。
 */
fun buildDefaultTargets(text: String): List<CoordTarget> {
    val names = discoverTargetNames(text)
    return if (names.isEmpty()) {
        listOf(CoordTarget(name = "敌人", x = 200.0, y = 0.0, dir = 0.0))
    } else {
        // 所有非 self 单位统一视为同一个敌人目标
        listOf(CoordTarget(name = "敌人", x = 200.0, y = 0.0, dir = 0.0))
    }
}

/**
 * 重新计算所有 Marker 的正向坐标。
 */
fun recalcMarkers(
    markers: List<CoordMarker>,
    self: CoordSelf,
    targets: List<CoordTarget>,
    simTime: Double,
    resources: Map<String, Double> = emptyMap()
) {
    val targetMap = targets.associateBy({ it.name }, { CoordUnit(it.x, it.y, it.dir) })
    val ctxBase = EvalContext(
        self = CoordUnit(self.x, self.y, self.dir),
        targets = targetMap,
        simTime = simTime,
        resources = resources
    )
    markers.forEach { marker ->
        marker.baseX = safeEval(marker.baseXExpr, ctxBase)
        marker.baseY = safeEval(marker.baseYExpr, ctxBase)
        marker.baseDir = safeEval(marker.baseDirExpr, ctxBase)

        val ctxOffset = ctxBase.copy(self = CoordUnit(marker.baseX, marker.baseY, marker.baseDir))
        marker.offsetX = safeEval(marker.offsetXExpr, ctxOffset)
        marker.offsetY = safeEval(marker.offsetYExpr, ctxOffset)
        marker.dirOffset = safeEval(marker.dirOffsetExpr, ctxOffset)

        val rad = marker.baseDir * DEG_TO_RAD
        marker.finalX = marker.baseX + marker.offsetX * cos(rad + RAD90) + marker.offsetY * cos(rad)
        marker.finalY = marker.baseY + marker.offsetX * sin(rad + RAD90) + marker.offsetY * sin(rad)
        marker.finalDir = marker.baseDir + marker.dirOffset
    }
}

/**
 * 由 finalX/finalY 反推 offsetX/offsetY。
 */
fun reverseOffset(marker: CoordMarker, finalX: Double, finalY: Double) {
    val rad = marker.baseDir * DEG_TO_RAD
    val dx = finalX - marker.baseX
    val dy = finalY - marker.baseY
    marker.offsetX = -dx * sin(rad) + dy * cos(rad)
    marker.offsetY = dx * cos(rad) + dy * sin(rad)
}

private fun safeEval(expr: String, ctx: EvalContext): Double {
    if (expr.isBlank()) return 0.0
    val node = parseCoordExpression(expr) ?: return 0.0
    return evaluateCoordExpression(node, ctx)
}

/* ---------- 文本工具 ---------- */

private data class CallMatch(
    val start: Int,
    val end: Int,
    val nameStart: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val content: String
)

private fun findCall(text: String, name: String, start: Int = 0): CallMatch? {
    val nameIdx = text.indexOf(name, start)
    if (nameIdx < 0) return null
    var i = nameIdx + name.length
    while (i < text.length && text[i].isWhitespace()) i++
    if (i >= text.length || text[i] != '(') return null
    val contentStart = i + 1
    val contentEnd = findMatchingParen(text, i)
    if (contentEnd < 0) return null
    return CallMatch(
        start = nameIdx,
        end = contentEnd + 1,
        nameStart = nameIdx,
        contentStart = contentStart,
        contentEnd = contentEnd,
        content = text.substring(contentStart, contentEnd)
    )
}

private fun findMatchingParen(text: String, openIdx: Int): Int {
    var depth = 1
    var i = openIdx + 1
    while (i < text.length && depth > 0) {
        when (text[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        i++
    }
    return if (depth == 0) i - 1 else -1
}

private fun String.findNextNonWs(start: Int): Int {
    var i = start
    while (i < length && this[i].isWhitespace()) i++
    return if (i < length) i else -1
}

/**
 * 解析 `name=expr` 或位置参数，按顺序返回 map。
 * 支持嵌套括号。
 */
private fun parseNamedArgs(content: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    if (content.isBlank()) return result
    val parts = splitTopLevel(content, ',')
    parts.forEachIndexed { index, part ->
        val eqIdx = part.indexOf('=')
        if (eqIdx > 0) {
            val name = part.substring(0, eqIdx).trim()
            val value = part.substring(eqIdx + 1).trim()
            result[name] = value
        } else {
            result["_$index"] = part.trim()
        }
    }
    return result
}

private fun splitTopLevel(text: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in text.indices) {
        when (text[i]) {
            '(' -> depth++
            ')' -> depth--
            delimiter -> if (depth == 0) {
                parts.add(text.substring(start, i))
                start = i + 1
            }
        }
    }
    parts.add(text.substring(start))
    return parts
}
