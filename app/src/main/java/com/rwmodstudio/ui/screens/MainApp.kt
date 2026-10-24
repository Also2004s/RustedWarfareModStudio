package com.rwmodstudio.ui.screens

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import com.rwmodstudio.BuildConfig
import com.rwmodstudio.MainActivity
import com.rwmodstudio.core.BookmarkManager
import com.rwmodstudio.core.BrowserSpaceManager
import com.rwmodstudio.core.DiffOp
import com.rwmodstudio.core.HighlightRuleManager
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.TaskProgressManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.UpdateChecker
import com.rwmodstudio.core.computeLineDiff
import com.rwmodstudio.editor.EditorViewState
import com.rwmodstudio.editor.EditorWorkspaceEntry
import com.rwmodstudio.editor.resolveEditorBackground
import com.rwmodstudio.core.translation.ProjectRegistry
import com.rwmodstudio.core.translation.TranslationDedupChecker
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.feature.completion.value.CALLABLE_CATEGORIES
import com.rwmodstudio.ui.components.DedupResultDialog
import com.rwmodstudio.ui.components.UpdateDialogHost
import com.rwmodstudio.ui.components.UpdateDialogState
import com.rwmodstudio.ui.components.UpdateStateManager
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.NoMediaHelper
import com.rwmodstudio.util.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "MainApp"

enum class Screen { HOME, BROWSER, EDITOR, CODE_REF, SETTINGS, TRANSLATION, CUSTOM, DEVELOPER, COMPLETION_TYPES, COORD_VISUAL, VERSION_COMPARE, PROJECT_MANAGER, TODO_LIST, MOD_INFO, HIGHLIGHT_RULES, SORT_CUSTOM, RECYCLE_BIN }

private val screenSaver: Saver<Screen, String> = Saver(
    save = { it.name },
    restore = { Screen.valueOf(it) }
)

private val nullableScreenSaver: Saver<Screen?, String> = Saver(
    save = { it?.name ?: "" },
    restore = { if (it.isEmpty()) null else Screen.valueOf(it) }
)

