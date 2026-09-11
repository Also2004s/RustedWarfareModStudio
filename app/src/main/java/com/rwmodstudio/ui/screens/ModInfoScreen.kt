package com.rwmodstudio.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SaveHistoryManager
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.util.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * mod-info.txt 可视化编辑页。
 *
 * 与普通 ini 不同，mod-info.txt 是模组元数据文件，节固定为 [mod]/[music]。
 * 本页把它渲染成表单：读入回填 → 编辑 → 保存写回。
 * 解析/写回采用「分段保留 + 键级局部写回」：文件按 [mod]/[music] 切成段，已知键提取为表单字段；
 * 保存时只有被编辑过的键重写（行级替换），没动过的键/未知键/注释整段原样保留——
 * 因此只改描述，保存后 title/minVersion 等原样不动；music 节没编辑就一个字都不写
 * （一般 mod-info.txt 没有 [music] 节），未编辑的版本号/注释/未知键等也不丢内容。
 */

/** mod-info.txt 表单数据（[mod] 五字段 + [music] 两字段；无 version 键，版本号习惯写在 title 里） */
private class ModInfoData(
    var title: String = "",
    var description: String = "",
    var tags: String = "",
    var minVersion: String = "",
    var thumbnail: String = "",
    var sourceFolder: String = "",
    var playExclusively: Boolean = false
)

/** 节头匹配（忽略大小写与首尾空白） */
private fun isSectionHeader(line: String, name: String): Boolean =
    line.trim().equals("[$name]", ignoreCase = true)

/** 单行 `key:value` 提取（忽略大小写；空白收窄） */
private fun parseSingleValue(line: String, key: String): String? {
    val t = line.trimStart()
    if (!t.startsWith("$key:", ignoreCase = true)) return null
    return t.substring(key.length + 1).trim()
}

/**
 * 从标题提取末尾版本号 token（含 v 前缀与后缀字母，大小写不敏感），无则返回 null。
 * 例："Mod v1.6.0" → "v1.6.0"；"Mod 1.15p11" → "1.15p11"；"Mod" → null。
 */
private fun extractVersionFromTitle(title: String): String? {
    val rx = Regex("(v?)(\\d+(?:\\.\\d+){1,3}[A-Za-z0-9._-]*)\\s*$", RegexOption.IGNORE_CASE)
    return rx.find(title)?.value?.trim()
}

/** 从标题中移除末尾版本号 token，返回去掉版本（并收尾空白）后的基础名。 */
private fun removeVersionFromTitle(title: String, token: String): String {
    val idx = title.indexOf(token)
    if (idx < 0) return title
    return title.removeRange(idx, idx + token.length).trimEnd()
}

/** 待确认的项目版本号同步信息（受影响文件 + 替换对） */
private class VersionSyncInfo(val files: List<File>, val oldToken: String, val newToken: String)

/** 任意节头判定（`[xxx]`） */
private fun isAnySectionHeader(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("[") && t.endsWith("]")
}

/** 节结束位置：下一个节头或 EOF（startIdx 是节头行下标） */
private fun sectionEnd(lines: List<String>, startIdx: Int): Int {
    var i = startIdx + 1
    while (i < lines.size) {
        if (isAnySectionHeader(lines[i])) return i
        i++
    }
    return lines.size
}

/**
 * 解析结果：表单数据 + 文件分段（原始行）。
 * 分段策略：文件按 [mod] → [music] 顺序切成 preMod / modLines / between / musicLines / postMusic。
 * 某节不存在时对应段为空。保存时「未编辑的节整段原样写回」，编辑过的节做键级局部写回——
 * 只重写被编辑的键行，其余键/未知键/注释原样，实现最小变更（music 节没动就一个字都不写）。
 */
private data class ParsedModInfo(
    val data: ModInfoData,
    val preMod: List<String>,
    val modLines: List<String>,
    val between: List<String>,
    val musicLines: List<String>,
    val postMusic: List<String>,
    /** description 原文是否为 `"""` 多行块格式（决定写回用块还是 `\n` 转义单行） */
    val descIsBlock: Boolean = false
)

