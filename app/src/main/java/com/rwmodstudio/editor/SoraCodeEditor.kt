package com.rwmodstudio.editor

import android.content.Context
import android.graphics.Matrix
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rwmodstudio.R
import com.rwmodstudio.core.DarkThemeColors
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.translation.BehaviorVerifier
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.ui.theme.editorTypeface
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SideIconClickEvent
import io.github.rosemoe.sora.event.Unsubscribe
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.line.LineSideIcon
import io.github.rosemoe.sora.langs.textmate.RainbowTextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SmartWrapCodeEditor
import io.github.rosemoe.sora.widget.DirectAccessProps
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

private const val TAG = "SoraCodeEditor"
private const val LIGHTBULB_REFRESH_DELAY_MS = 300L
private const val SIDE_ICON_SIZE_FACTOR = 0.85f
private const val EDITOR_TAB_WIDTH = 4

/**
 * 快捷符号自定义光标标记：符号输出值中出现该字符时，插入后会把光标停在此处，
 * 并在插入时移除该标记；未包含该标记时默认把光标停在插入内容的末尾。
 */
const val CURSOR_MARKER = '≡'

/**
 * 底部工具栏动作。所有动作通过 [onToolbarAction] 回调到宿主 Screen 处理。
 */
enum class EditorToolbarAction {
    EDIT_SYMBOLS,
    UNDO,
    REDO,
    HINT,
    REFERENCE,
    AT_REFERENCE,
    SEARCH,
    MORE
}

