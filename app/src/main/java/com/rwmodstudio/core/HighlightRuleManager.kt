package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

private const val TAG = "HighlightRuleManager"

/**
 * 用户自定义代码高亮规则的存储与管理（开发者模式 →「规则」）。
 *
 * 数据形态是「类别：规则」——每个 [HighlightRuleCategory] 对应编辑器里可单独调色的一类
 * token（见 [HighlightRuleManager.availableCategories]），其 [HighlightRuleCategory.rules]
 * 就是这一类的规则表。
 *
 * 生效方式：把用户规则转成 TextMate 规则，插到内置 grammar（`assets/textmate/ini/ini.tmLanguage.json`）
 * 的 patterns **最前面**（优先级高于内置规则），再注册进一个独立的 [GrammarRegistry] 子实例供
 * 编辑器使用。内置语法一行不改，用户规则为空时完全不介入（走原单例注册表，行为与历史一致）。
 */
object HighlightRuleManager {

    /** 规则匹配方式 */
    enum class RuleType {
        /** 关键字：整词匹配（两侧自动补词边界），内部正则元字符自动转义 */
        KEYWORD,

        /** 正则：原样使用用户写的正则表达式 */
        REGEX;

        companion object {
            fun fromName(name: String?): RuleType =
                if (name.equals("regex", ignoreCase = true)) REGEX else KEYWORD
        }
    }

    /**
     * 一条高亮规则。
     * @param pattern 关键字或正则源码
     * @param type 匹配方式
     * @param enabled 关闭后不参与 grammar 生成
     * @param source 该规则复刻自内置语法的哪条规则原文。合并语法按它**原位替换**内置规则，
     *               顺序与优先级完全不变（默认规则因此与内置逐 token 一致）；
     *               留空表示用户手写的自定义规则（插在最前，优先级最高）
     * @param displayGroup 同一类别下再分组的标签（如「自身检测」「帧率」），仅用于 UI 展示与逐组重置
     * @param sourceInstance 内置语法里同一条规则常定义两份（键值区 + 顶层），这是第几份（0 起）
     */
    data class HighlightRule(
        val pattern: String,
        val type: RuleType = RuleType.KEYWORD,
        val enabled: Boolean = true,
        val source: String = "",
        val displayGroup: String = "",
        val sourceInstance: Int = 0
    )

    /**
     * 一个类别下的规则表。
     * @param categoryId [availableCategories] 中的类别 id
     * @param rules 该类别下的规则
     * @param listName 分组标签（如「自身检测」「布尔值」），仅用于 UI 展示与逐组重置；
     *                 同一个 categoryId 可以有多组（例如「自身检测」「自身属性」都属于值高亮）
     */
    data class HighlightRuleCategory(
        val categoryId: String,
        val rules: List<HighlightRule> = emptyList(),
        val listName: String = ""
    )

    /** UI 用的类别定义：id + 显示名 + 该类别写入 grammar 的 scope。 */
    data class CategoryInfo(
        val id: String,
        val label: String,
        val scope: String
    )

    /** 规则文件是否启用（开关，关闭时完全不介入内置高亮，走纯内置语法） */
    var enabled: Boolean
        get() = SettingsManager.highlightRulesEnabled
        set(value) {
            if (SettingsManager.highlightRulesEnabled == value) return
            SettingsManager.highlightRulesEnabled = value
            revision++
            invalidateRegistry()
        }

    /**
     * 规则修订号：每次规则/开关变化自增。Compose 侧把它作为 key 观察，
     * 变化时重建编辑器语言（无需重启 App）。
     */
    var revision: Int by mutableStateOf(0)
        private set

    /**
     * 可自定义的类别白名单 —— **与「自定义高亮」的类型完全同一套**。
     *
     * 「自定义高亮」页的类型列表是 `["ui","plainText"] + DarkThemeColors.targetScopes.keys`
     * （见 `DarkTokenColorDialog`），这里用**同一个数据源**生成，所以两个页面的类型不再各说各话：
     * 之前这里手写了一套 43 个类别，拆出了「音效常量/效果常量/生成常量/块注释/多行字符串」这些
     * 自定义高亮里根本没有的名字，而那些 scope 在内置语法里出现 0 次，颜色永远不会生效。
     *
     * 顺序即 UI 展示顺序，也决定同一 token 多类别命中时的优先级（与主题首命中一致）。
     */
    val availableCategories: List<CategoryInfo> = buildList {
        // 不参与 scope 着色的两个类型：UI 高亮（编辑器符号匹配）、普通文本（全局前景色）。
        // 它们是「自定义高亮」里能调的键，所以这里也列出（规则页显示为不可写规则）。
        add(CategoryInfo("ui", "UI 高亮", ""))
        add(CategoryInfo("plainText", "普通文本", ""))
        DarkThemeColors.targetScopes.forEach { (id, scopes) ->
            add(CategoryInfo(id, DarkThemeColors.labels[id] ?: id, scopes.firstOrNull().orEmpty()))
        }
    }

    /** 类别 id → 定义（UI 与语法生成共用） */
    private val categoryById: Map<String, CategoryInfo> = availableCategories.associateBy { it.id }

    /**
     * 类别 id → 该类别覆盖的**全部** scope，直接取主题配色表（单一数据源，两者不可能再漂移）。
     * `ui` / `plainText` 不属于 scope 着色，记空集。
     */
    val categoryScopes: Map<String, Set<String>> =
        DarkThemeColors.targetScopes.toMap() + mapOf("ui" to emptySet(), "plainText" to emptySet())

    /**
     * 类别 id → 主题里**实际为该 scope 上色**的配色键（按 `targetScopes` 顺序首命中，
     * 与 `SoraEditorInitializer.findCategoryForScopes` 同一套规则）。
     *
     * 规则只有写在「首命中的那个类别」下才会真正改变颜色，单测用它校验类别归属。
     */
    internal fun themeCategoryOfScope(scope: String): String? =
        DarkThemeColors.targetScopes.entries.firstOrNull { (_, targets) ->
            targets.any { target ->
                scope == target || scope.startsWith("$target.") || target.startsWith("$scope.")
            }
        }?.key

