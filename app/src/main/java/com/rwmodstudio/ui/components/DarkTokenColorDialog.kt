package com.rwmodstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.rwmodstudio.core.DarkThemeColors
import com.rwmodstudio.core.HighlightPreset
import com.rwmodstudio.core.HighlightThemeManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DarkTokenColorDialog(
    initialColors: DarkThemeColors,
    onDismiss: () -> Unit,
    onConfirm: (DarkThemeColors) -> Unit
) {
    var colors by remember { mutableStateOf(initialColors) }
    var selectedCategory by remember { mutableStateOf("section") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    val currentColor = colorForCategory(colors, selectedCategory)

    // 取色盘「初始颜色」只随分类切换更新，取色回调不回写它（与调色盘 ColorPickerDialog 一致）。
    // 若直接用 currentColor 作 initialColor，取色回调会把它写回 colors → currentColor 变化 →
    // 取色盘 LaunchedEffect(initialColor) 触发重置 → 拖拽中断、HEX 输入框焦点丢失、输入法被收起。
    var pickerSeed by remember { mutableStateOf(currentColor) }
    LaunchedEffect(selectedCategory) {
        pickerSeed = colorForCategory(colors, selectedCategory)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("自定义高亮", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSavePresetDialog = true }) {
                        Text("保存预设", color = RustedPrimary)
                    }
                    TextButton(onClick = { showPresetDialog = true }) {
                        Text("使用预设", color = RustedPrimary)
                    }
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("选择类型", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val categories = listOf("ui", "plainText") + DarkThemeColors.targetScopes.keys.toList()
                        categories.forEach { category ->
                            val color = colorForCategory(colors, category)
                            val label = DarkThemeColors.labels[category] ?: category
                            val selected = selectedCategory == category
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) RustedPrimary.copy(alpha = 0.15f) else RustedSurface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) RustedPrimary else RustedOnBackground.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.clickable { selectedCategory = category }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(parseHexColor(color))
                                            .border(1.dp, RustedOnBackground.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, fontSize = 12.sp, color = RustedOnBackground)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "${DarkThemeColors.labels[selectedCategory] ?: selectedCategory} · $currentColor",
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))

                if (com.rwmodstudio.core.SettingsManager.devHsvPicker) {
                    ColorWheelHSV(
                        initialColor = pickerSeed,
                        onColorChanged = { newColor ->
                            colors = updateColor(colors, selectedCategory, newColor)
                        },
                        wheelSize = 180.dp,
                        showHexInput = false
                    )
                } else {
                    ColorWheelPicker(
                        initialColor = pickerSeed,
                        onColorChanged = { newColor ->
                            colors = updateColor(colors, selectedCategory, newColor)
                        },
                        wheelSize = 180.dp,
                        showHexInput = false
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(colors) }) { Text("应用", color = RustedPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = RustedSurface
    )

    if (showPresetDialog) {
        PresetPickerDialog(
            onDismiss = { showPresetDialog = false },
            onSelect = { preset ->
                colors = preset
                selectedCategory = "section"
                pickerSeed = preset.section
                showPresetDialog = false
            },
            onSaveCurrent = {
                showPresetDialog = false
                showSavePresetDialog = true
            }
        )
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            colors = colors,
            isDark = ThemeState.isDark,
            onDismiss = { showSavePresetDialog = false }
        )
    }
}

