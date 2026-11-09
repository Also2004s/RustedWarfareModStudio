package com.rwmodstudio.core

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 图片像素尺寸（宽×高）。 */
data class ImageSize(val width: Int, val height: Int) {
    /** 展示文本，形如 `64×64`。 */
    val label: String get() = "$width×$height"
}

/**
 * 图片尺寸缓存。
 *
 * 只通过 [BitmapFactory.Options.inJustDecodeBounds] 读取文件头，不解码整图；
 * 结果按绝对路径缓存，并用 lastModified + length 校验，文件被替换后自动重读。
 */
object ImageSizeCache {

    private const val TAG = "ImageSizeCache"
    private const val MAX_ENTRIES = 500

    private data class Entry(
        val size: ImageSize?,
        val lastModified: Long,
        val length: Long
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * 同步读取图片尺寸（调用方需保证在 IO 线程）。
     * 文件不存在、读取失败或尺寸无效时返回 null（不返回错误数字）。
     */
    fun get(file: File): ImageSize? {
        if (!file.isFile) {
            cache.remove(file.absolutePath)
            return null
        }
        val path = file.absolutePath
        val lastModified = file.lastModified()
        val length = file.length()
        val cached = cache[path]
        if (cached != null && cached.lastModified == lastModified && cached.length == length) {
            return cached.size
        }
        val size = readBounds(file)
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[path] = Entry(size, lastModified, length)
        return size
    }

    /** 在 IO 线程读取尺寸，供 Compose / 协程调用。 */
    suspend fun load(file: File): ImageSize? = withContext(Dispatchers.IO) { get(file) }

    /** 文件被替换或删除后主动失效缓存（正常展示依赖 mtime/size 自动校验）。 */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    fun clear() {
        cache.clear()
    }

    private fun readBounds(file: File): ImageSize? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageSize(options.outWidth, options.outHeight)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "readBounds failed for $file", e)
            null
        }
    }
}
