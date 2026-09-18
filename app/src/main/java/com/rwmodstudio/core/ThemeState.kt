package com.rwmodstudio.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeState {
    var isDark by mutableStateOf(SettingsManager.isDarkTheme)
    var bgColor by mutableStateOf(SettingsManager.bgColor)
    var highlightTheme by mutableStateOf(SettingsManager.highlightTheme)
    var darkTokenColors by mutableStateOf(SettingsManager.darkTokenColors.toDarkThemeColors())
    var highlightPresets by mutableStateOf(SettingsManager.highlightPresets)
    var sectionHighlightEnabled by mutableStateOf(SettingsManager.sectionHighlightEnabled)
    var sectionHighlightColor by mutableStateOf(SettingsManager.sectionHighlightColor)

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

    init {
        // 用户新增的高亮类型要同步给「规则」页：类别白名单、排序、显示名都从那边取。
        // 放在 init 里保证任何时候访问 ThemeState 之后，规则页拿到的类别表都是最新的。
        syncCustomCategories(darkTokenColors)
    }

    /** 用户新增类型 → 规则页类别表（内置类型不走这里，它们在 DarkThemeColors 的常量表里） */
    private fun syncCustomCategories(colors: DarkThemeColors) {
        HighlightRuleManager.updateCustomCategories(
            colors.customTypes.map {
                HighlightRuleManager.CategoryInfo(it.id, it.label, it.effectiveScope)
            }
        )
    }

    fun applyDarkTokenColors(colors: DarkThemeColors) {
        darkTokenColors = colors
        SettingsManager.darkTokenColors = colors.toJson()
        syncCustomCategories(colors)
    }

    fun applyHighlightTheme(theme: String) {
        highlightTheme = theme
        SettingsManager.highlightTheme = theme
        // 背景色不再随高亮主题切换而变更，保持用户独立设置
    }

    /** 刷新用户高亮预设列表（新增/覆盖/删除后调用）。 */
    fun applyPresets(presets: List<HighlightPreset>) {
        highlightPresets = presets
        SettingsManager.highlightPresets = presets
    }

    /** 切换节名行高亮开关。 */
    fun toggleSectionHighlight() {
        sectionHighlightEnabled = !sectionHighlightEnabled
        SettingsManager.sectionHighlightEnabled = sectionHighlightEnabled
    }

    /** 设置节名行高亮自定义底色（#RRGGBB），并确保开关开启。 */
    fun applySectionHighlightColor(color: String) {
        sectionHighlightColor = color
        SettingsManager.sectionHighlightColor = color
        sectionHighlightEnabled = true
        SettingsManager.sectionHighlightEnabled = true
    }
}