@Composable
private fun PresetPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (DarkThemeColors) -> Unit,
    onSaveCurrent: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<HighlightPreset?>(null) }
    val builtinPresets = listOf(
        "深色" to DarkThemeColors.Default,
        "浅色" to DarkThemeColors.PresetLight,
        "纯净" to DarkThemeColors.PresetPure
    )
    val userPresets = ThemeState.highlightPresets
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用预设", fontWeight = FontWeight.Medium) },
        text = {
            Column {
                builtinPresets.forEach { (name, preset) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(preset) },
                        shape = RoundedCornerShape(8.dp),
                        color = RustedSurface
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(parseHexColor(preset.section))
                            )
                            Text(name, fontSize = 14.sp, color = RustedOnBackground)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (userPresets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("我的预设", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    userPresets.forEach { preset ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(preset.colors) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(parseHexColor(preset.colors.section))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                preset.name,
                                fontSize = 14.sp,
                                color = RustedOnBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { confirmDelete = preset }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除 ${preset.name}",
                                    tint = RustedOnBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                if (onSaveCurrent != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onSaveCurrent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存当前为预设", color = RustedPrimary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = RustedSurface
    )

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除预设", fontWeight = FontWeight.Medium) },
            text = { Text("确定删除预设「${preset.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    HighlightThemeManager.deletePreset(preset.id)
                    Toast.makeText(context, "已删除预设「${preset.name}」", Toast.LENGTH_SHORT).show()
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
            containerColor = RustedSurface
        )
    }
}

/** 把当前配色命名保存为预设；重名时弹覆盖确认。 */
@Composable
private fun SavePresetDialog(
    colors: DarkThemeColors,
    isDark: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(HighlightThemeManager.nextDefaultName()) }
    var conflict by remember { mutableStateOf<HighlightPreset?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为预设", fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Text("把当前配色保存为预设，之后可在高亮主题中直接切换。", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = HighlightThemeManager.addPreset(name, colors, isDark, "saved")) {
                    is HighlightThemeManager.AddResult.Added -> {
                        Toast.makeText(context, "已保存预设「${r.preset.name}」", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    is HighlightThemeManager.AddResult.NameConflict -> conflict = r.existing
                    is HighlightThemeManager.AddResult.LimitExceeded ->
                        Toast.makeText(context, "预设已达上限（${r.max}）", Toast.LENGTH_SHORT).show()
                    is HighlightThemeManager.AddResult.InvalidName ->
                        Toast.makeText(context, r.reason, Toast.LENGTH_SHORT).show()
                }
            }) { Text("保存", color = RustedPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = RustedSurface
    )

    conflict?.let { existing ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("覆盖预设", fontWeight = FontWeight.Medium) },
            text = { Text("已存在同名预设「${existing.name}」，是否用当前配色覆盖？") },
            confirmButton = {
                TextButton(onClick = {
                    HighlightThemeManager.updatePreset(existing.id, colors, isDark)
                    Toast.makeText(context, "已覆盖预设「${existing.name}」", Toast.LENGTH_SHORT).show()
                    conflict = null
                    onDismiss()
                }) { Text("覆盖", color = RustedPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null }) { Text("取消") }
            },
            containerColor = RustedSurface
        )
    }
}

private fun colorForCategory(colors: DarkThemeColors, category: String): String {
    return when (category) {
        "ui" -> colors.ui
        "plainText" -> colors.plainText
        "section" -> colors.section
        "sectionSuffix" -> colors.sectionSuffix
        "sectionBracket" -> colors.sectionBracket
        "keyword" -> colors.keyword
        "control" -> colors.control
        "other" -> colors.other
        "value" -> colors.value
        "property" -> colors.property
        "comment" -> colors.comment
        "number" -> colors.number
        "string" -> colors.string
        "quote" -> colors.quote
        "boolean" -> colors.boolean
        "constant" -> colors.constant
        "team" -> colors.team
        "movement" -> colors.movement
        "path" -> colors.path
        "hexColor" -> colors.hexColor
        "logical" -> colors.logical
        "operator" -> colors.operator
        "comma" -> colors.comma
        "memory" -> colors.memory
        "reference" -> colors.reference
        "function" -> colors.function
        "parameter" -> colors.parameter
        "shortcutVariable" -> colors.shortcutVariable
        "declaration" -> colors.declaration
        "type" -> colors.type
        "modifier" -> colors.modifier
        "propertyValue" -> colors.propertyValue
        "bracket" -> colors.bracket
        "bracketRound" -> colors.bracketRound
        "bracketCurly" -> colors.bracketCurly
        "variable" -> colors.variable
        else -> "#FFFFFF"
    }
}

private fun updateColor(colors: DarkThemeColors, category: String, newColor: String): DarkThemeColors {
    return when (category) {
        "ui" -> colors.copy(ui = newColor)
        "plainText" -> colors.copy(plainText = newColor)
        "section" -> colors.copy(section = newColor)
        "sectionSuffix" -> colors.copy(sectionSuffix = newColor)
        "sectionBracket" -> colors.copy(sectionBracket = newColor)
        "keyword" -> colors.copy(keyword = newColor)
        "control" -> colors.copy(control = newColor)
        "other" -> colors.copy(other = newColor)
        "value" -> colors.copy(value = newColor)
        "property" -> colors.copy(property = newColor)
        "comment" -> colors.copy(comment = newColor)
        "number" -> colors.copy(number = newColor)
        "string" -> colors.copy(string = newColor)
        "quote" -> colors.copy(quote = newColor)
        "boolean" -> colors.copy(boolean = newColor)
        "constant" -> colors.copy(constant = newColor)
        "team" -> colors.copy(team = newColor)
        "movement" -> colors.copy(movement = newColor)
        "path" -> colors.copy(path = newColor)
        "hexColor" -> colors.copy(hexColor = newColor)
        "logical" -> colors.copy(logical = newColor)
        "operator" -> colors.copy(operator = newColor)
        "comma" -> colors.copy(comma = newColor)
        "memory" -> colors.copy(memory = newColor)
        "reference" -> colors.copy(reference = newColor)
        "function" -> colors.copy(function = newColor)
        "parameter" -> colors.copy(parameter = newColor)
        "shortcutVariable" -> colors.copy(shortcutVariable = newColor)
        "declaration" -> colors.copy(declaration = newColor)
        "type" -> colors.copy(type = newColor)
        "modifier" -> colors.copy(modifier = newColor)
        "propertyValue" -> colors.copy(propertyValue = newColor)
        "bracket" -> colors.copy(bracket = newColor)
        "bracketRound" -> colors.copy(bracketRound = newColor)
        "bracketCurly" -> colors.copy(bracketCurly = newColor)
        "variable" -> colors.copy(variable = newColor)
        else -> colors
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color.White
    }
}
