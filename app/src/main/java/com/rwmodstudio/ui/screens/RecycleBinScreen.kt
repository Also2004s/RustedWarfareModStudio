package com.rwmodstudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.RecycleBin
import com.rwmodstudio.core.RecycleEntry
import com.rwmodstudio.ui.components.AppDialog
import com.rwmodstudio.ui.components.DialogActionButton
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RECYCLE_TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 回收站页面：展示 `RWmod/exports` 里的 `.rwmod` 文件与文件夹。
 *
 * - 「还原」把条目移回**首页目录**（不再回原路径）；
 * - 「彻底删除」不可恢复；
 * - 顶部「清空」清空整个 exports 目录。
 *
 * 页面不参与文件浏览器的项目/空间逻辑，纯独立列表。
 */
@Composable
fun RecycleBinScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<RecycleEntry>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<RecycleEntry?>(null) }

    LaunchedEffect(tick) {
        val result = withContext(Dispatchers.IO) { RecycleBin.list() to RecycleBin.sizeBytes() }
        entries = result.first
        totalBytes = result.second
        loading = false
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Column(Modifier.fillMaxSize().background(RustedBackground).padding(horizontal = 12.dp)) {
        // 概览 + 清空
        ElevatedCard(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("共 ${entries.size} 项", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "占用 ${formatSize(totalBytes)} · 还原会放回首页目录 · 超过 ${formatSize(RecycleBin.MAX_BYTES)} 下次启动自动清空",
                        fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f)
                    )
                }
                TextButton(onClick = { confirmClear = true }, enabled = !busy) {
                    Text("清空", fontSize = 13.sp, color = RustedError)
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = RustedPrimary)
            }

            entries.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(42.dp), tint = RustedOnBackground.copy(alpha = 0.2f))
                    Spacer(Modifier.height(10.dp))
                    Text("回收站是空的", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                    Spacer(Modifier.height(4.dp))
                    Text("首页删除的模组包与文件夹会先放在这里", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.3f))
                }
            }

            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.name }) { entry ->
                    RecycleItemCard(
                        entry = entry,
                        enabled = !busy,
                        onRestore = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) { RecycleBin.restore(entry) }
                                    busy = false
                                    toast(err ?: "已还原到首页目录")
                                    tick++
                                }
                            }
                        },
                        onPurge = { confirmPurge = entry }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (confirmClear) {
        AppDialog(
            onDismissRequest = { confirmClear = false },
            title = "清空回收站",
            actions = {
                DialogActionButton("取消", color = RustedOnBackground.copy(alpha = 0.6f)) { confirmClear = false }
                Spacer(Modifier.weight(1f))
                DialogActionButton("清空", color = RustedError) {
                    confirmClear = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { RecycleBin.clear() }
                        busy = false
                        toast("回收站已清空")
                        tick++
                    }
                }
            }
        ) {
            Text("将彻底删除回收站内全部内容，无法恢复。", fontSize = 13.sp, color = RustedOnBackground)
        }
    }

    confirmPurge?.let { entry ->
        AppDialog(
            onDismissRequest = { confirmPurge = null },
            title = "彻底删除",
            actions = {
                DialogActionButton("取消", color = RustedOnBackground.copy(alpha = 0.6f)) { confirmPurge = null }
                Spacer(Modifier.weight(1f))
                DialogActionButton("删除", color = RustedError) {
                    confirmPurge = null
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RecycleBin.deletePermanently(entry) }
                        busy = false
                        toast(if (ok) "已彻底删除" else "删除失败")
                        tick++
                    }
                }
            }
        ) {
            Text("将彻底删除「${entry.name}」，无法恢复。", fontSize = 13.sp, color = RustedOnBackground)
        }
    }
}

@Composable
private fun RecycleItemCard(
    entry: RecycleEntry,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RustedSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                null, Modifier.size(20.dp),
                tint = if (entry.isDirectory) RustedSecondary else RustedPrimary.copy(alpha = 0.8f)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (entry.isDirectory) "文件夹" else "模组包") + " · ${formatSize(entry.sizeBytes)}",
                    fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    "修改于 ${RECYCLE_TIME_FMT.format(Date(entry.lastModified))}",
                    fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = onRestore, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Restore, "还原到首页目录", Modifier.size(18.dp),
                    tint = if (enabled) RustedPrimary else RustedOnBackground.copy(alpha = 0.2f)
                )
            }
            IconButton(onClick = onPurge, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.DeleteForever, "彻底删除", Modifier.size(18.dp),
                    tint = if (enabled) RustedError else RustedOnBackground.copy(alpha = 0.2f)
                )
            }
        }
    }
}
