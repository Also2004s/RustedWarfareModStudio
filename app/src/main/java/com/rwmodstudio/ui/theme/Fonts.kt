package com.rwmodstudio.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.rwmodstudio.R
import com.rwmodstudio.core.SettingsManager

/**
 * 内置 JetBrains Mono 字体族（Compose，包含 Regular / Bold / Italic）
 */
val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * 内置霞鹜文楷 Regular 字体族（Compose）
 */
val LxgwWenKaiRegularFontFamily = FontFamily(
    Font(R.font.lxgw_wenkai_regular, FontWeight.Normal)
)

/**
 * 当前应用应使用的代码字体族（Compose）。
 * 根据 [SettingsManager.editorFontFamily] 返回对应字体族。
 */
val AppCodeFontFamily: FontFamily
    get() = when (SettingsManager.editorFontFamily) {
        "jetbrains_mono" -> JetBrainsMonoFontFamily
        "lxgw_wenkai_regular" -> LxgwWenKaiRegularFontFamily
        else -> FontFamily.Monospace
    }

/**
 * 获取编辑器当前应使用的 Typeface。
 */
fun editorTypeface(context: Context): Typeface {
    return when (SettingsManager.editorFontFamily) {
        "jetbrains_mono" -> ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        "lxgw_wenkai_regular" -> ResourcesCompat.getFont(context, R.font.lxgw_wenkai_regular) ?: Typeface.MONOSPACE
        "system" -> Typeface.DEFAULT
        else -> Typeface.MONOSPACE
    }
}

/**
 * 字体族显示名称。
 */
fun fontFamilyDisplayName(key: String): String = when (key) {
    "system" -> "系统默认"
    "system_mono" -> "系统等宽"
    "jetbrains_mono" -> "JetBrains Mono"
    "lxgw_wenkai" -> "霞鹜文楷"
    "lxgw_wenkai_regular" -> "霞鹜文楷"
    else -> "系统等宽"
}

/**
 * 设置页字体选项的有序列表。
 */
val FONT_FAMILY_OPTIONS = listOf(
    "system",
    "system_mono",
    "jetbrains_mono",
    "lxgw_wenkai_regular"
)
