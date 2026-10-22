package com.rwmodstudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 补全排序自定义：默认表结构与排序查询的守卫测试。
 *
 * - 默认表条数必须等于约定值（隐藏行动 40 / 行动 26 / 核心 29 …），防止误增删；
 * - 未参与排序的节（炮塔/抛射体/效果/动画/腿）必须为空表；
 * - 查询按 name/nameEn/label 任一命中即算，且按节隔离。
 */
class SortCustomizationDefaultsTest {

    private val expectedCounts = mapOf(
        "隐藏行动" to 40, "行动" to 26, "核心" to 29, "运动" to 2,
        "攻击" to 6, "图像" to 9, "可建造" to 4, "资源" to 3,
        "全局资源" to 6, "贴花" to 8, "附属" to 13, "放置规则" to 5, "AI" to 10
    )

    @Test
    fun defaultCountsMatchAgreedSpec() {
        expectedCounts.forEach { (section, n) ->
            assertEquals(n, SortCustomizationDefaults.defaultFor(section).size, "节 $section 默认条数应为 $n")
        }
    }

    @Test
    fun untouchedSectionsHaveNoTable() {
        listOf("炮塔", "抛射体", "效果", "动画", "腿").forEach { section ->
            assertTrue(
                SortCustomizationDefaults.defaultFor(section).isEmpty(),
                "$section 应为空表（本次不参与排序）"
            )
        }
    }

    @Test
    fun entriesAreCompleteAndUnique() {
        SortCustomizationDefaults.DEFAULT.forEach { (section, list) ->
            list.forEach { e ->
                assertTrue(e.zh.isNotBlank(), "$section 存在空中文名")
                assertTrue(e.en.isNotBlank(), "$section 的「${e.zh}」缺英文键")
            }
            assertEquals(list.size, list.map { it.zh.lowercase() }.toSet().size, "$section 中文名重复")
            assertEquals(list.size, list.map { it.en.lowercase() }.toSet().size, "$section 英文键重复")
        }
    }

    @Test
    fun sectionNamesCoverAllDefaultKeys() {
        val names = SortCustomizationDefaults.SECTION_NAMES.toSet()
        assertTrue(
            names.containsAll(SortCustomizationDefaults.DEFAULT.keys),
            "SECTION_NAMES 未覆盖默认表全部节"
        )
    }

    // ===== 排序查询（纯函数，不读盘）=====

    @Test
    fun rankHitsByChineseOrEnglish() {
        val table = mapOf(
            "行动" to listOf(
                SortEntry("需要条件", "requireConditional"),
                SortEntry("自动触发", "autoTrigger")
            )
        )
        assertEquals(0, SortCustomizationManager.rankOf(table, "行动", "需要条件"))
        assertEquals(0, SortCustomizationManager.rankOf(table, "行动", "", "requireConditional"))
        assertEquals(1, SortCustomizationManager.rankOf(table, "行动", "自动触发", "autoTrigger"))
        assertEquals(Int.MAX_VALUE, SortCustomizationManager.rankOf(table, "行动", "无关项"))
    }

    @Test
    fun rankIsSectionScoped() {
        val table = mapOf("行动" to listOf(SortEntry("需要条件", "requireConditional")))
        assertEquals(Int.MAX_VALUE, SortCustomizationManager.rankOf(table, "核心", "需要条件"))
        assertEquals(Int.MAX_VALUE, SortCustomizationManager.rankOf(table, null, "需要条件"))
        assertEquals(Int.MAX_VALUE, SortCustomizationManager.rankOf(table, "炮塔", "需要条件"))
    }

    @Test
    fun defaultTablePutsTopKeysFirst() {
        val table = SortCustomizationDefaults.DEFAULT
        assertEquals(0, SortCustomizationManager.rankOf(table, "隐藏行动", "需要条件", "requireConditional"))
        assertEquals(1, SortCustomizationManager.rankOf(table, "隐藏行动", "自动触发", "autoTrigger"))
        assertEquals(0, SortCustomizationManager.rankOf(table, "行动", "text"))
    }
}
