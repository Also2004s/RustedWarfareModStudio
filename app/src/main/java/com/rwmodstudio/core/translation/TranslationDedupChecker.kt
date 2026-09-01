package com.rwmodstudio.core.translation

import android.os.Environment
import com.rwmodstudio.core.RwmodPaths
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
         *
         * 文件之间相互独立（读不同文件、写不同输出文件），按 CPU 核数并行处理，
         * 翻译引擎字典只读，并行调用安全（查重不依赖引擎内部统计）。
         */
        suspend fun checkProjectFiles(
            engine: TranslationEngine,
            projectPath: String? = null,
            onProgress: ((ProgressInfo) -> Unit)? = null
        ): List<DuplicateInfo> = withContext(Dispatchers.Default) {
            onProgress?.invoke(ProgressInfo("扫描项目文件...", 0f))
            val duplicates = Collections.synchronizedList(mutableListOf<DuplicateInfo>())

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
                return@withContext duplicates.toList()
            }

            // 输出目录：RWmod/dedup
            val outputDir = RwmodPaths.dedupDir
            outputDir.mkdirs()

            onProgress?.invoke(ProgressInfo("扫描 $totalFiles 个文件...", 0.1f))

            // 处理单个文件：翻译整行对比原文，提取被翻译的中文词，写查重输出
            fun checkOneFile(file: File) {
                val fileDuplicates = mutableSetOf<String>()
                try {
                    val content = file.readText(Charsets.UTF_8)
                    val lines = content.split("\n")
                    // 整文件翻译一次（processLines 一次处理所有行）：
                    // 相比逐行翻译，能正确识别多行字符串（"""..."""）并整体翻译（更准确），
                    // 且实测多线程已激活（CPU 5 核并行），瓶颈在翻译引擎而非调用方式。
                    val translatedContent = engine.translateToEnglish(content, false)
                    val translatedLines = translatedContent.split("\n")

                    for ((lineNum, line) in lines.withIndex()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                        // 对比原文，找出被翻译的中文词
                        val translated = translatedLines.getOrNull(lineNum) ?: line
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
                    }

                    // 保存翻译后的文件到查重文件夹（翻译后与原文不同才输出）
                    if (translatedContent != content) {
                        val relPath = file.absolutePath.substringAfter("rustedWarfare/").replace("\\", "/")
                        val outFile = File(outputDir, relPath)
                        outFile.parentFile?.mkdirs()
                        // 查重输出是可再生的（下次查重重生成），高频写盘用直接写，
                        // 避免原子写（tmp+rename+锁）拖慢多线程查重
                        outFile.writeText(translatedContent, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    // 跳过无法读取的文件
                }
            }

            // 按 CPU 核数限制并发，文件并行处理；文件间无共享可变状态，天然线程安全
            val parallelism = minOf(maxOf(2, Runtime.getRuntime().availableProcessors()), totalFiles)
            val semaphore = Semaphore(parallelism)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            coroutineScope {
                allFiles.forEach { file ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                checkOneFile(file)
                            } catch (e: Exception) {
                                // 单文件异常不中断整个查重
                            }
                            val doneCount = done.incrementAndGet()
                            onProgress?.invoke(ProgressInfo(
                                "检查 ${file.name} ($doneCount/$totalFiles)",
                                0.1f + doneCount.toFloat() / totalFiles * 0.9f
                            ))
                        }
                    }
                }
            }

            onProgress?.invoke(ProgressInfo("项目检查完成", 1f))
            duplicates.toList()
        }

        /**
         * 执行完整查重
         */
        suspend fun runFullCheck(
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
