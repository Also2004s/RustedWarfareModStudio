package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.completionMatchLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 项目图片路径缓存。
 *
 * 图片路径值补全在主线程扫描目录可能卡顿，因此在进入编辑器或项目时后台扫描一次，
 * 后续补全只读取缓存。
 */
object ProjectImageCache {

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp")

    @Volatile
    private var cachedProjectPath: String = ""

    @Volatile
    private var cachedPaths: List<String> = emptyList()

    /**
     * 后台扫描 [projectPath] 下的图片文件并缓存 ROOT:/ 相对路径。
     */
    suspend fun refresh(projectPath: String, maxDepth: Int = 4) {
        if (projectPath.isBlank()) return
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return

        val paths = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                .map { file ->
                    val relative = file.absolutePath.removePrefix(root.absolutePath)
                        .removePrefix(File.separator)
                        .replace(File.separator, "/")
                    "ROOT:/$relative"
                }
                .toList()
        }

        cachedProjectPath = projectPath
        cachedPaths = paths
    }

    /**
     * 返回当前缓存中匹配 [prefix] 的图片路径列表（最多 [limit] 条）。
     * 若 [projectPath] 与缓存不一致，返回空列表，等待外部刷新。
     */
    fun query(projectPath: String, prefix: String, limit: Int = 50): List<String> {
        if (projectPath.isBlank() || projectPath != cachedProjectPath) return emptyList()
        return cachedPaths
            .map { it to completionMatchLevel(prefix, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
            .map { it.first }
            .take(limit)
    }

    fun clear() {
        cachedProjectPath = ""
        cachedPaths = emptyList()
    }
}
