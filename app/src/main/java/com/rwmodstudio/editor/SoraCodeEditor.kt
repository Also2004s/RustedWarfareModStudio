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
import com.rwmodstudio.core.HighlightRuleManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.translation.BehaviorVerifier
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.multilineValueOwnerKey
import com.rwmodstudio.feature.completion.value.topLevelEq
import com.rwmodstudio.ui.theme.editorTypeface
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SideIconClickEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.event.Unsubscribe
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineBackground
import io.github.rosemoe.sora.lang.styling.line.LineSideIcon
import io.github.rosemoe.sora.langs.textmate.RainbowTextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
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
 * 将指定逻辑行中的光标垂直居中到视口中部。
 * sora 的渲染偏移在 EditorScroller（[CodeEditor.getOffsetX/getOffsetY]）而非 View.scrollX/Y。
 * [CodeEditor.getCharOffsetY] 返回的是该字符相对视口顶部的屏幕 Y（= 内容绝对 Y - 当前滚动偏移 getOffsetY），
 * 因此内容绝对 Y = getCharOffsetY + getOffsetY。把绝对 Y 与中线对齐即为目标滚动偏移，
 * 再用 scroller.startScroll 零时长瞬时定位即可。y 自动夹取到可滚动范围。
 */
fun CodeEditor.centerCursorOnVisible(line: Int, column: Int) {
    val rowH = getRowHeight().coerceAtLeast(1)
    val viewH = height
    val col = column.coerceAtMost(text.getColumnCount(line))
    // getCharOffsetY 是屏幕 Y（内容绝对 Y - 当前 offset），加上 getOffsetY 即还原内容绝对位置，
    // 因此与当前滚动偏移无关、自带时效性，不会受未落位的旧偏移干扰
    val absRowTopY = (getCharOffsetY(line, col).toLong() + getOffsetY()).coerceAtLeast(0L)
    val desiredY = (absRowTopY - (viewH - rowH) / 2L).coerceAtLeast(0L)
    val clampedY = desiredY.coerceAtMost(getScrollMaxY().toLong()).toInt()
    val scroller = getScroller()
    val curX = scroller.getCurrX()
    val curY = scroller.getCurrY()
    scroller.startScroll(curX, curY, 0, clampedY - curY, 0)
    // 触发一帧绘制让 computeScroll 把偏移最终落位（零时长立即到位）
    invalidate()
}

/**
 * 判断光标是否处于「多行值块续行的 RHS」：当前行无 `:` 但行内存在顶层 `=`（如 用逻辑设置资源:"""
 * 块内的续行 资源名=），且整段文本确实在未闭合的 key:""" 多行值块内。
 * 用于补齐编辑器侧补全触发门槛：单行 `key:资源名=` 靠行内 `:` 触发，而多行值块续行无 `:`，
 * 提交 资源名= 后需按「多行值块 + 行内顶层 =」判定，才能立即重触发表达式补全。
 * 括号内命名参数 `=`（几秒后=1.2）由 topLevelEq 跳过；非多行值块（如 [资源] 节裸 `name=value`）返回 false。
 */
private fun isMultilineBlockValueRhs(editor: CodeEditor, line: Int, column: Int): Boolean {
    val lineText = editor.text.getLineString(line)
    if (lineText.indexOf(':') > 0) return false
    val col = column.coerceAtMost(editor.text.getColumnCount(line))
    if (topLevelEq(lineText.substring(0, col)) < 0) return false
    val offset = editor.text.getCharIndex(line, col).coerceAtMost(editor.text.length)
    val textBefore = editor.text.substring(0, offset)
    return multilineValueOwnerKey(textBefore) != null
}

/**
 * 文件级编辑器视图状态：光标行列 + 视口滚动偏移。
 * 用于 tab 栏切换文件/重新进入时恢复上次离开的光标与视角位置。
 */
