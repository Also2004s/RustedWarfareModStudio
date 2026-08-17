package io.github.rosemoe.sora.langs.textmate

import android.util.Log
import com.rwmodstudio.editor.RAINBOW_BRACKET_1
import com.rwmodstudio.editor.RAINBOW_BRACKET_2
import com.rwmodstudio.editor.RAINBOW_BRACKET_3
import com.rwmodstudio.editor.RAINBOW_BRACKET_4
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import kotlin.math.min
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration

/**
 * 在 TextMate 高亮结果之上追加「彩虹括号」效果（仅处理圆括号）。
 *
 * 每逻辑行独立计算嵌套深度：遇到开括号深度 +1，闭括号深度 -1。
 * 所有 `()` 都按嵌套深度循环上色，最外层使用主题默认括号颜色，内层由此渐变推导；
 * 未匹配的单个 `)` 使用颜色1。注释 / 字符串内的括号不做处理。
 */
class RainbowTextMateAnalyzer(
    language: TextMateLanguage,
    grammar: IGrammar,
    configuration: LanguageConfiguration?,
    themeRegistry: ThemeRegistry
) : TextMateAnalyzer(language, grammar, configuration, themeRegistry) {

    companion object {
        private val OPEN_BRACKETS = setOf('(')
        private val CLOSE_BRACKETS = setOf(')')
        private val BRACKETS = OPEN_BRACKETS + CLOSE_BRACKETS

        private val RAINBOW_COLOR_IDS = intArrayOf(
            RAINBOW_BRACKET_1,
            RAINBOW_BRACKET_2,
            RAINBOW_BRACKET_3,
            RAINBOW_BRACKET_4
        )

        // TextMate StandardTokenType
        private const val TOKEN_COMMENT = 1
        private const val TOKEN_STRING = 2
    }

    override fun tokenizeLine(
        lineC: CharSequence,
        state: MyState?,
        lineIndex: Int
    ): LineTokenizeResult<MyState, Span> {
        val result = super.tokenizeLine(lineC, state, lineIndex)
        try {
            val newSpans = makeRainbowSpans(lineC, result.spans)
            if (newSpans != null) {
                result.spans = newSpans
            }
        } catch (e: Throwable) {
            // 兜底：彩虹括号处理失败时保持原始高亮，避免阻断编辑器
            Log.e("RainbowTextMateAnalyzer", "Failed to apply rainbow brackets at line $lineIndex", e)
        }
        return result
    }

    /**
     * 在原 spans 基础上拆分出彩虹括号 span。
     * 返回 null 表示无需改动（空行、空 spans 等）。
     */
    private fun makeRainbowSpans(lineC: CharSequence, originalSpans: List<Span>?): List<Span>? {
        if (originalSpans.isNullOrEmpty()) {
            return null
        }

        val lineLen = lineC.length
        if (lineLen <= 0) {
            return null
        }

        val newSpans = mutableListOf<Span>()
        var depth = 0

        for (spanIndex in originalSpans.indices) {
            val span = originalSpans[spanIndex]
            val startCol = span.column.coerceIn(0, lineLen)
            val nextCol = if (spanIndex + 1 < originalSpans.size) {
                originalSpans[spanIndex + 1].column
            } else {
                lineLen
            }
            val endCol = min(nextCol, lineLen)
            if (startCol >= endCol) continue

            // 注释 / 字符串内的括号保持原样
            val tokenType = span.extra as? Int
            if (tokenType == TOKEN_COMMENT || tokenType == TOKEN_STRING) {
                newSpans.add(copySpan(span, startCol))
                continue
            }

            var segStart = startCol
            var i = startCol
            while (i < endCol) {
                val ch = lineC[i]
                if (ch in BRACKETS) {
                    if (segStart < i) {
                        newSpans.add(copySpan(span, segStart))
                    }

                    val (newStyle, newExtra, newUnderline) = when {
                        ch in OPEN_BRACKETS -> {
                            depth++
                            val colorId = RAINBOW_COLOR_IDS[(depth - 1).mod(RAINBOW_COLOR_IDS.size)]
                            Triple(
                                makeRainbowStyle(span, colorId),
                                span.extra,
                                span.underlineColor
                            )
                        }
                        depth > 0 -> {
                            val colorId = RAINBOW_COLOR_IDS[(depth - 1).mod(RAINBOW_COLOR_IDS.size)]
                            val style = makeRainbowStyle(span, colorId)
                            depth--
                            Triple(style, span.extra, span.underlineColor)
                        }
                        else -> {
                            // 未匹配的 ) 使用颜色1
                            Triple(
                                makeRainbowStyle(span, RAINBOW_COLOR_IDS[0]),
                                span.extra,
                                span.underlineColor
                            )
                        }
                    }

                    newSpans.add(
                        SpanFactory.obtain(i, newStyle).apply {
                            setExtra(newExtra)
                            newUnderline?.let { setUnderlineColor(it) }
                        }
                    )
                    segStart = i + 1
                }
                i++
            }
            if (segStart < endCol) {
                newSpans.add(copySpan(span, segStart))
            }
        }

        return if (newSpans.isEmpty()) null else newSpans
    }

    private fun copySpan(span: Span, column: Int): Span {
        return span.copy().apply {
            setColumn(column)
            setExtra(span.extra)
            span.underlineColor?.let { setUnderlineColor(it) }
        }
    }

    private fun makeRainbowStyle(span: Span, colorId: Int): Long {
        val oldStyle = span.style
        return TextStyle.makeStyle(
            colorId,
            TextStyle.getBackgroundColorId(oldStyle),
            TextStyle.isBold(oldStyle),
            TextStyle.isItalics(oldStyle),
            TextStyle.isStrikeThrough(oldStyle),
            TextStyle.isNoCompletion(oldStyle)
        )
    }
}
