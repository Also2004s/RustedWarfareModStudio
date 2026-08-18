package com.rwmodstudio.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.BuildConfig
import com.rwmodstudio.core.UpdateChecker
import com.rwmodstudio.core.UpdateDownloader
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 检查更新弹窗状态 */
sealed interface UpdateDialogState {
    data object Checking : UpdateDialogState
    data object NoUpdate : UpdateDialogState
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateDialogState
    data class Downloading(val info: UpdateChecker.UpdateInfo, val bytes: Long, val total: Long) : UpdateDialogState
    data class ReadyToInstall(val info: UpdateChecker.UpdateInfo) : UpdateDialogState
    data class Error(val message: String) : UpdateDialogState
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

/**
 * 更新相关弹窗的统一宿主。SettingsScreen 手动检查与 MainApp 自动检查共用。
 * @param state 当前弹窗状态（null 不显示）
 * @param onStateChange 状态变化回调（下载进度、关闭弹窗等）
 * @param onVersionDismissed 用户关闭"发现新版本"弹窗时回调（用于自动提示记忆"该版本不再提示"）
 */
@Composable
fun UpdateDialogHost(
    state: UpdateDialogState?,
    onStateChange: (UpdateDialogState?) -> Unit,
    onVersionDismissed: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    when (val s = state) {
        is UpdateDialogState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = RustedPrimary, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在检查最新版本...", fontSize = 14.sp, color = RustedOnBackground)
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
        is UpdateDialogState.NoUpdate -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text("检查更新") },
            text = {
                Text(
                    "当前已是最新版本（${BuildConfig.VERSION_NAME}）",
                    fontSize = 14.sp,
                    color = RustedOnBackground
                )
            },
            confirmButton = {
                TextButton(onClick = { onStateChange(null) }) { Text("确定") }
            }
        )
        is UpdateDialogState.Available -> AlertDialog(
            onDismissRequest = {
                onVersionDismissed(s.info.versionName)
                onStateChange(null)
            },
            title = { Text("发现新版本 ${s.info.versionName}") },
            text = {
                Column {
                    Text("当前版本：${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    if (s.info.publishedAt != null) {
                        Text("发布时间：${s.info.publishedAt.substringBefore('T')}", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (s.info.body.isNotBlank()) {
                        Text(
                            text = s.info.body,
                            fontSize = 12.sp,
                            color = RustedOnBackground,
                            lineHeight = 16.sp,
                            maxLines = 14,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("点击下载更新以查看更新内容。", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = s.info.apkUrl ?: s.info.htmlUrl
                    if (s.info.apkUrl == null) {
                        // 无 APK 直链，回退到浏览器打开详情页
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "无法打开下载页面", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        onStateChange(null)
                        return@TextButton
                    }
                    // 有 APK 直链：内置下载并显示进度
                    val target = UpdateDownloader.apkTargetFile(s.info.versionName)
                    onStateChange(UpdateDialogState.Downloading(s.info, 0, -1))
                    scope.launch {
                        try {
                            val downloaded = withContext(Dispatchers.IO) {
                                UpdateDownloader.downloadApk(
                                    url = url,
                                    targetFile = target,
                                    onProgress = { bytes, total ->
                                        onStateChange(UpdateDialogState.Downloading(s.info, bytes, total))
                                    }
                                )
                            }
                            // 下载完成后自动调起系统安装界面（若需授权则先引导去授权页）
                            withContext(Dispatchers.IO) {
                                UpdateDownloader.installApk(context.applicationContext, downloaded)
                            }
                            onStateChange(UpdateDialogState.ReadyToInstall(s.info))
                        } catch (e: Exception) {
                            onStateChange(UpdateDialogState.Error("下载失败：${e.message ?: "未知错误"}"))
                        }
                    }
                }) { Text("下载更新") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onVersionDismissed(s.info.versionName)
                    onStateChange(null)
                }) { Text("以后再说") }
            }
        )
        is UpdateDialogState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 v${s.info.versionName}") },
            text = {
                Column {
                    Text(
                        "正在下载新版本安装包，请勿关闭应用...",
                        fontSize = 13.sp,
                        color = RustedOnBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = if (s.total > 0) (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) else 0f,
                        color = RustedPrimary,
                        trackColor = RustedPrimary.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (s.total > 0) {
                            "${formatSize(s.bytes)} / ${formatSize(s.total)}（${(s.bytes * 100 / s.total).toInt()}%）"
                        } else {
                            "${formatSize(s.bytes)}"
                        },
                        fontSize = 11.sp,
                        color = RustedOnBackground.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
        is UpdateDialogState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text("准备安装") },
            text = {
                Column {
                    Text("安装包已下载完成。", fontSize = 14.sp, color = RustedOnBackground)
                    Spacer(Modifier.height(6.dp))
                    if (!UpdateDownloader.canRequestPackageInstalls(context)) {
                        Text(
                            "首次安装需要允许「安装未知应用」权限，请在系统设置中开启后重试。",
                            fontSize = 12.sp,
                            color = RustedOnBackground.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            "如果安装界面没有弹出，请点击下方按钮重试。",
                            fontSize = 12.sp,
                            color = RustedOnBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = UpdateDownloader.apkTargetFile(s.info.versionName)
                    if (!UpdateDownloader.canRequestPackageInstalls(context)) {
                        UpdateDownloader.openUnknownSourcesSettings(context)
                    } else {
                        UpdateDownloader.installApk(context.applicationContext, target)
                    }
                }) { Text("重新安装") }
            },
            dismissButton = {
                TextButton(onClick = { onStateChange(null) }) { Text("完成") }
            }
        )
        is UpdateDialogState.Error -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text("检查更新失败") },
            text = {
                Column {
                    Text("无法获取最新版本信息，请检查网络后重试。", fontSize = 14.sp, color = RustedOnBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(s.message, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                }
            },
            confirmButton = {
                TextButton(onClick = { onStateChange(null) }) { Text("确定") }
            }
        )
        null -> Unit
    }
}
