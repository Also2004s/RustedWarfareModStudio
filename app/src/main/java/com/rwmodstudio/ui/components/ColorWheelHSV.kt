package com.rwmodstudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** HSV 分量 + 透明度。 */
private data class HsvColor(val h: Float, val s: Float, val v: Float, val a: Int)

private fun fromRgb(colorStr: String): HsvColor {
    val c = try {
        android.graphics.Color.parseColor(colorStr)
    } catch (_: Exception) {
        android.graphics.Color.parseColor("#569CD6")
    }
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c, hsv)
    return HsvColor(hsv[0], hsv[1], hsv[2], android.graphics.Color.alpha(c))
}

/**
 * HSV 取色盘：左侧为 饱和度×明度(SV) 二维色盘，右侧为 色相/明度/透明度 竖滑杆。
 * 拖拽色盘设定饱和度与明度，右侧滑杆可独立量化调节色相(H)、明度(V)、透明度(A)。
 * 输出与 RGB 组件一致：不透明 6 位 #RRGGBB，半透明 8 位 #AARRGGBB。
 */
@Composable
fun ColorWheelHSV(
    initialColor: String,
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: androidx.compose.ui.unit.Dp = 200.dp,
    showHexInput: Boolean = true
) {
    val initial = fromRgb(initialColor)
    var hue by remember { mutableFloatStateOf(initial.h) }      // 0..360
    var sat by remember { mutableFloatStateOf(initial.s) }      // 0..1
    var value by remember { mutableFloatStateOf(initial.v) }    // 0..1
    var alpha by remember { mutableIntStateOf(initial.a) }      // 0..255

    val currentColor = remember(hue, sat, value, alpha) {
        android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))
    }

    fun colorHex(color: Int): String {
        return if (alpha == 255) String.format("#%06X", 0xFFFFFF and color).uppercase()
        else String.format("#%08X", color).uppercase()
    }

    // HEX 输入框聚焦时，文本只由用户输入驱动，避免颜色回写覆盖导致焦点丢失、输入法被收起
    var hexFocused by remember { mutableStateOf(false) }
    var hexText by remember { mutableStateOf(colorHex(currentColor)) }

    // 记录最近一次对外输出的颜色，用于区分「外部切换 initialColor」与「调用方把自身输出回写」。
    // 回写时 initialColor == lastEmitted，必须跳过重置，否则会丢弃正在拖拽的状态、中断手势。
    var lastEmitted by remember { mutableStateOf(colorHex(currentColor)) }

    // 仅当 initialColor 是外部变化（切换分类等）且非自身回写时，才重新装载 HSV 与 hexText
    LaunchedEffect(initialColor) {
        if (initialColor != lastEmitted) {
            val e = fromRgb(initialColor)
            hue = e.h; sat = e.s; value = e.v; alpha = e.a
            val hex = colorHex(android.graphics.Color.HSVToColor(e.a, floatArrayOf(e.h, e.s, e.v)))
            if (!hexFocused) hexText = hex
            lastEmitted = hex
        }
    }

    // 颜色变化时同步 hexText（非聚焦）并通知外部，同时记录 lastEmitted 以识别后续回写
    LaunchedEffect(currentColor) {
        val hex = colorHex(currentColor)
        if (!hexFocused) hexText = hex
        lastEmitted = hex
        onColorChanged(hex)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 当前颜色预览 + HEX 输入
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE0E0E0.toInt()))
                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Box(Modifier.fillMaxSize().background(Color(currentColor)))
            }
            if (showHexInput) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val filtered = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        hexText = if (filtered.startsWith("#")) filtered else "#$filtered"
                        if (hexText.length == 7) {
                            try {
                                val p = android.graphics.Color.parseColor(hexText)
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(p, hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                alpha = 255
                            } catch (_: Exception) {
                            }
                        } else if (hexText.length == 9) {
                            try {
                                val p = android.graphics.Color.parseColor(hexText)
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(p, hsv)
                                alpha = android.graphics.Color.alpha(p)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            } catch (_: Exception) {
                            }
                        }
                    },
                    label = { Text("HEX", fontSize = 10.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f).onFocusChanged { hexFocused = it.isFocused }
                )
            } else {
                Text(colorHex(currentColor), fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val svHeight = wheelSize * 0.62f
            // 左侧 SV 二维色盘（自适应剩余宽度）
            Box(
                Modifier
                    .weight(1f)
                    .height(svHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(hue) {
                        detectDragGestures(
                            onDrag = { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        )
                    }
            ) {
                // 横向 白→当前色相纯色，再乘以 竖向 透明→黑，得到完整 SV 面板
                Canvas(Modifier.fillMaxSize()) {
                    val pureHue = Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 1f, 1f)))
                    drawRect(brush = Brush.horizontalGradient(0f to Color.White, 1f to pureHue))
                    drawRect(
                        brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black),
                        blendMode = BlendMode.Multiply
                    )
                    val cx = sat * size.width
                    val cy = (1f - value) * size.height
                    val radius = 7.dp.toPx()
                    drawCircle(Color.White, radius, Offset(cx, cy))
                    drawCircle(Color.Black, radius, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                }
            }

            // 右侧并排三根竖滑杆：饱和度 S / 明度 V / 透明度 A
            Row(
                Modifier
                    .width(90.dp)
                    .height(svHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 饱和度 S（顶部灰 → 底部当前色相纯色）
                VerticalSliderTrack(
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                    brush = Brush.verticalGradient(
                        0f to Color(android.graphics.Color.HSVToColor(255, floatArrayOf(0f, 0f, value))),
                        1f to Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 1f, value)))
                    ),
                    value = sat.coerceIn(0f, 1f),
                    thumbColor = Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, sat, value)))
                ) { sat = it.coerceIn(0f, 1f) }
                // 明度 V（顶部纯色 → 底部纯黑）
                VerticalSliderTrack(
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                    brush = Brush.verticalGradient(
                        0f to Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 1f, 1f))),
                        1f to Color.Black
                    ),
                    value = value.coerceIn(0f, 1f),
                    thumbColor = Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, sat, value)))
                ) { value = it.coerceIn(0f, 1f) }
                // 透明度 A（顶部不透明 → 底部透明）
                VerticalSliderTrack(
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                    brush = Brush.verticalGradient(
                        0f to Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, sat, value))),
                        1f to Color.White.copy(alpha = 0f)
                    ),
                    value = (alpha / 255f).coerceIn(0f, 1f),
                    thumbColor = Color(currentColor)
                ) { alpha = (it * 255f).coerceIn(0f, 255f).toInt() }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 下方横滑条：色相 H（横向彩虹）
        HorizontalSliderTrack(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            brush = Brush.horizontalGradient(
                0f to Color.Red,
                0.17f to Color.Yellow,
                0.33f to Color.Green,
                0.50f to Color.Cyan,
                0.67f to Color.Blue,
                0.83f to Color.Magenta,
                1f to Color.Red
            ),
            value = (hue / 360f).coerceIn(0f, 1f),
            thumbColor = Color(android.graphics.Color.HSVToColor(255, floatArrayOf(hue, 1f, 1f)))
        ) { hue = (it * 360f).coerceIn(0f, 360f) }

        Spacer(Modifier.height(12.dp))

        HsvPresetGrid(HSV_PRESET_COLORS, currentColor) { preset ->
            hue = preset.h
            sat = preset.s
            value = preset.v
        }
    }
}

