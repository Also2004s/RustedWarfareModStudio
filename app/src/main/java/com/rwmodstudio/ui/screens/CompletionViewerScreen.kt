package com.rwmodstudio.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.LOGIC_CONNECTORS
import com.rwmodstudio.feature.completion.LOGIC_ENTRY_TOKENS
import com.rwmodstudio.feature.completion.OPERATOR_ITEMS
import com.rwmodstudio.feature.completion.replaceableValuePrefix
import com.rwmodstudio.feature.completion.splitKeyValueLine
import com.rwmodstudio.feature.completion.value.CALLABLE_CATEGORIES
import com.rwmodstudio.feature.completion.value.ValueCompletionAggregator
import com.rwmodstudio.feature.completion.value.ValueCompletionAggregator.ValueCompletionOptions
import com.rwmodstudio.feature.completion.value.ValueDataLoader
import com.rwmodstudio.feature.completion.value.classifyCallableCategory
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 补全查看器（开发者工具）。
 * 挑选一个属性，在可编辑的演示行中实时查看该属性不同光标位置可调用的补全值，
 * 并按来源 Provider 分组展示，帮助理解补全机制。
 */
@Composable
fun CompletionViewerScreen(
    selectedPropertyName: String?,
    onSelectProperty: (String?) -> Unit,
    callableCategory: String? = null,
    onCloseCallableCategory: () -> Unit = {}
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var aggregator by remember { mutableStateOf<ValueCompletionAggregator?>(null) }
    var entries by remember { mutableStateOf<List<PropEntry>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val engine = TranslationEngine.getInstance()
            if (!engine.isLoaded) engine.load(context)
            val user = loadUserItems()
            val native = loadNativeItemsVerified(engine)
            val extra = loadExtraItemsVerified(context, engine)
            val merged = mergeCompletionTables(user, native, extra)
            val sp = buildValueSectionProperties(merged)
            aggregator = ValueCompletionAggregator(
                context,
                sp,
                ValueCompletionOptions(
                    boolEnabled = SettingsManager.devValueCompletionBool,
                    logicBooleanEnabled = SettingsManager.devValueCompletionLogicBoolean,
                    enumEnabled = SettingsManager.devValueCompletionEnum,
                    imageEnabled = SettingsManager.devValueCompletionImage,
                    unitSpawnEnabled = SettingsManager.devValueCompletionUnitSpawn,
                    autoTriggerOnEventEnabled = SettingsManager.devValueCompletionAutoTriggerOnEvent
                ),
                engine.getTranslationDict()
            )
            // 按属性名去重，合并其所属分类
            val map = linkedMapOf<String, PropEntry>()
            sp.forEach { (cat, props) ->
                props.forEach { p ->
                    val existing = map[p.name]
                    map[p.name] = if (existing == null) {
                        PropEntry(p.name, p.name_en.orEmpty(), p.type, p.desc_zh, listOf(cat))
                    } else {
                        existing.copy(categories = (existing.categories + cat).distinct())
                    }
                }
            }
            val entryList = map.values.toList().sortedBy { it.name.lowercase() }
            // 过滤掉「点进去后可调用补全为空」的属性，避免列表出现空详情；无项目扫描数据时保留全部
            val agg = aggregator
            val info = ProjectTagScanner.getCachedInfo()
            val entriesToShow = if (agg != null) {
                entryList.filter { hasDemoCompletions(agg, context, it, info) }
            } else {
                entryList
            }
            entries = entriesToShow
            // 属性挑选列表的分类 chips 统一为「+号」分类口径（CALLABLE_CATEGORIES），仅列出有属性的分类
            val typeCats = entriesToShow.map { classifyCallableCategory("", it.type) }
                .filter { it.isNotBlank() }
                .distinct()
            categories = CALLABLE_CATEGORIES.filter { it in typeCats } +
                typeCats.filter { it !in CALLABLE_CATEGORIES }
        }
        loaded = true
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // 进入详情（演示面板）时，返回先回到属性列表；分类浏览时返回关闭分类
    BackHandler(enabled = selectedPropertyName != null || callableCategory != null) {
        if (callableCategory != null) onCloseCallableCategory()
        if (selectedPropertyName != null) onSelectProperty(null)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }
            return@Column
        }

        if (callableCategory != null) {
            CallableCategoryBrowser(categoryLabel = callableCategory, aggregator = aggregator)
            return@Column
        }

        val current = selectedPropertyName?.let { name -> entries.firstOrNull { it.name == name } }
        if (current == null) {
            // 属性挑选
            SectionHeader(Icons.Default.Code, "选择要查看的属性")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索属性名 / 英文 / 描述", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            Spacer(Modifier.height(8.dp))
            // 节/分类过滤
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("全部", fontSize = 12.sp) }
                    )
                }
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(categoryClassLabel(cat), fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val filtered = remember(query, selectedCategory, entries) {
                val q = query.trim()
                entries.filter { e ->
                    (selectedCategory == null || classifyCallableCategory("", e.type) == selectedCategory) &&
                            (q.isEmpty() ||
                                    e.name.contains(q, ignoreCase = true) ||
                                    e.nameEn.contains(q, ignoreCase = true) ||
                                    e.desc.contains(q, ignoreCase = true) ||
                                    e.type.contains(q, ignoreCase = true))
                }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { e ->
                    PropRow(e, selectedCategory) { onSelectProperty(e.name) }
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("无匹配属性", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        } else {
            DemoPanel(
                entry = current,
                aggregator = aggregator
            )
        }
    }
}

/** 属性挑选列表顶部分类 chips 的显示标签：统一为「+号」分类口径（CALLABLE_CATEGORIES）并加「类」后缀 */
private fun categoryClassLabel(cat: String): String = "${cat}类"

/** 属性列表行 */
@Composable
private fun PropRow(e: PropEntry, selectedCategory: String?, onClick: () -> Unit) {
    val section = selectedCategory ?: classifyCallableCategory("", e.type)
    val format = remember(e.name, e.type) { buildFormatTemplate(e.name, e.type) }
    ElevatedCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, fontSize = 13.sp, color = RustedOnBackground, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.nameEn.isNotBlank() && e.nameEn != e.name) {
                        Spacer(Modifier.width(6.dp))
                        Text(e.nameEn, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.type.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = RustedPrimary.copy(alpha = 0.12f)
                        ) {
                            Text(e.type, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 9.sp, color = RustedPrimary)
                        }
                    }
                    if (section.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(section, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                    }
                }
                // 属性输入格式，如 `tags:标签名,标签名`，无需进入详情即可查看
                Spacer(Modifier.height(3.dp))
                Text(
                    format.displayLine,
                    fontSize = 10.sp,
                    color = RustedPrimary.copy(alpha = 0.7f),
                    fontFamily = AppCodeFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.4f))
        }
    }
}

