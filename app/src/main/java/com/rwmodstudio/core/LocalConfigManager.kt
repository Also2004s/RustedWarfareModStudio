package com.rwmodstudio.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地配置导出/导入管理器。
 * 打包用户翻译表、中文补全表、自定义补全表以及部分 SharedPreferences 配置到 zip，
 * 支持分享、外部打开导入。
 */
object LocalConfigManager {

    private const val TAG = "LocalConfigManager"
    private const val EXPORT_ZIP_NAME = "config_export.zip"
    private const val MANIFEST_NAME = "manifest.json"
    private const val MANIFEST_VERSION = 1

    /** zip 中包含的用户文件（zip 内为扁平文件名，实际路径由 [resolveUserFile] 映射到 RWmod 子目录） */
    private val USER_FILES = listOf(
        "user_translation.json",
        "user_completions.json",
        "custom_completions.json"
    )

    private fun resolveUserFile(name: String): File = when (name) {
        "user_translation.json" -> RwmodPaths.userTranslationFile
        "user_completions.json" -> RwmodPaths.userCompletionsFile
        "custom_completions.json" -> RwmodPaths.customCompletionsFile
        else -> File(SettingsManager.rwmodDir, name)
    }

    /** 需要导出/导入的 SharedPreferences 键 */
    private val PREFS_KEYS = listOf(
        "custom_completions_json",
        "custom_translations",
        "custom_completion_formats"
    )

    /** 导出到的文件 */
    fun exportFile(context: Context): File = RwmodPaths.localConfigExportFile

    /**
     * 导出本地配置到 zip。
     * @return 导出的 zip 文件，失败返回 null
     */
    suspend fun exportToZip(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val outFile = exportFile(context)
            if (outFile.exists()) outFile.delete()

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                // manifest
                val manifest = JSONObject().apply {
                    put("version", MANIFEST_VERSION)
                    put("files", org.json.JSONArray(USER_FILES))
                    put("prefs", org.json.JSONArray(PREFS_KEYS))
                }
                zos.putNextEntry(ZipEntry(MANIFEST_NAME))
                zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 用户文件（zip 内保持扁平文件名以兼容旧版导入）
                USER_FILES.forEach { name ->
                    val file = resolveUserFile(name)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // SharedPreferences 配置
                val prefsJson = JSONObject()
                PREFS_KEYS.forEach { key ->
                    val value = when (key) {
                        "custom_completions_json" -> SettingsManager.customCompletionsJson
                        "custom_translations" -> SettingsManager.customTranslations
                        "custom_completion_formats" -> SettingsManager.customCompletionFormats
                        else -> null
                    }
                    if (value != null) prefsJson.put(key, value)
                }
                zos.putNextEntry(ZipEntry("prefs.json"))
                zos.write(prefsJson.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            Log.d(TAG, "Exported to ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            null
        }
    }

    /**
     * 从 zip 导入本地配置。
     * @return 成功返回 true
     */
    suspend fun importFromZip(context: Context, zipFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists() || zipFile.length() == 0L) return@withContext false

            val tempDir = RwmodPaths.localConfigImportDir.apply {
                deleteRecursively()
                mkdirs()
            }

            // 解压到临时目录
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(tempDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // 复制用户文件到 RWmod 子目录
            USER_FILES.forEach { name ->
                val src = File(tempDir, name)
                if (src.exists()) {
                    val dst = resolveUserFile(name)
                    src.copyTo(dst, overwrite = true)
                }
            }

            // 写回 SharedPreferences
            val prefsFile = File(tempDir, "prefs.json")
            if (prefsFile.exists()) {
                val json = JSONObject(prefsFile.readText(Charsets.UTF_8))
                PREFS_KEYS.forEach { key ->
                    if (json.has(key)) {
                        val value = json.getString(key)
                        when (key) {
                            "custom_completions_json" -> SettingsManager.customCompletionsJson = value
                            "custom_translations" -> SettingsManager.customTranslations = value
                            "custom_completion_formats" -> SettingsManager.customCompletionFormats = value
                        }
                    }
                }
            }

            // 清除翻译/补全相关验证码，下次启动重新加载
            SettingsManager.writeVerifyCode(SettingsManager.VERIFY_EXTRA_COMPLETIONS, "")
            SettingsManager.writeVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS, "")

            Log.d(TAG, "Imported from ${zipFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            false
        }
    }

    /**
     * 分享 zip 文件。
     */
    fun shareZip(context: Context, zipFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "分享本地配置")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Share failed", e)
        }
    }

    /**
     * 在常见下载目录中搜索 zip 文件。
     */
    fun findConfigZipFiles(context: Context): List<File> {
        val result = mutableListOf<File>()
        val roots = mutableListOf<File>()

        roots.add(File(Environment.getExternalStorageDirectory(), "Download"))
        roots.add(File(Environment.getExternalStorageDirectory(), "Downloads"))
        roots.add(File(Environment.getExternalStorageDirectory(), "Tencent/QQfile_recv"))
        roots.add(File(Environment.getExternalStorageDirectory(), "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"))
        roots.add(SettingsManager.rwmodDir)
        roots.add(RwmodPaths.exportsDir)
        roots.add(RwmodPaths.importsDir)
        roots.add(RwmodPaths.cacheDir)

        roots.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file ->
                    file.isFile && file.extension.equals("zip", ignoreCase = true)
                }?.let { result.addAll(it) }
            }
        }
        return result.sortedByDescending { it.lastModified() }
    }
}
