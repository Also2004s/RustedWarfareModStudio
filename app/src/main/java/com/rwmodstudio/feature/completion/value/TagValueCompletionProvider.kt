package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 标签值补全。
 * type 为 tags / tag list / tag ref / message tag，或 type 为 string(s) 且属性名含 Tag 时触发。
 * 按属性名区分：含 Global/全局 → 全局标签，否则 → 本地标签。
 * type 为 message tag（带标签发送消息）→ 消息标签（sendMessageWithTags 项目使用处的取值，独立命名空间）。
 * 中文属性名通过翻译库反向查找判别。
 */
class TagValueCompletionProvider : BaseValueCompletionProvider() {

    /** 触发标签补全的 type 集合 */
    private val tagTypes = setOf("tags", "tag list", "tag ref")

    /** 消息标签类型（带标签发送消息/sendMessageWithTags）：来源为项目里 sendMessageWithTags: 行的取值 */
    private val messageTagType = "message tag"

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 光标位于值内未闭合的 '(' 之后（如 添加标签:(）时，不触发标签补全
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty()
        if (prop != null) {
            val typeLower = prop.type.lowercase().trim()
            if (typeLower == messageTagType) return true
            if (typeLower in tagTypes) return true
            // type 为 string(s) 且属性名含 Tag/标签
            if (typeLower == "string(s)") {
                val name = listOfNotNull(prop.name, prop.name_en).joinToString(" ").lowercase()
                if (name.contains("tag")) return true
            }
            return false
        }
        // findProperty 未找到：英文含 tag，或通过翻译库反向查找
        if (request.propertyName.lowercase().contains("tag")) return true
        if (request.isChineseName()) {
            val enName = request.toEnglishName().lowercase()
            if (enName.contains("tag")) return true
            // 兜底：含"标签"
            if (request.propertyName.contains("标签")) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 标签间用逗号分隔：当用户用空格分隔（光标在标签值内且前一字符是空格/制表符）时，
        // 空格不是合法的标签分隔符，此时不补标签名，只补「,」引导用户用逗号分隔；
        // 只有输入「,」后才继续补标签名。若空格前已是逗号（已分隔完成），不再弹「,」。
        val lastChar = request.textBeforeCursor.lastOrNull()
        val prevNonSpace = request.textBeforeCursor.trimEnd().lastOrNull()
        if (!request.isInsideParentheses() && (lastChar == ' ' || lastChar == '\t') &&
            prevNonSpace != ',' && prevNonSpace != '，'
        ) {
            return listOf(
                createValueItem(
                    label = ",",
                    detail = "标签分隔符（标签间用逗号分隔）",
                    insertText = ",",
                    prefixLength = 0,
                    valueType = "string"
                )
            )
        }

        // 消息标签（带标签发送消息）：只列项目里 sendMessageWithTags: 的取值，独立命名空间
        val typeLower = request.findProperty()?.type?.lowercase()?.trim()
        if (typeLower == messageTagType) {
            val source = request.messageTags
            if (source.isEmpty()) return emptyList()
            val prefix = request.valuePrefix
            return source
                .map { it to completionMatchLevel(prefix, it) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
                .map { tag ->
                    createValueItem(
                        label = tag,
                        detail = "消息标签",
                        insertText = tag,
                        prefixLength = request.rawValuePrefixLength,
                        valueType = "string"
                    )
                }
        }

        val isGlobal = request.propertyName.lowercase().contains("global") ||
            request.isChineseName() && request.toEnglishName().lowercase().contains("global")
        val source = if (isGlobal) request.globalTags else request.tags
        if (source.isEmpty()) return emptyList()

        val prefix = request.valuePrefix
        return source
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .map { tag ->
                createValueItem(
                    label = tag,
                    detail = if (isGlobal) "全局标签" else "标签",
                    insertText = tag,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
    }
}
