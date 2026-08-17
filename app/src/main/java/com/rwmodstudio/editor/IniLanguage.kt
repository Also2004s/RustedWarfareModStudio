package com.rwmodstudio.editor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rwmodstudio.feature.completion.CompletionProvider
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch

private const val TAG = "IniLanguage"

/**
 * 包装 AnalyzeManager，在 TextMate 高亮分析之上追加括号匹配诊断。
 * 文本变更后防抖检查整段文本的括号配对，未匹配的括号通过编辑器诊断系统标记。
 */
class BracketDiagnosticManager(
    private val delegate: AnalyzeManager
) : AnalyzeManager {

    private var receiver: StyleReceiver? = null
    private var contentRef: ContentReference? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private var diagnosticContainer: DiagnosticsContainer? = null

    /**
     * 包装 StyleReceiver，将 TextMate 分析器发出的 sourceManager 替换为本包装器，
     * 使 CodeEditor 能正确识别样式来源，避免高亮丢失。
     */
    private inner class ReceiverWrapper(
        private val original: StyleReceiver
    ) : StyleReceiver {
        override fun setStyles(sourceManager: AnalyzeManager, styles: Styles?) {
            original.setStyles(this@BracketDiagnosticManager, styles)
        }
        override fun setStyles(sourceManager: AnalyzeManager, styles: Styles?, action: Runnable?) {
            original.setStyles(this@BracketDiagnosticManager, styles, action)
        }
        override fun setDiagnostics(sourceManager: AnalyzeManager, diagnostics: DiagnosticsContainer?) {
            original.setDiagnostics(this@BracketDiagnosticManager, diagnostics)
        }
        override fun setInlayHints(sourceManager: AnalyzeManager, inlayHints: InlayHintsContainer?) {
            original.setInlayHints(this@BracketDiagnosticManager, inlayHints)
        }
        override fun updateBracketProvider(sourceManager: AnalyzeManager, provider: BracketsProvider?) {
            original.updateBracketProvider(this@BracketDiagnosticManager, provider)
        }
    }

    override fun setReceiver(receiver: StyleReceiver?) {
        this.receiver = receiver
        if (receiver != null) {
            delegate.setReceiver(ReceiverWrapper(receiver))
        } else {
            delegate.setReceiver(null)
        }
    }

    override fun reset(content: ContentReference, extraArguments: Bundle) {
        contentRef = content
        delegate.reset(content, extraArguments)
        scheduleBracketCheck()
    }

    override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
        delegate.insert(start, end, insertedContent)
        scheduleBracketCheck()
    }

    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
        delegate.delete(start, end, deletedContent)
        scheduleBracketCheck()
    }

    override fun rerun() {
        delegate.rerun()
        scheduleBracketCheck()
    }

    override fun destroy() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        receiver = null
        contentRef = null
        diagnosticContainer = null
        delegate.destroy()
    }

    private fun scheduleBracketCheck() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        val ref = contentRef ?: return
        val rcv = receiver ?: return
        val runnable = Runnable {
            try {
                val text = ref.toString()
                val regions = checkBrackets(text)
                // 复用持久化容器：首次创建并注册到编辑器，后续直接增删诊断项
                if (diagnosticContainer == null) {
                    diagnosticContainer = DiagnosticsContainer()
                    rcv.setDiagnostics(this, diagnosticContainer)
                }
                diagnosticContainer!!.reset()
                if (regions.isNotEmpty()) {
                    diagnosticContainer!!.addDiagnostics(regions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bracket check failed", e)
            }
        }
        checkRunnable = runnable
        handler.postDelayed(runnable, 300)
    }

    companion object {
        private val OPEN_BRACKETS = setOf('(', '[', '{')
        private val CLOSE_BRACKETS = setOf(')', ']', '}')
        private val MATCHING = mapOf(')' to '(', ']' to '[', '}' to '{')

        /**
         * 检查文本中的括号配对，返回未匹配括号的诊断区域列表。
         * 支持 ()、[]、{} 三种括号，使用栈进行匹配。
         */
        fun checkBrackets(text: String): List<DiagnosticRegion> {
            val regions = mutableListOf<DiagnosticRegion>()
            val stack = ArrayDeque<Int>()
            for (i in text.indices) {
                val c = text[i]
                if (c in OPEN_BRACKETS) {
                    stack.addLast(i)
                } else if (c in CLOSE_BRACKETS) {
                    val expected = MATCHING[c]!!
                    if (stack.isEmpty() || text[stack.last()] != expected) {
                        regions.add(DiagnosticRegion(i, i + 1, DiagnosticRegion.SEVERITY_ERROR))
                    } else {
                        stack.removeLast()
                    }
                }
            }
            for (idx in stack) {
                regions.add(DiagnosticRegion(idx, idx + 1, DiagnosticRegion.SEVERITY_ERROR))
            }
            return regions
        }
    }
}

/**
 * 节补全项，会同时删除光标后面已存在的 `]`，避免符号对自动补全导致多出一个 `]`。
 */
