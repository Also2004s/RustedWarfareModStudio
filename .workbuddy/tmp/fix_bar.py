# -*- coding: utf-8 -*-
"""把 SoraCodeEditor.kt 的底部快捷栏取色从「编辑器背景明暗」回退到「App 深浅主题 + 主题背景色」。
每个替换点都做唯一性校验(count==1)，任一失败即中止且不写盘。"""
import sys

P = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/editor/SoraCodeEditor.kt"

REPS = []

REPS.append(('''/**
 * 底部快捷栏/工具栏是否使用深色配色。
 *
 * 判据 = **编辑器实际背景色**的亮度，与 App 深浅开关（is_dark_theme）解耦：
 * 存在「浅色 App + 深色编辑器背景」的组合，若按 App 深浅取色，栏底与图标会变成纯白，
 * 与深色编辑器背景割裂。此处与编辑器内部 `applyEditorColors` 的 `isDark` 同口径。
 */
internal fun editorBarIsDark(bgColor: String, isDarkTheme: Boolean): Boolean =
    android.graphics.Color.luminance(resolveEditorBackground(bgColor, isDarkTheme)) < 0.5f

/**
 * 底部快捷栏底色 = 表面色（与主题里的 `EditorBarSurface` 同值），
 * 与底部「方块+」按钮及横栏圆按钮完全同色，深/浅跟随编辑器背景明暗。
 */
internal fun themeBarBackgroundColor(barDark: Boolean): Int =
    (if (barDark) DarkSurface else LightSurface).toArgb()

/**
 * 底部快捷栏前景色（图标/文字）= 反色（与主题里的 `EditorBarOnBackground` 同值），
 * 与「方块+」按钮的图标色同源。
 */
internal fun themeBarContentColor(barDark: Boolean): Int =
    (if (barDark) DarkOnBg else LightOnBg).toArgb()''', '''/**
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
    (if (isDarkTheme) DarkOnBg else LightOnBg).toArgb()'''))

REPS.append(('''        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        // 底部快捷栏配色跟随编辑器**实际背景的明暗**（非 App 深浅开关）：
        // 底色 = 表面色，图标/文字 = 反色，与底部「方块+」按钮、横栏圆按钮同源。
        val barDark = editorBarIsDark(bgColor, isDarkTheme)
        val barBg = themeBarBackgroundColor(barDark)
        val barFg = themeBarContentColor(barDark)''', '''        val resolvedBg = resolveEditorBackground(bgColor, isDarkTheme)
        // 底部快捷栏配色跟随 App 深浅主题：底色 = 主题背景色，图标/文字 = 主题反色，
        // 与底部「方块+」按钮、横栏圆按钮同源。
        val barBg = themeBarBackgroundColor(isDarkTheme)
        val barFg = themeBarContentColor(isDarkTheme)'''))

REPS.append(('''            applySymbols(bar, customSymbols, editorBarIsDark(bgColor, isDarkTheme), onSymbolInsert)''',
             '''            applySymbols(bar, customSymbols, isDarkTheme, onSymbolInsert)'''))

REPS.append(('''            SoraEditorInitializer.init(ctx)

            val barDark = editorBarIsDark(bgColor, isDarkTheme)
            val barBg = themeBarBackgroundColor(barDark)
            val barFg = themeBarContentColor(barDark)''', '''            SoraEditorInitializer.init(ctx)

            val barBg = themeBarBackgroundColor(isDarkTheme)
            val barFg = themeBarContentColor(isDarkTheme)'''))

REPS.append(('''            applySymbols(inner, customSymbols, editorBarIsDark(bgColor, isDarkTheme), onSymbolInsert)''',
             '''            applySymbols(inner, customSymbols, isDarkTheme, onSymbolInsert)'''))

REPS.append(('''            val toolbar = createToolbar(ctx, onToolbarAction, editorBarIsDark(bgColor, isDarkTheme)).apply {''',
             '''            val toolbar = createToolbar(ctx, onToolbarAction, isDarkTheme).apply {'''))

REPS.append(('''            // 每次重构图/视图重建后同步一次底部栏配色（表面色底 + 反色图标/文字，跟随编辑器背景明暗）
            val barDark = editorBarIsDark(bgColor, isDarkTheme)
            val barBg = themeBarBackgroundColor(barDark)
            val barFg = themeBarContentColor(barDark)''', '''            // 每次重构图/视图重建后同步一次底部栏配色（主题背景色底 + 反色图标/文字）
            val barBg = themeBarBackgroundColor(isDarkTheme)
            val barFg = themeBarContentColor(isDarkTheme)'''))

REPS.append(('''private fun createToolbar(
    ctx: Context,
    onToolbarAction: ((EditorToolbarAction) -> Unit)?,
    barDark: Boolean
): LinearLayout {''', '''private fun createToolbar(
    ctx: Context,
    onToolbarAction: ((EditorToolbarAction) -> Unit)?,
    isDarkTheme: Boolean
): LinearLayout {'''))

REPS.append(('''    // 功能栏图标色跟随编辑器背景明暗取反色（与底部「方块+」同源）
    val iconColor = themeBarContentColor(barDark)''', '''    // 功能栏图标色跟随主题反色（与底部「方块+」同源）
    val iconColor = themeBarContentColor(isDarkTheme)'''))

REPS.append(('''private fun applySymbols(
    bar: LinearLayout,
    symbols: List<Pair<String, String>>,
    barDark: Boolean,
    onSymbolInsert: ((String) -> Unit)?
) {''', '''private fun applySymbols(
    bar: LinearLayout,
    symbols: List<Pair<String, String>>,
    isDarkTheme: Boolean,
    onSymbolInsert: ((String) -> Unit)?
) {'''))

REPS.append(('''    val textColor = themeBarContentColor(barDark)''',
             '''    val textColor = themeBarContentColor(isDarkTheme)'''))

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
