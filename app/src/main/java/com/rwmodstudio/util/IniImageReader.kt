package com.rwmodstudio.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

object IniImageReader {

    private const val TAG = "IniImageReader"
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")
    private val cache = mutableMapOf<String, ImageBitmap?>()

    /** 采样解码：先读尺寸按 [maxDim] 上限算 inSampleSize，防 2K/4K 大贴图整图解码 OOM 闪退。
     *  供首页目录预览等场景复用（内部已 try-catch，失败返回 null）。 */
    internal fun decodeSampled(file: File, maxDim: Int = 1024): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            Log.e(TAG, "decodeSampled failed for $file", e)
            null
        }
    }

    fun getImageForFile(file: File): ImageBitmap? {
        val key = file.absolutePath
        if (cache.containsKey(key)) return cache[key]

        var result: ImageBitmap? = null
        try {
            if (file.extension.lowercase() == "ini") {
                val content = file.readText(Charsets.UTF_8)
                var inGraphics = false
                for (line in content.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.equals("[graphics]", ignoreCase = true)) { inGraphics = true; continue }
                    if (inGraphics && trimmed.startsWith("[") && trimmed.endsWith("]")) break
                    if (inGraphics && trimmed.startsWith("image:", ignoreCase = true)) {
                        val imageRef = trimmed.substringAfter(":").trim()
                        if (imageRef.isNotEmpty()) {
                            val imageFile = File(file.parentFile, imageRef)
                            if (imageFile.exists() && imageFile.extension.lowercase() in IMAGE_EXTENSIONS) {
                                val bitmap = decodeSampled(imageFile)
                                if (bitmap != null) result = bitmap.asImageBitmap()
                            }
                        }
                        break
                    }
                    if (trimmed.startsWith("image:", ignoreCase = true) && !inGraphics) {
                        val imageRef = trimmed.substringAfter(":").trim()
                        if (imageRef.isNotEmpty()) {
                            val imageFile = File(file.parentFile, imageRef)
                            if (imageFile.exists() && imageFile.extension.lowercase() in IMAGE_EXTENSIONS) {
                                val bitmap = decodeSampled(imageFile)
                                if (bitmap != null) result = bitmap.asImageBitmap()
                            }
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getImageForFile failed for $file", e)
        }

        cache[key] = result
        if (cache.size > 200) cache.clear()
        return result
    }
}
