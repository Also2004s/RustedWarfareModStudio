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
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.components.*
import com.rwmodstudio.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val INI_EXTENSIONS = setOf("ini", "template", "txt")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

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
    onStartPathChange: (String) -> Unit = {}
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
    var showToolMenu by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
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
                            if (idx > 0) Text("›", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.25f))
                            Box {
                                Surface(
                                    modifier = Modifier.clickable {
                                        if (isLast) {
                                            // 展开同级文件夹
                                            val parentDir = File(currentPath).parentFile
                                            siblingDirs = parentDir?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
                                            showSiblingMenu = true
                                        } else if (rootPath.isNotEmpty()) {
                                            currentPath = rootPath + File.separator + pathParts.take(idx + 1).joinToString(File.separator)
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isLast) RustedPrimary.copy(alpha = 0.12f) else Color.Transparent
                                ) {
                                    Text(part, fontSize = 12.sp,
                                        fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                                        color = if (isLast) RustedPrimary else RustedOnBackground.copy(alpha = 0.65f),
                                        fontFamily = AppCodeFontFamily,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                }
                                if (isLast) {
                                    DropdownMenu(expanded = showSiblingMenu, onDismissRequest = { showSiblingMenu = false }) {
                                        if (siblingDirs.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("无同级文件夹", fontSize = 12.sp) },
                                                onClick = { showSiblingMenu = false },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                            )
                                        } else {
                                            siblingDirs.forEach { dir ->
                                                DropdownMenuItem(
                                                    text = { Text(dir.name, fontSize = 12.sp, fontFamily = AppCodeFontFamily) },
                                                    onClick = { currentPath = dir.absolutePath; showSiblingMenu = false },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                                )
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
                        PlainMenuItem(icon = Icons.Default.NoteAdd, title = "新建文件", onClick = { newName = ""; showNewFile = true; showToolMenu = false })
                        PlainMenuItem(icon = Icons.Default.CreateNewFolder, title = "新建文件夹", onClick = { newName = ""; showNewFolder = true; showToolMenu = false })
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

    if (showNewFile) AlertDialog(onDismissRequest = { showNewFile = false }, title = { Text("新建文件") }, text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("文件名.ini") }) }, confirmButton = { TextButton(onClick = {
        val error = validateFileName(newName)
        if (error != null) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val target = File(currentPath, newName.trim())
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
    var clipMode by remember { mutableStateOf("") }
    var clipFiles by remember { mutableStateOf(listOf<String>()) }
    var ctxMenuFile by remember { mutableStateOf<File?>(null) }
    var ctxMenuBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(path, sort, hidden, refreshTick) { files = loadFiles(path, hidden, sort) }
    LaunchedEffect(path) { SettingsManager.lastPath = path }
    // 路径或列表刷新时关闭悬浮菜单（文件位置已失效）
    LaunchedEffect(path, refreshTick) { ctxMenuFile = null; ctxMenuBounds = null }

    Column(modifier.background(RustedBackground)) {
        if (clipFiles.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(RustedSecondary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${clipFiles.size} 项${if (clipMode == "cut") "（剪切）" else "（复制）"}", fontSize = 10.sp, color = RustedSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val srcFiles = clipFiles
                    val mode = clipMode
                    clipFiles = emptyList()
                    clipMode = ""
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
                                        finalTarget.deleteRecursively()
                                        f.copyRecursively(finalTarget, true)
                                        f.deleteRecursively()
                                    } else {
                                        finalTarget.delete()
                                        f.copyTo(finalTarget, true)
                                        f.delete()
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
                TextButton(onClick = { clipFiles = emptyList(); clipMode = "" }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("取消", fontSize = 10.sp) }
            }
        }
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.04f))

        if (files.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("空文件夹", color = RustedOnBackground.copy(alpha = 0.35f), fontSize = 13.sp) }
        else LazyColumn(Modifier.fillMaxSize()) { items(files, key = { it.absolutePath }) { f ->
            val ext = f.extension.lowercase()
            val isImage = ext in IMAGE_EXTENSIONS
            FileEntry(file = f, onClick = {
                if (f.isDirectory) onPathChange(f.absolutePath)
                else if (isImage) onImageClick(f)
                else if (ext in INI_EXTENSIONS) onFileSelected(f.name, f.absolutePath)
            }, onLongClick = { bounds ->
                ctxMenuFile = f
                ctxMenuBounds = bounds
            }, isSelected = ctxMenuFile == f)
        } }
    }
    // 长按文件弹出的悬浮操作菜单，定位到选中文件右侧
    ctxMenuFile?.let { file ->
        ctxMenuBounds?.let { bounds ->
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
                            clipFiles = listOf(file.absolutePath); clipMode = "copy"
                            ctxMenuFile = null; ctxMenuBounds = null
                        })
                        FloatingMenuItem(Icons.Outlined.ContentCut, "剪切", onClick = {
                            clipFiles = listOf(file.absolutePath); clipMode = "cut"
                            ctxMenuFile = null; ctxMenuBounds = null
                        })
                        FloatingMenuItem(Icons.Outlined.DriveFileRenameOutline, "重命名", onClick = {
                            rename = file; newName = file.name
                            ctxMenuFile = null; ctxMenuBounds = null
                        })
                        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                        FloatingMenuItem(Icons.Outlined.DeleteOutline, "删除", tint = RustedError, onClick = {
                            del = file
                            ctxMenuFile = null; ctxMenuBounds = null
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntry(file: File, onClick: () -> Unit, onLongClick: (Rect) -> Unit, isSelected: Boolean = false) {
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
            .combinedClickable(onClick = onClick, onLongClick = {
                entryBounds?.let { onLongClick(it) }
            }),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) RustedPrimary.copy(alpha = 0.08f) else RustedSurface
        ),
        border = BorderStroke(
            if (isSelected) 1.dp else 0.5.dp,
            if (isSelected) RustedPrimary.copy(alpha = 0.4f) else RustedOnBackground.copy(alpha = 0.10f)
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isDir) {
                Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(typeInfo.color))
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(typeInfo.color.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                imageBitmap?.let {
                    androidx.compose.foundation.Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
                } ?: Icon(typeInfo.icon, null, Modifier.size(18.dp), tint = typeInfo.color)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDir) "文件夹" else if (isImage) "图片" else fmtSize(file.length()), fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                    Text("  •  ", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.2f))
                    Text(df.format(Date(file.lastModified())), fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                }
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
private class FileMenuPositionProvider(private val fileBounds: Rect) : PopupPositionProvider {
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
private fun FloatingMenuItem(
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
