package com.rwmodstudio.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.rwmodstudio.core.BookmarkManager
import com.rwmodstudio.core.FileClipboardManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.components.*
import com.rwmodstudio.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val INI_EXTENSIONS = setOf("ini", "template", "txt")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

/** 新建 mod-info.txt 的模板内容（可视化页可读回填；空键在保存时会被过滤不写入；无 version 键，版本号习惯写在 title 里） */
private val MOD_INFO_TEMPLATE = """
[mod]
title:
description:
tags:
minVersion:v1.15p11
thumbnail:

[music]
sourceFolder:
whenUsingUnitsFromThisMod_playExclusively:true
""".trimIndent()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    startPath: String = "",
    rootPath: String = "",
    projectRoot: String = "",
    onFileSelected: (String, String) -> Unit,
    onHome: () -> Unit,
    onShowRecent: (() -> Unit)? = null,
    onShowFileSearch: (String) -> Unit = {},
    onJumpToLine: (String, String, Int) -> Unit = { _, _, _ -> },
    onOpenProjectManager: (() -> Unit)? = null,
    onStartPathChange: (String) -> Unit = {},
    onCreateModInfo: (String, String) -> Unit = { _, _ -> }
) {
    val initPath = remember {
        when {
            startPath.isNotEmpty() && File(startPath).exists() -> startPath
            rootPath.isNotEmpty() && File(rootPath).exists() -> rootPath
            SettingsManager.lastPath.isNotEmpty() && File(SettingsManager.lastPath).exists() -> SettingsManager.lastPath
            else -> {
                val es = android.os.Environment.getExternalStorageDirectory()
                listOf(File(es, "rustedWarfare/units"), File(es, "rustedWarfare/mods"), File(es, "rustedWarfare"))
                    .firstOrNull { it.exists() }?.absolutePath ?: es.absolutePath
            }
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initPath) }
    LaunchedEffect(currentPath) {
        onStartPathChange(currentPath)
    }

    // 异度空间：空间状态由 MainApp【顶部菜单栏】按钮管理，切换空间时会修改 startPath(browserStartPath)，
    // 此处同步 currentPath 以切换第一/第二浏览器位置。
    // ⚠️ 位置约定：本文件浏览器页面内部（尤其目录栏/面包屑区域）【不得】再放置异度空间切换按钮，
    //    该入口唯一归属 MainApp 的全局 topBar 最右侧，本文件只负责响应 startPath 变化。
    LaunchedEffect(startPath) {
        if (currentPath != startPath) currentPath = startPath
    }

    var showToolMenu by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newFileExt by remember { mutableStateOf<String?>(null) }
    var viewingImage by remember { mutableStateOf<File?>(null) }
    var browserRefreshTick by remember { mutableIntStateOf(0) }
    var pendingConflict by remember { mutableStateOf<FileConflict?>(null) }

    suspend fun awaitConflict(source: File?, target: File, operation: String): ConflictAction =
        suspendCancellableCoroutine { cont ->
            pendingConflict = FileConflict(source, target, operation) { action ->
                pendingConflict = null
                if (cont.isActive) cont.resume(action)
            }
        }

    Column(Modifier.fillMaxSize().background(RustedBackground).padding(horizontal = 12.dp)) {
        // 目录栏"上锁"：项目模式下位于项目根目录时，锁定"项目目录"段（末尾段）的点击，
        // 防止点击它展开同级文件夹菜单/切换到其他项目；进入子目录后该段恢复可点（可跳回项目根）
        val projectCanonical = remember(projectRoot) {
            if (projectRoot.isEmpty()) null
            else try { File(projectRoot).canonicalPath } catch (_: Exception) { null }
        }
        BackHandler {
            val p = File(currentPath).parentFile
            if (p != null && p.exists()) {
                // 项目模式：上限为 projectRoot，在项目根目录再返回就回首页
                if (projectRoot.isNotEmpty()) {
                    try {
                        val projectCanonical = File(projectRoot).canonicalPath
                        val parentCanonical = p.canonicalPath
                        val currentCanonical = File(currentPath).canonicalPath
                        // 当前在项目根目录 → 回首页；否则只要父目录在项目内就允许返回
                        if (currentCanonical == projectCanonical) {
                            onHome()
                        } else if (parentCanonical.startsWith(projectCanonical)) {
                            currentPath = p.absolutePath
                        } else {
                            onHome()
                        }
                    } catch (_: Exception) {
                        onHome()
                    }
                } else {
                    // 非项目模式：以 rootPath 为上限
                    val withinRoot = try {
                        rootPath.isEmpty() || p.canonicalPath.startsWith(File(rootPath).canonicalPath)
                    } catch (_: Exception) { false }
                    if (withinRoot) currentPath = p.absolutePath else onHome()
                }
            } else onHome()
        }
        // Toolbar
        var showSiblingMenu by remember { mutableStateOf(false) }
        var siblingDirs by remember { mutableStateOf(listOf<File>()) }
        ElevatedCard(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // 只显示相对路径
                        val relativePath = if (rootPath.isNotEmpty() && currentPath.startsWith(rootPath)) {
                            currentPath.removePrefix(rootPath).removePrefix(File.separator)
                        } else {
                            // 如果没有 rootPath，显示最后几级目录
                            val parts = currentPath.split(File.separator).filter { it.isNotEmpty() }
                            if (parts.size > 3) parts.takeLast(3).joinToString(File.separator) else currentPath
                        }
                        val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }

                        pathParts.forEachIndexed { idx, part ->
                            val isLast = idx == pathParts.size - 1
                            // 该路径段的绝对路径（rootPath 下的相对前缀拼接）
                            val segAbs = if (rootPath.isNotEmpty())
                                rootPath + File.separator + pathParts.take(idx + 1).joinToString(File.separator)
                            else ""
                            // 当前是否正位于项目根目录（此时目录栏末尾段即"项目目录"段）
                            val atProjectRoot = projectCanonical != null && try {
                                File(currentPath).canonicalPath == projectCanonical
                            } catch (_: Exception) { false }
                            // 上锁：位于项目根目录时，末尾段（项目目录段）点击会展开同级菜单/切项目 → 锁定（点击不展开目录）
                            val segLocked = isLast && atProjectRoot
                            if (idx > 0) Text("›", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.25f))
                            Box {
                                Surface(
                                    modifier = Modifier.clickable(enabled = !segLocked) {
                                        if (isLast) {
                                            // 展开同级文件夹
                                            val parentDir = File(currentPath).parentFile
                                            siblingDirs = parentDir?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
                                            showSiblingMenu = true
                                        } else if (rootPath.isNotEmpty()) {
                                            currentPath = segAbs
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isLast) RustedPrimary.copy(alpha = 0.12f) else Color.Transparent
                                ) {
                                    Text(
                                        if (part.length > 5) part.take(5) + "…" else part,
                                        fontSize = 12.sp,
                                        fontWeight = if (isLast && !atProjectRoot) FontWeight.Medium else FontWeight.Normal,
                                        color = if (isLast && !atProjectRoot) RustedPrimary
                                        else if (segLocked) RustedOnBackground.copy(alpha = 0.3f)
                                        else RustedOnBackground.copy(alpha = 0.65f),
                                        fontFamily = AppCodeFontFamily,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                }
                                if (isLast) {
                                    DropdownMenu(expanded = showSiblingMenu, onDismissRequest = { showSiblingMenu = false }) {
                                        if (siblingDirs.isEmpty()) {
                                            Row(
                                                Modifier
                                                    .clickable { showSiblingMenu = false }
                                                    .heightIn(min = 32.dp)
                                                    .padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("无同级文件夹", fontSize = 12.sp)
                                            }
                                        } else {
                                            siblingDirs.forEach { dir ->
                                                Row(
                                                    Modifier
                                                        .clickable { currentPath = dir.absolutePath; showSiblingMenu = false }
                                                        .heightIn(min = 36.dp)
                                                        .padding(horizontal = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(dir.name, fontSize = 12.sp, fontFamily = AppCodeFontFamily)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { onShowRecent?.invoke() }, Modifier.size(34.dp)) { Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                IconButton(onClick = { onOpenProjectManager?.invoke() }, Modifier.size(34.dp)) { Icon(Icons.Default.AccountTree, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                Box {
                    IconButton(onClick = { showToolMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                    StyledPopupMenu(
                        expanded = showToolMenu,
                        onDismissRequest = { showToolMenu = false },
                        modifier = Modifier.width(155.dp)
                    ) {
                        val sorts = listOf("名称", "大小", "日期")
                        PlainMenuItem(icon = Icons.Default.Sort, title = "${sorts[sort]}", onClick = { sort = (sort + 1) % 3; showToolMenu = false })
                        PlainMenuItem(icon = if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, title = if (hidden) "显示" else "筛选", onClick = { hidden = !hidden; showToolMenu = false })
                        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                        PlainMenuItem(icon = Icons.Default.NoteAdd, title = "新建文件", onClick = { newName = ""; newFileExt = null; showNewFile = true; showToolMenu = false })
                        PlainMenuItem(icon = Icons.Default.CreateNewFolder, title = "新建文件夹", onClick = { newName = ""; showNewFolder = true; showToolMenu = false })
                        // 仅「项目根目录」（不在子目录内、非项目模式不显示）且还没有 mod-info.txt 时可新建
                        // （模组元数据文件，新建后进入可视化编辑）
                        val atProjectRoot = projectRoot.isNotEmpty() && try {
                            File(currentPath).canonicalPath == File(projectRoot).canonicalPath
                        } catch (_: Exception) { false }
                        if (atProjectRoot && !File(currentPath, "mod-info.txt").exists()) {
                            PlainMenuItem(icon = Icons.Default.Description, title = "新建 mod-info.txt", onClick = {
                                showToolMenu = false
                                val target = File(currentPath, "mod-info.txt")
                                scope.launch(Dispatchers.IO) {
                                    val ok = try {
                                        target.parentFile?.mkdirs()
                                        target.writeText(MOD_INFO_TEMPLATE, Charsets.UTF_8)
                                        true
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "创建失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        false
                                    }
                                    if (ok) withContext(Dispatchers.Main) {
                                        browserRefreshTick++
                                        onCreateModInfo(target.name, target.absolutePath)
                                    }
                                }
                            })
                        }
                        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                        PlainMenuItem(icon = Icons.Default.Search, title = "查找文件", onClick = { onShowFileSearch(currentPath); showToolMenu = false })
                    }
                }
            }
        }

        // Single browser panel
        BrowserPanel(
            path = currentPath,
            root = rootPath,
            refreshTick = browserRefreshTick,
            onRefreshNeeded = { browserRefreshTick++ },
            onPathChange = { currentPath = it },
            onFileSelected = onFileSelected,
            onHome = onHome,
            onImageClick = { viewingImage = it },
            resolveConflict = { s, t, op -> awaitConflict(s, t, op) },
            modifier = Modifier.weight(1f),
            sort = sort,
            hidden = hidden
        )
    }

    if (showNewFile) AlertDialog(onDismissRequest = { showNewFile = false }, title = { Text("新建文件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("文件名（可不带扩展名）") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ini", "template").forEach { ext ->
                    val selected = newFileExt == ext
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (selected) RustedPrimary else RustedOnBackground.copy(alpha = 0.06f))
                            .clickable { newFileExt = ext }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(".$ext", color = if (selected) Color.White else RustedOnBackground, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val error = validateFileName(newName)
        if (error != null) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val raw = newName.trim()
            val name = if ('.' in raw) raw else (newFileExt?.let { "$raw.$it" } ?: raw)   // 以手输为准；未选格式则不补扩展名
            val target = File(currentPath, name)
            if (target.exists()) {
                android.widget.Toast.makeText(context, "已存在同名文件/文件夹", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                showNewFile = false
                scope.launch(Dispatchers.IO) {
                    val ok = try {
                        target.parentFile?.mkdirs()
                        target.createNewFile()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "创建文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        false
                    }
                    if (ok) withContext(Dispatchers.Main) { browserRefreshTick++ }
                }
            }
        }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("取消") } })
    if (showNewFolder) AlertDialog(onDismissRequest = { showNewFolder = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = {
        val error = validateFileName(newName)
        if (error != null) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val target = File(currentPath, newName.trim())
            if (target.exists()) {
                android.widget.Toast.makeText(context, "已存在同名文件/文件夹", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                showNewFolder = false
                scope.launch(Dispatchers.IO) {
                    val ok = try {
                        target.mkdirs()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "创建文件夹失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        false
                    }
                    if (ok) withContext(Dispatchers.Main) { browserRefreshTick++ }
                }
            }
        }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } })
    // 图片查看器
    viewingImage?.let { imgFile ->
        Dialog(
            onDismissRequest = { viewingImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val tileSize = 16.dp.toPx()
                        val rows = (size.height / tileSize).toInt() + 1
                        val cols = (size.width / tileSize).toInt() + 1
                        for (r in 0..rows) {
                            for (c in 0..cols) {
                                drawRect(
                                    color = if ((r + c) % 2 == 0) Color(0xFF333333) else Color(0xFF444444),
                                    topLeft = Offset(c * tileSize, r * tileSize),
                                    size = Size(tileSize, tileSize)
                                )
                            }
                        }
                    }
                    .clickable { viewingImage = null },
                contentAlignment = Alignment.Center
            ) {
                var bitmap by remember(imgFile.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                LaunchedEffect(imgFile.absolutePath) {
                    bitmap = withContext(Dispatchers.IO) {
                        try {
                            BitmapFactory.decodeFile(imgFile.absolutePath)?.asImageBitmap()
                        } catch (_: Exception) { null }
                    }
                }
                bitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it,
                        contentDescription = imgFile.name,
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                } ?: Text("无法加载图片", color = Color.White)
                // 文件名
                Text(imgFile.name, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                // 关闭按钮
                IconButton(onClick = { viewingImage = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
    // 文件冲突处理对话框
    pendingConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = {
                conflict.onResolve(ConflictAction.SKIP)
                pendingConflict = null
            },
            title = { Text("文件已存在") },
            text = {
                Column {
                    Text("目标位置已存在同名文件/文件夹：")
                    Text(conflict.target.name, fontWeight = FontWeight.Bold)
                    if (conflict.source != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("来源：${conflict.source.name}", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("请选择操作方式")
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { conflict.onResolve(ConflictAction.SKIP); pendingConflict = null }) { Text("取消") }
                    TextButton(onClick = { conflict.onResolve(ConflictAction.KEEP_BOTH); pendingConflict = null }) { Text("保留两个") }
                    TextButton(onClick = { conflict.onResolve(ConflictAction.OVERWRITE); pendingConflict = null }) { Text("覆盖") }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserPanel(
    path: String,
    root: String,
    refreshTick: Int,
    onRefreshNeeded: () -> Unit,
    onPathChange: (String) -> Unit,
    onFileSelected: (String, String) -> Unit,
    onHome: () -> Unit,
    onImageClick: (File) -> Unit,
    resolveConflict: suspend (source: File?, target: File, operation: String) -> ConflictAction,
    modifier: Modifier,
    sort: Int = 0,
    hidden: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(listOf<File>()) }
    var rename by remember { mutableStateOf<File?>(null) }
    var del by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var ctxMenuFile by remember { mutableStateOf<File?>(null) }
    var ctxMenuBounds by remember { mutableStateOf<Rect?>(null) }
    // 多选：入口是长按文件条目后悬浮菜单里的「多选」（左滑已不再触发，避免误触）
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var delMulti by remember { mutableStateOf<List<String>>(emptyList()) }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedFiles = emptySet()
    }

    fun toggleSelect(path: String) {
        selectedFiles = if (path in selectedFiles) selectedFiles - path else selectedFiles + path
    }

    LaunchedEffect(path, sort, hidden, refreshTick) { files = loadFiles(path, hidden, sort) }
    LaunchedEffect(path) {
        SettingsManager.lastPath = path
        // 切换目录时退出多选，避免选中项残留到新目录
        exitMultiSelect()
    }
    // 路径或列表刷新时关闭悬浮菜单（文件位置已失效）
    LaunchedEffect(path, refreshTick) { ctxMenuFile = null; ctxMenuBounds = null }
    // 多选模式下返回键优先退出多选，而不是返回上级目录
    BackHandler(enabled = multiSelectMode) { exitMultiSelect() }

    Column(modifier.background(RustedBackground)) {
        if (FileClipboardManager.files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(RustedSecondary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${FileClipboardManager.files.size} 项${if (FileClipboardManager.mode == "cut") "（剪切）" else "（复制）"}", fontSize = 10.sp, color = RustedSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val srcFiles = FileClipboardManager.files
                    val mode = FileClipboardManager.mode
                    FileClipboardManager.clear(context)
                    scope.launch(Dispatchers.IO) {
                        var skipped = 0
                        var success = 0
                        var cancelled = false
                        try {
                            srcFiles.forEach { s ->
                                if (cancelled) return@forEach
                                val f = File(s)
                                if (!f.exists()) { skipped++; return@forEach }
                                if (mode == "cut" && f.parentFile?.absolutePath == path) { success++; return@forEach }
                                val target = File(path, f.name)
                                val finalTarget = if (target.exists()) {
                                    when (resolveConflict(f, target, mode)) {
                                        ConflictAction.SKIP -> { cancelled = true; return@forEach }
                                        ConflictAction.OVERWRITE -> target
                                        ConflictAction.KEEP_BOTH -> uniqueFile(File(path), target)
                                    }
                                } else target
                                if (mode == "cut") {
                                    if (f.isDirectory) {
                                        if (finalTarget.exists()) {
                                            // 覆盖：先拷到临时目录，成功后再替换目标，避免先删后拷失败丢数据
                                            val tmpDir = File(finalTarget.parentFile, "${finalTarget.name}.tmp")
                                            tmpDir.deleteRecursively()
                                            f.copyRecursively(tmpDir, true)
                                            finalTarget.deleteRecursively()
                                            if (!tmpDir.renameTo(finalTarget)) {
                                                tmpDir.copyRecursively(finalTarget, true)
                                                tmpDir.deleteRecursively()
                                            }
                                            f.deleteRecursively()
                                        } else {
                                            f.copyRecursively(finalTarget, true)
                                            f.deleteRecursively()
                                        }
                                    } else {
                                        if (finalTarget.exists()) {
                                            // 覆盖：先拷到临时文件，成功后再替换目标，避免先删后拷失败丢数据
                                            val tmpFile = File(finalTarget.parentFile, "${finalTarget.name}.tmp")
                                            tmpFile.delete()
                                            f.copyTo(tmpFile, true)
                                            finalTarget.delete()
                                            if (!tmpFile.renameTo(finalTarget)) {
                                                tmpFile.copyTo(finalTarget, true)
                                                tmpFile.delete()
                                            }
                                            f.delete()
                                        } else {
                                            f.copyTo(finalTarget, true)
                                            f.delete()
                                        }
                                    }
                                } else {
                                    if (f.isDirectory) f.copyRecursively(finalTarget, true)
                                    else f.copyTo(finalTarget, true)
                                }
                                success++
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "粘贴失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onRefreshNeeded()
                            if (cancelled) {
                                android.widget.Toast.makeText(context, "已取消粘贴", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (success > 0 || skipped > 0) {
                                android.widget.Toast.makeText(context, "成功 $success 项，跳过 $skipped 项", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("粘贴", fontSize = 10.sp) }
                TextButton(onClick = { FileClipboardManager.clear(context) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("取消", fontSize = 10.sp) }
            }
        }
        // 多选模式无顶部栏：入口在长按悬浮窗（多选），退出靠返回键或执行操作后自动退出
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.04f))

        if (files.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("空文件夹", color = RustedOnBackground.copy(alpha = 0.35f), fontSize = 13.sp) }
        else LazyColumn(Modifier.fillMaxSize()) { items(files, key = { it.absolutePath }) { f ->
            val ext = f.extension.lowercase()
            val isImage = ext in IMAGE_EXTENSIONS
            FileEntry(
                file = f,
                onClick = {
                    if (multiSelectMode) toggleSelect(f.absolutePath)
                    else if (f.isDirectory) onPathChange(f.absolutePath)
                    else if (isImage) onImageClick(f)
                    else if (ext in INI_EXTENSIONS) onFileSelected(f.name, f.absolutePath)
                },
                onLongClick = { bounds ->
                    // 无论是否多选，长按都弹出同样的操作悬浮窗；多选时操作作用于全部已选项
                    ctxMenuFile = f
                    ctxMenuBounds = bounds
                },
                isSelected = multiSelectMode && selectedFiles.contains(f.absolutePath),
                multiSelectMode = multiSelectMode
            )
        } }
    }
    // 长按文件弹出的悬浮操作菜单，定位到选中文件右侧
    ctxMenuFile?.let { file ->
        ctxMenuBounds?.let { bounds ->
            // 多选模式下悬浮窗操作作用于所有已选项；单选时作用于该文件
            val targets = if (multiSelectMode && selectedFiles.isNotEmpty()) selectedFiles.toList() else listOf(file.absolutePath)
            val singleTarget = targets.size == 1
            Popup(
                popupPositionProvider = FileMenuPositionProvider(bounds),
                onDismissRequest = { ctxMenuFile = null; ctxMenuBounds = null },
                properties = PopupProperties(focusable = true)
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = RustedSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    border = BorderStroke(0.5.dp, RustedOnBackground.copy(alpha = 0.10f))
                ) {
                    Column(Modifier.width(132.dp).padding(vertical = 4.dp)) {
                        FloatingMenuItem(Icons.Outlined.ContentCopy, "复制", onClick = {
                            FileClipboardManager.set(context, "copy", targets)
                            ctxMenuFile = null; ctxMenuBounds = null
                            exitMultiSelect()
                        })
                        FloatingMenuItem(Icons.Outlined.ContentCut, "剪切", onClick = {
                            FileClipboardManager.set(context, "cut", targets)
                            ctxMenuFile = null; ctxMenuBounds = null
                            exitMultiSelect()
                        })
                        // 重命名/书签为单文件操作，多选时不显示
                        if (singleTarget) {
                            FloatingMenuItem(Icons.Outlined.DriveFileRenameOutline, "重命名", onClick = {
                                rename = file; newName = file.name
                                ctxMenuFile = null; ctxMenuBounds = null
                                exitMultiSelect()
                            })
                        }
                        // 单选模式下的多选入口：进入多选并选中该文件（位于重命名下方）
                        if (!multiSelectMode) {
                            FloatingMenuItem(Icons.Outlined.Checklist, "多选", onClick = {
                                multiSelectMode = true
                                selectedFiles = setOf(file.absolutePath)
                                ctxMenuFile = null; ctxMenuBounds = null
                            })
                        }
                        if (singleTarget) {
                            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            // 仅 .ini/.txt/.template 文件可添加书签（文件夹不可）
                            if (file.isFile && file.extension.lowercase() in setOf("ini", "txt", "template")) {
                                // 同一个值只算一次：isBookmarked 会读收藏文件、并逐条检查文件是否存在，避免重复读盘
                                val bookmarked = BookmarkManager.isBookmarked(file.absolutePath)
                                FloatingMenuItem(
                                    if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                    if (bookmarked) "取消书签" else "书签",
                                    onClick = {
                                        val added = BookmarkManager.toggle(file.absolutePath, file.name)
                                        android.widget.Toast.makeText(context, if (added) "已添加书签" else "已取消书签", android.widget.Toast.LENGTH_SHORT).show()
                                        ctxMenuFile = null; ctxMenuBounds = null
                                        exitMultiSelect()
                                    }
                                )
                                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            }
                        }
                        FloatingMenuItem(Icons.Outlined.DeleteOutline, "删除", tint = RustedError, onClick = {
                            if (targets.size > 1) delMulti = targets else del = file
                            ctxMenuFile = null; ctxMenuBounds = null
                            exitMultiSelect()
                        })
                    }
                }
            }
        }
    }
    rename?.let { f -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名") }, text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = {
        val error = validateFileName(newName)
        if (error != null) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val target = File(f.parentFile, newName.trim())
            rename = null
            scope.launch(Dispatchers.IO) {
                val ok = try {
                    f.renameTo(target)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "重命名失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    false
                }
                if (ok) withContext(Dispatchers.Main) { onRefreshNeeded() }
            }
        }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
    del?.let { f -> AlertDialog(onDismissRequest = { del = null }, title = { Text("确认删除") }, text = { Text("确定删除「${f.name}」？") }, confirmButton = { TextButton(onClick = {
        del = null
        scope.launch(Dispatchers.IO) {
            try {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "删除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            withContext(Dispatchers.Main) { onRefreshNeeded() }
        }
    }) { Text("删除", color = RustedError) } }, dismissButton = { TextButton(onClick = { del = null }) { Text("取消") } }) }
    // 批量删除确认
    if (delMulti.isNotEmpty()) {
        val targets = delMulti
        AlertDialog(
            onDismissRequest = { delMulti = emptyList() },
            title = { Text("确认删除") },
            text = { Text("确定删除选中的 ${targets.size} 项？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                delMulti = emptyList()
                scope.launch(Dispatchers.IO) {
                    var ok = 0; var fail = 0
                    targets.forEach { p ->
                        try {
                            val ff = File(p)
                            if (ff.isDirectory) ff.deleteRecursively() else ff.delete()
                            ok++
                        } catch (_: Exception) { fail++ }
                    }
                    withContext(Dispatchers.Main) {
                        onRefreshNeeded()
                        android.widget.Toast.makeText(context,
                            if (fail > 0) "已删除 $ok 项，失败 $fail 项" else "已删除 $ok 项",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("删除", color = RustedError) } },
            dismissButton = { TextButton(onClick = { delMulti = emptyList() }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntry(
    file: File,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    isSelected: Boolean = false,
    multiSelectMode: Boolean = false
) {
    val isDir = file.isDirectory
    val df = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val ext = file.extension.lowercase()
    val isImage = ext in IMAGE_EXTENSIONS
    val fileType = when {
        isDir -> FileType.FOLDER
        ext == "ini" -> FileType.INI
        ext == "template" -> FileType.TEMPLATE
        ext in IMAGE_EXTENSIONS -> FileType.IMAGE
        ext in setOf("txt", "log", "md") -> FileType.TEXT
        ext in setOf("json", "xml", "yaml", "yml") -> FileType.DATA
        else -> FileType.UNKNOWN
    }

    // 加载图片（仅图片文件，使用原图）
    var imageBitmap by remember(file.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        if (isImage && file.exists()) {
            imageBitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }

    val typeInfo = remember(fileType) { FileTypeInfo.forType(fileType) }

    // 文件浏览器固定「小」档位：统一 MT 管理器风格（无边框扁平两行），紧凑
    val padV = 4.dp
    val multiSelIcon = 18.dp
    val multiSelGap = 6.dp
    val iconBox = 30.dp
    val icon = 22.dp
    val img = 22.dp
    val nameSp = 14.sp
    val subSp = 9.sp

    // 记录当前条目在窗口中的位置，长按时回传给父级用于定位悬浮菜单
    var entryBounds by remember { mutableStateOf<Rect?>(null) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                val sz = coords.size
                entryBounds = Rect(pos, Size(sz.width.toFloat(), sz.height.toFloat()))
            }
            // 左滑不再进入多选（老板要求）：多选入口统一走长按悬浮窗里的「多选」
            .combinedClickable(onClick = onClick, onLongClick = {
                entryBounds?.let { onLongClick(it) }
            }),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) RustedPrimary.copy(alpha = 0.08f) else Color.Transparent
        ),
        border = BorderStroke(
            if (isSelected) 1.dp else 0.dp,
            if (isSelected) RustedPrimary.copy(alpha = 0.4f) else Color.Transparent
        )
    ) {
        // 统一 MT 管理器风格两行布局（大/中/小仅尺寸不同）
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = padV), verticalAlignment = Alignment.CenterVertically) {
            if (multiSelectMode) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null,
                    Modifier.size(multiSelIcon),
                    tint = if (isSelected) RustedPrimary else RustedOnBackground.copy(alpha = 0.3f)
                )
                Spacer(Modifier.width(multiSelGap))
            }
            Box(
                Modifier.size(iconBox),
                contentAlignment = Alignment.Center
            ) {
                imageBitmap?.let {
                    androidx.compose.foundation.Image(it, null, Modifier.size(img).clip(RoundedCornerShape(4.dp)))
                } ?: Icon(typeInfo.icon, null, Modifier.size(icon), tint = typeInfo.color)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontSize = nameSp,
                    fontWeight = FontWeight.Medium,
                    color = RustedOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = if (isDir) "文件夹  •  ${df.format(Date(file.lastModified()))}"
                    else "${fmtSize(file.length())}  •  ${df.format(Date(file.lastModified()))}",
                    fontSize = subSp,
                    color = RustedOnBackground.copy(alpha = 0.38f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MenuBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label) } }
}

/**
 * 悬浮菜单定位器：将菜单固定在选中文件条目的右侧，超出屏幕边界时自动回退。
 */
internal class FileMenuPositionProvider(private val fileBounds: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val desiredX = fileBounds.right.toInt() + 8
        val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)
        val x = if (desiredX > maxX) maxX else desiredX
        val maxY = (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8)
        val y = fileBounds.top.toInt().coerceIn(8, maxY)
        return IntOffset(x, y)
    }
}

/**
 * 悬浮菜单中的单个操作项（图标 + 文字）。
 */
@Composable
internal fun FloatingMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = RustedOnBackground,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = tint)
    }
}

private suspend fun loadFiles(p: String, h: Boolean, s: Int): List<File> = withContext(Dispatchers.IO) {
    val d = File(p)
    if (d.exists()) {
        d.listFiles()?.filter { h || !it.name.startsWith(".") }?.sortedWith(
            when (s) {
                0 -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                1 -> compareBy<File> { !it.isDirectory }.thenByDescending { it.length() }
                else -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
            }
        ) ?: emptyList()
    } else emptyList()
}

private fun fmtSize(b: Long) = when { b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"; else -> "${"%.1f".format(b / (1024.0 * 1024.0))} MB" }

private fun validateFileName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "名称不能为空"
    if (trimmed.contains("/") || trimmed.contains("\\")) return "名称不能包含路径分隔符"
    val illegal = Regex("[\\/:*?\"<>|]")
    if (illegal.containsMatchIn(trimmed)) return "名称包含非法字符"
    return null
}

private enum class FileType { FOLDER, INI, TEMPLATE, IMAGE, TEXT, DATA, UNKNOWN }

private data class FileTypeInfo(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
) {
    companion object {
        fun forType(type: FileType) = when (type) {
            FileType.FOLDER -> FileTypeInfo(Icons.Outlined.Folder, RustedAccent)
            FileType.INI -> FileTypeInfo(Icons.Outlined.Settings, RustedPrimary)
            FileType.TEMPLATE -> FileTypeInfo(Icons.Outlined.Code, RustedSecondary)
            FileType.IMAGE -> FileTypeInfo(Icons.Outlined.Image, Color(0xFFE91E63))
            FileType.TEXT -> FileTypeInfo(Icons.Outlined.Article, Color(0xFF607D8B))
            FileType.DATA -> FileTypeInfo(Icons.Outlined.Pages, Color(0xFF9C27B0))
            FileType.UNKNOWN -> FileTypeInfo(Icons.Outlined.InsertDriveFile, Color(0xFF78909C))
        }
    }
}

private enum class ConflictAction { OVERWRITE, SKIP, KEEP_BOTH }

private data class FileConflict(
    val source: File?,
    val target: File,
    val operation: String,
    val onResolve: (ConflictAction) -> Unit
)

private fun uniqueFile(dir: File, original: File): File {
    if (!dir.resolve(original.name).exists()) return dir.resolve(original.name)
    val base = original.nameWithoutExtension
    val ext = original.extension
    var n = 1
    while (true) {
        val candidate = if (ext.isEmpty()) File(dir, "$base ($n)") else File(dir, "$base ($n).$ext")
        if (!candidate.exists()) return candidate
        n++
    }
}
