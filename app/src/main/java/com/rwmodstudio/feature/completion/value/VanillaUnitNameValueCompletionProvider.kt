package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 原版单位名值补全。
 * 与 [UnitNameValueCompletionProvider] 触发条件一致（type 含 unitref/unit ref/unitType/unitTypes、
 * name: 键、单位名列表属性），但候选来自内置 assets/vanilla_units.json（rw_analysis 逆向的全部原版单位）。
 * [UnitNameValueCompletionProvider] 负责项目内单位，本 Provider 负责原版单位，二者 detail 不同、同 label 由外部去重。
 * 若同名字面存在，项目单位_name 优先（用户 mod 覆盖），原版单位兜底去重。
 */
class VanillaUnitNameValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        // 生成类属性（单位/抛射体）由 UnitSpawnCompletionProvider 处理，排除避免冲突
        if (CompletionShared.isUnitSpawnProperty(request.propertyName)) return false
        if (request.isChineseName() && CompletionShared.isUnitSpawnProperty(request.toEnglishName())) return false
        // 单位引用表达式属性交 UnitRefValueCompletionProvider
        if (CompletionShared.isUnitRefExpressionProperty(request.toEnglishName())) return false

        if (request.toEnglishName().equals("name", ignoreCase = true)) return true
        if (CompletionShared.isUnitNameListProperty(request.toEnglishName())) return true
        // 单位名固定精确名单（与 UnitNameValueCompletionProvider 共源，见 CompletionShared）
        if (CompletionShared.isUnitNameProperty(request.toEnglishName())) return true
        // type 初筛兜底（type 不可信，仅作第一道过滤；错误数据已由上述精确名单排除）
        val prop = request.findProperty()
        if (prop != null) {
            val typeLower = prop.type.lowercase().trim()
            if (CompletionShared.isUnitNameTypeMatch(typeLower)) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 打了空格 / Tab：空格不是合法分隔符（与 UnitNameValueCompletionProvider 一致）——
        // 列表属性 → 只提示「,」；单值单位名（核心 name、convertTo 等）→ 值已写完，不再补单位名
        if (needsCommaSeparator(request)) {
            return if (CompletionShared.isUnitNameListProperty(request.toEnglishName())) {
                listOf(commaSeparatorItem("单位名分隔符（值与值之间用逗号分隔）"))
            } else {
                emptyList()
            }
        }
        val prefix = request.valuePrefix
        // 原版单位 + 项目单位合并候选；项目单位优先（用户 mod 覆盖原版同名单位），按 label 去重保留首个。
        // 排序同 UnitName：前缀命中优先，组内字母序。
        val ordered = (request.unitNames + request.vanillaUnitNames)
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .distinct()
        if (ordered.isEmpty()) return emptyList()
        return ordered.map { name ->
            createValueItem(
                label = name,
                // 原版单位名查中文说明（value_desc 的 data.value.vanillaunit.*，如 heavyTank→重型坦克）；
                // 项目单位名由用户自定义，无法预置说明，回退「单位名」。
                detail = if (name in request.vanillaUnitNames && name !in request.unitNames) {
                    ValueDescriptionRepository.lookup(request.context, "data.value.vanillaunit.$name") ?: "原版单位"
                } else {
                    "单位名"
                },
                insertText = name,
                prefixLength = request.rawValuePrefixLength,
                valueType = "unitref"
            )
        }
    }
}