/** 竖直滑杆条：轨道为给定 [brush] 渐变，拖拽更新 [value]（0..1）。 */
@Composable
private fun VerticalSliderTrack(
    modifier: Modifier,
    brush: Brush,
    value: Float,
    thumbColor: Color,
    onValueChange: (Float) -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        onValueChange(change.position.y / size.height)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = brush)
            val cy = (value * size.height).coerceIn(0f, size.height)
            val radius = 5.dp.toPx()
            drawCircle(Color.White, radius, Offset(size.width / 2f, cy))
            drawCircle(thumbColor, radius - 1.dp.toPx(), Offset(size.width / 2f, cy))
        }
    }
}

/** 水平滑杆条：轨道为给定 [brush] 渐变，拖拽更新 [value]（0..1）。 */
@Composable
private fun HorizontalSliderTrack(
    modifier: Modifier,
    brush: Brush,
    value: Float,
    thumbColor: Color,
    onValueChange: (Float) -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        onValueChange(change.position.x / size.width)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = brush)
            val cx = (value * size.width).coerceIn(0f, size.width)
            val cy = size.height / 2f
            val radius = 5.dp.toPx()
            drawCircle(Color.White, radius, Offset(cx, cy))
            drawCircle(thumbColor, radius - 1.dp.toPx(), Offset(cx, cy))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HsvPresetGrid(
    colors: List<String>,
    currentColor: Int,
    onSelect: (HsvColor) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        maxItemsInEachRow = 8
    ) {
        colors.forEach { hex ->
            val c = fromRgb(hex)
            val colorInt = android.graphics.Color.HSVToColor(255, floatArrayOf(c.h, c.s, c.v))
            val selected = (0xFFFFFF and colorInt) == (0xFFFFFF and currentColor)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(colorInt))
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(c) }
            )
        }
    }
}

private val HSV_PRESET_COLORS = listOf(
    "#000000", "#1E1E1E", "#2B2B2B", "#4A3728",
    "#F7F7F7", "#F5F5F0", "#F0F4F8", "#FDF6E3",
    "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
    "#00BCD4", "#2196F3", "#673AB7", "#E91E63"
)