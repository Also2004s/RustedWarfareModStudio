package com.rwmodstudio.core.translation

import com.rwmodstudio.core.SettingsManager

/**
 * 四类名字（资源名 / 标签 / 内存名 / 变量名）在整行翻译链路中的**原位保护**。
 *
 * 「翻译禁用管理」的开关不只在补全里生效：整行翻译、行尾灯泡、保存反译、查重、
 * 文件翻译缓存走的都是同一套翻译流程，凡是这些名字出现的位置都必须保持原文。
 * 做法：翻译前把名字片段替换成占位符，翻译完成后原样还原，字典匹配自然碰不到它们。
 *
 * 覆盖的位置（开关打开时）：
 * - 资源名：`资源.xxx` / `resource.xxx` 链；资源属性值里的 `资源名=数值` 左侧；
 *   `自身有资源(资源名=...)` 参数名；
 * - 标签：标签参数 `有标签=xxx` / `需标签=xxx` 等的值（标签属性整值保护由引擎原有逻辑负责）；
 * - 内存名：`内存.xxx` / `memory.xxx` 链；`读取单位内存('xxx')` / `事件数据('xxx')` 的名字；
 *   设置/更新单位内存的 `变量名=表达式` 左侧；`定义单位内存` 声明的变量名；
 * - 变量名：`${变量}` 引用（含点的是节属性引用，不在此列）。
 */
internal object NameProtector {

    /**
     * 四个开关在一次翻译入口处的快照：避免每个 value 都去读设置（FileSettings 同一把同步锁，
     * 整项目查重多线程并行时会放大锁竞争）。
     */
    data class Options(
        val resource: Boolean,
        val tag: Boolean,
        val memory: Boolean,
        val variable: Boolean
    ) {
        companion object {
            fun current(): Options = Options(
                resource = SettingsManager.devTranslationBlockResource,
                tag = SettingsManager.devTranslationBlockTag,
                memory = SettingsManager.devTranslationBlockMemory,
                variable = SettingsManager.devTranslationBlockVariable
            )
        }
    }

    /** 占位符包边字符（Unicode 私用区，正常翻译文本里不会出现） */
    private const val PH_OPEN = '\uE000'
    private const val PH_CLOSE = '\uE001'

    /** 资源名引用：`资源.名字` / `self.resource.name` / `当前动作目标.资源.名字`（名字在比较/逻辑运算符处截断） */
    private val RESOURCE_REF = Regex(
        """((?:[\w\u4e00-\u9fff]+\.)?(?:资源|resource)\s*\.)([^.\s,()\[\]{}=+\-*/%!'"<>&|]+)""",
        RegexOption.IGNORE_CASE
    )

    /** 自身有资源(...) 调用：内部 `名字=表达式` 的名字部分 */
    private val HAS_RESOURCES_CALL = Regex(
        """((?:自身有资源|hasResources|self\.hasResources)\s*\()([^)]*)(\))""",
        RegexOption.IGNORE_CASE
    )

    /** 标签参数：`有标签=名字` / `withActionTag="名字"` 等（参数表里 tag/globalTag/messageTag/actionTag 的全部键名与中文名） */
    private val TAG_PARAM_RHS = Regex(
        """(有标签|需标签|有全局标签|行动标签|消息标签|withTag|withActionTag|includes|hasTag|globalTag|messageTag|actionTag)\s*=\s*('[^']*'|"[^"]*"|[^,，)）、\s]+)""",
        RegexOption.IGNORE_CASE
    )

    /** 内存名引用：`内存.名字` / `memory.name`（名字在比较/逻辑运算符处截断） */
    private val MEMORY_REF = Regex(
        """((?:内存|memory)\s*\.)([^.\s,()\[\]{}=+\-*/%!'"<>&|]+)""",
        RegexOption.IGNORE_CASE
    )

    /** `@memory 名字` token 引用 */
    private val MEMORY_AT_TOKEN = Regex(
        """(@memory\s+)([^\s,，:：()\[\]]+)""",
        RegexOption.IGNORE_CASE
    )

    /** 读取单位内存('名字') / 事件数据('名字') 的第一个引号参数 */
    private val MEMORY_QUOTED_ARG = Regex(
        """((?:读取单位内存|事件数据|readUnitMemory|eventData)\s*\(\s*)(['"])([^'"]+)\2""",
        RegexOption.IGNORE_CASE
    )

    /** 定义单位内存声明：`类型 名字` 里的名字 */
    private val DEFINE_MEMORY_NAME = Regex(
        """(\b(?:int|float|string|bool|boolean|number|text|logic|unit)(?:\[\])?\s+)([^\s,，]+)""",
        RegexOption.IGNORE_CASE
    )

    /** ${变量} 引用（不含点的才是变量名；含点的是节属性引用） */
    private val VARIABLE_REF = Regex("""\$\{([^}.]*)\}""")

    /** 逗号分隔的裸资源名（如 displayTextAppendResource: 资金） */
    private val STANDALONE_NAME = Regex(
        """(^|[,，])([ \t]*)([A-Za-z_\u4e00-\u9fff][\w\u4e00-\u9fff\[\]]*)([ \t]*)(?=$|[,，])"""
    )

    /** 裸资源名里不参与保护的布尔字面量（还原后本来也不变，避免无意义占位） */
    private val STANDALONE_EXCLUDED = setOf("true", "false", "真", "假")

