package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val TAG = "InheritanceCache"

/**
 * 继承链缓存管理器。
 *
 * 仿照 SearchTranslationCache 的增量机制：
 * - 对每个 INI 文件维护一份继承链合并结果缓存
 * - 通过目标文件 mtime + 所有源文件 mtime 判定是否新鲜
 * - 新鲜则直接读缓存，否则后台计算并写入
 *
 * 缓存位于外存 RWmod/cache/inheritance/ 下，长期保存。
 * 翻译缓存预热完成后自动预热继承链缓存。
 */
object InheritanceCache {
    private const val INDEX_VERSION = 1

    private val INI_EXTENSIONS = setOf("ini", "template", "txt")

    private data class CacheRecord(
        val targetMtime: Long,    // 目标文件 mtime
        val sourceSig: String,    // 源文件签名（path:mtime,...） 用于判断源文件是否变更
        val cachePath: String     // 相对于 dataDir
    )

    private val index = java.util.concurrent.ConcurrentHashMap<String, CacheRecord>()
    @Volatile private var indexLoaded = false
    @Volatile private var indexDirty = false

    private val dataDir: File get() = RwmodPaths.inheritanceCacheDir
    private val indexFile: File get() = RwmodPaths.inheritanceIndexFile

    // === 索引管理 ===

    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            try {
                if (indexFile.exists()) {
                    val json = JSONObject(indexFile.readText())
                    val files = json.optJSONObject("files") ?: return@synchronized
                    val keys = files.keys()
                    while (keys.hasNext()) {
                        val path = keys.next()
                        val rec = files.optJSONObject(path) ?: continue
                        index[path] = CacheRecord(
                            targetMtime = rec.optLong("targetMtime"),
                            sourceSig = rec.optString("sourceSig", ""),
                            cachePath = rec.optString("cachePath", "")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load index: ${e.message}")
            }
            indexLoaded = true
        }
    }

    private fun saveIndex() {
        try {
            val json = JSONObject()
            json.put("version", INDEX_VERSION)
            val files = JSONObject()
            for ((path, rec) in index) {
                val recJson = JSONObject()
                recJson.put("targetMtime", rec.targetMtime)
                recJson.put("sourceSig", rec.sourceSig)
                recJson.put("cachePath", rec.cachePath)
                files.put(path, recJson)
            }
            json.put("files", files)
            indexFile.writeText(json.toString())
            indexDirty = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save index: ${e.message}")
        }
    }

    // === 源文件签名 ===

    /** 计算所有源文件的签名：path:mtime,... */
    fun computeSourceSig(sourceFiles: List<File>): String {
        return sourceFiles.filter { it.exists() }
            .joinToString(",") { "${it.absolutePath}:${it.lastModified()}" }
    }

    // === 缓存路径计算 ===

    private fun computeCachePath(absPath: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(absPath.toByteArray())
        val hex = md5.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 2)}/${hex}.txt"
    }

    // === 新鲜度判定 ===

    fun isFresh(targetFile: File, sourceFiles: List<File>): Boolean {
        ensureIndexLoaded()
        val absPath = targetFile.absolutePath
        val rec = index[absPath] ?: return false
        if (!targetFile.exists()) return false
        if (targetFile.lastModified() != rec.targetMtime) return false
        val currentSig = computeSourceSig(sourceFiles)
        if (currentSig != rec.sourceSig) return false
        return File(dataDir, rec.cachePath).exists()
    }

    // === 读取缓存 ===

    fun readCache(targetFile: File): String? {
        ensureIndexLoaded()
        val absPath = targetFile.absolutePath
        val rec = index[absPath] ?: return null
        return try {
            File(dataDir, rec.cachePath).readText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache for ${targetFile.name}: ${e.message}")
            null
        }
    }

    // === 写入缓存 ===

    fun writeCache(targetFile: File, sourceFiles: List<File>, content: String) {
        ensureIndexLoaded()
        val absPath = targetFile.absolutePath
        val cachePath = computeCachePath(absPath)
        try {
            val cacheFile = File(dataDir, cachePath)
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(content)
            tmp.renameTo(cacheFile)

            index[absPath] = CacheRecord(
                targetMtime = targetFile.lastModified(),
                sourceSig = computeSourceSig(sourceFiles),
                cachePath = cachePath
            )
            indexDirty = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache for ${targetFile.name}: ${e.message}")
        }
    }

    /** 持久化索引到文件 */
    fun flushIndex() {
        if (indexDirty) saveIndex()
    }

    // === 预热 ===

    data class PrepareStats(val total: Int, val cached: Int, val built: Int, val failed: Int)

    /**
     * 翻译缓存预热完成后调用，遍历所有 INI 文件预计算继承链缓存。
     */
    suspend fun prepareIfNeeded(projectRoot: File, context: Context): PrepareStats = withContext(Dispatchers.Default) {
        ensureIndexLoaded()
        cleanStaleEntries()

        var total = 0; var cached = 0; var built = 0; var failed = 0

        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            TaskProgressManager.start("继承链预热 (路径不存在)")
            TaskProgressManager.finish()
            return@withContext PrepareStats(total, cached, built, failed)
        }

        // 收集所有 INI 文件
        val allFiles = try {
            projectRoot.walkTopDown().filter { it.isFile && it.extension.lowercase() in INI_EXTENSIONS }.toList()
        } catch (e: Exception) {
            Log.e(TAG, "walkTopDown failed", e)
            emptyList()
        }
        total = allFiles.size
        if (total == 0) {
            TaskProgressManager.start("继承链预热 (无INI文件)")
            TaskProgressManager.finish()
            return@withContext PrepareStats(total, cached, built, failed)
        }

        TaskProgressManager.start("继承链预热", total)
        try {
            allFiles.forEachIndexed { idx, file ->
                ensureActive()
                TaskProgressManager.update(idx + 1, file.name)

                try {
                    // 先构建链获取源文件
                    val chain = mutableListOf<Pair<File, String>>() // (file, relativePath)
                    // 直接调 resolveFormatted，内部有缓存逻辑
                    InheritanceResolver.resolveFormatted(file.absolutePath, projectRoot.absolutePath)
                    built++
                    // 每处理完一个文件让出 CPU
                    kotlinx.coroutines.yield()
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Prepare failed for ${file.name}: ${e.message}")
                }
            }
        } finally {
            saveIndex()
            // 关闭继承链预热进度任务（不再显示"预热完成"汇总弹窗）
            TaskProgressManager.finish()
        }
        Log.i(TAG, "Prepare done: total=$total cached=$cached built=$built failed=$failed")
        PrepareStats(total, cached, built, failed)
    }

    /** 清理已不存在的源文件对应的缓存条目 */
    private fun cleanStaleEntries() {
        synchronized(this) {
            ensureIndexLoaded()
            var removed = 0
            val it = index.entries.iterator()
            while (it.hasNext()) {
                val (path, rec) = it.next()
                if (!File(path).exists()) {
                    try {
                        File(dataDir, rec.cachePath).delete()
                    } catch (e: Exception) { Log.w(TAG, "继承链缓存操作失败", e) }
                    it.remove()
                    removed++
                }
            }
            if (removed > 0) {
                saveIndex()
            }
        }
    }
}
