package com.rwmodstudio.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.translation.TranslationDedupChecker
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary
import com.rwmodstudio.ui.theme.RustedSurface
import java.io.File

@Composable
fun DedupResultDialog(
    dups: List<TranslationDedupChecker.DuplicateInfo>,
    title: String = "查重结果",
    summary: String? = null,
    disabledWords: Set<String> = emptySet(),
    onDisable: ((word: String) -> Unit)? = null,
    onDisableAll: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onModify: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    val grouped = remember(dups) { dups.groupBy { it.key } }
    var showConfirmAll by remember { mutableStateOf(false) }
    // 未禁用过的查重词数量（「全部禁用」只统计这些）
    val disableAllCount = remember(grouped, disabledWords) {
        grouped.keys.count { it !in disabledWords }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.height(360.dp)) {
                summary?.let {
                    Text(it, fontSize = 13.sp, color = RustedSecondary, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("共 ${dups.size} 处，${grouped.size} 个查重词", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn {
                        grouped.forEach { (word, list) ->
                            val expanded = word in expandedGroups
                            item {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                        expandedGroups = if (expanded) expandedGroups - word else expandedGroups + word
                                    },
                                    colors = CardDefaults.cardColors(containerColor = RustedPrimary.copy(alpha = 0.1f))
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(word, fontSize = 13.sp, fontFamily = AppCodeFontFamily, fontWeight = FontWeight.Medium, color = RustedPrimary)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (onDisable != null) {
                                                if (word in disabledWords) {
                                                    Text(
                                                        "已禁用",
                                                        fontSize = 10.sp,
                                                        color = RustedOnBackground.copy(alpha = 0.35f),
                                                        modifier = Modifier.padding(horizontal = 4.dp)
                                                    )
                                                } else {
                                                    Text(
                                                        "禁用",
                                                        fontSize = 10.sp,
                                                        color = RustedError,
                                                        modifier = Modifier
                                                            .clickable { onDisable(word) }
                                                            .padding(horizontal = 4.dp)
                                                    )
                                                }
                                            }
                                            Text("${list.size} 处", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                null,
                                                Modifier.size(18.dp),
                                                tint = RustedOnBackground.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                            if (expanded) {
                                items(list) { d ->
                                    val fileName = File(d.source).name
                                    Card(Modifier.fillMaxWidth().padding(vertical = 1.dp), colors = CardDefaults.cardColors(containerColor = RustedSurface)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("$fileName  行:${d.line}", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f), fontFamily = AppCodeFontFamily,
                                                    modifier = Modifier.clickable {
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("文件名", fileName))
                                                    })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (onDisableAll != null && disableAllCount > 0) {
                    TextButton(onClick = { showConfirmAll = true }) {
                        Text("全部禁用($disableAllCount)", color = RustedError)
                    }
                }
                TextButton(onClick = onModify) { Text("修改") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )

    if (showConfirmAll) {
        AlertDialog(
            onDismissRequest = { showConfirmAll = false },
            title = { Text("全部禁用") },
            text = {
                Text(
                    "将禁用 $disableAllCount 个查重词，翻译时这些词保留原文（两个方向都不翻译）。" +
                        "可在 翻译库 → 已禁用 里恢复。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmAll = false
                    onDisableAll?.invoke()
                }) { Text("全部禁用", color = RustedError) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmAll = false }) { Text("取消") }
            }
        )
    }
}
