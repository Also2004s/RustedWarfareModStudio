package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.media.AudioPlayerController
import com.rwmodstudio.ui.theme.*
import java.io.File
import java.util.Locale

/**
 * 音频播放页（.ogg / .wav / .mp3 / .flac / .m4a 等）。
 *
 * 页面自身不画标题栏（全局顶栏显示文件名），进入即自动播放；
 * 离开页面（组合销毁）或切换到其他音频文件时停止并释放 MediaPlayer。
 */
@Composable
fun AudioPlayerScreen(filePath: String) {
    val file = remember(filePath) { File(filePath) }
    val scope = rememberCoroutineScope()
    val controller = remember { AudioPlayerController(scope) }

    // 切换文件：load 内部会先释放旧实例，重新异步准备并播放
    LaunchedEffect(filePath) { controller.load(filePath) }
    // 离开播放页：停止播放并释放播放器资源
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableFloatStateOf(0f) }

    val duration = controller.durationMs.coerceAtLeast(0)
    val sliderMax = maxOf(duration, 1).toFloat()
    val displayMs = if (dragging) dragMs.toInt() else controller.positionMs

    Box(
        Modifier
            .fillMaxSize()
            .background(RustedBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 唱片图标
            Box(
                Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(RustedPrimary.copy(alpha = 0.12f))
                    .border(1.dp, RustedPrimary.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    Modifier.size(60.dp),
                    tint = RustedPrimary
                )
            }

            Spacer(Modifier.height(22.dp))

            // 文件名
            Text(
                text = file.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = RustedOnBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            // 所在目录
            Text(
                text = file.parent ?: "",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(30.dp))

            // 进度条（拖动中只更新本地值，松手才 seek）
            Slider(
                value = displayMs.toFloat().coerceIn(0f, sliderMax),
                onValueChange = {
                    dragging = true
                    dragMs = it
                },
                onValueChangeFinished = {
                    controller.seekTo(dragMs.toInt())
                    dragging = false
                },
                valueRange = 0f..sliderMax,
                enabled = controller.isPrepared,
                colors = SliderDefaults.colors(
                    thumbColor = RustedPrimary,
                    activeTrackColor = RustedPrimary,
                    inactiveTrackColor = RustedOnBackground.copy(alpha = 0.15f)
                )
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatAudioTime(displayMs),
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.7f)
                )
                Text(
                    if (duration > 0) formatAudioTime(duration) else "--:--",
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // 播放 / 暂停
            FilledIconButton(
                onClick = { controller.togglePlayPause() },
                enabled = controller.isPrepared,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = RustedPrimary,
                    contentColor = RustedBackground
                )
            ) {
                Icon(
                    if (controller.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    Modifier.size(38.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 循环开关（至少支持单曲循环）
            FilterChip(
                selected = controller.looping,
                onClick = { controller.toggleLoop() },
                label = { Text(if (controller.looping) "单曲循环" else "不循环", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = RustedPrimary.copy(alpha = 0.18f),
                    selectedLabelColor = RustedPrimary,
                    selectedLeadingIconColor = RustedPrimary
                )
            )

            Spacer(Modifier.height(18.dp))

            when {
                controller.errorMessage != null -> Text(
                    controller.errorMessage ?: "",
                    fontSize = 12.sp,
                    color = RustedError,
                    textAlign = TextAlign.Center
                )
                !controller.isPrepared -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = RustedPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在加载…", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** 毫秒 → mm:ss（超过 1 小时显示 h:mm:ss）。 */
private fun formatAudioTime(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
