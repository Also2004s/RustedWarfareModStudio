package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.LOGIC_SYNTAX_ITEMS
import com.rwmodstudio.feature.completion.completionMatchLevel

/** 内存/变量名统一分级匹配：前缀命中优先，子串包含兜底（≥2字符），空前缀全量返回 */
    private fun Collection<String>.rankedByMatch(prefix: String): List<String> {
        if (prefix.isEmpty()) return toList()
    return map { it to completionMatchLevel(prefix, it) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .map { it.first }
}

/**
 * memory 变量值补全。
 * 触发条件：
 * 1. 内存属性精确名单命中（setUnitMemory/updateUnitMemory/defineUnitMemory/@memory，经 code_reference 核实）
 * 2. 值前缀为 "memory"/"内存"/"memory."/"内存."，或**任意位置**出现 内存./memory. 段
 *    （含 %{ 插值内，如 text:%{内存.攻击目标.}；任意属性值位置直接引用 内存.xxx）
 * 3. 值前缀为 "内存.<单位型变量>." → 单位成员补全（unit 型内存变量等价 当前动作目标，见 memoryUnitVarChainRegex）
 */
class MemoryValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 方式1：内存属性精确名单（经 code_reference 逐条核实，与分类页/模拟 ini 共源 CompletionShared），
        // 不做 contains("memory") 部分名匹配。
        // 覆盖：setUnitMemory(设置单位内存)/updateUnitMemory/defineUnitMemory(定义单位内存)/@memory，
        // 以及定义单位内存文档模板（defineUnitMemory: ...）。
        // 排除含 "memory" 子串但非内存引用的 updateUnitMemoryRate(更新单位内存频率,type=int)。
        val prop = request.findProperty()
        val direct = request.propertyName.trim().lowercase()
        val names = buildList {
            add(direct)
            if (prop != null) {
                add(prop.name.trim().lowercase())
                prop.name_en?.let { add(it.trim().lowercase()) }
            }
            if (request.isChineseName()) add(request.toEnglishName().trim().lowercase())
        }
        if (names.any { it in CompletionShared.memoryPropertyNames }) return true

        // 方式2：值前缀为 内存/memory（裸关键字），或任意位置含 内存./memory. 段（含 %{ 插值内）
        val prefix = request.valuePrefix.lowercase()
        if (prefix == "memory" || prefix == "内存" ||
            prefix.contains("内存.") || prefix.contains("memory.")
        ) return true
        // 方式3：dynamic resources（用逻辑设置资源/用逻辑添加资源 的 资源名=之后）与 key value pairs
        // （设置单位内存/更新单位内存 的 变量=之后）：值可为内存变量引用（AI阶段=内存.xxx），
        // 即便 RHS 前缀为空也触发列出已声明内存变量
        if (prop != null && (isDynamicResourcesValueType(prop.type) || isKeyValuePairsType(prop.type)) &&
            currentSegmentRhs(request.textBeforeCursor) != null
        ) return true
        return false
    }

    /** 内存类型（与 @memory 类型补全共用） */
    private val memoryTypes = listOf(
        "int", "float", "string", "bool", "boolean", "number", "text", "logic",
        "unit", "boolean[]", "float[]", "number[]", "unit[]"
    )

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // defineUnitMemory 声明（`类型 变量名, 类型 变量名`）：
        // 值片段处于类型位置（段首或 , 后、还没打空格）时给内存类型；
        // 打了空格进入变量名阶段后**一律不弹** —— 变量名由用户自己起，没有可补的词源，
        // 弹已声明内存变量只会干扰（老板决策：变量名不需要补，只要别在这个位置弹类型）。
        if (CompletionShared.isDefineUnitMemoryProperty(request.toEnglishName())) {
            val seg = request.valuePrefix.substringAfterLast(',').substringAfterLast('，').trim()
            if (isMemoryTypePosition(request.valuePrefix)) {
                return memoryTypes
                    .rankedByMatch(seg)
                    .map { type ->
                        createValueItem(
                            label = type,
                            detail = "内存类型",
                            insertText = type,
                            prefixLength = request.rawValuePrefixLength,
                            valueType = type.lowercase()
                        )
                    }
            }
            return emptyList()
        }

        val prefix = request.valuePrefix

        // 用逻辑设置资源/用逻辑添加资源：写完一个 `资源名=表达式` 后打空格 = 用逗号分隔下一组。
        // 该位置的「,」由 ResourceValueCompletionProvider 提供；此处不再兜底弹内存变量，
        // 否则空格后会混入内存变量候选（应只有逗号）。
        val propSpace = request.findProperty()
        if (propSpace != null &&
            (isDynamicResourcesValueType(propSpace.type) || isKeyValuePairsType(propSpace.type)) &&
            currentSegmentRhs(request.textBeforeCursor) != null &&
            needsCommaSeparator(request)
        ) return emptyList()

        // dynamic resources（用逻辑设置资源:AI阶段=内存）与 key value pairs（设置单位内存:变量=内存）：
        // 值区形如 资源名=RHS，用 RHS 片段做内存变量过滤（AI阶段= → 取 = 后），否则整段前缀匹配不到
        val prop = request.findProperty()
        val isRhsContext =
            prop != null && (isDynamicResourcesValueType(prop.type) || isKeyValuePairsType(prop.type)) &&
                currentSegmentRhs(request.textBeforeCursor) != null
        val memContext = if (isRhsContext) {
            currentSegmentRhs(request.textBeforeCursor)!!
        } else {
            prefix
        }

        // 内存.<单位型变量>. → 单位成员补全（unit 型内存变量等价 当前动作目标，如 内存.攻击目标.资源.总战力；
        // 非锚定匹配，%{内存.攻击目标.} 插值内同样生效）
        memoryUnitVarChainRegex.find(memContext)?.let { m ->
            // 数组下标（内存.空天航线[0].hp）变量名去 `[i]` 后再查声明类型，unit 型成员才补全
            val type = request.memoryTypes[m.groupValues[1].substringBefore('[')]
            if (type != null && isUnitMemoryType(type)) {
                val fragment = m.groupValues[2]
                return buildLogicMemberItems(request, fragment, fragment.length)
            }
        }

        val names = request.memoryNames
        if (names.isEmpty()) return emptyList()

        val memLower = memContext.lowercase()

        // dynamic resources / kvp 的 RHS（资源名=之后）：内存引用统一为 内存.变量名 形式。
        // - RHS 为空（用逻辑设置资源:AI阶段=）：给出全部 内存.变量，仅替换 RHS（0 长度），不破坏资源名；
        // - RHS 为裸关键字 内存/memory：续写 内存.变量；
        // - RHS 已含 内存./memory. 段：按点后片段过滤，仅替换点后，保留 内存. 前缀。
        // key value pairs（设置单位内存/更新单位内存）RHS 按 LHS 变量声明类型收紧引用：
        // 目标为 unit 型时只允许单位型内存变量（内存.攻击目标），剔除 int/float 等非单位型，
        // 避免 unit 型变量被赋非单位值（与 LogicBooleanValueCompletionProvider 的 kvpMemTarget 口径一致）。
        val isKvp = prop != null && isKeyValuePairsType(prop.type)
        val isUnitRhsTarget = isRhsContext && isKvp && run {
            val seg = lastTopLevelSegment(request.textBeforeCursor.substringAfter(':'))
            val eq = topLevelEq(seg)
            eq >= 0 && isUnitMemoryType(request.memoryTypes[seg.substring(0, eq).trim().substringBefore('[')])
        }
        val rhsNames = if (isUnitRhsTarget) names.filter {
            isUnitMemoryType(request.memoryTypes[it.substringBefore('[')])
        } else names

        if (isRhsContext) {
            if (isBareMemoryKeyword(memContext)) {
                val keyword = memoryKeyword(request)
                return rhsNames.map { name ->
                    createValueItem(
                        label = "$keyword.$name",
                        detail = memoryDetail(request.memoryTypes[name]),
                        insertText = "$keyword.$name",
                        prefixLength = memContext.length,
                        valueType = request.memoryTypes[name] ?: ""
                    )
                }
            }
            val memIdx = memLower.lastIndexOf("内存.")
            val memIdxEn = memLower.lastIndexOf("memory.")
            if (memIdx >= 0 || memIdxEn >= 0) {
                val afterDot = if (memIdx >= 0) memContext.substring(memIdx + 3) else memContext.substring(memIdxEn + 7)
                return rhsNames
                    .rankedByMatch(afterDot)
                    .map { name ->
                        createValueItem(
                            label = name,
                            detail = memoryDetail(request.memoryTypes[name]),
                            insertText = name,
                            prefixLength = afterDot.length,
                            valueType = request.memoryTypes[name] ?: ""
                        )
                    }
            }
            val keyword = memoryKeyword(request)
            return rhsNames
                .rankedByMatch(memContext)
                .map { name ->
                    createValueItem(
                        label = "$keyword.$name",
                        detail = memoryDetail(request.memoryTypes[name]),
                        insertText = "$keyword.$name",
                        prefixLength = memContext.length,
                        valueType = request.memoryTypes[name] ?: ""
                    )
                }
        }

        // 输入 "内存"/"memory"（未带点）：提供 关键字.变量名 续写项
        // （如 用逻辑设置资源:AI阶段=内存 → 内存.重拦集结），避免只输入关键字时空补全
        if (isBareMemoryKeyword(memContext)) {
            val keyword = memoryKeyword(request)
            return names.map { name ->
                createValueItem(
                    label = "$keyword.$name",
                    detail = memoryDetail(request.memoryTypes[name.substringBefore('[')]),
                    insertText = "$keyword.$name",
                    prefixLength = memContext.length,
                    valueType = request.memoryTypes[name.substringBefore('[')] ?: ""
                )
            }
        }

        // 内存变量引用：任意位置含 内存./memory. 段（含 %{ 插值内），按最后一个点后片段过滤
        val memIdx = memLower.lastIndexOf("内存.")
        val memIdxEn = memLower.lastIndexOf("memory.")
        if (memIdx >= 0 || memIdxEn >= 0) {
            val afterDot = if (memIdx >= 0) memContext.substring(memIdx + 3) else memContext.substring(memIdxEn + 7)
            return names
                .rankedByMatch(afterDot)
                .map { name ->
                    createValueItem(
                        label = name,
                        detail = memoryDetail(request.memoryTypes[name.substringBefore('[')]),
                        insertText = name,
                        prefixLength = afterDot.length,
                        valueType = request.memoryTypes[name.substringBefore('[')] ?: ""
                    )
                }
        }

        // 由属性名触发（如 setUnitMemory），valuePrefix 为空或与 memory 无关；
        // dynamic/kvp 用 RHS 片段、其余用 valuePrefix 做模糊过滤；
        // 替换长度：RHS 场景只替换 RHS 片段（保留 资源名=），普通场景替换整段已输入前缀
        // key value pairs（设置单位内存/更新单位内存）的变量名 LHS（顶层层 `=` 之前）：补全后紧跟 `=`，
        // 与动态资源名 LHS（ResourceValueCompletionProvider 的 isDynamicResourceName 追加 `=`）行为一致。
        val isKvpVarName =
            prop != null && isKeyValuePairsType(prop.type) &&
                currentSegmentRhs(request.textBeforeCursor) == null
        return names
            .rankedByMatch(memContext)
            .map { name ->
                val insert = if (isKvpVarName) "$name=" else name
                createValueItem(
                    label = insert,
                    detail = memoryDetail(request.memoryTypes[name.substringBefore('[')]),
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = request.memoryTypes[name.substringBefore('[')] ?: ""
                )
            }
    }
}

