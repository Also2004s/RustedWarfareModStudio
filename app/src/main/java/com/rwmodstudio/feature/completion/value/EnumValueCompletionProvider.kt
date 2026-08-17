package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 通用枚举值补全。
 * 根据属性名或属性类型匹配 data/value/ 下的枚举定义文件。
 */
class EnumValueCompletionProvider : BaseValueCompletionProvider() {

    /**
     * 属性名 -> 值文件名 的精确映射。
     */
    private val nameToFileMap = mapOf(
        "movementType" to "movementType",
        "drawType" to "drawType",
        "layer" to "layer",
        "displayType" to "displayType",
        "drawLayer" to "drawLayer",
        "teamColoringMode" to "teamColoringMode",
        "attackMovement" to "attackMovement",
        "onActions" to "onActions",
        "onlyTeam" to "onlyTeam",
        "searchTeam" to "searchTeam",
        "addWaypoint_type" to "addWaypoint_type",
        "addWaypoint_target_nearestUnit_team" to "addWaypoint_target_nearestUnit_team",
        "addWaypoint_target_randomUnit_team" to "addWaypoint_target_nearestUnit_team",
        "fireTurretXAtGround_onlyOverPassableTileOf" to "fireTurretXAtGround_onlyOverPassableTileOf",
        "turretsTargetGroundOnlyOverPassableTileOf" to "fireTurretXAtGround_onlyOverPassableTileOf",
        "takeResources_includeUnitsWithinRange_team" to "takeResources_includeUnitsWithinRange_team",
        "transportUnitsRequireMovementType" to "transportUnitsRequireMovementType",
        "convertTo_keepCurrentFields" to "convertTo_keepCurrentFields",
        "whenBuilding_temporarilyConvertTo_keepFields" to "whenBuilding_temporarilyConvertTo_keepFields",
        "onNewMapSpawn" to "onNewMapSpawn",
        "displayDigitGrouping" to "displayDigitGrouping",
        "setUnitStats" to "setUnitStats",
        "autoTriggerCheckRate" to "autoTriggerCheckRate"
    )

    /**
     * 类型 -> 值文件名 的映射。
     */
    private val typeToFileMap = mapOf(
        "movementtype" to "movementType",
        "movementtypes" to "movementType",
        "drawtype" to "drawType",
        "drawlayer" to "drawLayer",
        "layer" to "layer",
        "displaytype" to "displayType",
        "teamcoloringmode" to "teamColoringMode",
        "attackmovement" to "attackMovement"
    )

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 括号内由 FunctionParameterCompletionProvider 处理，此处抑制避免刷枚举全表
        if (request.isInsideParentheses()) return false
        return resolveFileName(request) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val fileName = resolveFileName(request) ?: return emptyList()
        val data = ValueDataLoader.load(request.context, fileName, request.translationDict)
        val prefix = request.valuePrefix
        return data.data
            .map { it to completionMatchLevel(prefix, it.name) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ValueDataLoader.ValueItem, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .map {
                createValueItem(
                    label = it.name,
                    detail = "枚举值",
                    insertText = it.name,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
    }

    private fun resolveFileName(request: ValueCompletionRequest): String? {
        // 优先按属性名精确匹配（英文 name_en 也在 findProperty 中处理）
        nameToFileMap[request.propertyName]?.let { return it }
        val prop = request.findProperty()
        prop?.name_en?.let { nameToFileMap[it]?.let { file -> return file } }
        prop?.name?.let { nameToFileMap[it]?.let { file -> return file } }

        // 通过翻译库反向查找：中文属性名 → 英文 → nameToFileMap
        if (request.isChineseName()) {
            nameToFileMap[request.toEnglishName()]?.let { return it }
        }

        // 再按类型匹配（取第一个有效 token）
        prop ?: return null
        val typeToken = prop.type.lowercase().split(" ", "/", "_").firstOrNull { it.isNotBlank() }
        return typeToFileMap[typeToken]
    }
}
