package com.rwmodstudio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.feature.completion.RequiredCodeReport
import com.rwmodstudio.ui.theme.RustedAccent
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedSurface

/**
 * 「缺失键」浮层：只读展示点灯泡时的检查结果。
 *
 * 定位与外观由调用方通过 [modifier] 决定（编辑器内右侧中部、非 modal）。
 * 内容分两组：整节缺失（红字 chip）与已存在节的缺失必备键（中文名 + 英文原名）。
 */
@Composable
fun RequiredCodeReportPanel(
    report: RequiredCodeReport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(200.dp),
        shape = RoundedCornerShape(12.dp),
        color = RustedSurface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, RustedOnBackground.copy(alpha = 0.14f)),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "缺失键",
                    color = RustedOnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = RustedOnBackground.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp, bottom = 6.dp)
                    .height(1.dp)
                    .background(RustedOnBackground.copy(alpha = 0.12f))
            )
            Column(
                Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (report.missingSections.isNotEmpty()) {
                    SectionLabel("缺少的节", RustedError)
                    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        report.missingSections.forEach { section ->
                            Text(
                                text = section,
                                color = RustedError,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (report.incomplete.isNotEmpty()) {
                    SectionLabel("缺少的属性", RustedAccent)
                    report.incomplete.forEach { gap ->
                        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = gap.section,
                                color = RustedAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            gap.missing.forEach { key ->
                                val label = when {
                                    key.chinese.isNullOrBlank() -> key.english
                                    key.chinese.equals(key.english, ignoreCase = true) -> key.chinese
                                    else -> "${key.chinese} ${key.english}"
                                }
                                Text(
                                    text = "· $label",
                                    color = RustedOnBackground.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 3.dp)
    )
}