/**
 * 内存变量补全的右侧说明：带类型时显示 `[类型]｜内存变量`，无类型仅 `内存变量`。
 * 供 MemoryValueCompletionProvider / UnitRefValueCompletionProvider 等复用。
 */
internal fun memoryDetail(type: String?): String {
    val t = type?.takeIf { it.isNotBlank() }
    return if (t == null) "内存变量" else "[$t]｜内存变量"
}

/**
 * 判断 defineUnitMemory 值片段是否处于「类型位置」（段首或 , 后，尚未输入变量名）。
 * 例：`boolean nukeActive, `（, 后）→ true；`boolean nukeAc`（已含空格在输变量名）→ false。
 */
internal fun isMemoryTypePosition(valuePrefix: String): Boolean {
    val seg = valuePrefix.substringAfterLast(',').substringAfterLast('，').trim()
    return seg.isBlank() || !seg.any { it == ' ' || it == '\t' }
}

/**
 * 是否为 unit 型内存变量（内存变量声明类型精确匹配，不做 contains("unit") 部分名匹配）。
 * 内存变量类型来自 defineUnitMemory 的固定类型表（int/float/string/bool/number/text/logic/unit 及其数组），
 * 单位型仅 unit 与 unit[]（数组下标场景如 内存.空天航线[0]）。
 */
