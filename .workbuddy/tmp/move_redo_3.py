# -*- coding: utf-8 -*-
"""MainApp.kt：顶部全局菜单栏「撤销」右侧、「书签」左侧插入重做按钮，并接上触发/可用态。"""
import sys

P = r"D:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/ui/screens/MainApp.kt"

REPS = []

# 1) 状态
REPS.append(('''    // 顶部菜单栏「撤销」：触发值 + 可用态（由 EditorScreen 上报，不可撤销时按钮变灰）
    var editorUndoTrigger by remember { mutableIntStateOf(0) }
    var editorCanUndo by remember { mutableStateOf(false) }''',
             '''    // 顶部菜单栏「撤销」「重做」：触发值 + 可用态（由 EditorScreen 上报，不可用时按钮变灰）
    var editorUndoTrigger by remember { mutableIntStateOf(0) }
    var editorCanUndo by remember { mutableStateOf(false) }
    var editorRedoTrigger by remember { mutableIntStateOf(0) }
    var editorCanRedo by remember { mutableStateOf(false) }'''))

# 2) EditorScreen 调用参数
REPS.append(('''                                                        onCanUndoChange = { if (path == activePath) editorCanUndo = it }
                                        )''',
             '''                                                        onCanUndoChange = { if (path == activePath) editorCanUndo = it },
                                            // 顶部全局菜单栏「重做」：同样只对前台实例生效
                                            redoTrigger = if (path == activePath) editorRedoTrigger else 0,
                                                        onRedoTriggerConsumed = { editorRedoTrigger = 0 },
                                                        onCanRedoChange = { if (path == activePath) editorCanRedo = it }
                                        )'''))

# 3) 重做按钮：撤销右侧、书签左侧
REPS.append(('''                                    // 钉子：标记/取消标记当前文件为书签
                                    val currentPathMaybe = selectedFile?.second ?: ""''',
             '''                                    // 重做：由底部符号栏移至此处（撤销右侧、书签左侧）。白色图标，
                                    // 仅在可重做时亮起，视觉与撤销/保存按钮一致
                                    IconButton(
                                        onClick = { if (editorCanRedo) editorRedoTrigger++ },
                                        enabled = editorCanRedo,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Redo, "重做", Modifier.size(20.dp),
                                            tint = Color.White.copy(alpha = if (editorCanRedo) 1f else 0.3f)
                                        )
                                    }
                                    // 钉子：标记/取消标记当前文件为书签
                                    val currentPathMaybe = selectedFile?.second ?: ""'''))

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
