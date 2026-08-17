package com.rwmodstudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeReferenceScreen(inlineMode: Boolean = false, userCompletions: List<CustomCompletion> = emptyList()) {
    val context = LocalContext.current
    val engine = remember { TranslationEngine.getInstance() }
    var loaded by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var detailItem by remember { mutableStateOf<CustomCompletion?>(null) }

    val localUserItems = remember { loadUserItems() }
    val userItems = if (userCompletions.isNotEmpty()) userCompletions else localUserItems
    var nativeItems by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var extraItems by remember { mutableStateOf(listOf<CustomCompletion>()) }

    LaunchedEffect(Unit) {
        if (!engine.isLoaded) engine.load(context)
        nativeItems = loadNativeItems().ifEmpty {
            val generated = generateNativeItems(engine)
            saveNativeCompletions(generated)
            SettingsManager.writeVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS, SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE)
            generated
        }
        extraItems = loadExtraItemsVerified(context, engine)
        loaded = true
    }

    if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RustedPrimary) }; return }

    // 用户表 > 原生表 > 附件表：同名项以优先级高者为准
    val mergedItems by remember(userItems, nativeItems, extraItems) {
        derivedStateOf {
            val userByName = userItems.associateBy { it.name }
            val nativeByName = nativeItems.associateBy { it.name }
            userItems + nativeItems.filter { it.name !in userByName } + extraItems.filter { it.name !in userByName && it.name !in nativeByName }
        }
    }
    val userNames by remember(userItems) { derivedStateOf { userItems.map { it.name }.toSet() } }

    val q = searchQuery.trim().lowercase()
    val filteredItems = remember(mergedItems, q) {
        if (q.isEmpty()) mergedItems
        else {
            // 「完全匹配优先」排序：名称 完全相等(3) > 前缀命中(2) > 子串包含(1) > 仅其它字段命中(0)
            fun namePriority(it: CustomCompletion): Int {
                val n = it.name.lowercase()
                return when {
                    n == q -> 3
                    n.startsWith(q) -> 2
                    n.contains(q) -> 1
                    else -> 0
                }
            }
            mergedItems.filter {
                it.name.lowercase().contains(q) ||
                it.desc.lowercase().contains(q) ||
                it.value.lowercase().contains(q) ||
                it.detail.lowercase().contains(q) ||
                it.category.any { c -> c.lowercase().contains(q) }
            }.sortedByDescending { namePriority(it) }
        }
    }

    // 按 category 分组；多分类项会同时出现在多个分类中，空 category 归入“其他”
    val grouped by remember(filteredItems) {
        derivedStateOf {
            filteredItems
                .flatMap { item ->
                    item.category.filter { it.isNotBlank() }
                        .ifEmpty { listOf("其他") }
                        .map { it to item }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.distinctBy { item -> item.name } }
                .toSortedMap()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; selectedCategory = "" },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            singleLine = true,
            placeholder = { Text("搜索属性...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )

        when {
            q.isNotEmpty() -> {
                LazyColumn(Modifier.weight(1f).background(RustedBackground), contentPadding = PaddingValues(8.dp)) {
                    if (filteredItems.isEmpty()) {
                        item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("无匹配", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.4f)) } }
                    } else {
                        items(filteredItems) { item ->
                            CompletionItemCard(item, isUser = item.name in userNames, onClick = { detailItem = item })
                        }
                    }
                }
            }
            selectedCategory.isNotEmpty() -> {
                val itemsInCategory = grouped[selectedCategory] ?: emptyList()
                CategoryList(
                    title = selectedCategory,
                    count = itemsInCategory.size,
                    items = itemsInCategory,
                    userNames = userNames,
                    onBack = { selectedCategory = "" },
                    onClick = { detailItem = it }
                )
            }
            else -> {
                LazyColumn(Modifier.weight(1f).background(RustedBackground), contentPadding = PaddingValues(8.dp)) {
                    grouped.entries.sortedBy { it.key }.forEachIndexed { index, (category, items) ->
                        if (index > 0) item { Spacer(Modifier.height(2.dp)) }
                        item {
                            CategoryListRow(category = category, count = items.size, onClick = { selectedCategory = category })
                        }
                    }
                }
            }
        }
    }

    detailItem?.let { item ->
        val ctx = LocalContext.current
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Dialog(onDismissRequest = { detailItem = null }) {
            Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 520.dp), shape = RoundedCornerShape(20.dp), color = RustedBackground, tonalElevation = 6.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(RustedPrimary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Info, null, Modifier.size(22.dp), tint = RustedPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text = item.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
                            if (item.detail.isNotEmpty()) {
                                Text(text = item.detail, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                            }
                        }
                        IconButton(onClick = { detailItem = null }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.5f)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Column {
                                if (item.category.isNotEmpty()) {
                                    InfoRow("分类", item.category.joinToString(", "))
                                }
                                if (item.desc.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(text = "说明", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RustedPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    Text(text = item.desc, fontSize = 14.sp, color = RustedOnBackground, lineHeight = 20.sp)
                                }
                                if (item.example.isNotEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "示例", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RustedPrimary, modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = { clipboard.setPrimaryClip(ClipData.newPlainText(item.name, completionFormatInsert(item))) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = RustedSecondary); Spacer(Modifier.width(4.dp)); Text("复制代码", fontSize = 11.sp, color = RustedSecondary) }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = RustedSurface), shape = RoundedCornerShape(12.dp)) {
                                        SelectionContainer {
                                            Text(text = item.example, modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary, lineHeight = 18.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    title: String,
    count: Int,
    items: List<CustomCompletion>,
    userNames: Set<String>,
    onBack: () -> Unit,
    onClick: (CustomCompletion) -> Unit
) {
    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, Modifier.size(28.dp)) { Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp), tint = RustedPrimary) }
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RustedPrimary, modifier = Modifier.weight(1f))
            Text(text = "$count 项", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
        }
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.06f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
            items(items) { item -> CompletionItemCard(item, isUser = item.name in userNames, onClick = { onClick(item) }) }
        }
    }
}

@Composable
private fun CategoryListRow(category: String, count: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = RustedSurface), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(RustedPrimary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = RustedPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text(text = category, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground, modifier = Modifier.weight(1f))
            Text(text = "$count 项", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun CompletionItemCard(item: CustomCompletion, isUser: Boolean, onClick: () -> Unit) {
    val tc = when (item.detail) { "bool" -> RustedPrimary; "float", "int" -> RustedSecondary; "string" -> RustedAccent; else -> RustedOnBackground.copy(alpha = 0.3f) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 1.dp), colors = CardDefaults.cardColors(containerColor = if (isUser) RustedSecondary.copy(alpha = 0.06f) else RustedSurface), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(tc)); Spacer(Modifier.width(6.dp))
                Text(text = item.name, fontSize = 13.sp, fontFamily = AppCodeFontFamily, color = RustedOnBackground, modifier = Modifier.weight(1f))
                if (isUser) Text("自定义", fontSize = 10.sp, color = RustedSecondary.copy(alpha = 0.6f))
            }
            if (item.desc.isNotEmpty()) Text(text = item.desc.take(36) + if (item.desc.length > 36) "..." else "", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f), maxLines = 1)
            if (item.example.isNotEmpty()) Text(text = item.example.take(44) + if (item.example.length > 44) "..." else "", fontSize = 10.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.45f))
        Text(text = value, fontSize = 13.sp, color = RustedOnBackground)
    }
}
