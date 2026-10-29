package com.rwmodstudio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.ui.theme.*
import java.io.File
import kotlinx.coroutines.launch

private enum class ProjectTagCategory(val label: String) {
    TAGS("标签"),
    GLOBAL_TAGS("全局标签"),
    RESOURCES("资源"),
    GLOBAL_RESOURCES("全局资源"),
    MEMORIES("内存"),
    UNIT_NAMES("单位名")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagerScreen(
    rootPath: String,
    info: ProjectTagScanner.ProjectTagInfo?,
    loading: Boolean,
    categoryIndex: Int,
    selectedValue: String?,
    listState: LazyListState,
    onCategoryChange: (Int) -> Unit,
    onSelectedValueChange: (String?) -> Unit,
    onJumpToLine: (String, String, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val category = ProjectTagCategory.entries.getOrElse(categoryIndex) { ProjectTagCategory.TAGS }

    val items = when (category) {
        ProjectTagCategory.TAGS -> info?.tags?.toList() ?: emptyList()
        ProjectTagCategory.GLOBAL_TAGS -> info?.globalTags?.toList() ?: emptyList()
        ProjectTagCategory.RESOURCES -> info?.resources?.toList() ?: emptyList()
        ProjectTagCategory.GLOBAL_RESOURCES -> info?.globalResources?.toList() ?: emptyList()
        ProjectTagCategory.MEMORIES -> info?.memories?.toList() ?: emptyList()
        ProjectTagCategory.UNIT_NAMES -> info?.unitNames?.toList() ?: emptyList()
    }.sorted()

    val primaryTabIndex = ProjectTagCategory.entries.indexOf(category)

    // ===== 平板横屏：左边竖排分类、右边条目列表（竖排比一排滚动 Tab 更适合横屏）=====
    // 分栏只看「平板模式」开关，跟横竖屏无关：开着就左栏+右内容，关掉就是原来的单列
    val isSplit = com.rwmodstudio.core.SettingsManager.devTabletMode

    @Composable
    fun CategoryTabsCard() {
        ElevatedCard(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            ScrollableTabRow(
                selectedTabIndex = primaryTabIndex,
                containerColor = Color.Transparent,
                contentColor = RustedPrimary,
                edgePadding = 4.dp,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                ProjectTagCategory.entries.forEachIndexed { index, cat ->
                    Tab(
                        selected = category == cat,
                        onClick = {
                            onCategoryChange(index)
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        text = {
                            Text(
                                cat.label,
                                fontSize = 12.sp,
                                fontWeight = if (category == cat) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.height(44.dp).padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun ValueList() {
        Box(Modifier.fillMaxSize()) {
            if (loading || info == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RustedPrimary, strokeWidth = 2.dp)
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无${category.label}",
                        fontSize = 14.sp,
                        color = RustedOnBackground.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState
                ) {
                    items(items, key = { "${category.name}:$it" }) { value ->
                        val expanded = selectedValue == value
                        // 统一 MT 管理器扁平列表风格：无边框、无圆角、透明底，仅选中时淡色高亮
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { onSelectedValueChange(if (expanded) null else value) },
                            shape = RoundedCornerShape(0.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (expanded) RustedPrimary.copy(alpha = 0.08f) else Color.Transparent
                            ),
                            border = if (expanded) BorderStroke(1.dp, RustedPrimary.copy(alpha = 0.4f)) else null
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        value,
                                        fontSize = 14.sp,
                                        fontWeight = if (expanded) FontWeight.Medium else FontWeight.Normal,
                                        color = RustedOnBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = RustedOnBackground.copy(alpha = 0.4f)
                                    )
                                }
                                if (expanded) {
                                    val refs = info.references[value] ?: emptyList()
                                    if (refs.isEmpty()) {
                                        Text(
                                            "无引用位置",
                                            fontSize = 12.sp,
                                            color = RustedOnBackground.copy(alpha = 0.4f),
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    } else {
                                        Spacer(Modifier.height(8.dp))
                                        Column(Modifier.fillMaxWidth()) {
                                            refs.take(5).forEachIndexed { idx, ref ->
                                                val rel = try {
                                                    ref.file.relativeTo(File(rootPath)).path
                                                } catch (_: Exception) { ref.file.name }
                                                Text(
                                                    text = "$rel:${ref.line}",
                                                    fontSize = 12.sp,
                                                    color = RustedPrimary,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            onJumpToLine(ref.file.name, ref.file.absolutePath, ref.line)
                                                        }
                                                        .padding(vertical = 6.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (idx < refs.take(5).size - 1) {
                                                    HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.06f))
                                                }
                                            }
                                            if (refs.size > 5) {
                                                Text(
                                                    "等 ${refs.size} 处引用",
                                                    fontSize = 12.sp,
                                                    color = RustedOnBackground.copy(alpha = 0.4f),
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
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

    if (isSplit) {
        Row(Modifier.fillMaxSize().background(RustedBackground)) {
            // 左：竖排分类
            Column(
                Modifier
                    .width(168.dp)
                    .fillMaxHeight()
                    .background(RustedSurface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Text(
                    "分类",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = RustedPrimary.copy(alpha = 0.7f)
                )
                ProjectTagCategory.entries.forEachIndexed { index, cat ->
                    val selected = category == cat
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) RustedPrimary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                onCategoryChange(index)
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cat.label,
                            fontSize = 13.sp,
                            color = if (selected) RustedPrimary else RustedOnBackground,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { ValueList() }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(RustedBackground)
                .padding(horizontal = 12.dp)
        ) {
            CategoryTabsCard()
            ValueList()
        }
    }
}
