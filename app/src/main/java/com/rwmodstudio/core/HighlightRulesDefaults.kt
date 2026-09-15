package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

private const val TAG = "HighlightRulesDefaults"

/**
 * 内置语法复刻：把 `assets/textmate/ini/ini.tmLanguage.json` 里**每条带 scope 的规则**逐条搬成
 * [HighlightRuleManager] 的默认规则（**一条对一条，含重复定义**）。
 *
 * 为什么要一条对一条：内置规则在合并语法里要靠 `source`+`instance` **原位替换**，
 * 只有条数、顺序、份数都对齐，着色才与内置完全一致（少一条就等于少一条内置规则）。
 *
 * 分组：`displayGroup` 是 UI 展示用的标签（如「自身检测」「帧率」），
 * 同一条内置规则的重复定义用「(顶层)」区分，方便用户分别自定义。
 */
object HighlightRulesDefaults {

    /** 一个复刻槽位：内置第 N 份「scope + 原文」对应的展示分组 */
    private data class Slot(
        val categoryId: String,
        val displayGroup: String,
        val scope: String,
        val instance: Int = 0,
        /** 只按正则复刻（节名这类整条正则、且同一规则服务两个类别） */
        val regexOnly: Boolean = false
    )

    private fun slot(
        categoryId: String,
        displayGroup: String,
        scope: String,
        instance: Int = 0,
        regexOnly: Boolean = false
    ) = Slot(categoryId, displayGroup, scope, instance, regexOnly)