/** 演示面板：可编辑演示行 + 格式模板（可点击占位）+ 按 Provider 分组的实时补全 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(
    entry: PropEntry,
    aggregator: ValueCompletionAggregator?
) {
    val context = LocalContext.current
    var demoText by rememberSaveable { mutableStateOf(entry.name + ":") }
    // 切换属性时重置演示行
    LaunchedEffect(entry.name) { demoText = entry.name + ":" }

    // 属性输入格式模板（由 value type 推导），占位段可点击定位到各自补全上下文
    val template = remember(entry.name, entry.type) { buildFormatTemplate(entry.name, entry.type) }

    val info = ProjectTagScanner.getCachedInfo()

    // 按当前演示行（demoText）构造真实值补全上下文：属性名/值前缀随输入变化，空结果的 Provider 不输出。
    // 不再使用 getAllCallableItems（全量、与上下文无关），避免「ammo:」等无匹配属性刷出「所有类型」。
    val grouped = remember(aggregator, demoText) {
        val agg = aggregator ?: return@remember emptyList<ValueCompletionAggregator.ProviderResult>()
        if (demoText.isBlank()) return@remember emptyList()
        val (keyPart, rawValue) = splitKeyValueLine(demoText)
        val replaceable = replaceableValuePrefix(rawValue)
        agg.getValueCompletionsGrouped(
            context = context,
            propertyName = keyPart,
            sectionName = null,
            valuePrefix = replaceable,
            rawValuePrefixLength = replaceable.length,
            lineText = demoText,
            textBeforeCursor = demoText,
            textAfterCursor = "",
            memoryNames = info?.memories ?: emptySet(),
            memoryTypes = info?.memoryTypes ?: emptyMap(),
            globalVariables = info?.globalVariables ?: emptySet(),
            tags = info?.tags ?: emptySet(),
            globalTags = info?.globalTags ?: emptySet(),
            messageTags = info?.messageTags ?: emptySet(),
            resources = info?.resources ?: emptySet(),
            globalResources = info?.globalResources ?: emptySet(),
            unitNames = info?.unitNames ?: emptySet()
        )
    }

    // 扁平化为展示行（组头 + 条目）；条目携带插入所需的信息（insertText/valuePrefixLength），供点击回填
    data class DisplayItem(
        val isHeader: Boolean,
        val label: String,
        val sub: String = "",
        val insertText: String = "",
        val valuePrefixLength: Int = 0
    )
    val displayRows = remember(grouped) {
        val list = mutableListOf<DisplayItem>()
        // 仅展示当前演示行上下文的真实值补全结果；分组完全复刻「+」分类口径（布尔值/布尔表达式拆分、连接符/运算符）。
        // 扁平化前按 label 去重：kvp 单位上下文（设置单位内存:目标=）下 LogicBoolean(memoryNames) 与 Memory 会各出一份 内存.xxx，
        // 与本面板同源展示去重（生产编辑器已在上游 CompletionProvider 去重，此处仅查看看板去重）。
        val byType = grouped
            .flatMap { g -> g.items }
            .distinctBy { it.label }
            .groupBy { classifyCallableCategory(it.label, it.valueType) }
        // 按「+」分类固定顺序展示，跳过空分组，避免随出现先后抖动
        val orderedTypes = CALLABLE_CATEGORIES + byType.keys.filter { it !in CALLABLE_CATEGORIES }
        orderedTypes.forEach { typeLabel ->
            val items = byType[typeLabel] ?: return@forEach
            list.add(DisplayItem(true, typeLabel, "${items.size} 项"))
            items.forEach { it ->
                list.add(DisplayItem(false, it.label, it.detail, it.insertText, it.valuePrefixLength))
            }
        }
        list
    }

    // 点击补全项回填到演示行末尾：按 valuePrefixLength 从末尾回退替换，插入 insertText（与生产补全替换语义一致）
    val fillDemoFromItem: (DisplayItem) -> Unit = { row ->
        val insert = row.insertText
        if (insert.isNotEmpty()) {
            val len = row.valuePrefixLength
            demoText = if (len > 0 && len <= demoText.length) {
                demoText.substring(0, demoText.length - len) + insert
            } else {
                demoText + insert
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 属性信息
        Surface(shape = RoundedCornerShape(12.dp), color = RustedSurface) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
                    if (entry.nameEn.isNotBlank() && entry.nameEn != entry.name) {
                        Spacer(Modifier.width(6.dp))
                        Text(entry.nameEn, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    }
                }
                if (entry.type.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("类型: ${entry.type}", fontSize = 11.sp, color = RustedPrimary)
                }
                if (entry.desc.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.desc, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 格式模板：展示该属性的输入格式，占位（如「标签名」）可点击定位到该处查看补全
        Text("格式模板（点击占位提示定位到对应补全）", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .background(RustedSurface, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            template.pieces.forEachIndexed { index, (text, hint) ->
                if (hint != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = RustedPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.clickable {
                            demoText = template.realLine.take(template.bounds[index])
                        }
                    ) {
                        Text(
                            hint,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                            color = RustedPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text,
                        fontSize = 13.sp,
                        color = RustedOnBackground,
                        fontFamily = AppCodeFontFamily
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 演示行
        Text("演示行（可编辑，光标停留在末尾）", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = demoText,
            onValueChange = { demoText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = AppCodeFontFamily),
            minLines = 2
        )
        Spacer(Modifier.height(6.dp))
        Spacer(Modifier.height(12.dp))

        // 补全结果
        Text("可调用补全（${displayRows.count { !it.isHeader }} 项）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RustedOnBackground)
        Spacer(Modifier.height(6.dp))
        if (displayRows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("当前光标位置无可补全项", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.4f))
            }
        } else {
            displayRows.forEach { displayRow ->
                if (displayRow.isHeader) {
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = RustedPrimary.copy(alpha = 0.14f)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(displayRow.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RustedPrimary, modifier = Modifier.weight(1f))
                            Text(displayRow.sub, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable {
                                fillDemoFromItem(displayRow)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("· ", fontSize = 11.sp, color = RustedPrimary)
                        Text(displayRow.label, fontSize = 12.sp, color = RustedOnBackground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (displayRow.sub.isNotBlank() && displayRow.sub != displayRow.label) {
                            Text(displayRow.sub, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 格式模板：由字面段与占位提示段交替组成。字面段拼成“真实编辑器行”realLine；占位段仅作提示，点击后定位到该处前缀以查看补全。 */
