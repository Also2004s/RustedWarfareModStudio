package com.rwmodstudio.core

import android.os.Environment
import java.io.File

/**
 * 统一管理 RWmod 目录下所有本地生成文件的路径。
 *
 * 规则：所有运行时生成的文件都必须位于 [rwmodDir] 下的细分文件夹中，
 * 不得再写入 filesDir、cacheDir 或 RWmod 根目录。
 */
object RwmodPaths {

    /** 外部存储根目录下的 RWmod 工作目录 */
    val rwmodDir: File
        get() = File(Environment.getExternalStorageDirectory(), "RWmod").also { if (!it.exists()) it.mkdirs() }

    // ==================== 分类目录 ====================

    val translationDir: File
        get() = File(rwmodDir, "translation").also { it.mkdirs() }

    val completionsDir: File
        get() = File(rwmodDir, "completions").also { it.mkdirs() }

    val configDir: File
        get() = File(rwmodDir, "config").also { it.mkdirs() }

    val cacheDir: File
        get() = File(rwmodDir, "cache").also { it.mkdirs() }

    val dedupDir: File
        get() = File(rwmodDir, "dedup").also { it.mkdirs() }

    val todosDir: File
        get() = File(rwmodDir, "todos").also { it.mkdirs() }

    val importsDir: File
        get() = File(rwmodDir, "imports").also { it.mkdirs() }

    val exportsDir: File
        get() = File(rwmodDir, "exports").also { it.mkdirs() }

    // ==================== 更新下载 ====================

    val updateDir: File
        get() = File(cacheDir, "update").also { it.mkdirs() }

    // ==================== 翻译库 ====================

    val userTranslationFile: File get() = File(translationDir, "user_translation.json")
    val legacyTranslationFile: File get() = File(translationDir, "translation.txt")
    val nativeTranslationFile: File get() = File(translationDir, "native_translation.txt")
    val extraTranslationFile: File get() = File(translationDir, "extra_translation.txt")
    val translationCacheFile: File get() = File(translationDir, "translation_cache.txt")
    val translationCacheMetaFile: File get() = File(translationDir, "translation_cache_meta.json")
    val translationCacheSourcesFile: File get() = File(translationDir, "translation_cache_sources.json")

    // ==================== 补全表 ====================

    val extraCompletionsFile: File get() = File(completionsDir, "extra_completions.json")
    val extraCompletionsEnFile: File get() = File(completionsDir, "extra_completions_en.json")
    val nativeCompletionsFile: File get() = File(completionsDir, "native_completions.json")
    val nativeCompletionsEnFile: File get() = File(completionsDir, "native_completions_en.json")
    val userCompletionsFile: File get() = File(completionsDir, "user_completions.json")
    val userCompletionsEnFile: File get() = File(completionsDir, "user_completions_en.json")
    val customCompletionsFile: File get() = File(completionsDir, "custom_completions.json")

    // ==================== 配置与状态 ====================

    val settingsFile: File get() = File(configDir, "settings.json")
    val verifyFile: File get() = File(configDir, "verify.json")
    val translationBlocklistFile: File get() = File(configDir, "translation_blocklist.json")
    val sectionFiltersFile: File get() = File(configDir, "section_filters.json")
    val verifiedBehaviorsFile: File get() = File(configDir, "verified_behaviors.json")
    val projectRegistryFile: File get() = File(configDir, "project_registry.txt")
    val saveHistoryFile: File get() = File(configDir, "save_history.json")

    // ==================== 缓存 ====================

    val codeReferenceFile: File get() = File(cacheDir, "code_reference.json")
    val importConfigTempFile: File get() = File(cacheDir, "import_config_temp.zip")
    fun importConfigTempFile(timestamp: Long = System.currentTimeMillis()): File = File(cacheDir, "import_config_${timestamp}.zip")
    val localConfigImportDir: File get() = File(cacheDir, "local_config_import").also { it.mkdirs() }

    /** 文件翻译缓存目录：存放每个 INI/template 文件的中文翻译副本，供搜索/编辑时免翻译 */
    val fileTranslationCacheDir: File
        get() = File(cacheDir, "file_translation").also { it.mkdirs() }
    val fileTranslationDataDir: File
        get() = File(fileTranslationCacheDir, "data").also { it.mkdirs() }
    val fileTranslationIndexFile: File
        get() = File(fileTranslationCacheDir, "index.json")

    /** 继承链缓存目录 */
    val inheritanceCacheDir: File
        get() = File(cacheDir, "inheritance").also { it.mkdirs() }
    val inheritanceIndexFile: File
        get() = File(inheritanceCacheDir, "index.json")

    /** 旧版搜索翻译缓存目录（已废弃，启动时自动清理） */
    val legacySearchTranslationDir: File
        get() = File(cacheDir, "search_translation")

    // ==================== 查重 ====================

    val dedupWordsFile: File get() = File(dedupDir, "dedup_words.txt")

    // ==================== 导出 ====================

    val localConfigExportFile: File get() = File(exportsDir, "config_export.zip")

    // ==================== 迁移兜底目录 ====================

    /** 无法自动分类的旧文件迁移到这里 */
    val migratedDir: File
        get() = File(rwmodDir, "migrated").also { it.mkdirs() }

    // ==================== 导入子目录 ====================

    val importsIniDir: File get() = File(importsDir, "ini").also { it.mkdirs() }

    // 兼容旧路径：应用启动时会一次性迁移旧文件到新路径
    val legacyRwmodRoot: File get() = rwmodDir
    val legacyAndroidRwmodRoot: File get() = File(Environment.getExternalStorageDirectory(), "Android/RWmod")
    val legacyDedupDir: File get() = File(rwmodDir, "查重")
    val legacyTodosDir: File get() = File(rwmodDir, "project_todos")
}
