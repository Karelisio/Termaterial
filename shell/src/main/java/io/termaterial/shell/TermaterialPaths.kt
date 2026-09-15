package io.termaterial.shell

import android.content.Context
import java.io.File

/** Real, on-device paths (inside this app's private storage) used by the shell backend. */
object TermaterialPaths {

    /** Real, final prefix directory: <filesDir>/usr. */
    fun realPrefixDir(context: Context): File = File(context.filesDir, "usr")

    /** Staging directory used while extracting a new bootstrap, swapped in atomically on success. */
    fun realPrefixStagingDir(context: Context): File = File(context.filesDir, "usr-staging")

    /** Real home directory: <filesDir>/home. */
    fun realHomeDir(context: Context): File = File(context.filesDir, "home")

    /** Marker file recording which bootstrap release tag is currently installed. */
    fun installedVersionMarker(context: Context): File = File(realPrefixDir(context), ".TERMATERIAL_BOOTSTRAP_VERSION")

    fun realBashBinary(context: Context): File = File(realPrefixDir(context), "bin/bash")
}
