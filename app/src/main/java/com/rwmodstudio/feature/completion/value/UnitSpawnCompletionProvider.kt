package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 单位/抛射体生成类属性值补全。
 * 对应 VS Code 插件的 UnitSpawnCompletionProvider。
 * 支持整体示例补全与括号内参数补全。
 * 中文属性名通过翻译库反向查找判别。
 */
class UnitSpawnCompletionProvider : BaseValueCompletionProvider() {
    override fun canProvide(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty() ?: return false
        val names = setOfNotNull(prop.name, prop.name_en)
        if (names.any { CompletionShared.isUnitSpawnProperty(it) }) return true
        // 中文属性名通过翻译库反向查找
        if (request.isChineseName()) {
            val enName = request.toEnglishName()
            if (CompletionShared.isUnitSpawnProperty(enName)) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty() ?: return emptyList()
        // spawnUnit（单数，可位于抛射体节内）type 虽为 projectile ref，但语义是「在抛射体爆炸处产生单位」，
        // 值填单位名（已纳入 CompletionShared.unitSpawnProperties 单位名单）。故 type 兜底前须排除它，
        // 否则会被 contains("projectile") 误判成抛射体生成而只补抛射体节名。
        val enName = prop.name_en?.lowercase() ?: ""
        // 名单判定兼容两种形态：name_en（英文）或 name 本身（可能是英文如 spawnUnit，也可能是中文）。
        val inUnitSpawnList = enName in CompletionShared.unitSpawnProperties ||
            prop.name.lowercase() in CompletionShared.unitSpawnProperties
        val isProjectile = CompletionShared.isProjectileSpawnProperty(prop.name) ||
            (prop.name_en != null && CompletionShared.isProjectileSpawnProperty(prop.name_en)) ||
            (prop.type.lowercase().contains("projectile") && !inUnitSpawnList)
        val fileName = if (isProjectile) "spawnProjectiles" else "spawnUnits"
        val data = ValueDataLoader.load(request.context, fileName, request.translationDict)

        return if (request.isInsideParentheses()) {
            // 括号内：去重已用参数 + 按已输入前缀过滤，与 FunctionParameterCompletionProvider 行为对齐
            val used = usedParamKeysInParens(request.textBeforeCursor, data.data.map { it.name })
            val prefix = request.valuePrefix
            data.data
                .filter { it.name !in used }
                .map { it to completionMatchLevel(prefix, it.name) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<ValueDataLoader.ValueItem, Int>> { it.second })
                .map { it.first }
                .map {
                    val insert = "${it.name}="
                    createValueItem(
                        label = insert,
                        detail = ValueDescriptionRepository.lookup(request.context, it.description)
                            ?: "${it.type} - 参数",
                        insertText = insert,
                        prefixLength = request.rawValuePrefixLength,
                        valueType = it.type
                    )
                }
        } else {
            val items = mutableListOf<CompletionProvider.CompletionItem>()
            // 括号外：列真实候选名（按已输入前缀过滤），不做示例格式兜底。
            // 单位生成列单位名（项目单位 + 原版单位，项目优先去重）；抛射体生成列项目抛射体节名。
            // 抛射体(生成为例)不补内置抛射体名——抛射体需配合项目定义节，不强行给原版清单。
            val names = if (isProjectile) request.projectileNames
            else (request.unitNames + request.vanillaUnitNames).distinct()
            val nameDetail = if (isProjectile) "项目抛射体" else "单位名"
            val nameType = if (isProjectile) "projectileref" else "unitref"
            if (names.isNotEmpty()) {
                val prefix = request.valuePrefix
                names
                    .map { it to completionMatchLevel(prefix, it) }
                    .filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
                    .map { it.first }
                    .forEach { name ->
                        items.add(
                            createValueItem(
                                label = name,
                                // 生成单位的原版单位名查中文说明（heavyTank→重型坦克）；
                                // 项目单位名用户自定义：核心节建造信息（建造速度换算为秒 + 价格），无数据回退 nameDetail
                                detail = if (!isProjectile && name in request.vanillaUnitNames && name !in request.unitNames) {
                                    ValueDescriptionRepository.lookup(request.context, "data.value.vanillaunit.$name") ?: nameDetail
                                } else {
                                    unitBuildDetail(request.unitData[name], nameDetail)
                                },
                                insertText = name,
                                prefixLength = request.rawValuePrefixLength,
                                valueType = nameType
                            )
                        )
                    }
            }
            // 不做示例兜底：示例是结构化模板噪音，非真实候选名。
            // 括号外只提供真实候选名（单位生成列单位名，抛射体生成列抛射体节名），
            // 带参表达式由上方括号内参数补全承载。
            items
        }
    }
}
