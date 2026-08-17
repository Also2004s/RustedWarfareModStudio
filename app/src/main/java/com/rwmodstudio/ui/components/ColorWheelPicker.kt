package com.rwmodstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PRESET_COLORS = listOf(
    "#000000", "#1E1E1E", "#2B2B2B", "#4A3728",
    "#F7F7F7", "#F5F5F0", "#F0F4F8", "#FDF6E3",
    "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
    "#00BCD4", "#2196F3", "#673AB7", "#E91E63"
)

/**
 * 简洁版 RGB 调色盘。
 * 使用 R/G/B 三条滑杆 + 成品色卡，交互更直接。
 */
@Composable
fun ColorWheelPicker(
    initialColor: String,
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: androidx.compose.ui.unit.Dp = 200.dp,
    showHexInput: Boolean = true
) {
    val initial = parseColor(initialColor)
    var red by remember(initialColor) { mutableIntStateOf(android.graphics.Color.red(initial)) }
    var green by remember(initialColor) { mutableIntStateOf(android.graphics.Color.green(initial)) }
    var blue by remember(initialColor) { mutableIntStateOf(android.graphics.Color.blue(initial)) }

    val currentColor = remember(red, green, blue) {
        android.graphics.Color.rgb(red, green, blue)
    }

    var hexText by remember(currentColor) {
        mutableStateOf(String.format("#%06X", 0xFFFFFF and currentColor).uppercase())
    }

    LaunchedEffect(currentColor) {
        onColorChanged(String.format("#%06X", 0xFFFFFF and currentColor))
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
                    .background(Color(currentColor))
                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            )
            if (showHexInput) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val filtered = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        hexText = if (filtered.startsWith("#")) filtered else "#$filtered"
                        if (hexText.length == 7) {
                            try {
                                val parsed = android.graphics.Color.parseColor(hexText)
                                red = android.graphics.Color.red(parsed)
                                green = android.graphics.Color.green(parsed)
                                blue = android.graphics.Color.blue(parsed)
                            } catch (_: Exception) {
                            }
                        }
                    },
                    label = { Text("HEX", fontSize = 10.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = String.format("#%06X", 0xFFFFFF and currentColor).uppercase(),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // RGB 滑杆
        RgbSlider("R", red, Color.Red) { red = it }
        Spacer(Modifier.height(4.dp))
        RgbSlider("G", green, Color.Green) { green = it }
        Spacer(Modifier.height(4.dp))
        RgbSlider("B", blue, Color.Blue) { blue = it }

        Spacer(Modifier.height(10.dp))

        // 紧凑色卡网格
        PresetColorGrid(PRESET_COLORS, currentColor) { applyPreset(it) { r, g, b -> red = r; green = g; blue = b } }
    }
}

@Composable
private fun RgbSlider(
    label: String,
    value: Int,
    activeColor: Color,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = activeColor.copy(alpha = 0.2f)
            )
        )
        Text(value.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(26.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetColorGrid(
    colors: List<String>,
    currentColor: Int,
    onSelect: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        maxItemsInEachRow = 8
    ) {
        colors.forEach { hex ->
            val colorInt = try {
                android.graphics.Color.parseColor(hex)
            } catch (_: Exception) {
                android.graphics.Color.BLACK
            }
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
                    .clickable { onSelect(hex) }
            )
        }
    }
}

private fun parseColor(colorStr: String): Int {
    return try {
        android.graphics.Color.parseColor(colorStr)
    } catch (_: Exception) {
        android.graphics.Color.parseColor("#569CD6")
    }
}

private fun applyPreset(hex: String, setter: (Int, Int, Int) -> Unit) {
    val c = parseColor(hex)
    setter(
        android.graphics.Color.red(c),
        android.graphics.Color.green(c),
        android.graphics.Color.blue(c)
    )
}
