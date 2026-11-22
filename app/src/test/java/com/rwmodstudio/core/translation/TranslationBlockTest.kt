package com.rwmodstudio.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 翻译禁用（查重禁用词 / 资源名白名单）单测。
 * 用 [TranslationDict.loadEntriesForTest] 注入词条，不依赖 Android assets。
 */
class TranslationBlockTest {

    private fun newDict(): TranslationDict {
        val dict = TranslationDict()
        dict.loadEntriesForTest(
            listOf(
                "credits" to "资金",
                "energy" to "能量",
                "Builder" to "建造者"
            )
        )
        return dict
    }

    @Test
    fun disabledWordBlocksBothDirections() {
        val dict = newDict()
        // 禁用前：双向可译
        assertEquals("资金", dict.getTranslation("credits"))
        assertEquals("credits", dict.getTranslationBack("资金"))

        dict.setDisabledWords(setOf("资金"))

        // 英译中：结果落在禁用表 → 返回英文原文
        assertEquals("credits", dict.getTranslation("credits"))
        // 中译英：入参是禁用词 → 原样返回
        assertEquals("资金", dict.getTranslationBack("资金"))
        // 其他词不受影响
        assertEquals("energy", dict.getTranslationBack("能量"))
    }

    @Test
    fun disabledWordNotTranslatedInText() {
        val dict = newDict()
        dict.setDisabledWords(setOf("资金"))
        // 英→中：credits 的译文落在禁用表 → 不替换，energy 照常替换
        assertEquals("credits 能量", dict.translateInText("credits energy", isEnToZh = true))
        // 中→英：整值查询同样停用（文本级中译英由引擎按整值查表，不依赖 \b 正则）
        assertEquals("资金", dict.getTranslationBack("资金"))
        assertEquals("资金", dict.getValueTranslationBack("资金"))
    }

    @Test
    fun resourceNameWhitelistDoesNotFilterDictionary() {
        val dict = newDict()
        dict.setResourceNameBlocked(true, setOf("credits"))
        // 白名单现在是“允许翻译名单”：词典层不再按名单过滤，资源名仍可双向翻译；
        // 名单外资源名由引擎侧名字保护决定是否保持原文。
        assertEquals("资金", dict.getTranslation("credits"))
        assertEquals("credits", dict.getTranslationBack("资金"))
        assertEquals("energy", dict.getTranslationBack("能量"))
    }

    @Test
    fun disabledWordBlocksSectionNames() {
        val dict = newDict()
        dict.loadSectionsForTest(listOf("Builder" to "建造者"))
        assertEquals("建造者", dict.getSectionTranslation("Builder"))
        assertEquals("Builder", dict.getSectionTranslationBack("建造者"))

        // 查重命中「建造者」后一键禁用：节名双向停用（查重不再把它算重复）
        dict.setDisabledWords(setOf("建造者"))
        assertEquals("Builder", dict.getSectionTranslation("Builder"))
        assertEquals("建造者", dict.getSectionTranslationBack("建造者"))
    }

    @Test
    fun clearDisabledWordsRestoresTranslation() {
        val dict = newDict()
        dict.setDisabledWords(setOf("资金"))
        assertEquals("credits", dict.getTranslation("credits"))
        dict.setDisabledWords(emptySet())
        assertEquals("资金", dict.getTranslation("credits"))
        assertEquals("credits", dict.getTranslationBack("资金"))
    }
}
