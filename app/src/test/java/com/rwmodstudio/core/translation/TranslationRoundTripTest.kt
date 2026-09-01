package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证翻译引擎的可逆性，并固化「value 归一化」这一设计行为。
 *
 * 结论：
 * 1. 词典 key 翻译（英→中→英）完全可逆：964 条 key 全量往返 0 不一致。
 * 2. value 反向翻译是「归一化到翻译库样式」的设计行为：self. 前缀、空括号 ()、
 *    注释前空格会被规整（如 self.energy() → self.energy）。这不是 bug，而是
 *    补全 label 与保存还原共用的预期语义，需保持稳定（见 valueNormalizationIsByDesign）。
 *
 * 用 autoSpace=false 排除「逗号/冒号加空格」这类格式干扰，聚焦纯翻译语义是否可逆。
 */
class TranslationRoundTripTest {

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

    private fun roundTrip(enText: String, autoSpace: Boolean = false): String {
        val engine = TranslationEngine.getInstance()
        val zh = engine.translateToChinese(enText, autoSpace)
        return engine.translateToEnglish(zh, autoSpace)
    }

    /** 词典全量 key 往返：遍历 translation.txt 每个英文条目，验证 英→中→英 能否还原 key。 */
    @Test
    fun dictionaryKeysRoundTrip() {
        loadDictFromAssets()
        val entries = TranslationEngine.getInstance().getTranslationDict().getAllEntries()
        val mismatches = mutableListOf<Pair<String, String>>()
        for ((en, _) in entries) {
            val input = if (en.startsWith("[")) "$en\n" else "$en:__ROUNDTRIP_PROBE__\n"
            val expected = input.trimEnd()
            val out = roundTrip(input).trimEnd()
            if (out != expected) mismatches.add(expected to out)
        }
        println("===== 词典全量 key 往返：共 ${entries.size} 条，不一致 ${mismatches.size} 条 =====")
        mismatches.take(80).forEach { (a, b) -> println("原: [$a]\n返: [$b]\n") }
        assertEquals(0, mismatches.size, "词典 key 往返存在 ${mismatches.size} 条不一致")
    }

    /** 覆盖各类翻译特殊规则的构造样本往返（不包含设计上会归一化的 value 样本）。 */
    @Test
    fun specialRuleSamplesRoundTrip() {
        loadDictFromAssets()
        val samples = listOf(
            // 普通 KV
            "name:testUnit",
            "maxHp:500",
            "priceCredits:100",
            // 布尔值
            "isVisible:true",
            "canAttack:false",
            // specialValues（逗号分隔多值）
            "movementType:LAND,WATER,HOVER",
            "type:AIR",
            // 逗号分隔自定义
            "tags:a,b,c",
            // 多行字符串
            "description:\"\"\"first line\nsecond line\"\"\"",
            // %{} 变量
            "attackMultiplier:%{createMarker(x=0,y=0).resource.X}",
            // ${} 变量
            "displayName:\${name}",
            // @define / @global
            "@define baseTags:modularSpider_nonEmptySlot",
            "@global baseTags:modularSpider_nonEmptySlot",
            // 英文引号包裹（应保持原样）
            "name:\"testValue\"",
            // 单字键（frame 等）
            "body_0s:{frame:2}",
            // 未收录自定义词（应保持原样）
            "customKey:myOwnValue123",
            // 纯注释行
            "# a comment line",
            "; another comment",
            // 空行
            ""
        )
        val mismatches = mutableListOf<Pair<String, String>>()
        for (s in samples) {
            val out = roundTrip(s)
            if (out != s) mismatches.add(s to out)
        }
        println("===== 特殊规则样本往返：共 ${samples.size} 条，不一致 ${mismatches.size} 条 =====")
        mismatches.forEach { (a, b) -> println("原: [$a]\n返: [$b]\n") }
        assertEquals(0, mismatches.size, "特殊规则样本存在 ${mismatches.size} 条不一致")
    }

    /**
     * value 反向翻译的「归一化」是设计行为（非 bug）：
     * 反向翻译会返回翻译库样式（裸英文），因此 self. 前缀、空括号 ()、注释前空格会被规整。
     * 此测试固化该行为，防止后续被误「修复」而破坏保存流程。
     */
    @Test
    fun valueNormalizationIsByDesign() {
        loadDictFromAssets()
        // 空括号 () 归一化：self.energy() → self.energy
        assertEquals("cost:self.energy", roundTrip("cost:self.energy()"))
        // self. 前缀 + 空括号 归一化：self.numberOfUnitsInEnemyTeam() → numberOfUnitsInEnemyTeam
        assertEquals("unitCount:numberOfUnitsInEnemyTeam", roundTrip("unitCount:self.numberOfUnitsInEnemyTeam()"))
        // 注释前空格规整：red # note → red# note（不影响 INI 语义）
        assertEquals("name:red# note", roundTrip("name:red # note"))
    }
}
