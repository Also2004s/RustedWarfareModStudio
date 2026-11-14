package com.rwmodstudio.editor.inline

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.rwmodstudio.util.IniImageReader
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHint
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.concurrent.Executors

/**
 * 编辑器内联装饰控制器。
 *
 * 职责：
 * - 只扫描**可见行窗口**（首行-2 ~ 末行+2），大文件（2 万行级）也不会全量扫描；
 * - 扫描与缩略图解码放后台线程，主线程只做 `setInlayHints`；
 * - 滚动/输入/换文件/字号变化触发防抖刷新；
 * - 点击分发：色块 → 宿主调色盘；音乐 → 试听；文本文件 → 宿主 tab 跳转。
 *
 * 所有公开方法必须在主线程调用（内部有 Handler 兜底）。生命周期由 SoraCodeEditor 管理。
 */
class InlineDecorController(
    private val editor: CodeEditor,
    context: Context,
    private val onColorClick: (line: Int, decor: InlineDecor) -> Unit,
    private val onFileClick: (String) -> Unit,
    private val onImageClick: (String) -> Unit = {}
) {

    private companion object {
        const val TAG = "InlineDecor"
        /** 缩略图解码上限（绘制时还要缩到字高，96 足够清晰） */
        const val THUMB_MAX_DIM = 96
        /** 滚动后的刷新防抖 */
        const val SCROLL_REFRESH_DELAY = 110L
        /** 输入后的刷新防抖（避开 TextMate 异步分析） */
        const val CONTENT_REFRESH_DELAY = 300L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "inline-decor-scan").apply { isDaemon = true }
    }
    /** 缩略图缓存：key = 绝对路径，条目数上限 32 */
    private val thumbCache = LruCache<String, Bitmap>(32)

    private val renderer = InlineDecorRenderer(
        appContext,
        thumbnailProvider = { path -> thumbCache.get(path) },
        isPlaying = { path -> InlineAudioPreview.isPlaying(path) }
    )

    /** 音频播放状态变化时刷新图标 */
    private val audioListener: () -> Unit = { scheduleRefresh(0) }

    private var enabled = false
    private var fileDir = ""
    private var projectRoot = ""
    private var generation = 0
    private var pending: Runnable? = null
    @Volatile
    private var disposed = false

    init {
        try {
            editor.registerInlayHintRenderer(renderer)
        } catch (e: Exception) {
            Log.e(TAG, "registerInlayHintRenderer failed", e)
        }
        InlineAudioPreview.addListener(audioListener)
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            cancelPending()
            applyHints(emptyList())
        } else {
            scheduleRefresh(0)
        }
    }

    /** 切换文件/项目时更新上下文（缩略图缓存按文件区分，直接清空最省事） */
    fun setPaths(root: String, dir: String) {
        if (projectRoot == root && fileDir == dir) return
        projectRoot = root
        fileDir = dir
        thumbCache.evictAll()
        if (enabled) scheduleRefresh(0)
    }

    /** 防抖刷新（delayMs=0 立即执行） */
    fun scheduleRefresh(delayMs: Long) {
        if (!enabled || disposed) return
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            refreshNow()
        }
        pending = r
        handler.postDelayed(r, delayMs.coerceAtLeast(0L))
    }

    fun onContentChanged() = scheduleRefresh(CONTENT_REFRESH_DELAY)

    fun onScrolled() = scheduleRefresh(SCROLL_REFRESH_DELAY)

    /** 点击某个 hint：按装饰类型分发 */
    fun handleClick(hint: InlayHint) {
        val decorHint = hint as? InlineDecorHint ?: return
        val decor = decorHint.decors.firstOrNull() ?: return
        when (decor.kind) {
            InlineDecorKind.COLOR -> onColorClick(decorHint.line, decor)
            InlineDecorKind.AUDIO -> {
                InlineAudioPreview.toggle(decor.resolvedPath)
                scheduleRefresh(0)
            }

            InlineDecorKind.FILE -> decor.resolvedPath?.let { onFileClick(it) }
            // 点击图片缩略图：宿主全屏查看原图
            InlineDecorKind.IMAGE -> decor.resolvedPath?.let { onImageClick(it) }
        }
    }

    fun dispose() {
        disposed = true
        cancelPending()
        InlineAudioPreview.removeListener(audioListener)
        worker.shutdownNow()
        thumbCache.evictAll()
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun refreshNow() {
        if (!enabled || disposed) return
        if (!editor.isAttachedToWindow) return
        val gen = ++generation
        val content = try {
            editor.text
        } catch (e: Exception) {
            Log.w(TAG, "read text failed", e)
            return
        }
        val lineCount = try {
            content.lineCount
        } catch (e: Exception) {
            return
        }
        if (lineCount <= 0) return

        // 可见行窗口（上下各留 2 行，滚动时预取）
        val first = (editor.firstVisibleLine - 2).coerceAtLeast(0)
        val last = (editor.lastVisibleLine + 2).coerceAtMost(lineCount - 1)
        if (last < first) return

        val dir = fileDir
        val root = projectRoot
        val lines = ArrayList<Pair<Int, String>>(last - first + 1)
        for (i in first..last) {
            lines.add(i to content.getLineString(i))
        }

        worker.execute {
            var decodedNew = false
            val hints = ArrayList<InlineDecorHint>()
            for ((line, text) in lines) {
                val decors = InlineDecorScanner.scanLine(text, dir, root)
                if (decors.isEmpty()) continue
                // 一个值一个 hint：不同值的列天然不同，点击能精确定位到具体那一块
                for (d in decors) {
                    hints.add(InlineDecorHint(line, d.endCol, listOf(d)))
                    if (d.kind == InlineDecorKind.IMAGE) {
                        val p = d.resolvedPath ?: continue
                        if (thumbCache.get(p) == null) {
                            val bmp = IniImageReader.decodeSampled(File(p), THUMB_MAX_DIM)
                            if (bmp != null) {
                                thumbCache.put(p, bmp)
                                decodedNew = true
                            }
                        }
                    }
                }
            }
            handler.post {
                if (disposed || gen != generation) return@post
                applyHints(hints)
                // 有新解码的缩略图：再刷一次把图片画出来（第二次全部命中缓存，不会再解码）
                if (decodedNew) scheduleRefresh(0)
            }
        }
    }

    private fun applyHints(hints: List<InlineDecorHint>) {
        if (disposed) return
        try {
            val container = InlayHintsContainer()
            hints.forEach { container.add(it) }
            editor.setInlayHints(container)
        } catch (e: Exception) {
            Log.w(TAG, "setInlayHints failed", e)
        }
    }
}
