package com.rwmodstudio.util

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * 屏蔽系统相册（MediaStore）扫描指定目录内的图片/视频等媒体文件。
 *
 * 原理：在目录中创建 `.nomedia` 空文件，Android 媒体扫描器会跳过
 * 该目录及其所有子目录的媒体文件，且不影响应用按文件路径直接读取
 * （如 IniImageReader）。创建后再触发一次重新扫描，让此前已入库的
 * 旧媒体也能从系统相册中移除。
 */
object NoMediaHelper {

    /** 在 [dir] 中确保存在 `.nomedia` 文件（已存在则无副作用） */
    fun ensureNoMedia(dir: File) {
        if (!dir.isDirectory) return
        try {
            File(dir, ".nomedia").createNewFile()
        } catch (_: Exception) {
        }
    }

    /** 创建 `.nomedia` 并触发系统重新扫描 [dir]，屏蔽相册扫描并移除已入库旧媒体 */
    fun excludeFromMediaStore(context: Context, dir: File) {
        ensureNoMedia(dir)
        try {
            MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
        } catch (_: Exception) {
        }
    }
}
