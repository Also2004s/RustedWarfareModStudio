package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel
import com.rwmodstudio.feature.completion.LOGIC_SYNTAX_ITEMS

/**
 * 值名翻译：剥离参数括号取基名后交给翻译引擎（TranslationDict.getValueTranslation）翻译。
 * 带参函数名（如 select(bool, textA, textB)、distance(x1, y1, x2, y2)）在数据里是「参数签名」形式，
 * 直接翻译查不到（翻译库存的是基名 select/distance）。剥离 `(...)` 后取基名，翻译引擎命中返回中文
 * （select→选择、distance→距离）；未命中返回剥离后的基名，不硬编码中文。
 * 另做一次 `self.` 前缀剥离（self.readUnitMemory()→readUnitMemory）与大小写归一化兜底，
 * 使自我快捷方式与裸基名命中同一翻译（self.readUnitMemory() 与 readUnitMemory 同译为 读取单位内存）。
 * 仅用于补全 label 显示，不改数据 name（签名解析仍用原始带参名）。
 */
internal fun translateValueName(dict: TranslationDict?, name: String): String {
    val base = name.substringBefore('(').trim()
    if (base.isEmpty()) return name
    // 候选键：基名 → 剥离一次 self. 后的裸基名，各配 原样/小写 两个查询键，命中即返回中文
    val keys = linkedSetOf<String>()
    keys.add(base)
    val bare = base.removePrefix("self.")
    if (bare != base) keys.add(bare)
    for (key in keys) {
        val zh = dict?.getValueTranslation(key)?.takeIf { it.isNotBlank() && it != key }
        if (zh != null) return zh
        val lower = key.lowercase()
        if (lower != key) {
            val zhLower = dict?.getValueTranslation(lower)?.takeIf { it.isNotBlank() && it != lower }
            if (zhLower != null) return zhLower
        }
    }
    // 未命中翻译：返回剥离一次 self. 与参数括号后的裸基名（而非原始带 self./() 名），
    // 使 self.readUnitMemory() 与 readUnitMemory 归一化为同一键，配合 label 去重消除重复补全。
    return bare.ifEmpty { base }
}

/**
 * 是否为 LogicBoolean 类型：去空格后忽略大小写等于 "logicboolean"。
 * 覆盖 "LogicBoolean" 与 "logic boolean"（如 隐藏）。
 */
internal fun isLogicBooleanValueType(type: String): Boolean =
    type.replace(" ", "").equals("logicboolean", ignoreCase = true)

/** 是否为 LogicNumber/Logic 类型（归一化去空格小写后命中），如 透明度/帧/setHeight 等数字表达式属性 */
internal fun isLogicNumberValueType(type: String): Boolean =
    type.replace(" ", "").lowercase() in setOf("logicnumber", "logic")

/** 属性 type 是否为 dynamic resources（用逻辑设置资源/用逻辑添加资源 的 资源名=逻辑表达式 形态） */
internal fun isDynamicResourcesValueType(type: String): Boolean =
    type.replace(" ", "").lowercase() in setOf("dynamicresources")

/**
 * 属性 type 是否为「资源名=值」形态（资源名 LHS 补全后需紧跟 `=`）。
 * 覆盖 resources（价格/增加资源/根据AI难度添加资源）、price（流式造价/资源获取/资源需求）、
 * customPrice（提取资源）、resource（修正直接添加资源）与 dynamic resources（用逻辑设置/添加资源）。
 * 不含 resource ref / ResourceRef（`<resources:name>`）与 customResource（转换资源来源: 裸名）。
 */
internal fun isResourceNameValueType(type: String): Boolean =
    type.replace(" ", "").lowercase() in setOf(
        "resources", "price", "customprice", "resource", "dynamicresources"
    )

/** 属性 type 是否为 key value pairs（设置单位内存/更新单位内存 的 变量名=逻辑表达式 形态） */
internal fun isKeyValuePairsType(type: String): Boolean =
    type.replace(" ", "").equals("keyvaluepairs", ignoreCase = true)

/** 属性 type 是否为 fields values（设置单位状态 setUnitStats，LHS=单位属性名，支持 =/+=/-=，RHS=动态数学/逻辑数值表达式） */
internal fun isFieldsValuesValueType(type: String): Boolean =
    type.replace(" ", "").equals("fieldsvalues", ignoreCase = true)

/** 取值区域（`:` 后）中光标所在的最内层/最后一段（按不在括号内的顶层逗号切分） */
internal fun lastTopLevelSegment(region: String): String {
    var depth = 0
    var lastComma = -1
    for ((i, c) in region.withIndex()) {
        when (c) {
            '(', '{' -> depth++
            ')', '}' -> if (depth > 0) depth--
            ',', '，' -> if (depth == 0) lastComma = i
        }
    }
    return region.substring(lastComma + 1)
}

