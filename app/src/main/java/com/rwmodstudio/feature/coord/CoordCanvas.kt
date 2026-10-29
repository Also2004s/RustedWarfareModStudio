package com.rwmodstudio.feature.coord

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DEG_TO_RAD = kotlin.math.PI / 180.0
private val DarkCanvasBackground = Color(0xFF1A1A1A)
private val LightCanvasBackground = Color(0xFFF5F5F5)

sealed class DragTarget {
    data object None : DragTarget()
    data object SelfBody : DragTarget()
    data object SelfRotate : DragTarget()
    data class Target(val index: Int) : DragTarget()
    data class TargetRotate(val index: Int) : DragTarget()
    data class Marker(val index: Int) : DragTarget()
}

@Composable
fun CoordCanvas(
    self: CoordSelf,
    targets: List<CoordTarget>,
    markers: List<CoordMarker>,
    scale: Float,
    panX: Float,
    panY: Float,
    selectedMarkerIndex: Int,
    draggedTargetIndex: Int,
    isDark: Boolean,
    // 仿真区域层（可选）：公式成立的区域掩码 + 已烘好的位图，绘在网格之上、单位之下
    regionMask: RegionMask? = null,
    regionImage: androidx.compose.ui.graphics.ImageBitmap? = null,
    // 区域边界等值线（世界坐标线段 x1,y1,x2,y2...）：零宽度区域靠它显示
    regionContour: FloatArray? = null,
    regionAlpha: Float = 0.24f,
    // 标记整体缩放（1=原尺寸）：仿真页用小号，避免几个点把画面塞满
    markerScale: Float = 1f,
    // 是否画单位外圈那两道大圆环（拖转向用的把手）：仿真页不需要，关掉更干净
    showRotateRings: Boolean = true,
    onScalePanChange: (Float, Float, Float) -> Unit,
    onSelfChange: (CoordSelf) -> Unit,
    onTargetChange: (Int, CoordTarget) -> Unit,
    onMarkerDrag: (Int, Double, Double, Boolean) -> Unit,
    onSelectMarker: (Int) -> Unit,
    onDragTargetStart: (Int) -> Unit = {},
    onDragTargetEnd: () -> Unit = {}
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val selfRadius = remember(markerScale) { with(density) { (22.dp * markerScale).toPx() } }
    val rotateRadius = remember(markerScale) { with(density) { (34.dp * markerScale).toPx() } }
    val targetRadius = remember(markerScale) { with(density) { (20.dp * markerScale).toPx() } }
    val targetRotateRadius = remember(targetRadius) { targetRadius * 1.6f }
    val markerRadius = remember(markerScale) { with(density) { (14.dp * markerScale).toPx() } }
    val hitSlop = remember { with(density) { 40.dp.toPx() } }

    // 使用 rememberUpdatedState 避免 pointerInput 因状态变化而重启
    val currentSelf by rememberUpdatedState(self)
    val currentTargets by rememberUpdatedState(targets)
    val currentMarkers by rememberUpdatedState(markers)
    val currentScale by rememberUpdatedState(scale)
    val currentPanX by rememberUpdatedState(panX)
    val currentPanY by rememberUpdatedState(panY)
    val currentOnScalePanChange by rememberUpdatedState(onScalePanChange)
    val currentOnSelfChange by rememberUpdatedState(onSelfChange)
    val currentOnTargetChange by rememberUpdatedState(onTargetChange)
    val currentOnMarkerDrag by rememberUpdatedState(onMarkerDrag)
    val currentOnSelectMarker by rememberUpdatedState(onSelectMarker)
    val currentOnDragTargetStart by rememberUpdatedState(onDragTargetStart)
    val currentOnDragTargetEnd by rememberUpdatedState(onDragTargetEnd)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) DarkCanvasBackground else LightCanvasBackground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        val start = down.position
                        val centerX = size.width / 2f + currentPanX
                        val centerY = size.height / 2f + currentPanY

                        fun toWorld(p: Offset): Offset {
                            return Offset(
                                (p.x - centerX) / currentScale,
                                (p.y - centerY) / currentScale
                            )
                        }

                        val target = hitTest(
                            start, currentSelf, currentTargets, currentMarkers,
                            centerX, centerY, currentScale,
                            selfRadius, rotateRadius, targetRadius, targetRotateRadius, markerRadius, hitSlop
                        )

                        when (target) {
                            is DragTarget.Marker -> {
                                currentOnSelectMarker(target.index)
                                val pointerId = down.id
                                val startScreen = start
                                val startWorld = toWorld(start)
                                val dragThreshold = with(density) { 6.dp.toPx() }
                                var lastWorld = startWorld
                                var hasMoved = false
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == pointerId } ?: break
                                    val currentScreen = change.position
                                    val currentWorld = toWorld(currentScreen)
                                    if (!hasMoved) {
                                        if ((currentScreen - startScreen).getDistance() > dragThreshold) {
                                            hasMoved = true
                                        } else {
                                            change.consume()
                                            if (!change.pressed) break
                                            continue
                                        }
                                    }
                                    lastWorld = currentWorld
                                    currentOnMarkerDrag(target.index, currentWorld.x.toDouble(), currentWorld.y.toDouble(), false)
                                    change.consume()
                                    if (!change.pressed) break
                                } while (true)
                                if (hasMoved) {
                                    currentOnMarkerDrag(target.index, lastWorld.x.toDouble(), lastWorld.y.toDouble(), true)
                                }
                            }

                            is DragTarget.SelfBody -> {
                                val pointerId = down.id
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == pointerId } ?: break
                                    val world = toWorld(change.position)
                                    currentOnSelfChange(currentSelf.copy(x = world.x.toDouble(), y = world.y.toDouble()))
                                    change.consume()
                                    if (!change.pressed) break
                                } while (true)
                            }

                            is DragTarget.SelfRotate -> {
                                val pointerId = down.id
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == pointerId } ?: break
                                    val world = toWorld(change.position)
                                    val dx = world.x - currentSelf.x
                                    val dy = world.y - currentSelf.y
                                    val deg = Math.toDegrees(kotlin.math.atan2(dy, dx))
                                    currentOnSelfChange(currentSelf.copy(dir = deg))
                                    change.consume()
                                    if (!change.pressed) break
                                } while (true)
                            }

                            is DragTarget.Target -> {
                                val t = currentTargets[target.index]
                                currentOnDragTargetStart(target.index)
                                val pointerId = down.id
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == pointerId } ?: break
                                    val world = toWorld(change.position)
                                    currentOnTargetChange(
                                        target.index,
                                        t.copy(x = world.x.toDouble(), y = world.y.toDouble())
                                    )
                                    change.consume()
                                    if (!change.pressed) break
                                } while (true)
                                currentOnDragTargetEnd()
                            }

                            is DragTarget.TargetRotate -> {
                                val t = currentTargets[target.index]
                                currentOnDragTargetStart(target.index)
                                val pointerId = down.id
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.find { it.id == pointerId } ?: break
                                    val world = toWorld(change.position)
                                    val dx = world.x - t.x
                                    val dy = world.y - t.y
                                    val deg = Math.toDegrees(kotlin.math.atan2(dy, dx))
                                    currentOnTargetChange(target.index, t.copy(dir = deg))
                                    change.consume()
                                    if (!change.pressed) break
                                } while (true)
                                currentOnDragTargetEnd()
                            }

                            DragTarget.None -> {
                                val pointerId = down.id
                                do {
                                    val event = awaitPointerEvent()
                                    val changes = event.changes
                                    if (changes.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        val centroid = event.calculateCentroid()
                                        val pan = event.calculatePan()
                                        val newScale = (currentScale * zoom).coerceIn(0.1f, 5f)
                                        val scaleRatio = newScale / currentScale
                                        val newPanX = currentPanX + pan.x + (centroid.x - currentPanX - size.width / 2f) * (1 - scaleRatio)
                                        val newPanY = currentPanY + pan.y + (centroid.y - currentPanY - size.height / 2f) * (1 - scaleRatio)
                                        currentOnScalePanChange(newScale, newPanX, newPanY)
                                        changes.forEach { it.consume() }
                                    } else if (changes.size == 1) {
                                        val change = changes.first()
                                        val delta = change.positionChange()
                                        currentOnScalePanChange(currentScale, currentPanX + delta.x, currentPanY + delta.y)
                                        change.consume()
                                    }
                                    if (event.changes.none { it.id == pointerId && it.pressed }) break
                                } while (true)
                            }
                        }
                    }
                }
            }
    ) {
        val centerX = size.width / 2f + panX
        val centerY = size.height / 2f + panY
        val labelStyle = TextStyle(color = if (isDark) Color.White else Color.Black, fontSize = 10.sp)
        val hintStyle = TextStyle(color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF555555), fontSize = 10.sp)

        drawGrid(centerX, centerY, scale, isDark, textMeasurer)
        drawAxes(centerX, centerY, isDark)

        // 公式区域层：贴在采样网格对应的世界矩形上，双线性缩放让边界柔和不发毛
        if (regionMask != null && regionImage != null) {
            val rx = centerX + (regionMask.minX * scale).toFloat()
            val ry = centerY + (regionMask.minY * scale).toFloat()
            val rw = (regionMask.cols * regionMask.stepX * scale).toFloat()
            val rh = (regionMask.rows * regionMask.stepY * scale).toFloat()
            drawImage(
                image = regionImage,
                dstOffset = androidx.compose.ui.unit.IntOffset(rx.roundToInt(), ry.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(
                    rw.roundToInt().coerceAtLeast(1),
                    rh.roundToInt().coerceAtLeast(1)
                ),
                alpha = regionAlpha,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
            )
        }

        // 区域边界等值线：区域退化成一条线（零宽度）时，这里画出来的就是区域本身。
        // 线宽按屏幕像素固定，与采样密度无关，所以再细也能看见。
        if (regionContour != null && regionContour.size >= 4) {
            val contourColor = if (isDark) Color(0xFF6FB6FF) else Color(0xFF2D7DD2)
            var i = 0
            while (i + 3 < regionContour.size) {
                drawLine(
                    color = contourColor.copy(alpha = 0.95f),
                    start = Offset(
                        centerX + regionContour[i] * scale,
                        centerY + regionContour[i + 1] * scale
                    ),
                    end = Offset(
                        centerX + regionContour[i + 2] * scale,
                        centerY + regionContour[i + 3] * scale
                    ),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                i += 4
            }
        }

        // Self 本地坐标轴
        drawLocalAxes(selfScreen = Offset(centerX + self.x.toFloat() * scale, centerY + self.y.toFloat() * scale), self.dir, scale, isDark)

        // Self
        val selfScreen = Offset(centerX + self.x.toFloat() * scale, centerY + self.y.toFloat() * scale)
        drawArrow(
            x = selfScreen.x,
            y = selfScreen.y,
            dir = self.dir,
            color = Color(0xFF4A90FF),
            length = selfRadius * 1.8f,
            bodyRadius = selfRadius * 0.7f
        )
        if (showRotateRings) {
            drawCircle(
                color = Color(0xFF4A90FF).copy(alpha = 0.2f),
                radius = rotateRadius,
                center = selfScreen,
                style = Stroke(width = 2f)
            )
        }
        drawLabel(textMeasurer, "自身", selfScreen.x, selfScreen.y + selfRadius + 12f, labelStyle)

        // Targets
        targets.forEachIndexed { index, target ->
            val targetScreen = Offset(centerX + target.x.toFloat() * scale, centerY + target.y.toFloat() * scale)
            drawArrow(
                x = targetScreen.x,
                y = targetScreen.y,
                dir = target.dir,
                color = Color(0xFFFF4D4D),
                length = targetRadius * 1.6f,
                bodyRadius = targetRadius * 0.7f
            )
            drawLabel(textMeasurer, target.name, targetScreen.x, targetScreen.y + targetRadius + 12f, labelStyle)

            // 目标旋转环
            if (showRotateRings) {
                drawCircle(
                    color = Color(0xFFFF4D4D).copy(alpha = 0.2f),
                    radius = targetRotateRadius,
                    center = targetScreen,
                    style = Stroke(width = 2f)
                )
            }

            // 拖拽目标时显示与 Self 的距离
            if (index == draggedTargetIndex) {
                drawLine(
                    color = Color(0xFFFFD700).copy(alpha = 0.7f),
                    start = selfScreen,
                    end = targetScreen,
                    strokeWidth = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                val dist = kotlin.math.hypot(target.x - self.x, target.y - self.y)
                val midX = (selfScreen.x + targetScreen.x) / 2f
                val midY = (selfScreen.y + targetScreen.y) / 2f
                drawLabel(textMeasurer, "${formatCoordNumber(dist)}", midX, midY - 12f, TextStyle(color = Color(0xFFFFD700), fontSize = 11.sp))
            }
        }

        // Markers
        markers.forEachIndexed { index, marker ->
            val baseScreen = Offset(
                centerX + marker.baseX.toFloat() * scale,
                centerY + marker.baseY.toFloat() * scale
            )
            val finalScreen = Offset(
                centerX + marker.finalX.toFloat() * scale,
                centerY + marker.finalY.toFloat() * scale
            )
            drawLine(
                color = Color(0xFFFF00FF).copy(alpha = 0.6f),
                start = baseScreen,
                end = finalScreen,
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
            drawDiamond(
                center = finalScreen,
                radius = markerRadius,
                color = if (index == selectedMarkerIndex) Color(0xFFFF00FF) else Color(0xFFFF00FF).copy(alpha = 0.8f),
                stroke = index == selectedMarkerIndex
            )
            drawLabel(
                textMeasurer,
                "标记${index + 1}",
                finalScreen.x,
                finalScreen.y + markerRadius + 10f,
                labelStyle
            )
        }
    }
}

private fun DrawScope.drawLocalAxes(selfScreen: Offset, dir: Double, scale: Float, isDark: Boolean) {
    val axisLen = 40f * scale.coerceAtLeast(0.3f)
    val rad = dir * DEG_TO_RAD
    val xColor = Color(0xFFFF6B6B)
    val yColor = Color(0xFF6BCB77)
    drawLine(
        xColor,
        start = selfScreen,
        end = Offset(selfScreen.x + (axisLen * cos(rad)).toFloat(), selfScreen.y + (axisLen * sin(rad)).toFloat()),
        strokeWidth = 2f
    )
    drawLine(
        yColor,
        start = selfScreen,
        end = Offset(
            selfScreen.x + (axisLen * cos(rad + kotlin.math.PI / 2)).toFloat(),
            selfScreen.y + (axisLen * sin(rad + kotlin.math.PI / 2)).toFloat()
        ),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    alignStart: Boolean = false
) {
    val measured = textMeasurer.measure(text, style)
    val startX = if (alignStart) x else x - measured.size.width / 2f
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(startX, y - measured.size.height)
    )
}

private fun DrawScope.drawGrid(
    centerX: Float,
    centerY: Float,
    scale: Float,
    isDark: Boolean,
    textMeasurer: TextMeasurer
) {
    val gridColor = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)
    val stepWorld = 100.0
    val width = size.width
    val height = size.height

    val startX = ((0f - centerX) / scale / stepWorld).toInt() - 1
    val endX = ((width - centerX) / scale / stepWorld).toInt() + 1
    for (i in startX..endX) {
        val x = centerX + (i * stepWorld * scale).toFloat()
        drawLine(gridColor, start = Offset(x, 0f), end = Offset(x, height), strokeWidth = 1f)
    }
    val startY = ((0f - centerY) / scale / stepWorld).toInt() - 1
    val endY = ((height - centerY) / scale / stepWorld).toInt() + 1
    for (i in startY..endY) {
        val y = centerY + (i * stepWorld * scale).toFloat()
        drawLine(gridColor, start = Offset(0f, y), end = Offset(width, y), strokeWidth = 1f)
    }

    // 网格刻度数字：原来画布只有网格线没有数值，想看某点坐标只能数格子。
    // x 值贴屏幕底部、y 值贴屏幕左侧，平移到哪都能读到；格子太密时自动跳标（每 2/5/10 格标一次）。
    val pxPerStep = stepWorld * scale
    val labelEvery = when {
        pxPerStep >= 110 -> 1
        pxPerStep >= 55 -> 2
        pxPerStep >= 28 -> 5
        else -> 10
    }
    val tickStyle = TextStyle(
        color = if (isDark) Color(0x99FFFFFF) else Color(0x99000000),
        fontSize = 9.sp
    )
    for (i in startX..endX) {
        if (i % labelEvery != 0) continue
        val x = centerX + (i * stepWorld * scale).toFloat()
        if (x < 20f || x > width - 20f) continue
        val measured = textMeasurer.measure((i * stepWorld).toInt().toString(), tickStyle)
        drawText(measured, topLeft = Offset(x + 2f, height - measured.size.height - 2f))
    }
    for (j in startY..endY) {
        if (j % labelEvery != 0) continue
        val y = centerY + (j * stepWorld * scale).toFloat()
        if (y < 12f || y > height - 12f) continue
        val measured = textMeasurer.measure((j * stepWorld).toInt().toString(), tickStyle)
        drawText(measured, topLeft = Offset(3f, y + 1f))
    }
}

private fun DrawScope.drawAxes(centerX: Float, centerY: Float, isDark: Boolean) {
    val axisColor = if (isDark) Color(0x66FFFFFF) else Color(0x66000000)
    drawLine(axisColor, start = Offset(centerX, 0f), end = Offset(centerX, size.height), strokeWidth = 2f)
    drawLine(axisColor, start = Offset(0f, centerY), end = Offset(size.width, centerY), strokeWidth = 2f)
}

private fun DrawScope.drawArrow(
    x: Float,
    y: Float,
    dir: Double,
    color: Color,
    length: Float,
    bodyRadius: Float
) {
    val rad = dir * DEG_TO_RAD
    val headX = x + (length * cos(rad)).toFloat()
    val headY = y + (length * sin(rad)).toFloat()
    drawLine(color, start = Offset(x, y), end = Offset(headX, headY), strokeWidth = 4f)
    drawCircle(color, radius = bodyRadius, center = Offset(x, y))

    // arrow head
    val headLen = length * 0.3f
    val leftRad = rad + 2.5
    val rightRad = rad - 2.5
    val path = Path().apply {
        moveTo(headX, headY)
        lineTo(
            headX + (headLen * cos(leftRad)).toFloat(),
            headY + (headLen * sin(leftRad)).toFloat()
        )
        lineTo(
            headX + (headLen * cos(rightRad)).toFloat(),
            headY + (headLen * sin(rightRad)).toFloat()
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawDiamond(center: Offset, radius: Float, color: Color, stroke: Boolean) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(path, color)
    if (stroke) {
        drawPath(path, Color.White, style = Stroke(width = 2f))
    }
}

private fun hitTest(
    point: Offset,
    self: CoordSelf,
    targets: List<CoordTarget>,
    markers: List<CoordMarker>,
    centerX: Float,
    centerY: Float,
    scale: Float,
    selfRadius: Float,
    rotateRadius: Float,
    targetRadius: Float,
    targetRotateRadius: Float,
    markerRadius: Float,
    hitSlop: Float
): DragTarget {
    // Markers first (smaller, on top)
    markers.forEachIndexed { index, marker ->
        val sx = centerX + marker.finalX.toFloat() * scale
        val sy = centerY + marker.finalY.toFloat() * scale
        if ((point - Offset(sx, sy)).getDistance() < markerRadius + hitSlop * 0.5f) {
            return DragTarget.Marker(index)
        }
    }
    // Self
    val selfScreen = Offset(centerX + self.x.toFloat() * scale, centerY + self.y.toFloat() * scale)
    val dSelf = (point - selfScreen).getDistance()
    if (dSelf < rotateRadius && dSelf > selfRadius) {
        return DragTarget.SelfRotate
    }
    if (dSelf < selfRadius + hitSlop * 0.5f) {
        return DragTarget.SelfBody
    }
    // Targets
    targets.forEachIndexed { index, target ->
        val tx = centerX + target.x.toFloat() * scale
        val ty = centerY + target.y.toFloat() * scale
        val dTarget = (point - Offset(tx, ty)).getDistance()
        if (dTarget < targetRotateRadius && dTarget > targetRadius) {
            return DragTarget.TargetRotate(index)
        }
        if (dTarget < targetRadius + hitSlop * 0.5f) {
            return DragTarget.Target(index)
        }
    }
    return DragTarget.None
}
