package com.rwmodstudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.RwModApplication
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.TranslationDedupChecker
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.components.DedupResultDialog
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "TranslationEditorScreen"

/** 当前正在后台执行的补全表刷新任务，避免重复启动导致文件写冲突 */
private var activeRefreshJob: Job? = null

// 防止用户快速连点保存导致多个协程同时写翻译库 JSON
private val translationSaveMutex = Mutex()

@Composable
fun TranslationEditorScreen(initialFilter: TranslationFilterType = TranslationFilterType.ALL) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val scope = rememberCoroutineScope()
    val engine = remember { TranslationEngine.getInstance() }
    var loaded by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(listOf<TranslationDict.TranslationEntry>()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(initialFilter) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var editKey by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var dedupProgress by remember { mutableStateOf<TranslationDedupChecker.ProgressInfo?>(null) }
    var showAddKey by remember { mutableStateOf(false) }
    var newEnKey by remember { mutableStateOf("") }
    var newZhValue by remember { mutableStateOf("") }
    var dedupResult by remember { mutableStateOf<String?>(null) }
    var showDedup by remember { mutableStateOf<List<TranslationDedupChecker.DuplicateInfo>?>(null) }
    var showLibDups by remember { mutableStateOf<LibraryDupGroups?>(null) }
    var dedupWords by remember { mutableStateOf(setOf<String>()) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteEntry by remember { mutableStateOf<TranslationDict.TranslationEntry?>(null) }

    // 加载查重词列表
    LaunchedEffect(loaded) {
        if (loaded) dedupWords = loadDedupWords()
    }

    LaunchedEffect(Unit) {
        loaded = true
    }

    LaunchedEffect(loaded) {
        if (!loaded || !engine.isLoaded) return@LaunchedEffect
        entries = engine.getTranslationDict().getAllEntriesWithSource()
    }

    if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RustedPrimary) }; return }

    val dict = engine.getTranslationDict()
    val defaultPath = SettingsManager.defaultPath

    val filtered = remember(entries, searchQuery, filterType, dedupWords) {
        entries.filter { entry ->
            val (source, en, zh) = entry
            val q = searchQuery.isEmpty() || en.contains(searchQuery, ignoreCase = true) || zh.contains(searchQuery, ignoreCase = true)
            val f = when (filterType) {
                TranslationFilterType.SECTION -> en.startsWith("[")
                TranslationFilterType.KEY -> !en.startsWith("[")
                TranslationFilterType.DEDUP -> zh in dedupWords
                TranslationFilterType.NATIVE -> source == TranslationDict.TranslationSource.NATIVE
                TranslationFilterType.EXTRA -> source == TranslationDict.TranslationSource.EXTRA
                TranslationFilterType.USER -> source == TranslationDict.TranslationSource.USER
                else -> true
            }
            q && f
        }
    }

    fun doSave() {
        if (saving) return
        saving = true
        scope.launch(Dispatchers.IO) {
            try {
                translationSaveMutex.withLock {
                    // 只保存用户表：key-value 对，原生/附件条目保持原样
                    val userEntries = entries.filter { it.source == TranslationDict.TranslationSource.USER }.map { it.key to it.value }
                    engine.getTranslationDict().saveToExternal(context, userEntries)

                    // 全库内容查重：
                    // 1) 同一中文值被多个英文键占用（中→英反查会互相覆盖）
                    // 2) 同一英文键被多个中文值占用（英→中翻译会互相覆盖）
                    // 特殊值（true/LAND 等大小写变体）属于设计内别名，不计入
                    val special = engine.getTranslationDict().getSpecialValues()
                    val filtered = entries.filter { it.key.isNotBlank() && it.value.isNotBlank() && it.key !in special }
                    val dupByValue = filtered
                        .groupBy { it.value }
                        .filter { group -> group.value.map { it.key }.distinct().size > 1 }
                        .toList()
                        .sortedByDescending { it.second.size }
                        .toMap()
                    val dupByKey = filtered
                        .groupBy { it.key }
                        .filter { group -> group.value.map { it.value }.distinct().size > 1 }
                        .toList()
                        .sortedByDescending { it.second.size }
                        .toMap()
                    val dupGroups = LibraryDupGroups(dupByValue, dupByKey)
                    val dupCount = dupByValue.size + dupByKey.size

                    withContext(Dispatchers.Main) {
                        saving = false
                        if (dupCount > 0) {
                            showLibDups = dupGroups
                            dedupResult = "保存成功；全库查重发现 ${dupCount} 组重复（中文值 ${dupByValue.size} / 英文键 ${dupByKey.size}）"
                        } else if (SettingsManager.autoRefreshCompletionsOnTranslationSave) {
                            dedupResult = "保存成功，查重通过，正在后台刷新补全表……"
                        } else {
                            dedupResult = "保存成功，查重通过 → ${engine.getTranslationDict().getUserTranslationPath(context).absolutePath}"
                        }
                    }

                    // 若开启自动刷新，把耗时的补全表刷新放到应用级后台执行，不阻塞当前页面
                    if (SettingsManager.autoRefreshCompletionsOnTranslationSave) {
                        val app = context.applicationContext as RwModApplication
                        activeRefreshJob?.cancel()
                        activeRefreshJob = app.applicationScope.launch {
                            try {
                                refreshCompletionsFromEnglish(engine)
                                withContext(Dispatchers.Main) {
                                    dedupResult = "补全表刷新完成 → ${engine.getTranslationDict().getUserTranslationPath(context).absolutePath}"
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "refreshCompletionsFromEnglish failed", e)
                                withContext(Dispatchers.Main) {
                                    dedupResult = "补全表刷新失败"
                                }
                            } finally {
                                activeRefreshJob = null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "doSave failed", e)
                withContext(Dispatchers.Main) {
                    saving = false
                    dedupResult = "保存失败: ${e.message}"
                }
            }
        }
    }

    fun doDedup() {
        if (!engine.isLoaded) {
            dedupResult = "翻译引擎未加载，请等待加载完成"
            return
        }
        saving = true
        dedupProgress = TranslationDedupChecker.ProgressInfo("扫描项目文件...", 0f)
        scope.launch(Dispatchers.Default) {
            try {
                val projDups = TranslationDedupChecker.checkProjectFiles(engine, onProgress = { p -> dedupProgress = p })
                // 保存查重词到文件
                val words = projDups.map { it.key }.toSet()
                saveDedupWords(words)
                withContext(Dispatchers.Main) {
                    saving = false
                    dedupProgress = null
                    dedupWords = words
                    showDedup = projDups
                    dedupResult = if (projDups.isEmpty()) "查重完成：未发现重复项" else "查重完成：发现 ${projDups.size} 处重复"
                }
            } catch (e: Exception) {
                Log.e(TAG, "doDedup failed", e)
                withContext(Dispatchers.Main) {
                    saving = false
                    dedupProgress = null
                    dedupResult = "查重失败: ${e.message}"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        Card(Modifier.fillMaxWidth().padding(10.dp), colors = CardDefaults.cardColors(containerColor = RustedSurface)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val keyCount = dict.getAllEnglishKeys().size
                val sectionCount = dict.getAllEnglishSections().size
                val valueCount = entries.size - keyCount - sectionCount
                StatChip("Key", "$keyCount", RustedPrimary)
                StatChip("Section", "$sectionCount", RustedSecondary)
                StatChip("Value", "$valueCount", RustedAccent)
                StatChip("总计", "${entries.size}", RustedOnBackground)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.4f)) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val chipScroll = rememberScrollState()
            Row(modifier = Modifier.horizontalScroll(chipScroll), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = filterType == TranslationFilterType.ALL, onClick = { filterType = TranslationFilterType.ALL }, label = { Text("全部", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = filterType == TranslationFilterType.KEY, onClick = { filterType = TranslationFilterType.KEY }, label = { Text("Key", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = filterType == TranslationFilterType.SECTION, onClick = { filterType = TranslationFilterType.SECTION }, label = { Text("Section", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = filterType == TranslationFilterType.NATIVE, onClick = { filterType = TranslationFilterType.NATIVE }, label = { Text("原生", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = filterType == TranslationFilterType.EXTRA, onClick = { filterType = TranslationFilterType.EXTRA }, label = { Text("附件", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = filterType == TranslationFilterType.USER, onClick = { filterType = TranslationFilterType.USER }, label = { Text("用户", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                if (dedupWords.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = filterType == TranslationFilterType.DEDUP, onClick = { filterType = TranslationFilterType.DEDUP }, label = { Text("查重(${dedupWords.size})", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(filtered.size) { idx ->
                val entry = filtered[idx]
                val realIdx = entries.indexOf(entry)
                val sourceColor = when (entry.source) {
                    TranslationDict.TranslationSource.USER -> RustedSecondary
                    TranslationDict.TranslationSource.EXTRA -> RustedAccent
                    TranslationDict.TranslationSource.NATIVE -> RustedOnBackground.copy(alpha = 0.4f)
                }
                val sourceText = when (entry.source) {
                    TranslationDict.TranslationSource.USER -> "用户"
                    TranslationDict.TranslationSource.EXTRA -> "附件"
                    TranslationDict.TranslationSource.NATIVE -> "原生"
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RustedSurface).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.key, fontSize = 12.sp, fontFamily = AppCodeFontFamily, color = RustedPrimary)
                        Text(entry.value, fontSize = 12.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary)
                    }
                    Text(sourceText, fontSize = 9.sp, color = sourceColor, modifier = Modifier.padding(horizontal = 6.dp))
                    IconButton(onClick = {
                        if (realIdx >= 0) {
                            editIndex = realIdx
                            editKey = entry.key
                            editValue = entry.value
                        }
                    }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(13.dp), tint = RustedOnBackground.copy(alpha = 0.3f)) }
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        Surface(Modifier.fillMaxWidth(), color = RustedSurface, tonalElevation = 4.dp) {
            Column {
                // 进度条
                dedupProgress?.let { progress ->
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text(progress.stage, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = RustedPrimary,
                            trackColor = RustedOnBackground.copy(alpha = 0.1f)
                        )
                        Text("${(progress.progress * 100).toInt()}%", fontSize = 9.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    }
                }
                if (dedupResult != null) {
                    Text(dedupResult!!, fontSize = 11.sp, color = if (dedupResult!!.startsWith("保存成功") && !dedupResult!!.contains("查重发现")) RustedSecondary else RustedError, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${filtered.size} 条", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { doDedup() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("一键查重", fontSize = 11.sp) }
                        OutlinedButton(onClick = { newEnKey = ""; newZhValue = ""; showAddKey = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("添加", fontSize = 11.sp) }
                        OutlinedButton(onClick = { showResetConfirm = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("重置", fontSize = 11.sp, color = RustedError) }
                        Button(onClick = { doSave() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)) { if (saving) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp) else Text("保存", fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    if (editIndex >= 0) {
        var editDuplicate by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editIndex = -1 }, title = { Text("编辑翻译") }, text = {
            Column {
                OutlinedTextField(value = editKey, onValueChange = { editKey = it; editDuplicate = null }, modifier = Modifier.fillMaxWidth(), label = { Text("英文键") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = editValue, onValueChange = { editValue = it; editDuplicate = null }, modifier = Modifier.fillMaxWidth(), label = { Text("中文值") })
                if (editDuplicate != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(editDuplicate!!, fontSize = 11.sp, color = RustedError)
                }
            }
        }, confirmButton = {
            Row {
                TextButton(onClick = {
                    val old = entries[editIndex]
                    val newKey = editKey.trim()
                    val newValue = editValue.trim()
                    val isCurrent: (TranslationDict.TranslationEntry) -> Boolean = { it.key == old.key && it.source == old.source }
                    when {
                        newKey.isEmpty() -> editDuplicate = "英文键不能为空"
                        entries.any { it.key == newKey && !isCurrent(it) } -> editDuplicate = "该英文键已存在，请修改"
                        entries.any { it.value == newValue && !isCurrent(it) } -> editDuplicate = "该中文值已存在，请修改"
                        newKey == old.key && newValue == old.value -> editIndex = -1
                        newKey != old.key -> scope.launch(Dispatchers.IO) {
                            val delOk = engine.getTranslationDict().deleteEntry(context, old.source, old.key)
                            val addOk = if (delOk) engine.getTranslationDict().updateEntry(context, old.source, newKey, newValue) else false
                            withContext(Dispatchers.Main) {
                                if (addOk) {
                                    val newList = entries.toMutableList()
                                    newList[editIndex] = old.copy(key = newKey, value = newValue)
                                    entries = newList
                                    editIndex = -1
                                } else {
                                    editDuplicate = "保存失败"
                                }
                            }
                        }
                        else -> scope.launch(Dispatchers.IO) {
                            val ok = engine.getTranslationDict().updateEntry(context, old.source, old.key, newValue)
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    val newList = entries.toMutableList()
                                    newList[editIndex] = old.copy(value = newValue)
                                    entries = newList
                                    editIndex = -1
                                } else {
                                    editDuplicate = "保存失败"
                                }
                            }
                        }
                    }
                }) { Text("保存") }
                TextButton(onClick = {
                    val entry = entries[editIndex]
                    if (entry.source == TranslationDict.TranslationSource.USER) {
                        val newList = entries.toMutableList()
                        newList.removeAt(editIndex)
                        entries = newList
                        editIndex = -1
                    } else {
                        pendingDeleteEntry = entry
                        showDeleteConfirm = true
                    }
                }) { Text("删除", color = RustedError) }
            }
        }, dismissButton = { TextButton(onClick = { editIndex = -1 }) { Text("取消") } })
    }

    if (showDeleteConfirm && pendingDeleteEntry != null) {
        val entryToDelete = pendingDeleteEntry!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; pendingDeleteEntry = null },
            title = { Text("删除确认") },
            text = {
                Text(
                    "这是${if (entryToDelete.source == TranslationDict.TranslationSource.EXTRA) "附件" else "原生"}表条目，删除后下次重置翻译库也不会恢复，确定删除？",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val ok = engine.getTranslationDict().deleteEntry(context, entryToDelete.source, entryToDelete.key)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                entries = entries.filterNot { it.key == entryToDelete.key && it.source == entryToDelete.source }
                            }
                            showDeleteConfirm = false
                            pendingDeleteEntry = null
                        }
                    }
                }) { Text("确定", color = RustedError) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false; pendingDeleteEntry = null }) { Text("取消") }
            }
        )
    }

    showDedup?.let { dups ->
        DedupResultDialog(
            dups = dups,
            title = "查重结果: ${dups.size} 处 / ${dups.distinctBy { it.key }.size} 个词",
            onDismiss = { showDedup = null },
            onModify = { showDedup = null; filterType = TranslationFilterType.DEDUP }
        )
    }

    // 保存时全库查重结果：双向查重
    showLibDups?.let { groups ->
        val dupCount = groups.byValue.size + groups.byKey.size
        AlertDialog(
            onDismissRequest = { showLibDups = null },
            title = { Text("全库查重：$dupCount 组重复", fontSize = 16.sp) },
            text = {
                Column {
                    LazyColumn(Modifier.height(340.dp)) {
                        if (groups.byValue.isNotEmpty()) {
                            item {
                                Text(
                                    "同一中文值被多个英文键占用（中→英会互相覆盖）",
                                    fontSize = 11.sp,
                                    color = RustedOnBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            groups.byValue.forEach { (zh, list) ->
                                item {
                                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = RustedSurface)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text(zh, fontSize = 13.sp, fontFamily = AppCodeFontFamily, fontWeight = FontWeight.Medium, color = RustedSecondary)
                                            list.forEach { e ->
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(e.key, fontSize = 11.sp, fontFamily = AppCodeFontFamily, color = RustedPrimary, modifier = Modifier.weight(1f))
                                                    Text(
                                                        when (e.source) {
                                                            TranslationDict.TranslationSource.USER -> "用户"
                                                            TranslationDict.TranslationSource.EXTRA -> "附件"
                                                            TranslationDict.TranslationSource.NATIVE -> "原生"
                                                        },
                                                        fontSize = 9.sp,
                                                        color = RustedOnBackground.copy(alpha = 0.4f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                        if (groups.byKey.isNotEmpty()) {
                            item {
                                Text(
                                    "同一英文键被多个中文值占用（英→中会互相覆盖）",
                                    fontSize = 11.sp,
                                    color = RustedOnBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            groups.byKey.forEach { (en, list) ->
                                item {
                                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = RustedSurface)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text(en, fontSize = 13.sp, fontFamily = AppCodeFontFamily, fontWeight = FontWeight.Medium, color = RustedPrimary)
                                            list.forEach { e ->
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(e.value, fontSize = 11.sp, fontFamily = AppCodeFontFamily, color = RustedSecondary, modifier = Modifier.weight(1f))
                                                    Text(
                                                        when (e.source) {
                                                            TranslationDict.TranslationSource.USER -> "用户"
                                                            TranslationDict.TranslationSource.EXTRA -> "附件"
                                                            TranslationDict.TranslationSource.NATIVE -> "原生"
                                                        },
                                                        fontSize = 9.sp,
                                                        color = RustedOnBackground.copy(alpha = 0.4f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLibDups = null }) { Text("关闭") } }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置翻译库") },
            text = { Text("确定要恢复为默认翻译库吗？当前外部自定义条目将被清空。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        saving = true
                        scope.launch(Dispatchers.IO) {
                            engine.getTranslationDict().resetToDefault(context)
                            withContext(Dispatchers.Main) {
                                entries = engine.getTranslationDict().getAllEntriesWithSource()
                                saving = false
                                showResetConfirm = false
                                dedupResult = "已恢复默认翻译库"
                            }
                        }
                    }
                ) { Text("确定", color = RustedError) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } }
        )
    }

    if (showAddKey) {
        var addDuplicate by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showAddKey = false }, title = { Text("添加翻译") }, text = {
            Column {
                OutlinedTextField(value = newEnKey, onValueChange = { newEnKey = it; addDuplicate = null }, label = { Text("英文键") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = newZhValue, onValueChange = { newZhValue = it; addDuplicate = null }, label = { Text("中文值") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (addDuplicate != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(addDuplicate!!, fontSize = 11.sp, color = RustedError)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            val key = newEnKey.trim()
            val value = newZhValue.trim()
            if (key.isEmpty() || value.isEmpty()) {
                addDuplicate = "英文键和中文值不能为空"
                return@TextButton
            }
            when {
                entries.any { it.key == key } -> addDuplicate = "该英文键已存在，请修改"
                entries.any { it.value == value } -> addDuplicate = "该中文值已存在，请修改"
                else -> {
                    entries = entries + TranslationDict.TranslationEntry(TranslationDict.TranslationSource.USER, key, value)
                    showAddKey = false
                }
            }
        }) { Text("添加") } }, dismissButton = { TextButton(onClick = { showAddKey = false }) { Text("取消") } })
    }
}

fun getDedupWordsFile(): java.io.File = RwmodPaths.dedupWordsFile

fun loadDedupWords(): Set<String> {
    val file = getDedupWordsFile()
    if (!file.exists()) return emptySet()
    return try {
        file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    } catch (e: Exception) { emptySet() }
}

fun saveDedupWords(words: Set<String>) {
    try {
        val file = getDedupWordsFile()
        file.parentFile?.mkdirs()
        // 先清空再保存
        if (words.isEmpty()) {
            file.writeText("", Charsets.UTF_8)
        } else {
            file.writeText(words.joinToString("\n"), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save dedup words", e)
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color); Text(label, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f)) }
}

enum class TranslationFilterType { ALL, KEY, SECTION, NATIVE, EXTRA, USER, DEDUP }

/** 全库双向查重结果 */
private data class LibraryDupGroups(
    val byValue: Map<String, List<TranslationDict.TranslationEntry>>,
    val byKey: Map<String, List<TranslationDict.TranslationEntry>>
)
