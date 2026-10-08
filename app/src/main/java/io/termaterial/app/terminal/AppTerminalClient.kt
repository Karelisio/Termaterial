package io.termaterial.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.termaterial.app.StartupTrace

/**
 * [TerminalSessionClient] + [TerminalViewClient] implementation wiring a [TerminalSession] to a
 * [TerminalView] and to simple callbacks of its owning [TerminalSessionManager].
 *
 * Lives as long as its session - i.e. longer than any Activity - so it only ever holds the
 * application [Context]; [view] is cleared by the hosting composable when it goes away.
 */
class AppTerminalClient(
    private val appContext: Context,
    private val extraKeysState: ExtraKeysState,
    private val onTitleChanged: (String?) -> Unit,
    private val onSessionFinished: () -> Unit,
    private val onCloseRequested: () -> Unit,
    private val onFontSizeStep: (increase: Boolean) -> Unit,
) : TerminalSessionClient, TerminalViewClient {

    /** Set while a [TerminalView] displays this client's session; used to request repaints. */
    var view: TerminalView? = null

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // TerminalSession has already printed "[Process completed (code N) - press Enter]": the
        // tab stays open (see onKeyDown/onCodePoint) so whatever the shell printed before exiting
        // - e.g. why it could not start - stays readable, instead of the tab vanishing at once.
        onSessionFinished()
        view?.onScreenUpdated()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Termaterial", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val emulator = session?.emulator ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)
        if (!text.isNullOrEmpty()) {
            // Not a raw write: paste() converts newlines to the carriage returns a terminal expects
            // and honours bracketed paste mode, so a multi-line paste is not run line by line by
            // the shell as it arrives.
            emulator.paste(text.toString())
        }
    }

    override fun onBell(session: TerminalSession) {
        // Intentionally silent for now; a settings-controlled bell/vibration can be added later.
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.invalidate()
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // No-op: nothing currently depends on the shell's pid.
    }

    override fun getTerminalCursorStyle(): Int? = null

    // --- Logging (both interfaces declare the same methods) ---

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, "", e)
    }

    // --- TerminalViewClient ---

    /** Pinch to zoom: one font size step per noticeable pinch, as Termux does. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onFontSizeStep(scale > 1f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val terminalView = view ?: return
        terminalView.requestFocus()
        val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // No-op.
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (!session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
            onCloseRequested()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = extraKeysState.consumeControl()

    override fun readAltKey(): Boolean = extraKeysState.consumeAlt()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // Soft keyboards often send Enter as text rather than as a key event.
        if (!session.isRunning && (codePoint == '\r'.code || codePoint == '\n'.code)) {
            onCloseRequested()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
        // The pty is live and the emulator is attached: startup got all the way through, so the
        // next launch has nothing to report. See StartupTrace.
        StartupTrace.log(appContext, StartupTrace.SESSION_READY)
    }

    companion object {
        private const val LOG_TAG = "Termaterial"
    }
}
