package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 单位名值补全。
 * type 含 unitref / unit ref / unitRef / unitType / unitTypes 时触发。
 * 排除已由 UnitSpawnCompletionProvider 处理的 spawn 类属性。
 * 中文属性名通过翻译库反向查找判别。
 */
class UnitNameValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 光标位于值内未闭合的 '(' 之后（如 name:(）时，不触发单位名补全
        if (request.isInsideParentheses()) return false
        // 已由 UnitSpawnCompletionProvider 处理的生成类属性（单位/抛射体）排除，避免冲突
        if (CompletionShared.isUnitSpawnProperty(request.propertyName)) return false
        if (request.isChineseName() && CompletionShared.isUnitSpawnProperty(request.toEnglishName())) return false
        // 单位引用表达式属性（设置自定义目标1/2、添加路径点来自参考等）不补单位名，交 UnitRefValueCompletionProvider
        // （中文属性名经翻译库反查为英文 name_en 后判定）
        if (CompletionShared.isUnitRefExpressionProperty(request.toEnglishName())) return false

        // name: 键（如 [核心] 的单位名、[可建造] 的可建造单位列表）→ 单位名补全。
        // 用翻译库反向查询判定（英文原样命中，中文如「名称」走字典反查），不硬编码中文。
        if (request.toEnglishName().equals("name", ignoreCase = true)) return true

        // 单位名列表属性（如 死亡产生单位/覆盖单位/升级自/builtFrom_1_name）→ 单位名补全。
        // 判定来源为 [CompletionShared]（与分类页共用），不按类型猜测。
        if (CompletionShared.isUnitNameListProperty(request.toEnglishName())) return true

        val prop = request.findProperty()
        if (prop != null) {
            val typeLower = prop.type.lowercase().trim()
            if (CompletionShared.isUnitNameTypeMatch(typeLower)) return true
            // 表内属性 type 明确非 unit 关键字时不再参与（避免与 bool/tags 等类型冲突）
            return false
        }
        // 兜底：英文含 unit，或中文反向查找
        val nameLower = request.propertyName.lowercase()
        if (nameLower.contains("unit")) return true
        if (request.isChineseName()) {
            val enName = request.toEnglishName().lowercase()
            if (enName.contains("unit")) return true
            if (request.propertyName.contains("单位")) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val source = request.unitNames
        if (source.isEmpty()) return emptyList()

        val prefix = request.valuePrefix
        return source
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .map { name ->
                createValueItem(
                    label = name,
                    detail = "单位名",
                    insertText = name,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "unitref"
                )
            }
    }
}
