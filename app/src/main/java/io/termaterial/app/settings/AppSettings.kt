package io.termaterial.app.settings

import io.termaterial.app.ui.theme.TerminalPalette

data class AppSettings(
    val monospaceFont: MonospaceFont = MonospaceFont.Default,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val terminalPalette: TerminalPalette = TerminalPalette.Green,
    val useDynamicColor: Boolean = true,
) {
    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 24
    }
}
