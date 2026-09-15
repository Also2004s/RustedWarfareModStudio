package com.rwmodstudio.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 自定义高亮规则（开发者模式→规则）：关键字/正则转正则片段 + 合并进内置 grammar 的结构与优先级。
 *
 * 注意：本仓库的 JVM 单测跑在 android.jar stub 上，`org.json` 的所有方法都是空实现，
 * 因此这里刻意不解析 JSON，改用文本断言（生产代码的 JSON 解析由真机构建/运行验证）。
 */
class HighlightRuleManagerTest {

    private fun loadBaseGrammar(): String {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/textmate/ini/ini.tmLanguage.json"),
            File(System.getProperty("user.dir"), "src/main/assets/textmate/ini/ini.tmLanguage.json"),
            File("app/src/main/assets/textmate/ini/ini.tmLanguage.json"),
            File("src/main/assets/textmate/ini/ini.tmLanguage.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("ini.tmLanguage.json not found. user.dir=${System.getProperty("user.dir")}")
        return file.readText()
    }

    /**
     * 顶层 patterns 数组里的规则条数。
     * 逐字符扫描并跳过字符串字面量（规则源码里会出现 `{`/`}`/`[`/`]`），
     * 否则会把正则里的括号算成结构括号。
     */
    private fun countPatterns(grammarJson: String): Int {
        val start = grammarJson.indexOf("\"patterns\"")
        require(start >= 0) { "no patterns in grammar" }
        val open = grammarJson.indexOf('[', start)
        require(open >= 0) { "no patterns array" }
        var depth = 0
        var count = 0
        var inString = false
        var escaped = false
        var i = open
        while (i < grammarJson.length) {
            val c = grammarJson[i]
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
                        if (depth == 0) return count
                    }
                    '}' -> if (depth == 1) count++
                }
            }
            i++
        }
        throw IllegalStateException("unterminated patterns array")
    }

    @Test
    fun keywordUsesAsciiBoundariesNotUnicodeWordBoundary() {
        // 中文关键字：不能用 \b（汉字也算词字符，\b 会判死），必须用 ASCII 边界
        val cn = HighlightRuleManager.keywordToRegexPiece("自身有活动的路径点")
        assertTrue(cn.contains("自身有活动的路径点"), "关键字本体应保留: $cn")
        assertTrue(!cn.contains("\\b"), "中文关键字不应使用 \\b: $cn")
        assertTrue(cn.contains("(?<![A-Za-z0-9_])"), "应带左侧 ASCII 边界: $cn")
        assertTrue(cn.contains("(?![A-Za-z0-9_])"), "应带右侧 ASCII 边界: $cn")

        // 语义验证：中文关键字在真实上下文里命中，且不会被 ASCII 标识符黏住
        val cnRegex = Regex(cn)
        assertTrue(cnRegex.containsMatchIn("需要条件: if 自身有活动的路径点(type='移动')"), "带参调用应命中")
        assertTrue(cnRegex.containsMatchIn("自身有活动的路径点"), "裸关键字应命中")
        assertTrue(!cnRegex.containsMatchIn("自身有活动的路径点X"), "右侧紧跟 ASCII 字母不应命中")

        // ASCII 关键字：点号转义 + 两侧 ASCII 边界（notify 里的 if 不命中）
        val en = HighlightRuleManager.keywordToRegexPiece("self.hasActiveWaypoint")
        assertEquals("(?<![A-Za-z0-9_])self\\.hasActiveWaypoint(?![A-Za-z0-9_])", en)
        val ifRegex = Regex(HighlightRuleManager.keywordToRegexPiece("if"))
        assertTrue(ifRegex.containsMatchIn("if 自身在移动"))
        assertTrue(!ifRegex.containsMatchIn("notify"), "不应命中 notify 里的 if")

        // 正则元字符关键字整体转义
        val meta = HighlightRuleManager.keywordToRegexPiece("a+b(c)|d")
        assertTrue(meta.contains("a\\+b\\(c\\)\\|d"), "元字符应被转义: $meta")
    }

    @Test
    fun ruleObjectJsonEscapesUnsafeCharacters() {
        val obj = HighlightRuleManager.buildRuleObjectJson("a\"b\\c\nd", "support.function.ini")
        assertTrue(obj.contains("\\\"b\\\\c\\nd"), "引号/反斜杠/换行应被转义: $obj")
        assertTrue(obj.contains("\"name\":\"support.function.ini\""), obj)
    }

    @Test
    fun categoryPatternsGroupRulesPerCategoryInGivenOrder() {
        val patterns = HighlightRuleManager.buildCategoryPatterns(
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "value",
                    listOf(
                        HighlightRuleManager.HighlightRule("自身有活动的路径点"),
                        HighlightRuleManager.HighlightRule("关掉的词", enabled = false),
                        HighlightRuleManager.HighlightRule("self\\.x\\(\\)", HighlightRuleManager.RuleType.REGEX)
                    )
                ),
                HighlightRuleManager.HighlightRuleCategory(
                    "function",
                    listOf(HighlightRuleManager.HighlightRule("平方根"))
                )
            )
        )
        assertEquals(2, patterns.size, "两个类别 → 两条 grammar 规则")
        assertEquals("string.unquoted.value.ini", patterns[0].second)
        assertEquals("support.function.ini", patterns[1].second)
        assertTrue(patterns[0].first.contains("自身有活动的路径点"), patterns[0].first)
        assertTrue(patterns[0].first.contains("self\\.x\\(\\)"), "正则规则原样保留: ${patterns[0].first}")
        assertTrue(!patterns[0].first.contains("关掉的词"), "禁用规则不应进语法: ${patterns[0].first}")
        assertTrue(patterns[1].first.contains("平方根"), patterns[1].first)
    }

    @Test
    fun unknownCategoryIsIgnored() {
        val patterns = HighlightRuleManager.buildCategoryPatterns(
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "not-a-real-category",
                    listOf(HighlightRuleManager.HighlightRule("任意词"))
                )
            )
        )
        assertTrue(patterns.isEmpty(), "未知类别应被忽略，不能让 grammar 崩掉")
    }

    @Test
    fun sourceRulesAreReplacedInPlaceKeepingOrder() {
        // 默认复刻出来的规则都带 source（= 内置原文）：合并时必须**原位替换**，
        // 规则条数与顺序都不变 —— 这是「默认规则与内置逐 token 一致」的机制保证。
        val base = loadBaseGrammar()
        val baseCount = countPatterns(base)
        // 挑一条**嵌套在键值块里**的规则（逗号规则只在键值区出现一次；比较运算符在顶层与键值区各一份）
        val nestedSource = "[,]"

        val (merged, replaced, added) = HighlightRuleManager.buildMergedGrammarRaw(
            base,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "comma",
                    listOf(
                        // 逗号规则在语法里定义了两处（键值区 + 顶层），两份都要指定才能都替换
                        HighlightRuleManager.HighlightRule("我的逗号", source = nestedSource, sourceInstance = 0),
                        HighlightRuleManager.HighlightRule("我的逗号", source = nestedSource, sourceInstance = 1)
                    )
                )
            )
        )

        assertEquals(2, replaced, "键值块内与顶层的同名内置规则都应被命中（逗号规则在语法里定义了两处）")
        assertEquals(0, added, "带 source 的规则不应新增规则对象")
        // 规则体逐条对齐：条数不变、且**只有被改写的那条**不同（顺序与优先级因此保持不变）
        val baseBodies = HighlightRuleManager.debugExtractBodies(base)
        val mergedBodies = HighlightRuleManager.debugExtractBodies(merged)
        val firstDiff = baseBodies.zip(mergedBodies).indexOfFirst { (a, b) -> a != b }
        val detail = "base=${baseBodies.size} merged=${mergedBodies.size} firstDiff=$firstDiff " +
            "baseAt=${baseBodies.getOrNull(firstDiff)?.take(60)} mergedAt=${mergedBodies.getOrNull(firstDiff)?.take(60)}"
        assertEquals(baseBodies.size, mergedBodies.size, "规则体条数应保持不变 | $detail")
        val changed = baseBodies.zip(mergedBodies).count { (a, b) -> a != b }
        assertEquals(2, changed, "只应有 2 条规则被改写（逗号的两份），其余逐条一致")
        assertTrue(merged.count { it == '我' } >= 2, "两处都应替换成新内容")
        assertTrue(!merged.contains(nestedSource), "原内置逗号规则应被替换掉")
        assertTrue(merged.contains("(#|;).*"), "其它内置规则必须原样保留")
        // 仍在键值块内部：第一次替换出现在键值 begin 之后（嵌套定义先出现）
        val kvBegin = merged.indexOf("\"begin\": \"^[ ")
        val position = merged.indexOf("我的逗号")
        assertTrue(kvBegin in 1 until position, "替换应发生在键值块内部（kvBegin=$kvBegin pos=$position）")
    }

    @Test
    fun removedSourceRuleDropsNestedBuiltinRule() {
        val base = loadBaseGrammar()
        // 该内置组被清空 → 内置规则应从合并语法里移除（用户主动不要它）。
        // 逗号规则在语法里定义了两次（键值块内 + 顶层），两处都要清掉。
        val (merged, replaced, added) = HighlightRuleManager.buildMergedGrammarRaw(
            base,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "comma",
                    listOf(
                        HighlightRuleManager.HighlightRule("", enabled = false, source = "[,]"),
                        HighlightRuleManager.HighlightRule("", enabled = false, source = "[,]")
                    )
                )
            )
        )
        assertEquals(2, replaced, "两处内置规则都应被移除")
        assertEquals(0, added)
        assertTrue(!merged.contains("[,]"), "原内置逗号规则应消失")
        assertTrue(merged.contains("(#|;).*"), "其它内置规则必须保留")
    }

    @Test
    fun handWrittenRulesGoToFrontAndKeepBuiltinRules() {
        val base = loadBaseGrammar()
        val baseCount = countPatterns(base)

        val (merged, replaced, added) = HighlightRuleManager.buildMergedGrammarRaw(
            base,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "value",
                    listOf(HighlightRuleManager.HighlightRule("自身有活动的路径点"))
                ),
                HighlightRuleManager.HighlightRuleCategory(
                    "function",
                    listOf(HighlightRuleManager.HighlightRule("self\\.\\w+\\(\\)", HighlightRuleManager.RuleType.REGEX))
                )
            )
        )

        assertEquals(0, replaced, "没有 source 的规则不是替换")
        assertEquals(2, added, "两个类别 → 两条新规则")
        assertEquals(baseCount + 2, countPatterns(merged), "内置规则全部保留")
        assertTrue(merged.contains("\"scopeName\": \"source.ini\""), "scopeName 必须保持 source.ini")

        // 手写规则插在最前 → 优先级最高
        val userIdx = merged.indexOf("自身有活动的路径点")
        val builtinIdx = merged.indexOf("(#|;).*")
        assertTrue(builtinIdx > 0, "应能找到内置第一条规则")
        assertTrue(userIdx in 1 until builtinIdx, "手写规则必须排在内置规则之前（user=$userIdx builtin=$builtinIdx）")
        assertTrue(merged.contains("用户自定义高亮规则"), "应带可读的规则注释")
        assertTrue(merged.contains("\"name\":\"support.function.ini\""), "正则规则应归到函数类别 scope")
    }

    @Test
    fun disabledAndBlankRulesReturnBaseUnchanged() {
        val base = loadBaseGrammar()
        val merged = HighlightRuleManager.buildMergedGrammar(
            base,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "value",
                    listOf(
                        HighlightRuleManager.HighlightRule("关掉的词", enabled = false),
                        HighlightRuleManager.HighlightRule("   ")
                    )
                )
            )
        )
        assertEquals(base, merged, "全部规则被禁用/空时应原样返回内置 grammar")
    }

    @Test
    fun emptyRulesReturnBaseUnchanged() {
        val base = loadBaseGrammar()
        assertEquals(base, HighlightRuleManager.buildMergedGrammar(base, emptyList()))
    }

    @Test
    fun topLevelRuleCountIgnoresNestedCaptures() {
        // 顶层 2 条规则 + 嵌套 captures 里也有 match —— 扫描器必须只数顶层的 2 条
        // （这两处误算过 42→65、42→81，导致合并自检误报并静默回退内置语法）
        val mini = """
            {
              "name": "ini",
              "patterns": [
                { "match": "(#|;).*$", "name": "comment.line.ini" },
                {
                  "begin": "^[ \t]*([^:=#\[\n]+)[ \t]*(:)",
                  "patterns": [
                    { "match": "\\.", "captures": { "1": { "name": "a" }, "2": { "name": "b" } } }
                  ]
                }
              ]
            }
        """.trimIndent()
        // 调试信息：定位扫描器行为（失败时输出关键子串与计数）
        val debug = "mini.length=${mini.length}, hasPatternsKey=${mini.contains("\"patterns\"")}, " +
            "count=${HighlightRuleManager.topLevelRuleCount(mini)}, " +
            "idxPatterns=${mini.indexOf("\"patterns\"")}, idxBracket=${mini.indexOf('[', mini.indexOf("\"patterns\""))}, " +
            "head=${mini.substring(0, 60)}, snippet=${mini.substring(50, 120)}"
        assertEquals(2, HighlightRuleManager.topLevelRuleCount(mini), "只应数顶层规则的 match/begin | $debug")
    }

    @Test
    fun rawMergeInsertsRulesAfterPatternsBracket() {
        val mini = """{"name":"ini","patterns":[{"match":"a","name":"x"}]}"""
        val (merged, replaced, added) = HighlightRuleManager.buildMergedGrammarRaw(
            mini,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "function",
                    listOf(HighlightRuleManager.HighlightRule("平方根"))
                )
            )
        )
        assertEquals(0, replaced)
        assertEquals(1, added)
        assertTrue(merged.contains("\"name\":\"x\""), "内置规则必须保留")
        assertTrue(merged.contains("平方根"), "用户规则必须插入")
        val userIdx = merged.indexOf("平方根")
        val builtinIdx = merged.indexOf("\"name\":\"x\"")
        assertTrue(userIdx in 1 until builtinIdx, "手写规则必须在内置规则之前")
        assertEquals(2, countPatterns(merged), "插入后顶层规则数 = 1 内置 + 1 用户")
    }

    @Test
    fun sourceReplacementWorksOnMinimalGrammar() {
        val mini = """{"name":"ini","patterns":[{"match":"a","name":"x"},{"match":"b","name":"y"}]}"""
        val (merged, replaced, added) = HighlightRuleManager.buildMergedGrammarRaw(
            mini,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "function",
                    listOf(HighlightRuleManager.HighlightRule("c", source = "b"))
                )
            )
        )
        assertEquals(1, replaced, "最小语法里也应命中 source | merged=$merged")
        assertEquals(0, added)
        assertEquals(2, countPatterns(merged), "原位替换不改变条数")
        assertTrue(merged.contains("c"), "替换内容应就位: $merged")
        assertTrue(!merged.contains("\"match\":\"b\""), "原模板（match=b）应被替换掉")
        assertTrue(merged.indexOf("\"match\":\"a\"") < merged.indexOf("\"name\":\"y\""), "顺序必须保持")
    }

    @Test
    fun extractSourceFromRealGrammarRules() {
        // 探针：确认「从语法里还原规则原文」覆盖到嵌套在键值块里的那 31 条规则
        // （内置 42 条规则 = 11 条顶层 + 31 条嵌在 key-value 的 begin/end 里；
        //   只扫顶层会让键值区那些复刻规则既替换不了内置规则、也不生效）
        val base = loadBaseGrammar()
        val probes = listOf(
            "(<=|>=|==|!=|<|>)",
            "(?<![<>=!])=(?!=)",
            "([+\\-*/%$]|\\*\\*)",
            "[,]",
            "(#|;).*$",
            "\\b(true|false|真|假)\\b",
            "\\b(自身有标签|自身有资源"
        )
        val rawRules = HighlightRuleManager.debugSplitRules(base)
        val extracted = HighlightRuleManager.debugExtractBodies(base)
        // 顶层 42 条（键值块是其中一条，内部还嵌着 31 条规则）；
        // 全文件扫描能看见嵌套的 match/begin，所以总数应明显多于顶层
        assertTrue(rawRules.size >= 40, "顶层规则应约 42 条，实际 ${rawRules.size}")
        assertTrue(extracted.size >= 70, "全文件扫描应看见嵌套规则，实际 ${extracted.size}")
        probes.forEach { probe ->
            assertTrue(
                extracted.any { it.contains(probe) },
                "全文件扫描里找不到「$probe」（共 ${extracted.size} 条）"
            )
        }
    }

    @Test
    fun defaultGroupingCoversEveryScopedBuiltinRule() {
        // 对账哨兵：内置「带 scope 的配色片段」必须**逐条**被复刻（一条对一条，才能连 captures/
        // beginCaptures 一起保留）。这里离线模拟设备端 collectRules 的口径，断言覆盖率。
        val grammar = loadBaseGrammar()
        val scopes = scopedRuleNames(grammar)
        assertTrue(scopes.size in 80..100, "内置带 scope 规则应约 90 条，实际 ${scopes.size}")
        val known = HighlightRulesDefaults.SCHEMA_SCOPES
        // 允许未登记的只有语法文件自身的 scopeName（`"name": "ini"`），它不是配色规则
        val allowedSkips = setOf("ini")
        val skipped = scopes.filter { it !in known }.distinct()
        assertTrue(
            skipped.all { it in allowedSkips },
            "出现了预期外的未登记 scope（这些规则会被丢掉）：$skipped"
        )
        // 每个已登记 scope 的分组数量（`SCOPE_GROUPS` 里该 scope 的标签数）必须够用；
        // 不够时 parseBuiltinRules 会打「分组不足，规则被丢弃」告警。这里只断言关键 scope 的量级。
        val counts = scopes.groupingBy { it }.eachCount()
        assertTrue(
            (counts["constant.language.boolean.ini"] ?: 0) in 8..12,
            "布尔 scope 规则数异常：${counts["constant.language.boolean.ini"]}"
        )
        assertTrue(
            (counts["string.unquoted.value.ini"] ?: 0) in 1..12,
            "值高亮 scope 规则数异常：${counts["string.unquoted.value.ini"]}"
        )
    }

    /**
     * 取语法里**每条带 scope 的配色片段**的 scope 名，口径与设备端 `HighlightRulesDefaults.collectRules` 一致：
     * 一个 JSON 对象的 `name`（规则级），以及它 `captures` / `beginCaptures` / `endCaptures` 里每个具名捕获的
     * `name`，都算一条规则——前提是所属规则有 `match`/`begin` 正文。
     * （节括号、节后缀、`@memory` 快捷键、键名/冒号这些**只**出现在 captures 里，漏掉就等于漏复刻内置语法。）
     *
     * 语法文件是逐行一个键的格式，所以按行扫 + 一个花括号栈就够了，不需要完整的 JSON 解析器。
     */
    private fun scopedRuleNames(grammarJson: String): List<String> {
        val out = mutableListOf<String>()
        val nameAt = mutableMapOf<Int, String>()
        val bodyAt = mutableMapOf<Int, String>()
        val captureBoxAt = mutableMapOf<Int, Boolean>()
        var inCaptureBox = false
        var depth = 0
        var lastKey = ""
        var i = 0

        while (i < grammarJson.length) {
            when (val c = grammarJson[i]) {
                '{' -> {
                    depth++
                    captureBoxAt[depth] = captureBoxAt.getOrDefault(depth - 1, false) || inCaptureBox
                    inCaptureBox = false
                    i++
                }
                '}' -> {
                    val name = nameAt.remove(depth).orEmpty()
                    val body = bodyAt.remove(depth).orEmpty()
                    val inCapture = captureBoxAt.remove(depth) ?: false
                    // 规则级 name：本对象自己有 match/begin 才是一条规则
                    if (name.isNotEmpty() && body.isNotEmpty() && !inCapture) out.add(name)
                    // 具名捕获（captures/beginCaptures/endCaptures 里的 name）：正文继承外层规则
                    if (name.isNotEmpty() && inCapture) {
                        val ownerBody = (depth - 1 downTo 1).firstNotNullOfOrNull { bodyAt[it] }
                        if (!ownerBody.isNullOrEmpty()) out.add(name)
                    }
                    depth--
                    i++
                }
                '"' -> {
                    // 读一个字符串字面量（语法文件里的正则含 `{` `}`，绝不能当成结构括号）
                    val sb = StringBuilder()
                    i++
                    while (i < grammarJson.length && grammarJson[i] != '"') {
                        if (grammarJson[i] == '\\' && i + 1 < grammarJson.length) {
                            sb.append(grammarJson[i]).append(grammarJson[i + 1])
                            i += 2
                        } else {
                            sb.append(grammarJson[i])
                            i++
                        }
                    }
                    i++ // 跳过收尾引号
                    var j = i
                    while (j < grammarJson.length && grammarJson[j] in " \t\r\n") j++
                    val isKey = j < grammarJson.length && grammarJson[j] == ':'
                    val value = sb.toString()
                    if (isKey) {
                        lastKey = value
                        if (value == "captures" || value == "beginCaptures" || value == "endCaptures") {
                            inCaptureBox = true
                        }
                    } else {
                        when (lastKey) {
                            "name" -> nameAt[depth] = value
                            "match", "begin" -> bodyAt[depth] = value
                        }
                        lastKey = ""
                    }
                }
                ':' -> {
                    i++
                }
                else -> {
                    i++
                }
            }
        }
        return out
    }


    private companion object {
        /** 语法文件每行一个键值对：`"name": "xxx"` */
        val KEY_LINE = Regex("^\\s*\"([A-Za-z]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    }

    @Test
    fun defaultSlotsMatchGrammarRuleCounts() {
        // 槽位序号（该 scope 内第几条规则）写错会漏复刻内置规则。
        // 这里离线按语法统计每个 scope 的规则数，断言槽位不会越界、且槽位总数 = 不同规则总数。
        val grammar = loadBaseGrammar()
        val scopes = scopedRuleNames(grammar)
        val counts = scopes.groupingBy { it }.eachCount()
        val slots = HighlightRulesDefaults.debugSlots()
        assertEquals(HighlightRulesDefaults.slotCount, slots.size, "槽位数应等于 slotCount")
        slots.forEach { s ->
            val n = counts[s.scope]
            assertNotNull(n, "槽位「${s.displayGroup}」引用了语法里不存在的 scope: ${s.scope}")
            assertTrue(
                s.instance < (n ?: 0),
                "槽位「${s.displayGroup}」要 ${s.scope} 的第 ${s.instance + 1} 条规则，但语法里只有 $n 条"
            )
            assertTrue(s.categoryId.isNotBlank())
        }
        // 槽位覆盖的 scope 必须全部存在
        HighlightRulesDefaults.SCHEMA_SCOPES.forEach { scope ->
            assertNotNull(counts[scope], "SCHEMA_SCOPES 里登记了语法中不存在的 scope: $scope")
        }
    }

    @Test
    fun slotsClaimEveryScopedBuiltinRuleExactlyOnce() {
        // 对账口径：内置每条带 scope 的规则由「它自己的 scope + 它是该 scope 内第几条」唯一确定。
        // 槽位必须与这个钥匙集合**一一对应**——撞车（两个槽位指同一条）等于漏复刻，
        // 曾经 节后缀 槽位写错 scope 又恰好与 节名 撞在同一把钥匙上，被 distinct() 掩盖成「完全一致」。
        val grammar = loadBaseGrammar()
        val builtin = scopedRuleNames(grammar)
        val seq = HashMap<String, Int>()
        val keys = builtin.map { scope ->
            val i = seq.getOrDefault(scope, 0)
            seq[scope] = i + 1
            scope to i
        }
        val slotKeys = HighlightRulesDefaults.debugSlots().map { it.scope to it.instance }
        val duplicates = slotKeys.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "槽位撞车（两个槽位指向同一条内置规则）: $duplicates")
        val missing = keys.toSet() - slotKeys.toSet()
        assertTrue(missing.isEmpty(), "有内置规则没被任何槽位认领（漏复刻）: ${missing.map { "${it.first}#${it.second}" }}")
        val extra = slotKeys.toSet() - keys.toSet()
        assertTrue(extra.isEmpty(), "槽位指向语法里不存在的规则: ${extra.map { "${it.first}#${it.second}" }}")
        assertEquals(HighlightRulesDefaults.slotCount, slotKeys.size)
    }

    @Test
    fun regexGroupsProduceRegexRules() {
        // 「纯正则」分组的原文必须抽不出字面量 → 会整条作为正则规则搬过去
        // （十六进制色值、运算符、注释、引号、括号、点访问符等，用户需要能看到并改它们）
        val regexBodies = listOf(
            "#([A-Fa-f0-9]{8}|[A-Fa-f0-9]{6})",
            "(<=|>=|==|!=|<|>)",
            "(?<![<>=!])=(?!=)",
            "([+\\-*/%$]|\\*\\*)",
            "[\\(\\)]",
            "\\.",
            "\"[^\"\\n]*\"",
            "'[^'\\n]*'",
            "(#|;).*$",
            "(ROOT|CUSTOM|SHARED|CORE|SHADOW):",
            "'{2,}"
        )
        regexBodies.forEach { body ->
            val keywords = HighlightRulesDefaults.literalAlternates(body)
            assertTrue(
                keywords == null,
                "「$body」不该被当成字面量关键词，实际抽到: $keywords"
            )
            Regex(body) // 能编译
        }
    }

    @Test
    fun keywordGroupsStillExtractKeywords() {
        // 反向保证：真正的关键字交替仍要能抽出来（否则默认规则会退化成一条巨大正则）
        val cases = mapOf(
            "\\b(true|false|真|假)\\b" to listOf("true", "false", "真", "假"),
            "\\b(if|and|not|or)\\b" to listOf("if", "and", "not", "or"),
            "\\b(自身资源)\\b" to listOf("自身资源")
        )
        cases.forEach { (body, expected) ->
            assertEquals(expected, HighlightRulesDefaults.literalAlternates(body), "原文: $body")
        }
    }

    @Test
    fun categoriesMatchCustomHighlightTypesExactly() {
        // 「规则」页的类别必须与「自定义高亮」的类型**完全同一套**：
        // 自定义高亮的类型列表 = ["ui","plainText"] + targetScopes.keys（见 DarkTokenColorDialog）。
        // 历史 bug：规则页自己手写了一套 43 个类别，拆出「音效常量/效果常量/生成常量/块注释/多行字符串」等
        // 自定义高亮里根本没有的名字，而那些 scope 在内置语法里出现 0 次 → 写了规则也不会有颜色变化。
        val expected = listOf("ui", "plainText") + DarkThemeColors.targetScopes.keys.toList()
        assertEquals(
            expected,
            HighlightRuleManager.availableCategories.map { it.id },
            "规则页类别必须与自定义高亮类型同集合、同顺序"
        )
    }

    @Test
    fun everyThemeColorFieldHasACategory() {
        // 反向校验：DarkThemeColors 里每个可调色的配色键都必须有对应类别，
        // 这样「自定义高亮」里能调的每一种颜色，规则页都能给它写规则。
        // ui（符号匹配高亮）与 plainText（未匹配内容的底色）不走 scope 着色，单独允许。
        val colorKeys = DarkThemeColors.labels.keys
        val categoryIds = HighlightRuleManager.availableCategories.map { it.id }.toSet()
        val missing = colorKeys.filter { it !in categoryIds }
        assertTrue(missing.isEmpty(), "主题配色键没有对应类别（规则页无法自定义）：$missing")
        // 反向：每个类别都必须有显示名，否则界面会退化成显示英文 id（曾经漏过 type/modifier）
        HighlightRuleManager.availableCategories.forEach { info ->
            assertTrue(
                DarkThemeColors.labels.containsKey(info.id),
                "类别 ${info.id} 没有中文显示名（labels 缺键）"
            )
        }
        // 每个配色键的 scope 集合都必须被覆盖
        val covered = HighlightRuleManager.categoryScopes.values.flatten().toSet()
        val noScope = colorKeys.filter { key ->
            val scopes = DarkThemeColors.targetScopes[key].orEmpty()
            scopes.isNotEmpty() && scopes.none { it in covered }
        }
        assertTrue(noScope.isEmpty(), "配色键的 scope 没有类别覆盖：$noScope")
    }

    @Test
    fun everyThemeColorableScopeHasACategory() {
        // 主题里能单独调色的每个 scope，规则页都必须有类别归属，且**类别与其 scope 集合必须一一对应**
        // （不能像以前那样用「至少被某个类别覆盖」糊过去）。
        assertEquals(
            DarkThemeColors.targetScopes,
            HighlightRuleManager.categoryScopes.filterKeys { it !in setOf("ui", "plainText") },
            "规则类别的 scope 集合必须与主题配色表完全一致"
        )
        // 不参与 scope 着色的两个类型：允许 scope 为空，但必须存在
        listOf("ui", "plainText").forEach { id ->
            assertTrue(HighlightRuleManager.categoryScopes[id]?.isEmpty() == true, "$id 应为空 scope 集合")
        }
        // 每个类别声明的 scope 都必须真的属于它（写规则才会变色）
        HighlightRuleManager.availableCategories.forEach { info ->
            val own = HighlightRuleManager.categoryScopes[info.id].orEmpty()
            if (own.isNotEmpty()) {
                assertTrue(
                    info.scope in own,
                    "类别 ${info.id} 的主 scope ${info.scope} 不在其 scope 集合里"
                )
            }
        }
    }

    @Test
    fun everyCategoryScopeResolvesBackToItself() {
        // 「写在这类别下的规则，颜色真的是这个类别管的吗？」——按主题首命中规则复算一遍。
        // 这条直接抓住「音效常量」式错位：某类别持有主题从不给它上色的 scope，
        // 用户写了规则也不会变色（历史上：节名归 section、点访问符归 decoration、类型归 type）。
        HighlightRuleManager.availableCategories.forEach { info ->
            val own = HighlightRuleManager.categoryScopes[info.id].orEmpty()
            own.forEach { scope ->
                assertEquals(
                    info.id,
                    HighlightRuleManager.themeCategoryOfScope(scope),
                    "类别 ${info.id} 持有 scope「$scope」，但主题把它归给了别的类别 → 在该类别下写规则不会生效"
                )
            }
        }
    }

    @Test
    fun everyGrammarScopeIsOwnedByExactlyOneResolvingCategory() {
        // 内置语法里出现的每个 scope，都必须有类别认领它、且类别解析回自己（否则那条内置配色就"没人管"）
        val grammarScopes = scopedRuleNames(loadBaseGrammar())
            .filter { it != "ini" } // 语法文件自身的 name 字段，不是配色
            .distinct()
        // 只保留真正持有 scope 的类别（ui / plainText 不参与 scope 着色）
        val owned: List<Pair<String, Set<String>>> = HighlightRuleManager.categoryScopes
            .filter { it.value.isNotEmpty() }
            .map { it.key to it.value }
        val unowned = grammarScopes.filter { scope -> owned.none { scope in it.second } }
        assertTrue(unowned.isEmpty(), "内置语法用了这些 scope，但没有任何类别认领（颜色改不了）：$unowned")
        // 认领它的那个类别，必须就是主题首命中的类别
        grammarScopes.forEach { scope ->
            val owner = owned.first { scope in it.second }.first
            assertEquals(
                owner,
                HighlightRuleManager.themeCategoryOfScope(scope),
                "scope「$scope」被 $owner 认领，但主题把它归给了别的类别"
            )
        }
    }

    @Test
    fun everyDefaultSlotLandsInAKnownCategory() {
        // 92 个复刻槽位的类别 id 必须都在白名单里（改类别 id 时容易漏改 SLOTS，写进去也没人显示）
        val ids = HighlightRuleManager.availableCategories.map { it.id }.toSet()
        val bad = HighlightRulesDefaults.debugSlots()
            .map { it.categoryId }
            .distinct()
            .filter { it !in ids }
        assertTrue(bad.isEmpty(), "复刻槽位引用了不存在的类别（这些规则界面上看不到）：$bad")
        // 反向：除了两个不参与 scope 着色的类型（UI 高亮 / 普通文本），每个类别都必须有 scope
        HighlightRuleManager.availableCategories.forEach { info ->
            if (info.id != "ui" && info.id != "plainText") {
                assertTrue(info.scope.isNotBlank(), "类别 ${info.id} 缺少 scope，写不了规则")
                assertTrue(
                    HighlightRuleManager.categoryScopes[info.id]?.isNotEmpty() == true,
                    "类别 ${info.id} 的 scope 集合为空"
                )
            }
        }
    }

    @Test
    fun generatedRulesSurviveNastyInput() {
        // 极端输入（含引号/反斜杠/换行/美元符）也要产出结构完整的 grammar
        val base = loadBaseGrammar()
        val nasty = listOf("a\"b", "c\\d", "e\nf", "g{h}i", "j" + '$' + "k")
        val (merged, _, added) = HighlightRuleManager.buildMergedGrammarRaw(
            base,
            listOf(
                HighlightRuleManager.HighlightRuleCategory(
                    "keyword",
                    nasty.map { HighlightRuleManager.HighlightRule(it, HighlightRuleManager.RuleType.REGEX) }
                )
            )
        )
        assertEquals(1, added)
        assertEquals(countPatterns(base) + added, countPatterns(merged))
        assertTrue(merged.contains("\"name\":\"keyword.key.ini\""), "类别 scope 应写入规则")
        // 换行必须被转义成 \n，不能真的断行（否则 grammar JSON 直接坏掉）
        assertTrue(merged.contains("e\\nf"), "换行应被转义")
    }
}
