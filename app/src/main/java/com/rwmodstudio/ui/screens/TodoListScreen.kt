package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.TodoManager
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TodoFilter { ALL, ACTIVE, COMPLETED }

private val priorityColors = listOf(
    RustedOnBackground.copy(alpha = 0.35f), // 普通
    RustedAccent,                            // 重要
    RustedError                              // 紧急
)

private val priorityLabels = listOf("普通", "重要", "紧急")

@Composable
fun TodoListScreen(
    projectPath: String,
    triggerAdd: Int = 0
) {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    var items by remember(projectPath) { mutableStateOf(listOf<TodoManager.TodoItem>()) }
    var loading by remember(projectPath) { mutableStateOf(true) }
    var filter by remember { mutableStateOf(TodoFilter.ALL) }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoManager.TodoItem?>(null) }

    // 初始加载：后台读盘，避免主线程 I/O
    LaunchedEffect(projectPath) {
        items = withContext(Dispatchers.IO) { TodoManager.load(projectPath) }
        loading = false
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val updated = TodoManager.load(projectPath)
            withContext(Dispatchers.Main) { items = updated }
        }
    }

    val displayItems = remember(items, filter) {
        when (filter) {
            TodoFilter.ALL -> items
            TodoFilter.ACTIVE -> items.filter { !it.done }
            TodoFilter.COMPLETED -> items.filter { it.done }
        }
    }

    // 顶部横栏的「添加」按钮触发
    LaunchedEffect(triggerAdd) {
        if (triggerAdd > 0) {
            editing = null
            showDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 过滤标签
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == TodoFilter.ALL,
                onClick = { filter = TodoFilter.ALL },
                label = { Text("全部", fontSize = 12.sp) }
            )
            FilterChip(
                selected = filter == TodoFilter.ACTIVE,
                onClick = { filter = TodoFilter.ACTIVE },
                label = { Text("未完成", fontSize = 12.sp) }
            )
            FilterChip(
                selected = filter == TodoFilter.COMPLETED,
                onClick = { filter = TodoFilter.COMPLETED },
                label = { Text("已完成", fontSize = 12.sp) }
            )
            Spacer(Modifier.weight(1f))
            if (items.any { it.done }) {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            TodoManager.deleteCompleted(projectPath)
                            val updated = TodoManager.load(projectPath)
                            withContext(Dispatchers.Main) { items = updated }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("清除已完成", fontSize = 12.sp, color = RustedError)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (displayItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(color = RustedPrimary)
                } else {
                    Text(
                        text = if (items.isEmpty()) "暂无待办" else "没有符合条件的待办",
                        fontSize = 13.sp,
                        color = RustedOnBackground.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayItems, key = { it.id }) { item ->
                    TodoCard(
                        item = item,
                        formatter = formatter,
                        onToggle = {
                            scope.launch(Dispatchers.IO) {
                                TodoManager.update(projectPath, item.copy(done = !item.done))
                                val updated = TodoManager.load(projectPath)
                                withContext(Dispatchers.Main) { items = updated }
                            }
                        },
                        onEdit = {
                            editing = item
                            showDialog = true
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                TodoManager.delete(projectPath, item.id)
                                val updated = TodoManager.load(projectPath)
                                withContext(Dispatchers.Main) { items = updated }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showDialog) {
        TodoEditorDialog(
            item = editing,
            onDismiss = { showDialog = false; editing = null },
            onSave = { title, note, priority ->
                val target = editing
                scope.launch(Dispatchers.IO) {
                    if (target != null) {
                        TodoManager.update(projectPath, target.copy(title = title, note = note, priority = priority))
                    } else {
                        TodoManager.add(projectPath, title, priority, note)
                    }
                    val updated = TodoManager.load(projectPath)
                    withContext(Dispatchers.Main) {
                        items = updated
                        showDialog = false
                        editing = null
                    }
                }
            }
        )
    }
}

@Composable
private fun TodoCard(
    item: TodoManager.TodoItem,
    formatter: SimpleDateFormat,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = RustedSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = RustedPrimary)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(priorityColors.getOrElse(item.priority) { priorityColors[0] })
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = RustedOnBackground,
                        textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = priorityLabels.getOrElse(item.priority) { "普通" } + " · " + formatter.format(Date(item.createdAt)),
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.45f)
                )
                if (item.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.note,
                        fontSize = 12.sp,
                        color = RustedOnBackground.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = RustedError)
            }
        }
    }
}

@Composable
private fun TodoEditorDialog(
    item: TodoManager.TodoItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String, priority: Int) -> Unit
) {
    var title by remember { mutableStateOf(item?.title ?: "") }
    var note by remember { mutableStateOf(item?.note ?: "") }
    var priority by remember { mutableIntStateOf(item?.priority?.coerceIn(0, 2) ?: 0) }
    var noteExpanded by remember { mutableStateOf(false) }
    val noteInteractionSource = remember { MutableInteractionSource() }
    val isNoteFocused by noteInteractionSource.collectIsFocusedAsState()

    LaunchedEffect(isNoteFocused) {
        noteExpanded = isNoteFocused
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "添加待办" else "编辑待办", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注", fontSize = 12.sp) },
                    minLines = if (noteExpanded) 8 else 3,
                    maxLines = if (noteExpanded) Int.MAX_VALUE else 3,
                    interactionSource = noteInteractionSource,
                    trailingIcon = {
                        IconButton(onClick = { noteExpanded = !noteExpanded }) {
                            Icon(
                                imageVector = if (noteExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = RustedOnBackground.copy(alpha = 0.5f)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("优先级", fontSize = 13.sp, color = RustedOnBackground)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    priorityLabels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = priority == index,
                            onClick = { priority = index },
                            label = { Text(label, fontSize = 12.sp) },
                            leadingIcon = if (priority == index) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(priorityColors[index])
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onSave(title.trim(), note.trim(), priority) },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = RustedSurface
    )
}
