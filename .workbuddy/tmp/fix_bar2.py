# -*- coding: utf-8 -*-
"""SoraCodeEditor.kt：底部栏/功能栏取色统一走 Color.kt 的 RustedBackground*/RustedOnBackground*，
不再在 editor 包内重复定义颜色；同时删掉随之无用的 isDarkTheme 参数与 import。"""
import sys

P = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/editor/SoraCodeEditor.kt"

REPS = []

# 1) import：换成主题色来源；删掉不再使用的 toArgb / DarkOnBg / LightOnBg
REPS.append(('''import androidx.compose.ui.graphics.toArgb
''', ''))

REPS.append(('''import com.rwmodstudio.ui.theme.DarkOnBg
import com.rwmodstudio.ui.theme.LightOnBg
import com.rwmodstudio.ui.theme.editorTypeface''',
             '''import com.rwmodstudio.ui.theme.RustedBackgroundArgb
import com.rwmodstudio.ui.theme.RustedOnBackgroundArgb
import com.rwmodstudio.ui.theme.editorTypeface'''))

# 2) 取色函数：直接复用全局主题色
REPS.append(('''/**
 * 底部快捷栏底色 = 主题背景色（与主题里的 `RustedBackground` 同值：深 #1E1E1E / 浅 #F0F0F0），
 * 与底部「方块+」按钮及横栏圆按钮完全同色，跟随 App 深浅主题切换。
 * 不用表面色 `#FFFFFF`：浅色主题下纯白比主题底色更刺眼。
 */
internal fun themeBarBackgroundColor(isDarkTheme: Boolean): Int = themeBackgroundColor(isDarkTheme)

/**
 * 底部快捷栏前景色（图标/文字）= 主题反色（与主题里的 `RustedOnBackground` 同值），
 * 与「方块+」按钮的图标色同源。
 */
internal fun themeBarContentColor(isDarkTheme: Boolean): Int =
    (if (isDarkTheme) DarkOnBg else LightOnBg).toArgb()''',
             '''/**
 * 底部快捷栏底色 = **全局主题背景色**（直接取主题里的 `RustedBackground`：深 #1E1E1E / 浅 #F0F0F0），
 * 与底部「方块+」按钮及横栏圆按钮完全同源同值，跟随 App 深浅主题切换。
 * 不用表面色 `#FFFFFF`：浅色主题下纯白比主题底色更刺眼。
 */
internal fun themeBarBackgroundColor(): Int = RustedBackgroundArgb

/**
 * 底部快捷栏前景色（图标/文字）= **全局主题反色**（直接取 `RustedOnBackground`），
 * 与「方块+」按钮的图标色同源。
 */
internal fun themeBarContentColor(): Int = RustedOnBackgroundArgb'''))

# 3) 三处取值点
REPS.append(('''        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        // 底部快捷栏配色跟随 App 深浅主题：底色 = 主题背景色，图标/文字 = 主题反色，
        // 与底部「方块+」按钮、横栏圆按钮同源。
        val barBg = themeBarBackgroundColor(isDarkTheme)
        val barFg = themeBarContentColor(isDarkTheme)''',
             '''        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        // 底部快捷栏配色 = 全局主题色（底色取主题背景色、图标取主题反色），
        // 与底部「方块+」按钮、横栏圆按钮同源。
        val barBg = themeBarBackgroundColor()
        val barFg = themeBarContentColor()'''))

REPS.append(('''            SoraEditorInitializer.init(ctx)

            val barBg = themeBarBackgroundColor(isDarkTheme)
            val barFg = themeBarContentColor(isDarkTheme)''',
             '''            SoraEditorInitializer.init(ctx)

            val barBg = themeBarBackgroundColor()
            val barFg = themeBarContentColor()'''))

REPS.append(('''            // 每次重构图/视图重建后同步一次底部栏配色（主题背景色底 + 反色图标/文字）
            val barBg = themeBarBackgroundColor(isDarkTheme)
            val barFg = themeBarContentColor(isDarkTheme)''',
             '''            // 每次重构图/视图重建后同步一次底部栏配色（全局主题色：背景色底 + 反色图标/文字）
            val barBg = themeBarBackgroundColor()
            val barFg = themeBarContentColor()'''))

# 4) applySymbols / createToolbar 调用点去掉 isDarkTheme
REPS.append(('''            applySymbols(bar, customSymbols, isDarkTheme, onSymbolInsert)''',
             '''            applySymbols(bar, customSymbols, onSymbolInsert)'''))

REPS.append(('''            applySymbols(inner, customSymbols, isDarkTheme, onSymbolInsert)''',
             '''            applySymbols(inner, customSymbols, onSymbolInsert)'''))

REPS.append(('''            val toolbar = createToolbar(ctx, onToolbarAction, isDarkTheme).apply {''',
             '''            val toolbar = createToolbar(ctx, onToolbarAction).apply {'''))

# 5) createToolbar 签名与内部
REPS.append(('''private fun createToolbar(
    ctx: Context,
    onToolbarAction: ((EditorToolbarAction) -> Unit)?,
    isDarkTheme: Boolean
): LinearLayout {''',
             '''private fun createToolbar(
    ctx: Context,
    onToolbarAction: ((EditorToolbarAction) -> Unit)?
): LinearLayout {'''))

REPS.append(('''    // 功能栏图标色跟随主题反色（与底部「方块+」同源）
    val iconColor = themeBarContentColor(isDarkTheme)''',
             '''    // 功能栏图标色 = 全局主题反色（与底部「方块+」同源）
    val iconColor = themeBarContentColor()'''))

# 6) applySymbols 签名与内部
REPS.append(('''private fun applySymbols(
    bar: LinearLayout,
    symbols: List<Pair<String, String>>,
    isDarkTheme: Boolean,
    onSymbolInsert: ((String) -> Unit)?
) {''',
             '''private fun applySymbols(
    bar: LinearLayout,
    symbols: List<Pair<String, String>>,
    onSymbolInsert: ((String) -> Unit)?
) {'''))

REPS.append(('''    val textColor = themeBarContentColor(isDarkTheme)''',
             '''    val textColor = themeBarContentColor()'''))

raw = open(P, "rb").read()
txt = raw.decode("utf-8")
crlf = b"\r\n" in raw

def norm(x):
    return x.replace("\n", "\r\n") if crlf else x

for i, (o, n) in enumerate(REPS):
    o2, n2 = norm(o), norm(n)
    c = txt.count(o2)
    if c != 1:
        print("FAIL rep#%d count=%d" % (i, c))
        sys.exit(1)
    txt = txt.replace(o2, n2)

open(P, "wb").write(txt.encode("utf-8"))
print("OK crlf=%s reps=%d" % (crlf, len(REPS)))
