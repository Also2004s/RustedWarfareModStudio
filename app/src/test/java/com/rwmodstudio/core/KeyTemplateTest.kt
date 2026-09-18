package com.rwmodstudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模板键识别 / 匹配 / 渲染 / 反查测试。
 *
 * 覆盖老板点名的 builtFrom / canBuild / action / weaponSlot / arm / leg，以及本轮自查补上的
 * 行动节 `{LANG}` 系列；同时钉死「5 条长得像模板但不是键」的排除规则与「未填占位符留空位 + 光标定位」。
 */
class KeyTemplateTest {

    private val names = listOf(
        "builtFrom_{NUM}_name", "builtFrom_{NUM}_pos", "builtFrom_{NUM}_forceNano",
        "builtFrom_{NUM}_isLocked", "builtFrom_{NUM}_isLockedMessage", "builtFrom_{NUM}_isLockedMessage_es",
        "builtFrom_{NUM}_tooltip", "builtFrom_{NUM}_tooltip_es", "builtFrom_{NUM}_icon",
        "builtFrom_{NUM}_category", "builtFrom_{NUM}_sortOrder",
        "action_#_addEnergy", "action_#_convertTo", "action_#_description", "action_#_pos",
        "action_#_price", "action_#_text", "action_#_whenBuilding_cannotMove",
        "canBuild_#_name", "canBuild_#_pos", "canBuild_#_isLocked",
        "weaponSlot_{NUM}_name", "weaponSlot_{NUM}_pos",
        "weaponSlotsForAI_{NUM}_name", "weaponSlotsForBuild_{NUM}_name",
        "arm#_[time]", "leg#_[time]",
        "text_{LANG}", "description_{LANG}", "showMessageToPlayer_{LANG}",
        // X 形占位符：只有这 2 条有实例证据（mutator1_ifUnitWithTags）
        "mutatorX_ifUnitWithTags", "mutatorX_ifUnitWithoutTags",
        // 下面这些「含 X 但不是占位符」的键必须不被识别（坐标轴 / 连接字母 / 参数名 / 无实例）
        "imageScaleX", "shadowOffsetX", "barrelX", "barrelOffsetX_onOddShots",
        "fireTurretXAtGround", "unloadUpToXUnitsFromSource", "offsetX", "offsetRandomXY",
        "mutatorX_directDamageMultiplier", "mutatorX_areaDamageMultiplier",
        // 下面 5 条必须被挡掉
        "canBuild_#_name/pos/isLocked",
        "defineUnitMemory: unit[] squad, float[] mango",
        "\${X}", "\${section.key}", "%{X}"
    )
    private val index = KeyTemplateIndex(names)

    /** 取指定模板在该前缀下的渲染结果（同一前缀通常命中同族多条，测试按模板名精确取）。 */
    private fun renderOf(prefix: String, source: String): KeyTemplateIndex.Rendered? {
        val hit = index.matchPrefix(prefix).firstOrNull { it.template.source == source } ?: return null
        return index.render(hit.template, hit.fills)
    }

    // ===== 识别 =====

    @Test
    fun acceptsRealTemplateKeys() {
        assertTrue(KeyTemplate.isTemplateKey("builtFrom_{NUM}_name"))
        assertTrue(KeyTemplate.isTemplateKey("canBuild_#_name"))
        assertTrue(KeyTemplate.isTemplateKey("weaponSlotsForAI_{NUM}_name"))
        assertTrue(KeyTemplate.isTemplateKey("arm#_[time]"))
        assertTrue(KeyTemplate.isTemplateKey("text_{LANG}"))
    }

    @Test
    fun rejectsLookalikeNonKeys() {
        // 「请改用 canBuild 部分」的说明性合并条目
        assertFalse(KeyTemplate.isTemplateKey("canBuild_#_name/pos/isLocked"))
        // 函数签名说明（含空格 / 冒号）
        assertFalse(KeyTemplate.isTemplateKey("defineUnitMemory: unit[] squad, float[] mango"))
        // 附件表变量引用语法
        assertFalse(KeyTemplate.isTemplateKey("\${X}"))
        assertFalse(KeyTemplate.isTemplateKey("\${section.key}"))
        assertFalse(KeyTemplate.isTemplateKey("%{X}"))
        // 普通键
        assertFalse(KeyTemplate.isTemplateKey("mass"))
        assertFalse(KeyTemplate.isTemplateKey("质量"))
    }

    // ===== 匹配 + 渲染：不带序号 → 留空位 + 光标停在空位 =====

    @Test
    fun blankSlotWithCaretForBuiltFrom() {
        val r = renderOf("builtFrom_", "builtFrom_{NUM}_name")!!
        assertEquals("builtFrom__name", r.key)
        assertEquals(10, r.caretOffset, "光标应停在 builtFrom_ 之后")

        val r2 = renderOf("builtFrom", "builtFrom_{NUM}_name")!!
        assertEquals("builtFrom__name", r2.key)
        assertEquals(10, r2.caretOffset)
    }

