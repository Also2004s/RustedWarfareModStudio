package com.rwmodstudio.feature.coord

import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 仿真动画引擎：把「公式」变成「区域」，再让参数随时间动起来。
 *
 * 三块内容：
 * 1. [SimMotion] 目标运动模型 —— 决定动画里目标怎么走；
 * 2. [sampleRegion] 区域采样 —— 在可视范围内逐点代入公式，得到"公式成立"的掩码；
 * 3. [SimPreset] 预设场景 —— 开箱即用的几个演示。
 *
 * 采样思路：公式里选一个「自由变量」（探针目标），把它当作可移动的点，
 * 在世界坐标网格上逐格代入求值；成立就标记该格。这样任何布尔公式都能画出区域，
 * 而等号类公式（如三角不等式的等号情形）配合容差会画成一条"等值带"。
 */

private const val DEG_TO_RAD = kotlin.math.PI / 180.0

/* ---------------- 运动模型 ---------------- */

sealed class SimMotion {
    /** 静止：位置就是基准点 */
    data object Still : SimMotion()

    /** 绕 (cx,cy) 匀速圆周：radius 半径（世界单位），periodSec 一圈用多少秒 */
    data class Circle(
        val cx: Double,
        val cy: Double,
        val radius: Double,
        val periodSec: Double,
        val phaseDeg: Double = 0.0
    ) : SimMotion()

    /** 沿折线匀速往返（点集为世界坐标绝对位置），totalSec 走完一遍的耗时 */
    data class Path(
        val points: List<Pair<Double, Double>>,
        val totalSec: Double,
        val loop: Boolean = true
    ) : SimMotion()
}

/** 一个参与仿真的目标：名字 + 基准位置 + 运动模型 */
data class SimTarget(
    val name: String,
    val baseX: Double,
    val baseY: Double,
    val dir: Double = 0.0,
    val motion: SimMotion = SimMotion.Still
)

/** 计算 t 秒时该目标的位置 */
fun SimTarget.positionAt(t: Double): Pair<Double, Double> {
    return when (val m = motion) {
    is SimMotion.Still -> baseX to baseY
    is SimMotion.Circle -> {
        val period = if (m.periodSec <= 0.0) 1.0 else m.periodSec
        val ang = (t / period) * 2.0 * kotlin.math.PI + m.phaseDeg * DEG_TO_RAD
        (m.cx + m.radius * cos(ang)) to (m.cy + m.radius * sin(ang))
    }
    is SimMotion.Path -> {
        val pts = m.points
        val total = if (m.totalSec <= 0.0) 1.0 else m.totalSec
        if (pts.size < 2) {
            baseX to baseY
        } else {
            // 各段长度 → 累计进度
            val segLen = ArrayList<Double>(pts.size - 1)
            var sum = 0.0
            for (i in 0 until pts.size - 1) {
                val l = hypot(pts[i + 1].first - pts[i].first, pts[i + 1].second - pts[i].second)
                segLen.add(l)
                sum += l
            }
            if (sum <= 0.0) {
                baseX to baseY
            } else {
                var u = (t / total) % 1.0
                if (u < 0) u += 1.0
                // 往返模式：后半程反向
                val forward = if (!m.loop) (u * 2.0).let { if (it > 1.0) 2.0 - it else it } else u
                var dist = forward * sum
                for (i in 0 until pts.size - 1) {
                    if (dist <= segLen[i] || i == pts.size - 2) {
                        val f = if (segLen[i] <= 0.0) 0.0 else (dist / segLen[i]).coerceIn(0.0, 1.0)
                        val x = pts[i].first + (pts[i + 1].first - pts[i].first) * f
                        val y = pts[i].second + (pts[i + 1].second - pts[i].second) * f
                        return x to y
                    }
                    dist -= segLen[i]
                }
                baseX to baseY
            }
        }
    }
    }
}

fun List<SimTarget>.animatedAt(t: Double): List<SimTarget> = map { target ->
    val (x, y) = target.positionAt(t)
    target.copy(baseX = x, baseY = y)
}

/* ---------------- 公式程序（支持前置变量定义） ---------------- */

