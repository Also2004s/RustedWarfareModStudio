package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 翻译屏蔽词配置
 *
 * 用于控制翻译引擎在哪些 key 或文本片段上跳过翻译。
 * 配置持久化在外部存储 RWmod/translation_blocklist.json。
 */
data class TranslationBlocklist(
    val enabled: Boolean = DEFAULT_ENABLED,
    val keys: List<String> = DEFAULT_BLOCK_KEYS,
    val blockVariables: Boolean = true,
    val blockAtTokens: Boolean = true,
    val blockFileNames: Boolean = true,
    val blockQuotedDictWords: Boolean = false,
    val forcePercentVariables: Boolean = true,
    val verifyCode: String = DEFAULT_VERIFY_CODE
) {
    companion object {
        private const val TAG = "TranslationBlocklist"
        private const val FILENAME = "translation_blocklist.json"
        private const val DEFAULT_ENABLED = true
        private val DEFAULT_VERIFY_CODE = SettingsManager.BLOCKLIST_VERIFY_CODE

        /**
         * 默认屏蔽 key 列表：命中这些 key 时，对应 value 不翻译。
         */
        val DEFAULT_BLOCK_KEYS = listOf(
            // 英文 key
            "name",
            "copyFrom",
            "tags",
            "description",
            "displayText",
            "displayDescription",
            "altNames",
            "overrideAndReplace",
            "canBuild_1_name",
            "alsoTriggerAction",
            "alsoQueueAction",
            "canOnlyAttackUnitsWithTags",
            "addGlobalTeamTags",
            "removeGlobalTeamTags",
            "interceptProjectiles_withTags",
            "sendMessageWithTags",
            "canOnlyAttackUnitsWithoutTags",
            "onlyUseAsHarvester_ifBaseHasUnitTagged",
            "retargetingInFlightSearchOnlyTags",
            "canReclaimUnitsOnlyWithTags",
            "canRepairUnitsOnlyWithTags",
            "canReclaimResourcesOnlyWithTags",
            "mutator1_ifUnitWithoutTags",
            "mutator1_ifUnitWithTags",
            "deleteNumUnitsFromTransport_onlyWithTags",
            "similarResourcesHaveTag",
            "transportUnitsRequireTag",
            "text",
            "explodeEffect",
            "projectile",
            "movementEffect",
            "ifUnitWithTags",
            "warmupStartEffect",
            "playAnimation",
            "onShoot_playAnimation",
            "displayText_zh",
            "text_zh",
            // 中文 key
            "复制与",
            "描述",
            "界面显示名称",
            "界面显示描述",
            "别名",
            "覆盖单位",
            "添加指定标签的随机单位",
            "添加路径点失败触发",
            "添加路径点匹配触发",
            "添加路径点检索标签",
            "提取资源标签",
            "提取资源触发行为",
            "添加路径点单位类型",
            "转换成",
            "图标",
            "产生效果",
            "也添加进队列",
            "也执行动作",
            "只攻击带特定标签单位",
            "添加全局标签",
            "移除全局标签",
            "拦截抛射体需有标签",
            "带标签发送消息",
            "不攻击带特定标签单位",
            "有此标签才作为采集者",
            "重新瞄准在飞行时针对标签",
            "仅允许回收特定标签单位",
            "仅允许维修特定标签单位",
            "仅允许回收特定标签资源",
            "从载具删除带标签单位",
            "像用于此标签的单位",
            "临时标签删除",
            "临时标签添加",
            "被运输单位需要标签",
            "仅许带此标签单位攻击"
        )

        /**
         * 默认受保护的文件扩展名：匹配到这些扩展名的文件名整体不被翻译。
         */
        val FILE_EXTENSIONS = listOf("png", "ini", "ogg", "wav", "jpg", "jpeg", "gif")

        fun getFile(context: Context): File = RwmodPaths.translationBlocklistFile

        fun getLegacyFile(): File = RwmodPaths.translationBlocklistFile

        /**
         * 加载配置，文件不存在时创建默认配置。
         * 验证码统一使用 RWmod/verify.json 中的 blocklist 字段。
         */
        fun load(context: Context): TranslationBlocklist {
            val file = resolveFile(context)
            if (!file.exists()) {
                val default = TranslationBlocklist()
                save(context, default)
                return default
            }
            // 验证码不匹配时重置为默认
            if (SettingsManager.readVerifyCode(SettingsManager.VERIFY_BLOCKLIST) != DEFAULT_VERIFY_CODE) {
                Log.d(TAG, "Verify code mismatch, reset blocklist to default")
                val default = TranslationBlocklist()
                save(context, default)
                return default
            }
            return try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val enabled = json.optBoolean("enabled", DEFAULT_ENABLED)
                val array = json.optJSONArray("keys")
                val keys = if (array != null) {
                    (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotEmpty() }
                } else {
                    DEFAULT_BLOCK_KEYS
                }
                TranslationBlocklist(
                    enabled = enabled,
                    keys = keys,
                    blockVariables = json.optBoolean("blockVariables", true),
                    blockAtTokens = json.optBoolean("blockAtTokens", true),
                    blockFileNames = json.optBoolean("blockFileNames", true),
                    blockQuotedDictWords = json.optBoolean("blockQuotedDictWords", false),
                    forcePercentVariables = json.optBoolean("forcePercentVariables", true),
                    verifyCode = DEFAULT_VERIFY_CODE
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocklist, using default", e)
                TranslationBlocklist()
            }
        }

        /**
         * 保存配置到外部存储。
         */
        fun save(context: Context, blocklist: TranslationBlocklist): Boolean {
            return try {
                val file = resolveFile(context)
                file.parentFile?.mkdirs()
                val json = JSONObject().apply {
                    put("enabled", blocklist.enabled)
                    put("keys", JSONArray(blocklist.keys))
                    put("blockVariables", blocklist.blockVariables)
                    put("blockAtTokens", blocklist.blockAtTokens)
                    put("blockFileNames", blocklist.blockFileNames)
                    put("blockQuotedDictWords", blocklist.blockQuotedDictWords)
                    put("forcePercentVariables", blocklist.forcePercentVariables)
                    put("verifyCode", DEFAULT_VERIFY_CODE)
                }
                file.writeText(json.toString(2), Charsets.UTF_8)
                SettingsManager.writeVerifyCode(SettingsManager.VERIFY_BLOCKLIST, DEFAULT_VERIFY_CODE)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save blocklist", e)
                false
            }
        }

        /**
         * 优先使用外置存储根目录下的 RWmod，兼容旧路径；不可用时回退到 Context.getExternalFilesDir 上级目录。
         */
        private fun resolveFile(context: Context): File {
            val legacy = getLegacyFile()
            if (legacy.exists() || legacy.parentFile?.exists() == true) {
                return legacy
            }
            return getFile(context)
        }
    }

    /**
     * 判断指定 key 是否需要屏蔽 value 翻译。
     */
    fun shouldBlockKey(key: String): Boolean {
        if (!enabled) return false
        return key.trim() in keys
    }

    /**
     * 对文本中的特殊片段进行占位保护，返回保护后的文本和占位映射。
     * 调用方在翻译完成后应使用 restoreProtected 还原。
     */
    fun protectFragments(text: String): Pair<String, Map<String, String>> {
        if (!enabled) return text to emptyMap()

        val placeholders = mutableMapOf<String, String>()
        var counter = 0
        var result = text

        // 保护 ${...} 变量
        if (blockVariables) {
            result = Regex("""\$\{[^}]*\}""").replace(result) { match ->
                val ph = generatePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            }
        }

        // 保护 %{...} 变量（除非开启强制翻译），需兼容内部的 ${...}
        if (!forcePercentVariables) {
            result = Regex("""%\{((?:[^$}]|\$\{[^}]*\})*?)\}""").replace(result) { match ->
                val ph = generatePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            }
        }

        // 保护 @xxx key:value 形式（如 @define name: 中立）
        if (blockAtTokens) {
            result = Regex("""@(\w+)\s+(\S+?)\s*:\s*(\S+)""").replace(result) { match ->
                val ph = generatePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            }

            // 保护 @xxx token（@memory 攻击目标、@global zfk 等）
            result = Regex("""@(\w+)\s+(\S+)""").replace(result) { match ->
                val ph = generatePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            }
        }

        // 保护带特定扩展名的文件名（如 跟随.png、跟随.ini）
        if (blockFileNames && FILE_EXTENSIONS.isNotEmpty()) {
            val extPattern = FILE_EXTENSIONS.joinToString("|") { Regex.escape(it) }
            result = Regex("""\S+\.(?:$extPattern)\b""", RegexOption.IGNORE_CASE).replace(result) { match ->
                val ph = generatePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            }
        }

        return result to placeholders
    }

    /**
     * 保护 value 中被英文单/双引号包裹且存在于翻译库中的词。
     * 返回保护后的文本和占位映射。
     */
    fun protectQuotedDictWords(text: String, isDictWord: (String) -> Boolean): Pair<String, Map<String, String>> {
        if (!enabled || !blockQuotedDictWords) return text to emptyMap()
        val placeholders = mutableMapOf<String, String>()
        var counter = 0
        val result = Regex(""""([^"]*)"|'([^']*)'""").replace(text) { match ->
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (inner.isNotEmpty() && isDictWord(inner)) {
                val ph = generateQuotePlaceholder(counter++)
                placeholders[ph] = match.value
                ph
            } else {
                match.value
            }
        }
        return result to placeholders
    }

    private fun generateQuotePlaceholder(index: Int): String = "@@QUOTE_${index}_@@"

    /**
     * 还原被占位保护的片段。
     * 循环还原以处理嵌套保护（如 blockVariables 先保护 ${...}，blockFileNames 再保护包含占位符的文件名）。
     */
    fun restoreProtected(text: String, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return text
        val escaped = placeholders.keys.joinToString("|") { Regex.escape(it) }
        val pattern = Regex(escaped)
        var result = text
        // 最多循环占位符数量次，防止异常情况下无限循环
        repeat(placeholders.size) {
            val prev = result
            result = pattern.replace(result) { match ->
                placeholders[match.value] ?: match.value
            }
            if (result == prev) return result
        }
        return result
    }

    private fun generatePlaceholder(index: Int): String {
        return "@@BLOCK_${index}_@@"
    }
}
