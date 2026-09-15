package io.termaterial.app.settings

import android.content.Context
import android.content.SharedPreferences
import io.termaterial.app.ui.theme.TerminalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple [SharedPreferences]-backed store for [AppSettings]. Plain `SharedPreferences` (rather
 * than DataStore) is enough for this handful of scalar values and keeps the app module's
 * dependency surface smaller.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setMonospaceFont(font: MonospaceFont) {
        prefs.edit().putString(KEY_FONT, font.id).apply()
        _settings.value = _settings.value.copy(monospaceFont = font)
    }

    fun setFontSizeSp(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(AppSettings.MIN_FONT_SIZE_SP, AppSettings.MAX_FONT_SIZE_SP)
        prefs.edit().putInt(KEY_FONT_SIZE, clamped).apply()
        _settings.value = _settings.value.copy(fontSizeSp = clamped)
    }

    fun setTerminalPalette(palette: TerminalPalette) {
        prefs.edit().putString(KEY_PALETTE, palette.id).apply()
        _settings.value = _settings.value.copy(terminalPalette = palette)
    }

    fun setUseDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settings.value = _settings.value.copy(useDynamicColor = enabled)
    }

    private fun readSettings(): AppSettings = AppSettings(
        monospaceFont = MonospaceFont.fromId(prefs.getString(KEY_FONT, null)),
        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, AppSettings.DEFAULT_FONT_SIZE_SP),
        terminalPalette = TerminalPalette.fromId(prefs.getString(KEY_PALETTE, null)),
        useDynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
    )

    companion object {
        private const val PREFS_NAME = "termaterial_settings"
        private const val KEY_FONT = "monospace_font"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_PALETTE = "terminal_palette"
        private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"
    }
}
