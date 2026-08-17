package com.rwmodstudio

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 应用级入口，提供跟随进程生命周期的 CoroutineScope，
 * 用于执行保存翻译库后后台刷新补全表等耗时任务。
 */
class RwModApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
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
