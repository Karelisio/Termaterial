package io.termaterial.app.terminal

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.termaterial.app.ui.theme.TerminalPalette
import java.util.Properties

/**
 * Applies a [TerminalPalette]'s background/foreground to the terminal's actual rendered colors
 * (as opposed to [io.termaterial.app.ui.theme.TermaterialTheme], which only themes the app's
 * Material chrome).
 *
 * [TerminalColors.COLOR_SCHEME] (from the terminal-emulator module) is a single process-wide
 * static default - not per-session - so switching the palette updates it for every open tab at
 * once, then resets each live session's current colors from it and asks the view to redraw.
 */
object TerminalColorSchemeApplier {

    fun apply(palette: TerminalPalette, sessions: List<TerminalSession>, view: TerminalView?) {
        val props = Properties().apply {
            setProperty("background", palette.terminalBackgroundHex)
            setProperty("foreground", palette.terminalForegroundHex)
        }
        TerminalColors.COLOR_SCHEME.updateWith(props)

        for (session in sessions) {
            session.emulator?.mColors?.reset()
        }
        view?.onScreenUpdated()
    }
}
