package com.rwmodstudio.feature.completion.value

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "VanillaUnitsRepository"

/**
 * 原版单位名仓库。
 * 读取 assets/data/vanilla_units.json（来自 rw_analysis 逆向解析的原版全部单位名），
 * 作为「原版单位」值补全的内置候选。纯静态只读，不做任何运行时写出，故无需 VerifyManager。
 */
object VanillaUnitsRepository {

    @Serializable
    private data class VanillaUnitsFile(
        val version: Int = 0,
        val source: String = "",
        val units: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var cache: Set<String>? = null

    /** 读取全部原版单位名，成功后缓存（进程内）。失败返回空集。 */
    fun get(context: Context): Set<String> {
        cache?.let { return it }
        val result = try {
            val text = context.assets.open("data/vanilla_units.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<VanillaUnitsFile>(text).units
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load vanilla_units.json", e)
            emptySet()
        }
        cache = result
        return result
    }

    fun clearCache() {
        cache = null
    }
}