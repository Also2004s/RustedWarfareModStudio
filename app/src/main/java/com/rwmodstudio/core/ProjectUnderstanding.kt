package com.rwmodstudio.core

import com.rwmodstudio.util.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 项目理解：由 AI 归纳生成的新版 MOD 行为认知。
 *
 * 流程：扫描新版文件夹，对每个文件采集「文件路径 + 核心节关键信息（名称/标签/模板引用）+ 隐藏行动节」
 * （无隐藏行动的文件不采集），按信息量排序取最有代表性的文件，交给
 * [AiService.streamBuildUnderstanding] 由 AI 形成「理解」→ 缓存（签名 = 路径+大小 SHA-1）复用。
 * 后续生成更新报告时直接注入这份理解。
 *
 * 必须在 IO 线程调用（内部已切 [Dispatchers.IO]）。
 */
object ProjectUnderstanding {

    private const val TAG = "ProjectUnderstanding"

    private val INI_EXTENSIONS = setOf("ini", "template", "txt")
    private const val MAX_FILES = 5000
    /** 单个隐藏行动节最多展示的行数（行为行优先，超出丢弃属性行） */
    private const val MAX_LINES_PER_HIDDEN = 6
    /** 单文件详细内容字符上限 */
    private const val MAX_CHARS_PER_FILE = 4000
    /** 全量索引预算（路径 + 节名清单，架构地图） */
    private const val INDEX_BUDGET = 20_000
    /** 重点文件详细内容预算 */
    private const val DETAIL_BUDGET = 24_000
    /** 单行截断长度：超长条件行保留开头判断 */
    private const val MAX_LINE_LENGTH = 300

    private val sectionRegex = Regex("""^\s*\[([^\]]+)\]""")

    /** 行为逻辑行关键词：命中优先保留（条件/命令/标签等），纯数值属性行排后 */
    private val LOGIC_KEYWORDS = arrayOf(
        "条件", "同时", "标签", "命令", "建造", "添加", "移除", "发送", "设置", "空袭", "核弹",
        "巡逻", "攻击", "维修", "集合", "返回", "撤退", "救援", "检测", "规避", "寻找", "选择", "等待",
        "if", "while", "then", "send", "build", "set", "add", "remove", "tag", "order",
        "attack", "move", "retreat", "repair", "target", "pick", "choose", "wait", "follow"
    )

    /** 核心节关键信息关键词：仅名称/标签（单位标识，是其他单位检测的锚点）；复制与/模板仅是代码复用，与关系无关 */
    private val CORE_KEYWORDS = arrayOf(
        "name", "名称", "tags", "标签", "单位类型", "unittype"
    )

    /**
     * 获取项目理解：缓存命中直接返回；未命中则采集素材 → AI 生成 → 缓存。
     * @param newDir 新版（目标）文件夹
     * @param onPhase 阶段提示回调（IO 线程触发）
     * @param onChunk 流式增量回调（IO 线程触发，AI 生成理解时的实时输出）
     */
    suspend fun getOrBuild(
        newDir: File,
        onPhase: (String) -> Unit = {},
        onChunk: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val cacheHit = runCatching { readCache(newDir) }.getOrNull()
        if (cacheHit != null) return@withContext Result.success(cacheHit)
        runCatching {
            if (!newDir.exists() || !newDir.isDirectory) {
                throw IOException("无法读取新版文件夹")
            }
            onPhase("正在扫描新版项目文件")
            val material = scanMaterial(newDir)
            onPhase("AI 正在构建项目理解")
            val text = AiService.streamBuildUnderstanding(material, onPhase, onChunk)
                .getOrElse { throw it }
            runCatching { writeCache(newDir, text) }
            text
        }
    }

    /** 仅读取缓存的 AI 项目理解（未生成/签名不匹配返回 null），不会触发 AI 调用。 */
    fun getCached(newDir: File): String? = runCatching { readCache(newDir) }.getOrNull()

    /** 清除缓存（仅供手动刷新，内容变化由签名自动失效） */
    fun clearCache() {
        runCatching { RwmodPaths.aiUnderstandingCacheFile.delete() }
    }

