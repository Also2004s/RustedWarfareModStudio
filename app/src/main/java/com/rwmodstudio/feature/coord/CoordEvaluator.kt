package com.rwmodstudio.feature.coord

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val DEG_TO_RAD = kotlin.math.PI / 180.0

data class CoordUnit(
    val x: Double,
    val y: Double,
    val dir: Double,
    val tags: List<String> = emptyList(),
    val relation: String = ""
)

data class EvalContext(
    val self: CoordUnit,
    val targets: Map<String, CoordUnit>,
    val simTime: Double,
    val resources: Map<String, Double> = emptyMap(),
    val rndSeed: Long? = null
)

fun evaluateCoordExpression(node: AstNode, ctx: EvalContext): Double {
    return evalNode(node, ctx).asNumber()
}

private sealed class EvalValue {
    data class Number(val value: Double) : EvalValue()
    data class Unit(val unit: CoordUnit) : EvalValue()

    fun asNumber(): Double = when (this) {
        is Number -> value
        is Unit -> 0.0
    }
}

private fun evalNode(node: AstNode, ctx: EvalContext): EvalValue {
    return when (node) {
        is NumberLiteral -> EvalValue.Number(node.value)
        is Identifier -> when (node.name) {
            "self" -> EvalValue.Unit(ctx.self)
            "存活时间", "时间" -> EvalValue.Number(ctx.simTime)
            else -> {
                val target = ctx.targets[node.name]
                    ?: ctx.targets["敌人"] // 非 self 单位统一视为敌人
                if (target != null) return EvalValue.Unit(target)
                val resource = ctx.resources[node.name]
                if (resource != null) return EvalValue.Number(resource)
                EvalValue.Number(0.0)
            }
        }
        is PropertyAccess -> evalPropertyAccess(node, ctx)
        is Call -> evalCall(node, ctx)
        is BinaryOp -> evalBinaryOp(node, ctx)
        is UnaryOp -> evalUnaryOp(node, ctx)
    }
}

private fun evalPropertyAccess(node: PropertyAccess, ctx: EvalContext): EvalValue {
    // 先尝试把整串点号连接的名字当作资源名（如 自身资源.金合并）
    val resourceKey = buildDottedName(node)
    if (resourceKey != null) {
        val resourceValue = ctx.resources[resourceKey]
        if (resourceValue != null) return EvalValue.Number(resourceValue)
    }

    val base = evalNode(node.expression, ctx)
    val unit = when (base) {
        is EvalValue.Unit -> base.unit
        else -> return EvalValue.Number(0.0)
    }
    return EvalValue.Number(
        when (node.property) {
            "x" -> unit.x
            "y" -> unit.y
            "dir" -> unit.dir
            else -> 0.0
        }
    )
}

private fun buildDottedName(node: AstNode): String? {
    val parts = mutableListOf<String>()
    var n = node
    while (n is PropertyAccess) {
        parts.add(n.property)
        n = n.expression
    }
    return if (n is Identifier) {
        parts.add(n.name)
        parts.asReversed().joinToString(".")
    } else null
}

private fun evalBinaryOp(node: BinaryOp, ctx: EvalContext): EvalValue {
    val left = evalNode(node.left, ctx).asNumber()
    val right = evalNode(node.right, ctx).asNumber()
    val result = when (node.operator) {
        "+" -> left + right
        "-" -> left - right
        "*" -> left * right
        "/" -> if (right == 0.0) 0.0 else left / right
        "%" -> left % right
        ">" -> if (left > right) 1.0 else 0.0
        "<" -> if (left < right) 1.0 else 0.0
        ">=" -> if (left >= right) 1.0 else 0.0
        "<=" -> if (left <= right) 1.0 else 0.0
        "==" -> if (left == right) 1.0 else 0.0
        "!=" -> if (left != right) 1.0 else 0.0
        else -> 0.0
    }
    return EvalValue.Number(result)
}

private fun evalUnaryOp(node: UnaryOp, ctx: EvalContext): EvalValue {
    val value = evalNode(node.operand, ctx).asNumber()
    return EvalValue.Number(
        when (node.operator) {
            "+" -> value
            "-" -> -value
            else -> 0.0
        }
    )
}

