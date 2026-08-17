package com.rwmodstudio.core

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "InheritanceResolver"

/** 一条带来源的 INI 行 */
data class SourcedLine(
    val content: String,          // 行内容
    val sourceName: String,       // 来源文件简称
    val sourceType: SourceType    // 来源类型
)

enum class SourceType {
    SELF,         // 当前文件自身
    COPY_FROM,    // 来自复制与:
    TEMPLATE,     // 来自 .template
    COPY_SECTION, // 来自 @copyFromSection 展开
}

/** 最终解析结果 */
data class ResolvedInheritance(
    val chainText: String,          // 继承链概览（头部）
    val mergedLines: List<SourcedLine>  // 合并后的行
)

/**
 * 文件继承链解析器。
 *
 * 规则：
 * 1. .template：从当前目录向上找第一个 all-units.template，找到即停
 * 2. .template 自身的 复制与: 递归解析
 * 3. 复制与: 递归解析（源文件的复制与也展开）
 * 4. 合并优先级：当前文件 > 复制与最后一个 > ... > 复制与第一个 > .template
 * 5. 复制与支持：单行逗号分隔、多行 """..."""、ROOT: 路径
 * 6. @copyFromSection / @copyFrom_skipThisSection 同文件内展开
 * 7. 禁止加载/dont_load 属性不会从其他来源继承，仅保留当前文件自身的定义
 * 8. 所有文件内容通过翻译缓存统一转为中文，节名精确匹配合并
 */
object InheritanceResolver {

    // === 禁止加载相关 ===

    /** 不可从其他来源继承的属性 key（翻译缓存统一为中文后匹配） */
    private val nonInheritableKeys = setOf("禁止加载", "dont_load")

    // === 符号解析内存 memo ===

    /** 继承链符号解析内存缓存：filePath -> (目标 mtime, 源签名, 结果)，编辑期避免重复读取/扫描 */
    private data class SymbolMemo(
        val targetMtime: Long,
        val sourceSig: String,
        val info: ProjectTagScanner.ProjectTagInfo?
    )
    private val symbolMemo = ConcurrentHashMap<String, SymbolMemo>()

    // === 公开接口 ===

    /**
     * 解析继承链并返回格式化后的中文文本。
     * 优先读缓存，未命中则计算并写入缓存。
     */
    fun resolveFormatted(filePath: String, projectRoot: String): String {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return "# 文件不存在\n"
        val rootDir = File(projectRoot.ifEmpty { file.parent ?: return "# 项目路径无效\n" })
        if (!rootDir.exists()) return "# 项目路径无效\n"

        // 构建链获取所有源文件
        val chain = buildChain(file, rootDir) { readFileContent(it) }
        val allSourceFiles = chain.map { it.file } + file

        // 检查继承链缓存
        if (InheritanceCache.isFresh(file, allSourceFiles)) {
            InheritanceCache.readCache(file)?.let { return it }
        }

        // 未命中或过期：重新计算
        val result = resolveInternal(file, rootDir, chain) { readFileContent(it) }
        val formatted = formatForDisplay(result)
        InheritanceCache.writeCache(file, allSourceFiles, formatted)
        return formatted
    }

    fun resolve(filePath: String, projectRoot: String): ResolvedInheritance? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val rootDir = File(projectRoot.ifEmpty { file.parent ?: return null })
        if (!rootDir.exists()) return null

