package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * code_reference.json 新增 default 字段：解析与数据完整性校验。
 */
class CodeReferenceDefaultTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCodeReference(): CodeReferenceRepository.CodeReference {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/code_reference.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/code_reference.json"),
            File("app/src/main/assets/data/code_reference.json"),
            File("src/main/assets/data/code_reference.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("code_reference.json not found. user.dir=${System.getProperty("user.dir")}")
        return json.decodeFromString(CodeReferenceRepository.CodeReference.serializer(), file.readText())
    }

    @Test
    fun propertyInfoDefaultMissingIsEmpty() {
        val p = json.decodeFromString(CodeReferenceRepository.PropertyInfo.serializer(), """{"name":"x"}""")
        assertEquals("", p.default)
    }

    @Test
    fun propertyInfoDefaultParsed() {
        val p = json.decodeFromString(
            CodeReferenceRepository.PropertyInfo.serializer(),
            """{"name":"队伍中此单位数量","default":"队伍中此单位数量("}"""
        )
        assertEquals("队伍中此单位数量(", p.default)
    }

    @Test
    fun allValuesCategoryItemsHaveNonBlankDefault() {
        val cr = loadCodeReference()
        var valuesItems = 0
        var missing = 0
        for ((_, cat) in cr.values) {
            val hasType = cat.data.any { it.type.isNotEmpty() }
            if (!hasType) continue
            for (p in cat.data) {
                valuesItems++
                if (p.default.isBlank()) missing++
            }
        }
        assertTrue(valuesItems >= 200, "values 带 type 条目应 >= 200, actual=$valuesItems")
        assertEquals(0, missing, "values 条目的 default 不应为空")
    }

    @Test
    fun codeReferenceStillParsesAfterRewrite() {
        // 重写后的文件仍能被完整解析（sections/values 结构未破坏）
        val cr = loadCodeReference()
        assertTrue(cr.sections.isNotEmpty())
        assertTrue(cr.values.isNotEmpty())
    }

    @Test
    fun pricesResourcesDefaultsAreBareNames() {
        val cr = loadCodeReference()
        val cat = cr.values["Prices_Resources"] ?: error("Prices_Resources 类别缺失")
        val byName = cat.data.associateBy { it.name }
        assertEquals("能量", byName["能量"]?.default, "能量 是资源键，default 应为裸名")
        assertEquals("拥有标志", byName["拥有标志"]?.default, "拥有标志 是资源键，default 应为裸名")
        assertEquals("hp", byName["hp"]?.default, "hp 是资源键，default 应为裸名")
        assertEquals("ammo", byName["ammo"]?.default, "ammo 是资源键，default 应为裸名")
    }

    @Test
    fun numericGettersDefaultsAreBareNames() {
        val cr = loadCodeReference()
        val cat = cr.values["logicboolean"] ?: error("logicboolean 类别缺失")
        val byName = cat.data.associateBy { it.name }
        assertEquals("资源", byName["资源"]?.default, "资源 是数值 getter，default 应为裸名")
        assertEquals("自身血量", byName["自身血量"]?.default, "自身血量 是数值 getter，default 应为裸名")
        assertEquals("生命值", byName["生命值"]?.default, "生命值 是数值 getter，default 应为裸名")
        assertEquals("高度", byName["高度"]?.default, "高度 是数值 getter，default 应为裸名")
        assertEquals("自身弹药", byName["自身弹药"]?.default, "自身弹药 是数值 getter，default 应为裸名")
    }

}
