package io.termaterial.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import io.termaterial.app.settings.SettingsRepository
import io.termaterial.app.terminal.TerminalColorSchemeApplier
import io.termaterial.app.terminal.TerminalSessionManager
import io.termaterial.app.ui.screens.BootstrapProgressScreen
import io.termaterial.app.ui.screens.SettingsBottomSheet
import io.termaterial.app.ui.screens.TerminalScreen
import io.termaterial.app.ui.screens.UpdateDialog
import io.termaterial.app.update.UpdateManager
import io.termaterial.app.ui.theme.TermaterialTheme
import io.termaterial.shell.BootstrapInstaller
import io.termaterial.shell.BootstrapProgress
import io.termaterial.shell.BootstrapShellSessionFactory

class MainActivity : ComponentActivity() {

    // POST_NOTIFICATIONS (API 33+) only controls whether the foreground-service notification is
    // *visible*; the service itself starts and keeps the session alive either way (see
    // TerminalSessionService and docs/step-5-permissions.md), so no result handling is needed
    // here beyond letting the system remember the user's choice - this is the "degrade
    // gracefully rather than crash" the task asks for.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var sessionManager: TerminalSessionManager

    // The notification's "Exit" action: leave the screen as well, as Termux does. A plain
    // listener rather than Compose state, so it also works while this Activity is stopped.
    private val exitListener: () -> Unit = { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.log(this, "MainActivity.onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as TermaterialApplication
        sessionManager = app.sessionManager
        sessionManager.addExitListener(exitListener)
        StartupTrace.log(this, "settings loaded, calling setContent")
        setContent {
            val settings by app.settingsRepository.settings.collectAsState()
            TermaterialTheme(
                useDynamicColor = settings.useDynamicColor,
                terminalPalette = settings.terminalPalette,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TermaterialApp(
                        settingsRepository = app.settingsRepository,
                        sessionManager = app.sessionManager,
                        updateManager = app.updateManager,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        sessionManager.removeExitListener(exitListener)
        super.onDestroy()
    }
}

/** What the next [BootstrapInstaller.install] collection should do; a new instance restarts it. */
private data class InstallRequest(val attempt: Int, val forceReinstall: Boolean)

@Composable
private fun TermaterialApp(
    settingsRepository: SettingsRepository,
    sessionManager: TerminalSessionManager,
    updateManager: UpdateManager,
) {
    val context = LocalContext.current
    val installer = remember { BootstrapInstaller(context.applicationContext) }
    val settings by settingsRepository.settings.collectAsState()

    var installRequest by remember { mutableStateOf(InstallRequest(attempt = 0, forceReinstall = false)) }
    // Keyed state rather than Flow.collectAsState(): that one keeps showing the previous flow's
    // last value (e.g. Installed) until the new flow emits, which would let the tab-opening effect
    // below start a shell in the very installation a reinstall is about to delete.
    val progressState = remember(installRequest) {
        mutableStateOf<BootstrapProgress>(BootstrapProgress.CheckingExistingInstallation)
    }
    LaunchedEffect(installRequest) {
        installer.install(forceReinstall = installRequest.forceReinstall).collect { progressState.value = it }
    }
    val progress = progressState.value

    var showSettings by remember { mutableStateOf(false) }
    val prootAvailable = remember { BootstrapShellSessionFactory.isProotAvailable(context) }

    val updateState by updateManager.state.collectAsState()
    LaunchedEffect(Unit) {
        if (settingsRepository.settings.value.autoCheckUpdates) updateManager.checkIfDue()
    }

    // Surfaced instead of letting an exception here crash the app outright (e.g. if the
    // bootstrap's bash cannot be executed on this device - see docs/step-2-shell-backend.md's
    // Android 10+ caveat): shown via BootstrapProgressScreen's existing Failed state below, so
    // the actual error is visible on-screen instead of only in a logcat the user may not have
    // access to. Also seeded from any crash CrashReporter recorded on a *previous* launch (for
    // crashes that happen inside an Android framework callback, e.g. TerminalView's layout pass,
    // which this composable cannot wrap in a try/catch of its own).
    var sessionError by remember {
        mutableStateOf(startupDiagnostics(context))
    }
    var sessionRetryAttempt by remember { mutableIntStateOf(0) }

    val isInstalled = progress is BootstrapProgress.Installed
    val tabCount = sessionManager.tabs.size
    // Also re-runs whenever the tab count drops back to 0 (every tab closed), opening a fresh one
    // rather than leaving the user stranded on no screen at all. A shell that exits does not close
    // its tab by itself (see AppTerminalClient.onSessionFinished), so a shell that cannot start
    // can no longer turn this into an endless reopen loop.
    LaunchedEffect(isInstalled, tabCount, sessionRetryAttempt) {
        val leaving = (context as? Activity)?.isFinishing == true
        if (isInstalled && tabCount == 0 && sessionError == null && !leaving) {
            // Throwable, not Exception: System.loadLibrary("termux") failing inside JNI's static
            // initializer surfaces as UnsatisfiedLinkError/ExceptionInInitializerError, which are
            // Errors and would otherwise sail straight past this handler.
            try {
                StartupTrace.log(context, "openNewTab: start")
                sessionManager.openNewTab()
                StartupTrace.log(context, "openNewTab: TerminalSession created")
            } catch (t: Throwable) {
                StartupTrace.log(context, "openNewTab: FAILED $t")
                sessionError = "${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString()}"
            }
        }
    }

    val currentSessionError = sessionError
    val activeTab = sessionManager.activeTab
    if (currentSessionError != null) {
        BootstrapProgressScreen(
            progress = BootstrapProgress.Failed(currentSessionError),
            onRetry = {
                sessionError = null
                sessionRetryAttempt++
            },
        )
    } else if (isInstalled && activeTab != null) {
        TerminalScreen(
            tabs = sessionManager.tabs,
            activeTab = activeTab,
            settings = settings,
            extraKeysState = sessionManager.extraKeysState,
            onSelectTab = sessionManager::selectTab,
            onNewTab = {
                try {
                    sessionManager.openNewTab()
                } catch (t: Throwable) {
                    sessionError = "${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString()}"
                }
            },
            onCloseTab = sessionManager::closeTab,
            onOpenSettings = { showSettings = true },
            updateAvailable = updateState.offer != null,
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
                        sessions = sessionManager.tabs.map { it.session },
                        view = activeTab.client.view,
                    )
                },
                onUseDynamicColorChange = settingsRepository::setUseDynamicColor,
                prootAvailable = prootAvailable,
                onUseProotChange = settingsRepository::setUseProot,
                onReinstallEnvironment = {
                    showSettings = false
                    sessionManager.closeAllTabs()
                    installRequest = InstallRequest(installRequest.attempt + 1, forceReinstall = true)
                },
                currentVersionName = updateManager.currentVersionName,
                updateState = updateState,
                onCheckForUpdates = { updateManager.check(userInitiated = true) },
                onShowUpdate = {
                    showSettings = false
                    updateManager.showOffer()
                },
                onAutoCheckUpdatesChange = settingsRepository::setAutoCheckUpdates,
                onDismiss = { showSettings = false },
            )
        }
    } else {
        BootstrapProgressScreen(
            progress = progress,
            // Not a forced reinstall: if a bootstrap is already installed (e.g. only applying
            // fixups to it failed), retrying must not wipe the packages installed since.
            onRetry = { installRequest = InstallRequest(installRequest.attempt + 1, forceReinstall = false) },
        )
    }

    // Above whichever screen is showing - including the first-launch one, so that a broken build
    // can still be updated away from.
    if (updateState.dialogVisible) {
        UpdateDialog(
            state = updateState,
            currentVersionName = updateManager.currentVersionName,
            onUpdate = updateManager::startUpdate,
            onCancelDownload = updateManager::cancelDownload,
            onDismiss = updateManager::dismiss,
        )
    }
}

/**
 * Anything worth showing about how the *previous* launch went: a recorded crash, or a startup
 * trace that stopped before the terminal was ready (which is what a native crash - invisible to
 * any exception handler - looks like). Returns null when the last run looked healthy.
 */
private fun startupDiagnostics(context: Context): String? {
    val crash = CrashReporter.consumeLastCrash(context)
    val previousTrace = StartupTrace.previous(context)
    val previousRunDiedEarly = previousTrace != null && !previousTrace.contains(StartupTrace.SESSION_READY)

    if (crash == null && !previousRunDiedEarly) return null

    return buildString {
        if (crash != null) {
            append("Crash au lancement précédent :\n\n")
            append(crash)
            append("\n\n")
        }
        if (previousRunDiedEarly) {
            append("Le lancement précédent s'est arrêté avant que le terminal soit prêt.\n")
            append("Trace (la dernière ligne indique où) :\n\n")
            append(previousTrace)
            append("\n")
        }
        append("\n--- Environnement ---\n")
        append(environmentDiagnostics(context))
    }
}

private fun environmentDiagnostics(context: Context): String = runCatching {
    val prefix = io.termaterial.shell.TermaterialPaths.realPrefixDir(context)
    val bash = io.termaterial.shell.TermaterialPaths.realBashBinary(context)
    val nativeLibDir = java.io.File(context.applicationInfo.nativeLibraryDir)
    buildString {
        append("abis=${android.os.Build.SUPPORTED_ABIS.joinToString("/")}\n")
        append("sdk=${android.os.Build.VERSION.SDK_INT}\n")
        append("prefix=${prefix.absolutePath} exists=${prefix.isDirectory}\n")
        append("bash exists=${bash.isFile} canExecute=${bash.canExecute()} size=${bash.length()}\n")
        append("nativeLibDir=${nativeLibDir.absolutePath}\n")
        append("proot=${BootstrapShellSessionFactory.isProotAvailable(context)}\n")
        append("nativeLibs=${nativeLibDir.list()?.joinToString(", ") ?: "(unreadable)"}\n")
    }
}.getOrElse { "diagnostics failed: $it" }
