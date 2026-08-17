package com.rwmodstudio.core

import android.util.Log
import com.rwmodstudio.core.RwmodPaths
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

    fun save(projectPath: String, items: List<TodoItem>) {
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
            file.writeText(JSONObject().apply { put("items", array) }.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save todos for $projectPath", e)
        }
    }

    fun add(projectPath: String, title: String, priority: Int = 0, note: String = "") {
        val current = load(projectPath).toMutableList()
        current.add(TodoItem(title = title, priority = priority.coerceIn(0, 2), note = note))
        save(projectPath, current)
    }

    fun update(projectPath: String, item: TodoItem) {
        val current = load(projectPath).toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
            save(projectPath, current)
        }
    }

    fun delete(projectPath: String, id: String) {
        val current = load(projectPath).filter { it.id != id }
        save(projectPath, current)
    }

    fun deleteCompleted(projectPath: String) {
        val current = load(projectPath).filter { !it.done }
        save(projectPath, current)
    }
}
