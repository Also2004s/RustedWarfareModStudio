package com.rwmodstudio.core.translation

import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.util.atomicWriteText

/**
 * 查重词禁用列表：查重结果里一键禁用的中文词。
 *
 * 作用：翻译引擎对命中列表的词**双向停用**——英译中不再把它作为译文，
 * 中译英不再把它换成英文。数据存 `RWmod/translation/disabled_words.txt`（一行一词）。
 * 用户数据，不配验证码。
 */
object DisabledWords {

    private const val TAG = "DisabledWords"

    @Volatile
    private var cached: Set<String>? = null

    /** 全部禁用词（trim 后，保持原始大小写）。首次访问读盘并缓存。 */
    fun words(): Set<String> {
        cached?.let { return it }
        val loaded = try {
            val file = RwmodPaths.disabledWordsFile
            if (file.exists()) {
                file.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "read disabled words failed", e)
            emptySet()
        }
        cached = loaded
        return loaded
    }

    fun contains(word: String): Boolean = word.trim() in words()

    @Synchronized
    fun add(word: String): Set<String> = addAll(listOf(word))

    /** 批量加入，返回更新后的集合；一次写盘。 */
    @Synchronized
    fun addAll(newWords: Collection<String>): Set<String> {
        val merged = words().toMutableSet()
        newWords.map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged.add(it) }
        persist(merged)
        return merged
    }

    @Synchronized
    fun remove(word: String): Set<String> {
        val merged = words().toMutableSet()
        merged.remove(word.trim())
        persist(merged)
        return merged
    }

    private fun persist(words: Set<String>) {
        cached = words
        try {
            RwmodPaths.disabledWordsFile.atomicWriteText(words.joinToString("\n"), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "save disabled words failed", e)
        }
    }
}
