package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 标签值补全。
 * 触发判定：硬编码精确名单（CompletionShared.tagProperties，经 code_reference 逐条核实真实用法）优先，
 * 含 type 不匹配但用法是标签的属性（onlyUseAsHarvester_ifBaseHasUnitTagged 等 string 型）；名单未命中回退
 * type 初筛（tags/tag list/tag ref/message tag）。全程不做属性名 contains(tag/标签/global) 模糊匹配。
 * 按属性区分：全局标签（addGlobalTeamTags/removeGlobalTeamTags）→ 全局标签，否则 → 本地标签。
 * type 为 message tag（带标签发送消息）→ 消息标签（sendMessageWithTags 项目使用处的取值，独立命名空间）。
 */
class TagValueCompletionProvider : BaseValueCompletionProvider() {

    /** 触发标签补全的 type 集合（初筛） */
    private val tagTypes = setOf("tags", "tag list", "tag ref")

    /** 消息标签类型（带标签发送消息/sendMessageWithTags）：来源为项目里 sendMessageWithTags: 行的取值 */
    private val messageTagType = "message tag"

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 光标位于值内未闭合的 '(' 之后（如 添加标签:(）时，不触发标签补全
        if (request.isInsideParentheses()) return false
        // 硬编码精确名单优先（含 mutatorX_ 通配，与分类页共源 CompletionShared）——真实标签属性必然命中
        if (CompletionShared.isTagProperty(request.toEnglishName())) return true
        // type 初筛兜底（type 不可信，仅作第一道过滤；错误数据已由上述精确名单排除）
        val prop = request.findProperty()
        if (prop != null) {
            val typeLower = prop.type.lowercase().trim()
            if (typeLower == messageTagType) return true
            if (typeLower in tagTypes) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 标签间用逗号分隔：当用户用空格分隔（光标在标签值内且前一字符是空格/制表符）时，
        // 空格不是合法的标签分隔符，此时不补标签名，只补「,」引导用户用逗号分隔；
        // 只有输入「,」后才继续补标签名。判定与「,」项统一走公共实现（与文件/音效等多值补全一致），
        // 选中「,」时会吃掉刚打的空格，直接得到 `a,b`。
        if (needsCommaSeparator(request)) {
            return listOf(commaSeparatorItem("标签分隔符（标签间用逗号分隔）"))
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

        // 全局标签精确判定（CompletionShared.globalTagProperties：addGlobalTeamTags/removeGlobalTeamTags），
        // 不做属性名 contains(global) 模糊匹配
        val isGlobal = CompletionShared.isGlobalTagProperty(request.toEnglishName())
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
