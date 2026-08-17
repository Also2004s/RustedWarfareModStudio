package com.rwmodstudio.ui.screens

import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.core.translation.TranslationEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 原生表英→中查找表优先级：属性/值翻译优先于节翻译。
 * 回归修复：attachment 值被 [attachment] 节翻译覆盖，导致 附件( 被错插为 附属(。
 */
class EnToZhLookupPriorityTest {

    private fun loadDictFromAssets(): TranslationDict {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/translation.txt"),
            File(System.getProperty("user.dir"), "src/main/assets/data/translation.txt"),
            File("app/src/main/assets/data/translation.txt"),
            File("src/main/assets/data/translation.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("translation.txt not found. user.dir=${System.getProperty("user.dir")}")
        val engine = TranslationEngine.getInstance()
        val dict = engine.getTranslationDict()
        val loadMethod = dict::class.java.getDeclaredMethod("loadFromFile", File::class.java)
        loadMethod.isAccessible = true
        loadMethod.invoke(dict, file)
        return dict
    }

    @Test
    fun valueTranslationWinsOverSection() {
        val dict = loadDictFromAssets()
        val lookup = buildEnToZhLookup(dict)
        assertEquals("附件", lookup["attachment"], "值翻译 attachment→附件 应优先于节翻译 [attachment]→附属")
    }

    @Test
    fun sectionStillFillsMissingKeys() {
        val dict = loadDictFromAssets()
        val lookup = buildEnToZhLookup(dict)
        // 无属性/值翻译的节名仍由节翻译兜底
        assertEquals("核心", lookup["core"])
        assertEquals("放置规则", lookup["placementRule"])
    }

    @Test
    fun translateAllToChineseKeepsAttachmentValue() {
        val dict = loadDictFromAssets()
        assertEquals("附件(", translateAllToChinese("attachment(", dict), "原生表 value 应为 附件( 而非 附属(")
        val example = translateAllToChinese("setCustomTarget2: self.attachment(withTag='x').lastDamagedBy.getAsMarker()", dict)
        assertTrue(example.contains("self.附件("), "示例中的 attachment 也应译为 附件，实际: $example")
    }

    @Test
    fun translateAllToEnglishStillWorks() {
        val dict = loadDictFromAssets()
        assertEquals("attachment(", translateAllToEnglish("附件(", dict))
    }
}
