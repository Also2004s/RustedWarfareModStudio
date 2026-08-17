package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

private const val TAG = "FunctionParameterCompletionProvider"

/** 状态属性类内置资源（单位天生具有而非可积累资源），补全时排序靠后 */
private val STATUS_RESOURCES = setOf("hp", "shield", "energy")

/** 参数数据文件名（logicboolean + autoTriggerOnEvent） */
private val paramDataFiles = listOf("logicboolean", "autoTriggerOnEvent")

/** 自身有资源(资源名=数量) 特例：资源名动态作为参数名补全 */
internal const val hasResourcesKey = "hasresources"

/** 参数表函数 key 缓存（小写基名，进程级只加载一次） */
@Volatile
private var cachedParamKeys: Set<String>? = null

private fun loadParamKeys(context: Context): Set<String> {
    cachedParamKeys?.let { return it }
    val keys = mutableSetOf<String>()
    for (file in paramDataFiles) {
        val data = ParamDataLoader.load(context, file)
        for (fn in data.functions) keys.add(fn.key.lowercase())
    }
    return keys.also { cachedParamKeys = it }
}

/**
 * 判断光标所在行是否处于「已知参数函数」的括号内（供 CompletionProvider 早退用）：
 * 不在括号内返回 false；解析最后一个未闭合 '(' 前的函数基名（中文经翻译库反查为英文小写基名），
 * 命中参数表（logicboolean/autoTriggerOnEvent，含 hasResources 特例）返回 true。
 * [context] 为 null 时无法加载参数表，仅 hasResources 特例返回 true（其余 false）。
 */
internal fun isKnownParamFunctionContext(
    lineBeforeCursor: String,
    context: Context?,
    translationDict: TranslationDict?
): Boolean {
    if (!isInsideParentheses(lineBeforeCursor)) return false
    val key = resolveFunctionBaseKey(lineBeforeCursor) { base -> toEnglishForParam(base, translationDict) }
        ?: return false
    if (key == hasResourcesKey) return true
    if (context == null) return false
    return key in loadParamKeys(context)
}

private fun toEnglishForParam(chinese: String, translationDict: TranslationDict?): String {
    val dict = translationDict
    if (dict == null || !dict.isLoaded) return chinese
    return dict.getTranslationBack(chinese).removePrefix("self.")
}

/**
 * 函数命名参数补全。
 * 对应「括号内参数补全」需求：光标位于值内未闭合的 '(' 之后时，
 * 解析该 '(' 前的函数名（去 self./父单位./内存. 等链式前缀，中文经翻译库反查为英文基名），
 * 从 assets/data/param/ 参数表取命名参数：
 * - 光标在 '(' 或 ',' 后：列出未使用的参数（label=insert=zh=，如 需标签=）；
 * - 光标在 'param=' 后：按参数类型给出值建议（tag→项目标签、type→类型枚举、relation→关系、resource→资源名、enum→枚举值）。
 * 纯位置参数函数（rnd/select/substring 等）不在参数表中，不补。
 */

class FunctionParameterCompletionProvider : BaseValueCompletionProvider() {

    private val paramFiles = listOf("logicboolean", "autoTriggerOnEvent")

    /** readUnitMemory/eventData 的 type 参数可选值：与 @memory 声明类型列表一致
     *  （@memory name:类型 能声明的类型，读取时 type 应能选到同款，保证前后一致） */
    private val typeParamValues = listOf(
        "int", "float", "string", "bool", "boolean", "number", "text", "logic",
        "unit", "boolean[]", "float[]", "number[]", "unit[]"
    )

    /** relation 参数可选值：完整关系枚举（英文），补全时经字典翻译为中文（未命中保持英文，如 notOwn） */
    private val relationValues = listOf("own", "notOwn", "neutral", "allyNotOwn", "ally", "enemy", "any")

    /** 参数表合并缓存：键=归一化小写函数基名（字典不变只建一次） */
    private var paramMapCache: Map<String, ParamDataLoader.FunctionParamInfo>? = null

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (!request.isInsideParentheses()) return false
        val fnKey = resolveFunctionBaseKey(request.textBeforeCursor) { toEnglish(it, request) }
            ?: return false
        if (fnKey == hasResourcesKey) return true
        return findParams(request, fnKey) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val fnKey = resolveFunctionBaseKey(request.textBeforeCursor) { toEnglish(it, request) }
            ?: return emptyList()

