package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.text.font.FontWeight
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.feature.completion.sectionEnToZh

import com.rwmodstudio.ui.components.CustomCompletionEditorDialog
import com.rwmodstudio.ui.theme.*

private const val TAG = "CustomCompletionsScreen"

private enum class CompletionFilter { USER, ALL, NATIVE, EXTRA }

// 防止用户快速连点保存导致多个协程同时写同一个 JSON 文件
private val completionsSaveMutex = Mutex()

/** 空值属性（原「数值属性」）格式分类：补全时只插入「名称:」，不附带默认值，由用户或值补全填写 */
const val FORMAT_EMPTY_VALUE = "空值属性"
/** 旧版「数值属性」别名：兼容已存表，插入仍按「名称:」，解析时归一化为 空值属性 */
const val LEGACY_FORMAT_NUMERIC = "数值属性"

/** 自由输入类型白名单：数值类 + 文本类（小写、trim 后精确匹配） */
private val freeInputPropertyTypes = setOf(
    "int", "integer", "ints", "float", "number", "logicnumber",
    "time", "time (seconds)", "addenergy",
    "string", "string(s)", "strings(s)", "localestring"
)

/** 是否为自由输入属性（数值或文本）：补全只插入「名称:」；price/自定义资源键值对、布尔及组合类型不算 */
fun isFreeInputPropertyType(type: String): Boolean =
    type.trim().lowercase() in freeInputPropertyTypes

@Serializable
data class CustomCompletion(
    val name: String,
    val value: String = "",
    val detail: String = "",
    val desc: String = "",
    val example: String = "",
    val category: List<String> = listOf("属性"),
    val formatCategory: String = "属性",
    val isOverridden: Boolean = false,
    val nameEn: String = "",
    val valueEn: String = "",
    val detailEn: String = "",
    val descEn: String = "",
    val exampleEn: String = ""
)

private val jsonFormat = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

/** section 英文名 → 统一中文分类。单一来源：复用 CompletionProvider.sectionEnToZh，避免两处映射漂移 */
fun mapSectionCategory(s: String): String {
    val low = s.lowercase().replace(" ", "")
    return sectionEnToZh[low] ?: s
}