    /**
     * 复刻槽位表 = 内置语法里全部带 scope 的规则（逐条对应，共 73 条）。
     *
     * `instance` = **该 scope 内的第几条规则**（0 起）：语法里同一条规则定义两份时是 0 / 1，
     * 而值高亮这类同 scope 下本来就有 5 条不同规则，就是 0..4。
     * 序号写错会漏复刻内置规则，单测 `defaultSlotsMatchGrammarRuleCounts` 会拦住。
     */
    private val SLOTS: List<Slot> = listOf(
        // ══ 值高亮：同 scope 下 5 条（键值区）+ 5 条（顶层）══
        slot("value", "自身检测", "string.unquoted.value.ini", 0),
        slot("value", "单位数量", "string.unquoted.value.ini", 1),
        slot("value", "自身属性", "string.unquoted.value.ini", 2),
        slot("value", "中文参数", "string.unquoted.value.ini", 3),
        slot("value", "兜底单词", "string.unquoted.value.ini", 4),
        slot("value", "自身检测(顶层)", "string.unquoted.value.ini", 5),
        slot("value", "单位数量(顶层)", "string.unquoted.value.ini", 6),
        slot("value", "自身属性(顶层)", "string.unquoted.value.ini", 7),
        slot("value", "中文参数(顶层)", "string.unquoted.value.ini", 8),
        slot("value", "兜底单词(顶层)", "string.unquoted.value.ini", 9),

        // ══ 引用前缀（同 scope 6 条：引用前缀/自身资源/时间函数 × 键值区+顶层）══
        slot("reference", "引用前缀", "variable.other.reference.ini", 0),
        slot("reference", "自身资源", "variable.other.reference.ini", 1),
        slot("reference", "时间函数", "variable.other.reference.ini", 2),
        slot("reference", "引用前缀(重复定义)", "variable.other.reference.ini", 3),
        slot("reference", "自身资源(重复定义)", "variable.other.reference.ini", 4),
        slot("reference", "时间函数(重复定义)", "variable.other.reference.ini", 5),
        // 引用目标 scope 4 条
        slot("reference", "引用目标", "variable.other.reference.target.ini", 0),
        slot("reference", "空单位", "variable.other.reference.target.ini", 1),
        slot("reference", "引用目标(重复定义)", "variable.other.reference.target.ini", 2),
        slot("reference", "空单位(重复定义)", "variable.other.reference.target.ini", 3),

        // ══ 函数 / 类型 / 数字 / 参数（各 2 条）══
        slot("function", "函数", "support.function.ini", 0),
        slot("function", "函数(重复定义)", "support.function.ini", 1),
        slot("type", "类型", "storage.type.ini", 0),
        slot("type", "类型(重复定义)", "storage.type.ini", 1),
        slot("number", "数字", "constant.numeric.ini", 0),
        slot("number", "数字(重复定义)", "constant.numeric.ini", 1),
        slot("parameter", "参数关键词", "variable.parameter.ini", 0),
        slot("parameter", "参数关键词(重复定义)", "variable.parameter.ini", 1),

        // ══ 节括号（4 条）══
        // 括号不是独立规则，是「节名规则」的两个具名捕获（captures[1] / captures[3]）：
        // 带下划线的节规则 + 普通节规则各一对，两份正文不同 → 两个展示分组，四把钥匙互不相撞。
        slot("sectionBracket", "左方括号（下划线节 capt[1]）", "punctuation.definition.section.begin.ini", 0, regexOnly = true),
        slot("sectionBracket", "右方括号（下划线节 capt[3]）", "punctuation.definition.section.end.ini", 0, regexOnly = true),
        slot("sectionBracket", "左方括号（普通节 capt[1]）", "punctuation.definition.section.begin.ini", 1, regexOnly = true),
        slot("sectionBracket", "右方括号（普通节 capt[3]）", "punctuation.definition.section.end.ini", 1, regexOnly = true),

        // ══ @memory 快捷键变量（7 条，一个片段一个分组）══
        // 两份 @memory 规则（完整声明：关键字/变量/冒号/类型/中括号；简写：关键字/变量）共 7 个具名捕获。
        // 每个片段单独一个展示分组：分组会按 (scope, instance) 唯一对应一条内置规则，
        // 一个分组塞两个槽位会让「逐条对账」分不清、用户也没法单独改其中一段。
        slot("memory", "内存声明·关键字（完整）", "keyword.control.shortcut.ini", 0, regexOnly = true),
        slot("memory", "内存声明·冒号（完整）", "punctuation.separator.shortcut.ini", 0, regexOnly = true),
        slot("memory", "内存声明·类型（完整）", "storage.type.shortcut.ini", 0, regexOnly = true),
        slot("memory", "内存声明·中括号（完整）", "punctuation.bracket.square.ini", 1, regexOnly = true),
        slot("memory", "内存声明·关键字（简写）", "keyword.control.shortcut.ini", 1, regexOnly = true),
        // 同上：主题里「快捷变量（专用配色）」是另一组配色键，指向 variable.other.shortcut.ini，
        // 槽位只给一个主人 —— 快捷变量归「快捷变量」类别
        slot("shortcutVariable", "快捷变量·变量（完整）", "variable.other.shortcut.ini", 0, regexOnly = true),
        slot("shortcutVariable", "快捷变量·变量（简写）", "variable.other.shortcut.ini", 1, regexOnly = true),

        // ══ 键名 / 冒号（键值对规则的 beginCaptures）══
        slot("keyword", "键名（beginCaptures[1]）", "keyword.key.ini", 0, regexOnly = true),
        slot("value", "键值冒号（beginCaptures[2]）", "punctuation.separator.ini", 0, regexOnly = true),

        // ══ 队伍 / 路径点 / 布尔（同一配色键：主题里 constant.language.boolean 与 team/waypoint 同色）══
        // 类别 id 用主题配色键 `team`（界面显示「队伍/路径点」）。内置语法里布尔常量与队伍值共用一套颜色，
        // 拆成「布尔常量」「队伍/路径点」两个类别会出现「改了布尔常量其实改了队伍色」的错位（见 AGENTS.md §9）。
        // 不带后缀的 = 键值块内那份（**默认显示**）；`(顶层)` = 顶层另一处写法不同的那份（**默认隐藏**）；
        // `(重复定义)` = 与上一组正文完全相同、只是语法里写了两遍（合成一组，改动会同时作用于两处）
        slot("team", "队伍值", "constant.language.team.ini", 0),
        slot("team", "队伍值(重复定义)", "constant.language.team.ini", 1),
        // 顶层那份比块内多 `guard|保护` → 写法不同，独立一组（默认隐藏）
        slot("team", "路径点类型", "constant.language.waypoint.ini", 1),
        slot("team", "路径点类型(顶层)", "constant.language.waypoint.ini", 0),
        slot("team", "布尔值", "constant.language.boolean.ini", 0),
        slot("team", "特殊值", "constant.language.boolean.ini", 1),
        slot("team", "帧率", "constant.language.boolean.ini", 2),
        slot("team", "内置属性", "constant.language.boolean.ini", 3),
        slot("team", "布尔值(重复定义)", "constant.language.boolean.ini", 4),
        slot("team", "特殊值(重复定义)", "constant.language.boolean.ini", 5),
        slot("team", "帧率(重复定义)", "constant.language.boolean.ini", 6),
        slot("team", "内置属性(重复定义)", "constant.language.boolean.ini", 7),
        // 剩余两条：`.获取相对偏移(...)` 链式方法调用的**返回值**（captures[1]，布尔配色）
        // 以及顶层那份的 captures[1]。同 scope 同正文的重复定义会合并成一组。
        slot("team", "链式方法返回值(重复定义)", "constant.language.boolean.ini", 8, regexOnly = true),
        slot("team", "链式方法返回值", "constant.language.boolean.ini", 9, regexOnly = true),

        // ══ 移动（2 条）══
        slot("movement", "移动类型", "constant.language.uppercase.ini", 0),
        slot("movement", "移动类型(重复定义)", "constant.language.uppercase.ini", 1),

        // ══ 颜色 / 注释 / 路径 ══
        slot("hexColor", "十六进制颜色", "constant.other.color.ini", 0),
        slot("hexColor", "十六进制颜色(重复定义)", "constant.other.color.ini", 1),
        slot("comment", "行注释", "comment.line.ini", 0),
        slot("path", "路径前缀", "constant.language.path.ini", 0),
        slot("path", "路径前缀(重复定义)", "constant.language.path.ini", 1),

        // ══ 字符串 / 引号（各 2 条）/ 装饰符（4 条）══
        slot("string", "双引号字符串", "string.quoted.double.ini", 1),
        slot("string", "双引号字符串(顶层)", "string.quoted.double.ini", 0),
        slot("quote", "单引号字符串", "string.quoted.single.ini", 1),
        slot("quote", "单引号字符串(顶层)", "string.quoted.single.ini", 0),
        slot("decoration", "相邻双引号", "punctuation.ignored.ini", 0),
        slot("decoration", "连续单引号", "punctuation.ignored.ini", 1),
        slot("decoration", "相邻双引号(重复定义)", "punctuation.ignored.ini", 2),
        slot("decoration", "连续单引号(重复定义)", "punctuation.ignored.ini", 3),

        // ══ 运算符（逻辑 2 / 比较 2 / 赋值 1 / 算术 2 / 点访问符 2）══
        // 「逻辑符」是主题里与 operator 分开的另一组配色键，也指向 keyword.operator.logical.ini；
        // 槽位只能有一个主人（否则两个类别指向同一条内置规则 = 撞车），所以逻辑符归「逻辑符」类别，
        // 「运算符」类别负责比较/赋值/算术符与**点访问符**
        //（点访问符的 scope `punctuation.accessor.ini` 在主题里就是归 operator 上色的，
        //  放在「装饰符」下写了规则也不会变色）
        slot("logical", "逻辑符", "keyword.operator.logical.ini", 0),
        slot("logical", "逻辑符(重复定义)", "keyword.operator.logical.ini", 1),
        slot("operator", "点访问符", "punctuation.accessor.ini", 0),
        slot("operator", "点访问符(重复定义)", "punctuation.accessor.ini", 1),
        slot("operator", "比较符(顶层)", "keyword.operator.comparison.ini", 0),
        slot("operator", "比较符", "keyword.operator.comparison.ini", 1),
        slot("operator", "赋值符", "keyword.operator.assignment.ini", 0),
        slot("operator", "算术符(顶层)", "keyword.operator.arithmetic.ini", 0),
        slot("operator", "算术符", "keyword.operator.arithmetic.ini", 1),
        // ══ 括号 / 逗号 ══
        // 圆括号/花括号：块内 `[\(\)]`（字符类）与顶层 `([\(\)])`（捕获组）写法不同 → 顶层那组默认隐藏
        slot("bracketRound", "圆括号(顶层)", "punctuation.bracket.round.ini", 1),
        slot("bracketRound", "圆括号", "punctuation.bracket.round.ini", 0),
        slot("bracketCurly", "花括号(顶层)", "punctuation.bracket.curly.ini", 0),
        slot("bracketCurly", "花括号", "punctuation.bracket.curly.ini", 1),
        slot("bracket", "方括号", "punctuation.bracket.square.ini", 0),
        slot("comma", "逗号(顶层)", "punctuation.separator.comma.ini", 0),
        slot("comma", "逗号", "punctuation.separator.comma.ini", 1),
        // 括号在语法里有三处：中括号规则本体 / @memory 的 captures[5] / 顶层那份捕获组写法，
        // 漏掉任何一处，「方括号」类别的自定义都改不干净（剩下的内置捕获会把颜色抢回去）
        slot("bracket", "方括号(顶层)", "punctuation.bracket.square.ini", 2, regexOnly = true),
        // 顶层那份圆括号（`([\(\)])`）与 …#2 的正文逐字相同 → 归到「圆括号(重复定义)」
        slot("bracketRound", "圆括号(重复定义)", "punctuation.bracket.round.ini", 2, regexOnly = true),
        slot("bracketRound", "链式方法调用的括号(capt[2])", "punctuation.bracket.round.ini", 3, regexOnly = true),
        // ══ 节名（规则级 name=entity.name.section.ini，靠 captures 给节括号/节后缀上色）══
        // 类别 id 用主题配色键 `sectionBracket`（界面显示「节括号」）：主题里 entity.name.section.ini
        // 就是归 sectionBracket 上色的，原来放在单独的「节」类别下写了规则也不会变色。
        // 节名整条规则 `(\[)(.+?)(\])` —— 规则级 name 与 captures[2] 都是 entity.name.section.ini，
        // 是**同一条内置规则**（该 scope 内第 0 条），所以节名只占 1 个槽位
        slot("sectionBracket", "节名（整条规则）", "entity.name.section.ini", 0, regexOnly = true),
        // 带下划线的节规则（`(?i)(\[)(族名)(_+后缀)(\])`）的规则级 name 也是 entity.name.section.ini，
        // 是该 scope 内第 1 条：正文与上面的普通节规则不同（带白名单族名），必须单独占槽位（顶层，默认隐藏）
        slot("sectionBracket", "节名（白名单族名）", "entity.name.section.ini", 1, regexOnly = true),
        // 带下划线的节 `(\[)(族名)(_+后缀)(\])`：captures[2] = 后缀，独立 scope，独立槽位
        slot("sectionSuffix", "节后缀（captures[2]）", "entity.name.section.suffix.ini", 0, regexOnly = true)
    )

