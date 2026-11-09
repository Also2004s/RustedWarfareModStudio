package com.rwmodstudio.core

/**
 * 文件浏览器「只读查看」支持的文本格式。
 *
 * 注意：这里只描述“能否用只读查看器打开”，不改变编辑器既有打开路径
 * （`.ini` / `.template` / `.txt` / `.md` 在浏览器里仍走编辑器）。
 */
enum class TextViewerFormat(val label: String) {
    JSON("JSON"),
    JSONC("JSONC"),
    XML("XML"),
    YAML("YAML"),
    CSV("CSV"),
    TSV("TSV"),
    MARKDOWN("Markdown"),
    LOG("日志"),
    PROPERTIES("Properties"),
    PLAIN("文本");

    /** 是否支持「格式化/美化」 */
    val canFormat: Boolean get() = this == JSON || this == JSONC
}

/** 文本查看器的格式识别与容量保护阈值 */
object TextFileFormats {
    /** 小于等于该大小自动完整加载 */
    const val AUTO_LOAD_LIMIT_BYTES = 2L * 1024 * 1024

    /** 超过自动加载上限时，默认只预览的文件大小 */
    const val PARTIAL_LOAD_BYTES = 1L * 1024 * 1024

    /** 用户手动「加载全部」的硬上限，超过直接拒绝，避免 OOM */
    const val HARD_LOAD_LIMIT_BYTES = 8L * 1024 * 1024

    /** 单行显示上限：超出部分只截断“显示”，复制仍使用完整内容 */
    const val MAX_DISPLAY_LINE_CHARS = 4000

    private val EXTENSION_TO_FORMAT = mapOf(
        "json" to TextViewerFormat.JSON,
        "jsonc" to TextViewerFormat.JSONC,
        "xml" to TextViewerFormat.XML,
        "yaml" to TextViewerFormat.YAML,
        "yml" to TextViewerFormat.YAML,
        "csv" to TextViewerFormat.CSV,
        "tsv" to TextViewerFormat.TSV,
        "md" to TextViewerFormat.MARKDOWN,
        "markdown" to TextViewerFormat.MARKDOWN,
        "log" to TextViewerFormat.LOG,
        "properties" to TextViewerFormat.PROPERTIES,
        "txt" to TextViewerFormat.PLAIN
    )

    /** 支持只读查看的扩展名（小写，不含点） */
    val viewableExtensions: Set<String> get() = EXTENSION_TO_FORMAT.keys

    /** 按文件名（或纯扩展名）识别格式；不支持时返回 null */
    fun formatOf(fileName: String): TextViewerFormat? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_FORMAT[ext]
    }

    fun isViewable(fileName: String): Boolean = formatOf(fileName) != null

    /** 文件大小的人类可读描述 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
