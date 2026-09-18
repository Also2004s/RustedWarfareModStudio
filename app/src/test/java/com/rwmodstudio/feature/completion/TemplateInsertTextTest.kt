package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 模板键插入文本。
 *
 * 回归钉死（2026-09-17 老板实测）：中文键视图下点「修正除此标签」这类中文候选，
 * 插入的必须是**英文实例化键**——中文名里没有序号位（「修正除此标签」里没有 X 的位置），
 * 原样插中文名等于把带占位符的原文写进文件，保存反译后仍然是 `mutatorX_ifUnitWithoutTags`。
 */
class TemplateInsertTextTest {

    @Test
    fun englishKeyReplacesInPlace() {
        assertEquals(
            "builtFrom_1_name:",
            templateInsertText("builtFrom_{NUM}_name:", "builtFrom_{NUM}_name", "builtFrom_1_name")
        )
        assertEquals(
            "mutator1_ifUnitWithTags:",
            templateInsertText("mutatorX_ifUnitWithTags:", "mutatorX_ifUnitWithTags", "mutator1_ifUnitWithTags")
        )
    }

    @Test
    fun blankSlotFormKeepsBothUnderscores() {
        assertEquals(
            "builtFrom__name:",
            templateInsertText("builtFrom_{NUM}_name:", "builtFrom_{NUM}_name", "builtFrom__name")
        )
    }

    @Test
    fun chineseKeyFallsBackToEnglishInstantiatedKey() {
        // 中文表的插入文本是「修正除此标签:」——不含模板原文，必须退回英文实例化键
        assertEquals(
            "mutator_ifUnitWithoutTags:",
            templateInsertText("修正除此标签:", "mutatorX_ifUnitWithoutTags", "mutator_ifUnitWithoutTags")
        )
        assertEquals(
            "mutator1_ifUnitWithoutTags:",
            templateInsertText("修正除此标签:", "mutatorX_ifUnitWithoutTags", "mutator1_ifUnitWithoutTags")
        )
        // 反之，绝不把中文名原样插出去
        assertEquals(
            "builtFrom__name:",
            templateInsertText("从此建造名称:", "builtFrom_{NUM}_name", "builtFrom__name")
        )
    }
}
