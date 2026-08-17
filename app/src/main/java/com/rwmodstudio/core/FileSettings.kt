package com.rwmodstudio.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 基于 JSON 文件的轻量级键值存储，接口与 [SharedPreferences] 对齐。
 *
 * 用途：替代 Android SharedPreferences，把所有用户设置持久化到 RWmod/config/settings.json，
 * 实现本地持久化完全外部化。
 */
class FileSettings private constructor(
    private val file: File
) {

    companion object {
        private const val TAG = "FileSettings"
        private val REMOVED = Any()

        fun create(context: Context): FileSettings {
            val file = RwmodPaths.settingsFile
            val settings = FileSettings(file)
            settings.migrateFromSharedPreferences(context)
            return settings
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val data = mutableMapOf<String, Any?>()

    init {
        load()
    }

    /** 立即从磁盘加载 JSON；文件不存在时保持空表 */
    private fun load() {
        synchronized(lock) {
            data.clear()
            if (!file.exists()) return
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when {
                        json.isNull(key) -> data[key] = null
                        else -> data[key] = json.get(key)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings from $file", e)
            }
        }
    }

    /** 异步写入磁盘 */
    private fun persist() {
        executor.execute {
            synchronized(lock) {
                try {
                    file.parentFile?.mkdirs()
                    val json = JSONObject()
                    for ((k, v) in data) {
                        when (v) {
                            null -> json.put(k, JSONObject.NULL)
                            is String -> json.put(k, v)
                            is Boolean -> json.put(k, v)
                            is Int -> json.put(k, v)
                            is Long -> json.put(k, v)
                            is Float -> json.put(k, v.toDouble())
                            is Double -> json.put(k, v)
                            else -> json.put(k, v.toString())
                        }
                    }
                    file.writeText(json.toString(2), Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save settings to $file", e)
                }
            }
        }
    }

    /** 一次性迁移：若 settings.json 不存在，从 SharedPreferences 读取并写入 */
    private fun migrateFromSharedPreferences(context: Context) {
        if (file.exists()) return
        try {
            val prefs = context.getSharedPreferences("rusted_mod_studio_prefs", Context.MODE_PRIVATE)
            synchronized(lock) {
                data.clear()
                for ((key, value) in prefs.all) {
                    data[key] = value
                }
            }
            persist()
            // 迁移完成后清空 SharedPreferences，避免双写
            prefs.edit().clear().apply()
            Log.d(TAG, "Migrated settings from SharedPreferences to $file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate SharedPreferences", e)
        }
    }

    fun getString(key: String, defValue: String?): String? {
        synchronized(lock) {
            return when (val v = data[key]) {
                is String -> v
                null -> defValue
                else -> v.toString()
            }
        }
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        synchronized(lock) {
            return when (val v = data[key]) {
                is Boolean -> v
                is String -> v.toBooleanStrictOrNull() ?: defValue
                is Number -> v.toInt() != 0
                null -> defValue
                else -> defValue
            }
        }
    }

    fun getFloat(key: String, defValue: Float): Float {
        synchronized(lock) {
            return when (val v = data[key]) {
                is Number -> v.toFloat()
                is String -> v.toFloatOrNull() ?: defValue
                null -> defValue
                else -> defValue
            }
        }
    }

    fun getInt(key: String, defValue: Int): Int {
        synchronized(lock) {
            return when (val v = data[key]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: defValue
                null -> defValue
                else -> defValue
            }
        }
    }

    fun contains(key: String): Boolean {
        synchronized(lock) {
            return data.containsKey(key)
        }
    }

    fun edit(): Editor = Editor(this)

    inner class Editor(private val settings: FileSettings) {

        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false

        fun putString(key: String, value: String?): Editor {
            pending[key] = value
            return this
        }

        fun putBoolean(key: String, value: Boolean): Editor {
            pending[key] = value
            return this
        }

        fun putFloat(key: String, value: Float): Editor {
            pending[key] = value
            return this
        }

        fun putInt(key: String, value: Int): Editor {
            pending[key] = value
            return this
        }

        fun putLong(key: String, value: Long): Editor {
            pending[key] = value
            return this
        }

        fun putStringSet(key: String, value: Set<String>?): Editor {
            pending[key] = value?.toList()
            return this
        }

        fun remove(key: String): Editor {
            pending[key] = REMOVED
            return this
        }

        fun clear(): Editor {
            clearAll = true
            pending.clear()
            return this
        }

        fun apply() {
            synchronized(lock) {
                if (clearAll) {
                    data.clear()
                    clearAll = false
                }
                for ((k, v) in pending) {
                    if (v === REMOVED) {
                        data.remove(k)
                    } else {
                        data[k] = v
                    }
                }
                pending.clear()
            }
            persist()
        }

        fun commit(): Boolean {
            apply()
            return true
        }
    }
}