private class SectionCompletionItem(
    label: CharSequence,
    desc: CharSequence?,
    prefixLength: Int,
    private val commitText: String
) : CompletionItem(label, desc, null) {

    init {
        this.prefixLength = prefixLength
    }

    override fun performCompletion(editor: io.github.rosemoe.sora.widget.CodeEditor, text: Content, line: Int, column: Int) {
        var endColumn = column
        // 如果光标后紧跟 ]，把终点延伸到 ] 之后一并删除
        if (column < text.getColumnCount(line) && text.charAt(line, column) == ']') {
            endColumn = column + 1
        }
        val startColumn = column - this.prefixLength
        text.replace(line, startColumn, line, endColumn, commitText)
        // 光标停在 ] 前面，方便继续输入节名后缀（如 _1）
        editor.setSelection(line, startColumn + commitText.length - 1)
    }
}

/**
 * 包装 TextMateLanguage，保留 INI 语法高亮，并接入项目自定义补全。
 */
class IniLanguage(private val delegate: TextMateLanguage) : EmptyLanguage() {

    @Volatile
    var completionProvider: CompletionProvider? = null

    @Volatile
    var currentSectionName: String? = null

    @Volatile
    var sectionFilters: Map<String, Set<String>> = emptyMap()

    @Volatile
    var sectionCompletionEnabled: Boolean = false

    @Volatile
    var bracketDiagnosticsEnabled: Boolean = false

    private var bracketDiagnosticManager: BracketDiagnosticManager? = null

    override fun getAnalyzeManager(): AnalyzeManager {
        if (!bracketDiagnosticsEnabled) {
            return delegate.analyzeManager
        }
        if (bracketDiagnosticManager == null) {
            bracketDiagnosticManager = BracketDiagnosticManager(delegate.analyzeManager)
        }
        return bracketDiagnosticManager!!
    }

    override fun getInterruptionLevel(): Int = delegate.interruptionLevel

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val provider = completionProvider
        if (provider == null) {
            return
        }

        val index = content.getCharIndex(position.line, position.column)
        if (index < 0 || index > content.length) {
            return
        }

        val fullText = content.toString()
        val textBeforeCursor = fullText.substring(0, index.coerceAtMost(fullText.length))
        val textAfterCursor = fullText.substring(index.coerceAtMost(fullText.length))
        val items = try {
            provider.getCompletions(
                textBeforeCursor,
                textAfterCursor,
                index,
                currentSectionName,
                sectionFilters,
                sectionCompletionEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "getCompletions failed", e)
            emptyList()
        }
        Log.d("SoraAdmin", "requireAutoComplete items=${items.size} textBefore=${textBeforeCursor.takeLast(25).replace("\n", "\\n")}")

        if (items.isEmpty()) return

        val isSectionCompletion = items.firstOrNull()?.type == CompletionProvider.CompletionType.SECTION
        val wordStart = if (isSectionCompletion) findSectionWordStart(textBeforeCursor) else computeWordStart(textBeforeCursor, index)
        val prefixLength = index - wordStart

        publisher.addItems(items.map { item ->
            val label = item.label
            val commit = item.insertText.ifEmpty { item.label }
            // 值补全只替换 `:` 后已输入的内容，对 VALUE 类型始终使用 valuePrefixLength
            val itemPrefixLength = if (item.type == CompletionProvider.CompletionType.VALUE) {
                item.valuePrefixLength
            } else {
                prefixLength
            }
            val baseItem = if (item.type == CompletionProvider.CompletionType.SECTION) {
                SectionCompletionItem(label, item.detail, prefixLength, commit)
            } else {
                SimpleCompletionItem(label, item.detail, itemPrefixLength, commit)
            }
            baseItem.apply {
                kind = when (item.type) {
                    CompletionProvider.CompletionType.SECTION -> CompletionItemKind.Class
                    CompletionProvider.CompletionType.KEY -> CompletionItemKind.Property
                    CompletionProvider.CompletionType.VALUE -> CompletionItemKind.Value
                    CompletionProvider.CompletionType.TEMPLATE -> CompletionItemKind.Snippet
                }
            }
        })

        // 确保数量较少时也能立即刷新
        publisher.updateList()
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        delegate.getIndentAdvance(content, line, column)

    override fun useTab(): Boolean = delegate.useTab()

    override fun getFormatter(): Formatter = delegate.formatter

    override fun getSymbolPairs(): SymbolPairMatch = delegate.symbolPairs

    override fun getNewlineHandlers(): Array<NewlineHandler>? = delegate.newlineHandlers

    override fun destroy() {
        delegate.destroy()
    }

    private fun computeWordStart(textBeforeCursor: String, cursor: Int): Int {
        var start = cursor
        while (start > 0) {
            val ch = textBeforeCursor[start - 1]
            // 与 CompletionProvider 的前缀截断逻辑保持一致，遇到换行/空格/触发符号即停止，
            // 否则会把 %{ 等符号前的整段内容都算入替换范围，导致自动补全覆盖前方文本。
            if (ch == '\n' || ch == ' ' || ch in CompletionProvider.AUTO_COMPLETE_TRIGGER_CHARS) break
            start--
        }
        return start
    }

    /**
     * 节补全场景下，把替换起点定位到 [，使 commit 文本 [节名] 能完整替换 [前缀 或 []。
     * 返回的是 [ 在 textBeforeCursor 中的索引（与 editor 的绝对索引一致）。
     */
    private fun findSectionWordStart(textBeforeCursor: String): Int {
        var pos = textBeforeCursor.length - 1
        while (pos >= 0 && textBeforeCursor[pos] != '[') {
            pos--
        }
        return pos.coerceAtLeast(0)
    }
}
