package com.rwmodstudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.rwmodstudio.core.InheritanceResolver
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.editor.CURSOR_MARKER
import com.rwmodstudio.editor.EditorToolbarAction
import com.rwmodstudio.editor.ReadOnlyCodeEditor
import com.rwmodstudio.editor.SoraCodeEditor
import com.rwmodstudio.feature.completion.extractFileSymbols
import com.rwmodstudio.feature.completion.mergeCompletionSymbols
import com.rwmodstudio.feature.completion.sectionEnToZh
import com.rwmodstudio.feature.completion.value.ProjectImageCache
import com.rwmodstudio.feature.completion.value.ProjectSoundCache
import com.rwmodstudio.ui.components.*
import com.rwmodstudio.ui.theme.*
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "EditorScreen"

/** 把字体大小规整到 0.5 的倍数，让 Slider 可以一点点调 */
private fun roundFontSize(size: Float): Float = (size * 2f).roundToInt() / 2f

/** 把英文原文的列位置映射到中文显示文本的列位置。
 *  用于搜索结果基于英文原文定位、但编辑器显示中文时的高亮对齐。
 *  策略：取出英文原文该行的关键词，翻译成中文，在中文行中查找位置。
 *  映射失败则返回原始列范围（退化为行内原位置）。 */
private fun mapEnglishColumnsToChineseImpl(
    englishText: String,
    chineseText: String,
    line0: Int,
    englishColStart: Int,
    englishColEnd: Int,
    engine: TranslationEngine
): Pair<Int, Int> {
    try {
        val enLines = englishText.split("\n")
        val cnLines = chineseText.split("\n")
        if (line0 < 0 || line0 >= enLines.size || line0 >= cnLines.size) {
            return englishColStart to englishColEnd
        }
        val enLine = enLines[line0]
        val cnLine = cnLines[line0]
        val cs = englishColStart.coerceIn(0, enLine.length)
        val ce = englishColEnd.coerceIn(cs, enLine.length)
        if (ce <= cs) return cs to ce
        val enKeyword = enLine.substring(cs, ce)
        // 先用词典级文本内翻译把英文关键词转成中文（能处理混合文本，如 self.resource.建造调度）
        val cnKeywordByDict = engine.getTranslationDict().translateInText(enKeyword, isEnToZh = true)
        val cnKeyword = if (cnKeywordByDict != enKeyword && cnKeywordByDict.isNotBlank()) cnKeywordByDict
            else engine.translateLineToChineseForce(enKeyword)
        if (cnKeyword.isBlank() || cnKeyword == enKeyword) {
            // 翻译无变化，直接用英文关键词在中文行查找
            val pos = cnLine.indexOf(enKeyword, ignoreCase = true)
            return if (pos >= 0) pos to (pos + enKeyword.length) else cs to ce
        }
        val pos = cnLine.indexOf(cnKeyword, ignoreCase = true)
        return if (pos >= 0) pos to (pos + cnKeyword.length) else cs to ce
    } catch (_: Exception) {
        return englishColStart to englishColEnd
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    fileName: String, filePath: String, autoWrap: Boolean = true, smartWrap: Boolean = true,
    externalInsertText: String = "",
    externalInsertTick: Int = 0,
    externalReplaceText: String = "",
    externalReplaceTick: Int = 0,
    cachedText: String = "",
    onBack: () -> Unit, onSwitchFile: ((String) -> Unit)? = null, projectRoot: String = "",
    saveAndExit: Boolean = false, onSaveAndExitDone: () -> Unit = {},
    onNavigate: (Screen) -> Unit = {},
    onOpenCoordVisual: ((String) -> Unit)? = null,
    onTextCacheRequest: ((String) -> Unit)? = null,
    onTextChange: ((String) -> Unit)? = null,
    saveTrigger: Int = 0,
    onShowRecent: (() -> Unit)? = null,
    jumpLine: Int = -1,
    jumpTick: Int = 0,
    jumpColumnStart: Int = -1,
    jumpColumnEnd: Int = -1,
    onExternalInsertConsumed: () -> Unit = {},
    onExternalReplaceConsumed: () -> Unit = {},
    rainbowSettingsTick: Int = 0,
    onSaved: ((Boolean) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { TranslationEngine.getInstance() }
    var loaded by remember { mutableStateOf(false) }
    var cp by remember { mutableStateOf<com.rwmodstudio.feature.completion.CompletionProvider?>(null) }
    var customCompletions by remember { mutableStateOf(listOf<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem>()) }
    var nativeCompletions by remember { mutableStateOf(listOf<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem>()) }
    var nativeCustomCompletions by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var extraCustomCompletions by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var rawUserCompletions by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var showCompletionEditor by remember { mutableStateOf(false) }
    var editingCompletion by remember { mutableStateOf<CustomCompletion?>(null) }
    var completionDetailEnabled by remember { mutableStateOf(SettingsManager.completionDetailEnabled) }
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var blockableStringKeys by remember { mutableStateOf(setOf<String>()) }
    var litLines by remember(filePath) { mutableStateOf(setOf<Int>()) }
    var lineOriginalTexts by remember(filePath) { mutableStateOf(mapOf<Int, String>()) }
    // 三表合并视图：值补全属性索引与灯泡类型查询共用
    val mergedTables = remember(rawUserCompletions, nativeCustomCompletions, extraCustomCompletions) { mergeCompletionTables(rawUserCompletions, nativeCustomCompletions, extraCustomCompletions) }
    // 三表变化时重建类型查询并刷新灯泡集合（引擎未加载时返回空集，与原行为一致）
    LaunchedEffect(mergedTables) {
        blockableStringKeys = engine.getBlockableStringKeys(buildTableTypeLookup(mergedTables))
    }
    LaunchedEffect(Unit) {
        // 确保翻译引擎已加载，再调用需要 appContext 的 API
        if (SettingsManager.devTranslationEngine && !engine.isLoaded) {
            engine.load(ctx)
        }
        // 三表始终加载：值补全与行尾灯泡的类型判断都依赖，不能受补全开关影响
        val (userItems, nativeItems, extraItems) = withContext(Dispatchers.IO) {
            Triple(loadUserItems(), loadNativeItemsVerified(engine), loadExtraItemsVerified(ctx, engine))
        }
        rawUserCompletions = userItems
        nativeCustomCompletions = nativeItems
        extraCustomCompletions = extraItems
        loaded = true

        // 后台刷新项目图片路径缓存，避免图片路径值补全在主线程扫描目录
        val imageCachePath = projectRoot.ifEmpty { SettingsManager.defaultPath }
        if (imageCachePath.isNotBlank()) {
            launch(Dispatchers.IO) { ProjectImageCache.refresh(imageCachePath) }
            launch(Dispatchers.IO) { ProjectSoundCache.refresh(imageCachePath) }
        }
        if (SettingsManager.devCompletionProvider) {
            customCompletions = customCompletionsToProviderItems(userItems)
            // 优先读取本地原生表，不存在或为空才重新生成，避免每次启动都覆盖已有 category
            val nativeNames = nativeItems.map { it.name }.toSet()
            nativeCompletions = customCompletionsToProviderItems(
                nativeItems + extraItems.filter { it.name !in nativeNames }
            )
            if (engine.isLoaded) {
                cp = engine.getCompletionProvider(customCompletions, nativeCompletions, completionDetailEnabled,
                    valueSectionProperties = buildValueSectionProperties(mergeCompletionTables(userItems, nativeItems, extraItems)))
            }
        }
    }

    // 与文件内容/编辑状态相关的变量按 filePath key，切换文件时立即重置，避免旧内容串写
    var text by remember(filePath) { mutableStateOf("") }
    // 编辑器实际最新文本（onTextChange 同步维护），保存时直读避免跨线程 runBlocking
    var latestEditorText by remember(filePath) { mutableStateOf("") }
    var englishText by remember(filePath) { mutableStateOf("") }
    var chineseText by remember(filePath) { mutableStateOf("") }
    var showChinese by remember(filePath) { mutableStateOf(true) }
    var switchingLanguage by remember { mutableStateOf(false) }
    var contentLoaded by remember(filePath) { mutableStateOf(false) }
    var isModified by remember(filePath) { mutableStateOf(false) }
    var isEnglish by remember(filePath) { mutableStateOf(false) }
    var currentSection by remember(filePath) { mutableStateOf<String?>(null) }
    // 文件级符号缓存（值补全用）：后台提取，避免每次按键全文扫描
    var fileSymbols by remember(filePath) { mutableStateOf<ProjectTagScanner.ProjectTagInfo?>(null) }
    // 继承链符号（@copyFromSection / 附件(slot=) 只查继承链），与 fileSymbols 同时维护
    var chainSymbols by remember(filePath) { mutableStateOf<ProjectTagScanner.ProjectTagInfo?>(null) }
    var allSections by remember(filePath) { mutableStateOf(listOf<Pair<String, Int>>()) }
    var showSectionJumpDialog by remember(filePath) { mutableStateOf(false) }
    var cursorWord by remember(filePath) { mutableStateOf("") }
    var cursorPos by remember(filePath) { mutableIntStateOf(0) }
    var fontSizeState by remember { mutableFloatStateOf(SettingsManager.fontSize) }
    var activePopup by remember(filePath) { mutableStateOf("") }
    var showMore by remember(filePath) { mutableStateOf(false) }
    var showInheritanceView by remember(filePath) { mutableStateOf(false) }
    var inheritanceText by remember { mutableStateOf("") }
    var showSearch by remember(filePath) { mutableStateOf(false) }
    var showSiblingMenu by remember(filePath) { mutableStateOf(false) }
    var siblingDir by remember(filePath) { mutableStateOf<File?>(null) }
    var siblingDirHistory by remember(filePath) { mutableStateOf(listOf<File>()) }
    var recentTick by remember(filePath) { mutableIntStateOf(0) }
    var hintQuery by remember(filePath) { mutableStateOf("") }
    var hintResults by remember(filePath) { mutableStateOf(listOf<CodeReferenceRepository.PropertyInfo>()) }
    var searchQuery by remember(filePath) { mutableStateOf(SettingsManager.lastSearchQuery) }
    var replaceText by remember(filePath) { mutableStateOf(SettingsManager.lastReplaceText) }
    var searchMatchCount by remember(filePath) { mutableIntStateOf(0) }
    var searchCurrentIndex by remember(filePath) { mutableIntStateOf(-1) }

    // 查找替换内容持久化：500ms 防抖写入 SettingsManager
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }
            .debounce(500)
            .collect { SettingsManager.lastSearchQuery = it }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { replaceText }
            .debounce(500)
            .collect { SettingsManager.lastReplaceText = it }
    }
    val context = LocalContext.current
    var insertTextRequest by remember(filePath) { mutableStateOf("") }
    var insertTextTick by remember(filePath) { mutableIntStateOf(0) }
    var resetTextRequest by remember(filePath) { mutableStateOf<Pair<String, Int>?>(null) }
    var resetTextTick by remember(filePath) { mutableIntStateOf(0) }
    var editorRef by remember(filePath) { mutableStateOf<CodeEditor?>(null) }

    // 外部触发插入（如调色盘颜色值）
    LaunchedEffect(externalInsertTick) {
        if (externalInsertTick > 0 && externalInsertText.isNotEmpty()) {
            insertTextRequest = externalInsertText
            insertTextTick++
            onExternalInsertConsumed()
        }
    }
    // 外部触发整文本替换（如坐标可视化反写代码）
    LaunchedEffect(externalReplaceTick) {
        if (externalReplaceTick > 0 && externalReplaceText.isNotEmpty() && externalReplaceText != text) {
            text = externalReplaceText
            latestEditorText = externalReplaceText
            isModified = true
            if (showChinese) chineseText = externalReplaceText else englishText = externalReplaceText
            resetTextTick++
            resetTextRequest = externalReplaceText to resetTextTick
            onExternalReplaceConsumed()
        }
    }
    // 从搜索/近期修改跳转：内容加载完成后定位到目标行，若带列范围则选中匹配区域
    // 搜索结果基于英文原文（1-based 行号），编辑器可能显示中文，需要做位置映射
    LaunchedEffect(jumpTick, contentLoaded, editorRef) {
        if (jumpTick > 0 && jumpLine >= 0 && contentLoaded) {
            editorRef?.let { ed ->
                // 搜索结果行号是 1-based，编辑器是 0-based
                val target0 = (jumpLine - 1).coerceAtLeast(0).coerceIn(0, (ed.text.lineCount - 1).coerceAtLeast(0))
                if (jumpColumnStart >= 0 && jumpColumnEnd > jumpColumnStart) {
                    val lineLen = ed.text.getColumnCount(target0)
                    // 若当前显示中文，尝试把英文列位置映射到中文显示位置
                    val (mappedCs, mappedCe) = if (showChinese && engine.isLoaded) {
                        mapEnglishColumnsToChineseImpl(englishText, chineseText, target0, jumpColumnStart, jumpColumnEnd, engine)
                    } else {
                        jumpColumnStart to jumpColumnEnd
                    }
                    val cs = mappedCs.coerceIn(0, lineLen)
                    val ce = mappedCe.coerceIn(cs, lineLen)
                    if (ce > cs) {
                        ed.setSelectionRegion(target0, cs, target0, ce)
                    } else {
                        ed.setSelection(target0, cs)
                    }
                } else {
                    ed.setSelection(target0, 0)
                }
                // 确保选区位置可见并重绘高亮
                ed.ensureSelectionVisible()
                ed.invalidate()
            }
        }
    }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var customSymbols by remember {
        val saved = SettingsManager.customSymbolsJson
        mutableStateOf(if (saved.isNotEmpty()) {
            saved.split("||").map { pair -> val parts = pair.split("|"); if (parts.size == 2) parts[0] to parts[1] else "=" to "=" }
        } else listOf("=" to "=", ":" to ":", ";" to ";", "#" to "#", "[" to "[", "]" to "]", "," to ",", "." to ".", "\"" to "\""))
    }
    var showSymbolEditor by remember { mutableStateOf(false) }
    var editingSymbolLabel by remember { mutableStateOf("") }
    // 输出值用 TextFieldValue 以便记录/控制输入框光标，支持「在光标处插入 ≡」
    var editingSymbolValue by remember { mutableStateOf(TextFieldValue("")) }
    var editingSymbolIndex by remember { mutableIntStateOf(-1) }
    var completionFilter by remember { mutableStateOf<Map<String, Set<String>>>(SettingsManager.loadAllSectionFilters()) }
    var showCompletionFilter by remember { mutableStateOf(false) }
    var autoSave by remember { mutableStateOf(SettingsManager.autoSave) }
    var autoSpace by remember { mutableStateOf(SettingsManager.autoSpace) }
    var showBackConfirm by remember { mutableStateOf(false) }

    // 查找替换由 sora-editor 原生 Searcher 处理
    LaunchedEffect(searchQuery, showSearch, editorRef) {
        editorRef?.let { editor ->
            if (!showSearch) {
                try {
                    editor.searcher.stopSearch()
                } catch (e: Exception) {
                    Log.e(TAG, "stopSearch failed on hide", e)
                }
                searchMatchCount = 0
                searchCurrentIndex = -1
                return@let
            }
            if (searchQuery.isEmpty()) {
                try {
                    editor.searcher.stopSearch()
                } catch (e: Exception) {
                    Log.e(TAG, "stopSearch failed on empty query", e)
                }
                searchMatchCount = 0
                searchCurrentIndex = -1
                return@let
            }
            try {
                editor.searcher.stopSearch()
                editor.searcher.search(searchQuery, EditorSearcher.SearchOptions(true, false))
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                searchMatchCount = 0
                searchCurrentIndex = -1
            }
        }
    }

    val recent = remember(filePath, recentTick) {
        SettingsManager.recentFiles
            .filter { it != filePath && (projectRoot.isEmpty() || it.startsWith(projectRoot)) }
            .take(5)
    }
    val homeDir = remember { projectRoot.ifEmpty { SettingsManager.defaultPath } }
    // 当前文件所属的项目扫描根：文件在 homeDir 下则用 homeDir，否则用文件所在目录
    val scanRoot = remember(filePath, homeDir) {
        if (homeDir.isNotEmpty() && filePath.startsWith(homeDir)) homeDir
        else File(filePath).parentFile?.absolutePath ?: homeDir
    }

    fun saveSymbols() { SettingsManager.customSymbolsJson = customSymbols.joinToString("||") { "${it.first}|${it.second}" } }
    fun showSaveToast(ok: Boolean) {
        Toast.makeText(ctx, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
    }
    suspend fun saveSync(caller: String = "unknown"): Boolean = withContext(Dispatchers.IO) {
        if (!contentLoaded) {
            Log.w(TAG, "[$caller] Skip save before content loaded: $filePath")
            withContext(Dispatchers.Main) { onSaved?.invoke(true) }
            return@withContext false
        }
        // 快照化目标路径与内容，避免重组过程中状态被覆盖导致串写
        val targetPath = filePath
        // 优先读取编辑器实际内容（onTextChange 同步维护），避免跨线程 runBlocking
        val currentText = latestEditorText.ifEmpty { text }
        val contentToSave = try {
            if (isEnglish && showChinese) engine.translateToEnglish(currentText, autoSpace, litLines)
            else currentText
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] Failed to prepare content for $targetPath", e)
            withContext(Dispatchers.Main) { showSaveToast(false); onSaved?.invoke(false) }
            return@withContext false
        }
        try {
            Log.d(TAG, "[$caller] Saving file=$targetPath length=${contentToSave.length}")
            val targetFile = java.io.File(targetPath)
            val beforeContent = if (targetFile.exists()) {
                try { targetFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            } else ""
            targetFile.writeText(contentToSave, Charsets.UTF_8)
            withContext(Dispatchers.Main) {
                if (targetPath == filePath) {
                    isModified = false
                } else {
                    Log.w(TAG, "[$caller] filePath changed during save: expected=$targetPath actual=$filePath")
                }
                showSaveToast(true)
                onSaved?.invoke(true)
            }
            SaveHistoryManager.record(ctx, targetPath, beforeContent, contentToSave)
            Log.d(TAG, "[$caller] Saved successfully: $targetPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] Failed to save $targetPath", e)
            withContext(Dispatchers.Main) { showSaveToast(false); onSaved?.invoke(false) }
            false
        }
    }
    fun copyToClip(l: String, v: String) { clipboard.setPrimaryClip(ClipData.newPlainText(l, v)) }
    fun safeEditorOp(op: (CodeEditor) -> Unit) {
        editorRef?.let { op(it) }
    }
    fun safeSearcherOp(op: (io.github.rosemoe.sora.widget.EditorSearcher) -> Unit) {
        editorRef?.searcher?.let {
            try {
                if (it.hasQuery()) op(it)
            } catch (e: Exception) {
                Log.e(TAG, "Searcher operation failed", e)
            }
        }
    }

    LaunchedEffect(
        completionDetailEnabled,
        SettingsManager.devValueCompletion,
        SettingsManager.devValueCompletionBool,
        SettingsManager.devValueCompletionLogicBoolean,
        SettingsManager.devValueCompletionEnum,
        SettingsManager.devValueCompletionImage,
        SettingsManager.devValueCompletionUnitSpawn,
        SettingsManager.devValueCompletionAutoTriggerOnEvent
    ) {
        if (SettingsManager.devCompletionProvider && cp != null && engine.isLoaded) {
            cp = engine.getCompletionProvider(customCompletions, nativeCompletions, completionDetailEnabled,
                valueSectionProperties = buildValueSectionProperties(mergedTables))
        }
    }

    // 立即解析节名（文件加载/切换时使用）
    fun parseSectionsImmediate(currentText: String, currentCursor: Int = 0, caller: String = "unknown") {
        if (!SettingsManager.devSectionParsing) return
        try {
            val lines = currentText.lines()
            val sectionLines = mutableListOf<Pair<String, Int>>()
            val seen = mutableSetOf<String>()
            lines.forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    val name = trimmed.substring(1, trimmed.length - 1).trim()
                    if (name.isNotEmpty() && name !in seen) {
                        sectionLines.add(name to idx)
                        seen.add(name)
                    }
                }
            }
            allSections = sectionLines
            val cursorLine = currentText.take(currentCursor.coerceAtMost(currentText.length)).count { it == '\n' }
            val newSection = sectionLines.lastOrNull { it.second <= cursorLine }?.first
            currentSection = newSection
            Log.d(TAG, "[$caller] sections=${sectionLines.size}, currentSection=$newSection, cursorLine=$cursorLine")
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] parseSectionsImmediate failed", e)
        }
    }

    // 节解析节流：text/cursor 停止变化 250ms 后再解析，避免连续输入时频繁全量解析
    // 必须用 filePath 做 key，否则切换文件后仍在观察旧状态，导致节名不更新
    LaunchedEffect(filePath) {
        if (SettingsManager.devSectionParsing) {
            snapshotFlow { text to cursorPos }
                .debounce(250)
                .collect { (currentText, currentCursor) ->
                    withContext(Dispatchers.Default) {
                        parseSectionsImmediate(currentText, currentCursor, "debounce")
                    }
                }
        }
    }

    // 文件级符号缓存：文本变化停止 250ms 后在后台提取，值补全直接读缓存，避免每次按键全文扫描
    LaunchedEffect(filePath) {
        snapshotFlow { text }
            .debounce(250)
            .collect { currentText ->
                val (symbols, chain) = withContext(Dispatchers.Default) {
                    // 确保当前项目根目录已被扫描（同 root 只扫一次），再提取符号
                    ProjectTagScanner.scanIfNeeded(File(scanRoot))
                    val chain = InheritanceResolver.resolveSymbols(filePath, homeDir)
                    mergeCompletionSymbols(
                        extractFileSymbols(
                            currentText,
                            sectionToEnglish = { engine.getTranslationDict().getSectionTranslationBack(it) },
                            keyToEnglish = { engine.getTranslationDict().getTranslationBack(it) }
                        ),
                        chain
                    ) to chain
                }
                fileSymbols = symbols
                chainSymbols = chain
            }
    }

    // 文件符号缓存变化时同步到补全 Provider
    LaunchedEffect(fileSymbols) {
        cp?.fileSymbols = fileSymbols
    }
    LaunchedEffect(chainSymbols) {
        cp?.chainSymbols = chainSymbols
    }

    LaunchedEffect(filePath) {
        if (filePath.isEmpty()) return@LaunchedEffect
        if (SettingsManager.devRecentFiles) {
            SettingsManager.addRecentFile(filePath)
        }
        recentTick++; contentLoaded = false
        showChinese = true
        isModified = false
        text = ""
        englishText = ""
        chineseText = ""
        isEnglish = false
        currentSection = null
        litLines = emptySet()
        lineOriginalTexts = emptyMap()
        // 优先恢复缓存内容（从 Settings/翻译库/自定义补全 返回时）
        if (cachedText.isNotEmpty()) {
            text = cachedText
            resetTextTick++
            resetTextRequest = cachedText to resetTextTick
            isModified = true
            if (SettingsManager.devTranslationEngine && !engine.isLoaded) {
                engine.load(ctx)
            }
            if (SettingsManager.devTranslationEngine && engine.isLoaded && engine.isEnglishIni(cachedText)) {
                englishText = cachedText
                chineseText = withContext(Dispatchers.Default) { engine.translateToChinese(cachedText, autoSpace) }
                isEnglish = true
                showChinese = false
            } else if (SettingsManager.devTranslationEngine && engine.isLoaded) {
                chineseText = cachedText
                englishText = withContext(Dispatchers.Default) { engine.translateToEnglish(cachedText, autoSpace, litLines) }
                isEnglish = true
                showChinese = true
            } else {
                englishText = cachedText
                chineseText = cachedText
                isEnglish = false
                showChinese = true
            }
            contentLoaded = true
            parseSectionsImmediate(text, 0, "cached:$filePath")
            return@LaunchedEffect
        }
        if (!SettingsManager.devFileLoading) {
            contentLoaded = true
            return@LaunchedEffect
        }
        try {
            // 首次打开时翻译引擎可能还在后台加载，等待完成后再判断是否需要翻译
            if (SettingsManager.devTranslationEngine && !engine.isLoaded) {
                engine.load(ctx)
            }
            val fileContent = withContext(Dispatchers.IO) { File(filePath).readText(Charsets.UTF_8) }
            val userItems = withContext(Dispatchers.IO) { loadUserItems() }
            val nativeItems = withContext(Dispatchers.IO) { loadNativeItemsVerified(engine) }
            val extraItems = withContext(Dispatchers.IO) { loadExtraItemsVerified(ctx, engine) }
            // 三表始终加载（灯泡类型判断依赖），cp 构建仍受补全开关控制
            rawUserCompletions = userItems
            nativeCustomCompletions = nativeItems
            extraCustomCompletions = extraItems
            if (SettingsManager.devCompletionProvider) {
                customCompletions = customCompletionsToProviderItems(userItems)
                val nativeNames = nativeItems.map { it.name }.toSet()
                nativeCompletions = customCompletionsToProviderItems(
                    nativeItems + extraItems.filter { it.name !in nativeNames }
                )
            }

            if (SettingsManager.devTranslationEngine && engine.isLoaded && engine.isEnglishIni(fileContent)) {
                englishText = fileContent
                chineseText = SearchTranslationCache.getChineseContent(File(filePath))
                isEnglish = true
                text = chineseText
            } else if (SettingsManager.devTranslationEngine && engine.isLoaded) {
                // 引擎已加载但 isEnglishIni 检测失败，仍标记为英文以确保反向翻译正常
                englishText = fileContent
                chineseText = SearchTranslationCache.getChineseContent(File(filePath))
                isEnglish = true
                text = chineseText
            } else {
                englishText = fileContent
                chineseText = fileContent
                isEnglish = false
                text = fileContent
            }
            if (SettingsManager.devCompletionProvider && engine.isLoaded) {
                cp = engine.getCompletionProvider(customCompletions, nativeCompletions, completionDetailEnabled,
                    valueSectionProperties = buildValueSectionProperties(mergeCompletionTables(userItems, nativeItems, extraItems)))
            }
            // 文件加载完成后立即解析节名，避免 debounce 延迟
            parseSectionsImmediate(text, 0, "fileLoad:$filePath")
            // 文件加载完成后立即提取符号缓存，避免首次补全回退全文扫描
            val (symbols, chain) = withContext(Dispatchers.Default) {
                // 确保当前项目根目录已被扫描（同 root 只扫一次），再提取符号
                ProjectTagScanner.scanIfNeeded(File(scanRoot))
                val chain = InheritanceResolver.resolveSymbols(filePath, homeDir)
                mergeCompletionSymbols(
                    extractFileSymbols(
                        text,
                        sectionToEnglish = { engine.getTranslationDict().getSectionTranslationBack(it) },
                        keyToEnglish = { engine.getTranslationDict().getTranslationBack(it) }
                    ),
                    chain
                ) to chain
            }
            fileSymbols = symbols
            chainSymbols = chain
            resetTextTick++
            resetTextRequest = text to resetTextTick
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file: $filePath", e)
            android.widget.Toast.makeText(ctx, "加载失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            text = e.message ?: "error"
            resetTextTick++
            resetTextRequest = text to resetTextTick
        }
        contentLoaded = true
    }

    BackHandler {
        if (isModified) {
            showBackConfirm = true
        } else {
            onBack()
        }
    }

    // 保存并退出
    LaunchedEffect(saveAndExit) {
        if (saveAndExit) {
            if (isModified) saveSync("SaveAndExit")
            onSaveAndExitDone()
        }
    }

    // 生命周期监听：应用进入后台时自动保存
    val lifecycleOwner = LocalLifecycleOwner.current

    // 外部触发保存（如抽屉导航离开编辑器时的自动保存）
    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) {
            lifecycleOwner.lifecycleScope.launch { saveSync("DrawerAutoSave") }
        }
    }

    // 编辑器加载完成后把初始文本同步给外部 holder，确保抽屉导航离开时缓存正确
    LaunchedEffect(contentLoaded, text) {
        if (contentLoaded && text.isNotEmpty()) {
            onTextChange?.invoke(text)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            Log.d(TAG, "Lifecycle event: $event, filePath=$filePath")
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (isModified && autoSave && SettingsManager.devSaveOnPause) {
                        // 非阻塞后台保存，避免 ON_PAUSE 阻塞主线程导致 ANR/死机
                        lifecycleOwner.lifecycleScope.launch {
                            try {
                                saveSync("LifecycleOnPause")
                            } catch (e: Exception) {
                                Log.e(TAG, "LifecycleOnPause save failed", e)
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // 回到前台时重新解析节名，防止后台进程回收后状态异常
                    parseSectionsImmediate(text, cursorPos, "LifecycleOnResume")
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Column布局：adjustResize 会让系统自动缩小窗口，底部栏自然在键盘上方
    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        // Tab bar
        if (SettingsManager.devTabBar && onSwitchFile != null && recent.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(RustedSurface.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                (listOf(filePath) + recent).distinct().forEach { path ->
                    val name = File(path).name; val active = path == filePath
                    if (active) {
                        val siblingFiles = remember(siblingDir) {
                            siblingDir?.listFiles()
                                ?.filter {
                                    !it.name.startsWith(".") &&
                                    (it.isDirectory || it.extension.lowercase() in setOf("ini", "template"))
                                }
                                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                                ?: emptyList()
                        }
                        Box {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 2.dp)) {
                                TextButton(onClick = {
                                    siblingDir = File(filePath).parentFile
                                    siblingDirHistory = emptyList()
                                    showSiblingMenu = true
                                }, Modifier.height(26.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.textButtonColors(contentColor = RustedPrimary)) { Text(name, fontSize = 12.sp, maxLines = 1) }
                                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(RustedPrimary))
                            }
                            DropdownMenu(expanded = showSiblingMenu, onDismissRequest = { showSiblingMenu = false }) {
                                val atProjectRoot = siblingDir?.let { cur ->
                                    projectRoot.isNotEmpty() && cur.absolutePath == File(projectRoot).absolutePath
                                } ?: false
                                if (siblingDir != null && !atProjectRoot &&
                                    (siblingDirHistory.isNotEmpty() || projectRoot.isNotEmpty())
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("← 返回上一级", fontSize = 12.sp) },
                                        onClick = {
                                            siblingDir = if (siblingDirHistory.isNotEmpty()) siblingDirHistory.last() else siblingDir?.parentFile
                                            if (siblingDirHistory.isNotEmpty()) siblingDirHistory = siblingDirHistory.dropLast(1)
                                        },
                                        leadingIcon = { Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp)) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                    )
                                    HorizontalDivider()
                                }
                                siblingFiles.forEach { f ->
                                    val isCurrent = f.absolutePath == filePath
                                    DropdownMenuItem(
                                        text = { Text(f.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            if (f.isDirectory) {
                                                val dir = siblingDir ?: return@DropdownMenuItem
                                                siblingDirHistory = siblingDirHistory + dir
                                                siblingDir = f
                                            } else {
                                                showSiblingMenu = false
                                                if (isModified) {
                                                    scope.launch { saveSync("TabSwitch"); onSwitchFile(f.absolutePath) }
                                                } else {
                                                    onSwitchFile(f.absolutePath)
                                                }
                                            }
                                        },
                                        enabled = if (f.isDirectory) true else !isCurrent && f.extension.lowercase() in setOf("ini", "template"),
                                        leadingIcon = { Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Description, null, Modifier.size(16.dp)) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 2.dp)) {
                            TextButton(onClick = {
                                if (isModified) {
                                    scope.launch {
                                        saveSync("TabSwitch")
                                        onSwitchFile(path)
                                    }
                                } else {
                                    onSwitchFile(path)
                                }
                            }, Modifier.height(26.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.textButtonColors(contentColor = RustedOnBackground.copy(alpha = 0.45f))) { Text(name, fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
            }
        }
        // 节名显示栏
        if (SettingsManager.devSectionBar) {
            Row(
                Modifier.fillMaxWidth().height(18.dp).background(RustedSurface.copy(alpha = 0.5f)).clickable { showSectionJumpDialog = true }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("节: ", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Text(currentSection ?: "无", fontSize = 11.sp, color = RustedPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${allSections.size} 个节", fontSize = 9.sp, color = RustedOnBackground.copy(alpha = 0.35f))
            }
        }
        // Editor - 占满剩余空间
        Box(Modifier.weight(1f)) {
            if (contentLoaded) {
                Box(Modifier.fillMaxSize()) {
                    SoraCodeEditor(
                        text = text,
                        onTextChange = {
                            text = it
                            latestEditorText = it
                            isModified = true
                            onTextChange?.invoke(it)
                        },
                        modifier = Modifier.fillMaxSize(),
                        fontSize = fontSizeState,
                        autoWrap = autoWrap,
                        smartWrap = smartWrap,
                        fontFamily = SettingsManager.editorFontFamily,
                        isDarkTheme = ThemeState.isDark,
                        highlightTheme = ThemeState.highlightTheme,
                        bgColor = ThemeState.bgColor,
                        darkTokenColors = ThemeState.darkTokenColors,
                        customSymbols = customSymbols,
                        completionProvider = cp,
                        currentSectionName = currentSection,
                        sectionFilters = completionFilter,
                        sectionCompletionEnabled = SettingsManager.devSectionCompletion,
                        insertTextRequest = insertTextRequest,
                        insertTextTick = insertTextTick,
                        resetTextRequest = resetTextRequest,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        showBottomToolbar = !showSearch,
                        blocklistEnabled = SettingsManager.translationBlockEnabled && SettingsManager.devLightbulbEnabled,
                        blockableStringKeys = blockableStringKeys,
                        onCursorChange = { cursorPos = it },
                        onSearchResultUpdate = { count, index ->
                            searchMatchCount = count
                            searchCurrentIndex = index
                        },
                        onCanUndoRedoChange = { undo, redo ->
                            canUndo = undo
                            canRedo = redo
                        },
                        onLitLinesChange = { litLines = it },
                        onLightbulbToggle = { line, isLit ->
                            val editor = editorRef
                            if (editor == null || line < 0 || line >= editor.text.lineCount) return@SoraCodeEditor
                            val currentLine = editor.text.getLineString(line)
                            if (isLit) {
                                // 记录点亮前的原文，然后强制翻译
                                lineOriginalTexts = lineOriginalTexts + (line to currentLine)
                                if (SettingsManager.devTranslationEngine && engine.isLoaded) {
                                    val translated = engine.translateLineToChineseForce(currentLine, autoSpace)
                                    editor.text.replace(line, 0, line, editor.text.getColumnCount(line), translated)
                                    // text/isModified/canUndo/canRedo 由 ContentChangeEvent 回调自动更新
                                }
                            } else {
                                // 取消点亮，恢复原文
                                lineOriginalTexts[line]?.let { original ->
                                    editor.text.replace(line, 0, line, editor.text.getColumnCount(line), original)
                                    // text/isModified/canUndo/canRedo 由 ContentChangeEvent 回调自动更新
                                }
                                lineOriginalTexts = lineOriginalTexts - line
                            }
                        },
                        onReady = { editor -> editorRef = editor },
                        onToolbarAction = { action ->
                            when (action) {
                                EditorToolbarAction.EDIT_SYMBOLS -> {
                                    editingSymbolIndex = -1
                                    editingSymbolLabel = ""
                                    editingSymbolValue = TextFieldValue("")
                                    showSymbolEditor = true
                                }
                                EditorToolbarAction.UNDO -> safeEditorOp { editor ->
                                    editor.undo()
                                    canUndo = editor.canUndo()
                                    canRedo = editor.canRedo()
                                }
                                EditorToolbarAction.REDO -> safeEditorOp { editor ->
                                    editor.redo()
                                    canUndo = editor.canUndo()
                                    canRedo = editor.canRedo()
                                }
                                EditorToolbarAction.HINT -> {
                                    editorRef?.let { ed ->
                                        val leftLine = ed.cursor.leftLine
                                        val leftCol = ed.cursor.leftColumn
                                        val lineStr = ed.text.getLineString(leftLine)
                                        if (lineStr.isNotEmpty() && leftCol > 0) {
                                            val before = lineStr.substring(0, leftCol.coerceAtMost(lineStr.length))
                                            var end = before.length
                                            while (end > 0) {
                                                val ch = before[end - 1]
                                                if (!ch.isLetterOrDigit() && ch != '_') break
                                                end--
                                            }
                                            cursorWord = before.substring(end)
                                        }
                                    }
                                    hintQuery = cursorWord
                                    hintResults = emptyList()
                                    activePopup = "hint"
                                }
                                EditorToolbarAction.REFERENCE -> activePopup = "ref"
                                EditorToolbarAction.AT_REFERENCE -> activePopup = "at"
                                EditorToolbarAction.SEARCH -> showSearch = true
                                EditorToolbarAction.MORE -> showMore = true
                            }
                        },
                        rainbowSettingsTick = rainbowSettingsTick,
                        onSymbolInsert = { value ->
                            insertTextRequest = value
                            insertTextTick++
                            isModified = true
                        }
                    )
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        StyledPopupMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                            modifier = Modifier.width(240.dp)
                        ) {
                            PlainMenuItem(icon = Icons.Default.Save, title = "保存", tint = RustedSecondary, onClick = { scope.launch { saveSync("MenuSave") }; showMore = false })
                            PlainMenuItem(icon = Icons.Default.Search, title = "查找替换", onClick = { showMore = false; showSearch = true })
                            if (isEnglish) {
                                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                                PlainMenuItem(
                                    icon = Icons.Default.Translate,
                                    title = "切换为${if (showChinese) "英文" else "中文"}",
                                    tint = RustedSecondary,
                                    onClick = {
                                        showMore = false
                                        if (switchingLanguage) return@PlainMenuItem
                                        switchingLanguage = true
                                        scope.launch {
                                            try {
                                                if (showChinese) {
                                                    chineseText = text
                                                    val translated = withContext(Dispatchers.Default) { engine.translateToEnglish(text, autoSpace, litLines) }
                                                    englishText = translated
                                                    showChinese = false
                                                    text = translated
                                                } else {
                                                    englishText = text
                                                    val translated = withContext(Dispatchers.Default) { engine.translateToChinese(text, autoSpace) }
                                                    chineseText = translated
                                                    showChinese = true
                                                    text = translated
                                                }
                                                resetTextTick++
                                                resetTextRequest = text to resetTextTick
                                                onTextChange?.invoke(text)
                                            } finally {
                                                switchingLanguage = false
                                            }
                                        }
                                    }
                                )
                            }
                            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            PlainMenuItem(icon = Icons.Default.CheckCircle, title = "自动保存", subtitle = if (autoSave) "已开启" else "已关闭", tint = if (autoSave) RustedSecondary else RustedOnBackground, onClick = { autoSave = !autoSave; SettingsManager.autoSave = autoSave })
                            PlainMenuSlider(icon = Icons.Default.FormatSize, title = "字体大小", value = fontSizeState, valueRange = 10f..24f, valueLabel = "${fontSizeState.toInt()}sp", onValueChange = { fontSizeState = roundFontSize(it); SettingsManager.fontSize = fontSizeState })
                            PlainMenuItem(icon = Icons.Default.FilterList, title = "补全筛选", onClick = { showMore = false; showCompletionFilter = true })
                            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            PlainMenuItem(icon = Icons.Default.Settings, title = "设置", onClick = {
                                showMore = false
                                onTextCacheRequest?.invoke(text)
                                lifecycleOwner.lifecycleScope.launch {
                                    if (autoSave && isModified) saveSync("NavigateAway")
                                    onNavigate(Screen.SETTINGS)
                                }
                            })
                            PlainMenuItem(icon = Icons.Default.Translate, title = "翻译库", onClick = {
                                showMore = false
                                onTextCacheRequest?.invoke(text)
                                lifecycleOwner.lifecycleScope.launch {
                                    if (autoSave && isModified) saveSync("NavigateAway")
                                    onNavigate(Screen.TRANSLATION)
                                }
                            })
                            PlainMenuItem(icon = Icons.Default.Extension, title = "自定义补全", onClick = {
                                showMore = false
                                onTextCacheRequest?.invoke(text)
                                lifecycleOwner.lifecycleScope.launch {
                                    if (autoSave && isModified) saveSync("NavigateAway")
                                    onNavigate(Screen.CUSTOM)
                                }
                            })
                            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                            if (SettingsManager.devInheritanceView) {
                                PlainMenuItem(icon = Icons.Default.AccountTree, title = "继承链", subtitle = "查看文件继承来源", tint = RustedSecondary, onClick = {
                                    showMore = false
                                    scope.launch(Dispatchers.IO) {
                                        val text = com.rwmodstudio.core.InheritanceResolver.resolveFormatted(filePath, homeDir)
                                        withContext(Dispatchers.Main) {
                                            inheritanceText = text
                                            showInheritanceView = true
                                        }
                                    }
                                })
                            }
                            if (SettingsManager.devCoordVisual && onOpenCoordVisual != null) {
                                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                                PlainMenuItem(icon = Icons.Default.Visibility, title = "坐标可视化", onClick = { showMore = false; onOpenCoordVisual(text) })
                            }
                            if (SettingsManager.devShowCopyPath) {
                                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                                val rel = "ROOT:/" + (if (homeDir.isNotEmpty() && filePath.startsWith(homeDir)) filePath.removePrefix(homeDir).removePrefix("/") else filePath.substringAfterLast("/"))
                                PlainMenuItem(icon = Icons.Default.DriveFileRenameOutline, title = "复制文件名", subtitle = fileName, onClick = { copyToClip("文件名", fileName); showMore = false })
                                PlainMenuItem(icon = Icons.Default.FolderCopy, title = "复制相对路径", subtitle = rel, onClick = { copyToClip("路径", rel); showMore = false })
                            }
                        }
                    }
            }
        } else if (!contentLoaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RustedPrimary) }
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("文件为空", color = RustedOnBackground.copy(alpha = 0.4f)) }
        }
        // 继承链查看对话框
        if (showInheritanceView) {
            AlertDialog(
                onDismissRequest = { showInheritanceView = false },
                title = { Text("继承链", fontWeight = FontWeight.Bold) },
                text = {
                    ReadOnlyCodeEditor(
                        text = inheritanceText,
                        modifier = Modifier.fillMaxWidth().height(480.dp),
                        fontSize = 12f,
                        autoWrap = SettingsManager.autoWrap,
                        smartWrap = smartWrap
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("继承链", inheritanceText))
                            showInheritanceView = false
                        }) { Text("复制全部", fontSize = 13.sp) }
                        TextButton(onClick = { showInheritanceView = false }) { Text("关闭", fontSize = 13.sp) }
                    }
                }
            )
        }
        // 查找替换栏 - 固定在底部，adjustResize会自动把它推到键盘上方
        if (showSearch) {
            // 查找替换栏
            Surface(Modifier.fillMaxWidth(), color = RustedSurface) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(searchQuery, { searchQuery = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("查找...", fontSize = 12.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }, Modifier.size(20.dp)) { Icon(Icons.Default.Clear, null, Modifier.size(14.dp)) } })
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(replaceText, { replaceText = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("替换...", fontSize = 12.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // 匹配计数
                        Text(
                            if (searchMatchCount == 0) "无匹配" else "${searchCurrentIndex + 1}/$searchMatchCount",
                            fontSize = 11.sp,
                            color = RustedOnBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        // 上一个/下一个
                        IconButton(
                            onClick = { safeSearcherOp { it.gotoPrevious() } },
                            Modifier.size(26.dp)
                        ) { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.6f)) }
                        IconButton(
                            onClick = { safeSearcherOp { it.gotoNext() } },
                            Modifier.size(26.dp)
                        ) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.6f)) }
                        Spacer(Modifier.weight(1f))
                        // 替换
                        TextButton(
                            onClick = { safeSearcherOp { it.replaceCurrentMatch(replaceText) } },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text("替换", fontSize = 11.sp) }
                        // 全部替换：使用 sora-editor 原生 replaceAll，状态同步更可靠
                        TextButton(
                            onClick = {
                                safeSearcherOp { searcher ->
                                    val matchCount = searcher.matchedPositionCount
                                    try {
                                        searcher.replaceAll(replaceText) {
                                            searchMatchCount = 0
                                            searchCurrentIndex = -1
                                            Toast.makeText(context, "已替换 $matchCount 处", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Replace all failed", e)
                                        Toast.makeText(context, "替换失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text("全替", fontSize = 11.sp) }
                        // 关闭
                        IconButton(
                            onClick = {
                                showSearch = false
                                editorRef?.searcher?.stopSearch()
                                searchMatchCount = 0
                                searchCurrentIndex = -1
                                // 保留 searchQuery 和 replaceText，下次打开时仍在；它们已通过 SettingsManager 持久化
                            },
                            Modifier.size(28.dp)
                        ) { Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = RustedOnBackground.copy(alpha = 0.5f)) }
                    }
                }
            }
        }
    }

    // Hint popup
    if (activePopup == "hint" || showCompletionEditor) {
        val completionCategories = remember(nativeCustomCompletions, extraCustomCompletions, rawUserCompletions) {
            (nativeCustomCompletions.flatMap { it.category } + extraCustomCompletions.flatMap { it.category } + rawUserCompletions.flatMap { it.category })
                .filter { it.isNotBlank() }.distinct().sorted()
        }
        AlertDialog(onDismissRequest = { activePopup = ""; showCompletionEditor = false }, title = { Text("代码提示") }, text = {
            var qr by remember { mutableStateOf(hintQuery) }
            var res by remember { mutableStateOf(hintResults) }
            LaunchedEffect(qr) {
                fun CustomCompletion.matchesQuery(q: String): Boolean {
                    return name.contains(q, ignoreCase = true) ||
                            nameEn.contains(q, ignoreCase = true) ||
                            desc.contains(q, ignoreCase = true) ||
                            value.contains(q, ignoreCase = true)
                }
                fun CustomCompletion.toPropertyInfo() = CodeReferenceRepository.PropertyInfo(
                    name = name, type = detail, desc_zh = desc,
                    example = example, name_en = nameEn.takeIf { it.isNotBlank() }
                )

                // 只从自定义补全三表搜索：用户表 > 附件表 > 原生表，按中文 name 去重
                val seen = mutableSetOf<String>()
                val merged = mutableListOf<CodeReferenceRepository.PropertyInfo>()

                // 1) 用户自定义补全
                rawUserCompletions.filter { it.matchesQuery(qr) }.forEach { item ->
                    if (seen.add(item.name)) merged.add(item.toPropertyInfo())
                }

                // 2) 附件表补全
                extraCustomCompletions.filter { it.matchesQuery(qr) }.forEach { item ->
                    if (seen.add(item.name)) merged.add(item.toPropertyInfo())
                }

                // 3) 原生补全表
                nativeCustomCompletions.filter { it.matchesQuery(qr) }.forEach { item ->
                    if (seen.add(item.name)) merged.add(item.toPropertyInfo())
                }

                // 「完全匹配优先」排序：名称 完全相等(3) > 前缀命中(2) > 子串包含(1) > 仅其它字段命中(0)
                val q = qr.trim().lowercase()
                fun namePriority(p: CodeReferenceRepository.PropertyInfo): Int {
                    val n = p.name.lowercase()
                    return when {
                        n == q -> 3
                        n.startsWith(q) -> 2
                        n.contains(q) -> 1
                        else -> 0
                    }
                }
                res = merged.sortedByDescending { namePriority(it) }
            }
            Column {
                OutlinedTextField(qr, { qr = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索...") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                Spacer(Modifier.height(6.dp))
                if (res.isEmpty()) Text("无匹配", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                else LazyColumn(Modifier.heightIn(max = 300.dp)) { items(res.take(15)) { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 1.dp), colors = CardDefaults.cardColors(containerColor = RustedSurface)) {
                        Column(Modifier.padding(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) { Text(p.name, fontSize = 14.sp, fontFamily = AppCodeFontFamily, fontWeight = FontWeight.Medium, color = RustedPrimary) }
                                Row {
                                    IconButton(onClick = {
                                        val userItem = rawUserCompletions.find { it.name == p.name }
                                        val nativeItem = nativeCustomCompletions.find { it.name == p.name }
                                        val extraItem = extraCustomCompletions.find { it.name == p.name }
                                        editingCompletion = userItem ?: nativeItem ?: extraItem ?: CustomCompletion(
                                            name = p.name,
                                            value = p.example.substringAfter(":").substringBefore(",").substringBefore("#").trim(),
                                            detail = p.type,
                                            desc = p.desc_zh,
                                            example = p.example,
                                            category = listOf(),
                                            formatCategory = "属性",
                                            isOverridden = true
                                        )
                                        showCompletionEditor = true
                                    }, Modifier.size(24.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = RustedSecondary) }
                                    IconButton(onClick = {
                                        // 与代码表「复制代码」同一逻辑：按格式分类生成插入文本（不解析 example）
                                        val src = (rawUserCompletions + nativeCustomCompletions + extraCustomCompletions).firstOrNull { it.name == p.name }
                                        copyToClip(p.name, if (src != null) completionFormatInsert(src) else p.name)
                                    }, Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = RustedSecondary) }
                                }
                            }
                            if (!p.name_en.isNullOrEmpty()) SelectionContainer { Text("(${p.name_en})", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f)) }
                            if (p.desc_zh.isNotEmpty()) SelectionContainer { Text(p.desc_zh, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.7f)) }
                            if (p.example.isNotEmpty()) SelectionContainer { Text(p.example, fontSize = 11.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary, maxLines = 3) }
                        }
                    }
                } }
            }
        }, confirmButton = { TextButton(onClick = { activePopup = "" }) { Text("关闭") } })

        if (showCompletionEditor) {
            CustomCompletionEditorDialog(
                item = editingCompletion,
                categories = completionCategories,
                onDismiss = {
                    showCompletionEditor = false
                    editingCompletion = null
                },
                onSave = { item ->
                    val idx = rawUserCompletions.indexOfFirst { it.name == item.name }
                    rawUserCompletions = if (idx >= 0) {
                        rawUserCompletions.toMutableList().also { it[idx] = item }
                    } else {
                        rawUserCompletions + item
                    }
                    scope.launch(Dispatchers.IO) { saveUserCompletions(rawUserCompletions, engine) }
                    customCompletions = customCompletionsToProviderItems(rawUserCompletions)
                    cp = if (engine.isLoaded) {
                        engine.getCompletionProvider(customCompletions, nativeCompletions, completionDetailEnabled,
                            valueSectionProperties = buildValueSectionProperties(mergeCompletionTables(rawUserCompletions, nativeCustomCompletions, extraCustomCompletions)))
                    } else cp
                    showCompletionEditor = false
                    editingCompletion = null
                }
            )
        }
    }

    // Symbol editor
    if (showSymbolEditor) {
        AlertDialog(onDismissRequest = { showSymbolEditor = false }, title = { Text("编辑快捷符号") }, text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                LazyColumn(Modifier.weight(1f)) { items(customSymbols.size) { idx ->
                    val (l, v) = customSymbols[idx]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { editingSymbolIndex = idx; editingSymbolLabel = l; editingSymbolValue = TextFieldValue(v, TextRange(v.length)) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l, fontSize = 16.sp, fontFamily = AppCodeFontFamily, color = RustedPrimary, modifier = Modifier.width(40.dp))
                        Text("→", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.3f), modifier = Modifier.width(24.dp))
                        Text(v, fontSize = 13.sp, fontFamily = AppCodeFontFamily, color = RustedOnBackground, modifier = Modifier.weight(1f))
                        IconButton(onClick = { customSymbols = customSymbols.toMutableList().also { it.removeAt(idx) }; saveSymbols() }, Modifier.size(24.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(14.dp), tint = RustedError.copy(alpha = 0.6f)) }
                    }
                } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("在输出值中放一个 $CURSOR_MARKER 标记，插入后光标会停在此处；不放则停在末尾", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(editingSymbolLabel, { editingSymbolLabel = it }, label = { Text("显示") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(editingSymbolValue, { editingSymbolValue = it }, label = { Text("输出") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                }
                TextButton(onClick = {
                    // 在「输出」输入框当前光标处插入 ≡，并把光标移到 ≡ 之后；输入框未聚焦时默认插到末尾
                    val tf = editingSymbolValue
                    val markerStr = CURSOR_MARKER.toString()
                    val offset = if (tf.selection.collapsed) tf.selection.start else tf.text.length
                    val newText = StringBuilder(tf.text).insert(offset, markerStr).toString()
                    editingSymbolValue = TextFieldValue(newText, TextRange(offset + markerStr.length))
                }, modifier = Modifier.align(Alignment.End)) { Text("插入 $CURSOR_MARKER（光标位置）") }
            }
        }, confirmButton = {
            Row {
                TextButton(onClick = {
                    val symValue = editingSymbolValue.text
                    if (editingSymbolLabel.isNotEmpty() && symValue.isNotEmpty()) {
                        if (editingSymbolIndex >= 0) customSymbols = customSymbols.toMutableList().also { it[editingSymbolIndex] = editingSymbolLabel to symValue }
                        else customSymbols = customSymbols + (editingSymbolLabel to symValue)
                        saveSymbols(); editingSymbolLabel = ""; editingSymbolValue = TextFieldValue(""); editingSymbolIndex = -1
                    }
                }) { Text("保存") }
                TextButton(onClick = { showSymbolEditor = false }) { Text("关闭") }
            }
        })
    }

    // 补全筛选 - 第一层选节，第二层选分类
    if (showCompletionFilter) {
        var selectedSection by remember { mutableStateOf<String?>(null) }
        // 节从当前文件实际解析出的节名归一化得到；分类只来自自定义补全表（native + user）
        val allCategories = remember(nativeCustomCompletions, rawUserCompletions) {
            (nativeCustomCompletions.flatMap { it.category } + rawUserCompletions.flatMap { it.category })
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        val sections = remember(allSections) {
            allSections.map { mapSectionName(it.first) }.filter { it.isNotBlank() }.distinct().sorted()
        }

        if (selectedSection == null) {
            // 第一层：选节
            AlertDialog(onDismissRequest = { showCompletionFilter = false }, title = { Text("补全筛选") }, text = {
                Column {
                    Text("选择节后配置分类", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    if (sections.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            Text("未检测到节", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 480.dp)) { items(sections) { section ->
                            val filter = completionFilter[section]
                            val count = filter?.size ?: 0
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selectedSection = section }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(section, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                if (filter != null) Text("${count}项已启用", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                                else Text("默认启用", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.3f))
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.3f))
                            }
                        } }
                    }
                }
            }, confirmButton = {
                Row {
                    TextButton(onClick = {
                        val ok = SettingsManager.resetSectionFilters()
                        completionFilter = SettingsManager.loadAllSectionFilters()
                        if (!ok) {
                            Toast.makeText(ctx, "重置分类筛选失败", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("重置默认", color = RustedError) }
                    TextButton(onClick = { showCompletionFilter = false }) { Text("确定") }
                }
            })
        } else {
            // 第二层：选分类
            val sectionKey = selectedSection ?: ""
            val currentFilter = completionFilter[sectionKey]
            // 首次打开时优先使用 SettingsManager 中的默认配置，否则启用所有可用分类
            val defaultForSection = SettingsManager.DEFAULT_SECTION_FILTERS[sectionKey]?.filter { it in allCategories }?.toSet()
            val initialEnabled = currentFilter ?: defaultForSection ?: allCategories.toSet()
            LaunchedEffect(sectionKey) {
                if (sectionKey.isNotEmpty() && currentFilter == null) {
                    val toSave = defaultForSection ?: allCategories.toSet()
                    completionFilter = completionFilter + (sectionKey to toSave)
                    SettingsManager.saveSectionFilter(sectionKey, toSave)
                }
            }
            var enabledCats by remember { mutableStateOf(initialEnabled) }

            AlertDialog(onDismissRequest = { showCompletionFilter = false }, title = { Text("[$sectionKey] 节 - 分类筛选") }, text = {
                Column(Modifier.heightIn(max = 400.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { enabledCats = allCategories.toSet(); completionFilter = completionFilter + (sectionKey to enabledCats); SettingsManager.saveSectionFilter(sectionKey, enabledCats) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("全选", fontSize = 11.sp) }
                        TextButton(onClick = { enabledCats = emptySet(); completionFilter = completionFilter + (sectionKey to enabledCats); SettingsManager.saveSectionFilter(sectionKey, enabledCats) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("取消全选", fontSize = 11.sp) }
                    }
                    LazyColumn(Modifier.weight(1f)) { items(allCategories) { cat ->
                        val isEnabled = cat in enabledCats
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            val newSet = enabledCats.toMutableSet()
                            if (isEnabled) newSet.remove(cat) else newSet.add(cat)
                            enabledCats = newSet
                            completionFilter = completionFilter + (sectionKey to newSet)
                            SettingsManager.saveSectionFilter(sectionKey, newSet)
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isEnabled, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(cat, fontSize = 14.sp)
                        }
                    } }
                }
            }, confirmButton = { TextButton(onClick = { selectedSection = null; showCompletionFilter = false }) { Text("确定") } })
        }
    }

    // Code reference popup
    if (activePopup == "ref") {
        AlertDialog(onDismissRequest = { activePopup = "" }, title = { Text("代码参考") }, text = { Box(Modifier.heightIn(max = 480.dp)) { CodeReferenceScreen(userCompletions = rawUserCompletions) } }, confirmButton = { TextButton(onClick = { activePopup = "" }) { Text("关闭") } })
    }

    // @ file reference popup
    if (activePopup == "at") {
        var atSearch by remember { mutableStateOf("") }
        var atFiles by remember { mutableStateOf(listOf<String>()) }
        val currentFileDir = remember(filePath) { File(filePath).parentFile?.absolutePath ?: homeDir }
        LaunchedEffect(atSearch) {
            withContext(Dispatchers.IO) {
                val results = mutableListOf<String>()
                val q = atSearch.lowercase()
                // 优先显示当前文件所在目录的文件
                if (currentFileDir.isNotEmpty() && File(currentFileDir).exists()) {
                    File(currentFileDir).listFiles()?.filter { it.isFile }?.sortedWith(compareBy<File> { !it.extension.lowercase().let { ext -> ext == "ini" || ext == "template" } }.thenBy { it.name.lowercase() })?.forEach { f ->
                        if (q.isEmpty() || f.name.lowercase().contains(q)) {
                            results.add(f.name)
                        }
                    }
                }
                // 然后添加项目根目录的其他文件（排除当前目录已有的）
                val root = File(if (homeDir.isNotEmpty()) homeDir else android.os.Environment.getExternalStorageDirectory().absolutePath)
                if (root.exists() && root.absolutePath != currentFileDir) {
                    root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace("\\", "/") }.filter { f ->
                        q.isEmpty() || f.lowercase().contains(q)
                    }.take(30).forEach { f ->
                        if (f !in results) results.add(f)
                    }
                }
                atFiles = results.take(30)
            }
        }
        AlertDialog(onDismissRequest = { activePopup = "" }, title = { Text("@ 文件引用") }, text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                OutlinedTextField(atSearch, { atSearch = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索项目文件...") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp), leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) })
                Spacer(Modifier.height(6.dp))
                if (atFiles.isEmpty()) Text("无匹配", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.padding(12.dp))
                else LazyColumn(Modifier.weight(1f)) { items(atFiles) { f ->
                    val isCurrentDirFile = !f.contains("/") && !f.contains("\\")
                    val fullPath = remember(f) { if (isCurrentDirFile) File(currentFileDir, f).absolutePath else File(homeDir, f).absolutePath }
                    val displayPath = if (isCurrentDirFile) f else "ROOT:/$f"
                    val isImage = f.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".bmp") || it.endsWith(".webp") }
                    val imageBitmap = remember(fullPath) {
                        if (isImage) {
                            try {
                                BitmapFactory.decodeFile(fullPath)?.asImageBitmap()
                            } catch (_: Exception) { null }
                        } else null
                    }
                    TextButton(onClick = { val ins = if (isCurrentDirFile) File(f).name else "ROOT:/$f"; insertTextRequest = ins; insertTextTick++; isModified = true; activePopup = "" }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (imageBitmap != null) {
                                Image(imageBitmap, null, Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(displayPath, fontSize = 13.sp, fontFamily = AppCodeFontFamily, color = if (isCurrentDirFile) RustedPrimary else RustedOnBackground)
                        }
                    }
                } }
            }
        }, confirmButton = { TextButton(onClick = { activePopup = "" }) { Text("关闭") } })
    }

    // 补全格式管理
    if (activePopup == "formatManager") {
        var formatTemplates by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var newTemplateName by remember { mutableStateOf("") }
        var newTemplateFormat by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val saved = SettingsManager.customCompletionFormats
            formatTemplates = if (saved.isNotEmpty()) {
                saved.split("||").map { pair ->
                    val parts = pair.split("|")
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }.filter { it.first.isNotEmpty() }
            } else emptyList()
        }
        AlertDialog(onDismissRequest = { activePopup = "" }, title = { Text("补全格式管理") }, text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                Text("管理已有分类的补全格式模板", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(newTemplateName, { newTemplateName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("分类名称") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(newTemplateFormat, { newTemplateFormat = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("格式: ${"$"}name:值") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    if (newTemplateName.isNotEmpty() && newTemplateFormat.isNotEmpty()) {
                        formatTemplates = formatTemplates + (newTemplateName to newTemplateFormat)
                        SettingsManager.customCompletionFormats = formatTemplates.joinToString("||") { "${it.first}|${it.second}" }
                        newTemplateName = ""; newTemplateFormat = ""
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)) { Text("添加格式") }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(formatTemplates) { (name, format) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RustedPrimary)
                                Text(format, fontSize = 11.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary)
                            }
                            IconButton(onClick = {
                                formatTemplates = formatTemplates.filter { it.first != name }
                                SettingsManager.customCompletionFormats = formatTemplates.joinToString("||") { "${it.first}|${it.second}" }
                            }, Modifier.size(20.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(12.dp), tint = RustedError.copy(alpha = 0.5f)) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { activePopup = "" }) { Text("关闭") } })
    }

    // 节跳转对话框
    if (showSectionJumpDialog) {
        AlertDialog(
            onDismissRequest = { showSectionJumpDialog = false },
            title = { Text("跳转到节") },
            text = {
                if (allSections.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text("未检测到节", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(allSections, key = { it.first + it.second }) { (name, line) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                    editorRef?.let { editor ->
                                    val targetLine = line.coerceIn(0, (editor.text.lineCount - 1).coerceAtLeast(0))
                                    editor.jumpToLine(targetLine)
                                }
                                    showSectionJumpDialog = false
                                }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("[$name]", fontSize = 13.sp, color = RustedPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("行 ${line + 1}", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSectionJumpDialog = false }) { Text("关闭") } }
        )
    }

    // 返回确认对话框
    if (showBackConfirm) {
        AlertDialog(
            onDismissRequest = { showBackConfirm = false },
            title = { Text("是否保存修改？") },
            text = { Text("文件已修改但未保存") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        saveSync("BackConfirm")
                        showBackConfirm = false
                        onBack()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showBackConfirm = false; onBack() }) { Text("不保存", color = RustedError) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showBackConfirm = false }) { Text("取消") }
                }
            }
        )
    }
}