private val pairSaver: Saver<Pair<String, String>?, String> = Saver(
    save = { if (it == null) "" else "${it.first}\u0000${it.second}" },
    restore = {
        if (it.isEmpty()) null else {
            val idx = it.indexOf('\u0000')
            if (idx > 0 && idx < it.length - 1) it.substring(0, idx) to it.substring(idx + 1) else null
        }
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 首次启动引导页（用固定验证码标识是否已完成引导）
    var showOnboarding by remember { mutableStateOf(!SettingsManager.onboardingVerified) }
    if (showOnboarding) {
        OnboardingScreen(onFinished = { showOnboarding = false })
        return
    }

    val scope = rememberCoroutineScope()
    var currentScreen by rememberSaveable(stateSaver = screenSaver) { mutableStateOf(Screen.HOME) }

    // 自定义高亮规则（开发者模式→规则）：首帧前同步加载一次（幂等、文件小），
    // 规则文件不存在时自动写入一份「内置语法复刻」作为默认规则；
    // 保证编辑器创建语言时规则已就绪，且不出现「先内置后自定义」的闪烁
    HighlightRuleManager.attachContext(context)
    HighlightRuleManager.load(context)

    // ===== 自动检查更新（一天最多提示一次；用户关闭后该版本不再提示） =====
    LaunchedEffect(Unit) {
        // 当天已提示过则不重复弹
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (SettingsManager.lastUpdatePromptDate == today) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            try {
                val info = UpdateChecker.fetchLatest()
                if (UpdateChecker.isNewerThan(info.versionName, BuildConfig.VERSION_NAME) &&
                    info.versionName != SettingsManager.dismissedUpdateVersion
                ) {
                    UpdateDialogState.Available(info)
                } else {
                    null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消必须重新抛出，否则取消被当网络失败静默吞掉
            } catch (e: Exception) {
                null // 网络失败静默，不打扰用户
            }
        }
        if (result != null) {
            // 弹出即记录当天已提示，保证"一天最多一次"
            SettingsManager.lastUpdatePromptDate = today
            UpdateStateManager.state.value = result
        }
    }

    // 返回栈：记录嵌套导航路径，支持“从哪进入，返回哪去”
    val backStack = remember { mutableStateListOf<Screen>() }
    var drawerState = rememberDrawerState(DrawerValue.Closed)
    var selectedFile by rememberSaveable(stateSaver = pairSaver) { mutableStateOf<Pair<String, String>?>(null) }
    var showEditorBackDialog by remember { mutableStateOf(false) }
    var saveAndExit by rememberSaveable { mutableStateOf(false) }
    // 保存并退出：等待所有实例保存完成再统一退出（避免「第一个完成即销毁其余实例、其余走兜底」）
    var pendingSaveExitCount by remember { mutableIntStateOf(0) }
    var pendingSaveExitFail by remember { mutableStateOf(false) }
    // 编辑器书签侧边栏：开关与重算标记
    var showBookmarkPanel by remember { mutableStateOf(false) }
    var bookmarkTick by remember { mutableIntStateOf(0) }
    var bookmarkSort by remember { mutableStateOf(BookmarkManager.Sort.TIME) }
    // 书签面板「返回」按钮触发编辑器返回手势（有修改弹保存确认，否则退回目录）
    var bookmarkBackRequest by remember { mutableIntStateOf(0) }
    // 外部 Intent 打开文件时，待用户确认保存后打开的文件
    var pendingExternalOpen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var saveAndOpenPending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dedupChecking by remember { mutableStateOf(false) }
    var dedupResult by remember { mutableStateOf<String?>(null) }
    var dedupItems by remember { mutableStateOf<List<TranslationDedupChecker.DuplicateInfo>>(emptyList()) }
    var pendingOpenFile by rememberSaveable(stateSaver = pairSaver) { mutableStateOf<Pair<String, String>?>(null) }
    var translationFilter by remember { mutableStateOf(TranslationFilterType.ALL) }
    var editorInsertText by remember { mutableStateOf("") }
    var editorInsertTick by remember { mutableIntStateOf(0) }
    var coordVisualText by remember { mutableStateOf("") }
    var coordVisualResult by remember { mutableStateOf<String?>(null) }
    var coordVisualResultTick by remember { mutableIntStateOf(0) }
    // 工作区：最近打开文件的完整缓冲（文本+光标+滚动+行尾灯泡），最多保留 5 个（含当前文件）。
    // 单活跃实例架构：非当前文件不持有存活视图，只保存缓冲；排到工作区窗口之外的缓冲自动落盘保存。
    var workspace by remember { mutableStateOf(mapOf<String, EditorWorkspaceEntry>()) }
    // 存活实例路径列表（MRU，最多 5）：每个打开的文件一个一直活着的 EditorScreen 实例，
    // 非前台实例 active=false 不渲染高亮，但状态（光标/滚动/文本）保留；排到窗口之外即销毁该实例。
    var openPaths by remember { mutableStateOf(listOf<String>()) }
    // 显式关闭/放弃的文件路径：这些文件本次离开不缓存进工作区（下次仍从磁盘加载），
    // 避免返回目录选「不保存」放弃修改后，重开文件又恢复出已放弃的内容。
    var discardPaths by remember { mutableStateOf(setOf<String>()) }
    // 「暂存」文件：直接返回（返回键/抽屉/工具页，未做明确保存或放弃选择）且未开自动保存时，
    // 这些文件只缓存不落盘（下次打开恢复未保存修改）；明确保存/不保存或自动保存开启时不暂存。
    var holdPaths by remember { mutableStateOf(setOf<String>()) }
    // 离场销毁标记：明确「保存/不保存」或自动保存开启的任何离开都会置位；
    // 由离场 LaunchedEffect 在实例全部销毁（onDispose 上报完成）后清空工作区与实例列表。
    var workspaceWipePending by remember { mutableStateOf(false) }

    // 把条目写入工作区（单活跃实例架构下由旧文件 onDispose 上报缓存，容量由 MRU 效果统一管理）。
    // 显式关闭/放弃的文件跳过本次缓存；discardPaths 的消费统一由 onDisposeFlush 处理，
    // 避免「onWorkspaceCache 先消费、onDisposeFlush 后看不到放弃标记」导致「不保存」的文件被误落盘。
    fun updateWorkspace(entry: EditorWorkspaceEntry) {
        if (entry.path.isEmpty()) return
        if (entry.path in discardPaths) return
        // 空文本不缓存：加载中途切走上报的竞态空缓冲会导致下次打开空白文件，直接从磁盘加载更可靠
        if (entry.displayText.isEmpty()) return
        workspace = workspace + (entry.path to entry)
    }

    // 把工作区条目落盘：显示内容需反向翻译回英文（与 EditorScreen.saveSync 同口径），并写入保存历史。
    suspend fun saveWorkspaceEntry(entry: EditorWorkspaceEntry): Boolean = withContext(Dispatchers.IO) {
        if (entry.path.isEmpty() || !entry.isModified) return@withContext false
        try {
            val engine = TranslationEngine.getInstance()
            if (!engine.isLoaded) engine.load(context)
            val contentToSave = if (entry.isEnglish && entry.showChinese) {
                engine.translateToEnglish(entry.displayText, SettingsManager.autoSpace, entry.litLines)
            } else {
                entry.displayText
            }
            val targetFile = File(entry.path)
            // 父目录校验：目录被删/路径非法时提前失败，避免 tmp.writeText 抛 IOException 被误报
            if (targetFile.parentFile != null && !targetFile.parentFile.exists()) {
                Log.w(TAG, "Refuse to save workspace entry with missing parent dir: ${entry.path}")
                return@withContext false
            }
            // 空内容保护：磁盘文件非空时禁止空内容覆盖写盘，防止加载异常/竞态缓存导致的空白内容写坏文件
            if (contentToSave.isEmpty() && targetFile.exists() && targetFile.length() > 0) {
                Log.w(TAG, "Refuse to save empty workspace content over non-empty file: ${entry.path}")
                return@withContext false
            }
            val beforeContent = if (targetFile.exists()) {
                try { targetFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            } else ""
            targetFile.atomicWriteText(contentToSave, Charsets.UTF_8)
            SaveHistoryManager.record(context, entry.path, beforeContent, contentToSave)
            // 写盘成功后清工作区 dirty 标记：onDispose 会先 onWorkspaceCache 缓存 isModified=true 的条目，
            // 再走本函数异步落盘，若不清会导致 saveAllWorkspaceFiles 重复写盘、重开文件时误报「未保存」。
            withContext(Dispatchers.Main) {
                workspace = workspace.mapValues { (p, e) -> if (p == entry.path) e.copy(isModified = false) else e }
            }
            Log.d(TAG, "WORKSPACE save path=${entry.path} len=${contentToSave.length}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WORKSPACE save failed path=${entry.path}", e)
            false
        }
    }

    // 保存检查点：触发任意保存行为后，把工作区内其它有修改的文件缓冲一并落盘（与当前文件同口径反向翻译）。
    // 工作区仅存缓冲（文本+光标+滚动+行尾灯泡），非当前文件不持有存活视图，只渲染一个活跃实例。
    suspend fun saveAllWorkspaceFiles() {
        // 跳过有存活实例的路径：存活实例的内容由自身 saveSync/onDisposeFlush 从实时编辑器文本写盘，
        // workspace 缓存只对「无存活实例」的文件才是权威。否则 autosave 关闭的暂存场景下，重开的文件
        // workspace 残留旧条目（isModified=true）可能被本函数写盘，随后又被实例实时内容覆盖，顺序不定致旧值覆盖新值。
        val live = openPaths.toSet()
        val modified = workspace.values.filter { it.isModified && it.path !in live }
        if (modified.isEmpty()) return
        val saved = modified.mapNotNull { entry -> if (saveWorkspaceEntry(entry)) entry.path else null }
        if (saved.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                workspace = workspace.mapValues { (p, e) -> if (p in saved) e.copy(isModified = false) else e }
            }
        }
    }

    // 任意保存后收尾：把非前台实例写盘并移除，只保留前台一个实例。
    // 移除非前台实例会触发其 onDispose，onDispose 内部会缓存缓冲并（若未保存修改）直接落盘，故这里只需移除实例。
    fun flushInactiveInstances() {
        val current = selectedFile?.second ?: return
        if ((openPaths - current).isEmpty()) return
        openPaths = listOf(current)
    }

    // 持有当前编辑器文本，供外部 intent 打开文件时判断是否有未保存修改；用普通 var 避免每次输入都触发重组
    val editorTextHolder = remember { object { var text = "" } }
    var editorSaveTrigger by remember { mutableIntStateOf(0) }
    // 顶部菜单栏「搜索」「保存」「撤销」触发状态
    var editorSearchTrigger by remember { mutableIntStateOf(0) }
    // 顶部菜单栏「撤销」「重做」：触发值 + 可用态（由 EditorScreen 上报，不可用时按钮变灰）
    var editorUndoTrigger by remember { mutableIntStateOf(0) }
    var editorCanUndo by remember { mutableStateOf(false) }
    var editorRedoTrigger by remember { mutableIntStateOf(0) }
    var editorCanRedo by remember { mutableStateOf(false) }
    // 存活实例的实时未保存状态（按路径）：每个 EditorScreen 的 isModified 变化都会上报到这里，
    // 供 tab 栏星号与保存按钮统一判断「哪个文件未保存」。editorModified 由此派生（当前前台文件是否未保存）。
    var liveModifiedPaths by remember { mutableStateOf(setOf<String>()) }
    val editorModified = (selectedFile?.second ?: "") in liveModifiedPaths
    // 模组信息页（mod-info.txt 可视化表单）：编辑状态 + 顶栏保存按钮触发
    var modInfoModified by remember { mutableStateOf(false) }
    var modInfoSaveTrigger by remember { mutableIntStateOf(0) }
    // 搜索页面状态持久化
    var homeSearchQuery by rememberSaveable { mutableStateOf("") }
    var homeSearchTargetPath by rememberSaveable { mutableStateOf("") }
    val homeSearchListState = rememberLazyListState()
    // 文件浏览器搜索状态持久化
    var fileBrowserSearchQuery by rememberSaveable { mutableStateOf("") }
    var fileBrowserSearchInContent by rememberSaveable { mutableStateOf(true) }
    var fileBrowserSearchPath by rememberSaveable { mutableStateOf("") }
    val fileBrowserSearchListState = rememberLazyListState()
    var showFileBrowserSearchSheet by rememberSaveable { mutableStateOf(false) }
    var fileBrowserSearchSourceScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    // 最近页面状态持久化
    var recentShowModified by rememberSaveable { mutableStateOf(SettingsManager.lastRecentDialogTab) }
    var recentSelectedHistoryPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 近期弹窗「智能助理」标签：默认关闭，仅开发者模式开启后显示入口
    var recentAssistantTab by rememberSaveable { mutableStateOf(false) }
    val recentListState = rememberLazyListState()
    // 搜索/最近作为全局底部弹窗，记录来源页面以便打开文件后返回
    var showHomeSearchSheet by rememberSaveable { mutableStateOf(false) }
    var showRecentSheet by rememberSaveable { mutableStateOf(false) }
    var homeSearchSourceScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    var recentSourceScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    // 搜索替换写盘后刷新对应打开实例：被替换文件路径的 tick 递增，EditorScreen 据此重新从盘加载
    var searchModifyTicks by remember { mutableStateOf(mapOf<String, Int>()) }
    // 搜索替换写盘前的外部保存请求：把「打开着且未保存」的编辑器实例落盘并回报结果，
    // 搜索协程 await 完成后才读盘替换，避免覆盖未保存修改
    var externalSaveRequests by remember { mutableStateOf(mapOf<String, Int>()) }
    var externalSaveDeferreds by remember { mutableStateOf(mapOf<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()) }

    // 从「近期修改」差异行跳转到编辑器指定行（0-based）
    var editorJumpLine by remember { mutableIntStateOf(-1) }
    var editorJumpTick by remember { mutableIntStateOf(0) }
    var editorJumpColumnStart by remember { mutableIntStateOf(-1) }
    var editorJumpColumnEnd by remember { mutableIntStateOf(-1) }

    // 从抽屉/菜单离开编辑器前的同步处理：自动保存开启则触发 EditorScreen 保存；
    // 未开启则立刻标记「暂存」（holdPaths）——必须在切屏前同步标记，否则实例 onDisposeFlush
    // 先于离场 LaunchedEffect 执行，会把本应暂存的修改直接落盘。
    fun cacheEditorStateBeforeLeave() {
        if (currentScreen == Screen.EDITOR) {
            if (SettingsManager.autoSave) editorSaveTrigger++
            else if (openPaths.isNotEmpty()) holdPaths = holdPaths + openPaths.toSet()
        }
    }

    // 离开编辑器统一处理（直接返回口径全路径统一：返回键/抽屉/工具页/坐标可视化等一切未做明确选择的离开）：
    // 自动保存开启 → 全部实例经 onDisposeFlush 落盘并销毁工作区与实例列表；
    // 未开自动保存 → 暂存（holdPaths，只缓存不落盘，下次打开恢复）。
    // 实例 onDispose 在组合丢弃阶段同步回调（先于本效应执行），故销毁分支在此清空即最终态；
    // saveWorkspaceEntry 异步落盘写磁盘不受影响，其事后对 workspace 的 mapValues 写回在空表上为无操作。
    var lastScreenForEditorExit by remember { mutableStateOf(currentScreen) }
    LaunchedEffect(currentScreen) {
        val leftEditor = lastScreenForEditorExit == Screen.EDITOR && currentScreen != Screen.EDITOR
        lastScreenForEditorExit = currentScreen
        if (leftEditor) {
            if (SettingsManager.autoSave) {
                workspaceWipePending = true
                holdPaths = emptySet()
            } else if (openPaths.isNotEmpty()) {
                holdPaths = holdPaths + openPaths.toSet()
            }
        }
        if (workspaceWipePending && currentScreen != Screen.EDITOR) {
            workspace = emptyMap()
            openPaths = emptyList()
            holdPaths = emptySet()
            workspaceWipePending = false
        }
    }

    // 进入新页面时把当前页面压入返回栈
    fun pushScreen(target: Screen) {
        backStack.add(currentScreen)
        // 限制栈深度，防止极端情况内存泄漏
        if (backStack.size > 20) backStack.removeAt(0)
        currentScreen = target
    }

    // 返回时弹出栈顶；栈为空则返回默认值
    fun popScreen(default: Screen = Screen.HOME): Screen {
        return if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            default
        }
    }

    /**
     * 关闭指定文件的工作区实例（近期修改回退/应用前调用）：
     * 先标记放弃，确保实例销毁时 onDisposeFlush 不把未保存修改落盘覆盖回退结果；
     * 若关闭的是当前活跃文件且无其他实例，则退出编辑器返回来源页。
     */
    fun closeWorkspaceFile(path: String) {
        discardPaths = discardPaths + path
        openPaths = openPaths - path
        workspace = workspace - path
        if (path == selectedFile?.second) {
            selectedFile = null
            if (openPaths.isEmpty()) {
                currentScreen = popScreen(Screen.BROWSER)
            } else {
                val next = openPaths.last()
                selectedFile = File(next).name to next
            }
        }
    }

    // 清空返回栈，用于抽屉切换到顶层页面
    fun clearBackStack() {
        backStack.clear()
    }

    val themeIsDark = ThemeState.isDark // Force recompose on theme toggle
    val view = LocalView.current

    // 动态设置状态栏/导航栏/窗口背景色：非 edge-to-edge 下系统导航栏是一块可见区域，
    // 将其颜色设为编辑器真实背景色，与编辑区完全一致，底部即无色块。
    // 用 SideEffect 而非 LaunchedEffect：LaunchedEffect 是协程副作用，组合后异步才执行，
    // 存在若干帧延迟，导致"刚打开编辑器/某次操作后"短暂露出跟随全局主题色的色块；
    // SideEffect 在每次重组后同步执行，保证编辑器可见时导航栏始终立即跟随背景色。
    // 其余页面导航栏跟随全局背景。仅做视觉着色 + 系统栏图标明暗，不动 insets，不叠 imePadding：
    // 保留 adjustResize 由系统顶高窗口，editor 靠 sora 感知尺寸变化自动校正光标，本就流畅不卡。
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (currentScreen == Screen.EDITOR) {
                val editorBarColor = Color(0xFF1E1E1E)
                val editorBgColor = Color(resolveEditorBackground(ThemeState.bgColor, ThemeState.isDark))
                // 状态栏与顶部横栏同色
                window.statusBarColor = editorBarColor.toArgb()
                insetsController.isAppearanceLightStatusBars = false
                // 导航栏与编辑区背景同色，消除底部色块（始终跟随编辑器背景色）
                window.navigationBarColor = editorBgColor.toArgb()
                window.decorView.setBackgroundColor(editorBgColor.toArgb())
                window.decorView.postInvalidate()
                insetsController.isAppearanceLightNavigationBars = !ThemeState.isDark
            } else {
                val bg = if (ThemeState.isDark) DarkBg else LightBg
                window.statusBarColor = bg.toArgb()
                window.navigationBarColor = bg.toArgb()
                window.decorView.setBackgroundColor(bg.toArgb())
                window.decorView.postInvalidate()
                insetsController.isAppearanceLightStatusBars = !ThemeState.isDark
                insetsController.isAppearanceLightNavigationBars = !ThemeState.isDark
            }
        }
    }

    var autoWrap by remember { mutableStateOf(SettingsManager.autoWrap) }
    var smartWrap by remember { mutableStateOf(SettingsManager.smartWrap) }
    var defaultPath by remember { mutableStateOf(SettingsManager.defaultModPath()) }
    // 编辑器页顶部两栏（全局菜单栏 + Tab 栏）显示状态：由编辑区滚动方向驱动，仅在编辑器页生效
    var editorTopBarsShown by remember { mutableStateOf(true) }
    var browserStartPath by rememberSaveable { mutableStateOf("") }
    // 文件浏览器的「自由浏览」开关：仅侧边栏「文件浏览」入口置 true（上限放开到外部存储根，
    // 可一路返回直到 RWmod 的上级目录）；首页打开项目、查重后打开等入口置回 false，行为保持原样。
    var browserRootUnbounded by remember { mutableStateOf(false) }
    // 打开外部传入的文件（外部 Intent / 保存确认后共用入口）
    fun openExternalFile(name: String, path: String) {
        editorJumpLine = -1
        selectedFile = name to path
        browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
        editorTopBarsShown = true
        currentScreen = Screen.EDITOR
    }

    // 统一打开入口：mod-info.txt 走可视化表单页，其余走文本编辑器
    fun openFileForEdit(name: String, path: String) {
        editorJumpLine = -1
        selectedFile = name to path
        browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
        editorTopBarsShown = true
        if (name.equals("mod-info.txt", ignoreCase = true)) {
            pushScreen(Screen.MOD_INFO)
        } else {
            pushScreen(Screen.EDITOR)
        }
    }

    // 新建 mod-info.txt 成功后打开可视化页（文件已由 FileBrowser 创建）
    fun openModInfoPage(name: String, path: String) {
        selectedFile = name to path
        browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
        pushScreen(Screen.MOD_INFO)
    }

    var projectRoot by rememberSaveable { mutableStateOf("") }
    // 返回首页时清空项目根路径，确保首页的「最近」显示全部项目文件
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME) projectRoot = ""
    }
    // 项目管理扫描结果缓存，避免从编辑器返回时重复扫描
    var projectTagInfo by remember { mutableStateOf<ProjectTagScanner.ProjectTagInfo?>(null) }
    var projectTagLoading by remember { mutableStateOf(false) }
    var projectTagCachedRoot by remember { mutableStateOf("") }

    // 项目打开时自动后台扫描并缓存标签/全局标签/单位名（ProjectTagScanner.scan 内部刷新静态 cachedInfo，
    // extractFileSymbols 读取后参与全 App 补全）；项目管理页复用同一逻辑。
    suspend fun scanProjectSymbols(root: String) {
        if (root.isEmpty()) return
        if (projectTagCachedRoot == root && projectTagInfo != null) return
        projectTagCachedRoot = root
        projectTagLoading = true
        try {
            // scanIfNeeded：同 root 只扫一次，与编辑器内触发的扫描共享同一份缓存
            projectTagInfo = withContext(Dispatchers.IO) { ProjectTagScanner.scanIfNeeded(File(root)) }
        } finally {
            projectTagLoading = false
        }
    }
    LaunchedEffect(projectRoot) {
        scanProjectSymbols(projectRoot)
    }

    // ===== 异度空间：每个项目缓存一份文件浏览器空间状态（激活空间 + 两空间位置） =====
    var browserSpace by rememberSaveable { mutableIntStateOf(0) }        // 0=第一空间 1=第二空间
    var browserFirstPath by rememberSaveable { mutableStateOf("") }
    var browserSecondPath by rememberSaveable { mutableStateOf("") }
    // 仿MT目录：双栏布局（左=第一空间，右=第二空间），此时顶栏空间切换按钮隐藏，激活栏=最后点按的栏
    val mtBrowserLayout = SettingsManager.devMtBrowserLayout
    // 仿MT目录：「更多」菜单入口移到全局菜单栏，用 tick 信号触发 FileBrowserScreen 内的菜单
    var browserToolMenuTick by remember { mutableIntStateOf(0) }

    fun persistBrowserSpace() {
        if (currentScreen != Screen.BROWSER || projectRoot.isEmpty()) return
        BrowserSpaceManager.saveSpace(
            BrowserSpaceManager.projectKey(projectRoot),
            BrowserSpaceManager.ProjectSpace(browserSpace, browserFirstPath, browserSecondPath)
        )
    }

    // 进入文件浏览器（含从编辑器返回）时恢复该项目缓存的空间状态；激活空间位置以 browserStartPath 为准
    LaunchedEffect(currentScreen, projectRoot) {
        if (currentScreen == Screen.BROWSER && projectRoot.isNotEmpty()) {
            val cached = BrowserSpaceManager.getSpace(BrowserSpaceManager.projectKey(projectRoot))
            val active = cached?.active ?: 0
            browserSpace = active
            browserFirstPath = cached?.first?.takeIf { File(it).exists() } ?: browserStartPath
            browserSecondPath = cached?.second?.takeIf { File(it).exists() } ?: browserStartPath
            if (active == 1) browserSecondPath = browserStartPath else browserFirstPath = browserStartPath
            persistBrowserSpace()
        }
    }

    fun toggleBrowserSpace() {
        if (currentScreen != Screen.BROWSER || projectRoot.isEmpty()) return
        if (browserSpace == 0) {
            browserFirstPath = browserStartPath
            val target = browserSecondPath.takeIf { it.isNotEmpty() && File(it).exists() } ?: projectRoot
            browserSecondPath = target
            browserSpace = 1
            browserStartPath = target
        } else {
            browserSecondPath = browserStartPath
            browserSpace = 0
            browserStartPath = browserFirstPath
        }
        persistBrowserSpace()
    }

    // 项目管理 UI 状态持久化（分类、展开项、滚动位置）
    var projectManagerCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var projectManagerSelectedValue by rememberSaveable { mutableStateOf<String?>(null) }
    val projectManagerListState = rememberLazyListState()
    // 待办页面顶部横栏「添加」按钮触发器
    var todoAddTrigger by remember { mutableIntStateOf(0) }
    // 待办页返回目标（不污染 previousScreen）
    var todoReturnScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    // 补全类型分类：右上角「+」选中的可调用对象分类（非空表示在分类浏览页）
    var completionTypesCallableCat by rememberSaveable { mutableStateOf<String?>(null) }

    // 消费 MainActivity 从外部 intent 传入的待打开文件。
    // key 用 Compose 状态值本身：App 已在前台时 onNewIntent 再次赋值会触发重组并重跑本效应，
    // 修复「singleTask + LaunchedEffect(Unit) 只跑一次 → 二次外部打开静默丢失」的问题。
    LaunchedEffect(MainActivity.pendingOpenFile) {
        MainActivity.pendingOpenFile?.let { (name, path) ->
            if (currentScreen == Screen.EDITOR && editorTextHolder.text.isNotEmpty()) {
                // 编辑器有未保存修改：先让用户确认保存，再打开外部文件
                pendingExternalOpen = name to path
            } else {
                openExternalFile(name, path)
            }
            MainActivity.pendingOpenFile = null
        }
    }

    // 仅查重，不打开文件夹（用于已通过验证项目的主动查重）
    fun doDedupCheckOnly(folderName: String, folderPath: String) {
        dedupChecking = true
        dedupResult = null
        scope.launch(Dispatchers.Default) {
            try {
                val engine = TranslationEngine.getInstance()
                if (!engine.isLoaded) {
                    engine.load(context)
                }
                TaskProgressManager.start("查重中...", 100)
                val dups = TranslationDedupChecker.checkProjectFiles(engine, folderPath) { info ->
                    TaskProgressManager.update((info.progress * 100).toInt(), info.stage)
                }
                val words = dups.map { it.key }.toSet()
                saveDedupWords(words)
                TaskProgressManager.finish()
                withContext(Dispatchers.Main) {
                    dedupChecking = false
                    if (dups.isEmpty()) {
                        dedupResult = "「$folderName」未检测到与翻译库重复的内容"
                        dedupItems = emptyList()
                    } else {
                        dedupItems = dups
                        dedupResult = "经过查询，项目「$folderName」内有与翻译库重复的内容，需要进行修改翻译键值或者对项目对应内容进行修改后方能使用"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "doDedupCheckOnly failed", e)
                TaskProgressManager.finish()
                withContext(Dispatchers.Main) {
                    dedupChecking = false
                    dedupResult = "查重失败: ${e.message}"
                }
            }
        }
    }

    // 查重检查函数（检查文件夹）
    fun checkProjectDedup(folderName: String, folderPath: String) {
        // 已注册则直接打开
        if (ProjectRegistry.isProjectRegistered(folderName)) {
            BrowserSpaceManager.resetSpace(BrowserSpaceManager.projectKey(folderPath), folderPath)
            projectRoot = folderPath
            browserStartPath = folderPath
            browserRootUnbounded = false
            currentScreen = Screen.BROWSER
            return
        }
        // 未注册则先查重
        dedupChecking = true
        dedupResult = null
        scope.launch(Dispatchers.Default) {
            try {
                val engine = TranslationEngine.getInstance()
                if (!engine.isLoaded) {
                    engine.load(context)
                }
                // 查重整个文件夹
                TaskProgressManager.start("查重中...", 100)
                val dups = TranslationDedupChecker.checkProjectFiles(engine, folderPath) { info ->
                    TaskProgressManager.update((info.progress * 100).toInt(), info.stage)
                }
                // 保存查重词到文件
                val words = dups.map { it.key }.toSet()
                saveDedupWords(words)
                TaskProgressManager.finish()
                withContext(Dispatchers.Main) {
                    dedupChecking = false
                    if (dups.isEmpty()) {
                        // 无重复，注册并打开
                        try {
                            ProjectRegistry.registerProject(folderName)
                            BrowserSpaceManager.resetSpace(BrowserSpaceManager.projectKey(folderPath), folderPath)
                            projectRoot = folderPath
                            browserStartPath = folderPath
                            browserRootUnbounded = false
                            currentScreen = Screen.BROWSER
                        } catch (e: Exception) {
                            Log.e(TAG, "registerProject failed", e)
                            dedupResult = "项目注册失败: ${e.message}"
                        }
                    } else {
                        // 有重复，显示提示并跳转到翻译库
                        dedupItems = dups
                        dedupResult = "经过查询，项目内有与翻译库重复的内容，需要进行修改翻译键值或者对项目对应内容进行修改后方能使用"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkProjectDedup failed", e)
                TaskProgressManager.finish()
                withContext(Dispatchers.Main) {
                    dedupChecking = false
                    dedupResult = "查重失败: ${e.message}"
                }
            }
        }
    }

    BackHandler {
        when (currentScreen) {
            Screen.HOME -> scope.launch { drawerState.open() }
            Screen.BROWSER -> currentScreen = Screen.HOME
            Screen.RECYCLE_BIN -> currentScreen = popScreen(Screen.HOME)
            Screen.EDITOR -> { /* EditorScreen handles its own back */ }
            Screen.PROJECT_MANAGER -> {
                val target = popScreen(Screen.HOME)
                currentScreen = target
            }
            Screen.TODO_LIST -> {
                val target = todoReturnScreen ?: popScreen(Screen.HOME)
                todoReturnScreen = null
                currentScreen = target
            }
            Screen.COMPLETION_TYPES -> {
                if (completionTypesCallableCat != null) {
                    completionTypesCallableCat = null
                } else {
                    currentScreen = popScreen(Screen.HOME)
                }
            }
            Screen.MOD_INFO -> { /* ModInfoScreen handles its own back */ }
            else -> {
                val target = popScreen(Screen.HOME)
                currentScreen = target
            }
        }
    }

    // 自动更新提示弹窗（全局共享状态：任何页面都显示；关闭时记忆该版本不再提示）
    UpdateDialogHost(
        onVersionDismissed = { version -> SettingsManager.dismissedUpdateVersion = version }
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen != Screen.EDITOR,
        drawerContent = {
            ModalDrawerSheet(
                Modifier.width(250.dp),
                drawerContainerColor = RustedBackground,
                drawerShape = RectangleShape,
                drawerTonalElevation = 0.dp
            ) {
                Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.BottomStart) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, null, tint = RustedPrimary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("铁锈工坊", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
                            Text("RW Mod Studio", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("导航", Modifier.padding(horizontal = 14.dp).padding(bottom = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = RustedPrimary.copy(alpha = 0.7f))
                // 导航区（老板指定）：文件浏览占原「首页」的位置，回收站紧随其后；
                // 「首页」入口移除（其余页面用顶栏返回/返回键都能回首页）。
                DrawerItem(Icons.Default.FolderOpen, "文件浏览", currentScreen == Screen.BROWSER) {
                    cacheEditorStateBeforeLeave()
                    clearBackStack()
                    // 该入口定位为「自由浏览」：清掉项目根（退出项目模式）并把上限放开到外部存储根，
                    // 于是可以一路返回直到 RWmod 的上级目录；其他入口不受影响。
                    projectRoot = ""
                    browserRootUnbounded = true
                    currentScreen = Screen.BROWSER
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Icons.Default.DeleteOutline, "回收站", currentScreen == Screen.RECYCLE_BIN) {
                    cacheEditorStateBeforeLeave()
                    pushScreen(Screen.RECYCLE_BIN)
                    scope.launch { drawerState.close() }
                }
                Spacer(Modifier.height(4.dp))
                Text("工具", Modifier.padding(horizontal = 14.dp).padding(bottom = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = RustedPrimary.copy(alpha = 0.7f))
                DrawerItem(Icons.Default.Settings, "设置", currentScreen == Screen.SETTINGS) { cacheEditorStateBeforeLeave(); pushScreen(Screen.SETTINGS); scope.launch { drawerState.close() } }
                DrawerItem(Icons.Default.MenuBook, "代码表", currentScreen == Screen.CODE_REF) { cacheEditorStateBeforeLeave(); pushScreen(Screen.CODE_REF); scope.launch { drawerState.close() } }
                DrawerItem(Icons.Default.Translate, "翻译库", currentScreen == Screen.TRANSLATION) { cacheEditorStateBeforeLeave(); pushScreen(Screen.TRANSLATION); scope.launch { drawerState.close() } }
                DrawerItem(Icons.Default.Extension, "自定义补全", currentScreen == Screen.CUSTOM) { cacheEditorStateBeforeLeave(); pushScreen(Screen.CUSTOM); scope.launch { drawerState.close() } }
                Spacer(Modifier.height(4.dp))
                Text("外观", Modifier.padding(horizontal = 14.dp).padding(bottom = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = RustedPrimary.copy(alpha = 0.7f))
                DrawerItem(if (ThemeState.isDark) Icons.Default.LightMode else Icons.Default.DarkMode, if (ThemeState.isDark) "切换浅色" else "切换深色", false) { ThemeState.toggle(); scope.launch { drawerState.close() } }
                Spacer(Modifier.weight(1f))
            }
        }
    ) {
        // 顶栏折叠动画进度（Scaffold 外层共享）：0=显示，1=折叠收起；topBar 高度与编辑区内容平移都随它联动。
        // 浮层开关关闭时强制 0（顶栏常显，不折叠）。
        val topFold by animateFloatAsState(
            targetValue = if (currentScreen != Screen.EDITOR || editorTopBarsShown || !SettingsManager.devTopBarsFold) 0f else 1f,
            animationSpec = tween(200),
            label = "topBarFold"
        )
        Scaffold(
            topBar = {
                val containerColor = if (currentScreen == Screen.EDITOR) Color(0xFF1E1E1E) else RustedBackground
                val contentColor = if (currentScreen == Screen.EDITOR) Color.White else RustedOnBackground
                val titleText = when (currentScreen) {
                    Screen.HOME -> "模组"
                    Screen.BROWSER -> "文件"
                    Screen.EDITOR -> selectedFile?.first ?: ""
                    Screen.CODE_REF -> "代码表"
                    Screen.SETTINGS -> "设置"
                    Screen.TRANSLATION -> "翻译库"
                    Screen.CUSTOM -> "自定义补全"
                    Screen.DEVELOPER -> "开发者模式"
                    Screen.COMPLETION_TYPES -> "补全类型分类"
                    Screen.COORD_VISUAL -> "坐标可视化"
                    Screen.VERSION_COMPARE -> "版本对比"
                    Screen.PROJECT_MANAGER -> "项目管理"
                    Screen.TODO_LIST -> "待办"
                    Screen.MOD_INFO -> "模组信息"
                    Screen.HIGHLIGHT_RULES -> "规则"
                    Screen.SORT_CUSTOM -> "排序自定义"
                    Screen.RECYCLE_BIN -> "回收站"
                }
                // 编辑器页的全局菜单栏改为浮层（渲染在 Screen.EDITOR 分支，随 topFold 上滑隐藏），
                // 此处仅渲染非编辑器页的顶栏，始终可见、不参与折叠。
                if (currentScreen != Screen.EDITOR) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(containerColor)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        when (currentScreen) {
                            Screen.HOME -> scope.launch { drawerState.open() }
                            Screen.BROWSER -> currentScreen = Screen.HOME
                            Screen.EDITOR -> showBookmarkPanel = !showBookmarkPanel
                            Screen.VERSION_COMPARE -> currentScreen = Screen.HOME
                            Screen.PROJECT_MANAGER -> currentScreen = popScreen(Screen.HOME)
                            Screen.TODO_LIST -> {
                                val target = todoReturnScreen ?: popScreen(Screen.HOME)
                                todoReturnScreen = null
                                currentScreen = target
                            }
                            Screen.COMPLETION_TYPES -> {
                                if (completionTypesCallableCat != null) {
                                    completionTypesCallableCat = null
                                } else {
                                    currentScreen = popScreen(Screen.HOME)
                                }
                            }
                            else -> currentScreen = popScreen(Screen.HOME)
                        }
                    }) { Icon(if (currentScreen == Screen.HOME || currentScreen == Screen.EDITOR) Icons.Default.Menu else Icons.Default.ArrowBack, null, tint = contentColor) }
                    if (currentScreen == Screen.EDITOR) {
                        // 编辑器页：去掉居中标题，钉子 | 近期 | 搜索 | 保存 置于右端
                        Spacer(Modifier.weight(1f))
                        // 钉子：标记/取消标记当前文件为书签
                        val currentPathMaybe = selectedFile?.second ?: ""
                        val currentIsBookmarked = (bookmarkTick >= 0) && currentPathMaybe.isNotEmpty() && BookmarkManager.isBookmarked(currentPathMaybe)
                        IconButton(onClick = {
                            val sf = selectedFile
                            if (sf != null) {
                                BookmarkManager.toggle(sf.second, sf.first)
                                bookmarkTick++
                            }
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                if (currentIsBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                "书签当前文件", Modifier.size(18.dp),
                                tint = if (currentIsBookmarked) RustedSecondary else contentColor.copy(alpha = 0.7f)
                            )
                        }
                        // 最近：打开近期修改历史
                        IconButton(onClick = {
                            editorSaveTrigger++
                            showRecentSheet = true
                            recentSourceScreen = Screen.EDITOR
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.History, "最近", Modifier.size(18.dp), tint = contentColor)
                        }
                        IconButton(onClick = { editorSearchTrigger++ }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Search, "查找替换", Modifier.size(20.dp), tint = contentColor)
                        }
                        // 保存：白色图标，仅在文件有修改（可保存）时亮起，样式同撤销/重做按钮
                        IconButton(
                            onClick = { if (editorModified) editorSaveTrigger++ },
                            enabled = editorModified,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Save, "保存", Modifier.size(20.dp),
                                tint = Color.White.copy(alpha = if (editorModified) 1f else 0.3f)
                            )
                        }
                    } else {
                        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                            Text(titleText, color = contentColor, fontSize = 18.sp, maxLines = 1)
                        }
                    }
                    if (currentScreen == Screen.MOD_INFO) {
                        // 保存：仅在表单有编辑时亮起可点，未编辑灰色（样式同编辑器保存按钮）
                        IconButton(
                            onClick = { if (modInfoModified) modInfoSaveTrigger++ },
                            enabled = modInfoModified,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Save, "保存", Modifier.size(20.dp),
                                tint = contentColor.copy(alpha = if (modInfoModified) 1f else 0.3f)
                            )
                        }
                    }
                    if (currentScreen == Screen.PROJECT_MANAGER) {
                        IconButton(
                            onClick = {
                                todoReturnScreen = currentScreen
                                currentScreen = Screen.TODO_LIST
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Checklist, "待办", Modifier.size(20.dp), tint = contentColor)
                        }
                    }
                    if (currentScreen == Screen.TODO_LIST) {
                        IconButton(
                            onClick = { todoAddTrigger++ },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, "添加待办", Modifier.size(20.dp), tint = contentColor)
                        }
                    }
                    if (currentScreen == Screen.COMPLETION_TYPES) {
                        var showCallableMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showCallableMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, "可调用对象分类", Modifier.size(20.dp), tint = contentColor)
                        }
                        DropdownMenu(expanded = showCallableMenu, onDismissRequest = { showCallableMenu = false }) {
                            CALLABLE_CATEGORIES.forEach { label ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    onClick = {
                                        showCallableMenu = false
                                        completionTypesCallableCat = label
                                    }
                                )
                            }
                        }
                    }
                    // 异度空间：仅文件浏览器项目模式显示，点击切换第一/第二文件浏览器（激活时高亮）
                    // ⚠️ 位置约定：此按钮属于【全局顶部菜单栏】（Scaffold 的 topBar，即 MainApp 标题栏右侧图标区），
                    //    与 FileBrowserScreen 页面内部的【目录栏】（面包屑/工具栏）是两层不同的 UI。
                    //    异度空间入口【只能】放在本顶部菜单栏，禁止挪进 FileBrowserScreen 内部目录栏。
                    // 仿MT目录下双栏同显，无需切换 → 隐藏本按钮。
                    if (currentScreen == Screen.BROWSER && projectRoot.isNotEmpty() && !mtBrowserLayout) {
                        IconButton(
                            onClick = { toggleBrowserSpace() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (browserSpace == 1) Icons.Default.Layers else Icons.Outlined.Layers,
                                "异度空间",
                                Modifier.size(20.dp),
                                tint = if (browserSpace == 1) RustedSecondary else contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                    // 仿MT目录：浏览器页内「近期/项目管理/更多」上移到全局菜单栏（页面工具栏只留每栏面包屑）
                    if (currentScreen == Screen.BROWSER && mtBrowserLayout) {
                        IconButton(onClick = { showRecentSheet = true; recentSourceScreen = currentScreen }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.History, "近期", Modifier.size(20.dp), tint = contentColor.copy(alpha = 0.7f))
                        }
                        IconButton(onClick = { pushScreen(Screen.PROJECT_MANAGER) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.AccountTree, "项目管理", Modifier.size(20.dp), tint = contentColor.copy(alpha = 0.7f))
                        }
                        IconButton(onClick = { browserToolMenuTick++ }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, "更多", Modifier.size(20.dp), tint = contentColor.copy(alpha = 0.7f))
                        }
                    }
                }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(
                        defaultPath = defaultPath,
                        onBrowseFolder = { path ->
                            // 屏蔽系统相册扫描项目内图片
                            NoMediaHelper.excludeFromMediaStore(context, File(path))
                            BrowserSpaceManager.resetSpace(BrowserSpaceManager.projectKey(path), path)
                            projectRoot = path; browserStartPath = path; browserRootUnbounded = false; clearBackStack(); currentScreen = Screen.BROWSER
                        },
                        onOpenFile = { n, p -> openFileForEdit(n, p) },
                        onOpenFolder = { name, path -> checkProjectDedup(name, path) },
                        onDedupFolder = { name, path -> doDedupCheckOnly(name, path) },
                        onShowRecent = { showRecentSheet = true; recentSourceScreen = currentScreen },
                        onShowSearch = { showHomeSearchSheet = true; homeSearchSourceScreen = currentScreen },
                        onVersionCompare = { clearBackStack(); currentScreen = Screen.VERSION_COMPARE }
                    )
                    Screen.BROWSER -> FileBrowserScreen(
                        startPath = browserStartPath,
                        // 自由浏览入口（侧边栏「文件浏览」）：上限放宽到外部存储根，
                        // 因此可以一路返回直到 RWmod 的上级目录；其余入口仍以 defaultPath 为上限。
                        rootPath = if (browserRootUnbounded) {
                            android.os.Environment.getExternalStorageDirectory().absolutePath
                        } else defaultPath,
                        projectRoot = projectRoot,
                        mtLayout = mtBrowserLayout,
                        firstStartPath = browserFirstPath,
                        secondStartPath = browserSecondPath,
                        activePane = browserSpace,
                        toolMenuTick = browserToolMenuTick,
                        onFileSelected = { n, p ->
                            openFileForEdit(n, p)
                        },
                        onHome = { clearBackStack(); currentScreen = Screen.HOME },
                        onShowRecent = { showRecentSheet = true; recentSourceScreen = currentScreen },
                        onShowFileSearch = { path ->
                            fileBrowserSearchPath = path
                            fileBrowserSearchSourceScreen = currentScreen
                            showFileBrowserSearchSheet = true
                        },
                        onJumpToLine = { name, path, line ->
                            selectedFile = name to path
                            browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                            editorJumpLine = line
                            editorJumpColumnStart = -1
                            editorJumpColumnEnd = -1
                            editorJumpTick++
                            pushScreen(Screen.EDITOR)
                        },
                        onOpenProjectManager = {
                            pushScreen(Screen.PROJECT_MANAGER)
                        },
                        onStartPathChange = { browserStartPath = it },
                        // 仿MT目录：某栏导航 → 更新该栏空间位置；激活栏同时镜像到 browserStartPath
                        onPanePathChange = { pane, path ->
                            if (pane == 0) browserFirstPath = path else browserSecondPath = path
                            if (browserSpace == pane) browserStartPath = path
                            persistBrowserSpace()
                        },
                        // 仿MT目录：点按某栏 → 该栏成为激活空间（browserSpace 0/1），路径镜像到 browserStartPath
                        onActivePaneChange = { pane, path ->
                            if (pane == 0) browserFirstPath = path else browserSecondPath = path
                            browserSpace = pane
                            browserStartPath = path
                            persistBrowserSpace()
                        },
                        onCreateModInfo = { n, p -> openModInfoPage(n, p) }
                    )
                    Screen.RECYCLE_BIN -> RecycleBinScreen()
                    Screen.MOD_INFO -> {
                        val modInfoPath = selectedFile?.second ?: ""
                        if (modInfoPath.isEmpty()) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            ModInfoScreen(
                                filePath = modInfoPath,
                                onBack = {
                                    selectedFile = null
                                    currentScreen = popScreen(Screen.BROWSER)
                                },
                                onModifiedChange = { modInfoModified = it },
                                saveTrigger = modInfoSaveTrigger,
                                // 版本号项目同步替换前：先把「打开着且未保存」的编辑器工作区落盘，避免覆盖未保存修改
                                onSaveWorkspaceBeforeReplace = { paths ->
                                    if (paths.isNotEmpty()) {
                                        workspace.values.filter { it.path in paths && it.path !in openPaths && it.isModified }
                                            .forEach { saveWorkspaceEntry(it) }
                                        val openModified = openPaths.filter { it in paths && it in liveModifiedPaths }
                                        if (openModified.isNotEmpty()) {
                                            val deferreds = openModified.associateWith { kotlinx.coroutines.CompletableDeferred<Boolean>() }
                                            externalSaveDeferreds = deferreds
                                            externalSaveRequests = externalSaveRequests + openModified.associateWith { (externalSaveRequests[it] ?: 0) + 1 }
                                            val results = deferreds.values.map { it.await() }
                                            if (results.any { !it }) throw RuntimeException("版本同步前保存工作区失败")
                                        }
                                    }
                                },
                                // 替换写盘成功：失效工作区缓存并让打开实例重新从盘加载最新内容
                                onFilesModified = { paths ->
                                    if (paths.isNotEmpty()) {
                                        workspace = workspace - paths
                                        liveModifiedPaths = liveModifiedPaths - paths
                                        searchModifyTicks = searchModifyTicks + paths.associateWith { (searchModifyTicks[it] ?: 0) + 1 }
                                    }
                                }
                            )
                        }
                    }
                    Screen.EDITOR -> {
                        val activePath = selectedFile?.second ?: ""
                        // 多实例叠放：每个打开的文件一个一直活着的 EditorScreen 实例（最多 5 个），
                        // 非前台实例 alpha=0 且 active=false（不渲染高亮、不响应交互），但状态（光标/滚动/文本）保留；
                        // 切回即重新激活并渲染高亮。超出 5 个时逐出最旧实例，其 onDispose 会缓存缓冲并（若未保存修改）直接落盘。
                        LaunchedEffect(activePath) {
                            if (activePath.isNotEmpty()) {
                                openPaths = (openPaths - activePath) + activePath
                                if (openPaths.size > 5) {
                                    // 移除最旧实例：其 onDispose 会缓存缓冲并直接落盘（无需 delay 等待）
                                    openPaths = openPaths.drop(1)
                                }
                            }
                        }
                        if (openPaths.isEmpty()) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            val modifiedAll = workspace.values.filter { it.isModified }.map { it.path }.toSet()
                            Box(Modifier.fillMaxSize()) {
                                openPaths.forEach { path ->
                                    key(path) {
                                        EditorScreen(
                                            fileName = File(path).name, filePath = path,
                                            active = path == activePath,
                                            openPaths = openPaths,
                                            autoWrap = autoWrap, smartWrap = smartWrap,
                                            workspaceEntry = workspace[path],
                                            modifier = Modifier.fillMaxSize().alpha(if (path == activePath) 1f else 0f),
                                            externalInsertText = if (path == activePath) editorInsertText else "",
                                            externalInsertTick = if (path == activePath) editorInsertTick else 0,
                                            externalReplaceText = if (path == activePath) coordVisualResult ?: "" else "",
                                            externalReplaceTick = if (path == activePath) coordVisualResultTick else 0,
                                            // 搜索替换写盘后刷新对应文件实例；写盘前的保存请求（各实例独立计数）
                                            externalModifyTick = searchModifyTicks[path] ?: 0,
                                            externalSaveRequest = externalSaveRequests[path] ?: 0,
                                            onExternalSaveDone = { ok -> externalSaveDeferreds[path]?.complete(ok) },
                                            modifiedPaths = modifiedAll + liveModifiedPaths,
                                            onWorkspaceCache = { entry -> if (entry.path.isNotEmpty()) updateWorkspace(entry) },
                                                onDisposeFlush = { entry ->
                                        // 实例销毁时落盘：放弃集合跳过（明确「不保存」），暂存集合跳过（未开自动保存的直接返回），有修改才写盘。
                                        // 同时移除其实时未保存状态，未保存修改已由 workspace/落盘承载，避免残留导致 tab 栏误标星号。
                                        liveModifiedPaths = liveModifiedPaths - entry.path
                                        if (entry.path in discardPaths) {
                                            discardPaths = discardPaths - entry.path
                                        } else if (entry.path in holdPaths) {
                                            holdPaths = holdPaths - entry.path
                                        } else if (entry.isModified) {
                                            scope.launch {
                                                // 兜底落盘结果要检查：失败时提示，避免用户以为已保存实际没写成功
                                                val ok = saveWorkspaceEntry(entry)
                                                if (!ok) {
                                                    Log.e(TAG, "onDisposeFlush save failed: ${entry.path}")
                                                    try {
                                                        Toast.makeText(
                                                            context,
                                                            "文件保存失败: ${File(entry.path).name}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    } catch (e: Exception) { Log.e(TAG, "save-fail toast failed", e) }
                                                }
                                            }
                                        }
                                            },
                                            onSaveAllWorkspace = {
                                        saveAllWorkspaceFiles()
                                        flushInactiveInstances()
                                            },
                                            onBack = { discard, saveAll ->
                                        // 返回目录，三种口径：discard=true 明确「不保存」（放弃全部实例不落盘并销毁工作区）；
                                        // saveAll=true 明确「保存」（全部实例靠 onDisposeFlush 落盘并销毁工作区）；
                                        // 都不是即直接返回——自动保存开启时同「保存」口径，未开启时标记「暂存」（只缓存不落盘，下次打开恢复）。
                                        val allPaths = openPaths.toSet()
                                        openPaths = emptyList()
                                        when {
                                            discard -> {
                                                discardPaths = discardPaths + allPaths
                                                workspaceWipePending = true
                                            }
                                            saveAll -> workspaceWipePending = true
                                            !SettingsManager.autoSave -> holdPaths = holdPaths + allPaths
                                            // 自动保存开启的直接返回：离场效应已置销毁标记，这里无需处理
                                            else -> {}
                                        }
                                        currentScreen = popScreen(Screen.BROWSER)
                                        selectedFile = null
                                            },
                                            onSwitchFile = { newPath ->
                                        // tab 栏切换：不落盘，交给 onDispose 缓存工作区；有修改也不在此保存，下次进入自动恢复。
                                        // 清除残留的搜索/近期修改跳转状态：否则 jumpTick/jumpLine 残留会让新文件跳过视图恢复
                                        editorJumpLine = -1
                                        editorJumpColumnStart = -1
                                        editorJumpColumnEnd = -1
                                        editorSearchTrigger = 0
                                        selectedFile = File(newPath).name to newPath
                                        browserStartPath = File(newPath).parentFile?.absolutePath ?: browserStartPath
                                            },
                                            projectRoot = projectRoot,
                                            saveAndExit = saveAndExit,
                                            onSaveAndExitDone = { ok ->
                                        // 无论成败都重置，防止保存失败后 saveAndExit 卡死无法再次触发
                                        saveAndExit = false
                                        pendingSaveExitCount++
                                        if (!ok) pendingSaveExitFail = true
                                        // 等所有实例都保存完成再统一退出：单个实例先完成不动实例列表，
                                        // 其余实例的保存协程不会被销毁取消（各自正常落盘而非靠兜底）
                                        if (pendingSaveExitCount >= openPaths.size) {
                                            val allOk = !pendingSaveExitFail
                                            pendingSaveExitCount = 0
                                            pendingSaveExitFail = false
                                            if (allOk) {
                                                // 保存并退出：明确保存口径——清空存活实例列表并销毁工作区
                                                openPaths = emptyList()
                                                discardPaths = discardPaths + activePath
                                                workspaceWipePending = true
                                                currentScreen = popScreen(Screen.BROWSER)
                                                selectedFile = null
                                            }
                                            // 有实例保存失败则不退出：留在编辑器，用户可重试（saveAndExit 已重置）
                                        }
                                            },
                                            onNavigate = { target -> cacheEditorStateBeforeLeave(); pushScreen(target) },
                                            onOpenCoordVisual = { text ->
                                        cacheEditorStateBeforeLeave()
                                        coordVisualText = text
                                        pushScreen(Screen.COORD_VISUAL)
                                            },
                                            onTextChange = { editorTextHolder.text = it },
                                            saveTrigger = if (path == activePath) editorSaveTrigger else 0,
                                            onSaveTriggerConsumed = { editorSaveTrigger = 0 },
                                            onSaved = { ok ->
                                        val pending = saveAndOpenPending
                                        if (pending != null) {
                                            saveAndOpenPending = null
                                            if (ok) {
                                                openExternalFile(pending.first, pending.second)
                                            } else {
                                                Toast.makeText(context, "保存失败，未能打开文件", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                            },
                                            onShowRecent = { showRecentSheet = true; recentSourceScreen = Screen.EDITOR },
                                            topBarsShown = editorTopBarsShown,
                                            topFold = topFold,
                                            onTopBarsScroll = { dir -> if (SettingsManager.devTopBarsFold) editorTopBarsShown = dir < 0 },
                                            jumpLine = if (path == activePath) editorJumpLine else -1,
                                            jumpTick = if (path == activePath) editorJumpTick else 0,
                                            jumpColumnStart = if (path == activePath) editorJumpColumnStart else -1,
                                            jumpColumnEnd = if (path == activePath) editorJumpColumnEnd else -1,
                                            onExternalInsertConsumed = { editorInsertText = "" },
                                            onExternalReplaceConsumed = { coordVisualResult = null },
                                            searchTrigger = if (path == activePath) editorSearchTrigger else 0,
                                            onModifiedChange = { modified ->
                                                liveModifiedPaths = if (modified) liveModifiedPaths + path else liveModifiedPaths - path
                                            },
                                            onSearchTriggerConsumed = { editorSearchTrigger = 0 },
                                            backRequest = if (path == activePath) bookmarkBackRequest else 0,
                                                        onBackRequestConsumed = { bookmarkBackRequest = 0 },
                                            // 顶部全局菜单栏「撤销」：只对前台实例生效，触发后立即清零；
                                            // 可用态由当前实例上报（避免后台实例覆盖前台按钮状态）
                                            undoTrigger = if (path == activePath) editorUndoTrigger else 0,
                                                        onUndoTriggerConsumed = { editorUndoTrigger = 0 },
                                                        onCanUndoChange = { if (path == activePath) editorCanUndo = it },
                                            // 顶部全局菜单栏「重做」：同样只对前台实例生效
                                            redoTrigger = if (path == activePath) editorRedoTrigger else 0,
                                                        onRedoTriggerConsumed = { editorRedoTrigger = 0 },
                                                        onCanRedoChange = { if (path == activePath) editorCanRedo = it }
                                        )
                                    }
                                }
                            // 编辑器页全局菜单栏浮层：叠加在编辑器上方，随 topFold 上滑隐藏（40dp），
                            // 不占布局空间，编辑器始终全屏、滚动完全不受折叠影响
                            // ⚠️ 高度 40dp 与 EditorScreen 里 tab 栏偏移量（40.dp - 60.dp * topFold）联动，改一处必须改另一处
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .offset { IntOffset(0, (-40.dp * topFold).roundToPx()) }
                                    .clipToBounds()
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .alpha(1 - topFold)
                                        .background(Color(0xFF1E1E1E))
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { showBookmarkPanel = !showBookmarkPanel }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                                    Spacer(Modifier.weight(1f))
                                    // 撤销：由底部功能栏移至此处（书签左侧）。白色图标，仅在可撤销时亮起，
                                    // 视觉与保存按钮一致（不可撤销时 0.3 透明度且不可点）
                                    IconButton(
                                        onClick = { if (editorCanUndo) editorUndoTrigger++ },
                                        enabled = editorCanUndo,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Undo, "撤销", Modifier.size(20.dp),
                                            tint = Color.White.copy(alpha = if (editorCanUndo) 1f else 0.3f)
                                        )
                                    }
                                    // 重做：由底部符号栏移至此处（撤销右侧、书签左侧）。白色图标，
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
                                    val currentPathMaybe = selectedFile?.second ?: ""
                                    val currentIsBookmarked = (bookmarkTick >= 0) && currentPathMaybe.isNotEmpty() && BookmarkManager.isBookmarked(currentPathMaybe)
                                    IconButton(onClick = {
                                        val sf = selectedFile
                                        if (sf != null) { BookmarkManager.toggle(sf.second, sf.first); bookmarkTick++ }
                                    }, modifier = Modifier.size(36.dp)) {
                                        Icon(if (currentIsBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, "书签当前文件", Modifier.size(18.dp), tint = if (currentIsBookmarked) RustedSecondary else Color.White.copy(alpha = 0.7f))
                                    }
                                    IconButton(onClick = { editorSaveTrigger++; showRecentSheet = true; recentSourceScreen = Screen.EDITOR }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.History, "最近", Modifier.size(18.dp), tint = Color.White)
                                    }
                                    IconButton(onClick = { editorSearchTrigger++ }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Search, "查找替换", Modifier.size(20.dp), tint = Color.White)
                                    }
                                    // 保存：白色图标，仅在文件有修改（可保存）时亮起
                                    IconButton(
                                        onClick = { if (editorModified) editorSaveTrigger++ },
                                        enabled = editorModified,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Save, "保存", Modifier.size(20.dp), tint = Color.White.copy(alpha = if (editorModified) 1f else 0.3f))
                                    }
                                }
                            }
                        }
                    }
                    }
                    Screen.PROJECT_MANAGER -> {
                        val root = projectRoot.ifEmpty { defaultPath }
                        LaunchedEffect(root) {
                            scanProjectSymbols(root)
                        }
                        ProjectManagerScreen(
                            rootPath = root,
                            info = projectTagInfo,
                            loading = projectTagLoading,
                            categoryIndex = projectManagerCategoryIndex,
                            selectedValue = projectManagerSelectedValue,
                            listState = projectManagerListState,
                            onCategoryChange = { projectManagerCategoryIndex = it; projectManagerSelectedValue = null },
                            onSelectedValueChange = { projectManagerSelectedValue = it },
                            onJumpToLine = { name, path, line ->
                                selectedFile = name to path
                                browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                                editorJumpLine = line
                                editorJumpColumnStart = -1
                                editorJumpColumnEnd = -1
                                editorJumpTick++
                                pushScreen(Screen.EDITOR)
                            }
                        )
                    }
                    Screen.CODE_REF -> CodeReferenceScreen()
                    Screen.SETTINGS -> SettingsScreen(
                        autoWrap = autoWrap, onAutoWrapChange = { autoWrap = it; SettingsManager.autoWrap = it },
                        smartWrap = smartWrap, onSmartWrapChange = { smartWrap = it; SettingsManager.smartWrap = it },
                        defaultPath = defaultPath, onDefaultPathChange = { defaultPath = it; SettingsManager.defaultPath = it },
                        onNavigateToDeveloper = { pushScreen(Screen.DEVELOPER) }
                    )
                    Screen.TRANSLATION -> TranslationEditorScreen(initialFilter = translationFilter)
                    Screen.CUSTOM -> CustomCompletionsScreen()
                    Screen.DEVELOPER -> DeveloperModeScreen(
                        onOpenMockIni = {
                            scope.launch(Dispatchers.IO) {
                                // 真实补全分类器（与自动补全同源，永不漂移），模拟 ini 始终按它生成
                                val classifier = buildMockIniClassifier(context) ?: return@launch
                                val file = com.rwmodstudio.core.translation.MockIniGenerator.generate(context, classifier)
                                if (file != null) {
                                    val path = file.absolutePath
                                    withContext(Dispatchers.Main) {
                                        // 走正常打开流程（不预填工作区缓存），让编辑器经翻译流水线打开即中文化显示
                                        editorJumpLine = -1
                                        selectedFile = file.name to path
                                        pushScreen(Screen.EDITOR)
                                    }
                                }
                            }
                        },
                        onOpenCompletionTypes = {
                            pushScreen(Screen.COMPLETION_TYPES)
                        },
                        onOpenHighlightRules = {
                            pushScreen(Screen.HIGHLIGHT_RULES)
                        },
                        onOpenSortCustomization = {
                            pushScreen(Screen.SORT_CUSTOM)
                        }
                    )
                    Screen.HIGHLIGHT_RULES -> HighlightRulesScreen()
                    Screen.SORT_CUSTOM -> SortCustomizationScreen()
                    Screen.COMPLETION_TYPES -> CompletionTypeScreen(
                        callableCategory = completionTypesCallableCat,
                        onCloseCallableCategory = { completionTypesCallableCat = null }
                    )
                    Screen.COORD_VISUAL -> CoordVisualScreen(
                        initialText = coordVisualText,
                        onBack = { currentScreen = popScreen(Screen.EDITOR) },
                        onTextChanged = { result ->
                            coordVisualResult = result
                            coordVisualResultTick++
                        }
                    )
                    Screen.VERSION_COMPARE -> VersionCompareScreen(
                        defaultPath = defaultPath,
                        onBack = { currentScreen = Screen.HOME }
                    )
                    Screen.TODO_LIST -> TodoListScreen(
                        projectPath = projectRoot.ifEmpty { defaultPath },
                        triggerAdd = todoAddTrigger
                    )
                }
                // Debug 任务进度浮窗（右上角，玻璃态多任务堆叠）
                if (SettingsManager.devDebugTaskProgress && TaskProgressManager.displayActive) {
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 已完成任务（上方，灰色半透明，小字）
                        TaskProgressManager.completedTasks.forEach { task ->
                            DebugTaskCard(
                                name = task.name,
                                current = task.current,
                                total = task.total,
                                detail = task.detail,
                                active = false
                            )
                        }
                        // 当前活跃任务（下方，高亮）
                        TaskProgressManager.currentTask?.let { task ->
                            DebugTaskCard(
                                name = task.name,
                                current = task.current,
                                total = task.total,
                                detail = task.detail,
                                active = true
                            )
                        }
                    }
                }
            }

            // 书签侧边栏（仅编辑器页，汉堡菜单打开）
            if (currentScreen == Screen.EDITOR && showBookmarkPanel) {
                BookmarkPanel(
                    topPadding = padding.calculateTopPadding(),
                    sort = bookmarkSort,
                    onSortChange = { bookmarkSort = it },
                    refresher = bookmarkTick,
                    onOpen = { p ->
                    val targetPath = File(p).absolutePath
                    val currentPath = selectedFile?.second
                    if (currentPath == targetPath) {
                        // 打开的就是当前文件：仅关闭面板
                        showBookmarkPanel = false
                    } else {
                        // 与 tab 切换一致：不保存、原地切换，复用实例缓存（修改留在内存，切回自动恢复）
                        selectedFile = File(p).name to p
                        browserStartPath = File(p).parentFile?.absolutePath ?: browserStartPath
                        editorJumpLine = -1
                        // 已处于编辑器则原地切换文件，不重复入栈，避免返回时栈顶仍是编辑器
                        if (currentScreen != Screen.EDITOR) pushScreen(Screen.EDITOR)
                        showBookmarkPanel = false
                    }
                },
                    onTogglePin = { p, pinned -> BookmarkManager.setPinned(p, pinned); bookmarkTick++ },
                    onRemove = { p -> BookmarkManager.remove(p); bookmarkTick++ },
                    onClose = { showBookmarkPanel = false },
                    onBackToDir = {
                        // 先收起面板，再触发编辑器返回手势（回到目录）
                        showBookmarkPanel = false
                        bookmarkBackRequest++
                    }
                )
            }
        }
    }

    // 全局搜索底部弹窗（从弹窗打开文件后返回来源页面；从编辑器打开时允许在编辑器页面上显示）
    if (showHomeSearchSheet && (currentScreen != Screen.EDITOR || homeSearchSourceScreen == Screen.EDITOR)) {
        HomeSearchScreen(
            defaultPath = defaultPath,
            query = homeSearchQuery,
            onQueryChange = { homeSearchQuery = it },
            targetPath = homeSearchTargetPath,
            onTargetPathChange = { homeSearchTargetPath = it },
            listState = homeSearchListState,
            onJumpToLine = { name, path, line, colStart, colEnd ->
                selectedFile = name to path
                browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                editorJumpLine = line
                editorJumpColumnStart = colStart
                editorJumpColumnEnd = colEnd
                editorJumpTick++
                if (homeSearchSourceScreen != Screen.EDITOR) {
                    pushScreen(Screen.EDITOR)
                }
            },
            onFilesModified = { paths ->
                if (paths.isNotEmpty()) {
                    // 替换写盘成功：失效这些文件的工作区缓存，下次打开重新从磁盘读取最新内容
                    workspace = workspace - paths
                    liveModifiedPaths = liveModifiedPaths - paths
                    // 递增被替换文件路径的 tick，让对应打开实例重新从盘加载最新内容
                    searchModifyTicks = searchModifyTicks + paths.associateWith { (searchModifyTicks[it] ?: 0) + 1 }
                }
            },
            // 替换写盘前：先把这些文件「打开着且未保存」的缓存工作区落盘（与编辑器保存同口径），
            // 替换随后基于最新盘内容进行，避免覆盖未保存修改
            onSaveWorkspaceBeforeReplace = { paths ->
                if (paths.isNotEmpty()) {
                    // 1) 已析构/关闭实例的缓存工作区条目直接落盘
                    workspace.values.filter { it.path in paths && it.path !in openPaths && it.isModified }
                        .forEach { saveWorkspaceEntry(it) }
                    // 2) 当前打开着且未保存的编辑器缓冲：触发各实例 saveSync 落盘并等待完成
                    val openModified = openPaths.filter { it in paths && it in liveModifiedPaths }
                    if (openModified.isNotEmpty()) {
                        val deferreds = openModified.associateWith { kotlinx.coroutines.CompletableDeferred<Boolean>() }
                        externalSaveDeferreds = deferreds
                        externalSaveRequests = externalSaveRequests + openModified.associateWith { (externalSaveRequests[it] ?: 0) + 1 }
                        val results = deferreds.values.map { it.await() }
                        if (results.any { !it }) throw RuntimeException("搜索替换前保存工作区失败")
                    }
                }
            },
            showSearchModeToggle = false,
            onDismiss = { showHomeSearchSheet = false }
        )
    }

    // 文件浏览器搜索底部弹窗（保持与首页搜索一致的持久化/返回行为）
    if (showFileBrowserSearchSheet && (currentScreen != Screen.EDITOR || fileBrowserSearchSourceScreen == Screen.EDITOR)) {
        HomeSearchScreen(
            defaultPath = fileBrowserSearchPath,
            query = fileBrowserSearchQuery,
            onQueryChange = { fileBrowserSearchQuery = it },
            targetPath = fileBrowserSearchPath,
            onTargetPathChange = { },
            listState = fileBrowserSearchListState,
            showFolderFilters = false,
            searchInContent = fileBrowserSearchInContent,
            onSearchInContentChange = { fileBrowserSearchInContent = it },
            onJumpToLine = { name, path, line, colStart, colEnd ->
                selectedFile = name to path
                browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                editorJumpLine = line
                editorJumpColumnStart = colStart
                editorJumpColumnEnd = colEnd
                editorJumpTick++
                if (fileBrowserSearchSourceScreen != Screen.EDITOR) {
                    pushScreen(Screen.EDITOR)
                }
            },
            onFilesModified = { paths ->
                if (paths.isNotEmpty()) {
                    // 替换写盘成功：失效这些文件的工作区缓存，下次打开重新从磁盘读取最新内容
                    workspace = workspace - paths
                    liveModifiedPaths = liveModifiedPaths - paths
                    // 递增被替换文件路径的 tick，让对应打开实例重新从盘加载最新内容
                    searchModifyTicks = searchModifyTicks + paths.associateWith { (searchModifyTicks[it] ?: 0) + 1 }
                }
            },
            // 替换写盘前：先把这些文件「打开着且未保存」的缓存工作区落盘（与编辑器保存同口径）
            onSaveWorkspaceBeforeReplace = { paths ->
                if (paths.isNotEmpty()) {
                    // 1) 已析构/关闭实例的缓存工作区条目直接落盘
                    workspace.values.filter { it.path in paths && it.path !in openPaths && it.isModified }
                        .forEach { saveWorkspaceEntry(it) }
                    // 2) 当前打开着且未保存的编辑器缓冲：触发各实例 saveSync 落盘并等待完成
                    val openModified = openPaths.filter { it in paths && it in liveModifiedPaths }
                    if (openModified.isNotEmpty()) {
                        val deferreds = openModified.associateWith { kotlinx.coroutines.CompletableDeferred<Boolean>() }
                        externalSaveDeferreds = deferreds
                        externalSaveRequests = externalSaveRequests + openModified.associateWith { (externalSaveRequests[it] ?: 0) + 1 }
                        val results = deferreds.values.map { it.await() }
                        if (results.any { !it }) throw RuntimeException("搜索替换前保存工作区失败")
                    }
                }
            },
            onDismiss = { showFileBrowserSearchSheet = false }
        )
    }

    // 全局最近底部弹窗（从弹窗打开文件后返回来源页面；从编辑器打开时允许在编辑器页面上显示）
    if (showRecentSheet && (currentScreen != Screen.EDITOR || recentSourceScreen == Screen.EDITOR)) {
        RecentFilesScreen(
            defaultPath = defaultPath,
            projectRoot = projectRoot,
            currentOpenFilePath = selectedFile?.second,
            showModified = recentShowModified,
            onShowModifiedChange = { recentShowModified = it },
            selectedHistoryPath = recentSelectedHistoryPath,
            onSelectedHistoryPathChange = { recentSelectedHistoryPath = it },
            assistantTab = recentAssistantTab,
            onAssistantTabChange = { recentAssistantTab = it },
            listState = recentListState,
            onSaveCurrent = { editorSaveTrigger++ },
            onCloseEditorFile = { closeWorkspaceFile(it) },
            onOpenFile = { name, path ->
                if (recentSourceScreen == Screen.EDITOR) editorSaveTrigger++
                selectedFile = name to path
                browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                if (recentSourceScreen != Screen.EDITOR) {
                    pushScreen(Screen.EDITOR)
                } else {
                    showRecentSheet = false
                }
            },
            onJumpToLine = { name, path, line ->
                if (recentSourceScreen == Screen.EDITOR) editorSaveTrigger++
                selectedFile = name to path
                browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
                editorJumpLine = line
                editorJumpColumnStart = -1
                editorJumpColumnEnd = -1
                editorJumpTick++
                if (recentSourceScreen != Screen.EDITOR) {
                    pushScreen(Screen.EDITOR)
                } else {
                    showRecentSheet = false
                }
            },
            onDismiss = { showRecentSheet = false }
        )
    }

    // 外部打开文件确认对话框：当前编辑器有未保存修改时，先确认再打开
    pendingExternalOpen?.let { (name, path) ->
        AlertDialog(
            onDismissRequest = { pendingExternalOpen = null },
            title = { Text("是否保存修改？") },
            text = { Text("打开外部文件前，当前文件可能有未保存的修改") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingExternalOpen = null
                        saveAndOpenPending = name to path
                        editorSaveTrigger++
                    }) { Text("保存并打开", color = RustedPrimary) }
                    TextButton(onClick = {
                        pendingExternalOpen = null
                        openExternalFile(name, path)
                    }) { Text("不保存", color = RustedError) }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalOpen = null }) { Text("取消") }
            }
        )
    }

    // 编辑器返回确认对话框
    if (showEditorBackDialog) {
        AlertDialog(
            onDismissRequest = { showEditorBackDialog = false },
            title = { Text("是否保存修改？") },
            text = { Text("当前文件可能有未保存的修改") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showEditorBackDialog = false
                        saveAndExit = true
                    }) { Text("保存", color = RustedPrimary) }
                    TextButton(onClick = {
                        showEditorBackDialog = false
                        currentScreen = popScreen(Screen.BROWSER)
                        selectedFile = null
                    }) { Text("不保存", color = RustedError) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditorBackDialog = false }) { Text("取消") }
            }
        )
    }

    // 查重检查对话框
    if (dedupChecking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("查重中...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = RustedPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("正在检查项目是否与翻译库重复...", fontSize = 13.sp)
                }
            },
            confirmButton = {}
        )
    }

    // 查重结果对话框
    dedupResult?.let { result ->
        DedupResultDialog(
            dups = dedupItems,
            title = "查重提示",
            summary = result,
            onDismiss = { dedupResult = null; dedupItems = emptyList() },
            onModify = {
                dedupResult = null
                dedupItems = emptyList()
                translationFilter = TranslationFilterType.DEDUP
                currentScreen = Screen.TRANSLATION
            }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentFilesScreen(
    defaultPath: String,
    projectRoot: String,
    currentOpenFilePath: String?,
    showModified: Boolean,
    onShowModifiedChange: (Boolean) -> Unit,
    selectedHistoryPath: String?,
    onSelectedHistoryPathChange: (String?) -> Unit,
    assistantTab: Boolean = false,
    onAssistantTabChange: (Boolean) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSaveCurrent: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onJumpToLine: (String, String, Int) -> Unit,
    onCloseEditorFile: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRevertPath by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = RustedBackground,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
        if (selectedHistoryPath == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showModified && !assistantTab,
                        onClick = {
                            onShowModifiedChange(false)
                            SettingsManager.lastRecentDialogTab = false
                            onAssistantTabChange(false)
                            onSelectedHistoryPathChange(null)
                        },
                        label = { Text("最近打开", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = showModified && !assistantTab,
                        onClick = {
                            onShowModifiedChange(true)
                            SettingsManager.lastRecentDialogTab = true
                            onAssistantTabChange(false)
                            onSelectedHistoryPathChange(null)
                        },
                        label = { Text("近期修改", fontSize = 12.sp) }
                    )
                    if (SettingsManager.devAiAssistant) {
                        FilterChip(
                            selected = assistantTab,
                            onClick = {
                                onAssistantTabChange(true)
                                onSelectedHistoryPathChange(null)
                            },
                            label = { Text("智能助理", fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val path = selectedHistoryPath
            // 列表状态提升到 when 外：切到详情再返回时保留缓存，避免每次返回重新加载
            var modifiedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
            var modifiedFilesLoading by remember { mutableStateOf(true) }
            // key 增加 selectedHistoryPath：从详情页返回列表（path 变为 null）时重新加载，
            // 使刚「确认/回退/应用」的文件及时从列表消失
            LaunchedEffect(showModified, selectedHistoryPath) {
                if (showModified && selectedHistoryPath == null) {
                    modifiedFiles = withContext(Dispatchers.IO) { SaveHistoryManager.getRecentFiles(context) }
                    modifiedFilesLoading = false
                }
            }
            when {
                assistantTab -> AssistantChatTab(
                    projectRoot = projectRoot,
                    onOpenFile = onOpenFile,
                    modifier = Modifier.weight(1f)
                )
                path != null -> {
                    var history by remember(path) { mutableStateOf<List<SaveHistoryManager.SaveRecord>>(emptyList()) }
                    var historyLoading by remember(path) { mutableStateOf(true) }
                    LaunchedEffect(path) {
                        history = withContext(Dispatchers.IO) { SaveHistoryManager.getHistoryForFile(context, path) }
                        historyLoading = false
                        if (history.isEmpty()) onSelectedHistoryPathChange(null)
                    }
                    Column {
                        Text(File(path).name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
                        if (history.isNotEmpty()) {
                            val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                            Text(
                                "首次保存 ${fmt.format(Date(history.last().timestamp))} ~ 最后修改 ${fmt.format(Date(history.first().timestamp))}",
                                fontSize = 11.sp,
                                color = RustedOnBackground.copy(alpha = 0.4f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (history.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                if (historyLoading) {
                                    CircularProgressIndicator(color = RustedPrimary)
                                } else {
                                    Text("暂无保存记录", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                                }
                            }
                        } else {
                            val historyBefore = remember(path) { history.last().beforeContent }
                            val historyAfter = remember(path) { history.first().afterContent }
                            val revertedBlockIndices = remember { mutableStateOf(setOf<Int>()) }
                            val confirmedBlockIndices = remember { mutableStateOf(setOf<Int>()) }
                            // 加载中文翻译用于 diff 显示
                            var chineseLines by remember(path) { mutableStateOf(emptyList<String>()) }
                            LaunchedEffect(path) {
                                withContext(Dispatchers.IO) {
                                    chineseLines = runCatching {
                                        val content = SearchTranslationCache.readCacheSync(File(path))
                                            ?: SearchTranslationCache.getChineseContent(File(path))
                                        content.split("\n")
                                    }.getOrDefault(emptyList())
                                }
                            }
                            val chineseLookup: ((Int) -> String?)? = if (chineseLines.isNotEmpty()) {
                                { lineNum -> chineseLines.getOrNull(lineNum - 1) }
                            } else null
                            Box(Modifier.weight(1f)) {
                                DiffView(
                                    before = historyBefore,
                                    after = historyAfter,
                                    onJump = { line ->
                                        onSaveCurrent()
                                        onJumpToLine(File(path).name, path, line)
                                    },
                                    revertedBlocks = revertedBlockIndices.value,
                                    confirmedBlocks = confirmedBlockIndices.value,
                                    onToggleRevert = { idx ->
                                        val r = revertedBlockIndices.value
                                        revertedBlockIndices.value = if (idx in r) r - idx else (r + idx) - confirmedBlockIndices.value
                                    },
                                    onToggleConfirm = { idx ->
                                        val c = confirmedBlockIndices.value
                                        confirmedBlockIndices.value = if (idx in c) c - idx else (c + idx) - revertedBlockIndices.value
                                    },
                                    chineseLookup = chineseLookup
                                )
                            }
                            val hasChanges = revertedBlockIndices.value.isNotEmpty() || confirmedBlockIndices.value.isNotEmpty()
                            if (hasChanges) Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { onSelectedHistoryPathChange(null) }) {
                                    Text("返回", fontSize = 13.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                if (hasChanges) {
                                    val revertCount = revertedBlockIndices.value.size
                                    val confirmCount = confirmedBlockIndices.value.size
                                    val labelParts = mutableListOf<String>()
                                    if (revertCount > 0) labelParts.add("回退$revertCount")
                                    if (confirmCount > 0) labelParts.add("确认$confirmCount")
                                    TextButton(
                                        onClick = {
                                            // 文件正被编辑器打开时：先自动关闭其工作区（未保存修改放弃），再应用，避免提示后无响应
                                            if (path == currentOpenFilePath) onCloseEditorFile(path)
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val newContent = computePartialRevert(historyBefore, historyAfter, revertedBlockIndices.value)
                                                    File(path).atomicWriteText(newContent)
                                                    // 更新 save history：确认过的块合并到基线
                                                    if (confirmedBlockIndices.value.isNotEmpty()) {
                                                        val newBaseline = computeConfirmedBaseline(historyBefore, historyAfter, confirmedBlockIndices.value)
                                                        SaveHistoryManager.updateBaseline(context, path, newBaseline)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "已应用 ${labelParts.joinToString("、")}", Toast.LENGTH_SHORT).show()
                                                        onSelectedHistoryPathChange(null)
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("应用(${labelParts.joinToString("、")})", fontSize = 13.sp, color = RustedError) }
                                }
                                TextButton(
                                    onClick = {
                                        // 文件正被编辑器打开时：先自动关闭其工作区（未保存修改放弃），再弹确认框，避免提示后无响应
                                        if (path == currentOpenFilePath) onCloseEditorFile(path)
                                        pendingRevertPath = path
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("全部回退", fontSize = 13.sp, color = RustedError) }
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            SaveHistoryManager.confirmFile(context, path)
                                            withContext(Dispatchers.Main) { onSelectedHistoryPathChange(null) }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("确认", fontSize = 13.sp, color = RustedSecondary) }
                            }
                        }
                    }
                }
                else -> {
                    val allFiles = if (showModified) modifiedFiles else SettingsManager.recentFiles
                    val files = if (projectRoot.isBlank()) allFiles else {
                        val root = File(projectRoot).canonicalPath + File.separator
                        allFiles.filter { File(it).canonicalPath.startsWith(root) }
                    }
                    if (files.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.History, null, Modifier.size(36.dp), tint = RustedOnBackground.copy(alpha = 0.25f))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (showModified) "暂无近期修改的文件" else "暂无最近打开的文件",
                                    fontSize = 13.sp,
                                    color = RustedOnBackground.copy(alpha = 0.4f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(files) { path ->
                                val file = File(path)
                                val exists = file.exists()
                                // 统一 MT 管理器扁平列表风格：无卡片外壳、透明底
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (showModified) {
                                                onSelectedHistoryPathChange(path)
                                            } else {
                                                onSaveCurrent()
                                                onOpenFile(file.name, path)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        null,
                                        Modifier.size(22.dp),
                                        tint = if (exists) RustedPrimary else RustedOnBackground.copy(alpha = 0.3f)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            fontSize = 14.sp,
                                            color = if (exists) RustedOnBackground else RustedOnBackground.copy(alpha = 0.4f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            relativeDirPath(file, defaultPath),
                                            fontSize = 9.sp,
                                            color = RustedOnBackground.copy(alpha = 0.35f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!exists) {
                                        Text("已失效", fontSize = 10.sp, color = RustedError.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRevertPath?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingRevertPath = null },
            title = { Text("确认回退？") },
            text = { Text("将把文件恢复到最早保存前的内容，并清空该文件的修改记录。若文件正在编辑器中打开，将自动关闭该文件（未保存修改将被放弃）。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevertPath = null
                        scope.launch(Dispatchers.IO) {
                            val ok = SaveHistoryManager.revertFile(context, path)
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    onSelectedHistoryPathChange(null)
                                } else {
                                    Toast.makeText(context, "回退失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) { Text("确认回退", color = RustedError) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevertPath = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DiffView(
    before: String, after: String,
    onJump: ((Int) -> Unit)? = null,
    revertedBlocks: Set<Int> = emptySet(),
    confirmedBlocks: Set<Int> = emptySet(),
    onToggleRevert: ((Int) -> Unit)? = null,
    onToggleConfirm: ((Int) -> Unit)? = null,
    chineseLookup: ((Int) -> String?)? = null
) {
    var ops by remember(before, after) { mutableStateOf<List<DiffOp>?>(null) }
    LaunchedEffect(before, after) {
        ops = withContext(Dispatchers.Default) {
            computeLineDiff(before.lines(), after.lines()).filter { it.type != ' ' }
        }
    }
    val currentOps = ops
    if (currentOps == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RustedPrimary)
        }
        return
    }
    if (currentOps.isEmpty()) {
        Text("无差异", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f))
        return
    }
    val blocks = remember(currentOps) {
        val result = mutableListOf<MutableList<DiffOp>>()
        for (op in currentOps) {
            if (result.isNotEmpty() && op.type == '-' && result.last().last().type == '+')
                result.add(mutableListOf())
            if (result.isEmpty()) result.add(mutableListOf())
            result.last().add(op)
        }
        result
    }
    LazyColumn {
        items(blocks.size) { blockIdx ->
            val block = blocks[blockIdx]
            val isReverted = blockIdx in revertedBlocks
            val isConfirmed = blockIdx in confirmedBlocks
            var expanded by remember(blockIdx) { mutableStateOf(false) }
            var needsExpand by remember(blockIdx) { mutableStateOf(false) }
            val firstDel = block.firstOrNull { it.type == '-' }
            val firstAdd = block.firstOrNull { it.type == '+' }
            val jumpLine = if (firstDel != null) firstDel.oldLine - 1 else (firstAdd?.newLine ?: 1) - 1

            Column(
                Modifier.fillMaxWidth()
            ) {
                block.forEach { op ->
                    val (rowBg, prefixColor, textColor) = when {
                        isReverted -> Triple(Color.Transparent, Color(0xFF666666), RustedOnBackground.copy(alpha = 0.35f))
                        isConfirmed -> Triple(Color(0xFF1B5E20).copy(alpha = 0.12f), Color(0xFF66BB6A), RustedOnBackground)
                        op.type == '+' -> Triple(Color(0xFF1B5E20).copy(alpha = 0.25f), Color(0xFF4CAF50), RustedOnBackground)
                        else -> Triple(Color(0xFFB71C1C).copy(alpha = 0.25f), Color(0xFFE57373), RustedOnBackground.copy(alpha = 0.6f))
                    }
                    val label = if (op.type == '+') op.newLine else op.oldLine
                    // 只对新增行（+）查中文翻译：修改前的内容在当前文件中已不存在
                    val chinese = if (op.type == '+') chineseLookup?.invoke(op.newLine) else null
                    val showChinese = !chinese.isNullOrEmpty() && chinese != op.text
                    Row(
                        Modifier.fillMaxWidth().background(rowBg),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "$label", fontSize = 10.sp,
                            color = RustedOnBackground.copy(alpha = when { isReverted -> 0.2f; isConfirmed -> 0.55f; else -> 0.45f }),
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            if (op.type == '+') "+" else "-", fontSize = 11.sp,
                            color = prefixColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(14.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            SelectionContainer {
                                Text(
                                    op.text, fontSize = 11.sp, color = textColor,
                                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (!expanded && it.hasVisualOverflow) needsExpand = true }
                                )
                            }
                            if (showChinese && chinese != null) {
                                Spacer(Modifier.height(1.dp))
                                SelectionContainer {
                                    Text(
                                        chinese, fontSize = 9.sp,
                                        color = if (isReverted) textColor else textColor.copy(alpha = 0.55f),
                                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                // 操作按钮行
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConfirmed) {
                        Text("已确认", fontSize = 10.sp, color = Color(0xFF66BB6A).copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                    } else if (isReverted) {
                        Text("已回退", fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.3f))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (onToggleConfirm != null && !isReverted) {
                        IconButton(onClick = { onToggleConfirm(blockIdx) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Check,
                                "确认此块", Modifier.size(14.dp),
                                tint = if (isConfirmed) Color(0xFF4CAF50) else Color(0xFF66BB6A).copy(alpha = 0.5f)
                            )
                        }
                    }
                    if (onToggleRevert != null && !isConfirmed) {
                        IconButton(onClick = { onToggleRevert(blockIdx) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Close,
                                "回退此块", Modifier.size(14.dp),
                                tint = if (isReverted) RustedPrimary else RustedError.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (needsExpand) {
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                if (expanded) "收起" else "展开", Modifier.size(14.dp),
                                tint = RustedPrimary
                            )
                        }
                    }
                    if (onJump != null) {
                        IconButton(onClick = { onJump(jumpLine) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "跳转", Modifier.size(14.dp), tint = RustedPrimary)
                        }
                    }
                }
            }
            if (blockIdx < blocks.size - 1) {
                HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.06f))
            }
        }
    }
}

/** 计算部分回退后的内容：将指定 diff 块回退到旧版本 */
internal fun computePartialRevert(before: String, after: String, revertedBlockIndices: Set<Int>): String = 
    applyBlocksTo(before, after, revertedBlockIndices, isRevert = true)

/** 计算确认后的基线内容：确认的块用新版本替换旧基线 */
internal fun computeConfirmedBaseline(before: String, after: String, confirmedBlockIndices: Set<Int>): String =
    applyBlocksTo(before, after, confirmedBlockIndices, isRevert = false)

private fun applyBlocksTo(before: String, after: String, targetIndices: Set<Int>, isRevert: Boolean): String {
    val allOps = computeLineDiff(before.lines(), after.lines())
    val changedOps = allOps.filter { it.type != ' ' }
    val blocks = mutableListOf<MutableList<DiffOp>>()
    for (op in changedOps) {
        if (blocks.isNotEmpty() && op.type == '-' && blocks.last().last().type == '+')
            blocks.add(mutableListOf())
        if (blocks.isEmpty()) blocks.add(mutableListOf())
        blocks.last().add(op)
    }
    val opToBlock = mutableMapOf<DiffOp, Int>()
    blocks.forEachIndexed { idx, block -> block.forEach { opToBlock[it] = idx } }
    val result = mutableListOf<String>()
    for (op in allOps) {
        val bIdx = opToBlock[op]
        val targeted = bIdx != null && bIdx in targetIndices
        when {
            op.type == ' ' -> result.add(op.text)
            op.type == '+' && isRevert && targeted -> { /* 回退+：跳过 */ }
            op.type == '+' && isRevert && !targeted -> result.add(op.text)
            op.type == '+' && !isRevert && targeted -> result.add(op.text)  // 确认：纳入基线
            op.type == '+' && !isRevert && !targeted -> { /* 非确认+：不纳入基线 */ }
            op.type == '-' && isRevert && targeted -> result.add(op.text)   // 回退-：恢复
            op.type == '-' && isRevert && !targeted -> { /* 非回退-：跳过 */ }
            op.type == '-' && !isRevert && targeted -> { /* 确认-：基线中移除 */ }
            op.type == '-' && !isRevert && !targeted -> result.add(op.text) // 非确认-：保留
        }
    }
    return result.joinToString("\n")
}

/** Debug 任务进度卡片：玻璃态风格，活跃任务带进度条和脉冲动画色，完成任务灰色小字 */
@Composable
private fun DebugTaskCard(name: String, current: Int, total: Int, detail: String, active: Boolean) {
    val bgColor = if (active) Color(0xFF1A1A2E) else Color(0xFF2D2D2D)
    val accentColor = if (active) Color(0xFF4FC3F7) else Color(0xFF888888)
    val textColor = if (active) Color.White else Color.White.copy(alpha = 0.7f)
    val txtSize = if (active) 11.sp else 10.sp

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (active) 6.dp else 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 第一行：任务名 + 计数
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (active) "⚡ $name" else "✓ $name",
                    fontSize = txtSize,
                    color = textColor,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (total > 0 || (!active)) {
                    Text(
                        if (total > 0) "$current/$total" else "",
                        fontSize = 9.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // 第二行：进度条（仅活跃且有总量的任务）
            if (active && total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                    color = accentColor,
                    trackColor = accentColor.copy(alpha = 0.15f),
                )
            }
            // 第三行：详情文件名
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    fontSize = 9.sp,
                    color = accentColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkPanel(
    topPadding: Dp = 0.dp,
    sort: BookmarkManager.Sort,
    onSortChange: (BookmarkManager.Sort) -> Unit,
    refresher: Int,
    onOpen: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
    onBackToDir: () -> Unit
) {
    val bookmarks = remember(refresher, sort) { BookmarkManager.all(sort) }
    var longPressPath by remember { mutableStateOf<String?>(null) }
    var longPressBounds by remember { mutableStateOf<Rect?>(null) }
    val panelBg = Color(0xFF252526)
    val rowHover = Color(0xFF2D2D2D)
    val primary = Color(0xFF4FC3F7)
    val panelText = Color.White
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val panelWidth = 260.dp

    // 遮罩容器：左侧面板 + 右侧点按层（点击关闭，不遮挡面板内子点击）
    // 支持水平滑动关闭：向左拖动面板/遮罩超过阈值后松手自动关闭，否则回弹
    val panelWidthPx = with(LocalDensity.current) { panelWidth.toPx() }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffset.value < -panelWidthPx / 3f) {
                            scope.launch {
                                dragOffset.animateTo(-panelWidthPx, tween(200))
                                onClose()
                            }
                        } else {
                            scope.launch { dragOffset.animateTo(0f, tween(200)) }
                        }
                    },
                    onDragCancel = { scope.launch { dragOffset.animateTo(0f, tween(200)) } }
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtMost(0f)) }
                }
            }
    ) {
        // 视觉遮罩：已按需求隐藏（不压暗背景），点空白关闭由右侧透明点击层负责
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .align(Alignment.CenterStart)
                .padding(top = topPadding), // 从顶栏下方开始，避免标题行被顶栏遮挡
            color = panelBg
        ) {
            Column(Modifier.fillMaxSize()) {
                // 头部：标题 + 排序切换
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("书签", color = panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (sort == BookmarkManager.Sort.TIME) "按时间" else "按名称",
                        color = primary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(rowHover)
                            .clickable {
                                onSortChange(if (sort == BookmarkManager.Sort.TIME) BookmarkManager.Sort.NAME else BookmarkManager.Sort.TIME)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                HorizontalDivider(color = panelText.copy(alpha = 0.08f))
                // 列表
                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("暂无书签\n点击顶栏书签标记当前文件", color = panelText.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(bookmarks, key = { it.path }) { b ->
                            var rowBounds by remember(b.path) { mutableStateOf<Rect?>(null) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        val pos = coords.positionInWindow()
                                        val sz = coords.size
                                        rowBounds = Rect(pos, Size(sz.width.toFloat(), sz.height.toFloat()))
                                    }
                                    .combinedClickable(
                                        onClick = { onOpen(b.path) },
                                        onLongClick = {
                                            longPressPath = b.path
                                            longPressBounds = rowBounds
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = if (b.pinned) primary else panelText.copy(alpha = 0.2f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, color = panelText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${if (b.pinned) "置顶 · " else ""}${timeFmt.format(Date(b.addedAt))}",
                                        color = panelText.copy(alpha = 0.4f),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = panelText.copy(alpha = 0.08f))
                // 底部：返回
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onBackToDir)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp), tint = panelText.copy(alpha = 0.7f))
                    Spacer(Modifier.width(4.dp))
                    Text("返回", color = panelText, fontSize = 13.sp)
                }
            }
        }
        // 面板右侧遮罩点按关闭（不含左侧 panelWidth 面板区）
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = panelWidth)
                .clickable(onClick = onClose)
        )
    }

    // 长按动作弹窗（与文件浏览器长按菜单一致风格）
    longPressPath?.let { p ->
        val entry = bookmarks.firstOrNull { it.path == p }
        val bounds = longPressBounds ?: return@let
        Popup(
            popupPositionProvider = FileMenuPositionProvider(bounds),
            onDismissRequest = { longPressPath = null; longPressBounds = null },
            properties = PopupProperties(focusable = true)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = RustedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(0.5.dp, RustedOnBackground.copy(alpha = 0.10f))
            ) {
                Column(Modifier.width(132.dp).padding(vertical = 4.dp)) {
                    FloatingMenuItem(
                        Icons.Default.PushPin,
                        if (entry?.pinned == true) "取消置顶" else "置顶",
                        onClick = {
                            onTogglePin(p, entry?.pinned != true)
                            longPressPath = null; longPressBounds = null
                        }
                    )
                    HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
                    FloatingMenuItem(
                        Icons.Outlined.DeleteOutline,
                        "移出书签",
                        tint = RustedError,
                        onClick = {
                            onRemove(p)
                            longPressPath = null; longPressBounds = null
                        }
                    )
                }
            }
        }
    }
}

private fun relativeDirPath(file: File, basePath: String): String {
    val parent = file.parentFile?.absolutePath ?: return ""
    if (basePath.isEmpty()) return parent
    val base = File(basePath).absolutePath
    return when {
        parent.startsWith(base) -> {
            val rel = parent.removePrefix(base).removePrefix(File.separator)
            if (rel.isEmpty()) "." else rel
        }
        else -> {
            val es = android.os.Environment.getExternalStorageDirectory().absolutePath
            if (parent.startsWith(es)) parent.removePrefix(es).removePrefix(File.separator) else parent
        }
    }
}

fun getDefaultPath(): String {
    val es = android.os.Environment.getExternalStorageDirectory()
    return listOf(File(es, "rustedWarfare/mods"), File(es, "rustedWarfare/units"), File(es, "rustedWarfare"), es).firstOrNull { it.exists() }?.absolutePath ?: es.absolutePath
}

@Composable
fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, tint = if (selected) RustedPrimary else RustedOnBackground.copy(alpha = 0.65f)) },
        label = { Text(label, color = if (selected) RustedPrimary else RustedOnBackground.copy(alpha = 0.65f), fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal) },
        selected = selected, onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Color.Transparent,
            unselectedContainerColor = Color.Transparent
        ),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
    )
}

internal fun parseHex(hex: String) = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { DarkBg }

