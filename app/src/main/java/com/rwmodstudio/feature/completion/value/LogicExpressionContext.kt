package com.rwmodstudio.feature.completion.value

import android.content.Context
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel
import com.rwmodstudio.feature.completion.LOGIC_CONNECTORS
import com.rwmodstudio.feature.completion.LOGIC_ENTRY_TOKENS
import com.rwmodstudio.feature.completion.OPERATOR_ITEMS

/**
 * 可调用对象分类（开发模式补全查看「+」菜单的分类）。
 * 与补全语法项分类共用同一来源（[OPERATOR_ITEMS] / [LOGIC_CONNECTORS] / [LOGIC_ENTRY_TOKENS]），
 * 其余条目按 [classifyLogicType] 归入数值/布尔/文本/单位标记/任意。
 * 布尔值（if/真/假/true/false）与布尔表达式（布尔函数）分开；单位类型引用并入「单位标记」；
 * and/or 归「连接符」；not 与二元运算符归「运算符」。
 */
internal fun classifyCallableCategory(name: String, type: String): String {
    // 带参函数名的 `()` 与 `self.` 前缀剥离，仅用基名匹配布尔值/连接符/运算符（与 translateValueName 一致）
    val n = name.substringBefore('(').removePrefix("self.").trim().lowercase()
    // 布尔值字面量 + if 入口（真/假/if/true/false）
    if (n in LOGIC_ENTRY_TOKENS) return CALLABLE_CAT_BOOLEAN_VALUE
    // 连接符 and/or
    if (n in LOGIC_CONNECTORS) return CALLABLE_CAT_CONNECTOR
    // not 与二元运算符（+ - * / % < > <= >= == !=）
    if (n in OPERATOR_ITEMS || n == "not") return CALLABLE_CAT_OPERATOR
    val allowed = classifyLogicType(type)
    return when {
        allowed.contains(LogicTarget.UNIT_MARKER) || allowed.contains(LogicTarget.UNIT_TYPE) -> CALLABLE_CAT_UNIT_MARKER
        allowed.contains(LogicTarget.NUMERIC) -> "数值表达式"
        allowed.contains(LogicTarget.BOOLEAN) -> "布尔表达式"
        allowed.contains(LogicTarget.STRING) -> "文本表达式"
        else -> CALLABLE_CAT_ANY
    }
}

/** 可调用对象分类标签（与 [classifyCallableCategory] 一一对应，供「+」菜单与分类浏览共用） */
internal val CALLABLE_CATEGORIES: List<String> = listOf(
    CALLABLE_CAT_UNIT_MARKER,
    "数值表达式",
    CALLABLE_CAT_BOOLEAN_VALUE,
    "布尔表达式",
    "文本表达式",
    CALLABLE_CAT_CONNECTOR,
    CALLABLE_CAT_OPERATOR,
    CALLABLE_CAT_ANY
)

private const val CALLABLE_CAT_UNIT_MARKER = "单位标记"
private const val CALLABLE_CAT_BOOLEAN_VALUE = "布尔值"
private const val CALLABLE_CAT_CONNECTOR = "连接符"
private const val CALLABLE_CAT_OPERATOR = "运算符"
private const val CALLABLE_CAT_ANY = "任意类型"

/**
 * 统一单位标记调用源：self + logicboolean 中 unit/marker/event + 多态 any（选择 select(...) 等）
 * + unit 型内存变量（内存.变量）。
 * 单一来源，供四类表达式源头（单位标记/数值/布尔/文本）内部统一内置，生产补全
 * （LogicBoolean kvp RHS/UnitRef 表达式/带参函数表达式参数）共用，
 * 确保所有单位标记（含 self、选择 与 unit 型内存变量）出自同一处——以 设置单位内存:目标=/更新单位内存 的
 * RHS 口径为准：凡允许 unit/marker/event 的上下文，一律并入 多态 any 与 unit 型内存变量，
 * 不再各处手工叠加、出现「某处有选择/内存变量、某处没有」。
 * [prefix]/[prefixLength] 用于按已输入片段过滤（生产补全传光标前片段，全量浏览传空）。
 * [memoryNames]/[memoryTypes]：仅为 unit 型内存变量并入提供；无内存信息（如 +号/演示全量浏览）传空即可。
 */
