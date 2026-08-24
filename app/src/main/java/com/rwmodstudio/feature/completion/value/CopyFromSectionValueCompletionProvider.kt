package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * @copyFromSection（复制节）值补全：完整节名（英文基名_节名，如 projectile_xxx / turret_主炮）。
 * 复制与（copyFrom，非核心节）则只补同类节的节后缀名（如 [炮塔_cannon2] 里 cannon1）。
 * 按当前节类型过滤：当前在 [效果_x] 只给 效果_yyy，其余命名节同理；
 * 当前为非命名节（核心/图像/资源等）或无节时回退给全部命名节。
 * 三源沿用 fileSymbols（当前文件 + 继承链 + 项目扫描），不新增扫描。
 */
class CopyFromSectionValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        // 直接按属性名判定，不依赖 findProperty 的 name_en：复制与/复制节是合成指令属性，
        // 未必出现在当前节的补全 category 里（findProperty 修复后只查当前节，可能返回 null）。
        // 核心节的复制与(copyFrom)是 ini 文件路径，由 ProjectRefValueCompletionProvider 独占——
        // 聚合器会合并所有命中 Provider 的结果，若不在此排除，核心节复制与会既出路径又出节名。
        // 其他节的复制与引用同类节节名；复制节(@copyFromSection)在任意节都补完整节名。
        val nameEn = request.toEnglishName()
        if (CompletionShared.isCopyFromProperty(request.propertyName, nameEn) &&
            CompletionShared.isCoreSection(request.sectionName)
        ) return false
        return CompletionShared.isCopyFromSectionProperty(request.propertyName, nameEn) ||
            CompletionShared.isCopyFromProperty(request.propertyName, nameEn)
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prefix = request.valuePrefix
        val currentBase = sectionBaseFromCategory(request.sectionName)
        // 复制节(@copyFromSection)=引用任意节，值是【完整节名】（带英文基名前缀，如 projectile_scoutBotProjectileSplit）；
        // 复制与(copyFrom，非核心节)=引用同类节，值是【节后缀名】（不带基名前缀，如 [炮塔_cannon2] 里 复制与:cannon1）
        val nameEn = request.toEnglishName()
        val withBasePrefix = CompletionShared.isCopyFromSectionProperty(request.propertyName, nameEn)
        val names = buildCopyFromSectionNames(
            currentBase = currentBase,
            chainSectionNames = request.chainSectionNames,
            withBasePrefix = withBasePrefix
        )
        return names
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .map { name ->
                createValueItem(
                    label = name,
                    detail = "复制节 - 节名",
                    insertText = name,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
    }
}

/** 映射后的中文节分类 → 命名节基名；非命名节/无节返回 null */
internal fun sectionBaseFromCategory(sectionName: String?): String? = when (sectionName) {
    "炮塔" -> "turret"
    "抛射体" -> "projectile"
    "效果" -> "effect"
    "行动" -> "action"
    "隐藏行动" -> "hiddenaction"
    "动画" -> "animation"
    "贴花" -> "decal"
    "附属" -> "attachment"
    "可建造" -> "canbuild"
    else -> null
}

/**
 * 拼 @copyFromSection/复制与 的节点候选，数据源为**继承链**节名（[chainSectionNames]）。
 * [currentBase] 非空时只取该基名的节名；为 null 时回退全部命名节。
 * [withBasePrefix]=true（复制节 @copyFromSection）→ 完整节名「基名_后缀」（如 projectile_scoutBotProjectileSplit）；
 * [withBasePrefix]=false（复制与 copyFrom）→ 只补同类节的节后缀名（如 炮塔节里 cannon1）。
 * 节基名前缀一律英文（节名是 INI 语法固定词，不硬编码中文），显示中文由翻译引擎后处理交还。
 */
internal fun buildCopyFromSectionNames(
    currentBase: String?,
    chainSectionNames: Map<String, Set<String>>,
    withBasePrefix: Boolean
): List<String> {
    // key 为节基名（小写，与 sectionBaseFromCategory 一致）；en 为完整节名的英文节前缀
    data class NamedSection(val key: String, val en: String)

    val sections = listOf(
        NamedSection("turret", "turret"),
        NamedSection("projectile", "projectile"),
        NamedSection("effect", "effect"),
        NamedSection("action", "action"),
        NamedSection("hiddenaction", "hiddenAction"),
        NamedSection("animation", "animation"),
        NamedSection("decal", "decal"),
        NamedSection("attachment", "attachment"),
        NamedSection("canbuild", "canBuild")
    )

    val selected = if (currentBase == null) sections else sections.filter { it.key == currentBase }
    return selected
        .flatMap { sec ->
            (chainSectionNames[sec.key] ?: emptySet()).map { name ->
                if (withBasePrefix) "${sec.en}_$name" else name
            }
        }
        .distinct()
        .sorted()
}
