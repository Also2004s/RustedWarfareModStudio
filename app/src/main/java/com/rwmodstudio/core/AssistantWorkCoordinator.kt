package com.rwmodstudio.core

import android.content.Context
import android.widget.Toast
import com.rwmodstudio.RwModApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 智能助理后台工作协调器。
 *
 * 把 AI 对话 / 项目理解生成放到进程级 [RwModApplication.applicationScope]，
 * 即使离开近期弹窗（页面作用域被取消）任务仍继续执行并落盘；返回弹窗后从共享状态/磁盘恢复。
 *
 * 每个项目一份共享状态（按 projectRoot 分 key）。
 */
object AssistantWorkCoordinator {

    /** 单个项目的助理工作状态 */
    data class WorkState(
        val messages: List<AiService.ChatMessage> = emptyList(),
        val sending: Boolean = false,
        val phase: String = "",
        val pending: String = "",
        val reasoning: String = "",
        val todoApplied: String? = null
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<WorkState>>()

    fun stateFor(projectRoot: String): MutableStateFlow<WorkState> =
        states.getOrPut(projectRoot) { MutableStateFlow(WorkState()) }

    /** 会话状态为空时，从磁盘加载历史聊天 */
    fun loadHistory(projectRoot: String) {
        val flow = stateFor(projectRoot)
        if (flow.value.messages.isEmpty()) {
            flow.value = flow.value.copy(messages = ProjectAssistant.loadChat(projectRoot))
        }
    }

    /**
     * 发送一条用户消息：在进程级作用域执行（离开页面不取消），
     * 流式增量/阶段/结果写入共享状态，完成后持久化并应用待办操作。
     */
    fun send(projectRoot: String, context: Context, text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val appContext = context.applicationContext
        val flow = stateFor(projectRoot)
        if (flow.value.sending) return
        val newMessages = flow.value.messages + AiService.ChatMessage("user", input)
        flow.value = flow.value.copy(messages = newMessages, sending = true, phase = "", pending = "", reasoning = "", todoApplied = null)

        RwModApplication.getInstance().applicationScope.launch {
            val system = buildContext(projectRoot, appContext) { p ->
                flow.value = flow.value.copy(phase = p)
            }
            // 流式增量节流：约 80ms 合并一次写入共享状态
            val buffer = StringBuilder()
            var lastEmit = 0L
            val result = AiService.streamAssistantChat(
                system = system,
                // 只带最近 12 条对话（新鲜上下文已含当前 diff/待办/文件结构/理解），控制输入 token 降首字延迟
                messages = newMessages.takeLast(12),
                onChunk = { c ->
                    buffer.append(c)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 80L) {
                        lastEmit = now
                        val snapshot = buffer.toString()
                        buffer.setLength(0)
                        flow.value = flow.value.copy(pending = flow.value.pending + snapshot)
                    }
                },
                onPhase = { p -> flow.value = flow.value.copy(phase = p) },
                onReasoning = { r ->
                    // 思考过程实时展示（仅 UI 用，不落盘）
                    flow.value = flow.value.copy(reasoning = flow.value.reasoning + r)
                }
            )
            val leftover = buffer.toString()
            flow.value = flow.value.copy(pending = flow.value.pending + leftover)
            result
                .onSuccess { raw ->
                    val clean = ProjectAssistant.stripTodoBlock(raw)
                    val finalMessages = flow.value.messages + AiService.ChatMessage("assistant", clean)
                    flow.value = flow.value.copy(messages = finalMessages, sending = false, phase = "", pending = "", reasoning = "")
                    ProjectAssistant.saveChat(projectRoot, finalMessages)
                    // 待办操作协议：解析并应用
                    val ops = ProjectAssistant.parseTodoOps(raw)
                    if (ops.isNotEmpty()) {
                        val applied = ProjectAssistant.applyTodoOps(projectRoot, ops)
                        if (applied.isNotEmpty()) {
                            flow.value = flow.value.copy(todoApplied = "待办已更新：$applied")
                            // Toast 必须在主线程（后台协程无 Looper 会崩溃）
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appContext, "待办已更新：$applied", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) return@launch
                    flow.value = flow.value.copy(
                        messages = flow.value.messages + AiService.ChatMessage("assistant", "⚠️ ${e.message ?: "AI 请求失败"}"),
                        sending = false,
                        phase = "",
                        pending = "",
                        reasoning = ""
                    )
                }
        }
    }

    /** 组装本次发送用的最新项目上下文（diff + 待办 + 文件结构 + 项目理解），IO 线程调用 */
    private suspend fun buildContext(projectRoot: String, context: Context, onPhase: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val recentFiles = SaveHistoryManager.getRecentFiles(context)
            val diff = ProjectAssistant.buildDiffContext(context, projectRoot, recentFiles)
            val todos = ProjectAssistant.buildTodosText(projectRoot)
            val fileIndex = ProjectAssistant.buildProjectFileIndex(projectRoot)
            // 项目理解：会话内缓存复用，缺失/手动刷新时才生成（避免每次发送因保存导致重复生成）
            val understanding = ProjectAssistant.getOrBuildUnderstanding(projectRoot, onPhase = onPhase)
            val understandingBlock = if (understanding.isNullOrBlank()) "" else "\n\n【项目理解】\n$understanding"
            AiService.ASSISTANT_SYSTEM_PROMPT + "\n\n【项目上下文】\n" + diff + "\n\n" + todos + "\n\n" + fileIndex + understandingBlock
        }
}