/**
 * 一段带前置定义的公式，对应脚本里常见的写法：
 *
 * ```
 * D:min(..., 360-...)
 * $(D)<=90 and 间距(self, 当前动作目标)*sin($(D))<=15
 * ```
 *
 * - `名称:表达式` 会被解析成定义，逐条求值后放进 [EvalContext.vars]；
 * - 条件里的 `$(名称)` 在解析前统一还原成标识符。
 * 定义只允许出现在最前面，结束于「括号深度为 0 的空格或分号」——表达式内部的空格都在括号里，不会被误切。
 */
data class FormulaProgram(
    val definitions: List<Pair<String, AstNode>>,
    val condition: AstNode
)

fun parseFormulaProgram(raw: String): FormulaProgram? {
    val (rawDefs, rawCondition) = splitDefinitions(raw)
    if (rawCondition.isBlank()) return null
    val defs = rawDefs.map { (name, body) ->
        val node = parseCoordExpression(body) ?: return null
        name to node
    }
    val condition = parseCoordExpression(rawCondition) ?: return null
    return FormulaProgram(defs, condition)
}

/** 把 `$(D)` 这类变量引用还原成普通标识符，并按 `名称:` 切出前置定义 */
private fun splitDefinitions(raw: String): Pair<List<Pair<String, String>>, String> {
    val defs = mutableListOf<Pair<String, String>>()
    var rest = raw.replace(Regex("""\$\(\s*([^)]+?)\s*\)"""), "$1").trim()
    val nameRegex = Regex("""^([\u4e00-\u9fa5A-Za-z_][\u4e00-\u9fa5A-Za-z0-9_]*)\s*:""")
    while (true) {
        val match = nameRegex.find(rest) ?: break
        val name = match.groupValues[1]
        var depth = 0
        var i = match.range.last + 1
        var end = rest.length
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                (c == ';' || c.isWhitespace()) && depth == 0 -> {
                    end = i
                    break
                }
            }
            i++
        }
        val body = rest.substring(match.range.last + 1, end).trim()
        if (body.isEmpty()) break
        defs.add(name to body)
        rest = rest.substring(end).trim().removePrefix(";").trim()
    }
    return defs to rest
}

/** AST 是否引用了某个名字（判断定义是否随自由变量变化） */
private fun AstNode.mentions(name: String): Boolean = when (this) {
    is Identifier -> this.name == name
    is PropertyAccess -> expression.mentions(name)
    is Call -> args.any { it.expression.mentions(name) }
    is BinaryOp -> left.mentions(name) || right.mentions(name)
    is UnaryOp -> operand.mentions(name)
    is NumberLiteral -> false
}

private fun evalDefinitions(defs: List<Pair<String, AstNode>>, ctx: EvalContext): Map<String, Double> {
    if (defs.isEmpty()) return ctx.vars
    var current = ctx
    defs.forEach { (name, node) ->
        val value = evaluateCoordExpression(node, current)
        current = current.copy(vars = current.vars + (name to value))
    }
    return current.vars
}

/* ---------------- 采样前的常量折叠 ---------------- */

/**
 * 把「与自由变量无关」且「不含单位（self/目标）」的子树一次性算成常数。
 *
 * 采样是逐格求值的热路径：像 `自身角度`、`360-x`、`(…+720)%360`、`>180` 这些跟采样点无关的部分，
 * 9000 格会重复算 9000 次。折叠后每格只剩真正依赖目标坐标的那几个节点，快一个数量级。
 *
 * 不能折的两类：含自由变量（每格都变）、含单位名（求值结果是单位而不是数字，折了就丢语义）。
 */
private fun AstNode.foldForSampling(
    ctx: EvalContext,
    probeName: String,
    blockers: Set<String>
): AstNode {
    if (!mentions(probeName) && !containsAnyName(blockers)) {
        return NumberLiteral(evaluateCoordExpression(this, ctx))
    }
    return when (this) {
        is BinaryOp -> BinaryOp(
            operator,
            left.foldForSampling(ctx, probeName, blockers),
            right.foldForSampling(ctx, probeName, blockers)
        )
        is UnaryOp -> UnaryOp(operator, operand.foldForSampling(ctx, probeName, blockers))
        is Call -> Call(name, args.map { NamedArgument(it.name, it.expression.foldForSampling(ctx, probeName, blockers)) })
        is PropertyAccess -> PropertyAccess(expression.foldForSampling(ctx, probeName, blockers), property)
        else -> this
    }
}

