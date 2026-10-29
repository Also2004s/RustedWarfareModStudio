package com.rwmodstudio.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.AiService
import com.rwmodstudio.core.ProjectUnderstanding
import com.rwmodstudio.core.DiffOp
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.VersionComparator
import com.rwmodstudio.core.computeLineDiff
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VersionCompareScreen(
    defaultPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootDir by remember { mutableStateOf("") }
    var metaDir by remember { mutableStateOf("") }
    var comparing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VersionComparator.FolderDiffResult?>(null) }
    var selectedFileDiff by remember { mutableStateOf<VersionComparator.FileDiffResult?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    // AI 更新报告
    var aiReport by remember { mutableStateOf<String?>(null) }
    var aiGenerating by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var showReportDialog by remember { mutableStateOf(false) }
    // 生成阶段提示 + 流式输出累积文本
    var aiPhase by remember { mutableStateOf("") }
    var aiStreamText by remember { mutableStateOf("") }
    // 项目理解（新版）：对比完成后读取缓存展示，可手动让 AI 生成，可随时查看
    var understandingText by remember { mutableStateOf<String?>(null) }
    var understandingLoading by remember { mutableStateOf(false) }
    var understandingExpanded by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("AI 更新报告") }
    val clipboardManager = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        // 路径选择区：在一个树形弹窗中同时选择源文件夹和目标文件夹
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = RustedSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("旧版文件夹（源）", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Text(
                    if (rootDir.isEmpty()) "点击选择旧版文件夹" else rootDir,
                    fontSize = 13.sp,
                    color = if (rootDir.isEmpty()) RustedOnBackground.copy(alpha = 0.3f) else RustedOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = AppCodeFontFamily
                )
                Spacer(Modifier.height(10.dp))
                Text("新版文件夹（目标）", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Text(
                    if (metaDir.isEmpty()) "点击选择新版文件夹" else metaDir,
                    fontSize = 13.sp,
                    color = if (metaDir.isEmpty()) RustedOnBackground.copy(alpha = 0.3f) else RustedOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = AppCodeFontFamily
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showFolderPicker = true },
                    enabled = !comparing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary.copy(alpha = 0.85f))
                ) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择旧版和新版文件夹", fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (rootDir.isEmpty() || metaDir.isEmpty()) return@Button
                        comparing = true
                        result = null
                        scope.launch(Dispatchers.IO) {
                            val res = VersionComparator.compareFolders(File(rootDir), File(metaDir))
                            withContext(Dispatchers.Main) {
                                result = res
                                comparing = false
                            }
                        }
                    },
                    enabled = rootDir.isNotEmpty() && metaDir.isNotEmpty() && !comparing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
                ) {
                    if (comparing) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("开始对比", fontSize = 13.sp)
                }
            }
        }

        // 结果区
        result?.let { res ->
            // 对比完成后读取缓存的 AI 项目理解（不触发 AI 调用），有则直接展示
            LaunchedEffect(res, metaDir) {
                if (metaDir.isNotEmpty()) {
                    understandingLoading = true
                    val cached = withContext(Dispatchers.IO) {
                        ProjectUnderstanding.getCached(File(metaDir))
                    }
                    understandingLoading = false
                    understandingText = cached
                }
            }
            if (res.commonFiles.isEmpty() && res.rootOnlyFiles.isEmpty() && res.metaOnlyFiles.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("两个文件夹内容一致", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // 统计
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBadge("共同差异", res.commonFiles.size, RustedPrimary)
                            StatBadge("旧版独有", res.rootOnlyFiles.size, RustedSecondary)
                            StatBadge("新版独有", res.metaOnlyFiles.size, RustedAccent)
                        }
                    }
                    // AI 操作区：项目理解 / 更新报告并排一行，不再各占一整行
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 项目理解：未生成时由 AI 构建，已生成则直接打开对话框查看（命中缓存不重复调 AI）
                            Button(
                                onClick = {
                                    aiGenerating = true
                                    aiError = null
                                    aiReport = null
                                    aiStreamText = ""
                                    aiPhase = "正在扫描新版项目文件"
                                    dialogTitle = "AI 项目理解"
                                    showReportDialog = true
                                    scope.launch {
                                        // 流式增量节流：onChunk 高频触发（每 token 一次），若每 chunk 都 scope.launch
                                        // 切主线程会堆积大量 Main 消息导致 UI 卡顿。改为 100ms 合并一批批量更新。
                                        val pending = StringBuilder()
                                        var lastFlushAt = 0L
                                        fun flushPending() {
                                            val batch = pending.toString()
                                            pending.setLength(0)
                                            if (batch.isNotEmpty()) scope.launch { aiStreamText += batch }
                                        }
                                        val r = ProjectUnderstanding.getOrBuild(
                                            File(metaDir),
                                            // onPhase/onChunk 在 IO 线程触发，切主线程更新 Compose state
                                            onPhase = { p -> scope.launch { aiPhase = p } },
                                            onChunk = { c ->
                                                synchronized(pending) {
                                                    pending.append(c)
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastFlushAt >= 100) {
                                                        lastFlushAt = now
                                                        flushPending()
                                                    }
                                                }
                                            }
                                        )
                                        // 收尾：flush 最后一批不足 100ms 的残留增量
                                        synchronized(pending) { flushPending() }
                                        withContext(Dispatchers.Main) {
                                            aiGenerating = false
                                            aiPhase = ""
                                            r.fold(
                                                onSuccess = {
                                                    understandingText = it
                                                    aiReport = it
                                                    aiError = null
                                                },
                                                onFailure = { aiError = it.message ?: "生成失败" }
                                            )
                                        }
                                    }
                                },
                                enabled = !aiGenerating && !understandingLoading,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RustedSecondary)
                            ) {
                                if (understandingLoading || (aiGenerating && dialogTitle != "AI 更新报告")) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (understandingText == null) "AI 项目理解" else "查看项目理解",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 更新报告：未生成项目理解时先构建理解再出报告
                            Button(
                                onClick = {
                                    aiGenerating = true
                                    aiError = null
                                    aiReport = null
                                    aiStreamText = ""
                                    aiPhase = if (understandingText == null) "AI 正在构建项目理解" else "正在准备 AI 生成"
                                    dialogTitle = "AI 更新报告"
                                    showReportDialog = true
                                    scope.launch {
                                        // 复用已生成的项目理解；未生成时先让 AI 构建理解（同步展示在下方），再生成报告
                                        val understanding = understandingText ?: ProjectUnderstanding.getOrBuild(
                                            File(metaDir),
                                            onPhase = { p -> aiPhase = p },
                                            onChunk = { c -> aiStreamText += c }
                                        ).getOrElse { e ->
                                            withContext(Dispatchers.Main) {
                                                aiGenerating = false
                                                aiPhase = ""
                                                aiError = e.message ?: "生成失败"
                                            }
                                            return@launch
                                        }
                                        withContext(Dispatchers.Main) {
                                            understandingText = understanding
                                            aiStreamText = ""
                                        }
                                        val r = AiService.streamGenerateUpdateReport(
                                            diff = res,
                                            understanding = understanding,
                                            onPhase = { p -> aiPhase = p },
                                            onChunk = { c -> aiStreamText += c }
                                        )
                                        withContext(Dispatchers.Main) {
                                            aiGenerating = false
                                            aiPhase = ""
                                            r.fold(
                                                onSuccess = { aiReport = it; aiError = null },
                                                onFailure = { aiError = it.message ?: "生成失败" }
                                            )
                                        }
                                    }
                                },
                                enabled = !aiGenerating,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RustedSecondary)
                            ) {
                                if (aiGenerating && dialogTitle == "AI 更新报告") {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "AI 更新报告",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // 未生成时用一行小字说明（原来是一整段说明 + 一整行按钮）
                        if (understandingText == null && !understandingLoading) {
                            Text(
                                "项目理解：AI 扫描新版隐藏行动节，生成结果供更新报告复用",
                                fontSize = 10.sp,
                                color = RustedOnBackground.copy(alpha = 0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                            )
                        }
                    }
                    // 已生成的项目理解：标题行右侧切换展开，默认折叠
                    understandingText?.let { text ->
                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = RustedSurface),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "项目理解（新版）",
                                            fontSize = 11.sp,
                                            color = RustedPrimary.copy(alpha = 0.8f),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (understandingExpanded) "收起" else "展开全部",
                                            fontSize = 11.sp,
                                            color = RustedPrimary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { understandingExpanded = !understandingExpanded }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    SelectionContainer {
                                        Text(
                                            text,
                                            fontSize = 11.sp,
                                            color = RustedOnBackground.copy(alpha = 0.85f),
                                            fontFamily = AppCodeFontFamily,
                                            maxLines = if (understandingExpanded) Int.MAX_VALUE else 6,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 源独有文件
                    if (res.rootOnlyFiles.isNotEmpty()) {
                        item { SectionHeader("旧版独有文件") }
                        items(res.rootOnlyFiles) { path ->
                            DiffFileItem(name = path, type = '+', badge = "旧版独有", onClick = {})
                        }
                    }
                    // 新版独有文件
                    if (res.metaOnlyFiles.isNotEmpty()) {
                        item { SectionHeader("新版独有文件") }
                        items(res.metaOnlyFiles) { path ->
                            DiffFileItem(name = path, type = '-', badge = "新版独有", onClick = {})
                        }
                    }
                    // 共同文件差异
                    if (res.commonFiles.isNotEmpty()) {
                        item { SectionHeader("共同文件差异") }
                        items(res.commonFiles) { diff ->
                            DiffFileItem(
                                name = diff.filePath,
                                type = '~',
                                badge = "+${diff.addedCount} / -${diff.removedCount}",
                                onClick = { selectedFileDiff = diff }
                            )
                        }
                    }
                }
            }
        }
    }

    // 文件夹树选择弹窗
    if (showFolderPicker) {
        FolderTreePickerDialog(
            rootPath = defaultPath,
            onDismiss = { showFolderPicker = false },
            onConfirm = { source, target ->
                rootDir = source
                metaDir = target
                showFolderPicker = false
            }
        )
    }

    // 文件差异详情
    selectedFileDiff?.let { diff ->
        FileDiffDetailDialog(diff = diff, onDismiss = { selectedFileDiff = null })
    }

    // AI 更新报告对话框
    if (showReportDialog) {
        AiReportDialog(
            title = dialogTitle,
            report = aiReport,
            error = aiError,
            phase = aiPhase,
            streamText = aiStreamText,
            streaming = aiGenerating,
            onDismiss = { showReportDialog = false }
        )
    }
}

