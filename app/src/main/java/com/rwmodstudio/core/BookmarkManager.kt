package com.rwmodstudio.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.rwmodstudio.util.atomicWriteText

/**
 * 书签管理器：记录用户打标的文件（可为置顶）。存储于 RWmod/config/bookmarks.json。
 * 支持添加/移除/置顶/排序（按时间或按文件名），供编辑器侧边栏与文件浏览器共用。
 */
object BookmarkManager {
    private const val TAG = "BookmarkManager"

    data class BookmarkEntry(
        val path: String,
        val name: String,
        val pinned: Boolean,
        val addedAt: Long
    )

    private const val MAX = 100

    private fun file(): File = RwmodPaths.bookmarksFile

    @Synchronized
    fun all(sort: Sort = Sort.TIME): List<BookmarkEntry> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = o.optString("path", "")
                    if (p.isNotEmpty() && File(p).exists()) {
                        add(BookmarkEntry(p, o.optString("name", File(p).name),
                            o.optBoolean("pinned", false), o.optLong("addedAt", 0)))
                    }
                }
            }
            list.sortedWith(comparator(sort))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bookmarks", e)
            emptyList()
        }
    }

    private fun comparator(sort: Sort): Comparator<BookmarkEntry> = when (sort) {
        Sort.TIME -> compareByDescending<BookmarkEntry> { it.pinned }.thenByDescending { it.addedAt }
        Sort.NAME -> compareByDescending<BookmarkEntry> { it.pinned }.thenBy { it.name.lowercase() }
    }

    enum class Sort { TIME, NAME }

    @Synchronized
    fun isBookmarked(path: String): Boolean = all().any { it.path == path }

    /** 对指定文件添加/移除书签，返回添加后的书签状态（true=已加） */
    @Synchronized
    fun toggle(path: String, name: String): Boolean {
        val current = readRaw()
        val exists = current.any { it.path == path }
        val updated = if (exists) current.filterNot { it.path == path } else
            (current + BookmarkEntry(path, name, pinned = false, addedAt = System.currentTimeMillis())).take(MAX)
        write(updated)
        return !exists
    }

    @Synchronized
    fun remove(path: String) {
        write(readRaw().filterNot { it.path == path })
    }

    @Synchronized
    fun setPinned(path: String, pinned: Boolean) {
        write(readRaw().map { if (it.path == path) it.copy(pinned = pinned) else it })
    }

    private fun readRaw(): List<BookmarkEntry> = all()

    private fun write(list: List<BookmarkEntry>) {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("path", e.path)
                    put("name", e.name)
                    put("pinned", e.pinned)
                    put("addedAt", e.addedAt)
                })
            }
            f.atomicWriteText(arr.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bookmarks", e)
        }
    }
}