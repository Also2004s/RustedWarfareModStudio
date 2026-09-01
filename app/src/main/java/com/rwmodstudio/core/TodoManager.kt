package com.rwmodstudio.core

import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.util.atomicWriteText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 项目待办管理器。
 * 每个项目的待办单独存储在 RWmod/project_todos/<md5(项目绝对路径)>.json 中。
 */
object TodoManager {

    private const val TAG = "TodoManager"

    data class TodoItem(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val done: Boolean = false,
        val priority: Int = 0,        // 0=普通, 1=重要, 2=紧急
        val createdAt: Long = System.currentTimeMillis(),
        val note: String = ""
    )

    private val todoDir: File
        get() = RwmodPaths.todosDir

    // 读改写串行锁：快速连续勾选/删除会并发「读整个文件→改→写回」，不加锁时后写覆盖先写导致操作丢失
    private val lock = Any()

    private fun projectKey(path: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(path.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun todoFile(path: String): File {
        return File(todoDir, "${projectKey(path)}.json")
    }

    fun load(projectPath: String): List<TodoItem> {
        val file = todoFile(projectPath)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("items") ?: return emptyList()
            val items = mutableListOf<TodoItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    TodoItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        done = obj.optBoolean("done", false),
                        priority = obj.optInt("priority", 0).coerceIn(0, 2),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        note = obj.optString("note", "")
                    )
                )
            }
            items.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load todos for $projectPath", e)
            emptyList()
        }
    }

    /** 保存待办，返回是否写盘成功（调用方据此提示用户） */
    fun save(projectPath: String, items: List<TodoItem>): Boolean = synchronized(lock) {
        try {
            val file = todoFile(projectPath)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("done", item.done)
                        put("priority", item.priority)
                        put("createdAt", item.createdAt)
                        put("note", item.note)
                    }
                )
            }
            file.atomicWriteText(JSONObject().apply { put("items", array) }.toString(2), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save todos for $projectPath", e)
            false
        }
    }

    /** 新增待办，返回是否保存成功 */
    fun add(projectPath: String, title: String, priority: Int = 0, note: String = ""): Boolean = synchronized(lock) {
        val current = load(projectPath).toMutableList()
        current.add(TodoItem(title = title, priority = priority.coerceIn(0, 2), note = note))
        save(projectPath, current)
    }

    /** 修改待办，返回是否保存成功 */
    fun update(projectPath: String, item: TodoItem): Boolean = synchronized(lock) {
        val current = load(projectPath).toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
            save(projectPath, current)
        } else true // 条目不存在（已被删），视为无需保存
    }

    /** 删除待办，返回是否保存成功 */
    fun delete(projectPath: String, id: String): Boolean = synchronized(lock) {
        val current = load(projectPath).filter { it.id != id }
        save(projectPath, current)
    }

    /** 删除已完成待办，返回是否保存成功 */
    fun deleteCompleted(projectPath: String): Boolean = synchronized(lock) {
        val current = load(projectPath).filter { !it.done }
        save(projectPath, current)
    }
}
