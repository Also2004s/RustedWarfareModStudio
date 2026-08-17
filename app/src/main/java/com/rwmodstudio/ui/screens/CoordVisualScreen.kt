package com.rwmodstudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.feature.coord.*
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSurface

private val DEFAULT_CODE = "创建标记(x=self.x, y=self.y).获取相对偏移(y=100)"

private val EXAMPLE_1 = "创建标记(x=敌人.x, y=敌人.y, dir=之间方向(self, 敌人)-存活时间).获取相对偏移(y=-120)"
private val EXAMPLE_2 = "创建标记(x=self.x+cos(存活时间*180),y=self.y+sin(存活时间*180)).获取相对偏移(y=(存活时间*11-550)*(3+cos(存活时间*50)*1.3),角度偏移=-(存活时间*250+sin(存活时间*100)*20))"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoordVisualScreen(
    initialText: String,
    onBack: () -> Unit,
    onTextChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current

    val startCode = remember(initialText) {
        val markers = parseCoordMarkers(initialText)
        if (markers.isNotEmpty()) {
            markers.mapIndexed { index, marker ->
                "标记${index + 1}:${initialText.substring(marker.fullRange)}"
            }.joinToString("\n")
        } else {
            DEFAULT_CODE
        }
    }
    var codeInput by remember { mutableStateOf(TextFieldValue(startCode, TextRange(startCode.length))) }
    var text by remember { mutableStateOf(startCode) }
    var self by remember { mutableStateOf(CoordSelf()) }
    var targets by remember { mutableStateOf(buildDefaultTargets(text)) }
    var markers by remember { mutableStateOf(ensureDefaultMarker(parseCoordMarkers(text), self)) }

    var simTime by remember { mutableDoubleStateOf(0.0) }
    var resources by remember { mutableStateOf(listOf<CoordResource>()) }
    var showResourceDialog by remember { mutableStateOf(false) }
    var editingResource by remember { mutableStateOf<CoordResource?>(null) }
    var showExampleMenu by remember { mutableStateOf(false) }
    var showExpandedEditor by remember { mutableStateOf(false) }
    var selectedMarkerIndex by remember { mutableIntStateOf(-1) }
    var draggedTargetIndex by remember { mutableIntStateOf(-1) }

    val resourceMap = remember(resources) { resources.associate { it.name to it.value } }

    var scale by remember { mutableFloatStateOf(0.5f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val isDark = ThemeState.isDark
    val bgColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)

    fun fitViewToObjects(list: List<CoordMarker>) {
        if (list.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return
        val allX = mutableListOf(self.x)
        val allY = mutableListOf(self.y)
        targets.forEach { allX.add(it.x); allY.add(it.y) }

        // 采样多个时间点，确保动态代码的轨迹也能被纳入视野
        val timeSamples = listOf(0.0, simTime, 75.0, 150.0, 225.0, 300.0).distinct()
        timeSamples.forEach { t ->
            val sampled = list.map { it.copy() }
            recalcMarkers(sampled, self, targets, t, resourceMap)
            sampled.forEach {
                allX.add(it.finalX)
                allY.add(it.finalY)
                allX.add(it.baseX)
                allY.add(it.baseY)
            }
        }

        val minX = allX.minOrNull() ?: 0.0
        val maxX = allX.maxOrNull() ?: 0.0
        val minY = allY.minOrNull() ?: 0.0
        val maxY = allY.maxOrNull() ?: 0.0
        val padding = 80.0
        val worldWidth = (maxX - minX + padding * 2).coerceAtLeast(100.0)
        val worldHeight = (maxY - minY + padding * 2).coerceAtLeast(100.0)
        val newScale = kotlin.math.min(
            canvasSize.width / worldWidth,
            canvasSize.height / worldHeight
        ).toFloat().coerceIn(0.05f, 2f)
        val centerWorldX = (minX + maxX) / 2.0
        val centerWorldY = (minY + maxY) / 2.0
        scale = newScale
        panX = -(centerWorldX * newScale).toFloat()
        panY = -(centerWorldY * newScale).toFloat()
    }

    fun applyCode(newCode: String) {
        val parsed = parseCoordMarkers(newCode)
        if (newCode.contains("创建标记") && parsed.isEmpty()) {
            Toast.makeText(context, "代码解析失败，请检查语法", Toast.LENGTH_SHORT).show()
            return
        }
        codeInput = TextFieldValue(newCode, TextRange(newCode.length))
        text = newCode
        // 保留用户手动调整过的目标位置，不因应用代码而重置
        if (targets.isEmpty()) {
            targets = buildDefaultTargets(newCode)
        }
        val list = parsed.toMutableList()
        selectedMarkerIndex = if (list.isEmpty()) -1 else 0
        recalcMarkers(list, self, targets, simTime, resourceMap)
        markers = list
        onTextChanged(newCode)
        if (list.isNotEmpty()) {
            Toast.makeText(context, "已解析 ${list.size} 个标记", Toast.LENGTH_SHORT).show()
        }
    }

    fun onMarkerDragged(index: Int, wx: Double, wy: Double, finished: Boolean) {
        // 紫色标记仅用于视图预览，不反写代码
        val list = markers.map { it.copy() }.toMutableList()
        val marker = list[index]
        selectedMarkerIndex = index

        if (finished) {
            reverseOffset(marker, wx, wy)
            recalcMarkers(list, self, targets, simTime, resourceMap)
            markers = list
        } else {
            reverseOffset(marker, wx, wy)
            marker.finalX = wx
            marker.finalY = wy
            markers = list
        }
    }

    fun resetView() {
        scale = 0.5f
        panX = 0f
        panY = 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // 画布区域（不会被底部面板遮挡）
        Box(modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it }) {
            CoordCanvas(
                self = self,
                targets = targets,
                markers = markers,
                scale = scale,
                panX = panX,
                panY = panY,
                selectedMarkerIndex = selectedMarkerIndex,
                draggedTargetIndex = draggedTargetIndex,
                isDark = isDark,
                onScalePanChange = { s, px, py ->
                    scale = s
                    panX = px
                    panY = py
                },
                onSelfChange = { newSelf ->
                    self = newSelf
                    val list = markers.map { it.copy() }.toMutableList()
                    recalcMarkers(list, newSelf, targets, simTime, resourceMap)
                    markers = list
                },
                onTargetChange = { index, t ->
                    val newTargets = targets.toMutableList().apply { set(index, t) }
                    targets = newTargets
                    val list = markers.map { it.copy() }.toMutableList()
                    recalcMarkers(list, self, newTargets, simTime, resourceMap)
                    markers = list
                },
                onMarkerDrag = { index, wx, wy, finished ->
                    onMarkerDragged(index, wx, wy, finished)
                },
                onSelectMarker = { index ->
                    selectedMarkerIndex = index
                },
                onDragTargetStart = { draggedTargetIndex = it },
                onDragTargetEnd = { draggedTargetIndex = -1 }
            )

            // 左上角返回按钮
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(12.dp)
                    .size(40.dp)
                    .background(RustedSurface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RustedOnBackground)
            }

            // 右上角重置视角
            IconButton(
                onClick = { resetView() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(RustedSurface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.Default.Refresh, null, tint = RustedPrimary)
            }

        }

        // 底部输入面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RustedSurface.copy(alpha = 0.95f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 代码输入框
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    label = { Text("输入坐标代码（长按/点击展开）", fontSize = 11.sp) },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 2,
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "展开编辑",
                            tint = RustedPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .combinedClickable(
                                    onClick = { showExpandedEditor = true },
                                    onLongClick = { showExpandedEditor = true }
                                )
                        )
                    }
                )
                Button(
                    onClick = {
                        if (codeInput.text.isBlank()) {
                            Toast.makeText(context, "代码不能为空", Toast.LENGTH_SHORT).show()
                        } else {
                            applyCode(codeInput.text)
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("应用", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // 展开编辑对话框
            if (showExpandedEditor) {
                var expandedText by remember(showExpandedEditor) { mutableStateOf(codeInput.text) }
                AlertDialog(
                    onDismissRequest = { showExpandedEditor = false },
                    title = { Text("编辑坐标代码", fontSize = 14.sp) },
                    text = {
                        OutlinedTextField(
                            value = expandedText,
                            onValueChange = { expandedText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            singleLine = false,
                            minLines = 6,
                            maxLines = 15
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (expandedText.isBlank()) {
                                    Toast.makeText(context, "代码不能为空", Toast.LENGTH_SHORT).show()
                                } else {
                                    applyCode(expandedText)
                                    showExpandedEditor = false
                                }
                            }
                        ) {
                            Text("应用", fontSize = 13.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExpandedEditor = false }) {
                            Text("取消", fontSize = 13.sp)
                        }
                    }
                )
            }

            // 块插入栏
            fun insertBlock(snippet: String) {
                val old = codeInput
                val start = old.selection.start.coerceIn(0, old.text.length)
                val end = old.selection.end.coerceIn(0, old.text.length)
                val newText = old.text.replaceRange(start, end, snippet)
                val newCursor = start + snippet.length
                codeInput = old.copy(
                    text = newText,
                    selection = TextRange(newCursor)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { insertBlock("self") },
                    label = { Text("自身", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { insertBlock("敌人") },
                    label = { Text("敌人", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { insertBlock("sin(存活时间*180)") },
                    label = { Text("sin", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { insertBlock("cos(存活时间*180)") },
                    label = { Text("cos", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { insertBlock("存活时间") },
                    label = { Text("时间", fontSize = 11.sp) }
                )
                resources.forEach { res ->
                    AssistChip(
                        onClick = { insertBlock(res.name) },
                        label = { Text(res.name, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 模拟时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("时间", modifier = Modifier.width(40.dp), fontSize = 12.sp, color = RustedOnBackground)
                Slider(
                    value = simTime.toFloat(),
                    onValueChange = {
                        simTime = it.toDouble()
                        val list = markers.map { it.copy() }.toMutableList()
                        recalcMarkers(list, self, targets, simTime, resourceMap)
                        markers = list
                    },
                    valueRange = 0f..300f,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = simTime.toInt().toString(),
                    onValueChange = { input ->
                        input.toDoubleOrNull()?.let { v ->
                            simTime = v.coerceIn(0.0, 300.0)
                            val list = markers.map { it.copy() }.toMutableList()
                            recalcMarkers(list, self, targets, simTime, resourceMap)
                            markers = list
                        }
                    },
                    modifier = Modifier.width(56.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(Modifier.height(4.dp))

            // 资源块
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("资源块", fontSize = 12.sp, color = RustedOnBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { showExampleMenu = true }) {
                            Text("示例", fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = showExampleMenu,
                            onDismissRequest = { showExampleMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("示例1：相对角度", fontSize = 12.sp) },
                                onClick = {
                                    showExampleMenu = false
                                    applyCode(EXAMPLE_1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("示例2：三角函数", fontSize = 12.sp) },
                                onClick = {
                                    showExampleMenu = false
                                    applyCode(EXAMPLE_2)
                                }
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            editingResource = null
                            showResourceDialog = true
                        }
                    ) {
                        Text("添加", fontSize = 12.sp)
                    }
                }
            }

            resources.forEachIndexed { index, res ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        res.name,
                        modifier = Modifier.width(44.dp),
                        fontSize = 10.sp,
                        color = RustedOnBackground,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Slider(
                        value = res.value.toFloat(),
                        onValueChange = { newValue ->
                            resources = resources.toMutableList().apply {
                                set(index, res.copy(value = newValue.toDouble()))
                            }
                            val list = markers.map { it.copy() }.toMutableList()
                            recalcMarkers(list, self, targets, simTime, resources.associate { it.name to it.value })
                            markers = list
                        },
                        valueRange = res.min.toFloat()..res.max.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = "${res.value.toInt()}",
                        onValueChange = { input ->
                            input.toDoubleOrNull()?.let { v ->
                                val clamped = v.coerceIn(res.min, res.max)
                                resources = resources.toMutableList().apply {
                                    set(index, res.copy(value = clamped))
                                }
                                val list = markers.map { it.copy() }.toMutableList()
                                recalcMarkers(list, self, targets, simTime, resources.associate { it.name to it.value })
                                markers = list
                            }
                        },
                        modifier = Modifier.width(48.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    IconButton(
                        onClick = { resources = resources.toMutableList().apply { removeAt(index) } },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("×", fontSize = 14.sp, color = RustedPrimary)
                    }
                }
            }
        }
    }

    // 资源块添加/编辑对话框
    if (showResourceDialog) {
        var dialogName by remember { mutableStateOf(editingResource?.name ?: "") }
        var dialogMin by remember { mutableStateOf(editingResource?.min?.toInt()?.toString() ?: "0") }
        var dialogMax by remember { mutableStateOf(editingResource?.max?.toInt()?.toString() ?: "100") }
        var dialogValue by remember { mutableStateOf(editingResource?.value?.toInt()?.toString() ?: "0") }

        AlertDialog(
            onDismissRequest = { showResourceDialog = false },
            title = { Text(if (editingResource == null) "添加资源块" else "编辑资源块") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("名称") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dialogMin,
                            onValueChange = { dialogMin = it },
                            label = { Text("最小值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = dialogMax,
                            onValueChange = { dialogMax = it },
                            label = { Text("最大值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    OutlinedTextField(
                        value = dialogValue,
                        onValueChange = { dialogValue = it },
                        label = { Text("默认值") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = dialogName.trim()
                        val min = dialogMin.toDoubleOrNull() ?: 0.0
                        val max = dialogMax.toDoubleOrNull() ?: 100.0
                        val value = dialogValue.toDoubleOrNull() ?: min
                        if (name.isNotBlank() && name !in listOf("self", "存活时间", "时间") && name.matches(Regex("""[\u4e00-\u9fa5_a-zA-Z][\u4e00-\u9fa5_a-zA-Z0-9.]*"""))) {
                            val safeMin = kotlin.math.min(min, max)
                            val safeMax = kotlin.math.max(min, max)
                            val safeValue = value.coerceIn(safeMin, safeMax)
                            if (editingResource != null) {
                                resources = resources.map {
                                    if (it.name == editingResource?.name) CoordResource(name, safeMin, safeMax, safeValue) else it
                                }
                            } else {
                                resources = resources + CoordResource(name, safeMin, safeMax, safeValue)
                            }
                            showResourceDialog = false
                        } else {
                            Toast.makeText(context, "名称不合法或与保留字冲突", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResourceDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 首次进入自动应用一次默认代码
    LaunchedEffect(Unit) {
        applyCode(codeInput.text)
    }
}

private fun ensureDefaultMarker(list: List<CoordMarker>, self: CoordSelf): List<CoordMarker> {
    return list.ifEmpty {
        listOf(
            CoordMarker(
                index = 0,
                fullRange = IntRange.EMPTY,
                offsetCallRange = IntRange.EMPTY,
                baseXExpr = "self.x",
                baseYExpr = "self.y",
                baseDirExpr = "self.dir",
                offsetXExpr = "0",
                offsetYExpr = "100",
                dirOffsetExpr = "0",
                baseX = self.x,
                baseY = self.y,
                baseDir = self.dir,
                offsetX = 0.0,
                offsetY = 100.0,
                dirOffset = 0.0,
                finalX = self.x,
                finalY = self.y + 100.0
            )
        )
    }
}