        // 自身有资源(资源名=数量)：动态把资源名作为参数名补全
        if (fnKey == hasResourcesKey) {
            return provideHasResourcesParams(request)
        }

        val fn = findParams(request, fnKey) ?: return emptyList()

        // 当前参数已输入 = 号 → 补该参数的值
        val currentParam = currentParamKeyInParens(request.textBeforeCursor)
        if (currentParam != null) {
            val param = fn.params.firstOrNull {
                it.key.equals(currentParam, ignoreCase = true) || it.zh.equals(currentParam, ignoreCase = true)
            } ?: return emptyList()
            return provideParamValues(request, param)
        }

        // 读取单位内存/事件数据 的第一个参数（name）是位置参数（真实写法 读取单位内存('攻击目标', type='unit')）：
        // 光标位于 '(' 后第一个位置且未输入参数名时，直接补内存变量名（带引号），不弹 name=
        providePositionalMemoryName(request, fn)?.let { return it }

        // 光标在 '(' 或 ',' 后 → 补未使用的参数名
        val used = usedParamKeysInParens(request.textBeforeCursor, fn.params.flatMap { listOfNotNull(it.key, it.zh) })
        val prefix = request.valuePrefix
        val hasMutexGroup = fn.params.any { isMutuallyExclusiveParam(it.key) }
        return fn.params
            .filter { it.key !in used && it.zh !in used }
            // 位置参数（读取单位内存/事件数据的 name）不作为参数名补全候选
            .filter { !(fn.params.firstOrNull()?.key == "name" && it.key == "name") }
            .map { it to matchesParamPrefix(it, prefix) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ParamDataLoader.ParamItem, Int>> { it.second })
            .map { it.first }
            .map { param ->
                val insert = "${param.zh}="
                createValueItem(
                    label = insert,
                    // 条件 getter 的互斥参数组（超过/少于/等于/空的/满）标注，避免误导组合使用
                    detail = if (hasMutexGroup && isMutuallyExclusiveParam(param.key)) {
                        "条件参数（互斥）"
                    } else {
                        paramTypeDetail(param.type)
                    },
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = paramTypeValueType(param.type)
                )
            }
    }

    /**
     * 读取单位内存/事件数据 的第一个位置参数（内存变量名）补全：
     * 输入 `读取单位内存('` 或 `读取单位内存(攻` 时弹 `'攻击目标'` 等（带引号，替换整个当前段）。
     * 仅当光标位于 '(' 后第一个位置（尚未输入 `参数名=`，且尚未输入 ','）时生效；
     * 无内存变量或不在第一位置返回 null（走常规参数名补全）。
     */
    private fun providePositionalMemoryName(
        request: ValueCompletionRequest,
        fn: ParamDataLoader.FunctionParamInfo
    ): List<CompletionProvider.CompletionItem>? {
        // fn.key 来自参数表 JSON 的原始 key（驼峰：readUnitMemory / eventData），须忽略大小写比较
        if (!fn.key.equals("readUnitMemory", ignoreCase = true) &&
            !fn.key.equals("eventData", ignoreCase = true)) return null
        if (fn.params.firstOrNull()?.key != "name") return null
        val openIdx = request.textBeforeCursor.lastIndexOf('(')
        if (openIdx < 0) return null
        val afterOpen = request.textBeforeCursor.substring(openIdx + 1)
        if (afterOpen.contains(',') || afterOpen.contains('，')) return null
        val seg = afterOpen.trim()
        val prefix = seg.removePrefix("'").removePrefix("\"")
        val projInfo = com.rwmodstudio.core.ProjectTagScanner.getCachedInfo()
        // 读取单位内存 是特殊的跨单位内存读取（也可读自己的），内存名应覆盖整个项目，
        // 而 request.memoryNames 仅当前文件+继承链，故并入项目级扫描缓存（@memory/defineUnitMemory 全项目汇总）
        val names = (request.memoryNames + (projInfo?.memories ?: emptySet())).toSet()
        if (names.isEmpty()) return emptyList()
        val projPairs = projInfo?.memoryTypePairs.orEmpty()
        return names
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
            .map { it.first }
            .flatMap { name ->
                // 同名不同类型不合并：读入「当前+链主类型 + 项目全部同名声明类型」的并集，
                // 有几种类型就出几条，各自自动带 type（仅「名称+类型都相同」的条目天然合并成一条）；
                // 无任何类型声明的名字则只插内存名
                val baseName = name.substringBefore('[')
                val typeSet = linkedSetOf<String>()
                request.memoryTypes[baseName]?.let { typeSet.add(it) }
                projPairs.forEach { (n, t) -> if (n == baseName) typeSet.add(t) }
                val effective = if (typeSet.isEmpty()) listOf(null) else typeSet.toList()
                effective.map { type ->
                    // 数组类型（float[]/unit[]/string[] 等）：内存名后补 `[]`，与「读取单位内存」数组取值语法一致
                    // （内存名 裸存（编号），类型存 float[]），如 内存编号:float[] → '编号[]', type='float[]'
                    val isArray = type != null && type.endsWith("[]")
                    val memName = if (isArray && !baseName.endsWith("[]")) "${baseName}[]" else name
                    val typeSuffix = type?.takeIf { it.isNotBlank() }?.let { ", type='$it'" }.orEmpty()
                    createValueItem(
                        label = "'$memName'$typeSuffix",
                        detail = memoryDetail(type),
                        insertText = "'$memName'$typeSuffix",
                        prefixLength = seg.length,
                        valueType = "any"
                    )
                }
            }
    }

    // ===== 参数值建议（param= 之后） =====

    private fun provideParamValues(
        request: ValueCompletionRequest,
        param: ParamDataLoader.ParamItem
    ): List<CompletionProvider.CompletionItem> {
        // 取当前参数 = 后的原始片段（含可能存在的空格），trim 后匹配、原始长度做替换范围，
        // 避免 `需标签= 'fi` 这种带空格写法把替换范围算成 0 导致插入错乱。
        val rawAfterEq = paramValueAfterEq(request.textBeforeCursor) ?: return emptyList()
        val afterEq = rawAfterEq.trim()
        val values: List<String> = when (param.type) {
            "tag" -> (request.tags + request.globalTags).toList()
            // 消息标签（新消息(需标签=)）：只列项目里 sendMessageWithTags: 的取值，独立命名空间
            "messageTag" -> request.messageTags.toList()
            // enum/type/relation 优先用参数表 values（可维护），空则回退内置默认
            "enum", "type", "relation" -> param.values.ifEmpty {
                when (param.type) {
                    "type" -> typeParamValues
                    "relation" -> relationValues
                    else -> emptyList()
                }
            }
            "resource" -> collectResourceNames(request).toList()
            // 动作标签（队列项目添加/取消的 withActionTag、自身队列量 withActionTag）：补 行动/隐藏行动 节内的 tags 值
            "actionTag" -> request.actionTags.toList()
            // 内存变量名（读取单位内存/事件数据的 name 参数，经 quoteTypes 单引号包裹）
            // 跨单位内存读取要覆盖整个项目：并入项目级扫描缓存的内存名，与位置参数补全一致
            "memoryName" -> (request.memoryNames +
                (com.rwmodstudio.core.ProjectTagScanner.getCachedInfo()?.memories ?: emptySet())).toList()
            "bool" -> boolValues(request)
            // 附属节名（slot='附属节名'）：继承链 [附属_xxx] 节名后缀，单引号包裹（与 复制节 同源）
            "attachmentSlot" -> (request.chainSectionNames["attachment"] ?: emptySet()).toList()
            else -> emptyList()
        }
        // 数值型参数分两类（由 param 数据 expression 标记区分）：
        // - 坐标/角度「可表达式」参数（创建标记 x/y/height/dir、获取相对/绝对偏移 x/y/height/角度偏移，
        //   expression=true）→ 逻辑数值表达式补全：之间方向(...)/self.x/选择(...)/rnd(...)/int(...) 等（真实项目如此）；
        // - 纯数值参数（范围内/超过/少于/等于/几秒内/几秒后 等，expression 缺省 false）→ 手填字面量，不做表达式补全。
        if (param.type in setOf("number", "int", "float") && param.expression) {
            return numericLogicValueItems(
                request.translationDict, request.context,
                prefix = afterEq, prefixLength = rawAfterEq.length,
                memoryNames = request.memoryNames, memoryTypes = request.memoryTypes
            )
        }
        // enum/type/relation 值经翻译库翻译：字典命中才显示中文（如 move→移动、own→己方），未命中保持英文（如 notOwn）；
        // 不改翻译引擎、不新增字典条目，保存反译依赖字典既有 zhToEn 映射。
        val translated = translateDictValues(values, request.translationDict, param.type in setOf("enum", "type", "relation"))
        return buildParamValueSuggestions(param.type, translated, afterEq, prefixLength = rawAfterEq.length).map { suggestion ->
            createValueItem(
                label = suggestion.label,
                detail = paramTypeDetail(param.type),
                insertText = suggestion.insertText,
                prefixLength = suggestion.prefixLength,
                valueType = paramTypeValueType(param.type)
            )
        }
    }

    private fun provideHasResourcesParams(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val names = collectResourceNames(request)
        val used = usedParamKeysInParens(request.textBeforeCursor, names.toList())
        val prefix = request.valuePrefix
        return names
            .filter { it !in used }
            .map { name ->
                name to maxOf(
                    completionMatchLevel(prefix, name),
                    completionMatchLevel(prefix, translateValueName(request.translationDict, name))
                )
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenComparator { a, b -> resourceComparator.compare(a.first, b.first) }
            )
            .map { it.first }
            .map { name ->
                val translated = translateValueName(request.translationDict, name)
                val insert = "$translated="
                createValueItem(
                    label = insert,
                    detail = "资源名 - 参数",
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "any"
                )
            }
    }

    private fun boolValues(request: ValueCompletionRequest): List<String> {
        return try {
            val data = ValueDataLoader.load(request.context, "bool", request.translationDict)
            data.data.map { it.name }.ifEmpty { listOf("真", "假") }
        } catch (e: Exception) {
            listOf("真", "假")
        }
    }

    private fun collectResourceNames(request: ValueCompletionRequest): Set<String> {
        val all = mutableSetOf<String>()
        try {
            val builtin = ValueDataLoader.load(request.context, "Prices_Resources")
            all.addAll(builtin.data.map { it.name }.filter(::isRealResourceName))
        } catch (e: Exception) {
            Log.w(TAG, "资源名加载失败", e)
        }
        all.addAll(request.resources.filter(::isRealResourceName))
        all.addAll(request.globalResources.filter(::isRealResourceName))
        return all
    }

    /** 状态属性类内置资源排序靠后，其余按字母序 */
    private val resourceComparator: Comparator<String> = compareBy(
        { name -> name.lowercase() in STATUS_RESOURCES },
        { name -> name.lowercase() }
    )

    /** 过滤 X 占位符（Prices_Resources 里的模板条目，非真实资源） */
    private fun isRealResourceName(name: String): Boolean =
        name.isNotBlank() && !name.equals("X", ignoreCase = true)

    // ===== 参数表查询 =====

    private fun findParams(
        request: ValueCompletionRequest,
        fnKey: String
    ): ParamDataLoader.FunctionParamInfo? {
        return paramMap(request)[fnKey]
    }

    /** 惰性构建合并后的参数表（小写函数基名 → 参数定义） */
    private fun paramMap(request: ValueCompletionRequest): Map<String, ParamDataLoader.FunctionParamInfo> {
        paramMapCache?.let { return it }
        val map = mutableMapOf<String, ParamDataLoader.FunctionParamInfo>()
        for (file in paramFiles) {
            val data = ParamDataLoader.load(request.context, file)
            for (fn in data.functions) {
                map[fn.key.lowercase()] = fn
            }
        }
        return map.also { paramMapCache = it }
    }

    private fun toEnglish(chinese: String, request: ValueCompletionRequest): String {
        val dict = request.translationDict
        if (dict == null || !dict.isLoaded) return chinese
        return dict.getTranslationBack(chinese).removePrefix("self.")
    }

    private fun matchesParamPrefix(param: ParamDataLoader.ParamItem, prefix: String): Int {
        if (prefix.isEmpty()) return 2
        return maxOf(completionMatchLevel(prefix, param.zh), completionMatchLevel(prefix, param.key))
    }

    private fun paramTypeDetail(type: String): String = when (type) {
        "tag" -> "标签 - 参数"
        "messageTag" -> "消息标签 - 参数"
        "actionTag" -> "动作 - 参数"
        "memoryName" -> "内存变量 - 参数"
        "type" -> "类型 - 参数"
        "relation" -> "关系 - 参数"
        "resource" -> "资源 - 参数"
        "enum" -> "枚举 - 参数"
        "bool" -> "布尔 - 参数"
        "attachmentSlot" -> "附属节名 - 参数"
        else -> "参数"
    }

    /** 参数类型 → 补全值类型（供补全查看器按值类型分组归类） */
    private fun paramTypeValueType(type: String): String = when (type) {
        "bool" -> "bool"
        "memoryName", "resource" -> "any"
        else -> "string"
    }
}