    /**
     * v2 规则文件的旧类别 id → 新 id。
     *
     * 旧 id 是手写的一套独立类别，现在统一成主题配色键，需要在 `parseRules` 的白名单判断**之前**改写，
     * 否则旧文件里的规则（尤其是用户手写的）会被当作未知类别直接丢弃。
     * 映射到 `null` = 该类别的 scope 在内置语法里出现 0 次（颜色从不生效），随类别一起移除。
     */
    private val CATEGORY_ID_MIGRATION: Map<String, String?> = mapOf(
        "section" to "sectionBracket",
        "boolean" to "team",
        "constant" to "team",
        "type" to "type",
        "modifier" to "modifier",
        "variable" to null,
        "declaration" to null,
        "propertyValue" to null,
        "commentBlock" to null,
        "multilineString" to null,
        "forwardSlash" to null,
        "layer" to null,
        "event" to null,
        "sound" to null,
        "effect" to null,
        "spawn" to null,
        "control" to null,
        "other" to null
    )

    fun categoryLabel(categoryId: String): String =
        categoryById[categoryId]?.label ?: categoryId

    @Volatile
    private var loaded = false

    @Volatile
    private var categories: List<HighlightRuleCategory> = emptyList()

    @Volatile
    private var cachedRegistry: GrammarRegistry? = null

    @Volatile
    private var cachedRegistryRevision: Int = -1

    // ==================== 加载 / 保存 ====================

    /**
     * 启动时加载一次（幂等）。
     *
     * 三种情况：
     * 1. 文件存在且可解析 → 用文件里的规则（不动文件）；
     * 2. 文件不存在 → **写入一份内置语法复刻作为默认规则**（默认即内置，外观零差异）；
     * 3. 文件损坏 → 用内置复刻兜底，但**不覆盖**用户文件（避免误删，只记日志）。
     */
    @Synchronized
    fun load(context: Context? = null) {
        if (loaded) {
            Log.d(TAG, "load skipped (already loaded): categories=${categories.size}, enabled=$enabled")
            return
        }
        loaded = true
        val file = RwmodPaths.highlightRulesFile
        if (!file.exists()) {
            val defaults = defaultsFrom(context)
            categories = defaults
            if (defaults.isEmpty()) {
                Log.e(TAG, "load: 规则文件不存在且内置复刻为空，回退为「无自定义规则」")
            } else {
                persist()
                Log.d(
                    TAG,
                    "load: seeded builtin defaults -> categories=${categories.size}, " +
                        "rules=${categories.sumOf { it.rules.size }}, file=${file.absolutePath}"
                )
            }
            return
        }
        try {
            val raw = file.readText(StandardCharsets.UTF_8)
            categories = parseRules(raw)
            val version = try {
                JSONObject(raw).optInt("version", 1)
            } catch (_: Exception) {
                1
            }
            Log.d(
                TAG,
                "load: categories=${categories.size}, rules=${categories.sumOf { it.rules.size }}, " +
                    "enabled=$enabled, fileVersion=$version, file=${file.absolutePath}"
            )
            if (version < FORMAT_VERSION) {
                // 旧格式：groups 只存名字、没有每组条数 → 切分必然错，按新格式重写
                persist()
                Log.d(TAG, "load: 规则文件格式从 v$version 升级到 v$FORMAT_VERSION")
            }
            // 默认规则过期（复刻机制改过）时，用最新内置复刻替换"复刻出来的规则"，
            // 用户手写的规则（source 为空）原样保留
            if (SettingsManager.highlightRulesDefaultsVersion != DEFAULTS_VERSION) {
                val merged = reseedKeepingCustom()
                if (merged) {
                    SettingsManager.highlightRulesDefaultsVersion = DEFAULTS_VERSION
                    Log.d(TAG, "load: 默认规则已按 v$DEFAULTS_VERSION 重新复刻（保留手写规则）")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${file.absolutePath}; 用内置复刻兜底（不覆盖文件）", e)
            categories = defaultsFrom(context)
        }
    }

    /**
     * 用最新内置复刻替换"复刻出来的默认规则"，保留用户手写规则（source 为空）。
     * @return 是否真的做了替换
     */
    private fun reseedKeepingCustom(): Boolean {
        val defaults = defaultsFrom(null)
        if (defaults.isEmpty()) {
            Log.e(TAG, "reseedKeepingCustom: 内置复刻为空，跳过")
            return false
        }
        val custom = categories.filter { list -> list.rules.any { it.source.isBlank() } }
        if (custom.isEmpty()) {
            categories = defaults
        } else {
            categories = orderByAvailable(defaults + custom)
        }
        persist()
        refresh()
        return true
    }

    /** 内置语法复刻（首次运行与「重置为内置规则」共用） */
    private fun defaultsFrom(context: Context?): List<HighlightRuleCategory> {
        val ctx = context ?: appContext
        if (ctx == null) {
            Log.e(TAG, "defaultsFrom: no Context，无法抽取内置语法")
            return emptyList()
        }
        return HighlightRulesDefaults.defaults(ctx)
    }

    /** 首次播种用：记录能力由 MainApp 注入的 Context */
    @Volatile
    private var appContext: Context? = null

    /** 由 MainApp 在首帧前调用，注入 Context（供 load 无参调用时抽取内置语法） */
    fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 重置为内置语法复刻（UI「重置为内置规则」） */
    fun resetToBuiltin() {
        val defaults = defaultsFrom(null)
        if (defaults.isEmpty()) {
            Log.e(TAG, "resetToBuiltin: 内置复刻为空，取消重置")
            return
        }
        categories = defaults
        persist()
        refresh()
        Log.d(TAG, "resetToBuiltin: categories=${categories.size}, rules=${categories.sumOf { it.rules.size }}")
    }

    /** 批量覆写全部类别（重置/导入用），只落盘一次、只 bump 一次 revision */
    fun setAllCategories(list: List<HighlightRuleCategory>) {
        categories = orderByAvailable(list.filter { c -> c.rules.any { it.pattern.isNotBlank() } })
        persist()
        refresh()
    }

    /** 全部类别（含空规则表，便于 UI 展示「类别：规则」结构） */
    fun allCategories(): List<HighlightRuleCategory> = categories

    /** 有生效规则的列表（且开关打开），供语法生成 */
    fun activeCategories(): List<HighlightRuleCategory> =
        if (!enabled) emptyList()
        else categories.filter { c -> c.rules.any { it.enabled && it.pattern.isNotBlank() } }

    /** 某个类别下的分组标签（来自当前规则；空类别返回空，UI 显示"+ 规则"入口） */
    fun displayGroupsOf(categoryId: String): List<String> =
        categories.filter { it.categoryId == categoryId }.map { it.listName }.filter { it.isNotBlank() }.distinct()

    fun rulesOf(categoryId: String): List<HighlightRule> =
        categories.firstOrNull { it.categoryId == categoryId }?.rules ?: emptyList()

    /** 某个类别下某个列表的规则 */
    fun rulesOfList(categoryId: String, listName: String): List<HighlightRule> = rulesIn(categoryId, listName)

    /** 非空的启用规则总数（UI 汇总显示） */
    fun totalActiveRules(): Int = activeCategories().sumOf { c -> c.rules.count { it.enabled && it.pattern.isNotBlank() } }

    /** 覆写某个列表（同一 categoryId 下按 listName 区分）的规则并落盘 */
    fun setRules(categoryId: String, rules: List<HighlightRule>, listName: String = "") {
        val cleaned = rules.map { it.copy(pattern = it.pattern.trim()) }.filter { it.pattern.isNotEmpty() }
        val next = categories.toMutableList()
        val idx = next.indexOfFirst { it.categoryId == categoryId && it.listName == listName }
        if (idx >= 0) {
            if (cleaned.isEmpty()) next.removeAt(idx) else next[idx] = next[idx].copy(rules = cleaned)
        } else if (cleaned.isNotEmpty()) {
            next.add(HighlightRuleCategory(categoryId, cleaned, listName))
        }
        categories = orderByAvailable(next)
        persistAndRefresh()
    }

    /** 追加一条规则（同类列表内同 pattern+type 去重） */
    fun addRule(categoryId: String, pattern: String, type: RuleType, listName: String = "") {
        val token = pattern.trim()
        if (token.isEmpty()) return
        val current = rulesIn(categoryId, listName)
        if (current.any { it.pattern == token && it.type == type }) return
        setRules(categoryId, current + HighlightRule(token, type, true), listName)
    }

    // ==================== 按列表下标操作（UI 用） ====================

    /** 打开某个列表、删除其中一条规则 */
    fun removeRuleAt(listIndex: Int, ruleIndex: Int) {
        val list = categories.getOrNull(listIndex) ?: return
        val rules = list.rules.toMutableList()
        if (ruleIndex !in rules.indices) return
        rules.removeAt(ruleIndex)
        setRules(list.categoryId, rules, list.listName)
    }

    /** 打开某个列表、开关其中一条规则 */
    fun toggleRuleAt(listIndex: Int, ruleIndex: Int, enabled: Boolean) {
        val list = categories.getOrNull(listIndex) ?: return
        val rules = list.rules.toMutableList()
        if (ruleIndex !in rules.indices) return
        rules[ruleIndex] = rules[ruleIndex].copy(enabled = enabled)
        setRules(list.categoryId, rules, list.listName)
    }

    /** 打开某个列表、修改其中一条规则（关键字 ↔ 正则） */
    fun updateRuleAt(listIndex: Int, ruleIndex: Int, pattern: String, type: RuleType) {
        val token = pattern.trim()
        if (token.isEmpty()) return
        val list = categories.getOrNull(listIndex) ?: return
        val rules = list.rules.toMutableList()
        if (ruleIndex !in rules.indices) return
        rules[ruleIndex] = rules[ruleIndex].copy(pattern = token, type = type)
        setRules(list.categoryId, rules, list.listName)
    }

    /** 只重置某个列表为内置复刻（该列表在内置语法里没有对应组时 = 删除该列表，交给内置语法） */
    fun resetListToBuiltin(listIndex: Int) {
        val list = categories.getOrNull(listIndex) ?: return
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "resetListToBuiltin: no Context")
            return
        }
        HighlightRulesDefaults.defaults(ctx) // 确保内置复刻已抽取
        val builtin = HighlightRulesDefaults.builtinRulesOf(list.categoryId, list.listName)
        if (builtin == null) {
            setRules(list.categoryId, emptyList(), list.listName)
            Log.d(TAG, "resetListToBuiltin: 内置无此列表，已清空 -> ${list.categoryId}/${list.listName}")
        } else {
            setRules(list.categoryId, builtin, list.listName)
            Log.d(TAG, "resetListToBuiltin: ${list.categoryId}/${list.listName} -> ${builtin.size} 条")
        }
    }

