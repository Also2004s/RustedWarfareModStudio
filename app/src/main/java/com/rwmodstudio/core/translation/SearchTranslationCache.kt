package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.TaskProgressManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 文件翻译缓存管理器。
 *
 * 仿照 Python 一键编译脚本的 BuildCache 增量机制：
 * - 对每个 INI/template 文件维护一份中文翻译副本
 * - 通过 srcMtime + srcSize + libVersion 判定是否新鲜
 * - 新鲜则直接读缓存，否则后台翻译并写入
 *
 * 缓存位于外存 RWmod/cache/file_translation/ 下，长期保存。
 * App 启动时后台预热（prepareIfNeeded），搜索/编辑时直接读缓存免翻译。
 */
object SearchTranslationCache {
    private const val TAG = "FileTranslationCache"
    private const val INDEX_VERSION = 1

    /** 支持的文件扩展名（与 HomeScreen 的 INI_EXTENSIONS 对齐） */
    private val INI_EXTENSIONS = setOf("ini", "template", "txt")

    /** 单条缓存记录 */
    private data class CacheRecord(
        val srcMtime: Long,
        val srcSize: Long,
        val cachePath: String,   // 相对于 dataDir
        val libVersion: String
    )

    private val index = java.util.concurrent.ConcurrentHashMap<String, CacheRecord>()
    @Volatile private var indexLoaded = false
    @Volatile private var currentLibVersion: String = ""

    /** 内存缓存：预热时把中文内容读入内存，搜索时直接从内存读，不读磁盘 */
    private val memoryCache = android.util.LruCache<String, String>(8 * 1024 * 1024) // 8MB

    /** 索引脏标记：翻译后设为 true，搜索结束后 flush 持久化 */
    @Volatile private var indexDirty = false

    /** 当前翻译库版本（用 code_reference 验证码，翻译库更新后自动变化） */
    private fun computeLibVersion(): String {
        return try {
            SettingsManager.readVerifyCode(SettingsManager.VERIFY_CODE_REFERENCE)
        } catch (_: Exception) { "" }
    }

