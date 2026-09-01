package com.rwmodstudio.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 助手服务：调用 OpenAI 兼容的 chat/completions 接口。
 *
 * 两种取回方式：
 * - **流式（默认，[SettingsManager.aiStreaming]）**：SSE 增量读取，边生成边回调 [onPartial]，
 *   首条建议秒出；若模型/网关不支持流式（首 token 前失败，或直接返回整段 JSON），
 *   自动降级为非流式，不会卡住。
 * - **非流式**：一次性取回全文，兼容所有模型，但需等整段生成完毕。
 *
 * 作用域：版本对比后的「项目理解」归纳与「更新报告」生成；编辑器内的「AI 建议」。
 */
object AiService {

    private const val TAG = "AiService"

    /** 连接超时 */
    private const val CONNECT_TIMEOUT_MS = 15_000
    /** 读取超时（单次 read；流式下为相邻 chunk 的最大间隔） */
    private const val READ_TIMEOUT_MS = 90_000
    /** 流式增量回调的最小间隔，避免每个 token 都触发一次 UI 重组 */
    private const val PARTIAL_EMIT_INTERVAL_MS = 100L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
        val temperature: Double = 0.2
    )

    /** 带 max_tokens 的请求体：仅在需要限制输出长度时使用（部分模型不认该字段，故与上面分开） */
    @Serializable
    private data class ChatRequestLimited(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
        val temperature: Double = 0.2,
        val max_tokens: Int
    )

    /** 按是否限制输出长度编码请求体 */
    private fun encodeRequest(model: String, messages: List<ChatMessage>, stream: Boolean): String {
        val limit = SettingsManager.aiMaxOutputTokens
        return if (limit > 0) {
            json.encodeToString(ChatRequestLimited.serializer(), ChatRequestLimited(model, messages, stream, 0.2, limit))
        } else {
            json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages, stream))
        }
    }

    @Serializable
    data class AiError(val message: String)

    /** 单个文件 diff 展示的最大行数（避免 prompt 过长） */
    private const val MAX_DIFF_LINES_PER_FILE = 40
    /** 参与报告的共同文件最大数量 */
    private const val MAX_COMMON_FILES_IN_PROMPT = 30

    /**
     * 构建「项目理解」：把新版文件夹的隐藏行动素材交给 AI 归纳。
     * 结果由 [ProjectUnderstanding] 缓存复用，后续生成更新报告时直接注入。
     *
     * @param material 素材：各文件的隐藏行动节原始内容
     * @param onPhase 阶段提示回调（IO 线程触发）
     * @param onChunk 流式增量回调（IO 线程触发）
     */
    suspend fun streamBuildUnderstanding(
        material: String,
        onPhase: (String) -> Unit,
        onChunk: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val (endpoint, model, apiKey) = resolveConfig()
            val messages = listOf(
                ChatMessage("system", UNDERSTANDING_PROMPT),
                ChatMessage("user", material)
            )
            onPhase("正在连接 AI 服务")
            val result = postCompletion(
                endpoint = endpoint, model = model, apiKey = apiKey,
                messages = messages,
                generatingPhase = "AI 正在构建项目理解",
                onPhase = onPhase
            )
            Log.d(TAG, "understanding ok: chars=${result.length}, costMs=${System.currentTimeMillis() - startedAt}")
            result
        }.onFailure { e ->
            Log.e(TAG, "understanding failed: ${e.message}, costMs=${System.currentTimeMillis() - startedAt}")
        }
    }

    private const val UNDERSTANDING_PROMPT =
        "你是《铁锈战争》(Rusted Warfare) MOD 的资深架构师。\n" +
        "以下是新版 MOD 的素材：全量索引（各文件路径 + 隐藏行动节名）+ 重点文件详述（节内行为逻辑）。\n" +
        "重要认知：本 MOD 单位/系统之间的关系**不是**靠代码复制（复制与）链接的，而是通过「标签 + 条件」驱动：\n" +
        "- 单位在满足某条件时添加/删除标签、发送带标签消息、产生单位、转换形态；\n" +
        "- 其他单位通过 需要条件 检测这些标签（如 需标签=、有全局标签、自身有标签、队伍中此单位数量）在相应条件下触发行为。\n" +
        "请形成对这个 MOD 的「项目理解」。注意：这是理解，不是复述。\n" +
        "禁止：逐文件复述素材、罗列每个隐藏行动在做什么、输出「文件 xx 是干什么的」这类流水账。\n" +
        "请重点输出：\n" +
        "1. 标签与条件网络：梳理核心标签由谁产生、被谁消费，条件如何串联成行为链（例如：区块单位打上区域标志标签 → 中控检测到后集结部队 → 部队检测到进攻标签开战）；\n" +
        "2. 指挥与调度体系：是否有中央指挥/调度机制（中控/指挥核心作为集结点），如何通过区域标志标签驱动不同区域的交互效果，附件/附属单位如何配合；\n" +
        "3. 行为体系如何组织：隐藏行动如何命名与分层（全局调度 / 单位个体 / 兵种配合），行为之间如何衔接与切换；\n" +
        "4. 文件间协作结构：哪些文件构成某个系统（指挥、建造、防御、进攻等）的行为链路，系统之间依赖哪些标签契约；\n" +
        "5. 整体行为模式：这套 AI 在不同局面下的决策倾向（进攻 / 防守 / 经济 / 支援 / 撤退如何权衡，兵种如何协同）。\n" +
        "要求：推断必须建立在素材之上，允许合理的架构推断；素材没有依据的部分不要编造细节；全中文；" +
        "全文控制在 800-1500 字；仅输出项目理解正文，用自然的段落与少量小标题，不要用清单式逐条复述。"

    /**
     * 生成更新报告：综合「项目理解」与「版本差异」。
     *
     * @param diff 版本对比结果
     * @param understanding AI 生成的项目理解文本
     * @param onPhase 阶段提示回调（IO 线程触发）
     * @param onChunk 流式增量回调（IO 线程触发）
     */
    suspend fun streamGenerateUpdateReport(
        diff: VersionComparator.FolderDiffResult,
        understanding: String,
        onPhase: (String) -> Unit,
        onChunk: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val (endpoint, model, apiKey) = resolveConfig()
            onPhase("正在组装上下文")
            val prompt = buildPrompt(diff, understanding)
            val messages = listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", prompt)
            )
            onPhase("正在连接 AI 服务")
            val result = postCompletion(
                endpoint = endpoint, model = model, apiKey = apiKey,
                messages = messages,
                generatingPhase = "AI 正在生成更新报告",
                onPhase = onPhase
            )
            Log.d(TAG, "report ok: chars=${result.length}, costMs=${System.currentTimeMillis() - startedAt}")
            result
        }.onFailure { e ->
            Log.e(TAG, "report failed: ${e.message}, costMs=${System.currentTimeMillis() - startedAt}")
        }
    }

    private const val SYSTEM_PROMPT =
        "你是《铁锈战争》(Rusted Warfare) MOD 资深开发与版本审阅助手。\n" +
        "用户会提供：\n" +
        "1)「项目理解」——新版 MOD 各文件内的隐藏行动(AI 行为)节内容，用于建立全局认知；\n" +
        "2)「版本差异」——旧版到新版的逐文件增删改。\n" +
        "请综合两者，生成一份面向玩家的中文更新日志。格式要求：\n" +
        "- 顶层用「修复」「更新」「细节」三类小标题（无内容的类别可省略；确有新增内容可加「新增」、删减可加「移除」）；\n" +
        "- 每类下用「数字.」编号列举，每条一句话，说清改了什么单位/机制的什么问题，必要时点出影响；\n" +
        "- 不要逐行复述 diff，要做语义归纳（例如把多条数值调整合并为「调整 XX 单位血量/造价」）；\n" +
        "- 语气简洁专业，不寒暄、不解释你做了什么；\n" +
        "- 仅输出更新日志正文，不要前后缀说明。"

    /** 把项目理解 + 对比结果转成发给 AI 的 prompt 文本 */
    private fun buildPrompt(diff: VersionComparator.FolderDiffResult, understanding: String): String {
        val sb = StringBuilder()
        sb.append("【项目理解】（新版 MOD 各文件内的隐藏行动节）\n")
        sb.append(understanding).append("\n\n")

        sb.append("【版本差异】（源文件夹=旧版，目标文件夹=新版）\n")
        sb.append("旧版独有文件（共 ${diff.rootOnlyFiles.size} 个，即旧版有而新版已删除/合并的）：\n")
        if (diff.rootOnlyFiles.isEmpty()) sb.append("（无）\n")
        else diff.rootOnlyFiles.forEach { sb.append("- $it\n") }

        sb.append("\n新版独有文件（共 ${diff.metaOnlyFiles.size} 个，即新版新增的）：\n")
        if (diff.metaOnlyFiles.isEmpty()) sb.append("（无）\n")
        else diff.metaOnlyFiles.forEach { sb.append("- $it\n") }

        val common = diff.commonFiles
        sb.append("\n两版共有但有差异的文件（共 ${common.size} 个）：\n")
        if (common.isEmpty()) {
            sb.append("（无）\n")
        } else {
            val shown = common.take(MAX_COMMON_FILES_IN_PROMPT)
            shown.forEach { fd ->
                sb.append("\n文件：${fd.filePath}（新增 ${fd.addedCount} 行 / 删除 ${fd.removedCount} 行）\n")
                if (fd.error != null) {
                    sb.append("  读取错误：${fd.error}\n")
                } else {
                    val lines = fd.ops.take(MAX_DIFF_LINES_PER_FILE)
                    lines.forEach { op ->
                        val sign = if (op.type == '+') "+" else "-"
                        sb.append("$sign ${op.text}\n")
                    }
                    if (fd.ops.size > MAX_DIFF_LINES_PER_FILE) {
                        sb.append("...（其余 ${fd.ops.size - MAX_DIFF_LINES_PER_FILE} 行差异已省略）\n")
                    }
                }
            }
            if (common.size > MAX_COMMON_FILES_IN_PROMPT) {
                sb.append("\n（另有 ${common.size - MAX_COMMON_FILES_IN_PROMPT} 个有差异的文件未列出）\n")
            }
        }
        sb.append("\n请综合项目理解与版本差异，生成中文更新日志。")
        return sb.toString()
    }

    // ==================== AI 建议 ====================

    /** AI 建议动作类型 */
    const val ACTION_INSERT = "插入"
    const val ACTION_REPLACE = "替换"

    /**
     * 单条 AI 建议：
     * - [action] 为 [ACTION_INSERT]：把 [code] 插入光标处；
     * - [action] 为 [ACTION_REPLACE]：在文件中定位 [target]（原有行原文），整段替换为 [code]。
     */
    data class AiSuggestion(
        val action: String,
        val target: String?,
        val code: String
    )

    /**
     * 按需 AI 代码建议：分析用户最近的编辑动作，推断其编辑意图，
     * 给出「插入」或「替换」两类编辑操作（不再是单纯续写）。
     *
     * @param filePath 当前文件相对路径（用于让 AI 理解模块层级）
     * @param sectionName 当前节名（如「隐藏行动_合并进攻」「核心」），可空
     * @param isHiddenAction 当前节是否为隐藏行动（行为节），决定续写风格
     * @param contextLines 光标前若干行文本（已含节头与上下文）
     * @param currentLine 光标所在行内容
     * @param isChineseView 当前是否中文视图（决定输出用中文键名还是英文）
     * @param streaming 是否走流式（true 时首 token 前失败会自动降级为非流式）
     * @param onPartial 流式增量回调（已解析出的建议列表，随生成过程不断增长/变化）
     * @param onPhase 阶段提示回调
     */
    suspend fun suggestCompletion(
        filePath: String,
        sectionName: String?,
        isHiddenAction: Boolean,
        recentInputLines: String,
        contextLines: String,
        currentLine: String,
        codeTable: String,
        fileStructure: String = "",
        isChineseView: Boolean,
        streaming: Boolean = true,
        onPartial: ((List<AiSuggestion>) -> Unit)? = null,
        onPhase: (String) -> Unit
    ): Result<List<AiSuggestion>> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val (endpoint, model, apiKey) = resolveConfig()
            val prompt = buildCompletionPrompt(filePath, sectionName, isHiddenAction, recentInputLines, contextLines, currentLine, codeTable, fileStructure, isChineseView)
            val messages = listOf(
                ChatMessage("system", COMPLETION_PROMPT),
                ChatMessage("user", prompt)
            )
            onPhase("正在连接 AI 服务")

            // 流式优先：首 token 前失败（或网关直接返回整段 JSON）自动降级，不会卡死
            if (streaming && SettingsManager.aiStreaming) {
                val buffer = StringBuilder()
                var received = false
                var lastEmit = 0L
                var firstTokenAt = 0L
                val streamResult = try {
                    // 注意：withContext 块内没有 CoroutineScope receiver，isActive 需经 coroutineContext 读取
                    postChatCompletionStream(
                        endpoint, model, apiKey, messages,
                        onDelta = { delta ->
                            if (!received) {
                                received = true
                                firstTokenAt = System.currentTimeMillis()
                                onPhase("AI 正在生成建议")
                            }
                            buffer.append(delta)
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PARTIAL_EMIT_INTERVAL_MS) {
                                lastEmit = now
                                val parsed = parseSuggestions(cleanPartial(buffer.toString()))
                                if (parsed.isNotEmpty()) onPartial?.invoke(parsed)
                            }
                        },
                        isCancelled = { !coroutineContext.isActive }
                    )
                    Result.success(buffer.toString())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 协程取消必须向上传播，不能当成流式失败降级
                } catch (e: Exception) {
                    Result.failure(e)
                }
                if (received) {
                    // 已拿到内容：即使中途断开也按已生成部分返回，不再重复请求
                    val raw = streamResult.getOrNull() ?: buffer.toString()
                    val suggestions = parseSuggestions(cleanCompletionResult(raw))
                    Log.d(TAG, "suggestions(stream) ok: count=${suggestions.size}, ttftMs=${firstTokenAt - startedAt}, costMs=${System.currentTimeMillis() - startedAt}")
                    return@withContext Result.success(suggestions)
                }
                Log.w(TAG, "stream unusable, fallback to non-stream: ${streamResult.exceptionOrNull()?.let { it::class.java.simpleName + ": " + it.message }}")
                onPhase("流式不可用，切换普通模式")
            }

            val result = postCompletion(
                endpoint = endpoint, model = model, apiKey = apiKey,
                messages = messages,
                generatingPhase = "AI 正在生成代码建议",
                onPhase = onPhase
            )
            // 清理：去掉 AI 可能加的 markdown 代码围栏与前后说明，再解析为结构化建议
            val cleaned = cleanCompletionResult(result)
            val suggestions = parseSuggestions(cleaned)
            Log.d(TAG, "suggestions ok: count=${suggestions.size}, costMs=${System.currentTimeMillis() - startedAt}")
            Result.success(suggestions)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消不是失败：必须向上传播，避免 UI 误报错误
        } catch (e: Exception) {
            Log.e(TAG, "suggestions failed: ${e::class.java.simpleName}: ${e.message}, costMs=${System.currentTimeMillis() - startedAt}")
            Result.failure(e)
        }
    }

    /**
     * 流式请求：SSE 逐行读取 `data:` 帧，把 delta.content 增量回调。
     *
     * 兼容两种「不支持流式」的网关表现：
     * 1. 200 但直接返回整段 JSON（非 SSE）→ 解析 message.content 直接返回，不重复请求；
     * 2. 非 2xx / 无内容 → 抛 IOException，由调用方降级为非流式。
     */
    private fun postChatCompletionStream(
        endpoint: String,
        model: String,
        apiKey: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String {
        val body = encodeRequest(model, messages, stream = true)
        val conn = openConnection(endpoint, apiKey)
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            writeBody(conn, body)
            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw IOException("AI 请求失败 (HTTP $code)：${parseErrorMessage(raw, code)}")
            }
            val sb = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    // 协程取消检查：阻塞读不会被取消打断，取消时必须主动断开连接终止读取，
                    // 否则关闭 AI 面板/切文件后仍读完整个响应，白烧 token
                    if (isCancelled()) {
                        conn.disconnect()
                        throw kotlinx.coroutines.CancellationException("AI stream cancelled")
                    }
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        // 注意：org.json 的 optString 在 key 缺失时返回字符串 "null"！
                        // 且 content 字段存在但值为 JSON null 时，optString 同样返回字符串 "null"，
                        // has("content") 会放行 —— 必须再过滤一次字符串 "null"
                        val deltaContent = runCatching {
                            val choices = JSONObject(payload).optJSONArray("choices") ?: return@runCatching null
                            if (choices.length() == 0) return@runCatching null
                            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching null
                            if (!delta.has("content")) return@runCatching null
                            val c = delta.optString("content")
                            if (c.isEmpty() || c == "null") return@runCatching null
                            c
                        }.getOrNull()
                        if (!deltaContent.isNullOrEmpty()) {
                            sb.append(deltaContent)
                            onDelta(deltaContent)
                        }
                    } else if (line.isNotBlank() && line.trimStart().startsWith("{")) {
                        // 网关忽略了 stream 参数：整段 JSON 一次返回，直接按非流式解析
                        val content = extractMessageContent(line)
                        if (!content.isNullOrEmpty()) return content
                    }
                }
            }
            if (sb.isEmpty()) throw IOException("流式响应为空，降级为非流式")
            Log.d(TAG, "stream ok: url=$endpoint, chars=${sb.length}")
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** 从非流式响应体（可能是一整行 JSON）中提取 message.content */
    private fun extractMessageContent(raw: String): String? = runCatching {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices")
        val content = if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0).optJSONObject("message")?.optString("content")
        } else {
            obj.optString("content")
        }
        content?.takeIf { it.isNotEmpty() && it != "null" } // optString 缺失时返回字符串 "null"，需排除
    }.getOrNull()

    /**
     * 流式过程中的部分文本清理：只处理「已出现但可能未闭合」的代码围栏，
     * 让 parseSuggestions 能在生成途中解析出已完成的块。
     */
    private fun cleanPartial(raw: String): String {
        var text = raw.trimStart()
        if (text.startsWith("```")) {
            // 去掉围栏起始行（含可能的语言标识）
            val nl = text.indexOf('\n')
            text = if (nl >= 0) text.substring(nl + 1) else ""
        }
        if (text.trimEnd().endsWith("```")) {
            text = text.trimEnd().removeSuffix("```")
        }
        return text.trim('\n')
    }

    /**
     * 解析 AI 输出为结构化建议列表。
     * 支持的块格式（可多条，顺序保留）：
     * ```
     * # 动作: 插入
     * 代码行...
     *
     * # 动作: 替换
     * # 目标: 被替换的原有行原文（多行目标则连续多条 # 目标: 行）
     * 替换后的代码行...
     * ```
     * `# 推断` 意图说明行忽略；无任何 `# 动作:` 标记时，整体视为一条插入建议（兼容旧行为）。
     */
    fun parseSuggestions(raw: String): List<AiSuggestion> {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("# 意图不明确")) return emptyList()
        if (!text.contains("# 动作:")) {
            // 兼容：AI 未按格式输出，整体作为一条插入建议（剔除意图说明行）
            val code = text.lines()
                .filter { !it.trimStart().startsWith("# 推断") }
                .joinToString("\n").trim()
            return if (code.isNotBlank()) listOf(AiSuggestion(ACTION_INSERT, null, code)) else emptyList()
        }
        val suggestions = mutableListOf<AiSuggestion>()
        var action: String? = null
        val targets = mutableListOf<String>()
        val codeLines = mutableListOf<String>()
        fun flush() {
            val a = action ?: return
            // 仅去掉首尾空行，保留代码内部的空行与换行结构
            val code = codeLines.joinToString("\n").trim('\n')
            if (code.isNotBlank()) {
                suggestions.add(
                    AiSuggestion(
                        action = a,
                        target = if (a == ACTION_REPLACE) targets.joinToString("\n").trim().ifBlank { null } else null,
                        code = code
                    )
                )
            }
            action = null; targets.clear(); codeLines.clear()
        }
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("# 动作:") -> { flush(); action = t.removePrefix("# 动作:").trim() }
                t.startsWith("# 目标:") -> targets.add(t.removePrefix("# 目标:").trim())
                t.startsWith("# 推断") || t.startsWith("# 意图") -> { /* 意图说明行，忽略 */ }
                else -> if (action != null) codeLines.add(line)
            }
        }
        flush()
        return suggestions
    }

    /**
     * 构建当前节的代码参考表（精简版），供 AI 生成合法代码。
     * - 标准节：从代码参考库查询，按「上下文中已出现的键优先」排序，限 30 条
     * - 隐藏行动节：从文件内容提取已用键名，限 15 条
     * - 格式：仅键名（一行一个），前 5 条附 example 值示例
     */
    fun buildCodeTableForSection(sectionName: String?, fileContent: String? = null): String {
        if (sectionName.isNullOrBlank()) return ""
        val mappedName = mapSectionName(sectionName)

        // 标准节：查询代码参考库
        if (mappedName.isNotEmpty()) {
            runCatching {
                val engine = com.rwmodstudio.core.translation.TranslationEngine.getInstance()
                val ref = engine.getCodeReference()
                val props = ref.getPropertiesForSection(mappedName)
                if (props.isNotEmpty()) {
                    // 上下文中已出现的键优先
                    val contextKeys = mutableSetOf<String>()
                    if (fileContent != null) {
                        extractUsedKeysFromSection(fileContent, sectionName).split('\n')
                            .forEach { contextKeys.add(it.trim()) }
                    }
                    val (used, unused) = props.partition { it.name in contextKeys }
                    val sorted = (used + unused).take(30)
                    val sb = StringBuilder()
                    sorted.forEachIndexed { i, p ->
                        if (i < 5 && p.example.isNotBlank()) {
                            sb.append("${p.name}  例: ${p.example.take(60)}\n")
                        } else {
                            sb.append("${p.name}\n")
                        }
                    }
                    return sb.toString().trim()
                }
            }
        }

        // 隐藏行动等非标准节：从文件内容提取
        if (fileContent != null) {
            val raw = extractUsedKeysFromSection(fileContent, sectionName)
            // 精简：只保留键名，去掉 :（键名）后缀，限 15 条
            return raw.split('\n')
                .map { it.substringBefore(':').trim() }
                .filter { it.isNotBlank() }
                .take(15)
                .joinToString("\n")
        }
        return ""
    }

    /** 从文件内容中提取指定节内已出现的键名列表（一行一个） */
    private fun extractUsedKeysFromSection(content: String, sectionName: String): String {
        val lines = content.split('\n')
        val sectionLower = sectionName.trim().lowercase()
        val foundKeys = LinkedHashSet<String>()
        var inSection = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                val currentSection = line.removePrefix("[").removeSuffix("]")
                val currentLower = currentSection.trim().lowercase()
                if (currentLower == sectionLower) inSection = true
                else if (inSection) break
                continue
            }
            if (inSection) {
                val colonIdx = line.indexOf(':')
                val eqIdx = line.indexOf('=')
                if (colonIdx > 0 && (eqIdx < 0 || colonIdx < eqIdx)) {
                    val key = line.substring(0, colonIdx).trim()
                    if (key.isNotBlank() && key.length <= 40) foundKeys.add(key)
                } else if (eqIdx > 0) {
                    val key = line.substring(0, eqIdx).trim()
                    if (key.isNotBlank() && key.length <= 40) foundKeys.add(key)
                }
            }
        }
        return foundKeys.joinToString("\n")
    }

    /** 节名 → 代码参考库键名（中文下划线/英文空格 → 中文分类名） */
    fun mapSectionName(name: String): String {
        val trimmed = name.trim()
        val lower = trimmed.lowercase()
        val noSpace = lower.replace(" ", "").replace("_", "")

        // 1) 中文分类匹配（隐藏行动_xxx / 隐藏行动 / 核心_1 / 核心 / ai / graphics 等）
        val zhSections = mapOf(
            "隐藏行动" to "隐藏行动", "hiddenaction" to "隐藏行动",
            "核心" to "核心", "core" to "核心",
            "图像" to "图像", "graphics" to "图像",
            "攻击" to "攻击", "attack" to "攻击",
            "运动" to "运动", "movement" to "运动",
            "行动" to "行动", "action" to "行动",
            "ai" to "ai",
            "动画" to "动画", "animation" to "animation",
            "附属" to "附属", "attached" to "attached",
            "可建造" to "可建造", "buildable" to "buildable",
            "贴花" to "贴花", "decals" to "decals",
            "资源" to "资源", "resources" to "resources",
            "全局资源" to "全局资源", "globalresource" to "全局资源",
            "炮塔" to "炮塔", "turret" to "turret",
            "抛射体" to "抛射体", "projectile" to "projectile",
            "腿" to "腿", "legs" to "legs",
            "放置规则" to "放置规则", "placementrules" to "放置规则"
        )
        for ((key, zh) in zhSections) {
            if (noSpace.contains(key.lowercase())) return zh
        }
        // 2) 直接匹配（可能已经是中文分类名）
        return trimmed.removeSuffix("_").removePrefix("[").removeSuffix("]")
            .substringBefore(' ').trim()
    }

    private const val COMPLETION_PROMPT =
        "你是铁锈战争(Rusted Warfare) MOD 的资深 INI 编写助手，擅长从用户的编辑行为中推断意图。\n" +
        "领域认知：MOD 单位/系统间的行为关系靠「标签+条件」驱动——单位在满足条件时添加/删除标签、发送带标签消息、产生单位、转换形态；" +
        "其他单位通过条件（需标签/有全局标签/自身有标签/队伍中该单位数量等）检测标签并触发行为。\n" +
        "你的核心任务不是续写语法，而是「意图推断」并给出**编辑操作建议**：\n" +
        "1. 分析【最近编辑动作】序列，识别用户正在执行的批量操作模式（如：跨文件给多个单位添加同一标签条件、批量调整某数值、在同类单位间复制条件集、删除一组相关行）；\n" +
        "2. 推断用户当前执行的任务与接下来最可能的操作；\n" +
        "3. 结合【光标上下文】判断当前光标位置是否处于该任务的链路中：在链路上→给出该处的编辑操作；不在→沿用上下文风格给最合理的一个操作，或输出空。\n" +
        "你给出的建议是「编辑操作」，分两类，用块格式输出（可多条块，每块一种操作）：\n" +
        "- 插入：把新代码写入光标处。块格式：第一行「# 动作: 插入」，其后直接跟代码行；\n" +
        "- 替换：把文件中已有的一行或多行替换为新代码。块格式：第一行「# 动作: 替换」，随后每行「# 目标: <被替换的原有行原文，必须逐字取自【光标上下文】>」（多行目标用连续多条 # 目标: 行），目标行之后跟替换后的代码行；\n" +
        "- 第一块之前可用一行「# 推断：…」简述推断的意图；无法给出合理建议时仅输出：# 意图不明确，无合适建议。\n" +
        "选择依据：需要修正/调整已有行（改数值、改条件、补完未写完的键、换标签名）→ 用替换；需要新增行 → 用插入。替换目标必须逐字复制上下文中的原有行，禁止改写、省略或凭记忆拼凑。\n" +
        "规则：\n" +
        "1. 键名语言与上下文一致（中文视图用中文键名，英文视图用英文键名）；\n" +
        "2. 键名只从参考表挑选，不编造；标签名优先复用【已知标签】，不创造新名；数值给典型值不留占位符；\n" +
        "3. 替换不得重复目标中已有且无需修改的行，只输出替换后的完整结果；若在写隐藏行动（行为节），优先条件→动作组合（如 需要标签:xxx 配 添加标签:xxx / 发送消息到:xxx）。\n" +
        "4. 最多 3 条建议，每条尽量短（1-3 行）；不要写解释、不要写总结、不要复述上下文——输出越长用户等得越久。"

    /** 组装补全 prompt：意图推断导向、结构化、有 token 预算控制 */
    private fun buildCompletionPrompt(
        filePath: String,
        sectionName: String?,
        isHiddenAction: Boolean,
        recentInputLines: String,
        contextLines: String,
        currentLine: String,
        codeTable: String,
        fileStructure: String,
        isChineseView: Boolean
    ): String {
        val sb = StringBuilder()
        // 文件与位置信息（含节类型）
        sb.append("文件: $filePath")
        if (!sectionName.isNullOrBlank()) sb.append(" | 节: [$sectionName]")
        sb.append(" | 节类型: ${if (isHiddenAction) "隐藏行动(行为节)" else "属性/配置节"}")
        sb.append(" | 视图: ${if (isChineseView) "中文" else "英文"}\n\n")

        // 文件结构概览（节头列表，帮助理解文件用途；低价值，配额压到最小）
        if (fileStructure.isNotBlank()) {
            sb.append("## 文件结构\n")
            sb.append(fileStructure.take(160))
            sb.append("\n\n")
        }

        // 编辑动作（意图推断主依据）
        if (recentInputLines.isNotBlank()) {
            sb.append("## 最近编辑动作（推断意图的主依据，跨文件）\n")
            sb.append(recentInputLines.trim().take(600))
            sb.append("\n\n")
        }

        // 已知标签（复用这些标签名，避免 AI 编造新名）
        val knownTags = extractKnownTags(contextLines)
        if (knownTags.isNotBlank()) {
            sb.append("## 已知标签（优先复用这些标签名，勿创造新名）\n")
            sb.append(knownTags.take(160))
            sb.append("\n\n")
        }

        // 光标上下文（锚定位置）
        if (contextLines.isNotBlank()) {
            sb.append("## 光标上下文\n")
            sb.append(contextLines.trim().take(400))
            sb.append("\n\n")
        }

        // 当前行
        sb.append("## 当前行(光标处)\n")
        sb.append(if (currentLine.isBlank()) "（空行）" else currentLine.trim().take(200))
        sb.append("\n\n")

        // 合法键名参考表（精简，仅键名）
        if (codeTable.isNotBlank()) {
            sb.append("## 合法键名\n")
            sb.append(codeTable.take(600))
            sb.append("\n\n")
        }

        sb.append("请先推断用户意图，再给出编辑操作建议（插入或替换，按块格式输出，最多 3 条）。")
        return sb.toString()
    }

    /**
     * 从上下文中提取已知标签集合（需要标签/添加标签/删除标签/发送消息到 等键的值）。
     * 供 AI 复用标签名，避免编造新名，保持标签契约一致。
     */
    private fun extractKnownTags(contextLines: String): String {
        val tags = LinkedHashSet<String>()
        for (line in contextLines.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("[") || t.startsWith("#")) continue
            val idx = maxOf(t.indexOf(':'), t.indexOf('='))
            if (idx <= 0) continue
            val key = t.substring(0, idx).trim()
            val value = t.substring(idx + 1).trim()
            // 仅收集标签/消息类键值
            if (value.isNotBlank() &&
                (key.contains("标签") || key.contains("消息") ||
                    key.lowercase().contains("tag") || key.lowercase().contains("message"))
            ) {
                value.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { tags.add(it) }
            }
        }
        return tags.joinToString(", ")
    }

    /** 清理 AI 输出：去掉 markdown 围栏、多余空行与可能的说明文字 */
    private fun cleanCompletionResult(raw: String): String {
        var text = raw.trim()
        // 去掉 ```ini / ``` 包围
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            // 去掉可能的语言标识行（ini / ini）
            text = text.trimStart()
            if (text.startsWith("ini") || text.startsWith("INI")) {
                text = text.substring(3).trimStart()
            }
            if (text.endsWith("```")) {
                text = text.removeSuffix("```").trimEnd()
            }
        }
        // 去掉首尾多余空行
        return text.trim().trim('\n').trim()
    }

    // ==================== 网络 ====================

    /** 解析并校验 AI 配置，返回 (endpoint, model, apiKey) */
    private fun resolveConfig(): Triple<String, String, String> {
        val baseUrl = SettingsManager.aiBaseUrl.trimEnd('/')
        val model = SettingsManager.aiModel.trim()
        val apiKey = SettingsManager.aiApiKey.trim()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            throw IOException("AI 配置不完整：请先在开发者模式 → AI 助手中填写服务地址、模型与 API Key")
        }
        val endpoint = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        return Triple(endpoint, model, apiKey)
    }

    /**
     * 非流式请求入口：设置生成中阶段提示后发出请求。
     * 默认非流式，兼容不支持流式的模型（如 sensenova 系列），避免挂起/转圈。
     */
    private fun postCompletion(
        endpoint: String,
        model: String,
        apiKey: String,
        messages: List<ChatMessage>,
        generatingPhase: String,
        onPhase: (String) -> Unit
    ): String {
        onPhase(generatingPhase)
        return postChatCompletionNonStream(endpoint, model, apiKey, messages)
    }

    /** 非流式 POST：完整响应解析 choices[0].message.content */
    private fun postChatCompletionNonStream(
        endpoint: String,
        model: String,
        apiKey: String,
        messages: List<ChatMessage>
    ): String {
        val body = encodeRequest(model, messages, stream = false)
        val conn = openConnection(endpoint, apiKey)
        conn.readTimeout = READ_TIMEOUT_MS
        try {
            writeBody(conn, body)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("AI 请求失败 (HTTP $code)：${parseErrorMessage(raw, code)}")
            }
            val content = runCatching {
                val obj = JSONObject(raw)
                val choices = obj.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    choice.optJSONObject("message")?.optString("content")
                        ?.takeIf { it.isNotEmpty() && it != "null" } // optString 缺失时返回 "null" 字符串
                        ?: choice.optString("text").takeIf { it.isNotEmpty() }
                } else {
                    obj.optString("content").takeIf { it.isNotEmpty() && it != "null" }
                }
            }.getOrNull()
                ?: throw IOException("AI 响应解析失败：${raw.take(300)}")
            Log.d(TAG, "non-stream ok: url=$endpoint, chars=${content.length}")
            return content
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(endpoint: String, apiKey: String): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            // 禁用自动重定向跟随
            instanceFollowRedirects = false
        }
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
    }

    /** 从错误响应体里尽量提取可读信息 */
    private fun parseErrorMessage(raw: String, code: Int): String {
        if (raw.isBlank()) return "HTTP $code"
        // 常见 OpenAI 兼容错误结构：{"error":{"message":"..."}}
        val msg = runCatching {
            val obj = org.json.JSONObject(raw)
            obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("message")
        }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: raw.take(300)
    }
}
