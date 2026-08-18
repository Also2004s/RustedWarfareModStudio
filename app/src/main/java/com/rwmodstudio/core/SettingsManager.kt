package com.rwmodstudio.core

import android.content.Context
import android.util.Log

object SettingsManager {

    private const val TAG = "SettingsManager"

    val FONT_FAMILIES = setOf("system", "system_mono", "jetbrains_mono", "lxgw_wenkai_regular")

    private lateinit var settings: FileSettings
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context
        // 初始化 RWmod 路径结构、统一验证码、文件版设置存储
        RwmodPaths.rwmodDir
        VerifyManager.init(context)
        settings = FileSettings.create(context)
        migrateFileStorage(context)
        migrateDefaults()
        initLocalFiles()
        pruneRecentFiles()
    }

    /**
     * 一次性迁移：把旧版本散落在 RWmod 根目录、filesDir、cacheDir 的文件移到新分类目录。
     */
    private fun migrateFileStorage(context: Context) {
        try {
            val moved = mutableListOf<String>()
            fun move(src: java.io.File?, dst: java.io.File) {
                if (src == null || !src.exists()) return
                try {
                    dst.parentFile?.mkdirs()
                    if (src.isDirectory) {
                        src.copyRecursively(dst, overwrite = true)
                        src.deleteRecursively()
                    } else {
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                    }
                    moved.add("${src.name} -> ${dst.parentFile?.name}/${dst.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate ${src.absolutePath}", e)
                }
            }

            fun mergeDir(src: java.io.File, dst: java.io.File) {
                if (!src.exists() || !src.isDirectory) return
                dst.mkdirs()
                src.listFiles()?.forEach { child ->
                    val target = java.io.File(dst, child.name)
                    try {
                        if (child.isDirectory) {
                            mergeDir(child, target)
                            child.deleteRecursively()
                        } else {
                            child.copyTo(target, overwrite = true)
                            child.delete()
                        }
                        moved.add("${child.name} -> ${dst.name}/${child.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to merge ${child.absolutePath} into ${dst.absolutePath}", e)
                    }
                }
                if (src.listFiles()?.isEmpty() == true) src.delete()
            }

            // 旧路径 Android/RWmod -> 新路径 RWmod/对应分类目录
            val legacyAndroid = RwmodPaths.legacyAndroidRwmodRoot
            if (legacyAndroid.exists()) {
                val fileDestinations = mapOf(
                    "custom_completions.json" to RwmodPaths.customCompletionsFile,
                    "native_completions.json" to RwmodPaths.nativeCompletionsFile,
                    "native_completions_en.json" to RwmodPaths.nativeCompletionsEnFile,
                    "user_completions.json" to RwmodPaths.userCompletionsFile,
                    "user_completions_en.json" to RwmodPaths.userCompletionsEnFile,
                    "extra_completions.json" to RwmodPaths.extraCompletionsFile,
                    "extra_completions_en.json" to RwmodPaths.extraCompletionsEnFile,
                    "user_translation.json" to RwmodPaths.userTranslationFile,
                    "translation.txt" to RwmodPaths.legacyTranslationFile,
                    "translation_cache.txt" to RwmodPaths.translationCacheFile,
                    "translation_cache_meta.json" to RwmodPaths.translationCacheMetaFile,
                    "translation_cache_sources.json" to RwmodPaths.translationCacheSourcesFile,
                    "section_filters.json" to RwmodPaths.sectionFiltersFile,
                    "translation_blocklist.json" to RwmodPaths.translationBlocklistFile,
                    "verified_behaviors.json" to RwmodPaths.verifiedBehaviorsFile,
                    "project_registry.txt" to RwmodPaths.projectRegistryFile,
                    "save_history.json" to RwmodPaths.saveHistoryFile,
                    "code_reference.json" to RwmodPaths.codeReferenceFile,
                    "config_export.zip" to RwmodPaths.localConfigExportFile,
                    "dedup_words.txt" to RwmodPaths.dedupWordsFile,
                    "verify.json" to RwmodPaths.verifyFile,
                    "settings.json" to RwmodPaths.settingsFile,
                    "import_config_temp.zip" to RwmodPaths.importConfigTempFile
                )
                val dirDestinations = mapOf(
                    "completions" to RwmodPaths.completionsDir,
                    "translation" to RwmodPaths.translationDir,
                    "config" to RwmodPaths.configDir,
                    "dedup" to RwmodPaths.dedupDir,
                    "查重" to RwmodPaths.dedupDir,
                    "todos" to RwmodPaths.todosDir,
                    "project_todos" to RwmodPaths.todosDir,
                    "imports" to RwmodPaths.importsDir,
                    "exports" to RwmodPaths.exportsDir,
                    "cache" to RwmodPaths.cacheDir
                )
                RwmodPaths.migratedDir.mkdirs()
                legacyAndroid.listFiles()?.forEach { src ->
                    when {
                        src.isDirectory -> {
                            val dst = dirDestinations[src.name] ?: RwmodPaths.migratedDir
                            mergeDir(src, dst)
                        }
                        else -> {
                            val dst = fileDestinations[src.name] ?: java.io.File(RwmodPaths.migratedDir, src.name)
                            move(src, dst)
                        }
                    }
                }
                if (legacyAndroid.listFiles()?.isEmpty() == true) legacyAndroid.delete()
            }

            // RWmod 根目录 -> completions/
            move(java.io.File(rwmodDir, "custom_completions.json"), RwmodPaths.customCompletionsFile)
            move(java.io.File(rwmodDir, "native_completions.json"), RwmodPaths.nativeCompletionsFile)
            move(java.io.File(rwmodDir, "native_completions_en.json"), RwmodPaths.nativeCompletionsEnFile)
            move(java.io.File(rwmodDir, "user_completions.json"), RwmodPaths.userCompletionsFile)
            move(java.io.File(rwmodDir, "user_completions_en.json"), RwmodPaths.userCompletionsEnFile)
            move(java.io.File(rwmodDir, "extra_completions.json"), RwmodPaths.extraCompletionsFile)
            move(java.io.File(rwmodDir, "extra_completions_en.json"), RwmodPaths.extraCompletionsEnFile)

            // RWmod 根目录 -> translation/
            move(java.io.File(rwmodDir, "user_translation.json"), RwmodPaths.userTranslationFile)
            move(java.io.File(rwmodDir, "translation.txt"), RwmodPaths.legacyTranslationFile)
            move(java.io.File(rwmodDir, "translation_cache.txt"), RwmodPaths.translationCacheFile)
            move(java.io.File(rwmodDir, "translation_cache_meta.json"), RwmodPaths.translationCacheMetaFile)
            move(java.io.File(rwmodDir, "translation_cache_sources.json"), RwmodPaths.translationCacheSourcesFile)

            // RWmod 根目录 -> config/
            move(java.io.File(rwmodDir, "section_filters.json"), RwmodPaths.sectionFiltersFile)
            move(java.io.File(rwmodDir, "translation_blocklist.json"), RwmodPaths.translationBlocklistFile)
            move(java.io.File(rwmodDir, "verified_behaviors.json"), RwmodPaths.verifiedBehaviorsFile)
            move(java.io.File(rwmodDir, "project_registry.txt"), RwmodPaths.projectRegistryFile)
            move(java.io.File(rwmodDir, "save_history.json"), RwmodPaths.saveHistoryFile)

            // 旧目录 -> 新目录
            move(RwmodPaths.legacyDedupDir, RwmodPaths.dedupDir)
            move(RwmodPaths.legacyTodosDir, RwmodPaths.todosDir)

            // filesDir/data/code_reference.json -> cache/
            move(java.io.File(context.filesDir, "data/code_reference.json"), RwmodPaths.codeReferenceFile)

            if (moved.isNotEmpty()) {
                Log.d(TAG, "Migrated files: $moved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "migrateFileStorage failed", e)
        }
    }

    private fun migrateDefaults() {
        try {
            // dev_value_completion 旧版默认 false；本次一次性强制迁移为 true，
            // 并记录 migration 标记避免重复覆盖用户后续手动设置。
            if (!settings.contains("dev_value_completion_migration_v1")) {
                settings.edit()
                    .putBoolean("dev_value_completion", true)
                    .putBoolean("dev_value_completion_migration_v1", true)
                    .apply()
                Log.d(TAG, "Migrated dev_value_completion to true (one-time)")
            }
            // 翻译自动空格旧版默认 true；本次一次性强制迁移为 false。
            if (!settings.contains("auto_space_migration_v1")) {
                settings.edit()
                    .putBoolean("auto_space", false)
                    .putBoolean("auto_space_migration_v1", true)
                    .apply()
                Log.d(TAG, "Migrated auto_space to false (one-time)")
            }
        } catch (e: Exception) { Log.w(TAG, "设置/迁移操作失败", e) }
    }

    /** 外部存储根目录下的 RWmod 工作目录 */
    val rwmodDir: java.io.File
        get() = RwmodPaths.rwmodDir

    /** 兼容旧代码：返回 RWmod 目录绝对路径 */
    val localModPath: String
        get() = rwmodDir.absolutePath

    private fun initLocalFiles() {
        try {
            val customFile = RwmodPaths.customCompletionsFile
            if (!customFile.exists()) customFile.writeText("{\"native\":[],\"user\":[]}")
        } catch (e: Exception) {
            Log.e(TAG, "initLocalFiles failed", e)
        }
    }

    val nativeCompletionsPath: String
        get() = RwmodPaths.nativeCompletionsFile.absolutePath

    val nativeCompletionsEnPath: String
        get() = RwmodPaths.nativeCompletionsEnFile.absolutePath

    val userCompletionsPath: String
        get() = RwmodPaths.userCompletionsFile.absolutePath

    val userCompletionsEnPath: String
        get() = RwmodPaths.userCompletionsEnFile.absolutePath

    val userTranslationPath: String
        get() = RwmodPaths.userTranslationFile.absolutePath

    val extraCompletionsPath: String
        get() = RwmodPaths.extraCompletionsFile.absolutePath

    val extraCompletionsEnPath: String
        get() = RwmodPaths.extraCompletionsEnFile.absolutePath

    // 验证码 key 与默认值常量（保留以兼容旧调用方）
    const val VERIFY_BLOCKLIST = VerifyManager.BLOCKLIST
    const val VERIFY_SECTION_FILTERS = VerifyManager.SECTION_FILTERS
    const val VERIFY_NATIVE_COMPLETIONS = VerifyManager.NATIVE_COMPLETIONS
    const val VERIFY_EXTRA_COMPLETIONS = VerifyManager.EXTRA_COMPLETIONS
    const val VERIFY_CODE_REFERENCE = VerifyManager.CODE_REFERENCE
    const val VERIFY_ONBOARDING = VerifyManager.ONBOARDING

    const val BLOCKLIST_VERIFY_CODE = VerifyManager.BLOCKLIST_CODE
    const val SECTION_FILTERS_VERIFY_CODE = VerifyManager.SECTION_FILTERS_CODE
    const val NATIVE_COMPLETIONS_VERIFY_CODE = VerifyManager.NATIVE_COMPLETIONS_CODE
    const val EXTRA_COMPLETIONS_VERIFY_CODE = VerifyManager.EXTRA_COMPLETIONS_CODE
    const val CODE_REFERENCE_VERIFY_CODE = VerifyManager.CODE_REFERENCE_CODE
    const val ONBOARDING_VERIFY_CODE = VerifyManager.ONBOARDING_CODE

    /** 读取指定功能的验证码（统一从 RWmod/config/verify.json） */
    fun readVerifyCode(key: String): String = VerifyManager.read(key)

    /** 写入指定功能的验证码 */
    fun writeVerifyCode(key: String, code: String) = VerifyManager.write(key, code)

    /** 重置所有验证码到默认值 */
    fun resetAllVerifyCodes() = VerifyManager.resetAll()

    var fontSize: Float
        get() {
            val raw = settings.getFloat("font_size", 14f)
            return when {
                raw.isNaN() || raw.isInfinite() -> 14f
                raw < 8f -> 8f
                raw > 32f -> 32f
                else -> raw
            }
        }
        set(value) {
            val clamped = when {
                value.isNaN() || value.isInfinite() -> 14f
                value < 8f -> 8f
                value > 32f -> 32f
                else -> value
            }
            settings.edit().putFloat("font_size", clamped).apply()
        }

    var autoWrap: Boolean
        get() = settings.getBoolean("auto_wrap", true)
        set(value) = settings.edit().putBoolean("auto_wrap", value).apply()

    var smartWrap: Boolean
        get() = settings.getBoolean("smart_wrap", true)
        set(value) = settings.edit().putBoolean("smart_wrap", value).apply()

    var defaultPath: String
        get() = settings.getString("default_path", "") ?: ""
        set(value) = settings.edit().putString("default_path", value).apply()

    /** 上次自动弹出更新提示的日期（yyyy-MM-dd），用于"一天最多提示一次" */
    var lastUpdatePromptDate: String
        get() = settings.getString("last_update_prompt_date", "") ?: ""
        set(value) = settings.edit().putString("last_update_prompt_date", value).apply()

    /** 用户已关闭（以后再说）的更新版本号，该版本不再自动提示 */
    var dismissedUpdateVersion: String
        get() = settings.getString("dismissed_update_version", "") ?: ""
        set(value) = settings.edit().putString("dismissed_update_version", value).apply()

    /**
     * 返回首页/设置页使用的默认模组目录。
     * 若用户已设置 defaultPath 则直接返回；否则优先返回 rustedWarfare/units，
     * 不存在则回退到 RWmod 工作目录（与旧行为兼容）。
     */
    fun defaultModPath(): String {
        val saved = defaultPath
        if (saved.isNotBlank()) return saved
        val es = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            java.io.File(es, "rustedWarfare/units"),
            java.io.File(es, "Android/data/com.corrodinggames.rts/files/rustedWarfare/units"),
            java.io.File(es, "games/com.corrodinggames.rts/units")
        ).firstOrNull { it.exists() }?.absolutePath
            ?: java.io.File(es, "rustedWarfare/units").absolutePath
    }

    var lastPath: String
        get() = settings.getString("last_path", "") ?: ""
        set(value) = settings.edit().putString("last_path", value).apply()

    var lastSearchQuery: String
        get() = settings.getString("last_search_query", "") ?: ""
        set(value) = settings.edit().putString("last_search_query", value).apply()

    var lastReplaceText: String
        get() = settings.getString("last_replace_text", "") ?: ""
        set(value) = settings.edit().putString("last_replace_text", value).apply()

    var isDarkTheme: Boolean
        get() = settings.getBoolean("is_dark_theme", true)
        set(value) = settings.edit().putBoolean("is_dark_theme", value).apply()

    var bgColor: String
        get() = settings.getString("bg_color", "#1E1E1E") ?: "#1E1E1E"
        set(value) = settings.edit().putString("bg_color", value).apply()

    var highlightTheme: String
        get() {
            val raw = settings.getString("highlight_theme", "dark") ?: "dark"
            return if (raw in setOf("dark", "light", "pure", "custom")) raw else "dark"
        }
        set(value) = settings.edit().putString("highlight_theme", value).apply()

    var darkTokenColors: String
        get() {
            val saved = settings.getString("dark_token_colors", null)
            if (!saved.isNullOrBlank()) return saved
            // 兼容旧版单一深色高亮色设置
            val old = settings.getString("dark_highlight_color", "#569CD6") ?: "#569CD6"
            return DarkThemeColors.Default.copy(ui = old, section = old).toJson()
        }
        set(value) = settings.edit().putString("dark_token_colors", value).apply()

    /**
     * 编辑器字体族。
     * 可选值：system / system_mono / jetbrains_mono / lxgw_wenkai_regular
     */
    var editorFontFamily: String
        get() {
            val saved = settings.getString("editor_font_family", null)
            // 兼容旧版单键字体族
            val migrated = when {
                saved == "lxgw_wenkai" -> "lxgw_wenkai_regular"
                saved == "maple_mono" -> "system_mono"
                saved?.startsWith("jetbrains_mono_") == true -> "jetbrains_mono"
                else -> saved
            }
            if (!migrated.isNullOrBlank() && migrated in FONT_FAMILIES) return migrated
            // 兼容旧版：跟随系统字体 / JetBrains Mono 开关
            val oldSystem = settings.getBoolean("use_system_font", false)
            val oldJb = settings.getBoolean("use_jetbrains_mono", false)
            return when {
                oldSystem -> "system"
                oldJb -> "jetbrains_mono"
                else -> "lxgw_wenkai_regular"
            }
        }
        set(value) {
            if (value in FONT_FAMILIES) {
                settings.edit()
                    .putString("editor_font_family", value)
                    .remove("use_system_font")
                    .remove("use_jetbrains_mono")
                    .apply()
            }
        }

    /**
     * 旧版兼容：是否使用系统默认字体。
     */
    var useSystemFont: Boolean
        get() = editorFontFamily == "system"
        set(value) { editorFontFamily = if (value) "system" else "system_mono" }



    var recentFiles: List<String>
        get() = (settings.getString("recent_files", "") ?: "").split(";").filter { it.isNotEmpty() }
        set(value) = settings.edit().putString("recent_files", value.take(20).joinToString(";")).apply()

    /**
     * 清理最近文件列表中已失效（文件不存在）的条目。
     * 该操作会写入 SharedPreferences，因此只在初始化或添加新文件时显式调用，
     * 避免在 getter 中产生写副作用。
     */
    private fun pruneRecentFiles() {
        try {
            val list = (settings.getString("recent_files", "") ?: "").split(";").filter { it.isNotEmpty() }
            val valid = list.filter { java.io.File(it).exists() }
            if (valid.size != list.size) {
                settings.edit().putString("recent_files", valid.take(20).joinToString(";")).apply()
            }
        } catch (e: Exception) { Log.w(TAG, "设置/迁移操作失败", e) }
    }

    fun addRecentFile(path: String) {
        pruneRecentFiles()
        val current = recentFiles.toMutableList()
        current.remove(path)
        current.add(0, path)
        recentFiles = current.take(20)
    }

    var customCompletions: String
        get() = settings.getString("custom_completions", "[]") ?: "[]"
        set(value) = settings.edit().putString("custom_completions", value).apply()

    var autoSave: Boolean
        get() = settings.getBoolean("auto_save", true)
        set(value) = settings.edit().putBoolean("auto_save", value).apply()

    var completionDetailEnabled: Boolean
        get() = settings.getBoolean("completion_detail_enabled", true)
        set(value) = settings.edit().putBoolean("completion_detail_enabled", value).apply()

    /** 非值限制补全：开启后，光标所在行已有键值时隐藏普通键补全，仅保留值/values/特定值 */
    var nonValueCompletionLimited: Boolean
        get() = settings.getBoolean("non_value_completion_limited", true)
        set(value) = settings.edit().putBoolean("non_value_completion_limited", value).apply()

    var autoSpace: Boolean
        get() = settings.getBoolean("auto_space", false)
        set(value) = settings.edit().putBoolean("auto_space", value).apply()

    /** 彩虹括号：按嵌套深度循环为括号上色 */
    var rainbowBrackets: Boolean
        get() = settings.getBoolean("rainbow_brackets", true)
        set(value) = settings.edit().putBoolean("rainbow_brackets", value).apply()

    /** 括号诊断：检查文本中未匹配的括号并通过编辑器诊断系统标记 */
    var bracketDiagnostics: Boolean
        get() = settings.getBoolean("bracket_diagnostics", false)
        set(value) = settings.edit().putBoolean("bracket_diagnostics", value).apply()

    /** 软换行提示符：自动换行开启时，在折行处绘制 ↵ 提示符 */
    var wrapIndicatorEnabled: Boolean
        get() = settings.getBoolean("wrap_indicator_enabled", true)
        set(value) = settings.edit().putBoolean("wrap_indicator_enabled", value).apply()

    /**
     * 彩虹括号分层强度，范围 0~2，默认 1.0。
     * 首次读取时会把旧的 `rainbow_bracket_gradient` 迁移过来。
     */
    var rainbowBracketIntensity: Float
        get() {
            val legacy = settings.getFloat("rainbow_bracket_gradient", -1f)
            return if (legacy >= 0f) {
                val migrated = legacy.coerceIn(0f, 2f)
                settings.edit()
                    .remove("rainbow_bracket_gradient")
                    .putFloat("rainbow_bracket_intensity", migrated)
                    .apply()
                migrated
            } else {
                settings.getFloat("rainbow_bracket_intensity", 1.0f)
            }
        }
        set(value) = settings.edit().putFloat("rainbow_bracket_intensity", value.coerceIn(0f, 2f)).apply()

    /** 彩虹括号：相邻两层在色环上的间隔角度，0°~180°，默认 60° */
    var rainbowHueStep: Float
        get() = settings.getFloat("rainbow_hue_step", 60f)
        set(value) = settings.edit().putFloat("rainbow_hue_step", value.coerceIn(0f, 180f)).apply()

    /** 彩虹括号：色相旋转方向，0=朝背景反色，1=固定顺时针，2=固定逆时针 */
    var rainbowHueDirection: Int
        get() = settings.getInt("rainbow_hue_direction", 0)
        set(value) = settings.edit().putInt("rainbow_hue_direction", value.coerceIn(0, 2)).apply()

    /** 彩虹括号：每层饱和度增强，-0.3~+0.3，默认 0 */
    var rainbowSaturationBoost: Float
        get() = settings.getFloat("rainbow_saturation_boost", 0f)
        set(value) = settings.edit().putFloat("rainbow_saturation_boost", value.coerceIn(-0.3f, 0.3f)).apply()

    /** 彩虹括号：每层亮度偏移，-0.3~+0.3，默认 0 */
    var rainbowLightnessShift: Float
        get() = settings.getFloat("rainbow_lightness_shift", 0f)
        set(value) = settings.edit().putFloat("rainbow_lightness_shift", value.coerceIn(-0.3f, 0.3f)).apply()

    /** 彩虹括号：亮度偏移是否根据背景深浅自动取反方向 */
    var rainbowAutoLightnessDirection: Boolean
        get() = settings.getBoolean("rainbow_auto_lightness_direction", true)
        set(value) = settings.edit().putBoolean("rainbow_auto_lightness_direction", value).apply()

    /** 彩虹括号：是否开启可见性保护，防止颜色融进背景 */
    var rainbowVisibilityGuard: Boolean
        get() = settings.getBoolean("rainbow_visibility_guard", true)
        set(value) = settings.edit().putBoolean("rainbow_visibility_guard", value).apply()

    var completionFilterSections: String
        get() = settings.getString("completion_filter_sections", "") ?: ""
        set(value) = settings.edit().putString("completion_filter_sections", value).apply()

    /** 各节默认启用的补全分类（基于自定义补全表的 category） */
    val DEFAULT_SECTION_FILTERS = mapOf(
        "核心" to setOf("核心"),
        "图像" to setOf("图像"),
        "攻击" to setOf("攻击"),
        "资源" to setOf("资源", "全局资源"),
        "全局资源" to setOf("资源", "全局资源"),
        "AI" to setOf("AI"),
        "动画" to setOf("动画"),
        "行动" to setOf("行动"),
        "隐藏行动" to setOf("行动"),
        "可建造" to setOf("可建造"),
        "抛射体" to setOf("抛射体"),
        "放置规则" to setOf("放置规则"),
        "腿" to setOf("腿"),
        "附属" to setOf("附属"),
        "运动" to setOf("运动"),
        "炮塔" to setOf("炮塔"),
        "效果" to setOf("效果"),
        "贴花" to setOf("贴花")
    )

    private val sectionFiltersFile: java.io.File
        get() = RwmodPaths.sectionFiltersFile

    fun resetSectionFilters(): Boolean {
        return try {
            saveAllSectionFilters(DEFAULT_SECTION_FILTERS)
            writeVerifyCode(VERIFY_SECTION_FILTERS, SECTION_FILTERS_VERIFY_CODE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "resetSectionFilters failed", e)
            false
        }.also {
            // 清空旧 SharedPreferences 数据
            settings.edit().remove("completion_filter_sections").remove("completion_filter_verify_code").apply()
        }
    }

    fun saveAllSectionFilters(filters: Map<String, Set<String>>) {
        try {
            val json = org.json.JSONObject().apply {
                for ((section, cats) in filters) {
                    put(section, org.json.JSONArray(cats.toList()))
                }
            }
            sectionFiltersFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save section filters", e)
        }
    }

    fun saveSectionFilter(sectionName: String, enabledCategories: Set<String>) {
        val all = loadAllSectionFilters().toMutableMap()
        if (enabledCategories.isEmpty()) all.remove(sectionName) else all[sectionName] = enabledCategories
        saveAllSectionFilters(all)
    }

    fun loadAllSectionFilters(): Map<String, Set<String>> {
        // 验证码校验：不匹配则重置为默认
        if (readVerifyCode(VERIFY_SECTION_FILTERS) != SECTION_FILTERS_VERIFY_CODE) {
            resetSectionFilters()
            return DEFAULT_SECTION_FILTERS
        }
        return try {
            val result = mutableMapOf<String, MutableSet<String>>()
            if (!sectionFiltersFile.exists()) {
                // 一次性迁移旧 SharedPreferences 数据
                migrateLegacySectionFilters(result)
                if (result.isEmpty()) {
                    // 无旧数据时写入默认配置
                    saveAllSectionFilters(DEFAULT_SECTION_FILTERS)
                    return DEFAULT_SECTION_FILTERS
                }
                return result
            }
            val json = org.json.JSONObject(sectionFiltersFile.readText(Charsets.UTF_8))
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = json.optJSONArray(key)
                val cats = mutableSetOf<String>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val v = array.optString(i, "")
                        if (v.isNotEmpty()) cats.add(v)
                    }
                }
                if (cats.isNotEmpty()) result[key] = cats
            }
            // 文件存在但内容为空（{}）时，也恢复默认配置
            if (result.isEmpty()) {
                saveAllSectionFilters(DEFAULT_SECTION_FILTERS)
                return DEFAULT_SECTION_FILTERS
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load section filters", e)
            saveAllSectionFilters(DEFAULT_SECTION_FILTERS)
            DEFAULT_SECTION_FILTERS
        }
    }

    private fun migrateLegacySectionFilters(result: MutableMap<String, MutableSet<String>>) {
        try {
            val raw = settings.getString("completion_filter_sections", "") ?: ""
            if (raw.isEmpty()) return
            for (entry in raw.split(";")) {
                if (entry.isEmpty()) continue
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) {
                    val cats = parts[1].split(",").filter { it.isNotEmpty() }.toMutableSet()
                    if (cats.isNotEmpty()) result[parts[0]] = cats
                }
            }
            if (result.isNotEmpty()) {
                saveAllSectionFilters(result)
                settings.edit().remove("completion_filter_sections").remove("completion_filter_verify_code").apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "migrateLegacySectionFilters failed", e)
        }
    }

    // 自定义补全
    var customCompletionsJson: String
        get() = settings.getString("custom_completions_json", "[]") ?: "[]"
        set(value) = settings.edit().putString("custom_completions_json", value).apply()

    // 翻译库自定义条目
    var customTranslations: String
        get() = settings.getString("custom_translations", "") ?: ""
        set(value) = settings.edit().putString("custom_translations", value).apply()

    // 保存翻译库后自动刷新自定义补全表
    var autoRefreshCompletionsOnTranslationSave: Boolean
        get() = settings.getBoolean("auto_refresh_completions_on_translation_save", true)
        set(value) = settings.edit().putBoolean("auto_refresh_completions_on_translation_save", value).apply()

    // 记录上一次执行“从英文补全表反查翻译库刷新中文表”时的原生表验证码。
    // 验证码变化（数据重置/更新）后自动重新刷新一次。
    var completionTranslationRefreshCode: String
        get() = settings.getString("completion_translation_refresh_code", "") ?: ""
        set(value) = settings.edit().putString("completion_translation_refresh_code", value).apply()

    // 翻译屏蔽词开关（配置文件在外部 RWmod/translation_blocklist.json）
    var translationBlockEnabled: Boolean
        get() = settings.getBoolean("translation_block_enabled", true)
        set(value) = settings.edit().putBoolean("translation_block_enabled", value).apply()

    // 快捷符号栏
    var customSymbolsJson: String
        get() = settings.getString("custom_symbols_json", "") ?: ""
        set(value) = settings.edit().putString("custom_symbols_json", value).apply()

    // 自定义补全格式（key: "属性名", value: "格式模板"）
    var customCompletionFormats: String
        get() = settings.getString("custom_completion_formats", "") ?: ""
        set(value) = settings.edit().putString("custom_completion_formats", value).apply()

    // 自定义分类（key: "分类名", value: "true"）
    var customCategories: String
        get() = settings.getString("custom_categories", "") ?: ""
        set(value) = settings.edit().putString("custom_categories", value).apply()

    // 开发者模式
    var devMode: Boolean
        get() = settings.getBoolean("dev_mode", false)
        set(value) = settings.edit().putBoolean("dev_mode", value).apply()

    /** 坐标可视化（开发者实验功能） */
    var devCoordVisual: Boolean
        get() = settings.getBoolean("dev_coord_visual_v2", true)
        set(value) = settings.edit().putBoolean("dev_coord_visual_v2", value).apply()

    // === 补全相关子开关 ===
    var devCompletionProvider: Boolean
        get() = settings.getBoolean("dev_completion_provider", true)
        set(value) = settings.edit().putBoolean("dev_completion_provider", value).apply()

    /** 值补全总开关（开发者模式） */
    var devValueCompletion: Boolean
        get() = settings.getBoolean("dev_value_completion", true)
        set(value) = settings.edit().putBoolean("dev_value_completion", value).apply()
    /** 布尔值补全 */
    var devValueCompletionBool: Boolean
        get() = settings.getBoolean("dev_value_completion_bool", true)
        set(value) = settings.edit().putBoolean("dev_value_completion_bool", value).apply()
    /** LogicBoolean 值补全 */
    var devValueCompletionLogicBoolean: Boolean
        get() = settings.getBoolean("dev_value_completion_logic_boolean", true)
        set(value) = settings.edit().putBoolean("dev_value_completion_logic_boolean", value).apply()
    /** 枚举值补全 */
    var devValueCompletionEnum: Boolean
        get() = settings.getBoolean("dev_value_completion_enum", true)
        set(value) = settings.edit().putBoolean("dev_value_completion_enum", value).apply()
    /** 图片路径补全 */
    var devValueCompletionImage: Boolean
        get() = settings.getBoolean("dev_value_completion_image", false)
        set(value) = settings.edit().putBoolean("dev_value_completion_image", value).apply()
    /** 单位/抛射体生成补全 */
    var devValueCompletionUnitSpawn: Boolean
        get() = settings.getBoolean("dev_value_completion_unit_spawn", true)
        set(value) = settings.edit().putBoolean("dev_value_completion_unit_spawn", value).apply()
    /** autoTriggerOnEvent 事件补全 */
    var devValueCompletionAutoTriggerOnEvent: Boolean
        get() = settings.getBoolean("dev_value_completion_auto_trigger_on_event", true)
        set(value) = settings.edit().putBoolean("dev_value_completion_auto_trigger_on_event", value).apply()

    // === 解析相关子开关 ===
    var devSectionParsing: Boolean
        get() = settings.getBoolean("dev_section_parsing", true)
        set(value) = settings.edit().putBoolean("dev_section_parsing", value).apply()
    var devTranslationEngine: Boolean
        get() = settings.getBoolean("dev_translation_engine", true)
        set(value) = settings.edit().putBoolean("dev_translation_engine", value).apply()
    var devDebugTaskProgress: Boolean
        get() = settings.getBoolean("dev_debug_task_progress", true)
        set(value) = settings.edit().putBoolean("dev_debug_task_progress", value).apply()

    // === UI相关子开关 ===
    var devLineNumber: Boolean
        get() = settings.getBoolean("dev_line_number", true)
        set(value) = settings.edit().putBoolean("dev_line_number", value).apply()
    var devTabBar: Boolean
        get() = settings.getBoolean("dev_tab_bar", true)
        set(value) = settings.edit().putBoolean("dev_tab_bar", value).apply()
    /** 小菜单栏显示复制文件名/路径 */
    var devShowCopyPath: Boolean
        get() = settings.getBoolean("dev_show_copy_path", false)
        set(value) = settings.edit().putBoolean("dev_show_copy_path", value).apply()
    /** 节名显示栏（点击可跳转） */
    var devSectionBar: Boolean
        get() = settings.getBoolean("dev_section_bar", false)
        set(value) = settings.edit().putBoolean("dev_section_bar", value).apply()
    /** 节补全：光标在节内空行时自动弹出补全 */
    var devSectionCompletion: Boolean
        get() = settings.getBoolean("dev_section_completion", true)
        set(value) = settings.edit().putBoolean("dev_section_completion", value).apply()
    /** 行尾灯泡（强制翻译单行 value） */
    var devLightbulbEnabled: Boolean
        get() = settings.getBoolean("dev_lightbulb_enabled", false)
        set(value) = settings.edit().putBoolean("dev_lightbulb_enabled", value).apply()
    /** 继承链查看（编辑器更多菜单） */
    var devInheritanceView: Boolean
        get() = settings.getBoolean("dev_inheritance_view", false)
        set(value) = settings.edit().putBoolean("dev_inheritance_view", value).apply()

    // === 保存相关子开关 ===
    var devSaveOnPause: Boolean
        get() = settings.getBoolean("dev_save_on_pause", true)
        set(value) = settings.edit().putBoolean("dev_save_on_pause", value).apply()

    // === 文件加载相关子开关 ===
    var devFileLoading: Boolean
        get() = settings.getBoolean("dev_file_loading", true)
        set(value) = settings.edit().putBoolean("dev_file_loading", value).apply()
    var devRecentFiles: Boolean
        get() = settings.getBoolean("dev_recent_files", true)
        set(value) = settings.edit().putBoolean("dev_recent_files", value).apply()

    // === 外部文件导入目录 ===
    var replayImportDir: String
        get() = settings.getString("replay_import_dir", defaultReplayImportDir()) ?: defaultReplayImportDir()
        set(value) = settings.edit().putString("replay_import_dir", value).apply()

    var rwmodImportDir: String
        get() = settings.getString("rwmod_import_dir", defaultRwmodImportDir()) ?: defaultRwmodImportDir()
        set(value) = settings.edit().putString("rwmod_import_dir", value).apply()

    fun defaultReplayImportDir(): String {
        val es = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            java.io.File(es, "rustedWarfare/replays"),
            java.io.File(es, "Android/data/com.corrodinggames.rts/files/rustedWarfare/replays"),
            java.io.File(es, "games/com.corrodinggames.rts/replays")
        ).firstOrNull { it.exists() }?.absolutePath
            ?: java.io.File(es, "rustedWarfare/replays").absolutePath
    }

    fun defaultRwmodImportDir(): String {
        val es = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            java.io.File(es, "rustedWarfare/units"),
            java.io.File(es, "Android/data/com.corrodinggames.rts/files/rustedWarfare/units"),
            java.io.File(es, "games/com.corrodinggames.rts/units")
        ).firstOrNull { it.exists() }?.absolutePath
            ?: java.io.File(es, "rustedWarfare/units").absolutePath
    }

    var mapImportDir: String
        get() = settings.getString("map_import_dir", defaultMapImportDir()) ?: defaultMapImportDir()
        set(value) = settings.edit().putString("map_import_dir", value).apply()

    fun defaultMapImportDir(): String {
        val es = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            java.io.File(es, "rustedWarfare/maps"),
            java.io.File(es, "Android/data/com.corrodinggames.rts/files/rustedWarfare/maps"),
            java.io.File(es, "games/com.corrodinggames.rts/maps")
        ).firstOrNull { it.exists() }?.absolutePath
            ?: java.io.File(es, "rustedWarfare/maps").absolutePath
    }

    // === 近期修改 ===
    var recentHistoryLimit: Int
        get() {
            val raw = settings.getInt("recent_history_limit", 100)
            return raw.coerceIn(50, 200)
        }
        set(value) = settings.edit().putInt("recent_history_limit", value.coerceIn(50, 200)).apply()

    // === 最近对话框缓存 ===
    var lastRecentDialogTab: Boolean
        get() = settings.getBoolean("last_recent_dialog_tab", false)
        set(value) = settings.edit().putBoolean("last_recent_dialog_tab", value).apply()

    // === 首页置顶项目 ===
    var pinnedHomeItems: Set<String>
        get() = (settings.getString("pinned_home_items", "") ?: "").split(";").filter { it.isNotEmpty() }.toSet()
        set(value) = settings.edit().putString("pinned_home_items", value.take(50).joinToString(";")).apply()

    fun togglePinnedHomeItem(path: String): Boolean {
        val current = pinnedHomeItems.toMutableSet()
        val added = if (path in current) {
            current.remove(path)
            false
        } else {
            current.add(path)
            true
        }
        pinnedHomeItems = current
        return added
    }

    // === 首次启动引导 ===
    var onboardingVerified: Boolean
        get() = readVerifyCode(VERIFY_ONBOARDING) == ONBOARDING_VERIFY_CODE
        set(value) {
            if (value) {
                writeVerifyCode(VERIFY_ONBOARDING, ONBOARDING_VERIFY_CODE)
            } else {
                writeVerifyCode(VERIFY_ONBOARDING, "")
            }
        }

}
