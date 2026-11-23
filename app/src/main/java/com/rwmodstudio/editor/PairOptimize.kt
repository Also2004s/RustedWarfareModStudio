package com.rwmodstudio.editor

import android.graphics.Canvas
import android.graphics.Paint
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 括号配对优化：配对表、待填充区域状态与绘制。
 *
 * 只服务编辑体验（输入法整对提交时光标居中、配对区域内高亮、区域内换行跳到右括号后），
 * 不参与 INI 解析、值切分、参数边界、翻译保护等任何语义逻辑——中文标点在这里只作为
 * 「配对符号」被识别，不会被当作英文符号的等价物。
 */
internal val PAIR_TABLE: List<Pair<Char, Char>> = listOf(
    '(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'',
    '（' to '）', '［' to '］', '｛' to '｝', '【' to '】', '「' to '」',
    '『' to '』', '《' to '》', '〈' to '〉', '〔' to '〕', '〖' to '〗',
    '“' to '”', '‘' to '’'
)

/** 整对提交匹配：文本必须恰好是「左符+右符」 */
internal fun matchCompletePair(text: String): Pair<Char, Char>? {
    if (text.length != 2) return null
    val left = text[0]
    val right = text[1]
    return PAIR_TABLE.firstOrNull { it.first == left && it.second == right }
}

/** 一对刚形成的括号（行列坐标） */
internal data class PendingPair(
    val leftLine: Int,
    val leftColumn: Int,
    val rightLine: Int,
    val rightColumn: Int,
    val leftChar: Char,
    val rightChar: Char
)

/** 光标两侧是否恰好夹着一对配对符（手打自动补充后落在这里） */
internal fun pairAroundCursor(editor: CodeEditor): PendingPair? {
    val cursor = editor.cursor
    if (cursor.isSelected) return null
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (column <= 0) return null
    val content = editor.text
    if (line >= content.lineCount) return null
    if (column >= content.getColumnCount(line)) return null
    val left = content.charAt(line, column - 1)
    val right = content.charAt(line, column)
    val match = PAIR_TABLE.firstOrNull { it.first == left && it.second == right } ?: return null
    return PendingPair(line, column - 1, line, column, match.first, match.second)
}

/** 待填充的配对区域（行列坐标；高亮期间不会跨行） */
internal class PairHighlightState {
    var leftLine: Int = -1
        private set
    var leftColumn: Int = -1
        private set
    var rightLine: Int = -1
        private set
    var rightColumn: Int = -1
        private set
    private var leftChar: Char = '\u0000'
    private var rightChar: Char = '\u0000'

    val isActive: Boolean get() = leftLine >= 0 && rightLine >= 0

    fun set(pair: PendingPair) {
        leftLine = pair.leftLine
        leftColumn = pair.leftColumn
        rightLine = pair.rightLine
        rightColumn = pair.rightColumn
        leftChar = pair.leftChar
        rightChar = pair.rightChar
    }

    fun clear() {
        leftLine = -1
        leftColumn = -1
        rightLine = -1
        rightColumn = -1
        leftChar = '\u0000'
        rightChar = '\u0000'
    }

    /** 光标是否还落在配对中间（右括号之前） */
    fun containsCursor(line: Int, column: Int): Boolean {
        if (!isActive) return false
        if (line < leftLine || line > rightLine) return false
        return when {
            leftLine == rightLine -> column in (leftColumn + 1)..rightColumn
            line == leftLine -> column > leftColumn
            line == rightLine -> column <= rightColumn
            else -> true
        }
    }

    /** 同行插入后平移列；插入换行或跨行则失效 */
    fun applyInsert(startLine: Int, startColumn: Int, inserted: CharSequence) {
        if (!isActive) return
        if (inserted.any { it == '\n' || it == '\r' }) {
            clear()
            return
        }
        if (startLine != leftLine || startLine != rightLine) {
            clear()
            return
        }
        val length = inserted.length
        if (startColumn <= leftColumn) {
            leftColumn += length
            rightColumn += length
        } else if (startColumn <= rightColumn) {
            rightColumn += length
        }
    }

    /** 同行删除后平移列；删除覆盖到括号或跨行则失效 */
    fun applyDelete(startLine: Int, startColumn: Int, deleted: CharSequence) {
        if (!isActive) return
        if (deleted.any { it == '\n' || it == '\r' }) {
            clear()
            return
        }
        if (startLine != leftLine || startLine != rightLine) {
            clear()
            return
        }
        val end = startColumn + deleted.length
        if (end <= leftColumn) {
            leftColumn -= deleted.length
            rightColumn -= deleted.length
        } else if (startColumn > leftColumn && end <= rightColumn) {
            // 只删中间内容：右括号列前移，区域保留
            rightColumn -= deleted.length
        } else {
            // 删到左/右括号本身
            clear()
        }
    }

    /** 内容里左右字符仍匹配才算有效 */
    fun validate(content: Content): Boolean {
        if (!isActive) return false
        if (leftLine >= content.lineCount || rightLine >= content.lineCount) {
            clear()
            return false
        }
        if (leftColumn >= content.getColumnCount(leftLine) || rightColumn >= content.getColumnCount(rightLine)) {
            clear()
            return false
        }
        if (content.charAt(leftLine, leftColumn) != leftChar || content.charAt(rightLine, rightColumn) != rightChar) {
            clear()
            return false
        }
        return true
    }
}

/** 在编辑器 View 坐标里画配对区域背景（取主题选区色，压暗透明度） */
internal fun drawPairHighlight(editor: CodeEditor, canvas: Canvas, state: PairHighlightState) {
    if (!state.isActive) return
    val content = editor.text
    if (state.rightLine >= content.lineCount) return
    val lineLength = content.getColumnCount(state.rightLine)
    val rightEndColumn = (state.rightColumn + 1).coerceAtMost(lineLength)
    val x1 = editor.getCharOffsetX(state.leftLine, state.leftColumn)
    val y1 = editor.getCharOffsetY(state.leftLine, state.leftColumn)
    val x2 = editor.getCharOffsetX(state.rightLine, rightEndColumn)
    val y2 = editor.getCharOffsetY(state.rightLine, rightEndColumn)
    val rowHeight = editor.rowHeight.toFloat()
    if (rowHeight <= 0f) return

    val schemeColor = editor.colorScheme.getColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND)
    val rgb = (schemeColor and 0x00FFFFFF).let { if (it == 0) 0x569CD6 else it }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 保留主题选区色 RGB，只把透明度压到约 16%，避免盖住文字
        color = rgb or (0x29 shl 24)
    }
    val radius = editor.dpUnit * 3f
    if (kotlin.math.abs(y1 - y2) < rowHeight * 0.5f) {
        canvas.drawRoundRect(x1, y1, x2, y1 + rowHeight, radius, radius, paint)
    } else {
        // 跨软换行：首行画到文本区右侧，末行从文本区左侧画到右括号
        val regionLeft = editor.measureTextRegionOffset()
        val regionRight = editor.width.toFloat() - editor.paddingRight
        canvas.drawRect(x1, y1, regionRight, y1 + rowHeight, paint)
        canvas.drawRect(regionLeft, y2, x2, y2 + rowHeight, paint)
    }
}