internal fun unitMarkerItems(
    dict: TranslationDict?,
    context: Context,
    prefix: String = "",
    prefixLength: Int = 0,
    memoryNames: Set<String> = emptySet(),
    memoryTypes: Map<String, String> = emptyMap()
): List<CompletionProvider.CompletionItem> {
    val data = ValueDataLoader.load(context, "logicboolean")
    val logicItems = data.data
        // unit/marker/event 单位标记表达式 + type=any 多态（选择 select(...) 等，可充当单位引用）
        // memory.NAME* 是文档占位模板（NAME 泛指变量名），非真实可补全项，剔除；内存引用由 MemoryValueCompletionProvider 提供。
        .filter { item ->
            !item.name.lowercase().startsWith("memory.name")
        }
        .filter { item ->
            isUnitMarkerType(item.type) || classifyLogicType(item.type).contains(LogicTarget.ANY)
        }
        .filter { item ->
            prefix.isEmpty() ||
                completionMatchLevel(prefix, item.name) > 0 ||
                completionMatchLevel(prefix, translateValueName(dict, item.name)) > 0
        }
        .map { item ->
            CompletionProvider.CompletionItem(
                label = translateValueName(dict, item.name),
                type = CompletionProvider.CompletionType.VALUE,
                detail = "单位标记",
                insertText = translateValueName(dict, item.name),
                valuePrefixLength = prefixLength.coerceAtLeast(0),
                valueType = item.type,
                isCallable = true
            )
        }
    // self（自身）：unit 型，能单独作为单位引用（logicboolean.json 里无独立条目）
    val selfItem = CompletionProvider.CompletionItem(
        label = "self",
        type = CompletionProvider.CompletionType.VALUE,
        detail = "单位标记",
        insertText = "self",
        valuePrefixLength = prefixLength.coerceAtLeast(0),
        valueType = "unit",
        isCallable = true
    )
    // unit 型内存变量（与 设置单位内存:目标= 的 kvp RHS 口径一致）：内存.变量，按已输入前缀过滤
    val unitMemItems = memoryValueItemsByTarget(prefix, prefixLength, memoryNames, memoryTypes) {
        it == LogicTarget.UNIT_MARKER
    }
    return (listOf(selfItem).filter { prefix.isEmpty() || "self".startsWith(prefix, ignoreCase = true) } + logicItems + unitMemItems)
        // 同 label 去重：self.xxx 与 xxx 翻译后同 label 只保留一次
        .distinctBy { it.label }
}

/**
 * 按内存变量声明类型过滤的 `内存.变量` 条目。
 * 与单位标记源（unitMarkerItems）并入 `内存.[unit]` 对称，供数值/文本/布尔源头按自身类型补充并入
 * （数值→内存.NUMERIC、文本→内存.STRING、布尔→内存.NUMERIC/STRING/BOOLEAN）。
 * unit 型内存已由 unitMarkerItems 单独并入，本 helper 仅处理其余目标类型；未知类型剔除。
 * [allowed]：认为类型可并入的判定（目标逻辑类型命中即并入）。
 */
private fun memoryValueItemsByTarget(
    prefix: String,
    prefixLength: Int,
    memoryNames: Set<String>,
    memoryTypes: Map<String, String>,
    allowed: (LogicTarget) -> Boolean
): List<CompletionProvider.CompletionItem> {
    // 裸 name 过滤前缀：`内存`/`memory` 关键字（未带点）本身不作为过滤键——空输入或正输入关键字
    // 时都应列出全部（如 自动触发:if 自身血量!=内存 → prefix=内存 需继续给 内存.目标）；
    // 带点/正文时才按点后或关键字后的片名做裸名过滤（如 内存.目 → 目）。
    val basePrefix = prefix.trim()
    val ls = basePrefix.lowercase()
    val filterForName: String = when {
        basePrefix.isEmpty() -> ""
        ls == "内存" || ls == "memory" -> ""
        basePrefix.startsWith("内存.") -> basePrefix.substringAfter("内存.")
        basePrefix.startsWith("memory.") -> basePrefix.substringAfter("memory.")
        basePrefix.startsWith("内存") -> basePrefix.substring(2)
        ls.startsWith("memory") -> basePrefix.substring("memory".length)
        else -> basePrefix
    }
    return memoryNames
        .map { mem ->
            val t = memoryTypes[mem.substringBefore('[')] ?: ""
            if (t.isBlank()) return@map mem to 0
            val lt = memoryTypeToLogicTarget(t)
            if (lt == LogicTarget.UNKNOWN || !allowed(lt)) mem to 0 else mem to completionMatchLevel(filterForName, mem)
        }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
        .map { it.first }
        .map { name ->
            CompletionProvider.CompletionItem(
                label = "内存.$name",
                type = CompletionProvider.CompletionType.VALUE,
                detail = memoryDetail(memoryTypes[name.substringBefore('[')]),
                insertText = "内存.$name",
                valuePrefixLength = prefixLength.coerceAtLeast(0),
                valueType = memoryTypes[name.substringBefore('[')] ?: "",
                isCallable = true
            )
        }
}

