package io.termaterial.app

import android.app.Application
import android.os.Build
import io.termaterial.app.settings.SettingsRepository
import io.termaterial.app.terminal.TerminalColorSchemeApplier
import io.termaterial.app.terminal.TerminalSessionManager
import io.termaterial.app.update.UpdateManager

class TermaterialApplication : Application() {

    /** Process-wide, like the terminal sessions whose rendering they configure. */
    lateinit var settingsRepository: SettingsRepository
        private set

    /** Process-wide owner of every open terminal tab, see [TerminalSessionManager]. */
    lateinit var sessionManager: TerminalSessionManager
        private set

    /** Process-wide, so that an update download survives the Activity being recreated. */
    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        StartupTrace.rotate(this)
        StartupTrace.log(this, "Application.onCreate (sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString("/")})")

        // Probe the pty JNI library up front, catching Throwable (an UnsatisfiedLinkError is an
        // Error, not an Exception): if it is missing from the APK or fails to load, the crash
        // would otherwise happen later, deep inside TerminalView's layout pass, where nothing can
        // catch it. Loading it twice is a no-op, so doing it here costs nothing.
        try {
            System.loadLibrary("termux")
            StartupTrace.log(this, "loadLibrary(termux) OK")
        } catch (t: Throwable) {
            StartupTrace.log(this, "loadLibrary(termux) FAILED: $t")
        }
        StartupTrace.log(this, "nativeLibraryDir=${applicationInfo.nativeLibraryDir}")

        settingsRepository = SettingsRepository(this)
        // The terminal color scheme is a process-wide static: prime it with the persisted palette
        // before any session's emulator exists, so the first one renders with the right colors
        // instead of flashing the xterm defaults.
        try {
            TerminalColorSchemeApplier.apply(settingsRepository.settings.value.terminalPalette, sessions = emptyList(), view = null)
        } catch (t: Throwable) {
            StartupTrace.log(this, "TerminalColorSchemeApplier FAILED: $t")
        }
        sessionManager = TerminalSessionManager(this, settingsRepository)
        updateManager = UpdateManager(
            context = this,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            repository = BuildConfig.UPDATE_REPOSITORY,
        )
    }
}
