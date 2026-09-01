package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.rwmodstudio.util.atomicWriteText

/**
 * 文件浏览器复制/剪切剪贴板：进程级全局状态 + 落盘持久化。
 *
 * 复制/剪切后无论回到首页、切换到其他项目还是重启 App，粘贴内容都保留，
 * 待粘贴时再对文件路径做存在性校验（不存在的项会被跳过）。
 */
object FileClipboardManager {
    private const val TAG = "FileClipboardManager"

    /** 剪贴板模式："" / "copy" / "cut" */
    var mode by mutableStateOf("")
        private set

    /** 待粘贴的源文件绝对路径列表 */
    var files by mutableStateOf(listOf<String>())
        private set

    private var loaded = false

    private fun getFile(context: Context): File = RwmodPaths.fileClipboardFile

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = getFile(context)
            if (!file.exists()) return
            val json = JSONObject(file.readText(Charsets.UTF_8))
            mode = json.optString("mode", "")
            val array = json.optJSONArray("files") ?: return
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) list.add(array.optString(i, ""))
            // 只保留仍存在的路径，避免残留失效项；有清理则回写
            val valid = list.filter { File(it).exists() }
            files = valid
            if (valid.size != list.size) save(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file clipboard", e)
        }
    }

    @Synchronized
    fun set(context: Context, newMode: String, newFiles: List<String>) {
        init(context)
        mode = newMode
        files = newFiles
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        mode = ""
        files = emptyList()
        save(context)
    }

    private fun save(context: Context) {
        try {
            val file = getFile(context)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (f in files) array.put(f)
            val json = JSONObject().apply {
                put("mode", mode)
                put("files", array)
            }
            file.atomicWriteText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file clipboard", e)
        }
    }
}
