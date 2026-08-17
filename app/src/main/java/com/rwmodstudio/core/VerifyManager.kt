package com.rwmodstudio.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * 统一验证码管理器。
 *
 * 所有生成文件/配置的验证码集中存放在 RWmod/config/verify.json 中。
 * 修改代码里的验证码常量即可在下次启动时强制重置对应数据。
 */
object VerifyManager {

    private const val TAG = "VerifyManager"

    /** 验证码 key */
    const val BLOCKLIST = "blocklist"
    const val SECTION_FILTERS = "sectionFilters"
    const val NATIVE_COMPLETIONS = "nativeCompletions"
    const val EXTRA_COMPLETIONS = "extraCompletions"
    const val CODE_REFERENCE = "codeReference"
    const val ONBOARDING = "onboarding"
    const val TRANSLATION_CACHE = "translationCache"
    const val COMPLETIONS = "completions"
    const val SETTINGS = "settings"
    const val NATIVE_TRANSLATION = "nativeTranslation"
    const val EXTRA_TRANSLATION = "extraTranslation"

    /** 各功能当前验证码（修改即触发对应配置重置） */
    const val BLOCKLIST_CODE = "384757"
    const val SECTION_FILTERS_CODE = "813546"
    const val NATIVE_COMPLETIONS_CODE = "819411"
    const val EXTRA_COMPLETIONS_CODE = "530719"
    const val CODE_REFERENCE_CODE = "720523"
    const val ONBOARDING_CODE = "294817"
    const val TRANSLATION_CACHE_CODE = "100001"
    const val COMPLETIONS_CODE = "100002"
    const val SETTINGS_CODE = "100003"
    const val NATIVE_TRANSLATION_CODE = "100004"
    const val EXTRA_TRANSLATION_CODE = "100005"

    private val defaults = mapOf(
        BLOCKLIST to BLOCKLIST_CODE,
        SECTION_FILTERS to SECTION_FILTERS_CODE,
        NATIVE_COMPLETIONS to NATIVE_COMPLETIONS_CODE,
        EXTRA_COMPLETIONS to EXTRA_COMPLETIONS_CODE,
        CODE_REFERENCE to CODE_REFERENCE_CODE,
        ONBOARDING to ONBOARDING_CODE,
        TRANSLATION_CACHE to TRANSLATION_CACHE_CODE,
        COMPLETIONS to COMPLETIONS_CODE,
        SETTINGS to SETTINGS_CODE,
        NATIVE_TRANSLATION to NATIVE_TRANSLATION_CODE,
        EXTRA_TRANSLATION to EXTRA_TRANSLATION_CODE
    )

    private var migrated = false

    private val file get() = RwmodPaths.verifyFile

    /** 初始化并迁移旧版验证码（SharedPreferences 或 RWmod/verify.json / verify.txt） */
    fun init(context: Context) {
        if (migrated) return
        migrated = true
        if (file.exists()) return

        try {
            val map = defaults.toMutableMap()

            // 1. 尝试从 SharedPreferences 读取
            val prefs = context.getSharedPreferences("rusted_mod_studio_prefs", Context.MODE_PRIVATE)
            for (key in defaults.keys) {
                val legacyKey = "verify_code_$key"
                val saved = prefs.getString(legacyKey, null)
                if (!saved.isNullOrBlank()) {
                    map[key] = saved
                }
            }

            // 2. 尝试从旧版 verify.json 读取
            val legacyJsonFile = java.io.File(RwmodPaths.rwmodDir, "verify.json")
            if (legacyJsonFile.exists()) {
                val json = JSONObject(legacyJsonFile.readText(Charsets.UTF_8))
                for (key in defaults.keys) {
                    if (json.has(key)) {
                        map[key] = json.getString(key)
                    }
                }
            }

            // 3. 尝试从旧版 verify.txt 读取（单条验证码，同步到所有 key）
            val legacyTxtFile = java.io.File(RwmodPaths.rwmodDir, "verify.txt")
            if (legacyTxtFile.exists()) {
                val code = legacyTxtFile.readText(Charsets.UTF_8).trim()
                if (code.isNotEmpty()) {
                    for (key in defaults.keys) {
                        map[key] = code
                    }
                }
            }

            writeAll(map)
            Log.d(TAG, "Migrated verify codes to $file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate verify codes", e)
            writeAll(defaults)
        }
    }

    /** 读取指定验证码，文件不存在时写入默认值 */
    fun read(key: String): String {
        ensureDefaults()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.optString(key, defaults[key] ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read verify code $key", e)
            defaults[key] ?: ""
        }
    }

    /** 写入指定验证码 */
    fun write(key: String, code: String) {
        ensureDefaults()
        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.put(key, code)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write verify code $key", e)
        }
    }

    /** 重置所有验证码为代码默认值 */
    fun resetAll() {
        writeAll(defaults)
    }

    /** 检查验证码是否匹配代码默认值 */
    fun isValid(key: String, expected: String): Boolean = read(key) == expected

    private fun ensureDefaults() {
        if (!file.exists()) {
            writeAll(defaults)
        }
    }

    private fun writeAll(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            file.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write verify codes", e)
        }
    }
}
