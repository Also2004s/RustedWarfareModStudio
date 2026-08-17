package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TranslationEngine private constructor() {

    companion object {
        private const val TAG = "TranslationEngine"
        private val SECTION_PATTERN = Regex("""^(\s*)\[([^\]]+)\](\s*)$""")
        private val KV_PATTERN = Regex("""^(\s*)([^:=#\[]+?)([:=])(.*?)(\s*)$""")
        private val DEFINE_PATTERN = Regex("""^(\s*)@(define|global)\s+(\S+?)\s*:\s*(.*?)(\s*)$""", RegexOption.IGNORE_CASE)
        private val PERCENT_VARIABLE_REGEX = Regex("""%\{((?:[^$}]|\$\{[^}]*\})*?)\}""")
        private val EN_BOOLEANS = setOf("true", "false")
        private val ZH_BOOLEANS = setOf("真", "假")
        // 匹配作为独立 token 出现的中文布尔值（前面是 = 、( 、, 或行首，后面是 , 、) 或行尾）
        // 用于翻译 spawnUnits 等参数列表中的 真/假，如 gridAlign=真, skipIfOverlapping=真)
        private val ZH_BOOL_TOKEN_REGEX = Regex("""(?<=[=,\(])\s*(真|假)\s*(?=[,\)])""")
        // 匹配作为独立 token 出现的英文布尔值（不区分大小写）
        // 用于反向翻译 spawnUnits 等参数列表中的 true/false，如 gridAlign=true, skipIfOverlapping=true)
        private val EN_BOOL_TOKEN_REGEX = Regex("""(?<=[=,\(])\s*(true|false)\s*(?=[,\)])""", RegexOption.IGNORE_CASE)

        private var instance: TranslationEngine? = null

        fun getInstance(): TranslationEngine {
            return instance ?: TranslationEngine().also { instance = it }
        }
    }

    private val translationDict = TranslationDict()
    private val codeReference = CodeReferenceRepository()
    private val snippetRepo = SnippetRepository()
    private var blocklist = TranslationBlocklist()

    @Volatile
    private var appContext: Context? = null

    /** 返回已加载的应用 Context（未加载时为 null），供补全表生成等读取 assets 数据 */
    fun getAppContext(): Context? = appContext

    private val loadMutex = Mutex()

    val isLoaded: Boolean get() = translationDict.isLoaded
    val isReferenceLoaded: Boolean get() = codeReference.isLoaded
    val isSnippetLoaded: Boolean get() = snippetRepo.isLoaded

    var stats = TranslationStats()
        private set

    data class TranslationStats(
        var filesProcessed: Int = 0, var sectionsTranslated: Int = 0,
        var keysTranslated: Int = 0, var linesTranslated: Int = 0
    )

    suspend fun load(context: Context) {
        if (isLoaded) return
        loadMutex.withLock {
            if (isLoaded) return
            withContext(Dispatchers.IO) {
                appContext = context.applicationContext
                loadBlocklist(context)
                translationDict.loadFromAssets(context)
                ensureCodeReferenceGenerated(context)
                codeReference.loadFromAssets(context)
                snippetRepo.load(context)
            }
        }
    }

    /**
     * 若验证码不匹配或 files/data/code_reference.json 不存在，
     * 则直接把 assets/data/code_reference.json（中文主表）复制到 files 目录。
     * 不再从 data/raw/ 英文 raw 数据生成。
     */
    private suspend fun ensureCodeReferenceGenerated(context: Context) {
        try {
            val filesRef = RwmodPaths.codeReferenceFile
            val code = SettingsManager.readVerifyCode(SettingsManager.VERIFY_CODE_REFERENCE)
            if (code != SettingsManager.CODE_REFERENCE_VERIFY_CODE || !filesRef.exists()) {
                filesRef.parentFile?.mkdirs()
                context.assets.open("data/code_reference.json").use { input ->
                    filesRef.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                SettingsManager.writeVerifyCode(SettingsManager.VERIFY_CODE_REFERENCE, SettingsManager.CODE_REFERENCE_VERIFY_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure code reference generated", e)
        }
    }

    fun loadBlocklist(context: Context) {
        blocklist = TranslationBlocklist.load(context)
    }

    fun resetBlocklist(context: Context): Boolean {
        val default = TranslationBlocklist()
        val ok = TranslationBlocklist.save(context, default)
        if (ok) blocklist = default
        return ok
    }

    fun getBlocklist(): TranslationBlocklist = blocklist

    fun setBlocklistEnabled(context: Context, enabled: Boolean): Boolean {
        val updated = blocklist.copy(enabled = enabled)
        val ok = TranslationBlocklist.save(context, updated)
        if (ok) blocklist = updated
        return ok
    }

    fun updateBlocklistKeys(context: Context, keys: List<String>): Boolean {
        val updated = blocklist.copy(keys = keys)
        val ok = TranslationBlocklist.save(context, updated)
        if (ok) blocklist = updated
        return ok
    }

    fun updateBlocklistFlags(
        context: Context,
        blockVariables: Boolean,
        blockAtTokens: Boolean,
        blockFileNames: Boolean,
        blockQuotedDictWords: Boolean,
        forcePercentVariables: Boolean
    ): Boolean {
        val updated = blocklist.copy(
            blockVariables = blockVariables,
            blockAtTokens = blockAtTokens,
            blockFileNames = blockFileNames,
            blockQuotedDictWords = blockQuotedDictWords,
            forcePercentVariables = forcePercentVariables
        )
        val ok = TranslationBlocklist.save(context, updated)
        if (ok) blocklist = updated
        return ok
    }

    fun isEnglishIni(text: String): Boolean {
        val lines = text.lines().filter { val t = it.trim(); t.isNotEmpty() && !t.startsWith("#") }
        if (lines.isEmpty()) return false
        var englishCount = 0; var chineseCount = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val baseName = trimmed.substring(1, trimmed.length - 1).split("_").firstOrNull() ?: trimmed
                if (translationDict.getSectionTranslation(baseName) != baseName) englishCount++
                else if (translationDict.getSectionTranslationBack(baseName) != baseName) chineseCount++
                continue
            }
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim()
                if (translationDict.getTranslation(key) != key) englishCount++
                else if (translationDict.getTranslationBack(key) != key) chineseCount++
            }
        }
        return englishCount > chineseCount
    }

    fun translateToChinese(englishText: String, autoSpace: Boolean = true): String {
        stats = TranslationStats()
        return processLines(englishText.lines(), autoSpace, enToZh = true)
            .joinToString("\n")
    }

    /**
     * 强制翻译单行（英→中），忽略屏蔽词。
     * 用于行尾灯泡点亮后的即时翻译。
     */
    fun translateLineToChineseForce(line: String, autoSpace: Boolean = true): String {
        val stripped = line.trim()
        if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith(";")) return line
        val sectionMatch = SECTION_PATTERN.matchEntire(line)
        if (sectionMatch != null) return translateSectionToChinese(sectionMatch)
        val kvMatch = KV_PATTERN.matchEntire(line)
        if (kvMatch != null) return translateKVToChinese(kvMatch, autoSpace, force = true)
        return line
    }

    private fun translateSectionToChinese(match: MatchResult): String {
        val (indent, sectionName, trailing) = match.destructured
        var cleanName = sectionName; var comment = ""
        if (sectionName.contains("#")) {
            val p = sectionName.split("#", limit = 2)
            cleanName = p[0].trim()
            comment = "#" + p[1]
        }
        if (cleanName.startsWith("global_resource_")) { stats.sectionsTranslated++; return "${indent}[全局资源_${cleanName.removePrefix("global_resource_")}]${trailing}${comment}" }
        val translated = translateSectionName(cleanName)
        if (translated != cleanName) { stats.sectionsTranslated++; return "${indent}[${translated}]${trailing}${comment}" }
        return "${indent}[${cleanName}]${trailing}${comment}"
    }

    /** 引擎已有的节名翻译逻辑：查字典 → 按_拆分翻译前缀（与translateSectionToChinese一致） */
    private fun translateSectionName(name: String): String {
        val direct = translationDict.getSectionTranslation(name)
        if (direct != name) return direct
        if (name.contains("_")) {
            val p = name.split("_", limit = 2)
            val tp = translationDict.getSectionTranslation(p[0])
            if (tp != p[0]) return "${tp}_${p[1]}"
        }
        return name
    }

    /** 中→英 节名反向翻译（与translateSectionToEnglish一致） */
    private fun translateSectionNameBack(name: String): String {
        val direct = translationDict.getSectionTranslationBack(name)
        if (direct != name) return direct
        if (name.contains("_")) {
            val p = name.split("_", limit = 2)
            val tp = translationDict.getSectionTranslationBack(p[0])
            if (tp != p[0]) return "${tp}_${p[1]}"
        }
        return name
    }

    private fun translateKVToChinese(match: MatchResult, autoSpace: Boolean = true, force: Boolean = false): String {
        val (indent, key, separator, value, trailing) = match.destructured
        val cleanKey = key.trim(); var cleanValue = value.trim(); var comment = ""
        if (cleanValue.contains("#")) {
            val p = cleanValue.split("#", limit = 2)
            cleanValue = p[0].trim()
            comment = "#" + p[1]
        }
        val translatedKey = translationDict.getTranslation(cleanKey)
        if (translatedKey != cleanKey) stats.keysTranslated++
        // @copyFromSection / 复制节 的值是节名，走引擎已有的节名翻译逻辑（两种语言形式都匹配）
        val translatedValue = if (cleanKey == "@copyFromSection" || cleanKey == "复制节") {
            translateSectionName(cleanValue)
        } else if (!force && shouldBlockKey(cleanKey)) {
            // key 被屏蔽时 value 整体不翻译；若开启 %{} 强制翻译，则仅翻译 %{} 内部
            if (blocklist.forcePercentVariables) translatePercentVariables(cleanValue, true, autoSpace) else cleanValue
        } else {
            translateValue(cleanValue, true, autoSpace)
        }
        return "${indent}${translatedKey}${separator}${translatedValue}${comment}"
    }

    /**
     * 判断 key 是否需要屏蔽 value 翻译。
     * 不仅检查 key 本身是否在屏蔽词列表中，还检查其中文或英文翻译形式。
     */
    private fun shouldBlockKey(key: String): Boolean {
        if (!blocklist.enabled) return false
        val trimmed = key.trim()
        if (trimmed in blocklist.keys) return true
        val translatedZh = translationDict.getTranslation(trimmed)
        if (translatedZh != trimmed && translatedZh in blocklist.keys) return true
        val translatedEn = translationDict.getTranslationBack(trimmed)
        if (translatedEn != trimmed && translatedEn in blocklist.keys) return true
        // 对 @xxx aaa 或 @xxx aaa:aaa 形式的 key，直接屏蔽其 value 翻译
        if (Regex("""@\w+\s+\S+""").matches(trimmed)) return true
        return false
    }

    fun translateToEnglish(chineseText: String, autoSpace: Boolean = true): String {
        return processLines(chineseText.lines(), autoSpace, enToZh = false)
            .joinToString("\n")
    }

    /**
     * 中→英翻译，支持指定行号强制翻译（跳过屏蔽词）。
     *
     * @param forcedLineIndices 0-based 行号集合，这些行会忽略屏蔽词进行翻译。
     */
    fun translateToEnglish(chineseText: String, autoSpace: Boolean = true, forcedLineIndices: Set<Int>): String {
        return processLines(chineseText.lines(), autoSpace, enToZh = false, forcedLineIndices)
            .joinToString("\n")
    }

    /**
     * 统一处理所有行：识别 key:"""...""" 多行字符串，保持真实换行并走完整 value 翻译流程。
     */
    private fun processLines(
        lines: List<String>,
        autoSpace: Boolean,
        enToZh: Boolean,
        forcedLineIndices: Set<Int> = emptySet()
    ): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val force = i in forcedLineIndices

            // 空行 / 注释
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith(";")) {
                result.add(line)
                i++
                continue
            }

            // Section
            val sectionMatch = SECTION_PATTERN.matchEntire(line)
            if (sectionMatch != null) {
                result.add(
                    if (enToZh) translateSectionToChinese(sectionMatch)
                    else translateSectionToEnglish(sectionMatch)
                )
                i++
                continue
            }

            // @define / @global name:value
            val defineMatch = DEFINE_PATTERN.matchEntire(line)
            if (defineMatch != null) {
                val (indent, keyword, name, value, trailing) = defineMatch.destructured
                val translatedValue = translateValue(value, enToZh, autoSpace)
                result.add("${indent}@${keyword} ${name}:${translatedValue}${trailing}")
                i++
                continue
            }

            // Key-value
            val kvMatch = KV_PATTERN.matchEntire(line)
            if (kvMatch != null) {
                val (indent, key, separator, value, trailing) = kvMatch.destructured
                val trimmedValue = value.trimStart()

                // 普通 key-value（不以 """ 开头）
                if (!trimmedValue.startsWith("\"\"\"")) {
                    result.add(
                        if (enToZh) translateKVToChinese(kvMatch, autoSpace, force)
                        else translateKVToEnglish(kvMatch, autoSpace, force)
                    )
                    i++
                    continue
                }

                // 多行字符串 key:"""..."""
                val translatedKey = translateKey(key.trim(), enToZh)
                val openLen = 3
                val firstCloseIdx = trimmedValue.indexOf("\"\"\"", startIndex = openLen)

                if (firstCloseIdx != -1) {
                    // 同一行内闭合
                    val content = trimmedValue.substring(openLen, firstCloseIdx)
                    val rest = trimmedValue.substring(firstCloseIdx + openLen)
                    val translatedContent = translateMultilineContent(content, key.trim(), enToZh)
                    result.add("${indent}${translatedKey}${separator}\"\"\"${translatedContent}\"\"\"${rest}${trailing}")
                    i++
                    continue
                }

                // 跨真实换行：收集完整内容后统一翻译，再按原始行结构还原
                val collected = StringBuilder(trimmedValue.substring(openLen))
                var j = i + 1
                while (j < lines.size) {
                    val cont = lines[j]
                    collected.append('\n').append(cont)
                    if (cont.contains("\"\"\"")) break
                    j++
                }
                val fullContent = collected.toString()
                val closeIdx = fullContent.indexOf("\"\"\"")
                if (closeIdx == -1) {
                    // 未找到闭合，按原样保留整行
                    result.add(line)
                    i++
                    continue
                }
                val beforeClose = fullContent.substring(0, closeIdx)
                val afterClose = fullContent.substring(closeIdx + openLen)
                val translatedBeforeClose = translateMultilineContent(beforeClose, key.trim(), enToZh)
                val translatedLines = translatedBeforeClose.lines()
                when (translatedLines.size) {
                    0 -> result.add("${indent}${translatedKey}${separator}\"\"\"\"\"\"${afterClose}${trailing}")
                    1 -> result.add("${indent}${translatedKey}${separator}\"\"\"${translatedLines[0]}\"\"\"${afterClose}${trailing}")
                    else -> {
                        result.add("${indent}${translatedKey}${separator}\"\"\"${translatedLines[0]}")
                        for (k in 1 until translatedLines.size - 1) {
                            result.add(translatedLines[k])
                        }
                        result.add("${translatedLines.last()}\"\"\"${afterClose}${trailing}")
                    }
                }
                i = j + 1
                continue
            }

            // 未匹配任何规则（非标准 INI 行），用词典做文本级翻译
            result.add(translationDict.translateInText(line, enToZh))
            i++
        }
        return result
    }

    private fun translateKey(key: String, enToZh: Boolean): String {
        return if (enToZh) translationDict.getTranslation(key) else translationDict.getTranslationBack(key)
    }

    private fun translateSectionToEnglish(match: MatchResult): String {
        val (indent, sectionName, trailing) = match.destructured
        var cleanName = sectionName; var comment = ""
        if (sectionName.contains("#")) {
            val p = sectionName.split("#", limit = 2)
            cleanName = p[0].trim()
            comment = "#" + p[1]
        }
        if (cleanName.startsWith("全局资源_")) return "${indent}[global_resource_${cleanName.removePrefix("全局资源_")}]${trailing}${comment}"
        val translated = translationDict.getSectionTranslationBack(cleanName)
        if (translated != cleanName) return "${indent}[${translated}]${trailing}${comment}"
        if (cleanName.contains("_")) { val p = cleanName.split("_", limit = 2); val tp = translationDict.getSectionTranslationBack(p[0]); if (tp != p[0]) return "${indent}[${tp}_${p[1]}]${trailing}${comment}" }
        return "${indent}[${cleanName}]${trailing}${comment}"
    }

    private fun translateKVToEnglish(match: MatchResult, autoSpace: Boolean = true, force: Boolean = false): String {
        val (indent, key, separator, value, trailing) = match.destructured
        val cleanKey = key.trim(); var cleanValue = value.trim(); var comment = ""
        if (cleanValue.contains("#")) {
            val p = cleanValue.split("#", limit = 2)
            cleanValue = p[0].trim()
            comment = "#" + p[1]
        }
        // @copyFromSection / 复制节 的值是节名，走引擎已有的节名反向翻译逻辑（两种语言形式都匹配）
        val translatedValue = if (cleanKey == "复制节" || cleanKey == "@copyFromSection") {
            translateSectionNameBack(cleanValue)
        } else if (!force && shouldBlockKey(cleanKey)) {
            // key 被屏蔽时 value 整体不翻译；若开启 %{} 强制翻译，则仅翻译 %{} 内部
            if (blocklist.forcePercentVariables) translatePercentVariables(cleanValue, false, autoSpace) else cleanValue
        } else {
            translateValue(cleanValue, false, autoSpace)
        }
        // 保留原始分隔符（含空格），autoSpace关闭时不做任何修改
        return "${indent}${translationDict.getTranslationBack(cleanKey)}${separator}${translatedValue}${comment}"
    }

    /**
     * 多行字符串内容翻译：与单行 value 的屏蔽逻辑对齐。
     * key 被屏蔽时，根据「跳过对 %{} 屏蔽」开关决定是否仅翻译 %{} 内部；
     * key 未被屏蔽时，走完整 value 翻译流程。
     */
    private fun translateMultilineContent(content: String, key: String, enToZh: Boolean): String {
        return if (shouldBlockKey(key)) {
            if (blocklist.forcePercentVariables) translatePercentVariableSingle(content, enToZh)
            else content
        } else {
            translateSingleValue(content, enToZh)
        }
    }

    /**
     * 仅翻译 value 中的 %{...} 变量内部内容，其余部分保持不变。
     * 用于开启「跳过%{}变量」后，在被屏蔽的 key 中仍能强制翻译 %{} 内部。
     * 注意：不再按逗号切分，因为 %{} 内部可能包含逗号（如 select/max 表达式）。
     */
    private fun translatePercentVariables(value: String, enToZh: Boolean, autoSpace: Boolean): String {
        return translatePercentVariableSingle(value, enToZh)
    }

    private fun translatePercentVariableSingle(value: String, enToZh: Boolean): String {
        val matches = PERCENT_VARIABLE_REGEX.findAll(value).toList()
        if (matches.isEmpty()) return value
        val sb = StringBuilder()
        var lastEnd = 0
        for (match in matches) {
            sb.append(value.substring(lastEnd, match.range.first))
            val inner = match.groupValues[1]
            val translatedInner = translationDict.translateInText(inner, enToZh)
            sb.append("%{${translatedInner}}")
            lastEnd = match.range.last + 1
        }
        sb.append(value.substring(lastEnd))
        return sb.toString()
    }

    private fun translateValue(value: String, enToZh: Boolean, autoSpace: Boolean = true): String {
        if (value.isEmpty()) return value

        // 处理逗号分隔的多个值（如 "LAND, WATER, HOVER"）
        // 但如果值中包含 %{} 插值或引号，则不应按逗号切分，避免破坏表达式/字符串
        // 同时跳过括号内的逗号，避免破坏 spawnUnits 等属性的参数结构
        if (value.contains(",") && !value.contains("%{") && !value.contains("\"") && !value.contains("'")) {
            val delimiter = if (autoSpace) ", " else ","
            return splitTopLevelCommas(value).joinToString(delimiter) { translateSingleValue(it.trim(), enToZh) }
        }

        // 单值翻译
        return translateSingleValue(value, enToZh)
    }

    /**
     * 按顶层逗号切分，跳过括号内的逗号。
     * 例如 "模版(a=1, b=2), 模版(c=3, d=4)" 切分为 ["模版(a=1, b=2)", " 模版(c=3, d=4)"]
     */
    private fun splitTopLevelCommas(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        for (ch in value) {
            when (ch) {
                '(' -> { parenDepth++; current.append(ch) }
                ')' -> { if (parenDepth > 0) parenDepth--; current.append(ch) }
                ',' -> {
                    if (parenDepth == 0) {
                        result.add(current.toString())
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /**
     * 翻译单个value值
     * 与Python的 _translate_single_value 对齐
     */
    private fun translateSingleValue(value: String, enToZh: Boolean): String {
        // 默认翻译 %{} 内部内容
        var processed = translatePercentVariableSingle(value, enToZh)

        // 处理布尔值（true/false 在 valueTranslations 中）
        if (enToZh && processed.lowercase() in EN_BOOLEANS) {
            return translationDict.getValueTranslationBack(processed.lowercase())
        }
        if (!enToZh && processed in ZH_BOOLEANS) {
            return translationDict.getValueTranslationBack(processed)
        }

        // 处理特殊值（在 specialValues 中的英文值，如 LAND, WATER 等）
        if (processed in translationDict.getSpecialValues()) {
            return translationDict.getValueTranslationBack(processed)
        }

        // 反向翻译时，检查 valueTranslations 中是否有对应的中文值（如 水面→WATER, 真→true）
        if (!enToZh) {
            val translated = translationDict.getValueTranslationBack(processed)
            if (translated != processed) return translated
        }

        // 保护翻译库中已存在且被英文引号包裹的词，如 "跟随" / '跟随'
        val (quoteProtected, quotePlaceholders) = blocklist.protectQuotedDictWords(processed, ::isDictWord)

        // 保护 ${...} 变量和 @xxx token，避免被翻译
        val (protected, placeholders) = blocklist.protectFragments(quoteProtected)

        // 使用 translateInText 进行文本内翻译（和Python对齐）
        val translated = translationDict.translateInText(protected, enToZh)

        // 翻译内嵌的布尔值 token（如 spawnUnits 参数中的 gridAlign=真, skipIfOverlapping=真）
        // translateInText 使用 \b 单词边界，对单字 真/假 不可靠，且 true/false 在 valueTranslations 中而非 enToZh，需单独处理
        val boolTranslated = if (!enToZh) {
            ZH_BOOL_TOKEN_REGEX.replace(translated) { match ->
                translationDict.getValueTranslationBack(match.groupValues[1])
            }
        } else {
            EN_BOOL_TOKEN_REGEX.replace(translated) { match ->
                translationDict.getValueTranslation(match.groupValues[1].lowercase())
            }
        }

        val restored = blocklist.restoreProtected(boolTranslated, placeholders)
        return blocklist.restoreProtected(restored, quotePlaceholders)
    }

    private fun isDictWord(word: String): Boolean {
        return translationDict.getTranslation(word) != word ||
                translationDict.getTranslationBack(word) != word ||
                word in translationDict.getSpecialValues() ||
                translationDict.getValueTranslationBack(word) != word
    }

    private fun translateWordsInValue(value: String, enToZh: Boolean): String {
        // 使用 TranslationDict 的 translateInText 方法
        return translationDict.translateInText(value, enToZh)
    }

    /**
     * 获取「在屏蔽词列表中，且三表类型为 string / LocaleString」的 key 集合。
     * 返回集合包含 key 本身、中文翻译形式、英文翻译形式（只要任一形式命中即可）。
     * 用于编辑器行尾灯泡：这类 key 的 value 默认被屏蔽，但用户可手动点亮灯泡强制翻译。
     * @param typeLookup 按 name/nameEn 精确查询三表属性（由 UI 层构建）
     */
    fun getBlockableStringKeys(typeLookup: (String) -> List<CodeReferenceRepository.PropertyInfo>): Set<String> {
        val result = mutableSetOf<String>()
        val targetTypes = setOf("string", "localestring")
        for (key in blocklist.keys) {
            val forms = setOf(
                key,
                translationDict.getTranslation(key),
                translationDict.getTranslationBack(key)
            )
            for (form in forms) {
                if (form.isBlank()) continue
                val props = typeLookup(form)
                if (props.any { it.type.lowercase() in targetTypes && (it.name == form || it.name_en == form) }) {
                    result.addAll(forms)
                    break
                }
            }
        }
        return result
    }

    fun getCompletionProvider(
        customCompletions: List<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem> = emptyList(),
        nativeCompletions: List<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem> = emptyList(),
        showDetail: Boolean = true,
        valueSectionProperties: Map<String, List<CodeReferenceRepository.PropertyInfo>> = emptyMap()
    ) = com.rwmodstudio.feature.completion.CompletionProvider(
        context = appContext ?: throw IllegalStateException("TranslationEngine not loaded"),
        translationDict = translationDict,
        valueSectionProperties = valueSectionProperties,
        customCompletions = customCompletions,
        nativeCompletions = nativeCompletions,
        showDetail = showDetail,
        valueCompletionEnabled = SettingsManager.devValueCompletion,
        valueCompletionOptions = com.rwmodstudio.feature.completion.value.ValueCompletionAggregator.ValueCompletionOptions(
            boolEnabled = SettingsManager.devValueCompletionBool,
            logicBooleanEnabled = SettingsManager.devValueCompletionLogicBoolean,
            enumEnabled = SettingsManager.devValueCompletionEnum,
            imageEnabled = SettingsManager.devValueCompletionImage,
            unitSpawnEnabled = SettingsManager.devValueCompletionUnitSpawn,
            autoTriggerOnEventEnabled = SettingsManager.devValueCompletionAutoTriggerOnEvent
        ),
        nonValueCompletionLimited = SettingsManager.nonValueCompletionLimited
    )
    fun getCodeReference() = codeReference
    fun getTranslationDict() = translationDict

    /**
     * 翻译补全表
     * 将补全建议中的英文内容翻译为中文
     */
    fun translateCompletionItems(items: List<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem>): List<com.rwmodstudio.feature.completion.CompletionProvider.CompletionItem> {
        return items.map { item ->
            val translatedLabel = translationDict.getTranslation(item.label)
            val translatedDetail = translateValue(item.detail, true)
            val translatedInsertText = translateValue(item.insertText, true)
            
            item.copy(
                label = if (translatedLabel != item.label) translatedLabel else item.label,
                detail = if (translatedDetail != item.detail) translatedDetail else item.detail,
                insertText = if (translatedInsertText != item.insertText) translatedInsertText else item.insertText
            )
        }
    }
}
