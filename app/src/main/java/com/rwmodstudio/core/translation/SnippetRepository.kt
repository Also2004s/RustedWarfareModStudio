package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 自定义补全表（从 VSCode snippets 加载）
 */
class SnippetRepository {

    private companion object {
        const val TAG = "SnippetRepository"
    }

    data class Snippet(
        val prefix: List<String> = emptyList(),
        val body: List<String> = emptyList(),
        val description: String = ""
    )

    private var snippets = mapOf<String, Snippet>()
    val isLoaded: Boolean get() = snippets.isNotEmpty()

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("data/snippets.json")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val json = reader.readText()
            reader.close()

            val jsonObj = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val element = jsonObj.parseToJsonElement(json)
            val result = mutableMapOf<String, Snippet>()
            if (element is JsonObject) {
                for ((key, value) in element) {
                    if (value !is JsonObject) continue
                    val prefix = value["prefix"]?.jsonArray?.mapNotNull {
                        (it as? JsonPrimitive)?.content
                    } ?: emptyList()
                    val body = when (val bodyEl = value["body"]) {
                        is JsonArray -> bodyEl.mapNotNull { (it as? JsonPrimitive)?.content }
                        is JsonPrimitive -> listOf(bodyEl.content)
                        else -> emptyList()
                    }
                    val description = (value["description"] as? JsonPrimitive)?.content ?: ""
                    result[key] = Snippet(prefix, body, description)
                }
            }
            snippets = result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snippets", e)
        }
    }

    /**
     * 根据前缀搜索 snippets
     */
    fun search(prefix: String): List<SnippetResult> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val results = mutableListOf<SnippetResult>()

        for ((key, snippet) in snippets) {
            for (p in snippet.prefix) {
                if (p.lowercase().startsWith(lower)) {
                    val firstLine = snippet.body.firstOrNull() ?: ""
                    results.add(SnippetResult(
                        key = key,
                        prefix = p,
                        body = snippet.body.joinToString("\n"),
                        firstLine = firstLine,
                        description = snippet.description
                    ))
                    break
                }
            }
        }

        return results
    }

    data class SnippetResult(
        val key: String,
        val prefix: String,
        val body: String,
        val firstLine: String,
        val description: String
    )
}