private data class FormatTemplate(
    val pieces: List<Pair<String, String?>> // first=字面/显示文本, second 非空表示占位提示
) {
    /** 真实编辑器行（仅含字面段，不含占位提示） */
    val realLine: String = pieces.joinToString("") { it.first }

    /** 展示用的完整格式（字面段 + 占位提示文本），如 tags → `tags:标签名,标签名` */
    val displayLine: String = pieces.joinToString("") { it.second ?: it.first }

    /** 各段在 realLine 中的起始长度：占位段该值即“点击后应裁剪到的前缀长度” */
    val bounds: List<Int> = run {
        var acc = 0
        pieces.map { (text, hint) ->
            val start = acc
            if (hint == null) acc += text.length
            start
        }
    }
}

/** 依据 value type 生成属性输入格式模板（如 tags → `tags:标签名,标签名`）；无法识别时退化为 `属性名:类型`。 */
private fun buildFormatTemplate(name: String, type: String): FormatTemplate {
    val head = name + ":"
    val t = type.lowercase().trim()
    val pieces = mutableListOf<Pair<String, String?>>(head to null)
    fun lit(s: String) { pieces.add(s to null) }
    fun ph(h: String) { pieces.add(h to h) }

    // 单位标记（填单位标记表达式）；注意「unit ref」带空格=标记，「unitref」无空格=单位类型，不能混用
    val marker = setOf(
        "unit ref", "unit ref/marker", "unit ref / marker", "marker", "marker ref", "event", "unit ref"
    )
    if (t in marker) { ph("单位标记") }
    else if (t.contains("/marker") || t.contains("标记表达式") || t.contains("marker expr")) { ph("单位标记") }
    // 单位类型引用（填单位类型名）
    else if (t in setOf("unitref", "unitref/unittype", "unittype", "unittypes", "unit types")) { ph("单位类型名") }
    else if (t in setOf("tags", "taglist", "tag ref")) { ph("标签名"); lit(","); ph("标签名") }
    else if (t == "message tag") { ph("消息标签") }
    else if (t.contains("tag") || t.contains("标签")) { ph("标签名"); lit(","); ph("标签名") }
    else if (t in setOf("resources", "customresource")) { ph("资源名") }
    else if (t == "dynamic resources") { ph("资源名"); lit("="); ph("表达式") }
    else if (t in setOf("key value pairs", "key-value", "key value")) { ph("变量名"); lit("="); ph("值") }
    else if (t in setOf("action ref", "action refs", "action ids", "actions")) { ph("行动名") }
    else if (t in setOf("turret ref")) { ph("炮塔名") }
    else if (t in setOf("effect ref")) { ph("效果名") }
    else if (t in setOf("projectile ref")) { ph("抛射体名") }
    else if (t in setOf("animation id", "animation ref")) { ph("动画名") }
    else if (t in setOf("sound ref", "sound(s)", "sound(s)")) { ph("音效名") }
    else if (t in setOf("file (image)", "file(image)")) { ph("图片文件") }
    else if (t in setOf("file(s) (ini)", "file(s) (ini)")) { ph("ini文件名") }
    else if (t in setOf("enum")) { ph("枚举值") }
    else if (t in setOf("point", "point3d")) { ph("坐标") }
    else if (t in setOf("colour", "color")) { ph("颜色值") }
    else if (t in setOf("movementtypes", "movement types")) { ph("移动类型") }
    else if (t in setOf("fields", "fields values", "dynamics", "list", "addenergy")) { ph("值") }
    else if (t in setOf("relation", "teamrelation")) { ph("关系") }
    else if (t.contains("logic") // 逻辑表达式（LogicBoolean/logicBoolean/logicNumber/logicnumber）
            || t in setOf("logicboolean", "logicnumber")) { ph("逻辑表达式") }
    else if (t in setOf("bool", "boolean")) { ph("真/假") }
    else if (t.contains("price") || t in setOf("int", "ints", "float", "time", "number", "customprice")) { ph("数值") }
    else if (t.isBlank()) { ph("值") }
    else { ph(if (type.isBlank()) "值" else type) } // 兜底直接展示类型本身

    return FormatTemplate(pieces)
}

