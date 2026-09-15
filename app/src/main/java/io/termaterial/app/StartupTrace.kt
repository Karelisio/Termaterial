package io.termaterial.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only breadcrumb log of the startup sequence, written straight to a file and `fsync`ed
 * after every line.
 *
 * Unlike [CrashReporter] (which relies on a JVM-level uncaught-exception handler), this survives
 * *any* way the process can die - including a native crash (SIGSEGV/SIGABRT in the JNI pty code),
 * an OS kill, or an `Error` such as `UnsatisfiedLinkError` that never reaches an exception
 * handler. Whatever the last line written was tells us exactly how far startup got.
 *
 * The trace of the previous run is rotated aside at each launch so it can be displayed if that
 * run never reached [SESSION_READY].
 */
object StartupTrace {

    /** Written once the terminal is actually up; its absence means the previous run died early. */
    const val SESSION_READY = "SESSION_READY"

    private const val CURRENT = "startup-trace.log"
    private const val PREVIOUS = "startup-trace-previous.log"

    /** Moves the current trace aside and starts a fresh one. Call once, at Application startup. */
    fun rotate(context: Context) {
        runCatching {
            val dir = context.applicationContext.filesDir
            val current = File(dir, CURRENT)
            val previous = File(dir, PREVIOUS)
            if (current.exists()) {
                previous.delete()
                current.renameTo(previous)
            }
        }
    }

    /** The previous run's trace, or null if there is none. */
    fun previous(context: Context): String? = runCatching {
        File(context.applicationContext.filesDir, PREVIOUS).takeIf { it.isFile }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun log(context: Context, message: String) {
        runCatching {
            val file = File(context.applicationContext.filesDir, CURRENT)
            FileOutputStream(file, true).use { out ->
                out.write("${System.currentTimeMillis() % 1_000_000}  $message\n".toByteArray())
                out.flush()
                // Force to disk: a native crash right after this line must not lose it.
                out.fd.sync()
            }
        }
    }
}
