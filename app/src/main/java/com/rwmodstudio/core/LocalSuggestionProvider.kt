package com.rwmodstudio.core

import com.rwmodstudio.core.translation.CodeReferenceRepository

/**
 * 本地规则建议：不联网、零延迟，用于 AI 建议「点击即出内容」的兜底层。
 *
 * 设计原则：**不编造键名**。候选键只来自两个真实来源——
 * 1. 当前文件里「同类节」已用过的键（如其它 [隐藏行动_xxx] 节用过的键），按出现频次排序。
 *    这是最贴合当前 MOD 的上下文，命中率高。
 * 2. 代码参考库中当前节的属性表，排除上下文里已出现的键，优先带默认值/示例的常用键。
 *
 * 生成的都是「插入」建议（本地规则不做替换，避免误改已有行）。
 * AI 结果回来后由 UI 覆盖掉本地结果。
 */
object LocalSuggestionProvider {

    private const val TAG = "LocalSuggestionProvider"
    private const val MAX_SUGGESTIONS = 3

    /**
     * 生成本地建议。
     *
     * @param sectionName 当前节名（如「隐藏行动_合并进攻」「核心」），可空
     * @param contextLines 光标上下文（判定哪些键已出现）
     * @param fileContent 当前文件全文（用于找同类节）
     * @param isChineseView 中文视图（决定布尔/键名语言）
     */
    fun build(
        sectionName: String?,
        contextLines: String,
        fileContent: String,
        isChineseView: Boolean
    ): List<AiService.AiSuggestion> {
        return try {
            val usedKeys = collectUsedKeys(contextLines)
            val candidates = buildFromSiblingSections(sectionName, fileContent, usedKeys)
                .takeIf { it.isNotEmpty() }
                ?: buildFromCodeReference(sectionName, usedKeys, isChineseView)
            candidates.take(MAX_SUGGESTIONS).map { code ->
                AiService.AiSuggestion(AiService.ACTION_INSERT, null, code)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "local suggestion failed: ${e.message}")
            emptyList()
        }
    }

    // ==================== 来源 1：同类节已用键 ====================

    /**
     * 从「同类节」中提取键：与当前节同一前缀的其它节（如 [隐藏行动_A]/[隐藏行动_B] 互称同类）。
     * 返回 `键: 最常见值` 列表，按出现频次降序。
     */
    private fun buildFromSiblingSections(
        sectionName: String?,
        fileContent: String,
        usedKeys: Set<String>
    ): List<String> {
        if (sectionName.isNullOrBlank() || fileContent.isBlank()) return emptyList()
        val target = sectionName.trim().lowercase()
        // 同类判定：取最后一个分隔符前的公共前缀（隐藏行动_合并进攻 → 隐藏行动）
        val prefix = target.substringBeforeLast('_')
        if (prefix.isBlank() || prefix == target) return emptyList()

        // key → (value → 出现次数)
        val stats = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for ((sec, lines) in parseSections(fileContent)) {
            val secLower = sec.trim().lowercase()
            if (secLower == target) continue // 跳过当前节自身
            if (!secLower.startsWith(prefix)) continue
            for (line in lines) {
                val (k, v) = splitKeyValue(line) ?: continue
                if (norm(k) in usedKeys) continue
                val values = stats.getOrPut(k) { LinkedHashMap() }
                values[v] = (values[v] ?: 0) + 1
            }
        }
        if (stats.isEmpty()) return emptyList()

        return stats.entries
            .sortedByDescending { (_, values) -> values.values.sum() }
            .map { (key, values) ->
                val topValue = values.entries.maxByOrNull { it.value }?.key.orEmpty()
                if (topValue.isBlank()) "$key:" else "$key: $topValue"
            }
    }

    // ==================== 来源 2：代码参考库 ====================

    /** 从代码参考库取当前节属性，排除已用键，优先带 default/example 的常用键 */
    private fun buildFromCodeReference(
        sectionName: String?,
        usedKeys: Set<String>,
        isChineseView: Boolean
    ): List<String> {
        if (sectionName.isNullOrBlank()) return emptyList()
        val mapped = AiService.mapSectionName(sectionName)
        if (mapped.isEmpty()) return emptyList()

        val props = runCatching {
            com.rwmodstudio.core.translation.TranslationEngine.getInstance()
                .getCodeReference()
                .getPropertiesForSection(mapped)
        }.getOrDefault(emptyList())
        if (props.isEmpty()) return emptyList()

        val (withValue, withoutValue) = props
            .filter { p ->
                norm(p.name.trim()) !in usedKeys && norm(p.name_en?.trim().orEmpty()) !in usedKeys
            }
            .partition { p ->
                p.default.isNotBlank() || p.example.isNotBlank() ||
                    p.type.equals("bool", true) || p.type.equals("int", true) ||
                    p.type.equals("float", true)
            }

        return (withValue + withoutValue).map { p ->
            val key = if (isChineseView) p.name.trim() else (p.name_en?.trim().orEmpty().ifBlank { p.name.trim() })
            val value = localValue(p, isChineseView)
            if (value.isBlank()) "$key:" else "$key: $value"
        }
    }

    /** 依据属性类型生成符合当前视图语言的值；无典型值时留空让用户填 */
    private fun localValue(p: CodeReferenceRepository.PropertyInfo, isChineseView: Boolean): String {
        if (p.default.isNotBlank()) return p.default.trim()
        return when (p.type.trim().lowercase()) {
            "bool" -> if (isChineseView) "真" else "true"
            "int", "float" -> runCatching {
                // example 形如「键: 5」，取冒号后的值
                p.example.substringAfter(':').trim().takeIf { it.isNotBlank() && it.length <= 12 }
            }.getOrNull() ?: "1"
            else -> ""
        }
    }

    // ==================== 解析工具 ====================

    /** 提取上下文里已出现的键名（归一化：小写、去空格） */
    private fun collectUsedKeys(contextLines: String): Set<String> {
        val keys = LinkedHashSet<String>()
        for (line in contextLines.split('\n')) {
            val (k, _) = splitKeyValue(line) ?: continue
            keys.add(k)
            keys.add(norm(k))
        }
        return keys
    }

    private fun norm(k: String): String = k.lowercase().replace(" ", "").replace("_", "")

    /** 拆分一行 `键: 值` / `键 = 值`；非键值对返回 null */
    private fun splitKeyValue(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("[") || t.startsWith("#") || t.startsWith("//")) return null
        val colonIdx = t.indexOf(':')
        val eqIdx = t.indexOf('=')
        val idx = when {
            colonIdx > 0 && eqIdx > 0 -> minOf(colonIdx, eqIdx)
            colonIdx > 0 -> colonIdx
            eqIdx > 0 -> eqIdx
            else -> return null
        }
        val key = t.substring(0, idx).trim()
        if (key.isBlank() || key.length > 40) return null
        val value = t.substring(idx + 1).trim()
        return key to value
    }

    /** 解析文件为「节名 → 该节内容行」列表（节头之前的内容归入空节名，忽略） */
    private fun parseSections(content: String): List<Pair<String, List<String>>> {
        val result = mutableListOf<Pair<String, MutableList<String>>>()
        var current: MutableList<String>? = null
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                val name = line.substring(1, line.length - 1)
                current = mutableListOf()
                result.add(name to current)
                continue
            }
            current?.add(rawLine)
        }
        return result
    }
}
