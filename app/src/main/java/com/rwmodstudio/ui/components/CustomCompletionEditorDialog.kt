package com.rwmodstudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.screens.CustomCompletion
import com.rwmodstudio.ui.screens.FORMAT_EMPTY_VALUE
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSurface

@Composable
fun CustomCompletionEditorDialog(
    item: CustomCompletion?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomCompletion) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var nameEn by remember { mutableStateOf(item?.nameEn ?: "") }
    var value by remember { mutableStateOf(item?.value ?: "") }
    var desc by remember { mutableStateOf(item?.desc ?: "") }

    val engine = remember { TranslationEngine.getInstance() }
    LaunchedEffect(name) {
        if (nameEn.isBlank() && name.isNotBlank() && engine.isLoaded) {
            val en = engine.getTranslationDict().getTranslationBack(name)
            if (en.isNotBlank() && en != name) {
                nameEn = en
            }
        }
    }
    var detail by remember { mutableStateOf(item?.detail ?: "") }
    var example by remember { mutableStateOf(item?.example ?: "") }
    var category by remember { mutableStateOf(item?.category ?: listOf<String>()) }
    var formatCategory by remember { mutableStateOf(item?.formatCategory ?: "属性") }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showFormatPicker by remember { mutableStateOf(false) }

    val formatCategories = listOf("属性" to "\$name:值", FORMAT_EMPTY_VALUE to "\$name:", "values" to "值", "特定值" to "\$name")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "添加补全" else "编辑补全") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(nameEn, { nameEn = it }, label = { Text("英文名") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("留空则尝试从翻译库自动匹配") })
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value, { value = it }, label = { Text("默认值") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("代码补全时使用的值") })
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().clickable { showCategoryPicker = true }) {
                    OutlinedTextField(category.joinToString(", ").ifEmpty { "点击选择" }, { }, label = { Text("补全分类") }, singleLine = true, modifier = Modifier.fillMaxWidth().clickable { showCategoryPicker = true }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, readOnly = true, enabled = false, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if (category.isNotEmpty()) RustedPrimary else MaterialTheme.colorScheme.outline, unfocusedBorderColor = MaterialTheme.colorScheme.outline, disabledBorderColor = if (category.isNotEmpty()) RustedPrimary else MaterialTheme.colorScheme.outline, disabledTextColor = RustedOnBackground, disabledLabelColor = RustedOnBackground.copy(alpha = 0.6f)))
                }
                Spacer(Modifier.height(4.dp))
                val fmtLabel = formatCategories.find { it.first == formatCategory }?.second ?: formatCategory
                Box(Modifier.fillMaxWidth().clickable { showFormatPicker = true }) {
                    OutlinedTextField(fmtLabel, { }, label = { Text("格式分类") }, singleLine = true, modifier = Modifier.fillMaxWidth().clickable { showFormatPicker = true }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, readOnly = true, enabled = false, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RustedPrimary, unfocusedBorderColor = MaterialTheme.colorScheme.outline, disabledBorderColor = RustedPrimary, disabledTextColor = RustedOnBackground, disabledLabelColor = RustedOnBackground.copy(alpha = 0.6f)))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(detail, { }, label = { Text("类型(只读)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = false, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f)))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(example, { }, label = { Text("示例(只读)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = false, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.6f)))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val dict = if (engine.isLoaded) engine.getTranslationDict() else null
                    fun translateValue(zh: String): String {
                        if (dict == null || zh.isBlank()) return zh
                        return dict.getValueTranslationBack(zh).takeIf { it != zh } ?: zh
                    }
                    onSave(
                        (item ?: CustomCompletion(name = name)).copy(
                            name = name,
                            value = value,
                            desc = desc,
                            detail = detail,
                            example = example,
                            category = category.distinct(),
                            formatCategory = formatCategory,
                            isOverridden = true,
                            nameEn = nameEn.takeIf { it.isNotBlank() } ?: name,
                            valueEn = translateValue(value),
                            descEn = translateValue(desc),
                            detailEn = item?.detailEn?.takeIf { it.isNotBlank() } ?: detail,
                            exampleEn = item?.exampleEn?.takeIf { it.isNotBlank() } ?: example
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = RustedSurface
    )

    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            title = { Text("选择补全分类（可多选）") },
            text = {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(categories) { cat ->
                        val isSelected = cat in category
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                category = if (isSelected) category - cat else category + cat
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(cat, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCategoryPicker = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { category = listOf(); showCategoryPicker = false }) { Text("清空") } },
            containerColor = RustedSurface
        )
    }

    if (showFormatPicker) {
        AlertDialog(
            onDismissRequest = { showFormatPicker = false },
            title = { Text("选择格式分类") },
            text = {
                Column {
                    formatCategories.forEach { (key, descText) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { formatCategory = key; showFormatPicker = false }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (key == formatCategory) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = RustedPrimary) else Spacer(Modifier.width(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) { Text(key, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium); Text("格式: $descText", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFormatPicker = false }) { Text("关闭") } },
            containerColor = RustedSurface
        )
    }
}
