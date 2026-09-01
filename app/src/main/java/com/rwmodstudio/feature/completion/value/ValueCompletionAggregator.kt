package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.core.translation.CompletionTypeRepository
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
        if (options.unitNameEnabled) {
            providers.add(UnitNameValueCompletionProvider())
            providers.add(VanillaUnitNameValueCompletionProvider())
        }
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
        vanillaUnitNames: Set<String> = emptySet(),
        unitData: Map<String, ProjectTagScanner.UnitBuildInfo> = emptyMap(),
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
        chainSectionNames: Map<String, Set<String>> = emptyMap(),
        currentFileDir: String = "",
        projectRoot: String = ""
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
            vanillaUnitNames = vanillaUnitNames,
            unitData = unitData,
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
            chainSectionNames = chainSectionNames,
            currentFileDir = currentFileDir,
            projectRoot = projectRoot
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
        vanillaUnitNames: Set<String> = emptySet(),
        unitData: Map<String, ProjectTagScanner.UnitBuildInfo> = emptyMap(),
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
        chainSectionNames: Map<String, Set<String>> = emptyMap(),
        currentFileDir: String = "",
        projectRoot: String = ""
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
            vanillaUnitNames = vanillaUnitNames,
            unitData = unitData,
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
            chainSectionNames = chainSectionNames,
            currentFileDir = currentFileDir,
            projectRoot = projectRoot
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

    /**
     * 真实补全判定：按「生产补全实际会由哪个 Provider 服务该属性」归类。
     *
     * 与生产补全用完全相同的 [providers]（同顺序）逐个调用 [BaseValueCompletionProvider.matches]，
     * 首个命中的 Provider 决定归属——分类与真实自动补全同源，不存在第二份名单，永不漂移。
     *
     * @param name 属性名（中文或英文，与分类页展示一致）
     * @param nameEn 属性英文名（无则传 null）
     * @param sectionName 该属性所属真实节名（用于依赖节的判定，如 复制与 仅核心节走文件路径；
     *                    分类页按各真实节判定时传入；无节上下文传 null）
     * @return 分类名（CompletionTypeRepository.CAT_*）；无任何 Provider 命中返回「文字/任意」兜底
     */
    fun realCompletionClassify(
        context: Context,
        name: String,
        nameEn: String?,
        sectionName: String? = null
    ): String {
        val request = ValueCompletionRequest(
            context = context,
            sectionProperties = sectionProperties,
            propertyName = name,
            sectionName = sectionName,
            valuePrefix = "",
            rawValuePrefixLength = 0,
            lineText = "",
            textBeforeCursor = "",
            textAfterCursor = "",
            translationDict = translationDict
        )
        for (provider in providers) {
            try {
                if (provider.matches(request)) {
                    return realCategoryOf(provider, name, nameEn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "realCompletionClassify ${provider.javaClass.simpleName} failed", e)
            }
        }
        return CompletionTypeRepository.CAT_TEXT
    }

    /** Provider → 分类名（与生产补全的 Provider 语义一致；UnitSpawn 需按抛射体/单位生成区分归属） */
    private fun realCategoryOf(provider: BaseValueCompletionProvider, name: String, nameEn: String?): String {
        return when (provider) {
            is BoolValueCompletionProvider -> CompletionTypeRepository.CAT_BOOL
            is LogicBooleanValueCompletionProvider -> CompletionTypeRepository.CAT_LOGIC
            is EnumValueCompletionProvider -> CompletionTypeRepository.CAT_ENUM
            is ImageValueCompletionProvider -> CompletionTypeRepository.CAT_IMAGE
            is UnitSpawnCompletionProvider -> {
                val en = (nameEn ?: name)
                if (CompletionShared.isProjectileSpawnProperty(en)) CompletionTypeRepository.CAT_PROJECT_REF
                else CompletionTypeRepository.CAT_UNIT_NAME
            }
            is AutoTriggerOnEventValueCompletionProvider -> CompletionTypeRepository.CAT_EVENT
            is MemoryValueCompletionProvider -> CompletionTypeRepository.CAT_MEMORY
            is TagValueCompletionProvider -> CompletionTypeRepository.CAT_TAG
            is ResourceValueCompletionProvider -> CompletionTypeRepository.CAT_RESOURCE
            is UnitNameValueCompletionProvider, is VanillaUnitNameValueCompletionProvider ->
                CompletionTypeRepository.CAT_UNIT_NAME
            is UnitRefValueCompletionProvider -> CompletionTypeRepository.CAT_UNIT_MARKER
            is ProjectRefValueCompletionProvider -> CompletionTypeRepository.CAT_PROJECT_REF
            is CopyFromSectionValueCompletionProvider -> CompletionTypeRepository.CAT_COPY_SECTION
            else -> CompletionTypeRepository.CAT_TEXT
        }
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
            "VanillaUnitNameValueCompletionProvider" -> "原版单位"
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