internal fun isUnitMemoryType(type: String?): Boolean =
    type?.lowercase() in setOf("unit", "unit[]")

/**
 * 是否为内存引用裸关键字（memory/内存，未带点）。
 * 裸关键字时提供 关键字.变量名 续写项（见 provideItems），不再直接续写空。
 */
internal fun isBareMemoryKeyword(prefix: String): Boolean =
    prefix.lowercase() in setOf("memory", "内存")

/**
 * 内存引用前缀的语言关键字：跟随翻译引擎开关（打开→中文「内存」，关闭→英文「memory」）。
 * 仅决定补全项「显示与插入值」的语言；**匹配规则不变**（用户无论输入 内存/攻击目标 还是 memory/attackTarget
 * 都能匹配）。与 LogicExpressionContext.memoryValueItemsByTarget 的 useChinese 判定一致，
 * 避免 memory./内存. 因语言不一致而双语重复。
 */
internal fun memoryKeyword(request: ValueCompletionRequest): String =
    if (com.rwmodstudio.core.translation.CompletionShared.translationEngineEnabled()) "内存" else "memory"

/**
 * 内存链前缀正则：`内存.<变量>.` 或 `memory.<var>.`（捕获 变量名 与 点后片段），供 unit 型内存变量成员补全。
 * 非锚定：`%{内存.攻击目标.} `、`当前动作目标.内存.X.` 等任意位置出现 内存./memory. 段均匹配。
 */