/** 解析 mod-info.txt：分段 + 提取已知键到表单 */
private fun parseModInfo(text: String): ParsedModInfo {
    val data = ModInfoData()
    var descIsBlock = false
    val lines = text.split("\n")
    val modIdx = lines.indexOfFirst { isSectionHeader(it, "mod") }
    val musicIdx = lines.indexOfFirst { isSectionHeader(it, "music") }
    val modEnd = if (modIdx >= 0) sectionEnd(lines, modIdx) else -1
    val musicEnd = if (musicIdx >= 0) sectionEnd(lines, musicIdx) else -1

    val preMod: List<String>
    val modLines: List<String>
    val between: List<String>
    val musicLines: List<String>
    val postMusic: List<String>
    when {
        modIdx >= 0 && musicIdx >= 0 && modIdx < musicIdx -> {
            preMod = lines.subList(0, modIdx)
            modLines = lines.subList(modIdx, modEnd)
            between = lines.subList(modEnd, musicIdx)
            musicLines = lines.subList(musicIdx, musicEnd)
            postMusic = lines.subList(musicEnd, lines.size)
        }
        modIdx >= 0 -> {
            preMod = lines.subList(0, modIdx)
            modLines = lines.subList(modIdx, modEnd)
            between = lines.subList(modEnd, lines.size)
            musicLines = emptyList()
            postMusic = emptyList()
        }
        musicIdx >= 0 -> {
            preMod = lines.subList(0, musicIdx)
            modLines = emptyList()
            between = emptyList()
            musicLines = lines.subList(musicIdx, musicEnd)
            postMusic = lines.subList(musicEnd, lines.size)
        }
        else -> {
            preMod = lines
            modLines = emptyList()
            between = emptyList()
            musicLines = emptyList()
            postMusic = emptyList()
        }
    }

    // 提取 [mod] 字段（跳过节头行；description 支持 """ 多行块）
    var i = 1
    while (i < modLines.size) {
        val line = modLines[i]
        val t = line.trimStart()
        when {
            t.startsWith("title:", ignoreCase = true) -> data.title = parseSingleValue(line, "title") ?: ""
            t.startsWith("tags:", ignoreCase = true) -> data.tags = parseSingleValue(line, "tags") ?: ""
            t.startsWith("minVersion:", ignoreCase = true) -> data.minVersion = parseSingleValue(line, "minVersion") ?: ""
            t.startsWith("thumbnail:", ignoreCase = true) -> data.thumbnail = parseSingleValue(line, "thumbnail") ?: ""
            t.startsWith("description:", ignoreCase = true) -> {
                val v = parseSingleValue(line, "description") ?: ""
                if (v.startsWith("\"\"\"")) {
                    // 多行块：收集到闭合 """（含首行已带的部分）。
                    // 块内真实换行原样保留，不做 \n 转义（防止块内字面 \n 被展平）；
                    // 剔除首尾边框换行（解析器给块加的隐含 \n 不属于内容），保证显示干净、往返稳定。
                    descIsBlock = true
                    val sb = StringBuilder(v.removePrefix("\"\"\""))
                    i++
                    while (i < modLines.size) {
                        val l = modLines[i]
                        val closeIdx = l.indexOf("\"\"\"")
                        if (closeIdx >= 0) {
                            sb.append('\n').append(l.substring(0, closeIdx))
                            i++ // 消费闭合行，i 落到块后第一行
                            break
                        } else {
                            sb.append('\n').append(l)
                            i++
                        }
                    }
                    data.description = sb.toString().trim('\n')
                    // 块已推进 i（闭合后一行或节尾），continue 跳过外层 i++，
                    // 否则块后的键（如 minVersion）会被隔行跳过读不到
                    continue
                } else {
                    // 单行值：引号剥离 + 字面 \n 转真实换行显示
                    data.description = v.removeSurrounding("\"").removeSurrounding("'").replace("\\n", "\n")
                }
            }
        }
        i++
    }
    // 提取 [music] 字段
    i = 1
    while (i < musicLines.size) {
        val line = musicLines[i]
        val t = line.trimStart()
        when {
            t.startsWith("sourceFolder:", ignoreCase = true) -> data.sourceFolder = parseSingleValue(line, "sourceFolder") ?: ""
            t.startsWith("whenUsingUnitsFromThisMod_playExclusively:", ignoreCase = true) ->
                data.playExclusively = (parseSingleValue(line, "whenUsingUnitsFromThisMod_playExclusively") ?: "").equals("true", ignoreCase = true)
        }
        i++
    }
    return ParsedModInfo(data, preMod, modLines, between, musicLines, postMusic, descIsBlock)
}

