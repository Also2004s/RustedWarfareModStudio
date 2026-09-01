package com.rwmodstudio.core

import android.util.Log
import org.json.JSONObject
import java.io.File
import com.rwmodstudio.util.atomicWriteText

/**
 * 文件浏览器异度空间管理器：每个项目缓存一份浏览器空间状态。
 * 存储于 RWmod/config/browser_spaces.json，按项目（规范化路径）隔离：
 *   - active：当前激活空间（0=第一，1=第二）
 *   - first：第一浏览器位置
 *   - second：第二浏览器位置
 * 供文件浏览器切换/恢复使用，首页打开项目时调用 [resetSpace] 默认回到第一空间。
 */
object BrowserSpaceManager {
    private const val TAG = "BrowserSpaceManager"

    data class ProjectSpace(
        val active: Int,
        val first: String,
        val second: String
    )

    private fun file(): File = RwmodPaths.browserSpacesFile

    /** 项目键：项目根目录的规范化路径，异常时回退原始路径 */
    fun projectKey(projectRoot: String): String = try {
        File(projectRoot).canonicalPath
    } catch (e: Exception) {
        projectRoot
    }

    @Synchronized
    fun getSpace(projectKey: String): ProjectSpace? {
        return try {
            val f = file()
            if (!f.exists()) return null
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val o = root.optJSONObject(projectKey) ?: return null
            ProjectSpace(
                active = o.optInt("active", 0),
                first = o.optString("first", ""),
                second = o.optString("second", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read browser space", e)
            null
        }
    }

    @Synchronized
    fun saveSpace(projectKey: String, space: ProjectSpace) {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            val root = if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
            root.put(projectKey, JSONObject().apply {
                put("active", space.active)
                put("first", space.first)
                put("second", space.second)
            })
            f.atomicWriteText(root.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save browser space", e)
        }
    }

    /**
     * 首页点进项目时重置：默认打开第一空间（active=0、first=项目根），
     * 保留已缓存的第二空间位置；无缓存时第二空间默认项目根。
     */
    @Synchronized
    fun resetSpace(projectKey: String, projectRoot: String) {
        val existing = getSpace(projectKey)
        saveSpace(projectKey, ProjectSpace(
            active = 0,
            first = projectRoot,
            second = existing?.second?.takeIf { File(it).exists() } ?: projectRoot
        ))
    }
}
