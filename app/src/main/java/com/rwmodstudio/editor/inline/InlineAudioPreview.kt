package com.rwmodstudio.editor.inline

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 编辑器内音频试听（点音乐图标直接播放）。
 *
 * 与播放页的 AudioPlayerController 分开：这里是"点一下听一下"的轻量试听，
 * 不占页面、不做进度条、不循环；同一时刻只播一个文件，重复点同一文件 = 停止。
 */
object InlineAudioPreview {

    private const val TAG = "InlineAudioPreview"

    private var player: MediaPlayer? = null
    private var currentPath: String? = null

    /** 当前正在播放的文件绝对路径（null = 没在播） */
    @Volatile
    var playingPath: String? = null
        private set

    /** 播放状态变化监听（主线程回调），供控制器刷新图标 */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 点同文件=停止，点别的=切歌并播放。路径为空则忽略。 */
    fun toggle(path: String?) {
        if (path.isNullOrBlank()) return
        if (currentPath == path && runCatching { player?.isPlaying == true }.getOrDefault(false)) {
            stop()
            return
        }
        release()
        val mp = MediaPlayer()
        player = mp
        currentPath = path
        playingPath = path
        notifyChanged()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setOnPreparedListener { prepared ->
                if (player !== mp) return@setOnPreparedListener
                prepared.start()
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                playingPath = null
                currentPath = null
                notifyChanged()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra path=$path")
                if (player === mp) {
                    release()
                    playingPath = null
                    currentPath = null
                    notifyChanged()
                }
                true
            }
            mp.setDataSource(path)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "play failed: $path", e)
            release()
            playingPath = null
            currentPath = null
            notifyChanged()
        }
    }

    fun stop() {
        release()
        playingPath = null
        currentPath = null
        notifyChanged()
    }

    fun isPlaying(path: String?): Boolean =
        path != null && playingPath == path && runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun release() {
        try {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.reset()
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
        player = null
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
