package io.termaterial.app.ui.screens

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.termux.view.TerminalView
import io.termaterial.app.terminal.ExtraKeysState

/**
 * Special-keys row shown above the virtual keyboard (Ctrl, Alt, Tab, arrows, Esc), as requested
 * for Step 4 ("Gestion du clavier"). Ctrl/Alt are sticky toggles (see [ExtraKeysState] for why);
 * the rest send one key press straight to the focused [TerminalView], which already knows how to
 * turn a D-pad/Tab/Escape key code into the right escape sequence for the emulator's current mode
 * (e.g. application vs. normal cursor keys) - see [dispatchSyntheticKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraKeysBar(
    extraKeysState: ExtraKeysState,
    view: TerminalView?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = extraKeysState.controlActive,
            onClick = extraKeysState::toggleControl,
            label = { Text("CTRL") },
        )
        FilterChip(
            selected = extraKeysState.altActive,
            onClick = extraKeysState::toggleAlt,
            label = { Text("ALT") },
        )
        ExtraKeyButton("ESC", view, KeyEvent.KEYCODE_ESCAPE)
        ExtraKeyButton("TAB", view, KeyEvent.KEYCODE_TAB)
        ExtraKeyButton("←", view, KeyEvent.KEYCODE_DPAD_LEFT)
        ExtraKeyButton("↓", view, KeyEvent.KEYCODE_DPAD_DOWN)
        ExtraKeyButton("↑", view, KeyEvent.KEYCODE_DPAD_UP)
        ExtraKeyButton("→", view, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraKeyButton(label: String, view: TerminalView?, keyCode: Int) {
    AssistChip(
        onClick = { view?.dispatchSyntheticKey(keyCode) },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
    )
}

/** Synthesizes a key down+up pair and routes it through the same path a real hardware key would take. */
private fun TerminalView.dispatchSyntheticKey(keyCode: Int) {
    val now = SystemClock.uptimeMillis()
    val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
    onKeyDown(keyCode, downEvent)
    val upEvent = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
    onKeyUp(keyCode, upEvent)
}
