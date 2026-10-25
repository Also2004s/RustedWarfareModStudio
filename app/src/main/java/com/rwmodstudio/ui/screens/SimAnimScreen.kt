package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.feature.coord.*
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary
import com.rwmodstudio.ui.theme.RustedSurface
import kotlin.math.roundToInt

/**
 * 仿真动画：把「公式」画成「区域」，并让参数随时间动起来。
 *
 * 用法：选一个预设场景（或自己写公式）→ 选一个「自由变量」→ 画布上会填出
 * 「该变量放在这里时公式成立」的全部位置。配合时间轴播放，能看到区域随目标运动而变化。
 *
 * 典型例子（预设一，三角不等式）：
 * `间距(self,当前动作目标) + 间距(当前动作目标,活动路径点目标) <= 间距(self,活动路径点目标)`
 * 右边是直连距离，左边是绕路距离。绕路恒不短于直连，所以成立的只有等号那条情况——
 * 也就是「活动路径点目标」必须落在 self→当前动作目标 的延长线上，画出来是一条射线。
 */
@Composable
fun SimAnimScreen() {
    val isDark = ThemeState.isDark
    val bgColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)

    val initial = SIM_PRESETS.first()
    var presetIndex by remember { mutableIntStateOf(0) }
    var formula by remember { mutableStateOf(initial.formula) }
    var targets by remember { mutableStateOf(initial.targets) }
    var probeName by remember { mutableStateOf(initial.probeName) }
    var tolerance by remember { mutableDoubleStateOf(initial.tolerance) }
    var self by remember { mutableStateOf(CoordSelf()) }

    var simTime by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showRegion by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(0.45f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 动画推进：只在 playing 时驱动；speed 变化时重启协程以应用新速度
    LaunchedEffect(playing, speed) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) simTime += (now - last) / 1_000_000_000.0 * speed
                last = now
            }
        }
    }

    // 当前时刻的动画位置
    val animated = remember(targets, simTime) { targets.animatedAt(simTime) }
    val animatedUnits = remember(animated) {
        animated.associate { it.name to CoordUnit(it.baseX, it.baseY, it.dir) }
    }
    val baseCtx = EvalContext(
        self = CoordUnit(self.x, self.y, self.dir),
        targets = animatedUnits,
        simTime = simTime
    )
    val ast = remember(formula) { parseCoordExpression(formula) }

    // 区域重算节流：动画 60fps，但逐格求值不必每帧跑；约每 80ms 重算一次
    val regionTick = (simTime / 0.08).roundToInt()
    val centerX = canvasSize.width / 2f + panX
    val centerY = canvasSize.height / 2f + panY
    // 采样格数上限（约 9000 格）：与画布像素无关，避免平板/大屏上逐格求值拖慢动画。
    // 位图交给画布双线性放大，所以格子少一点不会显得粗糙。
    val aspect = if (canvasSize.width > 0) canvasSize.height.toFloat() / canvasSize.width else 1f
    val cols = kotlin.math.sqrt(9000f / aspect.coerceAtLeast(0.25f)).roundToInt().coerceIn(32, 200)
    val rows = (9000 / cols).coerceIn(24, 200)
    val minWorldX = (-centerX / scale).toDouble()
    val minWorldY = (-centerY / scale).toDouble()
    val maxWorldX = ((canvasSize.width - centerX) / scale).toDouble()
    val maxWorldY = ((canvasSize.height - centerY) / scale).toDouble()

    val mask = remember(regionTick, ast, probeName, tolerance, showRegion, canvasSize, scale, panX, panY) {
        if (!showRegion || ast == null || canvasSize.width <= 0) {
            null
        } else {
            sampleRegion(
                ast = ast,
                base = baseCtx,
                probeName = probeName,
                cols = cols,
                rows = rows,
                minX = minWorldX,
                minY = minWorldY,
                stepX = (maxWorldX - minWorldX) / cols,
                stepY = (maxWorldY - minWorldY) / rows,
                tolerance = tolerance
            )
        }
    }
    val regionColor = if (isDark) Color(0xFF4A9EFF) else Color(0xFF2D7DD2)
    val regionImage = remember(mask, regionColor) { mask?.toImageBitmap(regionColor.toArgb()) }

    val readout = remember(ast, animatedUnits, simTime, tolerance) {
        ast?.let { evaluateReadout(it, baseCtx, tolerance) }
    }

    Column(Modifier.fillMaxSize().background(bgColor)) {
        // ---------------- 画布 ----------------
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it }) {
            CoordCanvas(
                self = self,
                targets = animated.map { CoordTarget(it.name, it.baseX, it.baseY, it.dir) },
                markers = emptyList(),
                scale = scale,
                panX = panX,
                panY = panY,
                selectedMarkerIndex = -1,
                draggedTargetIndex = -1,
                isDark = isDark,
                regionMask = mask,
                regionImage = regionImage,
                regionAlpha = 0.26f,
                onScalePanChange = { s, x, y -> scale = s; panX = x; panY = y },
                onSelfChange = { self = it },
                onTargetChange = { index, t ->
                    // 手动拖动后该目标改为静止：用户的拖动优先于预设运动
                    targets = targets.toMutableList().also { list ->
                        if (index in list.indices) {
                            list[index] = list[index].copy(baseX = t.x, baseY = t.y, motion = SimMotion.Still)
                        }
                    }
                },
                onMarkerDrag = { _, _, _, _ -> },
                onSelectMarker = { }
            )

            // 左上角图例：说明蓝色区域代表什么
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        (if (isDark) Color(0xCC000000) else Color(0xCCFFFFFF)),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(regionColor, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showRegion) "区域 = 公式成立的位置" else "区域已关闭",
                        fontSize = 10.sp,
                        color = RustedOnBackground
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "自由变量：$probeName（拖动可改）",
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.55f)
                )
            }

            // 右下角实时判定
            if (readout != null) {
                val okColor = if (readout.holds) RustedSecondary else RustedError
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            (if (isDark) Color(0xCC000000) else Color(0xCCFFFFFF)),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        if (readout.holds) "成立" else "不成立",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = okColor
                    )
                    if (readout.left != null && readout.right != null) {
                        Text(
                            "左 ${fmt(readout.left)}   右 ${fmt(readout.right)}",
                            fontSize = 10.sp,
                            color = RustedOnBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // ---------------- 控制面板 ----------------
        Column(
            Modifier
                .fillMaxWidth()
                .background(RustedSurface)
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // 时间轴
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playing = !playing }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "暂停" else "播放",
                        tint = RustedPrimary, modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    "${"%.1f".format(simTime)} s",
                    fontSize = 13.sp,
                    color = RustedOnBackground,
                    fontFamily = AppCodeFontFamily
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    speed = when (speed) {
                        0.5f -> 1f
                        1f -> 2f
                        else -> 0.5f
                    }
                }) {
                    Icon(Icons.Default.Speed, null, Modifier.size(15.dp), tint = RustedPrimary)
                    Spacer(Modifier.width(3.dp))
                    Text("${speed}x", fontSize = 12.sp, color = RustedPrimary)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showRegion = !showRegion }) {
                    Text(if (showRegion) "隐藏区域" else "显示区域", fontSize = 12.sp, color = RustedPrimary)
                }
                IconButton(onClick = { simTime = 0.0 }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "重置时间", tint = RustedOnBackground.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }

            // 场景选择
            var showPresetMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showPresetMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("场景：${SIM_PRESETS[presetIndex].title}", fontSize = 12.sp, color = RustedPrimary)
                    Icon(Icons.Default.ArrowDropDown, null, tint = RustedPrimary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                    SIM_PRESETS.forEachIndexed { index, preset ->
                        DropdownMenuItem(
                            text = { Text(preset.title, fontSize = 13.sp) },
                            onClick = {
                                showPresetMenu = false
                                presetIndex = index
                                formula = preset.formula
                                targets = preset.targets
                                probeName = preset.probeName
                                tolerance = preset.tolerance
                                simTime = 0.0
                                playing = true
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 公式输入
            OutlinedTextField(
                value = formula,
                onValueChange = { formula = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontFamily = AppCodeFontFamily,
                    color = RustedOnBackground
                ),
                label = { Text("公式（求值为非 0 即成立）", fontSize = 11.sp) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(Modifier.height(6.dp))

            // 自由变量 + 容差
            Row(verticalAlignment = Alignment.CenterVertically) {
                var showProbeMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showProbeMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Tune, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.7f))
                        Spacer(Modifier.width(4.dp))
                        Text("自由变量：$probeName", fontSize = 12.sp, color = RustedOnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = showProbeMenu, onDismissRequest = { showProbeMenu = false }) {
                        targets.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name, fontSize = 13.sp) },
                                onClick = { showProbeMenu = false; probeName = t.name }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("容差 ${fmt(tolerance)}", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                Slider(
                    value = tolerance.toFloat(),
                    onValueChange = { tolerance = it.toDouble() },
                    valueRange = 0f..40f,
                    modifier = Modifier.width(110.dp)
                )
            }

            Text(
                "提示：等号类公式（如三角不等式的等号）区域只有一条线，把容差调到 3~8 才看得见。",
                fontSize = 10.sp,
                color = RustedOnBackground.copy(alpha = 0.45f)
            )
            if (ast == null) {
                Spacer(Modifier.height(2.dp))
                Text("公式解析失败，请检查括号与运算符", fontSize = 11.sp, color = RustedError)
            }
        }
    }
}

private fun fmt(v: Double): String {
    val rounded = v.roundToInt()
    return if (kotlin.math.abs(v - rounded) < 0.05) rounded.toString() else "%.1f".format(v)
}
