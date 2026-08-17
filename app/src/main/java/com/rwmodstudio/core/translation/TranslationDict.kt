package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.VerifyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest

private const val TAG = "TranslationDict"

/**
 * 翻译词典 - 基于翻译库.txt
 *
 * 翻译库由三个来源合并而成：
 * 1. 用户表：外部存储 RWmod/user_translation.json（用户可编辑，旧版兼容 RWmod/translation.txt）
 * 2. 原生表：assets/data/translation.txt（应用自带默认翻译库）
 * 3. 附件表：assets/data/extra_completions.json + extra_translation_supplement.txt（从附件补全表提取的翻译键值）
 *
 * 合并优先级：用户表 > 附件表 > 原生表
 * 合并结果会缓存到 RWmod/translation_cache.txt，仅在源文件变化时重新合并。
 */
class TranslationDict {

    /** 翻译条目来源 */
    enum class TranslationSource {
        NATIVE, // assets/data/translation.txt
        EXTRA,  // assets/data/extra_completions.json / extra_translation_supplement.txt
        USER    // 外部存储 RWmod/user_translation.json（旧版兼容 RWmod/translation.txt）
    }

    /** 带来源的翻译条目 */
    data class TranslationEntry(
        val source: TranslationSource,
        val key: String,
        val value: String
    )

    /** 英文key → 中文key */
    private val enToZh = mutableMapOf<String, String>()

    /** 中文key → 英文key */
    private val zhToEn = mutableMapOf<String, String>()

    /** 英文section → 中文section */
    private val sectionEnToZh = mutableMapOf<String, String>()

    /** 中文section → 英文section */
    private val sectionZhToEn = mutableMapOf<String, String>()

    /** 特殊值翻译 */
    private val valueTranslations = mutableMapOf<String, String>()

    /** 每个条目的来源（key 格式与缓存文件一致：普通 key 或 [section]） */
    private val entrySources = mutableMapOf<String, TranslationSource>()

    // 预编译统一正则（加载后初始化，避免每次调用 translateInText 时重复编译）
    private var enToZhPattern: Regex? = null
    private var zhToEnPattern: Regex? = null
    private var enToZhLookup: Map<String, String> = emptyMap()
    private var zhToEnLookup: Map<String, String> = emptyMap()

    private val specialValues = setOf(
        "true", "false", "TRUE", "FALSE", "True", "False",
        "LAND", "WATER", "HOVER", "AIR", "OVER_CLIFF", "OVER_CLIFF_WATER",
        "AUTO", "NONE"
    )

    /** 逻辑布尔值前缀 */
    private val logicBooleanPrefixes = setOf(
        "thisActionTarget", "eventSource", "attachment", "transporting",
        "attacking", "lastDamagedBy", "parent", "activeWaypointTarget",
        "customTarget1", "customTarget2", "nearestUnit", "globalSearchForFirstUnit",
        "nullUnit", "getAsMarker", "getOffsetAbsolute", "getOffsetRelative",
        "distance", "distanceSquared", "distanceBetween", "directionBetween",
        "game.", "int(", "select(", "debug(", "str(", "substring(", "length(",
        "squareRoot(", "min(", "max(", "createMarker(", "eventData(", "sin(", "cos(",
        "rnd(", "lowercase(", "uppercase(", "direction",
        "numberOfUnitsInNeutralTeam", "numberOfUnitsInAggressiveTeam", "numberOfUnitsInAllTeams",
        "numberOfUnitsInTeam", "numberOfUnitsInEnemyTeam", "numberOfUnitsInAllyTeam",
        "readUnitMemory",
        "self.ammo", "self.builtAmount", "self.completed", "self.customTimer",
        "self.dir", "self.energy", "self.globalTeamTags", "self.hasFlag",
        "self.hasParent", "self.hasResources", "self.hasTakenDamage", "self.hasUnitInTeam",
        "self.height", "self.hp", "self.maxhp", "self.isAmmoEmpty", "self.isAtGroundHeight",
        "self.isAtTopSpeed", "self.isAttacking", "self.isControlledByAI", "self.isEnergyEmpty",
        "self.isEnergyFull", "self.isFlying", "self.isInWater", "self.isMoving",
        "self.isOnNeutralTeam", "self.isOverClift", "self.isOverLiquid", "self.isOverOpenLand",
        "self.isOverPassableTile", "self.isOverwater", "self.isResourceLargerThan", "self.isUnderwater",
        "self.kills", "self.lastConverted", "self.maxMoveSpeed", "self.noUnitInTeam",
        "self.numberOfAttachedUnits", "self.numberOfUnitsInAllyNotOwnTeam", "self.priceCredits",
        "self.queueSize", "self.resource", "self.shield", "self.speed", "self.tags",
        "self.teamDefeatedTech", "self.teamId", "self.teamVictory", "self.teamWipedOut",
        "self.timeAlive", "self.transportingCount", "self.transportingUnitWithTags",
        "self.numberOfUnitsInTeam", "self.numberOfUnitsInEnemyTeam", "self.numberOfUnitsInAllyTeam",
        "self.id", "self.teamName", "self.playerName", "self.x", "self.y", "self.z",
        "self.numberOfQueuedWaypoints", "self.maxHp", "self.maxEnergy", "self.isEnergyRecharging",
        "self.maxShield", "self.isInMap", "self.isReversing",
        "self.ammoIncludingQueued", "self.energyIncludingQueued", "self.resource.",
        "memory.",
    )

