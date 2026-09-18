package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * class 属性值补全：RW 文档规定 class 唯一合法值为 CustomUnitMetadata
 * （core.class，type=string，说明「无实际用处，默认情况下必须为 CustomUnitMetadata」）。
 *
 * 硬编码英文名单精确匹配（英文为基），中文说明经 code_reference 的 desc_zh 提供，不做中文化插入。
 */
class ClassValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 英文名单硬编码：class（中文视图输入 类别 时经翻译库反查为 class）
        return request.toEnglishName().lowercase() == "class"
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 单值（唯一合法值 CustomUnitMetadata）：打空格 → 值已写完，不弹
        if (needsCommaSeparator(request)) return emptyList()
        val prefix = request.valuePrefix
        // 唯一合法值：专有名词，无中文翻译，不做汉化
        val value = "CustomUnitMetadata"
        if (completionMatchLevel(prefix, value) <= 0) return emptyList()
        val detail = request.findProperty()?.desc_zh?.takeIf { it.isNotBlank() } ?: "类别（默认唯一合法值）"
        return listOf(
            createValueItem(
                label = value,
                detail = detail,
                insertText = value,
                prefixLength = prefix.length,
                valueType = "string"
            )
        )
    }
}
