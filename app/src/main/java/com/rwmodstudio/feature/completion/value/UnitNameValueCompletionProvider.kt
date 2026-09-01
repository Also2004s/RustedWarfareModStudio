package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 把核心节建造速度原始值格式化为可读时间。
 * 纯数字按「秒 = 1 ÷ (建造速度 × 60)」换算为秒并加 S 后缀，秒数保留 1 位小数（截断而非四舍五入，
 * 与游戏显示口径一致：0.0011 → 15.1515…S 截断为 15.1S）；
 * 已是带单位/其他格式（如 3s）或非正数时原样返回，不做换算。
 */
internal fun formatBuildSpeed(raw: String): String {
    val v = raw.trim().toDoubleOrNull() ?: return raw
    if (v <= 0.0) return raw
    val seconds = 1.0 / (v * 60.0)
    val truncated = Math.floor(seconds * 10.0) / 10.0
    return String.format("%.1fS", truncated)
}

/**
 * 由单位 [core] 建造信息生成右侧补全详情。
 * 精简格式：建造速度换算秒数 + 价格，用「｜」拼接（如 15.1S｜500）。
 * 价格为 0 或无任何可用信息时回退 [fallback]（如「单位名」）。
 */
internal fun unitBuildDetail(info: ProjectTagScanner.UnitBuildInfo?, fallback: String): String {
    if (info == null) return fallback
    // 价格为 0 视为无有效建造信息（免费/装饰单位），只显示单位名
    if (info.price.trim().toDoubleOrNull() == 0.0) return fallback
    val parts = mutableListOf<String>()
    if (info.buildSpeed.isNotBlank()) parts.add(formatBuildSpeed(info.buildSpeed))
    if (info.price.isNotBlank()) parts.add(info.price)
    return if (parts.isEmpty()) fallback else parts.joinToString("｜")
}

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

        // 单位名固定精确名单（经 code_reference 逐条核实，与分类页共源 CompletionShared）：
        // convertTo/onCreateSpawnUnitOf 等虽 type 为 unit ref（单位标记）但用法是单位类型名 → 精确注入，
        // 不做属性名 contains(unit/单位) 模糊匹配。
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
                    // 项目单位详情：核心节建造信息（建造速度换算为秒 + 价格）；
                    // 无数据时回退「单位名」
                    detail = unitBuildDetail(request.unitData[name], "单位名"),
                    insertText = name,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "unitref"
                )
            }
    }
}
