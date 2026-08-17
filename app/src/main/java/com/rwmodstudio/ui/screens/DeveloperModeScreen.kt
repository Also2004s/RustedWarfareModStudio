package com.rwmodstudio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.theme.*

@Composable
fun DeveloperModeScreen(onOpenCompletionViewer: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionHeader(Icons.Default.Code, "代码补全")
        SettingsCard {
            var completionProvider by remember { mutableStateOf(SettingsManager.devCompletionProvider) }
            var valueCompletion by remember { mutableStateOf(SettingsManager.devValueCompletion) }
            var nonValueLimited by remember { mutableStateOf(SettingsManager.nonValueCompletionLimited) }

            DevSwitchCompact(
                icon = Icons.Default.Extension,
                title = "补全提供器",
                checked = completionProvider,
                onCheckedChange = { completionProvider = it; SettingsManager.devCompletionProvider = it },
                isFirst = true
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Checklist,
                title = "值自动补全",
                checked = valueCompletion,
                onCheckedChange = { valueCompletion = it; SettingsManager.devValueCompletion = it }
            )
            AnimatedVisibility(visible = valueCompletion) {
                Column {
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.ToggleOn,
                        title = "布尔值",
                        checked = SettingsManager.devValueCompletionBool,
                        onCheckedChange = { SettingsManager.devValueCompletionBool = it },
                        isChild = true
                    )
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.Rule,
                        title = "逻辑布尔",
                        checked = SettingsManager.devValueCompletionLogicBoolean,
                        onCheckedChange = { SettingsManager.devValueCompletionLogicBoolean = it },
                        isChild = true
                    )
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.List,
                        title = "枚举值",
                        checked = SettingsManager.devValueCompletionEnum,
                        onCheckedChange = { SettingsManager.devValueCompletionEnum = it },
                        isChild = true
                    )
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.Image,
                        title = "图片路径",
                        checked = SettingsManager.devValueCompletionImage,
                        onCheckedChange = { SettingsManager.devValueCompletionImage = it },
                        isChild = true
                    )
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.Construction,
                        title = "单位生成",
                        checked = SettingsManager.devValueCompletionUnitSpawn,
                        onCheckedChange = { SettingsManager.devValueCompletionUnitSpawn = it },
                        isChild = true
                    )
                    DevDivider(startIndent = 40.dp)
                    DevSwitchCompact(
                        icon = Icons.Default.Bolt,
                        title = "事件触发",
                        checked = SettingsManager.devValueCompletionAutoTriggerOnEvent,
                        onCheckedChange = { SettingsManager.devValueCompletionAutoTriggerOnEvent = it },
                        isChild = true,
                        isLast = true
                    )
                }
            }
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.FilterAlt,
                title = "非值限制补全",
                checked = nonValueLimited,
                onCheckedChange = { nonValueLimited = it; SettingsManager.nonValueCompletionLimited = it }
            )
            DevDivider()
            DevActionRow(
                icon = Icons.Default.Visibility,
                title = "查看补全项",
                subtitle = "查看各属性可调用的补全值",
                isLast = true,
                onClick = onOpenCompletionViewer
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Language, "解析与翻译")
        SettingsCard {
            DevSwitchCompact(
                icon = Icons.Default.Segment,
                title = "节名解析",
                checked = SettingsManager.devSectionParsing,
                onCheckedChange = { SettingsManager.devSectionParsing = it },
                isFirst = true
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Translate,
                title = "翻译引擎",
                checked = SettingsManager.devTranslationEngine,
                onCheckedChange = { SettingsManager.devTranslationEngine = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.SpaceBar,
                title = "翻译自动空格",
                checked = SettingsManager.autoSpace,
                onCheckedChange = { SettingsManager.autoSpace = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.BugReport,
                title = "Debug 任务进度",
                checked = SettingsManager.devDebugTaskProgress,
                onCheckedChange = { SettingsManager.devDebugTaskProgress = it },
                isLast = true
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Visibility, "界面显示")
        SettingsCard {
            DevSwitchCompact(
                icon = Icons.Default.FormatListNumbered,
                title = "行号显示",
                checked = SettingsManager.devLineNumber,
                onCheckedChange = { SettingsManager.devLineNumber = it },
                isFirst = true
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Bookmark,
                title = "节名显示栏",
                checked = SettingsManager.devSectionBar,
                onCheckedChange = { SettingsManager.devSectionBar = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Lightbulb,
                title = "行尾灯泡",
                checked = SettingsManager.devLightbulbEnabled,
                onCheckedChange = { SettingsManager.devLightbulbEnabled = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Tab,
                title = "标签栏",
                checked = SettingsManager.devTabBar,
                onCheckedChange = { SettingsManager.devTabBar = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.GridOn,
                title = "坐标可视化",
                checked = SettingsManager.devCoordVisual,
                onCheckedChange = { SettingsManager.devCoordVisual = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.ContentCopy,
                title = "显示复制路径菜单",
                checked = SettingsManager.devShowCopyPath,
                onCheckedChange = { SettingsManager.devShowCopyPath = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.AccountTree,
                title = "继承链查看",
                checked = SettingsManager.devInheritanceView,
                onCheckedChange = { SettingsManager.devInheritanceView = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.SubdirectoryArrowRight,
                title = "软换行提示符",
                checked = SettingsManager.wrapIndicatorEnabled,
                onCheckedChange = { SettingsManager.wrapIndicatorEnabled = it },
                isLast = true
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Save, "保存功能")
        SettingsCard {
            DevSwitchCompact(
                icon = Icons.Default.Save,
                title = "后台暂停保存",
                checked = SettingsManager.devSaveOnPause,
                onCheckedChange = { SettingsManager.devSaveOnPause = it },
                isFirst = true,
                isLast = true
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Folder, "文件操作")
        SettingsCard {
            DevSwitchCompact(
                icon = Icons.Default.FolderOpen,
                title = "文件加载",
                checked = SettingsManager.devFileLoading,
                onCheckedChange = { SettingsManager.devFileLoading = it },
                isFirst = true
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.History,
                title = "最近文件",
                checked = SettingsManager.devRecentFiles,
                onCheckedChange = { SettingsManager.devRecentFiles = it },
                isLast = true
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DevSwitchCompact(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isChild: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false
) {
    var currentValue by remember(checked) { mutableStateOf(checked) }
    val verticalPadding = if (isFirst) 10.dp else if (isLast) 10.dp else 8.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }
            )
            .clickable { currentValue = !currentValue; onCheckedChange(!currentValue) }
            .padding(start = if (isChild) 16.dp else 12.dp, end = 12.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChild) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(RustedPrimary.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(10.dp))
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = RustedOnBackground.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            color = RustedOnBackground,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = currentValue,
            onCheckedChange = { currentValue = it; onCheckedChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = RustedPrimary,
                checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun DevDivider(startIndent: androidx.compose.ui.unit.Dp = 46.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = 12.dp),
        thickness = 0.5.dp,
        color = RustedOnBackground.copy(alpha = 0.08f)
    )
}

/** 开发模式动作行（非开关）：图标 + 标题 + 副标题 + 右箭头，点击触发 onClick */
@Composable
private fun DevActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = RustedOnBackground.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = RustedOnBackground
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.45f)
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = RustedOnBackground.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}
