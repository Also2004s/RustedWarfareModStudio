package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * collectSectionKeysFromMergedLines：从继承链合并行中提取指定节的已有键。
 * 覆盖节名归一化、注释/指令行跳过、多行值块内容跳过、非法行剔除。
 */
class SectionKeysFromMergedTest {

    @Test
    fun collectsKeysOfMatchedSectionOnly() {
        val lines = listOf(
            "[核心]",
            "name: tank",
            "price: 500",
            "# 注释",
            "// 注释2",
            "; 注释3",
            "@memory hp:int",
            "带 空格: 1",
            "[图像]",
            "image: tank.png"
        )
        val keys = collectSectionKeysFromMergedLines(lines, "核心") { it.trim().lowercase() }
        assertEquals(setOf("name", "price"), keys)
    }

    @Test
    fun normalizesSectionNamesViaNormalizer() {
        // 继承链内容里节名可能是英文（core），目标节是中文（核心）——由 normalizer 归一
        val lines = listOf("[core]", "name: tank", "maxHp: 100", "[graphics]", "image: a.png")
        val keys = collectSectionKeysFromMergedLines(lines, "核心") {
            when (it.trim().lowercase()) {
                "core" -> "核心"
                "graphics" -> "图像"
                else -> it.trim()
            }
        }
        assertEquals(setOf("name", "maxhp"), keys)
    }

    @Test
    fun skipsMultilineValueBlockContent() {
        val lines = listOf(
            "[核心]",
            "name: tank",
            "复制与:\"\"\"",
            "other.ini",
            "image: should_not_count",
            "\"\"\"",
            "radius: 20"
        )
        val keys = collectSectionKeysFromMergedLines(lines, "核心") { it.trim() }
        // 块内容 image 不计入；复制与 是开启块的那一行，按现有 existingKeysInSection 同口径计为键
        assertEquals(setOf("name", "复制与", "radius"), keys)
    }

    @Test
    fun ignoresLinesBeforeAnySectionHeader() {
        val lines = listOf("stray: 1", "[核心]", "name: tank")
        val keys = collectSectionKeysFromMergedLines(lines, "核心") { it.trim() }
        assertEquals(setOf("name"), keys)
    }
}