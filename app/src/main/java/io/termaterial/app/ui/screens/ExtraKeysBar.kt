package io.termaterial.app.ui.screens

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termux.view.TerminalView
import io.termaterial.app.terminal.ExtraKeysState
import io.termaterial.app.terminal.ModifierState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Special-keys rows shown above the virtual keyboard, laid out like Termux's default extra keys:
 *
 *     ESC  /  -  HOME  ↑  END  PGUP
 *     TAB CTRL ALT  ←  ↓   →  PGDN
 *
 * CTRL/ALT apply to the next key on tap and stay on after a long press (see [ExtraKeysState]).
 * Every other key is sent straight to the focused [TerminalView], which already knows how to turn
 * a key code into the right escape sequence for the emulator's current mode (e.g. application vs.
 * normal cursor keys); arrows and page keys repeat while held.
 */
@Composable
fun ExtraKeysBar(
    extraKeysState: ExtraKeysState,
    view: TerminalView?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(KEY_HEIGHT)) {
            KeyCodeKey("ESC", view, KeyEvent.KEYCODE_ESCAPE)
            CharKey("/", view)
            CharKey("-", view)
            KeyCodeKey("HOME", view, KeyEvent.KEYCODE_MOVE_HOME)
            KeyCodeKey("↑", view, KeyEvent.KEYCODE_DPAD_UP, repeatable = true)
            KeyCodeKey("END", view, KeyEvent.KEYCODE_MOVE_END)
            KeyCodeKey("PGUP", view, KeyEvent.KEYCODE_PAGE_UP, repeatable = true)
        }
        Row(Modifier.fillMaxWidth().height(KEY_HEIGHT)) {
            KeyCodeKey("TAB", view, KeyEvent.KEYCODE_TAB)
            ModifierKey("CTRL", extraKeysState.control, extraKeysState::tapControl, extraKeysState::lockControl)
            ModifierKey("ALT", extraKeysState.alt, extraKeysState::tapAlt, extraKeysState::lockAlt)
            KeyCodeKey("←", view, KeyEvent.KEYCODE_DPAD_LEFT, repeatable = true)
            KeyCodeKey("↓", view, KeyEvent.KEYCODE_DPAD_DOWN, repeatable = true)
            KeyCodeKey("→", view, KeyEvent.KEYCODE_DPAD_RIGHT, repeatable = true)
            KeyCodeKey("PGDN", view, KeyEvent.KEYCODE_PAGE_DOWN, repeatable = true)
        }
    }
}

private val KEY_HEIGHT = 40.dp
private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

@Composable
private fun RowScope.KeyCodeKey(label: String, view: TerminalView?, keyCode: Int, repeatable: Boolean = false) {
    ExtraKey(label, repeatable = repeatable, onPress = { view?.dispatchSyntheticKey(keyCode) })
}

@Composable
private fun RowScope.CharKey(char: String, view: TerminalView?) {
    // Through inputCodePoint() rather than as text, so a pending CTRL/ALT applies to it too.
    ExtraKey(char, onPress = {
        view?.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, char.codePointAt(0), false, false)
    })
}

@Composable
private fun RowScope.ModifierKey(label: String, state: ModifierState, onTap: () -> Unit, onLongPress: () -> Unit) {
    val (background, content) = when (state) {
        ModifierState.Off -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        ModifierState.Once -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ModifierState.Locked -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    KeyBox(
        label = label,
        interactionSource = interactionSource,
        background = background,
        content = content,
        bold = state != ModifierState.Off,
        gestures = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactionSource.emit(press)
                    val released = tryAwaitRelease()
                    interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onTap = { currentOnTap() },
                onLongPress = { currentOnLongPress() },
            )
        },
    )
}

@Composable
private fun RowScope.ExtraKey(label: String, repeatable: Boolean = false, onPress: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val currentOnPress by rememberUpdatedState(onPress)
    KeyBox(
        label = label,
        interactionSource = interactionSource,
        background = Color.Transparent,
        content = MaterialTheme.colorScheme.onSurface,
        bold = false,
        gestures = Modifier.pointerInput(repeatable) {
            detectTapGestures(onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interactionSource.emit(press)
                // On press, like a real keyboard key, rather than on release.
                currentOnPress()
                val repeater = if (repeatable) {
                    scope.launch {
                        delay(REPEAT_INITIAL_DELAY_MS)
                        while (isActive) {
                            currentOnPress()
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                } else {
                    null
                }
                val released = tryAwaitRelease()
                repeater?.cancel()
                interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            })
        },
    )
}

@Composable
private fun RowScope.KeyBox(
    label: String,
    interactionSource: MutableInteractionSource,
    background: Color,
    content: Color,
    bold: Boolean,
    gestures: Modifier,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .indication(interactionSource, LocalIndication.current)
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1,
        )
    }
}

/** Synthesizes a key down+up pair and routes it through the same path a real hardware key would take. */
private fun TerminalView.dispatchSyntheticKey(keyCode: Int) {
    val now = SystemClock.uptimeMillis()
    val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
    onKeyDown(keyCode, downEvent)
    val upEvent = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
    onKeyUp(keyCode, upEvent)
}