/** 取段内最后一个顶层（不在括号内）的 `=` 下标；无则 -1 */
internal fun topLevelEq(seg: String): Int {
    var depth = 0
    var eq = -1
    for ((i, c) in seg.withIndex()) {
        when (c) {
            '(', '{' -> depth++
            ')', '}' -> if (depth > 0) depth--
            '=' -> if (depth == 0) eq = i
        }
    }
    return eq
}

/**
 * 从完整当前行解析「光标所在赋值段的 RHS」。dynamic resources（用逻辑设置资源/用逻辑添加资源）
 * 以顶层 `=` 分隔（用逻辑设置资源:资源名=表达式）。返回顶层 `=` 之后到光标的分段；非 RHS 位置返回 null。
 */
internal fun currentSegmentRhs(line: String): String? {
    val colon = line.indexOf(':')
    if (colon < 0) return null
    val seg = lastTopLevelSegment(line.substring(colon + 1))
    val eq = topLevelEq(seg)
    if (eq >= 0) return seg.substring(eq + 1)
    return null
}

/** 内存变量声明的类型 → 逻辑目标类型（去 [i] 下标；未知返回 UNKNOWN 宽松处理） */
internal fun memoryTypeToLogicTarget(type: String): LogicTarget {
    val t = type.replace(" ", "").lowercase().substringBefore('[')
    return when {
        // 内存声明类型：unit / unit ref（去空格后 unitref）都表示「存的是单位」，逻辑表达式里作单位标记引用
        t == "unit" || t == "unitref" -> LogicTarget.UNIT_MARKER
        else -> logicTargetOfBaseType(t) ?: LogicTarget.UNKNOWN
    }
}

/**
 * 值片段是否已进入 dynamic resources 的 RHS：段内含顶层 `=`（用逻辑设置资源:资源名=表达式）。
 * 返回分隔符之后已输入片段；未进入（无 `=` 或仅 LHS）返回 null。
 */
internal fun dynamicRhsFilter(prefix: String): String? {
    val eq = prefix.indexOf('=')
    if (eq >= 0) return prefix.substring(eq + 1)
    return null
}

/**
 * 值片段是否含未闭合的 `%{` 插值（最后一个 `%{` 之后到光标无 `}`）。
 * 返回插值内已输入片段；无插值或已闭合返回 null。
 */
internal fun interpolationFilter(prefix: String): String? {
    val open = prefix.lastIndexOf("%{")
    if (open < 0) return null
    val after = prefix.substring(open + 2)
    if (after.contains('}')) return null
    return after
}

/** 特殊前缀（内存/自身资源/资源等，由专门 Provider 处理），不参与 logicboolean 的 . 全补全 */
internal val SPECIAL_DOT_PREFIXES = listOf("内存", "memory", "自身资源", "资源", "self.resource", "resource")

/**
 * 是否为特殊前缀：前缀**任意位置**出现 `内存.`/`memory.`/`资源.`/`self.resource.` 等段
 * （段内锚定，与 resourceChainRegex 后缀锚定对称），命中则不由 logicboolean 全补全处理。
 * 覆盖 %{ 插值内与链式上下文：%{当前动作目标.资源.、全局资源.、当前动作目标.内存.X. 等。
 */
internal fun isSpecialDotPrefix(prefix: String): Boolean {
    val p = prefix.lowercase()
    return SPECIAL_DOT_PREFIXES.any { special ->
        p == special || p.contains("$special.")
    }
}

/**
 * 运算符/比较符（`==`/`!=`/`<=`/`>=`/`+`/`-`/`*`/`/`/`%`/`<`/`>`/`=`）与取当前操作数。
 * 用户输入 `if 自身血量!=内存` 时，valuePrefix=自身血量!=内存 会把左操作数「自身血量」连同运算符
 * 一起并进过滤前缀，导致正在输入的操作数（内存）在 `内存.变量` 前缀过滤下全部匹配不上而空补全；
 * 也导致替换 baseLength 过长会摧毁左操作数。这里按最后一个运算符切分，只取运算符后的当前操作数
 * 作为过滤前缀（Pair.second 为替换长度）。无运算符返回 null（保持原逻辑）。
 */
internal fun lastOperandAfterOperator(segment: String): Pair<String, Int>? {
    val ops = listOf("==", "!=", "<=", ">=", "+", "-", "*", "/", "%", "<", ">", "=")
    var lastStart = -1
    var lastOp = ""
    for (op in ops) {
        val idx = segment.lastIndexOf(op)
        if (idx > lastStart) {
            lastStart = idx
            lastOp = op
        }
    }
    if (lastStart < 0) return null
    val operand = segment.substring(lastStart + lastOp.length)
    return operand to operand.length
}

