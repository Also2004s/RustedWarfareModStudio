package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 必备代码检查器单测。
 *
 * 必备属性表与代码参考库在 JVM 单测里没有 Context 可加载，故通过 [RequiredCodeChecker.check]
 * 的两个注入参数提供假数据，只验证「节归一化 / 缺节 / 缺属性 / 模板跳过缺节」这几条判定逻辑。
 */
class RequiredCodeCheckerTest {

    /** 与 assets/data/section_required.json 同口径的最小假表 */
    private val fakeRequired = mapOf(
        "核心" to setOf("name", "mass", "price", "maxhp", "radius"),
        "图像" to setOf("image"),
        "攻击" to setOf("canattack"),
        "运动" to setOf("movementtype")
    )

    /** 代码参考库里中文名映射（只给核心的几条，其余节无别名 → 按英文直比） */
    private val fakeProps = mapOf(
        "核心" to listOf(
            prop("名称", "name"),
            prop("质量", "mass"),
            prop("价格", "price"),
            prop("最大生命", "maxHp"),
            prop("半径", "radius")
        )
    )

    private fun prop(zh: String, en: String) =
        com.rwmodstudio.core.translation.CodeReferenceRepository.PropertyInfo(name = zh, name_en = en)

    private fun check(
        inherited: List<String> = emptyList(),
        own: List<String>,
        isTemplate: Boolean = false
    ) = RequiredCodeChecker.check(
        inheritedLines = inherited,
        ownLines = own,
        isTemplate = isTemplate,
        requiredKeysOf = { fakeRequired[it] },
        propertiesOf = { fakeProps[it] ?: emptyList() }
    )

    @Test
    fun emptyFileReportsAllRequiredSections() {
        val report = check(own = listOf(""))
        assertEquals(listOf("核心", "图像", "攻击", "运动"), report.missingSections)
        assertTrue(report.incomplete.isEmpty(), "没有任何节时不应有缺属性分组")
    }

    @Test
    fun coreWithOnlyNameReportsOtherKeys() {
        val report = check(
            own = listOf(
                "[core]",
                "name: 甲"
            )
        )
        assertEquals(listOf("图像", "攻击", "运动"), report.missingSections)
        val core = report.incomplete.firstOrNull { it.section == "核心" }
            ?: error("应报告核心缺属性")
        assertEquals(
            listOf("mass", "maxhp", "price", "radius"),
            core.missing.map { it.english }
        )
        assertEquals("质量", core.missing.first { it.english == "mass" }.chinese)
    }

    @Test
    fun templateSkipsMissingSectionsButStillChecksKeys() {
        val report = check(
            own = listOf(
                "[core]",
                "name: 甲"
            ),
            isTemplate = true
        )
        assertTrue(report.missingSections.isEmpty(), "模板文件不报缺节")
        assertTrue(report.incomplete.any { it.section == "核心" }, "模板仍查已存在节的缺属性")
    }

    @Test
    fun inheritedSectionsSatisfyRequirements() {
        val inherited = listOf(
            "[graphics]",
            "image: builder.png",
            "[attack]",
            "canAttack: true",
            "[movement]",
            "movementType: LAND"
        )
        val own = listOf(
            "[core]",
            "name: 甲",
            "mass: 1",
            "price: 10",
            "maxHp: 100",
            "radius: 5"
        )
        val report = check(inherited = inherited, own = own)
        assertTrue(report.isEmpty, "四节齐全且属性来自继承链时不应有缺失: $report")
    }

    @Test
    fun chineseKeysAreRecognizedViaPropertyAlias() {
        val own = listOf(
            "[core]",
            "名称: 甲",
            "质量: 1",
            "价格: 10",
            "最大生命: 100",
            "半径: 5"
        )
        val report = check(own = own)
        val core = report.incomplete.firstOrNull { it.section == "核心" }
        assertTrue(core == null, "中文键应经 name_en → name 别名命中，不应报核心缺失: $core")
    }
}
