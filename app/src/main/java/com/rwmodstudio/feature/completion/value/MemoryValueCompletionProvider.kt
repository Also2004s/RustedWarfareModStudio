package com.rwmodstudio.feature.completion.value

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
 * 1. 属性名含 "memory"（通过 findProperty 或翻译库反向查找），如 setUnitMemory、updateUnitMemory
 * 2. 值前缀为 "memory"/"内存"/"memory."/"内存."，或**任意位置**出现 内存./memory. 段
 *    （含 %{ 插值内，如 text:%{内存.攻击目标.}；任意属性值位置直接引用 内存.xxx）
 * 3. 值前缀为 "内存.<单位型变量>." → 单位成员补全（unit 型内存变量等价 当前动作目标，见 memoryUnitVarChainRegex）
 */
class MemoryValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 方式1a：属性名直接含 "memory"（英文属性）或 "内存"（中文属性），例如 setUnitMemory、设置单位内存
        if (request.propertyName.lowercase().contains("memory")) return true
        if (request.propertyName.contains("内存")) return true

        // 方式1b：通过代码表 findProperty 找到属性，且 name/name_en 含 "memory"
        val prop = request.findProperty()
        if (prop != null) {
            val names = listOfNotNull(prop.name, prop.name_en).joinToString(" ").lowercase()
            if (names.contains("memory")) return true
        }

        // 方式1c：中文属性名通过翻译库反向查找，英文名含 "memory"
        if (request.isChineseName()) {
            if (request.toEnglishName().lowercase().contains("memory")) return true
        }

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
        // defineUnitMemory 声明（boolean var1, float var2）：值片段处于类型位置（段首或 , 后）时给内存类型；
        // 变量名阶段（段内已有空格）按已输入变量名前缀补已声明的内存变量
        if (request.toEnglishName().equals("defineUnitMemory", ignoreCase = true)) {
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
            // 变量名阶段：如 `defineUnitMemory: unit nextTa` → 提示已声明内存变量
            return request.memoryNames
                .rankedByMatch(seg)
                .map { name ->
                    createValueItem(
                        label = name,
                        detail = memoryDetail(request.memoryTypes[name]),
                        insertText = name,
                        prefixLength = seg.length,
                        valueType = request.memoryTypes[name] ?: ""
                    )
                }
        }

        val prefix = request.valuePrefix

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
            if (type != null && type.lowercase().contains("unit")) {
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
            eq >= 0 && (request.memoryTypes[seg.substring(0, eq).trim().substringBefore('[')]
                ?.lowercase()?.contains("unit") == true)
        }
        val rhsNames = if (isUnitRhsTarget) names.filter {
            (request.memoryTypes[it.substringBefore('[')] ?: "").lowercase().contains("unit")
        } else names

        if (isRhsContext) {
            if (isBareMemoryKeyword(memContext)) {
                val keyword = if (memLower.startsWith("memory")) "memory" else "内存"
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
            val keyword = if (memLower.startsWith("memory")) "memory" else "内存"
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
            val keyword = if (memLower.startsWith("memory")) "memory" else "内存"
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
 * 是否为内存引用裸关键字（memory/内存，未带点）。
 * 裸关键字时提供 关键字.变量名 续写项（见 provideItems），不再直接续写空。
 */
internal fun isBareMemoryKeyword(prefix: String): Boolean =
    prefix.lowercase() in setOf("memory", "内存")

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