private fun AstNode.containsAnyName(names: Set<String>): Boolean = when (this) {
    is Identifier -> name in names
    is PropertyAccess -> expression.containsAnyName(names)
    is Call -> args.any { it.expression.containsAnyName(names) }
    is BinaryOp -> left.containsAnyName(names) || right.containsAnyName(names)
    is UnaryOp -> operand.containsAnyName(names)
    is NumberLiteral -> false
}

/* ---------------- 区域采样 ---------------- */

/**
 * 公式区域。
 *
 * 关键点：不是只记「成不成立」，而是记每个采样点上「左 − 右」的实数值。
 * 布尔掩码在零宽度区域上必然漏采（线比格子还细），但实数值场可以做**等值线提取**：
 * `f = 0` 的那条线就是区域边界；当区域退化成一条线（三角不等式等号情形）时，
 * 这条边界线本身就是区域——所以再细的区域也能精确画出来，不需要用户自己加容差。
 *
 * cells 仍保留，用于形状填充（大面积区域直接铺色）。
 */
data class RegionMask(
    val cols: Int,
    val rows: Int,
    val minX: Double,
    val minY: Double,
    val stepX: Double,
    val stepY: Double,
    /** 有符号场：比较式取「左 − 右」，普通式子取表达式值。0 即边界。 */
    val values: FloatArray,
    /** 填充掩码：1 = 该点满足公式 */
    val cells: ByteArray,
    /**
     * 等值线用的阈值。
     * 视野内有真正成立的点 → 0（就是边界）；
     * 一个都没有（三角不等式这类：左式恒大于右式，只在一条线上取等号）→ 取「最小值 + 极小量」，
     * 于是画出来的仍然是那条"最接近成立"的线，用户不需要给公式加任何宽容值。
     */
    val threshold: Float,
    /** 采样场上出现的最小值（= 0 说明有真正成立的点） */
    val minValue: Float,
    /** 视野内是否存在真正成立的点 */
    val hasSolution: Boolean,
    /**
     * 无解时（三角不等式这类：左侧恒大于右侧，只在一条线上取等号）的零集点链，
     * 世界坐标线段 [x1,y1,x2,y2,...]。
     *
     * 为什么不用等值线：等值线画出来是一个"有宽度的闭合轮廓"，看着就像容差 10 那种带子；
     * 而这条式子真正的区域是一条零宽度的线。这里沿每列对 f 做一维最小化（三分搜索），
     * 直接收敛到 f 的最小值位置，精度由搜索决定、与采样网格无关，所以再细也画得出来。
     */
    val zeroPath: FloatArray,
    /** 命中格子占比，供 UI 判断"恒真/恒假/只有一条线" */
    val coverage: Float
)

/**
 * 提取等值线（Marching Squares 简化版），返回世界坐标线段数组：
 * [x1,y1,x2,y2, x1,y1,x2,y2, ...]
 *
 * 退化区域（只有一条线）靠它就完整可见——线的粗细由画布描边决定，与采样密度无关。
 */
fun RegionMask.contourSegments(): FloatArray {
    val out = ArrayList<Float>(256)
    val thr = threshold
    fun v(col: Int, row: Int) = values[row * cols + col]
    fun wx(col: Int) = (minX + col * stepX).toFloat()
    fun wy(row: Int) = (minY + row * stepY).toFloat()
    // 在一条边上按线性插值找 f = threshold 的位置
    fun lerp(a: Float, b: Float): Float = if (a == b) 0.5f else ((a - thr) / (a - b)).coerceIn(0f, 1f)

    for (row in 0 until rows - 1) {
        for (col in 0 until cols - 1) {
            val v00 = v(col, row)
            val v10 = v(col + 1, row)
            val v11 = v(col + 1, row + 1)
            val v01 = v(col, row + 1)
            val anyPos = v00 > thr || v10 > thr || v11 > thr || v01 > thr
            val anyNeg = v00 <= thr || v10 <= thr || v11 <= thr || v01 <= thr
            if (!anyPos || !anyNeg) continue // 四角同侧：格内没有边界

            // 收集四条边上与 f = threshold 的交点（最多 4 个）
            val points = ArrayList<Float>(8)
            fun edge(ax: Float, ay: Float, av: Float, bx: Float, by: Float, bv: Float) {
                if ((av > thr) == (bv > thr)) return
                val t = lerp(av, bv)
                points.add(ax + (bx - ax) * t)
                points.add(ay + (by - ay) * t)
            }
            edge(wx(col), wy(row), v00, wx(col + 1), wy(row), v10)                    // 上
            edge(wx(col + 1), wy(row), v10, wx(col + 1), wy(row + 1), v11)            // 右
            edge(wx(col), wy(row + 1), v01, wx(col + 1), wy(row + 1), v11)            // 下
            edge(wx(col), wy(row), v00, wx(col), wy(row + 1), v01)                    // 左

            when (points.size) {
                4 -> {
                    out.add(points[0]); out.add(points[1]); out.add(points[2]); out.add(points[3])
                }
                8 -> {
                    // 鞍点：用格心符号把四条边分成两组
                    val center = (v00 + v10 + v11 + v01) / 4f
                    if ((center > 0f) == (v00 > 0f)) {
                        out.add(points[0]); out.add(points[1]); out.add(points[2]); out.add(points[3])
                        out.add(points[4]); out.add(points[5]); out.add(points[6]); out.add(points[7])
                    } else {
                        out.add(points[0]); out.add(points[1]); out.add(points[6]); out.add(points[7])
                        out.add(points[2]); out.add(points[3]); out.add(points[4]); out.add(points[5])
                    }
                }
            }
        }
    }
    return out.toFloatArray()
}

