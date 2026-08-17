package com.rwmodstudio.editor

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rwmodstudio.core.DarkThemeColors
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.ui.theme.editorTypeface
import io.github.rosemoe.sora.langs.textmate.RainbowTextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SmartWrapCodeEditor

private const val READONLY_EDITOR_TAB_WIDTH = 4

/**
 * 只读代码编辑器：用于查看类场景（如继承链）。
 * 复用主编辑器的高亮主题与背景色（applyEditorTheme），文本不可编辑，
 * 行号/字体/自动换行/彩虹括号等跟随当前设置。
 */
@Composable
fun ReadOnlyCodeEditor(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    autoWrap: Boolean = true,
    smartWrap: Boolean = SettingsManager.smartWrap,
    isDarkTheme: Boolean = ThemeState.isDark,
    highlightTheme: String = ThemeState.highlightTheme,
    bgColor: String = ThemeState.bgColor,
    darkTokenColors: DarkThemeColors = ThemeState.darkTokenColors
) {
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }

    // 主题/背景色变化时重新应用（与主编辑器一致）
    LaunchedEffect(isDarkTheme, highlightTheme, bgColor, darkTokenColors) {
        val editor = editorRef.value ?: return@LaunchedEffect
        applyEditorTheme(
            editor,
            isDarkTheme,
            highlightTheme,
            bgColor,
            darkTokenColors,
            resolveEditorBackground(bgColor, isDarkTheme)
        )
    }

    // 文本变化时同步（内容由调用方异步加载后传入）
    LaunchedEffect(text) {
        val editor = editorRef.value ?: return@LaunchedEffect
        if (editor.text.toString() != text) {
            editor.setText(text)
        }
    }

    AndroidView(
        factory = { ctx ->
            SoraEditorInitializer.init(ctx)
            val editor = SmartWrapCodeEditor(ctx, smartWrap).apply {
                isEditable = false
                typefaceText = editorTypeface(ctx)
                isLineNumberEnabled = SettingsManager.devLineNumber
                setTextSize(fontSize)
                isWordwrap = autoWrap
                tabWidth = READONLY_EDITOR_TAB_WIDTH
                setScrollBarEnabled(false)
                applyEditorTheme(
                    this,
                    isDarkTheme,
                    highlightTheme,
                    bgColor,
                    darkTokenColors,
                    resolveEditorBackground(bgColor, isDarkTheme)
                )
                val tmLanguage = try {
                    if (SettingsManager.rainbowBrackets) {
                        try {
                            RainbowTextMateLanguage.create("source.ini", false)
                        } catch (e: Exception) {
                            TextMateLanguage.create("source.ini", false)
                        }
                    } else {
                        TextMateLanguage.create("source.ini", false)
                    }
                } catch (e: Exception) {
                    null
                }
                if (tmLanguage != null) {
                    setEditorLanguage(IniLanguage(tmLanguage))
                }
                setText(text)
            }
            editorRef.value = editor
            editor
        },
        modifier = modifier,
        update = { editor ->
            editorRef.value = editor
        }
    )
}
