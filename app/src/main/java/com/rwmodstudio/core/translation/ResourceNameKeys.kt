package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源名补全属性名单。
 *
 * 该名单决定哪些属性在冒号后应触发「资源名=值」形态的资源名补全
 * （即用户输入 `资源活着时借用:` 时弹资源名列表）。
 *
 * 名单以**翻译库英文为准**（翻译库没有的用代码参考库英文），英文硬编码，
 * 方便后续维护。支持 `mutatorX_` 前缀通配多变体（如 `mutatorX_addResourcesAreaHit`
 * 对应实际文件中的 `mutator1_addResourcesAreaHit` / `mutator2_...`）。
 *
 * 配置持久化在 RWmod/config/resource_name_keys.json，验证码不匹配时重置默认值。
 */
data class ResourceNameKeys(
    val keys: List<String> = DEFAULT_KEYS,
    val verifyCode: String = DEFAULT_VERIFY_CODE
) {
    companion object {
        private const val TAG = "ResourceNameKeys"
        private const val FILENAME = "resource_name_keys.json"
        private val DEFAULT_VERIFY_CODE = SettingsManager.RESOURCE_NAME_KEYS_VERIFY_CODE

        /**
         * 默认名单：参与「资源名=值」资源名补全的属性英文名。
         * 以翻译库 name_en 为准，翻译库没有的（borrowResourcesWhileBuilt）用代码参考库英文。
         */
        val DEFAULT_KEYS = listOf(
            // 价格类
            "price",
            "borrowResourcesWhileAlive",        // 资源活着时借用
            "borrowResourcesWhileBuilt",        // borrowResourcesWhileBuilt
            "streamingCost",                    // 流式造价
            "generation_resources",             // 资源获取
            "resourceUsage",                    // 资源需求
            // 增加资源类
            "addResources",                     // 增加资源
            "addResourcesScaledByAIHandicaps",  // 根据AI难度添加资源
            // 用逻辑资源类
            "addResourcesWithLogic",            // 用逻辑添加资源
            "setResourcesWithLogic",            // 用逻辑设置资源
            // 修正多变体（mutatorX_ 前缀通配）
            "mutatorX_addResourcesAreaHit",     // 修正区域添加资源
            "mutatorX_addResourcesDirectHit"    // 修正直接添加资源
        )

        private val MUTATOR_WILDCARD_REGEX = Regex("mutator[0-9]+_", RegexOption.IGNORE_CASE)

        fun getFile(): File = RwmodPaths.resourceNameKeysFile

        /**
         * 加载名单，文件不存在或验证码不匹配时重建默认值。
         */
        fun load(): ResourceNameKeys {
            val file = getFile()
            if (!file.exists()) return ResourceNameKeys()
            if (SettingsManager.readVerifyCode(SettingsManager.VERIFY_RESOURCE_NAME_KEYS) != DEFAULT_VERIFY_CODE) {
                Log.d(TAG, "Verify code mismatch, reset resource name keys to default")
                return ResourceNameKeys()
            }
            return try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val array = json.optJSONArray("keys")
                val keys = if (array != null) {
                    (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotEmpty() }
                } else {
                    DEFAULT_KEYS
                }
                ResourceNameKeys(keys = keys)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load resource name keys, using default", e)
                ResourceNameKeys()
            }
        }

        /**
         * 保存名单到 RWmod/config/resource_name_keys.json。
         */
        fun save(keys: List<String>): Boolean {
            return try {
                val file = getFile()
                file.parentFile?.mkdirs()
                val json = JSONObject().apply {
                    put("keys", JSONArray(keys))
                }
                file.writeText(json.toString(2), Charsets.UTF_8)
                SettingsManager.writeVerifyCode(SettingsManager.VERIFY_RESOURCE_NAME_KEYS, DEFAULT_VERIFY_CODE)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save resource name keys", e)
                false
            }
        }

        /** 重置为默认名单（写入默认并刷新验证码） */
        fun reset(): Boolean = save(DEFAULT_KEYS)
    }

    /** 判断给定属性英文名是否命中名单（支持 mutatorX_ 前缀通配多变体） */
    fun matches(englishName: String): Boolean {
        val n = englishName.trim()
        if (n.isEmpty()) return false
        return keys.any { key ->
            val k = key.trim()
            if (k.isEmpty()) return@any false
            // 通配多变体：mutatorX_xxx 匹配 mutator<数字>_xxx（如 mutator1_addResourcesAreaHit）
            if (k.startsWith("mutatorX_", ignoreCase = true)) {
                val pattern = "mutator[0-9]+_" + Regex.escape(k.substringAfter("mutatorX_"))
                return@any Regex(pattern, RegexOption.IGNORE_CASE).matches(n)
            }
            k == n
        }
    }

    /** 将名单项统一归一化（仅用于维护页展示/新增，将 mutator<数字>_ 归一为 mutatorX_） */
    fun normalizedKey(key: String): String = MUTATOR_WILDCARD_REGEX.replace(key) { "mutatorX_" }
}