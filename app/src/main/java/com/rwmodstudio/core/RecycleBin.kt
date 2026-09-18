package com.rwmodstudio.core

import android.util.Log
import java.io.File

/**
 * 回收站条目：直接对应 `RWmod/exports` 目录下的一个实际条目。
 */
data class RecycleEntry(
    val file: File,
    val isDirectory: Boolean,
    /** 文件字节数；文件夹为递归统计值 */
    val sizeBytes: Long
) {
    val name: String get() = file.name
    val lastModified: Long get() = file.lastModified()
}

/**
 * 回收站 = `RWmod/exports` 目录本身（老板指定：不再建子目录，也不记录原路径）。
 *
 * 规则：
 * - 首页删除文件/文件夹 → 移动到这里（[moveToTrash]）；
 * - 列表只展示 **`.rwmod` 文件与文件夹**（其余零散文件不展示，避免把导出产物/杂项混进来）；
 * - 「还原」统一把条目移回**首页目录**（`SettingsManager.defaultModPath()`），不回原路径；
 * - App 每次启动 [purgeIfOversize]：目录内容超过 25MB 整体清空。
 */
object RecycleBin {

    private const val TAG = "RecycleBin"

    /** 启动清理阈值：exports 目录内容超过该体积则整体清空（25MB） */
    const val MAX_BYTES: Long = 25L * 1024 * 1024

    /** 回收站目录 = RWmod/exports 本身 */
    val dir: File
        get() = RwmodPaths.exportsDir

    // ==================== 移入回收站 ====================

    /**
     * 把文件/文件夹移入回收站，成功返回 true。
     *
     * 先尝试 renameTo（同分区几乎瞬时完成），失败再退化为「复制 + 删除」；
     * 大文件夹可能耗时，调用方应在 IO 线程执行。
     */
    @Synchronized
    fun moveToTrash(target: File): Boolean {
        if (!target.exists()) return false
        val trash = dir
        // 防御：目标若是回收站自身或其祖先（例如误删 exports 甚至存储根），拒绝执行，否则自毁
        if (contains(ancestor = target, child = trash)) return false
        return try {
            val dest = uniqueIn(trash, target.name)
            if (target.renameTo(dest)) true else copyThenDelete(target, dest)
        } catch (e: Exception) {
            Log.e(TAG, "moveToTrash failed: ${target.absolutePath}", e)
            false
        }
    }

    // ==================== 查询 ====================

    /** 列出回收站条目：只含 `.rwmod` 文件与文件夹，按修改时间倒序 */
    fun list(): List<RecycleEntry> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isDirectory || it.extension.equals("rwmod", ignoreCase = true) }
            .map { RecycleEntry(it, it.isDirectory, if (it.isDirectory) dirSize(it) else it.length()) }
            .sortedByDescending { it.lastModified }
    }

    /** 回收站（exports 目录）当前占用字节数 */
    fun sizeBytes(): Long = dirSize(dir)

    // ==================== 还原 / 删除 ====================

    /** 还原条目到**首页目录**；成功返回 null，失败返回原因（用于 UI 提示） */
    @Synchronized
    fun restore(entry: RecycleEntry): String? {
        val src = entry.file
        if (!src.exists()) return "文件已不存在"
        val home = try {
            File(SettingsManager.defaultModPath())
        } catch (e: Exception) {
            Log.e(TAG, "resolve home dir failed", e)
            return "无法定位首页目录"
        }
        if (!home.exists() && !home.mkdirs()) return "无法打开首页目录"
        return try {
            val dest = uniqueIn(home, src.name)
            val ok = if (src.renameTo(dest)) true else copyThenDelete(src, dest)
            if (ok) null else "还原失败"
        } catch (e: Exception) {
            Log.e(TAG, "restore failed: ${src.name}", e)
            "还原失败"
        }
    }

    /** 彻底删除某条目（不可恢复） */
    @Synchronized
    fun deletePermanently(entry: RecycleEntry): Boolean = try {
        val f = entry.file
        if (!f.exists()) true
        else if (f.isDirectory) f.deleteRecursively()
        else f.delete()
    } catch (e: Exception) {
        Log.e(TAG, "deletePermanently failed: ${entry.name}", e)
        false
    }

    /** 清空回收站：清空 exports 目录下的全部内容，保留目录本身 */
    @Synchronized
    fun clear() {
        try {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.e(TAG, "clear failed", e)
        }
    }

    // ==================== 启动清理 ====================

    /**
     * 启动清理：回收站内容超过 [MAX_BYTES] 则整体清空，返回释放的字节数（0 表示未触发）。
     * 由 MainActivity 在后台 IO 协程里调用。
     */
    @Synchronized
    fun purgeIfOversize(): Long {
        val size = sizeBytes()
        if (size <= MAX_BYTES) return 0
        clear()
        Log.i(TAG, "回收站超过阈值，已清空 $size 字节")
        return size
    }

    // ==================== 内部实现 ====================

    /** child 是否位于 ancestor 之内（含相等判定） */
    private fun contains(ancestor: File, child: File): Boolean {
        val a = try { ancestor.canonicalPath } catch (_: Exception) { ancestor.absolutePath }
        val c = try { child.canonicalPath } catch (_: Exception) { child.absolutePath }
        return c == a || c.startsWith(a + File.separator)
    }

    /** 目录递归占用（含子文件）；异常时返回已统计部分 */
    private fun dirSize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        Log.w(TAG, "dirSize failed: ${dir.absolutePath}", e)
        0L
    }

    /** 目标目录内不重名的落点：重名时追加时间戳后缀（不覆盖已有内容） */
    private fun uniqueIn(parent: File, name: String): File {
        val plain = File(parent, name)
        if (!plain.exists()) return plain
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        var candidate = File(parent, "${stem}_$stamp$ext")
        var i = 1
        while (candidate.exists()) {
            candidate = File(parent, "${stem}_${stamp}_$i$ext")
            i++
        }
        return candidate
    }

    /** 同分区 rename 不可用时退化为复制后删除 */
    private fun copyThenDelete(src: File, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
        else src.copyTo(dest, overwrite = true)
        return src.deleteRecursively()
    }
}
