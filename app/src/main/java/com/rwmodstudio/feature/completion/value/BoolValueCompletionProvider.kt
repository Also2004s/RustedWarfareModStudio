package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 是否为布尔值类型：归一化（小写、去空格、去 "-?" 后缀）后为 bool / boolean，
 * 或组合型前缀 bool/boolean（bool/string、bool/int、bool/effect，取 bool 分支补 真/假）。
 * 覆盖 "bool"、"boolean"、"bool -?"（如 帧随机）、"bool/string"（友伤）、"bool/int"（valueInStats）、"bool/effect"（尾焰）。
 */
internal fun isBoolValueType(type: String): Boolean {
    val normalized = type.lowercase().replace(" ", "").removeSuffix("-?")
    return normalized == "bool" || normalized == "boolean" ||
        normalized.startsWith("bool/") || normalized.startsWith("boolean/")
}

/**
 * 布尔值补全。
 * 对应 VS Code 插件的 BoolValueCompletionProvider。
 */
class BoolValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 括号内由 FunctionParameterCompletionProvider 处理，此处抑制避免刷 真/假
        if (request.isInsideParentheses()) return false
        // 复制但跳过节（@copyFrom_skipThisSection）是布尔指令，detail 为空，按名称启发触发
        val prop = request.findProperty()
        if (isCopyFromSkipSectionProperty(request.propertyName, prop?.name_en)) return true
        return prop != null && isBoolValueType(prop.type)
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prefix = request.valuePrefix
        // 数据源优先（bool.json 的 true/false 统一经 translateValueName 交给翻译引擎译成 真/假）；
        // 注意此处不传 translationDict：load 已翻译则 translateValueName 会再反向翻译一次（真→true），
        // 造成补全出英文 true/false。数据源缺失时兜底走英文 true/false，同样交给 translateValueName。
        val data = ValueDataLoader.load(request.context, "bool")
        val candidates = data.data.ifEmpty {
            listOf(
                ValueDataLoader.ValueItem(name = "true", description = ""),
                ValueDataLoader.ValueItem(name = "false", description = "")
            )
        }.map { item ->
            val zh = translateValueName(request.translationDict, item.name)
            item to completionMatchLevel(prefix, zh)
        }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<ValueDataLoader.ValueItem, Int>> { it.second })
        .map { it.first }
        .map {
            val zh = translateValueName(request.translationDict, it.name)
            createValueItem(
                label = zh,
                detail = it.description.ifEmpty { "布尔值" },
                insertText = zh,
                prefixLength = request.rawValuePrefixLength,
                valueType = "bool"
            )
        }
        return candidates
    }
}