    private data class TranslationTable(
        val enToZh: MutableMap<String, String> = mutableMapOf(),
        val sectionEnToZh: MutableMap<String, String> = mutableMapOf(),
        val valueTranslations: MutableMap<String, String> = mutableMapOf()
    )

    val isLoaded: Boolean get() = enToZh.isNotEmpty() || sectionEnToZh.isNotEmpty()

    /** 外部存储用户翻译表路径（JSON 格式，参考自定义补全用户表） */
    fun getUserTranslationPath(context: Context): java.io.File = RwmodPaths.userTranslationFile

    /** 旧版外部存储翻译库文件路径（兼容用） */
    private fun getLegacyExternalLibPath(context: Context): java.io.File = RwmodPaths.legacyTranslationFile

    /** 外部存储附件补全表路径（自定义补全附件表） */
    private fun getExternalExtraCompletionsPath(context: Context): java.io.File = RwmodPaths.extraCompletionsFile

    /** 缓存合并后的翻译库文件路径 */
    private fun getCacheFile(context: Context): java.io.File = RwmodPaths.translationCacheFile

    /** 缓存元数据文件路径 */
    private fun getCacheMetaFile(context: Context): java.io.File = RwmodPaths.translationCacheMetaFile

    /** 缓存来源映射文件路径 */
    private fun getCacheSourcesFile(context: Context): java.io.File = RwmodPaths.translationCacheSourcesFile

    /** 清空所有翻译数据 */
    fun clearAll() {
        enToZh.clear()
        zhToEn.clear()
        sectionEnToZh.clear()
        sectionZhToEn.clear()
        valueTranslations.clear()
        entrySources.clear()
    }



    /** 清除缓存文件 */
    private fun clearCache(context: Context) {
        try {
            getCacheFile(context).delete()
            getCacheMetaFile(context).delete()
            getCacheSourcesFile(context).delete()
        } catch (e: Exception) {
            Log.e(TAG, "clearCache failed", e)
        }
    }

