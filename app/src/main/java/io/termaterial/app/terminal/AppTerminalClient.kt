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
 * [TerminalView] and to simple Compose-facing callbacks.
 */
class AppTerminalClient(
    private val context: Context,
    private val extraKeysState: ExtraKeysState,
    private val onTitleChanged: (String?) -> Unit,
    private val onSessionFinished: () -> Unit,
) : TerminalSessionClient, TerminalViewClient {

    /** Set once the hosting Composable creates its [TerminalView]; used to request repaints. */
    var view: TerminalView? = null

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Termaterial", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (session == null) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        if (!text.isNullOrEmpty()) {
            val bytes = text.toString().toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
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

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {
        val terminalView = view ?: return
        terminalView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // No-op.
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = extraKeysState.controlActive

    override fun readAltKey(): Boolean = extraKeysState.altActive

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        // The pty is live and the emulator is attached: startup got all the way through, so the
        // next launch has nothing to report. See StartupTrace.
        StartupTrace.log(context, StartupTrace.SESSION_READY)
    }

    companion object {
        private const val LOG_TAG = "Termaterial"
    }
}
