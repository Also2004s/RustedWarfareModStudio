package com.rwmodstudio.core

import android.content.Context
import com.rwmodstudio.util.atomicWriteText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 智能助理（项目助理）的项目上下文与待办维护：
 * - 组装「项目 diff + 待办」上下文文本，注入 AI 系统提示词；
 * - 解析 AI 回复中的 [[todo]] 待办操作协议块并应用到 [TodoManager]；
 * - 聊天记录持久化（RWmod/cache/ai/assistant_chat_<md5(project)>.json）。
 */
object ProjectAssistant {

    private const val TAG = "ProjectAssistant"

    /** diff 上下文：参与的文件数量上限 */
    private const val MAX_FILES = 10
    /** 单个文件展示的差异行上限 */
    private const val MAX_DIFF_LINES_PER_FILE = 10
    /** 聊天记录保留条数上限 */
    private const val MAX_CHAT_MESSAGES = 30

    /** AI 待办操作（kind ∈ add / complete / delete / update） */
    data class TodoOp(
        val kind: String,
        val title: String,
        val newTitle: String? = null,
        val priority: Int = 0,
        val note: String = "",
        val done: Boolean? = null
    )

    // ==================== 项目上下文 ====================

    /** diff 上下文 TTL：短时间内多次发送复用，避免每次重跑大文件 LCS */
    private const val DIFF_TTL_MS = 3000L
    private data class DiffCacheEntry(val at: Long, val text: String)
    private val diffCache = java.util.concurrent.ConcurrentHashMap<String, DiffCacheEntry>()

    /**
     * 构建项目 diff 摘要：对 projectRoot（为空则全部）内近期保存的文件，
     * 取「最早保存前 → 最近保存后」做行级 diff，生成供 AI 阅读的文本。
     *
     * @param includeLines 是否包含具体差异行；false 时只给「文件 + 增减行数」摘要（普通对话用，省 token 提速）。
     * 带 3 秒 TTL 缓存（区分 includeLines），防止每次发送重复做昂贵的大文件 LCS。
     */
    fun buildDiffContext(
        context: Context,
        projectRoot: String,
        recentFiles: List<String>,
        includeLines: Boolean = true
    ): String {
        val now = System.currentTimeMillis()
        val key = projectRoot.ifBlank { "__global__" } + "|lines=$includeLines"
        diffCache[key]?.let { if (now - it.at < DIFF_TTL_MS) return it.text }
        val text = buildDiffContextInner(context, projectRoot, recentFiles, includeLines)
        diffCache[key] = DiffCacheEntry(now, text)
        return text
    }

    private fun buildDiffContextInner(
        context: Context,
        projectRoot: String,
        recentFiles: List<String>,
        includeLines: Boolean
    ): String {
        val rootPrefix = if (projectRoot.isNotBlank()) {
            runCatching { File(projectRoot).canonicalPath + File.separator }.getOrDefault("")
        } else ""
        val files = recentFiles.filter { path ->
            rootPrefix.isEmpty() ||
                runCatching { File(path).canonicalPath.startsWith(rootPrefix) }.getOrDefault(false)
        }.take(MAX_FILES)
        if (files.isEmpty()) return "（无近期修改记录）"

        val sb = StringBuilder()
        sb.append("【近期修改 diff】（共 ${files.size} 个文件）\n")
        for (path in files) {
            val history = SaveHistoryManager.getHistoryForFile(context, path)
            if (history.isEmpty()) continue
            val before = history.last().beforeContent
            val after = history.first().afterContent
            val ops = computeLineDiff(before.lines(), after.lines())
            val added = ops.count { it.type == '+' }
            val removed = ops.count { it.type == '-' }
            sb.append("\n文件：${displayPath(path, projectRoot)}（+$added / -$removed）\n")
            if (!includeLines) continue
            val shown = ops.filter { it.type != ' ' }.take(MAX_DIFF_LINES_PER_FILE)
            shown.forEach { op ->
                val sign = if (op.type == '+') "+" else "-"
                sb.append("$sign ${op.text}\n")
            }
            if (added + removed > MAX_DIFF_LINES_PER_FILE) {
                sb.append("...（其余 ${added + removed - shown.size} 行差异已省略）\n")
            }
        }
        return sb.toString()
    }

