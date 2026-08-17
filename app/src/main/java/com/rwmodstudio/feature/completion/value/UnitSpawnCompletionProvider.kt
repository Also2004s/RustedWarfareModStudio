package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 单位/抛射体生成类属性值补全。
 * 对应 VS Code 插件的 UnitSpawnCompletionProvider。
 * 支持整体示例补全与括号内参数补全。
 * 中文属性名通过翻译库反向查找判别。
 */
class UnitSpawnCompletionProvider : BaseValueCompletionProvider() {

    private val unitSpawnProperties = setOf(
        "spawnUnits", "spawnUnit", "produceUnits",
        "addUnitsIntoTransport", "attachments_addNewUnits"
    )

    private val projectileSpawnProperties = setOf(
        "spawnProjectiles", "spawnProjectile",
        "spawnProjectilesOnCreate", "spawnProjectilesOnEndOfLife", "spawnProjectilesOnExplode"
    )

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty() ?: return false
        val names = setOfNotNull(prop.name, prop.name_en)
        if (names.any { it in unitSpawnProperties || it in projectileSpawnProperties }) return true
        // 中文属性名通过翻译库反向查找
        if (request.isChineseName()) {
            val enName = request.toEnglishName()
            if (enName in unitSpawnProperties || enName in projectileSpawnProperties) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty() ?: return emptyList()
        val isProjectile = prop.name in projectileSpawnProperties ||
            prop.name_en in projectileSpawnProperties ||
            prop.type.lowercase().contains("projectile")
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
                        detail = "${it.type} - 参数",
                        insertText = insert,
                        prefixLength = request.rawValuePrefixLength,
                        valueType = it.type
                    )
                }
        } else {
            val items = mutableListOf<CompletionProvider.CompletionItem>()
            // 单位名位置（括号外）：先列项目单位名候选（按已输入前缀过滤），再保留示例格式。
            // 抛射体生成属性（spawnUnit/spawnProjectiles 等）只给示例格式，不列单位名。
            if (!isProjectile && request.unitNames.isNotEmpty()) {
                val prefix = request.valuePrefix
                request.unitNames
                    .map { it to completionMatchLevel(prefix, it) }
                    .filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
                    .map { it.first }
                    .forEach { name ->
                        items.add(
                            createValueItem(
                                label = name,
                                detail = "单位名",
                                insertText = name,
                                prefixLength = request.rawValuePrefixLength,
                                valueType = "unitref"
                            )
                        )
                    }
            }
            if (data.example.isNotBlank()) {
                val value = data.example.substringAfter(":").trim()
                items.add(
                    createValueItem(
                        label = value,
                        detail = "示例格式",
                        insertText = value,
                        prefixLength = request.rawValuePrefixLength,
                        valueType = "any"
                    )
                )
            }
            items
        }
    }
}
