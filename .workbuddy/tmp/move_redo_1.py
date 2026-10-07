# -*- coding: utf-8 -*-
"""SoraCodeEditor.kt：移除符号栏最左侧的重做按钮及其配套死代码
（按钮定义/addView、两处 REDO 颜色重绘、updateToolbarIcons、canUndo/canRedo 参数与 LaunchedEffect）。
EditorToolbarAction.REDO 枚举值保留（EditorScreen 的 when 分支仍引用）。"""
import sys

P = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/editor/SoraCodeEditor.kt"

REPS = []

# 1) 删按钮定义
REPS.append(('''            // 符号栏最左侧：重做按钮（从功能栏移入，方便拇指点击）
            val redoButton = android.widget.ImageButton(ctx).apply {
                tag = EditorToolbarAction.REDO.name
                setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_redo))
                setColorFilter(barFg)
                resolveRipple(ctx)?.let { background = it }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener { onToolbarAction?.invoke(EditorToolbarAction.REDO) }
            }

            val inner = LinearLayout(ctx).apply {''',
             '''            val inner = LinearLayout(ctx).apply {'''))

# 2) 删 addView
REPS.append(('''                // 重做按钮位于符号栏最左侧
                addView(redoButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
                addView(pasteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))''',
             '''                addView(pasteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))'''))

# 3) 主题 effect 里的颜色重绘（12 空格缩进）
REPS.append(('''            bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)?.setColorFilter(barFg)
            bar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)?.setColorFilter(barFg)
            for (i in 0 until bar.childCount) {''',
             '''            bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)?.setColorFilter(barFg)
            for (i in 0 until bar.childCount) {'''))

# 4) update 块里的颜色重绘（16 空格缩进）
REPS.append(('''                bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)?.setColorFilter(barFg)
                bar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)?.setColorFilter(barFg)
            }''',
             '''                bar.findViewWithTag<ImageButton>(EditorToolbarAction.EDIT_SYMBOLS.name)?.setColorFilter(barFg)
            }'''))

# 5) 删 canUndo / canRedo 参数
REPS.append(('''    canUndo: Boolean = false,
    canRedo: Boolean = false,
    showBottomToolbar: Boolean = true,''',
             '''    showBottomToolbar: Boolean = true,'''))

# 6) 删 LaunchedEffect
REPS.append(('''    // 撤销/重做按钮状态变化时刷新工具栏图标
    LaunchedEffect(canUndo, canRedo) {
        toolbarRef.value?.let { toolbar ->
            updateToolbarIcons(toolbar, symbolBarRef.value, canRedo)
        }
    }

    // 自定义符号栏''',
             '''    // 自定义符号栏'''))

# 7) 删 createToolbar 后的 updateToolbarIcons 调用
REPS.append(('''                toolbarRef.value = this
                updateToolbarIcons(this, symbolBarRef.value, canRedo)
                setBackgroundColor(barBg)''',
             '''                toolbarRef.value = this
                setBackgroundColor(barBg)'''))

# 8) 删 updateToolbarIcons 函数
REPS.append(('''private fun updateToolbarIcons(toolbar: LinearLayout, symbolBar: LinearLayout?, canRedo: Boolean) {
    // 撤销按钮已移至顶部全局菜单栏（可用态由 MainApp 依据 EditorScreen 上报的 canUndo 驱动），
    // 这里只负责符号栏最左侧的重做按钮
    val redo = symbolBar?.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
        ?: toolbar.findViewWithTag<ImageButton>(EditorToolbarAction.REDO.name)
    redo?.alpha = if (canRedo) 1.0f else 0.3f
    redo?.isEnabled = canRedo
}

private fun createToolbar(''',
             '''private fun createToolbar('''))

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
