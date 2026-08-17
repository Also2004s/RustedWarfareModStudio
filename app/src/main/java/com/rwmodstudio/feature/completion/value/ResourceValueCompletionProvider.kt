package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 资源名值补全。
 *
 * 触发条件：
 * 1. 链式前缀：值片段匹配 `<链>资源.` 或 `<链>resource.`（如 自身资源.、当前动作目标.资源.、
 *    内存.攻击目标.资源.、%{...资源.），保留整条链只替换点后片段；括号内（如 (资源.）同样生效；
 * 2. 属性型：属性 type 含 price / resource / customPrice / customResource /
 *    ResourceRef / resource ref / resources / dynamic resources，或中文属性名含"资源"/"价格"；
 *    属性型在值内未闭合的 '(' 之后不触发。
 *
 * 数据：合并 resources + globalResources + 内置资源（Prices_Resources.json）。
 */
class ResourceValueCompletionProvider : BaseValueCompletionProvider() {

    companion object {
        private val TYPE_KEYWORDS = setOf(
            "price", "resource", "customprice", "customresource",
            "resourceref", "resource ref", "resources", "dynamic resources"
        )
        /** 状态属性类内置资源（单位天生具有而非可积累资源），补全时排序靠后 */
        private val STATUS_RESOURCES = setOf("hp", "shield", "energy")
    }

    /** 过滤 X 占位符（Prices_Resources 里的模板条目，非真实资源） */
    private fun isRealResourceName(name: String): Boolean =
        name.isNotBlank() && !name.equals("X", ignoreCase = true)

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 1) 链式前缀：<链>资源. —— 括号内也触发（如 (资源. / (目标.资源.）
        if (resourceChainRegex.matches(request.valuePrefix)) return true

        // 2) 属性型：光标位于值内未闭合的 '(' 之后（如 价格:(）时不再触发
        if (request.isInsideParentheses()) return false

        // 3) 属性型判定：type 关键字
        val prop = request.findProperty()
        if (prop != null) {
            val typeLower = prop.type.lowercase()
            for (kw in TYPE_KEYWORDS) {
                if (typeLower.contains(kw)) return true
            }
        }
        // 中文兜底：属性名含"资源"或"价格"
        val pn = request.propertyName
        if (pn.contains("资源") || pn.contains("价格")) return true
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prefix = request.valuePrefix
        // 裸关键字（自身资源/资源/self.resource/resource，未带点）保持旧行为：不提示，由用户输入 . 触发
        if (isBareResourceKeyword(prefix)) return emptyList()
        // 链式前缀：保留 <链>资源.，只替换点后片段（与内存补全一致）
        resourceChainRegex.find(prefix)?.let { m ->
            val fragment = m.groupValues[2]
            val names = collectResourceNames(request)
            return names
                .map { it to completionMatchLevel(fragment, it) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<String, Int>> { it.second }
                        .thenComparator { a, b -> resourceComparator.compare(a.first, b.first) }
                )
                .map { it.first }
                .map { name ->
                    createValueItem(
                        label = translateValueName(request.translationDict, name),
                        detail = "资源名",
                        insertText = translateValueName(request.translationDict, name),
                        prefixLength = fragment.length,
                        valueType = "any"
                    )
                }
        }
        // 属性型：按前缀过滤，替换整个值片段
        val allResources = collectResourceNames(request)
        val matching = allResources
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenComparator { a, b -> resourceComparator.compare(a.first, b.first) }
            )
            .map { it.first }
        // 动态资源（用逻辑设置资源/用逻辑添加资源）的资源名 LHS（顶层 `=` 之前）：补全后紧跟 `=`
        val prop = request.findProperty()
        val isDynamicResourceName =
            prop != null && isDynamicResourcesValueType(prop.type) &&
                currentSegmentRhs(request.textBeforeCursor) == null
        return matching.map { name ->
            val translated = translateValueName(request.translationDict, name)
            val insert = if (isDynamicResourceName) "$translated=" else translated
            createValueItem(
                label = insert,
                detail = "资源名",
                insertText = insert,
                prefixLength = request.rawValuePrefixLength,
                valueType = "any"
            )
        }
    }

    /** 排序：状态属性类（hp/shield/energy）靠后，其余按字母序靠前 */
    private val resourceComparator: Comparator<String> = compareBy(
        { name -> name.lowercase() in STATUS_RESOURCES },
        { name -> name.lowercase() }
    )

    /** 收集资源名候选：内置资源 + 本地资源 + 全局资源（去重，过滤 X 占位符） */
    private fun collectResourceNames(request: ValueCompletionRequest): Set<String> {
        val allResources = mutableSetOf<String>()
        // 内置资源（Prices_Resources.json）
        try {
            val builtin = ValueDataLoader.load(request.context, "Prices_Resources")
            allResources.addAll(builtin.data.map { it.name }.filter(::isRealResourceName))
        } catch (e: Exception) {
            android.util.Log.w("ResourceValueCompletionProvider", "资源补全失败", e)
        }
        // 本地资源 + 全局资源（合并，去重）
        allResources.addAll(request.resources.filter(::isRealResourceName))
        allResources.addAll(request.globalResources.filter(::isRealResourceName))
        return allResources
    }
}

/**
 * 资源链前缀正则：值片段以 `<链>资源.` 或 `<链>resource.` 结尾（链可为空，如 自身资源.；
 * 也可为 unit 引用链，如 当前动作目标.资源.、内存.攻击目标.资源.、%{...资源.）。
 * 贪婪匹配取最后一个 资源./resource. 段；仅匹配大小写不敏感。
 */
internal val resourceChainRegex = Regex("""^(.*(?:资源|resource))\.(.*)$""", RegexOption.IGNORE_CASE)

/**
 * 判断值片段是否为资源引用前缀（自身资源./资源./self.resource./resource.，忽略大小写）。
 * 命中后触发资源名补全，保留前缀只替换点后片段；括号内同样生效。
 */
internal fun isResourceReferencePrefix(prefix: String): Boolean {
    val p = prefix.lowercase()
    return p == "自身资源" || p.startsWith("自身资源.") ||
        p == "资源" || p.startsWith("资源.") ||
        p == "self.resource" || p.startsWith("self.resource.") ||
        p == "resource" || p.startsWith("resource.")
}

/**
 * 是否为资源引用裸关键字（自身资源/资源/self.resource/resource，未带点）。
 * 裸关键字时不提供 "关键字." 续写项，由用户自行输入 . 后触发资源名补全。
 */
internal fun isBareResourceKeyword(prefix: String): Boolean {
    val p = prefix.lowercase()
    return p == "自身资源" || p == "资源" || p == "self.resource" || p == "resource"
}
