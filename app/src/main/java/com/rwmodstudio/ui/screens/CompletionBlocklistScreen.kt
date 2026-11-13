package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.feature.completion.CompletionBlocklist
import com.rwmodstudio.ui.theme.*

/**
 * 补全隐藏列表（开发者模式）。
 *
 * 默认隐藏项按原因分组，关掉开关即放回补全候选；也可追加/删除自己的隐藏项。
 * 数据存 `RWmod/config/completion_blocklist.json`（用户配置，无验证码）。
 * 只影响补全候选（含翻译库兜底），不改补全表数据。
 */
@Composable
fun CompletionBlocklistScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    // 读一下 revision 让配置变化后本页即时重组
    val revision = CompletionBlocklist.revision
    val q = query.trim()

    val defaultEntries = remember(revision, q) {
        CompletionBlocklist.DEFAULT_ENTRIES.filter { entry ->
            q.isEmpty() ||
                entry.display.contains(q, ignoreCase = true) ||
                entry.names.any { it.contains(q, ignoreCase = true) }
        }
    }
    val hiddenStates = remember(revision) {
        CompletionBlocklist.DEFAULT_ENTRIES.associateWith { CompletionBlocklist.isDefaultHidden(it) }
    }
    val userEntries = remember(revision, q) {
        CompletionBlocklist.userEntries().filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
    }
    val hasChanges = remember(revision) { CompletionBlocklist.hasUserChanges() }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
    ) {
        Text(
            "这里列出补全候选里被隐藏的条目。关掉开关即放回补全；也可以自己追加要隐藏的名字（中英均可）。" +
                "只影响补全候选，不改补全表数据，自定义补全页照旧显示。",
            fontSize = 11.sp,
            color = RustedOnBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // 搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            placeholder = { Text("搜索隐藏项", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 12.sp)
        )

        // 追加隐藏项
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; hint = "" },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入要隐藏的中文名 / 英文名", fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                hint = when {
                    input.isBlank() -> "请输入条目名"
                    !CompletionBlocklist.addExtra(input) -> "该条目已隐藏（或为空）"
                    else -> {
                        input = ""
                        ""
                    }
                }
            }) { Text("隐藏", fontSize = 12.sp) }
        }

        if (hint.isNotEmpty()) {
            Text(
                hint,
                fontSize = 11.sp,
                color = RustedError,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            )
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.12f))

        LazyColumn(Modifier.weight(1f)) {
            item { GroupTitle("内置隐藏项（关掉开关 = 放回补全）") }
            CompletionBlocklist.Reason.entries.forEach { reason ->
                val group = defaultEntries.filter { it.reason == reason }
                if (group.isNotEmpty()) {
                    item { SubGroupTitle(reason.label) }
                    items(group) { entry ->
                        BlockEntryRow(
                            title = entry.display,
                            subtitle = buildString {
                                if (entry.names.size > 1) append(entry.names.joinToString(" / "))
                                if (entry.note.isNotBlank()) {
                                    if (isNotEmpty()) append("　")
                                    append(entry.note)
                                }
                            },
                            checked = hiddenStates[entry] ?: true,
                            onCheckedChange = { hidden ->
                                CompletionBlocklist.setDefaultHidden(entry, hidden)
                                hint = ""
                            }
                        )
                    }
                }
            }

            item { GroupTitle("我添加的隐藏项") }
            if (userEntries.isEmpty()) {
                item {
                    Text(
                        if (q.isEmpty()) "还没有自定义隐藏项" else "没有匹配的自定义隐藏项",
                        fontSize = 12.sp,
                        color = RustedOnBackground.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            } else {
                items(userEntries) { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name,
                            fontSize = 13.sp,
                            color = RustedOnBackground,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 10.dp)
                        )
                        IconButton(
                            onClick = {
                                CompletionBlocklist.removeExtra(name)
                                hint = ""
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, "删除", Modifier.size(16.dp))
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(start = 16.dp),
                        color = RustedOnBackground.copy(alpha = 0.08f)
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.12f))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    CompletionBlocklist.resetToDefault()
                    input = ""
                    hint = ""
                },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复默认", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = RustedSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SubGroupTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = RustedOnBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun BlockEntryRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
        ) {
            Text(title, fontSize = 13.sp, color = RustedOnBackground)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.5f)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