/** [mod] 节已知键（顺序 = 追加新键时的顺序；无 version，版本号习惯写在 title 里） */
private val MOD_KEYS = listOf("title", "description", "tags", "minVersion", "thumbnail")
/** [music] 节已知键 */
private val MUSIC_KEYS = listOf("sourceFolder", "whenUsingUnitsFromThisMod_playExclusively")

/** 行首键名（小写；非 `key:value` 行返回 null，如注释/空行） */
private fun lineKey(line: String): String? {
    val t = line.trimStart()
    val idx = t.indexOf(':')
    if (idx <= 0) return null
    val k = t.substring(0, idx)
    if (k.any { it.isWhitespace() || it == ';' || it == '#' }) return null // 注释等行
    return k.lowercase()
}

/** description 是否以 `"""` 多行块起始 */
private fun isBlockStart(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("description:", ignoreCase = true) &&
        t.substringAfter(':', "").trimStart().startsWith("\"\"\"")
}

/** 跳过 description 块剩余行（含闭合 `"""` 行），返回下一行下标 */
private fun skipToBlockEnd(lines: List<String>, startIdx: Int): Int {
    var i = startIdx + 1
    while (i < lines.size) {
        if (lines[i].contains("\"\"\"")) return i + 1
        i++
    }
    return lines.size
}

/** 表单值 → 原始字符串（键名大小写不敏感：lineKey 返回小写、表单存驼峰） */
private fun rawValue(d: ModInfoData, key: String): String = when (key.lowercase()) {
    "title" -> d.title
    "description" -> d.description
    "tags" -> d.tags
    "minversion" -> d.minVersion
    "thumbnail" -> d.thumbnail
    "sourcefolder" -> d.sourceFolder
    "whenusingunitsfromthismod_playexclusively" -> d.playExclusively.toString()
    else -> ""
}

/** 真实换行 → 字面 `\n`（单行写回用） */
private fun escNewline(d: String): String = if (d.contains('\n')) d.replace("\n", "\\n") else d

/** 键值格式化一行（description 按块/单行分派） */
private fun formatKeyValue(key: String, value: String, descIsBlock: Boolean): String =
    if (key == "description") {
        if (descIsBlock) blockDescription(value) else "description:${escNewline(value)}"
    } else "$key:$value"

/**
 * 键级局部写回核心：重建被编辑过的节。
 * 逐行走原节：被编辑的键 → 行替换为表单值（值为空则删除该行；description 块整块替换/删除）；
 * 未动的键/未知键/注释/空行 → 原样保留；原节没有、本次新增的键追加到节尾（按已知键顺序）。
 */
private fun rebuildSection(
    original: List<String>,
    headerName: String,
    d: ModInfoData,
    dirtyKeys: Set<String>,
    descIsBlock: Boolean
): List<String> {
    val header = original.firstOrNull { isSectionHeader(it, headerName) } ?: "[$headerName]"
    val out = mutableListOf(header)
    val existing = mutableSetOf<String>()
    // 统一小写比较:lineKey 返回小写,而表单 markDirty 存驼峰键(如 "minVersion"/"sourceFolder"),
    // 不归一化会导致行级替换不命中(旧行删不掉)+ 追加判定恒真(重复追加) → 出现两行版本号
    val dirty = dirtyKeys.map { it.lowercase() }.toSet()
    val knownKeys = if (headerName == "music") MUSIC_KEYS else MOD_KEYS

    var i = 1
    while (i < original.size) {
        val line = original[i]
        val key = lineKey(line)
        if (key != null) {
            existing.add(key)
            if (key in dirty) {
                // 取原始键名（驼峰）写回,避免把 minVersion 存成小写 minversion;
                // 值非空 → 行替换;值空 → 删除该行(块则整块删除)
                val canonical = knownKeys.firstOrNull { it.lowercase() == key } ?: key
                if (rawValue(d, canonical).isNotBlank()) {
                    out.add(formatKeyValue(canonical, rawValue(d, canonical), descIsBlock))
                }
                i = if (isBlockStart(line)) skipToBlockEnd(original, i) else i + 1
                continue
            }
        }
        // 未编辑 / 未知行：原样保留（description 块整块原样——起始行、内容行、闭合行逐行输出，
        // 不能跳过：skipToBlockEnd 只跳不输出，会把块内容+闭合行在保存时丢掉）
        if (key == "description" && isBlockStart(line)) {
            out.add(line)
            i++
            while (i < original.size && !original[i].contains("\"\"\"")) {
                out.add(original[i])
                i++
            }
            if (i < original.size) { out.add(original[i]); i++ } // 闭合行
        } else {
            out.add(line)
            i++
        }
    }

    // 追加：原节没有、本次编辑且值非空的键（同样用小写与 existing 比较）
    for (k in knownKeys) {
        if (k.lowercase() in dirty && k.lowercase() !in existing && rawValue(d, k).isNotBlank()) {
            out.add(formatKeyValue(k, rawValue(d, k), descIsBlock))
        }
    }
    return out
}

