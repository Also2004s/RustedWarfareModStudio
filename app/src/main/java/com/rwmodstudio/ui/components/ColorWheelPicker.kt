package com.rwmodstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorWheelPicker(
    initialColor: String,
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: androidx.compose.ui.unit.Dp = 200.dp,
    showHexInput: Boolean = true
) {
    val initial = parseColor(initialColor)
    var red by remember { mutableIntStateOf(android.graphics.Color.red(initial)) }
    var green by remember { mutableIntStateOf(android.graphics.Color.green(initial)) }
    var blue by remember { mutableIntStateOf(android.graphics.Color.blue(initial)) }
    // 透明度 0..255，默认不透明
    var alpha by remember { mutableIntStateOf(android.graphics.Color.alpha(initial)) }

    val currentColor = remember(red, green, blue, alpha) {
        android.graphics.Color.argb(alpha, red, green, blue)
    }

    // 不透明时输出 6 位（兼容旧格式），半透明输出 8 位 #AARRGGBB（高 8 位为透明度）
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

    // 仅当 initialColor 是外部变化（切换分类等）且非自身回写时，才重新装载 RGBA 与 hexText。
    // 注意：此处不设 hexFocused 门禁——initialColor 只会在「分类切换/选用预设」这类外部重置时变化，
    // 且已有 lastEmitted 防回写，不会打断用户敲字；聚焦中输入保护由下方 currentColor 效应的门禁承担。
    LaunchedEffect(initialColor) {
        if (initialColor != lastEmitted) {
            val e = parseColor(initialColor)
            red = android.graphics.Color.red(e)
            green = android.graphics.Color.green(e)
            blue = android.graphics.Color.blue(e)
            alpha = android.graphics.Color.alpha(e)
            hexText = colorHex(e)
            lastEmitted = colorHex(e)
        }
    }

    // 颜色变化时同步 hexText（非聚焦）并通知外部，同时记录 lastEmitted 以识别后续回写。
    // 这里的 hexFocused 门禁必须保留：用户在 HEX 输入框敲字时，颜色写回不能覆盖正在编辑的文本。
    LaunchedEffect(currentColor) {
        val hex = colorHex(currentColor)
        if (!hexFocused) hexText = hex
        lastEmitted = hex
        onColorChanged(hex)
    }

    // 点击取色盘非输入区域（色盘/滑杆/预设色块之外的空白）时清除 HEX 输入框焦点，输入法随之收起。
    // 滑杆/色盘的拖拽手势由各自子组件消费，不会触达这里。
    val focusManager = LocalFocusManager.current

    // 输入法收起（无论按完成键、系统返回、下滑还是输入法自带收起按钮等任何方式）后不再保持聚焦。
    // 监听 dialog 窗口 root view 的 window insets：IME 状态变化即时回调，比屏幕高度几何法可靠
    //（对话框非全屏，几何对比屏幕高度对 dialog 恒失效）。
    val imeView = LocalView.current
    var imeVisible by remember { mutableStateOf(false) }
    var lastImeVisible by remember { mutableStateOf(false) }
    DisposableEffect(imeView) {
        val rootView = imeView.rootView
        val listener = OnApplyWindowInsetsListener { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView, listener)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
        }
    }
    // 仅在 IME 由「展开→收起」时清除焦点（首次组合不误清）
    LaunchedEffect(imeVisible) {
        if (!imeVisible && lastImeVisible) focusManager.clearFocus()
        lastImeVisible = imeVisible
    }

    Column(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { focusManager.clearFocus() }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    // 先铺浅灰底，便于观察透明度叠加效果
                    .background(Color(0xFFE0E0E0.toInt()))
                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(currentColor))
                )
            }
            if (showHexInput) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val filtered = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        hexText = if (filtered.startsWith("#")) filtered else "#$filtered"
                        if (hexText.length == 7) {
                            // 6 位 #RRGGBB：不透明，透明度复位为 255
                            try {
                                val parsed = android.graphics.Color.parseColor(hexText)
                                red = android.graphics.Color.red(parsed)
                                green = android.graphics.Color.green(parsed)
                                blue = android.graphics.Color.blue(parsed)
                                alpha = 255
                            } catch (_: Exception) {
                            }
                        } else if (hexText.length == 9) {
                            // 8 位 #AARRGGBB：含透明度
                            try {
                                val parsed = android.graphics.Color.parseColor(hexText)
                                alpha = android.graphics.Color.alpha(parsed)
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
                    // 按输入法「完成/换行」键收键盘时同时清除焦点，光标不再停留在输入框
                    keyboardActions = KeyboardActions(onAny = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f).onFocusChanged { hexFocused = it.isFocused }
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

        Spacer(Modifier.height(6.dp))
        // 透明度滑杆：A 高=不透明，轨道用当前色的不透明版本，便于直观对应
        RgbSlider("A", alpha, Color(red, green, blue, 255)) { alpha = it }

        Spacer(Modifier.height(10.dp))

        // 紧凑色卡网格
        PresetColorGrid(PRESET_COLORS, currentColor) { hex ->
            // 点击色块会修改颜色：先清除 HEX 输入框焦点（并同步 hexFocused=false，
            // 避免 currentColor 效应因「聚焦中不覆盖」门禁而挡住输入框色值刷新）
            focusManager.clearFocus()
            hexFocused = false
            applyPreset(hex) { r, g, b -> red = r; green = g; blue = b }
        }
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
