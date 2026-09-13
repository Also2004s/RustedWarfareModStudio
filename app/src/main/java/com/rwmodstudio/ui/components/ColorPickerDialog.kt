package com.rwmodstudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSurface

@Composable
fun ColorPickerDialog(
    initialColor: String,
    title: String = "选择颜色",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    showInsertButton: Boolean = false,
    onInsert: ((String) -> Unit)? = null,
    showApplyButton: Boolean = true
) {
    var picked by remember { mutableStateOf(initialColor) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        actions = {
            if (showInsertButton && onInsert != null) {
                DialogActionButton("插入文本") { onInsert(picked); onDismiss() }
            }
            // 符号栏调色盘仅插入、不应用背景色时隐藏「应用」按钮
            if (showApplyButton) {
                DialogActionButton("应用") { onConfirm(picked) }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (com.rwmodstudio.core.SettingsManager.devHsvPicker) {
                // 开发者模式：HSV 取色盘（左色盘 + 右滑杆，支持透明度）
                ColorWheelHSV(
                    initialColor = initialColor,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                    wheelSize = 180.dp,
                    showHexInput = true
                )
            } else {
                ColorWheelPicker(
                    initialColor = initialColor,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                    wheelSize = 180.dp,
                    showHexInput = true
                )
            }
        }
    }
}
