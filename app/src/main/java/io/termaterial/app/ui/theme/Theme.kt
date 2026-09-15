package io.termaterial.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App-wide Material 3 theme.
 *
 * - Android 12+ (API 31+): dynamic color derived from the device wallpaper, when [useDynamicColor]
 *   is true (the Settings screen, Step 4, will expose this as a toggle - see the task spec's
 *   "activer/désactiver dynamic color").
 * - Below API 31, or when dynamic color is disabled: a fixed classic-terminal [TerminalPalette]
 *   (green/amber, configurable in Settings).
 *
 * Light/dark follows the system setting automatically via [isSystemInDarkTheme].
 */
@Composable
fun TermaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    terminalPalette: TerminalPalette = TerminalPalette.Green,
    content: @Composable () -> Unit,
) {
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colorScheme = when {
        useDynamicColor && dynamicColorAvailable ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> terminalPalette.darkScheme()
        else -> terminalPalette.lightScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TermaterialTypography,
        content = content,
    )
}