/**
 * 解析行内最后一个未闭合 '(' 前的函数基名。
 * 去链式前缀（self./父单位./内存. 等，取最后一个 '.' 之后），中文经 [toEnglish] 反查为英文基名并小写。
 * 返回 null 表示当前括号不是函数调用（如分组括号、函数名缺失）。
 */
internal fun resolveFunctionBaseKey(
    lineBeforeCursor: String,
    toEnglish: (String) -> String
): String? {
    // 键值分隔取行内第一个 ':'（与 splitKeyValueLine 一致）；值内可含 ':'（如 ROOT:/CUSTOM:）
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return null
    val openIdx = lineBeforeCursor.lastIndexOf('(')
    if (openIdx <= colonIdx) return null

    var start = openIdx - 1
    while (start > colonIdx) {
        val c = lineBeforeCursor[start]
        if (c == ' ' || c == ',' || c == '，' || c == '(' || c == ':' || c == '=' ||
            c == '<' || c == '>' || c == '!' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
        ) break
        start--
    }
    val token = lineBeforeCursor.substring(start + 1, openIdx).trim()
    if (token.isEmpty()) return null
    // 逻辑关键字/分组括号（if/and/or/not）不是函数调用，直接排除
    if (token.lowercase() in setOf("if", "and", "or", "not")) return null

    val base = token.substringAfterLast('.').trim()
    if (base.isEmpty()) return null
    val en = if (base.any { it.code in 0x4E00..0x9FFF }) {
        toEnglish(base).trim().removePrefix("self.")
    } else {
        base
    }
    return en.lowercase()
}

