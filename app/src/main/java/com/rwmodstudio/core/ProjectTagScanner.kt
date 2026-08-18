package com.rwmodstudio.core

import android.util.Log
import java.io.File

/**
 * 项目标签/资源/内存扫描器。
 * 扫描指定项目根目录下所有 INI 风格文件，收集标签、全局标签、资源、全局资源、内存五类信息。
 */
object ProjectTagScanner {

    private const val TAG = "ProjectTagScanner"

    /** 静态缓存：最后一次扫描结果，供补全系统读取 */
    @Volatile
    private var cachedInfo: ProjectTagInfo? = null

    /** 静态缓存对应的扫描根目录（用于按 root 判定缓存是否仍适用） */
    @Volatile
    private var scannedRoot: String? = null

    fun getCachedInfo(): ProjectTagInfo? = cachedInfo

    /** 当前缓存对应的扫描根目录（可能为 null） */
    fun getScannedRoot(): String? = scannedRoot

    private val INI_EXTENSIONS = setOf("ini", "template", "txt")
    private const val MAX_FILES = 5000
    private const val MAX_ITEMS_PER_CATEGORY = 2000

    data class TagReference(
        val file: File,
        val line: Int,
        val rawLine: String
    )

    data class ProjectTagInfo(
        val tags: Set<String>,
        val globalTags: Set<String>,
        /** 消息标签：项目里所有 sendMessageWithTags:/带标签发送消息: 行的取值（独立命名空间，供 新消息(需标签=) 等补全） */
        val messageTags: Set<String> = emptySet(),
        val resources: Set<String>,
        val globalResources: Set<String>,
        val memories: Set<String>,
        val unitNames: Set<String>,
        /** 每个值（不区分分类）对应的引用位置，用于跳转 */
        val references: Map<String, List<TagReference>>,
        /** 命名节名：节基名（turret/projectile/effect/action/hiddenAction/animation/decal/attachment/canBuild）→ 节名集合 */
        val sectionNames: Map<String, Set<String>> = emptyMap(),
        /** 内存变量 → 声明类型（@memory 名:类型 / defineUnitMemory: 类型 名），供 unit 型内存变量链式补全 */
        val memoryTypes: Map<String, String> = emptyMap(),
        /** 内存变量声明的全部 (名, 类型) 对偶，不按名字合并：同名不同类型各保留一条（读取单位内存 按名+型分别补全） */
        val memoryTypePairs: List<Pair<String, String>> = emptyList(),
        /** 全局变量（@global 变量名，项目级，${} 引用），与资源/内存同级收集 */
        val globalVariables: Set<String> = emptySet(),
        /** 局部变量（@define 变量名）按节归属：节名（小写原始名）→ 变量名集合，供 ${} 引用（仅当前节+继承链可见） */
        val sectionDefines: Map<String, Set<String>> = emptyMap(),
        /** 行动标签：行动/隐藏行动 节内的 tags: 取值（withActionTag 供 队列项目添加/取消、自身队列量 补全） */
        val actionTags: Set<String> = emptySet()
    ) {
        companion object {
            val EMPTY = ProjectTagInfo(emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptyMap(), emptyMap())
        }
    }

    private data class MutableInfo(
        val tags: MutableSet<String> = linkedSetOf(),
        val globalTags: MutableSet<String> = linkedSetOf(),
        val messageTags: MutableSet<String> = linkedSetOf(),
        val resources: MutableSet<String> = linkedSetOf(),
        val globalResources: MutableSet<String> = linkedSetOf(),
        val memories: MutableSet<String> = linkedSetOf(),
        val unitNames: MutableSet<String> = linkedSetOf(),
        val references: MutableMap<String, MutableList<TagReference>> = linkedMapOf(),
        val sectionNames: MutableMap<String, MutableSet<String>> = linkedMapOf(),
        val memoryTypes: MutableMap<String, String> = linkedMapOf(),
        val memoryTypePairs: MutableList<Pair<String, String>> = mutableListOf(),
        val globalVariables: MutableSet<String> = linkedSetOf(),
        val sectionDefines: MutableMap<String, MutableSet<String>> = linkedMapOf(),
        /** 行动标签：行动/隐藏行动 节内的 tags: 取值（与单位标签 info.tags 分离，不污染单位标签补全） */
        val actionTags: MutableSet<String> = linkedSetOf()
    )

