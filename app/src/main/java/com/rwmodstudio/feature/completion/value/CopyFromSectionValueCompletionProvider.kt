package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * @copyFromSection（复制节）值补全：完整节名（基名_节名，如 抛射体_1 / turret_主炮）。
 * 按当前节类型过滤：当前在 [隐藏行动_x] 只给 隐藏行动_yyy，其余命名节同理；
 * 当前为非命名节（核心/图像/资源等）或无节时回退给全部命名节。
 * 三源沿用 fileSymbols（当前文件 + 继承链 + 项目扫描），不新增扫描。
 */
class CopyFromSectionValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty()
        return isCopyFromSectionProperty(request.propertyName, prop?.name_en)
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prefix = request.valuePrefix
        val currentBase = sectionBaseFromCategory(request.sectionName)
        // 视图语言由键名是否含中文推断（中文视图 复制节:，英文视图 @copyFromSection:）
        val useChinesePrefix = request.propertyName.any { it.code in 0x4E00..0x9FFF }
        val names = buildCopyFromSectionNames(
            useChinesePrefix = useChinesePrefix,
            currentBase = currentBase,
            chainSectionNames = request.chainSectionNames
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

/** 是否为 @copyFromSection（复制节）属性：name_en 或 propertyName 归一化为 @copyfromsection，或中文 复制节 */
internal fun isCopyFromSectionProperty(propertyName: String, nameEn: String?): Boolean =
    nameEn?.lowercase() == "@copyfromsection" ||
        propertyName.lowercase() == "@copyfromsection" ||
        propertyName == "复制节"

/** 是否为 @copyFrom_skipThisSection（复制但跳过节）属性 */
internal fun isCopyFromSkipSectionProperty(propertyName: String, nameEn: String?): Boolean =
    nameEn?.lowercase() == "@copyfrom_skipthissection" ||
        propertyName.lowercase() == "@copyfrom_skipthissection" ||
        propertyName == "复制但跳过节"

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
 * 拼 @copyFromSection 的完整节名列表（基名_节名），数据源为**继承链**节名（[chainSectionNames]）。
 * [currentBase] 非空时只取该基名的节名；为 null 时回退全部命名节。基名前缀语言由 [useChinesePrefix] 决定。
 */
internal fun buildCopyFromSectionNames(
    useChinesePrefix: Boolean,
    currentBase: String?,
    chainSectionNames: Map<String, Set<String>>
): List<String> {
    // key 为节基名（小写，与 sectionBaseFromCategory 一致）；enPrefix 为英文视图显示的节前缀
    data class NamedSection(val key: String, val zh: String, val enPrefix: String)

    val sections = listOf(
        NamedSection("turret", "炮塔", "turret"),
        NamedSection("projectile", "抛射体", "projectile"),
        NamedSection("effect", "效果", "effect"),
        NamedSection("action", "行动", "action"),
        NamedSection("hiddenaction", "隐藏行动", "hiddenAction"),
        NamedSection("animation", "动画", "animation"),
        NamedSection("decal", "贴花", "decal"),
        NamedSection("attachment", "附属", "attachment"),
        NamedSection("canbuild", "可建造", "canBuild")
    )

    val selected = if (currentBase == null) sections else sections.filter { it.key == currentBase }
    return selected
        .flatMap { sec ->
            (chainSectionNames[sec.key] ?: emptySet()).map { name ->
                val prefix = if (useChinesePrefix) sec.zh else sec.enPrefix
                "${prefix}_$name"
            }
        }
        .distinct()
        .sorted()
}