private val COMPARISON_OPS = setOf(">", "<", ">=", "<=", "==", "!=")

/**
 * 若公式顶层是比较运算，拆成 (运算符, 左, 右)，否则返回 null。
 * 拆分出来是为了支持「容差」：等号类公式不给容差的话区域是一条零宽度的线，采样必然采不到。
 */
fun splitTopComparison(node: AstNode): BinaryOp? {
    val bin = node as? BinaryOp ?: return null
    return if (bin.operator in COMPARISON_OPS) bin else null
}

/**
 * 在世界坐标矩形内逐格采样公式。
 *
 * @param program 已解析的公式（含前置定义）
 * @param base 基准求值上下文（自身/其它目标/时间/资源）
 * @param probeName 自由变量名：该目标的位置由采样点决定
 */
fun sampleRegion(
    program: FormulaProgram,
    base: EvalContext,
    probeName: String,
    cols: Int,
    rows: Int,
    minX: Double,
    minY: Double,
    stepX: Double,
    stepY: Double
): RegionMask {
    val c = cols.coerceIn(1, 512)
    val r = rows.coerceIn(1, 512)
    val cells = ByteArray(c * r)
    val values = FloatArray(c * r)
    // 前置定义若引用了自由变量（例如定义里出现目标坐标），就必须每格重算；否则整个网格共用一个结果
    val probeDefNames = program.definitions
        .filter { (_, node) -> node.mentions(probeName) }
        .map { it.first }
        .toSet()
    val blockers = base.targets.keys + setOf("self") + probeDefNames
    var foldCtx = base
    val foldedDefs = program.definitions.map { (name, node) ->
        val folded = node.foldForSampling(foldCtx, probeName, blockers)
        if (name !in probeDefNames) {
            foldCtx = foldCtx.copy(vars = foldCtx.vars + (name to evaluateCoordExpression(folded, foldCtx)))
        }
        name to folded
    }
    val condition = program.condition.foldForSampling(foldCtx, probeName, blockers)
    val cmp = splitTopComparison(condition)
    val defNeedsProbe = probeDefNames.isNotEmpty()
    val preVars = if (defNeedsProbe) emptyMap() else foldCtx.vars

    // 任意一点求 g（统一成「g <= 0 即成立」：>= / > 只是把左右对调）
    fun gAt(x: Double, y: Double): Double {
        var ctx = base.copy(probeName = probeName, probeX = x, probeY = y, vars = preVars)
        if (defNeedsProbe) ctx = ctx.copy(vars = evalDefinitions(foldedDefs, ctx))
        return if (cmp != null) {
            val l = evaluateCoordExpression(cmp.left, ctx)
            val r = evaluateCoordExpression(cmp.right, ctx)
            when (cmp.operator) {
                ">=", ">" -> r - l
                "==", "!=" -> abs(l - r)
                else -> l - r
            }
        } else {
            evaluateCoordExpression(condition, ctx)
        }
    }

    for (ry in 0 until r) {
        val wy = minY + (ry + 0.5) * stepY
        val rowOffset = ry * c
        for (rx in 0 until c) {
            val wx = minX + (rx + 0.5) * stepX
            values[rowOffset + rx] = gAt(wx, wy).toFloat()
        }
    }
    var minV = Float.MAX_VALUE
    var maxV = -Float.MAX_VALUE
    for (v in values) {
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }
    val op = cmp?.operator
    // 严格用「负得足够多」判定有解：等号处浮点误差会让 minV 在 0 附近抖动，
    // 若把 ±1e-13 当成"有解"，就会去画一个几乎退化成一个点的轮廓。
    val hasSolution = op == "!=" || minV < -1e-6f
    val threshold = 0f
    // 填充只认严格判据：不能把"最接近"也算成区域，否则恒不成立的公式会被画成一大片
    var hitCount = 0
    for (i in values.indices) {
        val g = values[i]
        val holds = when (op) {
            "<=", ">=", "<", ">" -> g <= 0f
            "==" -> abs(g) <= 1e-6f
            "!=" -> abs(g) > 1e-6f
            else -> g != 0f
        }
        if (holds) {
            cells[i] = 1
            hitCount++
        }
    }

    // 无解时求出零集点链：沿每列先取粗网格最小值所在行，再在该行上下各一格内做三分搜索，
    // 收敛到 f 的真正最小值位置。精度由搜索决定（亚像素级），与采样网格无关。
    val zeroPath: FloatArray = if (hasSolution) {
        FloatArray(0)
    } else {
        val acceptLimit = minV + (maxV - minV) * 0.05f
        val ys = FloatArray(c) { Float.NaN }
        for (col in 0 until c) {
            var bestRow = 0
            var bestV = Float.MAX_VALUE
            for (row in 0 until r) {
                val v = values[row * c + col]
                if (v < bestV) {
                    bestV = v
                    bestRow = row
                }
            }
            if (bestV > acceptLimit) continue
            val x = minX + (col + 0.5) * stepX
            var lo = minY + (bestRow - 1).coerceAtLeast(0) * stepY
            var hi = minY + (bestRow + 2).coerceAtMost(r) * stepY
            repeat(18) {
                val m1 = lo + (hi - lo) / 3.0
                val m2 = hi - (hi - lo) / 3.0
                if (gAt(x, m1) < gAt(x, m2)) hi = m2 else lo = m1
            }
            ys[col] = ((lo + hi) / 2.0).toFloat()
        }
        val seg = ArrayList<Float>(c * 4)
        for (col in 0 until c - 1) {
            val y0 = ys[col]
            val y1 = ys[col + 1]
            if (y0.isNaN() || y1.isNaN()) continue
            seg.add((minX + (col + 0.5) * stepX).toFloat()); seg.add(y0.toFloat())
            seg.add((minX + (col + 1.5) * stepX).toFloat()); seg.add(y1.toFloat())
        }
        seg.toFloatArray()
    }

    return RegionMask(
        cols = c, rows = r,
        minX = minX, minY = minY, stepX = stepX, stepY = stepY,
        values = values,
        cells = cells,
        threshold = threshold,
        minValue = minV,
        hasSolution = hasSolution,
        zeroPath = zeroPath,
        coverage = hitCount.toFloat() / (c * r).toFloat()
    )
}