internal val memoryUnitVarChainRegex = Regex("""(?:内存|memory)\.([^.]+)\.(.*)$""", RegexOption.IGNORE_CASE)

/**
 * 构建逻辑值成员列表（unit 型内存变量 `.` 后补全，与 当前动作目标. 的成员补全一致；
 * 也供 UnitRefValueCompletionProvider 的表达式链补全复用）。
 * 统一走布尔值表达式源 [booleanLogicValueItems]：布尔/数值/文本/任意/单位标记口径过滤，
 * 排除语法项（真/假/if/and/or/not/运算符），按 [filter] 过滤，仅替换 [prefixLength] 长度的已输入片段。
 */
internal fun buildLogicMemberItems(
    request: ValueCompletionRequest,
    filter: String,
    prefixLength: Int,
    detail: String = "LogicBoolean"
): List<CompletionProvider.CompletionItem> {
    // 统一布尔值表达式源：逻辑布尔条目按「布尔/数值/文本/任意/单位标记」口径过滤 + 排除语法项
    // （真/假/if/and/or/not/运算符），并内置统一单位标记源（self + unit/marker/event + 选择 + unit 型内存变量）。
    // 与 单位标记. 链（UnitRef）、内存.单位型变量. 链共用同一 source，行为保持一致。
    return booleanLogicValueItems(
        dict = request.translationDict,
        context = request.context,
        prefix = filter,
        prefixLength = prefixLength,
        memoryNames = request.memoryNames,
        memoryTypes = request.memoryTypes,
        excludeTokens = LOGIC_SYNTAX_ITEMS
    )
}
