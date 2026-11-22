package com.rwmodstudio.core.translation

import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.util.atomicWriteText
import org.json.JSONArray
import org.json.JSONObject

/**
 * 资源名翻译白名单：`翻译禁用管理` 页里可增删的英文资源名。
 *
 * 语义：`资源名禁用翻译` 开关打开时，**白名单里的资源名双向停用**（英译中/中译英都不译）；
 * 开关关闭时不使用本表。
 *
 * 默认值 = 原版内置资源名（与 assets/data/value/Prices_Resources.json 的 name 一致）。
 * 用户增删后存 `RWmod/config/resource_name_whitelist.json`（用户配置，不配验证码）。
 */
object ResourceNameWhitelist {

    private const val TAG = "ResourceNameWhitelist"

    /** 原版内置资源名（Prices_Resources.json 的 name 字段） */
    val DEFAULT_NAMES = listOf(
        "credits", "energy", "hp", "shield", "ammo",
        "setFlag", "unsetFlag", "hasFlag", "hasMissingFlag", "X"
    )

    @Volatile
    private var cached: Set<String>? = null

    /** 小写英文资源名集合（比较用）。 */
    fun names(): Set<String> {
        cached?.let { return it }
        val loaded = loadFromFile() ?: DEFAULT_NAMES
        val lowered = loaded.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        cached = lowered
        return lowered
    }

    fun contains(name: String): Boolean = name.trim().lowercase() in names()

    /** 原始大小写名单（界面展示用）。 */
    fun displayNames(): List<String> = currentRaw()

    /** 保存整份名单（原样大写形式写入文件；比较时忽略大小写）。 */
    @Synchronized
    fun save(names: Collection<String>) {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        cached = cleaned.map { it.lowercase() }.toSet()
        try {
            val json = JSONObject().apply { put("names", JSONArray(cleaned)) }
            RwmodPaths.resourceNameWhitelistFile.atomicWriteText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "save whitelist failed", e)
        }
    }

    @Synchronized
    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = currentRaw().toMutableList()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            current.add(trimmed)
            save(current)
        }
    }

    @Synchronized
    fun remove(name: String) {
        val trimmed = name.trim()
        save(currentRaw().filter { !it.equals(trimmed, ignoreCase = true) })
    }

    @Synchronized
    fun reset() {
        save(DEFAULT_NAMES)
    }

    /** 读文件原始大小写名单；文件不存在/损坏返回 null（调用方回退默认）。 */
    private fun currentRaw(): List<String> = loadFromFile() ?: DEFAULT_NAMES

    private fun loadFromFile(): List<String>? {
        return try {
            val file = RwmodPaths.resourceNameWhitelistFile
            if (!file.exists()) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("names") ?: return null
            (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "load whitelist failed", e)
            null
        }
    }
}
