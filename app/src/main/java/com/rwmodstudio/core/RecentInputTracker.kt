package com.rwmodstudio.core

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 全局编辑历史追踪器：记录用户在编辑器中实际的编辑动作（增/删/改），跨文件。
 *
 * 用途：AI 代码补全时，将用户「最近在做什么编辑」作为关键上下文传给 AI，
 * 让 AI 根据用户的编辑习惯推断下一步最可能的操作，而非仅续写。
 *
 * 原理：每次 onTextChange 时，将旧文本与新文本做行级 diff（公共前缀 + 公共后缀），
 * 提取中间差异段，分类为 ADD（新增行）/ MODIFY（修改行 旧→新）/ DELETE（删除行）。
 *
 * 去抖：连续 MODIFY 同一行（逐字符打字）时，合并为一条动作，保留最初的旧值和最新的新值。
 */
object RecentInputTracker {

    private const val TAG = "RecentInputTracker"
    private const val MAX_ACTIONS = 8
    private const val MAX_LINE_LENGTH = 300

    /** 编辑动作类型 */
    enum class EditType { ADD, MODIFY, DELETE }

    /** 单条编辑动作 */
    data class EditAction(
        val type: EditType,
        val oldLine: String?,  // MODIFY/DELETE 时有值
        val newLine: String?,  // ADD/MODIFY 时有值
        val filePath: String = ""  // 来源文件（相对路径）
    ) {
        fun format(): String = when (type) {
            EditType.ADD -> "[新增] $newLine"
            EditType.MODIFY -> "[修改] $oldLine → $newLine"
            EditType.DELETE -> "[删除] $oldLine"
        }
    }

    private val recentActions = ConcurrentLinkedDeque<EditAction>()

    /** 当前文件路径（由 EditorScreen 设置，用于关联动作来源） */
    @Volatile
    var currentFilePath: String = ""

    /**
     * 比较旧文本与新文本，记录编辑动作（增/删/改）。
     * 使用公共前缀+公共后缀算法定位差异段，再分类。
     * 连续 MODIFY 同一行会合并，避免逐字符打字产生噪声。
     *
     * @param oldText 修改前全文
     * @param newText 修改后全文
     */
    fun diffAndRecord(oldText: String, newText: String) {
        if (oldText == newText) return
        try {
            val oldLines = oldText.split('\n')
            val newLines = newText.split('\n')

            // 公共前缀
            var prefix = 0
            while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
                prefix++
            }
            // 公共后缀（不能与前缀重叠）
            var suffix = 0
            while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix
                && oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
            ) {
                suffix++
            }

            // 差异段
            val oldMiddle = if (oldLines.size - prefix - suffix > 0) oldLines.subList(prefix, oldLines.size - suffix) else emptyList()
            val newMiddle = if (newLines.size - prefix - suffix > 0) newLines.subList(prefix, newLines.size - suffix) else emptyList()

            if (oldMiddle.isEmpty() && newMiddle.isEmpty()) return

            val fp = currentFilePath

            when {
                // 纯新增
                oldMiddle.isEmpty() -> {
                    for (line in newMiddle) {
                        val t = line.trim()
                        if (t.isNotBlank()) recordAction(EditAction(EditType.ADD, null, truncate(t), fp))
                    }
                }
                // 纯删除
                newMiddle.isEmpty() -> {
                    for (line in oldMiddle) {
                        val t = line.trim()
                        if (t.isNotBlank()) recordAction(EditAction(EditType.DELETE, truncate(t), null, fp))
                    }
                }
                // 单行→单行：修改（可能需要与上一条合并）
                oldMiddle.size == 1 && newMiddle.size == 1 -> {
                    val old = oldMiddle[0].trim()
                    val nw = newMiddle[0].trim()
                    if (old != nw) {
                        recordOrMergeModify(truncate(old), truncate(nw), fp)
                    }
                }
                // 复杂差异：逐行配对，多出的归为新增/删除
                else -> {
                    val minLen = minOf(oldMiddle.size, newMiddle.size)
                    for (i in 0 until minLen) {
                        val old = oldMiddle[i].trim()
                        val nw = newMiddle[i].trim()
                        if (old != nw && (old.isNotBlank() || nw.isNotBlank())) {
                            recordOrMergeModify(truncate(old), truncate(nw), fp)
                        }
                    }
                    for (i in minLen until newMiddle.size) {
                        val t = newMiddle[i].trim()
                        if (t.isNotBlank()) recordAction(EditAction(EditType.ADD, null, truncate(t), fp))
                    }
                    for (i in minLen until oldMiddle.size) {
                        val t = oldMiddle[i].trim()
                        if (t.isNotBlank()) recordAction(EditAction(EditType.DELETE, truncate(t), null, fp))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "diffAndRecord failed: ${e.message}")
        }
    }

    /**
     * 记录 MODIFY 动作，如果上一条也是对同一行的连续修改则合并。
     * 判定：上一条的 newLine == 当前的 oldLine（用户在同一行继续打字）。
     */
    private fun recordOrMergeModify(old: String, nw: String, fp: String) {
        val last = recentActions.peekLast()
        if (last != null && last.type == EditType.MODIFY && last.newLine == old && last.filePath == fp) {
            // 合并：保留最初的 oldLine，更新为最新的 newLine
            recentActions.pollLast()
            recordAction(EditAction(EditType.MODIFY, last.oldLine, nw, fp))
        } else {
            recordAction(EditAction(EditType.MODIFY, old, nw, fp))
        }
    }

    private fun truncate(s: String): String =
        if (s.length > MAX_LINE_LENGTH) s.substring(0, MAX_LINE_LENGTH) else s

    private fun recordAction(action: EditAction) {
        recentActions.addLast(action)
        while (recentActions.size > MAX_ACTIONS) {
            recentActions.pollFirst()
        }
    }

    /**
     * 获取最近的编辑动作（按时间顺序，最旧→最新），格式化文本。
     * 每条一行：[新增] xxx / [修改] old → new / [删除] xxx
     * 如果来自不同文件，标注文件名。
     */
    fun getRecentLines(): String {
        if (recentActions.isEmpty()) return ""
        return recentActions.joinToString("\n") { action ->
            val fileTag = if (action.filePath.isNotBlank() && action.filePath != currentFilePath) {
                "(${action.filePath}) "
            } else ""
            fileTag + action.format()
        }
    }

    /**
     * 是否有记录
     */
    fun hasRecent(): Boolean = !recentActions.isEmpty()

    /**
     * 清空记录（切换项目/退出编辑时调用）
     */
    fun clear() = recentActions.clear()
}
