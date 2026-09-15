package io.termaterial.shell

import android.content.Context
import android.os.Build
import android.os.Environment
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates [TerminalSession]s that run the extracted Termux bootstrap's `bash` through `proot`.
 *
 * Termux's own packages are compiled with `/data/data/com.termux/files/usr` baked into shebang
 * lines and ELF RPATH/RUNPATH entries. Since Termaterial uses its own application ID, that path
 * does not exist on disk here - so every launch runs the bootstrap's `proot` (itself part of the
 * bootstrap, pulled in as a package dependency) with `-b <real path>:<virtual path>` bind mounts
 * that make the virtual `com.termux` paths resolve to this app's real private storage. This is
 * the same technique Termux's own "Android 10 compatible" bootstrap flavor uses internally to
 * cope with the exec-from-app-data-directory restriction - see docs/step-2-shell-backend.md for
 * the full rationale, including a known caveat around running `proot` itself on API 29+.
 */
class ProotShellSessionFactory {

    /**
     * Builds and starts a new proot-wrapped bash [TerminalSession]. [BootstrapInstaller.install]
     * must have completed successfully before calling this.
     */
    fun createSession(
        context: Context,
        client: TerminalSessionClient,
        transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    ): TerminalSession {
        val realPrefixDir = TermaterialPaths.realPrefixDir(context)
        val realHomeDir = TermaterialPaths.realHomeDir(context).apply { mkdirs() }
        val prootFile = TermaterialPaths.realProotBinary(context)

        check(prootFile.canExecute()) {
            "proot binary missing or not executable at ${prootFile.absolutePath}; " +
                "run BootstrapInstaller.install() before creating a shell session"
        }

        val argv = buildProotArgv(
            prootPath = prootFile.absolutePath,
            realPrefixDir = realPrefixDir,
            realHomeDir = realHomeDir,
            externalStorageDir = externalStorageDirOrNull(),
            apexBindRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        val env = buildShellEnvironment()

        return TerminalSession(
            /* shellPath = */ prootFile.absolutePath,
            // proot itself only chdir()s in the real filesystem, before it starts remapping
            // paths for the process it execs - so this must be a real, on-disk directory.
            /* cwd = */ realHomeDir.absolutePath,
            /* args = */ argv.toTypedArray(),
            /* env = */ env.toTypedArray(),
            /* transcriptRows = */ transcriptRows,
            /* client = */ client,
        )
    }

    private fun externalStorageDirOrNull(): File? =
        Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }

    companion object {
        const val DEFAULT_TRANSCRIPT_ROWS = 2000

        /**
         * Pure, unit-testable argv builder for the proot invocation. `argv[0]` doubles as the
         * `cmd` passed to `execvp()` by [TerminalSession]/the native pty JNI code, so it must be
         * [prootPath] itself, not just a display name.
         */
        internal fun buildProotArgv(
            prootPath: String,
            realPrefixDir: File,
            realHomeDir: File,
            externalStorageDir: File?,
            apexBindRequired: Boolean,
            shellArgs: List<String> = listOf("--login"),
        ): List<String> {
            val argv = mutableListOf(
                prootPath,
                // Termux's own proot build; --link2symlink works around filesystems without
                // hardlink support (e.g. some SD cards) and --sysvipc enables SysV IPC emulation
                // used by some packages (postgresql, etc).
                "--link2symlink",
                "--kill-on-exit",
                "--sysvipc",
                // Fake root: makes getuid()/chown/chmod etc. succeed as they would for a real
                // root user, which apt/dpkg postinst scripts and many packages expect.
                "-0",
                "-b", "/dev",
                "-b", "/proc",
            )

            if (externalStorageDir != null) {
                argv += listOf("-b", "${externalStorageDir.absolutePath}:/sdcard")
            }

            if (apexBindRequired) {
                // Android 10+ moved libc's DNS resolver config under /apex; without this bind,
                // name resolution (and so apt/pkg, curl, ...) fails inside the proot sandbox.
                argv += listOf("-b", "/apex")
            }

            argv += listOf(
                "-b", "${realPrefixDir.absolutePath}:${TermaterialPaths.VIRTUAL_PREFIX}",
                "-b", "${realHomeDir.absolutePath}:${TermaterialPaths.VIRTUAL_HOME}",
                "-w", TermaterialPaths.VIRTUAL_HOME,
                "${TermaterialPaths.VIRTUAL_PREFIX}/bin/bash",
            )
            argv += shellArgs

            return argv
        }

        /** Pure, unit-testable environment builder for the proot-launched shell. */
        internal fun buildShellEnvironment(extra: Map<String, String> = emptyMap()): List<String> {
            val env = linkedMapOf(
                "HOME" to TermaterialPaths.VIRTUAL_HOME,
                "PREFIX" to TermaterialPaths.VIRTUAL_PREFIX,
                "PATH" to "${TermaterialPaths.VIRTUAL_PREFIX}/bin",
                "LD_LIBRARY_PATH" to "${TermaterialPaths.VIRTUAL_PREFIX}/lib",
                "LANG" to "en_US.UTF-8",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "TMPDIR" to "${TermaterialPaths.VIRTUAL_PREFIX}/tmp",
            )
            env += extra
            return env.map { (key, value) -> "$key=$value" }
        }
    }
}
