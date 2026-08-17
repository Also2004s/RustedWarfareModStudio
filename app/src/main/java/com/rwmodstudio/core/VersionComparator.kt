package com.rwmodstudio.core

import java.io.File

/**
 * 版本对比引擎：比较两个文件夹（或其子文件夹）下的 .ini / .template 文件差异。
 * 使用基于 LCS 的逐行 diff，任何文本差异都会被检测到。
 */
object VersionComparator {

    data class FileDiffResult(
        val filePath: String, // 相对路径
        val ops: List<DiffOp>,
        val addedCount: Int,
        val removedCount: Int,
        val error: String? = null
    )

    data class FolderDiffResult(
        val rootPath: String,
        val metaPath: String,
        val commonFiles: List<FileDiffResult>,
        val rootOnlyFiles: List<String>,
        val metaOnlyFiles: List<String>
    )

    private val VALID_EXTENSIONS = setOf("ini", "template")

    /** 递归获取目录下所有有效文件，返回相对路径集合 */
    fun getAllFiles(dir: File, baseDir: File): Set<String> {
        if (!dir.exists() || !dir.isDirectory) return emptySet()
        val result = mutableSetOf<String>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in VALID_EXTENSIONS) {
                result.add(file.relativeTo(baseDir).path.replace("\\", "/"))
            }
        }
        return result
    }

    /** 比较单个共同文件 */
    private fun compareCommonFile(relPath: String, rootDir: File, metaDir: File): FileDiffResult? {
        val rootFile = File(rootDir, relPath)
        val metaFile = File(metaDir, relPath)
        return try {
            val before = rootFile.readText(Charsets.UTF_8)
            val after = metaFile.readText(Charsets.UTF_8)
            val ops = computeLineDiff(before.lines(), after.lines()).filter { it.type != ' ' }
            if (ops.isEmpty()) return null
            val addedCount = ops.count { it.type == '+' }
            val removedCount = ops.count { it.type == '-' }
            FileDiffResult(relPath, ops, addedCount, removedCount)
        } catch (e: Exception) {
            FileDiffResult(relPath, emptyList(), 0, 0, e.message)
        }
    }

    /** 比较两个文件夹 */
    fun compareFolders(rootDir: File, metaDir: File): FolderDiffResult {
        val rootFiles = getAllFiles(rootDir, rootDir)
        val metaFiles = getAllFiles(metaDir, metaDir)

        val common = rootFiles intersect metaFiles
        val rootOnly = (rootFiles - metaFiles).sorted()
        val metaOnly = (metaFiles - rootFiles).sorted()

        val fileDiffs = common.mapNotNull { compareCommonFile(it, rootDir, metaDir) }

        return FolderDiffResult(
            rootPath = rootDir.absolutePath,
            metaPath = metaDir.absolutePath,
            commonFiles = fileDiffs,
            rootOnlyFiles = rootOnly,
            metaOnlyFiles = metaOnly
        )
    }
}
