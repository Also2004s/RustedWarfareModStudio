package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

private const val TAG = "CodeDescriptionIndex"

/**
 * 代码说明索引（只读，进程内缓存）。
 *
 * 汇总所有「代码说明」关键词与其中文说明，供「代码提示」弹窗在三张补全表无命中时做模糊匹配，
 * 作为调用 AI 之前的本地兜底。数据来源（全部只读 assets）：
 * 1. data/value 目录下的值定义 json（name + description 说明 + example）；
 * 2. value_desc.json     未被上一步引用的说明键（取末段为关键词，补全图片/特效/音效/原版单位等）；
 * 3. vanilla_units.json  原版单位名；
 * 4. shared_images.json  内置共享图像名；
 * 5. data/param 目录下的参数 json（函数名 + 参数 key/zh）。
 *
 * 纯内存缓存，不做任何运行时写出，故无需 VerifyManager。
 */
object CodeDescriptionIndex {

    /** 索引条目：关键词 + 中文说明 + 示例 */
    data class Entry(
        val keyword: String,
        val desc: String,
        val example: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<Entry>? = null

    /** 预热（IO 线程调用）：构建并缓存索引。 */
    fun prepare(context: Context) {
        if (cache != null) return
        cache = build(context)
    }

    /** 模糊匹配（忽略大小写，匹配关键词或说明）。未预热时就地构建并缓存。 */
    fun search(context: Context, query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val list = cache ?: build(context).also { cache = it }
        val ql = q.lowercase()
        return list.filter { it.keyword.lowercase().contains(ql) || it.desc.lowercase().contains(ql) }
    }

    fun clearCache() {
        cache = null
    }

    private fun build(context: Context): List<Entry> {
        // 按关键词（忽略大小写）去重，保留首次出现的条目
        val out = LinkedHashMap<String, Entry>()
        fun add(keyword: String, desc: String, example: String) {
            val k = keyword.trim()
            if (k.isEmpty()) return
            out.putIfAbsent(k.lowercase(), Entry(k, desc.trim(), example.trim()))
        }

        // 1) data/value/*.json
        val valueFiles = try {
            context.assets.list("data/value")
                ?.filter { it.endsWith(".json") && it != "value_desc.json" }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "list data/value failed", e)
            emptyList()
        }
        for (file in valueFiles) {
            val data = try {
                ValueDataLoader.load(context, file.removeSuffix(".json"))
            } catch (e: Exception) {
                null
            } ?: continue
            for (item in data.data) {
                add(item.name, ValueDescriptionRepository.lookup(context, item.description) ?: "", item.example)
            }
        }

        // 2) value_desc.json 未被引用的说明键（取末段为关键词）
        try {
            val text = context.assets.open("data/value/value_desc.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val map = json.decodeFromString<Map<String, String>>(text)
            for ((key, zh) in map) {
                add(key.removeSuffix(".description").substringAfterLast('.'), zh, "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "load value_desc failed", e)
        }

        // 3) 原版单位名
        for (name in VanillaUnitsRepository.get(context)) {
            add(name, ValueDescriptionRepository.lookup(context, "data.value.vanillaunit.$name") ?: "", "")
        }

        // 4) 内置共享图像名
        for (name in SharedImagesRepository.get(context)) {
            add(name, ValueDescriptionRepository.lookup(context, "data.value.image.$name") ?: "", "")
        }

        // 5) 函数参数
        val paramFiles = try {
            context.assets.list("data/param")?.filter { it.endsWith(".json") } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        for (file in paramFiles) {
            val data = try {
                ParamDataLoader.load(context, file.removeSuffix(".json"))
            } catch (e: Exception) {
                null
            } ?: continue
            for (fn in data.functions) {
                add(fn.key, "函数", "")
                for (p in fn.params) {
                    add(p.key, p.zh, "")
                }
            }
        }

        return out.values.toList()
    }
}