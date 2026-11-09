package com.rwmodstudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.JsonTextFormatter
import com.rwmodstudio.core.LoadedTextFile
import com.rwmodstudio.core.TextFileFormats
import com.rwmodstudio.core.TextFileLoader
import com.rwmodstudio.core.TextViewerFormat
import com.rwmodstudio.core.ThemeState
import com.rwmodstudio.ui.theme.RustedAccent
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ViewerLoadState {
    data object Loading : ViewerLoadState
    data class Ready(val file: LoadedTextFile) : ViewerLoadState
    data class Failed(val message: String) : ViewerLoadState
}

/**
 * 只读文本查看器：JSON / JSONC / XML / YAML / CSV / TSV / Markdown / 日志 / Properties / 纯文本。
 *
 * - 行号 + 等宽字体 + 横向滚动 + 可复制；
 * - JSON/JSONC 提供「格式化/还原」与按行语法高亮；
 * - 解析失败只做提示，原始文本照常显示；
 * - 超过 [TextFileFormats.AUTO_LOAD_LIMIT_BYTES] 的文件默认只加载前
 *   [TextFileFormats.PARTIAL_LOAD_BYTES]，避免主线程卡死 / OOM。
 *
 * 页面标题由 MainApp 的全局标题栏提供，本页不再加标题栏。
 */
