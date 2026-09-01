package com.rwmodstudio.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新下载与安装：
 * - [downloadApk] 流式下载 APK 到 RWmod/cache/update/ 并实时回报进度；
 * - [installApk] 用 FileProvider 生成 content:// URI 调起系统安装界面；
 * - [cleanupStale] 清理遗留的旧安装包（升级后新版本启动时调用，实现"安装完自动清理"）。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"
    private const val TIMEOUT_MS = 15_000

    /** 下载进度回调 */
    fun interface ProgressListener {
        /** @param bytes 已下载字节；@param total 总字节（未知时为 -1） */
        fun onProgress(bytes: Long, total: Long)
    }

    /** 返回更新目录下本次要下载的目标文件（按版本号命名，避免与旧包冲突） */
    fun apkTargetFile(versionName: String): File = File(RwmodPaths.updateDir, "铁锈工坊-$versionName.apk")

    /**
     * 下载 APK（阻塞，需在 IO 线程调用）。
     * 直连失败（如国内网络被墙）时自动走 gh-proxy 镜像重试。
     * @param url APK 直链
     * @param targetFile 目标文件
     * @param onProgress 进度回调
     * @return 下载完成后的文件
     */
    fun downloadApk(url: String, targetFile: File, onProgress: ProgressListener): File {
        var lastError: Exception? = null
        for (candidate in listOf(url, UpdateChecker.proxiedUrl(url))) {
            try {
                return tryDownload(candidate, targetFile, onProgress)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun tryDownload(url: String, targetFile: File, onProgress: ProgressListener): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        // 临时文件名加唯一后缀：并发/重复下载时不会互相截断同一个 tmp 写坏数据
        val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp.${System.currentTimeMillis()}")
        try {
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败，服务器返回 ${conn.responseCode}")
            }
            val total = conn.contentLength.toLong()
            // 已存在且大小一致则视为已下载完成，跳过重复下载
            if (targetFile.exists() && targetFile.length() == total) {
                onProgress.onProgress(total, total)
                return targetFile
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress.onProgress(downloaded, total)
                    }
                }
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmp.renameTo(targetFile)) {
                tmp.copyTo(targetFile, overwrite = true)
                tmp.delete()
            }
            return targetFile
        } catch (e: Exception) {
            // 失败时清理残留的临时文件，避免下次误判
            try { if (tmp.exists()) tmp.delete() } catch (ignored: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
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
