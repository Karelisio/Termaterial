package io.termaterial.app

import android.content.Context

/**
 * Persists the stack trace of any uncaught exception so it can be shown on the *next* launch.
 *
 * Some crashes originate inside Android framework callbacks we don't control the call site of -
 * e.g. [com.termux.view.TerminalView]'s `onSizeChanged()` -> `updateSize()` -> `initializeEmulator()`
 * chain, invoked by the View layout pass rather than by our own code - so a local try/catch
 * around the code that *triggers* layout (as done for session creation in `MainActivity`) cannot
 * catch them. This is a safety net for exactly those cases: it does not attempt to keep the app
 * running past an unexpected crash (that would be relying on undefined behavior after a crash
 * inside a framework callback), it only makes sure the crash is visible afterwards instead of
 * only existing in a logcat the user may not have access to.
 */
object CrashReporter {
    private const val PREFS_NAME = "termaterial_crash_reports"
    private const val KEY_LAST_CRASH = "last_crash_trace"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, throwable.stackTraceToString())
                    .apply()
            }
            // Preserve default crash behaviour (including any OS-level crash dialog/report) -
            // this handler only records the trace, it does not try to suppress the crash.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Returns and clears the last recorded crash, if any - meant to be read once at startup. */
    fun consumeLastCrash(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY_LAST_CRASH, null)
        if (trace != null) {
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }
        return trace
    }
}
