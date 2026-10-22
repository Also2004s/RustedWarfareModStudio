package com.rwmodstudio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.theme.*

@Composable
fun DeveloperModeScreen(
    onOpenMockIni: () -> Unit = {},
    onOpenCompletionTypes: () -> Unit = {},
    onOpenHighlightRules: () -> Unit = {},
    onOpenSortCustomization: () -> Unit = {}
) {
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
            var valueCompletionExpanded by rememberSaveable { mutableStateOf(false) }
            var nonValueLimited by remember { mutableStateOf(SettingsManager.nonValueCompletionLimited) }

            DevSwitchCompact(
                icon = Icons.Default.Extension,
                title = "补全提供器",
                checked = completionProvider,
                onCheckedChange = { completionProvider = it; SettingsManager.devCompletionProvider = it },
                isFirst = true
            )
            DevDivider()
            DevExpandableSwitch(
                icon = Icons.Default.Checklist,
                title = "值自动补全",
                checked = valueCompletion,
                onCheckedChange = { valueCompletion = it; SettingsManager.devValueCompletion = it },
                expanded = valueCompletionExpanded,
                onExpandedChange = { valueCompletionExpanded = it }
            )
            AnimatedVisibility(visible = valueCompletion && valueCompletionExpanded) {
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
                icon = Icons.Default.Description,
                title = "模拟ini",
                subtitle = "按补全类型归类的模拟 ini 文件",
                onClick = onOpenMockIni
            )
            DevDivider()
            DevActionRow(
                icon = Icons.Default.Category,
                title = "补全类型分类",
                subtitle = "按实际补全代码归类各属性，可改分类",
                onClick = onOpenCompletionTypes
            )
            DevDivider()
            DevActionRow(
                icon = Icons.Default.Rule,
                title = "规则",
                subtitle = "自定义高亮规则：按「类别：规则」给任意关键字/正则上色",
                onClick = onOpenHighlightRules
            )
            DevDivider()
            DevActionRow(
                icon = Icons.Default.Sort,
                title = "排序自定义",
                subtitle = "按真实使用频次把高频键置顶；可自行增删调序（只调顺序，不增删补全项）",
                isLast = true,
                onClick = onOpenSortCustomization
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
                icon = Icons.Default.Notes,
                title = "自动补全说明",
                checked = SettingsManager.completionDetailEnabled,
                onCheckedChange = { SettingsManager.completionDetailEnabled = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Translate,
                title = "英文名显示",
                subtitle = "补全项说明位显示英文名（副名）；默认关闭，只显示中文说明",
                checked = SettingsManager.completionShowEnglishName,
                onCheckedChange = { SettingsManager.completionShowEnglishName = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.SpaceBar,
                title = "节补全",
                checked = SettingsManager.devSectionCompletion,
                onCheckedChange = { SettingsManager.devSectionCompletion = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Translate,
                title = "翻译自动空格",
                checked = SettingsManager.autoSpace,
                onCheckedChange = { SettingsManager.autoSpace = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Check,
                title = "完全匹配翻译",
                subtitle = "补全表：无符号分割的英文/中文视为整体，仅整段命中才翻译（如 priceCredits 不再拆译）",
                checked = SettingsManager.devFullMatchTranslation,
                onCheckedChange = { SettingsManager.devFullMatchTranslation = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.BugReport,
                title = "Debug 任务进度",
                checked = SettingsManager.devDebugTaskProgress,
                onCheckedChange = { SettingsManager.devDebugTaskProgress = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.RocketLaunch,
                title = "多线程",
                subtitle = "并行：缓存预热/项目扫描/搜索/版本对比",
                checked = SettingsManager.devParallelPreheat,
                onCheckedChange = { SettingsManager.devParallelPreheat = it },
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
                icon = Icons.Default.Palette,
                title = "HSV 取色盘",
                checked = SettingsManager.devHsvPicker,
                onCheckedChange = { SettingsManager.devHsvPicker = it }
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
                icon = Icons.Default.VerticalAlignTop,
                title = "顶部浮层滚动",
                checked = SettingsManager.devTopBarsFold,
                onCheckedChange = { SettingsManager.devTopBarsFold = it }
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
                onCheckedChange = { SettingsManager.wrapIndicatorEnabled = it }
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Save, "保存功能")
        SettingsCard {
            DevSwitchCompact(
                icon = Icons.Default.Save,
                title = "后台暂停保存",
                subtitle = "关闭后，即使开启自动保存，退到后台也不会自动保存（可能丢最后一次修改）",
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
                onCheckedChange = { SettingsManager.devRecentFiles = it }
            )
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.Splitscreen,
                title = "仿MT目录",
                subtitle = "文件浏览器左右分栏：左=第一空间，右=第二空间，图标文字变小",
                checked = SettingsManager.devMtBrowserLayout,
                onCheckedChange = { SettingsManager.devMtBrowserLayout = it },
                isLast = true
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.SmartToy, "AI 助手")
        SettingsCard {
            var aiBaseUrl by remember { mutableStateOf(SettingsManager.aiBaseUrl) }
            // 仅当用户自定义过 Key 时才回显明文；使用内置默认 Key 时一律隐藏、不可查看
            var keyIsUserSet by remember { mutableStateOf(SettingsManager.isAiApiKeyUserSet()) }
            // keyIsUserSet 变化时重新读取模型（默认密钥下 getter 强制返回锁定模型）
            var aiModel by remember(keyIsUserSet) { mutableStateOf(SettingsManager.aiModel) }
            var aiApiKey by remember { mutableStateOf(if (keyIsUserSet) SettingsManager.aiApiKey else "") }
            var showKey by rememberSaveable { mutableStateOf(false) }

            DevTextField(
                icon = Icons.Default.Link,
                label = "服务地址",
                value = aiBaseUrl,
                onValueChange = { aiBaseUrl = it; SettingsManager.aiBaseUrl = it },
                placeholder = "https://.../v1",
                isFirst = true
            )
            DevDivider()
            DevTextField(
                icon = Icons.Default.ModelTraining,
                label = "模型",
                value = aiModel,
                onValueChange = { aiModel = it; SettingsManager.aiModel = it },
                placeholder = "sensenova-6.8-flash-lite",
                enabled = keyIsUserSet
            )
            DevDivider()
            DevTextField(
                icon = Icons.Default.VpnKey,
                label = "API Key",
                value = aiApiKey,
                onValueChange = {
                    aiApiKey = it
                    if (it.isBlank()) {
                        // 清空 = 清除用户自定义，回退默认 Key
                        SettingsManager.clearAiApiKey()
                        keyIsUserSet = false
                        showKey = false
                    } else {
                        SettingsManager.aiApiKey = it
                        keyIsUserSet = true
                    }
                },
                placeholder = if (keyIsUserSet) "sk-..." else "使用内置默认密钥（不可查看）",
                isPassword = true,
                passwordVisible = showKey,
                trailing = {
                    if (keyIsUserSet) {
                        IconButton(
                            onClick = { showKey = !showKey },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showKey) "隐藏" else "显示",
                                tint = RustedOnBackground.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                isLast = false
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock, null, Modifier.size(14.dp),
                    tint = RustedOnBackground.copy(alpha = 0.4f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (keyIsUserSet)
                        "自定义密钥密文存储；填入自定义 Key 后可自由配置模型，清空可恢复默认"
                    else
                        "内置默认密钥不可查看，且锁定 sensenova-6.8-flash-lite 模型；填入自定义 Key 后可配置模型",
                    fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f)
                )
            }
            DevDivider()
            DevSwitchCompact(
                icon = Icons.Default.SmartToy,
                title = "智能助理",
                subtitle = "近期弹窗内，以项目差异与待办为上下文对话，可维护待办",
                checked = SettingsManager.devAiAssistant,
                onCheckedChange = { SettingsManager.devAiAssistant = it },
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
    subtitle: String = "",
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

/** 可折叠的开发模式开关：右侧折叠箭头与开关独立点击，子项默认收起 */
@Composable
private fun DevExpandableSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
            .padding(start = 12.dp, end = 12.dp, top = verticalPadding, bottom = verticalPadding),
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
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = RustedOnBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
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

/** 开发模式文本输入行：图标 + 标签 + OutlinedTextField，圆角与 DevSwitchCompact 对齐 */
@Composable
private fun DevTextField(
    icon: ImageVector,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false
) {
    val verticalPadding = if (isFirst) 10.dp else if (isLast) 10.dp else 6.dp
    Column(
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
            .padding(start = 12.dp, end = 12.dp, top = verticalPadding, bottom = verticalPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = RustedOnBackground.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, color = RustedOnBackground)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            placeholder = { Text(placeholder, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.3f)) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = RustedOnBackground,
                fontFamily = AppCodeFontFamily
            ),
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            trailingIcon = trailing?.let { cb -> { cb() } },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RustedPrimary,
                unfocusedBorderColor = RustedOnBackground.copy(alpha = 0.2f),
                cursorColor = RustedPrimary
            )
        )
    }
}