    /** 待办上下文：当前项目的待办列表（最多注入前 20 项，控制输入 token） */
    fun buildTodosText(projectPath: String): String {
        val todos = TodoManager.load(projectPath)
        if (todos.isEmpty()) return "（当前项目暂无待办）"
        val shown = todos.take(20)
        val sb = StringBuilder()
        sb.append("【当前待办】（共 ${todos.size} 项，列出前 ${shown.size} 项）\n")
        shown.forEachIndexed { index, t ->
            val mark = if (t.done) "[x]" else "[ ]"
            val priority = when (t.priority) {
                1 -> "重要"
                2 -> "紧急"
                else -> "普通"
            }
            sb.append("${index + 1}. $mark ${t.title}（$priority）")
            if (t.note.isNotBlank()) sb.append(" 备注：${t.note}")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 读取项目根目录的 mod-info.txt 作为【模组目标】注入助理上下文。
     * 文件不存在/为空返回 null；内容限 1500 字符（超出截断）。须在 IO 线程调用。
     */
    fun readModInfo(projectRoot: String): String? {
        if (projectRoot.isBlank()) return null
        val f = File(projectRoot, "mod-info.txt")
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) null else text.take(1500)
        }.getOrNull()
    }

    private fun displayPath(path: String, projectRoot: String): String {
        if (projectRoot.isNotBlank()) {
            runCatching {
                val root = File(projectRoot).canonicalPath
                val p = File(path).canonicalPath
                if (p.startsWith(root + File.separator)) {
                    return p.removePrefix(root + File.separator)
                }
            }
        }
        return path.substringAfterLast(File.separatorChar)
    }

    // ==================== 项目文件索引 ====================

    private const val MAX_INDEX_FILES = 25
    private const val MAX_SECTIONS_PER_FILE = 6
    private const val INDEX_CHAR_BUDGET = 5_000

    private val indexRegex = Regex("""^\s*\[([^\]]+)\]""")

    // 文件索引缓存（按项目 + TTL）：TTL 内连签名遍历都省；超时后签名没变也只复用文本
    private const val INDEX_TTL_MS = 3000L
    private data class IndexEntry(val at: Long, val sig: String, val text: String)
    private val fileIndexCache = java.util.concurrent.ConcurrentHashMap<String, IndexEntry>()