/** 数值型参数/属性值中的运算符与布尔关键字名（不参与数值表达式补全，由用户手填） */
private val NUMERIC_EXCLUDED_TOKENS = setOf(
    "+", "-", "*", "/", "%", "<", ">", "<=", ">=", "==", "!=",
    "if", "and", "or", "not", "true", "false"
)

/** 各表达式源头共享的「按类型过滤逻辑条目 + 内置统一单位标记源」样板 */
private fun logicExpressionItems(
    dict: TranslationDict?,
    context: Context,
    prefix: String = "",
    prefixLength: Int = 0,
    memoryNames: Set<String> = emptySet(),
    memoryTypes: Map<String, String> = emptyMap(),
    detail: String,
    excludeTokens: Set<String> = emptySet(),
    /** 额外并入的「自身类型」内存变量目标（unit 型由内置 unitMarkerItems 并入，不在此列） */
    memoryTargets: Set<LogicTarget> = emptySet(),
    allowed: (Set<LogicTarget>) -> Boolean
): List<CompletionProvider.CompletionItem> {
    val data = ValueDataLoader.load(context, "logicboolean")
    val logicItems = data.data
        .filter { item ->
            val n = item.name.removePrefix("self.").trim().lowercase()
            if (n in excludeTokens) return@filter false
            if (item.name.lowercase().startsWith("memory.name")) return@filter false
            allowed(classifyLogicType(item.type))
        }
        .filter { item ->
            prefix.isEmpty() ||
                completionMatchLevel(prefix, item.name) > 0 ||
                completionMatchLevel(prefix, translateValueName(dict, item.name)) > 0
        }
        .map { item ->
            CompletionProvider.CompletionItem(
                label = translateValueName(dict, item.name),
                type = CompletionProvider.CompletionType.VALUE,
                detail = detail,
                insertText = translateValueName(dict, item.name),
                valuePrefixLength = prefixLength.coerceAtLeast(0),
                valueType = item.type,
                isCallable = true
            )
        }
    // 本类表达式同样允许单位标记作为取值来源（self.x / 当前动作目标.x / 创建标记(...) 等），源头内置统一源
    val unitMarkers = unitMarkerItems(dict, context, prefix, prefixLength, memoryNames, memoryTypes)
    // 自身类型内存变量（数值/文本/布尔）补充并入，unit 型已含在 unitMarkerItems 内
    val memItems = if (memoryTargets.isNotEmpty()) {
        memoryValueItemsByTarget(prefix, prefixLength, memoryNames, memoryTypes) { it in memoryTargets }
    } else {
        emptyList()
    }
    return (logicItems + unitMarkers + memItems).distinctBy { it.label }
}

/**
 * 数值型（number/int/float）上下文下的逻辑表达式源。
 * 复刻 LogicBoolean 数值上下文的过滤口径（期望类型 NUMERIC + 多态 ANY + 单位标记 UNIT_MARKER），
 * 供带参函数「可表达式」数值参数值（创建标记 x/y/height/dir、获取相对/绝对偏移 x/y/height/角度偏移）
 * 补全 之间方向/self.x/选择/rnd/int 等表达式；纯数值参数（范围内/超过/少于/等于 等）不调用本函数。
 * [prefix]/[prefixLength]：按已输入片段过滤并替换整段；内部内置统一单位标记源 [unitMarkerItems]。
 */
