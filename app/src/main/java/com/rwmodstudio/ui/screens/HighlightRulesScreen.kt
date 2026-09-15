package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.rwmodstudio.core.HighlightRuleManager
import com.rwmodstudio.core.HighlightRulesDefaults
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedError
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary

/**
 * 自定义代码高亮规则（开发者模式 → 规则）。
 *
 * 结构是「类别：列表 → 规则」：类别对应编辑器里可单独调色的一类 token，列表是同一类别下的
 * 语义分组（如值高亮下的「自身检测」「单位数量」「自身属性」）。
 * 默认规则 = 内置语法的字面量复刻（首次运行自动生成），因此默认外观与内置完全一致；
 * 用户的增删改即时落盘（`RWmod/config/highlight_rules.json`）并对已打开的编辑器热生效。
 */
@Composable
fun HighlightRulesScreen() {
    val context = LocalContext.current
    var rulesRevision by remember { mutableStateOf(HighlightRuleManager.revision) }
    var enabled by remember { mutableStateOf(HighlightRuleManager.enabled) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // 顶层另一处写法：默认隐藏（只影响显示，规则照旧生效）；点开后就地展开
    var showTopLevel by remember { mutableStateOf(SettingsManager.highlightRulesShowTopLevel) }

    val allList = HighlightRuleManager.allCategories()
    val activeCount = HighlightRuleManager.totalActiveRules()

    // 内置组结构（列表标签与顺序）+ 用户实际规则 → 页面骨架
    val items = remember(rulesRevision, showTopLevel) {
        buildScreenItems(allList, showTopLevel)
    }
    val hiddenTotal = items.sumOf { it.hiddenTopLevel }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionHeader(Icons.Default.Rule, "规则")
        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Highlight,
                    null,
                    tint = RustedOnBackground.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("自定义高亮规则", fontSize = 13.sp, color = RustedOnBackground)
                    Text(
                        "共 $activeCount 条生效规则；关闭后使用内置语法",
                        fontSize = 10.sp,
                        color = RustedOnBackground.copy(alpha = 0.45f)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        HighlightRuleManager.enabled = it
                        rulesRevision = HighlightRuleManager.revision
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RustedPrimary,
                        checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
                    )
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = RustedOnBackground.copy(alpha = 0.08f)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showResetConfirm = true }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    null,
                    tint = RustedSecondary.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("重置为内置规则", fontSize = 13.sp, color = RustedSecondary)
                    Text(
                        "按内置语法重新复刻一份默认规则，覆盖你的改动",
                        fontSize = 10.sp,
                        color = RustedOnBackground.copy(alpha = 0.45f)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = RustedOnBackground.copy(alpha = 0.08f)
            )
            // 语法诊断：手动触发，不做开关（挂启动流程没意义）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        HighlightRuleManager.runGrammarDiagnostic(context)
                        Toast.makeText(context, "已生成 RWmod/cache/highlight_rule_report.txt", Toast.LENGTH_SHORT).show()
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FactCheck,
                    null,
                    tint = RustedOnBackground.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("生成语法诊断", fontSize = 13.sp, color = RustedOnBackground)
                    Text(
                        "对比内置语法与当前规则的规则条数/顺序，报告写到 RWmod/cache/highlight_rule_report.txt",
                        fontSize = 10.sp,
                        color = RustedOnBackground.copy(alpha = 0.45f)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = RustedOnBackground.copy(alpha = 0.08f)
            )
            // 顶层另一处写法：默认折叠。内置语法在「键值对块内」和「顶层」各写了一份，
            // 顶层那份多数只是写法不同（如比较符多一个 =），隐藏只影响显示，规则照旧生效。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        showTopLevel = !showTopLevel
                        SettingsManager.highlightRulesShowTopLevel = showTopLevel
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Layers,
                    null,
                    tint = RustedOnBackground.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("显示「顶层」分组", fontSize = 13.sp, color = RustedOnBackground)
                    Text(
                        if (showTopLevel) {
                            "已显示隐藏的 $hiddenTotal 个顶层分组（与上方同名，只是语法里另一处写法不同）"
                        } else {
                            "已折叠 $hiddenTotal 个顶层分组；折叠只是不显示，它们照样参与高亮"
                        },
                        fontSize = 10.sp,
                        color = RustedOnBackground.copy(alpha = 0.45f)
                    )
                }
                Switch(
                    checked = showTopLevel,
                    onCheckedChange = {
                        showTopLevel = it
                        SettingsManager.highlightRulesShowTopLevel = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RustedPrimary,
                        checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.HelpOutline, "用法")
        SettingsCard {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "· 默认规则是内置语法的复刻，所以默认外观与原来完全一致；想改哪一类就改哪一类。",
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 关键字：整词匹配，正则元字符自动转义、前后不会粘到别的字母数字上（写 自身有活动的路径点 即可）。",
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 正则：原样使用，例如 self\\.\\w*\\(\\) 或 (自身|单位)有\\w+；同一 token 出现在多个类别时，靠上的类别优先。",
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 规则插在内置语法之前，优先级高于内置规则；改完即时生效，不用重启。",
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.55f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(Icons.Default.Category, "类别：规则")
        items.forEach { category ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = RustedBackground.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    // 类别来自主题配色表：`scope` 为空表示这个类型不参与 scope 着色
                    //（UI 高亮 / 普通文本），此时不能写规则，界面只展示说明
                    val colorable = category.scope.isNotBlank()
                    Text(
                        if (colorable) "${category.label} · ${category.scope}"
                        else "${category.label} · 非 scope 着色",
                        fontSize = 10.sp,
                        color = RustedOnBackground.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp)
                    )
                    category.lists.forEach { list ->
                        val key = category.categoryId + "\u0000" + list.listName
                        val isExpanded = key in expanded
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = if (isExpanded) expanded - key else expanded + key
                                }
                                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                null,
                                tint = RustedOnBackground.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (list.listName.isEmpty()) "还没有规则" else list.listName,
                                    fontSize = 13.sp,
                                    color = if (list.rules.isEmpty()) {
                                        RustedOnBackground.copy(alpha = 0.5f)
                                    } else {
                                        RustedOnBackground
                                    }
                                )
                                Text(
                                    when {
                                        !colorable -> "该类型不走 scope 着色，无法用规则自定义"
                                        list.rules.isEmpty() -> "该类型还没有规则，点右侧「+ 规则」添加"
                                        else -> "${list.rules.count { it.enabled }} 条生效 / 共 ${list.rules.size} 条"
                                    },
                                    fontSize = 10.sp,
                                    color = RustedOnBackground.copy(alpha = 0.4f)
                                )
                            }
                            if (colorable) {
                                TextButton(onClick = {
                                    expanded = expanded + key
                                    // 空类型（还没有任何分组）时，新规则落到以类别名命名的分组里，
                                    // 否则分组名为空、界面上会继续显示「还没有规则」
                                    val group = list.listName.ifEmpty { category.label }
                                    editing = EditTarget(category.categoryId, group, -1)
                                }) {
                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("规则", fontSize = 11.sp)
                                }
                            }
                            if (colorable && list.rules.isNotEmpty()) {
                                TextButton(onClick = {
                                    resetList(category.categoryId, list.listName)
                                    rulesRevision = HighlightRuleManager.revision
                                }) {
                                    Text("重置", fontSize = 11.sp, color = RustedSecondary)
                                }
                            }
                        }
                        if (isExpanded) {
                            if (!colorable) {
                                Text(
                                    "「${category.label}」不是按 scope 着色的类型：" +
                                        "UI 高亮由编辑器的「点击符号匹配高亮」控制，普通文本是编辑器全局前景色。" +
                                        "它们不在语法里，所以不能用关键字/正则规则自定义，" +
                                        "请到「自定义高亮」里改颜色。",
                                    fontSize = 11.sp,
                                    color = RustedOnBackground.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 10.dp)
                                )
                            } else if (list.rules.isEmpty()) {
                                Text(
                                    "内置语法在该类型下没有规则（或已被清空）。点「+ 规则」添加关键字/正则，" +
                                        "新规则优先级最高，会覆盖内置高亮。",
                                    fontSize = 11.sp,
                                    color = RustedOnBackground.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 10.dp)
                                )
                            } else {
                                val listIndex = allList.indexOfFirst {
                                    it.categoryId == category.categoryId && it.listName == list.listName
                                }
                                list.rules.forEachIndexed { ruleIndex, rule ->
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 36.dp, end = 12.dp),
                                        thickness = 0.5.dp,
                                        color = RustedOnBackground.copy(alpha = 0.08f)
                                    )
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 36.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (rule.type == HighlightRuleManager.RuleType.REGEX) "正则" else "关键字",
                                            fontSize = 9.sp,
                                            color = if (rule.type == HighlightRuleManager.RuleType.REGEX) RustedSecondary else RustedPrimary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(RustedOnBackground.copy(alpha = 0.06f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            rule.pattern,
                                            fontSize = 12.sp,
                                            fontFamily = AppCodeFontFamily,
                                            color = RustedOnBackground.copy(alpha = if (rule.enabled) 1f else 0.4f),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = rule.enabled,
                                            onCheckedChange = {
                                                HighlightRuleManager.toggleRuleAt(listIndex, ruleIndex, it)
                                                rulesRevision = HighlightRuleManager.revision
                                            },
                                            modifier = Modifier.size(width = 44.dp, height = 26.dp),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = RustedPrimary,
                                                checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
                                            )
                                        )
                                        IconButton(
                                            onClick = {
                                                editing = EditTarget(category.categoryId, list.listName, ruleIndex)
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                null,
                                                tint = RustedOnBackground.copy(alpha = 0.5f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                HighlightRuleManager.removeRuleAt(listIndex, ruleIndex)
                                                rulesRevision = HighlightRuleManager.revision
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                null,
                                                tint = RustedError.copy(alpha = 0.7f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 36.dp, end = 12.dp),
                            thickness = 0.5.dp,
                            color = RustedOnBackground.copy(alpha = 0.06f)
                        )
                    }
                    // 被折叠的「顶层另一处写法」：就地提示，点了就地展开（只影响显示）
                    if (category.hiddenTopLevel > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTopLevel = true
                                    SettingsManager.highlightRulesShowTopLevel = true
                                }
                                .padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                null,
                                tint = RustedOnBackground.copy(alpha = 0.35f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "另有 ${category.hiddenTopLevel} 个顶层分组已折叠（内置语法在另一处写法不同），点此展开",
                                fontSize = 10.sp,
                                color = RustedOnBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    editing?.let { target ->
        HighlightRuleEditDialog(
            target = target,
            onDismiss = { editing = null },
            onSaved = {
                rulesRevision = HighlightRuleManager.revision
                editing = null
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置为内置规则？") },
            text = {
                Text(
                    "将按内置语法重新复刻一份默认规则，覆盖当前全部自定义规则；" +
                        "内置语法本身不受影响，重置后外观与原来一致。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HighlightRuleManager.resetToBuiltin()
                    rulesRevision = HighlightRuleManager.revision
                    showResetConfirm = false
                }) { Text("重置", color = RustedSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 逐列表重置（按 categoryId + listName 定位） */
private fun resetList(categoryId: String, listName: String) {
    val index = HighlightRuleManager.allCategories()
        .indexOfFirst { it.categoryId == categoryId && it.listName == listName }
    if (index >= 0) HighlightRuleManager.resetListToBuiltin(index)
}

/** 编辑目标：类别 + 列表标签 + 规则下标（-1 表示新增） */
private data class EditTarget(val categoryId: String, val listName: String, val index: Int)

/** 页面骨架用的列表视图模型 */
internal data class ListVm(val listName: String, val rules: List<HighlightRuleManager.HighlightRule>)

internal data class CategoryVm(
    val categoryId: String,
    val label: String,
    val scope: String,
    val lists: List<ListVm>,
    /** 被折叠的「顶层另一处写法」分组数（>0 时显示一行「展开」提示） */
    val hiddenTopLevel: Int = 0
)

/**
 * 骨架 = 当前规则里该类别**已有的分组**（同 scope 且正文相同的重复定义已在生成端合并，
 * 不会重复显示）；该类别还没有任何规则时给一个空组占位，UI 显示「+ 规则」入口，
 * 这样任何高亮类型都能直接加规则。
 *
 * [showTopLevel] = false 时，名字带 `(顶层)` 的分组**不显示**（内置语法在键值对块内和顶层各写了一份，
 * 顶层那份多数只是写法不同，并排显示会像重复）。隐藏只影响显示：那些规则照旧生效、照旧参与合并，
 * 点分组下的「展开」即可看到。
 */
internal fun buildScreenItems(
    actual: List<HighlightRuleManager.HighlightRuleCategory>,
    showTopLevel: Boolean
): List<CategoryVm> {
    val actualByKey = actual.associateBy { it.categoryId + "\u0000" + it.listName }
    return HighlightRuleManager.availableCategories.map { info ->
        val names = actual
            .filter { it.categoryId == info.id }
            .map { it.listName }
            .distinct()
        val hidden = if (showTopLevel) 0 else names.count { HighlightRulesDefaults.isTopLevelGroup(it) }
        val visible = if (showTopLevel) names else names.filterNot { HighlightRulesDefaults.isTopLevelGroup(it) }
        val lists = if (visible.isEmpty()) {
            listOf(ListVm("", emptyList()))
        } else {
            visible.map { name -> ListVm(name, actualByKey[info.id + "\u0000" + name]?.rules ?: emptyList()) }
        }
        CategoryVm(
            categoryId = info.id,
            label = info.label,
            scope = info.scope,
            lists = lists,
            hiddenTopLevel = hidden
        )
    }
}

@Composable
private fun HighlightRuleEditDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val existing = remember(target) {
        HighlightRuleManager.rulesOfList(target.categoryId, target.listName)
            .getOrNull(target.index)
    }
    var pattern by remember(target) { mutableStateOf(existing?.pattern ?: "") }
    var isRegex by remember(target) { mutableStateOf(existing?.type == HighlightRuleManager.RuleType.REGEX) }
    val categoryLabel = HighlightRuleManager.categoryLabel(target.categoryId)
    val listLabel = target.listName.ifEmpty { "自定义" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增规则 · $categoryLabel/$listLabel" else "编辑规则 · $categoryLabel/$listLabel") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !isRegex,
                        onClick = { isRegex = false },
                        label = { Text("关键字", fontSize = 11.sp) }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = isRegex,
                        onClick = { isRegex = true },
                        label = { Text("正则", fontSize = 11.sp) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (isRegex) "正则表达式" else "关键字", fontSize = 12.sp) },
                    placeholder = {
                        Text(
                            if (isRegex) "self\\.\\w*\\(\\)" else "自身有活动的路径点",
                            fontSize = 12.sp,
                            fontFamily = AppCodeFontFamily
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        fontFamily = AppCodeFontFamily
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RustedPrimary,
                        unfocusedBorderColor = RustedOnBackground.copy(alpha = 0.2f),
                        cursorColor = RustedPrimary
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isRegex) "原样使用，注意转义：点号要写 \\. ，括号要写 \\( \\)"
                    else "整词匹配：写什么就匹配什么，前后不会粘到字母数字上",
                    fontSize = 10.sp,
                    color = RustedOnBackground.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            val valid = pattern.trim().isNotEmpty()
            TextButton(
                onClick = {
                    if (!valid) return@TextButton
                    val type = if (isRegex) HighlightRuleManager.RuleType.REGEX else HighlightRuleManager.RuleType.KEYWORD
                    if (target.index >= 0) {
                        val listIndex = HighlightRuleManager.allCategories().indexOfFirst {
                            it.categoryId == target.categoryId && it.listName == target.listName
                        }
                        if (listIndex >= 0) {
                            HighlightRuleManager.updateRuleAt(listIndex, target.index, pattern, type)
                        }
                    } else {
                        HighlightRuleManager.addRule(target.categoryId, pattern, type, target.listName)
                    }
                    onSaved()
                }
            ) { Text(if (existing == null) "添加" else "保存", color = if (valid) RustedPrimary else RustedOnBackground.copy(alpha = 0.4f)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
