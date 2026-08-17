package com.rwmodstudio.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeState {
    var isDark by mutableStateOf(SettingsManager.isDarkTheme)
    var bgColor by mutableStateOf(SettingsManager.bgColor)
    var highlightTheme by mutableStateOf(SettingsManager.highlightTheme)
    var darkTokenColors by mutableStateOf(SettingsManager.darkTokenColors.toDarkThemeColors())

    // 纯净主题固定背景色
    val pureBgColor = "#F5F5F5"

    fun toggle() {
        isDark = !isDark
        SettingsManager.isDarkTheme = isDark
    }

    fun applyBgColor(color: String) {
        bgColor = color
        SettingsManager.bgColor = color
    }

    fun applyDarkTokenColors(colors: DarkThemeColors) {
        darkTokenColors = colors
        SettingsManager.darkTokenColors = colors.toJson()
    }

    fun applyHighlightTheme(theme: String) {
        highlightTheme = theme
        SettingsManager.highlightTheme = theme
        // 背景色不再随高亮主题切换而变更，保持用户独立设置
    }
}
