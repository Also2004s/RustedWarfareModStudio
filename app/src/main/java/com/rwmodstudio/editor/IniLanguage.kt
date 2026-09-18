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
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "IniLanguage"

/**
 * 一处括号问题（供 UI 跳转与提示使用）。
 * 位置基于扫描时的编辑器显示文本（中文视图即中文缓冲），无需中英列映射。
 */
data class BracketIssue(
    val startIndex: Int,   // 编辑器全文索引
    val line: Int,         // 0-based
    val column: Int,       // 0-based
    val message: String    // 如「第 12 行：「(」未闭合」
)

/**
 * INI 感知的括号配对扫描器：跳过注释（`;`/`#` 至行尾，`#RRGGBB` hex 颜色除外）、
 * `"""` 多行值块、单行引号字符串，再做栈匹配。
 * 交叉错配（如 `(]`）按 VS Code 风格处理：被挤出的中间开括号记「未闭合」，
 * 找不到同类开括号时闭括号记「多余」，避免同一处问题双重报告。
 */
internal object BracketScanner {

    private val OPEN_BRACKETS = setOf('(', '[', '{')
    private val CLOSE_BRACKETS = setOf(')', ']', '}')
    private val MATCHING = mapOf(')' to '(', ']' to '[', '}' to '{')
    private val HEX_CHARS = "0123456789abcdefABCDEF".toSet()

    private const val STATE_NORMAL = 0
    private const val STATE_COMMENT = 1
    private const val STATE_DQUOTE = 2
    private const val STATE_SQUOTE = 3
    private const val STATE_BLOCK = 4

    /** 扫描全文，返回按位置排序的括号问题列表 */
    fun scan(text: String): List<BracketIssue> {
        val issues = mutableListOf<BracketIssue>()
        val stack = ArrayDeque<StackEntry>()
        val lineStarts = mutableListOf(0)
        var state = STATE_NORMAL
        var i = 0
        val n = text.length

        // 按索引反查行号（记录位置可能在当前扫描位置之前的行，如被挤出的开括号/文末栈内条目）
        fun lineOf(index: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lineStarts[mid] <= index) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun record(index: Int, bracket: Char, kind: String) {
            val ln = lineOf(index)
            issues.add(
                BracketIssue(
                    startIndex = index,
                    line = ln,
                    column = index - lineStarts[ln],
                    message = "第 ${ln + 1} 行：「$bracket」$kind"
                )
            )
        }

        fun handleClose(index: Int, c: Char) {
            val expected = MATCHING[c]!!
            if (stack.isEmpty()) {
                record(index, c, "多余")
                return
            }
            if (stack.last().char == expected) {
                stack.removeLast()
                return
            }
            // 自顶向下找同类开括号
            var foundIdx = -1
            for (j in stack.indices.reversed()) {
                if (stack[j].char == expected) {
                    foundIdx = j
                    break
                }
            }
            if (foundIdx >= 0) {
                // 匹配位置之上的开括号记「未闭合」，匹配位置本身静默出栈（与该闭括号配对）
                while (stack.size > foundIdx + 1) {
                    val entry = stack.removeLast()
                    record(entry.index, entry.char, "未闭合")
                }
                stack.removeLast()
            } else {
                // 栈内没有同类开括号：闭括号多余，栈保持不变（开括号可能稍后闭合）
                record(index, c, "多余")
            }
        }

        while (i < n) {
            val c = text[i]
            when (state) {
                STATE_COMMENT -> {
                    if (c == '\n') {
                        state = STATE_NORMAL
                        lineStarts.add(i + 1)
                    }
                    i++
                }
                STATE_BLOCK -> {
                    // 块内只识别闭合 """，其余全部跳过
                    if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                        state = STATE_NORMAL
                        i += 3
                    } else {
                        if (c == '\n') lineStarts.add(i + 1)
                        i++
                    }
                }
                STATE_DQUOTE, STATE_SQUOTE -> {
                    // 单行引号字符串：遇配对引号或换行结束，不跨行
                    val quote = if (state == STATE_DQUOTE) '"' else '\''
                    if (c == quote) {
                        state = STATE_NORMAL
                    } else if (c == '\n') {
                        state = STATE_NORMAL
                        lineStarts.add(i + 1)
                    }
                    i++
                }
                STATE_NORMAL -> {
                    when {
                        c == ';' -> {
                            state = STATE_COMMENT
                            i++
                        }
                        c == '#' -> {
                            // #RRGGBB hex 颜色不算注释（其后同行内容仍需检查括号）
                            if (i + 6 < n && (1..6).all { text[i + it] in HEX_CHARS }) {
                                i += 7
                            } else {
                                state = STATE_COMMENT
                                i++
                            }
                        }
                        c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            state = STATE_BLOCK
                            i += 3
                        }
                        c == '"' -> {
                            state = STATE_DQUOTE
                            i++
                        }
                        c == '\'' -> {
                            state = STATE_SQUOTE
                            i++
                        }
                        c == '\n' -> {
                            lineStarts.add(i + 1)
                            i++
                        }
                        c in OPEN_BRACKETS -> {
                            stack.addLast(StackEntry(i, c))
                            i++
                        }
                        c in CLOSE_BRACKETS -> {
                            handleClose(i, c)
                            i++
                        }
                        else -> i++
                    }
                }
            }
        }
        // 文末：栈内剩余的开括号全部未闭合
        for (entry in stack) {
            record(entry.index, entry.char, "未闭合")
        }
        return issues.sortedBy { it.startIndex }
    }

    /** 把扫描结果转换为编辑器诊断区域（附加中文消息） */
    fun toDiagnosticRegions(issues: List<BracketIssue>): List<DiagnosticRegion> {
        return issues.mapIndexed { idx, issue ->
            DiagnosticRegion(
                issue.startIndex,
                issue.startIndex + 1,
                DiagnosticRegion.SEVERITY_ERROR,
                idx.toLong(),
                DiagnosticDetail(issue.message, null, emptyList(), null)
            )
        }
    }

    private data class StackEntry(val index: Int, val char: Char)
}