@Composable
fun SoraCodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    autoWrap: Boolean = true,
    smartWrap: Boolean = true,
    fontFamily: String = SettingsManager.editorFontFamily,
    isDarkTheme: Boolean = ThemeState.isDark,
    highlightTheme: String = ThemeState.highlightTheme,
    bgColor: String = ThemeState.bgColor,
    darkTokenColors: DarkThemeColors = ThemeState.darkTokenColors,
    customSymbols: List<Pair<String, String>> = emptyList(),
    completionProvider: CompletionProvider? = null,
    currentSectionName: String? = null,
    sectionFilters: Map<String, Set<String>> = emptyMap(),
    sectionCompletionEnabled: Boolean = false,
    insertTextRequest: String = "",
    insertTextTick: Int = 0,
    resetTextRequest: Pair<String, Int>? = null,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    showBottomToolbar: Boolean = true,
    blocklistEnabled: Boolean = true,
    blockableStringKeys: Set<String> = emptySet(),
    onCursorChange: ((Int) -> Unit)? = null,
    onSearchResultUpdate: ((count: Int, currentIndex: Int) -> Unit)? = null,
    onCanUndoRedoChange: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null,
    onLitLinesChange: ((Set<Int>) -> Unit)? = null,
    onLightbulbToggle: ((line: Int, isLit: Boolean) -> Unit)? = null,
    onReady: ((CodeEditor) -> Unit)? = null,
    onToolbarAction: ((EditorToolbarAction) -> Unit)? = null,
    onSymbolInsert: ((String) -> Unit)? = null,
    rainbowSettingsTick: Int = 0
) {
    val context = LocalContext.current
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    val languageRef = remember { mutableStateOf<IniLanguage?>(null) }
    val symbolBarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val toolbarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val bottomBarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val litLines = remember { mutableStateOf(setOf<Int>()) }
    // 灯泡图标只需加载一次，避免每次 refreshLineIcons 都重新 createLightbulbDrawable
    val bulbDrawables = remember(context) {
        val gray = createLightbulbDrawable(context, android.graphics.Color.GRAY)
            ?: createFallbackBulbDrawable(android.graphics.Color.GRAY)
        val yellow = createLightbulbDrawable(context, android.graphics.Color.YELLOW)
            ?: createFallbackBulbDrawable(android.graphics.Color.YELLOW)
        gray to yellow
    }
    val pendingIconUpdate = remember { mutableStateOf(false) }
    val iconRefreshHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val iconRefreshToken = remember { object { var runnable: Runnable? = null } }
    // 活跃探测补全：移动光标到触发符号（:=()+-*/%<> ,）后，若补全窗口未打开则周期性强弹一次，
    // 规避 sora 在 tap 选择时会 hide() 窗口导致的 SelectionChangeEvent 重触发竞态（光标移动不弹）。
    val probeHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val probeToken = remember { object { var runnable: Runnable? = null } }
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    var isApplyingExternalText by remember { mutableStateOf(false) }

    fun hasLightbulbForLine(editor: CodeEditor, line: Int): Boolean {
        if (!blocklistEnabled || blockableStringKeys.isEmpty()) return false
        if (line < 0 || line >= editor.text.lineCount) return false
        val key = parseLineKey(editor.text.getLineString(line)) ?: return false
        return key in blockableStringKeys
    }

    fun refreshLineIcons(editor: CodeEditor) {
        val styles = editor.styles ?: Styles().also { editor.styles = it }
        // 清除旧的灯泡图标
        styles.lineStyles?.toList()?.forEach { lineStyle ->
            lineStyle.eraseStyle(LineSideIcon::class.java)
        }
        if (blocklistEnabled && blockableStringKeys.isNotEmpty()) {
            val content = editor.text
            val (gray, yellow) = bulbDrawables
            for (i in 0 until content.lineCount) {
                val lineText = content.getLineString(i)
                val key = parseLineKey(lineText) ?: continue
                if (key !in blockableStringKeys) continue
                val drawable = if (i in litLines.value) yellow else gray
                styles.addLineStyle(LineSideIcon(i, drawable))
            }
        }
        editor.setStyles(styles)
    }

    fun applyLightbulbToggle(editor: CodeEditor, line: Int) {
        val willLit = line !in litLines.value
        litLines.value = if (willLit) litLines.value + line else litLines.value - line
        onLitLinesChange?.invoke(litLines.value)
        refreshLineIcons(editor)
        onLightbulbToggle?.invoke(line, willLit)
    }

    fun toggleLightbulb(editor: CodeEditor, line: Int, event: io.github.rosemoe.sora.event.Event) {
        if (!hasLightbulbForLine(editor, line)) return
        event.intercept()
        if (!BehaviorVerifier.isVerified(context, BehaviorVerifier.Type.LIGHTBULB_FORCE_TRANSLATE)) {
            android.app.AlertDialog.Builder(context)
                .setTitle("强制翻译确认")
                .setMessage("点亮灯泡后，当前行的 value 将跳过屏蔽词并立即翻译。\n请确认你了解此操作的影响。")
                .setPositiveButton("确认") { _, _ ->
                    val saved = BehaviorVerifier.markVerified(context, BehaviorVerifier.Type.LIGHTBULB_FORCE_TRANSLATE)
                    if (saved) {
                        applyLightbulbToggle(editor, line)
                    } else {
                        android.widget.Toast.makeText(context, "保存验证状态失败，请检查存储权限", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            applyLightbulbToggle(editor, line)
        }
    }

    // 文本变化后延迟刷新图标，等待 TextMate 语法分析完成
    fun scheduleIconRefresh(editor: CodeEditor) {
        // 没有启用灯泡功能时直接返回，避免每次按键都 postDelayed 无用任务
        if (!blocklistEnabled || blockableStringKeys.isEmpty()) return
        if (pendingIconUpdate.value) return
        pendingIconUpdate.value = true
        iconRefreshToken.runnable?.let { iconRefreshHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingIconUpdate.value = false
            refreshLineIcons(editor)
        }
        iconRefreshToken.runnable = r
        iconRefreshHandler.postDelayed(r, LIGHTBULB_REFRESH_DELAY_MS)
    }

    // 外部文本同步：仅在显式请求整篇替换时调用 setText，避免宿主 text 状态变化反复重置编辑器
    LaunchedEffect(resetTextRequest) {
        resetTextRequest?.let { (newText, _) ->
            editorRef.value?.let { editor ->
                if (editor.text.toString() != newText) {
                    Log.d(TAG, "resetText triggered: len=${newText.length}")
                    isApplyingExternalText = true
                    val cursor = editor.cursor
                    val line = cursor.leftLine
                    val column = cursor.leftColumn
                    editor.setText(newText)
                    if (line < editor.text.lineCount) {
                        val targetColumn = column.coerceAtMost(editor.text.getColumnCount(line))
                        editor.setSelection(line, targetColumn)
                        editor.ensurePositionVisible(line, targetColumn)
                    }
                    isApplyingExternalText = false
                }
            }
        }
    }

    // 活跃探测代理函数：光标前一字符是触发符号且补全窗口未打开时，强制重请求一次。
    // 用于弥补 tap 移动光标时 sora 先 hide() 掉 SelectionChangeEvent 重触发的缺口。
    fun activeProbeTrigger(editorIn: CodeEditor?) {
        if (!SettingsManager.devValueCompletion) return
        val editor = editorIn ?: editorRef.value ?: return
        if (editor.hasComposingText()) return // 组合输入中不打扰
        val completion = editor.getComponent(EditorAutoCompletion::class.java)
        if (completion.isShowing() || completion.isCompletionInProgress) return
        try {
            val cur = editor.cursor
            val col = cur.leftColumn
            val line = cur.leftLine
            if (col <= 0) return
            val prev = editor.text.charAt(line, col - 1)
            if (":=()+-*/%<>,".indexOf(prev) < 0) return
            val lineText = editor.text.getLineString(line)
            if (lineText.indexOf(':') <= 0) return
            // 窗口已隐藏（如 tap 选择/非标识符输入后），重置 requestTime 立即强弹
            (completion as? NoEnterCommitAutoCompletion)?.requireCompletionNow()
                ?: completion.requireCompletion()
        } catch (e: Exception) {
            Log.e(TAG, "activeProbeTrigger failed: ${e.message}", e)
        }
    }

    // 周期性强探测一次：移动光标到触发符号后补全未弹时兜底弹出。
    // 频率控制在节流之上，且仅当窗口关闭时触发，不会与正常输入/已显示窗口的补全冲突。
    LaunchedEffect(editorRef) {
        val r = object : Runnable {
            override fun run() {
                try {
                    activeProbeTrigger(editorRef.value)
                } finally {
                    probeHandler.postDelayed(this, 150L)
                }
            }
        }
        probeToken.runnable = r
        probeHandler.post(r)
    }

    // 字体大小
    LaunchedEffect(fontSize) {
        editorRef.value?.setTextSize(fontSize)
    }

    // 自动换行
    LaunchedEffect(autoWrap) {
        editorRef.value?.isWordwrap = autoWrap
    }

    // 智能换行（逻辑断点，仅显示层，不改变文件内容）
    LaunchedEffect(smartWrap) {
        (editorRef.value as? SmartWrapCodeEditor)?.applySmartWrap(smartWrap)
    }

    // 字体类型
    LaunchedEffect(fontFamily) {
        editorRef.value?.typefaceText = editorTypeface(context)
    }

    // 主题切换（包括亮/暗、具体高亮主题、背景色、深色代码高亮颜色；字重高亮已常驻启用）
    // rainbowSettingsTick 用于在彩虹括号参数变化时触发重新应用主题
    LaunchedEffect(isDarkTheme, highlightTheme, bgColor, darkTokenColors, rainbowSettingsTick) {
        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        val barBg = themeBackgroundColor(isDarkTheme)
        editorRef.value?.let { editor ->
            applyEditorTheme(editor, isDarkTheme, highlightTheme, bgColor, darkTokenColors, resolvedBg)
        }
        bottomBarRef.value?.let { bottomBar ->
            bottomBar.setBackgroundColor(barBg)
            bottomBar.postInvalidate()
            bottomBar.requestLayout()
        }
        toolbarRef.value?.let { toolbar ->
            toolbar.setBackgroundColor(barBg)
            toolbar.postInvalidate()
        }
        symbolBarRef.value?.let { bar ->
            bar.setBackgroundColor(barBg)
            (bar.parent as? android.widget.HorizontalScrollView)?.let { scroll ->
                scroll.setBackgroundColor(barBg)
                scroll.postInvalidate()
            }
            bar.findViewWithTag<ImageButton>("paste")
                ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)
                ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            val color = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            for (i in 0 until bar.childCount) {
                (bar.getChildAt(i) as? android.widget.TextView)?.setTextColor(color)
            }
            bar.postInvalidate()
        }
        toolbarRef.value?.let { toolbar ->
            val color = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            toolbar.findViewWithTag<android.widget.Button>(EditorToolbarAction.AT_REFERENCE.name)?.setTextColor(color)
            listOf(
                EditorToolbarAction.UNDO,
                EditorToolbarAction.REDO,
                EditorToolbarAction.HINT,
                EditorToolbarAction.REFERENCE,
                EditorToolbarAction.MORE
            ).forEach { action ->
                toolbar.findViewWithTag<ImageButton>(action.name)?.setColorFilter(color)
            }
            toolbar.postInvalidate()
        }
    }

    // 底部栏显隐
    LaunchedEffect(showBottomToolbar) {
        bottomBarRef.value?.visibility = if (showBottomToolbar) android.view.View.VISIBLE else android.view.View.GONE
    }

    // 撤销/重做按钮状态变化时刷新工具栏图标
    LaunchedEffect(canUndo, canRedo) {
        toolbarRef.value?.let { toolbar ->
            updateToolbarIcons(toolbar, canUndo, canRedo)
        }
    }

    // 自定义符号栏
    LaunchedEffect(customSymbols) {
        symbolBarRef.value?.let { bar ->
            applySymbols(bar, customSymbols, isDarkTheme, onSymbolInsert)
        }
    }

    // 补全数据源更新：语言实例创建时立即注入一次，后续变化再次注入
    LaunchedEffect(completionProvider, currentSectionName, sectionFilters, sectionCompletionEnabled) {
        languageRef.value?.let { language ->
            language.completionProvider = completionProvider
            language.currentSectionName = currentSectionName
            language.sectionFilters = sectionFilters
            language.sectionCompletionEnabled = sectionCompletionEnabled
            Log.d(TAG, "Completion updated: provider=${completionProvider != null}, section=$currentSectionName, filters=$sectionFilters")
        }
    }

    // 屏蔽词/可灯泡 key 集合变化时刷新行图标
    LaunchedEffect(blocklistEnabled, blockableStringKeys) {
        editorRef.value?.let { refreshLineIcons(it) }
    }

    // 文本插入（符号栏、@引用等）
    LaunchedEffect(insertTextTick) {
        if (insertTextTick > 0 && insertTextRequest.isNotEmpty()) {
            val s = insertTextRequest
            val marker = s.indexOf(CURSOR_MARKER)
            if (marker >= 0) {
                // 移除光标标记后插入；insertText(text, offset) 的 offset 即「从插入起点往后」的光标位置，直接传 marker 即可
                editorRef.value?.insertText(s.replace(CURSOR_MARKER.toString(), ""), marker)
            } else {
                // 默认把光标停在插入内容的末尾
                editorRef.value?.insertText(s, s.length)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SoraEditorInitializer.init(ctx)

            val barBg = themeBackgroundColor(isDarkTheme)
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)

            /** 构建并发送完整的 CursorAnchorInfo 给 IME，包含插入标记的屏幕像素坐标 */
            fun sendCursorAnchorToIme(editor: CodeEditor, caller: String = "") {
                try {
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
                    val leftChar = editor.cursor.left
                    val rightChar = editor.cursor.right
                    val line = editor.cursor.leftLine
                    val column = editor.cursor.leftColumn
                    val builder = CursorAnchorInfo.Builder()
                    builder.setSelectionRange(leftChar, rightChar)
                    val rowPx = try {
                        CodeEditor::class.java.getMethod("getRowHeight").invoke(editor) as Int
                    } catch (_: Exception) { 48 }
                    val charPx = (rowPx * 0.55f)
                    val editorLoc = IntArray(2)
                    editor.getLocationOnScreen(editorLoc)
                    val insetX = editor.scrollX.toFloat()
                    val insetY = editor.scrollY.toFloat()
                    val markerX = editorLoc[0] + editor.paddingLeft - insetX + (column * charPx)
                    val markerTop = editorLoc[1] + editor.paddingTop - insetY + (line * rowPx)
                    val markerBaseline = markerTop + rowPx
                    builder.setInsertionMarkerLocation(
                        markerX, markerBaseline,
                        markerTop, markerBaseline,
                        if (leftChar == rightChar) CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION else 0
                    )
                    builder.setMatrix(Matrix())
                    imm.updateCursorAnchorInfo(editor, builder.build())
                    Log.d(TAG, "[IME] sendCursorAnchorInfo caller=$caller line=$line col=$column char=$leftChar..$rightChar marker=($markerX,$markerTop-$markerBaseline) rowPx=$rowPx scroll=($insetX,$insetY) editorLoc=(${editorLoc[0]},${editorLoc[1]})")
                } catch (e: Exception) {
                    Log.e(TAG, "[IME] sendCursorAnchorInfo FAILED: ${e.message}", e)
                }
            }

            // 重写 onCreateInputConnection 以配置 IME 文本编辑面板支持
            val editor = object : SmartWrapCodeEditor(ctx, smartWrap) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val self = this  // CodeEditor 自身引用，供内层匿名类使用
                    val ic = super.onCreateInputConnection(outAttrs)
                    // 基础编辑属性
                    outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                    outAttrs.imeOptions = outAttrs.imeOptions or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    // 填充初始选区信息，部分输入法（如 vivo）据此判断是否支持文本编辑
                    val cursor = self.cursor
                    outAttrs.initialSelStart = cursor.left
                    outAttrs.initialSelEnd = cursor.right
                    Log.d(TAG, "[IME] onCreateInputConnection: inputType=${outAttrs.inputType}, imeOptions=${outAttrs.imeOptions}, selStart=${outAttrs.initialSelStart}, selEnd=${outAttrs.initialSelEnd}, ic=${ic?.javaClass?.simpleName}")
                    // 包装 InputConnection：强制 requestCursorUpdates 返回 true，并同步发送完整 CursorAnchorInfo
                    return ic?.let { original -> object : InputConnectionWrapper(original, true) {

                        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
                            // BaseInputConnection 默认返回 false，导致部分 IME 认为不支持文本编辑。
                            // 这里无论如何返回 true，并立即发送完整光标锚点信息。
                            Log.d(TAG, "[IME] requestCursorUpdates mode=$cursorUpdateMode originalIC=${original.javaClass.simpleName}")
                            super.requestCursorUpdates(cursorUpdateMode)
                            sendCursorAnchorToIme(self, "requestCursorUpdates")
                            return true
                        }
                    } }
                }
            }.apply {
                Log.d(TAG, "TextMate init ok=${SoraEditorInitializer.isInitialized()}")
                applyEditorTheme(this, isDarkTheme, highlightTheme, bgColor, darkTokenColors, resolvedBg)
                typefaceText = editorTypeface(ctx)
                isLineNumberEnabled = SettingsManager.devLineNumber
                setTextSize(fontSize)
                isWordwrap = autoWrap
                // 软换行提示符：使用 sora-editor 内置的 FLAG_DRAW_SOFT_WRAP
                nonPrintablePaintingFlags = if (SettingsManager.wrapIndicatorEnabled) {
                    CodeEditor.FLAG_DRAW_SOFT_WRAP
                } else {
                    0
                }
                tabWidth = EDITOR_TAB_WIDTH
                setScrollBarEnabled(false)
                // 增大行侧图标尺寸，让灯泡更容易点击；点击区域仍为一行高
                getProps().sideIconSizeFactor = SIDE_ICON_SIZE_FACTOR
                getProps().actionWhenLineNumberClicked = DirectAccessProps.LN_ACTION_PLACE_SELECTION_HOME
                // 替换默认补全组件：回车键不提交补全项，而是关闭列表让回车正常换行
                replaceComponent(EditorAutoCompletion::class.java, NoEnterCommitAutoCompletion(this))

                val tmLanguage = try {
                    if (SettingsManager.rainbowBrackets) {
                        try {
                            RainbowTextMateLanguage.create("source.ini", false)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create rainbow language, fallback to plain TextMate", e)
                            TextMateLanguage.create("source.ini", false)
                        }
                    } else {
                        TextMateLanguage.create("source.ini", false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create TextMate language", e)
                    null
                }
                if (tmLanguage != null) {
                    val language = IniLanguage(tmLanguage).apply {
                        this.completionProvider = completionProvider
                        this.currentSectionName = currentSectionName
                        this.sectionFilters = sectionFilters
                        this.sectionCompletionEnabled = sectionCompletionEnabled
                        this.bracketDiagnosticsEnabled = SettingsManager.bracketDiagnostics
                    }
                    setEditorLanguage(language)
                    languageRef.value = language
                    Log.d(TAG, "Language created, completionProvider=${completionProvider != null}")
                } else {
                    Log.e(TAG, "No TextMate language set; highlighting will be disabled")
                }

                var cachedText = ""
                // 撤销/重做状态去重缓存，避免每次按键触发 Compose 重组
                var lastCanUndo: Boolean? = null
                var lastCanRedo: Boolean? = null
                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    // 程序 setText 触发的事件只刷新按钮状态，不同步 text，避免宿主 isModified 被误标
                    if (!isApplyingExternalText) {
                        // 增量更新 cachedText，避免每次按键 O(n) 全量 toString()
                        when (event.action) {
                            ContentChangeEvent.ACTION_INSERT -> {
                                val idx = event.changeStart.index
                                val inserted = event.changedText.toString()
                                cachedText = if (idx <= 0) {
                                    inserted + cachedText
                                } else if (idx >= cachedText.length) {
                                    cachedText + inserted
                                } else {
                                    cachedText.substring(0, idx) + inserted + cachedText.substring(idx)
                                }
                            }
                            ContentChangeEvent.ACTION_DELETE -> {
                                val idx = event.changeStart.index
                                val len = event.changedText.length
                                val end = (idx + len).coerceAtMost(cachedText.length)
                                cachedText = if (idx <= 0) {
                                    cachedText.substring(end)
                                } else {
                                    cachedText.substring(0, idx) + cachedText.substring(end)
                                }
                            }
                            else -> {
                                // ACTION_SET_NEW_TEXT — 全量刷新
                                cachedText = event.editor.text.toString()
                            }
                        }
                        // 安全网：长度不一致说明缓存失步，全量刷新
                        if (cachedText.length != event.editor.text.length) {
                            cachedText = event.editor.text.toString()
                        }
                        currentOnTextChange(cachedText)
                    } else {
                        // 外部 setText — 静默刷新缓存
                        cachedText = event.editor.text.toString()
                    }
                    val canUndoNow = event.editor.canUndo()
                    val canRedoNow = event.editor.canRedo()
                    // 状态去重：仅在变化时通知宿主，避免每次按键触发 Compose 重组
                    if (canUndoNow != lastCanUndo || canRedoNow != lastCanRedo) {
                        lastCanUndo = canUndoNow
                        lastCanRedo = canRedoNow
                        onCanUndoRedoChange?.invoke(canUndoNow, canRedoNow)
                    }
                    scheduleIconRefresh(event.editor)
                }

                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    onCursorChange?.invoke(event.editor.cursor.left)
                    val editor = event.editor
                    val line = editor.cursor.leftLine
                    val column = editor.cursor.leftColumn
                    val lineText = editor.text.getLineString(line)
                    try {
                        // 光标移动到 : / = / ( / 逻辑运算符 / 逗号 后面时主动触发值补全窗口
                        // （输入 :、提交 键:/参数名=/函数名(、逻辑表达式运算符 + - * / % < >、参数分隔 , 后）。
                        // 逻辑运算符在 sora 默认触发字符之外，补全不会自动弹出，需在此主动触发，
                        // 否则 `if 自身在水下+` 得敲空格才弹（且此时已是连接符位）。
                        // 延迟一帧执行并在回调里重新读取光标状态：sora 的 EditorAutoCompletion.select()
                        // 在提交补全期间 cancelShowUp=true，会吞掉同步触发的 requireCompletion()，
                        // 延迟到提交完成后执行才能可靠弹出值补全。
                        if (SettingsManager.devValueCompletion) {
                            val valueTriggerChar = ":=()+-*/%<>,".indexOf(if (column > 0) editor.text.charAt(line, column - 1) else '\u0000') >= 0
                            if (column > 0 && valueTriggerChar) {
                                val completion = editor.getComponent(EditorAutoCompletion::class.java)
                                editor.postDelayedInLifecycle({
                                    val cur = editor.cursor
                                    val col = cur.leftColumn
                                    if (col > 0 && ":=(+-*/%<>,".indexOf(editor.text.charAt(cur.leftLine, col - 1)) >= 0) {
                                        val currentLineText = editor.text.getLineString(cur.leftLine)
                                        // sora 已自动弹出时不重复触发；否则重置 requestTime 绕过
                                        // 70ms 节流立即弹出（提交后窗口已 hide，无需延迟等待）。
                                        // 窗口已在显示（如 @memory 名字: 的变量名候选，: 是阶段切换符）
                                        // 也要强制重求值，否则切不到类型补全。
                                        if (currentLineText.indexOf(':') > 0) {
                                            if (!completion.isShowing()) {
                                                (completion as? NoEnterCommitAutoCompletion)?.requireCompletionNow()
                                                    ?: completion.requireCompletion()
                                            } else {
                                                completion.requireCompletion()
                                            }
                                        }
                                    }
                                }, 0L)
                            }
                        }
                        // 节补全：光标在节内空行时主动触发补全
                        if (sectionCompletionEnabled && currentSectionName != null) {
                            var allBlankBefore = true
                            for (i in 0 until column) {
                                val c = lineText[i]
                                if (c != ' ' && c != '\t') { allBlankBefore = false; break }
                            }
                            val startsWithBracket = lineText.isNotEmpty() && lineText.trimStart().startsWith("[")
                            if (allBlankBefore && !startsWithBracket) {
                                val completion = editor.getComponent(EditorAutoCompletion::class.java)
                                if (!completion.isCompletionInProgress) {
                                    completion.requireCompletion()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to trigger completion on selection change", e)
                    }
                    // 通知 IME 光标/选区位置变化，提供完整像素坐标以启用输入法文本编辑面板
                    sendCursorAnchorToIme(editor, "selectionChange")
                }

                // 文本变更兜底触发：sora 输入 `+`/`-`/`<` 等非标识符字符时会清空补全窗口并置
                // cancelShowUp，导致 SelectionChangeEvent 主动触发被吞、紧贴运算符不弹补全。
                // 这里在文本真正改动后（ContentChangeEvent 一定派发）对运算符/比较符/括号/逗号
                // 后的光标位置再兜底 requireCompletion，保证 `if 自身在水下+` 紧贴即弹操作数集。
                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    val editor = event.editor
                    try {
                        if (!SettingsManager.devValueCompletion) return@subscribeEvent
                        val col = editor.cursor.leftColumn
                        if (col > 0 && ":=(+-*/%<>,".contains(editor.text.charAt(editor.cursor.leftLine, col - 1))) {
                            val completion = editor.getComponent(EditorAutoCompletion::class.java)
                            editor.postDelayedInLifecycle({
                                val c = editor.cursor
                                val cl = c.leftColumn
                                if (cl > 0 && ":=(+-*/%<>,".contains(editor.text.charAt(c.leftLine, cl - 1))) {
                                    // 行内含冒号即触发：窗口未显示则立即弹出，已显示（如 @memory 名字:
                                    // 的变量名候选）则强制重求值，确保 : 能切到类型/值补全。
                                    if (editor.text.getLineString(c.leftLine).indexOf(':') > 0) {
                                        if (!completion.isShowing()) {
                                            (completion as? NoEnterCommitAutoCompletion)?.requireCompletionNow()
                                                ?: completion.requireCompletion()
                                        } else {
                                            completion.requireCompletion()
                                        }
                                    }
                                }
                            }, 0L)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to trigger completion on content change", e)
                    }
                }

                subscribeEvent(SideIconClickEvent::class.java) { event, _ ->
                    toggleLightbulb(event.editor, event.clickedIcon.line, event)
                }

                subscribeEvent(ClickEvent::class.java) { event, _ ->
                    // 光标定位由内置 LN_ACTION_PLACE_SELECTION_HOME 处理（自动换行时落到被点击视觉行的行首）
                    if (event.motionRegion == ClickEvent.REGION_LINE_NUMBER && hasLightbulbForLine(event.editor, event.line)) {
                        toggleLightbulb(event.editor, event.line, event)
                    }
                }

                subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                    val searcher = event.editor.searcher
                    var count = 0
                    var index = -1
                    try {
                        if (searcher.hasQuery()) {
                            count = searcher.matchedPositionCount
                            index = searcher.currentMatchedPositionIndex
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read search result", e)
                    }
                    onSearchResultUpdate?.invoke(count, index)
                }

                isApplyingExternalText = true
                setText(text)
                isApplyingExternalText = false
                editorRef.value = this
                refreshLineIcons(this)
                onReady?.invoke(this)
                // 编辑器就绪后发送一次完整的光标锚点信息，确保 IME 连接时能立即获取
                sendCursorAnchorToIme(this, "onReady")
            }

            val density = ctx.resources.displayMetrics.density
            val barHeight = (42 * density).toInt()

            val editButton = android.widget.ImageButton(ctx).apply {
                tag = EditorToolbarAction.EDIT_SYMBOLS.name
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_edit))
                setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                resolveRipple(ctx)?.let { background = it }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener { onToolbarAction?.invoke(EditorToolbarAction.EDIT_SYMBOLS) }
            }

            val pasteButton = android.widget.ImageButton(ctx).apply {
                tag = "paste"
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_paste))
                setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                resolveRipple(ctx)?.let { background = it }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener {
                    editorRef.value?.pasteText()
                }
            }

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(barBg)
                addView(pasteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                // 自定义符号按钮由 applySymbols 在粘贴按钮与编辑按钮之间构建
                addView(editButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                symbolBarRef.value = this
            }
            applySymbols(inner, customSymbols, isDarkTheme, onSymbolInsert)

            val symbolPanel = android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(barBg)
                addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            val toolbar = createToolbar(ctx, onToolbarAction, isDarkTheme).apply {
                toolbarRef.value = this
                updateToolbarIcons(this, canUndo, canRedo)
                setBackgroundColor(barBg)
            }

            val bottomBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = if (showBottomToolbar) android.view.View.VISIBLE else android.view.View.GONE
                setBackgroundColor(barBg)
                addView(
                    symbolPanel,
                    LinearLayout.LayoutParams(0, barHeight, 0.55f)
                )
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(0, barHeight, 0.45f)
                )
                bottomBarRef.value = this
            }

            container.addView(
                editor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            container.addView(
                bottomBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            container
        },
        modifier = modifier,
        update = { _ ->
            // 兜底：每次重构图/视图重建后都同步一次底部栏背景，使用固定主题色保证稳定。
            val barBg = themeBackgroundColor(isDarkTheme)
            bottomBarRef.value?.setBackgroundColor(barBg)
            toolbarRef.value?.setBackgroundColor(barBg)
            symbolBarRef.value?.let { bar ->
                bar.setBackgroundColor(barBg)
                (bar.parent as? android.widget.HorizontalScrollView)?.setBackgroundColor(barBg)
                bar.findViewWithTag<ImageButton>("paste")
                    ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)
                    ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
            toolbarRef.value?.let { toolbar ->
                val color = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                toolbar.findViewWithTag<android.widget.Button>(EditorToolbarAction.AT_REFERENCE.name)?.setTextColor(color)
                listOf(
                    EditorToolbarAction.UNDO,
                    EditorToolbarAction.REDO,
                    EditorToolbarAction.HINT,
                    EditorToolbarAction.REFERENCE,
                    EditorToolbarAction.MORE
                ).forEach { action ->
                    toolbar.findViewWithTag<ImageButton>(action.name)?.setColorFilter(color)
                }
            }
        },
        onRelease = { container ->
            iconRefreshToken.runnable?.let { iconRefreshHandler.removeCallbacks(it) }
            iconRefreshToken.runnable = null
            probeToken.runnable?.let { probeHandler.removeCallbacks(it) }
            probeToken.runnable = null
            (container as? LinearLayout)?.let { layout ->
                (layout.getChildAt(0) as? CodeEditor)?.release()
            }
        }
    )
}

