package com.rwmodstudio.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.CompletionTypeRepository
import com.rwmodstudio.core.translation.MockIniGenerator
import com.rwmodstudio.core.translation.PropertyClassifier
import com.rwmodstudio.core.translation.ResourceNameKeys
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.LOGIC_CONNECTORS
import com.rwmodstudio.feature.completion.LOGIC_ENTRY_TOKENS
import com.rwmodstudio.feature.completion.OPERATOR_ITEMS
import com.rwmodstudio.feature.completion.value.CALLABLE_CATEGORIES
import com.rwmodstudio.feature.completion.value.ParamDataLoader
import com.rwmodstudio.feature.completion.value.ValueCompletionAggregator
import com.rwmodstudio.feature.completion.value.ValueCompletionAggregator.ValueCompletionOptions
import com.rwmodstudio.feature.completion.value.ValueDataLoader
import com.rwmodstudio.feature.completion.value.classifyCallableCategory
import com.rwmodstudio.feature.completion.value.translateValueName
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 补全类型分类页（开发者工具）。
 *
 * 全部属性按「补全类型分类」归类展示（分类 = 调用源：该属性由哪个补全代码服务），
 * 归类唯一来源是「真实补全判定」（ValueCompletionAggregator.realCompletionClassify）：
 * 与生产补全同一组 Provider、同顺序、首中即定，**不存在第二份硬编码名单**，永不漂移。
 * 本页可对单个属性做 manual 覆盖。
 *
 * 分类共 14 类：13 属性类（属性名的补全来源，键不带括号） + 1 参数类（值类，仅作用于值：
 * 直接列出可带括号调用的值本身，如 int(a,b)、distance(x1,y1)、select(bool,textA,textB)）。
 * 同时并入「资源名补全属性」名单维护（每行开关）。
 *
 * 支持筛选：按分类（含参数类）+ 仅资源名补全 + 关键词搜索；
 * 「分类统计」可折叠表列出每类命中数（验证空分类/漏归档）。
 */