internal fun numericLogicValueItems(
    dict: TranslationDict?,
    context: Context,
    prefix: String = "",
    prefixLength: Int = 0,
    memoryNames: Set<String> = emptySet(),
    memoryTypes: Map<String, String> = emptyMap(),
    excludeTokens: Set<String> = NUMERIC_EXCLUDED_TOKENS
): List<CompletionProvider.CompletionItem> =
    logicExpressionItems(
        dict, context, prefix, prefixLength, memoryNames, memoryTypes,
        detail = "数值表达式",
        excludeTokens = excludeTokens,
        memoryTargets = setOf(LogicTarget.NUMERIC)
    ) { allowed ->
        // 上下文期望数值：保留 数值型 / 多态 ANY / 单位标记（参与一切表达式）
        allowed.contains(LogicTarget.NUMERIC) ||
            allowed.contains(LogicTarget.ANY) ||
            allowed.contains(LogicTarget.UNIT_MARKER)
    }

/**
 * 布尔型上下文下的逻辑表达式源。
 * 匹配「布尔表达式 = 布尔 + 数值 + 文本 + 单位标记」口径（期望类型 BOOLEAN/NUMERIC/STRING + 多态 ANY + 单位标记 UNIT_MARKER），
 * 承载 需要条件/自动触发 等布尔表达式的（本布尔条目 + 数值/文本表达式 + 统一单位标记源）。
 * 语法项（and/or/not/if/真/假/运算符）由调用方经 [excludeTokens] 按「表达式状态机」注入，此处默认不剔除。
 * [prefix]/[prefixLength]：按已输入片段过滤并替换整段；内部内置统一单位标记源 [unitMarkerItems]。
 */
internal fun booleanLogicValueItems(
    dict: TranslationDict?,
    context: Context,
    prefix: String = "",
    prefixLength: Int = 0,
    memoryNames: Set<String> = emptySet(),
    memoryTypes: Map<String, String> = emptyMap(),
    excludeTokens: Set<String> = emptySet()
): List<CompletionProvider.CompletionItem> =
    logicExpressionItems(
        dict, context, prefix, prefixLength, memoryNames, memoryTypes,
        detail = "布尔表达式",
        excludeTokens = excludeTokens,
        memoryTargets = setOf(LogicTarget.NUMERIC, LogicTarget.STRING, LogicTarget.BOOLEAN)
    ) { allowed ->
        // 上下文期望布尔：保留 布尔 / 数值 / 文本 / 多态 ANY / 单位标记；双态（含 bool、含 /）也保留
        allowed.contains(LogicTarget.BOOLEAN) ||
            allowed.contains(LogicTarget.NUMERIC) ||
            allowed.contains(LogicTarget.STRING) ||
            allowed.contains(LogicTarget.ANY) ||
            allowed.contains(LogicTarget.UNIT_MARKER)
    }

/**
 * 文本型上下文下的逻辑表达式源。
 * 匹配 LogicBoolean 文本上下文的过滤口径（期望类型 STRING + 多态 ANY + 单位标记 UNIT_MARKER），
 * 承载 队伍名/str/playerName 等文本表达式（本文本条目 + 统一单位标记源）。
 * [prefix]/[prefixLength]：按已输入片段过滤并替换整段；内部内置统一单位标记源 [unitMarkerItems]。
 */
internal fun stringLogicValueItems(
    dict: TranslationDict?,
    context: Context,
    prefix: String = "",
    prefixLength: Int = 0,
    memoryNames: Set<String> = emptySet(),
    memoryTypes: Map<String, String> = emptyMap(),
    excludeTokens: Set<String> = emptySet()
): List<CompletionProvider.CompletionItem> =
    logicExpressionItems(
        dict, context, prefix, prefixLength, memoryNames, memoryTypes,
        detail = "文本表达式",
        excludeTokens = excludeTokens,
        memoryTargets = setOf(LogicTarget.STRING)
    ) { allowed ->
        // 上下文期望文本：保留 文本型 / 多态 ANY / 单位标记
        allowed.contains(LogicTarget.STRING) ||
            allowed.contains(LogicTarget.ANY) ||
            allowed.contains(LogicTarget.UNIT_MARKER)
    }

