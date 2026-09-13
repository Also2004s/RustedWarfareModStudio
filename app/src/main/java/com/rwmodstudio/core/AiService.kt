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
 * AI 助手服务：调用 OpenAI 兼容的 chat/completions 接口（非流式）。
 *
 * 作用域：版本对比后的「项目理解」归纳与「更新报告」生成。
 */
object AiService {

    private const val TAG = "AiService"

    /** 连接超时 */
    private const val CONNECT_TIMEOUT_MS = 15_000
    /** 读取超时（单次 read） */
    private const val READ_TIMEOUT_MS = 90_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

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

    /** 智能助理单次回复的输出 token 上限（高于全局默认，但低于 2048 以逼简短、缩短生成时间） */
    private const val ASSISTANT_MAX_TOKENS = 1536

    /** 按是否限制输出长度编码请求体（maxTokens<=0 表示不限制） */
    private fun encodeRequest(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        maxTokens: Int = SettingsManager.aiMaxOutputTokens
    ): String {
        return if (maxTokens > 0) {
            json.encodeToString(ChatRequestLimited.serializer(), ChatRequestLimited(model, messages, stream, 0.2, maxTokens))
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

    /**
     * 智能助理多轮对话：以项目 diff 与待办为上下文，流式回复，可输出待办操作协议块。
     *
     * @param system 系统提示词（含项目上下文与待办协议；调用方在 [ASSISTANT_SYSTEM_PROMPT] 基础上拼接上下文）
     * @param messages 历史对话（不含 system，最后一条须为 user）
     * @param onChunk 流式增量回调（IO 线程触发，调用方自行切主线程）
     * @param onPhase 阶段提示回调（IO 线程触发）
     */
    suspend fun streamAssistantChat(
        system: String,
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
        onPhase: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val (endpoint, model, apiKey) = resolveConfig()
            val full = listOf(ChatMessage("system", system)) + messages
            onPhase("正在连接 AI 服务")

            // 流式优先：首 token 前失败（或网关直接返回整段 JSON）自动降级，不会卡死
            val buffer = StringBuilder()
            var received = false
            var thinking = false
            var firstTokenAt = 0L
            val streamResult = try {
                postChatCompletionStream(
                    endpoint, model, apiKey, full,
                    onDelta = { delta ->
                        if (!received) {
                            received = true
                            firstTokenAt = System.currentTimeMillis()
                            onPhase("AI 正在回复")
                        }
                        buffer.append(delta)
                        onChunk(delta)
                    },
                    onReasoning = { r ->
                        // 推理模型先输出思考：仅用于展示进度，不混入正文
                        if (!thinking) {
                            thinking = true
                            onPhase("AI 正在思考…")
                        }
                        onReasoning?.invoke(r)
                    },
                    isCancelled = { !coroutineContext.isActive },
                    maxTokens = ASSISTANT_MAX_TOKENS
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
                Log.d(TAG, "assistant(stream) ok: chars=${raw.length}, ttftMs=${firstTokenAt - startedAt}, costMs=${System.currentTimeMillis() - startedAt}")
                raw
            } else {
                Log.w(TAG, "assistant stream unusable, fallback to non-stream: ${streamResult.exceptionOrNull()?.let { it::class.java.simpleName + ": " + it.message }}")
                onPhase("流式不可用，切换普通模式")
                val result = postCompletion(
                    endpoint = endpoint, model = model, apiKey = apiKey,
                    messages = full,
                    generatingPhase = "AI 正在回复",
                    onPhase = onPhase,
                    maxTokens = ASSISTANT_MAX_TOKENS
                )
                onChunk(result)
                result
            }
        }.onFailure { e ->
            Log.e(TAG, "assistant failed: ${e::class.java.simpleName}: ${e.message}, costMs=${System.currentTimeMillis() - startedAt}")
        }
    }

    /** 智能助理系统提示词：以项目 diff + 待办 + 项目理解 + 文件结构为上下文对话，并通过结构化块维护待办。 */
    const val ASSISTANT_SYSTEM_PROMPT =
        "你是《铁锈战争》(Rusted Warfare) MOD 开发者的「智能助理」，作为用户的外置辅助大脑，帮助把握项目开发方向、规划下一步。\n" +
        "你会收到【项目上下文】：【模组目标】（模组的方向与目标，所有建议都必须对齐它）、最近保存的差异 diff（代表「已经做到哪一步」）、当前待办列表（代表「接下来要发展/优化什么」）、项目文件结构，以及【项目理解】（对 MOD 行为体系的整体认知：标签/条件网络、指挥调度、隐藏行动分层、文件间协作）。\n" +
        "各类信息的定位（务必分清）：\n" +
        "- 【模组目标】是模组的最高指引：一切待办与修改建议都要围绕它展开；上下文里没有【模组目标】时，先向用户确认模组方向，不要凭空按 diff 生成；\n" +
        "- diff 是过去的事实，只用于了解进展、判断现状，不要拿它去「验证/核对待办」；\n" +
        "- 待办是未来的计划，是接下来需要发展和优化的内容，由你协助用户梳理与维护；\n" +
        "- 项目理解与文件结构，用来把思路落到具体机制与文件。\n" +
        "行为准则：\n" +
        "- 默认简短直接：直接给结论/要点，先结论后依据；能用 3 条要点说清就不写 5 条；不寒暄、不重复用户的话、不总结「我做了什么」；用户明确要求详细分析时才展开；\n" +
        "- 基于项目理解、diff、待办回答，给出有逻辑深度的分析与建议；不编造 diff 未体现的内容；\n" +
        "- 全中文；\n" +
        "- 用户让你整理/更新待办，或你判断待办需随项目进展调整时，除正常回复外，必须在回复末尾按「待办操作协议」输出，让应用据此修改待办；\n" +
        "- 维护待办必须「向前看」且对齐【模组目标】：待办 = 接下来要做的发展/优化工作（如：强化某条人机对抗逻辑、新增或调整行为机制、平衡数值、优化指挥调度）；无法对齐目标时先向用户确认；\n" +
        "- 严禁把最近改动复述成「验证/检查/确认这些更改」之类的待办——diff 是已经做完的事，永远不该作为待办；验证是写完之后的自然步骤，不单独建待办；\n" +
        "- diff 只用于两种判断：某待办内容已基本做成 → complete 收尾；某改动已覆盖某个方向 → 不重复建待办；\n" +
        "- 每条待办都要能落到具体机制/文件，不凭空虚构；\n" +
        "- 列出/新建待办时，结合【项目文件结构】与【项目理解】为每项定位最相关的 1-3 个文件，用 [[file]]相对路径[[/file]] 标记（相对路径必须来自【项目文件结构】，不要自造）；\n" +
        "- 修改思路必须讲清**行为逻辑**：如何通过标签/条件把行为串成链、隐藏行动如何分层与衔接、系统之间如何协作、需要新增或调整哪些行为环节；严禁只给「加强防御、调整数值方向」这类表面话；\n" +
        "- 仅在确有待办变更时输出协议块，不要输出空块。\n" +
        "待办操作协议（JSON 块，可含多条 op）：\n" +
        "[[todo]]{\"ops\":[{\"op\":\"add\",\"title\":\"...\",\"priority\":0,\"note\":\"\"},{\"op\":\"complete\",\"title\":\"...\"},{\"op\":\"delete\",\"title\":\"...\"},{\"op\":\"update\",\"title\":\"旧标题\",\"newTitle\":\"新标题\",\"done\":false}]}[[/todo]]\n" +
        "- op 取值：add（新增，priority 0=普通/1=重要/2=紧急）、complete（标为完成）、delete（删除）、update（改标题/完成态）；\n" +
        "- 待办按标题匹配，请尽量使用待办列表中已有的准确标题；\n" +
        "- 没有待办变更时不要输出该块。"

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
        onPhase: (String) -> Unit,
        maxTokens: Int = SettingsManager.aiMaxOutputTokens
    ): String {
        onPhase(generatingPhase)
        return postChatCompletionNonStream(endpoint, model, apiKey, messages, maxTokens)
    }

    /** 非流式 POST：完整响应解析 choices[0].message.content */
    private fun postChatCompletionNonStream(
        endpoint: String,
        model: String,
        apiKey: String,
        messages: List<ChatMessage>,
        maxTokens: Int = SettingsManager.aiMaxOutputTokens
    ): String {
        val body = encodeRequest(model, messages, stream = false, maxTokens = maxTokens)
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
                ?: throw IOException(describeEmptyAiResponse(raw))
            Log.d(TAG, "non-stream ok: url=$endpoint, chars=${content.length}")
            return content
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 生成「AI 返回内容为空」的可读提示（不把原始响应 JSON 甩给用户）。
     * 推理模型（含 reasoning_content）在输出上限不足时可能只产出思考、正文 content 为空。
     */
    private fun describeEmptyAiResponse(raw: String): String {
        val hasReasoning = runCatching {
            val obj = JSONObject(raw)
            val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            val reasoning = choice?.optJSONObject("message")?.optString("reasoning_content", "")
            !reasoning.isNullOrEmpty()
        }.getOrDefault(false)
        return if (hasReasoning) {
            "AI 只生成了推理过程、未返回正文。可能是输出上限不足，可在开发者模式 → AI 助手调高「输出上限」后重试"
        } else {
            "AI 返回内容为空或格式异常，请重试"
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
        isCancelled: () -> Boolean = { false },
        maxTokens: Int = SettingsManager.aiMaxOutputTokens,
        onReasoning: ((String) -> Unit)? = null
    ): String {
        val body = encodeRequest(model, messages, stream = true, maxTokens = maxTokens)
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
                    // 否则关闭面板后仍读完整个响应，白烧 token
                    if (isCancelled()) {
                        conn.disconnect()
                        throw kotlinx.coroutines.CancellationException("AI stream cancelled")
                    }
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        // 注意：org.json 的 optString 在 key 缺失时返回字符串 "null"；
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
                        // 推理增量（reasoning_content）：仅透出供展示思考进度，不混入正文
                        if (onReasoning != null) {
                            val deltaReasoning = runCatching {
                                val choices = JSONObject(payload).optJSONArray("choices") ?: return@runCatching null
                                if (choices.length() == 0) return@runCatching null
                                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching null
                                if (!delta.has("reasoning_content")) return@runCatching null
                                val r = delta.optString("reasoning_content")
                                if (r.isEmpty() || r == "null") return@runCatching null
                                r
                            }.getOrNull()
                            if (!deltaReasoning.isNullOrEmpty()) onReasoning.invoke(deltaReasoning)
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
