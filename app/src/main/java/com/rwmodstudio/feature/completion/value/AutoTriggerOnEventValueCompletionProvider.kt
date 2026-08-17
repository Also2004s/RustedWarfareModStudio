package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * autoTriggerOnEvent 事件参数补全。
 * 对应 VS Code 插件的 AutoTriggerOnEventValueCompletionProvider。
 * 中文属性名通过翻译库反向查找判别。
 */
class AutoTriggerOnEventValueCompletionProvider : BaseValueCompletionProvider() {

    private val targetPropName = "autoTriggerOnEvent"

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 括号内由 FunctionParameterCompletionProvider 补带参事件（如 tookDamage(withTag=)），此处抑制避免全表刷屏
        if (request.isInsideParentheses()) return false
        if (request.propertyName.equals(targetPropName, ignoreCase = true)) return true
        val prop = request.findProperty() ?: return false
        if (prop.name.equals(targetPropName, ignoreCase = true) ||
            prop.name_en.equals(targetPropName, ignoreCase = true)) return true
        // 中文属性名通过翻译库反向查找
        if (request.isChineseName()) {
            return request.toEnglishName().equals(targetPropName, ignoreCase = true)
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 加载原始英文数据，统一走 translateValueName 剥离参数括号后翻译（tookDamage(withTag="#")→受到伤害 等）
        val data = ValueDataLoader.load(request.context, targetPropName)
        val prefix = request.valuePrefix
        return data.data
            .map { item ->
                val zh = translateValueName(request.translationDict, item.name)
                item to maxOf(completionMatchLevel(prefix, item.name), completionMatchLevel(prefix, zh))
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ValueDataLoader.ValueItem, Int>> { it.second })
            .map { it.first }
            .map {
                createValueItem(
                    label = translateValueName(request.translationDict, it.name),
                    detail = "事件",
                    insertText = translateValueName(request.translationDict, it.name),
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "any"
                )
            }
            // 同 label 去重：self.xxx 与 xxx 翻译后同 label 只保留一次（与 LogicBoolean/Memory 的 distinctBy 对齐）
            .distinctBy { it.label }
    }
}
