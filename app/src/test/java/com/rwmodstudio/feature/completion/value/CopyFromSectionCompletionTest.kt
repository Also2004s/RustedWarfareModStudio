package com.rwmodstudio.feature.completion.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 复制节（@copyFromSection）与复制但跳过节（@copyFrom_skipThisSection）补全纯函数单测。
 */
class CopyFromSectionCompletionTest {

    // ===== isCopyFromSectionProperty =====

    @Test
    fun copyFromSectionPropertyDetected() {
        assertTrue(isCopyFromSectionProperty("复制节", "@copyFromSection"), "中文键 + name_en 命中")
        assertTrue(isCopyFromSectionProperty("@copyFromSection", null), "英文键直接命中")
        assertTrue(isCopyFromSectionProperty("@copyfromsection", null), "大小写不敏感")
        assertTrue(isCopyFromSectionProperty("复制节", null), "name_en 缺失时按中文键命中")
    }

    @Test
    fun copyFromSectionPropertyRejected() {
        assertFalse(isCopyFromSectionProperty("复制与", null), "复制与 不是 复制节")
        assertFalse(isCopyFromSectionProperty("@copyFrom", "@copyFrom"), "前缀相似的键不命中")
        assertFalse(isCopyFromSectionProperty("", null))
    }

    // ===== isCopyFromSkipSectionProperty =====

    @Test
    fun copyFromSkipSectionPropertyDetected() {
        assertTrue(isCopyFromSkipSectionProperty("复制但跳过节", "@copyFrom_skipThisSection"))
        assertTrue(isCopyFromSkipSectionProperty("@copyFrom_skipThisSection", null))
        assertTrue(isCopyFromSkipSectionProperty("@copyfrom_skipthissection", null), "大小写不敏感")
        assertTrue(isCopyFromSkipSectionProperty("复制但跳过节", null))
    }

    @Test
    fun copyFromSkipSectionPropertyRejected() {
        assertFalse(isCopyFromSkipSectionProperty("复制节", null))
        assertFalse(isCopyFromSkipSectionProperty("跳过条件", null), "跳过条件 不是 复制但跳过节")
        assertFalse(isCopyFromSkipSectionProperty("", null))
    }

    // ===== sectionBaseFromCategory =====

    @Test
    fun sectionBaseFromCategoryMapsNamedSections() {
        assertEquals("turret", sectionBaseFromCategory("炮塔"))
        assertEquals("projectile", sectionBaseFromCategory("抛射体"))
        assertEquals("effect", sectionBaseFromCategory("效果"))
        assertEquals("action", sectionBaseFromCategory("行动"))
        assertEquals("hiddenaction", sectionBaseFromCategory("隐藏行动"))
        assertEquals("animation", sectionBaseFromCategory("动画"))
        assertEquals("decal", sectionBaseFromCategory("贴花"))
        assertEquals("attachment", sectionBaseFromCategory("附属"))
        assertEquals("canbuild", sectionBaseFromCategory("可建造"))
    }

    @Test
    fun sectionBaseFromCategoryNonNamedReturnsNull() {
        assertNull(sectionBaseFromCategory("核心"))
        assertNull(sectionBaseFromCategory("图像"))
        assertNull(sectionBaseFromCategory("资源"))
        assertNull(sectionBaseFromCategory(null))
    }

    // ===== buildCopyFromSectionNames =====

    // 数据源为继承链节名（chainSectionNames），与 内存/资源 一致只查继承链
    private fun build(useChinese: Boolean, currentBase: String? = null) = buildCopyFromSectionNames(
        useChinesePrefix = useChinese,
        currentBase = currentBase,
        chainSectionNames = mapOf(
            "turret" to setOf("主炮"),
            "projectile" to setOf("1", "2"),
            "effect" to setOf("爆炸"),
            "action" to setOf("巡逻"),
            "hiddenaction" to setOf("开火"),
            "attachment" to setOf("炮塔座")
        )
    )

    @Test
    fun currentBaseHiddenActionOnlySuggestsHiddenAction() {
        val names = build(useChinese = true, currentBase = "hiddenaction")
        assertEquals(listOf("隐藏行动_开火"), names, "当前为隐藏行动时只给 隐藏行动_yyy")
    }

    @Test
    fun currentBaseActionOnlySuggestsAction() {
        val names = build(useChinese = true, currentBase = "action")
        assertEquals(listOf("行动_巡逻"), names, "当前为行动时只给 行动_yyy，不含 隐藏行动_开火")
    }

    @Test
    fun currentBaseTurretOnlySuggestsTurret() {
        assertEquals(listOf("炮塔_主炮"), build(useChinese = true, currentBase = "turret"))
    }

    @Test
    fun nullBaseFallsBackToAllNamedSections() {
        val names = build(useChinese = true, currentBase = null)
        assertEquals(
            listOf("抛射体_1", "抛射体_2", "效果_爆炸", "炮塔_主炮", "行动_巡逻", "附属_炮塔座", "隐藏行动_开火"),
            names,
            "非命名节回退全部命名节，排序去重"
        )
    }

    @Test
    fun englishPrefixUsedWhenNotChinese() {
        val names = build(useChinese = false, currentBase = null)
        assertEquals(
            listOf("action_巡逻", "attachment_炮塔座", "effect_爆炸", "hiddenAction_开火", "projectile_1", "projectile_2", "turret_主炮"),
            names,
            "英文视图用英文基名"
        )
    }

    @Test
    fun chainOnlySourcingExcludesMissingBases() {
        // 继承链里没有 动画/贴花/可建造 节 → 不出现对应基名
        val names = build(useChinese = true, currentBase = null)
        assertTrue(names.none { it.startsWith("动画_") }, "链内无动画节不应提示")
        assertTrue(names.none { it.startsWith("贴花_") }, "链内无贴花节不应提示")
        assertTrue(names.none { it.startsWith("可建造_") }, "链内无可建造节不应提示")
        assertTrue("附属_炮塔座" in names, "链内附属节名应提示")
    }
}