/**
 * 取编辑器当前全文快照（供括号诊断扫描使用）。
 *
 * **不要**用 ContentReference.toString()：它走 TextReference.toString()，
 * 按**构造时**固定的长度切片（end 在创建时确定，之后不随文本变化），因此
 * 1) 文本被删短后取快照会抛 StringIndexOutOfBoundsException（诊断被静默吞掉，表现为「删了括号不诊断、角标不消失」）；
 * 2) 文本变长后快照会被截掉尾部同样多的字符（文件末尾内容看不到，可能误报未闭合）。
 * 底层 Content 的 toString() 始终返回当前全文，位置索引也与编辑器一致。
 */
internal fun bracketSnapshot(ref: ContentReference): String = ref.reference.toString()

/**
 * 包装 AnalyzeManager，在 TextMate 高亮分析之上追加括号匹配诊断。
 * 文本变更后短防抖（120ms）在后台线程扫描全文括号配对，结果回主线程
 * 更新诊断容器并回调监听器；文件加载（reset）用更短的 50ms 延迟。
 */
class BracketDiagnosticManager(
    private val delegate: AnalyzeManager
) : AnalyzeManager {

    /** 主线程回调：问题列表（清空时也回调空列表，便于 UI 撤销角标） */
    @Volatile
    var listener: ((List<BracketIssue>) -> Unit)? = null

    private var receiver: StyleReceiver? = null
    private var contentRef: ContentReference? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private var diagnosticContainer: DiagnosticsContainer? = null
    private var scanExecutor = newScanExecutor()
    private val generation = AtomicLong(0)
    @Volatile
    private var destroyed = false
    @Volatile
    private var lastScannedText: String? = null

    /** 已推送给编辑器的诊断列表：与本次结果相同就不再重推，避免每次按键都让编辑器重建诊断并重绘 */
    private var lastPushedIssues: List<BracketIssue>? = null

    private fun newScanExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bracket-diag-scan").apply { isDaemon = true }
    }

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
            // 同一个 IniLanguage 会被反复挂到编辑器上：切 tab / 应用前后台切换时
            // CodeEditor.setEditorLanguage(null) 会对本管理器调用 destroy()，切回来又
            // 重新 setReceiver+reset。若不复位销毁标记，括号诊断会就此永久失效。
            if (destroyed) {
                destroyed = false
                lastScannedText = null
                lastPushedIssues = null
                if (scanExecutor.isShutdown) scanExecutor = newScanExecutor()
            }
            delegate.setReceiver(ReceiverWrapper(receiver))
        } else {
            delegate.setReceiver(null)
        }
    }

    override fun reset(content: ContentReference, extraArguments: Bundle) {
        contentRef = content
        // 装载/重建后编辑器侧诊断会被清空（setEditorLanguage 置 diagnostics = null），
        // 因此即使文本没变也要重扫并重推一次，否则角标/下划线不再出现。
        lastScannedText = null
        lastPushedIssues = null
        delegate.reset(content, extraArguments)
        // 文件加载/语言重置：短延迟尽快出结果
        scheduleBracketCheck(RESET_DELAY_MS)
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
        destroyed = true
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        scanExecutor.shutdownNow()
        receiver = null
        contentRef = null
        diagnosticContainer = null
        lastScannedText = null
        lastPushedIssues = null
        delegate.destroy()
    }

    /**
     * 防抖后在后台线程扫描括号。主线程仅做文本快照（与旧实现成本相同），
     * 扫描本身交给单线程执行器，结果经主线程 Handler 回传；
     * generation 计数丢弃过期结果，避免旧文本的诊断覆盖新文本。
     */
    private fun scheduleBracketCheck(delayMs: Long = CHECK_DELAY_MS) {
        checkRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { runBracketCheck() }
        checkRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun runBracketCheck() {
        checkRunnable = null
        if (destroyed) return
        val ref = contentRef ?: return
        val rcv = receiver ?: return
        try {
            // 快照必须取「当前全文」：ContentReference.toString() 按构造时的固定长度切片
            // （见 bracketSnapshot），文本被删短后取快照会抛异常，正是「删除后不再诊断」的根因。
            val snapshot = bracketSnapshot(ref)
            // 与上次相同且已推送过则跳过重扫（rerun 无变更时省一次全文扫描）
            if (snapshot == lastScannedText && lastPushedIssues != null) return
            val gen = generation.incrementAndGet()
            scanExecutor.execute {
                val issues = try {
                    BracketScanner.scan(snapshot)
                } catch (e: Exception) {
                    Log.e(TAG, "Bracket scan failed", e)
                    return@execute
                }
                handler.post {
                    // 过期结果或已销毁：丢弃
                    if (destroyed || gen != generation.get()) return@post
                    try {
                        // 每次结果变化都推一个「新的」诊断容器：sora 只把
                        // EditorStyleDelegate 汇总出的合并容器挂到编辑器（attachEditor 仅在
                        // CodeEditor 内部调用），就地增删自建容器里的诊断项不会再被编辑器看到，
                        // 诊断标记会永远停在第一次扫描的结果上（角标与下划线都对不上）。
                        if (lastPushedIssues != issues) {
                            val container = DiagnosticsContainer()
                            val regions = BracketScanner.toDiagnosticRegions(issues)
                            if (regions.isNotEmpty()) {
                                container.addDiagnostics(regions)
                            }
                            diagnosticContainer = container
                            rcv.setDiagnostics(this, container)
                            lastPushedIssues = issues
                        }
                        lastScannedText = snapshot
                        listener?.invoke(issues)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bracket check failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bracket check schedule failed", e)
        }
    }

    companion object {
        private const val CHECK_DELAY_MS = 120L
        private const val RESET_DELAY_MS = 50L
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
 * 序号/参数模板键补全项：插入后把光标停在**占位符空位**处，用户直接敲数字即成完整键。
 *
 * 例：核心节输入 `builtFrom_` 选中后插入 `builtFrom__name:`，光标落在 `builtFrom_` 之后
 * （`cursorOffset` = 10），敲 `1` 得到 `builtFrom_1_name:`，不用回头找位置。
 * 已填满占位符的项不走这里（cursorOffset < 0，用 SimpleCompletionItem，光标落行尾即为期望位置）。
 */
private class KeyTemplateCompletionItem(
    label: CharSequence,
    desc: CharSequence?,
    prefixLength: Int,
    private val commitText: String,
    private val cursorOffset: Int
) : CompletionItem(label, desc, null) {

    init {
        this.prefixLength = prefixLength
    }

    override fun performCompletion(editor: io.github.rosemoe.sora.widget.CodeEditor, text: Content, line: Int, column: Int) {
        val startColumn = column - this.prefixLength
        text.replace(line, startColumn, line, column, commitText)
        val caret = (startColumn + cursorOffset).coerceIn(startColumn, startColumn + commitText.length)
        editor.setSelection(line, caret)
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

    /** 括号问题回调（主线程），由 BracketDiagnosticManager 触发，供编辑器 UI 显示角标/跳转 */
    @Volatile
    var bracketIssuesListener: ((List<BracketIssue>) -> Unit)? = null

    private var bracketDiagnosticManager: BracketDiagnosticManager? = null

    override fun getAnalyzeManager(): AnalyzeManager {
        if (!bracketDiagnosticsEnabled) {
            return delegate.analyzeManager
        }
        if (bracketDiagnosticManager == null) {
            bracketDiagnosticManager = BracketDiagnosticManager(delegate.analyzeManager).also { manager ->
                manager.listener = { issues -> bracketIssuesListener?.invoke(issues) }
            }
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
            val baseItem = when {
                item.type == CompletionProvider.CompletionType.SECTION ->
                    SectionCompletionItem(label, item.detail, prefixLength, commit)
                // 模板键留空了占位符 → 插入后把光标定位到空位处（老板决策：敲数字即成品）
                item.cursorOffset >= 0 ->
                    KeyTemplateCompletionItem(label, item.detail, itemPrefixLength, commit, item.cursorOffset)
                else ->
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