/**
 * 写回 mod-info.txt（分段保留 + 键级局部写回）：
 * - 未编辑的节整段原样保留（music 节没动 → 文件里一个字都不写）；
 * - 编辑过的节逐行重建：只替换被编辑的键行，其余键/未知键/注释原样——
 *   例如只改描述，保存后 title/minVersion/注释/未知键都原样不动；
 * - description 按原文格式写回：单行 → 真实换行转回字面 `\n`；原文 `"""` 块 → 保持块格式
 *   （块内真实换行原样、不做 \n 转义，防止块内字面 \n 被展平混叠）；
 * - 布尔开关 always 写入（true/false 都有显式语义）；空值键删除/不写（缺省即游戏默认）；
 * - 原文件没有的节、本次才写入（如新增 music），在对应位置追加（与前文空行分隔）。
 */
private fun buildModInfoText(p: ParsedModInfo, dirtyKeys: Set<String>): String {
    val modSection = if (MOD_KEYS.any { it in dirtyKeys }) {
        rebuildSection(p.modLines, "mod", p.data, dirtyKeys, p.descIsBlock)
    } else p.modLines
    val musicSection = if (MUSIC_KEYS.any { it in dirtyKeys }) {
        rebuildSection(p.musicLines, "music", p.data, dirtyKeys, p.descIsBlock)
    } else p.musicLines

    val out = mutableListOf<String>()
    out.addAll(p.preMod)
    if (modSection.isNotEmpty()) {
        // 原文件没有 [mod] 节、本次才写入：与前文隔一个空行
        if (out.isNotEmpty() && p.modLines.isEmpty()) out.add("")
        out.addAll(modSection)
    }
    out.addAll(p.between)
    if (musicSection.isNotEmpty()) {
        if (out.isNotEmpty() && p.musicLines.isEmpty()) out.add("")
        out.addAll(musicSection)
    }
    out.addAll(p.postMusic)
    return out.joinToString("\n")
}

/**
 * description 以 `"""` 多行块写回：块内真实换行原样，不做 \n 转义。
 * 剔除首尾边框换行（与解析端 trim 对称），保证块写回后重新解析结果一致、不累积变形。
 * 写回形如：
 *   description:"""
 *   第一行
 *   第二行
 *   """
 */
private fun blockDescription(d: String): String {
    val body = d.trim('\n')
    return buildString {
        append("description:\"\"\"")
        for (l in body.split("\n")) append('\n').append(l)
        append("\n\"\"\"")
    }
}

