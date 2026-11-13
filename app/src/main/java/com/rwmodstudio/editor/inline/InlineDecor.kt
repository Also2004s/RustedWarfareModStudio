package com.rwmodstudio.editor.inline

import com.rwmodstudio.core.media.isAudioFileName
import java.io.File

/** 内联装饰类型：色块 / 图片缩略图 / 音频播放 / 文本文件跳转 */
enum class InlineDecorKind { COLOR, IMAGE, AUDIO, FILE }

/**
 * 行内一处装饰（值旁边的方块）。
 *
 * [startCol]/[endCol] 是**值在行内的列范围**（endCol 为开区间），用于：
 * 1. 定位 hint 的插入列（取 endCol + CharacterSide.RIGHT）；
 * 2. 色块调色后按原范围替换色值文本。
 *
 * [raw] 保留原始值文本（不含引号/后缀），替换前用它校验该位置内容是否还是原来的值，
 * 防止用户中途改动后误替换。
 */
data class InlineDecor(
    val kind: InlineDecorKind,
    val startCol: Int,
    val endCol: Int,
    val raw: String,
    /** COLOR 专用：解析后的 ARGB */
    val argb: Int = 0,
    /** IMAGE/AUDIO/FILE 专用：解析后的绝对路径，null 表示不可解析（如 SHARED:/CORE: 内置资源） */
    val resolvedPath: String? = null
)

/**
 * 行内装饰扫描器：纯函数，按行文本识别四类装饰。
 *
 * 识别口径与补全/语法保持一致：
 * - 颜色：#RRGGBB / #RRGGBBAA（与 ini.tmLanguage.json 的 constant.other.color.ini 同一条正则）
 * - 图片：值以图片扩展名结尾（贴花多值里的 `xxx.png*3` 会剥掉 `*3`）
 * - 音频：值满足 [isAudioFileName]
 * - 文本文件：值以 .ini / .template / .md 结尾（图片、音频不加跳转）
 *
 * 注释行（# / ; 开头）与节行（[xxx]）整行跳过：行首 # 是注释，不是颜色值。
 */
object InlineDecorScanner {

    private val COLOR_REGEX = Regex("""#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})(?![0-9A-Fa-f])""")
    /** 值区分词：逗号/空白/分号分隔（贴花多值用逗号） */
    private val VALUE_TOKEN = Regex("""[^\s,;]+""")

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "webp", "gif")
    private val TEXT_FILE_EXTS = setOf("ini", "template", "md")

    /**
     * 扫描一行的装饰。纯 CPU 计算，可在任意线程调用。
     *
     * @param text 行文本（不含换行符）
     * @param fileDir 当前编辑文件所在目录（相对路径以它为准）
     * @param projectRoot 项目根（ROOT:/ 前缀与兜底查找用）
     */
    fun scanLine(text: String, fileDir: String, projectRoot: String): List<InlineDecor> {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("[")) {
            return emptyList()
        }
        val result = mutableListOf<InlineDecor>()

        // 值区起点：首个冒号之后（RW ini 的键值对都是 key: value）
        val colon = text.indexOf(':')
        val valueStart = if (colon >= 0) colon + 1 else -1

        // 颜色：只认值区内的（避免键名/注释里的 # 被误判）
        COLOR_REGEX.findAll(text).forEach { m ->
            if (valueStart >= 0 && m.range.first >= valueStart) {
                result += InlineDecor(
                    kind = InlineDecorKind.COLOR,
                    startCol = m.range.first,
                    endCol = m.range.last + 1,
                    raw = m.value,
                    argb = parseHexColor(m.value)
                )
            }
        }

        if (valueStart >= 0) {
            val valuePart = text.substring(valueStart)
            VALUE_TOKEN.findAll(valuePart).forEach { m ->
                val token = m.value
                // 颜色已单独处理
                if (token.startsWith("#")) return@forEach
                val start = valueStart + m.range.first
                val end = valueStart + m.range.last + 1
                val clean = normalizeToken(token)
                if (clean.isEmpty()) return@forEach
                val ext = clean.substringAfterLast('.', "").lowercase()
                when {
                    // 四类装饰都只在能解析到真实文件时才生成：找不到的路径不显示任何图标
                    // （SHARED:/CORE: 是游戏内置资源，App 内没有原文件，同样不显示）
                    ext in IMAGE_EXTS -> resolvePath(clean, fileDir, projectRoot)?.let { resolved ->
                        result += InlineDecor(
                            kind = InlineDecorKind.IMAGE,
                            startCol = start, endCol = end, raw = clean, resolvedPath = resolved
                        )
                    }

                    isAudioFileName(clean) -> resolvePath(clean, fileDir, projectRoot)?.let { resolved ->
                        result += InlineDecor(
                            kind = InlineDecorKind.AUDIO,
                            startCol = start, endCol = end, raw = clean, resolvedPath = resolved
                        )
                    }

                    ext in TEXT_FILE_EXTS -> resolvePath(clean, fileDir, projectRoot)?.let { resolved ->
                        result += InlineDecor(
                            kind = InlineDecorKind.FILE,
                            startCol = start, endCol = end, raw = clean, resolvedPath = resolved
                        )
                    }
                }
            }
        }
        return result.sortedBy { it.startCol }
    }

    /** 去掉包裹的引号/括号与贴花多值的 `*数字` 后缀 */
    private fun normalizeToken(token: String): String {
        var t = token.trim()
        t = t.trim('"', '\'', '(', ')', '[', ']', '{', '}', '<', '>')
        val star = t.indexOf('*')
        if (star > 0) t = t.substring(0, star)
        return t.trim()
    }

    /**
     * 解析值为绝对路径。SHARED:/CORE: 是游戏内置资源，App 内没有原文件，返回 null。
     * 相对路径先按当前文件目录找，找不到再按项目根兜底。
     */
    private fun resolvePath(raw: String, fileDir: String, projectRoot: String): String? {
        val v = raw.trim().trim('"').replace('\\', '/')
        if (v.isEmpty()) return null
        val lower = v.lowercase()
        if (lower.startsWith("shared:") || lower.startsWith("core:")) return null
        val candidates = if (lower.startsWith("root:")) {
            listOf(File(projectRoot, v.substringAfter(':').trimStart('/')))
        } else if (v.startsWith("/") || v.matches(Regex("^[A-Za-z]:/.*"))) {
            listOf(File(v))
        } else {
            listOfNotNull(
                File(fileDir, v),
                projectRoot.takeIf { it.isNotBlank() }?.let { File(it, v) }
            )
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    /** #RRGGBB → 不透明；#RRGGBBAA → 末尾两位为透明度（RW 的 8 位写法是 RRGGBBAA） */
    fun parseHexColor(hex: String): Int {
        val h = hex.removePrefix("#")
        return try {
            when (h.length) {
                8 -> {
                    val r = h.substring(0, 2).toInt(16)
                    val g = h.substring(2, 4).toInt(16)
                    val b = h.substring(4, 6).toInt(16)
                    val a = h.substring(6, 8).toInt(16)
                    ((if (a == 0) 255 else a) shl 24) or (r shl 16) or (g shl 8) or b
                }

                6 -> (0xFF shl 24) or h.toInt(16)
                else -> 0
            }
        } catch (_: NumberFormatException) {
            0
        }
    }
}
