package com.rwmodstudio.util

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

object IniImageReader {

    private const val TAG = "IniImageReader"
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")
    private val cache = mutableMapOf<String, ImageBitmap?>()

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
                                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
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
                                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
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