@Composable
fun ModInfoScreen(
    filePath: String,
    onBack: () -> Unit,
    onModifiedChange: (Boolean) -> Unit = {},
    saveTrigger: Int = 0,
    // 替换写盘前回调：先把受影响文件中「打开着且未保存」的编辑器工作区落盘，避免替换基于过期缓冲覆盖未保存修改
    onSaveWorkspaceBeforeReplace: suspend (Set<String>) -> Unit = {},
    // 替换写盘成功后上报被修改的绝对路径集合，供上层失效工作区缓存并让打开实例重载
    onFilesModified: (Set<String>) -> Unit = {}
) {
    val context = LocalContext.current
    val dir = remember(filePath) { File(filePath).parentFile ?: RwmodPaths.rwmodDir }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var minVersion by remember { mutableStateOf("") }
    var thumbnail by remember { mutableStateOf("") }
    var sourceFolder by remember { mutableStateOf("") }
    var playExclusively by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<ParsedModInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // 版本号（独立于原始键，默认从标题提取；仅用户修改后才同步到标题与项目）
    var version by remember { mutableStateOf("") }
    var originalVersion by remember { mutableStateOf("") }
    var versionEdited by remember { mutableStateOf(false) }
    // 待确认的项目版本号同步（扫描出的受影响文件 + 替换对），null 表示无待确认
    var pendingVersionSync by remember { mutableStateOf<VersionSyncInfo?>(null) }
    // 键级编辑标记：被编辑过的键名集合 → 保存时只重写这些键的行，其余键/未知行/注释原样保留
    // （music 节没编辑过 → 整节原样，一般 mod-info.txt 没有 [music] 节就一个字不写）。
    // 注意：保存成功后只清 edited（顶栏灯灭），不清 dirtyKeys —— 重建基于表单值，
    // 若清掉，后续再改其它键保存时会把刚写入的键又还原成解析时的旧分段，导致内容丢失。
    var dirtyKeys by remember { mutableStateOf(setOf<String>()) }
    var edited by remember { mutableStateOf(false) }
    fun markDirty(key: String) { dirtyKeys = dirtyKeys + key; edited = true }
    LaunchedEffect(edited) { onModifiedChange(edited) }

    // 加载（IO 只读文件；解析与 state 赋值回主线程）
    LaunchedEffect(filePath) {
        val text = withContext(Dispatchers.IO) {
            try { File(filePath).readText(Charsets.UTF_8) } catch (e: Exception) { "" }
        }
        val p = parseModInfo(text)
        val data = p.data
        title = data.title
        description = data.description
        tags = data.tags
        minVersion = data.minVersion
        thumbnail = data.thumbnail
        sourceFolder = data.sourceFolder
        playExclusively = data.playExclusively
        // 版本号默认从标题提取（只回填，不在加载时改动标题）
        val v = extractVersionFromTitle(data.title) ?: ""
        version = v
        originalVersion = v
        versionEdited = false
        pendingVersionSync = null
        parsed = p
        loaded = true
    }

    // 选图对话框状态
    var showImagePicker by remember { mutableStateOf(false) }
    // 选文件夹对话框状态
    var showFolderPicker by remember { mutableStateOf(false) }
    // 返回确认状态
    var showBackConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 扫描项目内精确出现的旧版本 token；命中则弹确认框，未命中静默跳过。 */
    fun scanAndConfirmVersionSync(oldToken: String, newToken: String) {
        scope.launch(Dispatchers.IO) {
            val files = dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("ini", "template", "txt") }
                .filter { f -> try { f.readText().indexOf(oldToken) >= 0 } catch (_: Exception) { false } }
                .toList()
            withContext(Dispatchers.Main) {
                if (files.isNotEmpty()) pendingVersionSync = VersionSyncInfo(files, oldToken, newToken)
            }
        }
    }

    fun save() {
        val p = parsed ?: return

        // —— 版本号同步：仅用户改过版本号才触发 ——
        val oldToken = originalVersion
        val newToken = version.trim()
        var needProjectSync = false
        if (versionEdited) {
            val newTitle = when {
                oldToken.isNotBlank() -> {
                    val base = removeVersionFromTitle(title, oldToken)
                    if (newToken.isNotBlank()) (base.trimEnd() + " " + newToken).trim() else base.trim()
                }
                newToken.isNotBlank() -> (title.trimEnd() + " " + newToken).trim() // 原标题无版本号，补一个
                else -> title
            }
            if (newTitle != title) { title = newTitle; markDirty("title") }
            // 只有旧版本号非空且不同于新值才需要项目内精确替换
            if (oldToken.isNotBlank() && oldToken != newToken) needProjectSync = true
        }

        val data = ModInfoData(title, description, tags, minVersion, thumbnail, sourceFolder, playExclusively)
        // 键级局部写回：只重写被编辑过的键，其余键/未知行/注释整段原样保留
        val content = buildModInfoText(p.copy(data = data), dirtyKeys)
        scope.launch(Dispatchers.IO) {
            val saved = try {
                File(filePath).parentFile?.mkdirs()
                File(filePath).atomicWriteText(content)
                true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
                false
            }
            if (saved) withContext(Dispatchers.Main) {
                edited = false // 保存成功：顶栏保存按钮熄灭
                android.widget.Toast.makeText(context, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                originalVersion = newToken // 同步完成：避免下次保存重复触发
                if (needProjectSync) scanAndConfirmVersionSync(oldToken, newToken)
            }
        }
    }

    // 顶栏保存按钮触发（MainApp 侧 saveTrigger 递增即保存一次）
    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) save()
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中...", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f)) }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ===== 模组信息 =====
        Text("模组信息", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RustedPrimary)
        FormField(
            label = "版本号 version",
            value = version,
            onValueChange = {
                version = it
                if (!versionEdited) versionEdited = true
                edited = true // 点亮顶栏保存（version 是独立字段，不走 markDirty，避免污染键级写回）
            },
            placeholder = "默认读取标题里的版本号；修改后保存会同步写入标题并在项目内精确替换"
        )
        FormField(label = "标题 title", value = title, onValueChange = { title = it; markDirty("title") }, placeholder = "模组标题，会显示到加载页面里面（版本号习惯写在标题里，如 xxx v1.6.0）")
        FormField(label = "描述 description", value = description, onValueChange = { description = it; markDirty("description") }, multiLine = true, minLines = 4, placeholder = "模组描述；原文为 \"\"\" 多行块时保持块格式，单行时换行自动转成 \\n")
        FormField(label = "标签 tags", value = tags, onValueChange = { tags = it; markDirty("tags") }, placeholder = "模组标签，逗号分隔，如：模组,自制")
        FormField(label = "最低版本 minVersion", value = minVersion, onValueChange = { minVersion = it; markDirty("minVersion") }, placeholder = "decal 为 v1.15p9，数组为 v1.15p11，缺省会爆错")
        // 略图
        Text("略图 thumbnail", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumbFull = remember(thumbnail) { if (thumbnail.isNotBlank()) File(dir, thumbnail).takeIf { it.isFile() } else null }
            val thumbBitmap = remember(thumbFull) {
                thumbFull?.let { try { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } catch (_: Exception) { null } }
            }
            if (thumbBitmap != null) {
                Image(thumbBitmap, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)))
                Spacer(Modifier.width(10.dp))
            } else {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(RustedOnBackground.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Text("无", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.4f))
                }
                Spacer(Modifier.width(10.dp))
            }
            OutlinedTextField(
                value = thumbnail, onValueChange = { thumbnail = it; markDirty("thumbnail") }, singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("xxx.png（相对项目目录）", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showImagePicker = true }) { Text("选图") }
            if (thumbnail.isNotBlank()) TextButton(onClick = { thumbnail = ""; markDirty("thumbnail") }) { Text("清除", color = RustedError) }
        }

        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))

        // ===== 音乐 =====
        Text("音乐", Modifier.padding(start = 16.dp, bottom = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RustedPrimary)
        Text("源文件夹 sourceFolder", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sourceFolder, onValueChange = { sourceFolder = it; markDirty("sourceFolder") }, singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("music/（相对项目目录）", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showFolderPicker = true }) { Text("选文件夹") }
            if (sourceFolder.isNotBlank()) TextButton(onClick = { sourceFolder = ""; markDirty("sourceFolder") }) { Text("清除", color = RustedError) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当使用本模组时强制播放", fontSize = 13.sp, color = RustedOnBackground)
                Text("whenUsingUnitsFromThisMod_playExclusively", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.4f))
            }
            Switch(checked = playExclusively, onCheckedChange = { playExclusively = it; markDirty("whenUsingUnitsFromThisMod_playExclusively") })
        }

        Spacer(Modifier.height(24.dp))
    }

    // 返回确认：无编辑直接返回；有编辑先弹确认，再按一次强制返回
    androidx.activity.compose.BackHandler {
        if (!edited) {
            onBack()
        } else if (showBackConfirm) {
            showBackConfirm = false
            onBack()
        } else {
            showBackConfirm = true
        }
    }

    if (showBackConfirm) AlertDialog(
        onDismissRequest = { showBackConfirm = false },
        title = { Text("未保存的修改") },
        text = { Text("返回将丢弃本次表单修改，确定返回？") },
        confirmButton = { TextButton(onClick = { showBackConfirm = false; onBack() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { showBackConfirm = false }) { Text("取消") } }
    )

    // 版本号同步确认：对项目内精确出现的旧版本做批量替换
    pendingVersionSync?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingVersionSync = null },
            title = { Text("同步版本号") },
            text = { Text("将在 ${info.files.size} 个文件中把「${info.oldToken}」精确替换为「${info.newToken}」，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    val files = info.files
                    val o = info.oldToken
                    val n = info.newToken
                    pendingVersionSync = null
                    scope.launch(Dispatchers.IO) {
                        var replaced = 0
                        var aborted = false
                        var replacedPaths = emptySet<String>()
                        try {
                            // 替换前先把受影响文件中「打开着且未保存」的编辑器工作区落盘，避免覆盖未保存修改
                            onSaveWorkspaceBeforeReplace(files.map { it.absolutePath }.toSet())
                            val collected = mutableSetOf<String>()
                            for (f in files) {
                                try {
                                    val oldContent = f.readText()
                                    val newContent = oldContent.replace(o, n)
                                    if (newContent != oldContent) {
                                        f.atomicWriteText(newContent)
                                        SaveHistoryManager.record(context, f.absolutePath, oldContent, newContent)
                                        replaced++
                                        collected.add(f.absolutePath)
                                    }
                                } catch (_: Exception) {}
                            }
                            replacedPaths = collected
                        } catch (e: Exception) {
                            // 工作区落盘失败：中止替换，避免基于过期缓冲写盘覆盖未保存修改
                            aborted = true
                        }
                        withContext(Dispatchers.Main) {
                            if (!aborted && replacedPaths.isNotEmpty()) onFilesModified(replacedPaths)
                            val msg = if (aborted) "有编辑器未保存内容，无法同步，请先保存" else "已同步 $replaced 个文件"
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { pendingVersionSync = null }) { Text("取消") } }
        )
    }

    if (showImagePicker) {
        ImagePickerDialog(dir = dir, current = thumbnail, onPick = { name ->
            thumbnail = name
            markDirty("thumbnail")
            showImagePicker = false
        }, onDismiss = { showImagePicker = false })
    }

    if (showFolderPicker) {
        FolderPickerDialog(dir = dir, onPick = { rel ->
            sourceFolder = rel
            markDirty("sourceFolder")
            showFolderPicker = false
        }, onDismiss = { showFolderPicker = false })
    }
}

