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

        // apt/dpkg from the bootstrap have /data/data/com.termux/files/usr baked in as their
        // config root at build time (see buildAptConfigOverride doc below); write the override
        // file fresh on every session so it can never go stale relative to realPrefixDir.
        aptConfigFile(realPrefixDir).writeText(buildAptConfigOverride(realPrefixDir))

        val env = buildShellEnvironment(realPrefixDir, realHomeDir)

        return TerminalSession(
            /* shellPath = */ bashFile.absolutePath,
            /* cwd = */ realHomeDir.absolutePath,
            // --noprofile: bash's --login otherwise sources /etc/profile from a path baked into
            // the binary at compile time by termux-packages - literally
            // /data/data/com.termux/files/usr/etc/profile, another app's private sandbox that can
            // never exist for us no matter how PREFIX is remapped (confirmed on a real device:
            // "Permission denied", harmless but confusing). Not a loss: we already set every env
            // var that file would have (HOME/PREFIX/PATH/LD_LIBRARY_PATH/...) directly above.
            /* args = */ arrayOf(bashFile.absolutePath, "--login", "--noprofile"),
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
                // See buildAptConfigOverride: redirects apt's compiled-in com.termux config root.
                "APT_CONFIG" to aptConfigFile(realPrefixDir).absolutePath,
                // dpkg has the same hardcoded admindir problem as apt, but for direct `dpkg ...`
                // invocations (not routed through apt, which already gets Dir::State::status from
                // the override above) - DPKG_ADMINDIR is dpkg's own env var equivalent of
                // --admindir.
                "DPKG_ADMINDIR" to "${realPrefixDir.absolutePath}/var/lib/dpkg",
            )
            env += extra
            return env.map { (key, value) -> "$key=$value" }
        }

        /** Where [buildAptConfigOverride]'s content is written; kept outside `usr/` so it is not
         *  wiped by a bootstrap reinstall. Derived from [realPrefixDir] alone (not [Context])
         *  purely so it stays trivial to unit test alongside [buildAptConfigOverride]. */
        internal fun aptConfigFile(realPrefixDir: File): File =
            File(realPrefixDir.parentFile, "termaterial-apt.conf")

        /**
         * apt (like bash's `/etc/profile`, see the `--noprofile` note above) has
         * `/data/data/com.termux/files/usr` baked in at build time as its default config root
         * (`Dir::Etc`, `Dir::State::status`, `Dir::Bin::methods`, ...) - a directory that belongs
         * to a different app and is never reachable here, causing `apt update` to fail with
         * "Unable to read .../etc/apt/apt.conf.d/ - Permission denied" then
         * "Unable to determine a suitable packaging system type" (confirmed on a real device).
         *
         * This is the standard technique chroot tooling (schroot, sbuild, mmdebstrap) uses to
         * repoint apt at a different root at runtime: a config file, referenced via the
         * `APT_CONFIG` environment variable (read before apt resolves its own default config
         * location, unlike a file placed inside the - wrong - default `Dir::Etc`), that sets the
         * top-level `Dir` and every `Dir::*` key apt does not resolve relative to it by default.
         *
         * `Acquire::https::CaInfo` is the same problem again, one level down: with `Dir` fixed,
         * `apt update` could reach packages-cf.termux.dev but then failed TLS verification
         * ("No system certificates available", confirmed on a real device) because apt's https
         * method also has a compiled-in, com.termux-only default CA bundle path
         * (`$PREFIX/etc/tls/cert.pem`, shipped by the bootstrap itself as an apt dependency, not
         * something Acquire::https::CaInfo's `Dir::*`-style relative resolution covers).
         */
        internal fun buildAptConfigOverride(realPrefixDir: File): String {
            val prefix = realPrefixDir.absolutePath
            return """
                |// Generated by Termaterial at every shell session start - see
                |// BootstrapShellSessionFactory.buildAptConfigOverride and
                |// docs/step-2-shell-backend.md. Redirects apt away from the bootstrap's
                |// compiled-in /data/data/com.termux/files/usr config root.
                |Dir "$prefix/";
                |Dir::State "var/lib/apt/";
                |Dir::State::status "var/lib/dpkg/status";
                |Dir::Cache "var/cache/apt/";
                |Dir::Etc "etc/apt/";
                |Dir::Etc::sourcelist "sources.list";
                |Dir::Etc::sourceparts "sources.list.d";
                |Dir::Etc::preferences "preferences";
                |Dir::Etc::preferencesparts "preferences.d";
                |Dir::Etc::trusted "trusted.gpg";
                |Dir::Etc::trustedparts "trusted.gpg.d";
                |Dir::Bin::methods "$prefix/lib/apt/methods";
                |Dir::Bin::dpkg "$prefix/bin/dpkg";
                |Dir::Log "var/log/apt";
                |Acquire::https::CaInfo "$prefix/etc/tls/cert.pem";
                |
            """.trimMargin()
        }
    }
}