/**
 * 非特殊前缀且含点：返回最后一个 '.' 之后的片段作为过滤前缀（可为空 = 与空格相同的全量补全），
 * 支持 当前动作目标.当前动作目标 链式叠加；无点或特殊前缀返回 null（不参与）。
 */
internal fun dotFallbackFilter(prefix: String): String? {
    if (isSpecialDotPrefix(prefix)) return null
    val idx = prefix.lastIndexOf('.')
    return if (idx >= 0) prefix.substring(idx + 1) else null
}

/**
 * LogicBoolean/LogicNumber 值补全。
 * 对应 VS Code 插件的 LogicBooleanValueCompletionProvider。
 * 同时按英文原名与翻译库样式中文名匹配（如 self.numberOfUnitsInEnemyTeam() ↔ 敌人有此单位数量）；
 * 括号内不触发（避免与函数参数补全冲突、消除全表刷屏）。
 * 插入文本：值前缀含中文时插入库样式中文名（裸名，用户再输入 ( 触发参数补全），否则插入英文原名；不拼回 self./()。
 */
class LogicBooleanValueCompletionProvider : BaseValueCompletionProvider() {

    private val arithmeticOperators = setOf("+", "-", "*", "/", "<", ">", "<=", ">=", "==", "!=", "%")
    private val boolItems = setOf("true", "false")

    /** 英文原名 → 库样式中文名 缓存（字典不变只算一次） */
    private var zhCache: Map<String, String>? = null

    /** 函数基名 → 签名参数类型 缓存（数据不变只算一次），供上下文分析器使用 */
    private var signatureCache: Map<String, List<LogicTarget>>? = null

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 括号内：命名参数函数（self.自身有标签(需标签=) 等）由 FunctionParameterCompletionProvider 处理，此处抑制；
        // 纯位置参数函数（选择(/rnd(/int( 等，如 选择(布尔条件, 单位参考, 任意逻辑表达式)）不在参数表，
        // 由本 Provider 按参数位置类型补逻辑表达式（选择( 第1参→布尔）。
        if (request.isInsideParentheses() &&
            isKnownParamFunctionContext(request.textBeforeCursor, request.context, request.translationDict)
        ) return false
        val prop = request.findProperty()
        // 既有：逻辑类型属性
        if (prop != null && (isLogicBooleanValueType(prop.type) || isLogicNumberValueType(prop.type))) return true
        // dynamic resources 的 RHS（用逻辑设置资源:资源名=之后，含括号内 valuePrefix 丢失 = 的场景）→ 逻辑表达式补全
        if (prop != null && isDynamicResourcesValueType(prop.type) && currentSegmentRhs(request.textBeforeCursor) != null) return true
        // key value pairs 的 RHS（设置单位内存:变量名=之后 / 更新单位内存）→ 逻辑表达式补全
        if (prop != null && isKeyValuePairsType(prop.type) && currentSegmentRhs(request.textBeforeCursor) != null) return true
        // fields values 的 RHS（设置单位状态 setUnitStats:单位属性=之后，支持 =/+=/-= 动态数学/逻辑）→ 数值逻辑表达式补全
        if (prop != null && isFieldsValuesValueType(prop.type) && currentSegmentRhs(request.textBeforeCursor) != null) return true
        // 新增：字符串属性内 %{ 逻辑插值（未闭合）→ 逻辑表达式补全
        if (interpolationFilter(request.valuePrefix.trim()) != null) return true
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val hasSelfPrefix = request.textBeforeCursor.endsWith("self.")
        // 加载原始英文数据，自行翻译，保证中英文两套匹配键都可用
        val data = ValueDataLoader.load(request.context, "logicboolean")
        val rawPrefix = request.valuePrefix.trim()
        val dict = request.translationDict
        val prop = request.findProperty()

        // 模式相关基础片段（三者互斥）：
        // 1) dynamic resources / key value pairs 的 RHS（用逻辑设置资源:资源名= / 设置单位内存:变量名= 之后）
        // 2) %{ 插值内（未闭合）
        // 3) 普通逻辑值（valuePrefix 当前词）
        val isKvp = prop != null && isKeyValuePairsType(prop.type)
        val isFieldsValues = prop != null && isFieldsValuesValueType(prop.type)
        val dynamicLikeRhs = prop != null &&
            (isDynamicResourcesValueType(prop.type) || isKvp || isFieldsValues)
        val rhsFragment = if (dynamicLikeRhs) {
            dynamicRhsFilter(rawPrefix)
        } else {
            null
        }
        val interpFragment = interpolationFilter(rawPrefix)
        val basePrefix: String
        val baseLength: Int
        when {
            rhsFragment != null -> { basePrefix = rhsFragment; baseLength = rhsFragment.trim().length }
            interpFragment != null -> { basePrefix = interpFragment; baseLength = interpFragment.trim().length }
            else -> { basePrefix = rawPrefix; baseLength = request.rawValuePrefixLength }
        }

