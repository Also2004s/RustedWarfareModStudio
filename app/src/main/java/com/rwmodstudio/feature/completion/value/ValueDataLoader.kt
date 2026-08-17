package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.translation.TranslationDict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ValueDataLoader"

/**
 * 值补全数据加载器
 * 负责从 assets/data/value/ 读取 VS Code 插件提供的值定义 JSON，并做内存缓存。
 * 可选接入翻译引擎，在首次加载时对 value 名称执行一次性英→中翻译。
 */
object ValueDataLoader {

    @Serializable
    data class ValueItem(
        val name: String = "",
        val type: String = "",
        val description: String = "",
        val version: String = "default",
        val example: String = ""
    )

    @Serializable
    data class ValueDataFile(
        val name: String = "",
        val example: String = "",
        val data: List<ValueItem> = emptyList()
    )

    private val cache = ConcurrentHashMap<String, ValueDataFile>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读取指定值定义文件。优先读缓存，失败返回空数据。
     * 若传入 [translationDict] 且已加载，则对条目执行一次性英→中翻译；未命中翻译保留英文。
     */
    fun load(
        context: Context,
        fileName: String,
        translationDict: TranslationDict? = null
    ): ValueDataFile {
        val useTranslation = translationDict?.isLoaded == true
        val cacheKey = if (useTranslation) "$fileName:zh" else fileName
        val cached = cache[cacheKey]
        if (cached != null) return cached

        val raw = try {
            val assetPath = "data/value/${fileName}.json"
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val text = reader.use { it.readText() }
            json.decodeFromString<ValueDataFile>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load value data: $fileName", e)
            ValueDataFile()
        }

        val result = if (useTranslation && raw.data.isNotEmpty()) {
            raw.copy(data = raw.data.map { item ->
                item.copy(
                    name = translationDict.getValueTranslation(item.name),
                    description = item.description.takeIf { it.isNotBlank() }
                        ?.let { translationDict.getValueTranslation(it) } ?: item.description
                )
            })
        } else {
            raw
        }

        cache[cacheKey] = result
        return result
    }

    fun clearCache() {
        cache.clear()
    }
}
