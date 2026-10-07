package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
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
        assertTrue(CompletionShared.isCopyFromSectionProperty("复制节", "@copyFromSection"), "中文键 + name_en 命中")
        assertTrue(CompletionShared.isCopyFromSectionProperty("@copyFromSection", null), "英文键直接命中")
        assertTrue(CompletionShared.isCopyFromSectionProperty("@copyfromsection", null), "大小写不敏感")
        assertTrue(CompletionShared.isCopyFromSectionProperty("复制节", null), "name_en 缺失时按中文键命中")
    }

    @Test
    fun copyFromSectionPropertyRejected() {
        assertFalse(CompletionShared.isCopyFromSectionProperty("复制与", null), "复制与 不是 复制节")
        assertFalse(CompletionShared.isCopyFromSectionProperty("@copyFrom", "@copyFrom"), "前缀相似的键不命中")
        assertFalse(CompletionShared.isCopyFromSectionProperty("", null))
    }

    // ===== isCopyFromSkipSectionProperty =====

    @Test
    fun copyFromSkipSectionPropertyDetected() {
        assertTrue(CompletionShared.isCopyFromSkipSectionProperty("复制但跳过节", "@copyFrom_skipThisSection"))
        assertTrue(CompletionShared.isCopyFromSkipSectionProperty("@copyFrom_skipThisSection", null))
        assertTrue(CompletionShared.isCopyFromSkipSectionProperty("@copyfrom_skipthissection", null), "大小写不敏感")
        assertTrue(CompletionShared.isCopyFromSkipSectionProperty("复制但跳过节", null))
    }

    @Test
    fun copyFromSkipSectionPropertyRejected() {
        assertFalse(CompletionShared.isCopyFromSkipSectionProperty("复制节", null))
        assertFalse(CompletionShared.isCopyFromSkipSectionProperty("跳过条件", null), "跳过条件 不是 复制但跳过节")
        assertFalse(CompletionShared.isCopyFromSkipSectionProperty("", null))
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

    // 数据源为继承链节名（chainSectionNames），与 内存/资源 一致只查继承链；
    // 节基名前缀一律英文（INI 语法固定词，不硬编码中文），withBasePrefix 决定是否带基名
    private fun build(withBasePrefix: Boolean, currentBase: String? = null) = buildCopyFromSectionNames(
        withBasePrefix = withBasePrefix,
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
        val names = build(withBasePrefix = true, currentBase = "hiddenaction")
        assertEquals(listOf("hiddenAction_开火"), names, "当前为隐藏行动时只给 hiddenAction_yyy")
    }

    @Test
    fun basePrefixUsesSectionToZhWhenProvided() {
        // 中文视图：基名前缀按引擎节名表翻为中文（保存时引擎反译回英文）
        val names = buildCopyFromSectionNames(
            withBasePrefix = true,
            currentBase = "hiddenaction",
            chainSectionNames = mapOf("hiddenaction" to setOf("开火")),
            sectionToZh = { en -> if (en == "hiddenAction") "隐藏行动" else en }
        )
        assertEquals(listOf("隐藏行动_开火"), names, "传入节名映射时基名前缀翻为中文")
        // 复制与（无基名前缀）不受影响
        val copyFrom = buildCopyFromSectionNames(
            withBasePrefix = false,
            currentBase = "hiddenaction",
            chainSectionNames = mapOf("hiddenaction" to setOf("开火")),
            sectionToZh = { "隐藏行动" }
        )
        assertEquals(listOf("开火"), copyFrom, "复制与只给后缀名，基名映射不参与")
    }

    @Test
    fun currentBaseActionOnlySuggestsAction() {
        val names = build(withBasePrefix = true, currentBase = "action")
        assertEquals(listOf("action_巡逻"), names, "当前为行动时只给 action_yyy，不含 hiddenAction_开火")
    }

    @Test
    fun currentBaseTurretOnlySuggestsTurret() {
        assertEquals(listOf("turret_主炮"), build(withBasePrefix = true, currentBase = "turret"))
    }

    @Test
    fun nullBaseFallsBackToAllNamedSections() {
        val names = build(withBasePrefix = true, currentBase = null)
        assertEquals(
            listOf("action_巡逻", "attachment_炮塔座", "effect_爆炸", "hiddenAction_开火", "projectile_1", "projectile_2", "turret_主炮"),
            names,
            "非命名节回退全部命名节，基名英文，排序去重"
        )
    }

    @Test
    fun noBasePrefixWhenCopyFrom() {
        // 复制与(copyFrom)只补同类节的节后缀名，不带基名前缀
        assertEquals(listOf("主炮"), build(withBasePrefix = false, currentBase = "turret"), "复制与在当前炮塔节只给后缀名")
        val names = build(withBasePrefix = false, currentBase = null)
        assertEquals(
            listOf("1", "2", "主炮", "巡逻", "开火", "炮塔座", "爆炸"),
            names,
            "复制与回退全部命名节时只给后缀名，排序去重"
        )
    }

    @Test
    fun chainOnlySourcingExcludesMissingBases() {
        // 继承链里没有 动画/贴花/可建造 节 → 不出现对应基名
        val names = build(withBasePrefix = true, currentBase = null)
        assertTrue(names.none { it.startsWith("animation_") }, "链内无动画节不应提示")
        assertTrue(names.none { it.startsWith("decal_") }, "链内无贴花节不应提示")
        assertTrue(names.none { it.startsWith("canBuild_") }, "链内无可建造节不应提示")
        assertTrue("attachment_炮塔座" in names, "链内附属节名应提示")
    }
}