        // 非特殊前缀的 . 全补全：如 当前动作目标. 弹出与空格相同的完整逻辑值列表，
        // 点后片段作过滤（支持 当前动作目标.当前动作目标 链式），只替换点后内容保留前缀；
        // . 后只给值，不给语法项（真/假/if/and/or/not/运算符）
        val dotFilter = dotFallbackFilter(basePrefix)
        val filterPrefix = dotFilter ?: basePrefix
        val filterPrefixLength = if (dotFilter != null) dotFilter.length else baseLength
        val useDotCandidates = dotFilter != null

        // 上下文类型识别：判断光标处期望「数值表达式」还是「布尔值/布尔表达式」，
        // 据此过滤候选——数值上下文只出数值型/任意型（选择），排除 if/and/or/not/比较符/布尔函数。
        // kvp（设置单位内存:变量=）按内存变量声明类型推导（unit→单位标记、int/float→数值、string→文本、bool→布尔；未声明宽松）。
        val kvpMemTarget = if (isKvp) {
            val seg = lastTopLevelSegment(request.textBeforeCursor.substringAfter(':'))
            val eq = topLevelEq(seg)
            if (eq >= 0) seg.substring(0, eq).trim()
                .let { request.memoryTypes[it.substringBefore('[')] }
                ?.let { memoryTypeToLogicTarget(it) } ?: LogicTarget.UNKNOWN
            else LogicTarget.UNKNOWN
        } else null
        val propertyTarget = when {
            // %{...} 插值内：无条件按「布尔值表达式」弹补全（布尔/数值/文本/任意/单位标记全集），
            // 不依赖外层属性类型或上下文推断——插值本质是逻辑表达式，无需额外条件。
            interpFragment != null -> LogicTarget.BOOLEAN
            prop != null && isDynamicResourcesValueType(prop.type) && rhsFragment != null -> LogicTarget.NUMERIC
            prop != null && isFieldsValuesValueType(prop.type) && rhsFragment != null -> LogicTarget.NUMERIC
            isKvp -> kvpMemTarget
            prop != null && isLogicNumberValueType(prop.type) -> LogicTarget.NUMERIC
            prop != null && isLogicBooleanValueType(prop.type) -> LogicTarget.BOOLEAN
            else -> null
        }
        // 传给分析器的表达式文本：从对应模式起点（`:`/`=`/`%{`）之后到光标，含函数前缀
        val exprText = when {
            rhsFragment != null -> rhsFragment
            interpFragment != null -> interpFragment
            else -> request.textBeforeCursor.substringAfter(':')
        }
        val signature = signatureCache ?: LogicExpressionContextAnalyzer.buildSignature(data.data).also { signatureCache = it }
        // 中文视图：函数中文名（选择）→ 英文基名小写（select/maxhp），供上下文分析器在中文文本里解析参数位置类型。
        // 需带 self. 前缀查翻译引擎（self.maxHp→self.maxhp 命中），再做大小写归一化兜底，未命中才回退裸基名。
        // value 统一存小写基名，与 buildSignature 的签名表 key（substringBefore('(').lowercase()）一致。
        val zhNameToBase: Map<String, String> = data.data.associate { item ->
            val base = item.name.substringBefore('(').trim()
            val bare = base.removePrefix("self.")
            val bareLower = bare.lowercase()
            val zhOf = { k: String -> dict?.getValueTranslation(k)?.takeIf { it.isNotBlank() && it != k } }
            val zh = zhOf(base) ?: zhOf(base.lowercase()) ?: zhOf(bare) ?: zhOf(bareLower)
            if (zh != null) zh to bareLower else base to bareLower
        }
        val target = LogicExpressionContextAnalyzer(signature, zhNameToBase).resolveTarget(exprText, propertyTarget)
        val expectedTarget = target