    /** 重置翻译库为 assets 默认版本（清空用户表、本地原生/附件表，从 assets 重新生成） */
    suspend fun resetToDefault(context: Context) = withContext(Dispatchers.IO) {
        try {
            getUserTranslationPath(context).delete()
            getLegacyExternalLibPath(context).delete()
            getExternalExtraCompletionsPath(context).delete()
            RwmodPaths.nativeTranslationFile.delete()
            RwmodPaths.extraTranslationFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "resetToDefault failed to delete external files", e)
        }
        clearCache(context)
        clearAll()
        loadFromAssets(context)
    }

    /** 保存用户翻译表到外部存储（JSON 格式） */
    fun saveToExternal(context: Context, entries: List<Pair<String, String>>) {
        val userFile = getUserTranslationPath(context)
        userFile.parentFile?.mkdirs()
        val jsonArray = JSONArray()
        for ((en, zh) in entries) {
            jsonArray.put(JSONObject().apply {
                put("en", en)
                put("zh", zh)
            })
        }
        userFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        // 清除缓存，下次冷启动时重新合并
        clearCache(context)
        // 增量更新内存中的用户条目，避免全量重载
        updateUserEntriesInMemory(context, entries)
    }

    /**
     * 修改指定来源的单个条目（NATIVE/EXTRA 直接写本地文件，USER 写 user_translation.json）。
     * 同时更新内存与来源映射，并清除合并缓存。
     */
    suspend fun updateEntry(context: Context, source: TranslationSource, key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val ok = when (source) {
            TranslationSource.USER -> updateUserEntry(context, key, value)
            TranslationSource.NATIVE -> updateNativeExtraEntry(RwmodPaths.nativeTranslationFile, key, value)
            TranslationSource.EXTRA -> updateNativeExtraEntry(RwmodPaths.extraTranslationFile, key, value)
        }
        if (ok) {
            clearCache(context)
            updateMemoryEntry(source, key, value)
        }
        ok
    }

    /**
     * 删除指定来源的单个条目。
     * 同时更新内存与来源映射，并清除合并缓存。
     */
    suspend fun deleteEntry(context: Context, source: TranslationSource, key: String): Boolean = withContext(Dispatchers.IO) {
        val ok = when (source) {
            TranslationSource.USER -> deleteUserEntry(context, key)
            TranslationSource.NATIVE -> deleteNativeExtraEntry(RwmodPaths.nativeTranslationFile, key)
            TranslationSource.EXTRA -> deleteNativeExtraEntry(RwmodPaths.extraTranslationFile, key)
        }
        if (ok) {
            clearCache(context)
            removeMemoryEntry(key)
        }
        ok
    }

    /**
     * 根据新的用户条目列表增量更新内存字典。
     * 只增删改用户表（USER）相关条目，原生/附件表保持不变。
     * 若检测到用户删除了条目，则回退到全量重载以保证原生/附件映射能恢复。
     */
    private fun updateUserEntriesInMemory(context: Context, entries: List<Pair<String, String>>) {
        // 旧用户条目 key 集合（section 的 key 格式为 [enSection]）
        val oldUserKeys = entrySources
            .filter { it.value == TranslationSource.USER }
            .keys
            .toSet()

        // 新用户条目 key 集合，同时把列表转成内部格式
        val newUserKeys = mutableSetOf<String>()
        val normalized = entries.mapNotNull { (rawEn, rawZh) ->
            val en = rawEn.trim()
            val zh = rawZh.trim()
            if (en.isEmpty() || zh.isEmpty() || en == zh) return@mapNotNull null

            if (en.startsWith("[") && zh.startsWith("[")) {
                val enSec = en.removeSurrounding("[", "]").trim()
                val zhSec = zh.removeSurrounding("[", "]").trim()
                if (enSec.isEmpty() || zhSec.isEmpty()) return@mapNotNull null
                val key = "[$enSec]"
                newUserKeys.add(key)
                key to (enSec to zhSec)
            } else {
                if (en.length <= 1) return@mapNotNull null
                newUserKeys.add(en)
                en to zh
            }
        }

        // 如果有用户条目被删除，内存中无法简单恢复原生/附件映射，回退到全量重载
        val deletedKeys = oldUserKeys - newUserKeys
        if (deletedKeys.isNotEmpty()) {
            clearAll()
            loadMerged(context)
            return
        }

        // 移除已删除的旧用户条目
        for (key in deletedKeys) {
            when {
                key.startsWith("[") && key.endsWith("]") -> {
                    val enSec = key.removeSurrounding("[", "]")
                    val zhSec = sectionEnToZh[enSec]
                    sectionEnToZh.remove(enSec)
                    if (zhSec != null) sectionZhToEn.remove(zhSec)
                    entrySources.remove(key)
                }
                else -> {
                    val zh = enToZh[key]
                    enToZh.remove(key)
                    if (zh != null) zhToEn.remove(zh)
                    valueTranslations.remove(key)
                    entrySources.remove(key)
                }
            }
        }

        // 添加/更新新用户条目
        for ((key, value) in normalized) {
            if (key.startsWith("[") && value is Pair<*, *>) {
                val pair = value as Pair<String, String>
                val enSec = pair.first
                val zhSec = pair.second
                sectionEnToZh[enSec] = zhSec
                sectionZhToEn[zhSec] = enSec
                entrySources[key] = TranslationSource.USER
            } else if (value is String) {
                if (key in specialValues) {
                    valueTranslations[key] = value
                    valueTranslations[value] = key
                } else {
                    enToZh[key] = value
                    zhToEn[value] = key
                }
                entrySources[key] = TranslationSource.USER
            }
        }

        compilePatterns()
    }

    /** 修改本地原生/附件表（key=value 或 [en]=[zh] 格式） */
    private fun updateNativeExtraEntry(file: java.io.File, key: String, value: String): Boolean {
        try {
            file.parentFile?.mkdirs()
            val lines = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()
            val isSection = key.startsWith("[") && value.startsWith("[")
            val newLine = if (isSection) {
                val enSec = key.removeSurrounding("[", "]")
                val zhSec = value.removeSurrounding("[", "]")
                "[$enSec]=[$zhSec]"
            } else {
                "$key=$value"
            }
            val enPart = if (isSection) key.removeSurrounding("[", "]") else key
            val pattern = if (isSection) {
                Regex("""^\s*\[${Regex.escape(enPart)}\]\s*=\s*\[.*?\]\s*$""")
            } else {
                Regex("""^\s*${Regex.escape(enPart)}\s*=.*$""")
            }
            var replaced = false
            val sb = StringBuilder()
            for (line in lines) {
                if (pattern.matches(line)) {
                    sb.appendLine(newLine)
                    replaced = true
                } else {
                    sb.appendLine(line)
                }
            }
            if (!replaced) sb.appendLine(newLine)
            file.writeText(sb.toString().trimEnd().plus("\n"), Charsets.UTF_8)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "updateNativeExtraEntry failed: $key=$value", e)
            return false
        }
    }

    /** 删除本地原生/附件表中的指定条目 */
    private fun deleteNativeExtraEntry(file: java.io.File, key: String): Boolean {
        try {
            if (!file.exists()) return false
            val lines = file.readLines(Charsets.UTF_8)
            val isSection = key.startsWith("[") && key.endsWith("]")
            val enPart = if (isSection) key.removeSurrounding("[", "]") else key
            val pattern = if (isSection) {
                Regex("""^\s*\[${Regex.escape(enPart)}\]\s*=\s*\[.*?\]\s*$""")
            } else {
                Regex("""^\s*${Regex.escape(enPart)}\s*=.*$""")
            }
            val remaining = lines.filterNot { pattern.matches(it) }
            if (remaining.size == lines.size) return false
            file.writeText(remaining.joinToString("\n").trimEnd().plus("\n"), Charsets.UTF_8)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "deleteNativeExtraEntry failed: $key", e)
            return false
        }
    }

    /** 读取用户表 JSON 为列表 */
    private fun readUserEntries(context: Context): MutableList<Pair<String, String>> {
        val file = getUserTranslationPath(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(obj.optString("en", "") to obj.optString("zh", ""))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "readUserEntries failed", e)
            mutableListOf()
        }
    }

    /** 写入用户表 JSON 列表 */
    private fun writeUserEntries(context: Context, entries: List<Pair<String, String>>) {
        val file = getUserTranslationPath(context)
        file.parentFile?.mkdirs()
        val array = JSONArray()
        for ((en, zh) in entries) {
            if (en.isBlank()) continue
            array.put(JSONObject().apply {
                put("en", en)
                put("zh", zh)
            })
        }
        file.writeText(array.toString(2), Charsets.UTF_8)
    }

    /** 修改用户表中的单个条目 */
    private fun updateUserEntry(context: Context, key: String, value: String): Boolean {
        return try {
            val entries = readUserEntries(context)
            val idx = entries.indexOfFirst { it.first == key }
            if (idx >= 0) entries[idx] = key to value else entries.add(key to value)
            writeUserEntries(context, entries)
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateUserEntry failed", e)
            false
        }
    }

    /** 删除用户表中的单个条目 */
    private fun deleteUserEntry(context: Context, key: String): Boolean {
        return try {
            val entries = readUserEntries(context)
            val originalSize = entries.size
            entries.removeAll { it.first == key }
            if (entries.size == originalSize) return false
            writeUserEntries(context, entries)
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteUserEntry failed", e)
            false
        }
    }

    /** 更新内存中的单个条目（含来源映射） */
    private fun updateMemoryEntry(source: TranslationSource, key: String, value: String) {
        if (key.startsWith("[") && value.startsWith("[")) {
            val enSec = key.removeSurrounding("[", "]")
            val zhSec = value.removeSurrounding("[", "]")
            val oldZhSec = sectionEnToZh[enSec]
            if (oldZhSec != null) sectionZhToEn.remove(oldZhSec)
            sectionEnToZh[enSec] = zhSec
            sectionZhToEn[zhSec] = enSec
            entrySources["[$enSec]"] = source
        } else {
            val oldZh = enToZh[key]
            if (oldZh != null) zhToEn.remove(oldZh)
            enToZh[key] = value
            zhToEn[value] = key
            if (key in specialValues) {
                val oldValue = valueTranslations[key]
                if (oldValue != null) valueTranslations.remove(oldValue)
                valueTranslations[key] = value
                valueTranslations[value] = key
            }
            entrySources[key] = source
        }
        compilePatterns()
    }

    /** 从内存中移除单个条目（含来源映射） */
    private fun removeMemoryEntry(key: String) {
        if (key.startsWith("[") && key.endsWith("]")) {
            val enSec = key.removeSurrounding("[", "]")
            val zhSec = sectionEnToZh.remove(enSec)
            if (zhSec != null) sectionZhToEn.remove(zhSec)
            entrySources.remove(key)
        } else {
            val zh = enToZh.remove(key)
            if (zh != null) zhToEn.remove(zh)
            if (key in specialValues) {
                val oldValue = valueTranslations[key]
                valueTranslations.remove(key)
                if (oldValue != null) valueTranslations.remove(oldValue)
            }
            entrySources.remove(key)
        }
        compilePatterns()
    }

    suspend fun loadFromAssets(context: Context) = withContext(Dispatchers.IO) {
        try {
            loadMerged(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load merged dictionary", e)
        }
    }

    /** 加载合并后的翻译库（带缓存） */
    private fun loadMerged(context: Context) {
        val cacheFile = getCacheFile(context)
        val metaFile = getCacheMetaFile(context)
        val sourcesFile = getCacheSourcesFile(context)

        if (isCacheValid(context, metaFile)) {
            // 缓存有效，直接加载缓存和来源映射
            loadFromFile(cacheFile)
            loadSources(sourcesFile)
            return
        }

        // 重新合并三个表：用户表 > 原生表 > 附件表
        val nativeTable = loadNativeTable(context)
        val extraTable = loadExtraTable(context)
        val userTable = loadUserTable(context)
        val merged = mergeTables(nativeTable, extraTable, userTable)

        // 写入缓存
        writeCache(merged, cacheFile, metaFile, sourcesFile, context)

        // 从缓存加载（复用现有解析逻辑，同时构建反向查找表）
        loadFromFile(cacheFile)
        loadSources(sourcesFile)
    }

    /** 检查缓存是否有效 */
    private fun isCacheValid(context: Context, metaFile: java.io.File): Boolean {
        try {
            val cacheFile = getCacheFile(context)
            val sourcesFile = getCacheSourcesFile(context)
            if (!cacheFile.exists() || !metaFile.exists() || !sourcesFile.exists()) return false

            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val nativeHash = meta.optString("nativeHash", "")
            val extraHash = meta.optString("extraHash", "")
            val assetExtraHash = meta.optString("assetExtraHash", "")
            val userHash = meta.optString("userHash", "")
            val supplementHash = meta.optString("supplementHash", "")
            val version = meta.optInt("version", 0)

            if (version != CACHE_VERSION) return false

            // 内置附件表（assets）与外存副本都纳入校验：
            // 应用升级改变内置表、或外存副本被刷新时，缓存都必须重建
            return nativeHash == hashAsset(context, "data/translation.txt") &&
                    extraHash == hashFile(getExternalExtraCompletionsPath(context)) &&
                    assetExtraHash == hashAsset(context, "data/extra_completions.json") &&
                    userHash == hashFile(getUserTranslationPath(context)) &&
                    supplementHash == hashAsset(context, "data/extra_translation_supplement.txt")
        } catch (_: Exception) {
            return false
        }
    }

    /** 写入缓存、元数据和来源映射 */
    private fun writeCache(
        merged: TranslationTable,
        cacheFile: java.io.File,
        metaFile: java.io.File,
        sourcesFile: java.io.File,
        context: Context
    ) {
        cacheFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        sb.appendLine("# 铁锈战争 MOD 翻译库（合并缓存）")
        sb.appendLine("# 来源：用户表 > 附件表 > 原生表")

        // Section 翻译
        for ((en, zh) in merged.sectionEnToZh.entries.sortedBy { it.key }) {
            sb.appendLine("[$en] = [$zh]")
        }

        // Key 翻译
        for ((en, zh) in merged.enToZh.entries.sortedBy { it.key }) {
            sb.appendLine("$en=$zh")
        }

        // Value 翻译
        val valueSeen = mutableSetOf<String>()
        for ((en, zh) in merged.valueTranslations.entries.sortedBy { it.key }) {
            // valueTranslations 是双向存储的，只输出英文 key 到中文 value
            if (!isChinese(en) && en !in valueSeen) {
                sb.appendLine("$en=$zh")
                valueSeen.add(en)
            }
        }

        cacheFile.writeText(sb.toString(), Charsets.UTF_8)

        val meta = JSONObject().apply {
            put("version", CACHE_VERSION)
            put("nativeHash", hashAsset(context, "data/translation.txt"))
            put("extraHash", hashFile(getExternalExtraCompletionsPath(context)))
            put("assetExtraHash", hashAsset(context, "data/extra_completions.json"))
            put("userHash", hashFile(getUserTranslationPath(context)))
            put("supplementHash", hashAsset(context, "data/extra_translation_supplement.txt"))
            put("generatedAt", System.currentTimeMillis())
        }
        metaFile.writeText(meta.toString(2), Charsets.UTF_8)

        // 写入来源映射
        val sources = JSONObject()
        for ((key, source) in entrySources.entries.sortedBy { it.key }) {
            sources.put(key, source.name)
        }
        sourcesFile.writeText(sources.toString(2), Charsets.UTF_8)
    }

    /** 从来源映射文件加载来源 */
    private fun loadSources(sourcesFile: java.io.File) {
        entrySources.clear()
        try {
            if (!sourcesFile.exists()) return
            val json = JSONObject(sourcesFile.readText(Charsets.UTF_8))
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val sourceName = json.optString(key, TranslationSource.NATIVE.name)
                val source = try {
                    TranslationSource.valueOf(sourceName)
                } catch (_: Exception) {
                    TranslationSource.NATIVE
                }
                entrySources[key] = source
            }
        } catch (_: Exception) {
            entrySources.clear()
        }
    }

    /** 确保本地原生表文件存在，验证码不匹配时从 assets 重新生成 */
    private fun ensureNativeTranslationFile(context: Context) {
        val file = RwmodPaths.nativeTranslationFile
        val code = VerifyManager.read(VerifyManager.NATIVE_TRANSLATION)
        if (file.exists() && code == VerifyManager.NATIVE_TRANSLATION_CODE) return
        try {
            file.parentFile?.mkdirs()
            context.assets.open("data/translation.txt").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            VerifyManager.write(VerifyManager.NATIVE_TRANSLATION, VerifyManager.NATIVE_TRANSLATION_CODE)
            Log.d(TAG, "Generated native translation file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure native translation file", e)
        }
    }

    /** 加载原生表（读取本地 native_translation.txt） */
    private fun loadNativeTable(context: Context): TranslationTable {
        ensureNativeTranslationFile(context)
        return try {
            parseTranslationText(RwmodPaths.nativeTranslationFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native translation table", e)
            TranslationTable()
        }
    }

    /** 加载用户表（外部存储 user_translation.json，旧版兼容 translation.txt） */
    private fun loadUserTable(context: Context): TranslationTable {
        val userFile = getUserTranslationPath(context)
        if (userFile.exists() && userFile.length() > 0) {
            return try {
                parseUserTranslationJson(userFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user translation table", e)
                TranslationTable()
            }
        }
        // 旧版兼容：若不存在 user_translation.json，则读取 RWmod/translation.txt
        val legacyFile = getLegacyExternalLibPath(context)
        return try {
            parseTranslationText(legacyFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            TranslationTable()
        }
    }

    /** 解析用户翻译表 JSON（{en, zh} 数组） */
    private fun parseUserTranslationJson(content: String): TranslationTable {
        val table = TranslationTable()
        try {
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val en = obj.optString("en", "").trim()
                val zh = obj.optString("zh", "").trim()
                if (en.isEmpty() || zh.isEmpty()) continue
                if (en == zh) continue
                if (en.startsWith("[") && zh.startsWith("[")) {
                    // Section: [en] = [zh]
                    val enSec = en.removeSurrounding("[", "]").trim()
                    val zhSec = zh.removeSurrounding("[", "]").trim()
                    if (enSec.isNotEmpty() && zhSec.isNotEmpty()) {
                        table.sectionEnToZh[enSec] = zhSec
                    }
                } else {
                    if (en in specialValues) {
                        table.valueTranslations[en] = zh
                        table.valueTranslations[zh] = en
                    } else if (en.length > 1) {
                        table.enToZh[en] = zh
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse user translation JSON", e)
        }
        return table
    }

    /** 确保本地附件表文件存在，验证码不匹配时重新生成 */
    private fun ensureExtraTranslationFile(context: Context) {
        val file = RwmodPaths.extraTranslationFile
        val code = VerifyManager.read(VerifyManager.EXTRA_TRANSLATION)
        if (file.exists() && code == VerifyManager.EXTRA_TRANSLATION_CODE) return

        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.appendLine("# 附件翻译表（由 extra_completions.json 与 extra_translation_supplement.txt 生成）")
            sb.appendLine("# 重置翻译库时会重新生成此文件")

            // 1. 从附件补全表提取翻译键值（优先外部自定义，否则 assets）
            val externalFile = getExternalExtraCompletionsPath(context)
            val jsonText = if (externalFile.exists() && externalFile.length() > 0) {
                externalFile.readText(Charsets.UTF_8)
            } else {
                context.assets.open("data/extra_completions.json").use {
                    it.bufferedReader().readText()
                }
            }
            val array = JSONArray(jsonText)
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val nameEnRaw = obj.optString("nameEn", "").trim()
                val name = obj.optString("name", "").trim()
                val nameEn = nameEnRaw.replace(TRAILING_PARENS_REGEX, "").trim()
                if (nameEn.isEmpty() || name.isEmpty()) continue
                if (nameEn == name) continue
                if (!isChinese(name)) continue
                if (nameEn.length <= 1) continue
                if (!seen.add(nameEn)) continue
                sb.appendLine("$nameEn=$name")
            }

            // 2. 追加补充表
            try {
                context.assets.open("data/extra_translation_supplement.txt").use {
                    for (line in it.bufferedReader().readText().lines()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        if (trimmed.contains("=")) {
                            val parts = trimmed.split("=", limit = 2)
                            val en = parts[0].trim()
                            val zh = parts[1].trim()
                            if (en.isNotEmpty() && zh.isNotEmpty() && seen.add(en)) {
                                sb.appendLine("$en=$zh")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load translation supplement", e)
            }

            file.writeText(sb.toString(), Charsets.UTF_8)
            VerifyManager.write(VerifyManager.EXTRA_TRANSLATION, VerifyManager.EXTRA_TRANSLATION_CODE)
            Log.d(TAG, "Generated extra translation file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure extra translation file", e)
        }
    }

    /** 加载附件表（读取本地 extra_translation.txt） */
    private fun loadExtraTable(context: Context): TranslationTable {
        ensureExtraTranslationFile(context)
        return try {
            parseTranslationText(RwmodPaths.extraTranslationFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load extra translation table", e)
            TranslationTable()
        }
    }

    /** 解析 translation.txt 格式文本 */
    private fun parseTranslationText(content: String): TranslationTable {
        val table = TranslationTable()
        val sectionPattern = Regex("""^\[(.+?)\]\s*=\s*\[(.+?)\]$$""")

        for (line in content.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val sectionMatch = sectionPattern.matchEntire(trimmed)
            if (sectionMatch != null) {
                val eng = sectionMatch.groupValues[1].trim()
                val chn = sectionMatch.groupValues[2].trim()
                if (eng.isNotEmpty() && chn.isNotEmpty()) {
                    table.sectionEnToZh[eng] = chn
                }
                continue
            }

            if (trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                val eng = parts[0].trim()
                val chn = parts[1].trim()
                if (eng.isEmpty() || chn.isEmpty()) continue

                if (eng in specialValues) {
                    table.valueTranslations[eng] = chn
                    table.valueTranslations[chn] = eng
                } else {
                    table.enToZh[eng] = chn
                }
            }
        }

        return table
    }

    /**
     * 按优先级合并三个表：用户 > 附件 > 原生。
     * 附件表是人工维护的补丁层：它定义过的键，值与来源标记都以附件表为准，
     * 否则这些键会被原生表覆盖并错误归入「原生」分类（附件分类将无条目可显示）。
     */
    private fun mergeTables(
        native: TranslationTable,
        extra: TranslationTable,
        user: TranslationTable
    ): TranslationTable {
        val merged = TranslationTable()
        entrySources.clear()

        fun putAllWithSource(
            targetMap: MutableMap<String, String>,
            sourceMap: Map<String, String>,
            source: TranslationSource,
            keyPrefix: String = ""
        ) {
            for ((key, value) in sourceMap) {
                targetMap[key] = value
                entrySources["$keyPrefix$key"] = source
            }
        }

        // 1) 原生表（最低优先级）
        putAllWithSource(merged.enToZh, native.enToZh, TranslationSource.NATIVE)
        putAllWithSource(merged.sectionEnToZh, native.sectionEnToZh, TranslationSource.NATIVE, "[")
        putAllWithSource(merged.valueTranslations, native.valueTranslations, TranslationSource.NATIVE)

        // 2) 附件表覆盖原生表
        putAllWithSource(merged.enToZh, extra.enToZh, TranslationSource.EXTRA)
        putAllWithSource(merged.sectionEnToZh, extra.sectionEnToZh, TranslationSource.EXTRA, "[")
        putAllWithSource(merged.valueTranslations, extra.valueTranslations, TranslationSource.EXTRA)

        // 3) 用户表最高优先级
        putAllWithSource(merged.enToZh, user.enToZh, TranslationSource.USER)
        putAllWithSource(merged.sectionEnToZh, user.sectionEnToZh, TranslationSource.USER, "[")
        putAllWithSource(merged.valueTranslations, user.valueTranslations, TranslationSource.USER)

        return merged
    }

    /** 计算 assets 文件 hash */
    private fun hashAsset(context: Context, assetPath: String): String {
        return try {
            context.assets.open(assetPath).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 计算文件 hash */
    private fun hashFile(file: java.io.File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    /** 从文件加载翻译库 */
    private fun loadFromFile(file: java.io.File) {
        try {
            val content = file.readText(Charsets.UTF_8)
            val sectionPattern = Regex("""^\[(.+?)\]\s*=\s*\[(.+?)\]$$""")

            clearAll()

            for (line in content.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val sectionMatch = sectionPattern.matchEntire(trimmed)
                if (sectionMatch != null) {
                    val eng = sectionMatch.groupValues[1].trim()
                    val chn = sectionMatch.groupValues[2].trim()
                    sectionEnToZh[eng] = chn
                    sectionZhToEn[chn] = eng
                    continue
                }

                if (trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    val eng = parts[0].trim()
                    val chn = parts[1].trim()

                    if (eng in specialValues) {
                        // 特殊值存入 valueTranslations
                        valueTranslations[eng] = chn
                        valueTranslations[chn] = eng
                    } else {
                        // 其他所有key都存入 enToZh（和Python对齐）
                        enToZh[eng] = chn
                        zhToEn[chn] = eng
                    }
                }
            }

            compilePatterns()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse translation text", e)
        }
    }

    /** 判断是否是逻辑布尔值 */
    private fun isLogicBooleanValue(eng: String): Boolean {
        for (prefix in logicBooleanPrefixes) {
            if (eng == prefix || eng.startsWith(prefix + ".") || eng.startsWith(prefix + "(")) {
                return true
            }
        }
        return false
    }

    /** 预编译统一正则表达式，只用 enToZh 和 zhToEn，不包含 valueTranslations */
    private fun compilePatterns() {
        // 英→中：只用 enToZh（和Python的 translate_in_text 对齐）
        val enSorted = enToZh.entries
            .filter { (k, v) -> k.length > 1 && v != k && k !in enToZh.values }
            .sortedByDescending { it.key.length }
        enToZhLookup = enSorted.associate { it.key to it.value }
        enToZhPattern = if (enSorted.isNotEmpty()) {
            val escaped = enSorted.joinToString("|") { Regex.escape(it.key) }
            Regex("\\b($escaped)\\b")
        } else null

        // 中→英：只用 zhToEn；所有中文（含单字）均参与反向翻译
        val zhSorted = zhToEn.entries
            .filter { (k, v) -> k.length > 0 && v != k && k !in zhToEn.values }
            .sortedByDescending { it.key.length }
        zhToEnLookup = zhSorted.associate { it.key to it.value }
        zhToEnPattern = if (zhSorted.isNotEmpty()) {
            val escaped = zhSorted.joinToString("|") { Regex.escape(it.key) }
            Regex("\\b($escaped)\\b")
        } else null
    }

    /** 英文key → 中文key（带回退逻辑，只查 enToZh） */
    fun getTranslation(enKey: String): String {
        // 直接查找 enToZh（和Python对齐，不查 valueTranslations）
        enToZh[enKey]?.let { return it }

        // 处理 self.XXX() 格式
        // 注意顺序：先查 self.stripped（含 self），失败才查裸 stripped。
        // 否则 self.energy() 会先被裸译成 energy→能量，而库里 self.energy=自身能量 被跳过。
        if (enKey.startsWith("self.")) {
            val stripped = enKey.removePrefix("self.").removeSuffix("()")
            val withSelf = "self.$stripped"
            if (withSelf != enKey) {
                enToZh[withSelf]?.let { return it }
            }
            enToZh[stripped]?.let { return it }
        }

        // 处理 XXX() 格式
        if (enKey.endsWith("()")) {
            val stripped = enKey.removeSuffix("()")
            enToZh[stripped]?.let { return it }
        }

        return enKey
    }

    /** 中文key → 英文key（只查 zhToEn，和Python对齐） */
    fun getTranslationBack(zhKey: String): String = zhToEn[zhKey] ?: zhKey

    /** 中文值 → 英文值（查 zhToEn + valueTranslations，用于value翻译）。
     *  支持 self.XXX()/XXX() 归一化：剥空括号与 self. 前缀后，先查 self.中文 再查裸 中文，
     *  直接返回翻译库样式（裸英文），不拼回 self./()；带参括号不剥离；未命中返回原值。 */
    fun getValueTranslationBack(zhValue: String): String = resolveValueTranslation(zhValue, englishToChinese = false)

    /** 英文值 → 中文值（查 valueTranslations + enToZh，用于值补全 label 翻译）。
     *  支持 self.XXX()/XXX() 归一化：剥空括号与 self. 前缀后，先查 self.XXX 再查裸 XXX，
     *  直接返回翻译库样式（裸中文），不拼回 self./()；带参括号不剥离；未命中返回原值。 */
    fun getValueTranslation(enValue: String): String = resolveValueTranslation(enValue, englishToChinese = true)

    /** 英文值查表：valueTranslations 优先，其次 enToZh（与 getValueTranslation 原语义一致） */
    private fun lookupEnValue(form: String): String? = valueTranslations[form] ?: enToZh[form]

    /** 中文值查表：zhToEn 优先，其次 valueTranslations（与 getValueTranslationBack 原语义一致） */
    private fun lookupZhValue(form: String): String? = zhToEn[form] ?: valueTranslations[form]

    /**
     * 值翻译归一化回退（仅 getValueTranslation/getValueTranslationBack 使用，不改 getTranslation/getTranslationBack 与翻译引擎）。
     * 1) 精确查表命中直接返回；
     * 2) 仅对以空括号 () 结尾的输入：剥掉 ()，若带 self. 前缀则先查 self.XXX（如 self.hasFlag）再查裸 XXX（如 numberOfUnitsInEnemyTeam），
     *    否则只查裸 XXX；命中直接返回库样式（裸名），不拼回 self./()；
     * 3) 带参括号（如 (withActionTag="#")）不剥离；未命中返回原值。
     */
    private fun resolveValueTranslation(value: String, englishToChinese: Boolean): String {
        val lookup: (String) -> String? = if (englishToChinese) ::lookupEnValue else ::lookupZhValue
        lookup(value)?.let { return it }
        if (!value.endsWith("()")) return value

        val stripped = value.removeSuffix("()")
        if (stripped.startsWith("self.")) {
            lookup(stripped)?.let { return it }
            lookup(stripped.removePrefix("self."))?.let { return it }
        } else {
            lookup(stripped)?.let { return it }
        }
        return value
    }

    /** 英文section → 中文section */
    fun getSectionTranslation(enSection: String): String = sectionEnToZh[enSection] ?: enSection

    /** 中文section → 英文section */
    fun getSectionTranslationBack(zhSection: String): String = sectionZhToEn[zhSection] ?: zhSection

    /**
     * 检查中文词是否在翻译库中（任何分类：Section/Key/Value）
     * 只查中文，英文不查
     */
    fun isChineseInLibrary(chinese: String): Boolean {
        // 先判断是否是中文，不是中文直接返回 false
        if (!isChinese(chinese)) return false
        return zhToEn.containsKey(chinese) || sectionZhToEn.containsKey(chinese) || valueTranslations.containsKey(chinese)
    }

    /**
     * 获取中文词对应的英文翻译
     */
    fun translateChineseToEnglish(chinese: String): String {
        if (!isChinese(chinese)) return chinese
        return zhToEn[chinese] ?: sectionZhToEn[chinese] ?: valueTranslations[chinese] ?: chinese
    }

    /** 获取所有英文key */
    fun getAllEnglishKeys(): Set<String> = enToZh.keys

    /** 获取所有中文key */
    fun getAllChineseKeys(): Set<String> = zhToEn.keys

    /** 获取所有英文section */
    fun getAllEnglishSections(): Set<String> = sectionEnToZh.keys

    /** 获取valueTranslations（用于查重） */
    fun getValueTranslations(): Map<String, String> = valueTranslations

    /** 获取所有条目（包含enToZh、sectionEnToZh、valueTranslations） */
    fun getAllEntries(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((k, v) in enToZh) result.add(k to v)
        for ((k, v) in sectionEnToZh) result.add("[$k]" to "[$v]")
        val seen = mutableSetOf<String>()
        for ((k, v) in valueTranslations) {
            if (!isChinese(k) && k !in seen) {
                result.add(k to v)
                seen.add(k)
            }
        }
        return result
    }

    /** 获取所有条目及其来源 */
    fun getAllEntriesWithSource(): List<TranslationEntry> {
        val result = mutableListOf<TranslationEntry>()
        for ((k, v) in enToZh) {
            result.add(TranslationEntry(entrySources[k] ?: TranslationSource.NATIVE, k, v))
        }
        for ((k, v) in sectionEnToZh) {
            result.add(TranslationEntry(entrySources["[$k]"] ?: TranslationSource.NATIVE, "[$k]", "[$v]"))
        }
        val seen = mutableSetOf<String>()
        for ((k, v) in valueTranslations) {
            if (!isChinese(k) && k !in seen) {
                result.add(TranslationEntry(entrySources[k] ?: TranslationSource.NATIVE, k, v))
                seen.add(k)
            }
        }
        return result
    }

    /** 获取某个条目的来源 */
    fun getSource(key: String): TranslationSource {
        return entrySources[key] ?: TranslationSource.NATIVE
    }

    /** 获取所有中文section */
    fun getAllChineseSections(): Set<String> = sectionZhToEn.keys

    /** 获取特殊值集合 */
    fun getSpecialValues(): Set<String> = specialValues

    /** 判断是否是中文 */
    fun isChinese(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }

    /**
     * 在文本中翻译所有匹配的key（用于value中的文本翻译）
     * 类似Python的 translate_in_text 方法
     * 使用统一正则表达式进行单词边界匹配，避免部分匹配
     * 同时搜索 enToZh 和 valueTranslations 中的英文key
     */
    fun translateInText(text: String, isEnToZh: Boolean = true): String {
        val pattern = if (isEnToZh) enToZhPattern else zhToEnPattern
        val lookup = if (isEnToZh) enToZhLookup else zhToEnLookup
        if (pattern == null || lookup.isEmpty()) return text

        // 统一正则单次扫描替换，性能远高于逐键循环
        return pattern.replace(text) { match ->
            lookup[match.groupValues[1]] ?: match.value
        }
    }

    /** 获取翻译统计 */
    fun getStats(): String {
        return "已加载: ${enToZh.size + sectionEnToZh.size + valueTranslations.size / 2}条翻译"
    }

    companion object {
        private const val CACHE_VERSION = 8

        /** 结尾一个括号组（如 self.eventData() / debugPassthrough(LogicBoolean) 的尾部） */
        private val TRAILING_PARENS_REGEX = Regex("""\([^()]*\)$""")
    }
}
