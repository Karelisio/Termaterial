package io.termaterial.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.termux.terminal.TerminalSession
import io.termaterial.app.terminal.AppTerminalClient
import io.termaterial.app.ui.screens.BootstrapProgressScreen
import io.termaterial.app.ui.screens.SettingsBottomSheet
import io.termaterial.app.ui.screens.TerminalScreen
import io.termaterial.app.ui.theme.TermaterialTheme
import io.termaterial.shell.BootstrapInstaller
import io.termaterial.shell.BootstrapProgress
import io.termaterial.shell.BootstrapShellSessionFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TermaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TermaterialApp()
                }
            }
        }
    }
}

@Composable
private fun TermaterialApp() {
    val context = LocalContext.current
    val installer = remember { BootstrapInstaller(context) }

    // Bumped to force a fresh install() collection (with forceReinstall=true) after a failure.
    var installAttempt by remember { mutableIntStateOf(0) }
    val progress by remember(installAttempt) {
        installer.install(forceReinstall = installAttempt > 0)
    }.collectAsState(initial = BootstrapProgress.CheckingExistingInstallation)

    var terminalTitle by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }

    val client = remember {
        AppTerminalClient(
            context = context,
            onTitleChanged = { terminalTitle = it },
            onSessionFinished = {
                // A restarted-session / "process exited" screen can be added once Step 4 (tabs,
                // multi-session UI) defines what should happen next (new tab, restart, close).
            },
        )
    }

    val isInstalled = progress is BootstrapProgress.Installed
    LaunchedEffect(isInstalled) {
        if (isInstalled && session == null) {
            session = BootstrapShellSessionFactory().createSession(context, client)
        }
    }

    val currentSession = session
    if (currentSession != null) {
        TerminalScreen(
            client = client,
            session = currentSession,
            title = terminalTitle,
            onOpenSettings = { showSettings = true },
        )
        if (showSettings) {
            SettingsBottomSheet(onDismiss = { showSettings = false })
        }
    } else {
        BootstrapProgressScreen(
            progress = progress,
            onRetry = { installAttempt++ },
        )
    }
}
