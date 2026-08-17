package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class TranslationEngineTest {

    private fun loadDictFromAssets() {
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
    }

    @Test
    fun multilineChinesePreserved() {
        loadDictFromAssets()
        val input = "[action_测试]\n" +
                "description:\"\"\"该选项用于查看全局信息\\n\\n游玩时间：%{createMarker(x=0,y=0,teamId=1).resource.游戏时间}s\n" +
                "刷怪数量倍数：%{numberOfUnitsInAllTeams(withTag=\"失败判定\")*createMarker(x=0,y=0,teamId=1).resource.AI难度*0.06}*%{1+createMarker(x=0,y=0,teamId=1).resource.额外刷怪数量}\n" +
                "\"\"\""
        val output = TranslationEngine.getInstance().translateToChinese(input)
        println("===== CN OUTPUT =====")
        println(output)
        println("===== END =====")
        assertFalse(output.contains("\"\"\"\"\"\""), "should not contain six quotes in a row")
        assertTrue(output.contains("描述:\"\"\"该选项用于查看全局信息"), "first line should preserve 该选项")
    }

    @Test
    fun multilineEnglishToChinese() {
        loadDictFromAssets()
        val input = "[action_测试]\n" +
                "description:\"\"\"This option is used to view global information\\n\\nGame time: %{createMarker(x=0,y=0,teamId=1).resource.游戏时间}s\n" +
                "\"\"\""
        val output = TranslationEngine.getInstance().translateToChinese(input)
        println("===== EN->CN OUTPUT =====")
        println(output)
        println("===== END =====")
        assertFalse(output.contains("\"\"\"\"\"\""), "should not contain six quotes in a row")
    }

    @Test
    fun multilineValueTranslatesEnToZh() {
        loadDictFromAssets()
        val input = "[action_测试]\n" +
                "setUnitMemory:\"\"\"attack\n" +
                "eventSource.nearestUnit(withinRange=810)\n" +
                "\"\"\""
        val output = TranslationEngine.getInstance().translateToChinese(input)
        println("===== MULTILINE EN->ZH OUTPUT =====")
        println(output)
        println("===== END =====")
        assertFalse(output.contains("\"\"\"\"\"\""), "should not contain six quotes in a row")
        assertTrue(output.contains("设置单位内存:\"\"\"攻击"), "key and first line should be translated to Chinese")
        assertTrue(output.contains("接近单位"), "nearestUnit inside triple quotes should be translated")
        assertTrue(output.contains("事件来源"), "eventSource inside triple quotes should be translated")
    }

    @Test
    fun multilineValueTranslatesZhToEn() {
        loadDictFromAssets()
        val input = "[action_测试]\n" +
                "设置单位内存:\"\"\"攻击\n" +
                "eventSource.接近单位(withinRange=810)\n" +
                "\"\"\""
        val output = TranslationEngine.getInstance().translateToEnglish(input)
        println("===== MULTILINE ZH->EN OUTPUT =====")
        println(output)
        println("===== END =====")
        assertFalse(output.contains("\"\"\"\"\"\""), "should not contain six quotes in a row")
        assertTrue(output.contains("setUnitMemory:\"\"\"attack"), "key and first line should be translated to English")
        assertTrue(output.contains("eventSource.nearestUnit(withinRange=810)"), "接近单位 inside triple quotes should be translated")
    }

    @Test
    fun defineKeywordStaysEnglishEnToZh() {
        loadDictFromAssets()
        val input = "@define baseTags:modularSpider_nonEmptySlot"
        val output = TranslationEngine.getInstance().translateToChinese(input)
        println("===== DEFINE EN->ZH OUTPUT =====")
        println(output)
        println("===== END =====")
        assertTrue(output.startsWith("@define "), "@define should stay English in Chinese output")
        assertTrue(output.contains("baseTags:modularSpider_nonEmptySlot"), "define name and value should be preserved")
    }

    @Test
    fun globalKeywordStaysEnglishEnToZh() {
        loadDictFromAssets()
        val input = "@global baseTags:modularSpider_nonEmptySlot"
        val output = TranslationEngine.getInstance().translateToChinese(input)
        println("===== GLOBAL EN->ZH OUTPUT =====")
        println(output)
        println("===== END =====")
        assertTrue(output.startsWith("@global "), "@global should stay English in Chinese output")
        assertTrue(output.contains("baseTags:modularSpider_nonEmptySlot"), "global name and value should be preserved")
    }

    @Test
    fun hashValueNoSpaceRegardlessOfAutoSpace() {
        loadDictFromAssets()
        val input = "name:#FFFFFF"
        val offOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = false)
        val onOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = true)
        println("===== HASH AUTO_SPACE_OFF OUTPUT =====")
        println(offOutput)
        println("===== HASH AUTO_SPACE_ON OUTPUT =====")
        println(onOutput)
        println("===== END =====")
        assertTrue(offOutput.contains(":#FFFFFF"), "when autoSpace is off, # value should not have a leading space")
        assertFalse(offOutput.contains(": #FFFFFF"), "when autoSpace is off, there should be no space before #")
        assertTrue(onOutput.contains(":#FFFFFF"), "when autoSpace is on, # value should still not have a leading space")
        assertFalse(onOutput.contains(": #FFFFFF"), "when autoSpace is on, there should still be no space before #")
    }

    @Test
    fun inlineCommentNoSpaceRegardlessOfAutoSpace() {
        loadDictFromAssets()
        val input = "name:red # note"
        val offOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = false)
        val onOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = true)
        println("===== INLINE COMMENT AUTO_SPACE_OFF OUTPUT =====")
        println(offOutput)
        println("===== INLINE COMMENT AUTO_SPACE_ON OUTPUT =====")
        println(onOutput)
        println("===== END =====")
        assertTrue(offOutput.contains("red# note"), "when autoSpace is off, inline comment should have no leading space")
        assertTrue(onOutput.contains("red# note"), "when autoSpace is on, inline comment should still have no leading space")
        assertFalse(onOutput.contains("red # note"), "when autoSpace is on, inline comment should not keep a leading space")
    }

    @Test
    fun commaDelimiterStillManagedByAutoSpace() {
        loadDictFromAssets()
        val input = "customKey:aaa,bbb,ccc"
        val offOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = false)
        val onOutput = TranslationEngine.getInstance().translateToChinese(input, autoSpace = true)
        println("===== COMMA AUTO_SPACE_OFF OUTPUT =====")
        println(offOutput)
        println("===== COMMA AUTO_SPACE_ON OUTPUT =====")
        println(onOutput)
        println("===== END =====")
        assertTrue(offOutput.contains("aaa,bbb,ccc"), "when autoSpace is off, commas should have no following space")
        assertTrue(onOutput.contains("aaa, bbb, ccc"), "when autoSpace is on, commas should have following spaces")
    }

    @Test
    fun singleCharFrameTranslatesBackAndForth() {
        loadDictFromAssets()
        val enInput = "body_0s:{frame:2}"
        val zhOutput = TranslationEngine.getInstance().translateToChinese(enInput)
        println("===== FRAME EN->ZH OUTPUT =====")
        println(zhOutput)
        println("===== END =====")
        assertTrue(zhOutput.contains("{帧:2}"), "frame should translate to 帧 inside braces")

        val zhInput = "body_0s:{帧:2}"
        val enOutput = TranslationEngine.getInstance().translateToEnglish(zhInput)
        println("===== FRAME ZH->EN OUTPUT =====")
        println(enOutput)
        println("===== END =====")
        assertTrue(enOutput.contains("{frame:2}"), "单字 帧 should translate back to frame inside braces")
    }
}