/**
 * 当前正在输入的参数名：取最后一个未闭合 '(' 后的最后一段（按 ',' 切分），
 * 若该段含 '=' 则返回 '=' 前的参数名，否则返回 null（处于 '(' 或 ',' 后待输入参数名）。
 */
internal fun currentParamKeyInParens(lineBeforeCursor: String): String? {
    // 键值分隔取行内第一个 ':'（与 splitKeyValueLine 一致）；值内可含 ':'（如 ROOT:/CUSTOM:）
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return null
    val openIdx = lineBeforeCursor.lastIndexOf('(')
    if (openIdx <= colonIdx) return null
    val afterOpen = lineBeforeCursor.substring(openIdx + 1)
    val lastSeg = afterOpen.substringAfterLast(',').substringAfterLast('，').trim()
    val eqIdx = lastSeg.lastIndexOf('=')
    if (eqIdx < 0) return null
    val name = lastSeg.substring(0, eqIdx).trim()
    return name.ifEmpty { null }
}

/**
 * 当前参数 `=` 后的原始片段：取行内最后一个未闭合 '(' 之后、最后一个 `,`/`，` 之后段的 `=` 之后部分。
 * 与 [currentParamKeyInParens] 对应（后者返回 `=` 前参数名，这里返回 `=` 后原始内容，含空格如 ` 'fi`）。
 * 无当前参数或未输入 `=` 返回 null。
 */