internal fun applyEditorTheme(
    editor: CodeEditor,
    isDarkTheme: Boolean,
    highlightTheme: String,
    bgColor: String,
    darkTokenColors: DarkThemeColors,
    resolvedBg: Int
) {
    try {
        Log.d(TAG, "Applying theme: highlight=$highlightTheme, bg=$bgColor, resolvedBg=${Integer.toHexString(resolvedBg)}, darkTokens=$darkTokenColors")
        val scheme = SoraEditorInitializer.getColorScheme(
            isDarkTheme,
            highlightTheme,
            darkTokenColors,
            boldHighlight = true,
            italicHighlight = true
        )
        // 注意：TextMateColorScheme.attachEditor() 会重新 setTheme() 并清空 colors，
        // 所以必须在 editor.colorScheme = scheme 之后再设置自定义颜色。
        editor.colorScheme = scheme

        // 先计算背景明暗，用于滚动条等判断
        val isDark = android.graphics.Color.luminance(resolvedBg) < 0.5

        // 深色主题下允许用户自定义 UI 高亮色（选中、当前行、匹配文本、高亮分隔符等）
        if (highlightTheme == "dark") {
            try {
                val highlight = android.graphics.Color.parseColor(darkTokenColors.ui)
                val highlightAlpha = (highlight and 0x00FFFFFF) or 0x44000000
                scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, highlightAlpha)
                scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, highlightAlpha)
                scheme.setColor(EditorColorScheme.CURRENT_LINE, highlightAlpha)
                scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, highlight)
                scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, (highlight and 0x00FFFFFF) or 0x22000000)
                scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, highlight)
                scheme.setColor(EditorColorScheme.SELECTION_INSERT, highlight)
                scheme.setColor(EditorColorScheme.SELECTION_HANDLE, highlight)
                Log.d(TAG, "Applied dark UI highlight color: ${darkTokenColors.ui}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse dark UI highlight color: ${darkTokenColors.ui}", e)
            }
        }

        // 生成彩虹括号颜色（内层嵌套用，方向由背景色决定）
        SoraEditorInitializer.applyRainbowBracketColors(scheme, highlightTheme, resolvedBg)

        // 始终使用解析后的统一背景色，保证编辑器主体与底部栏一致
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, resolvedBg)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, resolvedBg)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, resolvedBg)
        scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, resolvedBg)
        // 自动补全弹窗文字颜色根据背景明暗自适应，避免自定义背景+主题文字色冲突
        val completionTextPrimary = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val completionTextSecondary = if (isDark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, completionTextPrimary)
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, completionTextSecondary)
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, completionTextPrimary)
        scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, if (isDark) 0x1AFFFFFF.toInt() else 0x1A000000.toInt())
        scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, if (isDark) 0xFF333333.toInt() else 0xFFE0E0E0.toInt())
        // 滚动条半透明，避免深色/浅色背景下出现纯白/纯黑粗条
        scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, 0x00000000)
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, if (isDark) 0x80FFFFFF.toInt() else 0x80000000.toInt())
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, if (isDark) 0xBFFFFFFF.toInt() else 0xB0000000.toInt())
        scheme.setColor(EditorColorScheme.STICKY_SCROLL_DIVIDER, 0x00000000)
        editor.invalidate()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to apply editor theme", e)
    }
}

