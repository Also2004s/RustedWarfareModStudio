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
     * 后台扫描 [projectPath] 下全部子目录的图片文件并缓存项目相对路径（斜杠分隔，不带 ROOT: 前缀）。
     * 不做深度限制，保证项目根下任意子目录的图片都能补全到。
     */
    suspend fun refresh(projectPath: String, maxDepth: Int = Int.MAX_VALUE) {
        if (projectPath.isBlank()) return
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return

        val paths = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it.isFile && it.extension.lowercase() in imageExtensions }
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
     * 返回当前缓存中匹配 [prefix] 的图片项目相对路径列表（最多 [limit] 条）。
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

/**
 * 把项目相对路径格式化为补全插入文本（与 @文件引用 一致）：
 * 资源位于当前编辑文件所在目录（[currentFileDir]）下且不跨子目录时返回裸文件名，
 * 否则返回 `ROOT:/` + 项目相对路径。
 */
fun formatProjectRefInsertValue(relativePath: String, currentFileDir: String, projectRoot: String): String {
    val base = relativePath.substringAfterLast('/').substringAfterLast('\\')
    if (currentFileDir.isBlank() || projectRoot.isBlank()) return relativePath
    val normalizedDir = File(currentFileDir).absolutePath.trimEnd('/')
    val parentDir = File(File(projectRoot, relativePath).absolutePath).parentFile?.absolutePath ?: ""
    return if (parentDir == normalizedDir) base else "ROOT:/$relativePath"
}
