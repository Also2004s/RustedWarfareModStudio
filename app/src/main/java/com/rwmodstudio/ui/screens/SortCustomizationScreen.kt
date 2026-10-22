package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SortCustomizationDefaults
import com.rwmodstudio.core.SortCustomizationManager
import com.rwmodstudio.core.SortEntry
import com.rwmodstudio.ui.theme.*

/**
 * 排序自定义（开发者模式）。
 *
 * 每个节一份有序表：表内条目按顺序排到补全列表最前，未列入的条目保持原顺序。
 * **只调整顺序，不增删任何补全条目**。数据存 `RWmod/config/sort_custom.json`（用户配置，无验证码）。
 */
@Composable
fun SortCustomizationScreen() {
    var section by rememberSaveable { mutableStateOf(SortCustomizationDefaults.SECTION_NAMES.first()) }
    var rows by remember { mutableStateOf(SortCustomizationManager.entriesOf(section)) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    LaunchedEffect(section) {
        rows = SortCustomizationManager.entriesOf(section)
        hint = ""
    }

    fun persist(list: List<SortEntry>) {
        rows = list
        SortCustomizationManager.updateSection(section, list)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
    ) {
        Text(
            "表内条目按顺序排到补全列表最前；不在表里的条目保持原顺序。只调顺序、不增删补全条目。",
            fontSize = 11.sp,
            color = RustedOnBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // 节选择
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SortCustomizationDefaults.SECTION_NAMES.forEach { name ->
                val selected = name == section
                Surface(
                    color = if (selected) RustedPrimary.copy(alpha = 0.28f) else RustedSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clickable {
                        if (section != name) {
                            section = name
                            input = ""
                        }
                    }
                ) {
                    Text(
                        name,
                        fontSize = 12.sp,
                        color = RustedOnBackground,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(top = 8.dp),
            color = RustedOnBackground.copy(alpha = 0.12f)
        )

        // 列表
        Box(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                Text(
                    "该节暂无排序条目（补全顺序保持原样）",
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(rows) { index, entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 11.sp,
                                color = RustedSecondary,
                                modifier = Modifier.width(26.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.zh.ifBlank { entry.en },
                                    fontSize = 13.sp,
                                    color = RustedOnBackground
                                )
                                if (entry.en.isNotBlank() && entry.en != entry.zh) {
                                    Text(
                                        entry.en,
                                        fontSize = 10.sp,
                                        color = RustedOnBackground.copy(alpha = 0.55f)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val list = rows.toMutableList()
                                    val t = list[index - 1]; list[index - 1] = list[index]; list[index] = t
                                    persist(list)
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(34.dp)
                            ) { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp)) }
                            IconButton(
                                onClick = {
                                    val list = rows.toMutableList()
                                    val t = list[index + 1]; list[index + 1] = list[index]; list[index] = t
                                    persist(list)
                                },
                                enabled = index < rows.lastIndex,
                                modifier = Modifier.size(34.dp)
                            ) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp)) }
                            IconButton(
                                onClick = {
                                    val list = rows.toMutableList()
                                    list.removeAt(index)
                                    persist(list)
                                },
                                modifier = Modifier.size(34.dp)
                            ) { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.12f))

        // 添加
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
                placeholder = { Text("输入键名（中文或英文）", fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val k = input.trim()
                when {
                    k.isEmpty() -> hint = "请输入键名"
                    rows.any { it.zh == k || it.en == k } -> hint = "该条目已在表内"
                    else -> {
                        persist(rows + SortEntry(k, k))
                        input = ""
                        hint = ""
                    }
                }
            }) { Text("添加", fontSize = 12.sp) }
        }

        // 重置
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    SortCustomizationManager.resetSection(section)
                    rows = SortCustomizationManager.entriesOf(section)
                    hint = ""
                },
                modifier = Modifier.weight(1f)
            ) { Text("重置本节为默认", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    SortCustomizationManager.resetToDefault()
                    rows = SortCustomizationManager.entriesOf(section)
                    hint = ""
                },
                modifier = Modifier.weight(1f)
            ) { Text("重置全部为默认", fontSize = 12.sp) }
        }

        if (hint.isNotEmpty()) {
            Text(
                hint,
                fontSize = 11.sp,
                color = RustedError,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
            )
        }
    }
}
