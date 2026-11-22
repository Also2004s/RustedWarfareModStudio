package com.rwmodstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.translation.ResourceNameWhitelist
import com.rwmodstudio.core.translation.TranslationCacheRefresher
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.theme.AppCodeFontFamily
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary
import com.rwmodstudio.ui.theme.RustedSurface

/**
 * 开发者模式 → 翻译禁用管理。
 *
 * 统一管理四个「禁用翻译」开关与资源名白名单：
 * - 开关关闭：照常翻译；
 * - 开关打开：该类名字在补全、整行翻译、文件翻译缓存中都不翻译；
 * - 资源名开关只对白名单（英文资源名）生效，白名单可增删/重置。
 *
 * 任一项变更都会写设置并触发后台缓存重建（[TranslationCacheRefresher]）。
 */
@Composable
fun TranslationBlockScreen() {
    val context = LocalContext.current
    var blockTag by remember { mutableStateOf(SettingsManager.devTranslationBlockTag) }
    var blockResource by remember { mutableStateOf(SettingsManager.devTranslationBlockResource) }
    var blockMemory by remember { mutableStateOf(SettingsManager.devTranslationBlockMemory) }
    var blockVariable by remember { mutableStateOf(SettingsManager.devTranslationBlockVariable) }
    var whitelist by remember { mutableStateOf(ResourceNameWhitelist.displayNames()) }
    var newName by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    val engine = remember { TranslationEngine.getInstance() }

    /** 白名单只收英文资源名：中文先按翻译库反查成英文，反查不到或仍非英文则拒绝 */
    fun normalizeResourceName(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        fun isAsciiName(s: String) = s.isNotEmpty() &&
            s.all { it.code < 128 && (it.isLetterOrDigit() || it == '_') }
        if (isAsciiName(t)) return t
        val en = engine.getTranslationDict().getTranslationBackUnfiltered(t)
        return en.takeIf { it != t && isAsciiName(it) }
    }

    fun notifyChanged() {
        TranslationCacheRefresher.onSettingsChanged(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            "开关关闭 = 照常翻译；打开 = 该类名字在补全、整行翻译、文件翻译缓存里都不翻译（后台自动重建缓存）。",
            fontSize = 11.sp,
            color = RustedOnBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(bottom = 10.dp)
        )

        SettingsCard {
            BlockSwitchRow(
                title = "标签禁用翻译",
                subtitle = "tags 等标签属性里的标签名保持原文",
                checked = blockTag,
                isFirst = true,
                onCheckedChange = {
                    blockTag = it
                    SettingsManager.devTranslationBlockTag = it
                    notifyChanged()
                }
            )
            BlockDivider()
            BlockSwitchRow(
                title = "资源名禁用翻译",
                subtitle = "只对下方白名单里的资源名生效（双向停用）",
                checked = blockResource,
                onCheckedChange = {
                    blockResource = it
                    SettingsManager.devTranslationBlockResource = it
                    notifyChanged()
                }
            )
            BlockDivider()
            BlockSwitchRow(
                title = "内存名禁用翻译",
                subtitle = "@memory / defineUnitMemory 声明的内存名保持原文",
                checked = blockMemory,
                onCheckedChange = {
                    blockMemory = it
                    SettingsManager.devTranslationBlockMemory = it
                    notifyChanged()
                }
            )
            BlockDivider()
            BlockSwitchRow(
                title = "变量名禁用翻译",
                subtitle = "@global / @define 声明的变量名保持原文",
                checked = blockVariable,
                isLast = true,
                onCheckedChange = {
                    blockVariable = it
                    SettingsManager.devTranslationBlockVariable = it
                    notifyChanged()
                }
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "资源名白名单（${whitelist.size}）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RustedPrimary,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    ResourceNameWhitelist.reset()
                    whitelist = ResourceNameWhitelist.displayNames()
                    notifyChanged()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("重置为内置", fontSize = 11.sp) }
        }
        Text(
            "白名单里的英文资源名在「资源名禁用翻译」打开时双向停用（英译中/中译英都不译）。",
            fontSize = 10.sp,
            color = RustedOnBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        SettingsCard {
            if (whitelist.isEmpty()) {
                Text(
                    "白名单为空（当前不参与任何资源名翻译过滤）",
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = 0.45f),
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                whitelist.forEachIndexed { index, name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name,
                            fontSize = 12.sp,
                            fontFamily = AppCodeFontFamily,
                            color = RustedOnBackground,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除",
                            tint = RustedOnBackground.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    ResourceNameWhitelist.remove(name)
                                    whitelist = ResourceNameWhitelist.displayNames()
                                    notifyChanged()
                                }
                        )
                    }
                    if (index != whitelist.lastIndex) BlockDivider()
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("新增英文资源名，如 oil", fontSize = 11.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = AppCodeFontFamily),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val name = normalizeResourceName(newName)
                    if (name == null) {
                        inputError = "请填英文资源名（中文会先按翻译库转成英文，查不到则无法加入）"
                    } else {
                        inputError = null
                        ResourceNameWhitelist.add(name)
                        whitelist = ResourceNameWhitelist.displayNames()
                        newName = ""
                        notifyChanged()
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(15.dp), tint = RustedSecondary)
                Spacer(Modifier.width(3.dp))
                Text("添加", fontSize = 11.sp)
            }
        }
        inputError?.let { msg ->
            Text(
                msg,
                fontSize = 10.sp,
                color = RustedSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BlockSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = if (isFirst) 8.dp else 6.dp, bottom = if (isLast) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = RustedOnBackground)
            Text(subtitle, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.45f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = RustedPrimary)
        )
    }
}

@Composable
private fun BlockDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .height(0.5.dp)
            .background(RustedOnBackground.copy(alpha = 0.08f))
    )
}