data class EditorViewState(
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

/**
 * 工作区缓存条目：完整保存编辑器缓冲区语义状态，使 tab 栏工作区内的文件切换不再落盘、
 * 切回时直接还原显示内容，无需重读磁盘或重跑翻译，从而避免行结构重排导致视图/光标漂移。
 *
 * - [displayText]：最近一次编辑后的显示内容（= EditorScreen.latestEditorText），恢复时的权威内容。
 * - [englishText]/[chineseText]：两个语言缓冲区的「最后一次已知」内容（编辑期间可能滞后于显示内容），
 *   仅用于恢复后的中英列映射等场景；保存始终基于 [displayText] 反向翻译，与 EditorScreen.saveSync 一致。
 * - [lineOriginalTexts]：点亮行（灯泡强制翻译）前的英文原文，随缓存一起保存，保证切回后取消点亮仍能还原原文。
 */
data class EditorWorkspaceEntry(
    val path: String = "",
    val displayText: String = "",
    val englishText: String = "",
    val chineseText: String = "",
    val isEnglish: Boolean = false,
    val showChinese: Boolean = true,
    val isModified: Boolean = false,
    val litLines: Set<Int> = emptySet(),
    val lineOriginalTexts: Map<Int, String> = emptyMap(),
    val viewState: EditorViewState? = null
)

/**
 * 将编辑器视口恢复到指定的绝对滚动偏移（水平/垂直），超出可滚动范围时自动夹取。
 * 与 [centerCursorOnVisible] 不同，这里保持「光标可以不在视口内」的原始浏览位置。
 */
fun CodeEditor.restoreViewport(scrollX: Int, scrollY: Int) {
    val maxX = getScrollMaxX().coerceAtLeast(0)
    val maxY = getScrollMaxY().coerceAtLeast(0)
    val tx = scrollX.coerceIn(0, maxX)
    val ty = scrollY.coerceIn(0, maxY)
    val scroller = getScroller()
    val curX = scroller.getCurrX()
    val curY = scroller.getCurrY()
    scroller.startScroll(curX, curY, tx - curX, ty - curY, 0)
    // 触发一帧绘制让 computeScroll 把偏移最终落位（零时长立即到位）
    invalidate()
}

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
    MORE,
    COLOR_PICKER
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
    // 仅当「文件级整篇替换」（tab 切换/重新加载）时携带上次保存的视图状态，恢复光标与视角；中英切换等不触发。
    restoreViewStateRequest: Pair<EditorViewState, Int>? = null,
    onRestoreViewStateConsumed: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    showBottomToolbar: Boolean = true,
    // 滚动方向上报：手指上滑(内容下滚)=1 / 下滑(内容上滚)=-1，方向反转时触发一次，供宿主联动顶部栏显隐
    onScrollDirectionChanged: ((Int) -> Unit)? = null,
    blocklistEnabled: Boolean = true,
    blockableStringKeys: Set<String> = emptySet(),
    onCursorChange: ((Int) -> Unit)? = null,
    // 本实例是否为前台激活：false 时停用 TextMate 高亮（setEditorLanguage(null)），
    // 节省后台实例的渲染开销；true 时恢复语言高亮。
    active: Boolean = true,
    // 实时上报视图状态（光标行列 + 滚动偏移）：宿主据此缓存，切走时直接读缓存，
    // 避免单活跃实例下 View 先销毁导致 onDispose 读不到有效光标/滚动。
    onViewStateChanged: ((EditorViewState) -> Unit)? = null,
    onSearchResultUpdate: ((count: Int, currentIndex: Int) -> Unit)? = null,
    onCanUndoRedoChange: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null,
    onLitLinesChange: ((Set<Int>) -> Unit)? = null,
    // 括号诊断问题列表回调（主线程，清空时回调空列表）：供宿主显示角标/跳转
    onBracketIssues: ((List<BracketIssue>) -> Unit)? = null,
    onLightbulbToggle: ((line: Int, isLit: Boolean) -> Unit)? = null,
    onReady: ((CodeEditor) -> Unit)? = null,
    onToolbarAction: ((EditorToolbarAction) -> Unit)? = null,
    onSymbolInsert: ((String) -> Unit)? = null,
    rainbowSettingsTick: Int = 0,
    sectionHighlightTick: Int = 0,
    // 自定义高亮规则修订号（HighlightRuleManager.revision）：变化时就地重建语言，规则即时生效
    highlightRulesRevision: Int = HighlightRuleManager.revision,
    // 外部（如继承链对话框开合）触发重新应用主题/自定义颜色：扑灭其他实例
    // 创建配色方案时全局 ThemeRegistry 派发对本实例自定义颜色的清空。
    themeRefreshTick: Int = 0
) {
    val context = LocalContext.current
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    val languageRef = remember { mutableStateOf<IniLanguage?>(null) }
    val symbolBarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val toolbarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val bottomBarRef = remember { mutableStateOf<LinearLayout?>(null) }
    val litLines = remember { mutableStateOf(setOf<Int>()) }
    // 灯泡图标只需加载一次，避免每次 refreshLineStyles 都重新 createLightbulbDrawable
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
    // 顶部栏显隐的滚动方向跟踪（跨 View 复用生命周期共享）：只统计手指按住编辑器时的手动滚动，
    // 输入法弹出对焦、tap 光标定位、恢复视图等程序化滚动一律不计入，避免误折叠/误展开。
    val scrollTrack = remember { object {
        var lastY = 0
        var dir = 0      // +1 内容下滚(手指上滑)，-1 内容上滚(手指下滑)
        var accum = 0f   // 当前方向累计滚动距离（阈值过滤小幅拖动）
        var toggleDir = 0 // 最近已触发上报的方向，防止同方向重复触发
        var touching = false // 手指是否按住编辑器
    } }
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnScrollDirectionChanged by rememberUpdatedState(onScrollDirectionChanged)
    val currentRestoreViewStateRequest by rememberUpdatedState(restoreViewStateRequest)
    var isApplyingExternalText by remember { mutableStateOf(false) }
    // 整篇替换的稳定化代次：每次 resetTextRequest 递增，旧的 postDelayed 稳定化回调据此判定失效。
    // 关键：避免「上一个文件的恢复/居中回调在本文件上误触发」——单视图跨文件切换时，
    // 旧文件调度到 2~3 秒后的回调会在已切换到新文件后仍执行，把视角/光标掰回旧位置，造成反复跳动。
    val restoreGen = remember { object { var gen = 0 } }

    fun hasLightbulbForLine(editor: CodeEditor, line: Int): Boolean {
        if (!blocklistEnabled || blockableStringKeys.isEmpty()) return false
        if (line < 0 || line >= editor.text.lineCount) return false
        val key = parseLineKey(editor.text.getLineString(line)) ?: return false
        return key in blockableStringKeys
    }

    fun refreshLineStyles(editor: CodeEditor) {
        val styles = editor.styles ?: Styles().also { editor.styles = it }
        // 清除旧的灯泡图标与节名行高光，避免重复叠加
        styles.lineStyles?.toList()?.forEach { lineStyle ->
            lineStyle.eraseStyle(LineSideIcon::class.java)
            lineStyle.eraseStyle(LineBackground::class.java)
        }
        // 灯泡图标（行尾强制翻译）
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
        // 节名行高光：[xxx] 整行铺底色，颜色可自定义（未自定义时随编辑器背景明暗自适应）
        if (SettingsManager.sectionHighlightEnabled) {
            val hlColor = sectionHighlightResolvedColor(
                resolveEditorBackground(bgColor, isDarkTheme),
                SettingsManager.sectionHighlightColor
            )
            val contentText = editor.text
            for (i in 0 until contentText.lineCount) {
                val t = contentText.getLineString(i).trim()
                if (t.length >= 3 && t.startsWith("[") && t.endsWith("]")) {
                    styles.addLineStyle(LineBackground(i, ConstColor(hlColor)))
                }
            }
        }
        editor.setStyles(styles)
    }

    fun applyLightbulbToggle(editor: CodeEditor, line: Int) {
        val willLit = line !in litLines.value
        litLines.value = if (willLit) litLines.value + line else litLines.value - line
        onLitLinesChange?.invoke(litLines.value)
        refreshLineStyles(editor)
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

    // 文本变化后延迟刷新行样式（灯泡图标 + 节名行高光），等待 TextMate 语法分析完成
    fun scheduleLineStyleRefresh(editor: CodeEditor) {
        if (pendingIconUpdate.value) return
        pendingIconUpdate.value = true
        iconRefreshToken.runnable?.let { iconRefreshHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingIconUpdate.value = false
            refreshLineStyles(editor)
        }
        iconRefreshToken.runnable = r
        iconRefreshHandler.postDelayed(r, LIGHTBULB_REFRESH_DELAY_MS)
    }

    // 外部文本同步：仅在显式请求整篇替换时调用 setText，避免宿主 text 状态变化反复重置编辑器
    LaunchedEffect(resetTextRequest) {
        resetTextRequest?.let { (newText, tick) ->
            // 无条件打点：任何整篇替换请求(含中英切换)都必然产生此行，用于确认流程与分支是否真正命中
            Log.d(TAG, "resetText-REQ arrived len=${newText.length}")
            editorRef.value?.let { editor ->
                if (editor.text.toString() != newText) {
                    // 本次替换的代次：一旦发生新一次整篇替换（通常是切到新文件），旧替换调度的
                    // 所有延迟稳定化回调全部作废，防止把视角/光标掰回上一个文件的旧位置（反复跳动）。
                    val myGen = ++restoreGen.gen
                    // 仅当本次整篇替换是「文件级加载」且携带有上次保存的视图状态时才恢复位置；
                    // 用 tick 与 resetTextRequest 配对，避免中英切换等后续替换误触发。
                    val restore = currentRestoreViewStateRequest?.takeIf { it.second == tick }?.first
                    isApplyingExternalText = true
                    val cursor = editor.cursor
                    val line = cursor.leftLine
                    val column = cursor.leftColumn
                    val props = editor.getProps()
                    // 切换期间先关闭「高度收缩时自动滚动选区」，避免键盘/重排期间干扰（补充防护，非主因）。
                    props.adjustToSelectionOnResize = false
                    editor.setText(newText)
                    if (restore != null) {
                        // 文件重新加载：定位到上次保存的光标行列（超出则夹取）。
                        // 关键：用 setSelection(line, col, false) 只移动光标、不触发 ensurePositionVisible
                        // 的滚动动画，避免「先滚到光标再 restoreViewport 拉回」造成切换后视角反复跳动。
                        val rLine = restore.cursorLine.coerceIn(0, (editor.text.lineCount - 1).coerceAtLeast(0))
                        val rCol = restore.cursorColumn.coerceIn(0, editor.text.getColumnCount(rLine))
                        editor.setSelection(rLine, rCol, false)
                        // 恢复视角到保存位置。实测 setText 后布局基本同步到位：立即执行一次 restoreViewport
                        // 即可命中正确位置（50ms 内的瞬时跳动）。
                        // 在此基础上保留「一次」视角校正：用单次订阅 subscribe（触发一次即自动退订），
                        // 待布局忙转闲、maxY 完整后再校正一次——主要用于长文档刚恢复时 maxY 被短暂夹取
                        // 的场景；若已到位则 restoreViewport 因目标偏移为 0 而完全无动作，不产生可见滚动。
                        // 相比 subscribeAlways 在窗口内反复 reapply，这里最多只有一次校正，不会反复拉回。
                        val targetX = restore.scrollX.coerceAtLeast(0)
                        val targetY = restore.scrollY.coerceAtLeast(0)
                        if (restoreGen.gen != myGen) {
                            // 已切换到其它文件/进行下一次替换：跳过本文件恢复
                        } else {
                            editor.restoreViewport(targetX, targetY)
                            Log.d(TAG, "RESTORE-once scroll=($targetX,$targetY) maxY=${editor.getScrollMaxY()} curY=${editor.getOffsetY()}")
                            // 单次订阅：布局完成事件后做一次校正，触发即退订，不会反复滚动（sora 仅提供 subscribeAlways）
                            var correctReceipt: SubscriptionReceipt<LayoutStateChangeEvent>? = null
                            correctReceipt = editor.subscribeAlways(LayoutStateChangeEvent::class.java) { e ->
                                if (restoreGen.gen != myGen) { correctReceipt?.unsubscribe(); return@subscribeAlways }
                                if (!e.isLayoutBusy) {
                                    correctReceipt?.unsubscribe()   // 只校正一次
                                    editor.restoreViewport(targetX, targetY)
                                    Log.d(TAG, "RESTORE-correct maxY=${editor.getScrollMaxY()} curY=${editor.getOffsetY()}")
                                }
                            }
                            // 恢复自动滚动属性（关闭高度收缩自动滚动）
                            editor.postDelayed({
                                if (restoreGen.gen == myGen) props.adjustToSelectionOnResize = true
                            }, 250)
                        }
                        // 恢复请求已消费：宿主清空 pending，避免后续中英切换复用同一状态
                        onRestoreViewStateConsumed()
                        Log.d(TAG, "RESTORE-switch done line=$rLine col=$rCol len=${newText.length}")
                    } else {
                        val targetColumn = column.coerceAtMost(editor.text.getColumnCount(line))
                        editor.setSelection(line, targetColumn)
                    }
                    isApplyingExternalText = false
                    // 换文件/恢复视图后复位顶部栏显隐的滚动跟踪，避免旧文件残留的累计值让首次拖动误触发
                    scrollTrack.lastY = editor.getOffsetY()
                    scrollTrack.dir = 0
                    scrollTrack.accum = 0f
                    scrollTrack.toggleDir = 0
                    if (restore == null) {
                        // 稳定化：setText 后的异步重排/TextMate 分析期间可能多次触发内部滚动把视角漂离目标行，
                        // 这里用少量递增延时做「光标行垂直居中」覆盖（代次校验防串扰），确保停稳在目标位置。
                        val stepsMs = longArrayOf(0L, 60L, 150L, 320L, 700L)
                        for (d in stepsMs) {
                            editor.postDelayed({
                                if (restoreGen.gen != myGen) return@postDelayed
                                if (line < editor.text.lineCount) {
                                    val c = editor.cursor
                                    editor.centerCursorOnVisible(c.leftLine, c.leftColumn)
                                    Log.d(TAG, "RESET-stabilize d=${d}ms line=${c.leftLine} col=${c.leftColumn} curY=${editor.getOffsetY()} maxY=${editor.getScrollMaxY()}")
                                }
                            }, d)
                        }
                        // 稳定期结束后恢复自动滚动属性，并做最后一次居中兜底，确保最终停留位置正确。
                        editor.postDelayed({
                            if (restoreGen.gen != myGen) return@postDelayed
                            props.adjustToSelectionOnResize = true
                            if (line < editor.text.lineCount) {
                                val c = editor.cursor
                                editor.centerCursorOnVisible(c.leftLine, c.leftColumn)
                                Log.d(TAG, "RESET-final line=${c.leftLine} col=${c.leftColumn} curY=${editor.getOffsetY()} selLine=${editor.cursor.leftLine}")
                            }
                        }, 1200)
                        Log.d(TAG, "RESET-switch done line=$line col=$column len=${newText.length}")
                    }
                }
            }
        }
    }

    // 活跃探测代理函数：光标前一字符是触发符号且补全窗口未打开时，强制重请求一次。
    // 用于弥补 tap 移动光标时 sora 先 hide() 掉 SelectionChangeEvent 重触发的缺口。
    fun activeProbeTrigger(editorIn: CodeEditor?) {
        val editor = editorIn ?: editorRef.value ?: return
        if (editor.hasComposingText()) return // 组合输入中不打扰
        val completion = editor.getComponent(EditorAutoCompletion::class.java)
        if (completion.isShowing() || completion.isCompletionInProgress) return
        try {
            val cur = editor.cursor
            val col = cur.leftColumn
            val line = cur.leftLine
            // 节补全兜底：与 devValueCompletion 无关，仅受节补全开关控制。
            // 光标在节内空行（光标前全空白、不以 [ 开头、行内无冒号）时强制请求补全，
            // 节识别交由 CompletionProvider 的 fallback 现场定位，不依赖 currentSectionName 注入状态。
            if (col > 0 && sectionCompletionEnabled) {
                val lineText = editor.text.getLineString(line)
                if (lineText.indexOf(':') < 0) {
                    var blankBefore = true
                    for (i in 0 until col) {
                        val c = lineText[i]
                        if (c != ' ' && c != '\t') { blankBefore = false; break }
                    }
                    val startsWithBracket = lineText.trimStart().startsWith("[")
                    if (blankBefore && !startsWithBracket) {
                        Log.d("RW_SECTION_COMPL", "activeProbe trigger line=$line col=$col")
                        (completion as? NoEnterCommitAutoCompletion)?.requireCompletionNow()
                            ?: completion.requireCompletion()
                        return
                    }
                }
            }

            if (!SettingsManager.devValueCompletion) return
            if (editor.hasComposingText()) return
            val cur2 = editor.cursor
            val col2 = cur2.leftColumn
            val line2 = cur2.leftLine
            if (col2 <= 0) return
            val prev = editor.text.charAt(line2, col2 - 1)
            if (":=()+-*/%<>{,".indexOf(prev) < 0) return
            val lineText2 = editor.text.getLineString(line2)
            // 行内无冒号时仅多行值块续行（如 用逻辑设置资源:""" 块内 资源名=）允许触发，
            // 其余无冒号行（[资源] 节裸 name=value 等）不触发，避免刷全表
            if (lineText2.indexOf(':') <= 0 && !isMultilineBlockValueRhs(editor, line2, col2)) return
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
    LaunchedEffect(isDarkTheme, highlightTheme, bgColor, darkTokenColors, rainbowSettingsTick, themeRefreshTick) {
        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        val barBg = themeBackgroundColor(isDarkTheme)
        editorRef.value?.let { editor ->
            applyEditorTheme(editor, isDarkTheme, highlightTheme, bgColor, darkTokenColors, resolvedBg)
            // 主题变化可能改变节名高光/电池底色，重新应用行样式
            refreshLineStyles(editor)
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
            bar.findViewWithTag<ImageButton>("palette_quick")
                ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)
                ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            bar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
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
            updateToolbarIcons(toolbar, symbolBarRef.value, canUndo, canRedo)
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

    /**
     * 构建编辑器语言：有自定义高亮规则（开发者模式→规则）时用合并语法的独立注册表，
     * 否则走原单例注册表。规则变化后由 [highlightRulesRevision] 触发的重建复用本函数。
     */
    fun buildIniLanguage(): IniLanguage? {
        // 只调一次：有自定义规则时拿到合并语法的注册表，否则 null → 走原单例注册表
        val customRegistry = HighlightRuleManager.registryWithCustomRules(context)
        Log.d(
            TAG,
            "buildIniLanguage: customGrammar=${customRegistry != null}, " +
                "rules=${HighlightRuleManager.totalActiveRules()}, enabled=${HighlightRuleManager.enabled}"
        )
        val tmLanguage = try {
            if (SettingsManager.rainbowBrackets) {
                try {
                    RainbowTextMateLanguage.create("source.ini", false, customRegistry)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create rainbow language, fallback to plain TextMate", e)
                    TextMateLanguage.create("source.ini", customRegistry, false)
                }
            } else {
                TextMateLanguage.create("source.ini", customRegistry, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TextMate language", e)
            null
        } ?: return null
        return IniLanguage(tmLanguage).apply {
            this.completionProvider = completionProvider
            this.currentSectionName = currentSectionName
            this.sectionFilters = sectionFilters
            this.sectionCompletionEnabled = sectionCompletionEnabled
            this.bracketDiagnosticsEnabled = SettingsManager.bracketDiagnostics
            this.bracketIssuesListener = onBracketIssues
        }
    }

    // 自定义高亮规则变化（开发者模式→规则里增删改）：就地重建编辑器语言，无需重启 App。
    // 仅修订号变化时执行，首次组合时跳过（初次创建已在 AndroidView factory 里做）。
    var appliedRulesRevision by remember { mutableStateOf(highlightRulesRevision) }
    LaunchedEffect(highlightRulesRevision) {
        if (highlightRulesRevision == appliedRulesRevision) return@LaunchedEffect
        appliedRulesRevision = highlightRulesRevision
        val editor = editorRef.value ?: return@LaunchedEffect
        val old = languageRef.value
        val rebuilt = buildIniLanguage() ?: return@LaunchedEffect
        if (active) editor.setEditorLanguage(rebuilt)
        languageRef.value = rebuilt
        old?.destroy()
        refreshLineStyles(editor)
        Log.d(TAG, "Custom highlight rules changed → language rebuilt (revision=$highlightRulesRevision)")
    }

    // 前台/后台切换：非前台实例停用 TextMate 高亮（释放渲染），前台恢复语言。
    // 编辑器就绪后（editorRef 非 null）或 active 变化时执行。
    LaunchedEffect(active, editorRef.value) {
        val editor = editorRef.value ?: return@LaunchedEffect
        if (active) {
            languageRef.value?.let { language ->
                if (editor.editorLanguage !== language) editor.setEditorLanguage(language)
            }
            // 重新激活时对现有配色方案重放自定义颜色（背景/行号/补全/滚动条/高亮）。
            // 多实例下任一实例创建或切换主题都会触发全局 ThemeRegistry 派发，把其它存活实例
            // 的 TextMateColorScheme 自定义颜色清空回退为默认主题色，导致切回旧实例时背景变深。
            // 这里仅重放颜色、不重建方案，避免再次触发全局派发污染其它实例。
            editor.colorScheme?.let { scheme ->
                applyEditorColors(
                    editor, scheme, highlightTheme, darkTokenColors,
                    resolveEditorBackground(bgColor, isDarkTheme)
                )
            }
            refreshLineStyles(editor)
        } else {
            if (editor.editorLanguage != null) editor.setEditorLanguage(null)
        }
        Log.d(TAG, "active=$active highlight=${editor.editorLanguage != null}")
    }

    // 屏蔽词/可灯泡 key 集合变化时刷新行图标
    LaunchedEffect(blocklistEnabled, blockableStringKeys) {
        editorRef.value?.let { refreshLineStyles(it) }
    }

    // 节名行高亮开关/颜色变化时刷新行样式
    LaunchedEffect(sectionHighlightTick) {
        if (sectionHighlightTick > 0) {
            editorRef.value?.let { refreshLineStyles(it) }
        }
    }

    // 主题/背景/高亮开关切换时刷新节名行高亮：不关闭节行高亮的条件下（切换深浅色、高亮主题、自定义背景、
    // 切中英文等），重新落位高亮底色。切换代码高亮主题（TextMate）会触发 sora 异步重新分析/着色，异步
    // 完成后可能覆盖我们刚才 set 的行样式，所以除了立即刷新外，还要沿递增时序多次延迟重刷，确保在异步
    // 落定后最后一次刷新生效，避免主题切换后节行高亮「看似被清掉」。真正关闭开关时才移除行高亮。
    val highlightEnabledState = ThemeState.sectionHighlightEnabled
    // darkTokenColors 必须在 key 里：改色会触发 TextMate 异步重着色、冲掉行样式，
    // 没有它的话下面的递增延迟重刷不跑 → 节行高光失效（要重开文件才恢复）
    LaunchedEffect(isDarkTheme, bgColor, highlightTheme, highlightEnabledState, ThemeState.darkTokenColors) {
        if (!highlightEnabledState) {
            editorRef.value?.let { refreshLineStyles(it) }
            return@LaunchedEffect
        }
        fun refresh() { editorRef.value?.let { refreshLineStyles(it) } }
        refresh()
        // 递增延迟多处重刷，覆盖 TextMate 异步重新着色的时序窗口，最后一次保持高亮。
        editorRef.value?.let { editor ->
            listOf(100L, 250L, 450L, 650L, 900L).forEach { delayMs ->
                editor.postDelayedInLifecycle({ refresh() }, delayMs)
            }
        }
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

                // 自定义高亮规则（开发者模式→规则）：有启用规则时用「内置 + 用户规则」合并语法的独立注册表，
                // 否则返回 null，走原单例注册表（行为与历史完全一致）
                val language = buildIniLanguage()
                if (language != null) {
                    setEditorLanguage(language)
                    languageRef.value = language
                    Log.d(TAG, "Language created, completionProvider=${completionProvider != null}, rules=${HighlightRuleManager.totalActiveRules()}")
                } else {
                    Log.e(TAG, "No TextMate language set; highlighting will be disabled")
                }

                var cachedText = ""
                // 撤销/重做状态去重缓存，避免每次按键触发 Compose 重组
                var lastCanUndo: Boolean? = null
                var lastCanRedo: Boolean? = null
                // 实时上报视图状态：光标/滚动变化即同步给宿主缓存，切走时直接读缓存，
                // 避免单活跃实例下 View 先销毁导致 onDispose 读不到有效光标/滚动。
                fun reportViewState(ed: CodeEditor) {
                    try {
                        onViewStateChanged?.invoke(EditorViewState(
                            cursorLine = ed.cursor.leftLine,
                            cursorColumn = ed.cursor.leftColumn,
                            scrollX = ed.getOffsetX(),
                            scrollY = ed.getOffsetY()
                        ))
                    } catch (_: Exception) { }
                }
                // 手动滚动判定：仅当手指按住编辑器时产生的滚动才计入顶部栏显隐，
                // 输入法弹出对焦等程序化滚动（此时无手指）一律忽略。返回 false 不拦截，交给 sora 正常处理。
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> scrollTrack.touching = true
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> scrollTrack.touching = false
                    }
                    false
                }
                subscribeEvent(ScrollEvent::class.java) { event, _ ->
                    reportViewState(event.editor)
                    // 顶部栏显隐联动：只统计手指按住时的手动滚动方向 + 累计距离阈值，滚动停顿 250ms 后上报一次。
                    // 输入法弹出对焦、tap 光标定位、恢复视图等程序化滚动（无手指按住）不计入。
                    val st = scrollTrack
                    val y = event.editor.getOffsetY()
                    val dy = y - st.lastY
                    st.lastY = y
                    if (st.touching) {
                        val dir = when {
                            dy > 24f -> 1      // 内容下滚/手指上滑
                            dy < -24f -> -1    // 内容上滚/手指下滑
                            else -> 0
                        }
                        if (dir != 0) {
                            if (dir != st.dir) {
                                st.dir = dir
                                st.accum = 0f
                            }
                            st.accum += if (dy < 0) -dy else dy
                            // 累计超过阈值即立即上报方向（折叠是纯视觉浮层，不碰滚动，无需等滚动停顿）
                            if (st.accum > 150f && dir != st.toggleDir) {
                                st.toggleDir = dir
                            }
                        }
                    }
                    if (st.toggleDir != 0) {
                        val d = st.toggleDir
                        st.toggleDir = 0
                        currentOnScrollDirectionChanged?.invoke(d)
                    }
                }
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
                    scheduleLineStyleRefresh(event.editor)
                }

                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    onCursorChange?.invoke(event.editor.cursor.left)
                    reportViewState(event.editor)
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
                            val valueTriggerChar = ":=()+-*/%<>{,".indexOf(if (column > 0) editor.text.charAt(line, column - 1) else '\u0000') >= 0
                            if (column > 0 && valueTriggerChar) {
                                val completion = editor.getComponent(EditorAutoCompletion::class.java)
                                editor.postDelayedInLifecycle({
                                    val cur = editor.cursor
                                    val col = cur.leftColumn
                                    if (col > 0 && ":=(+-*/%<>{,".indexOf(editor.text.charAt(cur.leftLine, col - 1)) >= 0) {
                                        val currentLineText = editor.text.getLineString(cur.leftLine)
                                        // sora 已自动弹出时不重复触发；否则重置 requestTime 绕过
                                        // 70ms 节流立即弹出（提交后窗口已 hide，无需延迟等待）。
                                        // 窗口已在显示（如 @memory 名字: 的变量名候选，: 是阶段切换符）
                                        // 也要强制重求值，否则切不到类型补全。
                                        // 行内无冒号时仅多行值块续行（用逻辑设置资源:""" 块内 资源名=）允许触发
                                        if (currentLineText.indexOf(':') > 0 ||
                                            isMultilineBlockValueRhs(editor, cur.leftLine, col)
                                        ) {
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
                        // 节补全：光标在节内空行时主动触发补全。
                        // 不再依赖 currentSectionName（Sora 侧可能注入滞后/为 null），空行即触发，
                        // 节识别交由 CompletionProvider 的 fallback 现场定位当前节。
                        if (sectionCompletionEnabled) {
                            var allBlankBefore = true
                            for (i in 0 until column) {
                                val c = lineText[i]
                                if (c != ' ' && c != '\t') { allBlankBefore = false; break }
                            }
                            val startsWithBracket = lineText.isNotEmpty() && lineText.trimStart().startsWith("[")
                            if (allBlankBefore && !startsWithBracket) {
                                Log.d("RW_SECTION_COMPL", "selectionChange trigger line=$line col=$column section=$currentSectionName")
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
                        if (col > 0 && ":=(+-*/%<>{,".contains(editor.text.charAt(editor.cursor.leftLine, col - 1))) {
                            val completion = editor.getComponent(EditorAutoCompletion::class.java)
                            editor.postDelayedInLifecycle({
                                val c = editor.cursor
                                val cl = c.leftColumn
                                if (cl > 0 && ":=(+-*/%<>{,".contains(editor.text.charAt(c.leftLine, cl - 1))) {
                                    // 行内含冒号即触发：窗口未显示则立即弹出，已显示（如 @memory 名字:
                                    // 的变量名候选）则强制重求值，确保 : 能切到类型/值补全。
                                    // 行内无冒号时仅多行值块续行（用逻辑设置资源:""" 块内 资源名=）允许触发
                                    if (editor.text.getLineString(c.leftLine).indexOf(':') > 0 ||
                                        isMultilineBlockValueRhs(editor, c.leftLine, cl)
                                    ) {
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
                refreshLineStyles(this)
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

            // 符号栏左侧第二格：调色盘快捷入口（点击打开编辑器背景色选择）
            val paletteQuickButton = android.widget.ImageButton(ctx).apply {
                tag = "palette_quick"
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_palette))
                setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                resolveRipple(ctx)?.let { background = it }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener { onToolbarAction?.invoke(EditorToolbarAction.COLOR_PICKER) }
            }

            // 符号栏最左侧：重做按钮（从功能栏移入，方便拇指点击）
            val redoButton = android.widget.ImageButton(ctx).apply {
                tag = EditorToolbarAction.REDO.name
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_redo))
                setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                resolveRipple(ctx)?.let { background = it }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener { onToolbarAction?.invoke(EditorToolbarAction.REDO) }
            }

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(barBg)
                // 重做按钮位于符号栏最左侧
                addView(redoButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                addView(pasteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                // 调色盘快捷入口（位于粘贴按钮右侧 = 左侧第二格），不被 applySymbols 移除
                addView(paletteQuickButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
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
                updateToolbarIcons(this, symbolBarRef.value, canUndo, canRedo)
                setBackgroundColor(barBg)
            }

            // 底部栏：功能栏 25% + 符号栏 75%（节名显示已移至顶部 tab 栏，不放底部）
            val bottomBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = if (showBottomToolbar) android.view.View.VISIBLE else android.view.View.GONE
                setBackgroundColor(barBg)
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(0, barHeight, 0.25f)
                )
                addView(
                    symbolPanel,
                    LinearLayout.LayoutParams(0, barHeight, 0.75f)
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
                bar.findViewWithTag<ImageButton>("palette_quick")
                    ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)
                    ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                bar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
                    ?.setColorFilter(if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
            toolbarRef.value?.let { toolbar ->
                val color = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                toolbar.findViewWithTag<android.widget.Button>(EditorToolbarAction.AT_REFERENCE.name)?.setTextColor(color)
                listOf(
                    EditorToolbarAction.UNDO,
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
            (container as? LinearLayout)?.getChildAt(0)?.let { it as? CodeEditor }?.release()
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
        applyEditorColors(editor, scheme, highlightTheme, darkTokenColors, resolvedBg)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to apply editor theme", e)
    }
}

/**
 * 将自定义颜色（背景、行号、补全窗口、滚动条、深色 UI 高亮、彩虹括号）写入配色方案。
 * 与 [applyEditorTheme] 拆分：重建配色方案（editor.colorScheme = 新 TextMateColorScheme）会触发
 * 全局 ThemeRegistry 派发，把其它存活实例的自定义颜色一并清空。因此「实例重新激活」时只调用
 * 本函数对现有配色方案重放颜色，避免再次污染其它实例、也保证前台实例始终使用用户自定义背景。
 */
private fun applyEditorColors(
    editor: CodeEditor,
    scheme: EditorColorScheme,
    highlightTheme: String,
    darkTokenColors: DarkThemeColors,
    resolvedBg: Int
) {
    // 先计算背景明暗，用于滚动条等判断
    val isDark = android.graphics.Color.luminance(resolvedBg) < 0.5

    // 点击 () [] 等符号时的匹配高亮（光标处与配对符号）。
    // 「深色高亮 dark」始终生效；custom 由配色数据「UI 高亮」类别内的「激活高亮」开关控制（深色预设默认勾选）。
    // 颜色取该主题的 UI 高亮色（dark=默认深色 UI，custom=用户编辑的 UI 高亮色）。
    val delimiterHighlightActive =
        highlightTheme == "dark" || (highlightTheme == "custom" && darkTokenColors.highlightDelimiters)
    if (delimiterHighlightActive) {
        try {
            val ui = if (highlightTheme == "dark") DarkThemeColors.Default.ui else darkTokenColors.ui
            val highlight = android.graphics.Color.parseColor(ui)
            val highlightAlpha = (highlight and 0x00FFFFFF) or 0x44000000
            scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, highlightAlpha)
            scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, highlightAlpha)
            // 当前行背景更淡（0x22：约 13.3%），避免整行色块过重
            scheme.setColor(EditorColorScheme.CURRENT_LINE, (highlight and 0x00FFFFFF) or 0x22000000)
            scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, highlight)
            scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, (highlight and 0x00FFFFFF) or 0x22000000)
            scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, highlight)
            scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER, highlight)
            scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, highlight)
            scheme.setColor(EditorColorScheme.SELECTION_INSERT, highlight)
            scheme.setColor(EditorColorScheme.SELECTION_HANDLE, highlight)
            Log.d(TAG, "Applied delimiter highlight (ui=$ui, theme=$highlightTheme, active=$delimiterHighlightActive)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse delimiter highlight color", e)
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
    // 给 View 设置不透明 Android 背景，避免刚打开时首帧 onDraw 前透出外层主题色
    editor.setBackgroundColor(resolvedBg)
    editor.invalidate()
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

/**
 * 节名行高光底色：蓝调半透明覆盖，依据编辑器背景明暗自适应。
 * 深底用亮蓝叠加，浅底用深蓝叠加，确保两种主题下都清晰可辨。
 */
internal fun sectionHighlightColor(resolvedBg: Int): Int {
    return if (android.graphics.Color.luminance(resolvedBg) < 0.5f) 0x334A82C8 else 0x263A629F
}

/**
 * 节名行高亮最终颜色：优先使用用户自定义色，未自定义（空/非法）时退回自动自适应默认色。
 */
internal fun sectionHighlightResolvedColor(resolvedBg: Int, custom: String): Int {
    val parsed = custom.takeIf { it.isNotBlank() }
        ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
    return parsed ?: sectionHighlightColor(resolvedBg)
}

private fun updateToolbarIcons(toolbar: LinearLayout, symbolBar: LinearLayout?, canUndo: Boolean, canRedo: Boolean) {
    val undo = toolbar.findViewWithTag<ImageButton>(EditorToolbarAction.UNDO.name)
    // 重做按钮已移至符号栏最左侧，先从符号栏查找，找不到再回退工具栏
    val redo = symbolBar?.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
        ?: toolbar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
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
        EditorToolbarAction.REFERENCE to R.drawable.ic_reference,
        EditorToolbarAction.HINT to R.drawable.ic_hint,
        EditorToolbarAction.UNDO to R.drawable.ic_undo
    )
    val density = ctx.resources.displayMetrics.density
    val hPadding = (4 * density).toInt()

    items.forEach { (action, iconRes) ->
        if (iconRes != null) {
            val btn = ImageButton(ctx).apply {
                tag = action.name
                setImageDrawable(ContextCompat.getDrawable(ctx, iconRes))
                setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN)
                ripple?.constantState?.newDrawable()?.mutate()?.let { background = it }
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
                ripple?.constantState?.newDrawable()?.mutate()?.let { background = it }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setPadding(0, 0, 0, 0)
                setOnClickListener { onToolbarAction?.invoke(action) }
            }
            toolbar.addView(btn)
        }
    }

    return toolbar
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

