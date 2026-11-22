package com.rwmodstudio.feature.completion.value

import android.content.Context
import com.rwmodstudio.core.KeyTemplateRegistry
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.translation.CodeReferenceRepository
import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.feature.completion.CompletionProvider

/**
 * 值补全请求上下文
 * 聚合一次值补全所需的全部信息，避免每个 Provider 重复解析。
 */
data class ValueCompletionRequest(
    val context: Context,
    val sectionProperties: Map<String, List<CodeReferenceRepository.PropertyInfo>>,
    val propertyName: String,
    val sectionName: String?,
    val valuePrefix: String,
    val rawValuePrefixLength: Int,
    val lineText: String,
    val textBeforeCursor: String,
    val textAfterCursor: String,
    val translationDict: TranslationDict? = null,
    val memoryNames: Set<String> = emptySet(),
    /** 内存变量 → 声明类型（unit 型内存变量支持链式成员补全） */
    val memoryTypes: Map<String, String> = emptyMap(),
    /** 全局变量（@global 变量名，项目级，${} 引用） */
    val globalVariables: Set<String> = emptySet(),
    /** 局部变量（@define 变量名，仅当前节 + 继承链同节可见，${} 引用） */
    val localVariables: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val globalTags: Set<String> = emptySet(),
    val messageTags: Set<String> = emptySet(),
    val actionTags: Set<String> = emptySet(),
    val resources: Set<String> = emptySet(),
    val globalResources: Set<String> = emptySet(),
    val unitNames: Set<String> = emptySet(),
    /** 原版单位名（内置 assets/vanilla_units.json，rw_analysis 逆向的全部原版单位），独立于项目单位 unitNames */
    val vanillaUnitNames: Set<String> = emptySet(),
    /** 单位 [core] 节建造信息：单位名 → 价格/建造速度（供单位名补全右侧详情展示） */
    val unitData: Map<String, ProjectTagScanner.UnitBuildInfo> = emptyMap(),
    val turretNames: Set<String> = emptySet(),
    val projectileNames: Set<String> = emptySet(),
    val effectNames: Set<String> = emptySet(),
    val actionNames: Set<String> = emptySet(),
    val hiddenActionNames: Set<String> = emptySet(),
    val animationNames: Set<String> = emptySet(),
    val decalNames: Set<String> = emptySet(),
    val attachmentNames: Set<String> = emptySet(),
    val buildableNames: Set<String> = emptySet(),
    val soundFiles: Set<String> = emptySet(),
    val chainSectionNames: Map<String, Set<String>> = emptyMap(),
    /** 当前编辑文件所在目录（绝对路径），用于图片/音效「同目录裸名、跨目录 ROOT:」补全判定 */
    val currentFileDir: String = "",
    /** 项目根目录（绝对路径），用于将项目相对路径归一到 ROOT:/ 前缀 */
    val projectRoot: String = ""
) {
    /**
     * 在当前节（或全局）中查找属性定义（数据来自自定义补全三表）。
     */
    fun findProperty(): CodeReferenceRepository.PropertyInfo? {
        // 当前节优先，再跨节兜底：很多属性的 type 是固有语义（LogicBoolean/Bool/Enum/图片 等），
        // 与所在真实节无关，依赖 .type 判定的 Provider 需要能从全表取到定义（如 需要条件/自动触发）。
        // 当前节优先保证「type 因节而异」的属性（如 复制与：核心=file(ini)/腿=int/炮塔=turret ref）
        // 优先取当前节定义；该类属性的依赖方（isCopyFromFile）另用显式节判定（sectionName==核心 + type 含 file）
        // 双防火墙防误判，不依赖此处返回值的节精度。
        val sectionsToSearch = mutableListOf<String>()
        sectionName?.let { sectionsToSearch.add(it) }
        sectionsToSearch.addAll(sectionProperties.keys)
        // 中文属性名经翻译库反查英文名：code_reference 中部分条目 name 本身为英文（无中文名，
        // 如 alsoTriggerOrQueueActionWithTarget / teleportTo / builtFrom_{NUM}_name 等），
        // 中文视图输入时按 name/name_en 直接匹配不上，导致本函数返回 null、依赖 .type 的 Provider 漏触发。
        // 统一在此补反查匹配，一处修复全局生效，避免每个 Provider 各自修。
        val enName = if (propertyName.any { it.code in 0x4E00..0x9FFF })
            translationDict?.getTranslationBack(propertyName)?.takeIf { it != propertyName }
        else null
        // 序号/参数模板键：输入的是实例化键（builtFrom_1_name、含未填数字的空位形态 builtFrom__name），
        // 而表里存的是模板名（builtFrom_{NUM}_name）——先还原成模板名，否则查不到 type，
        // 依赖 .type 的 Provider（bool/enum/LogicBoolean/图片…）全部漏触发。
        val templateName = KeyTemplateRegistry.toTemplate(propertyName)
        val templateEnName = enName?.let { KeyTemplateRegistry.toTemplate(it) }
        for (section in sectionsToSearch.distinct()) {
            val prop = sectionProperties[section].orEmpty().find {
                it.name == propertyName || it.name_en == propertyName ||
                    (enName != null && (it.name.equals(enName, ignoreCase = true) || it.name_en.equals(enName, ignoreCase = true))) ||
                    (templateName != null && (it.name.equals(templateName, ignoreCase = true) || it.name_en.equals(templateName, ignoreCase = true))) ||
                    (templateEnName != null && (it.name.equals(templateEnName, ignoreCase = true) || it.name_en.equals(templateEnName, ignoreCase = true)))
            }
            if (prop != null) return prop
        }
        return null
    }

    /**
     * 通过翻译库把中文属性名转成英文，供各 Provider 查表时使用。
     * 若当前属性名已是英文或翻译库未就绪，则返回原值。
     * 序号/参数模板键优先还原成模板名（名单判定按模板名登记，如 builtFrom_{NUM}_name 属单位名列表）。
     */
    fun toEnglishName(): String {
        if (propertyName.isEmpty()) return propertyName
        KeyTemplateRegistry.toTemplate(propertyName)?.let { return it }
        if (propertyName.none { it.code in 0x4E00..0x9FFF }) return propertyName
        val en = translationDict?.getTranslationBack(propertyName) ?: return propertyName
        return if (en == propertyName) propertyName else KeyTemplateRegistry.normalize(en)
    }

    /**
     * 通过翻译库检查英文属性名映射到中文后是否匹配当前输入的 propertyName。
     * 用于判断如输入"临时标签添加"是否对应 temporallyAddTags 这类 tag 属性。
     */
    fun chineseNameMatchesEnglish(targetEn: String): Boolean {
        if (propertyName.isEmpty()) return false
        val zh = translationDict?.getTranslation(targetEn) ?: return false
        return zh.equals(propertyName, ignoreCase = true)
    }

    /**
     * 当前属性名是否为中文
     */
    fun isChineseName(): Boolean = propertyName.any { it.code in 0x4E00..0x9FFF }
    fun isInsideParentheses(): Boolean = isInsideParentheses(textBeforeCursor)
}

