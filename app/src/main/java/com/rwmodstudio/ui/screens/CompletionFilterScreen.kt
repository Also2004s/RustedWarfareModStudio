package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SectionCompletionConfig
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.feature.completion.mapSectionName
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary

/**
 * 补全筛选（开发者模式入口）：第一层选节，第二层勾选该节启用的补全分类。
 * 与编辑器无关，直接用 SettingsManager 的节筛选配置，进出编辑器都生效。
 */
@Composable
fun CompletionFilterScreen() {
    var filters by remember { mutableStateOf(SettingsManager.loadAllSectionFilters()) }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    var allCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        allCategories = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                (loadNativeItems().flatMap { it.category } + loadUserItems().flatMap { it.category })
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList())
        }
    }
    val sections = remember(filters) {
        (SectionCompletionConfig.entries().map { mapSectionName(it.en) } +
                SettingsManager.DEFAULT_SECTION_FILTERS.keys +
                filters.keys)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
    ) {
        val sectionKey = selectedSection
        if (sectionKey == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选择节后配置分类",
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    SettingsManager.resetSectionFilters()
                    filters = SettingsManager.loadAllSectionFilters()
                }) { Text("重置默认", fontSize = 12.sp, color = RustedPrimary) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(sections) { section ->
                    val filter = filters[section]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedSection = section }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(section, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (filter != null) {
                            Text("${filter.size}项已启用", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                        } else {
                            Text("默认启用", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.3f))
                        }
                        Icon(
                            Icons.Default.ChevronRight, null,
                            Modifier.width(18.dp).height(18.dp),
                            tint = RustedOnBackground.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        } else {
            val currentFilter = filters[sectionKey]
            val defaultForSection = SettingsManager.DEFAULT_SECTION_FILTERS[sectionKey]
                ?.filter { it in allCategories }?.toSet()
            val initialEnabled = currentFilter ?: defaultForSection ?: allCategories.toSet()
            LaunchedEffect(sectionKey) {
                if (currentFilter == null) {
                    val toSave = defaultForSection ?: allCategories.toSet()
                    SettingsManager.saveSectionFilter(sectionKey, toSave)
                    filters = SettingsManager.loadAllSectionFilters()
                }
            }
            var enabledCats by remember(sectionKey) { mutableStateOf(initialEnabled) }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedSection = null }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回",
                        tint = RustedOnBackground
                    )
                }
                Text("[$sectionKey] 节 - 分类筛选", fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    enabledCats = allCategories.toSet()
                    SettingsManager.saveSectionFilter(sectionKey, enabledCats)
                    filters = SettingsManager.loadAllSectionFilters()
                }) { Text("全选", fontSize = 11.sp) }
                TextButton(onClick = {
                    enabledCats = emptySet()
                    SettingsManager.saveSectionFilter(sectionKey, emptySet())
                    filters = SettingsManager.loadAllSectionFilters()
                }) { Text("取消全选", fontSize = 11.sp) }
            }
            if (allCategories.isEmpty()) {
                Text(
                    "暂无补全分类",
                    fontSize = 13.sp,
                    color = RustedOnBackground.copy(alpha = 0.4f),
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(allCategories) { cat ->
                        val isEnabled = cat in enabledCats
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val newSet = enabledCats.toMutableSet()
                                    if (isEnabled) newSet.remove(cat) else newSet.add(cat)
                                    enabledCats = newSet
                                    SettingsManager.saveSectionFilter(sectionKey, newSet)
                                    filters = SettingsManager.loadAllSectionFilters()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isEnabled, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(cat, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
