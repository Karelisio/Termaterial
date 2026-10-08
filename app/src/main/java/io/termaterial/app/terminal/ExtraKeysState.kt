package io.termaterial.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State of a modifier key of the extra-keys row. */
enum class ModifierState {
    Off,

    /** Applies to the next key only (tap), like Termux's extra keys. */
    Once,

    /** Applies until released again (long press). */
    Locked,
}

/**
 * Ctrl/Alt modifier state for the extra-keys row above the keyboard. Shared by every open
 * [TerminalTab]'s [AppTerminalClient] (only one is ever the active/visible one), since the row
 * itself is a single piece of UI, not per-session state.
 *
 * A tap arms a modifier for the next key only; [consumeControl]/[consumeAlt] are what
 * [com.termux.view.TerminalView] calls (through `readControlKey()`/`readAltKey()`) for each key
 * it processes, and they use a one-shot modifier up. TerminalView reads each modifier at most
 * once per key: its `onKeyDown()` hands the value it read to `inputCodePoint()`, which then
 * short-circuits its own read - the same contract Termux's extra keys rely on.
 */
class ExtraKeysState {
    var control by mutableStateOf(ModifierState.Off)
        private set

    var alt by mutableStateOf(ModifierState.Off)
        private set

    fun tapControl() {
        control = tapped(control)
    }

    fun lockControl() {
        control = locked(control)
    }

    fun tapAlt() {
        alt = tapped(alt)
    }

    fun lockAlt() {
        alt = locked(alt)
    }

    fun consumeControl(): Boolean {
        val active = control != ModifierState.Off
        if (control == ModifierState.Once) control = ModifierState.Off
        return active
    }

    fun consumeAlt(): Boolean {
        val active = alt != ModifierState.Off
        if (alt == ModifierState.Once) alt = ModifierState.Off
        return active
    }

    private fun tapped(state: ModifierState) =
        if (state == ModifierState.Off) ModifierState.Once else ModifierState.Off

    private fun locked(state: ModifierState) =
        if (state == ModifierState.Locked) ModifierState.Off else ModifierState.Locked
}
