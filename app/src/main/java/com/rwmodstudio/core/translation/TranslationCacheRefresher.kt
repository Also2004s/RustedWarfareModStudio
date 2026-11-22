package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.InheritanceCache
import com.rwmodstudio.core.InheritanceResolver
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 翻译禁用设置变更后的缓存重建调度器。
 *
 * 触发场景：查重词禁用/恢复、`翻译禁用管理` 四个开关、资源名白名单增删。
 * 流程：防抖 2 秒（连续操作合并）→ 重新注入翻译禁用设置 → 清空文件翻译缓存（保留查重词）
 * → 清空继承链缓存与符号 memo → 后台重新预热。
 */
object TranslationCacheRefresher {

    private const val TAG = "TranslationCacheRefresher"
    private const val DEBOUNCE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 安排一次重建（防抖）。[projectRoot] 为空时用 [SettingsManager.defaultPath]。 */
    @Synchronized
    fun schedule(context: Context, projectRoot: String = "") {
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                refresh(appContext, projectRoot)
            } catch (e: Exception) {
                Log.e(TAG, "refresh translation cache failed", e)
            }
        }
    }

    private suspend fun refresh(context: Context, projectRoot: String) {
        val engine = TranslationEngine.getInstance()
        // 规则先就位，再清缓存重建，保证新缓存用的是新规则。
        // 清缓存本身不依赖引擎就绪：即使引擎加载失败也必须把旧规则缓存清掉。
        engine.applyTranslationBlockSettings()
        SearchTranslationCache.clearTranslationOnly()
        InheritanceCache.clearAll()
        InheritanceResolver.clearSymbolMemo()

        // 预热必须等引擎真正就绪，否则跳过（下次启动预热会补上）
        if (!engine.awaitReady()) {
            Log.w(TAG, "engine not ready, skip cache warm-up")
            return
        }
        val root = projectRoot.ifBlank { SettingsManager.defaultPath }
        if (root.isBlank()) return
        val rootDir = File(root)
        if (!rootDir.exists() || !rootDir.isDirectory) return
        SearchTranslationCache.prepareIfNeeded(rootDir, context)
        InheritanceCache.prepareIfNeeded(rootDir, context)
    }

    /** 翻译禁用设置变更入口：后台注入规则 + 安排缓存重建。 */
    fun onSettingsChanged(context: Context) {
        // 规则注入放后台：compilePatterns 是全量正则重编译，不能占 UI 线程
        scope.launch {
            try {
                TranslationEngine.getInstance().applyTranslationBlockSettings()
            } catch (e: Exception) {
                Log.e(TAG, "apply translation block settings failed", e)
            }
        }
        schedule(context)
    }
}
