package com.rwmodstudio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.rwmodstudio.core.ThemeState

private val DarkColorScheme = darkColorScheme(
    primary = RustedPrimary,
    onPrimary = Color.White,
    secondary = RustedSecondary,
    tertiary = RustedAccent,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    error = RustedError,
    onBackground = DarkOnBg,
    onSurface = DarkOnBg,
    outline = DarkDivider
)

private val LightColorScheme = lightColorScheme(
    primary = RustedPrimary,
    onPrimary = Color.White,
    secondary = RustedSecondary,
    tertiary = RustedAccent,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightCard,
    error = RustedError,
    onBackground = LightOnBg,
    onSurface = LightOnBg,
    outline = LightDivider
)

@Composable
fun RustedWarfareModStudioTheme(
    content: @Composable () -> Unit
) {
    val isDark = ThemeState.isDark
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
