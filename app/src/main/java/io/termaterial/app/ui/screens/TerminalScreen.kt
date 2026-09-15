package io.termaterial.app.ui.screens

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import io.termaterial.app.R
import io.termaterial.app.settings.AppSettings
import io.termaterial.app.terminal.ExtraKeysState
import io.termaterial.app.terminal.TerminalTab

/**
 * Hosts a single, shared [TerminalView] (from the terminal-view module) inside Compose via
 * [AndroidView], switching which [TerminalTab]'s session/client it is attached to as the active
 * tab changes, plus the tab strip and extra-keys row (Step 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    tabs: List<TerminalTab>,
    activeTabId: String,
    settings: AppSettings,
    extraKeysState: ExtraKeysState,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTab = tabs.first { it.id == activeTabId }
    val activeTitle by activeTab.title
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = activeTitle?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.app_name)) },
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
                    activeTabId = activeTabId,
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
                TerminalView(context, null).apply {
                    // setTextSize()/setTypeface() must be called before attachSession(): they are
                    // what create the TerminalRenderer that attachSession()'s updateSize() call
                    // depends on.
                    setTypeface(settings.monospaceFont.toTypeface())
                    setTextSize(spToPx(context, settings.fontSizeSp))
                    setTerminalViewClient(activeTab.client)
                    tabs.forEach { it.client.view = this }
                    attachSession(activeTab.session)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    terminalView = this
                }
            },
            update = { view ->
                view.setTypeface(settings.monospaceFont.toTypeface())
                view.setTextSize(spToPx(view.context, settings.fontSizeSp))
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

private fun spToPx(context: Context, sp: Int): Int =
    (sp * context.resources.displayMetrics.scaledDensity).toInt()
