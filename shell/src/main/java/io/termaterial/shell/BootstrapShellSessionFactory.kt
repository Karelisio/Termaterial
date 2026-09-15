package io.termaterial.shell

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates [TerminalSession]s that run the extracted Termux bootstrap's `bash` directly.
 *
 * The bootstrap's binaries are real Android ELF executables (interpreter
 * `/system/bin/linker64`/`linker`), not something that needs a userspace sandbox like proot to
 * run - Termux itself has executed them directly since 2021. The only thing hardcoded at build
 * time by termux-packages that matters here is `/data/data/com.termux/files/usr/lib` as each
 * binary's `DT_RUNPATH`; since Termaterial uses a different application ID, that directory does
 * not exist on disk. The Android dynamic linker consults `LD_LIBRARY_PATH` *before*
 * `DT_RUNPATH`, so setting it to this app's real `lib` directory is enough - no bind mounts or
 * fake chroot needed. See docs/step-2-shell-backend.md for how this was verified (by downloading
 * and inspecting a real bootstrap archive) and for the known caveats this simpler approach still
 * carries (some maintainer scripts with a hardcoded `#!/data/data/com.termux/...` shebang, and
 * the Android 10+ exec-from-app-data-directory restriction, which applies here just as it would
 * to any other approach that downloads a binary at runtime).
 */
class BootstrapShellSessionFactory {

    /**
     * Builds and starts a new bash [TerminalSession]. [BootstrapInstaller.install] must have
     * completed successfully before calling this.
     */
    fun createSession(
        context: Context,
        client: TerminalSessionClient,
        transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    ): TerminalSession {
        val realPrefixDir = TermaterialPaths.realPrefixDir(context)
        val realHomeDir = TermaterialPaths.realHomeDir(context).apply { mkdirs() }
        val bashFile = TermaterialPaths.realBashBinary(context)

        check(bashFile.canExecute()) {
            "bash binary missing or not executable at ${bashFile.absolutePath}; " +
                "run BootstrapInstaller.install() before creating a shell session"
        }

        val env = buildShellEnvironment(realPrefixDir, realHomeDir)

        return TerminalSession(
            /* shellPath = */ bashFile.absolutePath,
            /* cwd = */ realHomeDir.absolutePath,
            /* args = */ arrayOf(bashFile.absolutePath, "--login"),
            /* env = */ env.toTypedArray(),
            /* transcriptRows = */ transcriptRows,
            /* client = */ client,
        )
    }

    companion object {
        const val DEFAULT_TRANSCRIPT_ROWS = 2000

        /** Pure, unit-testable environment builder for the bootstrap's bash. */
        internal fun buildShellEnvironment(
            realPrefixDir: File,
            realHomeDir: File,
            extra: Map<String, String> = emptyMap(),
        ): List<String> {
            val env = linkedMapOf(
                "HOME" to realHomeDir.absolutePath,
                "PREFIX" to realPrefixDir.absolutePath,
                "PATH" to "${realPrefixDir.absolutePath}/bin",
                // See the class doc: this replaces the bootstrap binaries' hardcoded, nonexistent
                // /data/data/com.termux/files/usr/lib DT_RUNPATH.
                "LD_LIBRARY_PATH" to "${realPrefixDir.absolutePath}/lib",
                "LANG" to "en_US.UTF-8",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "TMPDIR" to "${realPrefixDir.absolutePath}/tmp",
            )
            env += extra
            return env.map { (key, value) -> "$key=$value" }
        }
    }
}