internal fun resolveEditorBackground(bgColor: String, isDarkTheme: Boolean): Int {
    return try {
        android.graphics.Color.parseColor(bgColor)
    } catch (_: Exception) {
        themeBackgroundColor(isDarkTheme)
    }
}

internal fun themeBackgroundColor(isDarkTheme: Boolean): Int {
    return if (isDarkTheme) android.graphics.Color.parseColor("#1E1E1E")
    else android.graphics.Color.parseColor("#F0F0F0")
}

private fun updateToolbarIcons(toolbar: LinearLayout, canUndo: Boolean, canRedo: Boolean) {
    val undo = toolbar.findViewWithTag<ImageButton>(EditorToolbarAction.UNDO.name)
    val redo = toolbar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
    val alphaUndo = if (canUndo) 1.0f else 0.3f
    val alphaRedo = if (canRedo) 1.0f else 0.3f
    undo?.alpha = alphaUndo
    undo?.isEnabled = canUndo
    redo?.alpha = alphaRedo
    redo?.isEnabled = canRedo
}

private fun createToolbar(
    ctx: Context,
    onToolbarAction: ((EditorToolbarAction) -> Unit)?,
    isDarkTheme: Boolean
): LinearLayout {
    val toolbar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    val ripple = resolveRipple(ctx)

    val iconColor = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val items = listOf(
        EditorToolbarAction.UNDO to R.drawable.ic_undo,
        EditorToolbarAction.REDO to R.drawable.ic_redo,
        EditorToolbarAction.HINT to R.drawable.ic_hint,
        EditorToolbarAction.REFERENCE to R.drawable.ic_reference,
        EditorToolbarAction.AT_REFERENCE to null, // 用文本 “@”
        EditorToolbarAction.MORE to R.drawable.ic_more
    )
    val density = ctx.resources.displayMetrics.density
    val hPadding = (4 * density).toInt()

    val iconBg = createToolbarIconBackground(isDarkTheme)
    items.forEach { (action, iconRes) ->
        if (iconRes != null) {
            val btn = ImageButton(ctx).apply {
                tag = action.name
                setImageDrawable(ContextCompat.getDrawable(ctx, iconRes))
                setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN)
                background = iconBg.constantState?.newDrawable()?.mutate()
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setPadding(hPadding, 0, hPadding, 0)
                setOnClickListener { onToolbarAction?.invoke(action) }
            }
            toolbar.addView(btn)
        } else {
            val btn = android.widget.Button(ctx).apply {
                tag = action.name
                text = "@"
                textSize = 15f
                setTextColor(iconColor)
                background = iconBg.constantState?.newDrawable()?.mutate()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setPadding(0, 0, 0, 0)
                setOnClickListener { onToolbarAction?.invoke(action) }
            }
            toolbar.addView(btn)
        }
    }

    return toolbar
}

