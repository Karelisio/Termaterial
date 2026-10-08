package io.termaterial.shell

import android.content.Context
import java.io.File

/** Real, on-device paths (inside this app's private storage) used by the shell backend. */
object TermaterialPaths {

    /**
     * This app's data directory (`/data/user/<user>/<package>`): what the bootstrap's hardcoded
     * `/data/data/com.termux` is remapped to, see [TermuxPathRewriter]. Its layout mirrors
     * Termux's: `files/usr`, `files/home`, `cache`.
     */
    fun realDataDir(context: Context): File = context.filesDir.parentFile ?: context.filesDir

    /** Real, final prefix directory: <filesDir>/usr. */
    fun realPrefixDir(context: Context): File = File(context.filesDir, "usr")

    /** Staging directory used while extracting a new bootstrap, swapped in atomically on success. */
    fun realPrefixStagingDir(context: Context): File = File(context.filesDir, "usr-staging")

    /** Real home directory: <filesDir>/home. */
    fun realHomeDir(context: Context): File = File(context.filesDir, "home")

    /** apt's download cache, where Termux keeps it (`<dataDir>/cache/apt`, referenced by `pkg`). */
    fun aptCacheDir(context: Context): File = File(context.cacheDir, "apt")

    /** Marker file recording which bootstrap release tag is currently installed. */
    fun installedVersionMarker(context: Context): File = File(realPrefixDir(context), ".TERMATERIAL_BOOTSTRAP_VERSION")

    fun realBashBinary(context: Context): File = File(realPrefixDir(context), "bin/bash")

    /** Where Android extracted the APK's native libraries - [ProotBinaries] among them. */
    fun nativeLibraryDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)
}
