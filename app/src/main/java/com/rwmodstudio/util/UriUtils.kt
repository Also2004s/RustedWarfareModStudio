package com.rwmodstudio.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 把 Storage Access Framework 返回的 document tree URI 转成绝对路径。
 * 支持 primary 存储卷、MuMu 共享文件夹以及其他外置卷。
 */
fun uriToAbsolutePath(context: Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        when {
            docId.startsWith("primary:") -> {
                val subPath = docId.substringAfter("primary:")
                "/storage/emulated/0/" + if (subPath.isEmpty()) "" else subPath
            }
            docId.startsWith("MuMuShared:") -> {
                // MuMu 共享文件夹不是标准外部存储卷，需要探测真实挂载点
                val subPath = docId.substringAfter("MuMuShared:")
                val candidates = listOf(
                    "/sdcard/MuMuShared/$subPath",
                    "/mnt/shared/MuMuShared/$subPath",
                    "/storage/emulated/0/MuMuShared/$subPath",
                    "/storage/MuMuShared/$subPath"
                )
                candidates.firstOrNull { File(it).exists() } ?: candidates.first()
            }
            docId.contains(":") -> {
                val volume = docId.substringBefore(":")
                val subPath = docId.substringAfter(":")
                "/storage/$volume/" + if (subPath.isEmpty()) "" else subPath
            }
            else -> null
        }
    } catch (_: Exception) { null }
}