        val chain = buildChain(file, rootDir) { readFileContent(it) }
        return resolveInternal(file, rootDir, chain) { readFileContent(it) }
    }

    /**
     * 解析继承链并提取资源/全局资源/内存符号（供补全使用）。
     * 结果 = 模板 ∪ 复制与 ∪ @copyFromSection ∪ 当前文件 的合并内容扫描；
     * 优先读继承链磁盘缓存 + 内存 memo。
     */
    fun resolveSymbols(filePath: String, projectRoot: String): ProjectTagScanner.ProjectTagInfo? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val rootDir = File(projectRoot.ifEmpty { file.parent ?: return null })
        if (!rootDir.exists()) return null

        val chain = buildChain(file, rootDir) { readFileContent(it) }
        val allSourceFiles = chain.map { it.file } + file

        val absPath = file.absolutePath
        val targetMtime = file.lastModified()
        val sourceSig = InheritanceCache.computeSourceSig(allSourceFiles)

        // 内存 memo 命中（编辑期磁盘文件未变化时避免重复读取/扫描）
        symbolMemo[absPath]?.let { memo ->
            if (memo.targetMtime == targetMtime && memo.sourceSig == sourceSig) return memo.info
        }

        // 磁盘继承链缓存命中：直接扫描缓存文本
        if (InheritanceCache.isFresh(file, allSourceFiles)) {
            InheritanceCache.readCache(file)?.let { cachedText ->
                val info = ProjectTagScanner.scanChainLines(cachedText.lines())
                symbolMemo[absPath] = SymbolMemo(targetMtime, sourceSig, info)
                return info
            }
        }

        // 未命中或过期：重新计算合并并写缓存
        val result = resolveInternal(file, rootDir, chain) { readFileContent(it) }
        InheritanceCache.writeCache(file, allSourceFiles, formatForDisplay(result))
        val info = ProjectTagScanner.scanChainLines(result.mergedLines.map { it.content })
        symbolMemo[absPath] = SymbolMemo(targetMtime, sourceSig, info)
        return info
    }

    /**
     * 纯函数版继承链解析：用外部内容提供者读取文件，供 JVM 单元测试使用。
     * 生产路径通过 readFileContent（翻译缓存）提供内容。
     */
    internal fun resolveMergedLines(filePath: String, projectRoot: String, contentOf: (File) -> String): List<SourcedLine>? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val rootDir = File(projectRoot.ifEmpty { file.parent ?: return null })
        if (!rootDir.exists()) return null
        val chain = buildChain(file, rootDir, contentOf)
        return resolveInternal(file, rootDir, chain, contentOf).mergedLines
    }

    private fun resolveInternal(file: File, rootDir: File, chain: List<ChainNode>, contentOf: (File) -> String): ResolvedInheritance {
        val targetName = fileRel(file, rootDir)
        val chainText = buildChainHeader(chain.map { it.relativePath to it.sourceType }, targetName)
        val merged = mergeChain(chain, file, rootDir, targetName, contentOf)
        return ResolvedInheritance(chainText = chainText, mergedLines = merged)
    }

    // === 数据结构 ===

    private data class ChainNode(
        val file: File,
        val relativePath: String,
        val sourceType: SourceType
    )

    // === 链构建 ===

    private fun fileRel(file: File, rootDir: File): String =
        try { file.canonicalPath.removePrefix(rootDir.canonicalPath + File.separator) } catch (_: Exception) { file.name }

    /** 构建继承链（模板 + 复制与递归），供各解析入口共用 */
    private fun buildChain(file: File, rootDir: File, contentOf: (File) -> String): List<ChainNode> {
        val chain = mutableListOf<ChainNode>()
        val visited = mutableSetOf<String>()

        findNearestTemplate(file, rootDir)?.let { templateFile ->
            if (tryAddToChain(templateFile, rootDir, chain, visited, SourceType.TEMPLATE)) {
                resolveCopyFrom(templateFile, rootDir, chain, visited, SourceType.TEMPLATE, contentOf)
            }
        }
        resolveCopyFrom(file, rootDir, chain, visited, SourceType.COPY_FROM, contentOf)
        return chain
    }

    /** 从当前文件目录向上找第一个 all-units.template */
    private fun findNearestTemplate(file: File, rootDir: File): File? {
        var current = file.parentFile
        while (current != null) {
            val t = File(current, "all-units.template")
            if (t.exists() && t.isFile) return t
            try { if (current.canonicalPath == rootDir.canonicalPath) break } catch (_: Exception) { break }
            current = current.parentFile
        }
        return null
    }

    private fun tryAddToChain(file: File, rootDir: File, chain: MutableList<ChainNode>, visited: MutableSet<String>, type: SourceType): Boolean {
        val can = try { file.canonicalPath } catch (_: Exception) { return false }
        if (can in visited) { Log.w(TAG, "循环引用: $can"); return false }

        visited.add(can)
        chain.add(ChainNode(file, fileRel(file, rootDir), type))
        return true
    }

    /** 递归解析文件的 复制与: 链 */
    private fun resolveCopyFrom(file: File, rootDir: File, chain: MutableList<ChainNode>, visited: MutableSet<String>, ownType: SourceType, contentOf: (File) -> String) {
        val text = contentOf(file)
        if (text.isEmpty()) return
        val sources = parseCopyFrom(text)
        for (source in sources) {
            val srcFile = resolveSourcePath(source, file, rootDir) ?: continue
            if (!tryAddToChain(srcFile, rootDir, chain, visited, SourceType.COPY_FROM)) continue
            // 递归：源文件自身的复制与
            resolveCopyFrom(srcFile, rootDir, chain, visited, SourceType.COPY_FROM, contentOf)
        }
    }

    /** 解析 复制与:/copyFrom:，返回源路径列表（按声明顺序，最后一个优先级最高） */
    private fun parseCopyFrom(text: String): List<String> {
        val result = mutableListOf<String>()

        // 模式1：单行逗号分隔  复制与:file1.ini, file2.ini
        val singleRe = Regex("""^(?:复制与|copyFrom)\s*:\s*(.+?)\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        for (m in singleRe.findAll(text)) {
            val val0 = m.groupValues[1].trim()
            if (val0.startsWith("\"\"\"")) continue  // 多行格式另外处理
            val0.split(",").forEach { p ->
                val t = p.trim()
                if (t.isNotEmpty()) result.add(t)
            }
        }

        // 模式2：多行 """ 格式
        val multiRe = Regex("(?:复制与|copyFrom)\\s*:\\s*\"\"\"\\s*", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val mm = multiRe.find(text)
        if (mm != null) {
            val start = mm.range.last + 1
            val end = text.indexOf("\"\"\"", start)
            if (end >= 0) {
                text.substring(start, end).split(",").forEach { p ->
                    val t = p.trim()
                    if (t.isNotEmpty()) result.add(t)
                }
            }
        }
        return result
    }

    private fun resolveSourcePath(path: String, currentFile: File, rootDir: File): File? {
        val f = when {
            path.startsWith("ROOT:") -> File(rootDir, path.removePrefix("ROOT:").trimStart('/', '\\'))
            else -> File(currentFile.parentFile ?: rootDir, path)
        }
        return if (f.exists() && f.isFile) f else null
    }

    // === 合并引擎 ===

    /** 读取文件内容，优先使用翻译缓存的中文版本；缓存过期时即时翻译并回写缓存 */
    private fun readFileContent(file: File): String {
        val cached = com.rwmodstudio.core.translation.SearchTranslationCache.readCacheSync(file)
        if (cached != null) return cached

        // 缓存未命中（文件被修改过）：读取原文并翻译为中文，同时回写翻译缓存
        val raw = try { file.readText() } catch (_: Exception) { return "" }
        val engine = com.rwmodstudio.core.translation.TranslationEngine.getInstance()
        if (!engine.isLoaded) return raw

        val translated = engine.translateToChinese(raw)
        com.rwmodstudio.core.translation.SearchTranslationCache.putCacheSync(file, translated)
        return translated
    }

    private fun mergeChain(chain: List<ChainNode>, targetFile: File, rootDir: File, targetName: String, contentOf: (File) -> String): List<SourcedLine> {
        // 从低到高合并：先 template 层，再复制与层，最后当前文件
        val allKeys = linkedMapOf<String, MutableList<SourcedLine>>()  // key -> 来源行

        // 构建全局节映射（跨链查找），高优先级覆盖同名节
        val globalSections = mutableMapOf<String, ParsedSection>()
        for (node in chain) {
            for (sec in parseSections(contentOf(node.file))) {
                globalSections[sec.name] = sec
            }
        }
        for (sec in parseSections(contentOf(targetFile))) {
            globalSections[sec.name] = sec
        }

        fun isInheritable(line: SourcedLine): Boolean {
            if (line.sourceType == SourceType.SELF) return true
            val key = extractKey(line.content)
            return key == null || key !in nonInheritableKeys
        }

        fun mergeNode(nodeFile: File, srcType: SourceType, srcName: String) {
            val text = contentOf(nodeFile)
            val sections = parseSections(text)

            for ((secName, secLines) in sections) {
                // 检查 @copyFrom_skipThisSection — 布尔标记，不从继承链获取该节代码
                val skipInheritance = checkSkipInheritance(secLines)

                // 展开 @copyFromSection — 跨文件节间复制
                val expanded = expandSectionCopy(secLines, globalSections, srcName, srcType)

                if (skipInheritance) {
                    // 清除该节所有更低优先级节点贡献的 key，只保留当前文件自己的代码
                    val prefix = "$secName::"
                    allKeys.keys.filter { it.startsWith(prefix) }.toSet().forEach { allKeys.remove(it) }
                }

                for (line in expanded) {
                    // 禁止加载过滤：非 SELF 来源的不继承
                    if (!isInheritable(line)) continue

                    val key = extractKey(line.content)
                    val fullKey = if (key != null) "$secName::$key" else "$secName::__${line.content}"
                    allKeys[fullKey] = mutableListOf(line)
                }
            }
        }

        // 按优先级从低到高合并
        for (node in chain) {
            val label = when (node.sourceType) {
                SourceType.TEMPLATE -> node.relativePath + " [模板]"
                SourceType.COPY_FROM -> node.relativePath
                SourceType.SELF -> node.relativePath
                SourceType.COPY_SECTION -> node.relativePath
            }
            mergeNode(node.file, node.sourceType, label)
        }
        // 当前文件自身（最高优先级）
        mergeNode(targetFile, SourceType.SELF, targetName)

        return flattenMerged(allKeys)
    }

    // === 节解析 ===

    private data class ParsedSection(val name: String, val lines: List<String>)

    private fun parseSections(text: String): List<ParsedSection> {
        val result = mutableListOf<ParsedSection>()
        val allLines = text.lines()
        var curName = ""
        var curLines = mutableListOf<String>()

        for (line in allLines) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]") && !t.startsWith("[[")) {
                result.add(ParsedSection(curName, curLines.toList()))
                curName = t.removeSurrounding("[", "]").trim()
                curLines = mutableListOf()
            } else {
                curLines.add(line)
            }
        }
        result.add(ParsedSection(curName, curLines.toList()))
        return result
    }

    /** 检查节内是否有 @copyFrom_skipThisSection / 复制但跳过节（非 false 即视为 true） */
    private fun checkSkipInheritance(secLines: List<String>): Boolean {
        for (line in secLines) {
            val t = line.trim()
            if (t.startsWith("@copyFrom_skipThisSection") || t.startsWith("复制但跳过节")) {
                val value = t.removePrefix("@copyFrom_skipThisSection").removePrefix("复制但跳过节").trim().removePrefix(":")
                return !value.equals("false", ignoreCase = true)
            }
        }
        return false
    }

    /** 展开节内的 @copyFromSection / 复制节（跨文件节间复制，当前节的 key 覆盖来源节的同名 key） */
    private fun expandSectionCopy(
        secLines: List<String>, globalSections: Map<String, ParsedSection>,
        srcName: String, srcType: SourceType
    ): List<SourcedLine> {
        var copyFromSection: String? = null
        val ownLines = mutableListOf<String>()

        for (line in secLines) {
            val t = line.trim()
            when {
                t.startsWith("@copyFromSection") -> {
                    copyFromSection = t.removePrefix("@copyFromSection").trim().removePrefix(":")
                }
                t.startsWith("复制节") -> {
                    copyFromSection = t.removePrefix("复制节").trim().removePrefix(":")
                }
                t.startsWith("@copyFrom_skipThisSection") || t.startsWith("复制但跳过节") -> { /* 由 checkSkipInheritance 单独处理 */ }
                else -> ownLines.add(line)
            }
        }

        if (copyFromSection.isNullOrEmpty()) {
            return ownLines.map { SourcedLine(it, srcName, srcType) }
        }

        val sourceSection = globalSections[copyFromSection]
        val result = mutableListOf<SourcedLine>()

        for (l in ownLines) {
            result.add(SourcedLine(l, srcName, srcType))
        }

        if (sourceSection != null) {
            val ownKeys = ownLines.mapNotNull { extractKey(it) }.toSet()
            val copyLabel = "来自 @copyFromSection $copyFromSection"
            for (l in sourceSection.lines) {
                val lt = l.trim()
                // 跳过指令行（@ 开头或是复制节/复制但跳过节元数据，不复制）
                if (lt.startsWith("@") || lt.startsWith("复制节") || lt.startsWith("复制但跳过节")) continue
                // 当前节已有的 key 覆盖来源节
                if (extractKey(l) in ownKeys) continue
                result.add(SourcedLine(l, copyLabel, SourceType.COPY_SECTION))
            }
        }

        return result
    }

    private fun extractKey(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("@")
            || t.startsWith("复制节") || t.startsWith("复制但跳过节")) return null
        val idx = t.indexOf(':')
        return if (idx > 0) t.substring(0, idx).trim() else null
    }

    // === 展平输出 ===

    private fun flattenMerged(allKeys: Map<String, MutableList<SourcedLine>>): List<SourcedLine> {
        data class SectionGroup(val name: String, val lines: MutableList<SourcedLine>)
        val groups = linkedMapOf<String, SectionGroup>()
        val keyOrder = allKeys.entries.toList()

        for ((fullKey, lines) in keyOrder) {
            val parts = fullKey.split("::", limit = 2)
            val secName = if (parts.size == 2) parts[0] else ""
            if (secName !in groups) {
                groups[secName] = SectionGroup(secName, mutableListOf())
            }
            groups[secName]!!.lines.addAll(lines)
        }

        val result = mutableListOf<SourcedLine>()
        for ((secName, group) in groups) {
            if (secName.isNotEmpty()) {
                result.add(SourcedLine("[$secName]", "", SourceType.SELF))
            }
            result.addAll(group.lines)
        }
        return result
    }

    // === 链头部文本 ===

    private fun buildChainHeader(chain: List<Pair<String, SourceType>>, targetName: String): String {
        val sb = StringBuilder()
        sb.appendLine("# ====== 继承链 (优先级 从下到上) ======")
        for ((i, entry) in chain.withIndex()) {
            val (path, type) = entry
            val label = when (type) {
                SourceType.TEMPLATE -> " [模板]"
                else -> ""
            }
            sb.appendLine("# [${i + 1}] $path$label")
        }
        sb.appendLine("# [${chain.size + 1}] $targetName ← 当前文件")
        sb.appendLine("# ===================================")
        return sb.toString()
    }

    // === 公开格式化 ===

    fun formatForDisplay(result: ResolvedInheritance): String {
        val sb = StringBuilder()
        sb.appendLine(result.chainText)
        sb.appendLine()

        var lastSource = ""

        for (line in result.mergedLines) {
            val t = line.content.trim()

            // 节头
            if (t.startsWith("[") && t.endsWith("]")) {
                lastSource = ""
                sb.appendLine()
                sb.appendLine(t)
                continue
            }

            // 来源标注（仅在来源变化时输出）
            if (line.sourceType != SourceType.SELF && line.sourceName.isNotEmpty() && line.sourceName != lastSource) {
                lastSource = line.sourceName
                sb.appendLine("# [来源: $lastSource]")
            }

            sb.appendLine(line.content)
        }

        return sb.toString()
    }
}
