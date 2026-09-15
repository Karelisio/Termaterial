package io.termaterial.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.termaterial.app.service.TerminalSessionService
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

    /** Bound once [ensureBackgroundServiceStarted] connects; null while unbound/before that. */
    private val terminalSessionService = mutableStateOf<TerminalSessionService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            terminalSessionService.value = (binder as? TerminalSessionService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalSessionService.value = null
        }
    }

    private var boundToService = false

    // POST_NOTIFICATIONS (API 33+) only controls whether the foreground-service notification is
    // *visible*; the service itself starts and keeps the session alive either way (see
    // TerminalSessionService and docs/step-5-permissions.md), so no result handling is needed
    // here beyond letting the system remember the user's choice - this is the "degrade
    // gracefully rather than crash" the task asks for.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.log(this, "MainActivity.onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val settingsRepository = SettingsRepository(applicationContext)
        StartupTrace.log(this, "settings loaded, calling setContent")
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
                    TermaterialApp(
                        settingsRepository = settingsRepository,
                        service = terminalSessionService,
                        onEnsureBackgroundServiceStarted = ::ensureBackgroundServiceStarted,
                    )
                }
            }
        }
    }

    private fun ensureBackgroundServiceStarted() {
        if (boundToService) return
        boundToService = true
        try {
            TerminalSessionService.start(this)
            bindService(Intent(this, TerminalSessionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // The terminal itself must keep working even if the background-keep-alive service
            // can't start (see TerminalSessionService.onStartCommand for the same reasoning).
            CrashReporter.record(this, "MainActivity.ensureBackgroundServiceStarted:\n\n${e.stackTraceToString()}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (boundToService) {
            runCatching { unbindService(serviceConnection) }
        }
    }
}

@Composable
private fun TermaterialApp(
    settingsRepository: SettingsRepository,
    service: State<TerminalSessionService?>,
    onEnsureBackgroundServiceStarted: () -> Unit,
) {
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

    fun openNewTab() {
        // Throwable, not Exception: System.loadLibrary("termux") failing inside JNI's static
        // initializer surfaces as UnsatisfiedLinkError/ExceptionInInitializerError, which are
        // Errors and would otherwise sail straight past this handler.
        try {
            StartupTrace.log(context, "openNewTab: start")
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
            StartupTrace.log(context, "openNewTab: TerminalSession created")
            tabs.add(TerminalTab(id, session, client, title))
            activeTabId = id
        } catch (t: Throwable) {
            StartupTrace.log(context, "openNewTab: FAILED $t")
            sessionError = "${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString()}"
        }
    }

    val isInstalled = progress is BootstrapProgress.Installed
    // Also re-runs whenever tabs.size changes back to 0 (every tab closed, or a shell exited),
    // opening a fresh one rather than leaving the user stranded on no screen at all.
    LaunchedEffect(isInstalled, tabs.size, sessionRetryAttempt) {
        if (isInstalled && tabs.isEmpty() && sessionError == null) {
            try {
                // Prime the (process-wide) terminal color scheme with the persisted palette
                // before the first session's emulator is created, so it renders with the right
                // colors from the start instead of flashing the xterm defaults. A no-op on later
                // re-opens.
                StartupTrace.log(context, "bootstrap installed, applying palette")
                TerminalColorSchemeApplier.apply(settings.terminalPalette, sessions = emptyList(), view = null)
                openNewTab()
            } catch (t: Throwable) {
                StartupTrace.log(context, "palette/openNewTab FAILED: $t")
                sessionError = "${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString()}"
            }
        }
        if (isInstalled && tabs.isNotEmpty()) {
            onEnsureBackgroundServiceStarted()
        }
    }

    // Keep the foreground-service notification (Step 5) in sync with the open tabs.
    val activeTabTitle = tabs.firstOrNull { it.id == activeTabId }?.title?.value
    LaunchedEffect(tabs.size, activeTabTitle, service.value) {
        service.value?.updateStatus(sessionCount = tabs.size, activeTitle = activeTabTitle)
    }

    val currentSessionError = sessionError
    val currentActiveId = activeTabId
    if (currentSessionError != null) {
        BootstrapProgressScreen(
            progress = BootstrapProgress.Failed(currentSessionError),
            onRetry = {
                sessionError = null
                sessionRetryAttempt++
            },
        )
    } else if (currentActiveId != null && tabs.isNotEmpty()) {
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
        append("nativeLibs=${nativeLibDir.list()?.joinToString(", ") ?: "(unreadable)"}\n")
    }
}.getOrElse { "diagnostics failed: $it" }
