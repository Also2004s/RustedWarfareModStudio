package com.rwmodstudio.ui.screens

import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.RecycleBin
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.translation.ProjectRegistry
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.IniImageReader
import com.rwmodstudio.util.NoMediaHelper
import com.rwmodstudio.util.atomicWriteText
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "HomeSearch"
private val INI_EXTENSIONS = setOf("ini", "template", "txt")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
private const val SEARCH_RESULT_LIMIT = 200

/** 单条匹配：基于英文原文的行列位置（用于跳转），同时携带中文行用于免翻译显示。 */
private data class SearchMatch(
    val englishLine: Int,        // 英文原文行号 1-based（跨行匹配为起始行）
    val englishColumnStart: Int, // 英文原文列起始（跨行匹配为起始列）
    val englishColumnEnd: Int,   // 英文原文列结束（单行匹配有效；跨行匹配为首行片段结束列，用于跳转高亮）
    val englishFullLine: String, // 英文原文完整行
    val chineseFullLine: String, // 中文翻译完整行（从翻译缓存读取，渲染时免翻译）
    val chineseColumnStart: Int, // 中文行匹配位置起始（用于预览聚焦，-1 表示未知）
    val chineseColumnEnd: Int,   // 中文行匹配位置结束（用于预览聚焦，-1 表示未知）
    val matchedKeyword: String,  // 实际匹配到的关键词（英文变体，在文件中真实存在的字符串；跨行匹配为首行片段）
    val userQuery: String,       // 用户原始输入的搜索词（用于 UI 回显和替换匹配）
    val isMultiline: Boolean = false, // 是否跨行匹配（含换行符的查询）
    val englishEndLine: Int = -1,     // 英文原文结束行号（跨行且可定位英文区间时有效，-1 表示无有效英文区间）
    val englishEndColumn: Int = -1,   // 英文原文结束列（englishEndLine 行内，跨行替换用）
    val matchedFullText: String = "", // 英文原文完整匹配文本（跨行替换用；单行匹配为空则用 matchedKeyword）
    val multilineChineseFull: String = "" // 多行匹配的完整中文内容（起始行~结束行拼接，含换行符），仅 isMultiline 有效
)

/** 预览数据：包含截取的文本以及匹配词在截取文本中的偏移 */
private data class PreviewData(
    val text: String,
    val matchOffset: Int // 匹配词在 text 中的起始位置（用于自动滚动）
)

/** 截取预览文本：以匹配位置为中心，超长加省略号，同时返回匹配词在预览中的偏移 */
private fun buildPreview(line: String, matchStart: Int, matchEnd: Int): PreviewData {
    val leftContextChars = 40
    val rightContextChars = 40
    val previewStart = (matchStart - leftContextChars).coerceAtLeast(0)
    val previewEnd = (matchEnd + rightContextChars).coerceAtMost(line.length)
    val sb = StringBuilder()
    if (previewStart > 0) sb.append("…")
    sb.append(line.substring(previewStart, previewEnd))
    if (previewEnd < line.length) sb.append("…")
    val text = sb.toString().trim()
    // 匹配词在截取文本中的偏移：省略号占 1 个字符
    val matchOffset = if (previewStart > 0) (matchStart - previewStart + 1) else (matchStart - previewStart)
    return PreviewData(text, matchOffset.coerceAtLeast(0))
}

/** 生成中文预览：以实际匹配位置为中心截取预览（纯字符串操作，无翻译）
 * 当 matchStart < 0 时回退到用 highlightWords 查找首个命中位置。 */
private fun buildChinesePreview(
    chineseLine: String,
    matchStart: Int,
    matchEnd: Int,
    highlightWords: List<String>
): PreviewData {
    var finalStart = matchStart
    var finalEnd = matchEnd
    if (finalStart < 0 || finalEnd <= finalStart) {
        for (w in highlightWords) {
            if (w.isBlank()) continue
            val pos = chineseLine.indexOf(w, ignoreCase = true)
            if (pos >= 0) {
                finalStart = pos
                finalEnd = pos + w.length
                break
            }
        }
    }
    return if (finalStart >= 0 && finalEnd > finalStart) {
        buildPreview(chineseLine, finalStart, finalEnd)
    } else {
        // 找不到匹配词，截取行首部分
        buildPreview(chineseLine, 0, chineseLine.length.coerceAtMost(1))
    }
}

/** 将「去行尾符文本」中的绝对偏移映射到 1-based 行号 + 0-based 列 */
private fun offsetToLineCol(lines: List<String>, offset: Int): Pair<Int, Int> {
    var remaining = offset
    for ((i, l) in lines.withIndex()) {
        if (remaining <= l.length) return (i + 1) to remaining
        remaining -= l.length + 1
    }
    return lines.size to 0
}

/** 计算 1-based 行号 + 0-based 列在「去行尾符文本」中的绝对偏移 */
private fun offsetOf(lines: List<String>, line: Int, col: Int): Int {
    if (line <= 0) return col.coerceAtLeast(0)
    var offset = 0
    val end = minOf(line - 1, lines.size)
    for (i in 0 until end) offset += lines[i].length + 1
    return offset + col.coerceAtLeast(0)
}

/** 文件分组的搜索结果：可展开/折叠，匹配项可单独删除。
 *  expanded 和 matches 使用可观察状态，确保 Compose 能跟踪变化。 */
