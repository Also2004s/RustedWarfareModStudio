package com.rwmodstudio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.rwmodstudio.BuildConfig
import com.rwmodstudio.core.DarkThemeColors
import com.rwmodstudio.core.LocalConfigManager
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.UpdateChecker
import com.rwmodstudio.core.translation.TranslationBlocklist
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.components.ColorWheelPicker
import com.rwmodstudio.ui.components.DarkTokenColorDialog
import com.rwmodstudio.ui.components.UpdateDialogHost
import com.rwmodstudio.ui.components.UpdateDialogState
import com.rwmodstudio.ui.theme.fontFamilyDisplayName
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.uriToAbsolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 把字体大小规整到 0.5 的倍数 */
private fun roundFontSize(size: Float): Float = (size * 2f).roundToInt() / 2f

@Composable
fun SettingsScreen(
    autoWrap: Boolean, onAutoWrapChange: (Boolean) -> Unit,
    smartWrap: Boolean, onSmartWrapChange: (Boolean) -> Unit,
    defaultPath: String, onDefaultPathChange: (String) -> Unit,
    onNavigateToDeveloper: () -> Unit = {}
) {
    var path by remember { mutableStateOf(defaultPath) }
    var bg by remember { mutableStateOf(ThemeState.bgColor) }
    var hl by remember { mutableStateOf(ThemeState.highlightTheme) }
    var darkTokens by remember { mutableStateOf(ThemeState.darkTokenColors) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showDarkHlPicker by remember { mutableStateOf(false) }
    var editorFontFamily by remember { mutableStateOf(SettingsManager.editorFontFamily) }
    var fontSize by remember { mutableFloatStateOf(SettingsManager.fontSize) }
    var showFontPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val engine = remember { TranslationEngine.getInstance() }
    var blockEnabled by remember { mutableStateOf(SettingsManager.translationBlockEnabled) }
    var blockKeys by remember { mutableStateOf(TranslationBlocklist.DEFAULT_BLOCK_KEYS) }
    var localBlockKeys by remember { mutableStateOf(blockKeys) }
    var blockVariables by remember { mutableStateOf(true) }
    var blockAtTokens by remember { mutableStateOf(true) }
    var blockFileNames by remember { mutableStateOf(true) }
    var blockQuotedDictWords by remember { mutableStateOf(false) }
    var forcePercentVariables by remember { mutableStateOf(true) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showInternalFilterDialog by remember { mutableStateOf(false) }
    var showPickFromDict by remember { mutableStateOf(false) }
    var showLocalConfigSearch by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var localConfigMessage by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }
    val updateScope = rememberCoroutineScope()

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val absolute = uriToAbsolutePath(context, it)
            if (!absolute.isNullOrEmpty()) {
                path = absolute
                onDefaultPathChange(absolute)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val zipPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            lifecycleOwner.lifecycleScope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val tempFile = RwmodPaths.importConfigTempFile
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                        val ok = LocalConfigManager.importFromZip(context, tempFile)
                        localConfigMessage = if (ok) "配置已导入，请重启应用以生效" else "导入失败"
                    } ?: run {
                        localConfigMessage = "无法读取文件"
                    }
                } catch (e: Exception) {
                    localConfigMessage = "导入失败: ${e.message}"
                }
                showRestartDialog = true
            }
        }
    }

    val es = android.os.Environment.getExternalStorageDirectory()

    // 加载当前屏蔽词配置
    LaunchedEffect(Unit) {
        val bl = TranslationBlocklist.load(context)
        blockKeys = bl.keys
        localBlockKeys = bl.keys
        blockVariables = bl.blockVariables
        blockAtTokens = bl.blockAtTokens
        blockFileNames = bl.blockFileNames
        blockQuotedDictWords = bl.blockQuotedDictWords
        forcePercentVariables = bl.forcePercentVariables
    }

    // 打开屏蔽词弹窗时同步当前列表和子开关
    LaunchedEffect(showBlockDialog) {
        if (showBlockDialog) {
            localBlockKeys = blockKeys
            val bl = TranslationBlocklist.load(context)
            blockVariables = bl.blockVariables
            blockAtTokens = bl.blockAtTokens
            blockFileNames = bl.blockFileNames
            blockQuotedDictWords = bl.blockQuotedDictWords
            forcePercentVariables = bl.forcePercentVariables
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ===== 编辑器 =====
        SectionHeader(Icons.Default.Edit, "编辑器")
        SettingsCard {
            var completionDetail by remember { mutableStateOf(SettingsManager.completionDetailEnabled) }
            var sectionCompletion by remember { mutableStateOf(SettingsManager.devSectionCompletion) }
            var rainbowBrackets by remember { mutableStateOf(SettingsManager.rainbowBrackets) }
            var bracketDiagnostics by remember { mutableStateOf(SettingsManager.bracketDiagnostics) }

            SettingSlider(
                icon = Icons.Default.FormatSize,
                title = "字体大小",
                value = fontSize,
                onValueChange = {
                    fontSize = roundFontSize(it)
                    SettingsManager.fontSize = fontSize
                },
                valueRange = 10f..24f,
                steps = 27,
                valueText = "${fontSize.toInt()}sp"
            )
            SettingSwitch(
                icon = Icons.Default.WrapText,
                title = "自动换行",
                subtitle = "默认打开",
                checked = autoWrap,
                onCheckedChange = onAutoWrapChange
            )
            if (autoWrap) {
                SettingSwitch(
                    icon = Icons.Default.WrapText,
                    title = "智能换行（逻辑断点）",
                    subtitle = "在 \\n、and/or、括号组、, * 前断行",
                    checked = smartWrap,
                    onCheckedChange = onSmartWrapChange
                )
            }
            SettingClickable(
                icon = Icons.Default.FontDownload,
                title = "编辑器字体",
                subtitle = fontFamilyDisplayName(editorFontFamily),
                onClick = { showFontPicker = true }
            )
            SettingSwitch(
                icon = Icons.Default.Notes,
                title = "自动补全说明",
                subtitle = if (completionDetail) "显示说明" else "仅显示关键词",
                checked = completionDetail,
                onCheckedChange = {
                    completionDetail = it
                    SettingsManager.completionDetailEnabled = it
                }
            )
            SettingSwitch(
                icon = Icons.Default.SpaceBar,
                title = "节补全",
                subtitle = if (sectionCompletion) "节内空行自动弹出补全" else "关闭",
                checked = sectionCompletion,
                onCheckedChange = {
                    sectionCompletion = it
                    SettingsManager.devSectionCompletion = it
                }
            )
            SettingSwitch(
                icon = Icons.Default.Palette,
                title = "彩虹括号",
                subtitle = if (rainbowBrackets) "按嵌套深度循环上色" else "关闭",
                checked = rainbowBrackets,
                onCheckedChange = {
                    rainbowBrackets = it
                    SettingsManager.rainbowBrackets = it
                }
            )
            SettingSwitch(
                icon = Icons.Default.Code,
                title = "括号诊断",
                subtitle = if (bracketDiagnostics) "标记未匹配的括号" else "关闭",
                checked = bracketDiagnostics,
                onCheckedChange = {
                    bracketDiagnostics = it
                    SettingsManager.bracketDiagnostics = it
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ===== 外观 =====
        SectionHeader(Icons.Default.ColorLens, "外观")
        SettingsCard {
            val canEditHl = hl == "custom"

            // 背景颜色
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showColorPicker = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(parseHex(bg))
                        .border(1.dp, RustedOnBackground.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = "背景颜色", fontSize = 14.sp, color = RustedOnBackground)
                    Text(text = bg.uppercase(), fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                }
                OutlinedButton(onClick = { showColorPicker = true }) { Text("调色盘", fontSize = 12.sp) }
            }

            // 高亮自定义
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canEditHl) { showDarkHlPicker = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Highlight, null, tint = RustedOnBackground.copy(alpha = if (canEditHl) 0.7f else 0.3f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = "高亮自定义", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = if (canEditHl) 1f else 0.5f))
                    if (!canEditHl) {
                        Text(text = "仅自定义主题可调整", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    } else {
                        ColorPreviewBar(darkTokens)
                    }
                }
                OutlinedButton(onClick = { showDarkHlPicker = true }, enabled = canEditHl) { Text("调色盘", fontSize = 12.sp) }
            }

            // 高亮主题
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Style, null, tint = RustedOnBackground.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(text = "高亮主题", fontSize = 14.sp, color = RustedOnBackground, modifier = Modifier.width(64.dp))
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("dark" to "深色", "light" to "浅色", "pure" to "纯净", "custom" to "自定义").forEach { (t, l) ->
                        ThemeChip(
                            label = l,
                            selected = hl == t,
                            onClick = {
                                hl = t
                                ThemeState.applyHighlightTheme(t)
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 翻译 =====
        SectionHeader(Icons.Default.Translate, "翻译")
        SettingsCard {
            var autoRefresh by remember { mutableStateOf(SettingsManager.autoRefreshCompletionsOnTranslationSave) }

            SettingSwitch(
                icon = Icons.Default.FilterAlt,
                title = "翻译屏蔽词",
                subtitle = "过滤不需翻译的键与片段",
                checked = blockEnabled,
                onCheckedChange = {
                    blockEnabled = it
                    SettingsManager.translationBlockEnabled = it
                    engine.setBlocklistEnabled(context, it)
                }
            )
            if (blockEnabled) {
                EmphasisCard(
                    title = "配置屏蔽词",
                    subtitle = "${blockKeys.size} 条",
                    icon = Icons.Default.Tune,
                    onClick = { showBlockDialog = true }
                )
            }
            SettingSwitch(
                icon = Icons.Default.Refresh,
                title = "保存翻译库后刷新补全表",
                subtitle = "根据英文补全表重新生成中文版",
                checked = autoRefresh,
                onCheckedChange = {
                    autoRefresh = it
                    SettingsManager.autoRefreshCompletionsOnTranslationSave = it
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ===== 本地配置 =====
        SectionHeader(Icons.Default.Storage, "本地配置")
        SettingsCard {
            val scope = rememberCoroutineScope()
            var exporting by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            exporting = true
                            val file = LocalConfigManager.exportToZip(context)
                            exporting = false
                            file?.let {
                                LocalConfigManager.shareZip(context, it)
                            } ?: run {
                                localConfigMessage = "导出失败"
                                showRestartDialog = true
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !exporting,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
                ) {
                    Text(if (exporting) "导出中..." else "导出", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { zipPickerLauncher.launch("application/zip") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("导入", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { showLocalConfigSearch = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("搜索", fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 文件 =====
        SectionHeader(Icons.Default.FolderOpen, "文件")
        SettingsCard {
            var recentLimit by remember { mutableIntStateOf(SettingsManager.recentHistoryLimit) }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = "默认文件夹", fontSize = 14.sp, color = RustedOnBackground)
                Text(text = "首页展示此文件夹", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDefaultPathChange(path) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
                    ) { Text(text = "保存", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            val d = File(es, "rustedWarfare/units")
                            if (d.exists()) {
                                path = d.absolutePath
                                onDefaultPathChange(d.absolutePath)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(text = "units", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(text = "选择", fontSize = 12.sp) }
                }
            }
            SettingSlider(
                title = "近期修改缓存数量",
                value = recentLimit.toFloat(),
                onValueChange = {
                    recentLimit = it.toInt()
                    SettingsManager.recentHistoryLimit = recentLimit
                },
                valueRange = 50f..200f,
                steps = 149,
                valueText = "$recentLimit 条"
            )
        }

        Spacer(Modifier.height(16.dp))

        // ===== 导入 =====
        SectionHeader(Icons.Default.Download, "导入")
        var importExpanded by remember { mutableStateOf(false) }
        SettingsCard {
            var replayImportDir by remember { mutableStateOf(SettingsManager.replayImportDir) }
            var rwmodImportDir by remember { mutableStateOf(SettingsManager.rwmodImportDir) }
            var mapImportDir by remember { mutableStateOf(SettingsManager.mapImportDir) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { importExpanded = !importExpanded }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (importExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = RustedOnBackground.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(visible = importExpanded) {
                Column {
                    ImportDirItem(
                        icon = Icons.Default.VideogameAsset,
                        title = ".replay 目录",
                        path = replayImportDir,
                        onPathChange = { replayImportDir = it },
                        onSave = { SettingsManager.replayImportDir = replayImportDir },
                        onReset = { replayImportDir = SettingsManager.defaultReplayImportDir(); SettingsManager.replayImportDir = replayImportDir }
                    )
                    ImportDirItem(
                        icon = Icons.Default.Extension,
                        title = ".rwmod 目录",
                        path = rwmodImportDir,
                        onPathChange = { rwmodImportDir = it },
                        onSave = { SettingsManager.rwmodImportDir = rwmodImportDir },
                        onReset = { rwmodImportDir = SettingsManager.defaultRwmodImportDir(); SettingsManager.rwmodImportDir = rwmodImportDir }
                    )
                    ImportDirItem(
                        icon = Icons.Default.Map,
                        title = ".tmx 目录",
                        path = mapImportDir,
                        onPathChange = { mapImportDir = it },
                        onSave = { SettingsManager.mapImportDir = mapImportDir },
                        onReset = { mapImportDir = SettingsManager.defaultMapImportDir(); SettingsManager.mapImportDir = mapImportDir }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ===== 关于 =====
        SectionHeader(Icons.Default.Info, "关于")
        SettingsCard {
            Info("版本", com.rwmodstudio.BuildConfig.VERSION_NAME)
            Info("翻译库", "989 条")
            Info("代码参考", "13,907 条")
            Info("QQ群", "1093529136")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "声明：代码取自 VS Code xingwangzhe 的 RustedWarfareModSupport 插件与群聊 319198864 的 NDT-v1.15 代码表",
                fontSize = 10.sp,
                color = RustedOnBackground.copy(alpha = 0.5f),
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(4.dp))
            EmphasisCard(
                title = "检查更新",
                subtitle = if (checkingUpdate) "检查中..." else "点击检查是否有新版本可用",
                icon = Icons.Default.SystemUpdate,
                onClick = {
                    if (!checkingUpdate) {
                        checkingUpdate = true
                        updateDialog = UpdateDialogState.Checking
                        updateScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val info = UpdateChecker.fetchLatest()
                                    if (UpdateChecker.isNewerThan(info.versionName, BuildConfig.VERSION_NAME)) {
                                        UpdateDialogState.Available(info)
                                    } else {
                                        UpdateDialogState.NoUpdate
                                    }
                                } catch (e: Exception) {
                                    UpdateDialogState.Error(e.message ?: "未知错误")
                                }
                            }
                            checkingUpdate = false
                            updateDialog = result
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ===== 开发者模式 =====
        SectionHeader(Icons.Default.Code, "开发者模式")
        SettingsCard {
            var devMode by remember { mutableStateOf(SettingsManager.devMode) }
            SettingSwitch(
                icon = Icons.Default.BugReport,
                title = "开启开发者模式",
                subtitle = "用于排查卡顿问题",
                checked = devMode,
                onCheckedChange = { devMode = it; SettingsManager.devMode = it }
            )
            if (devMode) {
                EmphasisCard(
                    title = "功能开关设置",
                    subtitle = "进入开发者详细设置",
                    icon = Icons.Default.Tune,
                    onClick = onNavigateToDeveloper
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showColorPicker) {
        var picked by remember { mutableStateOf(bg) }
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text("背景颜色") },
            text = {
                ColorWheelPicker(
                    initialColor = bg,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                    wheelSize = 220.dp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bg = picked
                    ThemeState.applyBgColor(picked)
                    showColorPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showColorPicker = false }) { Text("取消") }
            }
        )
    }

    if (showDarkHlPicker) {
        DarkTokenColorDialog(
            initialColors = darkTokens,
            onDismiss = { showDarkHlPicker = false },
            onConfirm = { colors ->
                darkTokens = colors
                ThemeState.applyDarkTokenColors(colors)
                showDarkHlPicker = false
            }
        )
    }

    if (showFontPicker) {
        AlertDialog(
            onDismissRequest = { showFontPicker = false },
            title = { Text("编辑器字体") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    items(FONT_FAMILY_OPTIONS.size) { index ->
                        val key = FONT_FAMILY_OPTIONS[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    editorFontFamily = key
                                    SettingsManager.editorFontFamily = key
                                    showFontPicker = false
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = editorFontFamily == key,
                                onClick = {
                                    editorFontFamily = key
                                    SettingsManager.editorFontFamily = key
                                    showFontPicker = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = RustedPrimary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(fontFamilyDisplayName(key), fontSize = 14.sp, color = RustedOnBackground)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFontPicker = false }) { Text("取消") }
            }
        )
    }

    if (showBlockDialog) {
        var showBlockSubDialog by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("翻译屏蔽词") },
            text = {
                Column(Modifier.height(460.dp)) {
                    Text("以下 key 对应的 value 不会被翻译。", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { showBlockSubDialog = true },
                        colors = CardDefaults.cardColors(containerColor = RustedSurface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, null, tint = RustedPrimary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("特殊内容屏蔽设置", fontSize = 13.sp, color = RustedOnBackground, fontWeight = FontWeight.Medium)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(localBlockKeys.size) { idx ->
                            val key = localBlockKeys[idx]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(RustedSurface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        localBlockKeys = localBlockKeys.toMutableList().apply { removeAt(idx) }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = RustedError) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { showPickFromDict = true },
                        colors = CardDefaults.cardColors(containerColor = RustedPrimary.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = RustedPrimary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("从翻译库选择", fontSize = 13.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val ok = engine.resetBlocklist(context)
                        if (ok) {
                            android.widget.Toast.makeText(context, "已重置为默认屏蔽词", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "重置屏蔽词失败", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        val bl = TranslationBlocklist.load(context)
                        blockKeys = bl.keys
                        localBlockKeys = bl.keys
                        blockVariables = bl.blockVariables
                        blockAtTokens = bl.blockAtTokens
                        blockFileNames = bl.blockFileNames
                        blockQuotedDictWords = bl.blockQuotedDictWords
                        forcePercentVariables = bl.forcePercentVariables
                    }) { Text("重置默认", color = RustedError) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        blockKeys = localBlockKeys
                        val ok1 = engine.updateBlocklistKeys(context, localBlockKeys)
                        val ok2 = engine.updateBlocklistFlags(
                            context,
                            blockVariables,
                            blockAtTokens,
                            blockFileNames,
                            blockQuotedDictWords,
                            forcePercentVariables
                        )
                        if (ok1 && ok2) {
                            android.widget.Toast.makeText(context, "屏蔽词保存成功", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "屏蔽词保存失败", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        showBlockDialog = false
                    }) { Text("保存") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("取消") }
            }
        )

        if (showBlockSubDialog) {
            AlertDialog(
                onDismissRequest = { showBlockSubDialog = false },
                title = { Text("特殊内容屏蔽") },
                text = {
                    Column {
                        Text("选择需要额外保护的文本片段类型。", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        BlockSwitch("\${} 变量", blockVariables) { blockVariables = it }
                        BlockSwitch("@xxx 指令", blockAtTokens) { blockAtTokens = it }
                        BlockSwitch("文件扩展名", blockFileNames) { blockFileNames = it }
                        BlockSwitch("引号内翻译库词", blockQuotedDictWords) { blockQuotedDictWords = it }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showInternalFilterDialog = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("内部过滤内容", fontSize = 14.sp, color = RustedOnBackground)
                                Text("开启后可无视屏蔽词强制翻译指定片段", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = RustedOnBackground.copy(alpha = 0.5f))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBlockSubDialog = false }) { Text("确定") }
                }
            )
        }
    }

    if (showInternalFilterDialog) {
        AlertDialog(
            onDismissRequest = { showInternalFilterDialog = false },
            title = { Text("内部过滤内容") },
            text = {
                Column {
                    Text("以下选项开启后，对应片段将无视屏蔽词强制参与翻译。", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    BlockSwitch("跳过%{}变量", forcePercentVariables) { forcePercentVariables = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInternalFilterDialog = false }) { Text("确定") }
            }
        )
    }

    if (showPickFromDict) {
        var pickSearch by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var dictKeys by remember { mutableStateOf(listOf<String>()) }
        var dictLoading by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            if (!engine.isLoaded) engine.load(context)
            val dict = engine.getTranslationDict()
            dictKeys = (dict.getAllEnglishKeys() + dict.getAllChineseKeys()).distinct().sorted()
            dictLoading = false
        }

        val available = remember(dictKeys, localBlockKeys, pickSearch) {
            dictKeys.filter { key ->
                if (key.isBlank()) return@filter false
                val translations = setOf(
                    engine.getTranslationDict().getTranslation(key),
                    engine.getTranslationDict().getTranslationBack(key)
                )
                if (localBlockKeys.any { it == key || it in translations }) return@filter false
                pickSearch.isEmpty() || key.contains(pickSearch, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = { showPickFromDict = false },
            title = { Text("从翻译库选择屏蔽词") },
            text = {
                Column(Modifier.height(400.dp)) {
                    OutlinedTextField(
                        value = pickSearch,
                        onValueChange = { pickSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索 key...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.4f)) },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("已选 ${selected.size} 项", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.weight(1f)) {
                        if (dictLoading) {
                            CircularProgressIndicator(color = RustedPrimary, modifier = Modifier.align(Alignment.Center))
                        } else if (available.isEmpty()) {
                            Text("没有可添加的 key", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn {
                                items(available.size) { idx ->
                                    val key = available[idx]
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                selected = if (key in selected) selected - key else selected + key
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = key in selected,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) selected + key else selected - key
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = RustedPrimary)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(key, fontSize = 12.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selected.isNotEmpty()) {
                        localBlockKeys = (localBlockKeys + selected).distinct()
                    }
                    showPickFromDict = false
                }) { Text("添加 (${selected.size})") }
            },
            dismissButton = {
                TextButton(onClick = { showPickFromDict = false }) { Text("取消") }
            }
        )
    }

    if (showLocalConfigSearch) {
        var zipFiles by remember { mutableStateOf(listOf<File>()) }
        var zipLoading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            zipFiles = LocalConfigManager.findConfigZipFiles(context)
            zipLoading = false
        }

        AlertDialog(
            onDismissRequest = { showLocalConfigSearch = false },
            title = { Text("选择本地配置压缩包") },
            text = {
                Box(Modifier.height(420.dp)) {
                    if (zipLoading) {
                        CircularProgressIndicator(color = RustedPrimary, modifier = Modifier.align(Alignment.Center))
                    } else if (zipFiles.isEmpty()) {
                        Text(
                            "未找到 zip 文件，请将 RWmod.zip 放入下载目录后重试",
                            fontSize = 13.sp,
                            color = RustedOnBackground.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn {
                            items(zipFiles.size) { idx ->
                                val file = zipFiles[idx]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            scope.launch {
                                                val ok = LocalConfigManager.importFromZip(context, file)
                                                localConfigMessage = if (ok) "配置已导入，请重启应用以生效" else "导入失败"
                                                showLocalConfigSearch = false
                                                showRestartDialog = true
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = RustedSurface),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(file.name, fontSize = 13.sp, color = RustedOnBackground, fontWeight = FontWeight.Medium)
                                        Text(file.parent ?: "", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLocalConfigSearch = false }) { Text("取消") }
            }
        )
    }

    if (showRestartDialog && localConfigMessage.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("提示") },
            text = { Text(localConfigMessage, fontSize = 14.sp, color = RustedOnBackground) },
            confirmButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("确定") }
            }
        )
    }

    UpdateDialogHost(
        state = updateDialog,
        onStateChange = { updateDialog = it }
    )
}

@Composable
internal fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = RustedPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RustedPrimary)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RustedSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun SettingItem(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = RustedOnBackground.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(34.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = RustedOnBackground.copy(alpha = if (enabled) 1f else 0.5f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.45f)
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun SettingSwitch(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) }
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RustedPrimary,
                checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun SettingSlider(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    trailingAction: @Composable (RowScope.() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = RustedOnBackground.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, color = RustedOnBackground)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                }
                Text(text = valueText, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = if (subtitle != null) 0.35f else 0.45f))
            }
            trailingAction?.invoke(this)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingClickable(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    SettingItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick
    ) {
        Icon(Icons.Default.ChevronRight, null, tint = RustedOnBackground.copy(alpha = 0.35f))
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) RustedPrimary else RustedOnBackground.copy(alpha = 0.08f)
    val text = if (selected) androidx.compose.ui.graphics.Color.White else RustedOnBackground
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = text, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun ColorPreviewBar(colors: DarkThemeColors) {
    val categories = listOf(
        "section" to colors.section,
        "keyword" to colors.keyword,
        "value" to colors.value,
        "string" to colors.string,
        "boolean" to colors.boolean,
        "number" to colors.number,
        "comment" to colors.comment,
        "function" to colors.function,
        "type" to colors.type,
        "variable" to colors.variable,
        "bracket" to colors.bracket,
        "memory" to colors.memory,
        "logical" to colors.logical,
        "operator" to colors.operator
    )
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        categories.forEach { (_, color) ->
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(parseHex(color))
                    .border(0.5.dp, RustedOnBackground.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun EmphasisCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RustedPrimary.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = RustedPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontSize = 13.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = RustedPrimary)
        }
    }
}

@Composable
private fun ImportDirItem(
    icon: ImageVector,
    title: String,
    path: String,
    onPathChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = RustedOnBackground.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 13.sp, color = RustedOnBackground)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = path,
            onValueChange = onPathChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp)
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
            ) { Text("保存", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)
            ) { Text("恢复默认", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = k, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.45f))
        Text(text = v, fontSize = 12.sp, color = RustedOnBackground)
    }
}

@Composable
private fun BlockSwitch(label: String, value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = RustedOnBackground)
        Switch(
            checked = value,
            onCheckedChange = onValueChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RustedPrimary,
                checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
            )
        )
    }
}