/** 给定 (左 − 右) 的差值与运算符，判断是否成立。与采样用的是同一套判据。 */
private fun holdsFor(operator: String?, f: Double): Boolean = when (operator) {
    "<=" -> f <= 0.0
    ">=" -> f >= 0.0
    "<" -> f < 0.0
    ">" -> f > 0.0
    "==" -> abs(f) <= 1e-6
    "!=" -> abs(f) > 1e-6
    else -> f != 0.0
}

/** 公式两边的当前值（用于面板上显示「左 = 480，右 = 520」这种实数对照） */
data class FormulaReadout(
    val left: Double?,
    val right: Double?,
    val holds: Boolean
)

/**
 * 把区域掩码烘成一张小位图交给画布缩放贴图。
 * 直接画几千个矩形太慢，位图 + 双线性过滤既能省绘制调用，边缘也不会是生硬的锯齿方块。
 */
fun RegionMask.toImageBitmap(argbColor: Int): androidx.compose.ui.graphics.ImageBitmap {
    val pixels = IntArray(cols * rows) { i -> if (cells[i].toInt() != 0) argbColor else 0 }
    val bmp = android.graphics.Bitmap.createBitmap(pixels, cols, rows, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}

fun evaluateReadout(program: FormulaProgram, ctx: EvalContext): FormulaReadout {
    val full = ctx.copy(vars = evalDefinitions(program.definitions, ctx))
    val cmp = splitTopComparison(program.condition)
    return if (cmp != null) {
        val l = evaluateCoordExpression(cmp.left, full)
        val r = evaluateCoordExpression(cmp.right, full)
        FormulaReadout(l, r, holdsFor(cmp.operator, l - r))
    } else {
        val v = evaluateCoordExpression(program.condition, full)
        FormulaReadout(null, null, v != 0.0)
    }
}

/* ---------------- 预设场景 ---------------- */

data class SimPreset(
    val title: String,
    val formula: String,
    val targets: List<SimTarget>,
    val probeName: String,
    val durationSec: Double
)

/**
 * 预设场景：都是"选一个自由变量 → 画它的可行区域"的典型例子。
 * 名字里带「当前动作目标 / 活动路径点目标」是为了跟铁锈战争行动脚本里的说法对得上。
 */
val SIM_PRESETS: List<SimPreset> = listOf(
    SimPreset(
        // 判定「当前动作目标挡没挡在去路径点的路上」：|A-B| + |B-C| <= |A-C|
        // 三角不等式里等号只在 B 落在线段 A-C 上时成立 → 区域就是那条线段本身，
        // 给容差后变成以 A、C 为焦点的极扁椭圆。
        // 默认全部静止：开箱先看到一块确定的区域，想看运动再按播放。
        title = "目标是否挡在路上（B 在线段 A–C 上）",
        formula = "间距(self, 当前动作目标) + 间距(当前动作目标, 活动路径点目标) <= 间距(self, 活动路径点目标)",
        targets = listOf(
            SimTarget("当前动作目标", 350.0, 60.0),
            SimTarget("活动路径点目标", 733.0, 0.0)
        ),
        probeName = "当前动作目标",
        durationSec = 18.0
    ),
    SimPreset(
        title = "等距判定 · 垂直平分线",
        formula = "间距(self, 活动路径点目标) == 间距(当前动作目标, 活动路径点目标)",
        targets = listOf(
            SimTarget("当前动作目标", 400.0, 0.0),
            SimTarget("活动路径点目标", 200.0, 200.0)
        ),
        probeName = "活动路径点目标",
        durationSec = 10.0
    ),
    SimPreset(
        title = "追击窗口 · 圆形范围",
        formula = "间距(self, 活动路径点目标) <= 300",
        targets = listOf(
            SimTarget("当前动作目标", 420.0, 0.0),
            SimTarget("活动路径点目标", 0.0, 0.0)
        ),
        probeName = "活动路径点目标",
        durationSec = 12.0
    ),
    SimPreset(
        title = "折线路径 · 周长上界区域",
        formula = "间距(self, 活动路径点目标) + 间距(活动路径点目标, 当前动作目标) <= 900",
        targets = listOf(
            SimTarget("当前动作目标", 600.0, 0.0),
            SimTarget("活动路径点目标", 300.0, 180.0)
        ),
        probeName = "当前动作目标",
        durationSec = 14.0
    ),
    SimPreset(
        title = "正前方瞄准窄带（带 D 定义）",
        // 脚本原写的两条条件：夹角 ≤ 90°，且「距离 × sin(夹角) ≤ 15」。
        // 后者等于「目标到朝向轴的垂直距离 ≤ 15」，所以区域是一条宽 30、向前无限延伸的长条。
        formula = "D:min(" +
            "选择(((之间方向(self, 当前动作目标)-自身角度+720)%360)>180, " +
            "360-((之间方向(self, 当前动作目标)-自身角度+720)%360), " +
            "(之间方向(self, 当前动作目标)-自身角度+720)%360), " +
            "选择(((自身角度-之间方向(self, 当前动作目标)+720)%360)>180, " +
            "360-((自身角度-之间方向(self, 当前动作目标)+720)%360), " +
            "(自身角度-之间方向(self, 当前动作目标)+720)%360))\n" +
            "$(D)<=90 and 间距(self, 当前动作目标)*sin($(D))<=15",
        targets = listOf(SimTarget("当前动作目标", 300.0, 10.0)),
        probeName = "当前动作目标",
        durationSec = 10.0
    )
)
