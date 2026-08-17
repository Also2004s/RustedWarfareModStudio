package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.CompletionProvider

private const val TAG = "ValueCompletionAggregator"

/**
 * 值补全聚合器。
 * 维护多个具体 Provider，按 VS Code 插件架构依次调用并合并结果。
 */
class ValueCompletionAggregator(
    context: Context,
    private val sectionProperties: Map<String, List<CodeReferenceRepository.PropertyInfo>>,
    private val options: ValueCompletionOptions = ValueCompletionOptions(),
    private val translationDict: TranslationDict? = null
) {
    data class ValueCompletionOptions(
        val boolEnabled: Boolean = true,
        val logicBooleanEnabled: Boolean = true,
        val enumEnabled: Boolean = true,
        val imageEnabled: Boolean = false,
        val unitSpawnEnabled: Boolean = true,
        val autoTriggerOnEventEnabled: Boolean = true,
        val memoryEnabled: Boolean = true,
        val tagEnabled: Boolean = true,
        val resourceEnabled: Boolean = true,
        val unitNameEnabled: Boolean = true,
        val unitRefEnabled: Boolean = true,
        val functionParamEnabled: Boolean = true,
        val projectRefEnabled: Boolean = true,
        val copyFromSectionEnabled: Boolean = true,
        val defineVariableEnabled: Boolean = true,
        val crossSectionRefEnabled: Boolean = true
    )

    private val providers = mutableListOf<BaseValueCompletionProvider>()

    init {
        if (options.boolEnabled) providers.add(BoolValueCompletionProvider())
        if (options.logicBooleanEnabled) providers.add(LogicBooleanValueCompletionProvider())
        if (options.enumEnabled) providers.add(EnumValueCompletionProvider())
        if (options.imageEnabled) providers.add(ImageValueCompletionProvider())
        if (options.unitSpawnEnabled) providers.add(UnitSpawnCompletionProvider())
        if (options.autoTriggerOnEventEnabled) providers.add(AutoTriggerOnEventValueCompletionProvider())
        if (options.memoryEnabled) providers.add(MemoryValueCompletionProvider())
        if (options.tagEnabled) providers.add(TagValueCompletionProvider())
        if (options.resourceEnabled) providers.add(ResourceValueCompletionProvider())
        if (options.unitNameEnabled) providers.add(UnitNameValueCompletionProvider())
        if (options.unitRefEnabled) providers.add(UnitRefValueCompletionProvider())
        if (options.functionParamEnabled) providers.add(FunctionParameterCompletionProvider())
        if (options.projectRefEnabled) providers.add(ProjectRefValueCompletionProvider())
        if (options.copyFromSectionEnabled) providers.add(CopyFromSectionValueCompletionProvider())
        if (options.defineVariableEnabled) providers.add(DefineVariableValueCompletionProvider())
        if (options.crossSectionRefEnabled) providers.add(CrossSectionRefValueCompletionProvider())
    }

    /**
     * 单个 Provider 的补全结果（供演示/查看器按来源分组展示）。
     */
    data class ProviderResult(
        val providerLabel: String,
        val items: List<CompletionProvider.CompletionItem>
    )

    /**
     * 获取值补全候选。
     *
     * @param context Android Context，用于读取 assets
     * @param propertyName 属性名（: 左侧）
     * @param sectionName 当前节名（已映射为中文或英文，Provider 内部会自行查找）
     * @param valuePrefix 当前已输入的值前缀（trim 后）
     * @param rawValuePrefixLength : 后实际字符数（用于计算替换长度）
     * @param lineText 当前整行文本
     * @param textBeforeCursor 光标前文本
     * @param textAfterCursor 光标后文本
     */
    fun getValueCompletions(
        context: Context,
        propertyName: String,
        sectionName: String?,
        valuePrefix: String,
        rawValuePrefixLength: Int,
        lineText: String,
        textBeforeCursor: String,
        textAfterCursor: String,
        memoryNames: Set<String> = emptySet(),
        memoryTypes: Map<String, String> = emptyMap(),
        globalVariables: Set<String> = emptySet(),
        localVariables: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        globalTags: Set<String> = emptySet(),
        messageTags: Set<String> = emptySet(),
        actionTags: Set<String> = emptySet(),
        resources: Set<String> = emptySet(),
        globalResources: Set<String> = emptySet(),
        unitNames: Set<String> = emptySet(),
        turretNames: Set<String> = emptySet(),
        projectileNames: Set<String> = emptySet(),
        effectNames: Set<String> = emptySet(),
        actionNames: Set<String> = emptySet(),
        hiddenActionNames: Set<String> = emptySet(),
        animationNames: Set<String> = emptySet(),
        decalNames: Set<String> = emptySet(),
        attachmentNames: Set<String> = emptySet(),
        buildableNames: Set<String> = emptySet(),
        soundFiles: Set<String> = emptySet(),
        chainSectionNames: Map<String, Set<String>> = emptyMap()
    ): List<CompletionProvider.CompletionItem> {
        return getValueCompletionsGrouped(
            context = context,
            propertyName = propertyName,
            sectionName = sectionName,
            valuePrefix = valuePrefix,
            rawValuePrefixLength = rawValuePrefixLength,
            lineText = lineText,
            textBeforeCursor = textBeforeCursor,
            textAfterCursor = textAfterCursor,
            memoryNames = memoryNames,
            memoryTypes = memoryTypes,
            globalVariables = globalVariables,
            localVariables = localVariables,
            tags = tags,
            globalTags = globalTags,
            messageTags = messageTags,
            actionTags = actionTags,
            resources = resources,
            globalResources = globalResources,
            unitNames = unitNames,
            turretNames = turretNames,
            projectileNames = projectileNames,
            effectNames = effectNames,
            actionNames = actionNames,
            hiddenActionNames = hiddenActionNames,
            animationNames = animationNames,
            decalNames = decalNames,
            attachmentNames = attachmentNames,
            buildableNames = buildableNames,
            soundFiles = soundFiles,
            chainSectionNames = chainSectionNames
        ).flatMap { it.items }
    }

    /**
     * 获取值补全候选，并按来源 Provider 分组返回（补全查看器用）。
     * 参数含义与 [getValueCompletions] 完全一致；空结果的 Provider 不参与输出。
     */
    fun getValueCompletionsGrouped(
        context: Context,
        propertyName: String,
        sectionName: String?,
        valuePrefix: String,
        rawValuePrefixLength: Int,
        lineText: String,
        textBeforeCursor: String,
        textAfterCursor: String,
        memoryNames: Set<String> = emptySet(),
        memoryTypes: Map<String, String> = emptyMap(),
        globalVariables: Set<String> = emptySet(),
        localVariables: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        globalTags: Set<String> = emptySet(),
        messageTags: Set<String> = emptySet(),
        actionTags: Set<String> = emptySet(),
        resources: Set<String> = emptySet(),
        globalResources: Set<String> = emptySet(),
        unitNames: Set<String> = emptySet(),
        turretNames: Set<String> = emptySet(),
        projectileNames: Set<String> = emptySet(),
        effectNames: Set<String> = emptySet(),
        actionNames: Set<String> = emptySet(),
        hiddenActionNames: Set<String> = emptySet(),
        animationNames: Set<String> = emptySet(),
        decalNames: Set<String> = emptySet(),
        attachmentNames: Set<String> = emptySet(),
        buildableNames: Set<String> = emptySet(),
        soundFiles: Set<String> = emptySet(),
        chainSectionNames: Map<String, Set<String>> = emptyMap()
    ): List<ProviderResult> {
        val request = ValueCompletionRequest(
            context = context,
            sectionProperties = sectionProperties,
            propertyName = propertyName,
            sectionName = sectionName,
            valuePrefix = valuePrefix,
            rawValuePrefixLength = rawValuePrefixLength,
            lineText = lineText,
            textBeforeCursor = textBeforeCursor,
            textAfterCursor = textAfterCursor,
            translationDict = translationDict,
            memoryNames = memoryNames,
            memoryTypes = memoryTypes,
            globalVariables = globalVariables,
            localVariables = localVariables,
            tags = tags,
            globalTags = globalTags,
            messageTags = messageTags,
            actionTags = actionTags,
            resources = resources,
            globalResources = globalResources,
            unitNames = unitNames,
            turretNames = turretNames,
            projectileNames = projectileNames,
            effectNames = effectNames,
            actionNames = actionNames,
            hiddenActionNames = hiddenActionNames,
            animationNames = animationNames,
            decalNames = decalNames,
            attachmentNames = attachmentNames,
            buildableNames = buildableNames,
            soundFiles = soundFiles,
            chainSectionNames = chainSectionNames
        )

        val results = mutableListOf<ProviderResult>()
        for (provider in providers) {
            try {
                val items = provider.provideCompletionItems(request)
                if (items.isNotEmpty()) {
                    results.add(ProviderResult(providerLabel(provider), items))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Provider ${provider.javaClass.simpleName} failed", e)
            }
        }
        return results
    }

    /**
     * 全量可调用条目（供补全查看「+」菜单从 Provider 同源产出）。
     * 用全量宽松 request（无属性/无前缀），对逻辑相关 Provider（LogicBoolean/UnitRef/Memory）
     * 走 [BaseValueCompletionProvider.provideFullItems]（绕过 canProvide），收集与生产补全一致的条目。
     * 空结果的 Provider 不参与输出。
     */
    fun getAllCallableItems(
        context: Context,
        translationDict: TranslationDict? = null,
        memoryNames: Set<String> = emptySet(),
        memoryTypes: Map<String, String> = emptyMap()
    ): List<ProviderResult> {
        val request = ValueCompletionRequest(
            context = context,
            sectionProperties = sectionProperties,
            propertyName = "",
            sectionName = null,
            valuePrefix = "",
            rawValuePrefixLength = 0,
            lineText = "",
            textBeforeCursor = "",
            textAfterCursor = "",
            translationDict = translationDict ?: this.translationDict,
            memoryNames = memoryNames,
            memoryTypes = memoryTypes
        )
        val results = mutableListOf<ProviderResult>()
        // 统一单位标记源（unitMarkerItems）被 LogicBoolean/UnitRef 等多个 Provider 内置，
        // 全量聚合会重复产出 self 等条目，这里按 插入文本+label 跨 Provider 去重，只保留首个来源。
        val seen = mutableSetOf<String>()
        for (provider in providers) {
            val isLogicProvider = provider is LogicBooleanValueCompletionProvider ||
                provider is UnitRefValueCompletionProvider ||
                provider is MemoryValueCompletionProvider
            if (!isLogicProvider) continue
            try {
                val items = provider.provideFullItems(request).filter { item ->
                    val key = "${item.insertText}|${item.label}"
                    seen.add(key)
                }
                if (items.isNotEmpty()) {
                    results.add(ProviderResult(providerLabel(provider), items))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Provider ${provider.javaClass.simpleName} getAllCallableItems failed", e)
            }
        }
        return results
    }

    /** Provider 简单名 → 中文标签（未命中回退为简单名），供补全查看器展示来源 */
    private fun providerLabel(provider: BaseValueCompletionProvider): String {
        return when (provider.javaClass.simpleName) {
            "BoolValueCompletionProvider" -> "布尔值"
            "LogicBooleanValueCompletionProvider" -> "逻辑表达式"
            "EnumValueCompletionProvider" -> "枚举值"
            "ImageValueCompletionProvider" -> "图片路径"
            "UnitSpawnCompletionProvider" -> "单位生成"
            "AutoTriggerOnEventValueCompletionProvider" -> "事件触发"
            "MemoryValueCompletionProvider" -> "内存变量"
            "TagValueCompletionProvider" -> "标签"
            "ResourceValueCompletionProvider" -> "资源"
            "UnitNameValueCompletionProvider" -> "单位类型"
            "UnitRefValueCompletionProvider" -> "单位标记"
            "FunctionParameterCompletionProvider" -> "函数参数"
            "ProjectRefValueCompletionProvider" -> "项目引用"
            "CopyFromSectionValueCompletionProvider" -> "复制节"
            "DefineVariableValueCompletionProvider" -> "变量定义"
            "CrossSectionRefValueCompletionProvider" -> "跨节引用"
            else -> provider.javaClass.simpleName
        }
    }
}