@Composable
fun TextViewerScreen(
    fileName: String,
    filePath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val format = remember(fileName) { TextFileFormats.formatOf(fileName) }

    var forcedLoadLimit by remember(filePath) { mutableLongStateOf(0L) }
    var reloadTick by remember(filePath) { mutableIntStateOf(0) }
    var loadState by remember(filePath) { mutableStateOf<ViewerLoadState>(ViewerLoadState.Loading) }
    var jsonError by remember(filePath) { mutableStateOf<String?>(null) }
    var formattedText by remember(filePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath, forcedLoadLimit, reloadTick) {
        loadState = ViewerLoadState.Loading
        formattedText = null
        jsonError = null
        val file = File(filePath)
        val result = withContext(Dispatchers.IO) {
            try {
                when {
                    !file.exists() -> Result.failure<LoadedTextFile>(IllegalStateException("文件不存在"))
                    file.isDirectory -> Result.failure<LoadedTextFile>(IllegalStateException("这是文件夹，不能用文本查看器打开"))
                    else -> {
                        val total = file.length()
                        val limit = when {
                            forcedLoadLimit > 0L -> minOf(forcedLoadLimit, TextFileFormats.HARD_LOAD_LIMIT_BYTES)
                            total > TextFileFormats.AUTO_LOAD_LIMIT_BYTES -> TextFileFormats.PARTIAL_LOAD_BYTES
                            else -> TextFileFormats.AUTO_LOAD_LIMIT_BYTES
                        }
                        Result.success(TextFileLoader.load(file, limit))
                    }
                }
            } catch (e: Exception) {
                Result.failure<LoadedTextFile>(e)
            } catch (e: OutOfMemoryError) {
                Result.failure<LoadedTextFile>(RuntimeException("内存不足，文件过大", e))
            }
        }

        result.fold(
            onSuccess = { loaded ->
                loadState = ViewerLoadState.Ready(loaded)
                // 只对完整加载的内容做自动校验；截断内容必然解析失败，不再误报
                if (format != null && format.canFormat && !loaded.truncated) {
                    jsonError = withContext(Dispatchers.Default) {
                        JsonTextFormatter.check(loaded.text, format == TextViewerFormat.JSONC)
                    }
                }
            },
            onFailure = { e ->
                loadState = ViewerLoadState.Failed(e.message ?: "读取失败")
            }
        )
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    Column(modifier.fillMaxSize().background(RustedBackground)) {
        when (val state = loadState) {
            ViewerLoadState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }

            is ViewerLoadState.Failed -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("无法读取文件：${state.message}", color = RustedError, fontSize = 13.sp)
            }

            is ViewerLoadState.Ready -> {
                val loaded = state.file
                val contentText = formattedText ?: loaded.text
                val isJson = format == TextViewerFormat.JSON || format == TextViewerFormat.JSONC
                val canLoadAll = loaded.truncated && loaded.totalBytes <= TextFileFormats.HARD_LOAD_LIMIT_BYTES
                val overHardLimit = loaded.truncated && loaded.totalBytes > TextFileFormats.HARD_LOAD_LIMIT_BYTES

                // 操作行（不是标题栏）：格式标识 + 复制 / 格式化 / 加载全部 / 重新加载
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = format?.label ?: "文本",
                        color = RustedPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = "${TextFileFormats.formatSize(loaded.totalBytes)} · ${loaded.charsetName}",
                        color = RustedOnBackground.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    ViewerAction("复制全文") {
                        clipboard.setText(AnnotatedString(contentText))
                        toast(
                            if (loaded.truncated) "已复制已加载的内容（文件过大未完整加载）"
                            else "已复制全文"
                        )
                    }
                    if (isJson) {
                        ViewerAction(if (formattedText != null) "还原" else "格式化") {
                            if (formattedText != null) {
                                formattedText = null
                            } else if (loaded.truncated) {
                                toast("文件未完整加载，请先点「加载全部」再格式化")
                            } else {
                                scope.launch {
                                    val parsed = withContext(Dispatchers.Default) {
                                        JsonTextFormatter.parse(loaded.text, format == TextViewerFormat.JSONC)
                                    }
                                    if (parsed.formatted != null) {
                                        formattedText = parsed.formatted
                                        jsonError = null
                                    } else {
                                        jsonError = parsed.error
                                        toast("JSON 解析失败，已保留原文")
                                    }
                                }
                            }
                        }
                    }
                    if (canLoadAll) {
                        ViewerAction("加载全部") {
                            forcedLoadLimit = TextFileFormats.HARD_LOAD_LIMIT_BYTES
                        }
                    }
                    ViewerAction("重新加载") {
                        reloadTick++
                    }
                }

                if (jsonError != null) {
                    NoticeBar(
                        color = RustedError,
                        text = "JSON 解析失败：${jsonError}\n仍可查看原始文本，可点「格式化」重试。"
                    )
                }
                if (loaded.truncated) {
                    NoticeBar(
                        color = RustedAccent,
                        text = if (overHardLimit) {
                            "文件较大（${TextFileFormats.formatSize(loaded.totalBytes)}），已停止加载以避免内存不足，" +
                                "当前显示前 ${TextFileFormats.formatSize(loaded.loadedBytes)}。"
                        } else {
                            "文件较大（${TextFileFormats.formatSize(loaded.totalBytes)}），当前仅加载前 " +
                                "${TextFileFormats.formatSize(loaded.loadedBytes)}，可点「加载全部」。"
                        }
                    )
                }

                if (contentText.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("空文件", color = RustedOnBackground.copy(alpha = 0.4f), fontSize = 13.sp)
                    }
                } else {
                    CodeTextBlock(
                        text = contentText,
                        highlightJson = isJson,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(label, fontSize = 12.sp, color = RustedPrimary)
    }
}

@Composable
private fun NoticeBar(color: Color, text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = color, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CodeTextBlock(
    text: String,
    highlightJson: Boolean,
    modifier: Modifier = Modifier
) {
    // 大文件的行切分放到后台线程，避免在组合阶段阻塞主线程；小文件直接切分，避免闪烁
    var lines by remember(text) {
        mutableStateOf(if (text.length <= 200_000) text.split('\n') else null)
    }
    LaunchedEffect(text) {
        if (lines == null) {
            lines = withContext(Dispatchers.Default) { text.split('\n') }
        }
    }
    // 滚动位置随内容重置，切换文件/格式化后从顶部开始
    val hScroll = remember(text) { ScrollState(0) }
    val listState = remember(text) { LazyListState() }
    val colors = rememberJsonLineColors()
    val lineCount = lines?.size ?: 1
    val gutterWidth = remember(lineCount) {
        (lineCount.coerceAtLeast(1).toString().length * 9 + 14).dp
    }

    Box(modifier.fillMaxWidth()) {
        val currentLines = lines
        if (currentLines == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(hScroll)
            ) {
                items(currentLines.size) { index ->
                    ViewerLine(
                        lineNumber = index + 1,
                        line = currentLines[index],
                        gutterWidth = gutterWidth,
                        highlightJson = highlightJson,
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerLine(
    lineNumber: Int,
    line: String,
    gutterWidth: Dp,
    highlightJson: Boolean,
    colors: JsonLineColors
) {
    val overflow = line.length > TextFileFormats.MAX_DISPLAY_LINE_CHARS
    val shown = if (overflow) line.substring(0, TextFileFormats.MAX_DISPLAY_LINE_CHARS) else line
    val suffix = if (overflow) {
        " …（本行过长，仅显示前 ${TextFileFormats.MAX_DISPLAY_LINE_CHARS} 字符）"
    } else ""
    val annotated = remember(shown, suffix, highlightJson, colors) {
        if (highlightJson) buildJsonLine(shown + suffix, colors) else null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val gutterPx = gutterWidth.toPx()
                drawRect(
                    color = colors.gutterBg,
                    topLeft = Offset.Zero,
                    size = Size(gutterPx, size.height)
                )
                drawLine(
                    color = colors.gutterLine,
                    start = Offset(gutterPx, 0f),
                    end = Offset(gutterPx, size.height),
                    strokeWidth = 1f
                )
            }
    ) {
        Text(
            text = lineNumber.toString(),
            modifier = Modifier
                .width(gutterWidth)
                .padding(end = 8.dp),
            color = colors.gutterText,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false
        )
        SelectionContainer {
            Text(
                text = annotated ?: AnnotatedString(shown + suffix),
                modifier = Modifier.padding(start = 10.dp, end = 16.dp),
                color = colors.plain,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false
            )
        }
    }
}

private data class JsonLineColors(
    val plain: Color,
    val key: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val punctuation: Color,
    val comment: Color,
    val gutterText: Color,
    val gutterLine: Color,
    val gutterBg: Color
)

@Composable
private fun rememberJsonLineColors(): JsonLineColors {
    val onBackground = RustedOnBackground
    val dark = ThemeState.isDark
    return remember(onBackground, dark) {
        if (dark) {
            JsonLineColors(
                plain = onBackground,
                key = Color(0xFF9CDCFE),
                string = Color(0xFFCE9178),
                number = Color(0xFFB5CEA8),
                keyword = Color(0xFF569CD6),
                punctuation = onBackground.copy(alpha = 0.55f),
                comment = Color(0xFF6A9955),
                gutterText = onBackground.copy(alpha = 0.35f),
                gutterLine = onBackground.copy(alpha = 0.10f),
                gutterBg = onBackground.copy(alpha = 0.035f)
            )
        } else {
            JsonLineColors(
                plain = onBackground,
                key = Color(0xFF0451A5),
                string = Color(0xFFA31515),
                number = Color(0xFF098658),
                keyword = Color(0xFF0000FF),
                punctuation = onBackground.copy(alpha = 0.55f),
                comment = Color(0xFF008000),
                gutterText = onBackground.copy(alpha = 0.40f),
                gutterLine = onBackground.copy(alpha = 0.12f),
                gutterBg = onBackground.copy(alpha = 0.045f)
            )
        }
    }
}

/**
 * 单行 JSON 高亮（轻量、逐行）。
 *
 * JSON 字符串不能跨行，因此按行分词是安全的；JSONC 的块注释若跨行，
 * 只把当前行剩余部分当成注释，仍能正常阅读。
 */
private fun buildJsonLine(line: String, colors: JsonLineColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' -> {
                val start = i
                i++
                var escaped = false
                while (i < line.length) {
                    val c = line[i]
                    if (escaped) {
                        escaped = false
                        i++
                        continue
                    }
                    if (c == '\\') {
                        escaped = true
                        i++
                        continue
                    }
                    i++
                    if (c == '"') break
                }
                var j = i
                while (j < line.length && line[j].isWhitespace()) j++
                val isKey = j < line.length && line[j] == ':'
                withStyle(SpanStyle(color = if (isKey) colors.key else colors.string)) {
                    append(line.substring(start, i))
                }
                continue
            }

            ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                withStyle(SpanStyle(color = colors.comment)) { append(line.substring(i)) }
                return@buildAnnotatedString
            }

            ch == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                val end = line.indexOf("*/", startIndex = i + 2)
                val stop = if (end >= 0) end + 2 else line.length
                withStyle(SpanStyle(color = colors.comment)) { append(line.substring(i, stop)) }
                i = stop
                continue
            }

            ch.isDigit() || (ch == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                val start = i
                i++
                while (i < line.length &&
                    (line[i].isDigit() || line[i] == '.' || line[i] == 'e' || line[i] == 'E' ||
                        line[i] == '+' || line[i] == '-')
                ) i++
                withStyle(SpanStyle(color = colors.number)) { append(line.substring(start, i)) }
                continue
            }

            ch.isLetter() -> {
                val start = i
                while (i < line.length && line[i].isLetter()) i++
                val token = line.substring(start, i)
                withStyle(
                    SpanStyle(
                        color = if (token == "true" || token == "false" || token == "null") colors.keyword
                        else colors.plain
                    )
                ) { append(token) }
                continue
            }

            ch in "{}[]:," -> {
                withStyle(SpanStyle(color = colors.punctuation)) { append(ch) }
                i++
                continue
            }

            else -> {
                append(ch)
                i++
            }
        }
    }
}
