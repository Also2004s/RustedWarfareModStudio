package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 行为验证码管理器。
 *
 * 用于记录用户已确认过的敏感/危险操作，避免首次误触。
 * 文档保存在外部存储 RWmod/verified_behaviors.json，可与查重输出目录复用同一 RWmod 根目录。
 */
object BehaviorVerifier {

    private const val TAG = "BehaviorVerifier"
    private const val FILENAME = "verified_behaviors.json"

    /**
     * 当前已定义的行为类型。
     */
    object Type {
        /** 行尾灯泡：强制翻译当前行 value，跳过屏蔽词。 */
        const val LIGHTBULB_FORCE_TRANSLATE = "LIGHTBULB_FORCE_TRANSLATE"
    }

    private fun getFile(context: Context): File = RwmodPaths.verifiedBehaviorsFile

    /**
     * 读取已验证的行为类型集合。
     */
    fun getVerifiedTypes(context: Context): Set<String> {
        val file = getFile(context)
        if (!file.exists()) return emptySet()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("behaviors") ?: return emptySet()
            (0 until array.length()).mapNotNull {
                val type = array.getJSONObject(it).optString("type", "")
                if (type.isEmpty()) null else type
            }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load verified behaviors", e)
            emptySet()
        }
    }

    /**
     * 查询某种行为是否已经验证过。
     */
    fun isVerified(context: Context, type: String): Boolean {
        return type in getVerifiedTypes(context)
    }

    /**
     * 标记某种行为已验证。
     */
    fun markVerified(context: Context, type: String): Boolean {
        return try {
            val file = getFile(context)
            file.parentFile?.mkdirs()
            val verified = getVerifiedTypes(context).toMutableSet()
            verified.add(type)
            val json = JSONObject().apply {
                put("behaviors", JSONArray(verified.map {
                    JSONObject().apply {
                        put("type", it)
                        put("verifiedAt", System.currentTimeMillis())
                    }
                }))
            }
            file.writeText(json.toString(2), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save verified behavior", e)
            false
        }
    }
}
