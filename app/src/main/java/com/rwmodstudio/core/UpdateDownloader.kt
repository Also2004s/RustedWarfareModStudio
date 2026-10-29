package com.rwmodstudio.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 应用内更新下载与安装：
 * - [downloadApk] 多源测速 + 分段并发下载 APK 到 RWmod/cache/update/ 并实时回报进度；
 * - [installApk] 用 FileProvider 生成 content:// URI 调起系统安装界面；
 * - [cleanupStale] 清理遗留的旧安装包（升级后新版本启动时调用，实现"安装完自动清理"）。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"
    private const val TIMEOUT_MS = 15_000

    /** 下载源测速超时（并发探测，超过即视为该源不可用） */
    private const val PROBE_TIMEOUT_MS = 6_000

    /** 测速时读取的字节数：读 1KB 的耗时换算成速度，够判断快慢又不浪费流量 */
    private const val PROBE_BYTES = 1024

    /** 分段下载的段数上限（单连接被限速时，多连接并行能明显提速） */
    private const val PARALLEL_SEGMENTS = 4

    /** 小于这个大小的文件不值得分段（多开连接的开销大于收益） */
    private const val MIN_PARALLEL_BYTES = 1_000_000L

    /** 进度回调的步长：累计 64KB 报一次，避免高速下载时每秒上百次刷新 UI */
    private const val PROGRESS_STEP = 64L * 1024L

    /** 下载进度回调 */
    fun interface ProgressListener {
        /** @param bytes 已下载字节；@param total 总字节（未知时为 -1） */
        fun onProgress(bytes: Long, total: Long)
    }

    /** 返回更新目录下本次要下载的目标文件（按版本号命名，避免与旧包冲突） */
    fun apkTargetFile(versionName: String): File = File(RwmodPaths.updateDir, "铁锈工坊-$versionName.apk")

    /** 探测过的下载源：实测速度、文件总大小、是否支持 Range 分段 */
    private data class ProbedSource(
        val url: String,
        val speedBps: Long,
        val total: Long,
        val supportsRange: Boolean
    )

    /**
     * 下载 APK（阻塞，需在 IO 线程调用）。
     * 1. 并发测速挑最快的源（直连 / gh-proxy 镜像）——国内直连常"能连但只有几十 KB/s"，只做失败兜底会一直卡在慢源；
     * 2. 源支持 Range 时分段并行下载，否则单线程下载；
     * 3. 一个源失败就换下一个。
     * @param url APK 直链
     * @param targetFile 目标文件
     * @param onProgress 进度回调
     * @return 下载完成后的文件
     */
    fun downloadApk(url: String, targetFile: File, onProgress: ProgressListener): File {
        var lastError: Exception? = null
        for (source in rankSources(url)) {
            try {
                return downloadFrom(source, targetFile, onProgress)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    /**
     * 并发测速：直连与镜像同时各读 1KB，按实测速度从快到慢排序。
     * 全部探测失败时退回原始顺序（交给后续下载再试）。
     */
    private fun rankSources(originalUrl: String): List<ProbedSource> {
        val candidates = listOf(originalUrl, UpdateChecker.proxiedUrl(originalUrl))
        val probed = AtomicReferenceArray<ProbedSource?>(candidates.size)
        val threads = candidates.mapIndexed { index, candidate ->
            Thread {
                runCatching { probeSource(candidate) }.getOrNull()?.let { probed.set(index, it) }
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { thread ->
            try { thread.join(PROBE_TIMEOUT_MS.toLong()) } catch (ignored: InterruptedException) {}
        }
        val usable = (0 until candidates.size).mapNotNull { probed.get(it) }
            .filter { it.speedBps > 0 }
            .sortedByDescending { it.speedBps }
        return usable.ifEmpty { candidates.map { ProbedSource(it, -1, -1, false) } }
    }

    /** 探测单个源：从建立连接到读完 1KB 的耗时换算速度，并解析总大小与是否支持分段 */
    private fun probeSource(url: String): ProbedSource {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
        conn.setRequestProperty("User-Agent", "RustedWarfareModStudio")
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        try {
            val startedAt = System.nanoTime()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("探测失败，服务器返回 $code")
            }
            val read = conn.inputStream.use { input -> input.read(ByteArray(PROBE_BYTES)) }
            if (read <= 0) throw IOException("探测读取为空")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val speed = if (elapsedMs <= 0) Long.MAX_VALUE else read.toLong() * 1000 / elapsedMs
            // 206 的 Content-Range 形如 "bytes 0-1023/5635305"，斜杠后就是总大小
            val total = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?: conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { code == HttpURLConnection.HTTP_OK }
                ?: -1L
            val supportsRange = code == HttpURLConnection.HTTP_PARTIAL ||
                conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            return ProbedSource(url, speed, total, supportsRange)
        } finally {
            conn.disconnect()
        }
    }

    /** 从某个源下载；支持分段则并行下载，并行失败自动退回单线程 */
    private fun downloadFrom(source: ProbedSource, targetFile: File, onProgress: ProgressListener): File {
        val total = source.total
        if (targetFile.exists() && total > 0 && targetFile.length() == total) {
            onProgress.onProgress(total, total)
            return targetFile
        }
        if (source.supportsRange && total >= MIN_PARALLEL_BYTES) {
            try {
                return parallelDownload(source, targetFile, onProgress)
            } catch (e: Exception) {
                Log.w(TAG, "分段下载失败，退回单线程：${e.message}")
            }
        }
        return singleDownload(source.url, total, targetFile, onProgress)
    }

    /** 单线程流式下载 */
    private fun singleDownload(url: String, totalHint: Long, targetFile: File, onProgress: ProgressListener): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        // 临时文件名加唯一后缀：并发/重复下载时不会互相截断同一个 tmp 写坏数据
        val tmp = uniqueTmpFile(targetFile)
        try {
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败，服务器返回 ${conn.responseCode}")
            }
            val total = conn.contentLength.toLong().takeIf { it > 0 } ?: totalHint
            if (targetFile.exists() && total > 0 && targetFile.length() == total) {
                onProgress.onProgress(total, total)
                return targetFile
            }
            var downloaded = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReport = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= PROGRESS_STEP) {
                            lastReport = downloaded
                            onProgress.onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress.onProgress(downloaded, total)
            return finishTo(targetFile, tmp)
        } catch (e: Exception) {
            // 失败时清理残留的临时文件，避免下次误判
            try { if (tmp.exists()) tmp.delete() } catch (ignored: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** 分段并行下载（每段一个连接，写入同一个临时文件的不同偏移） */
    private fun parallelDownload(source: ProbedSource, targetFile: File, onProgress: ProgressListener): File {
        val total = source.total
        val tmp = uniqueTmpFile(targetFile)
        try {
            RandomAccessFile(tmp, "rw").use { it.setLength(total) }
            val downloaded = AtomicLong(0)
            val failure = AtomicReference<Throwable?>(null)
            val threads = segmentRanges(total).map { range ->
                Thread {
                    try {
                        downloadSegment(source.url, range, tmp, downloaded, total, onProgress)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.apply { isDaemon = true }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            failure.get()?.let { cause -> throw cause as? Exception ?: IOException("分段下载失败", cause) }
            if (downloaded.get() != total) throw IOException("下载不完整：${downloaded.get()}/$total")
            onProgress.onProgress(total, total)
            return finishTo(targetFile, tmp)
        } catch (e: Exception) {
            try { if (tmp.exists()) tmp.delete() } catch (ignored: Exception) {}
            throw e
        }
    }

    /** 平均切分下载区间（段数受 [PARALLEL_SEGMENTS] 与 [MIN_PARALLEL_BYTES] 限制） */
    private fun segmentRanges(total: Long): List<LongRange> {
        val count = minOf(PARALLEL_SEGMENTS.toLong(), total / MIN_PARALLEL_BYTES).toInt().coerceAtLeast(1)
        if (count <= 1) return listOf(0L..(total - 1))
        val segSize = total / count
        return (0 until count).map { i ->
            val start = i * segSize
            val end = if (i == count - 1) total - 1 else start + segSize - 1
            start..end
        }
    }

    /** 下载一个分段到临时文件的对应偏移处（必须返回 206，否则说明服务器忽略了 Range） */
    private fun downloadSegment(
        url: String,
        range: LongRange,
        tmp: File,
        downloaded: AtomicLong,
        total: Long,
        onProgress: ProgressListener
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
        conn.setRequestProperty("User-Agent", "RustedWarfareModStudio")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("分段下载失败，服务器返回 ${conn.responseCode}")
            }
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(range.first)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var pending = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        raf.write(buffer, 0, read)
                        pending += read
                        val done = downloaded.addAndGet(read.toLong())
                        if (pending >= PROGRESS_STEP) {
                            pending = 0
                            onProgress.onProgress(done, total)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 临时文件（文件名带唯一后缀，避免并发/重试时互相写坏） */
    private fun uniqueTmpFile(targetFile: File): File =
        File(targetFile.parentFile, "${targetFile.name}.tmp.${System.currentTimeMillis()}")

    /** 临时文件落盘后改名为目标文件 */
    private fun finishTo(targetFile: File, tmp: File): File {
        if (targetFile.exists()) targetFile.delete()
        if (!tmp.renameTo(targetFile)) {
            tmp.copyTo(targetFile, overwrite = true)
            tmp.delete()
        }
        return targetFile
    }

    /**
     * 调起系统安装界面。安装前会确保「安装未知应用」权限已授权。
     * @return 是否需要先引导去授权（返回 true 表示已调起授权页，本次不安装）
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!canRequestPackageInstalls(context)) {
            openUnknownSourcesSettings(context)
            return true
        }
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider 生成 URI 失败", e)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "调起安装界面失败", e)
            return false
        }
    }

    /** 当前设备是否允许从本应用安装未知来源应用 */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /** 跳转到「安装未知应用」设置页（Android O+） */
    fun openUnknownSourcesSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "无法打开未知来源设置", e)
        }
    }

    /**
     * 清理更新目录下遗留的旧安装包。
     * 只删除「版本早于当前运行版本」的安装包（即已安装成功的旧包）；
     * 保留等于/新于当前版本的包——用户下载完未安装就退出再重进时，不会把待安装的新包误删。
     * 无法解析版本的文件（下载中的临时文件等）视为旧包清理。
     */
    fun cleanupStale(context: Context) {
        try {
            val dir = RwmodPaths.updateDir
            if (!dir.exists()) return
            val current = com.rwmodstudio.BuildConfig.VERSION_NAME
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val apkVersion = apkVersionOf(file)
                    // 版本解析失败 或 旧于当前版本 → 清理；否则（等于/新于当前）保留
                    if (apkVersion == null || !UpdateChecker.isNewerThan(apkVersion, current) && apkVersion != current) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧安装包失败", e)
        }
    }

    /** 从安装包文件名解析版本号（形如 铁锈工坊-1.3.6.apk），无法解析返回 null */
    private fun apkVersionOf(file: File): String? {
        val name = file.name.removeSuffix(".apk")
        val prefix = "铁锈工坊-"
        return if (name.startsWith(prefix)) name.removePrefix(prefix).takeIf { it.isNotBlank() } else null
    }
}
