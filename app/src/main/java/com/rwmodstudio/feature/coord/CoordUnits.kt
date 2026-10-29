package com.rwmodstudio.feature.coord

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 单位名收集：公式里出现的每个单位都应该被当作**独立的点**来对待。
 *
 * 之前的问题：目标表里只登记「self / 敌人 / 当前动作目标 / 活动路径点目标」这几个名字，
 * 其余名字在求值时被兜底成「敌人」——于是公式里写 `间距(self, 敌方)` 会悄悄用"敌人"的位置，
 * 位置整个串台，而且界面上也看不到这个点、拖不了。
 *
 * 这里改为从 AST 精确提取「被当作单位使用」的标识符：
 * - 出现在 `间距/距离/distance`、`之间方向/方向/direction` 的位置参数上；
 * - 或者出现在 `xxx.x / .y / .dir` 的属性访问里。
 * 只写名字、当数值用的（存活时间、资源名、`D:` 定义出来的变量）不会被误收。
 */
private val UNIT_POSITIONAL_ARGS: Map<String, Set<Int>> = mapOf(
    "距离" to setOf(0, 1),
    "间距" to setOf(0, 1),
    "distance" to setOf(0, 1),
    "方向" to setOf(0, 1),
    "之间方向" to setOf(0, 1),
    "direction" to setOf(0, 1)
)

const val SELF_NAME = "self"

/**
 * 合法的「单位标记」（来源：代码表 logicboolean 枚举里代表单位的那批），中英双份。
 *
 * 只有完整命中这里的名字才算一个点：
 * 输入过程中打出的半截词（"当前"、"当前动作"）不是任何单位标记，不能凭空长出一个点；
 * 反过来，`thisActionIndex`（动作索引）这类同表但**不是单位**的标记也不该被当成点。
 */
val UNIT_MARKER_NAMES: Set<String> = setOf(
    SELF_NAME, "自身",
    "当前动作目标", "thisActionTarget",
    "活动路径点目标", "activeWaypointTarget",
    "最后伤害源", "lastDamagedBy",
    "父单位", "parent",
    "附件", "attachment",
    "运输", "transporting",
    "攻击中", "attacking",
    "自定义目标1", "customTarget1",
    "自定义目标2", "customTarget2",
    "空单位", "nullUnit",
    "事件来源", "eventSource"
)

/** 名字是否是合法单位标记（self 也算，但不算"其它单位"） */
fun isUnitMarker(name: String): Boolean = name in UNIT_MARKER_NAMES

/** 遍历 AST 里所有「被当作单位使用」的引用（self 也包含在内） */
private fun forEachUnitRef(node: AstNode, onName: (String) -> Unit) {
    when (node) {
        is PropertyAccess -> {
            val base = node.expression
            if (base is Identifier) onName(base.name) else forEachUnitRef(base, onName)
        }
        is Call -> {
            val unitArgs = UNIT_POSITIONAL_ARGS[node.name]
            var positionalIndex = -1
            node.args.forEach { arg ->
                val idx = if (arg.name.isEmpty()) {
                    positionalIndex++
                    positionalIndex
                } else {
                    -1
                }
                val isUnitArg = unitArgs != null && idx >= 0 && unitArgs.contains(idx)
                val expr = arg.expression
                if (isUnitArg && expr is Identifier) {
                    onName(expr.name)
                } else {
                    forEachUnitRef(expr, onName)
                }
            }
        }
        is BinaryOp -> {
            forEachUnitRef(node.left, onName)
            forEachUnitRef(node.right, onName)
        }
        is UnaryOp -> forEachUnitRef(node.operand, onName)
        else -> Unit
    }
}

fun collectUnitNames(node: AstNode, out: MutableSet<String> = mutableSetOf()): MutableSet<String> {
    // 只收合法单位标记：输入到一半的名字（"当前动作"）不建点
    forEachUnitRef(node) { if (it != SELF_NAME && isUnitMarker(it)) out.add(it) }
    return out
}

/**
 * 推断「考察点」：公式里出现次数最多的那个非 self 单位（平局取最先出现的）。
 *
 * 这个判定要回答的是「哪个点放在哪里才成立」，主语就是它。
 * 公式里写得越多的那个点，通常就是被反复约束的对象——
 * 例如 `间距(self,A) + 间距(A,C) <= 间距(self,C)` 里 A 和 C 各出现两次，
 * 取先出现的 A，正好等于「A 是不是挡在 self 到 C 的路上」。
 */
