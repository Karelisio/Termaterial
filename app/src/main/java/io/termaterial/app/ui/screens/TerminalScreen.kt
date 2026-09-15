package io.termaterial.app.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.termaterial.app.R
import io.termaterial.app.terminal.AppTerminalClient

/** Hosts the classic [TerminalView] (from the terminal-view module) inside Compose via [AndroidView]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    client: AppTerminalClient,
    session: TerminalSession,
    title: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = title?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.app_name)) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(id = R.string.settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context ->
                TerminalView(context, null).apply {
                    // setTextSize() must be called before attachSession(): it is what creates the
                    // TerminalRenderer that attachSession()'s updateSize() call depends on (a
                    // default-font-size text size is a placeholder here - Step 4's Settings screen
                    // will make it, and the monospace font choice, user-configurable).
                    setTextSize((DEFAULT_FONT_SIZE_SP * context.resources.displayMetrics.scaledDensity).toInt())
                    setTerminalViewClient(client)
                    client.view = this
                    attachSession(session)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }
            },
        )
    }
}

private const val DEFAULT_FONT_SIZE_SP = 14
