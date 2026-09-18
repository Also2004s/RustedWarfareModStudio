package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「值写在整值位置」与「值写在括号参数里」的翻译行为差异（2026-09-17 核实）。
 *
 * 结论：
 * 1. 整值位置（`运动类型: LAND`）会翻：引擎走 translateSingleValue 的 specialValues 分支 → 陆地，
 *    且反译可还原（陆地 → LAND）。所以这里补全用中文是安全的。
 * 2. 括号内属于句中：引擎只用 enToZh/zhToEn 正则（不含 valueTranslations）→ `type=LAND` 保持英文；
 *    反过来 `type=陆地` 也反译不回来 → 括号内补全必须保持英文，否则会往文件里留中文值。
 *
 * 该测试就是「值补全按场景决定语言」这条规则的事实依据，改动相关逻辑前先看这里。
 */
class TranslationInPhraseValueTest {

    private fun loadDictFromAssets() {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/translation.txt"),
            File(System.getProperty("user.dir"), "src/main/assets/data/translation.txt"),
            File("app/src/main/assets/data/translation.txt"),
            File("src/main/assets/data/translation.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("translation.txt not found. user.dir=${System.getProperty("user.dir")}")
        val dict = TranslationEngine.getInstance().getTranslationDict()
        val loadMethod = dict::class.java.getDeclaredMethod("loadFromFile", File::class.java)
        loadMethod.isAccessible = true
        loadMethod.invoke(dict, file)
    }

    /** 整值位置：LAND → 陆地，且能反译回来（补全在这里插中文安全） */
    @Test
    fun wholeValueSpecialValueTranslatesAndReverts() {
        loadDictFromAssets()
        val engine = TranslationEngine.getInstance()
        val zh = engine.translateToChinese("movementType:LAND", autoSpace = false)
        assertTrue(zh.contains("陆地"), "整值位置的 LAND 应翻译为 陆地，实际: $zh")
        assertEquals("movementType:LAND", engine.translateToEnglish(zh, autoSpace = false))
    }

    /** 括号内：LAND 不会被翻成中文（补全在这里该给英文） */
    @Test
    fun specialValueInsideParensStaysEnglish() {
        loadDictFromAssets()
        val engine = TranslationEngine.getInstance()
        val zh = engine.translateToChinese("requireConditional:if self.isOverPassableTile(type=LAND)", autoSpace = false)
        assertTrue(zh.contains("type=LAND"), "括号内的 LAND 应保持英文，实际: $zh")
        assertFalse(zh.contains("陆地"), "括号内的 LAND 不该被翻译成中文，实际: $zh")
    }

    /** 括号内的中文值反译不回来（所以补全在括号内插中文是往文件里留垃圾） */
    @Test
    fun chineseSpecialValueInsideParensIsNotReverted() {
        loadDictFromAssets()
        val engine = TranslationEngine.getInstance()
        val en = engine.translateToEnglish("requireConditional:if 自身在地块类型上(type=陆地)", autoSpace = false)
        assertTrue(
            en.contains("陆地"),
            "括号内的中文地块类型反译不回来（保持中文），补全因此在括号内必须用英文，实际: $en"
        )
    }

    /** 正例对照：普通词条在括号内双向可译（move ↔ 移动），补全用中文安全 */
    @Test
    fun ordinaryWordInsideParensTranslatesBothWays() {
        loadDictFromAssets()
        val engine = TranslationEngine.getInstance()
        val zh = engine.translateToChinese("requireConditional:if self.isOverPassableTile(type=move)", autoSpace = false)
        assertTrue(zh.contains("移动"), "普通词条 move 在括号内应译为 移动，实际: $zh")
        assertTrue(
            engine.translateToEnglish(zh, autoSpace = false).contains("move"),
            "括号内的 移动 应能反译回 move"
        )
    }
}