private class FileSearchResult(
    val file: File,
    val relativePath: String,
    matches: List<SearchMatch>,
    expanded: Boolean = true
) {
    val matches: androidx.compose.runtime.snapshots.SnapshotStateList<SearchMatch> =
        androidx.compose.runtime.snapshots.SnapshotStateList<SearchMatch>().apply { addAll(matches) }
    var expanded: Boolean by mutableStateOf(expanded)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    defaultPath: String,
    onBrowseFolder: (String) -> Unit,
    onOpenFile: (String, String) -> Unit,
    onOpenFolder: (String, String) -> Unit = { _, p -> onBrowseFolder(p) },
    onDedupFolder: (String, String) -> Unit = { _, _ -> },
    onShowRecent: (() -> Unit)? = null,
    onShowSearch: (() -> Unit)? = null,
    onVersionCompare: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val dir = File(defaultPath)
    val scope = rememberCoroutineScope()
    var pinned by remember(defaultPath) { mutableStateOf(SettingsManager.pinnedHomeItems) }
    var refresh by remember { mutableIntStateOf(0) }
    // 目录列表移入 IO 线程加载：大目录同步 listFiles+排序会卡组合期主线程，先置空再异步填充
    var mods by remember(defaultPath, refresh, pinned) { mutableStateOf(emptyList<File>()) }
    var modsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(defaultPath, refresh, pinned) {
        modsLoading = true
        mods = withContext(Dispatchers.IO) { loadMods(dir, pinned) }
        modsLoading = false
    }

    var showDel by remember { mutableStateOf<File?>(null) }
    var showExt by remember { mutableStateOf<File?>(null) }
    var showPack by remember { mutableStateOf<File?>(null) }
    var packN by remember { mutableStateOf("") }
    var extracting by remember { mutableStateOf(false) }
    var registered by remember(defaultPath) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(defaultPath) { registered = ProjectRegistry.getRegisteredProjects() }

    Column(Modifier.fillMaxSize().background(RustedBackground).padding(horizontal = 16.dp)) {
        if (!dir.exists()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOff, null, Modifier.size(56.dp), tint = RustedError.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text("目录不存在", fontSize = 16.sp, color = RustedError)
                    Text(dir.absolutePath, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.3f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (mods.isEmpty() && modsLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = RustedPrimary)
                    Spacer(Modifier.height(12.dp))
                    Text("加载中...", fontSize = 16.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                }
            }
        } else if (mods.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp), tint = RustedPrimary.copy(alpha = 0.25f))
                    Spacer(Modifier.height(12.dp))
                    Text("空目录", fontSize = 16.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    Text("将模组文件放入 ${dir.name}", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.3f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mods, key = { it.absolutePath }) { file ->
                    val isPinned = file.absolutePath in pinned
                    ModListCard(
                        file = file,
                        isPinned = isPinned,
                        onClick = {
                            if (file.isDirectory) onOpenFolder(file.name, file.absolutePath)
                            else if (file.extension.lowercase() in INI_EXTENSIONS) onOpenFile(file.name, file.absolutePath)
                            else if (file.extension.lowercase() == "rwmod") showExt = file
                        },
                        onLongClick = { showDel = file },
                        onPinToggle = {
                            SettingsManager.togglePinnedHomeItem(file.absolutePath)
                            pinned = SettingsManager.pinnedHomeItems
                        },
                        onPack = if (file.isDirectory) {{
                            val title = readModInfoTitle(file) ?: file.name
                            showPack = file
                            packN = sanitizeFileName(title) + ".rwmod"
                        }} else null,
                        onDedup = if (file.isDirectory && file.name in registered) {{ onDedupFolder(file.name, file.absolutePath) }} else null,
                        onShare = if (file.extension.lowercase() == "rwmod") {{ shareRwmod(context, file) }} else null
                    )
                }
            }
        }

        // 底部操作栏
        HomeDock(
            onVersionCompare = onVersionCompare,
            onShowSearch = onShowSearch,
            onShowRecent = onShowRecent
        )
    }

    showDel?.let { f ->
        // 只有「文件夹」与「.rwmod 包」进回收站；其余零散文件（ini/txt/图片…）直接删除——
        // 回收站列表只列 .rwmod 与文件夹，其它类型进去就成了看不见又占空间的条目。
        val toTrash = f.isDirectory || f.extension.equals("rwmod", ignoreCase = true)
        AlertDialog(onDismissRequest = { showDel = null }, title = { Text("确认删除") },
            text = {
                Text(
                    if (toTrash) "确定删除「${f.name}」？\n删除后可在侧边栏「回收站」中找回。"
                    else "确定删除「${f.name}」？\n该类型不进回收站，删除后无法找回。"
                )
            },
            confirmButton = { TextButton(onClick = {
                // 大文件夹的移动/删除可能较慢，放到 IO 线程执行，避免卡住界面。
                val target = f
                showDel = null
                scope.launch(Dispatchers.IO) {
                    val ok = try {
                        when {
                            toTrash -> RecycleBin.moveToTrash(target)
                            target.isDirectory -> target.deleteRecursively()
                            else -> target.delete()
                        }
                    } catch (e: Exception) {
                        false
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            when {
                                !ok -> "删除失败"
                                toTrash -> "已移入回收站"
                                else -> "已删除"
                            },
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        refresh++
                    }
                }
            }) { Text("删除", color = RustedError) } },
            dismissButton = { TextButton(onClick = { showDel = null }) { Text("取消") } })
    }
    showExt?.let { f ->
        AlertDialog(onDismissRequest = { showExt = null }, title = { Text("解压 .rwmod") },
            text = { Column { Text("解压「${f.name}」到 ${f.nameWithoutExtension}/？"); if (extracting) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator() } } },
            confirmButton = { TextButton(onClick = {
                extracting = true
                scope.launch(Dispatchers.IO) {
                    var success = true
                    var errorMsg: String? = null
                    try {
                        val t = File(f.parentFile, f.nameWithoutExtension)
                        t.mkdirs()
                        val targetRoot = t.absolutePath
                        val maxExtractBytes = 500L * 1024 * 1024   // 解压总量上限，防 zip 炸弹
                        val maxEntries = 10000                      // 条目数上限（大型模组可能数千文件，2000 会误伤合法包）
                        var totalBytes = 0L
                        var entryCount = 0
                        java.util.zip.ZipInputStream(f.inputStream()).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                entryCount++
                                if (entryCount > maxEntries) throw IllegalStateException("压缩包条目过多")
                                val name = e.name.replace('\\', '/')
                                // zip-slip 防护：entry 路径规范化后必须落在目标目录内，拒绝 .. 逃逸/绝对路径
                                val normalized = File(t, name).canonicalPath
                                if (normalized != targetRoot && !normalized.startsWith(targetRoot + File.separator)) {
                                    throw IllegalStateException("非法路径: $name")
                                }
                                val o = File(normalized)
                                if (e.isDirectory) o.mkdirs()
                                else {
                                    o.parentFile?.mkdirs()
                                    o.outputStream().use { os ->
                                        val buf = ByteArray(64 * 1024)
                                        while (true) {
                                            val n = z.read(buf)
                                            if (n < 0) break
                                            totalBytes += n
                                            if (totalBytes > maxExtractBytes) throw IllegalStateException("解压内容过大")
                                            os.write(buf, 0, n)
                                        }
                                    }
                                }
                                z.closeEntry()
                                e = z.nextEntry
                            }
                        }
                        // 屏蔽系统相册扫描解压出的项目图片
                        NoMediaHelper.excludeFromMediaStore(context, t)
                    } catch (e: Exception) {
                        success = false
                        errorMsg = e.message
                        // 解压失败时清理半成品目录，避免残留脏数据
                        try { File(f.parentFile, f.nameWithoutExtension).deleteRecursively() } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        extracting = false
                        showExt = null
                        refresh++
                        if (!success) {
                            android.widget.Toast.makeText(context, "解压失败${errorMsg?.let { ": $it" } ?: ""}", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "解压完成", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, enabled = !extracting) { Text("解压") } },
            dismissButton = { TextButton(onClick = { showExt = null }) { Text("取消") } })
    }
    showPack?.let { d ->
        AlertDialog(onDismissRequest = { showPack = null }, title = { Text("打包为 .rwmod") },
            text = { Column { Text("文件夹: ${d.name}"); Spacer(Modifier.height(6.dp)); OutlinedTextField(value = packN, onValueChange = { packN = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("文件名") }) } },
            confirmButton = { TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    var success = true
                    var errorMsg: String? = null
                    try {
                        ZipOutputStream(FileOutputStream(File(d.parentFile, packN))).use { z ->
                            d.walkTopDown()
                                .filter { x ->
                                    x != d &&
                                    !x.name.startsWith(".") &&
                                    x.extension.lowercase() != "rwmod" &&
                                    !(x.isDirectory && x.listFiles()?.isEmpty() == true)
                                }
                                .forEach { x ->
                                    val en = x.relativeTo(d).path.replace("\\", "/")
                                    if (x.isDirectory) {
                                        z.putNextEntry(ZipEntry("$en/"))
                                        z.closeEntry()
                                    } else {
                                        z.putNextEntry(ZipEntry(en))
                                        x.inputStream().use { it.copyTo(z) }
                                        z.closeEntry()
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        success = false
                        errorMsg = e.message
                    }
                    withContext(Dispatchers.Main) {
                        showPack = null
                        refresh++
                        if (!success) {
                            android.widget.Toast.makeText(context, "打包失败${errorMsg?.let { ": $it" } ?: ""}", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "打包完成", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) { Text("打包") } },
            dismissButton = { TextButton(onClick = { showPack = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSearchScreen(
    defaultPath: String,
    query: String,
    onQueryChange: (String) -> Unit,
    targetPath: String,
    onTargetPathChange: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showFolderFilters: Boolean = true,
    searchInContent: Boolean = true,
    onSearchInContentChange: (Boolean) -> Unit = {},
    showSearchModeToggle: Boolean = true,
    onFilesModified: (Set<String>) -> Unit = {}, // 替换写盘成功后上报被修改的绝对路径集合，供上层失效工作区缓存
    // 替换写盘前回调：供上层把「打开着且未保存」的缓存工作区先落盘，避免替换基于旧盘内容覆盖未保存修改
    onSaveWorkspaceBeforeReplace: suspend (Set<String>) -> Unit = {},
    onJumpToLine: (String, String, Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val engine = remember { TranslationEngine.getInstance() }
    val context = LocalContext.current
    // 搜索选项内部状态（VS Code 风格：输入框内右侧图标切换）
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    // 确保翻译引擎已加载（用于搜索词反向翻译和翻译缓存预热）
    LaunchedEffect(Unit) {
        if (!engine.isLoaded) engine.load(context)
    }
    // 使用 SnapshotStateList 以便 Compose 跟踪展开/删除等局部变化
    val searchResults = remember { mutableStateListOf<FileSearchResult>() }
    var searchLoading by remember { mutableStateOf(false) }
    var activeSearchJob by remember { mutableStateOf<Job?>(null) }
    var totalMatchCount by remember { mutableIntStateOf(0) }
    // 高亮词列表：搜索时在 IO 线程预计算（含双语翻译），渲染时直接用，避免主线程翻译
    var highlightWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var replaceText by rememberSaveable { mutableStateOf("") }
    val folderProjects = remember(defaultPath) {
        File(defaultPath).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun performSearch(q: String) {
        activeSearchJob?.cancel()
        if (q.isBlank()) {
            searchResults.clear()
            totalMatchCount = 0
            highlightWords = emptyList()
            searchLoading = false
            return
        }
        searchLoading = true
        activeSearchJob = scope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val searchQueries = LinkedHashSet<String>()
            searchQueries.add(q)

            // 中英文双向匹配：生成中/英两种语言的副搜索项，同时查找原文和译文
            if (engine.isLoaded) {
                try {
                    val dict = engine.getTranslationDict()
                    // 轻量级文本内翻译：把输入中的可翻译部分分别转中/英
                    val zhVariant = dict.translateInText(q, isEnToZh = true)
                    val enVariant = dict.translateInText(q, isEnToZh = false)
                    if (zhVariant != q && zhVariant.isNotBlank()) searchQueries.add(zhVariant)
                    if (enVariant != q && enVariant.isNotBlank() && enVariant !in searchQueries) searchQueries.add(enVariant)

                    // 纯英文输入时额外用整句翻译生成中文，覆盖 translateInText 处理不了的结构
                    val hasChinese = q.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
                    if (!hasChinese) {
                        val translated = engine.translateToChinese(q, SettingsManager.autoSpace)
                        if (translated != q && translated.isNotBlank() && translated !in searchQueries) searchQueries.add(translated)
                    }
                } catch (e: Exception) { Log.w(TAG, "操作失败", e) }
            }

            val base = File(targetPath).takeIf { it.exists() && it.isDirectory } ?: File(defaultPath)
            Log.d(TAG, "search: q=$q, targetPath=$targetPath, defaultPath=$defaultPath, base=$base exists=${base.exists()}, isDir=${base.isDirectory}")
            val candidates = base.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in INI_EXTENSIONS }
                .toList()
            Log.d(TAG, "search: found ${candidates.size} .ini files, queries=$searchQueries")
            // 单文件搜索：返回该文件命中的匹配列表（线程隔离，多线程安全）
            suspend fun searchOneFile(file: File): List<SearchMatch> {
                ensureActive()
                if (searchInContent) {
                    return try {
                        // 缓存新鲜直接同步读（避免 withContext 开销），不新鲜则翻译并写入
                        val chineseContent = SearchTranslationCache.readCacheSync(file)
                            ?: SearchTranslationCache.getChineseContent(file)
                        val chineseLines = chineseContent.split("\n")
                        val englishLines = file.readLines()
                        val totalLines = max(englishLines.size, chineseLines.size)
                        // 多行查询（含换行符）：对整份内容做跨行匹配。
                        // 优先锚定英文原文（文件真实内容，可替换）；中文译文命中时映射回英文定位，映射失败仅展示。
                        val multilineQueries = searchQueries.filter { it.contains("\n") }
                        if (multilineQueries.isNotEmpty()) {
                            val chineseFull = chineseLines.joinToString("\n")
                            val englishFull = englishLines.joinToString("\n")
                            val multilineMatches = mutableListOf<SearchMatch>()
                            val seenEnglishOffsets = mutableSetOf<Int>() // 英文原文起始偏移，跨语言去重，保证替换不重复
                            val seenChineseOffsets = mutableSetOf<Int>() // 中文匹配起始偏移去重
                            val seenChineseSpans = mutableSetOf<Pair<String, Int>>() // 跨路径位置去重：key=（完整中文内容, 起始行）。
                            // 同一位置（同行起点）的英文/中文路径重复只保留一条；不同位置但内容相同则各自保留（起始行不同）
                            // 根据英文原文偏移区间生成可替换的跨行匹配（锚定英文、携带中文预览列）
                            // 返回 null 表示该位置已在其它路径命中，跳过避免重复显示
                            fun buildEnglishMatch(engStart: Int, engEnd: Int, query: String): SearchMatch? {
                                val (sLine, sCol) = offsetToLineCol(englishLines, engStart)
                                val (eLine, eCol) = offsetToLineCol(englishLines, engEnd)
                                val engLineText = englishLines.getOrElse(sLine - 1) { "" }
                                val chLineText = chineseLines.getOrElse(sLine - 1) { engLineText }
                                val fullMatched = englishFull.substring(engStart, engEnd)
                                // 首行片段（预览/跳转高亮用）
                                val portionLen = minOf(engEnd - engStart, (engLineText.length - sCol).coerceAtLeast(0)).coerceAtLeast(0)
                                val firstLinePortion = fullMatched.substring(0, portionLen)
                                // 中文预览列：把首行片段翻译回中文定位
                                var chS = -1; var chE = -1
                                if (portionLen > 0 && engine.isLoaded) {
                                    try {
                                        val cn = engine.getTranslationDict().translateInText(firstLinePortion, isEnToZh = true)
                                        val pos = if (caseSensitive) chLineText.indexOf(cn) else chLineText.indexOf(cn, ignoreCase = true)
                                        if (pos >= 0) { chS = pos; chE = pos + cn.length }
                                    } catch (_: Exception) {}
                                }
                                // 完整多行中文：英文起始行~结束行对应的中文行拼接（行数可能不同，取安全边界）
                                val zhMultiText = if (eLine > sLine) {
                                    chineseLines.subList(sLine - 1, minOf(eLine, chineseLines.size)).joinToString("\n")
                                } else chLineText
                                if (!seenChineseSpans.add(zhMultiText to sLine)) return null
                                return SearchMatch(
                                    sLine, sCol, sCol + portionLen, engLineText, chLineText,
                                    chS, chE, firstLinePortion, query, isMultiline = true,
                                    englishEndLine = eLine, englishEndColumn = eCol, matchedFullText = fullMatched,
                                    multilineChineseFull = zhMultiText
                                )
                            }
                            // 第一遍：直接匹配英文原文（可替换）
                            for (sq in multilineQueries) {
                                ensureActive()
                                val regex = if (useRegex) {
                                    try {
                                        if (caseSensitive) Regex(sq) else Regex(sq, RegexOption.IGNORE_CASE)
                                    } catch (_: Exception) { null }
                                } else null
                                var searchFrom = 0
                                while (true) {
                                    ensureActive()
                                    val (start, end) = when {
                                        regex != null -> {
                                            val m = regex.find(englishFull, searchFrom) ?: break
                                            m.range.first to m.range.last + 1
                                        }
                                        else -> {
                                            val pos = if (caseSensitive) englishFull.indexOf(sq, searchFrom)
                                            else englishFull.indexOf(sq, searchFrom, ignoreCase = true)
                                            if (pos < 0) break else pos to pos + sq.length
                                        }
                                    }
                                    searchFrom = end
                                    if (!seenEnglishOffsets.add(start)) continue
                                    buildEnglishMatch(start, end, q)?.let { multilineMatches.add(it) }
                                }
                            }
                            // 第二遍：匹配中文译文，映射回英文定位；映射失败仅展示（不可替换）
                            for (sq in multilineQueries) {
                                ensureActive()
                                val regex = if (useRegex) {
                                    try {
                                        if (caseSensitive) Regex(sq) else Regex(sq, RegexOption.IGNORE_CASE)
                                    } catch (_: Exception) { null }
                                } else null
                                var searchFrom = 0
                                while (true) {
                                    ensureActive()
                                    val (start, end) = when {
                                        regex != null -> {
                                            val m = regex.find(chineseFull, searchFrom) ?: break
                                            m.range.first to m.range.last + 1
                                        }
                                        else -> {
                                            val pos = if (caseSensitive) chineseFull.indexOf(sq, searchFrom)
                                            else chineseFull.indexOf(sq, searchFrom, ignoreCase = true)
                                            if (pos < 0) break else pos to pos + sq.length
                                        }
                                    }
                                    searchFrom = end
                                    if (!seenChineseOffsets.add(start)) continue
                                    val (sLine, sCol) = offsetToLineCol(chineseLines, start)
                                    val zhMatched = chineseFull.substring(start, end)
                                    val engLineText = englishLines.getOrElse(sLine - 1) { "" }
                                    val chLineText = chineseLines.getOrElse(sLine - 1) { engLineText }
                                    var mapped = false
                                    if (engine.isLoaded) {
                                        try {
                                            val dict = engine.getTranslationDict()
                                            val enText = dict.translateInText(zhMatched, isEnToZh = false)
                                            if (enText != zhMatched && enText.isNotBlank()) {
                                                val pos = if (caseSensitive) englishFull.indexOf(enText)
                                                else englishFull.indexOf(enText, ignoreCase = true)
                                                if (pos >= 0 && seenEnglishOffsets.add(pos)) {
                                                    // 映射成功；若该中文内容已被其它路径命中则视为已处理，不再走仅展示路径
                                                    mapped = true
                                                    buildEnglishMatch(pos, pos + enText.length, q)?.let { multilineMatches.add(it) }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    if (!mapped) {
                                        // 无法映射到英文原文：仅展示（不可替换）
                                        val portionLen = minOf(end - start, (chLineText.length - sCol).coerceAtLeast(0)).coerceAtLeast(0)
                                        val firstLinePortion = zhMatched.substring(0, portionLen)
                                        val (zELine, _) = offsetToLineCol(chineseLines, end)
                                        val zhMultiText = chineseLines.subList(sLine - 1, minOf(zELine, chineseLines.size)).joinToString("\n")
                                        // 与其它路径命中同一位置（同内容同起始行）时跳过；不同位置的相同内容各自保留
                                        if (seenChineseSpans.add(zhMultiText to sLine)) {
                                            multilineMatches.add(
                                                SearchMatch(
                                                    sLine, -1, -1, engLineText, chLineText,
                                                    sCol, sCol + portionLen, firstLinePortion, q, isMultiline = true,
                                                    multilineChineseFull = zhMultiText
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            return multilineMatches
                        }
                        val matches = mutableListOf<SearchMatch>()
                        // 同一文件内按物理位置去重，避免中英文行内容相同或不同搜索词命中同一位置时重复显示
                        val seenPositions = mutableSetOf<Triple<Int, Int, Int>>()
                        // 对中/英文两套内容都做匹配
                        for (idx in 0 until totalLines) {
                            ensureActive()
                            val engLine = englishLines.getOrElse(idx) { "" }
                            val chLine = chineseLines.getOrElse(idx) { engLine }
                            val linesIdentical = chLine == engLine
                            // 同一行、同一搜索词不重复计入
                            val matchedQueries = mutableSetOf<String>()
                            for (sq in searchQueries) {
                                val regex = if (useRegex) {
                                    try {
                                        if (caseSensitive) Regex(sq) else Regex(sq, RegexOption.IGNORE_CASE)
                                    } catch (_: Exception) { null }
                                } else null
                                val wordRegex = if (!useRegex && wholeWord) {
                                    val escaped = Regex.escape(sq)
                                    if (caseSensitive) Regex("(?<![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])$escaped(?![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])")
                                    else Regex("(?<![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])$escaped(?![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])", RegexOption.IGNORE_CASE)
                                } else null
                                for (targetLine in listOf(engLine, chLine)) {
                                    // 中文行与英文行内容完全一致时，无需重复扫描
                                    if (targetLine !== engLine && linesIdentical) continue
                                    if (sq in matchedQueries) continue
                                    var searchFrom = 0
                                    while (true) {
                                        val (matchStart, matchEnd) = when {
                                            regex != null -> {
                                                val m = regex.find(targetLine, searchFrom) ?: break
                                                m.range.first to m.range.last + 1
                                            }
                                            wordRegex != null -> {
                                                val m = wordRegex.find(targetLine, searchFrom) ?: break
                                                m.range.first to m.range.last + 1
                                            }
                                            else -> {
                                                val pos = if (caseSensitive) targetLine.indexOf(sq, searchFrom)
                                                else targetLine.indexOf(sq, searchFrom, ignoreCase = true)
                                                if (pos < 0) break
                                                pos to pos + sq.length
                                            }
                                        }
                                        // 中文行匹配时，把匹配文本翻译回英文并在英文行中定位，确保替换位置正确
                                        val (finalStart, finalEnd, finalKeyword) = if (targetLine !== engLine) {
                                            val matchedText = targetLine.substring(matchStart, matchEnd)
                                            val translatedKeyword = if (engine.isLoaded) {
                                                try {
                                                    val dict = engine.getTranslationDict()
                                                    dict.translateInText(matchedText, isEnToZh = false)
                                                } catch (_: Exception) { matchedText }
                                            } else matchedText
                                            val ep = if (translatedKeyword != matchedText && translatedKeyword.isNotBlank()) {
                                                if (caseSensitive) engLine.indexOf(translatedKeyword)
                                                else engLine.indexOf(translatedKeyword, ignoreCase = true)
                                            } else -1
                                            if (ep >= 0) {
                                                Triple(ep, ep + translatedKeyword.length, translatedKeyword)
                                            } else {
                                                // 再尝试用当前搜索词的英文形式定位
                                                val enSq = if (engine.isLoaded) {
                                                    try {
                                                        val dict = engine.getTranslationDict()
                                                        dict.translateInText(sq, isEnToZh = false)
                                                    } catch (_: Exception) { sq }
                                                } else sq
                                                val searchWord = if (enSq != sq && enSq.isNotBlank()) enSq else sq
                                                val ep2 = if (caseSensitive) engLine.indexOf(searchWord)
                                                else engLine.indexOf(searchWord, ignoreCase = true)
                                                if (ep2 >= 0) Triple(ep2, ep2 + searchWord.length, searchWord)
                                                else {
                                                    // 无法映射到英文原文，跳过该匹配并继续向后查找，避免在同一位置死循环
                                                    searchFrom = matchEnd
                                                    continue
                                                }
                                            }
                                        } else {
                                            Triple(matchStart, matchEnd, sq)
                                        }
                                        // 计算中文行中的匹配位置，用于搜索结果预览聚焦
                                        val (chStart, chEnd) = if (targetLine !== engLine) {
                                            matchStart to matchEnd
                                        } else {
                                            val matchedText = engLine.substring(matchStart, matchEnd)
                                            val cnKeyword = if (engine.isLoaded) {
                                                try {
                                                    val dict = engine.getTranslationDict()
                                                    dict.translateInText(matchedText, isEnToZh = true)
                                                } catch (_: Exception) { matchedText }
                                            } else matchedText
                                            if (cnKeyword != matchedText && cnKeyword.isNotBlank()) {
                                                val cp = if (caseSensitive) chLine.indexOf(cnKeyword)
                                                else chLine.indexOf(cnKeyword, ignoreCase = true)
                                                if (cp >= 0) cp to cp + cnKeyword.length else -1 to -1
                                            } else {
                                                -1 to -1
                                            }
                                        }
                                        // 按物理位置去重：同一行同一英文列范围只保留一次
                                        val posKey = Triple(idx + 1, finalStart, finalEnd)
                                        if (posKey in seenPositions) {
                                            searchFrom = matchEnd
                                            continue
                                        }
                                        seenPositions.add(posKey)
                                        matchedQueries.add(sq)
                                        matches.add(
                                            SearchMatch(idx + 1, finalStart, finalEnd, engLine, chLine, chStart, chEnd, finalKeyword, q)
                                        )
                                        searchFrom = matchEnd
                                    }
                                }
                            }
                        }
                        matches
                    } catch (e: Exception) { Log.w(TAG, "操作失败", e); emptyList() }
                } else {
                    // 文件名搜索：用每个搜索词变体匹配文件名
                    val matches = mutableListOf<SearchMatch>()
                    for (sq in searchQueries) {
                        val regex = if (useRegex) {
                            try { if (caseSensitive) Regex(sq) else Regex(sq, RegexOption.IGNORE_CASE) }
                            catch (_: Exception) { null }
                        } else null
                        val matched = when {
                            regex != null -> regex.containsMatchIn(file.name)
                            wholeWord -> {
                                val escaped = Regex.escape(sq)
                                val wr = if (caseSensitive) Regex("(?<![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])$escaped(?![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])")
                                    else Regex("(?<![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])$escaped(?![\\w\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff])", RegexOption.IGNORE_CASE)
                                wr.containsMatchIn(file.name)
                            }
                            caseSensitive -> sq in file.name
                            else -> file.name.contains(sq, ignoreCase = true)
                        }
                        if (matched) {
                            val pos = if (caseSensitive) file.name.indexOf(sq) else file.name.indexOf(sq, ignoreCase = true)
                            val endPos = if (pos >= 0) pos + sq.length else pos
                            matches.add(
                                SearchMatch(0, pos, endPos, file.name, file.name, pos, endPos, sq, q)
                            )
                            break
                        }
                    }
                    return matches
                }
            }

            // 多线程开关开启且候选多时并行搜索，否则串行；每文件结果按候选顺序合并，保持结果确定
            val perFileMatches = if (SettingsManager.devParallelPreheat && candidates.size > 1) {
                coroutineScope {
                    candidates.map { file -> async(Dispatchers.Default) { file to searchOneFile(file) } }.awaitAll()
                }
            } else {
                candidates.map { file -> file to searchOneFile(file) }
            }
            val fileMap = LinkedHashMap<File, MutableList<SearchMatch>>()
            var totalMatches = 0
            for ((file, matches) in perFileMatches) {
                if (totalMatches >= SEARCH_RESULT_LIMIT) break
                if (matches.isNotEmpty()) {
                    fileMap[file] = matches.toMutableList()
                    totalMatches += matches.size
                }
            }
            val results = fileMap.map { (file, matches) ->
                val rel = try { file.relativeTo(base).path } catch (_: Exception) { file.name }
                FileSearchResult(file, rel, matches, expanded = true)
            }
            Log.d(TAG, "search: done ${totalMatches} matches in ${System.currentTimeMillis() - startMs}ms")
            withContext(Dispatchers.Main) {
                // 多行查询额外加入首行片段，便于预览高亮
                val highlightWordsList = searchQueries.toMutableList()
                searchQueries.filter { it.contains("\n") }.forEach { highlightWordsList.add(it.substringBefore("\n")) }
                highlightWords = highlightWordsList
                searchResults.clear()
                searchResults.addAll(results)
                totalMatchCount = totalMatches
                searchLoading = false
            }
            // 搜索结束后持久化索引（搜索时翻译的新文件需要保存，避免下次启动全量重译）
            SearchTranslationCache.flushIndex()
        }
    }

    /** 单行替换：逐行处理，兼容 CRLF，带关键词重定位兜底；返回新内容，失败返回 null。 */
    fun applySingleLineReplacement(content: String, match: SearchMatch, replaceText: String, caseSensitive: Boolean): String? {
        val lines = content.split("\n").toMutableList()
        val idx = match.englishLine - 1
        if (idx !in lines.indices) return null
        // 兼容 CRLF：split("\n") 会让行尾残留 \r，比较/定位前剥离，写回时保留，避免 CRLF 文件替换失效
        var line = lines[idx]
        val trailingCR = line.endsWith('\r')
        val clean = if (trailingCR) line.dropLast(1) else line
        val expected = match.matchedKeyword
        val start = match.englishColumnStart.coerceIn(0, clean.length)
        val end = match.englishColumnEnd.coerceIn(0, clean.length)
        val actual = clean.substring(start, end)
        val newLine = when {
            actual == expected ->
                clean.replaceRange(start, end, replaceText)
            else -> {
                // 尝试在当前行重新定位英文关键词（行内容可能在搜索后变化）
                val relocated = if (caseSensitive) clean.indexOf(expected)
                else clean.indexOf(expected, ignoreCase = true)
                if (relocated >= 0)
                    clean.replaceRange(relocated, relocated + expected.length, replaceText)
                else null
            }
        }
        if (newLine == null) return null
        lines[idx] = if (trailingCR) "$newLine\r" else newLine
        return lines.joinToString("\n")
    }

    /** 跨行替换：把匹配的英文区间换算为整份文本的绝对偏移后整段替换（合并多行为一行），兼容 CRLF。 */
    fun applyMultilineReplacement(content: String, match: SearchMatch, replaceText: String, caseSensitive: Boolean): String? {
        val rawLines = content.split("\n")
        val cleanLines = rawLines.map { if (it.endsWith('\r')) it.dropLast(1) else it }
        val cleanFull = cleanLines.joinToString("\n")
        val crlf = content.contains("\r\n")
        val startOffset = offsetOf(cleanLines, match.englishLine, match.englishColumnStart)
        val endOffset = offsetOf(cleanLines, match.englishEndLine, match.englishEndColumn)
        if (startOffset < 0 || endOffset < startOffset || endOffset > cleanFull.length) return null
        var start = startOffset
        var end = endOffset
        val expected = match.matchedFullText
        if (expected.isNotEmpty()) {
            val actual = cleanFull.substring(startOffset, endOffset)
            val ok = if (caseSensitive) actual == expected else actual.equals(expected, ignoreCase = true)
            if (!ok) {
                // 文件在搜索后可能变化，尝试重定位完整匹配文本
                val pos = if (caseSensitive) cleanFull.indexOf(expected)
                else cleanFull.indexOf(expected, ignoreCase = true)
                if (pos < 0) return null
                start = pos
                end = pos + expected.length
            }
        }
        val newCleanFull = cleanFull.replaceRange(start, end, replaceText)
        return if (crlf) newCleanFull.replace("\n", "\r\n") else newCleanFull
    }

    /**
     * 替换单条匹配：单行匹配逐行替换，跨行匹配按英文原文区间整段替换。
     * 替换后从搜索结果中移除该条；该文件所有匹配清完后移除文件条目。
     */
    fun performReplace(match: SearchMatch, fileResult: FileSearchResult) {
        val actualText = match.matchedFullText.ifEmpty { match.matchedKeyword }
        // 文件以英文为基底：把用户输入的替换文本（可能是中文）翻译成英文再写入，只翻译替换文本本身
        val replacement = if (engine.isLoaded && replaceText.isNotBlank()) {
            try { engine.translateToEnglish(replaceText, SettingsManager.autoSpace) }
            catch (_: Exception) { replaceText }
        } else replaceText
        if (replacement.isBlank() || replacement == actualText) return
        // 跨行匹配但无法定位英文原文（仅中文命中且映射失败）时提示并跳过
        if (match.isMultiline && match.englishEndLine <= 0) {
            android.widget.Toast.makeText(context, "该多行匹配无法定位到英文原文，不能替换", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                // 先保存该文件「打开着且未保存」的缓存工作区，再基于最新盘内容替换，避免覆盖未保存修改
                onSaveWorkspaceBeforeReplace(setOf(fileResult.file.absolutePath))
                val content = fileResult.file.readText()
                val newContent = if (match.isMultiline) {
                    applyMultilineReplacement(content, match, replacement, caseSensitive)
                } else {
                    applySingleLineReplacement(content, match, replacement, caseSensitive)
                }
                if (newContent != null) {
                    fileResult.file.atomicWriteText(newContent)
                    // 记录到近期修改（保存历史），与编辑器保存同口径
                    SaveHistoryManager.record(context, fileResult.file.absolutePath, content, newContent)
                }
                withContext(Dispatchers.Main) {
                    if (newContent != null) {
                        onFilesModified(setOf(fileResult.file.absolutePath))
                    } else {
                        android.widget.Toast.makeText(context, "替换失败，可能文件已变化", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    fileResult.matches.remove(match)
                    totalMatchCount = (totalMatchCount - 1).coerceAtLeast(0)
                    if (fileResult.matches.isEmpty()) {
                        searchResults.remove(fileResult)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "操作失败", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "替换失败，可能文件已变化", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 全部替换：按文件分组，按英文原文绝对偏移从后往前整段替换（单行与跨行统一处理），写回后清空搜索结果。 */
    fun performReplaceAll() {
        if (replaceText.isBlank() || searchResults.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                // 文件以英文为基底：把用户输入的替换文本（可能是中文）翻译成英文再写入，只翻译替换文本本身
                val replacement = if (engine.isLoaded) {
                    try { engine.translateToEnglish(replaceText, SettingsManager.autoSpace) }
                    catch (_: Exception) { replaceText }
                } else replaceText
                if (replacement.isBlank()) return@launch
                // 按文件分组匹配（跳过文件名匹配、替换文本与匹配内容相同、以及无法定位英文原文的跨行匹配）
                val byFile = LinkedHashMap<File, MutableList<SearchMatch>>()
                searchResults.forEach { fr ->
                    fr.matches.filter { m ->
                        val target = m.matchedFullText.ifEmpty { m.matchedKeyword }
                        m.englishLine > 0 && replacement != target && !(m.isMultiline && m.englishEndLine <= 0)
                    }.forEach { byFile.getOrPut(fr.file) { mutableListOf() }.add(it) }
                }
                // 先保存这些文件「打开着且未保存」的缓存工作区，再基于最新盘内容替换，避免覆盖未保存修改
                onSaveWorkspaceBeforeReplace(byFile.keys.map { it.absolutePath }.toSet())
                val replacedPaths = mutableSetOf<String>()
                var totalSkipped = 0
                for ((file, matches) in byFile) {
                    val content = file.readText()
                    val rawLines = content.split("\n")
                    val cleanLines = rawLines.map { if (it.endsWith('\r')) it.dropLast(1) else it }
                    val cleanFull = cleanLines.joinToString("\n")
                    val crlf = content.contains("\r\n")
                    // 计算每个匹配的绝对替换区间
                    val ranges = mutableListOf<Triple<Int, Int, SearchMatch>>()
                    for (match in matches) {
                        val endLine = if (match.isMultiline) match.englishEndLine else match.englishLine
                        val endCol = if (match.isMultiline) match.englishEndColumn else match.englishColumnEnd
                        val start = offsetOf(cleanLines, match.englishLine, match.englishColumnStart)
                        val end = offsetOf(cleanLines, endLine, endCol)
                        if (start >= 0 && end >= start && end <= cleanFull.length) {
                            ranges.add(Triple(start, end, match))
                        }
                    }
                    // 从后往前替换，避免偏移失效
                    ranges.sortByDescending { it.first }
                    var result = cleanFull
                    var skipped = 0
                    for ((start, end, match) in ranges) {
                        val expected = match.matchedFullText.ifEmpty { match.matchedKeyword }
                        var s = start
                        var e = end
                        val actual = result.substring(start, end)
                        val ok = if (caseSensitive) actual == expected else actual.equals(expected, ignoreCase = true)
                        if (!ok && expected.isNotEmpty()) {
                            // 文件内容可能在搜索后变化，尝试重定位
                            val pos = if (caseSensitive) result.indexOf(expected)
                            else result.indexOf(expected, ignoreCase = true)
                            if (pos < 0) { skipped++; continue }
                            s = pos
                            e = pos + expected.length
                        }
                        result = result.replaceRange(s, e, replacement)
                    }
                    if (skipped > 0) {
                        Log.w(TAG, "替换跳过 $skipped 条（文件已变化无法重定位）: ${file.name}")
                        totalSkipped += skipped
                    }
                    val afterContent = if (crlf) result.replace("\n", "\r\n") else result
                    file.atomicWriteText(afterContent)
                    replacedPaths.add(file.absolutePath)
                    // 记录到近期修改（保存历史），与编辑器保存同口径
                    SaveHistoryManager.record(context, file.absolutePath, content, afterContent)
                }
                withContext(Dispatchers.Main) {
                    if (replacedPaths.isNotEmpty()) onFilesModified(replacedPaths)
                    searchResults.clear()
                    totalMatchCount = 0
                    if (totalSkipped > 0) {
                        android.widget.Toast.makeText(
                            context,
                            "有 $totalSkipped 条匹配因文件已变化未能替换",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "操作失败", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "替换失败，可能文件已变化", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(query, caseSensitive, useRegex, wholeWord, targetPath, searchInContent) { performSearch(query) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = RustedBackground,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // 搜索栏（VS Code 风格）
            val surfaceColor = if (ThemeState.isDark) Color(0xFF2D2D2D) else Color(0xFFE8E8E8)
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(surfaceColor)
            ) {
                // ── 搜索行 ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // 左侧：替换展开/折叠三角
                    IconButton(
                        onClick = { showReplace = !showReplace },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            if (showReplace) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            null, Modifier.size(16.dp),
                            tint = RustedOnBackground.copy(alpha = 0.4f)
                        )
                    }
                    // 输入区（多行，支持换行符搜索；单行时文字垂直居中与右侧按钮组对齐）
                    Box(
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).heightIn(min = 28.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            maxLines = 3,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = RustedOnBackground
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(RustedPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) {
                                        Text("搜索", fontSize = 14.sp, lineHeight = 18.sp, color = RustedOnBackground.copy(alpha = 0.35f))
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    // 清除按钮：有内容时显示在 Aa 左侧
                    if (query.isNotEmpty()) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { onQueryChange("") }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Clear, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.5f))
                        }
                    }
                    // 右侧切换按钮组
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { caseSensitive = !caseSensitive }, contentAlignment = Alignment.Center) {
                        Text("Aa", fontSize = 12.sp, fontWeight = if (caseSensitive) FontWeight.Bold else FontWeight.Normal,
                            color = if (caseSensitive) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { wholeWord = !wholeWord }, contentAlignment = Alignment.Center) {
                        Text("ab", fontSize = 12.sp, fontWeight = if (wholeWord) FontWeight.Bold else FontWeight.Normal,
                            color = if (wholeWord) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { useRegex = !useRegex }, contentAlignment = Alignment.Center) {
                        Text(".*", fontSize = 12.sp, fontWeight = if (useRegex) FontWeight.Bold else FontWeight.Normal,
                            color = if (useRegex) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                }
                // ── 替换行（可折叠，左侧缩进对齐三角位） ──
                if (showReplace) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Spacer(Modifier.width(28.dp)) // 对齐上方三角位
                        // 输入区（多行，支持换行符替换；单行时文字垂直居中与右侧按钮组对齐）
                        Box(
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).heightIn(min = 28.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = replaceText,
                                onValueChange = { replaceText = it },
                                maxLines = 3,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    color = RustedOnBackground
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(RustedPrimary),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                        if (replaceText.isEmpty()) {
                                            Text("替换", fontSize = 14.sp, lineHeight = 18.sp, color = RustedOnBackground.copy(alpha = 0.35f))
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                        // 清除按钮：有内容时显示
                        if (replaceText.isNotEmpty()) {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { replaceText = "" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Clear, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.5f))
                            }
                        }
                        if (replaceText.isNotBlank() && searchResults.isNotEmpty()) {
                            TextButton(
                                onClick = { performReplaceAll() },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("全部", fontSize = 11.sp, color = RustedPrimary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (showSearchModeToggle) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (ThemeState.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.60f)
                        )
                        .border(
                            1.dp,
                            if (ThemeState.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSearchInContentChange(!searchInContent) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(18.dp), tint = RustedPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text(if (searchInContent) "查找文件内容" else "查找文件名", fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                        Switch(
                            checked = searchInContent,
                            onCheckedChange = { onSearchInContentChange(it) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showFolderFilters && folderProjects.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = targetPath.isEmpty(),
                        onClick = { onTargetPathChange("") },
                        label = { Text("全部", fontSize = 12.sp) },
                        leadingIcon = if (targetPath.isEmpty()) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null
                    )
                    folderProjects.forEach { folder ->
                        val selected = folder.absolutePath == targetPath
                        FilterChip(
                            selected = selected,
                            onClick = { onTargetPathChange(folder.absolutePath) },
                            label = { Text(folder.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (searchLoading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = RustedPrimary, strokeWidth = 2.dp)
                }
            } else if (query.isBlank()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("输入关键词开始搜索", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                }
            } else if (searchResults.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("无结果", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${searchResults.size} 个文件，${totalMatchCount} 处结果",
                        fontSize = 11.sp,
                        color = RustedOnBackground.copy(alpha = 0.5f)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(searchResults, key = { it.file.absolutePath }) { fileResult ->
                        FileSearchResultCard(
                            fileResult = fileResult,
                            highlightWords = highlightWords,
                            caseSensitive = caseSensitive,
                            onJumpToLine = { match ->
                                onJumpToLine(fileResult.file.name, fileResult.file.absolutePath, match.englishLine, match.englishColumnStart, match.englishColumnEnd)
                            },
                            onRemoveMatch = { match ->
                                fileResult.matches.remove(match)
                                totalMatchCount = (totalMatchCount - 1).coerceAtLeast(0)
                                if (fileResult.matches.isEmpty()) {
                                    searchResults.remove(fileResult)
                                }
                            },
                            onRemoveFile = {
                                totalMatchCount = (totalMatchCount - fileResult.matches.size).coerceAtLeast(0)
                                searchResults.remove(fileResult)
                            },
                            onToggleExpand = {
                                fileResult.expanded = !fileResult.expanded
                            },
                            onReplace = { match -> performReplace(match, fileResult) }
                        )
                    }
                }
                if (totalMatchCount >= SEARCH_RESULT_LIMIT) {
                    Text(
                        "结果过多，仅显示前 $SEARCH_RESULT_LIMIT 条",
                        fontSize = 11.sp,
                        color = RustedOnBackground.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * VS Code 风格的文件结果块：文件头（可折叠 + 关闭）+ 匹配行列表。
 */
@Composable
private fun FileSearchResultCard(
    fileResult: FileSearchResult,
    highlightWords: List<String>,
    caseSensitive: Boolean,
    onJumpToLine: (SearchMatch) -> Unit,
    onRemoveMatch: (SearchMatch) -> Unit,
    onRemoveFile: () -> Unit,
    onToggleExpand: () -> Unit,
    onReplace: (SearchMatch) -> Unit
) {
    // 统一 MT 管理器扁平列表风格：结果块无卡片外壳，文件头行 + 匹配行直接平铺
    Column(Modifier.fillMaxWidth()) {
        // 文件头：展开/折叠箭头 + 文件名 + 匹配数 + 关闭按钮
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (fileResult.expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                null,
                Modifier.size(16.dp),
                tint = RustedOnBackground.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Description,
                null,
                Modifier.size(22.dp),
                tint = RustedPrimary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = fileResult.relativePath,
                fontSize = 14.sp,
                color = RustedPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${fileResult.matches.size}",
                fontSize = 9.sp,
                color = RustedOnBackground.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onRemoveFile,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.5f))
            }
        }
        if (fileResult.expanded) {
            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.06f))
            Column {
                fileResult.matches.forEach { match ->
                    MatchLineItem(
                        match = match,
                        highlightWords = highlightWords,
                        caseSensitive = caseSensitive,
                        onJumpToLine = { onJumpToLine(match) },
                        onRemove = { onRemoveMatch(match) },
                        onReplace = { onReplace(match) }
                    )
                }
            }
        }
    }
}

/**
 * 单条匹配行：行号 + 可滚动高亮预览 + 关闭按钮。
 * 预览直接用翻译缓存里的中文行生成（免主线程翻译），高亮词在 IO 线程预计算。
 * 预览支持水平滚动，buildPreview 已以匹配位置为中心截取，通常无需自动滚动。
 */
@Composable
private fun MatchLineItem(
    match: SearchMatch,
    highlightWords: List<String>,
    caseSensitive: Boolean,
    onJumpToLine: () -> Unit,
    onRemove: () -> Unit,
    onReplace: () -> Unit
) {
    // 纯字符串操作：以实际匹配位置为中心截取预览，无翻译调用。
    // 多行匹配直接显示完整中文内容（含换行符），不做单行截取。
    val previewData = remember(
        match.isMultiline, match.multilineChineseFull, match.chineseFullLine,
        match.chineseColumnStart, match.chineseColumnEnd, highlightWords
    ) {
        if (match.isMultiline && match.multilineChineseFull.isNotEmpty())
            PreviewData(match.multilineChineseFull, 0)
        else
            buildChinesePreview(match.chineseFullLine, match.chineseColumnStart, match.chineseColumnEnd, highlightWords)
    }
    val scrollState = rememberScrollState()

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onJumpToLine() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (match.englishLine > 0) {
            Text(
                text = "${match.englishLine}",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.4f),
                modifier = Modifier.width(32.dp)
            )
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = highlightMatch(previewData.text, highlightWords, caseSensitive),
            fontSize = 12.sp,
            color = RustedOnBackground,
            maxLines = if (match.isMultiline) 3 else 1,
            softWrap = false,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        )
        Spacer(Modifier.width(2.dp))
        IconButton(
            onClick = onReplace,
            modifier = Modifier.size(18.dp)
        ) {
            Icon(Icons.Default.FindReplace, "替换", Modifier.size(12.dp), tint = RustedPrimary.copy(alpha = 0.6f))
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(18.dp)
        ) {
            Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = RustedOnBackground.copy(alpha = 0.4f))
        }
    }
}

/**
 * 构建高亮匹配词的 AnnotatedString。
 * 支持多个高亮词（用户输入 + 匹配关键词），任一命中即高亮。
 * 使用 RustedSecondary 作为关键词背景色，类似 VS Code 的搜索高亮。
 */
@Composable
private fun highlightMatch(text: String, queries: List<String>, caseSensitive: Boolean): androidx.compose.ui.text.AnnotatedString {
    val highlightColor = RustedSecondary.copy(alpha = 0.35f)
    val highlightTextColor = RustedOnBackground
    // 找出所有匹配区间
    data class Range(val start: Int, val end: Int)
    val ranges = mutableListOf<Range>()
    for (q in queries) {
        if (q.isBlank()) continue
        var searchFrom = 0
        while (searchFrom < text.length) {
            val pos = if (caseSensitive) text.indexOf(q, searchFrom)
            else text.indexOf(q, searchFrom, ignoreCase = true)
            if (pos < 0) break
            ranges.add(Range(pos, pos + q.length))
            searchFrom = pos + q.length
        }
    }
    // 按起始位置排序，合并重叠区间
    ranges.sortBy { it.start }
    val merged = mutableListOf<Range>()
    for (r in ranges) {
        if (merged.isNotEmpty() && r.start <= merged.last().end) {
            // 合并重叠
            val last = merged.removeAt(merged.lastIndex)
            merged.add(Range(last.start, maxOf(last.end, r.end)))
        } else {
            merged.add(r)
        }
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        for (r in merged) {
            if (r.start > cursor) {
                append(text.substring(cursor, r.start))
            }
            pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor
                )
            )
            append(text.substring(r.start, r.end))
            pop()
            cursor = r.end
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

/** 首页底部一体化分区操作栏：长圆角工具条 + 三段等宽分区（图标+文字内联）+ 段间细分割线 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeDock(
    onVersionCompare: (() -> Unit)?,
    onShowSearch: (() -> Unit)?,
    onShowRecent: (() -> Unit)?
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.25f), ambientColor = Color.Black.copy(alpha = 0.25f))
            .background(RustedSurface, shape)
            .border(1.dp, RustedOnBackground.copy(alpha = 0.10f), shape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeDockItem(Icons.Default.CompareArrows, "差异对比") { onVersionCompare?.invoke() }
        DockDivider()
        HomeDockItem(Icons.Default.Search, "搜索") { onShowSearch?.invoke() }
        DockDivider()
        HomeDockItem(Icons.Default.History, "最近") { onShowRecent?.invoke() }
    }
}

/** 工具条内的单个分区项：图标+标签内联，按压时主色过渡 + 轻微缩放反馈 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.HomeDockItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val itemShape = RoundedCornerShape(18.dp)

    val bgColor by animateColorAsState(
        targetValue = if (pressed) RustedPrimary.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(120), label = "dockBg"
    )
    val iconColor by animateColorAsState(
        targetValue = if (pressed) RustedPrimary else RustedOnBackground.copy(alpha = 0.55f),
        animationSpec = tween(120), label = "dockIcon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (pressed) RustedPrimary.copy(alpha = 0.92f) else RustedOnBackground.copy(alpha = 0.72f),
        animationSpec = tween(120), label = "dockLabel"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120), label = "dockScale"
    )

    Row(
        Modifier
            .weight(1f)
            .clip(itemShape)
            .background(bgColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = labelColor)
    }
}

/** 分区之间的竖向细分割线 */
@Composable
private fun DockDivider() {
    Box(
        Modifier
            .height(18.dp)
            .width(1.dp)
            .background(RustedOnBackground.copy(alpha = 0.10f))
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModListCard(
    file: File,
    isPinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPinToggle: () -> Unit,
    onPack: (() -> Unit)?,
    onDedup: (() -> Unit)?,
    onShare: (() -> Unit)?
) {
    val isDir = file.isDirectory
    val isRw = file.extension.lowercase() == "rwmod"
    val isIni = file.extension.lowercase() in INI_EXTENSIONS
    val ico = when { isDir -> Icons.Default.Folder; isRw -> Icons.Default.Archive; isIni -> Icons.Default.Description; else -> Icons.Default.InsertDriveFile }
    val clr = when { isDir -> RustedAccent; isRw -> RustedSecondary; isIni -> RustedPrimary; else -> RustedOnBackground.copy(alpha = 0.35f) }

    // 首页固定「大」档位：统一 MT 管理器风格（无边框扁平），大图标 + 文件名 + 描述 + 类型行
    val padV = 6.dp
    val iconBox = 56.dp
    val icon = 40.dp
    val nameSp = 16.sp
    val descSp = 11.sp
    val typeSp = 11.sp
    val actionBtn = 36.dp
    val actionIcon = 20.dp
    // 目录预览图与描述（首页显示）
    var dirImage by remember(file.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var description by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.absolutePath) {
        if (isDir && file.exists()) {
            dirImage = withContext(Dispatchers.IO) {
                // 优先 mod-info.txt 的 thumbnail 键，缺失/失败回退到目录第一个图片
                // 统一走采样解码（1024px 上限），避免 2K/4K 大贴图整图解码 OOM
                val fromThumb = resolveThumbnailFile(file)?.let {
                    IniImageReader.decodeSampled(it)?.asImageBitmap()
                }
                fromThumb ?: file.listFiles()?.firstOrNull { f -> f.extension.lowercase() in IMAGE_EXTENSIONS }?.let {
                    IniImageReader.decodeSampled(it)?.asImageBitmap()
                }
            }
            description = withContext(Dispatchers.IO) { readModInfoDescription(file) }
        }
    }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeThreshold = 80.dp
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { swipeThreshold.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(file.absolutePath, isPinned) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offsetX.value < -swipeThresholdPx) {
                                onPinToggle()
                            }
                            offsetX.animateTo(0f, tween(200))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val newValue = (offsetX.value + dragAmount).coerceIn(-swipeThresholdPx * 1.5f, 0f)
                        scope.launch { offsetX.snapTo(newValue) }
                    }
                )
            }
    ) {
        val progress = ((-offsetX.value / swipeThresholdPx).coerceIn(0f, 1f))
        Box(
            Modifier
                .fillMaxSize()
                .background(if (isPinned) RustedError.copy(alpha = 0.08f * progress) else RustedSecondary.copy(alpha = 0.08f * progress)),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = if (isPinned) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (isPinned) "取消置顶" else "置顶",
                modifier = Modifier
                    .padding(end = 24.dp)
                    .alpha(progress),
                tint = if (isPinned) RustedError else RustedSecondary
            )
        }
        // 统一 MT 管理器风格：无边框、无背景色块、无圆角（仅选中时淡色高亮）
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            // 统一 MT 管理器风格两行布局（大/中/小仅尺寸不同）
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = padV),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(iconBox),
                    contentAlignment = Alignment.Center
                ) {
                    if (dirImage != null) {
                        Image(
                            dirImage!!,
                            null,
                            Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Icon(ico, null, Modifier.size(icon), tint = clr.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (isPinned) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkBorder,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(14.dp),
                        tint = RustedSecondary
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        fontSize = nameSp,
                        fontWeight = FontWeight.Medium,
                        color = RustedOnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    description?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            fontSize = descSp,
                            color = RustedOnBackground.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = when {
                            isDir -> "文件夹"
                            isRw -> "模组包"
                            isIni -> "INI 文件"
                            else -> file.extension.uppercase()
                        },
                        fontSize = typeSp,
                        color = clr.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onPack != null || onDedup != null || onShare != null) {
                    Spacer(Modifier.width(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        onShare?.let {
                            IconButton(onClick = it, modifier = Modifier.size(actionBtn)) {
                                Icon(Icons.Default.Share, null, Modifier.size(actionIcon), tint = RustedPrimary)
                            }
                        }
                        onDedup?.let {
                            IconButton(onClick = it, modifier = Modifier.size(actionBtn)) {
                                Icon(Icons.Default.FindInPage, null, Modifier.size(actionIcon), tint = RustedPrimary)
                            }
                        }
                        onPack?.let {
                            IconButton(onClick = it, modifier = Modifier.size(actionBtn)) {
                                Icon(Icons.Default.Compress, null, Modifier.size(actionIcon), tint = RustedSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 生成亚克力磨砂噪点画笔：64x64 位图撒细密颗粒，平铺到卡片表面形成磨砂玻璃质感。
 * white=true 用于深色模式（白颗粒），white=false 用于浅色模式（深灰颗粒）。
 */
private fun buildNoisePaint(white: Boolean): android.graphics.Paint {
    val s = 64
    val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val dot = android.graphics.Paint()
    val rnd = java.util.Random(2024)
    repeat(1600) {
        val a = rnd.nextInt(20) + 6
        dot.color = if (white) android.graphics.Color.argb(a, 255, 255, 255) else android.graphics.Color.argb(a, 32, 32, 32)
        canvas.drawPoint(rnd.nextInt(s).toFloat(), rnd.nextInt(s).toFloat(), dot)
    }
    return android.graphics.Paint().apply {
        shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }
}

private fun loadMods(dir: File, pinned: Set<String>): List<File> =
    dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(
        compareByDescending<File> { it.absolutePath in pinned }
            .thenBy { !it.isDirectory }
            .thenBy { it.name.lowercase() }
    ) ?: emptyList()

private fun readModInfoValue(dir: File, key: String): String? {
    val info = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("mod-info.txt", ignoreCase = true) } ?: return null
    return try {
        val text = info.readText()
        val keyIndex = text.indexOf("$key:", ignoreCase = true)
        if (keyIndex < 0) return null
        val rest = text.substring(keyIndex + "$key:".length).trimStart()
        val raw = if (rest.startsWith("\"\"\"")) {
            val end = rest.indexOf("\"\"\"", startIndex = 3)
            if (end < 0) return null
            rest.substring(3, end)
        } else {
            val lineEnd = rest.indexOf('\n')
            if (lineEnd < 0) rest else rest.substring(0, lineEnd)
        }
        raw.trim().removeSurrounding("\"").removeSurrounding("'")
            .takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

/** 解析 mod-info.txt 中 thumbnail 键指向的图片文件；无值/文件不存在/路径越界返回 null。 */
private fun resolveThumbnailFile(dir: File): File? {
    val value = readModInfoValue(dir, "thumbnail") ?: return null
    val target = File(dir, value)   // 相对路径直接拼，支持子目录 units/xxx.png
    return try {
        val base = dir.canonicalPath
        val abs = target.canonicalPath
        // 防路径逃逸：拒绝绝对路径与 ../ 越界；正常相对路径不受影响
        if (target.isFile && (abs == base || abs.startsWith(base + File.separator))) target else null
    } catch (_: Exception) { null }
}

private fun readModInfoDescription(dir: File): String? =
    readModInfoValue(dir, "description")?.replace("\\n", "\n")

private fun readModInfoTitle(dir: File): String? = readModInfoValue(dir, "title")

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().takeIf { it.isNotBlank() } ?: "mod"

private fun fmt(b: Long) = when { b < 1024 -> "$b B"; b < 1024*1024 -> "${b/1024} KB"; else -> "${"%.1f".format(b/(1024.0*1024.0))} MB" }

private fun shareRwmod(context: android.content.Context, file: File) {
    try {
        // FileProvider 只暴露 RWmod 目录树（安全收窄，见 res/xml/file_paths.xml）。
        // 首页/浏览器分享的 .rwmod 可能位于 RWmod 之外（下载目录、外部项目等），
        // 直接 getUriForFile 会因路径不在暴露范围内抛异常导致分享失败——
        // 外部文件先拷入 RWmod/exports 再分享，保持共享范围收窄的同时保证任意位置分享可用。
        val rwmodRoot = com.rwmodstudio.core.RwmodPaths.rwmodDir.absolutePath
        val shareFile = if (file.absolutePath.startsWith(rwmodRoot)) {
            file
        } else {
            val exportDir = com.rwmodstudio.core.RwmodPaths.exportsDir
            exportDir.mkdirs()
            // exports 是导出/分享区，同名覆盖是预期行为（分享的就是最新文件，旧导出被顶掉），
            // 不要加时间戳后缀——会让导出区堆副本
            val copy = File(exportDir, file.name)
            file.copyTo(copy, overwrite = true)
            copy
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun relativeDirPath(file: File, basePath: String): String {
    val parent = file.parentFile?.absolutePath ?: return ""
    if (basePath.isEmpty()) return parent
    val base = File(basePath).absolutePath
    return when {
        parent.startsWith(base) -> {
            val rel = parent.removePrefix(base).removePrefix(File.separator)
            if (rel.isEmpty()) "." else rel
        }
        else -> {
            val es = android.os.Environment.getExternalStorageDirectory().absolutePath
            if (parent.startsWith(es)) parent.removePrefix(es).removePrefix(File.separator) else parent
        }
    }
}
