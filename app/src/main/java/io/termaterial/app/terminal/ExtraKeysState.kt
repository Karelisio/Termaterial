package io.termaterial.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sticky Ctrl/Alt modifier state for the extra-keys row above the keyboard. Shared by every open
 * [TerminalTab]'s [AppTerminalClient] (only one is ever the active/visible one), since the row
 * itself is a single piece of UI, not per-session state.
 *
 * These are toggles (tap to arm, tap again to release) rather than "auto-release after the next
 * keystroke": TerminalView's [com.termux.view.TerminalViewClient.readControlKey] /
 * `readAltKey` can be queried more than once while a single key press is being processed, so an
 * auto-release-after-one-read implementation risks releasing the modifier before it has actually
 * been applied. A visible pressed/armed state in the UI makes the toggle's current state obvious.
 */
class ExtraKeysState {
    var controlActive by mutableStateOf(false)
        private set

    var altActive by mutableStateOf(false)
        private set

    fun toggleControl() {
        controlActive = !controlActive
    }

    fun toggleAlt() {
        altActive = !altActive
    }
}
