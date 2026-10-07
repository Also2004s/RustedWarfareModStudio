# -*- coding: utf-8 -*-
"""EditorScreen.kt：新增重做触发链路（redoTrigger/onRedoTriggerConsumed/onCanRedoChange），
并删掉已不再需要的 canUndo/canRedo 向 SoraCodeEditor 的传参。"""
import sys

P = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/ui/screens/EditorScreen.kt"

REPS = []

# 1) 参数
REPS.append(('''    // 撤销可用态上报：MainApp 顶部撤销按钮据此亮起/变灰（视觉与保存按钮一致）
    onCanUndoChange: ((Boolean) -> Unit)? = null
) {''',
             '''    // 撤销可用态上报：MainApp 顶部撤销按钮据此亮起/变灰（视觉与保存按钮一致）
    onCanUndoChange: ((Boolean) -> Unit)? = null,
    // 顶部全局菜单栏的重做按钮（宿主触发）：递增后编辑器执行一次重做，并立即清零避免切换文件时误触发
    redoTrigger: Int = 0,
    onRedoTriggerConsumed: () -> Unit = {},
    // 重做可用态上报：MainApp 顶部重做按钮据此亮起/变灰（视觉与撤销/保存按钮一致）
    onCanRedoChange: ((Boolean) -> Unit)? = null
) {'''))

# 2) effect
REPS.append(('''    LaunchedEffect(undoTrigger) {
        if (undoTrigger > 0) {
            editorRef?.undo()
            onUndoTriggerConsumed()
        }
    }''',
             '''    LaunchedEffect(undoTrigger) {
        if (undoTrigger > 0) {
            editorRef?.undo()
            onUndoTriggerConsumed()
        }
    }
    // 顶部全局菜单栏「重做」按钮触发：执行一次重做后立即消费触发值，理由同撤销。
    LaunchedEffect(redoTrigger) {
        if (redoTrigger > 0) {
            editorRef?.redo()
            onRedoTriggerConsumed()
        }
    }'''))

# 3) 删向 SoraCodeEditor 的传参
REPS.append(('''                        canUndo = canUndo,
                        canRedo = canRedo,
                        showBottomToolbar = editingBarShown,''',
             '''                        showBottomToolbar = editingBarShown,'''))

# 4) 上报重做可用态
REPS.append(('''                        onCanUndoRedoChange = { undo, redo ->
                            canUndo = undo
                            canRedo = redo
                            // 上报给宿主的顶部撤销按钮：切换为不可撤销时同步变灰
                            onCanUndoChange?.invoke(undo)
                        },''',
             '''                        onCanUndoRedoChange = { undo, redo ->
                            canUndo = undo
                            canRedo = redo
                            // 上报给宿主的顶部菜单栏按钮：不可撤销/重做时同步变灰
                            onCanUndoChange?.invoke(undo)
                            onCanRedoChange?.invoke(redo)
                        },'''))

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
