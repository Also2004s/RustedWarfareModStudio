package com.rwmodstudio.core

import android.util.Log
import com.rwmodstudio.util.atomicWriteText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 补全排序表的一条：同时登记中文显示名与英文键。
 *
 * 补全项按 name / nameEn / label 任一命中即算命中，故中文视图与英文视图都能对上。
 */
@Serializable
data class SortEntry(val zh: String = "", val en: String = "")

/**
 * 「排序自定义」管理器（单一来源）。
 *
 * 数据：`RWmod/config/sort_custom.json`，格式 `{ "<节名>": [ {"zh":"中文名","en":"英文键"}, ... ] }`，
 * 数组顺序即优先级（靠前的排前面）。
 * - 文件不存在 / 为空时用 [SortCustomizationDefaults.DEFAULT] 播种；
 * - 用户配置，**不需要验证码**（参照 `highlight_rules.json` 的做法）；
 * - 只调顺序，不增删补全条目。
 */
object SortCustomizationManager {

    private const val TAG = "SortCustom"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var cache: Map<String, List<SortEntry>>? = null

    private val file: File get() = RwmodPaths.sortCustomizationFile

    private fun normalize(s: String?): String = s?.trim()?.lowercase().orEmpty()

    /** 读取排序表（首次访问时从磁盘读，缺失则播种默认表）。 */
    fun load(): Map<String, List<SortEntry>> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = readFromDisk() ?: SortCustomizationDefaults.DEFAULT.also { writeToDisk(it) }
            cache = loaded
            return loaded
        }
    }

    private fun readFromDisk(): Map<String, List<SortEntry>>? = try {
        val f = file
        if (!f.exists() || f.length() == 0L) {
            null
        } else {
            val text = f.readText(Charsets.UTF_8)
            if (text.isBlank()) null
            else json.decodeFromString<Map<String, List<SortEntry>>>(text).takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.e(TAG, "读取排序表失败", e)
        null
    }

    private fun writeToDisk(map: Map<String, List<SortEntry>>) {
        try {
            file.atomicWriteText(json.encodeToString(map), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "写入排序表失败", e)
        }
    }

    fun entriesOf(section: String): List<SortEntry> = load()[section].orEmpty()

    /** 覆盖保存整张表。 */
    fun saveAll(map: Map<String, List<SortEntry>>) {
        synchronized(this) {
            writeToDisk(map)
            cache = map
        }
    }

    /** 更新某节的排序表。 */
    fun updateSection(section: String, list: List<SortEntry>) {
        val next = load().toMutableMap()
        next[section] = list
        saveAll(next)
    }

    /** 重置为默认表（= 频次清单）。 */
    fun resetToDefault() = saveAll(SortCustomizationDefaults.DEFAULT)

    fun resetSection(section: String) =
        updateSection(section, SortCustomizationDefaults.defaultFor(section))

    /** 丢弃缓存，下次读取重新读盘。 */
    fun invalidate() {
        synchronized(this) {
            cache = null
        }
    }

    /**
     * 取某节的排序序号：命中越靠前序号越小；未命中返回 [Int.MAX_VALUE]。
     * 候选名（name / nameEn / label）任一命中即算，取最小序号。
     */
    fun rankOf(section: String?, vararg candidates: String): Int =
        rankOf(load(), section, *candidates)

    /** 纯函数版（与磁盘无关，供测试与内部复用）：在给定表上取序号。 */
    internal fun rankOf(table: Map<String, List<SortEntry>>, section: String?, vararg candidates: String): Int {
        if (section.isNullOrBlank()) return Int.MAX_VALUE
        val list = table[section] ?: return Int.MAX_VALUE
        if (list.isEmpty()) return Int.MAX_VALUE
        var best = Int.MAX_VALUE
        for (c in candidates) {
            val key = normalize(c)
            if (key.isEmpty()) continue
            val idx = list.indexOfFirst { normalize(it.zh) == key || normalize(it.en) == key }
            if (idx >= 0 && idx < best) best = idx
        }
        return best
    }
}
