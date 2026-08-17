package com.rwmodstudio.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.FileProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.translation.ProjectRegistry
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.IniImageReader
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
    val englishLine: Int,        // 英文原文行号 1-based
    val englishColumnStart: Int, // 英文原文列起始
    val englishColumnEnd: Int,   // 英文原文列结束
    val englishFullLine: String, // 英文原文完整行
    val chineseFullLine: String, // 中文翻译完整行（从翻译缓存读取，渲染时免翻译）
    val chineseColumnStart: Int, // 中文行匹配位置起始（用于预览聚焦，-1 表示未知）
    val chineseColumnEnd: Int,   // 中文行匹配位置结束（用于预览聚焦，-1 表示未知）
    val matchedKeyword: String,  // 实际匹配到的关键词（英文变体，在文件中真实存在的字符串）
    val userQuery: String        // 用户原始输入的搜索词（用于 UI 回显和替换匹配）
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
    var mods by remember(defaultPath, refresh, pinned) { mutableStateOf(loadMods(dir, pinned)) }
    LaunchedEffect(refresh) { mods = loadMods(dir, pinned) }

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
        ElevatedCard(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeActionButton(Icons.Default.CompareArrows, "差异对比") { onVersionCompare?.invoke() }
                HomeActionButton(Icons.Default.Search, "搜索") { onShowSearch?.invoke() }
                HomeActionButton(Icons.Default.History, "最近") { onShowRecent?.invoke() }
            }
        }
    }

    showDel?.let { f ->
        AlertDialog(onDismissRequest = { showDel = null }, title = { Text("确认删除") }, text = { Text("确定删除「${f.name}」？") },
            confirmButton = { TextButton(onClick = {
                val ok = try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (e: Exception) { false }
                if (!ok) android.widget.Toast.makeText(context, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                showDel = null
                refresh++
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
                        java.util.zip.ZipInputStream(f.inputStream()).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                val o = File(t, e.name)
                                if (e.isDirectory) o.mkdirs()
                                else {
                                    o.parentFile?.mkdirs()
                                    o.outputStream().use { z.copyTo(it) }
                                }
                                z.closeEntry()
                                e = z.nextEntry
                            }
                        }
                    } catch (e: Exception) {
                        success = false
                        errorMsg = e.message
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
            val fileMap = LinkedHashMap<File, MutableList<SearchMatch>>()
            var totalMatches = 0
            run filesLoop@{
                candidates.forEach { file ->
                        ensureActive()
                        if (totalMatches >= SEARCH_RESULT_LIMIT) return@filesLoop
                        if (searchInContent) {
                            try {
                                // 缓存新鲜直接同步读（避免 withContext 开销），不新鲜则翻译并写入
                                val chineseContent = SearchTranslationCache.readCacheSync(file)
                                    ?: SearchTranslationCache.getChineseContent(file)
                                val chineseLines = chineseContent.split("\n")
                                val englishLines = file.readLines()
                                val totalLines = max(englishLines.size, chineseLines.size)
                                // 同一文件内按物理位置去重，避免中英文行内容相同或不同搜索词命中同一位置时重复显示
                                val seenPositions = mutableSetOf<Triple<Int, Int, Int>>()
                                // 对中/英文两套内容都做匹配
                                for (idx in 0 until totalLines) {
                                    ensureActive()
                                    if (totalMatches >= SEARCH_RESULT_LIMIT) break
                                    val engLine = englishLines.getOrElse(idx) { "" }
                                    val chLine = chineseLines.getOrElse(idx) { engLine }
                                    val linesIdentical = chLine == engLine
                                    // 同一行、同一搜索词不重复计入
                                    val matchedQueries = mutableSetOf<String>()
                                    for (sq in searchQueries) {
                                        if (totalMatches >= SEARCH_RESULT_LIMIT) break
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
                                                fileMap.getOrPut(file) { mutableListOf() }.add(
                                                    SearchMatch(idx + 1, finalStart, finalEnd, engLine, chLine, chStart, chEnd, finalKeyword, q)
                                                )
                                                totalMatches++
                                                if (totalMatches >= SEARCH_RESULT_LIMIT) break
                                                searchFrom = matchEnd
                                            }
                                            if (totalMatches >= SEARCH_RESULT_LIMIT) break
                                        }
                                    }
                                }
                            } catch (e: Exception) { Log.w(TAG, "操作失败", e) }
                        } else {
                            // 文件名搜索：用每个搜索词变体匹配文件名
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
                                    fileMap.getOrPut(file) { mutableListOf() }.add(
                                        SearchMatch(0, pos, endPos, file.name, file.name, pos, endPos, sq, q)
                                    )
                                    totalMatches++
                                    break
                                }
                            }
                        }
                    }
            }
            val results = fileMap.map { (file, matches) ->
                val rel = try { file.relativeTo(base).path } catch (_: Exception) { file.name }
                FileSearchResult(file, rel, matches, expanded = true)
            }
            Log.d(TAG, "search: done ${totalMatches} matches in ${System.currentTimeMillis() - startMs}ms")
            withContext(Dispatchers.Main) {
                highlightWords = searchQueries.toList()
                searchResults.clear()
                searchResults.addAll(results)
                totalMatchCount = totalMatches
                searchLoading = false
            }
            // 搜索结束后持久化索引（搜索时翻译的新文件需要保存，避免下次启动全量重译）
            SearchTranslationCache.flushIndex()
        }
    }

    /**
     * 替换单条匹配：在文件中将 matchedKeyword 替换为 replaceText。
     * 替换后从搜索结果中移除该条；该文件所有匹配清完后移除文件条目。
     */
    fun performReplace(match: SearchMatch, fileResult: FileSearchResult) {
        if (replaceText.isBlank() || replaceText == match.matchedKeyword) return
        scope.launch(Dispatchers.IO) {
            try {
                val content = fileResult.file.readText()
                val lines = content.split("\n").toMutableList()
                val idx = match.englishLine - 1
                if (idx in lines.indices) {
                    val line = lines[idx]
                    val expected = match.matchedKeyword
                    val actual = line.substring(
                        match.englishColumnStart.coerceIn(0, line.length),
                        match.englishColumnEnd.coerceIn(0, line.length)
                    )
                    // 验证行内容未变且位置正确（防止并发修改或中文映射偏差导致替换错位）
                    if (line == match.englishFullLine && actual == expected) {
                        lines[idx] = line.replaceRange(match.englishColumnStart, match.englishColumnEnd, replaceText)
                        fileResult.file.writeText(lines.joinToString("\n"))
                    } else if (actual != expected) {
                        // 尝试在当前行重新定位英文关键词
                        val relocated = if (caseSensitive) line.indexOf(expected)
                        else line.indexOf(expected, ignoreCase = true)
                        if (relocated >= 0) {
                            lines[idx] = line.replaceRange(relocated, relocated + expected.length, replaceText)
                            fileResult.file.writeText(lines.joinToString("\n"))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    fileResult.matches.remove(match)
                    totalMatchCount = (totalMatchCount - 1).coerceAtLeast(0)
                    if (fileResult.matches.isEmpty()) {
                        searchResults.remove(fileResult)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "操作失败", e) }
        }
    }

    /** 全部替换：按文件分组，从后往前逐行替换（避免行偏移），写回后清空搜索结果。 */
    fun performReplaceAll() {
        if (replaceText.isBlank() || searchResults.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                // 按文件分组匹配，行号从高到低排序（避免替换导致行偏移）
                val byFile = LinkedHashMap<File, MutableList<SearchMatch>>()
                searchResults.forEach { fr ->
                    fr.matches.filter { replaceText != it.matchedKeyword }
                        .forEach { byFile.getOrPut(fr.file) { mutableListOf() }.add(it) }
                }
                for ((file, matches) in byFile) {
                    matches.sortWith(compareByDescending<SearchMatch> { it.englishLine }.thenByDescending { it.englishColumnStart })
                    val content = file.readText()
                    val lines = content.split("\n").toMutableList()
                    for (match in matches) {
                        val idx = match.englishLine - 1
                        if (idx in lines.indices) {
                            val line = lines[idx]
                            val expected = match.matchedKeyword
                            val actual = line.substring(
                                match.englishColumnStart.coerceIn(0, line.length),
                                match.englishColumnEnd.coerceIn(0, line.length)
                            )
                            if (line == match.englishFullLine && actual == expected) {
                                lines[idx] = line.replaceRange(
                                    match.englishColumnStart, match.englishColumnEnd, replaceText
                                )
                            } else if (actual != expected) {
                                val relocated = if (caseSensitive) line.indexOf(expected)
                                else line.indexOf(expected, ignoreCase = true)
                                if (relocated >= 0) {
                                    lines[idx] = line.replaceRange(
                                        relocated, relocated + expected.length, replaceText
                                    )
                                }
                            }
                        }
                    }
                    file.writeText(lines.joinToString("\n"))
                }
                withContext(Dispatchers.Main) {
                    searchResults.clear()
                    totalMatchCount = 0
                }
            } catch (e: Exception) { Log.w(TAG, "操作失败", e) }
        }
    }

    LaunchedEffect(query, caseSensitive, useRegex, wholeWord, targetPath, searchInContent) { performSearch(query) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = RustedSurface,
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
                    Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                    // 输入区
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = RustedOnBackground),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(RustedPrimary),
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text("搜索", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.35f))
                                }
                                inner()
                            }
                        }
                    )
                    // 右侧切换按钮组
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).clickable { caseSensitive = !caseSensitive }, contentAlignment = Alignment.Center) {
                        Text("Aa", fontSize = 12.sp, fontWeight = if (caseSensitive) FontWeight.Bold else FontWeight.Normal,
                            color = if (caseSensitive) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).clickable { wholeWord = !wholeWord }, contentAlignment = Alignment.Center) {
                        Text("ab", fontSize = 12.sp, fontWeight = if (wholeWord) FontWeight.Bold else FontWeight.Normal,
                            color = if (wholeWord) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).clickable { useRegex = !useRegex }, contentAlignment = Alignment.Center) {
                        Text(".*", fontSize = 12.sp, fontWeight = if (useRegex) FontWeight.Bold else FontWeight.Normal,
                            color = if (useRegex) RustedPrimary else RustedOnBackground.copy(alpha = 0.5f))
                    }
                }
                // ── 替换行（可折叠，左侧缩进对齐三角位） ──
                if (showReplace) {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(28.dp)) // 对齐上方三角位
                        androidx.compose.foundation.text.BasicTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = RustedOnBackground),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(RustedPrimary),
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (replaceText.isEmpty()) {
                                        Text("替换", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.35f))
                                    }
                                    inner()
                                }
                            }
                        )
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
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = RustedBackground),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RustedBackground),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 文件头：展开/折叠箭头 + 文件名 + 匹配数 + 关闭按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                    Modifier.size(14.dp),
                    tint = RustedPrimary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = fileResult.relativePath,
                    fontSize = 12.sp,
                    color = RustedPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${fileResult.matches.size}",
                    fontSize = 11.sp,
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
    // 纯字符串操作：以实际匹配位置为中心截取预览，无翻译调用
    val previewData = remember(match.chineseFullLine, match.chineseColumnStart, match.chineseColumnEnd, highlightWords) {
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
            maxLines = 1,
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

@Composable
private fun HomeActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(38.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = RustedPrimary.copy(alpha = 0.12f), contentColor = RustedPrimary)
        ) {
            Icon(icon, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.7f))
    }
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
    val iniImage = if (isIni) IniImageReader.getImageForFile(file) else null
    var dirImage by remember(file.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var description by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.absolutePath) {
        if (isDir && file.exists()) {
            dirImage = withContext(Dispatchers.IO) {
                file.listFiles()?.firstOrNull { f -> f.extension.lowercase() in IMAGE_EXTENSIONS }?.let {
                    try { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } catch (_: Exception) { null }
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
                .clip(RoundedCornerShape(14.dp))
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
        ElevatedCard(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp, pressedElevation = 4.dp)
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(clr.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    dirImage?.let { Image(it, null, Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(10.dp))) }
                        ?: iniImage?.let { Image(it, null, Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(10.dp))) }
                        ?: Icon(ico, null, Modifier.size(28.dp), tint = clr.copy(alpha = 0.85f))
                }
                Spacer(Modifier.width(14.dp))
                if (isPinned) {
                    Box(
                        Modifier.height(56.dp).width(18.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkBorder,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(18.dp),
                            tint = RustedSecondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = RustedOnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    description?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = RustedOnBackground.copy(alpha = 0.55f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            isDir -> "文件夹"
                            isRw -> "模组包"
                            isIni -> "INI 文件"
                            else -> file.extension.uppercase()
                        },
                        fontSize = 10.sp,
                        color = clr.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onPack != null || onDedup != null || onShare != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        onShare?.let {
                            IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Share, null, Modifier.size(20.dp), tint = RustedPrimary)
                            }
                        }
                        onDedup?.let {
                            IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.FindInPage, null, Modifier.size(20.dp), tint = RustedPrimary)
                            }
                        }
                        onPack?.let {
                            IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Compress, null, Modifier.size(20.dp), tint = RustedSecondary)
                            }
                        }
                    }
                }
            }
        }
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

private fun readModInfoDescription(dir: File): String? =
    readModInfoValue(dir, "description")?.replace("\\n", "\n")

private fun readModInfoTitle(dir: File): String? = readModInfoValue(dir, "title")

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().takeIf { it.isNotBlank() } ?: "mod"

private fun fmt(b: Long) = when { b < 1024 -> "$b B"; b < 1024*1024 -> "${b/1024} KB"; else -> "${"%.1f".format(b/(1024.0*1024.0))} MB" }

private fun shareRwmod(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
