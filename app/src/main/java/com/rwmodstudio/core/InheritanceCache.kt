package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.VerifyManager
import com.rwmodstudio.util.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val cachePath: String,    // 相对于 dataDir
        val libVersion: String    // 翻译库版本（内容 hash + 验证码），翻译库更新后失效
    )

    private val index = java.util.concurrent.ConcurrentHashMap<String, CacheRecord>()
    @Volatile private var indexLoaded = false
    @Volatile private var indexDirty = false
    @Volatile private var currentLibVersion: String = ""

    private val dataDir: File get() = RwmodPaths.inheritanceCacheDir
    private val indexFile: File get() = RwmodPaths.inheritanceIndexFile

    /**
     * 当前翻译库版本（两个维度，任一变化都使继承链缓存失效重建）：
     * 1) 翻译库版本号：翻译字典合并缓存（translation_cache.txt）内容 SHA-256，
     *    自动感知 translation.txt / 附件表 / 用户表等任何翻译库数据变化；
     * 2) 翻译库验证码：native/extra 翻译验证码，供人工递增强制刷新。
     */
    private fun computeLibVersion(): String {
        return try {
            val cacheFile = RwmodPaths.translationCacheFile
            val libHash = if (cacheFile.exists()) {
                MessageDigest.getInstance("SHA-256").digest(cacheFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
            } else ""
            val codes = VerifyManager.read(VerifyManager.NATIVE_TRANSLATION) + "_" +
                    VerifyManager.read(VerifyManager.EXTRA_TRANSLATION)
            "$libHash|$codes"
        } catch (_: Exception) { "" }
    }

    // === 索引管理 ===

    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            currentLibVersion = computeLibVersion()
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
                            cachePath = rec.optString("cachePath", ""),
                            libVersion = rec.optString("libVersion", "")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load index: ${e.message}")
            }
            indexLoaded = true
        }
    }

    private fun saveIndex() = synchronized(this) {
        try {
            val json = JSONObject()
            json.put("version", INDEX_VERSION)
            json.put("libVersion", currentLibVersion)
            val files = JSONObject()
            for ((path, rec) in index) {
                val recJson = JSONObject()
                recJson.put("targetMtime", rec.targetMtime)
                recJson.put("sourceSig", rec.sourceSig)
                recJson.put("cachePath", rec.cachePath)
                recJson.put("libVersion", rec.libVersion)
                files.put(path, recJson)
            }
            json.put("files", files)
            indexFile.atomicWriteText(json.toString())
            indexDirty = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save index: ${e.message}")
        }
    }

    // === 源文件签名 ===

    /** 计算所有源文件的签名：path:mtime:size,...（含 size：FAT32 mtime 仅 2 秒粒度，同秒重写文件靠 size 兜底失效） */
    fun computeSourceSig(sourceFiles: List<File>): String {
        return sourceFiles.filter { it.exists() }
            .joinToString(",") { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
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
        // 翻译库更新（内容 hash 或验证码变化）后继承链结果可能内含旧翻译，视为不新鲜强制重建
        if (currentLibVersion != rec.libVersion) return false
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
        // 防御：引擎未就绪时绝不写继承链缓存，避免把未翻译原文持久化
        if (!com.rwmodstudio.core.translation.TranslationEngine.getInstance().isLoaded) {
            Log.w(TAG, "writeCache skipped (engine not loaded) for ${targetFile.name}")
            return
        }
        ensureIndexLoaded()
        val absPath = targetFile.absolutePath
        val cachePath = computeCachePath(absPath)
        try {
            val cacheFile = File(dataDir, cachePath)
            cacheFile.parentFile?.mkdirs()
            // 继承链缓存可再生的（失效重新解析），高频写盘用直接写：
            // 原子写（tmp+rename+per-path 锁）会让预热多线程写盘串行化、拖慢预热。
            // 预热每个文件写不同缓存路径，不会同路径并发写，直接写安全。
            cacheFile.writeText(content)

            index[absPath] = CacheRecord(
                targetMtime = targetFile.lastModified(),
                sourceSig = computeSourceSig(sourceFiles),
                cachePath = cachePath,
                libVersion = currentLibVersion
            )
            indexDirty = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache for ${targetFile.name}: ${e.message}")
        }
    }

    /** 持久化索引到文件（flushIndex 的落盘实现） */
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

        var total = 0
        val cachedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val builtCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // 引擎未就绪则等待加载完成后再预热，避免把未翻译的原文写入继承链缓存
        val engine = com.rwmodstudio.core.translation.TranslationEngine.getInstance()
        if (!engine.isLoaded) {
            Log.i(TAG, "Engine not loaded, waiting for engine to load before warm-up")
            TaskProgressManager.start("等待翻译引擎就绪...")
            try {
                engine.load(context)
                Log.i(TAG, "Engine loaded: ${engine.stats}")
                TaskProgressManager.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Engine load failed during warm-up", e)
                TaskProgressManager.start("继承链预热跳过（引擎加载失败）")
                TaskProgressManager.finish()
                return@withContext PrepareStats(total, cachedCount.get(), builtCount.get(), failedCount.get())
            }
        }

        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            TaskProgressManager.start("继承链预热 (路径不存在)")
            TaskProgressManager.finish()
            return@withContext PrepareStats(total, cachedCount.get(), builtCount.get(), failedCount.get())
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
            return@withContext PrepareStats(total, cachedCount.get(), builtCount.get(), failedCount.get())
        }

        TaskProgressManager.start("继承链预热", total)

        // 处理单个文件：直接调 resolveFormatted，内部有缓存逻辑
        suspend fun warmOne(file: File) {
            try {
                InheritanceResolver.resolveFormatted(file.absolutePath, projectRoot.absolutePath)
                builtCount.incrementAndGet()
            } catch (e: Exception) {
                failedCount.incrementAndGet()
                Log.w(TAG, "Prepare failed for ${file.name}: ${e.message}")
            }
        }

        try {
            if (SettingsManager.devParallelPreheat && total > 1) {
                // 多线程并行预热：按 CPU 核数限制并发，文件并行解析继承链
                val parallelism = minOf(maxOf(2, Runtime.getRuntime().availableProcessors()), total)
                val semaphore = Semaphore(parallelism)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                coroutineScope {
                    allFiles.forEach { file ->
                        launch(Dispatchers.Default) {
                            ensureActive()
                            semaphore.withPermit {
                                try {
                                    warmOne(file)
                                } finally {
                                    TaskProgressManager.update(done.incrementAndGet(), file.name)
                                }
                            }
                        }
                    }
                }
            } else {
                // 单线程串行预热
                allFiles.forEachIndexed { idx, file ->
                    ensureActive()
                    TaskProgressManager.update(idx + 1, file.name)
                    warmOne(file)
                    // 每处理完一个文件让出 CPU
                    kotlinx.coroutines.yield()
                }
            }
        } finally {
            saveIndex()
            // 关闭继承链预热进度任务（不再显示"预热完成"汇总弹窗）
            TaskProgressManager.finish()
        }
        val cached = cachedCount.get(); val built = builtCount.get(); val failed = failedCount.get()
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