    /**
     * 同步扫描项目目录。调用方应自行切到 IO 线程。
     */
    fun scan(root: File): ProjectTagInfo {
        if (!root.exists() || !root.isDirectory) return ProjectTagInfo.EMPTY

        val info = MutableInfo()
        var scannedFiles = 0
        try {
            root.walkTopDown()
                .onFail { _, _ -> }
                .filter { it.isFile && it.extension.lowercase() in INI_EXTENSIONS }
                .take(MAX_FILES)
                .forEach { file ->
                    scannedFiles++
                    scanFile(file, root, info)
                }
        } catch (_: Exception) {
            // 扫描异常时返回已收集部分
        }
        val result = ProjectTagInfo(
            tags = info.tags.sorted().toSet(),
            globalTags = info.globalTags.sorted().toSet(),
            messageTags = info.messageTags.sorted().toSet(),
            resources = info.resources.sorted().toSet(),
            globalResources = info.globalResources.sorted().toSet(),
            memories = info.memories.sorted().toSet(),
            unitNames = info.unitNames.sorted().toSet(),
            references = info.references.mapValues { it.value.sortedBy { ref -> ref.file.absolutePath + ref.line } },
            sectionNames = info.sectionNames.mapValues { it.value.sorted().toSet() },
            memoryTypes = info.memoryTypes.toMap(),
            memoryTypePairs = info.memoryTypePairs.sortedBy { it.first },
            globalVariables = info.globalVariables.sorted().toSet(),
            sectionDefines = info.sectionDefines.mapValues { it.value.sorted().toSet() },
            actionTags = info.actionTags.sorted().toSet()
        )
        Log.d(TAG, "Scanned $scannedFiles files: tags=${result.tags.size}, globalTags=${result.globalTags.size}, messageTags=${result.messageTags.size}, actionTags=${result.actionTags.size}, resources=${result.resources.size}, globalResources=${result.globalResources.size}, memories=${result.memories.size}, unitNames=${result.unitNames.size}, globalVariables=${result.globalVariables.size}")
        cachedInfo = result
        scannedRoot = root.absolutePath
        return result
    }

    /**
     * 按根目录缓存的扫描入口：同一 root 只扫一次（返回缓存），换 root 自动重扫。
     * 调用方应自行切到 IO 线程。
     */
    @Synchronized
    fun scanIfNeeded(root: File): ProjectTagInfo {
        val abs = root.absolutePath
        if (scannedRoot == abs && cachedInfo != null) return cachedInfo!!
        return scan(root)
    }

    /** 清空静态缓存（仅测试用，避免跨测试用例相互污染） */
    internal fun resetCacheForTests() {
        cachedInfo = null
        scannedRoot = null
    }

