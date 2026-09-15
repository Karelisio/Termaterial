package io.termaterial.app

import android.app.Application
import android.os.Build

class TermaterialApplication : Application() {
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
    }
}
