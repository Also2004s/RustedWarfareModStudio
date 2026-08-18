package com.rwmodstudio.feature.completion

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.value.ProjectSoundCache
import com.rwmodstudio.feature.completion.value.ValueCompletionAggregator
import com.rwmodstudio.feature.completion.value.isKnownParamFunctionContext
import com.rwmodstudio.feature.completion.value.isInsideParentheses

/**
 * 补全统一匹配级别：
 * - 2 = 前缀命中（最优先）
 * - 1 = 子串包含命中（仅当 query 长度 ≥2 时启用，避免单字符输入时列表变脏）
 * - 0 = 不匹配
 * 空前缀视为全量匹配（返回 2）。用于把「仅前缀匹配」升级为「前缀优先 + 子串包含兜底」。
 */
internal fun completionMatchLevel(query: String, candidate: String): Int {
    if (query.isEmpty()) return 2
    return when {
        candidate.startsWith(query, ignoreCase = true) -> 2
        query.length >= 2 && candidate.contains(query, ignoreCase = true) -> 1
        else -> 0
    }
}

/**
 * 英文节名 → 中文分类（core→核心 等）。供节名映射（mapSectionName）与跨节引用 `${core.X}` 归一化使用。
 * 节名是 INI 语法固定词汇，非运行时翻译，故此处保留中英映射。
 */
internal val sectionEnToZh = mapOf(
    "core" to "核心",
    "graphics" to "图像",
    "ai" to "AI",
    "attack" to "攻击",
    "movement" to "运动",
    "action" to "行动",
    "hiddenaction" to "隐藏行动",
    "effect" to "效果",
    "animation" to "动画",
    "attachment" to "附属",
    "canbuild" to "可建造",
    "decal" to "贴花",
    "resource" to "资源",
    "global_resource" to "全局资源",
    "turret" to "炮塔",
    "projectile" to "抛射体",
    "leg" to "腿",
    "placementrule" to "放置规则"
)

