package com.rwmodstudio.core

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** 一次只读加载的结果 */
data class LoadedTextFile(
    /** 实际用于显示的文本（超大文件时为前半段） */
    val text: String,
    /** 文件总大小 */
    val totalBytes: Long,
    /** 本次实际读取的字节数 */
    val loadedBytes: Long,
    /** 是否因体积保护而没有读全 */
    val truncated: Boolean,
    /** 实际采用的编码名（便于排查乱码） */
    val charsetName: String
)

/**
 * 文本文件的只读加载器。
 *
 * 保护策略：
 * - 调用方传入 [maxBytes]，超过的文件只读取前 [maxBytes] 字节；
 * - 截断读取后尽量在最后一个换行处收尾，避免把多字节字符/一行切成两半；
 * - 编码按 BOM 判断 UTF-8 / UTF-16，其余先按 UTF-8 解，出现替换符再尝试 GBK。
 */
object TextFileLoader {

    fun load(file: File, maxBytes: Long): LoadedTextFile {
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val totalBytes = file.length()
        val limit = minOf(totalBytes, maxBytes)
        val buffer = ByteArray(limit.toInt())
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
        }
        val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)
        val truncated = read.toLong() < totalBytes

        var charset = detectCharset(bytes)
        var text = String(bytes, charset)
        // UTF-8 BOM 会保留成 \uFEFF，显示时去掉
        if (text.startsWith('\uFEFF')) text = text.substring(1)

        // 中文日志 / CSV 常见 GBK：UTF-8 解码出现替换符时择优
        if (charset == StandardCharsets.UTF_8) {
            val badCount = text.count { it == '\uFFFD' }
            if (badCount > 0) {
                val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
                if (gbk != null) {
                    val gbkText = runCatching { String(bytes, gbk) }.getOrNull()
                    if (gbkText != null && gbkText.count { it == '\uFFFD' } < badCount) {
                        text = gbkText
                        charset = gbk
                    }
                }
            }
        }

        // 截断读取：优先在最后一个换行处收尾（至少保留一半，避免极端情况下显示为空）
        if (truncated) {
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline > text.length / 2) text = text.substring(0, lastNewline)
        }

        return LoadedTextFile(
            text = text,
            totalBytes = totalBytes,
            loadedBytes = read.toLong(),
            truncated = truncated,
            charsetName = charset.displayName()
        )
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // UTF-16 BOM：StandardCharsets.UTF_16 会依据 BOM 选择字节序并去掉 BOM
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return StandardCharsets.UTF_16
            }
        }
        return StandardCharsets.UTF_8
    }
}
