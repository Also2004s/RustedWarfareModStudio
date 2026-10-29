package com.rwmodstudio.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.rwmodstudio.core.ThemeState
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
    mtLayout: Boolean = false,
    onToggleLayout: () -> Unit = {},
    firstStartPath: String = "",
    secondStartPath: String = "",
    activePane: Int = 0,
    toolMenuTick: Int = 0,   // 仿MT目录：「更多」菜单由全局菜单栏触发（tick 递增即弹出）
    onFileSelected: (String, String) -> Unit,
    onHome: () -> Unit,
    onShowRecent: (() -> Unit)? = null,
    onShowFileSearch: (String) -> Unit = {},
    onJumpToLine: (String, String, Int) -> Unit = { _, _, _ -> },
    onOpenProjectManager: (() -> Unit)? = null,
    onStartPathChange: (String) -> Unit = {},
    onPanePathChange: (Int, String) -> Unit = { _, _ -> },
    onActivePaneChange: (Int, String) -> Unit = { _, _ -> },
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
    LaunchedEffect(currentPath, mtLayout) {
        if (!mtLayout) onStartPathChange(currentPath)
    }
    LaunchedEffect(startPath, mtLayout) {
        if (!mtLayout && currentPath != startPath) currentPath = startPath
    }

    // ===== 仿MT目录（双栏）：左栏=第一空间，右栏=第二空间 =====
    // 两栏各自导航；点按哪栏哪栏成为激活栏（MainApp 的 browserSpace/browserStartPath 随之更新，
    // 编辑器往返、空间持久化均复用既有链路）。
    var leftPath by remember {
        mutableStateOf(if (firstStartPath.isNotEmpty() && File(firstStartPath).exists()) firstStartPath else initPath)
    }
    var rightPath by remember {
        mutableStateOf(if (secondStartPath.isNotEmpty() && File(secondStartPath).exists()) secondStartPath else initPath)
    }
    var pane by remember { mutableIntStateOf(activePane) }
    LaunchedEffect(activePane) { if (pane != activePane) pane = activePane }
    // MainApp 侧空间状态变化（进入浏览器恢复缓存/编辑器返回）时同步对应栏
    LaunchedEffect(firstStartPath) {
        if (firstStartPath.isNotEmpty() && leftPath != firstStartPath && File(firstStartPath).exists()) leftPath = firstStartPath
    }
    LaunchedEffect(secondStartPath) {
        if (secondStartPath.isNotEmpty() && rightPath != secondStartPath && File(secondStartPath).exists()) rightPath = secondStartPath
    }
    val activePath = if (pane == 0) leftPath else rightPath
    // 工具菜单/新建对话框/查找文件的作用目录：单栏=currentPath，双栏=激活栏
    val workPath = if (mtLayout) activePath else currentPath

    fun activatePane(p: Int) {
        if (pane == p) return
        pane = p
        onActivePaneChange(p, if (p == 0) leftPath else rightPath)
    }
    fun navigatePane(p: Int, path: String) {
        if (p == 0) leftPath = path else rightPath = path
        if (pane != p) {
            // 在非激活栏里点进了文件夹 → 该栏同时成为激活栏
            pane = p
            onActivePaneChange(p, path)
        } else {
            onPanePathChange(p, path)
        }
    }

    var showToolMenu by remember { mutableStateOf(false) }
    // 「更多」入口在全局菜单栏，用 tick 信号远程触发本页菜单。
    // 只响应 tick 的「增量」：本页首次组合时把当前 tick 记为已消费，否则第二次进入浏览器会一进来就自动展开菜单。
    var lastToolMenuTick by remember { mutableIntStateOf(toolMenuTick) }
    LaunchedEffect(toolMenuTick) {
        if (toolMenuTick != lastToolMenuTick) {
            lastToolMenuTick = toolMenuTick
            showToolMenu = true
        }
    }
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

    // 仿MT目录：双栏空间紧张，页面左右内边距收窄
    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .padding(horizontal = if (mtLayout) 6.dp else 12.dp)
    ) {
        // 目录栏"上锁"：项目模式下位于项目根目录时，锁定"项目目录"段（末尾段）的点击，
        // 防止点击它展开同级文件夹菜单/切换到其他项目；进入子目录后该段恢复可点（可跳回项目根）
        val projectCanonical = remember(projectRoot) {
            if (projectRoot.isEmpty()) null
            else try { File(projectRoot).canonicalPath } catch (_: Exception) { null }
        }
        BackHandler {
            val cur = workPath
            val p = File(cur).parentFile
            if (p != null && p.exists()) {
                // 项目模式：上限为 projectRoot，在项目根目录再返回就回首页
                if (projectRoot.isNotEmpty()) {
                    try {
                        val projectCanonical = File(projectRoot).canonicalPath
                        val parentCanonical = p.canonicalPath
                        val currentCanonical = File(cur).canonicalPath
                        // 当前在项目根目录 → 回首页；否则只要父目录在项目内就允许返回
                        if (currentCanonical == projectCanonical) {
                            onHome()
                        } else if (parentCanonical.startsWith(projectCanonical)) {
                            if (mtLayout) navigatePane(pane, p.absolutePath) else currentPath = p.absolutePath
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
                    if (withinRoot) {
                        if (mtLayout) navigatePane(pane, p.absolutePath) else currentPath = p.absolutePath
                    } else onHome()
                }
            } else onHome()
        }
        // Toolbar（仿MT目录下每栏自带面包屑，此处只留右侧按钮）
        // 「新建 mod-info.txt」条件：当前作用目录位于项目根（不在子目录内、非项目模式不显示）且尚无该文件
        val atProjectRoot = projectRoot.isNotEmpty() && try {
            File(workPath).canonicalPath == File(projectRoot).canonicalPath
        } catch (_: Exception) { false }
        val createModInfoHere: () -> Unit = {
            showToolMenu = false
            val target = File(workPath, "mod-info.txt")
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
        }

        // Toolbar（仿MT目录：近期/项目管理/更多三个按钮移至全局菜单栏 MainApp topBar，
        // 页面内不再保留工具卡，只留「更多」菜单锚点，让双栏占据全部纵向空间）
        if (mtLayout) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.size(1.dp)) {
                    BrowserToolMenu(
                        expanded = showToolMenu,
                        onDismiss = { showToolMenu = false },
                        sort = sort,
                        hidden = hidden,
                        showModInfoItem = atProjectRoot && !File(workPath, "mod-info.txt").exists(),
                        onSort = { sort = (sort + 1) % 3; showToolMenu = false },
                        onToggleHidden = { hidden = !hidden; showToolMenu = false },
                        onToggleLayout = { onToggleLayout(); showToolMenu = false },
                        onNewFile = { newName = ""; newFileExt = null; showNewFile = true; showToolMenu = false },
                        onNewFolder = { newName = ""; showNewFolder = true; showToolMenu = false },
                        onNewModInfo = createModInfoHere,
                        onFind = { onShowFileSearch(workPath); showToolMenu = false }
                    )
                }
            }
        } else {
            ElevatedCard(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BreadcrumbBar(
                        path = currentPath,
                        rootPath = rootPath,
                        projectCanonical = projectCanonical,
                        onPathChange = { currentPath = it },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onShowRecent?.invoke() }, Modifier.size(34.dp)) { Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                    IconButton(onClick = { onOpenProjectManager?.invoke() }, Modifier.size(34.dp)) { Icon(Icons.Default.AccountTree, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                    Box {
                        IconButton(onClick = { showToolMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(20.dp), tint = RustedOnBackground.copy(alpha = 0.55f)) }
                        BrowserToolMenu(
                            expanded = showToolMenu,
                            onDismiss = { showToolMenu = false },
                            sort = sort,
                            hidden = hidden,
                            showModInfoItem = atProjectRoot && !File(workPath, "mod-info.txt").exists(),
                            onSort = { sort = (sort + 1) % 3; showToolMenu = false },
                            onToggleHidden = { hidden = !hidden; showToolMenu = false },
                            onToggleLayout = { onToggleLayout(); showToolMenu = false },
                            onNewFile = { newName = ""; newFileExt = null; showNewFile = true; showToolMenu = false },
                            onNewFolder = { newName = ""; showNewFolder = true; showToolMenu = false },
                            onNewModInfo = createModInfoHere,
                            onFind = { onShowFileSearch(workPath); showToolMenu = false }
                        )
                    }
                }
            }
        }

        // 浏览器主体：仿MT目录=左(第一空间)右(第二空间)双栏；否则单栏
        if (mtLayout) {
            // 未激活栏朝分隔线一侧压一道渐隐阴影（inactiveEdge：0=画在左缘向右渐隐，1=画在右缘向左渐隐，-1=激活栏不画）
            Row(Modifier.weight(1f)) {
                BrowserPane(
                    path = leftPath,
                    isActive = pane == 0,
                    root = rootPath,
                    projectCanonical = projectCanonical,
                    refreshTick = browserRefreshTick,
                    sort = sort,
                    hidden = hidden,
                    onActivate = { activatePane(0) },
                    onPathChange = { navigatePane(0, it) },
                    onRefreshNeeded = { browserRefreshTick++ },
                    onFileSelected = onFileSelected,
                    onImageClick = { viewingImage = it },
                    resolveConflict = { s, t, op -> awaitConflict(s, t, op) },
                    modifier = Modifier.weight(1f),
                    paneIndex = 0,
                    otherPanePath = rightPath,
                    inactiveEdge = if (pane == 0) -1 else 1
                )
                // 栏间分隔线：统一用黑色（不跟主题翻转，与同侧阴影同色系）
                Box(Modifier.fillMaxHeight().width(1.dp).background(Color.Black.copy(alpha = 0.18f)))
                BrowserPane(
                    path = rightPath,
                    isActive = pane == 1,
                    root = rootPath,
                    projectCanonical = projectCanonical,
                    refreshTick = browserRefreshTick,
                    sort = sort,
                    hidden = hidden,
                    onActivate = { activatePane(1) },
                    onPathChange = { navigatePane(1, it) },
                    onRefreshNeeded = { browserRefreshTick++ },
                    onFileSelected = onFileSelected,
                    onImageClick = { viewingImage = it },
                    resolveConflict = { s, t, op -> awaitConflict(s, t, op) },
                    modifier = Modifier.weight(1f),
                    paneIndex = 1,
                    otherPanePath = leftPath,
                    inactiveEdge = if (pane == 0) 0 else -1
                )
            }
        } else {
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
        // 未填文件名且选了 .template → 默认 all-units.template（RW 惯例的全局模板文件名）
        val raw = newName.trim().ifEmpty { if (newFileExt == "template") "all-units" else "" }
        val error = validateFileName(raw)
        if (error != null) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val name = if ('.' in raw) raw else (newFileExt?.let { "$raw.$it" } ?: raw)   // 以手输为准；未选格式则不补扩展名
            val target = File(workPath, name)
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
            val target = File(workPath, newName.trim())
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

/**
 * 仿MT目录的单栏容器：紧凑面包屑头 + 文件列表。
 * 点按栏内任意位置（空白/条目/面包屑）都会触发 [onActivate] 使其成为激活栏。
 */
@Composable
private fun BrowserPane(
    path: String,
    isActive: Boolean,
    root: String,
    projectCanonical: String?,
    refreshTick: Int,
    sort: Int,
    hidden: Boolean,
    onActivate: () -> Unit,
    onPathChange: (String) -> Unit,
    onRefreshNeeded: () -> Unit,
    onFileSelected: (String, String) -> Unit,
    onImageClick: (File) -> Unit,
    resolveConflict: suspend (source: File?, target: File, operation: String) -> ConflictAction,
    modifier: Modifier = Modifier,
    paneIndex: Int = -1,
    otherPanePath: String = "",
    inactiveEdge: Int = -1
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            // 该栏发生任何触摸（点击/长按/滚动/拖拽）都视为聚焦：在 Initial 阶段旁听，不消费事件，
            // 不影响列表自身的手势处理。只在双栏下挂载（单栏没有"切换聚焦栏"的概念）。
            .then(
                if (paneIndex >= 0) Modifier.pointerInput(paneIndex) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Press || e.type == PointerEventType.Move) onActivate()
                        }
                    }
                } else Modifier
            )
            .clickable(interactionSource = interaction, indication = null) { onActivate() }
    ) {
        // 激活栏面包屑底色高亮，用于区分当前作用栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = if (isActive) RustedPrimary.copy(alpha = 0.10f) else RustedSurface
        ) {
            BreadcrumbBar(
                path = path,
                rootPath = root,
                projectCanonical = projectCanonical,
                compact = true,
                onPathChange = onPathChange,
                onActivate = onActivate,
                modifier = Modifier.fillMaxWidth()
            )
        }
        BrowserPanel(
            path = path,
            root = root,
            refreshTick = refreshTick,
            onRefreshNeeded = onRefreshNeeded,
            onPathChange = onPathChange,
            onFileSelected = onFileSelected,
            onHome = {},
            onImageClick = onImageClick,
            resolveConflict = resolveConflict,
            modifier = Modifier.weight(1f),
            sort = sort,
            hidden = hidden,
            isActive = isActive,
            compact = true,
            onActivate = onActivate,
            paneIndex = paneIndex,
            otherPanePath = otherPanePath,
            inactiveEdge = inactiveEdge
        )
    }
}

