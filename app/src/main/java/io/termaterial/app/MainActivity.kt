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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.termaterial.app.settings.SettingsRepository
import io.termaterial.app.terminal.AppTerminalClient
import io.termaterial.app.terminal.ExtraKeysState
import io.termaterial.app.terminal.TerminalColorSchemeApplier
import io.termaterial.app.terminal.TerminalTab
import io.termaterial.app.ui.screens.BootstrapProgressScreen
import io.termaterial.app.ui.screens.SettingsBottomSheet
import io.termaterial.app.ui.screens.TerminalScreen
import io.termaterial.app.ui.theme.TermaterialTheme
import io.termaterial.shell.BootstrapInstaller
import io.termaterial.shell.BootstrapProgress
import io.termaterial.shell.BootstrapShellSessionFactory
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = SettingsRepository(applicationContext)
        setContent {
            val settings by settingsRepository.settings.collectAsState()
            TermaterialTheme(
                useDynamicColor = settings.useDynamicColor,
                terminalPalette = settings.terminalPalette,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TermaterialApp(settingsRepository)
                }
            }
        }
    }
}

@Composable
private fun TermaterialApp(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val installer = remember { BootstrapInstaller(context) }
    val settings by settingsRepository.settings.collectAsState()

    // Bumped to force a fresh install() collection (with forceReinstall=true) after a failure.
    var installAttempt by remember { mutableIntStateOf(0) }
    val progress by remember(installAttempt) {
        installer.install(forceReinstall = installAttempt > 0)
    }.collectAsState(initial = BootstrapProgress.CheckingExistingInstallation)

    val extraKeysState = remember { ExtraKeysState() }
    var showSettings by remember { mutableStateOf(false) }
    val tabs = remember { mutableStateListOf<TerminalTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    fun openNewTab() {
        val id = UUID.randomUUID().toString()
        val title = mutableStateOf<String?>(null)
        val client = AppTerminalClient(
            context = context,
            extraKeysState = extraKeysState,
            onTitleChanged = { title.value = it },
            onSessionFinished = {
                val wasActive = activeTabId == id
                tabs.removeAll { it.id == id }
                if (wasActive) {
                    activeTabId = tabs.lastOrNull()?.id
                }
            },
        )
        val session = BootstrapShellSessionFactory().createSession(context, client)
        tabs.add(TerminalTab(id, session, client, title))
        activeTabId = id
    }

    val isInstalled = progress is BootstrapProgress.Installed
    // Also re-runs whenever tabs.size changes back to 0 (every tab closed, or a shell exited),
    // opening a fresh one rather than leaving the user stranded on no screen at all.
    LaunchedEffect(isInstalled, tabs.size) {
        if (isInstalled && tabs.isEmpty()) {
            // Prime the (process-wide) terminal color scheme with the persisted palette before
            // the first session's emulator is created, so it renders with the right colors from
            // the start instead of flashing the xterm defaults. A no-op on later re-opens.
            TerminalColorSchemeApplier.apply(settings.terminalPalette, sessions = emptyList(), view = null)
            openNewTab()
        }
    }

    val currentActiveId = activeTabId
    if (currentActiveId != null && tabs.isNotEmpty()) {
        TerminalScreen(
            tabs = tabs,
            activeTabId = currentActiveId,
            settings = settings,
            extraKeysState = extraKeysState,
            onSelectTab = { activeTabId = it },
            onNewTab = { openNewTab() },
            onCloseTab = { id ->
                tabs.find { it.id == id }?.session?.finishIfRunning()
                val wasActive = activeTabId == id
                tabs.removeAll { it.id == id }
                if (wasActive) {
                    activeTabId = tabs.lastOrNull()?.id
                }
            },
            onOpenSettings = { showSettings = true },
        )
        if (showSettings) {
            SettingsBottomSheet(
                settings = settings,
                onMonospaceFontChange = settingsRepository::setMonospaceFont,
                onFontSizeChange = settingsRepository::setFontSizeSp,
                onTerminalPaletteChange = { palette ->
                    settingsRepository.setTerminalPalette(palette)
                    TerminalColorSchemeApplier.apply(
                        palette = palette,
                        sessions = tabs.map { it.session },
                        view = tabs.firstOrNull()?.client?.view,
                    )
                },
                onUseDynamicColorChange = settingsRepository::setUseDynamicColor,
                onDismiss = { showSettings = false },
            )
        }
    } else {
        BootstrapProgressScreen(
            progress = progress,
            onRetry = { installAttempt++ },
        )
    }
}
