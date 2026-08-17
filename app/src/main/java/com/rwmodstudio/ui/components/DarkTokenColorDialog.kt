package com.rwmodstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.DarkThemeColors
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

    val currentColor = colorForCategory(colors, selectedCategory)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("自定义高亮", fontWeight = FontWeight.Medium)
                TextButton(onClick = { showPresetDialog = true }) {
                    Text("使用预设", color = RustedPrimary)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
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

                ColorWheelPicker(
                    initialColor = currentColor,
                    onColorChanged = { newColor ->
                        colors = updateColor(colors, selectedCategory, newColor)
                    },
                    wheelSize = 180.dp
                )
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
                showPresetDialog = false
            }
        )
    }
}

@Composable
private fun PresetPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (DarkThemeColors) -> Unit
) {
    val presets = listOf(
        "深色" to DarkThemeColors.Default,
        "浅色" to DarkThemeColors.PresetLight,
        "纯净" to DarkThemeColors.PresetPure
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用预设", fontWeight = FontWeight.Medium) },
        text = {
            Column {
                presets.forEach { (name, preset) ->
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
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = RustedSurface
    )
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