/**
 * 面屑导航条（可复用）：单栏模式下在共享工具栏内，仿MT目录下每栏各一份。
 * 末段点击展开同级文件夹菜单；项目根目录时末段上锁（见 FileBrowserScreen 注释）。
 */
@Composable
private fun BreadcrumbBar(
    path: String,
    rootPath: String,
    projectCanonical: String?,
    compact: Boolean = false,
    onPathChange: (String) -> Unit,
    onActivate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSiblingMenu by remember { mutableStateOf(false) }
    var siblingDirs by remember { mutableStateOf(listOf<File>()) }
    Box(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 只显示相对路径
            val relativePath = if (rootPath.isNotEmpty() && path.startsWith(rootPath)) {
                path.removePrefix(rootPath).removePrefix(File.separator)
            } else {
                // 如果没有 rootPath，显示最后几级目录
                val parts = path.split(File.separator).filter { it.isNotEmpty() }
                if (parts.size > 3) parts.takeLast(3).joinToString(File.separator) else path
            }
            val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }
            // 当前是否正位于项目根目录（此时目录栏末尾段即"项目目录"段）
            val atProjectRoot = projectCanonical != null && try {
                File(path).canonicalPath == projectCanonical
            } catch (_: Exception) { false }
            val segSp = if (compact) 11.sp else 12.sp

            pathParts.forEachIndexed { idx, part ->
                val isLast = idx == pathParts.size - 1
                // 该路径段的绝对路径（rootPath 下的相对前缀拼接）
                val segAbs = if (rootPath.isNotEmpty())
                    rootPath + File.separator + pathParts.take(idx + 1).joinToString(File.separator)
                else ""
                // 上锁：位于项目根目录时，末尾段（项目目录段）点击会展开同级菜单/切项目 → 锁定（点击不展开目录）
                val segLocked = isLast && atProjectRoot
                if (idx > 0) Text("›", fontSize = segSp, color = RustedOnBackground.copy(alpha = 0.25f))
                Box {
                    Surface(
                        modifier = Modifier.clickable(enabled = !segLocked) {
                            onActivate()
                            if (isLast) {
                                // 展开同级文件夹
                                val parentDir = File(path).parentFile
                                siblingDirs = parentDir?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
                                showSiblingMenu = true
                            } else if (rootPath.isNotEmpty()) {
                                onPathChange(segAbs)
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isLast) RustedPrimary.copy(alpha = 0.12f) else Color.Transparent
                    ) {
                        Text(
                            if (part.length > 5) part.take(5) + "…" else part,
                            fontSize = segSp,
                            fontWeight = if (isLast && !atProjectRoot) FontWeight.Medium else FontWeight.Normal,
                            color = if (isLast && !atProjectRoot) RustedPrimary
                            else if (segLocked) RustedOnBackground.copy(alpha = 0.3f)
                            else RustedOnBackground.copy(alpha = 0.65f),
                            fontFamily = AppCodeFontFamily,
                            maxLines = 1,
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
                                            .clickable {
                                                onActivate()
                                                onPathChange(dir.absolutePath)
                                                showSiblingMenu = false
                                            }
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
}

/**
 * 「更多」工具菜单（复用）：排序/筛选/新建文件/新建文件夹/新建 mod-info/查找文件。
 * 单栏模式锚在页面工具栏的更多按钮；仿MT目录下按钮在全局菜单栏（MainApp topBar），
 * 菜单本体仍在本页锚点弹出（toolMenuTick 信号触发）。
 */
@Composable
private fun BrowserToolMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sort: Int,
    hidden: Boolean,
    showModInfoItem: Boolean,
    onSort: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleLayout: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onNewModInfo: () -> Unit,
    onFind: () -> Unit
) {
    StyledPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(155.dp)
    ) {
        val sorts = listOf("名称", "大小", "日期")
        PlainMenuItem(icon = Icons.Default.Sort, title = "${sorts[sort]}", onClick = onSort)
        PlainMenuItem(icon = if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, title = if (hidden) "显示" else "筛选", onClick = onToggleHidden)
        // 双栏 / 单栏切换（取代原开发者模式里的「仿MT目录」开关）
        PlainMenuItem(
            icon = Icons.Default.Splitscreen,
            title = "切换布局",
            onClick = onToggleLayout
        )
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
        PlainMenuItem(icon = Icons.Default.NoteAdd, title = "新建文件", onClick = onNewFile)
        PlainMenuItem(icon = Icons.Default.CreateNewFolder, title = "新建文件夹", onClick = onNewFolder)
        // 仅「项目根目录」（不在子目录内、非项目模式不显示）且还没有 mod-info.txt 时可新建
        // （模组元数据文件，新建后进入可视化编辑）
        if (showModInfoItem) {
            PlainMenuItem(icon = Icons.Default.Description, title = "新建 mod-info.txt", onClick = onNewModInfo)
        }
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
        PlainMenuItem(icon = Icons.Default.Search, title = "查找文件", onClick = onFind)
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
    hidden: Boolean = false,
    isActive: Boolean = true,
    compact: Boolean = false,
    onActivate: () -> Unit = {},
    /** 仿MT双栏：-1=单栏，0=左栏，1=右栏（决定箭头方向与"另一栏"目标） */
    paneIndex: Int = -1,
    /** 仿MT双栏：另一栏当前目录（移动/复制的目标） */
    otherPanePath: String = "",
    /** 仿MT双栏：未激活栏阴影方向（0=左缘向右渐隐，1=右缘向左渐隐，-1=不画） */
    inactiveEdge: Int = -1
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(listOf<File>()) }
    var rename by remember { mutableStateOf<File?>(null) }
    var del by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    // 原地址「粘贴」：先弹命名框，确认后才复制（不经过剪贴板）
    var pasteSource by remember { mutableStateOf<File?>(null) }
    var pasteName by remember { mutableStateOf("") }
    // 移动/复制到另一栏：先弹确认框，把目标目录摆给用户看清
    var transferConfirm by remember { mutableStateOf<TransferConfirm?>(null) }
    var ctxMenuFile by remember { mutableStateOf<File?>(null) }
    var ctxMenuBounds by remember { mutableStateOf<Rect?>(null) }
    // 多选：入口是长按文件条目后悬浮菜单里的「多选」（左滑已不再触发，避免误触）
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var delMulti by remember { mutableStateOf<List<String>>(emptyList()) }
    // 双栏「原地址粘贴」行右端勾选框：勾上后该项改为「复制」（内部剪贴板，支持多选），状态持久化
    var dualPasteAsCopy by remember { mutableStateOf(SettingsManager.dualPanePasteAsCopy) }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedFiles = emptySet()
    }

    fun toggleSelect(path: String) {
        selectedFiles = if (path in selectedFiles) selectedFiles - path else selectedFiles + path
    }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 移动/复制的统一结果提示
    fun reportTransfer(r: TransferOutcome, verb: String, targetDir: String) {
        val dirName = File(targetDir).name.ifEmpty { targetDir }
        when {
            r.error != null -> toast("${verb}失败：${r.error}")
            r.cancelled -> toast("已取消")
            r.success == 0 && r.skipped > 0 -> toast("跳过 ${r.skipped} 项")
            r.skipped > 0 -> toast("已${verb} ${r.success} 项到 $dirName，跳过 ${r.skipped} 项")
            else -> toast("已${verb} ${r.success} 项到 $dirName")
        }
    }

    LaunchedEffect(path, sort, hidden, refreshTick) { files = loadFiles(path, hidden, sort) }
    LaunchedEffect(path) {
        // 双栏时仅激活栏写 lastPath，避免非激活栏覆盖
        if (isActive) SettingsManager.lastPath = path
        // 切换目录时退出多选，避免选中项残留到新目录
        exitMultiSelect()
    }
    // 路径或列表刷新时关闭悬浮菜单（文件位置已失效）
    LaunchedEffect(path, refreshTick) { ctxMenuFile = null; ctxMenuBounds = null }
    // 多选模式下返回键优先退出多选，而不是返回上级目录（双栏时仅激活栏接管返回键）
    BackHandler(enabled = multiSelectMode && isActive) { exitMultiSelect() }

    val shadowColor = Color.Black.copy(alpha = if (ThemeState.isDark) 0.45f else 0.12f)
    Column(
        modifier
            .background(RustedBackground)
            // 未激活栏的层级阴影只压文件列表区域，不盖到顶部目录栏（面包屑）上
            .drawWithContent {
                drawContent()
                if (inactiveEdge >= 0) {
                    val w = 4.dp.toPx()
                    val left = if (inactiveEdge == 0) 0f else size.width - w
                    val brush = if (inactiveEdge == 0)
                        Brush.horizontalGradient(listOf(shadowColor, Color.Transparent), startX = 0f, endX = w)
                    else
                        Brush.horizontalGradient(listOf(Color.Transparent, shadowColor), startX = size.width - w, endX = size.width)
                    drawRect(brush = brush, topLeft = Offset(left, 0f), size = Size(w, size.height))
                }
            }
    ) {
        // 粘贴条只出现在激活栏（粘贴目标=激活栏目录）
        if (isActive && FileClipboardManager.files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(RustedSecondary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${FileClipboardManager.files.size} 项${if (FileClipboardManager.mode == "cut") "（移动）" else "（复制）"}", fontSize = 10.sp, color = RustedSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val srcFiles = FileClipboardManager.files
                    val move = FileClipboardManager.mode == "cut"
                    FileClipboardManager.clear(context)
                    scope.launch {
                        val r = performTransfer(srcFiles, move, path, resolveConflict = resolveConflict)
                        withContext(Dispatchers.Main) {
                            onRefreshNeeded()
                            when {
                                r.error != null -> toast("粘贴失败: ${r.error}")
                                r.cancelled -> toast("已取消粘贴")
                                r.success > 0 || r.skipped > 0 -> toast("成功 ${r.success} 项，跳过 ${r.skipped} 项")
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
                    onActivate()
                    if (multiSelectMode) toggleSelect(f.absolutePath)
                    else if (f.isDirectory) onPathChange(f.absolutePath)
                    else if (isImage) onImageClick(f)
                    else if (ext in INI_EXTENSIONS) onFileSelected(f.name, f.absolutePath)
                },
                onLongClick = { bounds ->
                    onActivate()
                    // 无论是否多选，长按都弹出同样的操作悬浮窗；多选时操作作用于全部已选项
                    ctxMenuFile = f
                    ctxMenuBounds = bounds
                },
                isSelected = multiSelectMode && selectedFiles.contains(f.absolutePath),
                multiSelectMode = multiSelectMode,
                compact = compact
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
                    // 双栏时菜单尾部多一个勾选框，宽度按需放宽（单栏保持原宽）
                    Column(Modifier.width(if (paneIndex >= 0) 148.dp else 132.dp).padding(vertical = 4.dp)) {
                        if (paneIndex >= 0) {
                            // 仿MT双栏：一步到位的方向化操作，目标=另一栏当前目录，全程不经过剪贴板。
                            // 箭头跟着操作栏变：长按左栏 → 指向右；长按右栏 ← 指向左。
                            val sameDir = try {
                                otherPanePath.isNotEmpty() &&
                                    File(otherPanePath).canonicalPath == File(path).canonicalPath
                            } catch (_: Exception) { false }
                            FloatingMenuItem(
                                Icons.Outlined.DriveFileMove,
                                if (paneIndex == 0) "移动 →" else "← 移动",
                                enabled = !sameDir,
                                onClick = {
                                    ctxMenuFile = null; ctxMenuBounds = null
                                    exitMultiSelect()
                                    transferConfirm = TransferConfirm(targets, move = true, targetDir = otherPanePath)
                                }
                            )
                            FloatingMenuItem(
                                Icons.Outlined.ContentCopy,
                                if (paneIndex == 0) "复制 →" else "← 复制",
                                enabled = !sameDir,
                                onClick = {
                                    ctxMenuFile = null; ctxMenuBounds = null
                                    exitMultiSelect()
                                    transferConfirm = TransferConfirm(targets, move = false, targetDir = otherPanePath)
                                }
                            )
                            // 原地址粘贴：给副本命名后再复制（不读剪贴板）；右端勾选框勾上后改为单栏「复制」
                            // （写内部剪贴板 → 激活栏出现粘贴条，可粘到任意目录/另一栏），此时支持多选
                            if (dualPasteAsCopy || singleTarget) {
                                FloatingCheckMenuItem(
                                    icon = if (dualPasteAsCopy) Icons.Outlined.ContentCopy else Icons.Outlined.ContentPaste,
                                    label = if (dualPasteAsCopy) "复制" else "原地址粘贴",
                                    checked = dualPasteAsCopy,
                                    onCheckedChange = { on ->
                                        dualPasteAsCopy = on
                                        SettingsManager.dualPanePasteAsCopy = on
                                    },
                                    onClick = {
                                        ctxMenuFile = null; ctxMenuBounds = null
                                        exitMultiSelect()
                                        if (dualPasteAsCopy) {
                                            // 与单栏「复制」同款：不复制文件，只把选中项放进内部剪贴板
                                            FileClipboardManager.set(context, "copy", targets)
                                        } else {
                                            pasteSource = file
                                            pasteName = file.parentFile?.let { uniqueFile(it, file).name } ?: file.name
                                        }
                                    }
                                )
                            }
                        } else {
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
                        }
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
    // 移动/复制确认框：把目标目录摆清楚（移动是不可逆动作，源会消失）
    transferConfirm?.let { req ->
        val relTarget = if (root.isNotEmpty() && req.targetDir.startsWith(root)) {
            req.targetDir.removePrefix(root).removePrefix(File.separator)
        } else req.targetDir
        val shownTarget = relTarget.ifEmpty { File(req.targetDir).name.ifEmpty { req.targetDir } }
        val what = if (req.srcFiles.size == 1) File(req.srcFiles.first()).name
        else "${File(req.srcFiles.first()).name} 等 ${req.srcFiles.size} 项"
        AlertDialog(
            onDismissRequest = { transferConfirm = null },
            title = { Text(if (req.move) "确认移动" else "确认复制") },
            text = {
                Column {
                    Text("将 $what ${if (req.move) "移动" else "复制"}到：", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        shownTarget,
                        fontFamily = AppCodeFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = RustedPrimary
                    )
                    if (req.move) {
                        Spacer(Modifier.height(8.dp))
                        Text("移动后原位置不再保留", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val src = req.srcFiles
                    val dest = req.targetDir
                    val move = req.move
                    transferConfirm = null
                    scope.launch {
                        val r = performTransfer(src, move, dest, resolveConflict = resolveConflict)
                        withContext(Dispatchers.Main) { onRefreshNeeded(); reportTransfer(r, if (move) "移动" else "复制", dest) }
                    }
                }) { Text(if (req.move) "移动" else "复制") }
            },
            dismissButton = { TextButton(onClick = { transferConfirm = null }) { Text("取消") } }
        )
    }
    // 原地址「粘贴」命名框（仿MT双栏）：先命名，确认后才生成副本
    pasteSource?.let { src ->
        AlertDialog(
            onDismissRequest = { pasteSource = null },
            title = { Text("原地址粘贴") },
            text = {
                Column {
                    Text("给副本起个名字，原文件保持不变", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.65f))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pasteName,
                        onValueChange = { pasteName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = pasteName.trim()
                    val error = validateFileName(raw)
                    val dir = src.parentFile
                    when {
                        error != null -> toast(error)
                        dir == null -> toast("无法确定目标目录")
                        else -> {
                            // 没写扩展名时补回原扩展名（文件夹不涉及）
                            val finalName = if (src.isFile && src.extension.isNotEmpty() && '.' !in raw) "$raw.${src.extension}" else raw
                            val target = File(dir, finalName)
                            if (target.exists()) {
                                toast("已存在同名文件/文件夹")
                            } else {
                                pasteSource = null
                                val source = src
                                scope.launch {
                                    val err = try {
                                        withContext(Dispatchers.IO) {
                                            if (source.isDirectory) source.copyRecursively(target, false)
                                            else source.copyTo(target, false)
                                        }
                                        null
                                    } catch (e: Exception) { e.message ?: "未知错误" }
                                    withContext(Dispatchers.Main) {
                                        onRefreshNeeded()
                                        if (err == null) toast("已生成副本 $finalName") else toast("粘贴失败：$err")
                                    }
                                }
                            }
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pasteSource = null }) { Text("取消") } }
        )
    }
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
    multiSelectMode: Boolean = false,
    compact: Boolean = false
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
    // 仿MT目录双栏（compact）下进一步缩小：图标/文字/内边距全部收窄
    val padV = if (compact) 2.dp else 4.dp
    val padH = if (compact) 6.dp else 10.dp
    val multiSelIcon = if (compact) 16.dp else 18.dp
    val multiSelGap = if (compact) 4.dp else 6.dp
    val iconBox = if (compact) 24.dp else 30.dp
    val icon = if (compact) 18.dp else 22.dp
    // 图片缩略图比普通图标大（MT 管理器同款：图片行预览更醒目），仿MT目录下进一步放大
    val img = if (compact) 28.dp else 22.dp
    val nameSp = if (compact) 12.sp else 14.sp
    val subSp = if (compact) 8.sp else 9.sp
    val nameGap = if (compact) 4.dp else 8.dp

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
        Row(Modifier.fillMaxWidth().padding(horizontal = padH, vertical = padV), verticalAlignment = Alignment.CenterVertically) {
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
                // 图片行外框随缩略图变大，非图片行保持原外框尺寸
                Modifier.size(if (isImage) img else iconBox),
                contentAlignment = Alignment.Center
            ) {
                imageBitmap?.let {
                    androidx.compose.foundation.Image(it, null, Modifier.size(img).clip(RoundedCornerShape(4.dp)))
                } ?: Icon(typeInfo.icon, null, Modifier.size(icon), tint = typeInfo.color)
            }
            Spacer(Modifier.width(nameGap))
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.35f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = effectiveTint)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = effectiveTint)
    }
}

/**
 * 悬浮菜单项 + 右端细边框复选框（双栏「原地址粘贴」行的复制模式开关）。
 * 主区域点击执行 [onClick]；复选框区域点击只切换状态、不执行动作（菜单保持打开）。
 * 描边刻意做成 0.8dp——比 Material Checkbox 的 2dp 细得多。
 */
@Composable
internal fun FloatingCheckMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    tint: Color = RustedOnBackground,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.35f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = effectiveTint)
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, color = effectiveTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier.size(26.dp).clickable(enabled = enabled) { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .border(
                        width = 0.8.dp,
                        color = if (checked) RustedPrimary else RustedOnBackground.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(2.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(Icons.Default.Check, null, Modifier.size(10.dp), tint = RustedPrimary)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
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

/** 移动/复制的结果（剪贴板不参与，提示与刷新由调用方负责） */
private data class TransferOutcome(
    val success: Int = 0,
    val skipped: Int = 0,
    val cancelled: Boolean = false,
    val error: String? = null
)

/** 待确认的移动/复制请求（菜单点下去先确认，再执行） */
private data class TransferConfirm(
    val srcFiles: List<String>,
    val move: Boolean,
    val targetDir: String
)

/**
 * 移动 / 复制 / 原地址粘贴的统一实现（单栏粘贴条、双栏「移动 →」「复制 →」「粘贴」共用）。
 *
 * @param srcFiles 源文件绝对路径
 * @param move true=移动（先拷贝成功再删源，跨分区也安全），false=复制
 * @param targetDir 目标目录
 * @param renameTo 目标文件名（仅原地址粘贴用；非空时不走同名冲突弹窗，遇到占用直接跳过）
 */
private suspend fun performTransfer(
    srcFiles: List<String>,
    move: Boolean,
    targetDir: String,
    renameTo: String? = null,
    resolveConflict: suspend (source: File?, target: File, operation: String) -> ConflictAction
): TransferOutcome = withContext(Dispatchers.IO) {
    var success = 0
    var skipped = 0
    var cancelled = false
    val op = if (move) "cut" else "copy"
    try {
        srcFiles.forEach { s ->
            if (cancelled) return@forEach
            val f = File(s)
            if (!f.exists()) { skipped++; return@forEach }
            // 移动到自身所在目录：没有实际动作（沿用原行为的计数方式）
            if (move && renameTo == null && f.parentFile?.absolutePath == targetDir) { success++; return@forEach }
            val target = File(targetDir, renameTo ?: f.name)
            val finalTarget = if (renameTo != null) {
                if (target.exists()) { skipped++; return@forEach } else target
            } else if (target.exists()) {
                when (resolveConflict(f, target, op)) {
                    ConflictAction.SKIP -> { cancelled = true; return@forEach }
                    ConflictAction.OVERWRITE -> target
                    ConflictAction.KEEP_BOTH -> uniqueFile(File(targetDir), target)
                }
            } else target

            if (move) {
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
        return@withContext TransferOutcome(success, skipped, cancelled, e.message ?: "未知错误")
    }
    TransferOutcome(success, skipped, cancelled, null)
}