/** 表单字段（单行/多行） */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    multiLine: Boolean = false,
    minLines: Int = 1,
    placeholder: String = ""
) {
    Text(label, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        singleLine = !multiLine,
        minLines = if (multiLine) minLines else 1,
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
    )
}

/** 从项目目录选图：图片文件列表 + 缩略图预览，点击回填相对文件名 */
@Composable
private fun ImagePickerDialog(
    dir: File,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var images by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(dir) {
        images = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp") }
                ?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择略图") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text("图片需位于项目目录内（${dir.name}）", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 6.dp))
                if (images.isEmpty()) {
                    Text("目录内没有图片文件", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(images) { name ->
                            val full = File(dir, name)
                            val bmp = remember(name) { try { BitmapFactory.decodeFile(full.absolutePath)?.asImageBitmap() } catch (_: Exception) { null } }
                            val selected = name == current
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) RustedPrimary.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { onPick(name) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bmp != null) {
                                    Image(bmp, null, Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(name, fontSize = 13.sp, color = if (selected) RustedPrimary else RustedOnBackground, modifier = Modifier.weight(1f))
                                if (selected) Text("✓", fontSize = 13.sp, color = RustedPrimary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 从项目目录选文件夹：逐级下钻（仅限项目目录内），回填相对项目目录的路径（正斜杠分隔） */
@Composable
private fun FolderPickerDialog(
    dir: File,           // 项目根目录
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember(dir) { mutableStateOf(dir) }
    var folders by remember { mutableStateOf(listOf<File>()) }
    LaunchedEffect(currentDir) {
        folders = withContext(Dispatchers.IO) {
            currentDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }
    // 当前目录相对项目根（"" = 根目录；/ 分隔）
    val rel = remember(currentDir, dir) {
        if (currentDir == dir) "" else try { currentDir.relativeTo(dir).invariantSeparatorsPath } catch (_: Exception) { "" }
    }
    // 可否返回上级（仅限项目目录内）
    val canGoUp = remember(currentDir, dir) {
        val p = currentDir.parentFile
        if (p == null) false else try { p.canonicalPath.startsWith(dir.canonicalPath) } catch (_: Exception) { false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文件夹") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    if (rel.isEmpty()) "当前：项目根目录" else "当前：$rel/",
                    fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 6.dp)
                )
                if (canGoUp) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable { currentDir = currentDir.parentFile!! }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Text("上级目录", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                }
                if (folders.isEmpty()) {
                    Text("目录内没有子文件夹", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(folders) { f ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .clickable { currentDir = f }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.6f))
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(rel) }) { Text("选择当前文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
