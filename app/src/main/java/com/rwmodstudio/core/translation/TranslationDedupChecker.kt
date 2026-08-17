package com.rwmodstudio.core.translation

import android.os.Environment
import com.rwmodstudio.core.RwmodPaths
import java.io.File

/**
 * 翻译查重工具
 * 检测项目中已翻译内容与翻译库的重复
 * 对齐 Python 的一键查重.py 逻辑
 */
class TranslationDedupChecker {

    data class DuplicateInfo(
        val key: String,
        val source: String,
        val file: String = "",
        val line: Int = 0
    )

    /**
     * 查重进度回调
     * @param stage 当前阶段描述
     * @param progress 0.0 ~ 1.0
     */
    data class ProgressInfo(val stage: String, val progress: Float)

    companion object {
        /**
         * 检查翻译库字典中的中文值重复
         * 只查中文，不查英文
         */
        fun checkDictDuplicates(
            dict: TranslationDict,
            onProgress: ((ProgressInfo) -> Unit)? = null
        ): List<DuplicateInfo> {
            onProgress?.invoke(ProgressInfo("检查翻译库中文重复...", 0f))
            val duplicates = mutableListOf<DuplicateInfo>()

            // 收集所有中文值，检查是否有重复
            val allZhValues = mutableListOf<String>() // 所有中文值

            // Section 中文值
            for (enSection in dict.getAllEnglishSections()) {
                val zh = dict.getSectionTranslation(enSection)
                if (zh.isNotEmpty() && zh != enSection) allZhValues.add(zh)
            }

            onProgress?.invoke(ProgressInfo("检查重复...", 0.5f))

            // Key 中文值
            for (key in dict.getAllEnglishKeys()) {
                val zh = dict.getTranslation(key)
                if (zh.isNotEmpty() && zh != key) allZhValues.add(zh)
            }

            // 找出重复的中文值
            val seen = mutableSetOf<String>()
            for (zh in allZhValues) {
                if (!seen.add(zh)) {
                    // 已经见过这个中文值，说明重复
                    if (duplicates.none { it.key == zh }) {
                        duplicates.add(DuplicateInfo(zh, "中文值重复"))
                    }
                }
            }

            onProgress?.invoke(ProgressInfo("检查完成", 1f))
            return duplicates
        }

        /**
         * 检查项目文件中的中文重复
         * 用已加载的翻译引擎翻译一次，对比原文找出被翻译的中文词
         */
        fun checkProjectFiles(
            engine: TranslationEngine,
            projectPath: String? = null,
            onProgress: ((ProgressInfo) -> Unit)? = null
        ): List<DuplicateInfo> {
            onProgress?.invoke(ProgressInfo("扫描项目文件...", 0f))
            val duplicates = mutableListOf<DuplicateInfo>()

            // 扫描项目文件
            val searchPaths = mutableListOf<String>()
            projectPath?.let { searchPaths.add(it) }
            if (searchPaths.isEmpty()) {
                val es = Environment.getExternalStorageDirectory()
                val defaultPaths = listOf(
                    File(es, "rustedWarfare/units"),
                    File(es, "rustedWarfare/mods"),
                    File(es, "rustedWarfare")
                )
                for (path in defaultPaths) {
                    if (path.exists()) { searchPaths.add(path.absolutePath); break }
                }
            }

            val allFiles = mutableListOf<File>()
            for (path in searchPaths) {
                val dir = File(path)
                if (!dir.exists()) continue
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "ini" || it.extension == "template") }
                    .forEach { allFiles.add(it) }
            }

            val totalFiles = allFiles.size
            if (totalFiles == 0) {
                onProgress?.invoke(ProgressInfo("未找到项目文件", 1f))
                return duplicates
            }

            // 输出目录：RWmod/dedup
            val outputDir = RwmodPaths.dedupDir
            outputDir.mkdirs()

            onProgress?.invoke(ProgressInfo("扫描 $totalFiles 个文件...", 0.1f))