    @Test
    fun blankSlotForActionAndCanBuild() {
        assertEquals("action__price" to 7, renderOf("action_", "action_#_price")!!.let { it.key to it.caretOffset })
        assertEquals("canBuild__name" to 9, renderOf("canBuild_", "canBuild_#_name")!!.let { it.key to it.caretOffset })
        assertEquals("weaponSlot__name" to 11, renderOf("weaponSlot_", "weaponSlot_{NUM}_name")!!.let { it.key to it.caretOffset })
    }

    @Test
    fun blankSlotForLanguagePlaceholder() {
        val r = renderOf("text_", "text_{LANG}")!!
        assertEquals("text_", r.key, "占位符在末尾，删掉后原样保留前缀下划线")
        assertEquals(5, r.caretOffset)
    }

    @Test
    fun blankSlotForArmLegWithTwoPlaceholders() {
        val r = renderOf("arm", "arm#_[time]")!!
        assertEquals("arm_", r.key)
        assertEquals(3, r.caretOffset, "光标停在 arm 之后，等着敲序号")

        val r2 = renderOf("arm_", "arm#_[time]")!!
        assertEquals("arm_", r2.key)
        assertEquals(3, r2.caretOffset)
    }

    // ===== 匹配 + 渲染：带序号 → 完整键，光标落行尾 =====

    @Test
    fun instantiateWhenNumberProvided() {
        val r = renderOf("builtFrom_1_", "builtFrom_{NUM}_name")!!
        assertEquals("builtFrom_1_name", r.key)
        assertNull(r.caretOffset, "占位符填满后光标落行尾")
    }

    @Test
    fun instantiateWithMultiDigitNumber() {
        assertEquals("builtFrom_12_pos", renderOf("builtFrom_12_pos", "builtFrom_{NUM}_pos")!!.key)
        assertEquals("action_3_addEnergy", renderOf("action_3_", "action_#_addEnergy")!!.key)
    }

    @Test
    fun instantiateArmLegFullKey() {
        val r = renderOf("arm1_5s", "arm#_[time]")!!
        assertEquals("arm1_5s", r.key)
        assertNull(r.caretOffset)

        val r2 = renderOf("arm1_", "arm#_[time]")!!
        assertEquals("arm1_", r2.key)
        assertEquals(5, r2.caretOffset, "时间位留空，光标停在末尾等敲 5s")
    }

    @Test
    fun instantiateLanguagePlaceholder() {
        val r = renderOf("text_es", "text_{LANG}")!!
        assertEquals("text_es", r.key)
        assertNull(r.caretOffset)
    }

    // ===== 前缀命中（用户还在敲字面段） =====

    @Test
    fun partialLiteralStillMatches() {
        assertEquals("builtFrom_1_name", renderOf("builtFrom_1_n", "builtFrom_{NUM}_name")!!.key)
        assertEquals("builtFrom_1_isLocked", renderOf("builtFrom_1_is", "builtFrom_{NUM}_isLocked")!!.key)
    }

    @Test
    fun allBuiltFromSiblingsMatchSamePrefix() {
        val hits = index.matchPrefix("builtFrom_1_")
        assertEquals(11, hits.size, "builtFrom 一族的 11 条都应命中")
    }

    @Test
    fun longerInputThanTemplateDoesNotMatch() {
        // 输入比模板更长时，短模板（builtFrom_{NUM}_isLocked）不应命中
        val keys = index.matchPrefix("builtFrom_1_isLockedMessage").map { index.render(it.template, it.fills).key }
        assertTrue("builtFrom_1_isLockedMessage" in keys)
        assertFalse("builtFrom_1_isLocked" in keys)
    }

    @Test
    fun numberedInputKeepsExactSuffix() {
        val keys = index.matchPrefix("builtFrom_1_icon").map { index.render(it.template, it.fills).key }
        assertEquals(listOf("builtFrom_1_icon"), keys, "只应命中 _icon 一条")
    }

    @Test
    fun shortPrefixMatchesWholeFamily() {
        // 与现状 startsWith 行为一致：短前缀把同族全带出来（列表里是留空位形态）
        val keys = index.matchPrefix("action_").map { index.render(it.template, it.fills).key }
        assertEquals(7, keys.size, "action 一族 7 条")
        assertTrue("action__addEnergy" in keys)
    }

    // ===== 反查模板（值补全用） =====

    @Test
    fun toTemplateResolvesInstantiatedKeys() {
        assertEquals("builtFrom_{NUM}_name", index.toTemplate("builtFrom_1_name"))
        assertEquals("builtFrom_{NUM}_name", index.toTemplate("builtFrom_12_name"))
        assertEquals("builtFrom_{NUM}_icon", index.toTemplate("builtFrom_1_icon"))
        assertEquals("action_#_price", index.toTemplate("action_1_price"))
        assertEquals("canBuild_#_isLocked", index.toTemplate("canBuild_1_isLocked"))
        assertEquals("weaponSlot_{NUM}_name", index.toTemplate("weaponSlot_2_name"))
        assertEquals("arm#_[time]", index.toTemplate("arm1_5s"))
        assertEquals("text_{LANG}", index.toTemplate("text_es"))
    }

