package io.termaterial.shell

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates [TerminalSession]s running the extracted Termux bootstrap, in one of two modes.
 *
 * Everything termux-packages builds hardcodes Termux's own data directory,
 * `/data/data/com.termux`, which never exists for a different application ID - in scripts,
 * symlinks, ELF binaries (`DT_RUNPATH`, compiled-in config paths) and in the paths inside every
 * `.deb` package.
 *
 * - **proot mode** (preferred, when the APK ships [ProotBinaries]): the shell runs under proot with
 *   this app's data directory bound at `/data/data/com.termux`, so every one of those paths
 *   resolves, exactly as in Termux - including `pkg`/`apt install`, package maintainer scripts and
 *   whatever paths installed programs have compiled in. Costs a ptrace-based syscall
 *   interception.
 * - **direct mode** (fallback): the bootstrap's bash runs as is. Text files and symlinks were
 *   rewritten at install time by [BootstrapFixups]; `DT_RUNPATH` is superseded by
 *   `LD_LIBRARY_PATH`; the compiled-in paths that matter for the bootstrap itself (bash's system
 *   profile, apt's directory layout, CA bundles) are overridden here. Installing packages cannot
 *   work in this mode (dpkg extracts them to `/data/data/com.termux/...`).
 *
 * See docs/step-2-shell-backend.md and docs/step-7-reprise.md.
 */
class BootstrapShellSessionFactory {

    /**
     * Builds and starts a new shell [TerminalSession] - under proot when [useProot] is set and
     * the APK ships it, directly otherwise. [BootstrapInstaller.install] must have completed
     * successfully before calling this.
     */
    fun createSession(
        context: Context,
        client: TerminalSessionClient,
        useProot: Boolean = true,
        transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    ): TerminalSession {
        val realPrefixDir = TermaterialPaths.realPrefixDir(context)
        val realHomeDir = TermaterialPaths.realHomeDir(context).apply { mkdirs() }
        val aptCacheDir = TermaterialPaths.aptCacheDir(context)
        val bashFile = TermaterialPaths.realBashBinary(context)

        check(bashFile.canExecute()) {
            "bash binary missing or not executable at ${bashFile.absolutePath}; " +
                "run BootstrapInstaller.install() before creating a shell session"
        }

        // Directories apt expects to find: the bootstrap ships none of them, and Android may clear
        // the cache directory at any time. The same real directories in both modes.
        for (dir in runtimeDirectories(realPrefixDir, aptCacheDir)) dir.mkdirs()

        val proot = if (useProot) ProotBinaries.find(TermaterialPaths.nativeLibraryDir(context)) else null
        if (proot != null) {
            return TerminalSession(
                /* shellPath = */ proot.proot.absolutePath,
                /* cwd = */ realHomeDir.absolutePath,
                /* args = */ buildProotArguments(proot, TermaterialPaths.realDataDir(context)).toTypedArray(),
                /* env = */ buildProotEnvironment(proot, realPrefixDir, systemEnv = System.getenv()).toTypedArray(),
                /* transcriptRows = */ transcriptRows,
                /* client = */ client,
            )
        }

        // Both rewritten on every session so they can never go stale relative to the real paths.
        aptConfigFile(realPrefixDir).writeText(buildAptConfigOverride(realPrefixDir, aptCacheDir))
        val rcFile = bashRcFile(realPrefixDir).apply { writeText(BASH_RC) }

        val env = buildShellEnvironment(realPrefixDir, realHomeDir, systemEnv = System.getenv())

        return TerminalSession(
            /* shellPath = */ bashFile.absolutePath,
            /* cwd = */ realHomeDir.absolutePath,
            // An interactive *non-login* shell running BASH_RC, rather than `bash --login`: a login
            // shell reads its system profile from a path compiled into the bootstrap's bash -
            // /data/data/com.termux/files/usr/etc/profile, another app's private sandbox ("Permission
            // denied" on a real device). BASH_RC performs the same startup sequence with real paths.
            /* args = */ arrayOf(bashFile.absolutePath, "--rcfile", rcFile.absolutePath, "-i"),
            /* env = */ env.toTypedArray(),
            /* transcriptRows = */ transcriptRows,
            /* client = */ client,
        )
    }

    companion object {
        const val DEFAULT_TRANSCRIPT_ROWS = 2000

        /**
         * Android runtime variables that `/system/bin` tools (`am`, `pm`, `cmd`, `dalvikvm`...)
         * need, passed through from this app's own process environment - as Termux does.
         */
        private val ANDROID_ENV_PASSTHROUGH = listOf(
            "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE", "EXTERNAL_STORAGE",
            "ASEC_MOUNTPOINT", "LOOP_MOUNTPOINT", "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH",
            "SYSTEMSERVERCLASSPATH",
        )

        /**
         * Replaces Termux's login sequence (`login` -> `bash -l` -> `$PREFIX/etc/profile` ->
         * personal login files), which bash cannot run itself here, see [createSession].
         * `$PREFIX/etc/profile` has had its paths rewritten by [BootstrapFixups]; it sources
         * `etc/profile.d/` and `etc/bash.bashrc` (Termux's prompt, history settings,
         * command-not-found handler, bash-completion).
         */
        internal val BASH_RC = """
            |# Generated by Termaterial at every shell session start: edits are overwritten.
            |# Put personal settings in ~/.bashrc or ~/.bash_profile, which are read below.
            |if [ -r "${'$'}PREFIX/etc/profile" ]; then
            |    . "${'$'}PREFIX/etc/profile"
            |fi
            |# Termux's etc/profile sources ~/.bashrc itself, but only in a login shell.
            |if [ -f "${'$'}HOME/.bashrc" ]; then
            |    . "${'$'}HOME/.bashrc"
            |fi
            |if [ -f "${'$'}HOME/.bash_profile" ]; then
            |    . "${'$'}HOME/.bash_profile"
            |elif [ -f "${'$'}HOME/.bash_login" ]; then
            |    . "${'$'}HOME/.bash_login"
            |elif [ -f "${'$'}HOME/.profile" ]; then
            |    . "${'$'}HOME/.profile"
            |fi
            |
        """.trimMargin()

        /** Termux's paths, as every program of the bootstrap and of its packages expects them. */
        const val TERMUX_PREFIX = "${TermuxPathRewriter.TERMUX_DATA_DIR}/files/usr"
        const val TERMUX_HOME = "${TermuxPathRewriter.TERMUX_DATA_DIR}/files/home"

        /** True if this APK ships proot, i.e. if [createSession] can use proot mode. */
        fun isProotAvailable(context: Context): Boolean =
            ProotBinaries.find(TermaterialPaths.nativeLibraryDir(context)) != null

        /**
         * proot's command line: this app's data directory bound at `/data/data/com.termux` (see
         * the class doc) on top of the real root file system, then Termux's own `login` script -
         * which sets up termux-exec and `SHELL` and starts bash as a login shell, reading
         * `$PREFIX/etc/profile` and the user's files as in Termux.
         *
         * `--kill-on-exit`: when the shell exits, its leftover background processes go too, rather
         * than lingering untraced (and thus without any of these paths).
         * `--link2symlink`: Android forbids hard links to apps; proot emulates them.
         */
        internal fun buildProotArguments(binaries: ProotBinaries, realDataDir: File): List<String> = listOf(
            binaries.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-b", "${realDataDir.absolutePath}:${TermuxPathRewriter.TERMUX_DATA_DIR}",
            "-w", TERMUX_HOME,
            "$TERMUX_PREFIX/bin/login",
        )

        /**
         * Environment of proot mode: Termux's own paths (they resolve inside proot) - so no
         * `LD_LIBRARY_PATH`/`APT_CONFIG` workaround - plus what proot itself needs to know about
         * where it was installed, since Termux built it to run from its own prefix.
         */
        internal fun buildProotEnvironment(
            binaries: ProotBinaries,
            realPrefixDir: File,
            systemEnv: Map<String, String> = emptyMap(),
            extra: Map<String, String> = emptyMap(),
        ): List<String> {
            val env = linkedMapOf<String, String>()
            for (key in ANDROID_ENV_PASSTHROUGH) {
                systemEnv[key]?.let { env[key] = it }
            }
            env += linkedMapOf(
                "HOME" to TERMUX_HOME,
                "PREFIX" to TERMUX_PREFIX,
                "PATH" to "$TERMUX_PREFIX/bin",
                "TMPDIR" to "$TERMUX_PREFIX/tmp",
                "LANG" to "en_US.UTF-8",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                // Exported by the Termux app since v0.119; its login script and
                // termux-setup-package-manager fall back to guesswork without it.
                "TERMUX_APP_PACKAGE_MANAGER" to "apt",
                // No Termux welcome banner: it points at Termux's own support channels.
                "TERMUX_HUSHLOGIN" to "1",
                // Termux's proot looks for these under its own $PREFIX by default, which only
                // exists inside the guest it has not set up yet - so real, outside paths.
                "PROOT_LOADER" to binaries.loader.absolutePath,
                "PROOT_TMP_DIR" to "${realPrefixDir.absolutePath}/tmp",
            )
            binaries.loader32?.let { env["PROOT_LOADER_32"] = it.absolutePath }
            env += extra
            return env.map { (key, value) -> "$key=$value" }
        }

        internal fun runtimeDirectories(realPrefixDir: File, aptCacheDir: File): List<File> = listOf(
            File(realPrefixDir, "tmp"),
            File(realPrefixDir, "var/lib/apt/lists/partial"),
            File(realPrefixDir, "var/log/apt"),
            File(aptCacheDir, "archives/partial"),
        )

        /** Pure, unit-testable environment builder for the bootstrap's bash. */
        internal fun buildShellEnvironment(
            realPrefixDir: File,
            realHomeDir: File,
            systemEnv: Map<String, String> = emptyMap(),
            extra: Map<String, String> = emptyMap(),
        ): List<String> {
            val prefix = realPrefixDir.absolutePath
            val env = linkedMapOf<String, String>()
            for (key in ANDROID_ENV_PASSTHROUGH) {
                systemEnv[key]?.let { env[key] = it }
            }
            env += linkedMapOf(
                "HOME" to realHomeDir.absolutePath,
                "PREFIX" to prefix,
                "PATH" to "$prefix/bin",
                // See the class doc: this replaces the bootstrap binaries' hardcoded, nonexistent
                // /data/data/com.termux/files/usr/lib DT_RUNPATH.
                "LD_LIBRARY_PATH" to "$prefix/lib",
                "LANG" to "en_US.UTF-8",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "TMPDIR" to "$prefix/tmp",
                "SHELL" to "$prefix/bin/bash",
                // See buildAptConfigOverride: redirects apt's compiled-in com.termux layout.
                "APT_CONFIG" to aptConfigFile(realPrefixDir).absolutePath,
                // dpkg has the same hardcoded admindir problem as apt, but for direct `dpkg ...`
                // invocations (not routed through apt, which already gets Dir::State::status from
                // the override above) - DPKG_ADMINDIR is dpkg's own env var equivalent of
                // --admindir.
                "DPKG_ADMINDIR" to "$prefix/var/lib/dpkg",
                // The CA bundle path compiled into curl/OpenSSL-based tools has the same problem
                // as apt's (see Acquire::https::CaInfo below); both variables are standard
                // overrides for it.
                "SSL_CERT_FILE" to "$prefix/etc/tls/cert.pem",
                "CURL_CA_BUNDLE" to "$prefix/etc/tls/cert.pem",
            )
            env += extra
            return env.map { (key, value) -> "$key=$value" }
        }

        /** Where [buildAptConfigOverride]'s content is written; kept outside `usr/` so it is not
         *  wiped by a bootstrap reinstall. Derived from [realPrefixDir] alone (not [Context])
         *  purely so it stays trivial to unit test alongside [buildAptConfigOverride]. */
        internal fun aptConfigFile(realPrefixDir: File): File =
            File(realPrefixDir.parentFile, "termaterial-apt.conf")

        /** Where [BASH_RC] is written, next to [aptConfigFile]. */
        internal fun bashRcFile(realPrefixDir: File): File =
            File(realPrefixDir.parentFile, "termaterial-bashrc")

        /**
         * apt (like bash's `/etc/profile`, see [createSession]) has `/data/data/com.termux` baked
         * in at build time for its whole layout - `Dir::Etc`, `Dir::State::status`,
         * `Dir::Bin::methods`, ..., all in a directory that belongs to a different app and is
         * never reachable here (confirmed on a real device: "Unable to read
         * .../etc/apt/apt.conf.d/ - Permission denied", then "Unable to determine a suitable
         * packaging system type").
         *
         * This is the standard technique chroot tooling (schroot, sbuild, mmdebstrap) uses to
         * repoint apt at a different root at runtime: a config file, referenced via the
         * `APT_CONFIG` environment variable (read before apt resolves its own default config
         * location, unlike a file placed inside the - wrong - default `Dir::Etc`), that sets the
         * top-level `Dir` and every key apt does not resolve relative to it. The list below is
         * every com.termux path found in the bootstrap's `libapt-pkg.so` that apt reads from its
         * configuration:
         * - `Dir::Cache`: Termux keeps it outside the prefix, in the app's cache directory;
         * - `Dir::Bin::apt-key`: run to verify every repository signature;
         * - `Dir::Bin::<compressor>`: run to decompress package indexes;
         * - `DPkg::Path`: the `PATH` apt gives dpkg, which refuses to run without `sh` & co in it;
         * - `Acquire::https::CaInfo`: the CA bundle of apt's https method ("No system
         *   certificates available", confirmed on a real device before this was set).
         */
        internal fun buildAptConfigOverride(realPrefixDir: File, aptCacheDir: File): String {
            val prefix = realPrefixDir.absolutePath
            return """
                |// Generated by Termaterial at every shell session start - see
                |// BootstrapShellSessionFactory.buildAptConfigOverride and
                |// docs/step-2-shell-backend.md. Redirects apt away from the bootstrap's
                |// compiled-in /data/data/com.termux paths.
                |Dir "$prefix/";
                |Dir::State "var/lib/apt/";
                |Dir::State::status "var/lib/dpkg/status";
                |Dir::Cache "${aptCacheDir.absolutePath}/";
                |Dir::Etc "etc/apt/";
                |Dir::Etc::sourcelist "sources.list";
                |Dir::Etc::sourceparts "sources.list.d";
                |Dir::Etc::preferences "preferences";
                |Dir::Etc::preferencesparts "preferences.d";
                |Dir::Etc::trusted "trusted.gpg";
                |Dir::Etc::trustedparts "trusted.gpg.d";
                |Dir::Log "var/log/apt";
                |Dir::Bin::methods "$prefix/lib/apt/methods";
                |Dir::Bin::planners "$prefix/lib/apt/planners";
                |Dir::Bin::solvers "$prefix/lib/apt/solvers";
                |Dir::Bin::dpkg "$prefix/bin/dpkg";
                |Dir::Bin::apt-key "$prefix/bin/apt-key";
                |Dir::Bin::gzip "$prefix/bin/gzip";
                |Dir::Bin::bzip2 "$prefix/bin/bzip2";
                |Dir::Bin::xz "$prefix/bin/xz";
                |Dir::Bin::lzma "$prefix/bin/lzma";
                |Dir::Bin::lz4 "$prefix/bin/lz4";
                |Dir::Bin::zstd "$prefix/bin/zstd";
                |DPkg::Path "$prefix/bin";
                |Acquire::https::CaInfo "$prefix/etc/tls/cert.pem";
                |
            """.trimMargin()
        }
    }
}
