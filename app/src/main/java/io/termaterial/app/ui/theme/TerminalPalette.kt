package io.termaterial.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Fixed color schemes used when dynamic color (Android 12+ wallpaper-based Material You) is
 * unavailable or disabled by the user, evoking classic terminal color themes. Selectable in
 * Settings (Step 4); [Green] is the default.
 */
enum class TerminalPalette(private val seed: Color) {
    Green(Color(0xFF00C853)),
    Amber(Color(0xFFFFAB00));

    fun lightScheme(): ColorScheme = lightColorScheme(
        primary = seed,
        onPrimary = Color.Black,
        secondary = seed.copy(alpha = 0.7f),
        tertiary = seed,
    )

    fun darkScheme(): ColorScheme = darkColorScheme(
        primary = seed,
        onPrimary = Color.Black,
        secondary = seed.copy(alpha = 0.7f),
        tertiary = seed,
        background = Color(0xFF0B0F0D),
        surface = Color(0xFF0B0F0D),
    )
}
