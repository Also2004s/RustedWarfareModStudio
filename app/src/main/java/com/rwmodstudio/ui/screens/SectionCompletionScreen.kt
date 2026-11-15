package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SectionCompletionConfig
import com.rwmodstudio.core.SectionCompletionEntry
import com.rwmodstudio.ui.theme.*

/**
 * 节补全管理（开发者模式）。
 *
 * 自定义输入 `[` 时弹出的节名列表：内容（中文显示名 / 英文节名）与顺序都可改，
 * 修改即时生效（补全每次读取 [SectionCompletionConfig.entries]），「恢复默认」回内置 19 项。
 */
@Composable
fun SectionCompletionScreen() {
    var entries by remember { mutableStateOf(SectionCompletionConfig.entries()) }
    var newZh by remember { mutableStateOf("") }
    var newEn by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    fun persist(next: List<SectionCompletionEntry>) {
        entries = next
        SectionCompletionConfig.save(next)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
    ) {
        Text(
            "输入「[」时弹出的节名列表。每项由「显示名」和「插入的英文节名」组成，顺序即补全顺序；修改即时生效。",
            fontSize = 11.sp,
            color = RustedOnBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${entries.size} 项",
                fontSize = 12.sp,
                color = RustedOnBackground.copy(alpha = 0.65f)
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                SectionCompletionConfig.reset()
                entries = SectionCompletionConfig.entries()
                hint = ""
            }) {
                Text("恢复默认", fontSize = 12.sp, color = RustedPrimary)
            }
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.12f))

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(entries, key = { index, entry -> "$index-${entry.zh}-${entry.en}" }) { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "[${entry.zh}]",
                            fontSize = 14.sp,
                            color = RustedOnBackground
                        )
                        Text(
                            entry.en.ifBlank { "（无英文名）" },
                            fontSize = 11.sp,
                            color = RustedOnBackground.copy(alpha = 0.55f)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                persist(entries.toMutableList().apply {
                                    val prev = this[index - 1]
                                    this[index - 1] = this[index]
                                    this[index] = prev
                                })
                            }
                        },
                        enabled = index > 0
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp, "上移", Modifier.size(20.dp),
                            tint = RustedOnBackground.copy(alpha = if (index > 0) 0.75f else 0.25f)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (index < entries.lastIndex) {
                                persist(entries.toMutableList().apply {
                                    val next = this[index + 1]
                                    this[index + 1] = this[index]
                                    this[index] = next
                                })
                            }
                        },
                        enabled = index < entries.lastIndex
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown, "下移", Modifier.size(20.dp),
                            tint = RustedOnBackground.copy(alpha = if (index < entries.lastIndex) 0.75f else 0.25f)
                        )
                    }
                    IconButton(onClick = { persist(entries.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = RustedPrimary)
                    }
                }
                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
            }
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.12f))

        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newZh,
                    onValueChange = { newZh = it; hint = "" },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text("显示名", fontSize = 11.sp) },
                    textStyle = TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newEn,
                    onValueChange = { newEn = it; hint = "" },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text("英文节名", fontSize = 11.sp) },
                    textStyle = TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = {
                    val zh = newZh.trim()
                    val en = newEn.trim()
                    when {
                        zh.isEmpty() -> hint = "显示名不能为空"
                        en.isEmpty() -> hint = "英文节名不能为空"
                        entries.any { it.zh == zh } -> hint = "「$zh」已存在"
                        else -> {
                            persist(entries + SectionCompletionEntry(zh, en))
                            newZh = ""
                            newEn = ""
                            hint = ""
                        }
                    }
                }) {
                    Icon(Icons.Default.Add, "添加", Modifier.size(20.dp))
                }
            }
            if (hint.isNotEmpty()) {
                Text(hint, fontSize = 11.sp, color = RustedPrimary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
