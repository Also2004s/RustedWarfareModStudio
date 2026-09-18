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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rwmodstudio.core.FileClipboardManager
import com.rwmodstudio.core.InheritanceCache
import com.rwmodstudio.core.LocalConfigManager
import com.rwmodstudio.core.RecycleBin
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.TaskProgressManager
import com.rwmodstudio.core.UpdateDownloader
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.screens.MainApp
import com.rwmodstudio.ui.screens.loadExtraItemsVerified
import com.rwmodstudio.ui.screens.loadNativeItemsVerified
import com.rwmodstudio.ui.screens.refreshCompletionsFromEnglish
import com.rwmodstudio.ui.theme.RustedWarfareModStudioTheme
import com.rwmodstudio.util.FileImportHelper
import com.rwmodstudio.util.NoMediaHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jcodings.util.ArrayReader
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 外部传入的待打开文件（文件名, 绝对路径），由 MainApp 消费。
         * 用 Compose 状态承载：App 已在前台时 onNewIntent 再次赋值，能驱动 MainApp 的 LaunchedEffect 重新消费，
         * 修复「singleTask + LaunchedEffect(Unit) 只跑一次 → 二次外部打开静默丢失」的问题。
         */
        var pendingOpenFile by mutableStateOf<Pair<String, String>?>(null)
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
        // 保持 HEAD 原顺序：loadBlocklist 必须在主线程同步执行——
        // TranslationEngine.load 内部（loadMutex 内）也会调 loadBlocklist，
        // 若挪到后台协程会与之并发竞态（屏蔽词加载依赖翻译字典先就绪），导致首次启动初始化异常。
        SettingsManager.init(applicationContext)
        TranslationEngine.getInstance().loadBlocklist(applicationContext)
        ArrayReader.init(applicationContext)
        // 启动时清理遗留的更新安装包（升级后新版本冷启动时执行，实现"安装完自动清理"）
        UpdateDownloader.cleanupStale(applicationContext)

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
                // 回收站超限清理：首页删除的文件/文件夹先移入 RWmod/exports/recycle，
                // 若累计超过 25MB，则本次启动整体清空（只清 recycle 子目录，不动 exports 下其它内容）。
                try {
                    RecycleBin.purgeIfOversize()
                } catch (e: Exception) {
                    Log.e(TAG, "RecycleBin purge failed", e)
                }
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
                        // 翻译缓存预热完成后，预热继承链缓存。
                        // 不按 devInheritanceView 门控：该开关只管「查看」入口，而补全的继承链符号
                        // （@copyFromSection/复制节/附属 slot 节名、@define 同节变量）无条件依赖继承链数据，
                        // 关掉查看开关也必须预热，否则每个文件首开都要现算整条链、且孤儿缓存无人清理。
                        try {
                            InheritanceCache.prepareIfNeeded(projectRoot, applicationContext)
                        } catch (e: Exception) {
                            Log.e(TAG, "InheritanceCache prepare failed", e)
                            TaskProgressManager.start("继承链预热失败: ${e.message}")
                            TaskProgressManager.finish()
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
        ensureGameDataNoMedia()
        processIntent(intent)
    }

    /**
     * 在铁锈战争数据根目录（首页所在目录的上级，即 rustedWarfare 文件夹）
     * 放置 .nomedia，屏蔽系统相册扫描整个目录树内的项目图片。
     */
    private fun ensureGameDataNoMedia() {
        try {
            val modDir = File(SettingsManager.defaultModPath())
            val dataRoot = modDir.parentFile
            // 防御：避免把 .nomedia 写到外部存储根（会屏蔽整机相册）
            if (dataRoot != null && dataRoot.exists() &&
                dataRoot.absolutePath != Environment.getExternalStorageDirectory().absolutePath
            ) {
                NoMediaHelper.excludeFromMediaStore(this, dataRoot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureGameDataNoMedia failed", e)
        }
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
            try {
                val dir = File(SettingsManager.replayImportDir)
                FileImportHelper.importFromUri(this@MainActivity, uri, dir)
            } catch (e: Exception) {
                Log.e(TAG, "importReplay failed", e)
                Toast.makeText(this@MainActivity, "导入回放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importRwmod(uri: Uri, name: String) {
        if (!ensureStoragePermission()) return
        lifecycleScope.launch {
            try {
                val dir = File(SettingsManager.rwmodImportDir)
                FileImportHelper.importFromUri(this@MainActivity, uri, dir)
            } catch (e: Exception) {
                Log.e(TAG, "importRwmod failed", e)
                Toast.makeText(this@MainActivity, "导入模组失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importMap(uri: Uri, name: String) {
        if (!ensureStoragePermission()) return
        lifecycleScope.launch {
            try {
                val dir = File(SettingsManager.mapImportDir)
                FileImportHelper.importFromUri(this@MainActivity, uri, dir)
            } catch (e: Exception) {
                Log.e(TAG, "importMap failed", e)
                Toast.makeText(this@MainActivity, "导入地图失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
