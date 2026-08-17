package com.rwmodstudio.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 全局任务进度管理器：Debug 模式下在 UI 右上角显示后台任务进度浮窗。
 * 支持多任务堆叠显示：已完成任务滞留在上方，当前任务显示进度条。
 */
object TaskProgressManager {
    data class TaskInfo(
        val name: String,
        val current: Int = 0,
        val total: Int = 0,
        val detail: String = "",
        val active: Boolean = true
    )

    /** 当前活跃任务 */
    private val _currentTask = mutableStateOf<TaskInfo?>(null)
    val currentTask: TaskInfo? get() = _currentTask.value

    /** 已完成任务列表（滞留显示 3 秒后移除） */
    val completedTasks: SnapshotStateList<TaskInfo> = mutableStateListOf()

    /** 是否有任何显示内容 */
    val displayActive get() = _currentTask.value != null || completedTasks.isNotEmpty()

    private val handler = Handler(Looper.getMainLooper())

    fun start(name: String, total: Int = 0) {
        handler.removeCallbacksAndMessages(null)
        // 把之前活跃的任务移到完成列表
        _currentTask.value?.let { prev ->
            if (prev.active) {
                completedTasks.add(prev.copy(active = false))
                scheduleRemove(completedTasks.lastIndex)
            }
        }
        _currentTask.value = TaskInfo(name = name, total = total)
    }

    fun update(current: Int, detail: String = "") {
        _currentTask.value = _currentTask.value?.copy(current = current, detail = detail)
    }

    fun finish() {
        val finished = _currentTask.value ?: return
        completedTasks.add(finished.copy(active = false))
        scheduleRemove(completedTasks.lastIndex)
        _currentTask.value = null
        // 如果还有等待中的任务（很少见），3秒后清空全部
        handler.postDelayed({
            if (_currentTask.value == null) completedTasks.clear()
        }, 3000)
    }

    private fun scheduleRemove(index: Int) {
        handler.postDelayed({
            if (index in completedTasks.indices) {
                completedTasks.removeAt(index)
            }
        }, 3000)
    }
}
