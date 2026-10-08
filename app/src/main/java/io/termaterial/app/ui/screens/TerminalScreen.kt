package io.termaterial.app.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import io.termaterial.app.R
import io.termaterial.app.StartupTrace
import io.termaterial.app.settings.AppSettings
import io.termaterial.app.terminal.ExtraKeysState
import io.termaterial.app.terminal.TerminalTab
import kotlin.math.roundToInt

/**
 * Hosts a single, shared [TerminalView] (from the terminal-view module) inside Compose via
 * [AndroidView], switching which [TerminalTab]'s session/client it is attached to as the active
 * tab changes, plus the tab strip and extra-keys row (Step 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    tabs: List<TerminalTab>,
    activeTab: TerminalTab,
    settings: AppSettings,
    extraKeysState: ExtraKeysState,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    // What was last applied to the view, so recompositions that change neither (a new tab, a
    // title update...) do not rebuild its renderer.
    val appliedRenderer = remember { arrayOfNulls<Any>(2) }

    // The tabs (and their clients) outlive this screen: never leave them pointing at a view that
    // is gone - nor keep it, and its Activity, from being garbage collected.
    DisposableEffect(Unit) {
        onDispose {
            for (tab in tabs) tab.client.view = null
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = tabLabel(activeTab)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(id = R.string.settings),
                            )
                        }
                    },
                )
                TerminalTabRow(
                    tabs = tabs,
                    activeTabId = activeTab.id,
                    onSelectTab = onSelectTab,
                    onNewTab = onNewTab,
                    onCloseTab = onCloseTab,
                )
            }
        },
        bottomBar = {
            ExtraKeysBar(extraKeysState = extraKeysState, view = terminalView)
        },
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context ->
                StartupTrace.log(context, "TerminalScreen: creating TerminalView")
                TerminalView(context, null).apply {
                    // setTextSize() must run first and before attachSession(): it is what
                    // creates the TerminalRenderer (tolerating mRenderer == null by falling back
                    // to Typeface.MONOSPACE), which attachSession()'s updateSize() call and
                    // setTypeface() both depend on - setTypeface() reads mRenderer.mTextSize and
                    // NPEs if called first (confirmed by a real-device crash: this order used to
                    // be reversed here).
                    appliedRenderer.fill(null)
                    applyRendererSettings(this, settings, appliedRenderer)
                    StartupTrace.log(context, "TerminalScreen: renderer configured")
                    setTerminalViewClient(activeTab.client)
                    tabs.forEach { it.client.view = this }
                    // attachSession() itself is cheap, but the layout pass it leads to is what
                    // spawns the pty through JNI - the last thing that happens before the
                    // terminal is live, and the step a native crash would die in.
                    attachSession(activeTab.session)
                    StartupTrace.log(context, "TerminalScreen: attachSession returned")
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    terminalView = this
                    // Bring up the IME as soon as the terminal is ready, rather than making the
                    // user tap the screen first - onCheckIsTextEditor() lets an IME attach, but
                    // nothing shows it automatically (a tap does too, via AppTerminalClient).
                    post {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            },
            update = { view ->
                applyRendererSettings(view, settings, appliedRenderer)
                if (view.currentSession !== activeTab.session) {
                    view.setTerminalViewClient(activeTab.client)
                    view.attachSession(activeTab.session)
                    view.requestFocus()
                }
                tabs.forEach { it.client.view = view }
            },
        )
    }
}

/** The tab's own title if the shell set one, "Session <n>" otherwise. */
@Composable
fun tabLabel(tab: TerminalTab): String {
    val title by tab.title
    return title?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.tab_default_title, tab.number)
}

private fun applyRendererSettings(view: TerminalView, settings: AppSettings, applied: Array<Any?>) {
    val textSizePx = spToPx(view.context, settings.fontSizeSp)
    val typeface: Typeface = settings.monospaceFont.toTypeface()
    if (applied[0] == textSizePx && applied[1] == settings.monospaceFont) return
    // Same order constraint as in the factory above: setTextSize() first.
    view.setTextSize(textSizePx)
    view.setTypeface(typeface)
    applied[0] = textSizePx
    applied[1] = settings.monospaceFont
}

private fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).roundToInt()