        // 运算符/比较符（+ - * / % < > <= >= == !=）不参与补全（用户手打），从源头上剔除；
        // 真/假（true/false）属布尔原语，保留（布尔属性与表达式里都要用）。
        // memory.NAME* 是文档占位模板（NAME 泛指变量名），非真实可补全项，内存引用由 MemoryValueCompletionProvider 提供，剔除。
        val candidates = data.data.filter {
            it.name !in arithmeticOperators && !it.name.lowercase().startsWith("memory.name")
        }
        // 按上下文期望类型过滤：候选条目的允许类型集合须含期望类型或含多态 ANY。
        // 数值上下文（用逻辑设置资源:X=）只保留数值型/双态数值/选择，
        //   排除 if/and/or/not/比较符/布尔函数（它们 type 为 bool → BOOLEAN 不匹配）；
        // 布尔表达式上下文（需要条件:if 自身有标签(...)）保留布尔型/双态布尔/选择/单位标记。
        // 单位标记（自身/当前动作目标/创建标记/内存.N）包含在**所有逻辑表达式**里：
        //   作为函数参数（间距(self, 当前动作目标)）或链式求值前缀（当前动作目标.tags(...)），
        //   故数值/文本/布尔表达式上下文都保留；布尔值（真/假）入口由下方 isBooleanEntry
        //   （空前缀、非括号内）单独过滤，此时只出 真/假/if，不含单位标记。
        // UNKNOWN/ANY 期望类型不过滤（插值/纯文本等宽松场景）。
        val contextFiltered = if (expectedTarget != LogicTarget.UNKNOWN && expectedTarget != LogicTarget.ANY) {
            candidates.filter { item ->
                val allowed = classifyLogicType(item.type)
                allowed.contains(LogicTarget.ANY) ||
                    allowed.contains(expectedTarget) ||
                    allowed.contains(LogicTarget.UNIT_MARKER)
            }
        } else {
            candidates
        }
        // 真/假/if 与布尔函数互斥：布尔属性顶层**空值**（如 自动触发: 空前缀、非括号内）只出 真/假/if 三个布尔原语；
        // 一旦输入了前缀（哪怕只是 if），即进入表达式模式，给布尔函数/选择/and/or/not 等。
        // 项目实际：自动触发:真（空值给真）、需要条件:if 自身有标签(...)（if 后给布尔函数）。
        val booleanPrimitives = setOf("true", "false", "if")
        // 空入口判定用「整段表达式是否空白」而非 filterPrefix.isEmpty()：
        // filterPrefix 会把空格当分隔，输入 `自动触发:if `（if 后空格）时被切空而误判为空入口；
        // 用 exprText（光标前到 `:` 的整段）判断，`if ` 非空白 → 进入表达式状态机。
        val isBooleanEntry = expectedTarget == LogicTarget.BOOLEAN && !request.isInsideParentheses() &&
            exprText.isBlank() && interpFragment == null
        // 括号内布尔表达式起点（选择( 第1参 / 函数布尔参数 空前缀）：此为表达式起点，
        // and/or（中缀二元运算符）与 if 无左操作数不可用，真/假 是字面量也不应作为表达式"起点"直接给出，
        // 只保留 not（逻辑非）与布尔函数（self.isXxx 等）；用户需真/假时在表达式中自行输入。
        val isBooleanExprStart = expectedTarget == LogicTarget.BOOLEAN && request.isInsideParentheses() &&
            filterPrefix.isEmpty()
        val finalFiltered = when {
            isBooleanEntry -> contextFiltered.filter { it.name.removePrefix("self.").lowercase() in booleanPrimitives }
            isBooleanExprStart -> contextFiltered.filterNot {
                it.name.removePrefix("self.").lowercase() in setOf("and", "or", "if", "true", "false")
            }
            else -> contextFiltered.filterNot { it.name.removePrefix("self.").lowercase() == "if" }
        }
        val dotCandidates = if (useDotCandidates) finalFiltered.filterNot { it.name in LOGIC_SYNTAX_ITEMS } else finalFiltered

        // 惰性缓存 en→zh 翻译（字典不变只算一次）：剥离参数括号取基名后走翻译引擎，不硬编码中文
        val zhByName = zhCache ?: data.data.associate { item ->
            item.name to translateValueName(dict, item.name)
        }.also { zhCache = it }

