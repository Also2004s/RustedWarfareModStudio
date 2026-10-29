package com.rwmodstudio.core

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.rwmodstudio.util.atomicWriteText

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
        // 去重：同一文件连续记录且 beforeContent 相同，是并发写盘双读旧值的标志（合法链中连续 before 必不同）。
        // 全局写锁下正常保存不会触发；此守卫防止并发写盘导致的重复记录/历史链断裂。
        val last = records.firstOrNull { it.filePath == filePath }
        if (last != null && last.beforeContent == beforeContent) return
        records.add(0, SaveRecord(filePath, System.currentTimeMillis(), beforeContent, afterContent))
        // 只对当前文件做净零检查（改了一通又改回来即清记录），避免每次保存全量 diff 历史大文件拖慢保存
        removeNetZeroRecordsFor(filePath)
        val limit = maxRecords()
        if (records.size > limit) {
            records = records.take(limit).toMutableList()
        }
        save(context)
    }

    /** 与差异视图一致的「无差异」判定：忽略空白（与 computeLineDiff 相同规则），容忍行尾符/末尾换行差异 */
    private fun sameContent(a: String, b: String): Boolean {
        if (a == b) return true
        // 快速否定：逐行归一化相等 ⇒ 全串去空白后必然相等；故全串去空白后不等时无需再跑 LCS。
        // 大多数保存都确实改了内容，走这里直接 O(长度) 返回，避免大文件时 computeLineDiff 的 O(m*n) 动态规划。
        if (a.normalizedForDiff() != b.normalizedForDiff()) return false
        return computeLineDiff(a.lines(), b.lines()).all { it.type == ' ' }
    }

    /** 只对指定文件做净零检查：该文件「首次保存前 → 最后修改后」无净差异则清除其所有记录 */
    private fun removeNetZeroRecordsFor(filePath: String): Boolean {
        val list = records.filter { it.filePath == filePath }
        if (list.size < 2) return false
        val same = sameContent(list.last().beforeContent, list.first().afterContent)
        if (same) {
            records.removeAll { it.filePath == filePath }
            return true
        }
        return false
    }

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

    /**
     * 局部应用后的记录整理：把该文件历史压缩为一条「基线 → 当前内容」记录。
     *
     * - [baseline]：原最早记录的 beforeContent，叠加已确认的块（确认 = 该改动归入基线）
     * - [current]：文件写入回退结果后的实际内容（回退的块已恢复旧版本）
     *
     * 两者无净差异说明所有差异都被处理完 → 直接清空该文件记录（文件不再算近期修改）。
     * 中间那些旧的保存快照不再参与展示（消费方一律取 last().beforeContent / first().afterContent），
     * 保留它们反而会让记录链与磁盘内容脱节，所以这里重建为一条。
     */
    @Synchronized
    fun applyPartialResult(context: Context, filePath: String, baseline: String, current: String) {
        init(context)
        val earliestTs = records.filter { it.filePath == filePath }.minByOrNull { it.timestamp }?.timestamp
            ?: System.currentTimeMillis()
        records.removeAll { it.filePath == filePath }
        if (!sameContent(baseline, current)) {
            records.add(SaveRecord(filePath, earliestTs, baseline, current))
            records.sortByDescending { it.timestamp }
        }
        save(context)
    }

    /** 回退修改：将文件恢复为首次保存前的内容，并清空该文件的历史记录 */
    @Synchronized
    fun revertFile(context: Context, filePath: String): Boolean {
        init(context)
        val earliestBefore = records.filter { it.filePath == filePath }.minByOrNull { it.timestamp }?.beforeContent ?: return false
        return try {
            File(filePath).atomicWriteText(earliestBefore, Charsets.UTF_8)
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
            file.atomicWriteText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save save_history.json", e)
        }
    }
}
