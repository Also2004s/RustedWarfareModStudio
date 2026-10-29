package com.rwmodstudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.feature.completion.startsWithEnglishLetter
import com.rwmodstudio.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CodeReferenceScreen(inlineMode: Boolean = false, userCompletions: List<CustomCompletion> = emptyList()) {
    val context = LocalContext.current
    val engine = remember { TranslationEngine.getInstance() }
    var loaded by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedCategory by rememberSaveable { mutableStateOf<String?>(null) }
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
    // 双语匹配：搜索词首字符是英文字母 → 搜英文名字段，否则搜中文名字段（原行为不变）
    val searchInEnglish = startsWithEnglishLetter(q)
    val filteredItems = remember(mergedItems, q, searchInEnglish) {
        if (q.isEmpty()) mergedItems
        else {
            // 「完全匹配优先」排序：名称 完全相等(3) > 前缀命中(2) > 子串包含(1) > 仅其它字段命中(0)
            fun namePriority(it: CustomCompletion): Int {
                val n = (if (searchInEnglish) it.nameEn else it.name).lowercase()
                return when {
                    n == q -> 3
                    n.startsWith(q) -> 2
                    n.contains(q) -> 1
                    else -> 0
                }
            }
            mergedItems.filter {
                if (searchInEnglish) {
                    it.nameEn.lowercase().contains(q) ||
                    it.descEn.lowercase().contains(q) ||
                    it.valueEn.lowercase().contains(q) ||
                    it.detailEn.lowercase().contains(q) ||
                    it.exampleEn.lowercase().contains(q) ||
                    it.category.any { c -> c.lowercase().contains(q) }
                } else {
                    it.name.lowercase().contains(q) ||
                    it.desc.lowercase().contains(q) ||
                    it.value.lowercase().contains(q) ||
                    it.detail.lowercase().contains(q) ||
                    it.category.any { c -> c.lowercase().contains(q) }
                }
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

    // ===== 平板横屏：左列表 + 右详情（详情不再弹窗）=====
    // 分栏只看「平板模式」开关，跟横竖屏无关：开着就左栏+右内容，关掉就是原来的单列
    val isSplit = com.rwmodstudio.core.SettingsManager.devTabletMode

    @Composable
    fun ListBody() {
    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        CodeRefSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onClear = { searchQuery = "" }
        )

        when {
            q.isNotEmpty() -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (filteredItems.isEmpty()) {
                        item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("无匹配", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.4f)) } }
                    } else {
                        items(filteredItems, key = { it.name }) { item ->
                            Column {
                                CompletionItemRow(item = item, isUser = item.name in userNames, onClick = { detailItem = item })
                                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
            else -> {
                Box(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        grouped.entries.sortedBy { it.key }.forEach { (category, items) ->
                            item(key = "cat_$category") {
                                Column {
                                    CategoryDisclosureRow(
                                        category = category,
                                        count = items.size,
                                        expanded = expandedCategory == category,
                                        onClick = { expandedCategory = if (expandedCategory == category) null else category }
                                    )
                                    HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                                }
                            }
                            if (expandedCategory == category) {
                                items(items, key = { it.name }) { item ->
                                    Column {
                                        CompletionItemRow(item = item, isUser = item.name in userNames, onClick = { detailItem = item })
                                        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                                    }
                                }
                            }
                        }
                    }
                    // 展开分类时底部悬浮「收起」胶囊：随时拇指可及，无需滑回顶部
                    expandedCategory?.let { cat ->
                        FloatingCollapsePill(
                            category = cat,
                            onCollapse = { expandedCategory = null },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }
    }

    if (isSplit) {
        Row(Modifier.fillMaxSize().background(RustedBackground)) {
            Box(Modifier.weight(1f)) { ListBody() }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val item = detailItem
                if (item == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("点左侧条目查看详情", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    }
                } else {
                    CodeRefDetailPanel(item = item, onClose = { detailItem = null }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    } else {
        ListBody()
    }

    // 条目详情弹窗（仅竖屏）：横屏时详情显示在右栏
    if (!isSplit) detailItem?.let { item ->
        Dialog(onDismissRequest = { detailItem = null }) {
            CodeRefDetailPanel(
                item = item,
                onClose = { detailItem = null },
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 560.dp)
            )
        }
    }


}

@Composable
private fun CodeRefDetailPanel(item: CustomCompletion, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val tc = when (item.detail) { "bool" -> RustedPrimary; "float", "int" -> RustedSecondary; "string" -> RustedAccent; else -> RustedOnBackground.copy(alpha = 0.3f) }
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = RustedSurface,
        border = BorderStroke(1.dp, RustedOnBackground.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tc))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.displayPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = AppCodeFontFamily, color = RustedOnBackground)
                    val secondary = item.displaySecondary()
                    if (secondary.isNotEmpty()) Text(secondary, fontSize = 12.sp, fontFamily = AppCodeFontFamily, color = RustedOnBackground.copy(alpha = 0.5f))
                    if (item.detail.isNotEmpty()) Text(item.detail, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                }
                IconButton(onClick = onClose, Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.5f)) }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (item.detail.isNotEmpty() || item.category.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.detail.isNotEmpty()) {
                            DetailChip {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(tc))
                                    Text(item.detail, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                                }
                            }
                        }
                        item.category.take(2).forEach { c ->
                            DetailChip { Text(c, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.6f)) }
                        }
                    }
                }
                if (item.desc.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("说明", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RustedPrimary)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer { Text(item.desc, fontSize = 14.sp, color = RustedOnBackground, lineHeight = 20.sp) }
                }
                if (item.example.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("示例", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RustedPrimary, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { clipboard.setPrimaryClip(ClipData.newPlainText(item.name, completionFormatInsert(item))) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = RustedSecondary); Spacer(Modifier.width(4.dp)); Text("复制代码", fontSize = 11.sp, color = RustedSecondary) }
                    }
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = item.example,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RustedBackground).padding(10.dp),
                            fontSize = 12.sp,
                            fontFamily = AppCodeFontFamily,
                            color = RustedSecondary.copy(alpha = 0.9f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/** 顶部搜索条：极简圆角条，聚焦时主色描边，非空显示清除按钮 */
@Composable
private fun CodeRefSearchBar(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(RustedSurface)
            .border(1.dp, if (focused) RustedPrimary.copy(alpha = 0.6f) else RustedOnBackground.copy(alpha = 0.08f), shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.45f))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = RustedOnBackground),
            cursorBrush = SolidColor(RustedPrimary)
        )
        if (query.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(shape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.4f))
            }
        }
    }
}

