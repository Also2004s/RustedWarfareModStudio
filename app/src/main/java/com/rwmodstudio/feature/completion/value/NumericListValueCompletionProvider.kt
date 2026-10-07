package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider

/**
 * type → 定长值个数上限（ints 四方块 / point 两坐标 / point3d 三坐标）；非此类返回 null。
 * 依据 code_reference：建筑碰撞范围/覆盖/选择UI 为「四个数值(左,上,右,下)」；
 * point 为 x,y；point3d 为 x,y,z。按 type 硬编码（部分属性官方示例为空，不能靠示例推）。
 */
internal fun numericListMaxValues(type: String): Int? = when (type.lowercase().replace(" ", "")) {
    "ints" -> 4
    "point" -> 2
    "point3d" -> 3
    else -> null
}

/** 统计值区内顶层（不在括号/花括号内）已填的非空段数，如 `0, 0, 1` → 3 */
internal fun countTopLevelValues(region: String): Int {
    var depth = 0
    var count = 0
    var hasContent = false
    for (c in region) {
        when (c) {
            '(', '{' -> {
                depth++
                hasContent = true
            }
            ')', '}' -> {
                if (depth > 0) depth--
                hasContent = true
            }
            ',', '，' -> {
                if (depth == 0) {
                    if (hasContent) count++
                    hasContent = false
                } else {
                    hasContent = true
                }
            }
            ' ', '\t' -> Unit // 空白不计内容
            else -> hasContent = true
        }
    }
    if (hasContent) count++
    return count
}

/**
 * 数值列表（逗号分隔、定长）值补全：type = ints（建筑碰撞范围 0,0,1,1）/ point（10, 20）/ point3d（0, 0, 40）。
 *
 * 这类属性的值是用户手打的数值，没有候选词源；本 Provider 只做一件事：
 * 值后打空格/Tab 且**尚未填满定长**时给出「,」分隔符（空格不是合法分隔符），
 * 避免写成 `0,0,1,1 0,0,2,2`；已达上限后不再弹。
 * 非空格场景返回空，不产生任何数值候选（杜绝「空前缀全量兜底」）。
 */
class NumericListValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty() ?: return false
        return numericListMaxValues(prop.type) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val max = request.findProperty()?.let { numericListMaxValues(it.type) } ?: return emptyList()
        if (!needsCommaSeparator(request)) return emptyList()
        val region = request.textBeforeCursor.substringAfter(':').trimEnd()
        if (countTopLevelValues(region) >= max) return emptyList() // 已填满 → 不再弹
        return listOf(commaSeparatorItem("数值分隔符（值与值之间用逗号分隔）"))
    }
}