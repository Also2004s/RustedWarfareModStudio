package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 保存历史管理器：记录最近 20 次文件保存的前后内容，供「近期修改」查看差异。
 */
object SaveHistoryManager {
    private const val TAG = "SaveHistoryManager"
    private const val FILENAME = "save_history.json"

    data class SaveRecord(
        val filePath: String,
        val timestamp: Long,
        val beforeContent: String,
        val afterContent: String
    )

    private var records: MutableList<SaveRecord> = mutableListOf()
    private var initialized = false

    private fun maxRecords(): Int {
        return try {
            SettingsManager.recentHistoryLimit.coerceIn(50, 200)
        } catch (_: Exception) { 100 }
    }

    private fun getFile(context: Context): File = RwmodPaths.saveHistoryFile

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val file = getFile(context)
            if (!file.exists()) return
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("records") ?: return
            records.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                records.add(
                    SaveRecord(
                        filePath = obj.optString("filePath", ""),
                        timestamp = obj.optLong("timestamp", 0),
                        beforeContent = obj.optString("beforeContent", ""),
                        afterContent = obj.optString("afterContent", "")
                    )
                )
            }
            pruneMissing(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load save history", e)
        }
    }

    @Synchronized
    fun record(context: Context, filePath: String, beforeContent: String, afterContent: String) {
        init(context)
        // 内容没变化的保存不记录，避免占用缓存
        if (sameContent(beforeContent, afterContent)) return
        records.add(0, SaveRecord(filePath, System.currentTimeMillis(), beforeContent, afterContent))
        // 净差异为零的文件（改了一通又改回来）清除其所有记录；全量检查顺带自愈存量数据
        removeNetZeroRecords()
        val limit = maxRecords()
        if (records.size > limit) {
            records = records.take(limit).toMutableList()
        }
        save(context)
    }

    /** 与差异视图一致的「无差异」判定：逐行比较，容忍行尾符/末尾换行差异 */
    private fun sameContent(a: String, b: String) = a == b || a.lines() == b.lines()

    /** 清除「首次保存前 → 最后修改后」无净差异的文件记录，有移除返回 true */
    private fun removeNetZeroRecords(): Boolean {
        var removed = false
        for ((path, list) in records.groupBy { it.filePath }) {
            if (sameContent(list.last().beforeContent, list.first().afterContent)) {
                records.removeAll { it.filePath == path }
                removed = true
            }
        }
        return removed
    }

    /** 清理文件已不存在的记录，有移除则回写 */
    private fun pruneMissing(context: Context) {
        val before = records.size
        records.removeAll { !File(it.filePath).exists() }
        if (records.size != before) save(context)
    }

    @Synchronized
    fun getRecentFiles(context: Context): List<String> {
        init(context)
        pruneMissing(context)
        if (removeNetZeroRecords()) save(context)
        return records.map { it.filePath }.distinct().take(maxRecords())
    }

    @Synchronized
    fun getHistoryForFile(context: Context, filePath: String): List<SaveRecord> {
        init(context)
        return records.filter { it.filePath == filePath }.sortedByDescending { it.timestamp }
    }

    /** 确认差异：将该文件所有记录的 beforeContent 更新为当前 afterContent，使差异消失 */
    @Synchronized
    fun confirmFile(context: Context, filePath: String) {
        init(context)
        val latestAfter = records.filter { it.filePath == filePath }.maxByOrNull { it.timestamp }?.afterContent ?: return
        records = records.map { if (it.filePath == filePath) it.copy(beforeContent = latestAfter) else it }.toMutableList()
        removeNetZeroRecords()
        save(context)
    }

    /** 部分确认：将指定基线内容写入最早记录的 beforeContent（用于块级确认） */
    @Synchronized
    fun updateBaseline(context: Context, filePath: String, newBaseline: String) {
        init(context)
        val fileRecords = records.filter { it.filePath == filePath }
        val earliest = fileRecords.minByOrNull { it.timestamp } ?: return
        records = records.map {
            if (it.filePath == filePath && it.timestamp == earliest.timestamp)
                it.copy(beforeContent = newBaseline)
            else it
        }.toMutableList()
        removeNetZeroRecords()
        save(context)
    }

    /** 回退修改：将文件恢复为首次保存前的内容，并清空该文件的历史记录 */
    @Synchronized
    fun revertFile(context: Context, filePath: String): Boolean {
        init(context)
        val earliestBefore = records.filter { it.filePath == filePath }.minByOrNull { it.timestamp }?.beforeContent ?: return false
        return try {
            File(filePath).writeText(earliestBefore, Charsets.UTF_8)
            records.removeAll { it.filePath == filePath }
            save(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revert file: $filePath", e)
            false
        }
    }

    private fun save(context: Context) {
        try {
            val file = getFile(context)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (r in records) {
                array.put(JSONObject().apply {
                    put("filePath", r.filePath)
                    put("timestamp", r.timestamp)
                    put("beforeContent", r.beforeContent)
                    put("afterContent", r.afterContent)
                })
            }
            val json = JSONObject().apply { put("records", array) }
            file.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save save_history.json", e)
        }
    }
}