/** 该属性在默认演示上下文（`name+":"`，光标在值起点）下是否可产出补全项；用于列表隐藏“点进去后可调用补全为空”的属性。 */
private fun hasDemoCompletions(
    aggregator: ValueCompletionAggregator,
    context: Context,
    entry: PropEntry,
    info: ProjectTagScanner.ProjectTagInfo?
): Boolean {
    val demoText = entry.name + ":"
    val (keyPart, rawValue) = splitKeyValueLine(demoText)
    val replaceable = replaceableValuePrefix(rawValue)
    val results = aggregator.getValueCompletionsGrouped(
        context = context,
        propertyName = keyPart,
        sectionName = null,
        valuePrefix = replaceable,
        rawValuePrefixLength = replaceable.length,
        lineText = demoText,
        textBeforeCursor = demoText,
        textAfterCursor = "",
        memoryNames = info?.memories ?: emptySet(),
        memoryTypes = info?.memoryTypes ?: emptyMap(),
        globalVariables = info?.globalVariables ?: emptySet(),
        tags = info?.tags ?: emptySet(),
        globalTags = info?.globalTags ?: emptySet(),
        messageTags = info?.messageTags ?: emptySet(),
        resources = info?.resources ?: emptySet(),
        globalResources = info?.globalResources ?: emptySet(),
        unitNames = info?.unitNames ?: emptySet()
    )
    return results.any { it.items.isNotEmpty() }
}

