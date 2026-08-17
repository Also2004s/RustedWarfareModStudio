package com.rwmodstudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rwmodstudio.core.InheritanceCache
import com.rwmodstudio.core.LocalConfigManager
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.TaskProgressManager
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.screens.MainApp
import com.rwmodstudio.ui.screens.loadExtraItemsVerified
import com.rwmodstudio.ui.screens.loadNativeItemsVerified
import com.rwmodstudio.ui.screens.refreshCompletionsFromEnglish
import com.rwmodstudio.ui.theme.RustedWarfareModStudioTheme
import com.rwmodstudio.util.FileImportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jcodings.util.ArrayReader
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        /** 外部传入的待打开文件（文件名, 绝对路径），由 MainApp 消费 */
        var pendingOpenFile: Pair<String, String>? = null
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && result.any { !it.value }) {
            Toast.makeText(this, "缺少存储权限，可能无法读取或创建外部文件", Toast.LENGTH_LONG).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(applicationContext)
        TranslationEngine.getInstance().loadBlocklist(applicationContext)
        ArrayReader.init(applicationContext)

        setContent {
            RustedWarfareModStudioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }

        // 仅在数据确实需要更新时才在后台执行补全表生成/翻译，避免阻塞 UI
        val nativeFile = File(SettingsManager.nativeCompletionsPath)
        val nativeEnFile = File(SettingsManager.nativeCompletionsEnPath)
        val extraFile = File(SettingsManager.extraCompletionsPath)
        val extraEnFile = File(SettingsManager.extraCompletionsEnPath)

        val needsNative = (SettingsManager.readVerifyCode(SettingsManager.VERIFY_NATIVE_COMPLETIONS)
                != SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE)
                || !nativeFile.exists() || !nativeEnFile.exists()
        val needsExtra = (SettingsManager.readVerifyCode(SettingsManager.VERIFY_EXTRA_COMPLETIONS)
                != SettingsManager.EXTRA_COMPLETIONS_VERIFY_CODE)
                || !extraFile.exists() || !extraEnFile.exists()
        val needsTranslationRefresh = (SettingsManager.completionTranslationRefreshCode
                != SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE)

        // 单一协程串行执行所有后台初始化，避免并发操作 TaskProgressManager 导致状态覆盖
        (application as RwModApplication).applicationScope.launch(Dispatchers.IO) {
            try {
                if (needsExtra) {
                    TaskProgressManager.start("加载附件表")
                    loadExtraItemsVerified(applicationContext, TranslationEngine.getInstance())
                    TaskProgressManager.finish()
                }
                if (SettingsManager.devTranslationEngine && !TranslationEngine.getInstance().isLoaded) {
                    TaskProgressManager.start("加载翻译引擎")
                    TranslationEngine.getInstance().load(applicationContext)
                    TaskProgressManager.update(0, TranslationEngine.getInstance().getTranslationDict().getStats())
                    TaskProgressManager.finish()
                }
                if (needsNative) {
                    TaskProgressManager.start("加载补全表")
                    loadNativeItemsVerified(TranslationEngine.getInstance())
                    TaskProgressManager.finish()
                }
                if (needsTranslationRefresh) {
                    TaskProgressManager.start("刷新补全翻译")
                    refreshCompletionsFromEnglish(TranslationEngine.getInstance())
                    SettingsManager.completionTranslationRefreshCode = SettingsManager.NATIVE_COMPLETIONS_VERIFY_CODE
                    TaskProgressManager.finish()
                }
                // 翻译引擎加载完成后，预热翻译缓存
                if (SettingsManager.devTranslationEngine) {
                    val projectRoot = File(SettingsManager.defaultModPath())
                    if (projectRoot.exists() && projectRoot.isDirectory) {
                        try {
                            SearchTranslationCache.prepareIfNeeded(projectRoot, applicationContext)
                        } catch (e: Exception) {
                            Log.e(TAG, "PrepareIfNeeded failed", e)
                            TaskProgressManager.start("预热失败: ${e.message}")
                            TaskProgressManager.finish()
                        }
                        // 翻译缓存预热完成后，预热继承链缓存
                        if (SettingsManager.devInheritanceView) {
                            try {
                                InheritanceCache.prepareIfNeeded(projectRoot, applicationContext)
                            } catch (e: Exception) {
                                Log.e(TAG, "InheritanceCache prepare failed", e)
                                TaskProgressManager.start("继承链预热失败: ${e.message}")
                                TaskProgressManager.finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background initialization failed", e)
            } finally {
                TaskProgressManager.finish()
            }
        }

        requestStoragePermissions()
        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        val name = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: return
        val ext = name.substringAfterLast('.', "").lowercase()

        when (ext) {
            "replay" -> importReplay(uri, name)
            "rwmod" -> importRwmod(uri, name)
            "tmx" -> importMap(uri, name)
            "ini", "template" -> {
                // file:// scheme 可以直接拿到绝对路径；content:// 需要先导入缓存
                val path = FileImportHelper.uriToAbsolutePath(uri)
                if (path != null) {
                    pendingOpenFile = name to path
                } else {
                    // content:// 的 ini 先复制到 RWmod/imports/ini 再打开
                    lifecycleScope.launch {
                        val dir = RwmodPaths.importsIniDir
                        val file = FileImportHelper.importFromUri(this@MainActivity, uri, dir)
                        file?.let { pendingOpenFile = it.name to it.absolutePath }
                    }
                }
            }
            "zip" -> importLocalConfig(uri)
        }
    }

    private fun importReplay(uri: Uri, name: String) {
        if (!ensureStoragePermission()) return
        lifecycleScope.launch {
            val dir = File(SettingsManager.replayImportDir)
            FileImportHelper.importFromUri(this@MainActivity, uri, dir)
        }
    }

    private fun importRwmod(uri: Uri, name: String) {
        if (!ensureStoragePermission()) return
        lifecycleScope.launch {
            val dir = File(SettingsManager.rwmodImportDir)
            FileImportHelper.importFromUri(this@MainActivity, uri, dir)
        }
    }

    private fun importMap(uri: Uri, name: String) {
        if (!ensureStoragePermission()) return
        lifecycleScope.launch {
            val dir = File(SettingsManager.mapImportDir)
            FileImportHelper.importFromUri(this@MainActivity, uri, dir)
        }
    }

    private fun importLocalConfig(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tempFile = RwmodPaths.importConfigTempFile(System.currentTimeMillis())
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tempFile.exists()) {
                    Toast.makeText(this@MainActivity, "无法读取配置文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ok = LocalConfigManager.importFromZip(this@MainActivity, tempFile)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "本地配置已导入，请重启应用以生效" else "配置导入失败",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Import local config failed", e)
                Toast.makeText(this@MainActivity, "配置导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensureStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) true else {
                Toast.makeText(this, "需要所有文件访问权限才能导入", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
                false
            }
        } else {
            val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                Toast.makeText(this, "需要存储权限才能导入", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
                false
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun requestStoragePermissions() {
        // 首次启动由引导页统一处理权限，避免与系统弹窗冲突
        if (!SettingsManager.onboardingVerified) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needRequest.isNotEmpty()) {
                requestPermissionLauncher.launch(needRequest.toTypedArray())
            }
        }
    }
}
