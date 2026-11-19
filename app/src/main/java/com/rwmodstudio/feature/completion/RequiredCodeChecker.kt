package com.rwmodstudio.feature.completion

import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.SectionRequiredProperties

/** 缺失的一枚必备键：[english] 为 RW 里的英文键名，[chinese] 是代码参考库里的中文名（查不到为 null）。 */
data class MissingKey(val english: String, val chinese: String?)

/** 某个已存在的节里缺的必备键。 */
data class SectionGap(val section: String, val missing: List<MissingKey>)

/**
 * 必备代码检查结果。
 *
 * [missingSections] = 整节缺失（模板文件不参与该判定，恒为空）；
 * [incomplete] = 已存在的节里缺的必备键（按 [SectionRequiredProperties.REQUIRED_SECTIONS] 在前、
 * 其余节按文件出现顺序排列）。
 */
data class RequiredCodeReport(
    val missingSections: List<String>,
    val incomplete: List<SectionGap>
) {
    val isEmpty: Boolean get() = missingSections.isEmpty() && incomplete.isEmpty()
}

/**
 * 必备代码检查器：纯函数，可在任意线程调用。
 *
 * 判定口径：
 * - 必备节 = [SectionRequiredProperties.REQUIRED_SECTIONS]（核心/图像/攻击/运动）；
 *   模板文件（.template）跳过缺节判定，只查已存在节的缺属性。
 * - 必备属性 = `assets/data/section_required.json`，键为英文小写；文件里写中文键时，
 *   经 [CodeReferenceRepository.getPropertiesForSection] 的 `name_en → name` 映射成中文别名后一并比对。
 * - 只看「当前文件 + 继承链」的并集，不区分来源优先级。
 */
object RequiredCodeChecker {

    /**
     * @param inheritedLines 继承链（模板/复制与/@copyFromSection）合并出的行，**不含当前文件自身**
     * @param ownLines 当前文件的文本行（取自编辑器内存，可包含未保存修改）
     * @param isTemplate 当前文件是否为 .template（跳过缺节判定）
     * @param requiredKeysOf 节 → 必备键集合（默认查 [SectionRequiredProperties]，单测可注入）
     * @param propertiesOf 节 → 代码参考属性（默认查 [CodeReferenceRepository]，单测可注入）
     */
    fun check(
        inheritedLines: List<String>,
        ownLines: List<String>,
        isTemplate: Boolean,
        requiredKeysOf: (String) -> Set<String>? = SectionRequiredProperties::requiredKeysFor,
        propertiesOf: (String) -> List<CodeReferenceRepository.PropertyInfo> = { section ->
            com.rwmodstudio.core.translation.TranslationEngine.getInstance()
                .getCodeReference().getPropertiesForSection(section)
        }
    ): RequiredCodeReport {
        val effective = ownLines + inheritedLines

        // 1) 文件（含继承链）里出现过的节，统一归一为中文分类
        val present = LinkedHashSet<String>()
        for (raw in effective) {
            val t = raw.trim()
            if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) {
                val name = t.substring(1, t.length - 1).trim()
                if (name.isNotEmpty()) present += mapSectionName(name)
            }
        }

        // 2) 缺失的必备节（模板不报缺节）
        val requiredSections = SectionRequiredProperties.REQUIRED_SECTIONS
        val missingSections = if (isTemplate) {
            emptyList()
        } else {
            requiredSections.filter { it !in present }
        }

        // 3) 已存在节里缺的必备属性：必备四节在前，其余按出现顺序
        val ordered = requiredSections.filter { it in present } + present.filter { it !in requiredSections }
        val incomplete = ordered.mapNotNull { section ->
            val requiredKeys = requiredKeysOf(section) ?: return@mapNotNull null
            val existing = collectSectionKeysFromMergedLines(effective, section) { mapSectionName(it) }
            val props = propertiesOf(section)
            val missing = requiredKeys.sorted().mapNotNull { key ->
                val chinese = props.firstOrNull { it.name_en?.lowercase() == key }?.name
                // 文件里可能写中文键（名称）也可能写英文键（name），两种都算已存在
                val aliases = buildSet {
                    add(key)
                    if (!chinese.isNullOrBlank()) add(chinese.lowercase())
                }
                if (existing.any { it in aliases }) null else MissingKey(key, chinese)
            }
            if (missing.isEmpty()) null else SectionGap(section, missing)
        }

        return RequiredCodeReport(missingSections, incomplete)
    }
}
