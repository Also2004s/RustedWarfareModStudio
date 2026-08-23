package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "SharedImagesRepository"

/**
 * 游戏内置共享图像仓库。
 * 读取 assets/data/shared_images.json（来自逆向库 AssetsID$drawable，游戏内置全部 drawable 图像资源名）。
 * 作为图片补全「SHARED:/CORE: 内置共享图」的候选（basename，不含扩展名，补全时按需拼前缀）。
 * 纯静态只读，不做任何运行时写出，故无需 VerifyManager。
 */
object SharedImagesRepository {

    @Serializable
    private data class SharedImagesFile(
        val version: Int = 0,
        val source: String = "",
        val images: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var cache: Set<String>? = null

    /** 内置共享图像 basename 集合（成功后缓存，进程内）。失败返回空集。 */
    fun get(context: Context): Set<String> {
        cache?.let { return it }
        val result = try {
            val text = context.assets.open("data/shared_images.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<SharedImagesFile>(text).images
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load shared_images.json", e)
            emptySet()
        }
        cache = result
        return result
    }

    fun clearCache() {
        cache = null
    }
}