package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 从值片段中提取 ${ 引用已输入的变量名：
 * 只要值片段含 `${`（不管 $ 前面是什么，如 `攻击范围=${攻`）即触发，
 * 取最后一个 `${` 之后的片段（可为空 = 全量提示）；
 * `${节名.属性`（如 ${攻击.攻击距离}）含点，为节属性引用 → null（不参与变量补全）。
 */
internal fun defineVariableTyped(prefix: String): String? {
    val idx = prefix.lastIndexOf("\${")
    if (idx < 0) return null
    val inner = prefix.substring(idx + 2).trim()
    if (inner.contains('.')) return null
    return inner
}

/**
 * ${ 变量引用补全：${全局变量} / ${局部变量}。
 * 全局变量（@global）项目级可见；局部变量（@define）仅当前节 + 继承链同节可见
 * （request.globalVariables / localVariables 已由 CompletionProvider 按此聚合）。
 * 输入 `${` 后（值上下文）补变量名，仅替换变量名片段（保留 ${ 前缀）；
 * `${节名.属性`（如 ${攻击.攻击距离}）为节属性引用，不参与（defineVariableTyped 返回 null）。
 */
class DefineVariableValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 只要值前缀识别到 ${ 就直接触发（候选是否为空由 provideItems 决定，不在此拦截）
        return defineVariableTyped(request.valuePrefix) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val typed = defineVariableTyped(request.valuePrefix) ?: return emptyList()
        val candidates = request.globalVariables + request.localVariables
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .map { it to completionMatchLevel(typed, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .map { it.first }
            .map { name ->
                val isGlobal = name in request.globalVariables
                createValueItem(
                    label = name,
                    detail = if (isGlobal) "全局变量" else "局部变量",
                    insertText = name,
                    prefixLength = typed.length,
                    valueType = "any"
                )
            }
    }
}
