package com.rwmodstudio.core.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频播放控制器：封装 Android 原生 [MediaPlayer]。
 *
 * - [load]：切歌/首次进入时调用，内部先释放旧实例；setDataSource 放到 IO 线程，
 *   prepareAsync 异步准备，不阻塞主线程。
 * - [release]：离开播放页时必须调用（页面 DisposableEffect 负责），停止播放并释放资源。
 * - 进度 [positionMs] 由内部轮询协程刷新（仅在播放中运行，约 200ms 一次）。
 *
 * 所有公开方法均应在主线程调用；构造时传入的 [scope] 随播放页销毁自动取消。
 */
class AudioPlayerController(private val scope: CoroutineScope) {

    var isPlaying by mutableStateOf(false)
        private set
    var isPrepared by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    /** 单曲循环开关（默认开启）。 */
    var looping by mutableStateOf(true)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** 加载并自动开始播放 [path]；切换文件时先释放上一个实例。 */
    suspend fun load(path: String) {
        release()
        errorMessage = null
        // 文件存在性检查同样放到 IO 线程
        val exists = withContext(Dispatchers.IO) { File(path).exists() }
        if (!exists) {
            errorMessage = "文件不存在"
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            // setDataSource(String) 会打开文件描述符，放到 IO 线程避免主线程磁盘阻塞
            withContext(Dispatchers.IO) { mp.setDataSource(path) }
            mp.setOnPreparedListener { prepared ->
                if (player !== mp) return@setOnPreparedListener
                isPrepared = true
                durationMs = prepared.duration.coerceAtLeast(0)
                prepared.isLooping = looping
                prepared.start()
                isPlaying = true
                positionMs = 0
                startTicker()
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                // 未开启循环时自然播完：停在末尾，等待用户再次播放
                isPlaying = false
                positionMs = durationMs
                stopTicker()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (player !== mp) return@setOnErrorListener true
                errorMessage = "播放失败（$what / $extra）"
                isPrepared = false
                isPlaying = false
                stopTicker()
                true
            }
            mp.prepareAsync()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 切歌/离页导致的取消：只清理本次实例，不能误伤后创建的新实例
            if (player === mp) release()
            throw e
        } catch (e: Exception) {
            if (player === mp) {
                errorMessage = "无法播放：${e.message ?: "未知错误"}"
                release()
            }
        }
    }

    fun togglePlayPause() {
        val mp = player ?: return
        if (!isPrepared) return
        try {
            if (mp.isPlaying) {
                positionMs = mp.currentPosition.coerceAtLeast(0)
                mp.pause()
                isPlaying = false
                stopTicker()
            } else {
                // 播完后再次点击：从头开始
                if (durationMs > 0 && positionMs >= durationMs) {
                    mp.seekTo(0)
                    positionMs = 0
                }
                mp.start()
                isPlaying = true
                startTicker()
            }
        } catch (_: IllegalStateException) {
            // 播放器状态异常（如正在准备中），忽略本次点击
        }
    }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        if (!isPrepared) return
        val target = ms.coerceIn(0, maxOf(durationMs, 1))
        try {
            mp.seekTo(target)
            positionMs = target
        } catch (_: IllegalStateException) {
        }
    }

    fun toggleLoop() {
        looping = !looping
        try {
            player?.isLooping = looping
        } catch (_: IllegalStateException) {
        }
    }

    /** 停止播放并释放 MediaPlayer（可重复调用）。 */
    fun release() {
        stopTicker()
        player?.let { mp ->
            try {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
            } catch (_: Exception) {
            }
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.reset()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
        isPrepared = false
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player
                if (mp != null && isPrepared) {
                    val pos = try {
                        mp.currentPosition
                    } catch (_: IllegalStateException) {
                        null
                    }
                    if (pos != null) positionMs = pos.coerceAtLeast(0)
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
