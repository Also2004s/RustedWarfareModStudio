package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ParamDataLoader"

/**
 * 函数参数数据加载器。
 * 从 assets/data/param/ 读取命名参数定义（logicboolean / autoTriggerOnEvent 等），并做内存缓存。
 * 纯静态 assets 直接读取，不做运行时生成文件，因此不涉及 VerifyManager。
 */
object ParamDataLoader {

    @Serializable
    data class ParamItem(
        val key: String = "",
        val zh: String = "",
        val type: String = "",
        val values: List<String> = emptyList(),
        // 数值型参数是否接受逻辑表达式（坐标/角度参数如 创建标记 的 x/y/height/dir、
        // 获取相对/绝对偏移 的 x/y/height/角度偏移）；纯数值参数（范围内/超过/少于/等于/几秒内/几秒后）缺省 false。
        val expression: Boolean = false
    )

    @Serializable
    data class FunctionParamInfo(
        val key: String = "",
        val params: List<ParamItem> = emptyList()
    )

    @Serializable
    data class ParamDataFile(
        val name: String = "",
        val functions: List<FunctionParamInfo> = emptyList()
    )

    private val cache = ConcurrentHashMap<String, ParamDataFile>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读取指定参数定义文件。优先读缓存，失败返回空数据。
     */
    fun load(context: Context, fileName: String): ParamDataFile {
        cache[fileName]?.let { return it }
        val raw = try {
            val assetPath = "data/param/${fileName}.json"
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val text = reader.use { it.readText() }
            json.decodeFromString<ParamDataFile>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load param data: $fileName", e)
            ParamDataFile()
        }
        cache[fileName] = raw
        return raw
    }

    fun clearCache() {
        cache.clear()
    }
}