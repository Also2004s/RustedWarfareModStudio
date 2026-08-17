package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.completionMatchLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 项目音频文件缓存（镜像 [ProjectImageCache]）。
 * 声音引用值（如 playSoundToPlayer: confirm.wav）使用相对路径（不带 ROOT: 前缀），
 * 因此缓存相对路径字符串。
 */
object ProjectSoundCache {

    private val audioExtensions = setOf("ogg", "wav", "mp3")

    @Volatile
    private var cachedProjectPath: String = ""

    @Volatile
    private var cachedPaths: List<String> = emptyList()

    /**
     * 后台扫描 [projectPath] 下的音频文件并缓存相对路径。
     */
    suspend fun refresh(projectPath: String, maxDepth: Int = 4) {
        if (projectPath.isBlank()) return
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return

        val paths = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it.isFile && it.extension.lowercase() in audioExtensions }
                .map { file ->
                    file.absolutePath.removePrefix(root.absolutePath)
                        .removePrefix(File.separator)
                        .replace(File.separator, "/")
                }
                .toList()
        }

        cachedProjectPath = projectPath
        cachedPaths = paths
    }

    /**
     * 返回当前缓存中匹配 [prefix] 的音频相对路径列表（最多 [limit] 条）。
     * 若 [projectPath] 与缓存不一致，返回空列表，等待外部刷新。
     */
    fun query(projectPath: String, prefix: String, limit: Int = 50): List<String> {
        if (projectPath.isBlank() || projectPath != cachedProjectPath) return emptyList()
        return cachedPaths
            .map { it to matchLevel(it, prefix) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
            .map { it.first }
            .take(limit)
    }

    private fun matchLevel(name: String, prefix: String): Int {
        if (prefix.isEmpty()) return 2
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return maxOf(completionMatchLevel(prefix, name), completionMatchLevel(prefix, base))
    }

    fun clear() {
        cachedProjectPath = ""
        cachedPaths = emptyList()
    }
}