/** 演示面板「按补全值类型」分组标签（用于补全查看器展示，按 [classifyLogicType] 归类） */
internal fun valueTypeGroupLabel(valueType: String): String {
    val allowed = classifyLogicType(valueType)
    return when {
        allowed.contains(LogicTarget.UNIT_TYPE) -> "单位类型"
        allowed.contains(LogicTarget.UNIT_MARKER) -> "单位标记"
        allowed.contains(LogicTarget.NUMERIC) -> "数值"
        allowed.contains(LogicTarget.BOOLEAN) -> "布尔"
        allowed.contains(LogicTarget.STRING) -> "文本"
        allowed.contains(LogicTarget.ANY) -> "任意"
        else -> "其他"
    }
}

/** 演示面板分组展示顺序（固定，避免按出现先后抖动） */
internal val VALUE_TYPE_GROUP_ORDER: List<String> = listOf(
    "单位标记", "数值", "布尔", "文本", "单位类型", "任意", "其他"
)

/**
 * 判定逻辑表达式条目是否为「调用方」（补全后还能继续调用其他代码），供 DemoPanel 过滤。
 * - 单位标记类型（当前动作目标/父单位/创建标记 等）→ 可继续 `.成员`，调用方；
 * - 带非空参数签名的函数（select(bool, textA, textB)、getOffsetAbsolute(x=,y=)）→ 参数内可嵌表达式，调用方；
 * - 含 `.` 的链（self.resource.RESOURCE_TYPE）→ 链访问起点，调用方；
 * - 否则（无参取值 getter，如 self.ammo()/self.hp()/self.isFlying()）→ 被调用方，非调用方。
 */
internal fun isCallableLogicItem(name: String, type: String): Boolean {
    if (isUnitMarkerType(type)) return true
    val open = name.indexOf('(')
    if (open >= 0) {
        val close = name.lastIndexOf(')')
        return close > open && name.substring(open + 1, close).isNotBlank()
    }
    return name.contains('.')
}

/**
 * 逻辑表达式值/上下文类型。
 * 用户明确的类型谱系：布尔值、布尔表达式、数值表达式、文本表达式、单位名、单位标记；
 * 其中「选择」/读单位内存/事件数据 等为多态（ANY），可充当任一类型。
 */
internal enum class LogicTarget {
    /** 布尔值 或 结果为真/假的布尔表达式（真/假、比较符、and/or/not、self.isXxx） */
    BOOLEAN,
    /** 结果为数值的表达式（自身血量、距离、max、int、算术运算） */
    NUMERIC,
    /** 结果为文本的表达式（队伍名、str、playerName） */
    STRING,
    /** 单位类型名（unitref 填类型名，如 heavyTank、猛犸坦克T1） */
    UNIT_TYPE,
    /** 单位标记/单位引用表达式（unit ref 填表达式链，如 自身、攻击目标、创建标记(...)） */
    UNIT_MARKER,
    /** 多态：任一类型皆可（选择、读单位内存、事件数据、内存.N） */
    ANY,
    /** 无法确定上下文期望类型（不主动过滤） */
    UNKNOWN,
    /** 参数表已满（如 select 已填入全部声明参数后又加逗号）：此位置不再提供补全 */
    NONE
}

/**
 * 是否为「单位标记表达式」类型（logicboolean.json 的 type 字段）。
 * 统一入口：classifyLogicType 与 UnitRef 补全共用，避免各处对同一类型判断不一致。
 * 覆盖 unit / marker / "unit / marker" / event（事件来源），排除 unitref/unittype/unitname（单位类型名）。
 */
internal fun isUnitMarkerType(type: String): Boolean {
    val t = type.replace(" ", "").lowercase()
    if (t.startsWith("unitref") || t.contains("unittype") || t.contains("unitname")) return false
    return t.contains("unit") || t.contains("marker") || t == "event"
}

/**
 * 基础类型 → 逻辑目标类型。仅涵盖真正的基础类型（布尔/数值/文本），
 * 供 classifyLogicType、memoryTypeToLogicTarget、buildSignature 三处共用，避免基础分类漂移。
 * unit/marker/unitref/坐标参数/内存声明等特殊类型由各调用方按各自语义补充，不在此处理；非基础类型返回 null。
 */