/**
 * 将实际节名归一化为补全筛选使用的基础节名。
 * 例如：全局资源_xxx -> 全局资源；core_1 -> 核心。
 */
private fun mapSectionName(name: String): String {
    // 单一来源：复用 CompletionProvider.sectionEnToZh，避免三处节名映射漂移
    val knownZhSections = sectionEnToZh.values.toSet()
    val enToZh = sectionEnToZh
    val trimmed = name.trim()
    val normalized = trimmed.lowercase().replace(" ", "")
    val lowerZhSet = knownZhSections.map { it.lowercase().replace(" ", "") }.toSet()

    if (normalized in lowerZhSet) {
        return knownZhSections.first { it.lowercase().replace(" ", "") == normalized }
    }

    val underscoreStripped = normalized.substringBefore('_')
    if (underscoreStripped in lowerZhSet) {
        return knownZhSections.first { it.lowercase().replace(" ", "") == underscoreStripped }
    }
    enToZh[underscoreStripped]?.let { return it }

    for (zh in knownZhSections) {
        val low = zh.lowercase().replace(" ", "")
        if (normalized.contains(low)) return zh
    }

    enToZh[normalized]?.let { return it }
    for ((en, zh) in enToZh) {
        if (normalized.contains(en.lowercase())) return zh
    }
    return trimmed
}