internal fun paramValueAfterEq(lineBeforeCursor: String): String? {
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return null
    val openIdx = lineBeforeCursor.lastIndexOf('(')
    if (openIdx <= colonIdx) return null
    val afterOpen = lineBeforeCursor.substring(openIdx + 1)
    val lastSeg = afterOpen.substringAfterLast(',').substringAfterLast('，')
    val eqIdx = lastSeg.lastIndexOf('=')
    if (eqIdx < 0) return null
    return lastSeg.substring(eqIdx + 1)
}

/**
 * 已使用参数名：扫描最后一个未闭合 '(' 之后的内容，凡以段首形式出现 `key=` 的视为已使用。
 */
internal fun usedParamKeysInParens(lineBeforeCursor: String, paramKeys: List<String>): Set<String> {
    // 键值分隔取行内第一个 ':'（与 splitKeyValueLine 一致）；值内可含 ':'（如 ROOT:/CUSTOM:）
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return emptySet()
    val openIdx = lineBeforeCursor.lastIndexOf('(')
    if (openIdx <= colonIdx) return emptySet()
    val afterOpen = lineBeforeCursor.substring(openIdx + 1)
    val used = mutableSetOf<String>()
    for (key in paramKeys) {
        if (key.isBlank() || !afterOpen.contains(key)) continue
        if (Regex("(^|[，,\\s（(])" + Regex.escape(key) + "\\s*=").containsMatchIn(afterOpen)) {
            used.add(key)
        }
    }
    return used
}

