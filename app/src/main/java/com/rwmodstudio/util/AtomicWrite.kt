package com.rwmodstudio.util

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 原子写文本：先写同目录临时文件，再 rename 到目标，避免「写一半（进程被杀/磁盘满）导致目标文件损坏」。
 * 同一文件系统内 rename 是原子的（旧文件或新文件二选一，不会半新半旧）；
 * renameTo 失败（跨文件系统等罕见情况）时回退到 copy + delete。
 */
// 按路径加锁：atomicWriteText 使用固定临时文件名 $name.tmp，**同一路径**并发写会互相截断同一 tmp
// 拼出混合内容再 rename 落盘——因此同一路径必须串行。
// 不同路径的写盘互相独立，可并行（查重/翻译缓存预热等并行任务各自写输出文件）。
// 若用单一全局锁，所有写盘被串行化，并行任务（查重多线程、预热并行）会性能退化。
// 锁对象按路径缓存在 map 中（不 remove：remove 会引入「新锁对象」与「等待旧锁的线程」并行的窗口，
// 反而重新产生同路径并发；路径数量有限，锁对象内存开销可忽略）。
private val pathLocks = ConcurrentHashMap<String, Any>()

private fun lockFor(path: String): Any = pathLocks.computeIfAbsent(path) { Any() }

fun File.atomicWriteText(content: String, charset: Charset = Charsets.UTF_8) {
    val abs = absolutePath
    synchronized(lockFor(abs)) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(content, charset)
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }
}
