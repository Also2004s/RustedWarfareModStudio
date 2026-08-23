package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider

/**
 * 值补全提供者基类。
 * 子类只需实现 [canProvide] 与 [provideItems]。
 */
abstract class BaseValueCompletionProvider {

    /**
     * 判断当前请求是否应由本 Provider 处理。
     */
    protected abstract fun canProvide(request: ValueCompletionRequest): Boolean

    /**
     * 公开触发判定入口（供「真实补全判定」等复用 [canProvide]）。
     * 与补全生产路径共用同一判定源，保证分类归属与真实补全一致。
     */
    fun matches(request: ValueCompletionRequest): Boolean = canProvide(request)

    /**
     * 返回补全候选。
     */
    protected abstract fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem>

    fun provideCompletionItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        if (!canProvide(request)) return emptyList()
        return provideItems(request)
    }

    /**
     * 全量条目入口：绕过 [canProvide]，直接用全量宽松 [request] 调用 [provideItems]。
     * 供补全查看「+」菜单从 Provider 产出同源条目（与生产补全一致）。
     */
    fun provideFullItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        return provideItems(request)
    }

    /**
     * 通用构建函数：把值名包装成 VALUE 类型补全项。
     */
    protected fun createValueItem(
        label: String,
        detail: String,
        insertText: String = label,
        prefixLength: Int,
        valueType: String = "",
        isCallable: Boolean = true
    ): CompletionProvider.CompletionItem {
        return CompletionProvider.CompletionItem(
            label = label,
            type = CompletionProvider.CompletionType.VALUE,
            detail = detail,
            insertText = insertText,
            valuePrefixLength = prefixLength.coerceAtLeast(0),
            valueType = valueType,
            isCallable = isCallable
        )
    }
}