    // ==================== 缓存 ====================

    private fun readCache(newDir: File): String? {
        val cacheFile = RwmodPaths.aiUnderstandingCacheFile
        if (!cacheFile.exists()) return null
        val obj = JSONObject(cacheFile.readText())
        if (obj.optString("signature") != signature(newDir)) return null
        if (obj.optString("dirPath") != newDir.absolutePath) return null
        return obj.optString("text").takeIf { it.isNotBlank() }
    }

    private fun writeCache(newDir: File, text: String) {
        val obj = JSONObject()
        obj.put("dirPath", newDir.absolutePath)
        obj.put("signature", signature(newDir))
        obj.put("text", text)
        RwmodPaths.aiUnderstandingCacheFile.atomicWriteText(obj.toString())
    }

    /** 计算文件夹签名：所有 ini/template/txt 的 相对路径|大小 拼接后 SHA-1 */
    private fun signature(dir: File): String {
        val files = collectFiles(dir)
        val sb = StringBuilder()
        files.forEach { f ->
            val rel = runCatching { f.relativeTo(dir).path }.getOrElse { f.name }
            sb.append(rel).append('|').append(f.length()).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun collectFiles(root: File): List<File> = try {
        root.walkTopDown()
            .onFail { _, _ -> }
            .filter { it.isFile && it.extension.lowercase() in INI_EXTENSIONS }
            .take(MAX_FILES)
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    // ==================== 素材采集 ====================

    private data class ParsedFile(
        val relPath: String,
        val coreLines: List<String>,
        val sections: List<Pair<String, List<String>>>,
        val chars: Int
    )

    /**
     * 采集素材：
     * 1) 全量索引——所有有隐藏行动文件的「路径 + 节名清单」（架构地图，路径即模块层级）；
     * 2) 重点详述——按隐藏行动节数降序取重点文件，展开 关键信息(名称/标签/模板引用) + 各节行为逻辑。
     * 隐藏行动为空的文件不采集。
     */
    private fun scanMaterial(newDir: File): String {
        val files = collectFiles(newDir)
        if (files.isEmpty()) return "（新版文件夹无可识别的 ini/template 文件）"

        val parsed = files.mapNotNull { parseFile(it, newDir) }
            .filter { it.sections.isNotEmpty() }
            .sortedWith(
                compareByDescending<ParsedFile> { it.sections.size }
                    .thenByDescending { it.chars }
            )
        if (parsed.isEmpty()) return "（新版文件夹中未识别到 [hiddenaction] 节）"

        // 全量索引：路径 + 节名清单
        val indexSb = StringBuilder()
        for (info in parsed) {
            val names = info.sections.map { if (it.first.isEmpty()) "（无名）" else it.first }
            val line = "## ${info.relPath}：${names.joinToString("、")}\n"
            if (indexSb.length + line.length > INDEX_BUDGET) break
            indexSb.append(line)
        }

        // 重点详述：按节数降序展开关键信息 + 各节内容（单文件配额）
        val detailSb = StringBuilder()
        for (info in parsed) {
            if (detailSb.length >= DETAIL_BUDGET) break
            val block = buildDetail(info)
            if (detailSb.isNotEmpty() && detailSb.length + block.length > DETAIL_BUDGET) break
            detailSb.append(block)
        }

        val sb = StringBuilder()
        sb.append("【全量索引】各文件及其隐藏行动节（节名即行为名，路径即模块层级，可用于理解文件间关系与整体架构）：\n")
        sb.append(indexSb)
        sb.append("\n【重点文件详细内容】\n")
        sb.append(detailSb)
        return sb.toString()
    }

    /** 生成单个文件的详细内容块（超单文件配额则截断节数） */
    private fun buildDetail(info: ParsedFile): String {
        val sb = StringBuilder()
        sb.append("## 文件：${info.relPath}\n")
        if (info.coreLines.isNotEmpty()) {
            sb.append("关键信息：${info.coreLines.joinToString("；")}\n")
        }
        for ((name, hLines) in info.sections) {
            if (sb.length >= MAX_CHARS_PER_FILE) break
            sb.append("[隐藏行动 ${if (name.isEmpty()) "（无名）" else name}]\n")
            hLines.forEach { sb.append("  $it\n") }
        }
        return sb.toString()
    }

    /** 解析单个文件：提取核心节关键信息 + 隐藏行动节（行为行优先） */
    private fun parseFile(file: File, root: File): ParsedFile? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val relPath = runCatching { file.relativeTo(root).path }.getOrElse { file.name }

        var sectionType = ""   // core / hiddenaction / other
        var hiddenName = ""
        var logicLines = mutableListOf<String>()
        var otherLines = mutableListOf<String>()
        val coreLines = mutableListOf<String>()
        val sections = mutableListOf<Pair<String, List<String>>>()

        fun flush() {
            if (sectionType == "hiddenaction" && (logicLines.isNotEmpty() || otherLines.isNotEmpty())) {
                sections.add(hiddenName to combineLines(logicLines, otherLines))
                logicLines = mutableListOf()
                otherLines = mutableListOf()
            }
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val m = sectionRegex.find(line)
            if (m != null) {
                flush()
                val content = m.groupValues[1].trim()
                sectionType = when {
                    isCoreSection(content) -> "core"
                    isHiddenAction(content) -> "hiddenaction"
                    else -> "other"
                }
                hiddenName = actionName(content)
                continue
            }
            when (sectionType) {
                "core" -> {
                    if (isImportantCoreLine(line)) coreLines.add(truncate(line))
                }
                "hiddenaction" -> {
                    if (isLogicLine(line)) logicLines.add(truncate(line)) else otherLines.add(truncate(line))
                }
            }
        }
        flush()

        if (sections.isEmpty()) return null
        val chars = sections.sumOf { sec -> sec.second.sumOf { it.length } } +
            coreLines.sumOf { it.length }
        return ParsedFile(relPath, coreLines.take(6), sections, chars)
    }

    /** 行为行优先：逻辑行在前，属性行在后，总长不超过单节上限 */
    private fun combineLines(logicLines: MutableList<String>, otherLines: MutableList<String>): List<String> {
        val combined = ArrayList<String>(logicLines.size + otherLines.size)
        combined.addAll(logicLines)
        combined.addAll(otherLines)
        return combined.take(MAX_LINES_PER_HIDDEN)
    }

    private fun truncate(line: String): String =
        if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) + "…" else line

    /** 行是否包含行为逻辑关键词（条件/命令/标签等） */
    private fun isLogicLine(line: String): Boolean =
        LOGIC_KEYWORDS.any { line.contains(it, ignoreCase = true) }

    /** 核心节关键信息行：名称/标签/模板引用等关系线索 */
    private fun isImportantCoreLine(line: String): Boolean =
        CORE_KEYWORDS.any { line.contains(it, ignoreCase = true) }

    /** 节内容是否为核心节：[core] 或 [核心] */
    private fun isCoreSection(content: String): Boolean {
        val first = content.trim().lowercase().split(Regex("[\\s_]"))[0]
        return first == "core" || first == "核心"
    }

    /** 节内容是否为隐藏行动节：支持 [hiddenaction xxx] / [隐藏行动 xxx] / [hiddenaction_xxx] / [隐藏行动_xxx] */
    private fun isHiddenAction(content: String): Boolean {
        val s = content.trim().lowercase()
        val first = s.split(Regex("[\\s_]"))[0]
        return first == "hiddenaction" || first == "隐藏行动"
    }

    /** 隐藏行动节名：[hiddenaction 巡逻] -> 巡逻；[隐藏行动_创建检测] -> 创建检测；无名字返回空串 */
    private fun actionName(content: String): String {
        val s = content.trim()
        val idx = s.indexOf(' ')
        return if (idx > 0) {
            s.substring(idx + 1).trim()
        } else {
            s.substringAfter('_', "").trim()
        }
    }
}