class CompletionProvider(
    private val context: Context,
    private val translationDict: TranslationDict,
    private val valueSectionProperties: Map<String, List<CodeReferenceRepository.PropertyInfo>> = emptyMap(),
    private val customCompletions: List<CompletionItem> = emptyList(),
    private val nativeCompletions: List<CompletionItem> = emptyList(),
    private val showDetail: Boolean = true,
    private val valueCompletionEnabled: Boolean = false,
    private val valueCompletionOptions: ValueCompletionAggregator.ValueCompletionOptions = ValueCompletionAggregator.ValueCompletionOptions(),
    private val nonValueCompletionLimited: Boolean = true
) {

    companion object {
        internal val AUTO_COMPLETE_TRIGGER_CHARS = setOf('.', '(', '{', '[', '=', '+', ':', '*', '/', '-', '%', '<', '>', ',', '，', ')', ']')
    }

    /**
     * 文件级符号缓存（由 EditorScreen 在后台维护）。
     * 非 null 时值补全直接读取，避免每次按键全文扫描。
     */
    var fileSymbols: ProjectTagScanner.ProjectTagInfo? = null

    /** 继承链符号（仅父文件/模板，供 @copyFromSection 等只查继承链的补全使用）；由 EditorScreen 后台维护。 */
    var chainSymbols: ProjectTagScanner.ProjectTagInfo? = null

    private val valueAggregator by lazy {
        ValueCompletionAggregator(context, valueSectionProperties, valueCompletionOptions, translationDict)
    }

    data class CompletionItem(
        val label: String,
        val type: CompletionType,
        val detail: String = "",
        val insertText: String = "",
        val category: List<String> = emptyList(),
        /**
         * 值补全专用：只替换 `:` 后已经输入的字符数。
         * 其他类型保持 0，由调用方使用默认 prefixLength。
         */
        val valuePrefixLength: Int = 0,
        /**
         * 是否来自用户自定义补全表。用于排序时把用户表项置顶。
         */
        val isUserCompletion: Boolean = false,
        /**
         * 原始名称（CustomCompletion.name），可能是中文或英文。
         * 用于翻译库兜底去重，不参与自动补全匹配。
         */
        val name: String = "",
        /**
         * 原始英文 key（CustomCompletion.nameEn），仅用于翻译库兜底去重，不参与自动补全匹配。
         */
        val nameEn: String = "",
        /**
         * 值补全专用：该条目产生的原始值类型（如 unit/float/bool/any）。
         * 供补全查看器按「补全值类型」归类展示；非值补全或未知类型保持空串。
         */
        val valueType: String = "",
        /**
         * 是否为「调用方」（补全后还能继续调用其他代码：单位标记可继续 `.成员`、
         * 带参函数参数内可嵌套表达式）。无参取值 getter（自身弹药/自身血量）为「被调用方」。
         * 供补全查看器的 DemoPanel 只展示调用方；非值补全或默认保留（true）。
         */
        val isCallable: Boolean = true
    )

    enum class CompletionType { SECTION, KEY, VALUE, TEMPLATE }

    /**
     * 节名映射表：英文节名 / 中文节名 到 统一中文分类。
     * 支持 core / core_1 / global_resource_xxx / 全局资源 / 全局资源_xxx 等形式。
     */
    private val knownZhSections = sectionEnToZh.values.toSet()
    // 预计算小写中文节名集合，避免每次补全都重建
    private val lowerZhSections = knownZhSections.map { it.lowercase() }.toSet()

    private fun mapSectionName(name: String): String {
        val trimmed = name.trim()
        val normalized = trimmed.lowercase()
        val normalizedNoSpace = normalized.replace(" ", "")

        // 1) 精确匹配已知中文分类（不区分大小写 / 忽略空格）
        if (normalized in lowerZhSections || normalizedNoSpace in lowerZhSections) {
            return knownZhSections.first { it.lowercase() == normalized || it.lowercase() == normalizedNoSpace }
        }

        // 2) 去掉下划线后缀后再匹配，如 global_resource_xxx -> 全局资源，核心_1 -> 核心
        val underscoreStripped = normalized.substringBefore('_')
        if (underscoreStripped in lowerZhSections) {
            return knownZhSections.first { it.lowercase() == underscoreStripped }
        }
        // 英文下划线后缀：core_1 -> 核心
        sectionEnToZh[underscoreStripped]?.let { return it }

        // 3) 包含已知中文分类，如 全局资源_xxx -> 全局资源
        for (zh in knownZhSections) {
            val low = zh.lowercase()
            if (normalized.contains(low) || normalizedNoSpace.contains(low)) return zh
        }

        // 4) 英文完整匹配或包含，如 global_resource_xxx -> 全局资源
        sectionEnToZh[normalized]?.let { return it }
        for ((en, zh) in sectionEnToZh) {
            val low = en.lowercase()
            if (normalized.contains(low) || normalizedNoSpace.contains(low)) return zh
        }
        return trimmed
    }

    private fun List<CompletionItem>.withDetail(): List<CompletionItem> {
        return if (showDetail) this else map { it.copy(detail = "") }
    }

    // 用户表覆盖原生表，且用户表优先显示
    private val mergedCustom by lazy {
        val customLabels = customCompletions.map { it.label }.toSet()
        customCompletions.map { it.copy(isUserCompletion = true) } +
                nativeCompletions.filter { it.label !in customLabels }
    }

    // 翻译库 key 合并去重，启动后字典不变，只算一次
    private val allDictKeys by lazy {
        (translationDict.getAllEnglishKeys() + translationDict.getAllChineseKeys()).toSet()
    }

    fun getCompletions(
        textBeforeCursor: String,
        textAfterCursor: String,
        cursorPosition: Int,
        currentSectionName: String?,
        sectionFilters: Map<String, Set<String>> = emptyMap(),
        sectionCompletionEnabled: Boolean = false
    ): List<CompletionItem> {
        val results = mutableListOf<CompletionItem>()

        // 提取光标前的字符，遇到换行、空格、括号或指定触发符号则停止，不限制长度
        var end = textBeforeCursor.length
        while (end > 0) {
            val ch = textBeforeCursor[end - 1]
            if (ch == '\n' || ch == ' ' || ch in AUTO_COMPLETE_TRIGGER_CHARS) break
            end--
        }
        val rawPrefix = textBeforeCursor.substring(end)

        // @memory 指令定义行（@memory 名字:类型）：变量名/类型补全优先处理。
        // 必须在值补全之前拦截——否则 `@memory 遍历次数:` 会被 splitKeyValueLine 当成「属性:值」，
        // 因「空值未知键兜底抑制」提前 return，导致冒号后的内存类型补全永远不可达。
        val currentLineForMemory = textBeforeCursor.substringAfterLast('\n')
        val memoryDefMatch = memoryDefLineRegex.find(currentLineForMemory)
        if (memoryDefMatch != null) {
            val afterMemory = memoryDefMatch.groupValues[1]
            val colonIdx = afterMemory.indexOf(':')
            if (colonIdx >= 0) {
                // 类型补全阶段：@memory name: → 提示类型
                val typePrefix = afterMemory.substring(colonIdx + 1).trim()
                memoryTypes.forEach { type ->
                    if (completionMatchLevel(typePrefix, type) > 0) {
                        results.add(CompletionItem(label = type, type = CompletionType.KEY, detail = "内存类型", insertText = type, category = listOf("指令")))
                    }
                }
            } else {
                // 变量名补全阶段：@memory na → 提示常用变量名
                val namePrefix = afterMemory.trim()
                memoryVarNames.forEach { name ->
                    if (completionMatchLevel(namePrefix, name) > 0) {
                        results.add(CompletionItem(label = name, type = CompletionType.KEY, detail = "内存变量名", insertText = "$name:", category = listOf("指令")))
                    }
                }
            }
            return results.withDetail()
        }

        // 是否满足自动补全触发条件：有前缀、行首、前一个是空白，或前一个是触发符号
        val isAutoCompleteTriggered = rawPrefix.isNotEmpty() ||
                textBeforeCursor.isEmpty() ||
                textBeforeCursor.last().isWhitespace() ||
                textBeforeCursor.last() in AUTO_COMPLETE_TRIGGER_CHARS

        // 直接使用编辑器传入的实际节名（顶部节名显示已保证正确），不再内部解析
        val mappedSection = currentSectionName?.let { mapSectionName(it) }

        // : 后的值补全（基于当前行，避免 rawPrefix 被空格截断导致识别失败）
        val valueResults = mutableListOf<CompletionItem>()
        var suppressEntryTokens = false
        var suppressLogicSyntax = false
        var includeOperators = true
        var afterCompleteValue = false
        if (valueCompletionEnabled) {
            val currentLine = textBeforeCursor.substringAfterLast('\n')
            // 逻辑值入口抑制：当前值已出现 if/真/假 后，不再提示这三个
            suppressEntryTokens = logicValueHasEntryToken(currentLine)
            // 关键字后（if/and/or/not）不再提示 真/假/if/and/or/not 与运算符
            suppressLogicSyntax = valueEndsWithLogicKeyword(currentLine)
            // 运算符是二元的：仅当光标前最后一个 token 是完整操作数时才提示运算符；
            // 完整值后也不再提示表达式起始关键字（if/真/假/not）
            includeOperators = hasCompleteValueBeforeCursor(currentLine)
            afterCompleteValue = includeOperators
            // 键/值分隔符取第一个 ':'（INI 键名不含冒号），与 SoraCodeEditor 的触发端保持一致；
            // 值内可含 ':'（如 ROOT:、CUSTOM:），取最后一个会把键名切错导致补全失效。
            val (keyPart, rawValuePrefix) = splitKeyValueLine(currentLine)
            if (keyPart.isNotEmpty()) {
                // 值补全只替换当前正在输入的"片段"，而非从 ':' 到光标的全部内容。
                // 以 ','、'，'、'(' 或空白作为分隔，找到最后一个分隔符后的可替换前缀。
                // 例如 "防御建筑模版(偏移量x=10," 只需替换空串（光标紧跟在 ',' 后），
                // "防御建筑模版(偏移量x=10,偏移量y" 只需替换 "偏移量y"。
                val replaceablePrefix = replaceableValuePrefix(rawValuePrefix)
                val valuePrefix = replaceablePrefix.trim()
                val textAfterCursorInLine = textAfterCursor.takeWhile { it != '\n' }
                // 优先使用文件级符号缓存（EditorScreen 后台维护，含继承链 ∪ 当前文件），
                // 未就绪时回退当前文件全文扫描；中文视图下用翻译库反向归一化节名/键名，不硬编码中文
                // 继承链节名（@copyFromSection 只用继承链，不扫项目全量）
                val chainSectionNames = chainSymbols?.sectionNames ?: emptyMap()
                val fallbackSymbols = fileSymbols ?: extractFileSymbols(
                    textBeforeCursor,
                    sectionToEnglish = { translationDict.getSectionTranslationBack(it) },
                    keyToEnglish = { translationDict.getTranslationBack(it) }
                )
                val memoryNames = fallbackSymbols.memories
                val memoryTypes = fallbackSymbols.memoryTypes
                // 全局变量（@global，项目级，${} 引用）
                val globalVariables = fallbackSymbols.globalVariables
                // 局部变量（@define）：仅当前节 + 继承链同节（"继承链的当前节"），不扫全项目其他节
                val currentSectionLower = currentSectionName?.trim()?.lowercase().orEmpty()
                val localVariables = (fallbackSymbols.sectionDefines[currentSectionLower] ?: emptySet()) +
                    (chainSymbols?.sectionDefines?.get(currentSectionLower) ?: emptySet())
                val tags = fallbackSymbols.tags
                val globalTags = fallbackSymbols.globalTags
                val messageTags = fallbackSymbols.messageTags
                val resources = fallbackSymbols.resources
                val globalResources = fallbackSymbols.globalResources
                val unitNames = fallbackSymbols.unitNames
                val sectionNames = fallbackSymbols.sectionNames
                val turretNames = sectionNames["turret"] ?: emptySet()
                val projectileNames = sectionNames["projectile"] ?: emptySet()
                val effectNames = sectionNames["effect"] ?: emptySet()
                // actionNames 为纯「行动」节名；隐藏行动节名单独存 hiddenActionNames（供 复制节 按节类型过滤）
                val actionNames = sectionNames["action"] ?: emptySet()
                val hiddenActionNames = sectionNames["hiddenaction"] ?: emptySet()
                val animationNames = sectionNames["animation"] ?: emptySet()
                val decalNames = sectionNames["decal"] ?: emptySet()
                val attachmentNames = sectionNames["attachment"] ?: emptySet()
                val buildableNames = sectionNames["canbuild"] ?: emptySet()
                // 声音引用：走音频文件扫描缓存（相对路径，不带 ROOT:）
                val soundFiles = run {
                    val projectPath = SettingsManager.defaultPath.takeIf { it.isNotBlank() }
                        ?: SettingsManager.lastPath.takeIf { it.isNotBlank() }
                        ?: ""
                    if (projectPath.isBlank()) emptySet() else ProjectSoundCache.query(projectPath, valuePrefix).toSet()
                }
                val valueCandidates = valueAggregator.getValueCompletions(
                    context = context,
                    propertyName = keyPart,
                    sectionName = mappedSection,
                    valuePrefix = valuePrefix,
                    rawValuePrefixLength = replaceablePrefix.length,
                    lineText = currentLine + textAfterCursorInLine,
                    textBeforeCursor = currentLine,
                    textAfterCursor = textAfterCursorInLine,
                    memoryNames = memoryNames,
                    memoryTypes = memoryTypes,
                    globalVariables = globalVariables,
                    localVariables = localVariables,
                    tags = tags,
                    globalTags = globalTags,
                    messageTags = messageTags,
                    actionTags = fallbackSymbols.actionTags,
                    resources = resources,
                    globalResources = globalResources,
                    unitNames = unitNames,
                    turretNames = turretNames,
                    projectileNames = projectileNames,
                    effectNames = effectNames,
                    actionNames = actionNames,
                    hiddenActionNames = hiddenActionNames,
                    animationNames = animationNames,
                    decalNames = decalNames,
                    attachmentNames = attachmentNames,
                    buildableNames = buildableNames,
                    soundFiles = soundFiles,
                    chainSectionNames = chainSectionNames
                )
                // 同 label 去重：self.readUnitMemory() 与 readUnitMemory 翻译后同为 读取单位内存，
                // 避免同一补全重复出现两行（同 label 即同插入文本）
                valueResults.addAll(valueCandidates.distinctBy { it.label })
                // ${ 变量引用：值片段含 ${（不管 $ 前面是什么，如 攻击范围=${攻）只显示变量补全，
                // 不做通用 KEY/翻译库兜底，避免混入无关项；候选为空则空
                if (valuePrefix.contains("\${")) {
                    return sortCompletions(valueResults).withDetail()
                }
                // 值上下文：光标紧跟触发符（. / , / ( / = / 空格等）之后且值补全已有结果时，
                // 值补全已按 valuePrefix 给出结果，直接显示，不再运行通用 KEY/翻译库兜底
                // （避免 也可以使用单位参考触发或队列操作: 刷出整张自定义补全表、自身资源. 后刷出
                // @define、真/假/if 等无关项）。空值片段（冒号后）但有结果同样早退，只返回值补全。
                if (shouldReturnValueOnlyForValueFragment(rawPrefix.isEmpty(), valuePrefix.isNotEmpty(), valueResults.isNotEmpty(), valuePrefix.contains('.'))) {
                    return sortCompletions(valueResults).withDetail()
                }
            }

            // 括号内：已知参数函数（参数表命中）无论是否已输入前缀都只显示值结果；
            // 未知函数在空前缀时也只显示值结果（原有行为），避免通用 KEY/翻译库兜底刷全表
            val insideParens = isInsideParentheses(currentLine)
            val knownParamFn = isKnownParamFunctionContext(currentLine, context, translationDict)
            if (shouldReturnValueOnlyInParens(keyPart, insideParens, knownParamFn, rawPrefix.isEmpty())) {
                return if (valueResults.isNotEmpty()) sortCompletions(valueResults).withDetail() else emptyList()
            }
            // 空值未知键：无输入前缀且值补全无结果时不刷全表（有前缀仍走按前缀过滤的通用兜底）
            if (shouldSuppressEmptyValueFallback(keyPart, rawPrefix.isEmpty(), valueResults.isEmpty())) {
                return emptyList()
            }
        }

        // [ 节名补全：rawPrefix 之前紧跟 [ 或光标前直接就是 [
        val hasOpenBracket = textBeforeCursor.isNotEmpty() && textBeforeCursor.last() == '['
        if (rawPrefix.isEmpty() && hasOpenBracket) {
            return getSectionCompletions("").withDetail()
        }
        if (rawPrefix.isNotEmpty()) {
            val prefixStart = textBeforeCursor.length - rawPrefix.length
            if (prefixStart > 0 && textBeforeCursor[prefixStart - 1] == '[') {
                return getSectionCompletions(rawPrefix).withDetail()
            }
        }

        // 节补全：光标在节内且当前行无内容时自动弹出当前节允许的分类属性
        val currentLineBefore = textBeforeCursor.substringAfterLast('\n')
        val currentLineFull = currentLineBefore + textAfterCursor.takeWhile { it != '\n' }
        // 节名优先用 EditorScreen 解析出的 currentSectionName；若为空（个别文件节解析未就绪/失败），
        // 回退用光标前文本现场定位当前节，保证空行节补全不再依赖外部节状态。
        val sectionForBody = currentSectionName?.takeIf { it.isNotBlank() }
            ?: fallbackSectionName(textBeforeCursor)
        val isInSectionBody = sectionForBody != null &&
                currentLineBefore.trim().isEmpty() &&
                !currentLineFull.trim().startsWith("[") &&
                sectionCompletionEnabled
        if (isInSectionBody) {
            val body = getSectionBodyCompletions(sectionForBody!!, sectionFilters)
            Log.d("RW_SECTION_COMPL", "sectionBody call section=$sectionForBody mapped=${mapSectionName(sectionForBody)} filters=${sectionFilters.keys} result=${body.size}")
            return body.withDetail()
        }

        if (rawPrefix.isEmpty() && valueResults.isEmpty() && !isAutoCompleteTriggered) return emptyList()

        val wordPrefix = rawPrefix

        // @ 补全
        if (wordPrefix.startsWith("@")) {
            listOf("@global" to "全局变量", "@define" to "局部变量", "@memory" to "内存变量").forEach { (cmd, desc) ->
                if (cmd.startsWith(wordPrefix, ignoreCase = true)) {
                    results.add(CompletionItem(label = cmd, type = CompletionType.KEY, detail = desc, insertText = "$cmd ", category = listOf("指令")))
                }
            }
            return results.withDetail()
        }

        val enabledCategories = mappedSection?.let { sectionFilters[it] }

        // 自动补全主源：只从自定义补全表（native + user）查询
        // 前缀命中优先，子串包含兜底（≥2字符），保证「中间命中」也能弹出且前缀项置顶
        val customResults = mergedCustom
            .map { it to completionMatchLevel(wordPrefix, it.label) }
            .filter { it.second > 0 && (enabledCategories == null || it.first.category.any { c -> c in enabledCategories }) }
            .sortedWith(
                compareByDescending<Pair<CompletionItem, Int>> { it.second }
                    .thenBy { it.first.label }
            )
            .map { it.first }
        results.addAll(customResults)

        // 翻译库兜底：只有当补全表（含原始 name、原始 nameEn、显示 label）中确实不存在该 key 时才参与；
        // 仅在输入了前缀时按前缀兜底（情况②），空前缀不 dump 字典（避免 @define/@global/@copyFromSection 等噪音）
        // 前缀命中优先，子串包含兜底（≥2字符）
        val matched = if (wordPrefix.isNotEmpty()) {
            allDictKeys.map { it to completionMatchLevel(wordPrefix, it) }
                .filter { it.first.length > 1 && it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<String, Int>> { it.second }
                        .thenBy { it.first }
                )
                .take(20)
                .map { it.first }
        } else {
            emptyList()
        }
        // 值上下文（行内已有 键:）中翻译库兜底插裸词，键位置插 键:
        val translateInValueContext = valueCompletionEnabled && splitKeyValueLine(currentLineBefore).first.isNotEmpty()
        for (key in matched) {
            // 检查补全表中是否已存在该 key（原始 name、原始英文 nameEn、显示 label）
            if (mergedCustom.any { it.name == key || it.nameEn == key || it.label == key }) continue
            if (results.any { it.name == key || it.nameEn == key || it.label == key }) continue
            val insert = if (translateInValueContext) key else "$key:"
            results.add(CompletionItem(label = key, type = CompletionType.KEY, detail = "翻译库", insertText = insert, category = listOf("翻译库")))
        }
        // 值上下文：值补全返回的词若在翻译库且补全表没有，补一个翻译库项
        // （解决空前缀下 自身资源 这类词只有值补全、来源/排序不对的问题）
        if (translateInValueContext) {
            for (v in valueResults) {
                val key = v.label
                // 仅当该词确实存在于翻译库时才补「翻译库」项，避免自定义资源名等被误标来源
                if (!shouldBridgeToTranslationLibrary(key, allDictKeys)) continue
                if (mergedCustom.any { it.name == key || it.nameEn == key || it.label == key }) continue
                if (results.any { it.name == key || it.nameEn == key || it.label == key }) continue
                results.add(CompletionItem(label = key, type = CompletionType.KEY, detail = "翻译库", insertText = key, category = listOf("翻译库")))
            }
        }

        // 非值限制补全：光标所在行已存在键值时，移除非 VALUE 且非 values/特定值 分类的普通键补全
        val finalResults = if (nonValueCompletionLimited) {
            val (keyBeforeColon, _) = splitKeyValueLine(currentLineBefore)
            val hasKeyBeforeCursor = keyBeforeColon.isNotEmpty()
            if (hasKeyBeforeCursor) {
                valueResults + filterValueContextItems(results)
            } else {
                valueResults + results
            }
        } else {
            valueResults + results
        }
        // 同名去重：用户表/补全表（非 VALUE）优先于值补全（VALUE），同 label 时保留 KEY 丢弃 VALUE
        val deduped = dedupeValueByKeyPriority(valueResults, finalResults)
        // 排序：与值补全同名的项按值补全原始顺序置顶（恢复 真/假/if/and/or 靠前）
        val ordered = orderByValueCollision(deduped, valueResults.map { it.label })
        // 逻辑值入口抑制：值里已有 if/真/假 后不再提示这三个，避免 `if ... if ...` 重复入口
        val entryFiltered = filterLogicEntryTokens(ordered, suppressEntryTokens)
        // 关键字后不提示语法项（真/假/if/and/or/not/运算符）
        val syntaxFiltered = filterLogicSyntaxItems(entryFiltered, suppressLogicSyntax)
        // 完整值后不提示表达式起始关键字（if/真/假/not）
        val valueFiltered = filterStartersAfterValue(syntaxFiltered, afterCompleteValue)
        // 运算符需要左操作数：无完整操作数时不提示运算符
        return filterOperatorItems(valueFiltered, includeOperators).withDetail()
    }

    private fun getSectionCompletions(prefix: String): List<CompletionItem> {
        val sections = listOf(
            "核心" to "core", "图像" to "graphics", "攻击" to "attack",
            "炮塔_" to "turret_", "抛射体_" to "projectile_", "运动" to "movement",
            "行动_" to "action_", "隐藏行动_" to "hiddenAction_", "效果_" to "effect_",
            "动画_" to "animation_", "附属_" to "attachment_", "可建造_" to "canBuild_",
            "贴花" to "decal", "资源_" to "resource_", "全局资源_" to "global_resource_",
            "leg_" to "leg_", "放置规则_" to "placementRule_"
        )
        return sections.mapNotNull { (zh, en) ->
            val level = completionMatchLevel(prefix, zh)
            if (level <= 0) null else Triple(zh, en, level)
        }.sortedWith(compareByDescending<Triple<String, String, Int>> { it.third })
        .map { (zh, en) ->
            CompletionItem(label = "[$zh]", type = CompletionType.SECTION, detail = en, insertText = "[$zh]", category = listOf("节"))
        }
    }

    /**
     * 节补全：返回当前节允许分类下的所有属性，供空行时直接选择。
     */
    private fun getSectionBodyCompletions(
        activeSection: String,
        sectionFilters: Map<String, Set<String>>
    ): List<CompletionItem> {
        val mappedSection = mapSectionName(activeSection)
        val enabledCategories = sectionFilters[mappedSection]
        val results = mutableListOf<CompletionItem>()
        val seen = mutableSetOf<String>()

        // 节补全只从自定义补全表（native + user）查询
        for (item in mergedCustom) {
            if (enabledCategories != null && !item.category.any { c -> c in enabledCategories }) continue
            if (item.label in seen) continue
            seen.add(item.label)
            results.add(item.copy(detail = if (showDetail) item.detail else ""))
        }

        return sortCompletions(results)
    }

    /**
     * 兜底定位当前节：从光标前文本反向找最后一个 [节] 行。
     * 用于 currentSectionName 未解析/迟滞（如节解析失败或未就绪）时仍能触发空行节补全。
     * 只扫描光标前最近若干行，避免大文件每次都全量遍历。
     */
    private fun fallbackSectionName(textBeforeCursor: String): String? {
        val beforeLines = textBeforeCursor.lines()
        val start = (beforeLines.size - 512).coerceAtLeast(0)
        for (i in beforeLines.lastIndex downTo start) {
            val t = beforeLines[i].trim()
            if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) {
                val n = t.substring(1, t.length - 1).trim()
                if (n.isNotEmpty()) return n
            }
        }
        return null
    }

    private fun extractDefaultValue(example: String, propName: String): String {
        if (example.isEmpty()) return ""
        val colonIdx = example.indexOf(':')
        if (colonIdx >= 0) {
            return example.substring(colonIdx + 1).trim().substringBefore(",").substringBefore("#").trim()
        }
        return ""
    }

    /**
     * 排序补全结果：
     * 1. 值补全（VALUE 类型）
     * 2. 自动补全非 values/特定值
     * 3. 自动补全 values/特定值
     * 组内不再按字母排序，保持原有顺序。
     */
    private fun sortCompletions(items: List<CompletionItem>): List<CompletionItem> {
        fun isValueCategory(item: CompletionItem): Boolean {
            return item.category.any { it == "values" || it == "特定值" }
        }
        return items.sortedWith(compareBy {
            when {
                it.type == CompletionType.VALUE -> 0
                !isValueCategory(it) -> 1
                else -> 2
            }
        })
    }

    // --- @memory 补全辅助 ---

    /** 匹配当前行 @memory 后的内容（变量名或类型阶段），仅行首匹配 */
    private val memoryDefLineRegex = Regex("""^\s*@memory\s+(.*)""", RegexOption.IGNORE_CASE)

    /** 从全文提取所有 memory 变量名：
     *  1. @memory name:type 指令
     *  2. defineUnitMemory: type name, type name, ... 声明 */

    /** @memory 支持的类型 */
    private val memoryTypes = listOf(
        "int", "float", "string", "bool", "boolean", "number", "text", "logic",
        "unit", "boolean[]", "float[]", "number[]", "unit[]"
    )

    /** @memory 常用变量名建议 */
    private val memoryVarNames = listOf(
        "customHp", "maxHp", "currentHp", "damage", "unitName", "unitType",
        "playerId", "teamId", "isAlive", "isMoving", "isAttacking", "isDamaged",
        "customValue1", "customValue2", "tempVar"
    )
}

