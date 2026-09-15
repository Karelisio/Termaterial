package io.termaterial.shell

import android.content.Context
import java.io.File

/**
 * Real, on-device paths (inside this app's private storage) and the "virtual" paths that the
 * Termux bootstrap binaries expect to see (hardcoded at build time by termux-packages into
 * shebang lines, RPATH/RUNPATH entries and dpkg metadata as `/data/data/com.termux/files/...`).
 *
 * Termaterial does not use the `com.termux` package name, so those hardcoded paths do not exist
 * on a real device. [ProotShellSessionFactory] bind-mounts the real paths below onto the virtual
 * ones via proot, which is what lets the unmodified Termux bootstrap run correctly under a
 * different application ID (the same trick Termux's own "Android 10 compatible" bootstrap
 * flavor uses to work around the exec-from-app-data-directory restriction - see
 * docs/step-2-shell-backend.md).
 */
object TermaterialPaths {

    /** Virtual prefix path hardcoded into the Termux bootstrap binaries. Never created on disk. */
    const val VIRTUAL_PREFIX = "/data/data/com.termux/files/usr"

    /** Virtual home path hardcoded into the Termux bootstrap binaries. Never created on disk. */
    const val VIRTUAL_HOME = "/data/data/com.termux/files/home"

    /** Real, final prefix directory: <filesDir>/usr. */
    fun realPrefixDir(context: Context): File = File(context.filesDir, "usr")

    /** Staging directory used while extracting a new bootstrap, swapped in atomically on success. */
    fun realPrefixStagingDir(context: Context): File = File(context.filesDir, "usr-staging")

    /** Real home directory: <filesDir>/home. */
    fun realHomeDir(context: Context): File = File(context.filesDir, "home")

    /** Marker file recording which bootstrap release tag is currently installed. */
    fun installedVersionMarker(context: Context): File = File(realPrefixDir(context), ".TERMATERIAL_BOOTSTRAP_VERSION")

    fun realProotBinary(context: Context): File = File(realPrefixDir(context), "bin/proot")

    fun realBashBinary(context: Context): File = File(realPrefixDir(context), "bin/bash")
}