@Composable
fun CompletionTypeScreen(
    callableCategory: String? = null,
    onCloseCallableCategory: () -> Unit = {}
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    // 真实补全判定聚合器（全部 Provider、默认选项；与生产补全同源），条目分类唯一来源
    var classifyAggregator by remember { mutableStateOf<ValueCompletionAggregator?>(null) }
    // 每个属性首中所属的真实节名（分类页按各真实节判定，如 复制与 仅核心节归「项目引用」，不跨节兜底）
    var entrySection by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // manual 覆盖（属性名 → 分类）
    var manualOverrides by remember { mutableStateOf(mapOf<String, String>()) }
    // 资源名补全名单（本地编辑态，保存时写盘）
    var rnKeys by remember { mutableStateOf(ResourceNameKeys.DEFAULT_KEYS) }
    // 筛选状态
    var query by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var onlyRn by remember { mutableStateOf(false) }
    // 正在编辑的项
    var editing by remember { mutableStateOf<Entry?>(null) }
    var pendingEdit by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 分类统计表（默认收起，不占空间，展开可见各分类命中数用于验证）
    var showStats by remember { mutableStateOf(false) }
    // 参数类（值类）函数值清单：可带括号调用的值本身（如 int(a,b)、distance(x1,y1)、select(bool,textA,textB)）
    var paramFuncs by remember { mutableStateOf<List<ParamFunc>>(emptyList()) }
    // 值补全聚合器（供「+」号可调用对象分类浏览，与生产补全同源）
    var aggregator by remember { mutableStateOf<ValueCompletionAggregator?>(null) }
    var paramLoaded by remember { mutableStateOf(false) }

    // 加载：真实节属性（getRealSectionNames，不含 value 数据文件条目）+ 合成指令属性 + 值补全聚合器；
    // 分类归属唯一来源 = 真实补全判定（manual 覆盖优先，否则按 Provider 实际服务归属，与自动补全同源）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val repo = CodeReferenceRepository()
            repo.loadFromAssets(context)
            CompletionTypeRepository.load()
            manualOverrides = CompletionTypeRepository.currentManual()
            rnKeys = ResourceNameKeys.load().keys

            // 构建值补全聚合器（「+」号分类浏览与生产补全同源）+ 真实补全判定聚合器（全 Provider 默认选项）
            val engine = TranslationEngine.getInstance()
            if (!engine.isLoaded) engine.load(context)
            paramFuncs = loadParamFuncs(context, engine.getTranslationDict())
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
            // 真实补全判定聚合器：默认选项 = 全部 Provider 开启（含图片路径），
            // 分类归属与运行时开关无关，与自动补全同源
            val classifyAgg = ValueCompletionAggregator(
                context,
                sp,
                ValueCompletionOptions(),
                engine.getTranslationDict()
            )
            classifyAggregator = classifyAgg

            // 收集 17 个真实节的属性（按 name 去重，首中优先；与生成脚本同口径），并记录属性首中所属真实节
            val props = linkedMapOf<String, CodeReferenceRepository.PropertyInfo>()
            val sectOf = linkedMapOf<String, String>()
            repo.getRealSectionNames().forEach { sec ->
                repo.getPropertiesForSection(sec).forEach { p ->
                    if (!p.name.contains(": ")) {
                        if (props.putIfAbsent(p.name, p) == null) sectOf[p.name] = sec
                    }
                }
            }
            entrySection = sectOf
            // 分类：manual 覆盖优先，否则真实补全判定（按属性所在真实节，与自动补全同源，无第二份名单、不跨节兜底）
            fun classify(name: String, nameEn: String?, sec: String?): String =
                manualOverrides[name]
                    ?: classifyAgg.realCompletionClassify(context, name, nameEn?.takeIf { it.isNotBlank() }, sec) 
            val list = props.values.map { p ->
                Entry(
                    p.name, p.name_en.orEmpty(), p.type,
                    classify(p.name, p.name_en, sectOf[p.name]),
                    isResourceName = isResourceName(p, rnKeys)
                )
            }.toMutableList()
            // 合成条目：不在 code_reference 但真实存在的指令属性（复制节 等）
            CompletionTypeRepository.SYNTHETIC_PROPERTIES.forEach { sp ->
                list.add(
                    Entry(
                        sp.name, sp.nameEn, "-",
                        classify(sp.name, sp.nameEn, null),
                        isResourceName = false
                    )
                )
            }
            entries = list.sortedBy { it.name }
            loaded = true
            paramLoaded = true
        }
    }

    // 「+」号分类浏览：顶栏选择分类后进入（数据源与生产补全同源）
    if (callableCategory != null) {
        CallableCategoryBrowser(
            categoryLabel = callableCategory,
            aggregator = aggregator,
            onClose = onCloseCallableCategory
        )
        return
    }

    // ===== 平板横屏：左侧竖排分类、右侧内容（分类 chips 在横屏时隐藏，改用左栏）=====
    // 分栏只看「平板模式」开关，跟横竖屏无关：开着就左栏+右内容，关掉就是原来的单列
    val isSplit = com.rwmodstudio.core.SettingsManager.devTabletMode

    @Composable
    fun Body() {
    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        // 顶部说明 + 操作（另存 / 重置）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "补全类型分类 + 资源名补全名单（点击可编辑，归类=真实补全判定，与自动补全同源）",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f)
            )
            if (loaded) {
                TextButton(onClick = {
                    // 重置：清空内存 manual + 删除持久化文件，分类回到真实补全判定
                    CompletionTypeRepository.resetManual()
                    ResourceNameKeys.reset()
                    manualOverrides = emptyMap()
                    val defaultKeys = ResourceNameKeys.DEFAULT_KEYS
                    rnKeys = defaultKeys
                    val agg = classifyAggregator
                    entries = entries.map {
                        Entry(
                            it.name, it.nameEn, it.type,
                            if (agg != null) {
                                agg.realCompletionClassify(
                                    context,
                                    it.name,
                                    it.nameEn.takeIf { x -> x.isNotBlank() },
                                    entrySection[it.name]
                                )
                            } else CompletionTypeRepository.CAT_TEXT,
                            isResourceName = ResourceNameKeys(keys = defaultKeys).matches(
                                it.nameEn.takeIf { x -> x.isNotBlank() } ?: it.name
                            )
                        )
                    }
                    Toast.makeText(context, "已重置分类与资源名单（恢复默认）", Toast.LENGTH_SHORT).show()
                }) { Text("重置", color = RustedError, fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val catOk = CompletionTypeRepository.saveManual(manualOverrides)
                        val rnOk = ResourceNameKeys.save(rnKeys)
                        val m = CompletionTypeRepository.currentManual()
                        if (m != manualOverrides) manualOverrides = m
                        Toast.makeText(
                            context,
                            when {
                                catOk && rnOk -> "已保存分类与资源名单"
                                catOk -> "分类已保存，资源名单失败"
                                rnOk -> "资源名单已保存，分类失败"
                                else -> "保存失败"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
                ) { Text("保存", fontSize = 12.sp) }
            }
        }

        // 关键词搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("搜索属性名 / 英文 / 分类", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        Spacer(Modifier.height(8.dp))

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }
            return@Column
        }

        // 分类筛选 + 资源名补全筛选（横屏时改用左侧竖栏，这里隐藏）
        if (!isSplit) LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedCat == null,
                    onClick = { selectedCat = null },
                    label = { Text("全部", fontSize = 11.sp) }
                )
            }
            items(CompletionTypeRepository.CATEGORY_ORDER) { cat ->
                FilterChip(
                    selected = selectedCat == cat,
                    onClick = { selectedCat = if (selectedCat == cat) null else cat },
                    label = { Text(cat, fontSize = 11.sp) }
                )
            }
            item {
                FilterChip(
                    selected = onlyRn,
                    onClick = { onlyRn = !onlyRn },
                    label = { Text("仅资源名补全", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Paid, null, Modifier.size(14.dp)) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 按分类分组 + 筛选（属性类按主分类；参数类直接展示函数值）
        val grouped = remember(query, entries, selectedCat, onlyRn) {
            val q = query.trim()
            val filtered = entries.filter { e ->
                val sel = selectedCat
                val inCat = sel == null || (
                    if (sel in CompletionTypeRepository.PROPERTY_CATEGORY_ORDER) e.category == sel
                    else sel == CompletionTypeRepository.CAT_PARAM
                    )
                (q.isEmpty() || e.name.contains(q, true) || e.nameEn.contains(q, true) ||
                    e.category.contains(q, true) || e.type.contains(q, true)) &&
                    inCat && (!onlyRn || e.isResourceName)
            }
            val result = linkedMapOf<String, List<Entry>>()
            CompletionTypeRepository.PROPERTY_CATEGORY_ORDER.forEach { cat ->
                result[cat] = filtered.filter { it.category == cat }
            }
            result
        }
        // 参数类（值类）函数值清单筛选
        val filteredParams = remember(query, paramFuncs, selectedCat) {
            val q = query.trim()
            val inCat = selectedCat == null || selectedCat == CompletionTypeRepository.CAT_PARAM
            if (!inCat) return@remember emptyList()
            paramFuncs.filter {
                q.isEmpty() || it.zhName.contains(q, true) || it.baseName.contains(q, true) ||
                    it.signature.contains(q, true) || it.params.any { p -> p.contains(q, true) }
            }
        }
        // 全量分类统计（属性类按成员数；参数类按函数值数）
        val categoryCounts = remember(entries, paramFuncs) {
            val m = linkedMapOf<String, Int>()
            CompletionTypeRepository.PROPERTY_CATEGORY_ORDER.forEach { cat ->
                m[cat] = entries.count { it.category == cat }
            }
            CompletionTypeRepository.SYNTAX_CATEGORY_ORDER.forEach { cat ->
                m[cat] = paramFuncs.size
            }
            m
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分类统计：属性类+语法类均按成员数验证（默认收起不占空间）
            item {
                CollapsibleHeader("分类统计（${entries.size} 个属性）", showStats) {
                    showStats = !showStats
                }
                AnimatedVisibility(showStats) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = RustedSurface),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("属性类（成员数）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RustedPrimary)
                            CompletionTypeRepository.PROPERTY_CATEGORY_ORDER.forEach { cat ->
                                StatsRow(cat, categoryCounts[cat] ?: 0)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("值类（函数值数 = 可带括号调用的值，如 int(a,b)）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RustedPrimary)
                            CompletionTypeRepository.SYNTAX_CATEGORY_ORDER.forEach { cat ->
                                StatsRow(cat, categoryCounts[cat] ?: 0)
                            }
                            if (categoryCounts[CompletionTypeRepository.CAT_TEXT] ?: 0 > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "「${CompletionTypeRepository.CAT_TEXT}」为兜底分类：未命中任何名单（手填数值/文本），若发现本应有补全的属性落此，把它加进对应名单即可。",
                                    fontSize = 10.sp,
                                    color = RustedError.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }

            // 全部分类：属性类与参数类统一渲染（参数类直接展示可带括号调用的函数值清单）
            CompletionTypeRepository.PROPERTY_CATEGORY_ORDER.forEach { cat ->
                val list = grouped[cat]
                if (!list.isNullOrEmpty()) {
                    item {
                        SectionHeader(Icons.Default.Category, "$cat（${list.size}）")
                        CompletionTypeRepository.CATEGORY_SOURCE[cat]?.let { src ->
                            Text(
                                "调用源：$src",
                                fontSize = 10.sp,
                                color = RustedPrimary.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                    }
                    items(list) { e ->
                        PropCatRow(
                            e,
                            inManual = manualOverrides.containsKey(e.name),
                            onToggleResourceName = {
                                val key = rnKeyOf(e)
                                rnKeys = if (e.isResourceName) {
                                    rnKeys.filterNot { it == key }
                                } else {
                                    (rnKeys + key).distinct()
                                }
                                entries = entries.map {
                                    if (it.name == e.name) it.copy(isResourceName = !e.isResourceName) else it
                                }
                            },
                            onClick = { editing = e }
                        )
                    }
                }
            }
            // 参数类（值类）：直接列出可带括号调用的函数值本身（int(a,b)、distance(x,y)、select(bool,textA,textB) 等）
            CompletionTypeRepository.SYNTAX_CATEGORY_ORDER.forEach { cat ->
                if (selectedCat == null || selectedCat == cat) {
                    item {
                        SectionHeader(Icons.Default.Functions, "$cat（${filteredParams.size}）")
                        CompletionTypeRepository.CATEGORY_SOURCE[cat]?.let { src ->
                            Text(
                                "调用源：$src",
                                fontSize = 10.sp,
                                color = RustedPrimary.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                        val info = CompletionTypeRepository.PARAM_INFO
                        Card(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = RustedSurface),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                SyntaxInfoRow("触发", info.trigger)
                                SyntaxInfoRow("补全", info.completes)
                                SyntaxInfoRow("位置", info.positions)
                            }
                        }
                    }
                    items(filteredParams) { f ->
                        ParamFuncRow(f)
                    }
                }
            }
        }
    }
    }

    if (isSplit) {
        Row(Modifier.fillMaxSize().background(RustedBackground)) {
            CompletionCategoryRail(
                selected = selectedCat,
                onlyRn = onlyRn,
                onSelect = { selectedCat = it },
                onOnlyRnChange = { onlyRn = it }
            )
            Box(Modifier.weight(1f)) { Body() }
        }
    } else {
        Body()
    }

    // 编辑对话框：选择属性分类（13 类；值语法类非属性归类，不在覆盖范围）
    editing?.let { entry ->
        var category by remember(entry.name) { mutableStateOf(entry.category) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(entry.name, fontSize = 16.sp) },
            text = {
                Column {
                    Text("英文: ${entry.nameEn}", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    Text("类型: ${entry.type}", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    Text("（归类=真实补全判定，此处为手工覆盖，保存后生效）", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                    Spacer(Modifier.height(10.dp))
                    Text("属性分类", fontSize = 12.sp, color = RustedOnBackground)
                    Spacer(Modifier.height(4.dp))
                    FlowRowCompat(CompletionTypeRepository.PROPERTY_CATEGORY_ORDER) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingEdit = entry.name to category
                        editing = null
                    }) { Text("应用", color = RustedPrimary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }

    // 应用手动覆盖：更新内存 map 与条目展示
    pendingEdit?.let { (name, cat) ->
        LaunchedEffect(name, cat) {
            manualOverrides = manualOverrides + (name to cat)
            entries = entries.map {
                if (it.name == name) it.copy(category = cat) else it
            }
            pendingEdit = null
        }
    }
}

/** 统计行：分类名 + 命中数（0 时标红提示空分类） */
@Composable
private fun StatsRow(cat: String, count: Int) {
    Text(
        "$cat：$count",
        fontSize = 11.sp,
        color = if (count == 0) RustedError.copy(alpha = 0.9f) else RustedOnBackground.copy(alpha = 0.8f)
    )
}

/** 语法信息卡字段行：标签 + 内容 */
@Composable
private fun SyntaxInfoRow(label: String, content: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$label：",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = RustedOnBackground.copy(alpha = 0.6f)
        )
        Text(
            content,
            fontSize = 11.sp,
            color = RustedOnBackground.copy(alpha = 0.85f)
        )
    }
}

private data class Entry(
    val name: String,
    val nameEn: String,
    val type: String,
    val category: String,
    val isResourceName: Boolean = false
)

/**
 * 参数类（值类）函数值条目：可带括号调用的值本身（如 int(a,b)、distance(x1,y1,x2,y2)、select(bool,textA,textB)、tookDamage(withTag=)）。
 * 判定口径：只要括号内带参数（命名参数=值 或 位置参数）即算，如自 ishp() 这类空括号（无参）不算。
 * 数据源 = value/{logicboolean,autoTriggerOnEvent}.json 中 name 带 `(…)` 且括号内有内容的条目
 *         + param/{logicboolean,autoTriggerOnEvent}.json 的命名参数函数表。
 */
private data class ParamFunc(
    val baseName: String,   // 英文基名（剥离 self. 与括号），如 select / numberOfUnitsInTeam / tookDamage
    val zhName: String,     // 中文名（翻译引擎），如 选择
    val signature: String,  // 带参签名（原文），如 select(bool, textA, textB) / tookDamage(withTag="#")
    val type: String,       // 返回类型，如 any / float / marker / event
    val params: List<String> // 参数名（命名参数函数来自 param 表；原子函数从签名解析）
)

/**
 * 提取参数类（值类）可带括号调用的函数值清单（括号内必须带参数，空括号不算）：
 * 1) value/{logicboolean,autoTriggerOnEvent}.json：name 含 `(…)` 且括号内有内容的条目
 *    视为可带参调用值（int(x)、select(…)、distance(…)、tookDamage(withTag=) 等）；self.hp() 空括号排除；
 * 2) param/{logicboolean,autoTriggerOnEvent}.json：functions 命名参数函数（numberOfUnitsInTeam、tookDamage 等）。
 * 两源按基名合并去重；中文名经 translateValueName 剥离括号+self. 后由翻译引擎得出，不硬编码。
 */
private fun loadParamFuncs(
    context: android.content.Context,
    dict: com.rwmodstudio.core.translation.TranslationDict?
): List<ParamFunc> {
    val map = linkedMapOf<String, ParamFunc>()

    // 值定义文件的原子函数/事件（name 带括号且括号内有内容 → 可带参调用值）
    listOf("logicboolean", "autoTriggerOnEvent").forEach { fileName ->
        ValueDataLoader.load(context, fileName).data.forEach { item ->
            val sig = item.name.trim()
            // 仅当含括号且括号内非空（有实参）才算值类；self.hp() 这类空括号不算
            val inner = if (sig.contains('(')) sig.substringAfter('(').substringBeforeLast(')').trim() else ""
            if (inner.isNotEmpty()) {
                val base = sig.substringBefore('(').trim().removePrefix("self.").trim()
                    .removePrefix("game.").trim()
                if (base.isEmpty()) return@forEach
                val key = base.lowercase()
                val existing = map[key]
                map[key] = ParamFunc(
                    baseName = base,
                    zhName = translateValueName(dict, sig),
                    signature = sig,
                    type = item.type,
                    params = existing?.params ?: parseParams(sig)
                )
            }
        }
    }

    // 参数表的命名参数函数（补参数名；已存在则仅补参数，签名保留值定义原文）
    listOf("logicboolean", "autoTriggerOnEvent").forEach { fileName ->
        ParamDataLoader.load(context, fileName).functions.forEach { fn ->
            val key = fn.key.removePrefix("self.").trim()
                .removePrefix("game.").trim().lowercase()
            if (key.isEmpty()) return@forEach
            val params = fn.params.map { it.zh.ifEmpty { it.key } }
            val existing = map[key]
            val signature = existing?.signature ?: "${fn.key}(${fn.params.joinToString(", ") { it.key }})"
            map[key] = ParamFunc(
                baseName = existing?.baseName ?: fn.key.removePrefix("self.").removePrefix("game.").trim(),
                zhName = existing?.zhName ?: translateValueName(dict, fn.key),
                signature = signature,
                type = existing?.type ?: "-",
                params = if (existing != null) existing.params.ifEmpty { params } else params
            )
        }
    }

    return map.values.sortedBy { it.zhName }
}

/** 从函数签名中解析位置参数名（取括号内顶层逗号分隔的片段），供原子函数展示 */
private fun parseParams(signature: String): List<String> {
    val inner = signature.substringAfter('(').substringBeforeLast(')').trim()
        .removePrefix("self.")
    if (inner.isEmpty()) return emptyList()
    // 按顶层逗号切分（忽略 [..] 与文件名或字符串内的逗号，这里按最简逗号切分即可）
    return inner.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/** 参数类（值类）函数值卡片：中文名 + 签名 + 类型 + 参数名 */
@Composable
private fun ParamFuncRow(f: ParamFunc) {
    Card(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = RustedSurface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.zhName,
                    fontSize = 13.sp,
                    color = RustedOnBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "值类型：${f.type}",
                    fontSize = 10.sp,
                    color = RustedPrimary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                f.signature,
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (f.params.isNotEmpty()) {
                Text(
                    "参数：${f.params.joinToString("、")}",
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 判定某属性是否命中当期资源名名单（nameEn 优先，无英文用中文名，mutator 变体归一） */
private fun isResourceName(p: CodeReferenceRepository.PropertyInfo, keys: List<String>): Boolean {
    if (keys.isEmpty()) return false
    val en = p.name_en?.takeIf { it.isNotBlank() } ?: p.name
    return ResourceNameKeys(keys = keys).matches(en)
}

/** 解析属性对应的名单键：英文优先（归一 mutatorX_），无英文用中文名归一 */
private fun rnKeyOf(e: Entry): String {
    val en = e.nameEn.takeIf { it.isNotBlank() } ?: e.name
    return ResourceNameKeys().normalizedKey(en)
}

@Composable
private fun PropCatRow(
    entry: Entry,
    inManual: Boolean,
    onToggleResourceName: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RustedSurface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, fontSize = 13.sp, color = RustedOnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (inManual) {
                        Spacer(Modifier.width(6.dp))
                        Text("已改", fontSize = 9.sp, color = RustedPrimary)
                    }
                }
                Text(
                    "${entry.nameEn}  •  ${entry.type}",
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(entry.category, fontSize = 12.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            // 资源名补全开关
            Switch(
                checked = entry.isResourceName,
                onCheckedChange = { onToggleResourceName() },
                modifier = Modifier.scale(0.72f)
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = RustedOnBackground.copy(alpha = 0.4f))
        }
    }
}

/** 可折叠小节标题：一行 + 展开箭头，点击切换 */
@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = RustedPrimary
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = RustedPrimary
        )
    }
}

/** 简单的 FlowRow 包装（横向换行分类选择） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(items: List<String>, chip: @Composable (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { chip(it) }
    }
}

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
private fun CallableCategoryBrowser(
    categoryLabel: String,
    aggregator: ValueCompletionAggregator?,
    onClose: () -> Unit
) {
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
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                SectionHeader(Icons.Default.Code, "可调用 · $categoryLabel（${items.size} 个）")
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "返回", Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.6f))
            }
        }
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

/**
 * 构建「真实补全判定」聚合器：全部 Provider、默认选项（= 全开，含图片路径；分类归属与运行时开关无关），
 * 供分类页与 MainApp 打开模拟 ini 时共用，保证与自动补全同源、不漂移。
 * 需要加载补全三表 + 翻译引擎，较慢，调用方应在 IO 线程。失败返回 null（调用方处理）。
 */
suspend fun buildClassifyAggregator(context: Context): ValueCompletionAggregator? {
    return try {
        val engine = TranslationEngine.getInstance()
        if (!engine.isLoaded) engine.load(context)
        val user = loadUserItems()
        val native = loadNativeItemsVerified(engine)
        val extra = loadExtraItemsVerified(context, engine)
        val merged = mergeCompletionTables(user, native, extra)
        val sp = buildValueSectionProperties(merged)
        ValueCompletionAggregator(
            context,
            sp,
            ValueCompletionOptions(),
            engine.getTranslationDict()
        )
    } catch (e: Exception) {
        Log.e("CompletionTypeScreen", "buildClassifyAggregator failed", e)
        null
    }
}

/**
 * 构建模拟 ini 属性分类器：真实补全判定（与自动补全同源，永不漂移，无第二份名单）。
 * 失败返回 null（调用方处理）。
 */
suspend fun buildMockIniClassifier(context: Context): PropertyClassifier? {
    return buildClassifyAggregator(context)?.let { agg ->
        PropertyClassifier { name, nameEn, sectionName ->
            agg.realCompletionClassify(context, name, nameEn, sectionName)
        }
    }
}

/** 平板横屏时左侧的分类竖栏：点一项即筛选，再点一次回到「全部」 */
@Composable
private fun CompletionCategoryRail(
    selected: String?,
    onlyRn: Boolean,
    onSelect: (String?) -> Unit,
    onOnlyRnChange: (Boolean) -> Unit
) {
    Column(
        Modifier
            .width(168.dp)
            .fillMaxHeight()
            .background(RustedSurface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        CompletionRailItem("全部", selected == null) { onSelect(null) }
        CompletionTypeRepository.CATEGORY_ORDER.forEach { cat ->
            CompletionRailItem(cat, selected == cat) {
                onSelect(if (selected == cat) null else cat)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
        Spacer(Modifier.height(10.dp))
        CompletionRailItem("仅资源名补全", onlyRn) { onOnlyRnChange(!onlyRn) }
    }
}

@Composable
private fun CompletionRailItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RustedPrimary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) RustedPrimary else RustedOnBackground,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
