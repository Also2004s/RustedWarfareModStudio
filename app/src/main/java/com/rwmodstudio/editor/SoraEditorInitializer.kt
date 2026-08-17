package com.rwmodstudio.editor

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import com.rwmodstudio.core.DarkThemeColors
import com.rwmodstudio.core.SettingsManager
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

private const val TAG = "SoraEditorInitializer"

// 彩虹括号自定义颜色 ID。
// 注意：TextMateAnalyzer 使用 foreground + 255 作为 colorId，所以自定义 ID 必须 < 255，否则会覆盖主题颜色。
const val RAINBOW_BRACKET_1 = 200
const val RAINBOW_BRACKET_2 = 201
const val RAINBOW_BRACKET_3 = 202
const val RAINBOW_BRACKET_4 = 203

private val RAINBOW_BRACKET_IDS = intArrayOf(
    RAINBOW_BRACKET_1,
    RAINBOW_BRACKET_2,
    RAINBOW_BRACKET_3,
    RAINBOW_BRACKET_4
)

/**
 * sora-editor 的 TextMate 环境一次性初始化。
 *
 * 必须在创建任何 CodeEditor 之前调用（建议在 Application 或 MainActivity 中）。
 * 重复调用会被忽略。
 */
object SoraEditorInitializer {

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    /**
     * 主题名到文件路径的映射。
     */
    private val themeFiles = mapOf(
        "dark" to "textmate/themes/dark_vs.json",
        "light" to "textmate/themes/light_vs.json",
        "pure" to "textmate/themes/pure.json",
        "custom" to "textmate/themes/custom.json"
    )