            for ((fileIdx, file) in allFiles.withIndex()) {
                val progress = 0.1f + (fileIdx + 1).toFloat() / totalFiles * 0.9f
                onProgress?.invoke(ProgressInfo("检查 ${file.name} (${fileIdx + 1}/$totalFiles)", progress))

                try {
                    val content = file.readText(Charsets.UTF_8)
                    val lines = content.split("\n")
                    val translatedLines = mutableListOf<String>()
                    val fileDuplicates = mutableSetOf<String>()

                    for ((lineNum, line) in lines.withIndex()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            translatedLines.add(line)
                            continue
                        }

                        // 用翻译引擎翻译整行（中文→英文）
                        val translated = engine.translateToEnglish(line, false)

                        // 对比原文，找出被翻译的中文词
                        if (translated != line) {
                            // 提取原文中的中文词
                            val chineseWords = Regex("[\\u4e00-\\u9fff]+").findAll(line).map { it.value }.toList()
                            for (zh in chineseWords) {
                                // 检查这个词是否被翻译了（翻译后不存在了）
                                if (!translated.contains(zh)) {
                                    if (zh !in fileDuplicates) {
                                        fileDuplicates.add(zh)
                                        duplicates.add(DuplicateInfo(key = zh, source = file.absolutePath, line = lineNum + 1))
                                    }
                                }
                            }
                        }
                        translatedLines.add(translated)
                    }

                    // 保存翻译后的文件到查重文件夹（翻译后与原文不同才输出）
                    val translatedContent = translatedLines.joinToString("\n")
                    if (translatedContent != content) {
                        val relPath = file.absolutePath.substringAfter("rustedWarfare/").replace("\\", "/")
                        val outFile = File(outputDir, relPath)
                        outFile.parentFile?.mkdirs()
                        outFile.writeText(translatedContent, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    // 跳过无法读取的文件
                }
            }

            onProgress?.invoke(ProgressInfo("项目检查完成", 1f))
            return duplicates
        }

        /**
         * 执行完整查重
         */
        fun runFullCheck(
            engine: TranslationEngine,
            projectPath: String? = null,
            onProgress: ((ProgressInfo) -> Unit)? = null
        ): List<DuplicateInfo> {
            onProgress?.invoke(ProgressInfo("扫描项目文件...", 0f))
            return checkProjectFiles(engine, projectPath, onProgress)
        }

        /**
         * 清理过期的查重输出文件。
         * 遍历 [RwmodPaths.dedupDir] 下的所有文件，删除源项目文件已不存在的查重结果。
         *
         * 由于查重输出以相对于项目根目录的路径存储（如 rustedWarfare/units/xxx.ini），
         * 需要尝试常见的搜索路径来定位源文件：
         *   1. /sdcard/rustedWarfare/...
         *   2. /sdcard/rustedWarfare/units/...
         *   3. /sdcard/rustedWarfare/mods/...
         *
         * @return 被删除的文件数量
         */
        fun cleanStaleEntries(): Int {
            val es = android.os.Environment.getExternalStorageDirectory()
            val sourceRoots = listOf(
                java.io.File(es, "rustedWarfare"),
                java.io.File(es, "rustedWarfare/units"),
                java.io.File(es, "rustedWarfare/mods")
            )
            val dedupDir = com.rwmodstudio.core.RwmodPaths.dedupDir
            if (!dedupDir.exists()) return 0

            var removed = 0
            val toDelete = mutableListOf<java.io.File>()

            dedupDir.walkTopDown().filter { it.isFile }.forEach { cacheFile ->
                val relPath = cacheFile.relativeTo(dedupDir).path
                // 检查能否找到源文件
                val sourceExists = sourceRoots.any { root ->
                    java.io.File(root, relPath).exists()
                }
                if (!sourceExists) {
                    toDelete.add(cacheFile)
                }
            }

            for (file in toDelete) {
                try {
                    file.delete()
                    removed++
                } catch (e: Exception) { android.util.Log.w("TranslationDedupChecker", "查重操作失败", e) }
            }

            // 清理空的父目录
            dedupDir.walkBottomUp().filter { it.isDirectory && it != dedupDir && it.listFiles()?.isEmpty() == true }
                .forEach { try { it.delete() } catch (e: Exception) { android.util.Log.w("TranslationDedupChecker", "查重操作失败", e) } }

            // 若所有查重文件都已清理，也清除 dedup_words.txt
            val hasRemaining = dedupDir.walkTopDown().any { it.isFile && it.name != "dedup_words.txt" }
            if (!hasRemaining) {
                try { com.rwmodstudio.core.RwmodPaths.dedupWordsFile.delete() } catch (e: Exception) { android.util.Log.w("TranslationDedupChecker", "查重操作失败", e) }
            }

            return removed
        }
    }
}
