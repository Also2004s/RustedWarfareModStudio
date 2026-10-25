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

/* ---------------- 区域采样 ---------------- */

/**
 * 公式区域掩码。cells 按行优先存放（1 = 公式成立）。
 * minX/minY/stepX/stepY 一起把掩码映射回世界坐标，画布据此贴图。
 */
data class RegionMask(
    val cols: Int,
    val rows: Int,
    val minX: Double,
    val minY: Double,
    val stepX: Double,
    val stepY: Double,
    val cells: ByteArray,
    /** 命中格子占比，供 UI 显示"区域覆盖多少"、也用于判断公式是不是恒真/恒假 */
    val coverage: Float
)

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
 * @param ast 已解析的公式
 * @param base 基准求值上下文（自身/其它目标/时间/资源）
 * @param probeName 自由变量名：该目标的位置由采样点决定
 * @param tolerance 边界容差（世界单位）。<= / >= 放宽这么多，== 则是等值带宽度
 */
fun sampleRegion(
    ast: AstNode,
    base: EvalContext,
    probeName: String,
    cols: Int,
    rows: Int,
    minX: Double,
    minY: Double,
    stepX: Double,
    stepY: Double,
    tolerance: Double
): RegionMask {
    val c = cols.coerceIn(1, 512)
    val r = rows.coerceIn(1, 512)
    val cells = ByteArray(c * r)
    val cmp = splitTopComparison(ast)
    var hitCount = 0
    for (ry in 0 until r) {
        val wy = minY + (ry + 0.5) * stepY
        val rowOffset = ry * c
        for (rx in 0 until c) {
            val wx = minX + (rx + 0.5) * stepX
            val ctx = base.copy(probeName = probeName, probeX = wx, probeY = wy)
            val hit = if (cmp != null) {
                evalComparison(cmp, ctx, tolerance)
            } else {
                evaluateCoordExpression(ast, ctx) != 0.0
            }
            if (hit) {
                cells[rowOffset + rx] = 1
                hitCount++
            }
        }
    }
    return RegionMask(
        cols = c, rows = r,
        minX = minX, minY = minY, stepX = stepX, stepY = stepY,
        cells = cells,
        coverage = hitCount.toFloat() / (c * r).toFloat()
    )
}

private fun evalComparison(node: BinaryOp, ctx: EvalContext, tol: Double): Boolean {
    val left = evaluateCoordExpression(node.left, ctx)
    val right = evaluateCoordExpression(node.right, ctx)
    return when (node.operator) {
        "<=" -> left <= right + tol
        ">=" -> left >= right - tol
        "<" -> left < right + tol
        ">" -> left > right - tol
        "==" -> abs(left - right) <= tol
        "!=" -> abs(left - right) > tol
        else -> false
    }
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

fun evaluateReadout(ast: AstNode, ctx: EvalContext, tolerance: Double): FormulaReadout {
    val cmp = splitTopComparison(ast)
    return if (cmp != null) {
        val l = evaluateCoordExpression(cmp.left, ctx)
        val r = evaluateCoordExpression(cmp.right, ctx)
        FormulaReadout(l, r, evalComparison(cmp, ctx, tolerance))
    } else {
        val v = evaluateCoordExpression(ast, ctx)
        FormulaReadout(null, null, v != 0.0)
    }
}

/* ---------------- 预设场景 ---------------- */

data class SimPreset(
    val title: String,
    val formula: String,
    val targets: List<SimTarget>,
    val probeName: String,
    val tolerance: Double,
    val durationSec: Double
)

/**
 * 预设场景：都是"选一个自由变量 → 画它的可行区域"的典型例子。
 * 名字里带「当前动作目标 / 活动路径点目标」是为了跟铁锈战争行动脚本里的说法对得上。
 */
val SIM_PRESETS: List<SimPreset> = listOf(
    SimPreset(
        title = "三角不等式 · 绕路是否成立",
        formula = "间距(self, 当前动作目标) + 间距(当前动作目标, 活动路径点目标) <= 间距(self, 活动路径点目标)",
        targets = listOf(
            // 当前动作目标绕自身转圈：可行区域（一条射线）会跟着一起扫过
            SimTarget("当前动作目标", 250.0, 0.0, motion = SimMotion.Circle(0.0, 0.0, 250.0, 8.0)),
            SimTarget("活动路径点目标", 700.0, 220.0)
        ),
        probeName = "活动路径点目标",
        tolerance = 4.0,
        durationSec = 16.0
    ),
    SimPreset(
        title = "等距判定 · 垂直平分线",
        formula = "间距(self, 活动路径点目标) == 间距(当前动作目标, 活动路径点目标)",
        targets = listOf(
            SimTarget("当前动作目标", 400.0, 0.0),
            SimTarget("活动路径点目标", 200.0, 200.0)
        ),
        probeName = "活动路径点目标",
        tolerance = 6.0,
        durationSec = 10.0
    ),
    SimPreset(
        title = "追击窗口 · 圆形范围",
        formula = "间距(self, 活动路径点目标) <= 300",
        targets = listOf(
            SimTarget("当前动作目标", 420.0, 0.0, motion = SimMotion.Circle(0.0, 0.0, 420.0, 12.0)),
            SimTarget("活动路径点目标", 0.0, 0.0)
        ),
        probeName = "活动路径点目标",
        tolerance = 3.0,
        durationSec = 12.0
    ),
    SimPreset(
        title = "折线路径 · 周长上界区域",
        formula = "间距(self, 活动路径点目标) + 间距(活动路径点目标, 当前动作目标) <= 900",
        targets = listOf(
            SimTarget("当前动作目标", 600.0, 0.0),
            SimTarget(
                "活动路径点目标", 0.0, 0.0,
                motion = SimMotion.Path(
                    listOf(-200.0 to 200.0, 200.0 to -200.0, 600.0 to 300.0, 900.0 to -100.0),
                    totalSec = 14.0
                )
            )
        ),
        probeName = "当前动作目标",
        tolerance = 4.0,
        durationSec = 14.0
    )
)
