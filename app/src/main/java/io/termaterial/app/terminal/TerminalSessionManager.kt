package io.termaterial.app.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.termaterial.app.CrashReporter
import io.termaterial.app.service.TerminalSessionService
import io.termaterial.app.settings.SettingsRepository
import io.termaterial.shell.BootstrapShellSessionFactory
import java.util.UUID

/**
 * Owns every open terminal tab for the lifetime of the process, not of an Activity: the shell
 * processes behind them outlive any Activity (TerminalSessionService keeps the process alive in
 * the background), so the tabs have to as well. They used to live in MainActivity's Compose state
 * and were orphaned - shell still running, unreachable - whenever the Activity was recreated:
 * Back on Android 11 and older, a configuration change missing from configChanges (resizing in
 * split screen, changing the display size...), "Don't keep activities".
 *
 * Main thread only, like every TerminalSession callback. Starts [TerminalSessionService] when the
 * first tab opens; the service observes this manager (see [addListener]) and stops itself once
 * the last tab is closed.
 */
class TerminalSessionManager(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
) {
    val tabs = mutableStateListOf<TerminalTab>()

    var activeTabId by mutableStateOf<String?>(null)
        private set

    val extraKeysState = ExtraKeysState()

    private var nextTabNumber = 1
    private val listeners = mutableListOf<() -> Unit>()
    private val exitListeners = mutableListOf<() -> Unit>()

    val activeTab: TerminalTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    /** Called after every change of the tab list, active tab, a title or a session's state. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Called by [exit], before the tabs are closed, so that the UI can go away first. */
    fun addExitListener(listener: () -> Unit) {
        exitListeners += listener
    }

    fun removeExitListener(listener: () -> Unit) {
        exitListeners -= listener
    }

    /**
     * Opens a new shell tab and makes it the active one. Throws (any [Throwable], e.g. an
     * UnsatisfiedLinkError from the pty JNI library) if the session cannot be created; the
     * caller is expected to show it.
     */
    fun openNewTab(): TerminalTab {
        val id = UUID.randomUUID().toString()
        val title = mutableStateOf<String?>(null)
        val finished = mutableStateOf(false)
        val client = AppTerminalClient(
            appContext = appContext,
            extraKeysState = extraKeysState,
            onTitleChanged = {
                title.value = it
                notifyChanged()
            },
            onSessionFinished = {
                finished.value = true
                notifyChanged()
            },
            onCloseRequested = { closeTab(id) },
            onFontSizeStep = settingsRepository::stepFontSize,
        )
        val session = BootstrapShellSessionFactory().createSession(appContext, client)
        val tab = TerminalTab(id, nextTabNumber++, session, client, title, finished)
        tabs += tab
        activeTabId = id
        // On every new tab, not just the first: harmless if the service already runs, and it
        // restarts it if it could not start earlier or was stopped since.
        startKeepAliveService()
        notifyChanged()
        return tab
    }

    fun selectTab(id: String) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
            notifyChanged()
        }
    }

    /** Kills the tab's shell if it is still running and removes the tab. */
    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index].session.finishIfRunning()
        tabs.removeAt(index)
        if (activeTabId == id) {
            // The neighbour that takes the closed tab's place, as in a browser.
            activeTabId = (tabs.getOrNull(index) ?: tabs.lastOrNull())?.id
        }
        if (tabs.isEmpty()) nextTabNumber = 1
        notifyChanged()
    }

    /** Kills every tab's shell and removes all tabs. */
    fun closeAllTabs() {
        for (tab in tabs) tab.session.finishIfRunning()
        tabs.clear()
        activeTabId = null
        nextTabNumber = 1
        notifyChanged()
    }

    /** Asks the UI to go away and closes every tab (the notification's "Exit" action). */
    fun exit() {
        for (listener in exitListeners.toList()) listener()
        closeAllTabs()
    }

    private fun notifyChanged() {
        for (listener in listeners.toList()) listener()
    }

    private fun startKeepAliveService() {
        try {
            TerminalSessionService.start(appContext)
        } catch (e: Exception) {
            // Keeping sessions alive in the background is a nice-to-have: the terminal itself must
            // keep working in the foreground even if the service cannot start.
            CrashReporter.record(appContext, "TerminalSessionService.start:\n\n${e.stackTraceToString()}")
        }
    }
}
