package io.termaterial.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Fixed color themes used both for the app's own Material chrome, when dynamic color (Android
 * 12+ wallpaper-based Material You) is unavailable or disabled, and for the terminal's own
 * text/background colors (applied via [io.termaterial.app.terminal.TerminalColorSchemeApplier]).
 * Selectable in Settings (Step 4); [Green] is the default.
 */
enum class TerminalPalette(
    val id: String,
    val label: String,
    private val seed: Color,
    /** Terminal background, as "#RRGGBB" (the format [com.termux.terminal.TerminalColors] parses). */
    val terminalBackgroundHex: String,
    /** Terminal foreground (default text color), as "#RRGGBB". */
    val terminalForegroundHex: String,
) {
    Green("green", "Vert classique", Color(0xFF00C853), "#000000", "#33FF66"),
    Amber("amber", "Ambre classique", Color(0xFFFFAB00), "#000000", "#FFB300");

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

    companion object {
        fun fromId(id: String?): TerminalPalette = entries.firstOrNull { it.id == id } ?: Green
    }
}