/**
 * 多值属性（值与值之间用逗号分隔）在刚打了空格/Tab 时该提示「,」而不是候选值。
 *
 * 为什么：空格**不是**合法的分隔符（游戏只认逗号），若此时照常弹候选，用户一选就会写出
 * `a.ini b.ini` 这种游戏不认识的写法。行为与标签补全一致：打了空格只弹「,」，点了再继续补下一个。
 *
 * 判定（沿用标签补全口径，并收紧一条）：
 * - 括号内不参与（括号里的逗号是参数分隔，归参数补全管）；
 * - 光标紧前一个字符是空格或 Tab；
 * - 去掉尾部空白后最后一个字符不能是逗号（逗号后已分隔完成，允许"逗号 + 空格"的美观写法）；
 * - 也不能是冒号（值还没内容就敲空格，不该提示逗号）。
 */
internal fun needsCommaSeparator(request: ValueCompletionRequest): Boolean {
    if (request.isInsideParentheses()) return false
    val lastChar = request.textBeforeCursor.lastOrNull()
    if (lastChar != ' ' && lastChar != '\t') return false
    val prevNonSpace = request.textBeforeCursor.trimEnd().lastOrNull()
    return prevNonSpace != null &&
        prevNonSpace != ',' && prevNonSpace != '，' &&
        prevNonSpace != ':' && prevNonSpace != '：'
}

/**
 * 生成「,」分隔符补全项：选中后**吞掉刚打的那个空格**（valuePrefixLength = 1），
 * 直接得到 `a.ini,b.ini`，不会留下 `a.ini ,b.ini` 这种怪写法。
 *
 * @param detail 右侧说明，各补全模块按自己的值类型给（如「文件分隔符（值与值之间用逗号分隔）」）。
 */
internal fun commaSeparatorItem(detail: String): CompletionProvider.CompletionItem =
    CompletionProvider.CompletionItem(
        label = ",",
        type = CompletionProvider.CompletionType.VALUE,
        detail = detail,
        insertText = ",",
        valuePrefixLength = 1,
        valueType = "string"
    )

/**
 * 判断文本中光标位置是否位于值内未闭合的 '(' 之后（括号内）：
 * 从行内最后一个 ':' 之后向前扫描，遇到未闭合的 '(' 返回 true，遇到 ')' 返回 false。
 */
internal fun isInsideParentheses(textBeforeCursor: String): Boolean {
    // 键值分隔取行内第一个 ':'（与 splitKeyValueLine 一致）；值内可含 ':'（如 ROOT:/CUSTOM:）
    val colonIdx = textBeforeCursor.indexOf(':')
    if (colonIdx == -1) return false
    for (i in textBeforeCursor.length - 1 downTo colonIdx + 1) {
        when (textBeforeCursor[i]) {
            '(' -> return true
            ')' -> return false
        }
    }
    return false
}