        // 统一四源头：对本类表达式上下文（非布尔入口、非点链）直接走对应源头函数，
        // 各源头内部已内置统一单位标记源（self + unit/marker/event + 选择 + unit 型内存变量），
        // 与 设置单位内存:目标= 的 RHS 同源，避免手写重复 merge。
        // 布尔源走「表达式状态机」：if/and/or/not 后给完整操作数集 + not，
        // 操作数完成只给 and/or、真/假后不弹 and/or、not 单次、运算符后继续弹全集；
        // 数值/文本源按各自类型过滤（数值源排除运算符与布尔语法项）。
        val arithmeticChars = setOf('+', '-', '*', '/', '%', '<', '>')
        // 运算符紧邻（无空格）时重置补全前缀：运算符会被并入 filterPrefix，若不重置
        // 操作数集按 `<数字>+` 过滤成空而不弹。此逻辑与 BOOLEAN 分支的 `自动触发:if 自身血量+`
        // 一致，并统一分发到数值等源，让「运算符后弹数值表达式」在所有运算符场景生效。
        val rawLastGlobal = if (exprText.isNotEmpty()) exprText.last() else '\u0000'
        val trimmedGlobal = exprText.trim()
        val lastCharGlobal = trimmedGlobal.lastOrNull()
        // 末尾运算符探测（含双字符比较/相等符 >= <= == !=）：需紧跟光标（运算符后无空格才视为等待操作数）
        val trailingOp: String? = when {
            trimmedGlobal.length >= 2 && trimmedGlobal.last() == '=' &&
                trimmedGlobal[trimmedGlobal.length - 2] in setOf('<', '>', '=', '!') -> trimmedGlobal.takeLast(2)
            lastCharGlobal != null && lastCharGlobal in arithmeticChars -> lastCharGlobal.toString()
            else -> null
        }
        val afterArithmetic = !rawLastGlobal.isWhitespace() && trailingOp != null
        // 运算符后应等待的操作数目标类型：算术/大小比较（+ - * / % < > >= <=）运算对象总是数值 → 数值表达式；
        // 相等比较（== !=）依左侧操作数类型（数值→数值、文本/布尔→对应表达式，未知默认数值）。
        val sizeArithOps = setOf("+", "-", "*", "/", "%", "<", ">", "<=", ">=")
        val comparandTarget: LogicTarget = when {
            !afterArithmetic -> expectedTarget
            trailingOp in sizeArithOps -> LogicTarget.NUMERIC
            else -> inferEqualityComparandTarget(trimmedGlobal, trailingOp.orEmpty(), data.data, dict, request.memoryTypes)
        }
        // 运算符/比较符（== != >= <= + - * / % < > =）与当前操作数的切割：
        // 用户输入 `if 自身血量!=内存`（正在打操作数）时按最后一个运算符切分，只取运算符后的当前操作数
        // 做过滤前缀与替换长度，避免把左操作数「自身血量」一起当前缀导致内存源全不命中、并破坏左操作数。
        // 末尾运算符（正在等操作数、运算符后无内容）场景已由 afterArithmetic 重置为空串，此处同样有效。
        val opOperandSplit = lastOperandAfterOperator(filterPrefix)
        val operandPrefix = when {
            afterArithmetic -> ""
            opOperandSplit != null -> opOperandSplit.first
            else -> filterPrefix
        }
        val operandPrefixLen = when {
            afterArithmetic -> 0
            opOperandSplit != null -> opOperandSplit.second
            else -> filterPrefixLength
        }
        val source = if (!isBooleanEntry && dotFilter == null) {
            when (expectedTarget) {
                LogicTarget.NUMERIC -> numericLogicValueItems(
                    dict, request.context, operandPrefix, operandPrefixLen,
                    request.memoryNames, request.memoryTypes
                )
                LogicTarget.BOOLEAN -> {
                    // 用「光标前一字符」（未 trim）判断是否运算符后紧跟空白：
                    // 运算符/比较符与操作数必须紧贴无空格，一旦运算符后出现空格 → 该操作数已结束，
                    // 绝不允许表达式补全，只弹 and/or 连接符；紧贴（无空格）时弹对应操作数集。
                    // 值片段分隔符（replaceableValuePrefix）不含运算符，运算符会被并入 filterPrefix，
                    // 若不重置前缀，操作数集会按 `<数字>+` 过滤成空而不弹；
                    // afterArithmetic/operandPrefix/operandPrefixLen/trailingOp/comparandTarget
                    // 已由上方统一分发（与数值源一致），此处复用。
                    val trimmed = exprText.trim()
                    val lastChar = trimmed.lastOrNull()
                    val typingOperand = operandPrefix.isNotEmpty()
                    // 子式起点（刚输入 ( 或 , ）/ 运算符后：给完整操作数集（数值/文本/单位标记/布尔函数），
                    // 不含 and/or/not/真/假/if/运算符（由 excludeTokens 剔除，not 由下方状态机按片段注入）。
                    val operands = booleanLogicValueItems(
                        dict, request.context, operandPrefix, operandPrefixLen,
                        request.memoryNames, request.memoryTypes,
                        excludeTokens = LOGIC_SYNTAX_ITEMS
                    )
                    // 数值操作数集（数值表达式源）：算术/大小比较（+ - * / % < > >= <=）以及相等比较(数值左)
                    // 后跟随（紧贴无空格）的下一个操作数 → 弹数值表达式源，而非布尔操作数集（含布尔函数）。
                    val numericOperands = numericLogicValueItems(
                        dict, request.context, operandPrefix, operandPrefixLen,
                        request.memoryNames, request.memoryTypes,
                        excludeTokens = LOGIC_SYNTAX_ITEMS
                    )
                    // 文本操作数集：相等比较(文本左) 后跟随的下一个操作数 → 弹文本表达式源
                    val stringOperands = stringLogicValueItems(
                        dict, request.context, operandPrefix, operandPrefixLen,
                        request.memoryNames, request.memoryTypes,
                        excludeTokens = LOGIC_SYNTAX_ITEMS
                    )
                    val lastWord = trimmed.substringAfterLast(' ').trim()
                        .removePrefix("self.").lowercase()
                    fun syntaxItem(text: String, detail: String) = CompletionProvider.CompletionItem(
                        label = text,
                        type = CompletionProvider.CompletionType.VALUE,
                        detail = detail,
                        insertText = text,
                        valuePrefixLength = filterPrefixLength,
                        valueType = "bool",
                        isCallable = false
                    )
                    val notItem = syntaxItem("not", "逻辑非")
                    val andItem = syntaxItem("and", "逻辑连接")
                    val orItem = syntaxItem("or", "逻辑连接")
                    when {
                        // 运算符/比较符后紧跟空白：该操作数已结束 → 不补表达式，只弹连接符
                        rawLastGlobal.isWhitespace() && trailingOp != null -> listOf(andItem, orItem)
                        // 运算符紧贴（无空格）等待下一个操作数 → 按比较目标类型弹对应表达式：
                        // 数值左/大小比较 → 数值表达式，文本左 → 文本表达式，布尔左 → 布尔操作数集
                        trailingOp != null -> when (comparandTarget) {
                            LogicTarget.BOOLEAN -> operands
                            LogicTarget.STRING -> stringOperands
                            else -> numericOperands
                        }
                        // 正在输入半截操作词：只给操作数（按当前词过滤）
                        typingOperand -> operands
                        // 子式/参数起点：完整布尔操作数集、不弹连接符
                        (lastChar == '(' || lastChar == ',') -> operands
                        // 空插值起点（%{ 内刚打开、未输入）：本质是表达式起点，弹完整布尔操作数集，
                        // 而非只给 and/or（exprText 空白被 when 尾部 else 误判为「操作数已完成」）
                        (interpFragment != null && exprText.isBlank()) -> operands
                        // 片段起点（if/and/or）：操作数全集 + not（not 在此片段首次可用）
                        lastWord in setOf("if", "and", "or") -> listOf(notItem) + operands
                        // not 已用一次：只给操作数，不再给 not/and/or
                        lastWord == "not" -> operands
                        // 布尔字面量后：不弹 and/or
                        lastWord in setOf("true", "false", "真", "假") -> emptyList()
                        // 操作数完成：只弹 and/or 连接符
                        else -> listOf(andItem, orItem)
                    }
                }
                LogicTarget.STRING -> stringLogicValueItems(
                    dict, request.context, operandPrefix, operandPrefixLen,
                    request.memoryNames, request.memoryTypes
                )
                // 任意/多态上下文（如 选择( 第2/3参，type=ANY）：统一用布尔值表达式源
                // （布尔/数值/文本/任意/单位标记 + 排除语法项），与其它表达式上下文一致，
                // 不再把整套 logicboolean 全量堆出。
                LogicTarget.ANY -> booleanLogicValueItems(
                    dict, request.context, operandPrefix, operandPrefixLen,
                    request.memoryNames, request.memoryTypes,
                    excludeTokens = LOGIC_SYNTAX_ITEMS
                )
                // 单位标记上下文（如 distanceBetween(unit1,unit2) 的 间距( 第1参 → 单位标记）：
                // 直接走统一单位标记调用源（self + unit/marker/event + 选择 + unit 型内存变量），
                // 而非落入下方零散回退分支，与 UnitRef/「+号」/演示面板同源。
                LogicTarget.UNIT_MARKER -> unitMarkerItems(
                    dict, request.context, operandPrefix, operandPrefixLen,
                    request.memoryNames, request.memoryTypes
                )
                // 参数表已满（如 select 已填满 3 参后又加逗号）：此位置不再提供补全
                LogicTarget.NONE -> emptyList()
                else -> null
            }
        } else null
        if (source != null) return source