internal fun logicTargetOfBaseType(type: String): LogicTarget? {
    val t = type.replace(" ", "").lowercase()
    return when {
        t == "bool" || t == "boolean" || t == "logicboolean" -> LogicTarget.BOOLEAN
        t == "int" || t == "integer" || t == "ints" || t == "float" || t == "number" -> LogicTarget.NUMERIC
        t == "string" || t == "text" -> LogicTarget.STRING
        else -> null
    }
}

/**
 * 根据逻辑函数表（logicboolean.json）的 type 字段把条目归类为「允许类型集合」。
 * 返回集合而非单值，因为源码里 getter 常标注双态（如 `float / bool`：数值上下文取数、布尔上下文作真值）。
 * - bool / LogicBoolean → {BOOLEAN}（true/false/and/or/not/比较符/self.isXxx）
 * - float / int / number / same type → {NUMERIC}（self.hp()、算术运算符 + - * / %）
 * - "float / bool"、"int / bool"（含 /）→ {NUMERIC, BOOLEAN}（自身血量 等双态 getter）
 * - string / 文本类 → {STRING}（队伍名/str/playerName）
 * - unit / marker / unit / marker / event → {UNIT_MARKER}（单位标记表达式）
 * - unitref / unit type / 单位类型 → {UNIT_TYPE}（单位类型名）
 * - any / any type / all arguments / self only（选择/读单位内存/事件数据/内存.N）→ {ANY}
 * - 其他 → 空集（不参与任何上下文的主过滤，交给更宽泛规则）
 */
internal fun classifyLogicType(type: String): Set<LogicTarget> {
    val t = type.replace(" ", "").lowercase()
    return when {
        t == "sametype" -> setOf(LogicTarget.NUMERIC)
        t.contains("/") -> {
            // "float / bool"、"int / bool" 等双态：数值 + 布尔都允许
            if (t.contains("bool")) setOf(LogicTarget.NUMERIC, LogicTarget.BOOLEAN)
            // "unit / marker" 双标记：单位标记（勿被上面的 or/else 误判为数值）
            else if (isUnitMarkerType(t)) setOf(LogicTarget.UNIT_MARKER)
            else setOf(LogicTarget.NUMERIC)
        }
        else -> logicTargetOfBaseType(t)?.let { setOf(it) }
            ?: when {
                isUnitMarkerType(t) -> setOf(LogicTarget.UNIT_MARKER)
                t.startsWith("unitref") || t.contains("unittype") || t.contains("unitname") -> setOf(LogicTarget.UNIT_TYPE)
                t == "any" || t == "anytype" || t == "allarguments" || t == "selfonly" -> setOf(LogicTarget.ANY)
                else -> emptySet()
            }
    }
}

/**
 * 逻辑表达式上下文分析器：根据光标前的值表达式文本（从 `:`/`=`/`%{` 之后到光标，含函数前缀），
 * 判断光标处期望的类型（数值/布尔/文本/单位标记/多态/未知），供补全按上下文过滤语法项与函数候选。
 *
 * 典型场景：
 * - `用逻辑设置资源:X=` → NUMERIC（只出数值型 + 选择，不出 if/and/or/not/比较）
 * - `用逻辑设置资源:X=选择(` → 选择 第1参数是布尔条件（可用 and/or/比较/真/假/布尔函数）
 * - `autoTrigger: if` → BOOLEAN（if/and/or/not/比较/布尔函数可用）
 * - `%{...}` 文本插值 → 宽松（由调用方决定，interp 场景传给 propertyTarget 为 null → UNKNOWN）
 */
