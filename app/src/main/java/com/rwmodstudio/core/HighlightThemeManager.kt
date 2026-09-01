package com.rwmodstudio.core

import kotlinx.serialization.Serializable

/**
 * 高亮预设：一份命名的配色快照，可作为可选主题出现在高亮主题选择列表中。
 *
 * @param id 稳定唯一标识（"p" + 时间戳），覆盖更新不改变；未来支持改名时不破坏引用。
 * @param name 显示名（预设列表中展示；重名时提示覆盖）。
 * @param colors 35 分类配色（[DarkThemeColors]）。
 * @param isDark 是否为暗色主题（影响括号默认基色等）。
 * @param source 来源（预留扩展位，当前仅 "saved"（自定义高亮另存））。
 */
@Serializable
data class HighlightPreset(
    val id: String,
    val name: String,
    val colors: DarkThemeColors,
    val isDark: Boolean = true,
    val source: String = "saved"
)

/**
 * 高亮预设的统一收口：内置主题表 + 预设 CRUD。
 *
 * 主题 key 约定：
 * - 内置主题：`dark` / `light` / `pure` / `custom`（与 [SettingsManager.highlightTheme] 现值兼容）
 * - 用户预设：`preset:<id>`
 */
object HighlightThemeManager {

    const val MAX_PRESETS = 20
    const val PRESET_KEY_PREFIX = "preset:"

    sealed class AddResult {
        data class Added(val preset: HighlightPreset) : AddResult()
        data class NameConflict(val existing: HighlightPreset) : AddResult()
        data class LimitExceeded(val max: Int) : AddResult()
        data class InvalidName(val reason: String) : AddResult()
    }

    /** 内置主题表：key -> (中文名, 对应配色；custom 为 null 表示用当前编辑色) */
    private val builtinThemes = linkedMapOf(
        "dark" to ("深色" to DarkThemeColors.Default),
        "light" to ("浅色" to DarkThemeColors.PresetLight),
        "pure" to ("纯净" to DarkThemeColors.PresetPure),
        "custom" to ("自定义" to null)
    )

    fun builtinEntries(): List<Pair<String, String>> =
        builtinThemes.map { it.key to it.value.first }

    /** 用户预设列表（响应式：读 [ThemeState.highlightPresets]，CRUD 后由 applyPresets 刷新）。 */
    fun userPresets(): List<HighlightPreset> = ThemeState.highlightPresets

    /** UI 主题列表用：(key, 显示名)，内置 4 项 + 用户预设。 */
    fun allThemeEntries(): List<Pair<String, String>> =
        builtinEntries() + userPresets().map { PRESET_KEY_PREFIX + it.id to it.name }

    /** 按主题 key（含 "preset:" 前缀）查预设；非预设 key 返回 null。 */
    fun findPreset(presetKey: String): HighlightPreset? =
        if (presetKey.startsWith(PRESET_KEY_PREFIX)) {
            userPresets().firstOrNull { PRESET_KEY_PREFIX + it.id == presetKey }
        } else {
            null
        }

    /** 按主题 key 取配色；custom 返回 null（表示使用当前编辑色）。 */
    fun colorsFor(key: String): DarkThemeColors? {
        builtinThemes[key]?.let { return it.second }
        return findPreset(key)?.colors
    }

    /** 是否有效主题 key（内置名 或 存在的预设）。 */
    fun isThemeKeyValid(key: String): Boolean =
        key in builtinThemes || findPreset(key) != null

    /** 生成默认预设名："预设 N"，跳过已占用。 */
    fun nextDefaultName(): String {
        val used = userPresets().map { it.name }.toSet()
        var n = 1
        while ("预设 $n" in used) n++
        return "预设 $n"
    }

    fun addPreset(name: String, colors: DarkThemeColors, isDark: Boolean, source: String): AddResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return AddResult.InvalidName("预设名不能为空")
        val current = userPresets()
        current.firstOrNull { it.name == trimmed }?.let { return AddResult.NameConflict(it) }
        if (current.size >= MAX_PRESETS) return AddResult.LimitExceeded(MAX_PRESETS)
        val preset = HighlightPreset(genId(), trimmed, colors, isDark, source)
        ThemeState.applyPresets(current + preset)
        return AddResult.Added(preset)
    }

    /** 覆盖更新预设颜色（保留原 id 与 name）。 */
    fun updatePreset(id: String, colors: DarkThemeColors, isDark: Boolean): Boolean {
        val current = userPresets()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val next = current.toMutableList().apply {
            this[idx] = this[idx].copy(colors = colors, isDark = isDark)
        }
        ThemeState.applyPresets(next)
        return true
    }

    /** 删除预设；若当前正使用该预设，回退到深色主题。 */
    fun deletePreset(id: String): Boolean {
        val current = userPresets()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        if (ThemeState.highlightTheme == PRESET_KEY_PREFIX + id) {
            ThemeState.applyHighlightTheme("dark")
        }
        ThemeState.applyPresets(next)
        return true
    }

    private fun genId(): String {
        var id = "p" + System.currentTimeMillis()
        val used = userPresets().map { it.id }.toSet()
        var suffix = 0
        while (id in used) {
            id = "p" + System.currentTimeMillis() + "_" + (++suffix)
        }
        return id
    }
}