/** 分类行（无边框）：图标 + 名称 + 数量徽章 + 旋转 chevron，点击展开/收起该分类条目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryDisclosureRow(category: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val nameColor by animateColorAsState(
        targetValue = if (pressed) RustedPrimary else RustedOnBackground,
        animationSpec = tween(120), label = "catName"
    )
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "catChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = RustedPrimary)
        Spacer(Modifier.width(10.dp))
        Text(category, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nameColor, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(RustedOnBackground.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text("$count", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.55f))
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp).rotate(chevronAngle), tint = RustedOnBackground.copy(alpha = 0.35f))
    }
}

/** 条目行（无边框）：类型色点 + 名称 + 描述，点击弹出详情 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompletionItemRow(item: CustomCompletion, isUser: Boolean, onClick: () -> Unit) {
    val tc = when (item.detail) { "bool" -> RustedPrimary; "float", "int" -> RustedSecondary; "string" -> RustedAccent; else -> RustedOnBackground.copy(alpha = 0.3f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val nameColor by animateColorAsState(
        targetValue = if (pressed) RustedPrimary else RustedOnBackground,
        animationSpec = tween(120), label = "itemName"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tc))
            Spacer(Modifier.width(8.dp))
            Text(item.displayPrimary(), fontSize = 13.sp, fontFamily = AppCodeFontFamily, color = nameColor, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = item.displaySecondary()
            if (secondary.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(secondary, fontSize = 11.sp, fontFamily = AppCodeFontFamily, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (isUser) Text("自定义", fontSize = 10.sp, color = RustedSecondary.copy(alpha = 0.6f))
        }
        if (item.desc.isNotEmpty()) {
            Text(item.desc, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        }
    }
}

/** 小胶囊标签 */
@Composable
private fun DetailChip(content: @Composable () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(RustedOnBackground.copy(alpha = 0.06f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        content()
    }
}

/** 展开分类时底部悬浮的「收起」胶囊 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingCollapsePill(category: String, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) RustedPrimary.copy(alpha = 0.16f) else RustedSurface,
        animationSpec = tween(120), label = "pillBg"
    )
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(bg)
            .border(1.dp, RustedOnBackground.copy(alpha = 0.10f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onCollapse)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(15.dp), tint = RustedPrimary)
        Spacer(Modifier.width(5.dp))
        Text("收起「$category」", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
    }
}
