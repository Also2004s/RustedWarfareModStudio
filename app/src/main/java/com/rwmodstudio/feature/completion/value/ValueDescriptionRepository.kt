package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ValueDescriptionRepository"

/**
 * 值补全中文说明仓库。
 *
 * 读取维护文件 `assets/data/value/value_desc.json`（data.value.<文件>.<值> → 中文说明），
 * 供值补全弹窗的 detail 副标题展示，解释枚举值/补全值的实际意义。
 *
 * 该文件为独立维护的 UI 层说明源，不依赖翻译引擎与代码参考库（两者均无 data.value.* 键）。
 * 首次调用时从 assets 加载并缓存；加载失败回退空说明（不展示副标题，不影响补全）。
 */
object ValueDescriptionRepository {

    private const val ASSET_PATH = "data/value/value_desc.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val map = ConcurrentHashMap<String, String>()
    @Volatile
    private var loaded = false

    /**
     * 查 value 条目的 description 键对应的中文说明；未命中返回 null。
     * 兼容带 `.description` 后缀的键（如 data.value.bool.true.description）。
     */
    fun lookup(context: Context, key: String): String? {
        if (key.isBlank()) return null
        if (!loaded) synchronized(this) {
            if (!loaded) load(context)
        }
        val zh = map[key]
        if (zh != null) return zh
        return map[key.removeSuffix(".description")]
    }

    private fun load(context: Context) {
        try {
            val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            map.putAll(json.decodeFromString<Map<String, String>>(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load value descriptions", e)
        }
        loaded = true
    }
}