private fun createToolbarIconBackground(isDarkTheme: Boolean): android.graphics.drawable.Drawable {
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 8f
        setColor(if (isDarkTheme) 0x30FFFFFF else 0x20000000)
    }
}

private fun resolveRipple(ctx: Context): android.graphics.drawable.Drawable? {
    return try {
        val typedValue = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        ContextCompat.getDrawable(ctx, typedValue.resourceId)
    } catch (_: Exception) {
        null
    }
}

private fun parseLineKey(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("[")) return null
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx <= 0) return null
    return trimmed.substring(0, colonIdx).trim()
}

private fun createLightbulbDrawable(ctx: Context, color: Int): android.graphics.drawable.Drawable? {
    return try {
        androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_lightbulb)?.mutate()?.apply {
            setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }
    } catch (_: Exception) {
        null
    }
}

private fun createFallbackBulbDrawable(color: Int): android.graphics.drawable.Drawable {
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
        setSize(24, 24)
    }
}

private const val SYMBOL_BUTTON_TAG = "custom_symbol"

/**
 * 构建/重建快捷符号按钮：先移除旧的符号按钮，再在编辑按钮之前插入新的按钮。
 * 点击按钮时通过 [onSymbolInsert] 把该符号的输出值交给宿主处理（宿主负责光标定位）。
 */