/** param= 后的值建议构建结果 */
internal data class ParamValueSuggestion(
    val label: String,
    val insertText: String,
    val prefixLength: Int
)

/**
 * enum/type/relation 参数值经翻译库英→中翻译（仅字典命中才变中文，未命中保持英文原样）。
 * 不修改翻译引擎、不新增字典条目；仅当 [translate] 为 true 且字典已加载时生效。
 */
internal fun translateDictValues(values: List<String>, dict: TranslationDict?, translate: Boolean): List<String> {
    if (!translate || dict?.isLoaded != true) return values
    return values.map { dict.getValueTranslation(it) }
}

/**
 * 互斥条件参数组：条件 getter（自身血量/自身队列量/自身运输数量等）的一次调用里只能使用其中一个。
 * 真实 MOD 均为单参数使用（自身血量(少于=1)、自身队列量(空的=true)）；代码表 desc 列出全部参数，补全时标注互斥。
 */
internal val MUTUALLY_EXCLUSIVE_PARAMS = setOf("greaterthan", "lessthan", "equalto", "empty", "full")

/** 是否为互斥条件参数（超过/少于/等于/空的/满） */
internal fun isMutuallyExclusiveParam(key: String): Boolean = key.lowercase() in MUTUALLY_EXCLUSIVE_PARAMS

/**
 * 构建 param= 后的值建议。
 * [quoteTypes] 中的字符串型参数（tag/messageTag/actionTag/type/relation/enum/memoryName）值需用单引号包裹
 * （代码表写法如 需标签='fish'、队列项目取消(withActionTag="actionFire")、读取单位内存('攻击目标', type='unit')）；
 * 匹配前缀先去除用户已输入的引号（'fi / "fi → fish），prefixLength 保留已输入片段长度以替换掉引号。
 */
internal fun buildParamValueSuggestions(
    paramType: String,
    values: List<String>,
    typedAfterEq: String,
    quoteTypes: Set<String> = setOf("tag", "messageTag", "actionTag", "type", "relation", "enum", "attachmentSlot", "memoryName"),
    prefixLength: Int = typedAfterEq.length
): List<ParamValueSuggestion> {
    val quote = paramType in quoteTypes
    val matchPrefix = if (quote) typedAfterEq.trim('\'', '"') else typedAfterEq
    // tag/messageTag/actionTag/resource/attachmentSlot/memoryName 按字母排序便于查找；relation/enum/type 保持参数表书写顺序（语义顺序）
    val ordered = if (paramType == "tag" || paramType == "messageTag" || paramType == "actionTag" ||
        paramType == "resource" || paramType == "attachmentSlot" || paramType == "memoryName"
    ) {
        values.sortedBy { it.lowercase() }
    } else {
        values
    }
    return ordered
        .map { it to completionMatchLevel(matchPrefix, it) }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenComparator { a, b -> a.first.lowercase().compareTo(b.first.lowercase()) }
        )
        .map { it.first }
        .map { value ->
            val insert = if (quote) "'$value'" else value
            ParamValueSuggestion(label = insert, insertText = insert, prefixLength = prefixLength)
        }
}