private fun evalCall(node: Call, ctx: EvalContext): EvalValue {
    val name = node.name
    val args = node.args
    val positional = args.filter { it.name.isEmpty() }.map { it.expression }
    val named = args.filter { it.name.isNotEmpty() }.associate { it.name to it.expression }

    fun numberAt(index: Int, default: Double = 0.0): Double {
        val expr = positional.getOrNull(index) ?: return default
        return evalNode(expr, ctx).asNumber()
    }

    fun namedNumber(key: String, default: Double = 0.0): Double {
        val expr = named[key] ?: return default
        return evalNode(expr, ctx).asNumber()
    }

    fun unitAt(index: Int): CoordUnit? {
        val expr = positional.getOrNull(index) ?: return null
        val value = evalNode(expr, ctx)
        return when (value) {
            is EvalValue.Unit -> value.unit
            else -> null
        }
    }

    fun namedUnit(key: String): CoordUnit? {
        val expr = named[key] ?: return null
        val value = evalNode(expr, ctx)
        return when (value) {
            is EvalValue.Unit -> value.unit
            else -> null
        }
    }

    fun stringArg(key: String? = null, index: Int = -1): String? {
        val expr = if (key != null) named[key] else if (index >= 0) positional.getOrNull(index) else null
        return when (expr) {
            is Identifier -> expr.name
            is NumberLiteral -> expr.value.toString()
            else -> expr?.let { evalNode(it, ctx).asNumber().toString() }
        }
    }

    // 辅助：把第一个可用的 unit 参数取出来
    fun firstUnit(): CoordUnit? {
        return unitAt(0) ?: namedUnit("target") ?: namedUnit("目标") ?: namedUnit("a") ?: namedUnit("单位")
    }

    fun secondUnit(): CoordUnit? {
        return unitAt(1) ?: namedUnit("source") ?: namedUnit("自身") ?: namedUnit("b")
    }

    return when (name) {
        "sin" -> EvalValue.Number(sin(numberAt(0) * DEG_TO_RAD))
        "cos" -> EvalValue.Number(cos(numberAt(0) * DEG_TO_RAD))
        "int" -> EvalValue.Number(numberAt(0).toInt().toDouble())
        "sqrt" -> EvalValue.Number(sqrt(numberAt(0)))
        "min", "最小" -> {
            val values = if (positional.isNotEmpty()) positional.map { evalNode(it, ctx).asNumber() }
            else named.values.map { evalNode(it, ctx).asNumber() }
            EvalValue.Number(values.minOrNull() ?: 0.0)
        }
        "max", "最大" -> {
            val values = if (positional.isNotEmpty()) positional.map { evalNode(it, ctx).asNumber() }
            else named.values.map { evalNode(it, ctx).asNumber() }
            EvalValue.Number(values.maxOrNull() ?: 0.0)
        }
        "rnd" -> {
            val min = numberAt(0)
            val max = numberAt(1)
            EvalValue.Number((min + max) / 2.0)
        }
        "distance", "距离", "间距" -> {
            val a = firstUnit()
            val b = secondUnit()
            if (a != null && b != null) {
                EvalValue.Number(distance(a.x, a.y, b.x, b.y))
            } else {
                val x1 = numberAt(0, namedNumber("x1"))
                val y1 = numberAt(1, namedNumber("y1"))
                val x2 = numberAt(2, namedNumber("x2"))
                val y2 = numberAt(3, namedNumber("y2"))
                EvalValue.Number(distance(x1, y1, x2, y2))
            }
        }
        "direction", "方向", "之间方向" -> {
            val a = firstUnit()
            val b = secondUnit()
            if (a != null && b != null) {
                EvalValue.Number(Math.toDegrees(atan2(b.y - a.y, b.x - a.x)))
            } else {
                val x1 = numberAt(0, namedNumber("x1"))
                val y1 = numberAt(1, namedNumber("y1"))
                val x2 = numberAt(2, namedNumber("x2"))
                val y2 = numberAt(3, namedNumber("y2"))
                EvalValue.Number(Math.toDegrees(atan2(y2 - y1, x2 - x1)))
            }
        }
        "存活时间" -> EvalValue.Number(ctx.simTime)
        "选择" -> {
            val condition = numberAt(0) != 0.0
            val a = numberAt(1)
            val b = numberAt(2)
            EvalValue.Number(if (condition) a else b)
        }
        "接近单位" -> {
            // 忽略 tag/relation，直接返回距离 self 最近的目标
            val selfPos = ctx.self
            val target = ctx.targets.values
                .minByOrNull { distance(selfPos.x, selfPos.y, it.x, it.y) }
                ?: CoordUnit(0.0, 0.0, 0.0)
            EvalValue.Unit(target)
        }
        else -> EvalValue.Number(0.0)
    }
}

private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    return sqrt(dx * dx + dy * dy)
}
