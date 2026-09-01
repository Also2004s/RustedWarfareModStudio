package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel
import com.rwmodstudio.feature.completion.sectionEnToZh

/**
 * `${节名.属性}` 跨节属性引用补全。
 *
 * 实际项目大量使用 `${攻击.攻击距离}`、`${运动.移动速度}` 读取其他节属性值（含算术，如
 * `${攻击.攻击距离-15}`）。触发条件（值片段含未闭合的 `${`）：
 * 1. `${` 后尚未输入节名 → 建议节名（英文节名 core/attack 等，排除 values/特定值 类别）；
 * 2. `${节名.片段` → 建议该节属性名（只替换点后片段，保留 `${节名.`）。
 * 节名支持英文（${core.X}→核心）与特殊关键字 `section`（当前节）。
 *
 * 数据：request.sectionProperties（code_reference 以中文节名为 key 的属性表）。
 */
class CrossSectionRefValueCompletionProvider : BaseValueCompletionProvider() {

    // 中文节名→英文节名反向表（由 sectionEnToZh 反推）：节名是 INI 固定词汇，跨节引用始终用英文节名建议 ${core. 等。
    // 用 putIfAbsent 保证一对多（leg/arm→腿）时取首个英文名（leg），避免 reverse-associate 覆盖成 arm。
    private val zhToEnSection: Map<String, String> = buildMap {
        sectionEnToZh.forEach { (en, zh) -> putIfAbsent(zh, en) }
    }

    // 不可通过 `${节.键}` 静态引用的属性（依据 rw_analysis 逆向文档 + rwTool 解析器）：
    //   - dont_load（禁止加载）：文档明确「被引用 → 错误」
    //   - @memory/@define/@global 等宏键：走裸名引用语法（@define xxx），非 `节.键` 形式
    //   - copyFrom/defineUnitMemory 等指令键：值为文件/内存清单，非普通静态数值
    //   - updateUnitMemory(rate)：运行时会变动的值，静态引用无意义（「访问变更内容 → 异常」）
    // 前缀匹配兜底 `@` 开头的宏键与 defineUnitMemory: ...（name 含内存清单、无 name_en）情况。
    private val nonReferenceableKeyPrefixes = listOf("@", "defineUnitMemory")
    private val nonReferenceableKeys = setOf(
        "dont_load",
        "copyFrom",
        "updateUnitMemory",
        "updateUnitMemoryRate",
    )

    /** 判断某属性是否可被 `${节.键}` 静态引用 */
    private fun isReferenceable(en: String): Boolean {
        if (en in nonReferenceableKeys) return false
        return nonReferenceableKeyPrefixes.none { en.startsWith(it) }
    }

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        val ref = crossSectionRef(request.valuePrefix)
        // 括号内通常由 FunctionParameterCompletionProvider 处理参数名/参数值；
        // 但括号内若含未闭合 `${`（如 范围内=${攻击.攻），FunctionParameter 不处理 `${`，
        // 仍由本 Provider 提供跨节补全。故仅当括号内且无 `${` 时才抑制。
        if (request.isInsideParentheses() && ref == null) return false
        return ref != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val ref = crossSectionRef(request.valuePrefix) ?: return emptyList()

        // 模式1：${节名.片段 → 补该节属性名
        val section = ref.section
        if (section != null) {
            // 节名归一化：`section`→当前节、英文→中文（core→核心），再按中文节名查属性表
            val key = resolveCrossSectionKey(section, request) ?: return emptyList()
            val props = request.sectionProperties[key] ?: return emptyList()
            // 跨节引用写入 ini 的是真实语法值：属性名不做汉化，一律用英文（name_en，缺失回退 name），
            // 中文说明放 detail（desc_zh，缺失回退 type），不做双语候选。
            // 过滤掉不可静态引用的宏/指令/运行时键（dont_load/@memory/copyFrom 等）。
            val candidates = props
                .map { p ->
                    val en = p.name_en?.takeIf { it.isNotBlank() } ?: p.name
                    val desc = p.desc_zh.takeIf { it.isNotBlank() }
                        ?: p.type.takeIf { it.isNotBlank() }
                        ?: "跨节属性"
                    en to desc
                }
                .distinctBy { it.first }
                .filter { it.first.isNotBlank() && isReferenceable(it.first) }
            return candidates
                .map { (en, desc) -> Triple(en, desc, completionMatchLevel(ref.fragment, en)) }
                .filter { it.third > 0 }
                .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first.lowercase() })
                .map { (en, desc, _) ->
                    createValueItem(
                        label = en,
                        detail = desc,
                        insertText = en,
                        prefixLength = ref.fragment.length,
                        valueType = "any"
                    )
                }
        }

        // 模式2：${片段（未输入节名）→ 建议节名
        // 节名是 INI 语法固定词汇，不做汉化：一律建议英文节名（core/attack，映射不到保留原样）；
        // 并排除 values/特定值 等非真实节类别。
        val excludedCategories = setOf("values", "特定值")
        val sectionNames = request.sectionProperties.keys
            .filterNot { it in excludedCategories }
            .map { zhToEnSection[it] ?: it }
        return sectionNames
            .filter { it.isNotBlank() }
            .map { it to completionMatchLevel(ref.fragment, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .map { it.first }
            .map { sectionName ->
                createValueItem(
                    label = sectionName,
                    detail = "节名",
                    insertText = sectionName,
                    prefixLength = ref.fragment.length,
                    valueType = "string"
                )
            }
    }

    /**
     * 归一化 `${节` 中的节名：特殊关键字 `section` → 当前节；英文节名 → 中文（core→核心，含 `_` 后缀
     * core_base→核心）；已是中文节名则原样返回。无法解析返回 null。
     */
    private fun resolveCrossSectionKey(section: String, request: ValueCompletionRequest): String? {
        val s = section.trim()
        if (s.isEmpty()) return null
        // 特殊关键字 section：表示当前节
        if (s.equals("section", ignoreCase = true)) {
            return request.sectionName?.takeIf { it.isNotBlank() }
        }
        // 英文节名 → 中文（core→核心、attack→攻击），含下划线后缀（global_resource_xxx→全局资源）
        val lower = s.lowercase().replace(" ", "").substringBefore('_')
        sectionEnToZh[lower]?.let { return it }
        return s
    }
}

/** 跨节引用解析结果：section=已输入节名（null 表示仅 `${` 未输入节名）；fragment=点后/节名已输入片段 */
internal class CrossSectionRef(val section: String?, val fragment: String)

/**
 * 解析 `${` 跨节引用。命中 `${节名.片段` 或 `${片段`（未输入节名）时返回，否则 null。
 * 已闭合（`${...}` 后出现 `}`）不触发。
 */
internal fun crossSectionRef(valuePrefix: String): CrossSectionRef? {
    val open = valuePrefix.lastIndexOf("\${")
    if (open < 0) return null
    val after = valuePrefix.substring(open + 2)
    if (after.contains('}')) return null // 已闭合
    val dotIdx = after.lastIndexOf('.')
    if (dotIdx >= 0) {
        val section = after.substring(0, dotIdx).trim()
        if (section.isEmpty()) return null
        return CrossSectionRef(section = section, fragment = after.substring(dotIdx + 1))
    }
    // 未输入节名：仅建议节名（fragment 为已输入的部分）
    return CrossSectionRef(section = null, fragment = after)
}