fun inferProbeName(node: AstNode): String? {
    val counts = linkedMapOf<String, Int>()
    forEachUnitRef(node) { name -> counts[name] = (counts[name] ?: 0) + 1 }
    counts.remove(SELF_NAME)
    return counts.entries.maxByOrNull { it.value }?.key
}

fun inferProbeName(program: FormulaProgram): String? {
    val counts = linkedMapOf<String, Int>()
    program.definitions.forEach { (_, node) -> forEachUnitRef(node) { n -> counts[n] = (counts[n] ?: 0) + 1 } }
    forEachUnitRef(program.condition) { n -> counts[n] = (counts[n] ?: 0) + 1 }
    counts.remove(SELF_NAME)
    return counts.entries.maxByOrNull { it.value }?.key
}

/** 从一段脚本式代码（`创建标记(...)` 或多行公式）里收集单位名 */
fun collectUnitNamesFromText(text: String, out: MutableSet<String> = mutableSetOf()): MutableSet<String> {
    val markers = parseCoordMarkers(text)
    if (markers.isNotEmpty()) {
        markers.forEach { marker ->
            listOf(
                marker.baseXExpr, marker.baseYExpr, marker.baseDirExpr,
                marker.offsetXExpr, marker.offsetYExpr, marker.dirOffsetExpr
            ).forEach { expr ->
                if (expr.isNotBlank()) parseCoordExpression(expr)?.let { collectUnitNames(it, out) }
            }
        }
    }
    if (out.isEmpty()) {
        parseCoordExpression(text)?.let { collectUnitNames(it, out) }
        parseFormulaProgram(text)?.let { program ->
            program.definitions.forEach { (_, node) -> collectUnitNames(node, out) }
            collectUnitNames(program.condition, out)
        }
    }
    return out
}

/**
 * 给新出现的单位分配一个错开的默认位置（黄金角散开，避免全叠在原点）。
 * self 占 0 号位，其它单位从 1 开始。
 */
fun defaultUnitPosition(index: Int): Pair<Double, Double> {
    val angle = index * 137.5 * PI / 180.0
    val radius = 200.0 + index * 45.0
    return cos(angle) * radius to sin(angle) * radius
}

/** 把新发现的单位补进目标表：已有的位置/朝向一律保留，只追加缺的 */
fun mergeDiscoveredTargets(existing: List<CoordTarget>, names: Set<String>): List<CoordTarget> {
    val known = existing.map { it.name }.toMutableSet()
    val added = mutableListOf<CoordTarget>()
    names.forEach { name ->
        if (isUnitMarker(name) && name != SELF_NAME && known.add(name)) {
            val (x, y) = defaultUnitPosition(known.size)
            added.add(CoordTarget(name = name, x = x, y = y))
        }
    }
    return if (added.isEmpty()) existing else existing + added
}

/**
 * 同步目标表：**增**（新出现的单位标记）+ **删**（名字已经不在代码里被引用、或不是合法单位标记）。
 *
 * 用于代码边输入边预览：打出"当前"时它既不是单位标记也不会被当成点；
 * 等打到完整的"当前动作目标"才建点；把某个名字改掉后，旧的多余点也会跟着消失，不会越积越多。
 */
fun syncDiscoveredTargets(existing: List<CoordTarget>, referenced: Set<String>): List<CoordTarget> {
    val kept = existing.filter { it.name in referenced }
    return mergeDiscoveredTargets(kept, referenced)
}

/** 同上，给仿真动画页用（新增的单位默认静止） */
fun mergeDiscoveredSimTargets(existing: List<SimTarget>, names: Set<String>): List<SimTarget> {
    val known = existing.map { it.name }.toMutableSet()
    val added = mutableListOf<SimTarget>()
    names.forEach { name ->
        if (isUnitMarker(name) && name != SELF_NAME && known.add(name)) {
            val (x, y) = defaultUnitPosition(known.size)
            added.add(SimTarget(name = name, baseX = x, baseY = y))
        }
    }
    return if (added.isEmpty()) existing else existing + added
}

fun syncDiscoveredSimTargets(existing: List<SimTarget>, referenced: Set<String>): List<SimTarget> {
    val kept = existing.filter { it.name in referenced }
    return mergeDiscoveredSimTargets(kept, referenced)
}
