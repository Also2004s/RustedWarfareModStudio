package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.completionMatchLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 项目 ini/template 文件缓存（镜像 [ProjectSoundCache]）。
 * 核心节 `复制与`(copyFrom) 引用 ini/template 单位文件（支持 ROOT: 前缀），
 * 因此缓存相对路径字符串。
 */
object ProjectIniFileCache {

    private val fileExtensions = setOf("ini", "template")

    @Volatile
    private var cachedProjectPath: String = ""

    @Volatile
    private var cachedPaths: List<String> = emptyList()

    /**
     * 后台扫描 [projectPath] 下全部子目录的 ini/template 文件并缓存相对路径。
     * 不做深度限制，保证项目根下任意子目录的复制度源文件都能补全到。
     */
    suspend fun refresh(projectPath: String, maxDepth: Int = Int.MAX_VALUE) {
        if (projectPath.isBlank()) return
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return

        val paths = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it.isFile && it.extension.lowercase() in fileExtensions }
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
     * 返回当前缓存中匹配 [prefix] 的 ini/template 相对路径列表（最多 [limit] 条）。
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