    /** 清空全部规则（= 完全走内置语法） */
    fun clearAll() {
        categories = emptyList()
        persistAndRefresh()
    }

    /** 同一 categoryId + 列表标签下的规则 */
    private fun rulesIn(categoryId: String, listName: String): List<HighlightRule> =
        categories.firstOrNull { it.categoryId == categoryId && it.listName == listName }?.rules ?: emptyList()

    private fun orderByAvailable(list: List<HighlightRuleCategory>): List<HighlightRuleCategory> {
        val order = availableCategories.mapIndexed { i, c -> c.id to i }.toMap()
        return list.sortedBy { order[it.categoryId] ?: Int.MAX_VALUE }
    }

    /** 落盘（不 bump revision） */
    private fun persist() {
        try {
            RwmodPaths.highlightRulesFile.writeText(toJson(categories), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "persist failed: ${RwmodPaths.highlightRulesFile.absolutePath}", e)
        }
    }

    /** 规则变化后的统一收尾：bump revision（触发编辑器重建语言）+ 失效合并语法缓存 */
    private fun refresh() {
        revision++
        invalidateRegistry()
    }

    private fun persistAndRefresh() {
        persist()
        refresh()
    }

    private fun invalidateRegistry() {
        cachedRegistry = null
        cachedRegistryRevision = -1
    }

    // ==================== JSON 读写 ====================

    internal fun toJson(list: List<HighlightRuleCategory>): String {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        // 规则按类别合并写入（一个类别一条数组），同时用 groups 记录列表标签与顺序，
        // 便于回读时还原「类别 → 多个列表（自身检测/自身属性/…）」的结构
        val cats = JSONObject()
        val groups = JSONObject()
        val merged = LinkedHashMap<String, MutableList<HighlightRuleCategory>>()
        list.forEach { cat -> merged.getOrPut(cat.categoryId) { mutableListOf() }.add(cat) }
        merged.forEach { (categoryId, groupList) ->
            val arr = JSONArray()
            val groupArr = JSONArray()
            groupList.forEach { cat ->
                // count 必须落盘：否则回读时无法知道每个列表占多少条，会把规则平均分错
                val g = JSONObject()
                g.put("name", cat.listName)
                g.put("count", cat.rules.size)
                groupArr.put(g)
                cat.rules.forEach { rule ->
                    val obj = JSONObject()
                    obj.put("pattern", rule.pattern)
                    obj.put("type", if (rule.type == RuleType.REGEX) "regex" else "keyword")
                    obj.put("enabled", rule.enabled)
                    // source：这条规则复刻自哪条内置语法规则；instance：该原文的第几份
                    // （内置语法同一条规则常定义两份，两份都要复刻才能与内置完全一致）
                    if (rule.source.isNotBlank()) obj.put("source", rule.source)
                    if (rule.sourceInstance != 0) obj.put("instance", rule.sourceInstance)
                    if (rule.displayGroup.isNotBlank()) obj.put("group", rule.displayGroup)
                    arr.put(obj)
                }
            }
            cats.put(categoryId, arr)
            groups.put(categoryId, groupArr)
        }
        root.put("categories", cats)
        root.put("groups", groups)
        return root.toString(2)
    }

