package com.rwmodstudio.core

import androidx.compose.ui.graphics.Color
import com.rwmodstudio.ui.theme.*

object ThemeManager {

    val bgColor: Color
        get() = parseColor(SettingsManager.bgColor)

    fun getAdjustedRustedBackground(): Color = bgColor

    fun getAdjustedRustedSurface(): Color {
        val bg = bgColor
        return Color(
            bg.red * 0.9f + 0.1f,
            bg.green * 0.9f + 0.1f,
            bg.blue * 0.9f + 0.1f,
            bg.alpha
        )
    }

    private fun parseColor(hex: String): Color {
        return try {
            val h = hex.removePrefix("#")
            when (h.length) {
                6 -> Color(
                    h.substring(0, 2).toInt(16) / 255f,
                    h.substring(2, 4).toInt(16) / 255f,
                    h.substring(4, 6).toInt(16) / 255f
                )
                8 -> Color(
                    h.substring(2, 4).toInt(16) / 255f,
                    h.substring(4, 6).toInt(16) / 255f,
                    h.substring(6, 8).toInt(16) / 255f,
                    h.substring(0, 2).toInt(16) / 255f
                )
                else -> RustedBackground
            }
        } catch (_: Exception) {
            RustedBackground
        }
    }
}
