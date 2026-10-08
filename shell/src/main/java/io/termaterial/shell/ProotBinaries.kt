package io.termaterial.shell

import java.io.File

/**
 * Termux's proot, packaged in the APK by `scripts/fetch-proot.sh` (CI builds) and extracted by
 * Android to the app's native library directory - the one place an app may always execute files
 * from. Absent from builds made without that script, which then fall back to running bash
 * directly (see [BootstrapShellSessionFactory]).
 */
data class ProotBinaries(
    val proot: File,
    /** Static helper proot executes in place of every guest program. */
    val loader: File,
    /** Same, for 32-bit programs on a 64-bit device; only shipped for 64-bit ABIs. */
    val loader32: File?,
) {
    companion object {
        fun find(nativeLibraryDir: File): ProotBinaries? {
            val proot = File(nativeLibraryDir, "libproot.so")
            val loader = File(nativeLibraryDir, "libproot-loader.so")
            if (!proot.isFile || !loader.isFile) return null
            val loader32 = File(nativeLibraryDir, "libproot-loader32.so").takeIf { it.isFile }
            return ProotBinaries(proot, loader, loader32)
        }
    }
}