        // 点链成员（当前动作目标. / 内存.unit型. 等，dotFilter 非空）：与 UnitRef/Memory 点链
        // 用同一统一布尔值表达式源 buildLogicMemberItems（含统一单位标记源 + 排除语法项），
        // 避免「同一 . 链场景」LogicBoolean 用本地过滤、UnitRef/Memory 用统一源的两套口径。
        if (dotFilter != null) {
            return buildLogicMemberItems(request, dotFilter, dotFilter.length, detail = "逻辑表达式")
        }

        // 同一个特殊情况（布尔入口/表达式起点/点链/EUR）：保持 LogicBoolean 特有逻辑
        // （DATA dynamic/rhs/interp 前缀、dotCandidates 排除语法项、hasSelfPrefix 剥 self 前缀）
        // 末尾按 !isBooleanEntry 并入统一单位标记源为补充（其余情况源头已内置）。
        val logicItems = buildLogicItems(dotCandidates, filterPrefix, filterPrefixLength, hasSelfPrefix, zhByName)
        // 统一调用源：只要当前上下文允许单位标记（即非纯布尔入口 isBooleanEntry），就并入统一单位标记源
        // （self + unit/marker/event + 选择 + unit 型内存变量）——与 UnitRef/「+号」/演示面板同源。
        // contextFiltered 对所有上下文（数值/文本/未知/表达式起点/点链）都通过 allowed.contains(UNIT_MARKER) 保留单位标记，
        // 故此处按 !isBooleanEntry 对齐该口径：纯布尔入口（需要条件: 空值）只出 真/假/if 不并入，其余情况并入。
        // 用全部逻辑条目去重（self.xxx 与 xxx 同 label 只留一次），避免重复。
        return if (!isBooleanEntry) {
            val unitMarkers = unitMarkerItems(
                dict, request.context, filterPrefix, filterPrefixLength,
                request.memoryNames, request.memoryTypes
            )
            (logicItems + unitMarkers).distinctBy { it.label }
        } else {
            logicItems
        }
    }

    /**
     * 相等比较（== !=）后应等待的操作数目标类型推演：取 `==`/`!=` 左侧紧邻操作数判定类型。
     * 数值左（如 `自身血量==`、`坐标x==`、数字字面量）→ 数值表达式；文本左 → 文本表达式；
     * 布尔函数左（如 `自身有标签(...)==`）→ 布尔表达式；内存变量依声明类型；未知默认数值。
     */
    private fun inferEqualityComparandTarget(
        trimmed: String,
        op: String,
        data: List<ValueDataLoader.ValueItem>,
        dict: TranslationDict?,
        memoryTypes: Map<String, String>
    ): LogicTarget {
        val before = trimmed.substringBefore(op)
        val base = before.substringAfterLast(' ').trim()
            .removePrefix("self.").substringBefore('(').trim()
        if (base.isEmpty()) return LogicTarget.NUMERIC
        // 内存引用 memory.X / 内存.X：按声明类型
        val mem = Regex("(?:memory|内存)\\.(.+)").matchEntire(base)?.groupValues?.get(1)?.substringBefore('[')
        if (mem != null) {
            return memoryTypes[mem]?.let { memoryTypeToLogicTarget(it) } ?: LogicTarget.NUMERIC
        }
        // 数据条目（英文名/中文名基名 命中）：按 classifyLogicType 判定
        val found = data.firstOrNull { item ->
            item.name.removePrefix("self.").substringBefore('(').trim().equals(base, ignoreCase = true) ||
                translateValueName(dict, item.name).removePrefix("self.").substringBefore('(').trim().equals(base, ignoreCase = true)
        }
        if (found != null) {
            val allowed = classifyLogicType(found.type)
            return when {
                allowed.contains(LogicTarget.BOOLEAN) -> LogicTarget.BOOLEAN
                allowed.contains(LogicTarget.STRING) -> LogicTarget.STRING
                else -> LogicTarget.NUMERIC
            }
        }
        // 数字字面量或未知操作数 → 数值表达式
        return LogicTarget.NUMERIC
    }

    private fun buildLogicItems(
        candidates: List<ValueDataLoader.ValueItem>,
        filter: String,
        prefixLength: Int,
        hasSelfPrefix: Boolean,
        zhByName: Map<String, String>
    ): List<CompletionProvider.CompletionItem> {
        return candidates.mapNotNull { item ->
            val rawName = item.name
            val zhName = zhByName[rawName] ?: rawName

            // 匹配键：英文原名/中文名，及去 self. 与尾部 () 的基名形式
            // 分级匹配：前缀命中优先，子串包含兜底（≥2字符），保证写路径点等中间命中也能弹出
            val enBase = rawName.removePrefix("self.").removeSuffix("()")
            val zhBase = zhName.removePrefix("self.").removeSuffix("()")
            val level = if (filter.isEmpty()) 2 else maxOf(
                completionMatchLevel(filter, enBase),
                completionMatchLevel(filter, zhBase),
                completionMatchLevel(filter, rawName),
                completionMatchLevel(filter, zhName)
            )
            if (level <= 0) return@mapNotNull null

            val label = if (hasSelfPrefix) zhName.removePrefix("self.") else zhName
            // 补全始终插入中文 label（自身血量），不插入带 () 的英文整条；
            // 保存时由翻译引擎 translateToEnglish 反译为 self.hp
            createValueItem(
                label = label,
                detail = "LogicBoolean",
                insertText = label,
                prefixLength = prefixLength,
                valueType = item.type,
                isCallable = isCallableLogicItem(item.name, item.type)
            )
        }.distinctBy { it.label }
    }
}