    /**
     * 保护一段 value 里的名字片段。
     * @param keyEn 属性英文名（用于判断资源/内存等属性上下文，可传空）
     * @param block 四个开关的快照；一次翻译入口取一次，避免每个值重复读设置
     * @return 保护后的文本 + 占位原文表（翻译完成后交给 [restore]）
     */
    fun protect(
        value: String,
        keyEn: String,
        block: Options = Options.current(),
        /** 返回 true 的资源名不保护（白名单允许翻译）；默认全部保护 */
        allowResourceName: (String) -> Boolean = { false }
    ): Pair<String, List<String>> {
        if (value.isEmpty()) return value to emptyList()
        val masker = Masker()
        var text = value

        if (block.resource) {
            text = masker.mask(text, RESOURCE_REF, 2, allow = allowResourceName)
            if (isResourcePropertyKey(keyEn)) {
                text = masker.maskTopLevelLhsNames(text, allowResourceName)
                text = masker.mask(text, STANDALONE_NAME, 3, STANDALONE_EXCLUDED, allowResourceName)
            }
            text = maskHasResourcesArgs(masker, text, allowResourceName)
        }

        if (block.tag && !isTagPropertyKey(keyEn)) {
            text = masker.mask(text, TAG_PARAM_RHS, 2)
        }

        if (block.memory) {
            text = masker.mask(text, MEMORY_REF, 2)
            text = masker.mask(text, MEMORY_AT_TOKEN, 2)
            text = masker.mask(text, MEMORY_QUOTED_ARG, 3)
            if (isKvpMemoryKey(keyEn)) text = masker.maskTopLevelLhsNames(text)
            if (isDefineUnitMemoryKey(keyEn)) text = masker.mask(text, DEFINE_MEMORY_NAME, 2)
        }

        if (block.variable) {
            text = masker.mask(text, VARIABLE_REF, 1)
        }

        return text to masker.originals
    }

    /** 把占位符还原成原文 */
    fun restore(text: String, originals: List<String>): String {
        if (originals.isEmpty()) return text
        return Regex("\uE000(\\d+)\uE001").replace(text) { m ->
            originals.getOrNull(m.groupValues[1].toIntOrNull() ?: -1) ?: m.value
        }
    }

    // ===== 属性上下文判定 =====

    private fun isResourcePropertyKey(keyEn: String): Boolean {
        if (keyEn.isEmpty()) return false
        return CompletionShared.isResourceProperty(keyEn)
    }

    private fun isTagPropertyKey(keyEn: String): Boolean {
        if (keyEn.isEmpty()) return false
        return CompletionShared.isTagProperty(keyEn)
    }

    private fun isKvpMemoryKey(keyEn: String): Boolean {
        if (keyEn.isEmpty()) return false
        val en = keyEn.lowercase()
        return en == "setunitmemory" || en == "updateunitmemory"
    }

    private fun isDefineUnitMemoryKey(keyEn: String): Boolean =
        CompletionShared.isDefineUnitMemoryProperty(keyEn)

    // ===== 内部工具 =====

    private fun maskHasResourcesArgs(
        masker: Masker,
        text: String,
        allowResourceName: (String) -> Boolean
    ): String {
        return HAS_RESOURCES_CALL.replace(text) { m ->
            val args = m.groupValues[2]
            val maskedArgs = masker.maskTopLevelLhsNames(args, allowResourceName)
            "${m.groupValues[1]}$maskedArgs${m.groupValues[3]}"
        }
    }

    private class Masker {
        val originals = mutableListOf<String>()

        /** 用占位符替换 regex 指定捕获组；allow 返回 true 的片段不保护（白名单允许翻译） */
        fun mask(
            text: String,
            regex: Regex,
            group: Int,
            exclude: Set<String> = emptySet(),
            allow: ((String) -> Boolean)? = null
        ): String {
            return regex.replace(text) { m ->
                val g = m.groups[group] ?: return@replace m.value
                val original = g.value
                if (original.isBlank()) return@replace m.value
                if (exclude.isNotEmpty() && original.trim().lowercase() in exclude) return@replace m.value
                if (allow?.invoke(original) == true) return@replace m.value
                val ph = "\uE000${originals.size}\uE001"
                originals.add(original)
                val start = g.range.first - m.range.first
                val end = g.range.last - m.range.first + 1
                m.value.replaceRange(start, end, ph)
            }
        }

        /**
         * 只在最外层（括号深度 0）屏蔽 `名字=值` 的左侧名字。
         * 嵌套函数参数（如 withinRange=500 / greaterThan=0）不动，避免把参数名当资源名/变量名保护。
         */
        fun maskTopLevelLhsNames(text: String, allow: ((String) -> Boolean)? = null): String {
            val out = StringBuilder(text.length)
            var depth = 0
            var segStart = 0
            var i = 0
            fun flush(end: Int) {
                if (end <= segStart) return
                val seg = text.substring(segStart, end)
                val firstOpen = seg.indexOfFirst { it == '(' || it == '[' || it == '{' }
                val eq = seg.indexOf('=')
                if (eq > 0 && (firstOpen < 0 || eq < firstOpen)) {
                    val lhs = seg.substring(0, eq)
                    val name = lhs.trim()
                    if (name.isNotEmpty() && name.none { it.isWhitespace() } && allow?.invoke(name) != true) {
                        val at = lhs.indexOf(name)
                        val ph = "\uE000${originals.size}\uE001"
                        originals.add(name)
                        out.append(lhs, 0, at).append(ph)
                        out.append(seg, eq, seg.length)
                        return
                    }
                }
                out.append(seg)
            }
            while (i < text.length) {
                when (text[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> if (depth > 0) depth--
                    ',', '，' -> if (depth == 0) {
                        flush(i)
                        out.append(text[i])
                        segStart = i + 1
                    }
                }
                i++
            }
            flush(text.length)
            return out.toString()
        }
    }
}