    /** 展示分组 → 该组对应的内置 scope（用于对账计数） */
    private fun slotScopeOf(displayGroup: String): String =
        SLOTS.firstOrNull { it.displayGroup == displayGroup }?.scope.orEmpty()

    /** 分组名里「顶层」标记：该组对应的是语法顶层那一处写法不同的副本，UI 默认折叠隐藏 */
    const val TOP_LEVEL_MARK = "(顶层)"

    /** 分组名里「重复定义」标记：该组与上一组正文完全相同，只是语法里写了两遍（合并且同时生效） */
    const val DUPLICATE_MARK = "(重复定义)"

    /** 该分组是否是「顶层另一处写法」——界面默认隐藏这类分组（可在规则页打开显示） */
    fun isTopLevelGroup(listName: String): Boolean = listName.endsWith(TOP_LEVEL_MARK)

    /** 参数关键字规则所在 scope（只有它里面那条带 `==?` 的规则归「参数」类别） */
    private const val PARAM_SCOPE = "variable.parameter.ini"

    /** 参数关键字规则的特征（避免只靠关键词判断而误伤值高亮里的「中文参数」） */
    private val PARAM_HINT = "有标签|超过|少于"

    /** 该类别下**实际存在**的分组标签（同正文重复定义已合并）；空类别返回空列表 */
    fun declaredGroups(categoryId: String): List<String> = groupNamesByCategory[categoryId].orEmpty()

