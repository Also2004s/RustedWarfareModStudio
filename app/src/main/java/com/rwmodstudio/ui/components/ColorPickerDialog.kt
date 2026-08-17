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
    onInsert: ((String) -> Unit)? = null
) {
    var picked by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Medium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {

                ColorWheelPicker(
                    initialColor = initialColor,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                    wheelSize = 180.dp,
                    showHexInput = true
                )
            }
        },
        confirmButton = {
            Row {
                if (showInsertButton && onInsert != null) {
                    TextButton(
                        onClick = { onInsert(picked); onDismiss() }
                    ) { Text("插入文本", color = RustedPrimary) }
                }
                TextButton(
                    onClick = { onConfirm(picked) }
                ) { Text("应用", color = RustedPrimary) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = RustedSurface
    )
}
