package com.rwmodstudio.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rwmodstudio.util.atomicWriteText
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
    const val RESOURCE_NAME_KEYS = "resourceNameKeys"
    const val COMPLETION_TYPES = "completionTypes"
    const val MOCK_INI = "mockIni"

    /** 各功能当前验证码（修改即触发对应配置重置） */
    const val BLOCKLIST_CODE = "384758"
    const val SECTION_FILTERS_CODE = "813546"
    const val NATIVE_COMPLETIONS_CODE = "819413"
    const val EXTRA_COMPLETIONS_CODE = "530720"
    const val CODE_REFERENCE_CODE = "720524"
    const val ONBOARDING_CODE = "481203"
    const val TRANSLATION_CACHE_CODE = "100001"
    const val COMPLETIONS_CODE = "100002"
    const val SETTINGS_CODE = "100003"
    const val NATIVE_TRANSLATION_CODE = "100107"
    const val EXTRA_TRANSLATION_CODE = "100005"
    const val RESOURCE_NAME_KEYS_CODE = "620411"
    const val COMPLETION_TYPES_CODE = "731827"
    const val MOCK_INI_CODE = "481218"

    private val defaults = mapOf(
        BLOCKLIST to BLOCKLIST_CODE,
        SECTION_FILTERS to SECTION_FILTERS_CODE,
        NATIVE_COMPLETIONS to NATIVE_COMPLETIONS_CODE,
        EXTRA_COMPLETIONS to EXTRA_COMPLETIONS_CODE,
        CODE_REFERENCE to CODE_REFERENCE_CODE,
        // 首次引导的默认值必须是「未完成」：写默认值即等于把 onboarding 标记为已完成，
        // 新装/清数据后首次启动会被判定为「已引导」而永不显示引导页。
        ONBOARDING to "",
        TRANSLATION_CACHE to TRANSLATION_CACHE_CODE,
        COMPLETIONS to COMPLETIONS_CODE,
        SETTINGS to SETTINGS_CODE,
        NATIVE_TRANSLATION to NATIVE_TRANSLATION_CODE,
        EXTRA_TRANSLATION to EXTRA_TRANSLATION_CODE,
        RESOURCE_NAME_KEYS to RESOURCE_NAME_KEYS_CODE,
        COMPLETION_TYPES to COMPLETION_TYPES_CODE,
        MOCK_INI to MOCK_INI_CODE
    )

    private var migrated = false

    // 读写串行锁：设置页重置 / 翻译引擎加载 / 缓存版本计算可能并发访问 verify.json，
    // 不加锁时「整文件读→改→整文件写回」会互相覆盖丢 key，或读到写一半的坏文件（解析失败当默认值 → 数据被误重置）。
    private val verifyLock = Any()
    // 内存缓存：read 不再每次全文件 IO；write/writeAll 后同步更新，文件不存在时按默认值建缓存
    private var cache: Map<String, String>? = null

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
    fun read(key: String): String = synchronized(verifyLock) {
        ensureDefaultsLocked()
        loadCacheLocked()[key] ?: defaults[key] ?: ""
    }

    /** 写入指定验证码 */
    fun write(key: String, code: String) = synchronized(verifyLock) {
        ensureDefaultsLocked()
        // 必须先加载缓存再改：cache 未建立时若基于空 map 写回，会清掉文件中其它已存在的 key
        val map = loadCacheLocked().toMutableMap()
        map[key] = code
        writeAllLocked(map)
    }

    /** 重置所有验证码为代码默认值 */
    fun resetAll() {
        writeAll(defaults)
    }

    /** 检查验证码是否匹配代码默认值 */
    fun isValid(key: String, expected: String): Boolean = read(key) == expected

    private fun ensureDefaults() = synchronized(verifyLock) { ensureDefaultsLocked() }

    private fun ensureDefaultsLocked() {
        if (!file.exists()) {
            writeAllLocked(defaults)
        }
    }

    /** 加载内存缓存（锁内调用）：缓存未建立时读文件，避免 write 基于空 map 丢其它 key */
    private fun loadCacheLocked(): Map<String, String> {
        cache?.let { return it }
        val loaded = try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            defaults.keys.mapNotNull { k ->
                if (json.has(k)) k to json.getString(k) else null
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read verify codes", e)
            emptyMap()
        }
        cache = loaded
        return loaded
    }

    private fun writeAll(map: Map<String, String>) = synchronized(verifyLock) { writeAllLocked(map) }

    private fun writeAllLocked(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            // 原子写 + 成功后更新内存缓存，避免半写文件被误读为默认值
            file.atomicWriteText(json.toString(2), Charsets.UTF_8)
            cache = map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write verify codes", e)
        }
    }
}
