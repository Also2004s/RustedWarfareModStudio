package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.AssistantWorkCoordinator
import com.rwmodstudio.core.ProjectAssistant
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.core.TodoManager
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary
import com.rwmodstudio.ui.theme.RustedSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 智能助理聊天标签（近期弹窗内）：以项目 diff + 待办 + 项目理解为上下文与用户对话，
 * 通过 [[todo]]/[[file]] 协议维护待办并跳转文件。
 *
 * 对话/项目理解在 [AssistantWorkCoordinator]（进程级作用域）执行，
 * 离开弹窗任务不中断，返回后从共享状态自动恢复。
 */
@Composable
fun AssistantChatTab(
    projectRoot: String,
    onOpenFile: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 共享工作状态（进程级）：离开弹窗任务仍继续，返回后自动恢复
    val state by AssistantWorkCoordinator.stateFor(projectRoot).collectAsState()

    var diffFileCount by remember(projectRoot) { mutableStateOf(0) }
    var todoCount by remember(projectRoot) { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }

    val projectLabel = remember(projectRoot) {
        if (projectRoot.isBlank()) "未打开项目" else File(projectRoot).name
    }

    // 模组目标来源（mod-info.txt）是否已存在：缺失时提示用户补充
    val hasModInfo = remember(projectRoot) {
        projectRoot.isNotBlank() && File(projectRoot, "mod-info.txt").isFile
    }

    /** 刷新顶部统计（diff 文件数 / 待办数） */
    fun refreshContext() {
        scope.launch(Dispatchers.IO) {
            val recentFiles = SaveHistoryManager.getRecentFiles(context)
            val rootPrefix = if (projectRoot.isNotBlank()) {
                runCatching { File(projectRoot).canonicalPath + File.separator }.getOrDefault("")
            } else ""
            val inProject = recentFiles.count { path ->
                rootPrefix.isEmpty() ||
                    runCatching { File(path).canonicalPath.startsWith(rootPrefix) }.getOrDefault(false)
            }
            val todoNum = if (projectRoot.isNotBlank()) TodoManager.load(projectRoot).size else 0
            withContext(Dispatchers.Main) {
                diffFileCount = inProject
                todoCount = todoNum
            }
        }
    }

    // 首次进入：载入历史聊天（若共享状态为空）+ 刷新统计
    LaunchedEffect(projectRoot) {
        AssistantWorkCoordinator.loadHistory(projectRoot)
        refreshContext()
    }

    // 待办操作完成后刷新统计
    LaunchedEffect(state.todoApplied) {
        if (state.todoApplied != null) refreshContext()
    }

    // 新增消息后自动滚动到底部
    LaunchedEffect(state.messages.size, state.pending, state.sending) {
        val extra = if (state.sending || state.pending.isNotEmpty()) 1 else 0
        val target = (state.messages.size - 1 + extra).coerceAtLeast(0)
        if (state.messages.isNotEmpty() || state.sending) {
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || state.sending) return
        input = ""
        AssistantWorkCoordinator.send(projectRoot, context, text)
    }

    Column(modifier.fillMaxSize()) {
        // 顶部信息栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    projectLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = RustedOnBackground,
                    maxLines = 1
                )
                Text(
                    "diff $diffFileCount 文件 · 待办 $todoCount 项",
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.45f)
                )
            }
            TextButton(
                onClick = {
                    // 刷新上下文：让项目理解下次发送时重新同步/生成
                    ProjectAssistant.invalidateUnderstanding(projectRoot)
                    refreshContext()
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(14.dp), tint = RustedSecondary)
                Spacer(Modifier.width(4.dp))
                Text("刷新上下文", fontSize = 11.sp)
            }
        }
        if (projectRoot.isBlank()) {
            Text(
                "当前未打开项目，仅提供近期修改 diff；请先在文件浏览中打开项目以维护待办。",
                fontSize = 10.sp,
                color = RustedOnBackground.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (projectRoot.isNotBlank() && !hasModInfo) {
            Text(
                "在项目根目录放 mod-info.txt（写清模组方向）可让助理更懂你的模组",
                fontSize = 10.sp,
                color = RustedOnBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (state.messages.isEmpty() && !state.sending) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "与智能助理对话，它会基于项目近期修改与待办帮你跟进进度。",
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.messages, key = { index, _ -> index }) { _, msg ->
                    ChatBubble(role = msg.role, content = msg.content, projectRoot = projectRoot, onOpenFile = onOpenFile)
                }
                if (state.sending || state.pending.isNotEmpty()) {
                    item(key = "pending") {
                        ChatBubble(role = "assistant", content = state.pending.ifBlank { "…" }, projectRoot = projectRoot, onOpenFile = onOpenFile)
                    }
                }
            }
        }

        // 思考过程实时展示：推理模型先输出 reasoning_content，这里让用户看到在干活
        if (state.sending && state.reasoning.isNotBlank()) {
            Text(
                "思考中… ${state.reasoning.trim()}",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = RustedOnBackground.copy(alpha = 0.4f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (state.sending) {
            Text(
                state.phase.ifBlank { "AI 正在回复…" },
                fontSize = 10.sp,
                color = RustedSecondary.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 底部输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = RustedOnBackground),
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RustedPrimary,
                    unfocusedBorderColor = RustedOnBackground.copy(alpha = 0.2f),
                    cursorColor = RustedPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { send() },
                enabled = input.isNotBlank() && !state.sending,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = RustedPrimary)
            ) {
                Icon(Icons.Default.Send, "发送", Modifier.size(18.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun ChatBubble(
    role: String,
    content: String,
    projectRoot: String = "",
    onOpenFile: (String, String) -> Unit = { _, _ -> }
) {
    val isUser = role == "user"
    // 渲染时解析文件引用块：正文去掉 [[file]] 标记，相关文件渲染为可点击芯片
    val displayText = remember(content) { ProjectAssistant.stripFileRefs(content) }
    val fileRefs = remember(content) { ProjectAssistant.parseFileRefs(content) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 300.dp)) {
            Box(
                Modifier
                    .background(
                        if (isUser) RustedPrimary.copy(alpha = 0.9f) else RustedSurface,
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
                    )
                    .clickable(enabled = false) {}
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    displayText.ifBlank { if (isUser) "" else "…" },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = if (isUser) Color.White else RustedOnBackground
                )
            }
            // 相关文件芯片（最多 3 个，点击跳转编辑器）
            if (!isUser && fileRefs.isNotEmpty()) {
                fileRefs.take(3).forEach { rel ->
                    val file = ProjectAssistant.resolveProjectFile(projectRoot, rel)
                    if (file != null) {
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(RustedPrimary.copy(alpha = 0.12f))
                                .clickable { onOpenFile(file.name, file.absolutePath) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Description, null, Modifier.size(14.dp), tint = RustedPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                file.name,
                                fontSize = 12.sp,
                                color = RustedPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