    internal fun parseRules(text: String): List<HighlightRuleCategory> {
        val root = JSONObject(text)
        val cats = root.optJSONObject("categories") ?: return emptyList()
        val groups = root.optJSONObject("groups")
        val out = mutableListOf<HighlightRuleCategory>()
        cats.keys().forEach { rawCategoryId ->
            // 旧文件的类别 id 先按迁移表改写（见 CATEGORY_ID_MIGRATION），
            // 否则被改名/移除的类别会被当作未知 id 直接丢弃，用户手写规则跟着丢
            val categoryId = if (CATEGORY_ID_MIGRATION.containsKey(rawCategoryId)) {
                CATEGORY_ID_MIGRATION[rawCategoryId] ?: return@forEach
            } else {
                rawCategoryId
            }
            if (categoryById[categoryId] == null) return@forEach
            val arr = cats.optJSONArray(rawCategoryId) ?: return@forEach
            val rules = mutableListOf<HighlightRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pattern = obj.optString("pattern").trim()
                if (pattern.isEmpty()) continue
                rules.add(
                    HighlightRule(
                        pattern = pattern,
                        type = RuleType.fromName(obj.optString("type")),
                        enabled = obj.optBoolean("enabled", true),
                        source = obj.optString("source", ""),
                        displayGroup = obj.optString("group", ""),
                        sourceInstance = obj.optInt("instance", 0)
                    )
                )
            }
            if (rules.isEmpty()) return@forEach
            // groups: [{name, count}]（带 count 才能精确切分；老文件是 ["名"] 字符串数组，按「均分」兜底）
            val names = mutableListOf<Pair<String, Int>>()
            val groupArr = groups?.optJSONArray(rawCategoryId)
            if (groupArr != null) {
                for (i in 0 until groupArr.length()) {
                    val entry = groupArr.optJSONObject(i)
                    if (entry != null) {
                        val count = entry.optInt("count", -1)
                        names.add(entry.optString("name", "") to count)
                    } else {
                        names.add(groupArr.optString(i, "") to -1)
                    }
                }
            }
            var cursor = 0
            if (names.isEmpty()) {
                out.add(HighlightRuleCategory(categoryId, rules, ""))
            } else {
                names.forEach { (name, count) ->
                    val take = when {
                        count >= 0 -> count
                        else -> rules.size / names.size // 老格式：只能均分兜底
                    }.coerceAtMost(rules.size - cursor)
                    if (take > 0) {
                        out.add(HighlightRuleCategory(categoryId, rules.subList(cursor, cursor + take).toList(), name))
                        cursor += take
                    }
                }
                if (cursor < rules.size) {
                    // 兜底：剩余规则（组信息不完整时）归到「自定义」
                    out.add(HighlightRuleCategory(categoryId, rules.subList(cursor, rules.size).toList(), "自定义"))
                }
            }
        }
        return orderByAvailable(out)
    }

    // ==================== 语法生成 ====================

    /**
     * 关键字 → 正则片段。
     *
     * 边界**不能**用 `\b`：语法引擎按 Unicode 判定词字符，汉字也算词字符，
     * 「自身资源大于」两侧加 `\b` 会变成 `\b自身资源大于\b`——前面若是「条件: 」这种
     * 非词字符才成立，紧跟汉字/字母时就不成立，等于规则失效（这正是内置 `自身检测`
     * 规则族长期只能靠人工补词的原因）。
     *
     * 这里改为无条件加「ASCII 标识符边界」`(?<![A-Za-z0-9_])` / `(?![A-Za-z0-9_])`：
     * - 中文关键字（前一个字符通常是空格/运算符/引号）照常命中；
     * - ASCII 关键字不会被 `notify` 这类词吞掉（`if` 不会命中 `notify` 里的 if）；
     * - 与两侧汉字相邻时不算命中，避免「自身有活动」被 `自身有活动的路径点` 之类的长规则误吃。
     */
    internal fun keywordToRegexPiece(keyword: String): String =
        "(?<![A-Za-z0-9_])${escapeRegex(keyword)}(?![A-Za-z0-9_])"

    private fun escapeRegex(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|', '/' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 生成「类别 → 一条 grammar 规则」的 match/scope 对（**仅用于手写规则**的合成）。
     * 复刻自内置的规则不走这里：它们按 [HighlightRule.source] 在原位替换内置规则，
     * 以完全保留内置的规则顺序与优先级（顺序变了着色就会变，见 [buildMergedGrammar]）。
     * 同一类别下所有规则合成一条交替 `(a|b|c)`（每个片段自带边界），长片段在前避免前缀抢占。
     */
    internal fun buildCategoryPatterns(list: List<HighlightRuleCategory>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        list.forEach { cat ->
            val info = categoryById[cat.categoryId] ?: return@forEach
            val pieces = cat.rules
                .filter { it.enabled && it.pattern.isNotBlank() && it.source.isBlank() }
                .map { rule -> if (rule.type == RuleType.REGEX) rule.pattern.trim() else keywordToRegexPiece(rule.pattern) }
                .distinct()
                // 长的排前面：同类别内 a 与 abc 同时在表里时，先试 abc，避免前缀抢占
                .sortedByDescending { it.length }
            if (pieces.isEmpty()) return@forEach
            result.add("(" + pieces.joinToString("|") + ")" to info.scope)
        }
        return result
    }

    /**
     * 把用户规则合并进内置 grammar JSON，返回合并后的 JSON 文本。
     *
     * **两种合并方式**（这是「与内置一致」的关键）：
     * 1. 带 [HighlightRule.source] 的规则（默认复刻出来的那些）：在**原位替换**内置对应规则，
     *    规则顺序与优先级完全不变 → 默认状态下着色与内置逐 token 一致；
     *    某条内置规则的复刻规则被用户删光/禁用时，该内置规则被移除（= 用户主动不要它）。
     * 2. 不带 source 的规则（用户手写新增）：合成一条插入 patterns **最前**，优先级最高。
     */
    internal fun buildMergedGrammar(baseJson: String, list: List<HighlightRuleCategory>): String {
        val (raw, replaced, customAdded) = buildMergedGrammarRaw(baseJson, list)
        if (raw === baseJson) return baseJson
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IllegalStateException("合并后的 grammar JSON 解析失败", e)
        }
        val patterns = root.optJSONArray("patterns")
            ?: throw IllegalStateException("合并后的 grammar 没有 patterns 数组")
        val expected = topLevelRuleCount(baseJson) - droppedTopLevelInside(baseJson, list) + customAdded
        if (patterns.length() != expected) {
            throw IllegalStateException(
                "合并后 patterns 条数异常: ${patterns.length()} != $expected (替换 $replaced, 新增 $customAdded)"
            )
        }
        return root.toString()
    }

    /** 顶层规则里被「清空」掉的数量（用于自检对账：只有顶层被删才会改变 patterns 条数） */
    private fun droppedTopLevelInside(baseJson: String, list: List<HighlightRuleCategory>): Int {
        val activeSources = activeSources(list).keys.map { it.first }.toSet()
        val touched = sourcePatterns(list)
        val cleared = touched.filter { it !in activeSources } // 用户碰过但现在没有生效规则 = 清空
        if (cleared.isEmpty()) return 0
        return debugSplitRules(baseJson).mapNotNull { extractMatchOrBegin(it) }.count { it in cleared }
    }

    /** 所有出现过的 source 原文（含被禁用的，用于判断一条内置规则是否被用户清空） */
    private fun sourcePatterns(list: List<HighlightRuleCategory>): Set<String> =
        list.flatMap { cat -> cat.rules.map { it.source }.filter { it.isNotBlank() } }.toSet()

    /**
     * 合并后的规则正文：把所有规则合成**一条交替**，每条自带 ASCII 边界
     * （`(?<![A-Za-z0-9_])tok(?![A-Za-z0-9_])`）。
     *
     * 为什么统一走关键字语义：默认规则是"用户可编辑的规则集"，用户往一组里加词时，
     * 只有整组重建成交替才能生效；而**每一项都带自己的边界**，所以：
     * - 单项 = 单关键字（与内置 `\b(x)\b` 在真实输入上等价，且**不是**捕获组，captures 编号不动）；
     * - 多项 = 非捕获交替 `(?:a|b|c)`，长项在前避免前缀抢占。
     * 正则类型的那一项由调用方原样放入（见 [activeSources] 的 KEYWORD/REGEX 分支）。
     * **绝不使用捕获组**：内置规则靠 `captures: {"1": …}` 给节括号/后缀上色，
     * 多一层捕获组会把编号整体挪位。
     */
    private fun mergeReplacement(pieces: List<String>): String {
        val distinct = pieces.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return ""
        if (distinct.size == 1) return distinct.first()
        return "(?:" + distinct.sortedByDescending { it.length }.joinToString("|") + ")"
    }

    /**
     * 有生效规则的 (source 原文, 第几份) → 替换体列表。
     *
     * 用「原文 + 份号」定位：内置语法里同一条规则常定义两份（键值区 + 顶层），
     * 只按原文会把两份合并成一份，等于少复刻一条内置规则（用户要求 100% 一致，必须分开）。
     */
    private fun activeSources(list: List<HighlightRuleCategory>): Map<Pair<String, Int>, List<String>> {
        val bySource = LinkedHashMap<Pair<String, Int>, MutableList<String>>()
        list.forEach { cat ->
            if (categoryById[cat.categoryId] == null) return@forEach
            cat.rules.filter { it.enabled && it.pattern.isNotBlank() && it.source.isNotBlank() }.forEach { rule ->
                val piece = if (rule.type == RuleType.REGEX) rule.pattern.trim() else keywordToRegexPiece(rule.pattern)
                bySource.getOrPut(rule.source to rule.sourceInstance) { mutableListOf() }.add(piece)
            }
        }
        return bySource
    }

    /**
     * 纯文本合并（可测）：返回 (合并后 JSON, 被替换的内置规则条数, 新建规则条数)。
     * 没有任何改动时第一个元素就是入参本身（同一对象引用）。
     *
     * 实现要点：**扫描整个文件**（不是只扫顶层）——内置语法有 42 条规则，
     * 其中 31 条嵌在「键值对 begin/end」块里，只在顶层找会漏掉它们（历史 bug：
     * 那些复刻规则既没替换内置规则、也没生效）。
     */
    internal fun buildMergedGrammarRaw(
        baseJson: String,
        list: List<HighlightRuleCategory>
    ): Triple<String, Int, Int> {
        val bySource = activeSources(list)
        // 用户**碰过但现在没有生效规则**的原文 = 被清空（该内置规则要删掉）。
        // 注意：只针对"用户文件里出现过的 source"，不能拿内置正文全集去比——
        // 否则键值对的 begin 正文（用户文件里根本没有）会被误判成"清空"而删掉整条规则。
        val touched = sourcePatterns(list)
        val cleared = touched.filter { source -> bySource.keys.none { it.first == source } }
        // 手写规则（无 source）→ 新建规则，插到 patterns 最前
        val customPatterns = mutableListOf<Pair<String, String>>()
        list.forEach { cat ->
            val info = categoryById[cat.categoryId] ?: return@forEach
            cat.rules
                .filter { it.enabled && it.pattern.isNotBlank() && it.source.isBlank() }
                .forEach { rule ->
                    val piece = if (rule.type == RuleType.REGEX) rule.pattern.trim() else keywordToRegexPiece(rule.pattern)
                    customPatterns.add(piece to info.scope)
                }
        }

        val marker = "\"patterns\""
        val markerIdx = baseJson.indexOf(marker)
        if (markerIdx < 0) throw IllegalStateException("内置 grammar 没有 patterns 字段")
        val bracketIdx = baseJson.indexOf('[', markerIdx + marker.length)
        if (bracketIdx < 0) throw IllegalStateException("内置 grammar 的 patterns 不是数组")
        val endIdx = findArrayEnd(baseJson, bracketIdx)

        // 1) 全文件扫描所有规则对象，按 (source 原文, 第几份) 原位替换（从右往左改，避免下标失效）
        data class Edit(val start: Int, val endExclusive: Int, val body: String?)

        val edits = mutableListOf<Edit>()
        val seenCount = HashMap<String, Int>() // 某 scope 已出现过几次 → 当前这次是第几份
        var replaced = 0
        var i = 0
        while (i < baseJson.length) {
            if (baseJson.startsWith("\"match\"", i) || baseJson.startsWith("\"begin\"", i)) {
                val span = stringValueSpan(baseJson, i)
                if (span != null) {
                    val (quote, closeQuote) = span
                    val body = decodeJsonEscapes(baseJson.substring(quote + 1, closeQuote))
                    // 实例号按 **scope 内的出现次序**（与复刻端一致）：
                    // 同一条规则的两份写法可能不同，按正文编号会错位
                    val scope = enclosingScopeOf(baseJson, i)
                    val instance = if (scope == null) -1 else {
                        val n = seenCount.getOrDefault(scope, 0)
                        seenCount[scope] = n + 1
                        n
                    }
                    val key = body to instance
                    if (key in bySource) {
                        val pieces = bySource.getValue(key)
                        edits.add(Edit(quote, closeQuote + 1, mergeReplacement(pieces)))
                        replaced++
                    } else if (scope != null && body in cleared) {
                        // 用户清空了该内置规则（scope 内所有份都没有生效规则）：整条规则对象删掉
                        val objStart = startOfContainingObject(baseJson, i)
                        if (objStart >= 0) {
                            val objEnd = endOfObject(baseJson, i)
                            edits.add(Edit(objStart, objEnd, null))
                            replaced++
                        }
                    }
                }
                i += 8
            } else {
                i++
            }
        }
        var out = baseJson
        edits.sortedByDescending { it.start }.forEach { edit ->
            out = if (edit.body != null) {
                out.substring(0, edit.start) + quoteJson(edit.body) + out.substring(edit.endExclusive)
            } else {
                out.substring(0, edit.start) + out.substring(edit.endExclusive)
            }
        }

        // 2) 手写规则插到最前（优先级最高）
        val customJson = customPatterns
            .groupBy { it.second }
            .map { (scope, pieces) ->
                val body = "(" + pieces.map { it.first }.distinct().sortedByDescending { it.length }.joinToString("|") + ")"
                buildRuleObjectJson(body, scope)
            }
        if (customJson.isNotEmpty()) {
            // 重新定位（替换后下标会变）
            val m2 = out.indexOf("\"patterns\"")
            val b2 = out.indexOf('[', m2 + marker.length)
            val joined = customJson.joinToString(",")
            out = if (out.substring(b2 + 1).trimStart().startsWith("]")) {
                out.substring(0, b2 + 1) + joined + out.substring(b2 + 1)
            } else {
                out.substring(0, b2 + 1) + joined + "," + out.substring(b2 + 1)
            }
        }
        val changed = replaced > 0 || customJson.isNotEmpty()
        return Triple(if (changed) out else baseJson, replaced, customJson.size)
    }

    /** 定位 `"match"`/`"begin"` 之后的字符串值范围：返回 (开引号下标, 闭引号下标) */
    private fun stringValueSpan(json: String, keyIdx: Int): Pair<Int, Int>? {
        val colon = json.indexOf(':', keyIdx)
        if (colon < 0) return null
        var quote = -1
        var j = colon + 1
        while (j < json.length && j < colon + 40) {
            val c = json[j]
            if (c == '"') {
                quote = j
                break
            }
            if (c !in " \t\r\n") return null
            j++
        }
        if (quote < 0) return null
        var i = quote + 1
        var escaped = false
        while (i < json.length) {
            val c = json[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return quote to i
            }
            i++
        }
        return null
    }

    /** 包含下标的最内层 `{...}` 起点 */
    private fun startOfContainingObject(json: String, idx: Int): Int {
        var depth = 0
        var i = idx
        while (i >= 0) {
            when (json[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
            i--
        }
        return -1
    }

    /** 包含下标的 `{...}` 终点（含 `}`，用作 exclusive 边界） */
    private fun endOfObject(json: String, idx: Int): Int {
        var depth = 0
        var i = idx
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    if (depth == 0) return i + 1
                    depth--
                }
            }
            i++
        }
        return json.length
    }

    /** 取包含下标 i 的**最近外层规则对象**的 `name`（找不到返回 null；用于按 scope 计数实例） */
    private fun enclosingScopeOf(json: String, idx: Int): String? {
        var depth = 0
        var i = idx
        while (i >= 0) {
            when (json[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) {
                        val objEnd = endOfObjectAt(json, i)
                        val obj = json.substring(i, objEnd.coerceAtMost(json.length))
                        val m = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(obj)
                        return m?.groupValues?.get(1)
                    }
                    depth--
                }
            }
            i--
        }
        return null
    }

    /** 从 `{` 出发找到配对的 `}` 下标（含），跳过字符串 */
    private fun endOfObjectAt(json: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        var inString = false
        var escaped = false
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return json.length
    }

    /** 找到与 `[` 匹配的 `]` 下标（跳过字符串） */
    private fun findArrayEnd(json: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        var inString = false
        var escaped = false
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        throw IllegalStateException("patterns 数组没有闭合")
    }

    /** 按顶层对象切分数组内容（跳过字符串与嵌套对象） */
    private fun splitTopLevelObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        body.forEachIndexed { i, c ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(body.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return out
    }

    /** 取一条规则对象的 match/begin 原文（按 JSON 转义规则反转义），没有则 null */
    private fun extractMatchOrBegin(ruleJson: String): String? {
        for (key in listOf("match", "begin")) {
            val idx = ruleJson.indexOf("\"$key\"")
            if (idx < 0) continue
            val colon = ruleJson.indexOf(':', idx)
            if (colon < 0) continue
            val quote = ruleJson.indexOf('"', colon + 1)
            if (quote < 0) continue
            val sb = StringBuilder()
            var i = quote + 1
            var escaped = false
            while (i < ruleJson.length) {
                val c = ruleJson[i]
                if (escaped) {
                    sb.append('\\').append(c)
                    escaped = false
                } else {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> return decodeJsonEscapes(sb.toString())
                        else -> sb.append(c)
                    }
                }
                i++
            }
        }
        return null
    }

    /** 把规则对象里的 match/begin 替换成新值（只改第一个命中的键） */
    private fun rewriteMatchOrBegin(ruleJson: String, newMatch: String): String {
        for (key in listOf("match", "begin")) {
            val idx = ruleJson.indexOf("\"$key\"")
            if (idx < 0) continue
            val colon = ruleJson.indexOf(':', idx)
            if (colon < 0) continue
            val quote = ruleJson.indexOf('"', colon + 1)
            if (quote < 0) continue
            var i = quote + 1
            var escaped = false
            while (i < ruleJson.length) {
                val c = ruleJson[i]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    return ruleJson.substring(0, quote) + quoteJson(newMatch) + ruleJson.substring(i + 1)
                }
                i++
            }
        }
        throw IllegalStateException("规则对象里没有可替换的 match/begin")
    }

    /** JSON 字符串反转义（extractMatchOrBegin 用，只处理常见转义） */
    private fun decodeJsonEscapes(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 6
                            continue
                        }
                        sb.append(n)
                    }
                    else -> sb.append('\\').append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 统计顶层 patterns 条数：把 JSON 当文本扫一遍，数**顶层规则对象里的 `match`/`begin` 键**。
     *
     * 判断「顶层规则对象」= 括号深度与对象深度都为 1：
     * - 深度 1 处的 `}` 不能直接当条数（`captures.foo` 这种嵌套对象的结尾也在同一深度，
     *   42 条规则会被数成 65 条）；
     * - 也不能只数 `"match"` 出现次数（`captures` 里也有 `match`，会被数成 81 条）。
     */
    internal fun topLevelRuleCount(grammarJson: String): Int {
        val marker = "\"patterns\""
        val markerIdx = grammarJson.indexOf(marker)
        if (markerIdx < 0) return 0
        val open = grammarJson.indexOf('[', markerIdx + marker.length)
        if (open < 0) return 0
        var arrayDepth = 0
        var objectDepth = 0
        var count = 0
        var i = open
        var inString = false
        var escaped = false
        var stringStart = -1
        while (i < grammarJson.length) {
            val c = grammarJson[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        inString = false
                        if (arrayDepth == 1 && objectDepth == 1) {
                            // 注意 +1：闭引号本身也要包含进来，否则 token 是 `"match`（少一个引号）永不匹配
                            val token = grammarJson.substring(stringStart, i + 1)
                            if (token == "\"match\"" || token == "\"begin\"") count++
                        }
                    }
                }
            } else {
                when (c) {
                    '"' -> {
                        inString = true
                        stringStart = i
                    }
                    '[' -> arrayDepth++
                    ']' -> {
                        arrayDepth--
                        if (arrayDepth == 0) return count
                    }
                    '{' -> objectDepth++
                    '}' -> objectDepth--
                }
            }
            i++
        }
        return count
    }

    /** 自检：全文件扫描所有规则的 match/begin 原文（调试/单测探针） */
    internal fun debugExtractBodies(baseJson: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < baseJson.length) {
            if (baseJson.startsWith("\"match\"", i) || baseJson.startsWith("\"begin\"", i)) {
                stringValueSpan(baseJson, i)?.let { (quote, closeQuote) ->
                    out.add(decodeJsonEscapes(baseJson.substring(quote + 1, closeQuote)))
                }
                i += 8
            } else {
                i++
            }
        }
        return out
    }

    /** 自检：按顶层对象切分内置语法的 patterns（调试/单测探针） */
    internal fun debugSplitRules(baseJson: String): List<String> {
        val marker = "\"patterns\""
        val markerIdx = baseJson.indexOf(marker)
        val bracketIdx = baseJson.indexOf('[', markerIdx + marker.length)
        val endIdx = findArrayEnd(baseJson, bracketIdx)
        return splitTopLevelObjects(baseJson.substring(bracketIdx + 1, endIdx))
    }

    /** 自检：对单条规则对象跑一次 match/begin 抽取（调试/单测探针） */
    internal fun debugExtractOne(ruleJson: String): String? = extractMatchOrBegin(ruleJson)

    /** 一条用户规则对应的 TextMate 规则对象（原始 JSON 文本）。 */
    internal fun buildRuleObjectJson(match: String, scope: String): String =
        "{" +
            "\"comment\":\"用户自定义高亮规则（开发者模式→规则）\"," +
            "\"match\":" + quoteJson(match) + "," +
            "\"name\":" + quoteJson(scope) +
            "}"

    /** 最小 JSON 字符串转义（规则源码里可能含引号/反斜杠/控制字符） */
    internal fun quoteJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 读内置 grammar 原始 JSON（每次从内存资产读，避免持有大字符串） */
    private fun readBaseGrammar(context: Context): String =
        context.assets.open(BASE_GRAMMAR_PATH).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    /**
     * 构建注册了「内置 + 用户规则」合并语法的独立注册表。
     *
     * 为什么用独立注册表：sora/tm4e 的 grammar 缓存按 scope 名做 `computeIfAbsent`，
     * 在同一注册表里重载同名 scope 不会生效；用子注册表（parent=单例）既保证了重新加载，
     * 又保留了语言配置（completion / 自动括号）与主题监听。
     *
     * @return 无启用规则时返回 null（调用方回退到单例注册表，等于零改动）
     */
    @Synchronized
    fun registryWithCustomRules(context: Context): GrammarRegistry? {
        load(context)
        val active = activeCategories()
        val activeRuleCount = totalActiveRules()
        Log.d(
            TAG,
            "registryWithCustomRules: enabled=$enabled, lists=${categories.size}, activeLists=${active.size}, " +
                "rules=$activeRuleCount, revision=$revision, cachedRevision=$cachedRegistryRevision"
        )
        if (!enabled) {
            Log.d(TAG, "registryWithCustomRules -> null (开关关闭，走纯内置语法)")
            return null
        }
        if (active.isEmpty()) {
            Log.d(TAG, "registryWithCustomRules -> null (无生效规则，走纯内置语法)")
            return null
        }
        cachedRegistry?.let {
            if (cachedRegistryRevision == revision) {
                Log.d(TAG, "registryWithCustomRules -> cached (revision=$revision)")
                return it
            }
        }
        // 本次调用只建一次语法；任何失败都必须抛到下面的 catch 里留下可见日志
        return try {
            val base = readBaseGrammar(context)
            val merged = buildMergedGrammar(base, active)
            Log.d(TAG, "merged grammar ready: baseRules=${topLevelRuleCount(base)}, customRules=${active.size}, mergedChars=${merged.length}")
            val registry = GrammarRegistry.getInstance()
            installIntoRegistry(registry, context, merged)
            cachedRegistry = registry
            cachedRegistryRevision = revision
            Log.d(
                TAG,
                "custom grammar built: revision=$revision, lists=${active.size}, rules=$activeRuleCount, " +
                    "scope=source.ini"
            )
            // Debug 浮窗可见性：设备上 logcat 未必抓得到（本机实测经常被系统丢），
            // 所以在 Debug 任务进度浮窗里也留一条「已生效」标记，方便一眼确认走了哪条路
            if (SettingsManager.devDebugTaskProgress) {
                TaskProgressManager.start("自定义高亮已生效")
                TaskProgressManager.update(activeRuleCount, "规则 $activeRuleCount 条 · 列表 ${active.size} 个")
                TaskProgressManager.finish()
            }
            registry
        } catch (e: Exception) {
            Log.e(TAG, "build custom grammar failed; 回退内置语法（本次不生效）", e)
            invalidateRegistry()
            null
        }
    }

    /**
     * 把「内置 + 用户规则」的合并语法装进**单例语法注册表**（scope 仍为 `source.ini`）。
     *
     * 为什么不另起一个子注册表：sora 的 token 颜色是按注册表内部 id 空间编码后写进
     * EditorColorScheme 的，子注册表与全局主题的 id 空间不一致时，token 会取不到颜色
     * （表现就是「自定义规则不生效、整篇像没高亮」）。装回单例后 id 空间与主题一致，
     * 编辑器创建语言时 `findGrammar("source.ini")` 拿到的就是合并语法。
     *
     * tm4e 的 Registry 会把「某 scope 已加载」记进 `_ensureGrammarCache`，同一注册表内
     * 再 `loadGrammar("source.ini")` 只会返回旧语法，所以这里换一个全新的 Registry 实例
     * 再重新登记（只影响语法解析，主题/配色方案是另一套对象，不受影响）。
     */
    private fun installIntoRegistry(registry: GrammarRegistry, context: Context, merged: String) {
        val fresh = org.eclipse.tm4e.core.registry.Registry()
        val field = GrammarRegistry::class.java.getDeclaredField("registry")
        field.isAccessible = true
        field.set(registry, fresh)

        val source = IGrammarSource.fromString(IGrammarSource.ContentType.JSON, merged)
        val definition = DefaultGrammarDefinition.withGrammarSource(source, "ini_custom_r$revision", "source.ini")
        val grammar = registry.loadGrammar(definition)
            ?: throw IllegalStateException("loadGrammar 返回 null（未加载到合并语法）")
        if (grammar.scopeName != "source.ini") {
            throw IllegalStateException("合并语法 scope 异常: ${grammar.scopeName}")
        }
        // 语言配置（自动括号/缩进）在换注册表后要重新挂一次
        val config = context.assets.open(LANGUAGE_CONFIG_PATH)
            .bufferedReader(StandardCharsets.UTF_8).use {
                org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration.load(it)
            }
        registry.languageConfigurationToGrammar(config, grammar)
        Log.d(TAG, "merged grammar installed into singleton registry: scope=${grammar.scopeName}")
    }

    /** 合并后 grammar 文本中是否包含某 token 的规则片段（自检用） */
    internal fun mergedContainsToken(baseJson: String, categoryId: String, token: String): Boolean {
        val list = listOf(HighlightRuleCategory(categoryId, listOf(HighlightRule(token, RuleType.KEYWORD))))
        return buildMergedGrammarRaw(baseJson, list).first.contains(keywordToRegexPiece(token))
    }

    /**
     * 合并语法诊断（**开发者手动调用**，不挂在启动/编辑流程上）：
     * 把「内置语法」与「当前规则合并后的语法」写到 `RWmod/cache/grammar_base.json` /
     * `grammar_merged.json`，并做一次**结构对比**（规则条数、顺序、每条正文是否只在被改写的规则上有差异）。
     * 报告写 `RWmod/cache/highlight_rule_report.txt`。
     *
     * 结构对比而不是"逐 token 比色"：色号还受主题影响，结构对齐才是"与内置一致"的根证据。
     */
    fun runGrammarDiagnostic(context: Context) {
        val file = java.io.File(RwmodPaths.cacheDir, "highlight_rule_report.txt")
        try {
            val base = readBaseGrammar(context)
            val active = activeCategories()
            val merged = if (active.isEmpty()) base else buildMergedGrammar(base, active)
            java.io.File(RwmodPaths.cacheDir, "grammar_base.json").writeText(base, StandardCharsets.UTF_8)
            java.io.File(RwmodPaths.cacheDir, "grammar_merged.json").writeText(merged, StandardCharsets.UTF_8)
            val baseBodies = debugExtractBodies(base)
            val mergedBodies = debugExtractBodies(merged)
            val changed = baseBodies.zip(mergedBodies).count { (a, b) -> a != b }
            val sb = StringBuilder()
            sb.appendLine("=== 合并语法诊断 ===")
            sb.appendLine("样式: enabled=$enabled, lists=${active.size}, rules=${totalActiveRules()}")
            sb.appendLine("内置规则体=${baseBodies.size}, 合并后=${mergedBodies.size}")
            sb.appendLine("被改写的规则数=$changed（其余必须逐条一致）")
            if (baseBodies.size != mergedBodies.size) {
                sb.appendLine("⚠ 规则条数不一致：合并过程增删了规则")
            }
            file.writeText(sb.toString(), StandardCharsets.UTF_8)
            Log.d(TAG, "grammar diagnostic -> ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "grammar diagnostic failed", e)
        }
    }

    const val BASE_GRAMMAR_PATH = "textmate/ini/ini.tmLanguage.json"
    const val LANGUAGE_CONFIG_PATH = "textmate/ini/language-configuration.json"

    /**
     * 规则文件格式版本。
     * v1：groups 只存列表名，回读时无法知道每组条数（已修）；
     * v2：groups 存 [{name,count}]，回读可精确切分；
     * v3：类别 id 改为主题配色键（`CATEGORY_ID_MIGRATION` 负责旧 id 改写），回读时重写文件。
     */
    private const val FORMAT_VERSION = 3

    /**
     * 「内置复刻默认规则」的版本。**复刻机制有实质改动时必须 +1**：
     * 启动时若与设置里记录的不一致，会用新复刻替换默认规则（用户手写规则保留）。
     * 1：按 scope 分配分组、全量收规则（此前锚定"原文"，漏了 14 条内置规则）。
     * 2：收集器补上 `captures`/`beginCaptures` 里的具名捕获（节括号/节后缀/@memory 快捷键/键名/冒号
     * 这些**只**出现在捕获里的配色原来是完全没被复刻的），槽位 74 → 91，内置规则 73 → 92 条全复刻。
     * 3：分组名统一为「顶层 / 重复定义」两种后缀（顶层分组界面默认折叠），槽位 92。
     * 4：类别 id 统一为主题配色键（规则页类别 == 自定义高亮类型）；`section`→`sectionBracket`、
     *    `boolean`/`constant`→`team`，槽位 scope 归属不变（仍是 92/92）。
     */
    const val DEFAULTS_VERSION = 4
}
