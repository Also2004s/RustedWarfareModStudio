package com.rwmodstudio.core.translation

import com.rwmodstudio.feature.completion.sectionEnToZh
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * section_required.json（节内必备属性表）数据完整性校验：
 * 1) 每个必备键都能在 code_reference（name / name_en，忽略大小写）里找到，防止拼写写错；
 * 2) 每个节分类名都是受支持的节（sectionEnToZh 的中文取值），保证能被执行链/节过滤识别。
 */
class SectionRequiredPropertiesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun assetFile(name: String): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/$name"),
            File(System.getProperty("user.dir"), "src/main/assets/data/$name"),
            File("app/src/main/assets/data/$name"),
            File("src/main/assets/data/$name")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$name not found. user.dir=${System.getProperty("user.dir")}")
    }

    private fun loadRequired(): Map<String, List<String>> =
        json.decodeFromString(assetFile("section_required.json").readText())

    private fun loadCodeReference(): CodeReferenceRepository.CodeReference =
        json.decodeFromString(CodeReferenceRepository.CodeReference.serializer(), assetFile("code_reference.json").readText())

    @Test
    fun everyRequiredKeyExistsInCodeReference() {
        val required = loadRequired()
        val cr = loadCodeReference()

        val known = mutableSetOf<String>()
        fun add(p: CodeReferenceRepository.PropertyInfo) {
            if (p.name.isNotBlank()) known.add(p.name.trim().lowercase())
            p.name_en?.takeIf { it.isNotBlank() }?.let { known.add(it.trim().lowercase()) }
        }
        cr.sections.values.forEach { it.data.forEach(::add) }
        cr.values.values.forEach { it.data.forEach(::add) }

        assertTrue(required.isNotEmpty(), "必备属性表不应为空")
        val missing = mutableListOf<String>()
        for ((section, keys) in required) {
            for (k in keys) {
                if (k.trim().lowercase() !in known) missing.add("$section / $k")
            }
        }
        assertTrue(missing.isEmpty(), "以下必备键在 code_reference 中不存在（拼写可能有误）: $missing")
    }

    @Test
    fun everyRequiredSectionIsKnownSection() {
        val required = loadRequired()
        val knownZh = sectionEnToZh.values.toSet()
        val unknown = required.keys.filter { it !in knownZh }
        assertTrue(unknown.isEmpty(), "以下节名不是受支持的节（sectionEnToZh 无此中文名）: $unknown")
    }
}