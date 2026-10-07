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
        "autoTriggerCheckRate" to "autoTriggerCheckRate",
        // 内置预设选项类：死亡爆炸类型 / 效果图像集（desc 给出明确内置名单）
        "explodeTypeOnDeath" to "explodeTypeOnDeath",
        "stripIndex" to "stripIndex",
        // 优先级：接受关键字 veryLow/low/normal/high/veryHigh/critical（也可填数字）
        "priority" to "priority"
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
        // 打了空格 / Tab：空格不是合法分隔符——
        // 多值枚举（transportUnitsRequireMovementType / convertTo_keepCurrentFields /
        // whenBuilding_temporarilyConvertTo_keepFields）→ 只弹「,」；
        // setUnitStats（fields values）段内空格是「字段 += 值」的合法分隔，不拦截（保留运算符输入）；
        // 仅当「= 之后已写完值」时让位给 LogicBoolean 的「,」；
        // 其余单值枚举 → 值已写完，不弹。
        if (needsCommaSeparator(request)) {
            if (fileName == "setUnitStats") {
                // fields values（设置单位状态 护盾值 += 20）：段内空格是「字段 += 值」的合法分隔，
                // 不拦截（保留输入运算符前后的空格）。但「= 之后已写完值」再打空格（`护盾值 += 20 `）
                // 是「换下一组」的手势 → 让位给 LogicBooleanValueCompletionProvider 的「,」，
                // 本 Provider 不再倒出整份字段名（历史：此处会倒出全部字段名）。
                if (currentSegmentRhs(request.textBeforeCursor)?.trim()?.isNotEmpty() == true) return emptyList()
            } else if (isMultiValueEnumProperty(request)) {
                return listOf(commaSeparatorItem("枚举分隔符（值与值之间用逗号分隔）"))
            } else {
                return emptyList()
            }
        }
        val data = ValueDataLoader.load(request.context, fileName, request.translationDict)
        val prefix = request.valuePrefix
        return data.data
            .map { it to completionMatchLevel(prefix, it.name) }
            .filter { it.second > 0 }
            // 并列时保持数据文件声明顺序（稳定排序），不再按名称字典序——
            // 各枚举数据文件的条目顺序即人工整理好的推荐顺序（按真实项目使用频率排定）。
            .sortedWith(compareByDescending<Pair<ValueDataLoader.ValueItem, Int>> { it.second })
            .map { it.first }
            .map {
                createValueItem(
                    label = it.name,
                    detail = ValueDescriptionRepository.lookup(request.context, it.description) ?: "枚举值",
                    insertText = it.name,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
    }

    /**
     * 可一次填多个的枚举属性（依据 code_reference 官方示例）：
     * - 被运输单位类型限制: 空中, 水面
     * - 转换时保留字段: 装甲, maxEnergy, 生命值（fields：字段名之间逗号分隔，段内无空格）
     * - whenBuilding_temporarilyConvertTo_keepFields: 生命值, maxEnergy（同上）
     * 注意：设置单位状态（setUnitStats，fields values）**不在其中** —— 它的段内空格是
     * 「字段 += 值」的合法分隔（`护盾值 += 20`），打空格弹「,」会拦住运算符输入。
     */
    private val multiValueEnumProperties = setOf(
        "transportunitsrequiremovementtype",
        "convertto_keepcurrentfields",
        "whenbuilding_temporarilyconvertto_keepfields"
    )

    private fun isMultiValueEnumProperty(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty()
        val names = listOfNotNull(request.propertyName, prop?.name, prop?.name_en, request.toEnglishName())
        return names.any { it.trim().lowercase() in multiValueEnumProperties }
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
