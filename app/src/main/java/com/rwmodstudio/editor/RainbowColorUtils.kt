package com.rwmodstudio.editor

import android.graphics.Color
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import org.eclipse.tm4e.core.internal.grammar.ScopeStack
import kotlin.math.abs

/**
 * 彩虹括号颜色工具：从当前 TextMate 主题读取默认圆括号颜色，并据此生成内层渐变颜色。
 */
object RainbowColorUtils {

    private const val ROUND_BRACKET_SCOPE = "punctuation.bracket.round.ini"

    /**
     * 从当前主题中读取 `punctuation.bracket.round.ini` 的 foreground 色。
     * 读取失败返回 null。
     */
    fun resolveThemeBracketColor(themeRegistry: ThemeRegistry): Int? {
        return resolveThemeBracketColor(themeRegistry.currentThemeModel)
    }

    fun resolveThemeBracketColor(themeModel: ThemeModel?): Int? {
        val theme = themeModel?.theme ?: return null
        return try {
            val scope = ScopeStack.from(ROUND_BRACKET_SCOPE)
            val attrs = theme.match(scope) ?: return null
            val colorStr = theme.getColor(attrs.foregroundId)
            Color.parseColor(colorStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 默认括号基础色：亮色主题用金色，深色主题用淡紫。
     * 与 [SoraEditorInitializer.applyRainbowBracketColors] 的兜底逻辑保持一致。
     */
    fun defaultBracketColor(themeName: String): Int {
        return if (themeName in setOf("light", "pure")) {
            0xFFdeae12.toInt()
        } else {
            0xFFAD99B7.toInt()
        }
    }

    /**
     * 解析预览用的括号基础色：优先从当前 TextMate 主题读取 `punctuation.bracket.round.ini`
     * 的 foreground 色；读不到时按主题名回落到默认色。
     *
     * 与编辑器实际渲染（[SoraEditorInitializer.applyRainbowBracketColors]）使用同一套逻辑，
     * 保证设置面板预览与编辑器实际显示一致。
     */
    fun resolvePreviewBaseColor(themeName: String): Int {
        return try {
            resolveThemeBracketColor(io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance())
                ?: defaultBracketColor(themeName)
        } catch (_: Exception) {
            defaultBracketColor(themeName)
        }
    }

    /**
     * 以 baseColor 为最外层（depth1）颜色，生成 4 层彩虹色板。
     *
     * 所有参数均可由用户自定义：
     * - [hueStepDegrees]：相邻两层在色环上的间隔角度（0°~180°）。
     * - [hueDirection]：色相旋转方向，0=朝背景反色，1=固定顺时针，2=固定逆时针。
     * - [saturationBoost]：每层饱和度增量（-0.3~+0.3）。
     * - [lightnessShift]：每层亮度增量（-0.3~+0.3）。
     * - [autoLightnessDirection]：开启时根据背景深浅自动翻转亮度偏移方向。
     * - [visibilityGuard]：开启时强制保证括号不会融进背景。
     */
    fun generatePalette(
        baseColor: Int,
        backgroundColor: Int,
        hueStepDegrees: Float = 72f,
        hueDirection: Int = 0,
        saturationBoost: Float = 0.08f,
        lightnessShift: Float = 0.06f,
        autoLightnessDirection: Boolean = true,
        visibilityGuard: Boolean = true
    ): IntArray {
        val bgLuminance = android.graphics.Color.luminance(backgroundColor)
        val isDarkBg = bgLuminance < 0.5f
        val baseHsl = rgbToHsl(baseColor)
        val bgHsl = rgbToHsl(backgroundColor)

        // 背景反色：色相旋转 180°，作为「朝背景反色」方向的基准
        val inverseHue = (bgHsl[0] + 0.5f) % 1f

        val hueStep = hueStepDegrees.coerceIn(0f, 180f) / 360f

        // 确定色相旋转方向
        val directionSign = when (hueDirection) {
            1 -> 1f   // 固定顺时针
            2 -> -1f  // 固定逆时针
            else -> { // 朝背景反色：取最短路径的符号
                var diff = inverseHue - baseHsl[0]
                while (diff > 0.5f) diff -= 1f
                while (diff < -0.5f) diff += 1f
                if (diff >= 0) 1f else -1f
            }
        }

        return IntArray(4) { i ->
            val hsl = baseHsl.copyOf()

            // 色相：按指定方向和步长旋转，允许绕色环回绕
            hsl[0] = (baseHsl[0] + directionSign * hueStep * i) % 1f
            if (hsl[0] < 0) hsl[0] += 1f

            // 亮度：应用用户偏移，可选根据背景自动取反方向
            val lightnessDirection = if (autoLightnessDirection) {
                if (isDarkBg) 1f else -1f
            } else {
                1f
            }
            val lightnessDelta = lightnessShift.coerceIn(-0.3f, 0.3f) * lightnessDirection * i
            hsl[2] = (hsl[2] + lightnessDelta).coerceIn(0.05f, 0.95f)

            // 饱和度：应用用户偏移
            val saturationDelta = saturationBoost.coerceIn(-0.3f, 0.3f) * i
            hsl[1] = (hsl[1] + saturationDelta).coerceIn(0f, 1f)

            // 可见性保护：防止颜色融进背景
            if (visibilityGuard) {
                hsl[2] = if (isDarkBg) hsl[2].coerceAtLeast(0.20f) else hsl[2].coerceAtMost(0.80f)
            }

            hslToRgb(hsl)
        }
    }

    /**
     * 按最短路径在色环上从 [from] 插值到 [to]，[fraction] 为 0~1。
     */
    private fun interpolateHue(from: Float, to: Float, fraction: Float): Float {
        var diff = to - from
        while (diff > 0.5f) diff -= 1f
        while (diff < -0.5f) diff += 1f
        var result = (from + diff * fraction) % 1f
        if (result < 0) result += 1f
        return result
    }

    private fun rgbToHsl(color: Int): FloatArray {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f

        val h: Float
        val s: Float
        if (max == min) {
            h = 0f
            s = 0f
        } else {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
                g -> ((b - r) / d + 2f) / 6f
                else -> ((r - g) / d + 4f) / 6f
            }
        }
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(hsl: FloatArray): Int {
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        val c = (1f - abs(2 * l - 1f)) * s
        val x = c * (1f - abs((h * 6f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 1f / 6f -> Triple(c, x, 0f)
            h < 2f / 6f -> Triple(x, c, 0f)
            h < 3f / 6f -> Triple(0f, c, x)
            h < 4f / 6f -> Triple(0f, x, c)
            h < 5f / 6f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.rgb(
            ((r + m) * 255).toInt().coerceIn(0, 255),
            ((g + m) * 255).toInt().coerceIn(0, 255),
            ((b + m) * 255).toInt().coerceIn(0, 255)
        )
    }
}