    @Test
    fun toTemplateAcceptsBlankSlotForm() {
        // 用户还没填数字就敲了冒号：builtFrom__name
        assertEquals("builtFrom_{NUM}_name", index.toTemplate("builtFrom__name"))
        assertEquals("action_#_pos", index.toTemplate("action__pos"))
    }

    @Test
    fun toTemplateRejectsUnrelatedKeys() {
        assertNull(index.toTemplate("mass"))
        assertNull(index.toTemplate("builtFrom_1_unknown"))
        assertNull(index.toTemplate("builtFrom_1_"))
        assertNull(index.toTemplate("builtFrom"))
    }

    // ===== X 形占位符：只认有实例证据的 2 条 =====

    @Test
    fun acceptsOnlyEvidenceBasedXKeys() {
        assertTrue(KeyTemplate.isTemplateKey("mutatorX_ifUnitWithTags"))
        assertTrue(KeyTemplate.isTemplateKey("mutatorX_ifUnitWithoutTags"))
    }

    @Test
    fun rejectsXLookalikesAndUnevidencedMutators() {
        // 坐标轴 X
        assertFalse(KeyTemplate.isTemplateKey("imageScaleX"))
        assertFalse(KeyTemplate.isTemplateKey("shadowOffsetX"))
        assertFalse(KeyTemplate.isTemplateKey("barrelX"))
        assertFalse(KeyTemplate.isTemplateKey("barrelOffsetX_onOddShots"))
        // 名字里的连接字母
        assertFalse(KeyTemplate.isTemplateKey("fireTurretXAtGround"))
        assertFalse(KeyTemplate.isTemplateKey("unloadUpToXUnitsFromSource"))
        // 函数参数名（压根不是键）
        assertFalse(KeyTemplate.isTemplateKey("offsetX"))
        assertFalse(KeyTemplate.isTemplateKey("offsetRandomXY"))
        // 同系列但没有实例证据的键，同样不动
        assertFalse(KeyTemplate.isTemplateKey("mutatorX_directDamageMultiplier"))
        assertFalse(KeyTemplate.isTemplateKey("mutatorX_areaDamageMultiplier"))
    }

    @Test
    fun xPlaceholderRendersBlankSlotAndInstantiates() {
        val blank = renderOf("mutator", "mutatorX_ifUnitWithTags")!!
        assertEquals("mutator_ifUnitWithTags", blank.key, "X 位留空")
        assertEquals(7, blank.caretOffset, "光标停在 mutator 之后")

        val filled = renderOf("mutator1_", "mutatorX_ifUnitWithTags")!!
        assertEquals("mutator1_ifUnitWithTags", filled.key)
        assertNull(filled.caretOffset)

        val filled2 = renderOf("mutator2_ifUnitWithoutTags", "mutatorX_ifUnitWithoutTags")!!
        assertEquals("mutator2_ifUnitWithoutTags", filled2.key)
    }

    @Test
    fun xPlaceholderMatchesFamilyOnPrefix() {
        val keys = index.matchPrefix("mutator1_").map { index.render(it.template, it.fills).key }
        assertEquals(2, keys.size, "模板集里只有这 2 条 mutator")
        assertTrue("mutator1_ifUnitWithTags" in keys)
        assertTrue("mutator1_ifUnitWithoutTags" in keys)
    }

    @Test
    fun toTemplateResolvesMutatorKeys() {
        assertEquals("mutatorX_ifUnitWithTags", index.toTemplate("mutator1_ifUnitWithTags"))
        assertEquals("mutatorX_ifUnitWithoutTags", index.toTemplate("mutator2_ifUnitWithoutTags"))
        // 空位形态（还没填数字）
        assertEquals("mutatorX_ifUnitWithTags", index.toTemplate("mutator_ifUnitWithTags"))
        // 无实例证据的同系列键：不认
        assertNull(index.toTemplate("mutator1_directDamageMultiplier"))
        // 坐标轴 X：不认
        assertNull(index.toTemplate("imageScaleX"))
    }

    @Test
    fun xPlaceholderDoesNotLeakToCoordinateKeys() {
        assertTrue(index.matchPrefix("imageScale").isEmpty(), "imageScaleX 不是模板")
        assertTrue(index.matchPrefix("barrelOffset").isEmpty(), "barrelOffsetX_* 不是模板")
        assertTrue(index.matchPrefix("fireTurretX").isEmpty(), "fireTurretXAt* 不是模板")
        assertTrue(index.matchPrefix("offsetX").isEmpty(), "offsetX 是函数参数名，不是键")
    }
}
