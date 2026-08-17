package com.rwmodstudio.ui.theme

import androidx.compose.ui.graphics.Color
import com.rwmodstudio.core.ThemeState

val RustedPrimary = Color(0xFF569CD6)
val RustedSecondary = Color(0xFF4EC9B0)
val RustedAccent = Color(0xFFDCB468)
val RustedError = Color(0xFFF44747)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val DarkBg = Color(0xFF1E1E1E)
val DarkSurface = Color(0xFF2D2D2D)
val DarkOnBg = Color(0xFFD4D4D4)
val DarkCard = Color(0xFF252526)
val DarkDivider = Color(0xFF3C3C3C)

val LightBg = Color(0xFFF0F0F0)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBg = Color(0xFF1E1E1E)
val LightCard = Color(0xFFE8E8E8)
val LightDivider = Color(0xFFD0D0D0)

val RustedBackground get() = if (ThemeState.isDark) DarkBg else LightBg
val RustedSurface get() = if (ThemeState.isDark) DarkSurface else LightSurface
val RustedOnBackground get() = if (ThemeState.isDark) DarkOnBg else LightOnBg