// ===== 文件级符号提取（供 CompletionProvider 与 EditorScreen 共用） =====

private fun extractMemoryNames(fullText: String): Set<String> {
    val names = linkedSetOf<String>()
    // 1. @memory name:type
    memoryDefAtRegex.findAll(fullText).forEach { match ->
        val name = match.groupValues[1].trim()
        if (name.isNotBlank()) names.add(name)
    }
    // 2. defineUnitMemory: boolean var1, float var2, unit[] var3, ...
    val defMemRegex = Regex("""(?:defineUnitMemory|定义单位内存)\s*:\s*(.+?)(?:\r?\n|$)""", RegexOption.IGNORE_CASE)
    defMemRegex.find(fullText)?.let { match ->
        val declarations = match.groupValues[1].replace("\r", "").replace("\n", " ").trim()
        names.addAll(ProjectTagScanner.parseDefineUnitMemory(declarations))
    }
    return names
}

/** 提取 内存变量名→声明类型（@memory 名:类型 / defineUnitMemory: 类型 名），供 unit 型内存变量链式补全 */
private fun extractMemoryTypes(fullText: String): Map<String, String> {
    val types = linkedMapOf<String, String>()
    // 1. @memory name:type
    memoryDefAtTypedRegex.findAll(fullText).forEach { match ->
        val name = match.groupValues[1].trim()
        val type = match.groupValues[2].trim()
        if (name.isNotBlank() && type.isNotBlank()) types[name] = type
    }
    // 2. defineUnitMemory: boolean var1, float var2, unit[] var3, ...
    val defMemRegex = Regex("""(?:defineUnitMemory|定义单位内存)\s*:\s*(.+?)(?:\r?\n|$)""", RegexOption.IGNORE_CASE)
    defMemRegex.find(fullText)?.let { match ->
        val declarations = match.groupValues[1].replace("\r", "").replace("\n", " ").trim()
        types.putAll(ProjectTagScanner.parseDefineUnitMemoryTyped(declarations))
    }
    return types
}

