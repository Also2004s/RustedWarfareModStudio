package com.rwmodstudio

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.rwmodstudio.core.InheritanceCache
import com.rwmodstudio.core.translation.SearchTranslationCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 应用级入口，提供跟随进程生命周期的 CoroutineScope，
 * 用于执行保存翻译库后后台刷新补全表等耗时任务。
 */
class RwModApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 退后台时统一落盘缓存索引：InheritanceCache.writeCache 置 indexDirty 后没有落盘时机，
        // 进程被杀会丢新条目（下次启动索引缺失 → 性能损失 + 孤儿缓存）。毫秒级小文件写，放 IO 协程。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                applicationScope.launch {
                    runCatching { InheritanceCache.flushIndex() }
                    runCatching { SearchTranslationCache.flushIndex() }
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }

    companion object {
        private lateinit var instance: RwModApplication

        fun getInstance(): RwModApplication = instance
    }
}