    /**
     * 主题名到是否暗色的映射。
     */
    private val themeDark = mapOf(
        "dark" to true,
        "light" to false,
        "pure" to false,
        "custom" to true
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            try {
                FileProviderRegistry.getInstance().addFileProvider(
                    AssetsFileResolver(appContext.assets)
                )
                loadThemes()
                GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
                initialized = true
                Log.d(TAG, "TextMate initialized, themes=${themeFiles.keys}, grammars loaded from textmate/languages.json")
            } catch (e: Exception) {
                Log.e(TAG, "TextMate init failed", e)
            }
        }
    }

    fun isInitialized(): Boolean = initialized


    /**
     * 为指定主题配置彩虹括号颜色。
     * 最外层（depth1）使用当前 TextMate 主题中 `punctuation.bracket.round.ini` 的默认颜色，
     * 内层颜色根据用户在设置中自定义的色相/饱和度/亮度参数生成。
     */
    fun applyRainbowBracketColors(
        scheme: EditorColorScheme,
        themeName: String,
        backgroundColor: Int
    ) {
        val themeRegistry = ThemeRegistry.getInstance()
        val baseColor = RainbowColorUtils.resolveThemeBracketColor(themeRegistry)
            ?: defaultBracketColor(themeName)
        val colors = RainbowColorUtils.generatePalette(
            baseColor = baseColor,
            backgroundColor = backgroundColor,
            hueStepDegrees = SettingsManager.rainbowHueStep,
            hueDirection = SettingsManager.rainbowHueDirection,
            saturationBoost = SettingsManager.rainbowSaturationBoost,
            lightnessShift = SettingsManager.rainbowLightnessShift,
            autoLightnessDirection = SettingsManager.rainbowAutoLightnessDirection,
            visibilityGuard = SettingsManager.rainbowVisibilityGuard
        )
        RAINBOW_BRACKET_IDS.forEachIndexed { index, id ->
            scheme.setColor(id, colors[index])
        }
    }

    private fun defaultBracketColor(themeName: String): Int {
        return if (themeName in setOf("light", "pure")) {
            0xFFdeae12.toInt()
        } else {
            0xFFAD99B7.toInt()
        }
    }

    private fun loadThemes() {
        val themeRegistry = ThemeRegistry.getInstance()
        themeFiles.forEach { (name, path) ->
            try {
                val source = IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(path),
                    path,
                    null
                )
                val model = ThemeModel(source, path.substringAfterLast("/").substringBeforeLast(".")).apply {
                    setDark(themeDark[name] ?: true)
                }
                themeRegistry.loadTheme(model, false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load theme $name at $path", e)
            }
        }
    }



    /**
     * 需要加粗的 TextMate scope 列表。
     */
    private val BOLD_SCOPES = setOf(
        "entity.name.section.ini",
        "entity.name.section.prefix.ini",
        "entity.name.section.suffix.ini"
    )

    /**
     * 需要倾斜的 TextMate scope 列表。
     */
    private val ITALIC_SCOPES = setOf(
        "comment.line.ini",
        "comment.block.ini",
        "punctuation.definition.comment.ini"
    )

    /**
     * 根据当前主题配置返回对应的 TextMateColorScheme。
     *
     * @param isDarkTheme 当前是否暗色（用于兜底）
     * @param highlightTheme 主题名，如 dark / light / pure / custom
     * @param darkTokenColors 自定义高亮颜色（仅 custom 主题使用）
     */
    fun getColorScheme(
        isDarkTheme: Boolean,
        highlightTheme: String,
        darkTokenColors: DarkThemeColors = DarkThemeColors.Default,
        boldHighlight: Boolean = true,
        italicHighlight: Boolean = true
    ): EditorColorScheme {
        val registry = ThemeRegistry.getInstance()
        when (highlightTheme) {
            "custom" -> loadThemeWithColors(
                registry,
                "custom",
                darkTokenColors,
                boldHighlight,
                italicHighlight
            )
            "dark" -> loadThemeWithColors(
                registry,
                "dark",
                DarkThemeColors.Default,
                boldHighlight,
                italicHighlight
            )
            "light" -> loadThemeWithColors(
                registry,
                "light",
                DarkThemeColors.PresetLight,
                boldHighlight,
                italicHighlight
            )
            "pure" -> loadThemeWithColors(
                registry,
                "pure",
                DarkThemeColors.PresetPure,
                boldHighlight,
                italicHighlight
            )
            else -> {
                val fallback = if (isDarkTheme) "dark_vs" else "pure"
                try {
                    registry.setTheme(fallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set fallback theme $fallback", e)
                }
            }
        }
        return TextMateColorScheme.create(registry)
    }

    /**
     * 加载指定主题，并按 TextMate scope 注入字重样式与 token 颜色。
     * 所有内置预设（dark / light / pure）和自定义主题都通过 scope 精确替换，
     * 保证同一套类别在各个主题下覆盖一致。
     */
    private fun loadThemeWithColors(
        registry: ThemeRegistry,
        themeKey: String,
        tokenColors: DarkThemeColors,
        boldHighlight: Boolean = false,
        italicHighlight: Boolean = false
    ) {
        val themeFile = themeFiles[themeKey] ?: "textmate/themes/dark_vs.json"
        val isDark = themeDark[themeKey] ?: true
        try {
            var json = appContext.assets.open(themeFile)
                .bufferedReader().use { it.readText() }
            if (boldHighlight || italicHighlight) {
                json = injectFontStyles(json, boldHighlight, italicHighlight)
            }
            json = applyScopeColors(json, tokenColors)
            val suffix = buildString {
                if (boldHighlight) append("b")
                if (italicHighlight) append("i")
            }
            val modelName = if (suffix.isEmpty()) "${themeKey}_styled" else "${themeKey}_${suffix}"
            val source = IThemeSource.fromInputStream(
                json.byteInputStream(StandardCharsets.UTF_8),
                "$modelName.json",
                null
            )
            val model = ThemeModel(source, modelName).apply {
                setDark(isDark)
                load()
            }
            registry.setTheme(model)
            Log.d(TAG, "Loaded themed $themeKey with token colors=$tokenColors")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load theme $themeKey", e)
            try {
                registry.setTheme(themeKey)
            } catch (_: Exception) {
                try {
                    registry.setTheme("dark_vs")
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 判断单个 scope 是否命中目标集合。
     */
    private fun matchesScope(scope: String, targets: Set<String>): Boolean {
        return targets.any { target ->
            scope == target || scope.endsWith(".$target") || target.startsWith("$scope.")
        }
    }

    /**
     * 在 TextMate 主题 JSON 中给相关 scope 注入 `fontStyle`。
     */
    private fun injectFontStyles(
        json: String,
        boldHighlight: Boolean,
        italicHighlight: Boolean
    ): String {
        return try {
            val obj = JSONObject(json)
            val settings = obj.optJSONArray("settings") ?: return json
            var injectedBold = 0
            var injectedItalic = 0
            for (i in 0 until settings.length()) {
                val rule = settings.optJSONObject(i) ?: continue
                val scopeObj = rule.opt("scope")
                val scopes = when (scopeObj) {
                    is String -> scopeObj.split(",").map { it.trim() }
                    is JSONArray -> (0 until scopeObj.length()).map { scopeObj.optString(it) }
                    else -> emptyList()
                }
                val wantBold = boldHighlight && scopes.any { matchesScope(it, BOLD_SCOPES) }
                val wantItalic = italicHighlight && scopes.any { matchesScope(it, ITALIC_SCOPES) }
                if (wantBold || wantItalic) {
                    val st = rule.optJSONObject("settings")
                        ?: JSONObject().also { rule.put("settings", it) }
                    val style = buildList {
                        if (wantBold) add("bold")
                        if (wantItalic) add("italic")
                    }.joinToString(" ")
                    st.put("fontStyle", style)
                    if (wantBold) injectedBold++
                    if (wantItalic) injectedItalic++
                }
            }
            Log.d(TAG, "Injected fontStyles: bold=$injectedBold, italic=$injectedItalic")
            obj.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject fontStyles", e)
            json
        }
    }

    /**
     * 按 scope 替换主题 JSON 中的 foreground 颜色，并同步设置全局普通文本颜色。
     */
    private fun applyScopeColors(json: String, darkTokenColors: DarkThemeColors): String {
        val obj = JSONObject(json)
        val settings = obj.optJSONArray("settings") ?: return json
        for (i in 0 until settings.length()) {
            val rule = settings.optJSONObject(i) ?: continue
            val scopes = parseScopes(rule.opt("scope"))
            val settingsObj = rule.optJSONObject("settings") ?: continue
            if (scopes.isEmpty()) {
                // 无 scope 的全局设置项：同步普通文本颜色
                if (settingsObj.has("foreground")) {
                    settingsObj.put("foreground", darkTokenColors.plainText.uppercase())
                }
                continue
            }
            val category = findCategoryForScopes(scopes) ?: continue
            val userColor = colorForCategory(darkTokenColors, category) ?: continue
            if (settingsObj.has("foreground")) {
                settingsObj.put("foreground", userColor)
            }
        }
        return obj.toString()
    }

    private fun parseScopes(scopeObj: Any?): List<String> {
        return when (scopeObj) {
            is String -> scopeObj.split(",").map { it.trim() }
            is org.json.JSONArray -> (0 until scopeObj.length()).map { scopeObj.optString(it) }
            else -> emptyList()
        }
    }

    private fun findCategoryForScopes(scopes: List<String>): String? {
        for ((category, targets) in DarkThemeColors.targetScopes) {
            for (scope in scopes) {
                for (target in targets) {
                    if (scopeMatches(scope, target)) {
                        return category
                    }
                }
            }
        }
        return null
    }

    private fun scopeMatches(scope: String, target: String): Boolean {
        return scope == target || scope.startsWith("$target.") || target.startsWith("$scope.")
    }

    private fun colorForCategory(colors: DarkThemeColors, category: String): String? {
        return when (category) {
            "ui" -> colors.ui
            "plainText" -> colors.plainText
            "section" -> colors.section
            "sectionSuffix" -> colors.sectionSuffix
            "sectionBracket" -> colors.sectionBracket
            "keyword" -> colors.keyword
            "control" -> colors.control
            "other" -> colors.other
            "value" -> colors.value
            "property" -> colors.property
            "comment" -> colors.comment
            "number" -> colors.number
            "string" -> colors.string
            "quote" -> colors.quote
            "boolean" -> colors.boolean
            "constant" -> colors.constant
            "team" -> colors.team
            "movement" -> colors.movement
            "path" -> colors.path
            "hexColor" -> colors.hexColor
            "logical" -> colors.logical
            "operator" -> colors.operator
            "comma" -> colors.comma
            "memory" -> colors.memory
            "reference" -> colors.reference
            "function" -> colors.function
            "parameter" -> colors.parameter
            "shortcutVariable" -> colors.shortcutVariable
            "declaration" -> colors.declaration
            "type" -> colors.type
            "modifier" -> colors.modifier
            "propertyValue" -> colors.propertyValue
            "bracket" -> colors.bracket
            "bracketRound" -> colors.bracketRound
            "bracketCurly" -> colors.bracketCurly
            "variable" -> colors.variable
            else -> null
        }?.uppercase()
    }
}