internal class LogicExpressionContextAnalyzer(
    private val signatureParams: Map<String, List<LogicTarget>> = emptyMap(),
    /** 中文视图下函数名（如 选择）→ 英文基名（select），用于把中文文本映射回签名表 */
    private val zhNameToBase: Map<String, String> = emptyMap()
) {
    private val comparisonOps = listOf("<=", ">=", "==", "!=", "<", ">")
    private val arithmeticOps = setOf('+', '-', '*', '/', '%')
    private val booleanKeywords = setOf("if", "and", "or", "not")

    /**
     * 判断光标处期望类型。
     * [expr] 为光标前的值表达式文本；[propertyTarget] 为无进一步线索时的属性层兜底。
     */
    fun resolveTarget(expr: String, propertyTarget: LogicTarget?): LogicTarget {
        val text = expr.trim()
        if (text.isEmpty()) return propertyTarget ?: LogicTarget.UNKNOWN

        // 去掉尾部正在输入的半截词（标识符/链式点），得到光标前的上下文前缀
        var end = text.length
        while (end > 0 && isIdentifierChar(text[end - 1])) end--
        val trailingToken = text.substring(end)
        val contextPart = text.substring(0, end).trimEnd()

        // 若尾部半截词本身是完整布尔关键字（如刚输入 `if`），其后为布尔条件
        if (trailingToken.lowercase() in booleanKeywords) return LogicTarget.BOOLEAN

        if (contextPart.isNotEmpty()) {
            val last = contextPart.last()
            // 函数参数位置（'(' 或 ',' 结尾）→ 内部参数层级优先（int 等带参函数按签名参数类型）
            if (last == '(' || last == ',') {
                resolveFunctionParamContext(contextPart)?.let { return it }
            }
            // 算术符 / 比较符 → 操作数。内部层级优先：若光标处在某个带签名的位置参数函数
            // 的参数表达式内部（如 int(x+ ），按该函数参数类型（内部围栏优先）；否则回到
            // 外层布尔属性 → 布尔全集（布尔表达式含数值/文本/单位标记），非布尔属性维持数值。
            // 运算符必须紧邻操作数（`自身血量+`）；运算符后加空格再上表达式会语法报错——
            // 空格语义由 LogicBoolean 状态机的算术分支（紧邻→数值操作数、空格→and/or）处理。
            val operandTarget: LogicTarget? = when {
                last in arithmeticOps -> LogicTarget.NUMERIC
                comparisonOps.any { contextPart.endsWith(it) } -> LogicTarget.NUMERIC
                else -> null
            }
            if (operandTarget != null) {
                return innerCtxOperandTarget(contextPart)
                    ?: if (propertyTarget == LogicTarget.BOOLEAN) LogicTarget.BOOLEAN else operandTarget
            }
            // 布尔关键字结尾（如 `if 自`：contextPart 去尾半截词后以空格结束，需按整个词判断）
            val contextLastToken = contextPart.substringAfterLast(' ').trim()
            if (contextLastToken.lowercase() in booleanKeywords) return LogicTarget.BOOLEAN
        }

        return propertyTarget ?: LogicTarget.UNKNOWN
    }

    /**
     * 内部层级优先：取最内层「位置参数函数」的参数类型。
     * 光标在带括号签名的参数表达式内部（如 int(x+alpha) 的 x+ 之后）时，最内层是被调函数，
     * 其参数求值类型以签名表为准（int→数值、distance→数值）；无签名/多态 ANY/UNKNOWN 返回 null，
     * 交由 resolveTarget 回退到外层上下文（布尔属性→布尔全集）。
     */
    private fun innerCtxOperandTarget(contextPart: String): LogicTarget? {
        var idx = contextPart.lastIndexOf('(')
        while (idx >= 0) {
            var j = idx - 1
            while (j >= 0 && isIdentifierChar(contextPart[j])) j--
            val fnToken = contextPart.substring(j + 1, idx).trim()
            if (fnToken.isNotEmpty() && fnToken.last().let { it.isLetter() || it == '_' || it.code in 0x4E00..0x9FFF }) {
                val base = fnToken.substringAfterLast('.').lowercase()
                val params = signatureParams[base] ?: zhNameToBase[base]?.let { signatureParams[it] }
                val t = params?.firstOrNull()
                if (t != null && t != LogicTarget.ANY && t != LogicTarget.UNKNOWN) return t
            }
            idx = contextPart.lastIndexOf('(', idx - 1)
        }
        return null
    }

    /**
     * 解析最内层函数调用参数位置的类型。找不到函数调用返回 null（交由兜底）。
     * 从后往前找最后一个「函数调用」左括号（前面紧跟标识符），统计该层顶层逗号数得参数索引，
     * 从签名参数表取类型；无签名或索引越界返回 UNKNOWN。
     */
    private fun resolveFunctionParamContext(contextPart: String): LogicTarget? {
        var idx = contextPart.lastIndexOf('(')
        while (idx >= 0) {
            // 该 '(' 是否为函数调用：前面紧跟标识符（函数名）
            var j = idx - 1
            while (j >= 0 && isIdentifierChar(contextPart[j])) j--
            val fnToken = contextPart.substring(j + 1, idx).trim()
            if (fnToken.isNotEmpty() && fnToken.last().let { it.isLetter() || it == '_' || it.code in 0x4E00..0x9FFF }) {
                val base = fnToken.substringAfterLast('.').lowercase()
                // 中文视图：函数名可能是中文（如 选择），经 zhNameToBase 映射回英文基名再查签名
                val key = signatureParams[base]
                    ?: zhNameToBase[base]?.let { signatureParams[it] }
                val params = key
                // 统计该 '(' 之后、光标前，位于该层（括号深度内）的逗号数
                var depth = 0
                var commaCount = 0
                for (k in idx + 1 until contextPart.length) {
                    when (contextPart[k]) {
                        '(' -> depth++
                        ')' -> if (depth > 0) depth--
                        ',', '，' -> if (depth == 0) commaCount++
                    }
                }
                // 有签名但当前下标已越过最后声明的参数（如 select 已填满 3 个参数后又加逗号）：
                // 参数表已满，不再提供该函数参数位置的补全，返回 NONE 让调用方判定为空。
                if (params != null && commaCount >= params.size) return LogicTarget.NONE
                return params?.getOrNull(commaCount) ?: LogicTarget.UNKNOWN
            }
            idx = contextPart.lastIndexOf('(', idx - 1)
        }
        return null
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c.code in 0x4E00..0x9FFF

    companion object {
        /**
         * 从 value 条目构建「函数基名(小写) → 签名参数类型表」。
         * 解析 name 字段形如 `select(bool, textA, textB)` 的括号签名；
         * 无签名或无法解析的条目不参与（参数位置类型回退 UNKNOWN）。
         */
        fun buildSignature(items: List<ValueDataLoader.ValueItem>): Map<String, List<LogicTarget>> {
            val map = HashMap<String, List<LogicTarget>>()
            for (item in items) {
                val name = item.name
                val open = name.indexOf('(')
                if (open < 0) continue
                val close = name.lastIndexOf(')')
                if (close <= open) continue
                val base = name.substring(0, open).removePrefix("self.").trim().lowercase()
                if (base.isEmpty()) continue
                val inner = name.substring(open + 1, close)
                if (inner.isBlank()) continue
                map[base] = inner.split(',').map { segment ->
                    // 取参数片段里的类型关键字（x/y/[height] 等坐标参数视为数值）
                    val tok = segment.trim().removePrefix("[").removeSuffix("]")
                    val typeTok = tok.substringBefore(' ').trim().lowercase()
                    when {
                        typeTok == "unit" || typeTok == "marker" || typeTok == "unitref" ||
                            typeTok == "unit1" || typeTok == "unit2" -> LogicTarget.UNIT_MARKER
                        logicTargetOfBaseType(typeTok) != null -> logicTargetOfBaseType(typeTok)!!
                        typeTok == "num" || typeTok == "num1" || typeTok == "num2" ||
                            typeTok == "start" || typeTok == "end" -> LogicTarget.NUMERIC
                        typeTok == "x" || typeTok == "y" || typeTok == "z" || typeTok == "height" ||
                            typeTok == "dir" || typeTok == "angle" || typeTok == "min" ||
                            typeTok == "max" || typeTok == "damage" || typeTok == "speed" ||
                            // distance/distanceSquared/direction 的坐标数字后缀参数（x1,y1,x2,y2）同为数值
                            typeTok == "x1" || typeTok == "y1" ||
                            typeTok == "x2" || typeTok == "y2" ||
                            // createMarker 的 [teamId] 为数值（队伍编号）
                            typeTok == "teamid" -> LogicTarget.NUMERIC
                        else -> LogicTarget.ANY
                    }
                }
            }
            return map
        }
    }
}