    /** 复刻后各类别实际分组（进程内缓存） */
    @Volatile
    private var groupNamesByCategory: Map<String, List<String>> = emptyMap()

    /** 全部声明的 scope（单测哨兵用） */
    internal val SCHEMA_SCOPES: Set<String> = SLOTS.map { it.scope }.toSet()

    /** 槽位明细（单测用来校验序号不越界）：(类别, 分组标签, scope, instance) */
    internal fun debugSlots(): List<Quadruple> =
        SLOTS.map { Quadruple(it.categoryId, it.displayGroup, it.scope, it.instance) }

    internal data class Quadruple(
        val categoryId: String,
        val displayGroup: String,
        val scope: String,
        val instance: Int
    )

    /** 复刻槽位总数（应等于内置带 scope 的规则条数） */
    internal val slotCount: Int get() = SLOTS.size

    @Volatile
    private var cached: List<HighlightRuleManager.HighlightRuleCategory>? = null

    /** 读内置 grammar 并抽取默认规则（进程内缓存一次） */
    fun defaults(context: Context): List<HighlightRuleManager.HighlightRuleCategory> {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val list = try {
                val json = context.assets
                    .open(HighlightRuleManager.BASE_GRAMMAR_PATH)
                    .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                parseBuiltinRules(json)
            } catch (e: Exception) {
                Log.e(TAG, "抽取内置语法失败", e)
                emptyList()
            }
            cached = list
            list
        }
    }

    /** 某个类别下内置复刻的标签顺序（UI 用它排列表格，含空组） */
    fun builtinListNames(categoryId: String): List<String> = declaredGroups(categoryId)

    /** 某个标签的内置复刻规则（逐列表重置用，需已加载过 [defaults]） */
    fun builtinRulesOf(categoryId: String, listName: String): List<HighlightRuleManager.HighlightRule>? =
        cached?.firstOrNull { it.categoryId == categoryId && it.listName == listName }?.rules

    /**
     * 从 grammar JSON 抽取默认规则（依赖 [JSONObject]，JVM 单测跑不了，由设备报告验证）。
     * 每个槽位消费「该 scope 的第 instance 份」规则；消费不到的槽位留空（UI 仍会显示该组）。
     */
    fun parseBuiltinRules(grammarJson: String): List<HighlightRuleManager.HighlightRuleCategory> {
        val root = try {
            JSONObject(grammarJson)
        } catch (e: Exception) {
            Log.e(TAG, "grammar JSON 解析失败", e)
            return emptyList()
        }
        val rules = mutableListOf<Pair<String, String>>()
        collectRules(root.optJSONArray("patterns"), rules)
        // 实例 = **该 scope 内的第几条规则**（不是第几个不同正文）：
        // 语法里同一条规则定义两份时写法常不同（顶层那份少两个词、多一层括号），
        // 按正文编号会让后一份永远匹配不到；同一 scope 也可能有多条本来就不同的规则（如值高亮的 5 条）。
        val scopeSeq = HashMap<String, Int>()
        val instanceBodies = mutableListOf<Pair<Pair<String, Int>, String>>()
        rules.forEach { (scope, body) ->
            val index = scopeSeq.getOrDefault(scope, 0)
            scopeSeq[scope] = index + 1
            instanceBodies.add((scope to index) to body)
        }
        Log.d(TAG, "内置带 scope 规则=${rules.size}, 不同正文=${rules.map { it.second }.distinct().size}")

        val buckets = LinkedHashMap<String, MutableList<HighlightRuleManager.HighlightRule>>()
        val bucketOrder = mutableListOf<String>()
        val unmatched = mutableListOf<String>()
        // 规则正文签名的首次归属分组：**同 scope 且正文完全相同**的重复定义合并成一组
        // （用户不需要看到两遍一样的词），正文不同的（哪怕同 scope，如帧率 vs 内置属性）保持分开
        val signatureToGroup = HashMap<String, String>()
        SLOTS.forEach { slotDef ->
            val body = instanceBodies.firstOrNull { it.first == (slotDef.scope to slotDef.instance) }?.second
            if (body == null) {
                unmatched.add("${slotDef.displayGroup}(${slotDef.scope}#${slotDef.instance})")
                return@forEach
            }
            val signature = slotDef.categoryId + "\u0000" + slotDef.scope + "\u0000" + body
            val groupLabel = signatureToGroup[signature] ?: slotDef.displayGroup
            signatureToGroup[signature] = groupLabel
            val key = slotDef.categoryId + "\u0000" + groupLabel
            val bucket = buckets.getOrPut(key) {
                bucketOrder.add(key)
                mutableListOf()
            }
            val keywords = if (slotDef.regexOnly) null else literalAlternates(body)
            if (keywords != null) {
                keywords.forEach { keyword ->
                    if (bucket.none { it.pattern == keyword && it.sourceInstance == slotDef.instance }) {
                        bucket.add(
                            HighlightRuleManager.HighlightRule(
                                keyword,
                                HighlightRuleManager.RuleType.KEYWORD,
                                source = body,
                                displayGroup = groupLabel,
                                sourceInstance = slotDef.instance
                            )
                        )
                    }
                }
            } else {
                bucket.add(
                    HighlightRuleManager.HighlightRule(
                        body,
                        HighlightRuleManager.RuleType.REGEX,
                        source = body,
                        displayGroup = groupLabel,
                        sourceInstance = slotDef.instance
                    )
                )
            }
        }

        val result = bucketOrder.mapNotNull { bucketKey ->
            val parts = bucketKey.split("\u0000", limit = 2)
            val bucketRules = buckets.getValue(bucketKey)
            if (bucketRules.isEmpty()) return@mapNotNull null
            HighlightRuleManager.HighlightRuleCategory(parts[0], bucketRules, parts[1])
        }
        // 实际存在的分组写入缓存：同正文的重复定义已合并，所以这里的分组数 < 槽位数
        groupNamesByCategory = result.groupBy({ it.categoryId }, { it.listName })
        val report = parityReport(grammarJson, result) +
            if (unmatched.isEmpty()) "" else "\n未匹配槽位(${unmatched.size}): " + unmatched.joinToString(", ")
        Log.d(TAG, report)
        try {
            java.io.File(RwmodPaths.cacheDir, "defaults_report.txt").writeText(report, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "写 defaults_report 失败", e)
        }
        return result
    }

    /**
     * 复刻对账：**每条内置带 scope 的规则都必须被复刻**（含重复定义），
     * 所以 `已复刻份数 == 内置规则数` 才算完全一致。
     *
     * 对账口径：内置每条规则由 `(scope, 该 scope 内第几条)` 唯一确定，槽位表也用同一把钥匙。
     * 因此这里直接比**钥匙集合**（不是去重后的计数）——去重会掩盖两类错误：
     * ① 两个槽位指向同一条内置规则（撞车，等于漏了一条）；② 某条内置规则没人认领。
     */
    fun parityReport(
        grammarJson: String,
        defaults: List<HighlightRuleManager.HighlightRuleCategory>
    ): String {
        val builtin = mutableListOf<Pair<String, String>>()
        try {
            collectRules(JSONObject(grammarJson).optJSONArray("patterns"), builtin)
        } catch (e: Exception) {
            return "parity: 解析失败 ${e.message}"
        }
        val builtinKeys = instanceKeys(builtin)
        val slotKeys = SLOTS.map { it.scope to it.instance }
        val dupSlots = slotKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val missing = builtinKeys - slotKeys.toSet()
        val extra = slotKeys.toSet() - builtinKeys.toSet()
        val covered = defaults.flatMap { it.rules }
            .filter { it.source.isNotBlank() }
            .map { slotScopeOf(it.displayGroup) to it.sourceInstance }
            .toSet()
        val kw = defaults.sumOf { list -> list.rules.count { it.type == HighlightRuleManager.RuleType.KEYWORD } }
        val rx = defaults.sumOf { list -> list.rules.count { it.type == HighlightRuleManager.RuleType.REGEX } }
        val groups = defaults.size
        val ok = dupSlots.isEmpty() && missing.isEmpty() && extra.isEmpty()
        val rated = if (ok) "完全一致" else "不一致(漏 ${missing.size}/撞车 ${dupSlots.size}/多余 ${extra.size})"
        val detail = buildString {
            append("parity: 内置规则=${builtin.size}, 已复刻=${covered.size}, 槽位=${slotCount}, 分组(有规则的)=$groups, ")
            append("关键字=$kw, 正则=$rx → $rated")
            if (missing.isNotEmpty()) append("\n漏复刻: " + missing.joinToString(", ") { "${it.first}#${it.second}" })
            if (dupSlots.isNotEmpty()) append("\n槽位撞车: " + dupSlots.joinToString(", ") { "${it.first}#${it.second}" })
            if (extra.isNotEmpty()) append("\n多余槽位: " + extra.joinToString(", ") { "${it.first}#${it.second}" })
            // 每个 scope 的内置条数：排查「少复刻」时用来对账（应与语法文件逐 scope 统计一致）
            append("\n内置 scope 分布(${builtin.groupingBy { it.first }.eachCount().size} 个): ")
            append(builtin.groupingBy { it.first }.eachCount().entries.joinToString(", ") { "${it.key}=${it.value}" })
            // 逐条对账：内置每条规则，与复刻出来的规则按 (scope, instance) 配对，比对复刻有没有丢规则
            append("\n逐条对账(内置 vs 复刻)：")
            val replicaByKey = defaults.flatMap { it.rules }
                .filter { it.source.isNotBlank() }
                .associateBy { slotScopeOf(it.displayGroup) to it.sourceInstance }
            var unclaimed = 0
            var bodyDiff = 0
            builtin.forEachIndexed { index, rule ->
                val key = builtinKeys[index]
                val owner = SLOTS.firstOrNull { it.scope == key.first && it.instance == key.second }
                val replica = replicaByKey[key]
                if (owner == null || replica == null) unclaimed++
                else if (replica.source != rule.second) bodyDiff++
            }
            append("\n逐条对账小结: 内置=${builtin.size} 复刻=${replicaByKey.size} 无人认领=$unclaimed 原文不一致=$bodyDiff")
            if (unclaimed > 0 || bodyDiff > 0) {
                builtin.forEachIndexed { index, rule ->
                    val key = builtinKeys[index]
                    if (replicaByKey[key] == null || SLOTS.none { it.scope == key.first && it.instance == key.second }) {
                        append("\n  ⚠ ${key.first}#${key.second} len=${rule.second.length}")
                    }
                }
            }
        }
        return detail
    }

    /** 把 `(scope, body)` 序列换算成 `(scope, 该 scope 内第几条)` 的钥匙集合 */
    private fun instanceKeys(rules: List<Pair<String, String>>): List<Pair<String, Int>> {
        val seq = HashMap<String, Int>()
        return rules.map { (scope, _) ->
            val index = seq.getOrDefault(scope, 0)
            seq[scope] = index + 1
            scope to index
        }
    }

    /**
     * 递归收集所有带 scope 的「配色片段」的 (scope, body)，保持语法顺序。
     *
     * 口径：一条规则的规则级 `name`、以及它 `captures`/`beginCaptures`/`endCaptures` 里**每个具名捕获的
     * `name`**，都算一条内置配色规则，body 用所属规则的 `match`/`begin` 原文
     * （节括号 `punctuation.definition.section.begin.ini`、节后缀 `entity.name.section.suffix.ini`、
     *  `@memory` 的 `keyword.control.shortcut.ini`、键名 `keyword.key.ini`、冒号 `punctuation.separator.ini`
     *  这些都是**只在 captures / beginCaptures 里**出现的；早期只读规则级 name 会把它们整批漏掉 ——
     *  界面上就成了「内置语法里没有这个类型」，而那正是用户报的「空的类别」）。
     */
    private fun collectRules(patterns: JSONArray?, out: MutableList<Pair<String, String>>) {
        if (patterns == null) return
        for (i in 0 until patterns.length()) {
            val rule = patterns.optJSONObject(i) ?: continue
            val body = rule.optString("match", "").ifEmpty { rule.optString("begin", "") }
            val own = rule.optString("name", "")
            if (own.isNotEmpty() && body.isNotEmpty()) out.add(own to body)
            for (key in CAPTURE_KEYS) {
                val captures = rule.optJSONObject(key) ?: continue
                val keys = captures.keys()
                while (keys.hasNext()) {
                    val entry = captures.optJSONObject(keys.next()) ?: continue
                    val cap = entry.optString("name", "")
                    if (cap.isNotEmpty() && body.isNotEmpty()) out.add(cap to body)
                    collectRules(entry.optJSONArray("patterns"), out)
                }
            }
            collectRules(rule.optJSONArray("patterns"), out)
        }
    }

    /** 语法里所有可能给捕获命名的字段 */
    private val CAPTURE_KEYS = arrayOf("captures", "beginCaptures", "endCaptures")

    /**
     * 抽取「字面量关键词」；抽不出来（= 该按正则整条搬）时返回 null。
     * 交替项必须不含转义与元字符且括号成对（见 [isPlainKeyword]）。
     */
    internal fun literalAlternates(body: String): List<String>? {
        val inner = when {
            body.startsWith("\\b(") && body.endsWith(")\\b") -> body.substring(3, body.length - 3)
            body.startsWith("(") && body.endsWith(")") -> body.substring(1, body.length - 1)
            else -> return null
        }
        if (inner.isEmpty()) return null
        val pieces = splitUnescaped(inner).map { it.trim() }
        if (pieces.isEmpty()) return null
        if (pieces.any { !isPlainKeyword(it) }) return null
        return pieces.distinct()
    }

    /** 是否为「普通关键字」：不含转义与正则元字符，且括号成对 */
    private fun isPlainKeyword(piece: String): Boolean {
        if (piece.isEmpty()) return false
        if (piece.any { it in FORBIDDEN_CHARS }) return false
        var depth = 0
        for (ch in piece) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    /** 关键字里不允许出现的字符（出现即说明是正则片段） */
    private const val FORBIDDEN_CHARS = "\\[](){}.*+?|$^<>=!"

    /** 按**未转义**的 `|` 切分交替（`\|` 不能被切碎） */
    private fun splitUnescaped(inner: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var escaped = false
        for (ch in inner) {
            when {
                escaped -> {
                    sb.append(ch)
                    escaped = false
                }
                ch == '\\' -> {
                    sb.append(ch)
                    escaped = true
                }
                ch == '|' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }
}
