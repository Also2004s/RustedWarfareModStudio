package com.rwmodstudio.feature.completion.value

import android.content.Context
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.TranslationDict

/**
 * 值补全请求上下文
 * 聚合一次值补全所需的全部信息，避免每个 Provider 重复解析。
 */
data class ValueCompletionRequest(
    val context: Context,
    val sectionProperties: Map<String, List<CodeReferenceRepository.PropertyInfo>>,
    val propertyName: String,
    val sectionName: String?,
    val valuePrefix: String,
    val rawValuePrefixLength: Int,
    val lineText: String,
    val textBeforeCursor: String,
    val textAfterCursor: String,
    val translationDict: TranslationDict? = null,
    val memoryNames: Set<String> = emptySet(),
    /** 内存变量 → 声明类型（unit 型内存变量支持链式成员补全） */
    val memoryTypes: Map<String, String> = emptyMap(),
    /** 全局变量（@global 变量名，项目级，${} 引用） */
    val globalVariables: Set<String> = emptySet(),
    /** 局部变量（@define 变量名，仅当前节 + 继承链同节可见，${} 引用） */
    val localVariables: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val globalTags: Set<String> = emptySet(),
    val messageTags: Set<String> = emptySet(),
    val actionTags: Set<String> = emptySet(),
    val resources: Set<String> = emptySet(),
    val globalResources: Set<String> = emptySet(),
    val unitNames: Set<String> = emptySet(),
    val turretNames: Set<String> = emptySet(),
    val projectileNames: Set<String> = emptySet(),
    val effectNames: Set<String> = emptySet(),
    val actionNames: Set<String> = emptySet(),
    val hiddenActionNames: Set<String> = emptySet(),
    val animationNames: Set<String> = emptySet(),
    val decalNames: Set<String> = emptySet(),
    val attachmentNames: Set<String> = emptySet(),
    val buildableNames: Set<String> = emptySet(),
    val soundFiles: Set<String> = emptySet(),
    val chainSectionNames: Map<String, Set<String>> = emptyMap()
) {
    /**
     * 在当前节（或全局）中查找属性定义（数据来自自定义补全三表）。
     */
    fun findProperty(): CodeReferenceRepository.PropertyInfo? {
        val sectionsToSearch = mutableListOf<String>()
        sectionName?.let { sectionsToSearch.add(it) }
        sectionsToSearch.addAll(sectionProperties.keys)
        // 中文属性名经翻译库反查英文名：code_reference 中部分条目 name 本身为英文（无中文名，
        // 如 alsoTriggerOrQueueActionWithTarget / teleportTo / builtFrom_{NUM}_name 等），
        // 中文视图输入时按 name/name_en 直接匹配不上，导致本函数返回 null、依赖 .type 的 Provider 漏触发。
        // 统一在此补反查匹配，一处修复全局生效，避免每个 Provider 各自修。
        val enName = if (propertyName.any { it.code in 0x4E00..0x9FFF })
            translationDict?.getTranslationBack(propertyName)?.takeIf { it != propertyName }
        else null
        for (section in sectionsToSearch.distinct()) {
            val prop = sectionProperties[section].orEmpty().find {
                it.name == propertyName || it.name_en == propertyName ||
                    (enName != null && (it.name.equals(enName, ignoreCase = true) || it.name_en.equals(enName, ignoreCase = true)))
            }
            if (prop != null) return prop
        }
        return null
    }

    /**
     * 通过翻译库把中文属性名转成英文，供各 Provider 查表时使用。
     * 若当前属性名已是英文或翻译库未就绪，则返回原值。
     */
    fun toEnglishName(): String {
        if (propertyName.isEmpty()) return propertyName
        if (propertyName.none { it.code in 0x4E00..0x9FFF }) return propertyName
        val en = translationDict?.getTranslationBack(propertyName) ?: return propertyName
        return if (en == propertyName) propertyName else en
    }

    /**
     * 通过翻译库检查英文属性名映射到中文后是否匹配当前输入的 propertyName。
     * 用于判断如输入"临时标签添加"是否对应 temporallyAddTags 这类 tag 属性。
     */
    fun chineseNameMatchesEnglish(targetEn: String): Boolean {
        if (propertyName.isEmpty()) return false
        val zh = translationDict?.getTranslation(targetEn) ?: return false
        return zh.equals(propertyName, ignoreCase = true)
    }

    /**
     * 当前属性名是否为中文
     */
    fun isChineseName(): Boolean = propertyName.any { it.code in 0x4E00..0x9FFF }
    fun isInsideParentheses(): Boolean = isInsideParentheses(textBeforeCursor)
}

/**
 * 判断文本中光标位置是否位于值内未闭合的 '(' 之后（括号内）：
 * 从行内最后一个 ':' 之后向前扫描，遇到未闭合的 '(' 返回 true，遇到 ')' 返回 false。
 */
internal fun isInsideParentheses(textBeforeCursor: String): Boolean {
    // 键值分隔取行内第一个 ':'（与 splitKeyValueLine 一致）；值内可含 ':'（如 ROOT:/CUSTOM:）
    val colonIdx = textBeforeCursor.indexOf(':')
    if (colonIdx == -1) return false
    for (i in textBeforeCursor.length - 1 downTo colonIdx + 1) {
        when (textBeforeCursor[i]) {
            '(' -> return true
            ')' -> return false
        }
    }
    return false
}