/** 兼容旧数据：category 字段可能是字符串，自动转成单元素列表 */
internal fun parseCustomCompletions(json: String): List<CustomCompletion> {
    return try {
        val element = jsonFormat.parseToJsonElement(json)
        when (element) {
            is kotlinx.serialization.json.JsonArray -> {
                element.map { fixCompletionCategory(it) }
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun fixCompletionCategory(element: kotlinx.serialization.json.JsonElement): CustomCompletion {
    return when (element) {
        is kotlinx.serialization.json.JsonObject -> {
            jsonFormat.decodeFromJsonElement(CustomCompletion.serializer(), fixCategoryAndFormat(element))
        }
        else -> CustomCompletion(name = "")
    }
}

/** 解析时修正：category 字符串→单元素数组；formatCategory 旧版「数值属性」归一化为「空值属性」 */
private fun fixCategoryAndFormat(element: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val mutable = element.toMutableMap()
    val category = mutable["category"]
    if (category is kotlinx.serialization.json.JsonPrimitive) {
        val categoryList = if (category.content.isBlank()) emptyList() else listOf(category)
        mutable["category"] = kotlinx.serialization.json.JsonArray(categoryList)
    }
    val fmt = mutable["formatCategory"]
    if (fmt is kotlinx.serialization.json.JsonPrimitive && fmt.content == LEGACY_FORMAT_NUMERIC) {
        mutable["formatCategory"] = kotlinx.serialization.json.JsonPrimitive(FORMAT_EMPTY_VALUE)
    }
    return kotlinx.serialization.json.JsonObject(mutable)
}

@Composable
fun CustomCompletionsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { TranslationEngine.getInstance() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!engine.isLoaded) engine.load(ctx)
        loaded = true
    }

    var nativeItems by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var userItems by remember { mutableStateOf(loadUserItems()) }
    var extraItems by remember { mutableStateOf(listOf<CustomCompletion>()) }
    var showAdd by remember { mutableStateOf(false) }
    var showResetNativeConfirm by remember { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var editIsNative by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var editDetail by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf(listOf<String>()) }
    var editFormat by remember { mutableStateOf("属性") }
    var editExample by remember { mutableStateOf("") }
    var editNameEn by remember { mutableStateOf("") }
    var editValueEn by remember { mutableStateOf("") }
    var editDetailEn by remember { mutableStateOf("") }
    var editDescEn by remember { mutableStateOf("") }
    var editExampleEn by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CompletionFilter.USER) }
    var isLoading by remember { mutableStateOf(true) }

    fun saveNative() {
        val items = nativeItems
        scope.launch(Dispatchers.IO) {
            val ok = saveNativeCompletions(items)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "原生补全表保存失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun saveUser() {
        val items = userItems
        scope.launch(Dispatchers.IO) {
            val ok = saveUserCompletions(items, engine)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "用户补全表保存失败（翻译引擎未加载）", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 生成原生表（带验证码校验，不匹配则重新生成）
    LaunchedEffect(loaded) {
        if (loaded) {
            isLoading = true
            val (patchedUser, native, extra) = withContext(Dispatchers.IO) {
                val patched = patchUserItemsNameEn(userItems, engine)
                if (patched != null) saveUserCompletions(patched, engine)
                val nativeItemsLoaded = loadNativeItemsVerified(engine)
                val extraItemsLoaded = loadExtraItemsVerified(ctx, engine)
                Triple(patched, nativeItemsLoaded, extraItemsLoaded)
            }
            patchedUser?.let { userItems = it }
            nativeItems = native
            extraItems = extra
            isLoading = false
        }
    }

    // 用户表 > 原生表 > 附件表
    val allItems = remember(nativeItems, userItems, extraItems) {
        val userNames = userItems.map { it.name }.toSet()
        val nativeNames = nativeItems.map { it.name }.toSet()
        val nativeOnly = nativeItems.filter { it.name !in userNames }
        val extraOnly = extraItems.filter { it.name !in userNames && it.name !in nativeNames }
        userItems + nativeOnly + extraOnly
    }

    Column(Modifier.fillMaxSize().background(RustedBackground)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("搜索...", fontSize=12.sp) }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize=12.sp))
            Spacer(Modifier.width(4.dp))
            Button(onClick = {
                editName = ""; editValue = ""; editDetail = ""; editDesc = ""; editExample = ""
                editCategory = listOf(); editFormat = "属性"
                editNameEn = ""; editValueEn = ""; editDetailEn = ""; editDescEn = ""; editExampleEn = ""
                showAdd = true
            }, colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)) { Text("添加", fontSize=12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = filter == CompletionFilter.USER, onClick = { filter = CompletionFilter.USER }, label = { Text("我的(${userItems.size})", fontSize=11.sp) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = filter == CompletionFilter.ALL, onClick = { filter = CompletionFilter.ALL }, label = { Text("全部(${allItems.size})", fontSize=11.sp) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = filter == CompletionFilter.NATIVE, onClick = { filter = CompletionFilter.NATIVE }, label = { Text("原生", fontSize=11.sp) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = filter == CompletionFilter.EXTRA, onClick = { filter = CompletionFilter.EXTRA }, label = { Text("附件(${extraItems.size})", fontSize=11.sp) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showResetNativeConfirm = true }, contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)) { Text("重置原生", fontSize=11.sp, color=RustedError) }

            if (showResetNativeConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetNativeConfirm = false },
                    title = { Text("确认重置原生补全表") },
                    text = { Text("将重新生成原生补全表并覆盖现有修正，是否继续？") },
                    confirmButton = {
                        TextButton(onClick = {
                            showResetNativeConfirm = false
                            nativeItems = generateNativeItems(engine, preserveExisting = false)
                            saveNative()
                            SettingsManager.writeVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS, SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE)
                        }) { Text("确认", color = RustedError) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetNativeConfirm = false }) { Text("取消") }
                    }
                )
            }
        }

        val displayList = when (filter) {
            CompletionFilter.NATIVE -> nativeItems
            CompletionFilter.ALL -> allItems
            CompletionFilter.EXTRA -> extraItems
            CompletionFilter.USER -> userItems
        }
        val displayFiltered = displayList.filter { searchQuery.isEmpty() || it.name.contains(searchQuery, true) }
        when {
            isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RustedPrimary) }
            displayFiltered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("无结果", color = RustedOnBackground.copy(alpha=0.4f)) }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { items(displayFiltered) { item ->
                Card(Modifier.fillMaxWidth().padding(vertical=2.dp), colors = CardDefaults.cardColors(containerColor = if (item.isOverridden) RustedSecondary.copy(alpha=0.08f) else RustedSurface), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(item.name, fontSize=14.sp, fontFamily=AppCodeFontFamily, fontWeight=FontWeight.Medium, color=if (item.isOverridden) RustedSecondary else RustedPrimary, modifier=Modifier.weight(1f)); if (item.detail.isNotEmpty()) Text(item.detail, fontSize=10.sp, color=RustedOnBackground.copy(alpha=0.4f)) }
                        if (item.value.isNotEmpty()) Text("值: ${item.value}", fontSize=12.sp, fontFamily=AppCodeFontFamily, color=RustedOnBackground.copy(alpha=0.6f))
                        if (item.desc.isNotEmpty()) Text(item.desc.take(40), fontSize=11.sp, color=RustedOnBackground.copy(alpha=0.5f), maxLines=1)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = {
                                editIndex = userItems.indexOfFirst { it.name == item.name }
                                editIsNative = !item.isOverridden
                                editName = item.name; editValue = item.value; editDetail = item.detail; editDesc = item.desc; editExample = item.example
                                editCategory = item.category; editFormat = item.formatCategory
                                editNameEn = item.nameEn; editValueEn = item.valueEn; editDetailEn = item.detailEn; editDescEn = item.descEn; editExampleEn = item.exampleEn
                                showAdd = true
                            }, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint=RustedSecondary) }
                            if (item.isOverridden) IconButton(onClick = { userItems = userItems.filter { it.name != item.name }; saveUser() }, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(14.dp), tint=RustedError) }
                        }
                    }
                }
            } }
        }
    }

    if (showAdd) {
        val allCategories = remember(loaded) {
            if (loaded) {
                val cr = engine.getCodeReference()
                cr.getRealSectionNames()
                    .map { mapSectionCategory(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted() + listOf("values", "特定值")
            } else listOf("values", "特定值")
        }
        val editingItem = if (editIndex >= 0 && !editIsNative) {
            userItems.getOrNull(editIndex)
        } else {
            CustomCompletion(
                name = editName, value = editValue, detail = editDetail, desc = editDesc,
                example = editExample, category = editCategory, formatCategory = editFormat,
                isOverridden = true,
                nameEn = editNameEn, valueEn = editValueEn, detailEn = editDetailEn,
                descEn = editDescEn, exampleEn = editExampleEn
            )
        }
        CustomCompletionEditorDialog(
            item = editingItem,
            categories = allCategories,
            onDismiss = {
                showAdd = false
                editIndex = -1; editIsNative = false
                editName = ""; editValue = ""; editDetail = ""; editDesc = ""; editExample = ""; editCategory = listOf(); editFormat = "属性"
                editNameEn = ""; editValueEn = ""; editDetailEn = ""; editDescEn = ""; editExampleEn = ""
            },
            onSave = { item ->
                if (editIndex >= 0 && !editIsNative) {
                    userItems = userItems.toMutableList().also { it[editIndex] = item }
                } else {
                    // 新增时若用户表已存在同名项，则替换旧项，避免同名补全项重复
                    val dupIndex = userItems.indexOfFirst { it.name == item.name }
                    userItems = if (dupIndex >= 0) {
                        userItems.toMutableList().also { it[dupIndex] = item }
                    } else {
                        userItems + item
                    }
                }
                saveUser()
                showAdd = false
                editIndex = -1; editIsNative = false
                editName = ""; editValue = ""; editDetail = ""; editDesc = ""; editExample = ""; editCategory = listOf(); editFormat = "属性"
                editNameEn = ""; editValueEn = ""; editDetailEn = ""; editDescEn = ""; editExampleEn = ""
            }
        )
    }
}

fun generateNativeItems(engine: TranslationEngine, preserveExisting: Boolean = true): List<CustomCompletion> {
    val cr = engine.getCodeReference()
    val dict = engine.getTranslationDict()

    // 读取已有的原生表，用于保留用户修正过的 category
    val existingItems = if (preserveExisting) {
        try {
            val file = java.io.File(SettingsManager.nativeCompletionsPath)
            if (file.exists()) {
                parseCustomCompletions(file.readText()).associateBy { it.name }
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    } else emptyMap()

    // 收集每个属性名对应的所有分类以及首次遇到时的格式分类
    val propCategories = mutableMapOf<String, MutableSet<String>>()
    val propFormatCategory = mutableMapOf<String, String>()
    val propInfo = mutableMapOf<String, com.rwmodstudio.core.translation.CodeReferenceRepository.PropertyInfo>()

    // sections里的属性 → 补全分类=中文节名, 格式分类=空值属性（只插 名称:）
    // values里有type的（存储在sectionProperties中）→ 补全分类=values, 格式分类=values
    val spawnParamNames = mutableSetOf<String>()
    for (s in cr.getAllSectionNames()) {
        val isReal = cr.isRealSection(s)
        val category = if (isReal) mapSectionCategory(s) else "values"
        val isSpawnSection = !isReal && (s == "产生单位" || s.lowercase().contains("spawn"))
        for (p in cr.getPropertiesForSection(s)) {
            val fmt = if (isReal) FORMAT_EMPTY_VALUE else "values"
            if (isSpawnSection) spawnParamNames.add(p.name)
            propCategories.getOrPut(p.name) { mutableSetOf() }.add(category)
            if (p.name !in propInfo) {
                propInfo[p.name] = p
                propFormatCategory[p.name] = fmt
            }
        }
    }

    // values里没type的（存储在valueCategories中）→ 补全分类=特定值, 格式分类=特定值
    for (catName in cr.getAllValueCategoryNames()) {
        val cat = cr.getValueCategory(catName) ?: continue
        for (p in cat.data) {
            propCategories.getOrPut(p.name) { mutableSetOf() }.add("特定值")
            if (p.name !in propInfo) {
                propInfo[p.name] = p
                propFormatCategory[p.name] = "特定值"
            }
        }
    }

    val items = mutableListOf<CustomCompletion>()
    for ((name, p) in propInfo) {
        val categories = propCategories[name] ?: mutableSetOf()
        val existing = existingItems[name]
        val nameEn = dict.getTranslationBack(p.name).takeIf { it != p.name } ?: p.name_en ?: p.name
        val exampleEn = translateAllToEnglish(p.example, dict)
        val fmt = propFormatCategory[name] ?: "属性"
        val value = when (fmt) {
            FORMAT_EMPTY_VALUE -> ""
            "values" -> p.default.takeIf { it.isNotBlank() }
                ?: valuesDefaultValue(p.name, p.name in spawnParamNames)
            else -> p.example.substringAfter(":").trim()
        }
        val valueEn = translateAllToEnglish(value, dict)
        val descEn = translateAllToEnglish(p.desc_zh, dict)
        items.add(CustomCompletion(
            name = p.name,
            value = value,
            detail = p.type,
            desc = p.desc_zh,
            example = p.example,
            category = existing?.category?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: categories.toList(),
            formatCategory = propFormatCategory[name] ?: "属性",
            isOverridden = false,
            nameEn = nameEn,
            valueEn = valueEn,
            detailEn = p.type,
            descEn = descEn,
            exampleEn = exampleEn
        ))
    }

    return items
}

fun loadNativeItems(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.nativeCompletionsPath)
        if (jsonFile.exists()) {
            val content = jsonFile.readText()
            if (content.isNotBlank() && content != "[]") {
                parseCustomCompletions(content)
            } else emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

fun loadUserItems(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.userCompletionsPath)
        if (jsonFile.exists()) {
            val content = jsonFile.readText()
            if (content.isNotBlank() && content != "[]") {
                parseCustomCompletions(content)
            } else emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

/**
 * 对用户表中 nameEn 为空或与 name 相同的项，尝试从翻译库回译英文。
 * 返回补全后的新列表；若无需补全则返回 null。
 */
fun patchUserItemsNameEn(items: List<CustomCompletion>, engine: TranslationEngine): List<CustomCompletion>? {
    if (!engine.isLoaded) return null
    val dict = engine.getTranslationDict()
    var changed = false
    val patched = items.map { item ->
        if (item.nameEn.isBlank() || item.nameEn == item.name) {
            val en = dict.getTranslationBack(item.name)
            if (en.isNotBlank() && en != item.name) {
                changed = true
                item.copy(nameEn = en)
            } else item
        } else item
    }
    return if (changed) patched else null
}

fun toEnglishCompletion(item: CustomCompletion, dict: TranslationDict? = null): CustomCompletion {
    fun translate(text: String): String {
        if (dict == null || text.isBlank()) return text
        val en = translateAllToEnglish(text, dict)
        return if (en.isNotBlank() && en != text) en else text
    }
    return item.copy(
        name = item.nameEn.takeIf { it.isNotBlank() } ?: translate(item.name),
        value = item.valueEn.takeIf { it.isNotBlank() } ?: translate(item.value),
        detail = item.detailEn.takeIf { it.isNotBlank() } ?: translate(item.detail),
        desc = item.descEn.takeIf { it.isNotBlank() } ?: translate(item.desc),
        example = item.exampleEn.takeIf { it.isNotBlank() } ?: translate(item.example)
    )
}

suspend fun saveUserCompletions(items: List<CustomCompletion>, engine: TranslationEngine): Boolean = completionsSaveMutex.withLock {
    if (!engine.isLoaded) {
        Log.w(TAG, "saveUserCompletions skipped: translation engine not loaded")
        return@withLock false
    }
    val dict = engine.getTranslationDict()
    try {
        java.io.File(SettingsManager.userCompletionsPath).writeText(jsonFormat.encodeToString(items))
        java.io.File(SettingsManager.userCompletionsEnPath).writeText(
            jsonFormat.encodeToString(items.map { toEnglishCompletion(it, dict) })
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveUserCompletions failed", e)
        false
    }
}

suspend fun saveNativeCompletions(items: List<CustomCompletion>): Boolean = completionsSaveMutex.withLock {
    try {
        java.io.File(SettingsManager.nativeCompletionsPath).writeText(jsonFormat.encodeToString(items))
        java.io.File(SettingsManager.nativeCompletionsEnPath).writeText(
            jsonFormat.encodeToString(items.map { toEnglishCompletion(it) })
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveNativeCompletions failed", e)
        false
    }
}

fun loadNativeItemsEn(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.nativeCompletionsEnPath)
        if (jsonFile.exists()) {
            val content = jsonFile.readText()
            if (content.isNotBlank() && content != "[]") parseCustomCompletions(content) else emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

fun loadUserItemsEn(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.userCompletionsEnPath)
        if (jsonFile.exists()) {
            val content = jsonFile.readText()
            if (content.isNotBlank() && content != "[]") parseCustomCompletions(content) else emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

fun loadExtraItems(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.extraCompletionsPath)
        if (jsonFile.exists() && jsonFile.length() > 0) {
            val content = jsonFile.readText()
            if (content.isNotBlank() && content != "[]") parseCustomCompletions(content) else emptyList()
        } else emptyList()
    } catch (_: Exception) { emptyList() }
}

/**
 * 从 assets 加载内置附件补全表。
 * 版本升级（验证码不匹配）或强制刷新时的唯一数据源，
 * 避免旧格式/旧内容的外存文件遮蔽随包发布的新表。
 */
fun loadExtraItemsFromAssets(context: Context): List<CustomCompletion> {
    return try {
        val content = context.assets.open("data/extra_completions.json").use { it.bufferedReader().readText() }
        if (content.isNotBlank() && content != "[]") parseCustomCompletions(content) else emptyList()
    } catch (_: Exception) { emptyList() }
}

/**
 * 加载附件补全表（带验证码校验，与原生表逻辑一致）。
 * 验证码匹配且外存非空时直接使用外存副本；否则从 assets 重新加载并覆盖外存中英文文件。
 * force 为 true 时无视验证码，强制从 assets 重建（开发者工具「生成英文附件表」使用）。
 */
suspend fun loadExtraItemsVerified(context: Context, engine: TranslationEngine, force: Boolean = false): List<CustomCompletion> {
    val stored = loadExtraItems()
    val code = SettingsManager.readVerifyCode(SettingsManager.VERIFY_EXTRA_COMPLETIONS)
    if (!force && code == SettingsManager.EXTRA_COMPLETIONS_VERIFY_CODE && stored.isNotEmpty()) {
        return stored
    }
    // 重新生成必须来自 assets 内置表；外存副本仅作为 assets 不可用时的兜底
    val items = loadExtraItemsFromAssets(context).ifEmpty { stored }
    if (items.isEmpty()) return items
    saveExtraCompletions(items)
    SettingsManager.writeVerifyCode(SettingsManager.VERIFY_EXTRA_COMPLETIONS, SettingsManager.EXTRA_COMPLETIONS_VERIFY_CODE)
    // 附件表是翻译字典的数据源之一：引擎已加载时立即重载字典，避免整个会话沿用旧表
    if (engine.isLoaded) {
        engine.getTranslationDict().loadFromAssets(context)
    }
    return items
}

fun loadExtraItemsEn(): List<CustomCompletion> {
    return try {
        val jsonFile = java.io.File(SettingsManager.extraCompletionsEnPath)
        val content = if (jsonFile.exists() && jsonFile.length() > 0) {
            jsonFile.readText()
        } else ""
        if (content.isNotBlank() && content != "[]") parseCustomCompletions(content) else emptyList()
    } catch (_: Exception) { emptyList() }
}

suspend fun saveExtraCompletions(items: List<CustomCompletion>): Boolean = completionsSaveMutex.withLock {
    try {
        java.io.File(SettingsManager.extraCompletionsPath).writeText(jsonFormat.encodeToString(items))
        java.io.File(SettingsManager.extraCompletionsEnPath).writeText(
            jsonFormat.encodeToString(items.map { toEnglishCompletion(it) })
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveExtraCompletions failed", e)
        false
    }
}

/**
 * 合并 property / value / section 三类翻译，构建英→中查找表。
 * 属性/值翻译优先；节翻译（如 [attachment]→[附属]）只在对应裸 key 无翻译时兜底，
 * 避免节翻译覆盖值翻译（修复 附件 被错插为 附属( 的问题）。
 * 对齐 Python 参考库生成工具的 _translate_example：按 key 长度降序，优先替换长词。
 */
internal fun buildEnToZhLookup(dict: TranslationDict): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for ((k, v) in dict.getAllEntries()) {
        val isSection = k.startsWith("[")
        val key = k.removeSurrounding("[", "]")
        val value = v.removeSurrounding("[", "]")
        if (key.isNotBlank() && value.isNotBlank() && key != value && key.length > 1) {
            if (isSection) map.putIfAbsent(key, value) else map[key] = value
        }
    }
    for ((k, v) in dict.getValueTranslations()) {
        if (k.isNotBlank() && v.isNotBlank() && k != v && k.length > 1 && !dict.isChinese(k)) {
            map[k] = v
        }
    }
    return map.entries
        .sortedByDescending { it.key.length }
        .associate { it.key to it.value }
}

/**
 * 合并 property / value / section 三类翻译，构建中→英查找表。
 */
private fun buildZhToEnLookup(dict: TranslationDict): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for ((k, v) in dict.getAllEntries()) {
        val key = k.removeSurrounding("[", "]")
        val value = v.removeSurrounding("[", "]")
        if (key.isNotBlank() && value.isNotBlank() && key != value && value.length > 1) {
            map[value] = key
        }
    }
    for ((k, v) in dict.getValueTranslations()) {
        if (k.isNotBlank() && v.isNotBlank() && k != v && v.length > 1 && dict.isChinese(v)) {
            map[v] = k
        }
    }
    return map.entries
        .sortedByDescending { it.key.length }
        .associate { it.key to it.value }
}

/**
 * 把文本中所有已知英文 key 替换成中文。
 * 含 `.` 或 `()` 的 key 精确匹配，其余加单词边界。
 */
internal fun translateAllToChinese(text: String, dict: TranslationDict): String {
    val lookup = buildEnToZhLookup(dict)
    if (lookup.isEmpty() || text.isBlank()) return text
    val escaped = lookup.keys.joinToString("|") { Regex.escape(it) }
    val pattern = Regex("(?<!\\w)(?:$escaped)(?!\\w)|(?:$escaped)")
    return pattern.replace(text) { match ->
        lookup[match.value] ?: match.value
    }
}

/**
 * 把文本中所有已知中文 key 替换成英文。
 * 中文没有天然分词，使用精确子串匹配（按长度降序已保证长词优先）。
 */
internal fun translateAllToEnglish(text: String, dict: TranslationDict): String {
    val lookup = buildZhToEnLookup(dict)
    if (lookup.isEmpty() || text.isBlank()) return text
    var result = text
    for ((zh, en) in lookup) {
        result = result.replace(zh, en)
    }
    return result
}

/**
 * 剥离补全项名称尾部的一层参数括号组（如 debugPassthrough(LogicBoolean) → debugPassthrough）。
 * 与编辑翻译不同，代码表/补全表名称带参数签名时，getTranslation 无法直接命中，
 * 先在此剥括号取基名，保证函数名类条目的中文显示。
 */
private val TRAILING_PARENS_REGEX = Regex("""\([^()]*\)$""")

/**
 * 将英文补全表整体翻译成中文（用于翻译库更新后刷新）。
 */
fun translateCompletionsToChinese(items: List<CustomCompletion>, engine: TranslationEngine): List<CustomCompletion> {
    val dict = engine.getTranslationDict()
    return items.map { item ->
        item.copy(
            name = dict.getTranslation(item.nameEn.replace(TRAILING_PARENS_REGEX, "")),
            value = translateAllToChinese(item.valueEn, dict),
            detail = dict.getTranslation(item.detailEn),
            desc = translateAllToChinese(item.descEn, dict),
            example = translateAllToChinese(item.exampleEn, dict)
        )
    }
}

/**
 * 从英文版补全表反查翻译库生成中文版。
 * 与 TranslationEditorScreen 中“保存翻译库”按钮的刷新逻辑保持一致。
 * 三张表独立刷新：仅当对应英文表非空时才回写，避免空表覆盖正常表
 * （例如升级后 extra_completions_en.json 缺失时，绝不能把附件表清空）。
 */
suspend fun refreshCompletionsFromEnglish(engine: TranslationEngine) {
    if (!engine.isLoaded) return
    val nativeEn = loadNativeItemsEn()
    if (nativeEn.isNotEmpty()) {
        saveNativeCompletions(translateCompletionsToChinese(nativeEn, engine))
    }
    val userEn = loadUserItemsEn()
    if (userEn.isNotEmpty()) {
        saveUserCompletions(translateCompletionsToChinese(userEn, engine), engine)
    }
    val extraEn = loadExtraItemsEn()
    if (extraEn.isNotEmpty()) {
        saveExtraCompletions(translateCompletionsToChinese(extraEn, engine))
    }
}

/**
 * 加载原生补全表，验证码不匹配或文件为空时重新生成。
 */
/**
 * 仅翻译补全项的显示名称和类型说明，保留 value/example 原样，避免路径/示例被误改。
 */
fun translateCompletionLabelsToChinese(items: List<CustomCompletion>, engine: TranslationEngine): List<CustomCompletion> {
    val dict = engine.getTranslationDict()
    return items.map { item ->
        item.copy(
            name = dict.getTranslation(item.name.replace(TRAILING_PARENS_REGEX, "")),
            detail = dict.getTranslation(item.detail),
            desc = dict.translateInText(item.desc, isEnToZh = true)
        )
    }
}

suspend fun loadNativeItemsVerified(engine: TranslationEngine): List<CustomCompletion> {
    val stored = loadNativeItems()
    val code = SettingsManager.readVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS)
    return if (code == SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE && stored.isNotEmpty()) {
        // 验证码匹配且数据已存在，直接返回存储的中文表，避免每次进入页面都全表翻译
        stored
    } else {
        val generated = generateNativeItems(engine, preserveExisting = false)
        // 首次启动或验证码不匹配时，根据当前翻译库（含附件表补充）把英文 name 翻译成中文
        val translated = translateCompletionsToChinese(generated, engine)
        saveNativeCompletions(translated)
        SettingsManager.writeVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS, SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE)
        translated
    }
}

fun parseItems(json: String): List<CustomCompletion> {
    return try {
        parseCustomCompletions(json)
    } catch (_: Exception) {
        emptyList()
    }
}

/** 按「格式分类」计算补全要插入的文本（与生产补全一致的插入形式，供复制代码等复用） */
fun completionFormatInsert(item: CustomCompletion): String {
    // 自动补全 label 优先使用中文名；只有没有中文名时才使用英文名
    val hasChineseName = item.name.isNotBlank() && item.name.any { it.code in 0x4E00..0x9FFF }
    val label = if (hasChineseName) item.name else item.nameEn.takeIf { it.isNotBlank() } ?: item.name
    return when (item.formatCategory) {
        FORMAT_EMPTY_VALUE, LEGACY_FORMAT_NUMERIC -> "$label:"
        "values" -> item.value.ifEmpty { label }
        "特定值" -> label
        else -> if (item.value.isNotEmpty()) "$label:${item.value}" else "$label:"
    }
}

fun customCompletionsToProviderItems(items: List<CustomCompletion>): List<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem> {
    return items.map { item ->
        // 自动补全 label 优先使用中文名；只有没有中文名时才使用英文名
        val hasChineseName = item.name.isNotBlank() && item.name.any { it.code in 0x4E00..0x9FFF }
        val label = if (hasChineseName) item.name else item.nameEn.takeIf { it.isNotBlank() } ?: item.name
        val insertText = completionFormatInsert(item)
        val detail = if (item.nameEn.isNotBlank() && item.nameEn != item.name && item.name.isNotBlank()) {
            "${item.name} | ${item.desc.ifEmpty { item.detail }}"
        } else {
            item.desc.ifEmpty { item.detail }
        }
        com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem(
            label = label,
            type = com.rwmodstudio.feature.completion.CompletionProvider.CompletionType.KEY,
            detail = detail,
            insertText = insertText,
            category = item.category,
            name = item.name,
            nameEn = item.nameEn
        )
    }
}


// ===== 三表属性查询助手：值补全与行尾灯泡共用 =====

// 用户表 > 附件表 > 原生表，按 name 去重合并
fun mergeCompletionTables(user: List<CustomCompletion>, native: List<CustomCompletion>, extra: List<CustomCompletion>): List<CustomCompletion> {
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<CustomCompletion>()
    fun add(items: List<CustomCompletion>) = items.forEach { if (seen.add(it.name)) merged.add(it) }
    add(user); add(extra); add(native)
    return merged
}

// detail 即类型，nameEn 即英文名
fun CustomCompletion.toPropertyInfo() = CodeReferenceRepository.PropertyInfo(
    name = name, type = detail, desc_zh = desc,
    example = example, name_en = nameEn.takeIf { it.isNotBlank() }
)

// 值补全用：按 category 分组的属性索引
fun buildValueSectionProperties(merged: List<CustomCompletion>): Map<String, List<CodeReferenceRepository.PropertyInfo>> {
    val map = mutableMapOf<String, MutableList<CodeReferenceRepository.PropertyInfo>>()
    merged.forEach { item ->
        val info = item.toPropertyInfo()
        item.category.filter { it.isNotBlank() }.forEach { cat ->
            map.getOrPut(cat) { mutableListOf() }.add(info)
        }
    }
    return map
}

// 行尾灯泡用：按 name / nameEn 精确查找
fun buildTableTypeLookup(merged: List<CustomCompletion>): (String) -> List<CodeReferenceRepository.PropertyInfo> {
    val map = mutableMapOf<String, MutableList<CodeReferenceRepository.PropertyInfo>>()
    merged.forEach { item ->
        val info = item.toPropertyInfo()
        map.getOrPut(item.name) { mutableListOf() }.add(info)
        if (item.nameEn.isNotBlank()) map.getOrPut(item.nameEn) { mutableListOf() }.add(info)
    }
    return { form -> map[form].orEmpty() }
}

/**
 * values 类条目的默认值兜底规则（来源表无 default 时使用，仅处理生成参数的 "="）。
 * 不再为任何条目补 "("：真正需要带括号的带参函数（命名参数/位置参数函数）在来源表
 * code_reference.json 中已自带 default（如 "自身弹药包括队列("）；无 default 者即为
 * 纯值枚举（如 setunitstats.energy），一律用裸名，绝不误加括号。
 */
internal fun valuesDefaultValue(
    name: String,
    isSpawnParam: Boolean
): String {
    return if (isSpawnParam) "$name=" else name
}