    private fun projectIndexFiles(root: File): List<File> = try {
        root.walkTopDown()
            .onFail { _, _ -> }
            .filter { it.isFile && (it.extension.lowercase() == "ini" || it.extension.lowercase() == "template") }
            .take(MAX_INDEX_FILES)
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 索引签名：相对路径 + 大小 + 最后修改时间，任一变化即失效 */
    private fun indexSignature(root: File): String {
        val sb = StringBuilder()
        for (f in projectIndexFiles(root)) {
            val rel = runCatching { f.relativeTo(root).path }.getOrElse { f.name }
            sb.append(rel).append('|').append(f.length()).append('|').append(f.lastModified()).append('\n')
        }
        return md5(sb.toString())
    }

    /**
     * 构建项目文件结构索引：文件相对路径 → 节名清单（[xxx]）。
     * 供 AI 定位「待办相关文件」并给出修改指引；带按项目 + 3 秒 TTL 的内存缓存。
     */
    fun buildProjectFileIndex(projectRoot: String): String {
        if (projectRoot.isBlank()) return "（未打开项目）"
        val root = File(projectRoot)
        if (!root.isDirectory) return "（项目目录不可读）"
        val now = System.currentTimeMillis()
        fileIndexCache[projectRoot]?.let { e ->
            if (now - e.at < INDEX_TTL_MS) return e.text
            // 超 TTL：重算签名，没变则复用文本
            if (indexSignature(root) == e.sig) {
                fileIndexCache[projectRoot] = IndexEntry(now, e.sig, e.text)
                return e.text
            }
        }

        val sig = indexSignature(root)
        val sb = StringBuilder()
        sb.append("【项目文件结构】（文件 → 节名清单，用于定位待办相关文件）\n")
        for (f in projectIndexFiles(root)) {
            val rel = runCatching { f.relativeTo(root).path }.getOrElse { f.name }
            // 只读前 400 行找节头，控制扫描开销
            val sections = runCatching {
                val names = mutableListOf<String>()
                f.useLines { seq ->
                    seq.take(400).forEach { raw ->
                        val m = indexRegex.find(raw)
                        if (m != null) {
                            names.add(m.groupValues[1].trim())
                            if (names.size >= MAX_SECTIONS_PER_FILE) return@forEach
                        }
                    }
                }
                names
            }.getOrDefault(emptyList())
            val line = if (sections.isEmpty()) "## $rel\n" else "## $rel：${sections.joinToString("、")}\n"
            if (sb.length + line.length > INDEX_CHAR_BUDGET) break
            sb.append(line)
        }
        val text = sb.toString()
        fileIndexCache[projectRoot] = IndexEntry(now, sig, text)
        return text
    }

    // ==================== 项目理解（会话级缓存） ====================

    // 会话级项目理解缓存：ProjectUnderstanding 磁盘缓存签名含文件大小，任何保存都会失效。
    // 若每次发送都重新生成会非常慢，故在会话内缓存一份，仅手动「刷新上下文」时失效。
    private val understandingSessionCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 获取项目理解：会话内已生成直接复用；否则读磁盘缓存（最新则命中）；都没有才调用 AI 生成。
     * @param onPhase 生成阶段的进度回调（IO 线程触发）
     */
    suspend fun getOrBuildUnderstanding(projectRoot: String, onPhase: (String) -> Unit): String? {
        if (projectRoot.isBlank()) return null
        understandingSessionCache[projectRoot]?.let { return it }
        val disk = ProjectUnderstanding.getCached(File(projectRoot))
        if (!disk.isNullOrBlank()) {
            understandingSessionCache[projectRoot] = disk
            return disk
        }
        val built = ProjectUnderstanding.getOrBuild(File(projectRoot), onPhase = onPhase, onChunk = {}).getOrNull()
        if (!built.isNullOrBlank()) understandingSessionCache[projectRoot] = built
        return built
    }

    /** 使会话级项目理解失效（下次发送重新同步磁盘缓存/重新生成） */
    fun invalidateUnderstanding(projectRoot: String) {
        understandingSessionCache.remove(projectRoot)
    }

    // ==================== 待办操作协议 ====================

    /** 从 AI 回复中解析 [[todo]]{json}[[/todo]] 块 */
    fun parseTodoOps(reply: String): List<TodoOp> {
        val start = reply.indexOf("[[todo]]")
        if (start < 0) return emptyList()
        val end = reply.indexOf("[[/todo]]", start)
        if (end < 0) return emptyList()
        val jsonStr = reply.substring(start + "[[todo]]".length, end).trim()
        return runCatching {
            val arr = JSONObject(jsonStr).optJSONArray("ops") ?: return emptyList()
            val ops = mutableListOf<TodoOp>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.optString("op", "")
                val title = o.optString("title", "").trim()
                if (kind.isEmpty() || title.isEmpty()) continue
                ops.add(
                    TodoOp(
                        kind = kind,
                        title = title,
                        newTitle = o.optString("newTitle", "").takeIf { it.isNotBlank() },
                        priority = o.optInt("priority", 0).coerceIn(0, 2),
                        note = o.optString("note", ""),
                        done = if (o.has("done")) o.optBoolean("done", false) else null
                    )
                )
            }
            ops
        }.getOrDefault(emptyList())
    }