/** 属性条目（去重后） */
private data class PropEntry(
    val name: String,
    val nameEn: String,
    val type: String,
    val desc: String,
    val categories: List<String>
)

/** 由生产补全条目转浏览条目（+号 分类浏览数据源与生产补全同源） */
private fun CompletionProvider.CompletionItem.toValueItem(): ValueDataLoader.ValueItem =
    ValueDataLoader.ValueItem(
        name = label,
        type = valueType.ifBlank { detail },
        description = detail,
        example = insertText.ifBlank { detail }
    )

/**
 * 可调用对象分类浏览：展示某分类（单位标记/数值表达式/布尔值/布尔表达式/文本表达式/连接符/运算符/任意类型）下的条目。
 * 数据源与生产补全同源（经 aggregator 从 Provider 产条目）；「连接符/布尔值/运算符」为语法项拆分（LOGIC_CONNECTORS/
 * LOGIC_ENTRY_TOKENS/OPERATOR_ITEMS，生产 Provider 不产出这些语法项）；「任意类型」展示全部条目。
 */
@Composable
private fun CallableCategoryBrowser(categoryLabel: String, aggregator: ValueCompletionAggregator?) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<ValueDataLoader.ValueItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(categoryLabel, aggregator) {
        withContext(Dispatchers.IO) {
            val engine = TranslationEngine.getInstance()
            if (!engine.isLoaded) engine.load(context)
            val dict = engine.getTranslationDict()
            // 「连接符/布尔值/运算符」为语法项拆分（+号 特有，生产 Provider 不产出），直接用语法项合集
            val syntaxItems = when (categoryLabel) {
                "连接符" -> LOGIC_CONNECTORS.map { ValueDataLoader.ValueItem(name = it, type = "bool") }
                "运算符" -> (OPERATOR_ITEMS + "not").map { ValueDataLoader.ValueItem(name = it, type = "bool") }
                "布尔值" -> LOGIC_ENTRY_TOKENS.map { ValueDataLoader.ValueItem(name = it, type = "bool") }
                else -> emptyList()
            }
            if (syntaxItems.isNotEmpty()) {
                items = syntaxItems
            } else {
                // 其余分类：从生产补全 Provider 同源产出（与补全展示一致，含 self）
                val info = ProjectTagScanner.getCachedInfo()
                val providerItems = aggregator?.getAllCallableItems(
                    context, dict,
                    info?.memories ?: emptySet(),
                    info?.memoryTypes ?: emptyMap()
                )?.flatMap { it.items }.orEmpty()
                items = if (categoryLabel == "任意类型") {
                    providerItems.map { it.toValueItem() }
                } else {
                    providerItems
                        .filter { classifyCallableCategory(it.label, it.valueType) == categoryLabel }
                        .map { it.toValueItem() }
                }
            }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(Icons.Default.Code, "可调用 · $categoryLabel（${items.size} 个）")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索名称", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        Spacer(Modifier.height(8.dp))
        val filtered = remember(query, items) {
            val q = query.trim()
            items.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { item ->
                    ElevatedCard(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, fontSize = 13.sp, color = RustedOnBackground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (item.type.isNotBlank()) {
                                    Surface(shape = RoundedCornerShape(4.dp), color = RustedPrimary.copy(alpha = 0.12f)) {
                                        Text(item.type, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 9.sp, color = RustedPrimary)
                                    }
                                }
                            }
                            if (item.example.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                SelectionContainer {
                                    Text(item.example, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f), fontFamily = AppCodeFontFamily)
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("无匹配条目", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}