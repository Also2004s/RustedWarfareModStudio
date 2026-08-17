package com.rwmodstudio.ui.screens

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.rwmodstudio.MainActivity
import com.rwmodstudio.core.DiffOp
import com.rwmodstudio.core.ProjectTagScanner
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.TaskProgressManager
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.core.computeLineDiff
import com.rwmodstudio.core.translation.ProjectRegistry
import com.rwmodstudio.core.translation.TranslationDedupChecker
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.core.translation.SearchTranslationCache
import com.rwmodstudio.feature.completion.value.CALLABLE_CATEGORIES
import com.rwmodstudio.ui.components.ColorPickerDialog
import com.rwmodstudio.ui.components.DarkTokenColorDialog
import com.rwmodstudio.ui.components.DedupResultDialog
import com.rwmodstudio.ui.components.RainbowBracketSettingsPanel
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MainApp"

enum class Screen { HOME, BROWSER, EDITOR, CODE_REF, SETTINGS, TRANSLATION, CUSTOM, DEVELOPER, COMPLETION_VIEWER, COORD_VISUAL, VERSION_COMPARE, PROJECT_MANAGER, TODO_LIST }

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
    // 返回栈：记录嵌套导航路径，支持“从哪进入，返回哪去”
    val backStack = remember { mutableStateListOf<Screen>() }
    var drawerState = rememberDrawerState(DrawerValue.Closed)
    var selectedFile by rememberSaveable(stateSaver = pairSaver) { mutableStateOf<Pair<String, String>?>(null) }
    var showEditorBackDialog by remember { mutableStateOf(false) }
    var saveAndExit by rememberSaveable { mutableStateOf(false) }
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
    var editorTextCache by remember { mutableStateOf(mapOf<String, String>()) }
    // 持有当前编辑器文本，供抽屉导航离开编辑器时缓存；用普通 var 避免每次输入都触发重组
    val editorTextHolder = remember { object { var text = "" } }
    var editorSaveTrigger by remember { mutableIntStateOf(0) }
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
    val recentListState = rememberLazyListState()
    // 搜索/最近作为全局底部弹窗，记录来源页面以便打开文件后返回
    var showHomeSearchSheet by rememberSaveable { mutableStateOf(false) }
    var showRecentSheet by rememberSaveable { mutableStateOf(false) }
    var homeSearchSourceScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    var recentSourceScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }

    // 从「近期修改」差异行跳转到编辑器指定行（0-based）
    var editorJumpLine by remember { mutableIntStateOf(-1) }
    var editorJumpTick by remember { mutableIntStateOf(0) }
    var editorJumpColumnStart by remember { mutableIntStateOf(-1) }
    var editorJumpColumnEnd by remember { mutableIntStateOf(-1) }

    // 彩虹括号参数变化时用于触发编辑器刷新
    var rainbowSettingsTick by remember { mutableIntStateOf(0) }

    // 从抽屉离开编辑器时缓存当前文本；若开启自动保存则触发 EditorScreen 保存
    fun cacheEditorStateBeforeLeave() {
        if (currentScreen == Screen.EDITOR) {
            selectedFile?.second?.let { path ->
                // holder 可能尚未被编辑器事件初始化（刚加载未编辑），避免用空字符串覆盖已有缓存
                if (editorTextHolder.text.isNotEmpty()) {
                    editorTextCache = editorTextCache + (path to editorTextHolder.text)
                }
                if (SettingsManager.autoSave) editorSaveTrigger++
            }
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

    // 清空返回栈，用于抽屉切换到顶层页面
    fun clearBackStack() {
        backStack.clear()
    }

    val themeIsDark = ThemeState.isDark // Force recompose on theme toggle
    val view = LocalView.current

    // 动态设置状态栏/导航栏/窗口背景色：编辑器页面与底部栏一致，其他页面跟随主题色
    LaunchedEffect(currentScreen, ThemeState.isDark) {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (currentScreen == Screen.EDITOR) {
                val editorBarColor = Color(0xFF1E1E1E)
                val editorBgColor = if (ThemeState.isDark) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
                // 状态栏与顶部横栏同色
                window.statusBarColor = editorBarColor.toArgb()
                insetsController.isAppearanceLightStatusBars = false
                // 导航栏与窗口背景跟随底部栏主题色，避免输入法间隙黑条
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
    var browserStartPath by rememberSaveable { mutableStateOf("") }
    // 打开外部传入的文件（外部 Intent / 保存确认后共用入口）
    fun openExternalFile(name: String, path: String) {
        editorJumpLine = -1
        selectedFile = name to path
        browserStartPath = File(path).parentFile?.absolutePath ?: browserStartPath
        currentScreen = Screen.EDITOR
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
    // 项目管理 UI 状态持久化（分类、展开项、滚动位置）
    var projectManagerCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var projectManagerSelectedValue by rememberSaveable { mutableStateOf<String?>(null) }
    val projectManagerListState = rememberLazyListState()
    // 待办页面顶部横栏「添加」按钮触发器
    var todoAddTrigger by remember { mutableIntStateOf(0) }
    // 待办页返回目标（不污染 previousScreen）
    var todoReturnScreen by rememberSaveable(stateSaver = nullableScreenSaver) { mutableStateOf<Screen?>(null) }
    // 补全查看：当前选中的属性名（非空表示在演示面板详情，返回先回属性列表）
    var completionViewerSelected by rememberSaveable { mutableStateOf<String?>(null) }
    // 补全查看：右上角「+」选中的可调用对象分类（非空表示在分类浏览页）
    var completionViewerCallableCat by rememberSaveable { mutableStateOf<String?>(null) }

    // 消费 MainActivity 从外部 intent 传入的待打开文件
    LaunchedEffect(Unit) {
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
            projectRoot = folderPath
            browserStartPath = folderPath
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
                            projectRoot = folderPath
                            browserStartPath = folderPath
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
            Screen.COMPLETION_VIEWER -> {
                if (completionViewerSelected != null) {
                    completionViewerSelected = null
                } else {
                    currentScreen = popScreen(Screen.HOME)
                }
            }
            else -> {
                val target = popScreen(Screen.HOME)
                currentScreen = target
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen != Screen.EDITOR,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(250.dp), drawerContainerColor = RustedBackground) {
                Box(Modifier.fillMaxWidth().height(90.dp).background(RustedBackground), contentAlignment = Alignment.BottomStart) {
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
                DrawerItem(Icons.Default.Home, "首页", currentScreen == Screen.HOME) { clearBackStack(); currentScreen = Screen.HOME; scope.launch { drawerState.close() } }
                DrawerItem(Icons.Default.FolderOpen, "文件浏览", currentScreen == Screen.BROWSER) { clearBackStack(); currentScreen = Screen.BROWSER; scope.launch { drawerState.close() } }
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
                Text("v1.0.0", Modifier.padding(14.dp), fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.2f))
            }
        }
    ) {
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
                    Screen.COMPLETION_VIEWER -> "补全查看"
                    Screen.COORD_VISUAL -> "坐标可视化"
                    Screen.VERSION_COMPARE -> "版本对比"
                    Screen.PROJECT_MANAGER -> "项目管理"
                    Screen.TODO_LIST -> "待办"
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp).background(containerColor).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        when (currentScreen) {
                            Screen.HOME -> scope.launch { drawerState.open() }
                            Screen.BROWSER -> currentScreen = Screen.HOME
                            Screen.EDITOR -> showEditorBackDialog = true
                            Screen.VERSION_COMPARE -> currentScreen = Screen.HOME
                            Screen.PROJECT_MANAGER -> currentScreen = popScreen(Screen.HOME)
                            Screen.TODO_LIST -> {
                                val target = todoReturnScreen ?: popScreen(Screen.HOME)
                                todoReturnScreen = null
                                currentScreen = target
                            }
                            Screen.COMPLETION_VIEWER -> {
                                if (completionViewerCallableCat != null) {
                                    completionViewerCallableCat = null
                                } else if (completionViewerSelected != null) {
                                    completionViewerSelected = null
                                } else {
                                    currentScreen = popScreen(Screen.HOME)
                                }
                            }
                            else -> currentScreen = popScreen(Screen.HOME)
                        }
                    }) { Icon(if (currentScreen == Screen.HOME) Icons.Default.Menu else Icons.Default.ArrowBack, null, tint = contentColor) }
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                        Text(titleText, color = contentColor, fontSize = 18.sp, maxLines = 1)
                    }
                    if (currentScreen == Screen.EDITOR) {
                        var showColors by remember { mutableStateOf(false) }
                        var showThemes by remember { mutableStateOf(false) }
                        var showDarkTokens by remember { mutableStateOf(false) }
                        var showRainbowBracketDialog by remember { mutableStateOf(false) }
                        IconButton(onClick = {
                            editorSaveTrigger++
                            showRecentSheet = true
                            recentSourceScreen = Screen.EDITOR
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.History, "最近", Modifier.size(18.dp), tint = contentColor)
                        }
                        IconButton(onClick = { showColors = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Palette, "背景色", Modifier.size(18.dp), tint = contentColor)
                        }
                        IconButton(onClick = { showThemes = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Brush, "高亮主题", Modifier.size(18.dp), tint = contentColor)
                        }
                        IconButton(onClick = {
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage("com.corrodinggames.rts")
                                if (intent != null) {
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "未安装铁锈战争", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "启动铁锈战争失败", Toast.LENGTH_SHORT).show()
                            }
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.RocketLaunch, "启动铁锈战争", Modifier.size(18.dp), tint = contentColor)
                        }
                        if (showColors) {
                            ColorPickerDialog(
                                initialColor = ThemeState.bgColor,
                                onDismiss = { showColors = false },
                                onConfirm = { color ->
                                    ThemeState.applyBgColor(color)
                                    showColors = false
                                },
                                showInsertButton = true,
                                onInsert = { color ->
                                    editorInsertText = color
                                    editorInsertTick++
                                    showColors = false
                                }
                            )
                        }
                        if (showThemes) {
                            val hlThemes = listOf(
                                "dark" to "深色",
                                "light" to "浅色",
                                "pure" to "纯净",
                                "custom" to "自定义"
                            )
                            DropdownMenu(expanded = showThemes, onDismissRequest = { showThemes = false }) {
                                DropdownMenuItem(
                                    text = { Text("切换为${if (ThemeState.isDark) "浅色" else "深色"}", fontSize = 13.sp) },
                                    onClick = { ThemeState.toggle(); showThemes = false },
                                    leadingIcon = { Icon(if (ThemeState.isDark) Icons.Default.LightMode else Icons.Default.DarkMode, null, Modifier.size(18.dp)) }
                                )
                                HorizontalDivider()
                                hlThemes.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 13.sp) },
                                        onClick = { ThemeState.applyHighlightTheme(id); showThemes = false }
                                    )
                                }
                                if (ThemeState.highlightTheme == "custom") {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("自定义高亮色", fontSize = 13.sp) },
                                        onClick = {
                                            showThemes = false
                                            showDarkTokens = true
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("括号颜色", fontSize = 13.sp) },
                                    onClick = {
                                        showThemes = false
                                        showRainbowBracketDialog = true
                                    }
                                )
                            }
                        }
                        if (showRainbowBracketDialog) {
                            val previewBg = remember {
                                try {
                                    android.graphics.Color.parseColor(ThemeState.bgColor)
                                } catch (_: Exception) {
                                    if (ThemeState.isDark) android.graphics.Color.parseColor("#1E1E1E")
                                    else android.graphics.Color.parseColor("#F0F0F0")
                                }
                            }
                            // 预览基础色：从当前 TextMate 主题读取括号色，读不到用默认兜底，
                            // 保证预览初始色与编辑器实际渲染一致（会随高亮主题变化而变化）
                            val previewBaseColor = remember(ThemeState.highlightTheme) {
                                com.rwmodstudio.editor.RainbowColorUtils.resolvePreviewBaseColor(ThemeState.highlightTheme)
                            }
                            AlertDialog(
                                onDismissRequest = {
                                    showRainbowBracketDialog = false
                                    rainbowSettingsTick++
                                },
                                title = { Text("括号颜色", fontSize = 16.sp) },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        RainbowBracketSettingsPanel(
                                            previewBackgroundColor = previewBg,
                                            previewBaseColor = previewBaseColor
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showRainbowBracketDialog = false
                                        rainbowSettingsTick++
                                    }) {
                                        Text("关闭")
                                    }
                                }
                            )
                        }
                        if (showDarkTokens) {
                            DarkTokenColorDialog(
                                initialColors = ThemeState.darkTokenColors,
                                onDismiss = { showDarkTokens = false },
                                onConfirm = { colors ->
                                    ThemeState.applyDarkTokenColors(colors)
                                    showDarkTokens = false
                                }
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
                    if (currentScreen == Screen.COMPLETION_VIEWER) {
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
                                        completionViewerSelected = null
                                        completionViewerCallableCat = label
                                    }
                                )
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
                        onBrowseFolder = { path -> projectRoot = path; browserStartPath = path; clearBackStack(); currentScreen = Screen.BROWSER },
                        onOpenFile = { n, p -> editorJumpLine = -1; selectedFile = n to p; pushScreen(Screen.EDITOR) },
                        onOpenFolder = { name, path -> checkProjectDedup(name, path) },
                        onDedupFolder = { name, path -> doDedupCheckOnly(name, path) },
                        onShowRecent = { showRecentSheet = true; recentSourceScreen = currentScreen },
                        onShowSearch = { showHomeSearchSheet = true; homeSearchSourceScreen = currentScreen },
                        onVersionCompare = { clearBackStack(); currentScreen = Screen.VERSION_COMPARE }
                    )
                    Screen.BROWSER -> FileBrowserScreen(
                        startPath = browserStartPath,
                        rootPath = defaultPath,
                        projectRoot = projectRoot,
                        onFileSelected = { n, p ->
                            editorJumpLine = -1
                            selectedFile = n to p
                            browserStartPath = File(p).parentFile?.absolutePath ?: browserStartPath
                            pushScreen(Screen.EDITOR)
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
                        onStartPathChange = { browserStartPath = it }
                    )
                    Screen.EDITOR -> selectedFile?.let { (name, path) ->
                        EditorScreen(
                            fileName = name, filePath = path, autoWrap = autoWrap, smartWrap = smartWrap,
                            externalInsertText = editorInsertText,
                            externalInsertTick = editorInsertTick,
                            externalReplaceText = coordVisualResult ?: "",
                            externalReplaceTick = coordVisualResultTick,
                            cachedText = editorTextCache[path] ?: "",
                            onBack = {
                                editorTextCache = editorTextCache - path
                                currentScreen = popScreen(Screen.BROWSER)
                                selectedFile = null
                            },
                            onSwitchFile = { newPath ->
                                editorTextCache = editorTextCache - path
                                selectedFile = File(newPath).name to newPath
                                browserStartPath = File(newPath).parentFile?.absolutePath ?: browserStartPath
                            },
                            projectRoot = projectRoot,
                            saveAndExit = saveAndExit,
                            onSaveAndExitDone = {
                                editorTextCache = editorTextCache - path
                                saveAndExit = false
                                currentScreen = popScreen(Screen.BROWSER)
                                selectedFile = null
                            },
                            onNavigate = { target -> pushScreen(target) },
                            onOpenCoordVisual = { text ->
                                cacheEditorStateBeforeLeave()
                                coordVisualText = text
                                pushScreen(Screen.COORD_VISUAL)
                            },
                            onTextCacheRequest = { text ->
                                editorTextCache = editorTextCache + (path to text)
                            },
                            onTextChange = { editorTextHolder.text = it },
                            saveTrigger = editorSaveTrigger,
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
                            jumpLine = editorJumpLine,
                            jumpTick = editorJumpTick,
                            jumpColumnStart = editorJumpColumnStart,
                            jumpColumnEnd = editorJumpColumnEnd,
                            onExternalInsertConsumed = { editorInsertText = "" },
                            onExternalReplaceConsumed = { coordVisualResult = null },
                            rainbowSettingsTick = rainbowSettingsTick
                        )
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
                    Screen.DEVELOPER -> DeveloperModeScreen(onOpenCompletionViewer = {
                        completionViewerSelected = null
                        pushScreen(Screen.COMPLETION_VIEWER)
                    })
                    Screen.COMPLETION_VIEWER -> CompletionViewerScreen(
                        selectedPropertyName = completionViewerSelected,
                        onSelectProperty = { completionViewerSelected = it },
                        callableCategory = completionViewerCallableCat,
                        onCloseCallableCategory = { completionViewerCallableCat = null }
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
            listState = recentListState,
            onSaveCurrent = { editorSaveTrigger++ },
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSaveCurrent: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onJumpToLine: (String, String, Int) -> Unit,
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
        containerColor = RustedSurface,
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
                        selected = !showModified,
                        onClick = {
                            onShowModifiedChange(false)
                            SettingsManager.lastRecentDialogTab = false
                            onSelectedHistoryPathChange(null)
                        },
                        label = { Text("最近打开", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = showModified,
                        onClick = {
                            onShowModifiedChange(true)
                            SettingsManager.lastRecentDialogTab = true
                            onSelectedHistoryPathChange(null)
                        },
                        label = { Text("近期修改", fontSize = 12.sp) }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            val path = selectedHistoryPath
            when {
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
                                            if (path == currentOpenFilePath) {
                                                Toast.makeText(context, "请先关闭当前文件后再操作", Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val newContent = computePartialRevert(historyBefore, historyAfter, revertedBlockIndices.value)
                                                    File(path).writeText(newContent)
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
                                        if (path == currentOpenFilePath) {
                                            Toast.makeText(context, "请先关闭当前文件后再回退", Toast.LENGTH_SHORT).show()
                                            return@TextButton
                                        }
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
                    var modifiedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
                    var modifiedFilesLoading by remember { mutableStateOf(true) }
                    LaunchedEffect(showModified) {
                        if (showModified) {
                            modifiedFiles = withContext(Dispatchers.IO) { SaveHistoryManager.getRecentFiles(context) }
                            modifiedFilesLoading = false
                        }
                    }
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
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(files) { path ->
                                val file = File(path)
                                val exists = file.exists()
                                ElevatedCard(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = RustedBackground),
                                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
                                ) {
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
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = if (exists) RustedPrimary else RustedOnBackground.copy(alpha = 0.3f)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                file.name,
                                                fontSize = 13.sp,
                                                color = if (exists) RustedOnBackground else RustedOnBackground.copy(alpha = 0.4f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                relativeDirPath(file, defaultPath),
                                                fontSize = 10.sp,
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
    }

    pendingRevertPath?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingRevertPath = null },
            title = { Text("确认回退？") },
            text = { Text("将把文件恢复到最早保存前的内容，并清空该文件的修改记录。此操作不可撤销。") },
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
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
    )
}

internal fun parseHex(hex: String) = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { DarkBg }