    /**
     * 扫描继承链合并后的行，提取资源/全局资源/内存三类符号。
     * 供 InheritanceResolver.resolveSymbols 使用；纯函数，不依赖项目扫描缓存。
     */
    fun scanChainLines(lines: List<String>): ProjectTagInfo {
        val resources = linkedSetOf<String>()
        val globalResources = linkedSetOf<String>()
        val memories = linkedSetOf<String>()
        val memoryTypes = linkedMapOf<String, String>()
        val memoryTypePairs = mutableListOf<Pair<String, String>>()
        val sectionNames = linkedMapOf<String, MutableSet<String>>()
        val globalVariables = linkedSetOf<String>()
        val sectionDefines = linkedMapOf<String, MutableSet<String>>()

        var currentSection = ""
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            genericSectionRegex.find(line)?.let { m ->
                currentSection = m.groupValues[1].trim().lowercase()
            }
            parseSection(line)?.let { (type, name) ->
                when (type) {
                    SectionType.RESOURCE -> resources.add(name)
                    SectionType.GLOBAL_RESOURCE -> globalResources.add(name)
                    else -> {}
                }
            }
            parseNamedSection(line)?.let { (base, name) ->
                sectionNames.getOrPut(base) { linkedSetOf() }.add(name)
            }
            parseMemory(line)?.let { memories.add(it) }
            parseMemoryType(line)?.let { (name, type) ->
                memoryTypes[name] = type
                memoryTypePairs.add(name to type)
            }
            parseDefineUnitMemoryLine(line)?.let { memories.addAll(it) }
            parseDefineUnitMemoryTypedLine(line)?.let { typed ->
                memoryTypes.putAll(typed)
                typed.forEach { (n, t) -> memoryTypePairs.add(n to t) }
            }
            defineRegex.matchEntire(line)?.let { m ->
                val name = m.groupValues[2].trim()
                if (name.isNotBlank()) {
                    if (m.groupValues[1].equals("global", ignoreCase = true)) {
                        globalVariables.add(name)
                    } else {
                        sectionDefines.getOrPut(currentSection) { linkedSetOf() }.add(name)
                    }
                }
            }
        }