    /** 加载索引文件（线程安全，仅加载一次） */
    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            currentLibVersion = computeLibVersion()
            try {
                val idxFile = RwmodPaths.fileTranslationIndexFile
                if (idxFile.exists()) {
                    val json = JSONObject(idxFile.readText())
                    val files = json.optJSONObject("files") ?: return@synchronized
                    val keys = files.keys()
                    while (keys.hasNext()) {
                        val path = keys.next()
                        val rec = files.optJSONObject(path) ?: continue
                        index[path] = CacheRecord(
                            srcMtime = rec.optLong("srcMtime"),
                            srcSize = rec.optLong("srcSize"),
                            cachePath = rec.optString("cachePath"),
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

    /** 持久化索引到文件 */
    private fun saveIndex() {
        try {
            val json = JSONObject()
            json.put("version", INDEX_VERSION)
            json.put("libVersion", currentLibVersion)
            val files = JSONObject()
            for ((path, rec) in index) {
                val recJson = JSONObject()
                recJson.put("srcMtime", rec.srcMtime)
                recJson.put("srcSize", rec.srcSize)
                recJson.put("cachePath", rec.cachePath)
                recJson.put("libVersion", rec.libVersion)
                files.put(path, recJson)
            }
            json.put("files", files)
            RwmodPaths.fileTranslationIndexFile.writeText(json.toString())
            indexDirty = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save index: ${e.message}")
        }
    }

    /** 搜索结束后调用：如果索引有变更（搜索时翻译了新文件），持久化到文件 */
    fun flushIndex() {
        if (indexDirty) saveIndex()
    }

    /**
     * 同步写入翻译缓存（不切换协程，不读文件）。
     * 用于 InheritanceResolver 等同步上下文中，文件内容已修改但缓存过期时补充缓存。
     */
    fun putCacheSync(file: File, chineseContent: String) {
        ensureIndexLoaded()
        val absPath = file.absolutePath
        try {
            val cachePath = computeCachePath(absPath)
            val cacheFile = File(RwmodPaths.fileTranslationDataDir, cachePath)
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(chineseContent)
            tmp.renameTo(cacheFile)
            index[absPath] = CacheRecord(
                srcMtime = file.lastModified(),
                srcSize = file.length(),
                cachePath = cachePath,
                libVersion = currentLibVersion
            )
            indexDirty = true
            memoryCache.put(absPath, chineseContent)
        } catch (e: Exception) {
            Log.w(TAG, "putCacheSync failed for ${file.name}: ${e.message}")
        }
    }

    /** 计算缓存文件相对路径：MD5 前两位作子目录 + 全 hash 作文件名 */
    private fun computeCachePath(absPath: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(absPath.toByteArray())
        val hex = md5.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 2)}/${hex}.txt"
    }

    /** 判定文件缓存是否新鲜（对标 Python BuildCache.is_fresh） */
    fun isFresh(file: File): Boolean {
        ensureIndexLoaded()
        val rec = index[file.absolutePath] ?: return false
        if (!file.exists()) return false
        if (file.lastModified() != rec.srcMtime) return false   // 源文件改过
        if (file.length() != rec.srcSize) return false          // 大小变了
        if (currentLibVersion != rec.libVersion) return false   // 翻译库升级
        return File(RwmodPaths.fileTranslationDataDir, rec.cachePath).exists()
    }

    /**
     * 同步读取缓存内容（不触发翻译，不切换协程）。
     * 优先从内存缓存读，未命中读磁盘并放入内存。
     * 轻量检查 mtime（1 次 stat），不检查 size/exists/libVersion 以减少 IO。
     * 用于搜索时快速读取，避免 suspend/withContext 开销。
     */
    fun readCacheSync(file: File): String? {
        ensureIndexLoaded()
        val absPath = file.absolutePath
        // 内存缓存命中时仍需验证 mtime，防止文件被外部修改后返回过期内容
        memoryCache.get(absPath)?.let { cached ->
            val rec = index[absPath]
            if (rec != null && file.lastModified() == rec.srcMtime) return cached
            memoryCache.remove(absPath)
        }
        val rec = index[absPath] ?: return null
        // 轻量检查：只比较 mtime（1 次 stat），省掉 size + exists + libVersion 调用
        if (file.lastModified() != rec.srcMtime) return null
        return try {
            val content = File(RwmodPaths.fileTranslationDataDir, rec.cachePath).readText()
            memoryCache.put(absPath, content)
            content
        } catch (e: Exception) { null }
    }

    /**
     * 获取文件的中文翻译内容。
     * 缓存新鲜则直接读取，否则翻译并写入缓存。
     * 应在 IO 线程调用（涉及文件 IO 和翻译）。
     */
    suspend fun getChineseContent(file: File): String {
        ensureIndexLoaded()
        val absPath = file.absolutePath
        // 内存缓存命中时仍需验证 mtime，防止文件被外部修改后返回过期内容
        memoryCache.get(absPath)?.let { cached ->
            val rec = index[absPath]
            if (rec != null && file.lastModified() == rec.srcMtime) return cached
            memoryCache.remove(absPath)
        }
        // 命中磁盘缓存：读入内存
        if (isFresh(file)) {
            val rec = index[absPath] ?: return readOriginal(file)
            return try {
                val content = File(RwmodPaths.fileTranslationDataDir, rec.cachePath).readText()
                memoryCache.put(absPath, content)
                content
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cache for ${file.name}: ${e.message}")
                readOriginal(file)
            }
        }
        // 未命中：翻译并写入
        return withContext(Dispatchers.IO) {
            try {
                val english = file.readText()
                val engine = TranslationEngine.getInstance()
                val chinese = if (engine.isLoaded) {
                    try { engine.translateToChinese(english, SettingsManager.autoSpace) } catch (_: Exception) { english }
                } else {
                    // 引擎未就绪：不缓存，直接返回原文，避免把英文当"中文翻译"写入缓存
                    Log.w(TAG, "Engine not loaded, skip caching for ${file.name}")
                    return@withContext english
                }
                // 写入缓存文件（临时文件 + rename 保证原子性）
                val cachePath = computeCachePath(absPath)
                val cacheFile = File(RwmodPaths.fileTranslationDataDir, cachePath)
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                tmp.writeText(chinese)
                tmp.renameTo(cacheFile)
                // 更新索引
                index[absPath] = CacheRecord(
                    srcMtime = file.lastModified(),
                    srcSize = file.length(),
                    cachePath = cachePath,
                    libVersion = currentLibVersion
                )
                indexDirty = true
                // 放入内存缓存
                memoryCache.put(absPath, chinese)
                chinese
            } catch (e: Exception) {
                Log.w(TAG, "Failed to translate ${file.name}: ${e.message}")
                readOriginal(file)
            }
        }
    }

    /** 读取原始文件内容（容错） */
    private fun readOriginal(file: File): String {
        return try { file.readText() } catch (_: Exception) { "" }
    }

    data class PrepareStats(val total: Int, val skipped: Int, val translated: Int, val failed: Int)

    /**
     * 启动时预热：扫描项目目录，增量翻译过期文件。
     * 纯后台执行，不阻塞 UI。翻译库升级时自动全量重译。
     */
    suspend fun prepareIfNeeded(projectRoot: File, context: Context): PrepareStats = withContext(Dispatchers.Default) {
        ensureIndexLoaded()
        cleanStaleEntries()
        var total = 0; var skipped = 0; var translated = 0; var failed = 0
        // 引擎未就绪则等待加载完成后再预热，避免在引擎就绪前写入错误缓存
        val engine = TranslationEngine.getInstance()
        if (!engine.isLoaded) {
            Log.i(TAG, "Engine not loaded, waiting for engine to load before warm-up")
            TaskProgressManager.start("等待翻译引擎就绪...")
            try {
                engine.load(context)
                Log.i(TAG, "Engine loaded: ${engine.stats}")
                TaskProgressManager.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Engine load failed during warm-up", e)
                TaskProgressManager.start("缓存预热跳过（引擎加载失败）")
                TaskProgressManager.finish()
                return@withContext PrepareStats(total, skipped, translated, failed)
            }
        }
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            TaskProgressManager.start("翻译缓存预热 (路径不存在: ${projectRoot.absolutePath})")
            TaskProgressManager.finish()
            return@withContext PrepareStats(total, skipped, translated, failed)
        }
        // 先收集文件列表，知道总数才能显示进度
        val allFiles = try {
            projectRoot.walkTopDown().filter { it.isFile }.toList()
        } catch (e: Exception) {
            Log.e(TAG, "walkTopDown failed", e)
            emptyList()
        }
        val fileList = allFiles.filter { it.extension.lowercase() in INI_EXTENSIONS }
        total = fileList.size
        if (total == 0) {
            val extInfo = allFiles.groupBy { it.extension.lowercase() }.map { "${it.key}:${it.value.size}" }.take(10).joinToString()
            TaskProgressManager.start("未找到INI文件 目录${allFiles.size}文件 ext: $extInfo")
            TaskProgressManager.finish()
            return@withContext PrepareStats(total, skipped, translated, failed)
        }
        TaskProgressManager.start("翻译缓存预热", total)
        try {
            fileList.forEachIndexed { idx, file ->
                ensureActive()
                TaskProgressManager.update(idx + 1, file.name)
                if (isFresh(file)) {
                    skipped++
                    // 读入内存缓存，搜索时直接从内存读，不读磁盘
                    if (memoryCache.get(file.absolutePath) == null) {
                        val rec = index[file.absolutePath]
                        if (rec != null) {
                            try {
                                val content = File(RwmodPaths.fileTranslationDataDir, rec.cachePath).readText()
                                memoryCache.put(file.absolutePath, content)
                            } catch (e: Exception) { Log.w(TAG, "翻译缓存操作失败", e) }
                        }
                    }
                    return@forEachIndexed
                }
                try {
                    getChineseContent(file)
                    translated++
                    // 每翻译完一个文件让出 CPU，避免长时间占用导致 UI 卡顿
                    kotlinx.coroutines.yield()
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Prepare failed for ${file.name}: ${e.message}")
                }
            }
        } finally {
            // 确保即使协程被取消也能保存索引，避免下次启动索引丢失
            saveIndex()
            // 关闭翻译缓存预热进度任务（不再显示"预热完成"汇总弹窗）
            TaskProgressManager.finish()
        }
        Log.i(TAG, "Prepare done: total=$total skipped=$skipped translated=$translated failed=$failed")
        PrepareStats(total, skipped, translated, failed)
    }

    /** 清理已不存在的源文件对应的缓存条目，并删除旧版 search_translation 目录和过期查重输出 */
    fun cleanStaleEntries() {
        synchronized(this) {
            ensureIndexLoaded()
            TaskProgressManager.start("清理过期缓存")
            // 清理旧版目录
            val legacyDir = RwmodPaths.legacySearchTranslationDir
            if (legacyDir.exists()) {
                try { legacyDir.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "翻译缓存操作失败", e) }
            }
            // 清理已删除源文件对应的缓存
            var removed = 0
            val it = index.entries.iterator()
            while (it.hasNext()) {
                val (path, rec) = it.next()
                if (!File(path).exists()) {
                    try {
                        File(RwmodPaths.fileTranslationDataDir, rec.cachePath).delete()
                    } catch (e: Exception) { Log.w(TAG, "翻译缓存操作失败", e) }
                    it.remove()
                    removed++
                }
            }
            if (removed > 0) {
                saveIndex()
            }
            // 同步清理过期查重输出文件
            val dedupRemoved = TranslationDedupChecker.cleanStaleEntries()
            val totalRemoved = removed + dedupRemoved
            if (totalRemoved > 0) {
                Log.i(TAG, "清理了 $totalRemoved 条过期缓存")
                TaskProgressManager.update(totalRemoved, "清理 $totalRemoved 条过期缓存")
            } else {
                TaskProgressManager.update(0, "无过期缓存")
            }
            TaskProgressManager.finish()
        }
    }

    /** 清除所有文件翻译缓存和查重输出 */
    fun clear() {
        synchronized(this) {
            index.clear()
            memoryCache.evictAll()
            RwmodPaths.fileTranslationCacheDir.deleteRecursively()
            RwmodPaths.fileTranslationCacheDir.mkdirs()
            indexLoaded = true
        }
        // 同步清除查重输出目录和查重词列表
        try { RwmodPaths.dedupDir.deleteRecursively(); RwmodPaths.dedupDir.mkdirs() } catch (e: Exception) { Log.w(TAG, "翻译缓存操作失败", e) }
    }
}