    /**
     * 应用待办操作（按标题匹配）。返回中文结果描述，如「已新增 2 项、已完成 1 项」；
     * 无项目或无操作时返回空串。
     */
    fun applyTodoOps(projectPath: String, ops: List<TodoOp>): String {
        if (projectPath.isBlank() || ops.isEmpty()) return ""
        var added = 0
        var completed = 0
        var deleted = 0
        var updated = 0
        for (op in ops) {
            when (op.kind) {
                "add" -> {
                    if (TodoManager.add(projectPath, op.title, op.priority, op.note)) added++
                }
                "complete" -> {
                    val item = TodoManager.load(projectPath).firstOrNull { it.title == op.title && !it.done }
                    if (item != null && TodoManager.update(projectPath, item.copy(done = true))) completed++
                }
                "delete" -> {
                    val item = TodoManager.load(projectPath).firstOrNull { it.title == op.title }
                    if (item != null && TodoManager.delete(projectPath, item.id)) deleted++
                }
                "update" -> {
                    val item = TodoManager.load(projectPath).firstOrNull { it.title == op.title }
                    if (item != null) {
                        val changed = item.copy(
                            title = op.newTitle ?: item.title,
                            done = op.done ?: item.done
                        )
                        if (TodoManager.update(projectPath, changed)) updated++
                    }
                }
            }
        }
        val parts = mutableListOf<String>()
        if (added > 0) parts.add("新增 $added 项")
        if (completed > 0) parts.add("完成 $completed 项")
        if (deleted > 0) parts.add("删除 $deleted 项")
        if (updated > 0) parts.add("更新 $updated 项")
        return parts.joinToString("、")
    }

    /** 把回复中的 [[todo]] 块从展示文本中剔除（UI 气泡显示用） */
    fun stripTodoBlock(reply: String): String {
        val start = reply.indexOf("[[todo]]")
        if (start < 0) return reply
        val end = reply.indexOf("[[/todo]]", start)
        if (end < 0) return reply.substring(0, start).trim()
        return (reply.substring(0, start) + reply.substring(end + "[[/todo]]".length)).trim()
    }

    // ==================== 文件引用协议 ====================

    /** 解析回复中的 [[file]]相对路径[[/file]] 块，返回相对路径列表 */
    fun parseFileRefs(reply: String): List<String> {
        val refs = mutableListOf<String>()
        var idx = 0
        while (true) {
            val start = reply.indexOf("[[file]]", idx)
            if (start < 0) break
            val end = reply.indexOf("[[/file]]", start)
            if (end < 0) break
            val rel = reply.substring(start + "[[file]]".length, end).trim()
            if (rel.isNotEmpty()) refs.add(rel)
            idx = end + "[[/file]]".length
        }
        return refs
    }

    /** 把回复中的 [[file]] 块剔除，供气泡展示纯文本 */
    fun stripFileRefs(reply: String): String {
        var text = reply
        while (true) {
            val start = text.indexOf("[[file]]")
            if (start < 0) break
            val end = text.indexOf("[[/file]]", start)
            if (end < 0) break
            text = text.removeRange(start, end + "[[/file]]".length)
        }
        return text.trim()
    }

    /**
     * 把相对路径解析为项目内的真实文件（防目录穿越 + 存在性校验）。
     * 不合法返回 null。
     */
    fun resolveProjectFile(projectRoot: String, relPath: String): File? {
        if (projectRoot.isBlank() || relPath.isBlank()) return null
        return runCatching {
            val root = File(projectRoot).canonicalPath
            val f = File(projectRoot, relPath).canonicalFile
            if (f.absolutePath.startsWith(root + File.separator) && f.isFile) f else null
        }.getOrNull()
    }

    // ==================== 聊天记录持久化 ====================

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun chatFile(projectPath: String): File =
        File(RwmodPaths.aiCacheDir, "assistant_chat_${md5(projectPath.ifBlank { "__global__" })}.json")

    fun loadChat(projectPath: String): List<AiService.ChatMessage> {
        val file = chatFile(projectPath)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("messages") ?: return emptyList()
            val list = mutableListOf<AiService.ChatMessage>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AiService.ChatMessage(o.optString("role", "user"), o.optString("content", "")))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveChat(projectPath: String, messages: List<AiService.ChatMessage>) {
        val file = chatFile(projectPath)
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            messages.takeLast(MAX_CHAT_MESSAGES).forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            file.atomicWriteText(JSONObject().apply { put("messages", arr) }.toString(2), Charsets.UTF_8)
        }.onFailure { e ->
            android.util.Log.e(TAG, "saveChat failed", e)
        }
    }
}
