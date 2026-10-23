package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 特殊值的方向性回归（保存时把英文值写坏的那个 bug）。
 *
 * valueTranslations 是双向登记的（true↔真 写在同一处、同一个 map），
 * 「中→英」方向若仍按「查中文对应的英文」去查，就会把本来就是英文目标值的
 * true/LAND 等反查成中文——中文视图里粘贴的英文内容在保存时被写坏（true→真、LAND→陆地）。
 *
 * 本测试钉死两件事：
 * 1. 英文特殊值经过一次「中→英」必须原样保留；
 * 2. 中文值反译、英→中方向、既有括号参数位置都不能倒退。
 */
class TranslationSpecialValueDirectionTest {

    /** 与 TranslationRoundTripTest 相同的加载方式：JVM 侧直接解析 assets 里的原生翻译库 */
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

    private fun toEnglish(text: String): String =
        TranslationEngine.getInstance().translateToEnglish(text, autoSpace = false)

    private fun toChinese(text: String): String =
        TranslationEngine.getInstance().translateToChinese(text, autoSpace = false)

    /**
     * 核心：值本身就是英文目标值时，中→英方向必须保持原样。
     * 这 9 个值就是值表里全部英文条目，一条都不能被反查成中文。
     */
    @Test
    fun englishSpecialValuesStayUntouchedInReverseTranslation() {
        loadDictFromAssets()
        val values = listOf(
            "true", "false", "LAND", "WATER", "HOVER", "AIR",
            "OVER_CLIFF", "OVER_CLIFF_WATER", "NONE"
        )
        for (v in values) {
            assertEquals("k:$v", toEnglish("k:$v"), "英文值 $v 在中→英方向被改写了")
        }
        // 多值同样逐项保留
        assertEquals("k:LAND,WATER,HOVER", toEnglish("k:LAND,WATER,HOVER"))
        assertEquals("k:true,false", toEnglish("k:true,false"))
    }

    /** 中文值反译不能倒退 */
    @Test
    fun chineseValuesStillTranslateToEnglish() {
        loadDictFromAssets()
        assertEquals("k:true", toEnglish("k:真"))
        assertEquals("k:false", toEnglish("k:假"))
        assertEquals("k:WATER", toEnglish("k:水面"))
        assertEquals("k:LAND", toEnglish("k:陆地"))
        assertEquals("k:HOVER", toEnglish("k:两栖"))
        assertEquals("k:NONE", toEnglish("k:无"))
    }

    /** 英→中方向不受影响（切中文视图时照旧翻译） */
    @Test
    fun englishToChineseStillWorks() {
        loadDictFromAssets()
        assertEquals("k:真", toChinese("k:true"))
        assertEquals("k:假", toChinese("k:false"))
        assertEquals("k:陆地", toChinese("k:LAND"))
        assertEquals("k:真", toChinese("k:真"))
    }

    /** 既有的布尔位置（括号参数、逗号多值）不能退化 */
    @Test
    fun existingBoolPositionsStillWork() {
        loadDictFromAssets()
        assertEquals("k:true", toEnglish("k:真"))
        assertEquals("k:true,false", toEnglish("k:真,假"))
        assertEquals("k:func(true)", toEnglish("k:func(真)"))
        assertEquals("k:func(a=true, b=false)", toEnglish("k:func(a=真, b=假)"))
        assertEquals("k:%{gridAlign=true}", toEnglish("k:%{gridAlign=真}"))
    }

    /** 词内出现不能被误翻（前后是汉字/字母） */
    @Test
    fun wordsContainingBoolCharAreNotTranslated() {
        loadDictFromAssets()
        for (text in listOf("k:真值", "k:测试真", "k:真假", "k:真的很强")) {
            assertEquals(text, toEnglish(text), "词内出现被误翻：$text")
        }
        for (text in listOf("k:isTrue", "k:trueFlag", "k:truthy")) {
            assertEquals(text, toChinese(text), "英文标识符被误翻：$text")
        }
    }
}