@Composable
private fun StatBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RustedPrimary.copy(alpha = 0.8f), modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun DiffFileItem(name: String, type: Char, badge: String = "", onClick: () -> Unit) {
    val tint = when (type) {
        '+' -> Color(0xFF4CAF50)
        '-' -> Color(0xFFE57373)
        else -> RustedPrimary
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(RustedSurface).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$type", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.width(20.dp))
        Text(name, fontSize = 12.sp, color = RustedOnBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = AppCodeFontFamily)
        if (badge.isNotEmpty()) {
            Text(badge, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f))
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * 树形文件夹选择弹窗。
 * 交互规则：
 * - 第一次点击目录设为“源文件夹”；
 * - 第二次点击另一个目录设为“目标文件夹”；
 * - 当源、目标都已选择后，再次点击目录会重新开始，将新目录设为源。
 * 这样用户无需手动清空即可快速更换对比目录。
 */
@Composable
private fun FolderTreePickerDialog(
    rootPath: String,
    onDismiss: () -> Unit,
    onConfirm: (source: String, target: String) -> Unit
) {
    val root = remember(rootPath) {
        File(rootPath).takeIf { it.exists() && it.isDirectory }
            ?: android.os.Environment.getExternalStorageDirectory()
    }
    var selectedSource by remember { mutableStateOf<File?>(null) }
    var selectedTarget by remember { mutableStateOf<File?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择源文件夹和目标文件夹", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                // 顶部显示当前已选项
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        text = "源：${selectedSource?.absolutePath ?: "未选择"}",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = AppCodeFontFamily,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                    Text(
                        text = "目标：${selectedTarget?.absolutePath ?: "未选择"}",
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = AppCodeFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        DirectoryTreeNode(
                            dir = root,
                            depth = 0,
                            selectedSource = selectedSource,
                            selectedTarget = selectedTarget,
                            onSelect = { file ->
                                when {
                                    selectedSource == null -> selectedSource = file
                                    selectedSource == file -> { /* 已是源，保持不变 */ }
                                    selectedTarget == file -> { /* 已是目标，保持不变 */ }
                                    selectedTarget == null -> selectedTarget = file
                                    else -> {
                                        // 已选满两个，重新开始：新目录作为源
                                        selectedSource = file
                                        selectedTarget = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val source = selectedSource
                    val target = selectedTarget
                    if (source != null && target != null) {
                        onConfirm(source.absolutePath, target.absolutePath)
                    }
                },
                enabled = selectedSource != null && selectedTarget != null
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DirectoryTreeNode(
    dir: File,
    depth: Int,
    selectedSource: File?,
    selectedTarget: File?,
    onSelect: (File) -> Unit
) {
    val subDirs = remember(dir) {
        dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    var expanded by remember(dir) { mutableStateOf(depth == 0) }
    val isSource = dir == selectedSource
    val isTarget = dir == selectedTarget
    val rowBg = when {
        isSource -> Color(0xFF4CAF50).copy(alpha = 0.18f)
        isTarget -> Color(0xFFE57373).copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(rowBg)
            .clickable { onSelect(dir) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (subDirs.isNotEmpty()) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = RustedOnBackground.copy(alpha = 0.6f)
                )
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text = dir.name,
            fontSize = 13.sp,
            color = RustedOnBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isSource) {
            Text("源", fontSize = 10.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
        }
        if (isTarget) {
            Text("目标", fontSize = 10.sp, color = Color(0xFFE57373), fontWeight = FontWeight.Medium)
        }
    }
    if (expanded) {
        subDirs.forEach { child ->
            DirectoryTreeNode(
                dir = child,
                depth = depth + 1,
                selectedSource = selectedSource,
                selectedTarget = selectedTarget,
                onSelect = onSelect
            )
        }
    }
}

@Composable
private fun FileDiffDetailDialog(diff: VersionComparator.FileDiffResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(diff.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
        text = {
            if (diff.error != null) {
                Text("错误：${diff.error}", fontSize = 12.sp, color = RustedError)
            } else if (diff.ops.isEmpty()) {
                Text("无差异", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
            } else {
                // 按修改块分组：同一修改的删除+新增合并在一个卡片里
                val blocks = remember(diff.ops) {
                    val result = mutableListOf<MutableList<DiffOp>>()
                    for (op in diff.ops) {
                        if (result.isNotEmpty() && op.type == '-' && result.last().last().type == '+') {
                            result.add(mutableListOf())
                        }
                        if (result.isEmpty()) result.add(mutableListOf())
                        result.last().add(op)
                    }
                    result
                }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text("+${diff.addedCount}", fontSize = 12.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text("-${diff.removedCount}", fontSize = 12.sp, color = Color(0xFFE57373), fontWeight = FontWeight.Bold)
                        }
                    }
                    items(blocks.size) { blockIdx ->
                        val block = blocks[blockIdx]
                        var expanded by remember(blockIdx) { mutableStateOf(false) }
                        var needsExpand by remember(blockIdx) { mutableStateOf(false) }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(RustedBackground)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                                block.forEach { op ->
                                    val (rowBg, prefixColor, textColor) = when (op.type) {
                                        '+' -> Triple(Color(0xFF1B5E20).copy(alpha = 0.25f), Color(0xFF4CAF50), RustedOnBackground)
                                        else -> Triple(Color(0xFFB71C1C).copy(alpha = 0.25f), Color(0xFFE57373), RustedOnBackground.copy(alpha = 0.6f))
                                    }
                                    val label = if (op.type == '+') op.newLine else op.oldLine
                                    Row(
                                        Modifier.fillMaxWidth().background(rowBg).padding(vertical = 1.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text("$label", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f), modifier = Modifier.width(22.dp))
                                        Text(
                                            if (op.type == '+') "+" else "-",
                                            fontSize = 11.sp, color = prefixColor, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(12.dp)
                                        )
                                        SelectionContainer {
                                            Text(
                                                op.text, Modifier.weight(1f),
                                                fontSize = 11.sp, color = textColor,
                                                fontFamily = AppCodeFontFamily,
                                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                                overflow = TextOverflow.Ellipsis,
                                                onTextLayout = { if (!expanded && it.hasVisualOverflow) needsExpand = true }
                                            )
                                        }
                                    }
                                }
                                if (needsExpand) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(22.dp)) {
                                            Icon(
                                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                null, Modifier.size(14.dp),
                                                tint = RustedOnBackground.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        if (blockIdx < blocks.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = RustedOnBackground.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * AI 更新报告对话框：生成中显示阶段提示 + 流式实时输出，完成后展示完整报告，
 * 支持复制 / 保存到 RWmod/exports/ai_reports/。
 */
@Composable
private fun AiReportDialog(
    title: String,
    report: String?,
    error: String?,
    phase: String,
    streamText: String,
    streaming: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var saveMsg by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    // 流式输出时自动滚到底部
    LaunchedEffect(streamText) {
        if (streamText.isNotEmpty()) {
            scrollState.animateScrollTo(Int.MAX_VALUE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = RustedPrimary)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    error != null -> Text(
                        "生成失败：$error",
                        fontSize = 12.sp, color = RustedError
                    )
                    report != null -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                report,
                                fontSize = 12.sp,
                                color = RustedOnBackground,
                                fontFamily = AppCodeFontFamily
                            )
                        }
                    }
                    streamText.isNotEmpty() -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(scrollState)
                    ) {
                        SelectionContainer {
                            Text(
                                streamText,
                                fontSize = 12.sp,
                                color = RustedOnBackground,
                                fontFamily = AppCodeFontFamily
                            )
                        }
                    }
                    else -> Column(Modifier.fillMaxWidth()) {
                        if (phase.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    color = RustedPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(phase, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                            }
                        } else {
                            Text("正在生成…", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                        }
                    }
                }
                // 生成中的阶段提示（有流式内容时放在顶部）
                if (streaming && streamText.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp),
                            color = RustedPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            phase.ifBlank { "AI 正在生成…" },
                            fontSize = 11.sp,
                            color = RustedPrimary.copy(alpha = 0.7f)
                        )
                    }
                }
                saveMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = RustedPrimary.copy(alpha = 0.85f))
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (report != null) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        saveMsg = "已复制到剪贴板"
                    }) { Text("复制", fontSize = 12.sp) }
                    Spacer(Modifier.width(2.dp))
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                val file = File(RwmodPaths.aiReportsDir, "update_report_$ts.md")
                                file.atomicWriteText(report)
                                file
                            }.fold(
                                onSuccess = { withContext(Dispatchers.Main) { saveMsg = "已保存：${it.name}" } },
                                onFailure = { withContext(Dispatchers.Main) { saveMsg = "保存失败：${it.message}" } }
                            )
                        }
                    }) { Text("保存", fontSize = 12.sp) }
                    Spacer(Modifier.width(2.dp))
                }
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
            }
        }
    )
}