        return ProjectTagInfo(
            tags = emptySet(),
            globalTags = emptySet(),
            resources = resources.sorted().toSet(),
            globalResources = globalResources.sorted().toSet(),
            memories = memories.sorted().toSet(),
            unitNames = emptySet(),
            references = emptyMap(),
            sectionNames = sectionNames.mapValues { it.value.sorted().toSet() },
            memoryTypes = memoryTypes.toMap(),
            memoryTypePairs = memoryTypePairs.sortedBy { it.first },
            globalVariables = globalVariables.sorted().toSet(),
            sectionDefines = sectionDefines.mapValues { it.value.sorted().toSet() }
        )
    }

    /**
     * 解析 defineUnitMemory:/定义单位内存: 声明中的变量名列表。
     * 形如 "boolean var1, float var2, unit[] var3"，取每项最后一个词作为变量名；
     * 支持全角逗号与行内 # 注释。
     */
    fun parseDefineUnitMemory(value: String): List<String> {
        val commentIdx = value.indexOf('#')
        val clean = if (commentIdx >= 0) value.substring(0, commentIdx) else value
        return clean.split(',', '，').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            trimmed.split(Regex("""\s+""")).lastOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    private fun scanFile(file: File, root: File, info: MutableInfo) {
        val relPath = try { file.relativeTo(root).path } catch (_: Exception) { file.name }
        // 先完整读取文件，第一遍收集 @define / @global 变量，第二遍解析引用
        val lines = try { file.readLines() } catch (_: Exception) { return }
        val defines = collectDefines(lines)

        var inCoreSection = false
        var inActionSection = false
        var currentSection = ""

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val lineNo = index + 1

            // 通用节头检测：[xxx] 或 [xxx_yyy]
            val sectionHeader = genericSectionRegex.find(line)
            if (sectionHeader != null) {
                val sectionName = sectionHeader.groupValues[1].trim().lowercase()
                currentSection = sectionName
                inCoreSection = sectionName == "core"
                // 行动标签范围：仅 行动/隐藏行动 节内的 tags 值（与编辑端 extractCurrentFileSymbols 一致）
                inActionSection = parseNamedSection(line)
                    ?.first?.let { it == "action" || it == "hiddenaction" } ?: false
                // 资源 / 全局资源节名解析（同时兼容英文 resource / globalResource）
                parseSection(line)?.let { (type, name) ->
                    when (type) {
                        SectionType.RESOURCE -> addLimited(info.resources, name, info.references, file, lineNo, rawLine)
                        SectionType.GLOBAL_RESOURCE -> addLimited(info.globalResources, name, info.references, file, lineNo, rawLine)
                        else -> {}
                    }
                }
                // 命名节（炮塔/抛射体/效果/行动/动画/贴花/附属/可建造等）节名收集，供引用名补全
                parseNamedSection(line)?.let { (base, name) ->
                    info.sectionNames.getOrPut(base) { linkedSetOf() }.add(name)
                }
                return@forEachIndexed
            }

            // @define / @global 变量收集：@global 项目级（${} 引用）、@define 按当前节归属（仅当前节+继承链可见）
            defineRegex.matchEntire(line)?.let { m ->
                val name = m.groupValues[2].trim()
                if (name.isNotBlank()) {
                    if (m.groupValues[1].equals("global", ignoreCase = true)) {
                        info.globalVariables.add(name)
                    } else {
                        info.sectionDefines.getOrPut(currentSection) { linkedSetOf() }.add(name)
                    }
                }
                return@forEachIndexed
            }

            // 键值对：tags / 临时标签添加 / 添加全局标签 / name（仅 core 节）
            parseKeyValue(line)?.let { (key, rawValue) ->
                val values = expandValue(rawValue, defines)
                when (key) {
                    TagKey.TAGS -> {
                        // 纯 tags: 按节归属：核心节→单位标签、行动/隐藏行动→行动标签、其他节丢弃。
                        // 与编辑端 extractCurrentFileSymbols 一致，避免行动节 tags 污染项目级单位标签补全。
                        if (inCoreSection) {
                            values.forEach { addLimited(info.tags, it, info.references, file, lineNo, rawLine) }
                        } else if (inActionSection) {
                            values.forEach { addLimited(info.actionTags, it, info.references, file, lineNo, rawLine) }
                        }
                    }
                    TagKey.TEMP_TAG_ADD -> {
                        // 临时标签添加：任意节都算单位标签（未限定范围，与编辑端一致）
                        values.forEach { addLimited(info.tags, it, info.references, file, lineNo, rawLine) }
                    }
                    TagKey.ADD_GLOBAL_TAG -> {
                        values.forEach { v ->
                            addLimited(info.globalTags, v, info.references, file, lineNo, rawLine)
                        }
                    }
                    TagKey.MESSAGE_TAG -> {
                        values.forEach { v ->
                            addLimited(info.messageTags, v, info.references, file, lineNo, rawLine)
                        }
                    }
                    TagKey.NAME -> {
                        if (inCoreSection) {
                            values.forEach { v ->
                                addLimited(info.unitNames, v, info.references, file, lineNo, rawLine)
                            }
                        }
                    }
                    else -> {}
                }
            }

            // @memory name:type
            parseMemory(line)?.let { memoryName ->
                addLimited(info.memories, memoryName, info.references, file, lineNo, rawLine)
            }
            parseMemoryType(line)?.let { (name, type) ->
                info.memoryTypes[name] = type
                info.memoryTypePairs.add(name to type)
            }
            // defineUnitMemory: 类型 名, ...
            parseDefineUnitMemoryLine(line)?.let { names ->
                names.forEach { name ->
                    addLimited(info.memories, name, info.references, file, lineNo, rawLine)
                }
            }
            parseDefineUnitMemoryTypedLine(line)?.let { typed ->
                typed.forEach { (name, type) ->
                    info.memoryTypes[name] = type
                    info.memoryTypePairs.add(name to type)
                }
            }
        }
    }

    /**
     * 收集文件内所有 @define / @global 变量定义。
     * 支持 @define name:value、@global name:value 以及 value 中的多值逗号分隔。
     */
    private fun collectDefines(lines: List<String>): Map<String, String> {
        val defines = mutableMapOf<String, String>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val match = defineRegex.matchEntire(line) ?: return@forEach
            val name = match.groupValues[2].trim()
            val value = match.groupValues[3].trim()
            if (name.isNotBlank()) {
                defines[name] = value
            }
        }
        return defines
    }

    /**
     * 把原始值按逗号拆分，并解析其中 ${xxx} 变量引用。
     * 若变量未找到，保留原始 ${xxx} 占位。
     */
    private fun expandValue(rawValue: String, defines: Map<String, String>): List<String> {
        return rawValue.split(",").flatMap { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@flatMap emptyList()
            val resolved = resolveVariables(trimmed, defines)
            resolved.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private fun resolveVariables(value: String, defines: Map<String, String>): String {
        if (!value.contains("\${")) return value
        return variableRefRegex.replace(value) { match ->
            val name = match.groupValues[1].trim()
            defines[name] ?: match.value
        }
    }

    private fun addLimited(
        set: MutableSet<String>,
        value: String,
        refs: MutableMap<String, MutableList<TagReference>>,
        file: File,
        line: Int,
        rawLine: String
    ) {
        if (set.size >= MAX_ITEMS_PER_CATEGORY && value !in set) return
        if (set.add(value)) {
            refs[value] = mutableListOf(TagReference(file, line, rawLine.trim()))
        } else {
            refs.getOrPut(value) { mutableListOf() }
                .add(TagReference(file, line, rawLine.trim()))
        }
    }

    private enum class SectionType { RESOURCE, GLOBAL_RESOURCE, OTHER }
    private enum class TagKey { TAGS, TEMP_TAG_ADD, ADD_GLOBAL_TAG, MESSAGE_TAG, NAME, OTHER }

    // 通用节头：[xxx] 或 [xxx_yyy]
    private val genericSectionRegex = Regex("""^\[([^\]]+)\]$""")
    // 节名：支持 [资源_xxx]、[resource_xxx]、[全局资源_xxx]、[globalResource_xxx]、[global_resource_xxx]
    private val sectionRegex = Regex("""^\[((?:全局资源|globalResource|global_resource|资源|resource))_([^\]]+)\]$""", RegexOption.IGNORE_CASE)
    // 键值对：tags / 临时标签添加 / 添加全局标签 / 带标签发送消息 / name（含英文、下划线、复数、队伍变体）
    private val kvRegex = Regex("""^(\s*(tags|临时标签添加|tempTagAdd|temp_tag_add|添加全局标签|addGlobalTag|add_global_tag|addGlobalTags|add_global_tags|addGlobalTeamTags|add_global_team_tags|带标签发送消息|sendMessageWithTags|name)\s*):\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val memoryRegex = Regex("""@memory\s+(\S+)\s*:""")
    // @memory name:type（类型可选，供 unit 型内存变量链式补全）
    private val memoryTypeRegex = Regex("""@memory\s+(\S+)\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)
    // @define / @global 变量定义：@define name:value、@global name:value
    private val defineRegex = Regex("""^@(define|global)\s+(\S+?)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    // ${xxx} 变量引用
    private val variableRefRegex = Regex("""\$\{([^}]+)\}""")

    private fun parseSection(line: String): Pair<SectionType, String>? {
        val match = sectionRegex.matchEntire(line) ?: return null
        val prefix = match.groupValues[1].trim().lowercase()
        val name = match.groupValues[2].trim()
        val type = when {
            prefix == "全局资源" || prefix == "globalresource" || prefix == "global_resource" -> SectionType.GLOBAL_RESOURCE
            prefix == "资源" || prefix == "resource" -> SectionType.RESOURCE
            else -> SectionType.OTHER
        }
        return type to name
    }

    /** 命名节：支持中文/英文节基名（炮塔|turret、抛射体|projectile、效果|effect、行动|action、隐藏行动|hiddenAction、动画|animation、贴花|decal、附属|attachment、可建造|canBuild） */
    private val namedSectionRegex = Regex(
        """^\[((?:炮塔|turret|抛射体|projectile|效果|effect|行动|action|隐藏行动|hiddenAction|hidden_action|动画|animation|贴花|decal|附属|attachment|可建造|canBuild|can_build))_([^\]]+)\]$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析命名节头，返回 (节基名小写, 节名)。
     * 如 [turret_mainGun] → ("turret", "mainGun")、[效果_爆炸] → ("effect", "爆炸")。
     */
    /** 供补全端（CompletionProvider 当前文件扫描）复用 */
    internal fun parseNamedSectionLine(line: String): Pair<String, String>? = parseNamedSection(line)

    private fun parseNamedSection(line: String): Pair<String, String>? {
        val match = namedSectionRegex.matchEntire(line) ?: return null
        val prefix = match.groupValues[1].trim().lowercase()
        val name = match.groupValues[2].trim()
        if (name.isBlank()) return null
        val base = when {
            prefix == "炮塔" || prefix == "turret" -> "turret"
            prefix == "抛射体" || prefix == "projectile" -> "projectile"
            prefix == "效果" || prefix == "effect" -> "effect"
            prefix == "行动" || prefix == "action" -> "action"
            prefix == "隐藏行动" || prefix == "hiddenaction" || prefix == "hidden_action" -> "hiddenaction"
            prefix == "动画" || prefix == "animation" -> "animation"
            prefix == "贴花" || prefix == "decal" -> "decal"
            prefix == "附属" || prefix == "attachment" -> "attachment"
            prefix == "可建造" || prefix == "canbuild" || prefix == "can_build" -> "canbuild"
            else -> return null
        }
        return base to name
    }

    private fun parseKeyValue(line: String): Pair<TagKey, String>? {
        val match = kvRegex.matchEntire(line) ?: return null
        val keyRaw = match.groupValues[2].trim().lowercase()
        val key = when (keyRaw) {
            "tags" -> TagKey.TAGS
            "临时标签添加", "temptagadd", "temp_tag_add" -> TagKey.TEMP_TAG_ADD
            "添加全局标签", "addglobaltag", "add_global_tag", "addglobaltags", "add_global_tags", "addglobalteamtags", "add_global_team_tags" -> TagKey.ADD_GLOBAL_TAG
            "带标签发送消息", "sendmessagewithtags" -> TagKey.MESSAGE_TAG
            "name" -> TagKey.NAME
            else -> TagKey.OTHER
        }
        return key to match.groupValues[3].trim()
    }

    /** 匹配 defineUnitMemory:/定义单位内存: 声明行 */
    private val defineUnitMemoryRegex = Regex("""^(?:defineUnitMemory|定义单位内存)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    private fun parseDefineUnitMemoryLine(line: String): List<String>? {
        val match = defineUnitMemoryRegex.matchEntire(line) ?: return null
        return parseDefineUnitMemory(match.groupValues[1])
    }

    /** 匹配 defineUnitMemory:/定义单位内存: 声明行，返回 变量名→类型 映射（"unit[] 目标" → 目标→unit[]） */
    private fun parseDefineUnitMemoryTypedLine(line: String): Map<String, String>? {
        val match = defineUnitMemoryRegex.matchEntire(line) ?: return null
        return parseDefineUnitMemoryTyped(match.groupValues[1])
    }

    private fun parseMemory(line: String): String? {
        val match = memoryRegex.find(line) ?: return null
        return match.groupValues[1].trim()
    }

    /** 解析 @memory name:type 行，返回 (变量名, 类型)；类型缺失返回 null */
    private fun parseMemoryType(line: String): Pair<String, String>? {
        val match = memoryTypeRegex.find(line) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    /**
     * 解析 defineUnitMemory:/定义单位内存: 声明中的 变量名→类型 映射。
     * 形如 "boolean var1, float var2, unit[] var3"，每项最后一个词为变量名，其余为类型；
     * 支持全角逗号与行内 # 注释；无类型（单 token）项忽略。
     */
    fun parseDefineUnitMemoryTyped(value: String): Map<String, String> {
        val commentIdx = value.indexOf('#')
        val clean = if (commentIdx >= 0) value.substring(0, commentIdx) else value
        return clean.split(',', '，').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val tokens = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size < 2) return@mapNotNull null
            tokens.last() to tokens.dropLast(1).joinToString(" ")
        }.toMap()
    }
}
