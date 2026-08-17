package com.rwmodstudio.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object FileImportHelper {

    private const val TAG = "FileImportHelper"

    /**
     * 从 content URI 导入文件到目标目录。
     * @return 成功返回最终 File，失败返回 null
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        targetDir: File,
        onConflict: (existing: File) -> Unit = { it.delete() }
    ): File? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val fileName = getFileName(resolver, uri) ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "无法读取文件名", Toast.LENGTH_SHORT).show()
                }
                return@withContext null
            }

            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, fileName)
            onConflict(targetFile)

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "无法打开文件输入流", Toast.LENGTH_SHORT).show()
                }
                return@withContext null
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已导入: ${targetFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: $uri", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    /**
     * 将本地/远程 URI 转成可读的绝对路径（仅限 file scheme）。
     * content scheme 返回 null，需要走 importFromUri。
     */
    fun uriToAbsolutePath(uri: Uri): String? {
        return if (uri.scheme == "file") uri.path else null
    }

    private fun getFileName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