/** 全局匹配 @memory name:type */
private val memoryDefAtRegex = Regex("""@memory\s+(\S+)\s*:""", RegexOption.IGNORE_CASE)
/** 全局匹配 @memory name:type（捕获类型） */
private val memoryDefAtTypedRegex = Regex("""@memory\s+(\S+)\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)

// --- 标签/资源/单位名 提取辅助 ---

/** 从全文匹配 tags:/tempTagAdd: 值（本地标签） */
private val localTagRegex = Regex("""^\s*(?:tags|tempTagAdd|temp_tag_add|临时标签添加)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
/** 仅匹配 tags: 属性值（行动标签 withActionTag 只认行动节里的 tags，不含 tempTagAdd 等其它标签类属性） */
private val actionTagLineRegex = Regex("""^\s*tags\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
/** 从全文匹配 addGlobalTag:/addGlobalTeamTags: 等值（全局标签） */
private val globalTagRegex = Regex("""^\s*(?:addGlobalTag|add_global_tag|addGlobalTags|add_global_tags|addGlobalTeamTags|add_global_team_tags|添加全局标签)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
/** 从全文匹配 sendMessageWithTags:/带标签发送消息: 值（消息标签） */
private val messageTagRegex = Regex("""^\s*(?:sendMessageWithTags|带标签发送消息)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
/** 从全文匹配 [resource_xxx] / [资源_xxx] 节头（本地资源） */
private val localResourceSectionRegex = Regex("""^\[(?:resource|资源)_([^\]]+)\]$""", RegexOption.IGNORE_CASE)
/** 从全文匹配 [global_resource_xxx] / [全局资源_xxx] 节头（全局资源） */
private val globalResourceSectionRegex = Regex("""^\[(?:global_resource|globalResource|全局资源)_([^\]]+)\]$""", RegexOption.IGNORE_CASE)
/** 通用节头检测 */
private val sectionHeaderRegex = Regex("""^\[([^\]]+)\]$""")
/** @define / @global 变量定义行（@global 全局变量、@define 局部变量，${} 引用） */
private val defineVarRegex = Regex("""^\s*@(define|global)\s+(\S+?)\s*:""", RegexOption.IGNORE_CASE)
/**
 * 从当前文件全文提取五类符号（不合并项目级缓存）。
 * [sectionToEnglish] / [keyToEnglish] 用于把中文视图下的节名/键名归一化回英文（默认恒等，保持英文行为）；
 * 生产调用方传入翻译库反向查询，避免硬编码中文节名/键名。
 */
fun extractCurrentFileSymbols(
    fullText: String,
    sectionToEnglish: (String) -> String = { it },
    keyToEnglish: (String) -> String = { it }
): ProjectTagScanner.ProjectTagInfo {
    val memoryNames = extractMemoryNames(fullText)
    val memoryTypes = extractMemoryTypes(fullText)
    val fileTags = linkedSetOf<String>()
    val fileGlobalTags = linkedSetOf<String>()
    val fileMessageTags = linkedSetOf<String>()
    val fileResources = linkedSetOf<String>()
    val fileGlobalResources = linkedSetOf<String>()
    val fileUnitNames = linkedSetOf<String>()
    val fileSectionNames = linkedMapOf<String, MutableSet<String>>()
    val fileGlobalVariables = linkedSetOf<String>()
    val fileSectionDefines = linkedMapOf<String, MutableSet<String>>()
    val fileActionTags = linkedSetOf<String>()

    var inCoreSection = false
    var inActionSection = false
    var currentSection = ""
    fullText.split('\n').forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach

        val sectionMatch = sectionHeaderRegex.find(line)
        if (sectionMatch != null) {
            currentSection = sectionMatch.groupValues[1].trim().lowercase()
            inCoreSection = sectionToEnglish(sectionMatch.groupValues[1].trim())
                .equals("core", ignoreCase = true)
            localResourceSectionRegex.find(line)?.let { fileResources.add(it.groupValues[1].trim()) }
            globalResourceSectionRegex.find(line)?.let { fileGlobalResources.add(it.groupValues[1].trim()) }
            // 命名节（炮塔/抛射体/效果/行动/动画/贴花/附属/可建造）节名收集，供引用名补全
            ProjectTagScanner.parseNamedSectionLine(line)?.let { (base, name) ->
                fileSectionNames.getOrPut(base) { linkedSetOf() }.add(name)
            }
            // 行动标签范围：仅 行动/隐藏行动 节内的 tags 值（withActionTag 引用，非整文件 tags）
            inActionSection = ProjectTagScanner.parseNamedSectionLine(line)?.first
                ?.let { it == "action" || it == "hiddenaction" } ?: false
            return@forEach
        }

        // @define / @global 变量：@global 全局变量（项目级）、@define 按当前节归属（仅当前节+继承链可见）
        defineVarRegex.find(line)?.let { m ->
            val name = m.groupValues[2].trim()
            if (name.isNotBlank()) {
                if (m.groupValues[1].equals("global", ignoreCase = true)) {
                    fileGlobalVariables.add(name)
                } else {
                    fileSectionDefines.getOrPut(currentSection) { linkedSetOf() }.add(name)
                }
            }
            return@forEach
        }

        localTagRegex.find(line)?.let { m ->
            val isPureTagAttr = actionTagLineRegex.find(line) != null
            val values = splitValues(m.groupValues[1])
            if (isPureTagAttr) {
                // 纯 tags: 属性按节归属：核心节→单位标签，行动节→行动标签
                if (inCoreSection) fileTags.addAll(values)
                if (inActionSection) fileActionTags.addAll(values)
            } else {
                // tempTagAdd/临时标签添加：任意节，进单位标签（未限定范围）
                fileTags.addAll(values)
            }
        }
        globalTagRegex.find(line)?.let { m ->
            splitValues(m.groupValues[1]).forEach { fileGlobalTags.add(it) }
        }
        messageTagRegex.find(line)?.let { m ->
            splitValues(m.groupValues[1]).forEach { fileMessageTags.add(it) }
        }
        if (inCoreSection) {
            // 单位名键：name（或其翻译后的中文，如「名称」），经 keyToEnglish 归一化后比较，不硬编码中文
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                if (keyToEnglish(key).equals("name", ignoreCase = true)) {
                    splitValues(line.substring(colonIdx + 1)).forEach { fileUnitNames.add(it) }
                }
            }
        }
    }

    return ProjectTagScanner.ProjectTagInfo(
        tags = fileTags,
        globalTags = fileGlobalTags,
        messageTags = fileMessageTags,
        resources = fileResources,
        globalResources = fileGlobalResources,
        memories = memoryNames,
        unitNames = fileUnitNames,
        references = emptyMap(),
        sectionNames = fileSectionNames.mapValues { it.value.toSet() },
        memoryTypes = memoryTypes,
        globalVariables = fileGlobalVariables,
        sectionDefines = fileSectionDefines.mapValues { it.value.toSet() },
        actionTags = fileActionTags
    )
}

/**
 * 合并当前文件符号与继承链符号：
 * resources/globalResources/memories/memoryTypes/sectionDefines 取并集（Set/Map 去重，current 优先），
 * globalVariables 取并集（继承链文件的 @global 也可引用），其余字段沿用 current。
 */
fun mergeCompletionSymbols(
    current: ProjectTagScanner.ProjectTagInfo,
    chain: ProjectTagScanner.ProjectTagInfo?
): ProjectTagScanner.ProjectTagInfo {
    if (chain == null) return current
    return current.copy(
        resources = current.resources + chain.resources,
        globalResources = current.globalResources + chain.globalResources,
        memories = current.memories + chain.memories,
        sectionNames = mergeSectionNames(current.sectionNames, chain.sectionNames),
        memoryTypes = current.memoryTypes + chain.memoryTypes,
        globalVariables = current.globalVariables + chain.globalVariables,
        sectionDefines = mergeSectionDefines(current.sectionDefines, chain.sectionDefines),
        actionTags = current.actionTags + chain.actionTags
    )
}

/** 合并两组节级局部变量（@define）：同节取并集 */
private fun mergeSectionDefines(
    current: Map<String, Set<String>>,
    extra: Map<String, Set<String>>?
): Map<String, Set<String>> {
    if (extra.isNullOrEmpty()) return current
    val result = current.toMutableMap()
    for ((section, names) in extra) {
        result[section] = (result[section] ?: emptySet()) + names
    }
    return result
}

/** 逗号分隔值拆分（去除行内注释） */
private fun splitValues(raw: String): List<String> {
    val commentIdx = raw.indexOf('#')
    val value = if (commentIdx >= 0) raw.substring(0, commentIdx) else raw
    return value.split(',', '，').map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("$") && !it.startsWith("@") }
}

/**
 * 提取单个文件的符号（内存变量/标签/资源/单位名/命名节名/全局变量/局部变量），供 EditorScreen 后台缓存。
 * 标签/全局标签/消息标签/单位名/全局变量（@global）合并 ProjectTagScanner 项目级缓存（跨文件引用合法，
 * 全局变量如 fw/also 在别的文件定义被大量 ${} 引用）；
 * 资源/全局资源/内存/命名节名/局部变量（@define，按节）仅当前文件（继承链部分由 mergeCompletionSymbols 叠加），
 * 避免节名引用（炮塔/效果/行动等 ref）提示项目里无关文件定义的节。
 */
fun extractFileSymbols(
    fullText: String,
    sectionToEnglish: (String) -> String = { it },
    keyToEnglish: (String) -> String = { it }
): ProjectTagScanner.ProjectTagInfo {
    val current = extractCurrentFileSymbols(fullText, sectionToEnglish, keyToEnglish)
    val cached = ProjectTagScanner.getCachedInfo()
    return current.copy(
        tags = (cached?.tags ?: emptySet()) + current.tags,
        globalTags = (cached?.globalTags ?: emptySet()) + current.globalTags,
        messageTags = (cached?.messageTags ?: emptySet()) + current.messageTags,
        unitNames = (cached?.unitNames ?: emptySet()) + current.unitNames,
        memoryTypes = current.memoryTypes + (cached?.memoryTypes ?: emptyMap()),
        globalVariables = (cached?.globalVariables ?: emptySet()) + current.globalVariables
    )
}

/** 合并两组命名节名：同基名取并集 */
private fun mergeSectionNames(
    current: Map<String, Set<String>>,
    extra: Map<String, Set<String>>?
): Map<String, Set<String>> {
    if (extra.isNullOrEmpty()) return current
    val result = current.toMutableMap()
    for ((base, names) in extra) {
        result[base] = (result[base] ?: emptySet()) + names
    }
    return result
}

/**
 * 解析「键: 值」行：取第一个 ':' 切分键名与值前缀（INI 键名不含冒号）。
 * 值内可含 ':'（如 ROOT:、CUSTOM:），因此不能取最后一个冒号。
 */
internal fun splitKeyValueLine(line: String): Pair<String, String> {
    val colonIdx = line.indexOf(':')
    return if (colonIdx >= 0) {
        line.substring(0, colonIdx).trim() to line.substring(colonIdx + 1)
    } else {
        "" to ""
    }
}

/**
 * 计算 ':' 后当前可替换的片段：以 ,、，、空格、Tab、( 为分隔，取最后一个分隔符之后的部分。
 */
internal fun replaceableValuePrefix(rawValuePrefix: String): String {
    val lastDelimIdx = rawValuePrefix.lastIndexOfAny(charArrayOf(',', '，', ' ', '\t', '('))
    return if (lastDelimIdx >= 0) rawValuePrefix.substring(lastDelimIdx + 1) else rawValuePrefix
}

/**
 * 括号内只显示值结果的早退条件：值上下文 + 光标在未闭合括号内，
 * 且（函数命中参数表，或当前无输入前缀）。命中时跳过通用 KEY/翻译库兜底，避免刷全表。
 */
internal fun shouldReturnValueOnlyInParens(
    keyPart: String,
    insideParens: Boolean,
    knownParamFunction: Boolean,
    rawPrefixEmpty: Boolean
): Boolean = keyPart.isNotEmpty() && insideParens && (knownParamFunction || rawPrefixEmpty)

/**
 * 空值未知键兜底抑制：值上下文、无输入前缀且值补全无结果时不再刷全表。
 * 有输入前缀时仍走按前缀过滤的通用 KEY/翻译库兜底。
 */
internal fun shouldSuppressEmptyValueFallback(
    keyPart: String,
    rawPrefixEmpty: Boolean,
    valueResultsEmpty: Boolean
): Boolean = keyPart.isNotEmpty() && rawPrefixEmpty && valueResultsEmpty

/**
 * 值片段早退：值上下文、值补全有结果时，只显示值补全结果，跳过通用 KEY/翻译库兜底
 * （避免刷全表/无关项）。
 * 条件（满足其一即早退，不再要求值片段非空）：
 * - 光标紧跟触发符（rawPrefix 为空）——冒号后空值但值补全已有结果（如
 *   也可以使用单位参考触发或队列操作: 补单位标记、自动触发: 补 真/假/if）→ 直接返回 valueResults；
 * - 值片段为带点引用（如 自身资源.g / 当前动作目标.g / 内存.d），即使点后已输入前缀也只显示对应值补全。
 * 有输入前缀且非带点（如 自动触发: 自）时不早退，仍走按前缀过滤的通用 KEY/翻译库兜底。
 */
internal fun shouldReturnValueOnlyForValueFragment(
    rawPrefixEmpty: Boolean,
    valuePrefixNotEmpty: Boolean,
    valueResultsNotEmpty: Boolean,
    valuePrefixDotted: Boolean = false
): Boolean = valueResultsNotEmpty && (rawPrefixEmpty || valuePrefixDotted)

/**
 * 值补全翻译库桥接校验：仅当 label 确实存在于翻译库 key 集合（且长度 > 1）时，
 * 才为该值补全项补一个「翻译库」KEY 项（解决 自身资源 这类词来源/排序问题）。
 * 不在字典的值（如自定义资源名）保持 Provider 的 VALUE 来源，避免误标「翻译库」。
 */
internal fun shouldBridgeToTranslationLibrary(label: String, dictKeys: Set<String>): Boolean =
    label.length > 1 && label in dictKeys

/**
 * 同名去重：补全表（非 VALUE：用户表/原生表/翻译库）优先于值补全（VALUE）。
 * 仅当 valueResults 非空且存在同 label 的非 VALUE 项时，丢弃该 VALUE 项；无碰撞原样返回。
 */
internal fun dedupeValueByKeyPriority(
    valueResults: List<CompletionProvider.CompletionItem>,
    all: List<CompletionProvider.CompletionItem>
): List<CompletionProvider.CompletionItem> {
    if (valueResults.isEmpty()) return all
    val keyLabels = all.filter { it.type != CompletionProvider.CompletionType.VALUE }.map { it.label }.toSet()
    if (keyLabels.isEmpty()) return all
    return all.filterNot { it.type == CompletionProvider.CompletionType.VALUE && it.label in keyLabels }
}

/**
 * 排序：与值补全（VALUE）同名的项按 valueOrder 的原始顺序置顶，恢复 `真/假/if/and/or` 等靠前。
 * 组：VALUE→0；label 命中 valueOrder→1（按 valueOrder 索引）；普通键→2；values/特定值→3；同键稳定排序。
 */
internal fun orderByValueCollision(
    items: List<CompletionProvider.CompletionItem>,
    valueOrder: List<String>
): List<CompletionProvider.CompletionItem> {
    if (valueOrder.isEmpty()) return items
    val valueIndex = valueOrder.withIndex().associate { it.value to it.index }
    fun group(item: CompletionProvider.CompletionItem): Int = when {
        item.type == CompletionProvider.CompletionType.VALUE -> 0
        item.label in valueIndex -> 1
        item.category.any { it == "values" || it == "特定值" } -> 3
        else -> 2
    }
    return items.sortedWith(
        compareBy<CompletionProvider.CompletionItem>({ group(it) }, { valueIndex[it.label] ?: 0 })
    )
}

/**
 * 值上下文（行内已有 键:）过滤：保留 VALUE、values/特定值，以及翻译库兜底项
 * （翻译库无条件参与，不再受输入前缀限制）。
 */
internal fun filterValueContextItems(
    items: List<CompletionProvider.CompletionItem>
): List<CompletionProvider.CompletionItem> {
    return items.filter { item ->
        item.type == CompletionProvider.CompletionType.VALUE ||
                item.category.any { it == "values" || it == "特定值" } ||
                item.category.any { it == "翻译库" }
    }.map { item ->
        // 值上下文：被保留的 KEY 项此时用作「值」，插入文本不应再带「键:」冒号。
        // 例如 生命值(maxHp) 同时属于核心节（category=核心，formatCategory=空值属性→insertText=「生命值:」）
        // 与 setUnitStats 值（category=特定值），因含 特定值 被保留，但插入时应只插「生命值」（去尾冒号）。
        if (item.type == CompletionProvider.CompletionType.KEY && item.insertText.endsWith(":")) {
            item.copy(insertText = item.insertText.dropLast(1))
        } else item
    }
}

/** 逻辑入口 token：if/真/假 及英文 true/false，同一逻辑布尔值内只出现一次 */
internal val LOGIC_ENTRY_TOKENS = setOf("if", "真", "假", "true", "false")

/** 逻辑值运算符（二元，需要左右操作数） */
internal val OPERATOR_ITEMS = setOf("+", "-", "*", "/", "<", ">", "<=", ">=", "==", "!=", "%")

/** 逻辑值里的纯语法项：关键字 + 布尔常量 + 运算符 */
internal val LOGIC_SYNTAX_ITEMS = OPERATOR_ITEMS + setOf("真", "假", "true", "false", "if", "and", "or", "not")

/** 表达式起始关键字（if/真/假/not 等）：仅表达式开头合法，完整值后不再提示 */
internal val LOGIC_STARTERS = setOf("if", "真", "假", "true", "false", "not")

/** 逻辑组合关键字 */
internal val LOGIC_KEYWORDS = setOf("if", "and", "or", "not")

/** 逻辑连接符（二元中缀，如 `条件A and 条件B`）；与 LOGIC_KEYWORDS 同源但专指连接用途 */
internal val LOGIC_CONNECTORS = setOf("and", "or")

/** 布尔值字面量（真/假），非逻辑表达式；and/or 连接符在其后不再提示 */
internal val LOGIC_BOOLEAN_VALUES = setOf("true", "false", "真", "假")

/**
 * 判断当前行值部分（第一个 : 之后）去尾空白后的最后一个 token 是否为布尔字面量（真/假/true/false）。
 * 仅布尔字面量后不再提示 and/or/not 等语法项（真/假后不弹连接符）；
 * if/and/or/not 属于「表达式关键字」，其后的展开（操作数集/not/andor）交由值 Provider 的表达式
 * 状态机按位置注入，这里不再干预，避免 provider 注入的连接符被上游一并移除。
 */
internal fun valueEndsWithLogicKeyword(lineBeforeCursor: String): Boolean {
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return false
    val value = lineBeforeCursor.substring(colonIdx + 1).trimEnd()
    val lastToken = value.substringAfterLast(' ', "").trim()
    if (lastToken.isEmpty()) return false
    return lastToken in LOGIC_BOOLEAN_VALUES
}

/**
 * 判断光标前的值部分最后一个 token 是否为完整操作数（决定是否提示运算符补全）。
 * 运算符是二元的，需要左操作数：表达式开头/关键字后/点后/另一个运算符后都不提示运算符。
 */
internal fun hasCompleteValueBeforeCursor(lineBeforeCursor: String): Boolean {
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return false
    val value = lineBeforeCursor.substring(colonIdx + 1).trimEnd()
    val lastToken = value.substringAfterLast(' ', "").trim()
    if (lastToken.isEmpty()) return false
    if (lastToken.lowercase() in LOGIC_SYNTAX_ITEMS) return false
    val last = lastToken.last()
    if (last == '.' || last == '(' || last == ',' || last == '，' || last == '=' ||
        last == '+' || last == '-' || last == '*' || last == '/' || last == '<' || last == '>' || last == '%') {
        return false
    }
    return true
}

/** 值上下文最终过滤：suppress 时移除逻辑值语法项（真/假/if/and/or/not/运算符） */
internal fun filterLogicSyntaxItems(
    items: List<CompletionProvider.CompletionItem>,
    suppress: Boolean
): List<CompletionProvider.CompletionItem> {
    if (!suppress) return items
    return items.filterNot { it.label.lowercase() in LOGIC_SYNTAX_ITEMS }
}

/** 运算符过滤：includeOperators 为 false 时移除运算符项（二元运算符需要左操作数） */
internal fun filterOperatorItems(
    items: List<CompletionProvider.CompletionItem>,
    includeOperators: Boolean
): List<CompletionProvider.CompletionItem> {
    if (includeOperators) return items
    return items.filterNot { it.label.lowercase() in OPERATOR_ITEMS }
}

/** 完整值后过滤：表达式起始关键字（if/真/假/not/true/false）不再提示，只留组合/运算符/值 */
internal fun filterStartersAfterValue(
    items: List<CompletionProvider.CompletionItem>,
    afterCompleteValue: Boolean
): List<CompletionProvider.CompletionItem> {
    if (!afterCompleteValue) return items
    return items.filterNot { it.label.lowercase() in LOGIC_STARTERS }
}

/**
 * 判断当前行值部分（第一个 : 之后）是否已含逻辑入口 token（if/真/假/true/false）。
 * 一旦出现，后续不再提示入口 token，只续写条件/运算符。
 */
internal fun logicValueHasEntryToken(lineBeforeCursor: String): Boolean {
    val colonIdx = lineBeforeCursor.indexOf(':')
    if (colonIdx < 0) return false
    val value = lineBeforeCursor.substring(colonIdx + 1)
    if (Regex("""\b(if|true|false)\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
    // 真/假：前后为非字母数字/汉字（或边界），避免命中中文词内嵌
    val boundary = Regex("""(^|[^0-9A-Za-z\u4e00-\u9fff])(真|假)([^0-9A-Za-z\u4e00-\u9fff]|$)""")
    return boundary.containsMatchIn(value)
}

/** 值上下文最终过滤：suppress 时移除逻辑入口 token（if/真/假/true/false），否则原样返回 */
internal fun filterLogicEntryTokens(
    items: List<CompletionProvider.CompletionItem>,
    suppressEntryTokens: Boolean
): List<CompletionProvider.CompletionItem> {
    if (!suppressEntryTokens) return items
    return items.filterNot { it.label.lowercase() in LOGIC_ENTRY_TOKENS }
}