private fun applySymbols(
    bar: LinearLayout,
    symbols: List<Pair<String, String>>,
    isDarkTheme: Boolean,
    onSymbolInsert: ((String) -> Unit)?
) {
    // 移除旧的符号按钮（保留粘贴/编辑按钮）
    for (i in bar.childCount - 1 downTo 0) {
        if (bar.getChildAt(i).tag == SYMBOL_BUTTON_TAG) bar.removeViewAt(i)
    }
    val list = symbols.ifEmpty {
        listOf(
            "=" to "=", ":" to ":", ";" to ";", "#" to "#",
            "[" to "[", "]" to "]", "," to ",", "." to ".",
            "\"" to "\""
        )
    }
    // 找到编辑按钮的索引，符号按钮插入在它之前
    var insertIndex = bar.childCount
    for (i in 0 until bar.childCount) {
        if (bar.getChildAt(i).tag == EditorToolbarAction.EDIT_SYMBOLS.name) {
            insertIndex = i
            break
        }
    }
    val density = bar.resources.displayMetrics.density
    val hPadding = (10 * density).toInt()
    val vPadding = (4 * density).toInt()
    val textColor = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    var index = insertIndex
    for ((label, value) in list) {
        val btn = android.widget.Button(bar.context).apply {
            tag = SYMBOL_BUTTON_TAG
            text = label
            setTextColor(textColor)
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            setPadding(hPadding, vPadding, hPadding, vPadding)
            // 去掉系统默认按钮背景，只显示符号文本，避免出现突兀的色块
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            setOnClickListener { onSymbolInsert?.invoke(value) }
        }
        bar.addView(btn, index++)
    }
}

/**
 * 自定义补全组件：回车键不提交补全项，只关闭列表让回车键正常换行。
 * 其他按键行为不变（Tab 提交、方向键导航等）。
 */
private class NoEnterCommitAutoCompletion(editor: CodeEditor) : EditorAutoCompletion(editor) {

    override fun onKeyEvent(event: EditorKeyEvent, unsubscribe: Unsubscribe) {
        if (event.eventType == EditorKeyEvent.Type.DOWN &&
            event.keyCode == KeyEvent.KEYCODE_ENTER &&
            isShowing()
        ) {
            hide()
            return
        }
        super.onKeyEvent(event, unsubscribe)
    }

    // 提交补全后的重触发统一由 SelectionChangeEvent 处理器负责（光标落到 ':'/'='/(' 后），
    // 不再在 select() 里重复触发，避免与事件处理器双重重触发导致 requireCompletion
    // 节流互踩（弹窗刚显示又被主动 hide()）。

    /**
     * 绕过 sora 的 70ms 补全节流立即请求补全：提交 ':'/'='/(' 后窗口已 hide，
     * 直接 requireCompletion() 可能因距上次请求不足 70ms 被节流并主动 hide()，
     * 先重置 requestTime 再触发即可立即生效（无需延迟等待）。
     */
    fun requireCompletionNow() {
        requestTime = 0L
        requireCompletion()
    }
}

